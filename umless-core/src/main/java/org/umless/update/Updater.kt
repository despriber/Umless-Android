package org.umless.update

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.umless.api.ApiException
import org.umless.api.UmlessApi
import org.umless.core.DownloadSources
import org.umless.core.I18n.t
import org.umless.core.Jobs
import org.umless.core.Py
import org.umless.core.Umless
import org.umless.core.UmlessConfig
import org.umless.core.UmlessLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 读出的安装包信息；[certificates] 是签名证书的 SHA-256（小写十六进制） */
class ArchiveInfo(val packageName: String, val versionCode: Long, val certificates: Set<String>)

/**
 * 更新里与 Android 系统打交道的部分（实现在主 App 模块的 org.umless.update.AndroidUpdatePlatform）。
 * 单元测试换成假的实现。
 */
interface UpdatePlatform {
    /** 本应用的包名 */
    val packageName: String

    /** 已安装的完整 versionCode（PackageInfo.longVersionCode） */
    fun installedVersionCode(): Long

    fun installedVersionName(): String

    /** Build.SUPPORTED_ABIS，按系统偏好排序 */
    fun supportedAbis(): List<String>

    /** 安装包下载目录：cacheDir/updates */
    val updatesDir: File

    /** 读取安装包的包名、versionCode 与签名证书；读不出来时返回 null */
    fun inspectArchive(apk: File): ArchiveInfo?

    /** 已安装应用的签名证书 SHA-256 */
    fun installedCertificates(): Set<String>

    /** 是否已允许「安装未知应用」 */
    fun canRequestInstall(): Boolean

    /** 打开本应用的「安装未知应用」设置页 */
    suspend fun openInstallPermissionSettings()

    /** 通过系统 PackageInstaller 提交安装（结果异步回调）；失败抛出异常 */
    fun install(apk: File)
}

/**
 * 应用更新（Umless 主仓库 spec/update.md）：读取 GitHub Releases 上的 update.json，比较版本，
 * 下载安装包（下载源见 DownloadSources，可续传、sha256 校验），核对包名 / 版本 / 签名后交给系统安装。
 *
 * 状态缓存在本地数据库 kv 的 "update" 里（上次检查时间、清单原文、错误、已下载的安装包），
 * 「跳过此版本」与自动检查开关在配置 update.* 里，都与网页界面共用。
 * 接口：check_update(force)、download_update()、install_update()、skip_update(version)，由 [register] 注册。
 */
object Updater {
    /** GitHub 仓库；改名时只改这里 */
    const val REPO = "despriber/Umless-Android"
    const val DEFAULT_MANIFEST_URL = "https://github.com/$REPO/releases/latest/download/update.json"

    /** 自动检查的间隔 */
    const val CHECK_INTERVAL_MS = 24L * 3600 * 1000

    /** 下载任务的 Jobs key：重复点击不会启动第二个下载 */
    const val JOB_KEY = "update:download"

    const val MANIFEST_TIMEOUT_MS = 10_000
    const val CONNECT_TIMEOUT_MS = 20_000
    const val READ_TIMEOUT_MS = 60_000

    private const val TAG = "Update"
    private const val KV_KEY = "update"

    /** 下载目录里的文件名：umless-<完整 versionCode>.apk（下载中为 .part），清理时据此判断是否已过时 */
    private val APK_NAME = Regex("""^umless-(\d+)\.apk(\.part)?$""")

    @Volatile
    var http: UpdateHttp = UrlConnectionUpdateHttp

    /** 当前时间（毫秒），测试可替换 */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var platform: UpdatePlatform? = null

    /** 检查串行执行：打开 App 时的自动检查与网页的 check_update 同时到来时只请求一次 */
    private val checkLock = Mutex()

    /** 下载、安装、清理互斥（都动 updatesDir 里的文件） */
    private val fileLock = Mutex()

    /** kv 读改写 */
    private val kvLock = Any()

    /** 注册接口；必须在 Umless.init 之后调用（重复调用只替换平台实现） */
    fun register(platform: UpdatePlatform) {
        this.platform = platform
        UmlessApi.register("check_update") { a -> check(Py.truthy(a.getOrNull(0))) }
        UmlessApi.register("download_update") { startDownload() }
        UmlessApi.register("install_update") { install() }
        UmlessApi.register("skip_update") { a ->
            skip((a.getOrNull(0) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull)
            JsonNull
        }
    }

    private fun requirePlatform(): UpdatePlatform = platform ?: throw IllegalStateException("Updater.register() has not been called")

    /* ---------------------------------------------------------------- 缓存 */

    /** 已下载并校验通过的安装包 */
    internal data class Downloaded(val version: String, val versionCode: Long, val sha256: String, val file: String)

    internal data class Cache(
        val checkedMs: Long = 0,
        val checkedAt: String? = null,
        val error: String? = null,
        val manifest: JsonObject? = null,
        val downloaded: Downloaded? = null,
        /** 上次检查时所有源都对清单返回 404（还没有发布任何正式版） */
        val notPublished: Boolean = false,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("checked_ms", checkedMs)
            put("checked_at", checkedAt)
            put("error", error)
            put("not_published", notPublished)
            put("manifest", manifest ?: JsonNull)
            put("downloaded", downloaded?.let {
                buildJsonObject {
                    put("version", it.version)
                    put("version_code", it.versionCode)
                    put("sha256", it.sha256)
                    put("file", it.file)
                }
            } ?: JsonNull)
        }

        companion object {
            private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

            fun fromJson(o: JsonObject?): Cache {
                if (o == null) return Cache()
                val d = o["downloaded"] as? JsonObject
                val downloaded = d?.let {
                    val code = (it["version_code"] as? JsonPrimitive)?.longOrNull
                    val sha = str(it["sha256"])
                    val file = str(it["file"])
                    if (code != null && sha != null && file != null) Downloaded(str(it["version"]) ?: "", code, sha, file) else null
                }
                return Cache(
                    checkedMs = (o["checked_ms"] as? JsonPrimitive)?.longOrNull ?: 0,
                    checkedAt = str(o["checked_at"]),
                    error = str(o["error"]),
                    manifest = o["manifest"] as? JsonObject,
                    downloaded = downloaded,
                    notPublished = (o["not_published"] as? JsonPrimitive)?.content == "true",
                )
            }
        }
    }

    internal fun loadCache(): Cache = synchronized(kvLock) { Cache.fromJson(Umless.db.getKv(KV_KEY) as? JsonObject) }

    private fun updateCache(change: (Cache) -> Cache): Cache = synchronized(kvLock) {
        val old = loadCache()
        val new = change(old)
        if (new != old) Umless.db.setKv(KV_KEY, new.toJson())
        new
    }

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

    /* ---------------------------------------------------------------- 检查 */

    /** 清单的候选地址（按配置的下载源排序；自定义的非 GitHub 地址只直连） */
    internal fun manifestUrls(): List<DownloadSources.Candidate> =
        DownloadSources.candidates(UmlessConfig.updateManifestUrl.ifEmpty { DEFAULT_MANIFEST_URL })

    /**
     * check_update(force)：force 为真时总是请求清单；否则距上次检查不到 24 小时用缓存，
     * 超过 24 小时且开启了自动检查才请求（关闭自动检查时不联网，只返回缓存）。网络错误不抛出，放在 error 里
     */
    suspend fun check(force: Boolean): JsonObject {
        val p = requirePlatform()
        checkLock.withLock {
            val cache = loadCache()
            val age = clock() - cache.checkedMs
            val due = cache.checkedMs <= 0 || age !in 0 until CHECK_INTERVAL_MS
            if (force || (due && UmlessConfig.updateAutoCheck)) fetchManifest()
        }
        return status(p, loadCache())
    }

    /** 打开主 App 时调用：开启自动检查且距上次超过 24 小时才检查；失败只记日志 */
    suspend fun autoCheckIfDue() {
        if (platform == null || !UmlessConfig.updateAutoCheck) return
        check(force = false)
    }

    private fun fetchManifest() {
        var manifest: UpdateManifest? = null
        var lastError: String? = null
        var allNotFound = true // 每个源都返回 404：还没有发布过正式版，不算错误
        for ((source, url) in manifestUrls()) {
            try {
                manifest = UpdateManifest.parse(http.getText(url, MANIFEST_TIMEOUT_MS))
                DownloadSources.succeeded(source)
                break
            } catch (e: IllegalArgumentException) {
                UmlessLog.w(TAG, "更新清单格式不正确：$url（${e.message}）")
                lastError = t("update.bad_manifest")
                allNotFound = false
            } catch (e: Exception) {
                UmlessLog.w(TAG, "获取更新清单失败：$url（${e.message ?: e.javaClass.simpleName}）")
                lastError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                if ((e as? HttpStatusException)?.status != 404) allNotFound = false
            }
        }
        val now = clock()
        updateCache { c ->
            if (manifest != null) c.copy(checkedMs = now, checkedAt = iso(now), error = null, manifest = manifest.raw, notPublished = false)
            else if (allNotFound) c.copy(checkedMs = now, checkedAt = iso(now), error = null, notPublished = true)
            // 失败时保留上次成功取到的清单，界面仍可显示已知的新版本
            else c.copy(checkedMs = now, checkedAt = iso(now), error = t("update.check_failed", "error" to lastError), notPublished = false)
        }
        if (manifest != null) UmlessLog.i(TAG, "检查更新：最新 ${manifest.version}（${manifest.baseVersionCode}），已安装 ${requirePlatform().installedVersionCode()}")
    }

    /** 当前有可安装的新版本时返回清单与适合本机的安装包 */
    internal fun pending(p: UpdatePlatform, cache: Cache): Pair<UpdateManifest, UpdateAsset>? {
        val manifest = cache.manifest?.let { runCatching { UpdateManifest.fromJson(it) }.getOrNull() } ?: return null
        val installed = p.installedVersionCode()
        val asset = manifest.assetFor(VersionCodes.preferredAbis(installed, p.supportedAbis())) ?: return null
        val latest = manifest.baseVersionCode ?: return null
        return if (latest > VersionCodes.baseOf(installed)) manifest to asset else null
    }

    /** 已下载且与 [asset] 一致的安装包文件 */
    private fun downloadedFile(p: UpdatePlatform, cache: Cache, asset: UpdateAsset): File? {
        val d = cache.downloaded ?: return null
        if (asset.sha256 == null || d.sha256 != asset.sha256) return null
        return File(p.updatesDir, d.file).takeIf { it.isFile }
    }

    /** check_update 的返回值（docs/ui-api.md「更新」） */
    internal fun status(p: UpdatePlatform, cache: Cache): JsonObject {
        val manifest = cache.manifest?.let { runCatching { UpdateManifest.fromJson(it) }.getOrNull() }
        val installed = p.installedVersionCode()
        val asset = manifest?.assetFor(VersionCodes.preferredAbis(installed, p.supportedAbis()))
        val target = pending(p, cache)
        val skipped = UmlessConfig.updateSkippedVersion
        return buildJsonObject {
            put("current", p.installedVersionName())
            put("latest", manifest?.version)
            put("available", target != null)
            put("skipped", manifest != null && skipped.isNotEmpty() && manifest.version == skipped)
            put("notes", manifest?.notesFor(Umless.language()) ?: "")
            put("published_at", manifest?.publishedAt)
            put("size", asset?.size)
            put("checked_at", cache.checkedAt)
            put("error", cache.error)
            put("not_published", cache.notPublished)
            put("can_install", true)
            put("install_hint", JsonNull)
            put("downloaded", target != null && downloadedFile(p, cache, target.second) != null)
        }
    }

    /** skip_update(version)：自动检查不再提示该版本；空值清除 */
    fun skip(version: String?) {
        UmlessConfig.savePatch(buildJsonObject {
            putJsonObject("update") { put("skipped_version", version?.trim().orEmpty()) }
        })
    }

    /* ---------------------------------------------------------------- 下载 */

    /** download_update()：后台下载，返回 {"job_id"}；任务结果 {"downloaded": true} */
    suspend fun startDownload(): JsonObject {
        val p = requirePlatform()
        val target = pending(p, loadCache()) ?: run {
            check(force = true)
            pending(p, loadCache())
        } ?: throw ApiException(t("update.none"))
        val (manifest, asset) = target
        return Jobs.start(message = t("update.preparing"), key = JOB_KEY) { progress ->
            download(p, manifest, asset, progress)
            buildJsonObject { put("downloaded", true) }
        }
    }

    /** 下载并校验；成功后安装包在 updatesDir/umless-<versionCode>.apk，并记入缓存。失败抛出异常（message 已翻译） */
    internal suspend fun download(p: UpdatePlatform, manifest: UpdateManifest, asset: UpdateAsset, progress: (Float?, String?) -> Unit) {
        val sha = asset.sha256 ?: throw IOException(t("update.bad_manifest"))
        val code = asset.versionCode
            ?: manifest.baseVersionCode?.let { it * 10 + (VersionCodes.ABI_IDS[asset.abi] ?: 0) }
            ?: throw IOException(t("update.bad_manifest"))
        fileLock.withLock {
            val dir = p.updatesDir.apply { mkdirs() }
            val name = "umless-$code.apk"
            val apk = File(dir, name)
            val part = File(dir, "$name.part")

            // 之前已下载过：校验通过就直接用
            if (apk.isFile) {
                if (sha256(apk) == sha) {
                    verifyOrDelete(p, apk)
                    recordDownloaded(manifest, code, sha, name)
                    progress(1f, null)
                    return
                }
                apk.delete()
            }
            // 其他版本留下的文件（含未下载完的）
            dir.listFiles()?.forEach { if (it.name != name && it.name != part.name) it.deleteRecursively() }

            // 下载 + 安装时系统再复制一份
            val size = asset.size
            if (size != null && dir.usableSpace in 1 until size * 2 - part.length()) {
                throw IOException(t("update.no_space", "size" to ((size * 2) shr 20)))
            }

            var lastError: String? = null
            for ((source, url) in DownloadSources.candidates(asset.url)) {
                progress(fraction(part.length(), size), t("update.downloading_from", "host" to hostOf(url)))
                try {
                    fetchTo(url, part, size, progress)
                } catch (e: IOException) {
                    UmlessLog.w(TAG, "下载更新失败：$url（${e.message ?: e.javaClass.simpleName}）")
                    lastError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    continue // 保留 .part，下一个源从断点续传
                }
                progress(0.96f, t("update.verifying"))
                val actual = sha256(part)
                if (actual != sha) {
                    UmlessLog.w(TAG, "安装包校验失败：$url sha256=$actual，期望 $sha")
                    part.delete()
                    lastError = t("update.checksum_failed")
                    continue
                }
                verifyOrDelete(p, part)
                if (!part.renameTo(apk)) {
                    part.delete()
                    throw IOException(t("update.download_failed", "error" to "rename failed"))
                }
                DownloadSources.succeeded(source)
                recordDownloaded(manifest, code, sha, name)
                UmlessLog.i(TAG, "更新已下载：${manifest.version}（$code）")
                progress(1f, null)
                return
            }
            throw IOException(
                if (lastError == t("update.checksum_failed")) lastError
                else t("update.download_failed", "error" to lastError)
            )
        }
    }

    private fun fraction(done: Long, total: Long?): Float? =
        if (total != null && total > 0) (done.toFloat() / total * 0.95f).coerceIn(0f, 0.95f) else null

    private fun hostOf(url: String): String = try {
        URL(url).host
    } catch (_: Exception) {
        url
    }

    /**
     * 下载到 [part]：已有部分内容时带 Range 续传；服务器不支持续传（返回 200）时从头写。
     * [expected] 为清单里的大小，已下完时不再请求
     */
    private fun fetchTo(url: String, part: File, expected: Long?, progress: (Float?, String?) -> Unit) {
        var offset = if (part.isFile) part.length() else 0L
        if (expected != null && offset > expected) {
            part.delete()
            offset = 0
        }
        if (expected != null && offset == expected && offset > 0) return
        val body = try {
            http.open(url, offset, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        } catch (e: HttpStatusException) {
            // 416：断点超出文件（多半是旧的残留），从头下载
            if (e.status != 416 || offset == 0L) throw e
            part.delete()
            offset = 0
            http.open(url, 0, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        }
        body.use {
            val append = offset > 0 && body.partial
            val start = if (append) offset else 0L
            val total = expected ?: body.length.takeIf { it >= 0 }?.let { start + it }
            var done = start
            var written = 0L
            var lastReport = 0L
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = body.stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    written += n
                    if (done - lastReport >= (512 shl 10)) {
                        lastReport = done
                        progress(
                            fraction(done, total),
                            t("update.progress", "done" to (done shr 20), "total" to ((total ?: done) shr 20)),
                        )
                    }
                }
                out.fd.sync()
            }
            if (body.length >= 0 && written != body.length) throw IOException("incomplete download: $written / ${body.length}")
            if (expected != null && done < expected) throw IOException("incomplete download: $done / $expected")
        }
    }

    private fun recordDownloaded(manifest: UpdateManifest, code: Long, sha: String, name: String) {
        updateCache { it.copy(downloaded = Downloaded(manifest.version, code, sha, name)) }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 安装包必须是本应用（包名一致）、比已安装的新，且签名证书与已安装的完全一致；
     * 否则抛出 ApiException（不一致说明安装包被替换或不是官方版本）
     */
    internal fun verifyArchive(p: UpdatePlatform, apk: File) {
        val info = p.inspectArchive(apk) ?: throw ApiException(t("update.bad_apk"))
        if (info.packageName != p.packageName) {
            UmlessLog.w(TAG, "安装包包名不符：${info.packageName}")
            throw ApiException(t("update.wrong_package"))
        }
        val installed = p.installedVersionCode()
        if (info.versionCode <= installed) {
            UmlessLog.w(TAG, "安装包版本 ${info.versionCode} 不比已安装的 $installed 新")
            throw ApiException(t("update.not_newer"))
        }
        val mine = p.installedCertificates()
        if (mine.isEmpty() || info.certificates.isEmpty() || info.certificates != mine) {
            UmlessLog.w(TAG, "安装包签名不一致：${info.certificates} ≠ $mine")
            throw ApiException(t("update.bad_signature"))
        }
    }

    private fun verifyOrDelete(p: UpdatePlatform, apk: File) {
        try {
            verifyArchive(p, apk)
        } catch (e: Exception) {
            apk.delete()
            throw e
        }
    }

    /* ---------------------------------------------------------------- 安装 */

    /**
     * install_update()：再次核对已下载的安装包，没有「安装未知应用」权限时打开设置页并返回 needs_permission，
     * 否则提交给系统安装并返回 installing（之后由系统界面确认，结果见 UpdateInstallReceiver）
     */
    suspend fun install(): JsonObject {
        val p = requirePlatform()
        return fileLock.withLock {
            val d = loadCache().downloaded
            val apk = d?.let { File(p.updatesDir, it.file) }?.takeIf { it.isFile }
            if (d == null || apk == null) throw ApiException(t("update.not_downloaded"))
            if (sha256(apk) != d.sha256) {
                apk.delete()
                updateCache { it.copy(downloaded = null) }
                throw ApiException(t("update.checksum_failed"))
            }
            try {
                verifyArchive(p, apk)
            } catch (e: ApiException) {
                apk.delete()
                updateCache { it.copy(downloaded = null) }
                throw e
            }
            if (!p.canRequestInstall()) {
                UmlessLog.i(TAG, "没有「安装未知应用」权限，打开设置页")
                p.openInstallPermissionSettings()
                return@withLock buildJsonObject { put("status", "needs_permission") }
            }
            try {
                p.install(apk)
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                UmlessLog.e(TAG, "提交安装失败", e)
                throw ApiException(t("update.install_failed", "error" to (e.message ?: e.javaClass.simpleName)))
            }
            UmlessLog.i(TAG, "已提交安装：${d.version}（${d.versionCode}）")
            buildJsonObject { put("status", "installing") }
        }
    }

    /**
     * 启动时调用：已安装的版本不低于下载的安装包（更新成功）时删除它们；
     * 下载目录里其他无法识别或已过时的文件也一并删除
     */
    fun cleanupAfterUpdate() {
        val p = platform ?: return
        if (!fileLock.tryLock()) return
        try {
            val installed = p.installedVersionCode()
            p.updatesDir.listFiles()?.forEach { f ->
                val code = APK_NAME.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull()
                if (code == null || code <= installed) {
                    f.deleteRecursively()
                    UmlessLog.i(TAG, "清理已安装或无用的安装包：${f.name}")
                }
            }
            updateCache { c -> if (c.downloaded != null && c.downloaded.versionCode <= installed) c.copy(downloaded = null) else c }
        } finally {
            fileLock.unlock()
        }
    }
}
