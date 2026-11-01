package org.umless.update

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.umless.api.ApiException
import org.umless.api.UmlessApi
import org.umless.core.Jobs
import org.umless.core.TestEnv
import org.umless.core.UmlessConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** 应用更新：版本号、清单解析与安装包选择、比较 / 跳过、缓存、下载续传与校验、签名核对、安装与清理 */
class UpdaterTest {

    /* ---------------------------------------------------------------- 假的网络与系统 */

    private class FakeHttp : UpdateHttp {
        val texts = mutableMapOf<String, String>()
        val files = mutableMapOf<String, ByteArray>()
        val failing = mutableSetOf<String>()
        val noRange = mutableSetOf<String>()
        val requests = mutableListOf<Pair<String, Long>>()

        override fun getText(url: String, timeoutMs: Int): String {
            requests.add(url to 0L)
            if (url in failing) throw IOException("connection reset")
            return texts[url] ?: throw HttpStatusException(404)
        }

        override fun open(url: String, offset: Long, connectTimeoutMs: Int, readTimeoutMs: Int): UpdateHttp.Body {
            requests.add(url to offset)
            if (url in failing) throw IOException("connection reset")
            val data = files[url] ?: throw HttpStatusException(404)
            if (offset > 0 && url !in noRange) {
                if (offset >= data.size) throw HttpStatusException(416)
                val rest = data.size - offset.toInt()
                return UpdateHttp.Body(206, rest.toLong(), ByteArrayInputStream(data, offset.toInt(), rest))
            }
            return UpdateHttp.Body(200, data.size.toLong(), ByteArrayInputStream(data))
        }
    }

    private class FakePlatform(override val updatesDir: File) : UpdatePlatform {
        override val packageName = "org.umless.ime"
        var installedCode = 10022L
        var installedName = "0.1.2"
        var abis = listOf("arm64-v8a", "armeabi-v7a")
        var certs = setOf("aa11")
        var archive: (File) -> ArchiveInfo? = { ArchiveInfo("org.umless.ime", 20002, setOf("aa11")) }
        var canInstall = true
        var settingsOpened = 0
        val installed = mutableListOf<File>()

        override fun installedVersionCode() = installedCode
        override fun installedVersionName() = installedName
        override fun supportedAbis() = abis
        override fun inspectArchive(apk: File) = archive(apk)
        override fun installedCertificates() = certs
        override fun canRequestInstall() = canInstall
        override suspend fun openInstallPermissionSettings() {
            settingsOpened++
        }

        override fun install(apk: File) {
            installed.add(apk)
        }
    }

    private lateinit var http: FakeHttp
    private lateinit var platform: FakePlatform
    private var now = 1_800_000_000_000L

    private val manifestUrl = Updater.DEFAULT_MANIFEST_URL
    private val proxyManifest = "https://gh-proxy.com/$manifestUrl"
    private val apkUrl = "https://github.com/despriber/Umless-Android/releases/download/v0.2.0/Umless-0.2.0-arm64.apk"
    private val proxyApk = "https://gh-proxy.com/$apkUrl"
    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(
        version: String = "0.2.0",
        versionCode: Long? = 2000,
        platform: String = "android-arm64-v8a",
        sha256: String = sha(payload),
    ) = buildJsonObject {
        put("schema", 1)
        put("app", "umless-android")
        put("version", version)
        if (versionCode != null) put("version_code", versionCode)
        put("published_at", "2026-11-05T10:00:00Z")
        putJsonObject("notes") { put("zh", "修复……"); put("en", "Fixed …") }
        put("future_field", "ignored")
        putJsonArray("assets") {
            add(buildJsonObject {
                put("platform", platform)
                put("kind", "apk")
                put("name", "Umless-$version-arm64.apk")
                put("url", apkUrl)
                put("size", payload.size)
                put("sha256", sha256.uppercase())
                put("version_code", (versionCode ?: 0) * 10 + 2)
            })
        }
    }.toString()

    @Before
    fun setUp() {
        TestEnv.setup()
        http = FakeHttp()
        platform = FakePlatform(File(org.umless.core.Umless.home, "cache/updates"))
        now = 1_800_000_000_000L
        Updater.http = http
        Updater.clock = { now }
        Updater.register(platform)
    }

    private fun check(force: Boolean) = runBlocking { Updater.check(force) }
    private fun JsonObject.bool(k: String) = getValue(k).jsonPrimitive.boolean
    private fun JsonObject.str(k: String) = (get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /* ---------------------------------------------------------------- 版本号 */

    @Test
    fun versionCodeMath() {
        assertEquals(1002L, VersionCodes.base("0.1.2"))
        assertEquals(10022L, VersionCodes.full("0.1.2", "arm64-v8a"))
        assertEquals(20002L, VersionCodes.full("0.2.0", "arm64-v8a"))
        assertEquals(20001L, VersionCodes.full("0.2.0", "armeabi-v7a"))
        assertEquals(20003L, VersionCodes.full("0.2.0", "x86"))
        assertEquals(20004L, VersionCodes.full("0.2.0", "x86_64"))
        assertEquals(10_020_032L, VersionCodes.full("1.2.3", "arm64-v8a"))
        assertEquals(1_002_000L, VersionCodes.base("v1.2.0-beta.1"))
        assertNull(VersionCodes.base("1.2"))
        assertNull(VersionCodes.base("0.1000.0"))
        assertNull(VersionCodes.base("abc"))
        assertEquals("arm64-v8a", VersionCodes.abiOf(10022))
        assertEquals("x86_64", VersionCodes.abiOf(20004))
        assertNull(VersionCodes.abiOf(10020))
        // 0.1.0 / 0.1.1 测试包沿用上游的 112：基础号 11，任何 0.1.2 以上都比它大
        assertEquals(11L, VersionCodes.baseOf(112))
        assertTrue(VersionCodes.base("0.1.2")!! > VersionCodes.baseOf(112))
    }

    @Test
    fun abiPreference() {
        // 已安装 arm64 包：即使系统首选 x86_64 也继续用 arm64
        assertEquals(listOf("arm64-v8a", "x86_64"), VersionCodes.preferredAbis(10022, listOf("x86_64", "arm64-v8a")))
        // 已安装包的 ABI 本机不支持（或 ABI 位为 0）时按系统顺序
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), VersionCodes.preferredAbis(10024, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), VersionCodes.preferredAbis(10020, listOf("arm64-v8a", "armeabi-v7a")))
    }

    /* ---------------------------------------------------------------- 清单 */

    @Test
    fun manifestParsing() {
        val m = UpdateManifest.parse(manifest())
        assertEquals("0.2.0", m.version)
        assertEquals(2000L, m.baseVersionCode)
        assertEquals("修复……", m.notesFor("zh"))
        assertEquals("Fixed …", m.notesFor("en"))
        val asset = m.assetFor(listOf("arm64-v8a"))!!
        assertEquals(sha(payload), asset.sha256) // 统一成小写
        assertEquals(20002L, asset.versionCode)
        assertEquals("arm64-v8a", asset.abi)
        assertNull(m.assetFor(listOf("armeabi-v7a", "x86_64")))

        // 缺 version_code 时由 version 计算；更大的 schema 照常按已知字段解析
        val m2 = UpdateManifest.parse(manifest(versionCode = null).replace("\"schema\":1", "\"schema\":2"))
        assertEquals(2, m2.schema)
        assertEquals(2000L, m2.baseVersionCode)

        // 说明只有一种语言时两种界面语言都用它
        val onlyEn = UpdateManifest.parse("""{"version":"0.2.0","notes":{"en":"Only"},"assets":[]}""")
        assertEquals("Only", onlyEn.notesFor("zh"))

        for (bad in listOf("<html>", "[]", """{"schema":1}""", """{"app":"umless-desktop","version":"0.2.0"}""", """{"schema":0,"version":"1.0.0"}""")) {
            try {
                UpdateManifest.parse(bad)
                fail("should reject $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        // 平台不对、kind 不是 apk 的项不选
        val desktop = UpdateManifest.parse(
            """{"version":"0.2.0","assets":[{"platform":"windows-x64","kind":"installer","url":"u"},
               {"platform":"android-arm64-v8a","kind":"portable","url":"u"}]}"""
        )
        assertNull(desktop.assetFor(listOf("arm64-v8a")))
    }

    /* ---------------------------------------------------------------- 检查、比较、跳过、缓存 */

    @Test
    fun checkUsesProxyThenDirect() {
        http.failing.add(proxyManifest)
        http.texts[manifestUrl] = manifest()
        val r = check(force = true)
        assertEquals(listOf(proxyManifest, manifestUrl), http.requests.map { it.first })
        assertEquals(
            setOf("current", "latest", "available", "skipped", "notes", "published_at", "size", "checked_at", "error",
                "can_install", "install_hint", "downloaded"),
            r.keys,
        )
        assertEquals("0.1.2", r.str("current"))
        assertEquals("0.2.0", r.str("latest"))
        assertTrue(r.bool("available"))
        assertFalse(r.bool("skipped"))
        assertEquals("修复……", r.str("notes"))
        assertEquals("2026-11-05T10:00:00Z", r.str("published_at"))
        assertEquals(payload.size.toString(), r.str("size"))
        assertEquals("2027-01-15T08:00:00Z", r.str("checked_at"))
        assertNull(r.str("error"))
        assertTrue(r.bool("can_install"))
        assertEquals(JsonNull, r["install_hint"])
        assertFalse(r.bool("downloaded"))
    }

    @Test
    fun networkErrorsDoNotThrow() {
        http.failing.addAll(listOf(proxyManifest, manifestUrl))
        val r = check(force = true)
        assertFalse(r.bool("available"))
        assertNull(r.str("latest"))
        assertTrue(r.str("error")!!.startsWith("检查更新失败"))
        assertNotNull(r.str("checked_at"))

        // 之前成功过：失败时保留上次的清单，同时报告错误
        http.failing.clear()
        http.texts[manifestUrl] = manifest()
        http.texts[proxyManifest] = "<html>gateway error</html>" // 镜像返回了错误页面，换直连
        assertTrue(check(force = true).bool("available"))
        http.failing.addAll(listOf(proxyManifest, manifestUrl))
        val again = check(force = true)
        assertTrue(again.bool("available"))
        assertNotNull(again.str("error"))
    }

    @Test
    fun compareAndSkip() = runBlocking {
        http.texts[proxyManifest] = manifest()
        // 旧方案的 0.1.1（versionCode 112）也能看到更新
        platform.installedCode = 112
        platform.installedName = "0.1.1"
        assertTrue(Updater.check(true).bool("available"))
        // 已是同一版本 / 更新的版本：没有更新
        platform.installedCode = 20002
        assertFalse(Updater.check(true).bool("available"))
        platform.installedCode = 30002
        assertFalse(Updater.check(true).bool("available"))
        // 只有 ABI 位不同也不算更新
        platform.installedCode = 20001
        platform.abis = listOf("armeabi-v7a", "arm64-v8a")
        assertFalse(Updater.check(true).bool("available"))

        // 本机没有匹配的安装包
        platform.installedCode = 10022
        platform.abis = listOf("x86_64")
        val none = Updater.check(true)
        assertFalse(none.bool("available"))
        assertEquals(JsonNull, none["size"])
        platform.abis = listOf("arm64-v8a")

        // 跳过此版本：available 仍为真（手动检查照常显示），skipped 为真
        assertEquals(JsonNull, UmlessApi.call("skip_update", JsonArray(listOf(JsonPrimitive("0.2.0")))))
        assertEquals("0.2.0", UmlessConfig.updateSkippedVersion)
        val skipped = UmlessApi.call("check_update", JsonArray(listOf(JsonPrimitive(false)))) as JsonObject
        assertTrue(skipped.bool("available"))
        assertTrue(skipped.bool("skipped"))
        // 更新的版本出来后不再算跳过
        http.texts[proxyManifest] = manifest(version = "0.3.0", versionCode = 3000)
        assertFalse(Updater.check(true).bool("skipped"))
        UmlessApi.call("skip_update", JsonArray(listOf(JsonNull)))
        assertEquals("", UmlessConfig.updateSkippedVersion)
    }

    @Test
    fun cacheBehaviour() {
        http.texts[proxyManifest] = manifest()
        assertTrue(check(force = false).bool("available")) // 从未检查过：自动检查开启时请求
        assertEquals(1, http.requests.size)

        now += Updater.CHECK_INTERVAL_MS - 1000
        assertTrue(check(force = false).bool("available")) // 24 小时内用缓存
        runBlocking { Updater.autoCheckIfDue() }
        assertEquals(1, http.requests.size)

        check(force = true) // 手动检查总是请求
        assertEquals(2, http.requests.size)

        now += Updater.CHECK_INTERVAL_MS + 1
        runBlocking { Updater.autoCheckIfDue() }
        assertEquals(3, http.requests.size)

        // 关闭自动检查：过期也不联网，只返回缓存；手动检查照常
        UmlessConfig.savePatch(buildJsonObject { putJsonObject("update") { put("auto_check", false) } })
        now += Updater.CHECK_INTERVAL_MS + 1
        assertTrue(check(force = false).bool("available"))
        runBlocking { Updater.autoCheckIfDue() }
        assertEquals(3, http.requests.size)
        check(force = true)
        assertEquals(4, http.requests.size)

        // 清单地址可在配置里覆盖（非 GitHub 地址不走镜像）
        UmlessConfig.savePatch(buildJsonObject { putJsonObject("update") { put("manifest_url", "https://mirror.example/update.json") } })
        http.texts["https://mirror.example/update.json"] = manifest(version = "0.4.0", versionCode = 4000)
        assertEquals("0.4.0", check(force = true).str("latest"))
        assertEquals("https://mirror.example/update.json", http.requests.last().first)
    }

    @Test
    fun configDefaultsAndValidation() {
        assertTrue(UmlessConfig.updateAutoCheck)
        assertEquals("", UmlessConfig.updateSkippedVersion)
        assertEquals("", UmlessConfig.updateManifestUrl)
        for (bad in listOf(
            buildJsonObject { put("update", "x") },
            buildJsonObject { putJsonObject("update") { put("auto_check", "yes") } },
            buildJsonObject { putJsonObject("update") { put("skipped_version", 1) } },
            buildJsonObject { putJsonObject("update") { put("manifest_url", "ftp://x") } },
        )) {
            try {
                UmlessConfig.savePatch(bad)
                fail("should reject $bad")
            } catch (_: ApiException) {
            }
        }
    }

    /* ---------------------------------------------------------------- 下载 */

    private fun pendingTarget(): Pair<UpdateManifest, UpdateAsset> {
        check(force = true)
        return Updater.pending(platform, Updater.loadCache())!!
    }

    private fun download(): List<Pair<Float?, String?>> {
        val (m, a) = pendingTarget()
        val events = mutableListOf<Pair<Float?, String?>>()
        runBlocking { Updater.download(platform, m, a) { p, msg -> events.add(p to msg) } }
        return events
    }

    private val apkFile get() = File(platform.updatesDir, "umless-20002.apk")
    private val partFile get() = File(platform.updatesDir, "umless-20002.apk.part")

    @Test
    fun downloadVerifiesAndCaches() {
        http.texts[proxyManifest] = manifest()
        http.files[proxyApk] = payload
        val events = download()
        assertTrue(apkFile.isFile)
        assertFalse(partFile.exists())
        assertEquals(sha(payload), Updater.sha256(apkFile))
        assertEquals(1f, events.last().first)
        assertTrue(events.any { it.second == "正在从 gh-proxy.com 下载…" })
        assertTrue(check(force = false).bool("downloaded"))

        // 再下载一次：文件已在且校验通过，不再请求
        val before = http.requests.size
        download()
        assertEquals(before + 1, http.requests.size) // 只有 pendingTarget 里的那次检查
    }

    @Test
    fun checksumMismatchDeletesAndFails() {
        http.texts[proxyManifest] = manifest()
        val corrupt = payload.copyOf().also { it[10] = (it[10] + 1).toByte() }
        http.files[proxyApk] = corrupt
        http.files[apkUrl] = corrupt
        try {
            download()
            fail("checksum mismatch should fail")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("sha256"))
        }
        assertFalse(apkFile.exists())
        assertFalse(partFile.exists())
        assertFalse(check(force = false).bool("downloaded"))

        // 镜像给的文件坏了，直连的是好的：换源成功
        http.files[apkUrl] = payload
        download()
        assertTrue(apkFile.isFile)
    }

    @Test
    fun resumesPartialDownload() {
        http.texts[proxyManifest] = manifest()
        http.files[proxyApk] = payload
        platform.updatesDir.mkdirs()
        partFile.writeBytes(payload.copyOf(100_000))
        download()
        assertEquals(proxyApk to 100_000L, http.requests.last())
        assertEquals(sha(payload), Updater.sha256(apkFile))
    }

    @Test
    fun restartsWhenServerIgnoresRange() {
        http.texts[proxyManifest] = manifest()
        http.files[proxyApk] = payload
        http.noRange.add(proxyApk)
        platform.updatesDir.mkdirs()
        partFile.writeBytes(ByteArray(50_000) { 7 }) // 内容不对的残留：服务器返回整个文件，从头写
        download()
        assertEquals(sha(payload), Updater.sha256(apkFile))
    }

    @Test
    fun failedSourceKeepsPartForNextSource() {
        http.texts[proxyManifest] = manifest()
        http.failing.add(proxyApk)
        http.files[apkUrl] = payload
        platform.updatesDir.mkdirs()
        partFile.writeBytes(payload.copyOf(1234))
        File(platform.updatesDir, "umless-10032.apk").writeText("old") // 其他版本的残留会被清掉
        download()
        assertEquals(apkUrl to 1234L, http.requests.last())
        assertTrue(apkFile.isFile)
        assertFalse(File(platform.updatesDir, "umless-10032.apk").exists())
    }

    @Test
    fun rejectsWrongSignaturePackageOrVersion() {
        http.texts[proxyManifest] = manifest()
        http.files[proxyApk] = payload
        val cases = listOf(
            ArchiveInfo("org.umless.ime", 20002, setOf("bb22")) to "签名",
            ArchiveInfo("org.umless.ime", 20002, emptySet()) to "签名",
            ArchiveInfo("com.evil.app", 20002, setOf("aa11")) to "包名",
            ArchiveInfo("org.umless.ime", 10022, setOf("aa11")) to "不比当前版本新",
        )
        for ((info, msg) in cases) {
            platform.archive = { info }
            try {
                download()
                fail("should reject $msg")
            } catch (e: ApiException) {
                assertTrue("${e.message} / $msg", e.message!!.contains(msg))
            }
            assertFalse(apkFile.exists())
            assertFalse(partFile.exists())
        }
        platform.archive = { null }
        try {
            download()
            fail("unreadable package should be rejected")
        } catch (e: ApiException) {
            assertEquals("无法读取安装包", e.message)
        }
    }

    @Test
    fun downloadJobThroughApi() = runBlocking {
        http.texts[proxyManifest] = manifest()
        http.files[proxyApk] = payload
        val job = UmlessApi.call("download_update", JsonArray(emptyList())) as JsonObject
        val id = job.str("job_id")!!
        var status: JsonObject
        do {
            delay(20)
            status = Jobs.status(id)
        } while (status.str("state") == "running")
        assertEquals("done", status.str("state"))
        assertEquals(buildJsonObject { put("downloaded", true) }, status["result"])
        assertTrue(apkFile.isFile)

        // 没有新版本时报错
        platform.installedCode = 20002
        try {
            UmlessApi.call("download_update", JsonArray(emptyList()))
            fail("no update available")
        } catch (e: ApiException) {
            assertEquals("没有可下载的新版本", e.message)
        }
    }

    /* ---------------------------------------------------------------- 安装与清理 */

    @Test
    fun installFlow() = runBlocking {
        try {
            Updater.install()
            fail("nothing downloaded")
        } catch (e: ApiException) {
            assertEquals("请先下载更新", e.message)
        }
        http.texts[proxyManifest] = manifest()
        http.files[proxyApk] = payload
        download()

        platform.canInstall = false
        assertEquals("needs_permission", (UmlessApi.call("install_update", JsonArray(emptyList())) as JsonObject).str("status"))
        assertEquals(1, platform.settingsOpened)
        assertTrue(platform.installed.isEmpty())

        platform.canInstall = true
        assertEquals("installing", Updater.install().str("status"))
        assertEquals(listOf(apkFile), platform.installed)

        // 下载后文件被改动：安装前再次校验，拒绝并删除
        apkFile.appendBytes(byteArrayOf(1))
        try {
            Updater.install()
            fail("tampered file")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("sha256"))
        }
        assertFalse(apkFile.exists())
        assertEquals(1, platform.installed.size)
    }

    @Test
    fun cleanupAfterSuccessfulUpdate() {
        http.texts[proxyManifest] = manifest()
        http.files[proxyApk] = payload
        download()
        File(platform.updatesDir, "junk.tmp").writeText("x")

        // 还没装上：安装包保留
        Updater.cleanupAfterUpdate()
        assertTrue(apkFile.isFile)
        assertFalse(File(platform.updatesDir, "junk.tmp").exists())
        assertNotNull(Updater.loadCache().downloaded)

        // 更新成功（已安装版本 ≥ 安装包）：删除并清掉记录
        platform.installedCode = 20002
        Updater.cleanupAfterUpdate()
        assertFalse(apkFile.exists())
        assertNull(Updater.loadCache().downloaded)
        assertFalse(check(force = false).bool("available"))
    }

    @Test
    fun cacheRoundTrip() {
        val c = Updater.Cache(
            checkedMs = 5, checkedAt = "2026-01-01T00:00:00Z", error = "x",
            manifest = buildJsonObject { put("version", "1.0.0") },
            downloaded = Updater.Downloaded("1.0.0", 10_000_002, "ab", "umless-10000002.apk"),
        )
        assertEquals(c, Updater.Cache.fromJson(c.toJson()))
        assertEquals(Updater.Cache(), Updater.Cache.fromJson(null))
        assertEquals(Updater.Cache(), Updater.Cache.fromJson(buildJsonObject { put("downloaded", buildJsonArray { }) }))
    }
}
