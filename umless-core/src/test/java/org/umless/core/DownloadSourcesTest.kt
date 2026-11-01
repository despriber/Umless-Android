package org.umless.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.umless.api.ApiException
import org.umless.api.UmlessApi
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 下载源：配置默认值与校验、各模式的尝试顺序与上次成功的记忆、非 GitHub 地址不走镜像、测速接口 */
class DownloadSourcesTest {
    private val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/x.tar.bz2"
    private val defaultProbe = DownloadSources.probe

    @Before
    fun setUp() {
        TestEnv.setup()
    }

    @After
    fun tearDown() {
        DownloadSources.probe = defaultProbe
    }

    private fun network(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        UmlessConfig.savePatch(buildJsonObject { putJsonObject("network", block) })

    private fun order(u: String = url) = DownloadSources.candidates(u).map { it.source to it.url }

    @Test
    fun configDefaultsAndValidation() {
        val net = UmlessConfig.get()["network"] as JsonObject
        assertEquals("auto", net.getValue("download_source").jsonPrimitive.content)
        assertEquals("https://gh-proxy.com/", net.getValue("mirror_url").jsonPrimitive.content)
        assertEquals("auto", UmlessConfig.downloadSource)
        assertEquals("https://gh-proxy.com/", UmlessConfig.mirrorUrl)
        // get_settings 带上 network
        val settings = runBlocking { UmlessApi.call("get_settings", JsonArray(emptyList())) } as JsonObject
        assertTrue("network" in (settings["config"] as JsonObject))

        // 镜像地址规范化：去空白、末尾补 "/"
        network { put("mirror_url", "  https://mirror.example/gh  ") }
        assertEquals("https://mirror.example/gh/", UmlessConfig.str("network.mirror_url"))
        assertEquals("https://mirror.example/gh/", UmlessConfig.mirrorUrl)
        network { put("mirror_url", "http://m.example/") }
        assertEquals("http://m.example/", UmlessConfig.mirrorUrl)
        network { put("download_source", "github") }
        assertEquals("github", UmlessConfig.downloadSource)

        for ((key, bad) in listOf(
            "cfg.bad_format" to buildJsonObject { put("network", "x") },
            "cfg.bad_download_source" to buildJsonObject { putJsonObject("network") { put("download_source", "proxy") } },
            "cfg.bad_download_source" to buildJsonObject { putJsonObject("network") { put("download_source", 1) } },
            "cfg.bad_mirror_url" to buildJsonObject { putJsonObject("network") { put("mirror_url", "ftp://m.example/") } },
            "cfg.bad_mirror_url" to buildJsonObject { putJsonObject("network") { put("mirror_url", "") } },
            "cfg.bad_mirror_url" to buildJsonObject { putJsonObject("network") { put("mirror_url", "https://") } },
            "cfg.bad_mirror_url" to buildJsonObject { putJsonObject("network") { put("mirror_url", JsonNull) } },
        )) {
            try {
                UmlessConfig.savePatch(bad)
                fail("should reject $bad")
            } catch (e: ApiException) {
                val prefix = I18n.t(key).substringBefore("{").substringBefore("：")
                assertTrue("${e.message} should start with $prefix", e.message!!.startsWith(prefix))
            }
        }
        // 被拒绝的补丁不改动配置
        assertEquals("github", UmlessConfig.downloadSource)
        assertEquals("http://m.example/", UmlessConfig.mirrorUrl)
        assertEquals("Unknown download source: proxy", I18n.tr("en", "cfg.bad_download_source", "value" to "proxy"))
    }

    @Test
    fun orderPerMode() {
        val mirror = "mirror" to "https://gh-proxy.com/$url"
        val github = "github" to url
        // auto：从未成功过时先镜像
        assertEquals(listOf(mirror, github), order())
        network { put("download_source", "github") }
        assertEquals(listOf(github), order())
        network { put("download_source", "mirror") }
        assertEquals(listOf(mirror), order())
        // 自定义镜像前缀
        network { put("mirror_url", "https://m.example/p") }
        assertEquals(listOf("mirror" to "https://m.example/p/$url"), order())
    }

    @Test
    fun autoRemembersLastGoodSource() {
        assertEquals("mirror", DownloadSources.preferred())
        DownloadSources.succeeded("github")
        assertEquals("github", DownloadSources.preferred())
        assertEquals(JsonPrimitive("github"), Umless.db.getKv(DownloadSources.KV_LAST))
        assertEquals(listOf("github", "mirror"), order().map { it.first })
        DownloadSources.succeeded(null) // 非 GitHub 地址成功不改变记忆
        assertEquals("github", DownloadSources.preferred())
        DownloadSources.succeeded("mirror")
        assertEquals(listOf("mirror", "github"), order().map { it.first })
        // 只用一个源的模式不受记忆影响
        DownloadSources.succeeded("github")
        network { put("download_source", "mirror") }
        assertEquals(listOf("mirror"), order().map { it.first })
        // kv 里的值不认识时按默认（先镜像）
        Umless.db.setKv(DownloadSources.KV_LAST, JsonPrimitive("bogus"))
        assertEquals("mirror", DownloadSources.preferred())
    }

    @Test
    fun nonGithubUrlsAreNeverMirrored() {
        for (mode in listOf("auto", "github", "mirror")) {
            network { put("download_source", mode) }
            for (u in listOf(
                "https://mirror.example/update.json",
                "http://github.com/x/y", // 非 https
                "https://raw.githubusercontent.com/x/y",
                "https://github.com.evil.example/x",
                "https://gh-proxy.com/https://github.com/x/y",
            )) {
                assertEquals(mode, listOf(null to u), order(u))
            }
        }
        assertTrue(DownloadSources.isGithub("https://GitHub.com/x"))
        assertFalse(DownloadSources.isGithub("not a url"))
    }

    @Test
    fun testDownloadSourcesProbesBothInParallel() = runBlocking {
        val mirrorProbe = "https://gh-proxy.com/${DownloadSources.PROBE_URL}"
        val started = CountDownLatch(2)
        val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        DownloadSources.probe = { u, timeout ->
            seen.add(u to timeout)
            started.countDown()
            // 两个请求都开始后才返回：串行执行会在这里等到超时
            assertTrue("probes should run in parallel", started.await(5, TimeUnit.SECONDS))
            when (u) {
                DownloadSources.PROBE_URL -> 206
                mirrorProbe -> throw IOException("connection reset")
                else -> 404
            }
        }
        val r = UmlessApi.call("test_download_sources", JsonArray(emptyList())) as JsonObject
        assertEquals(setOf("github", "mirror", "preferred"), r.keys)
        assertEquals(setOf(DownloadSources.PROBE_URL to 8000, mirrorProbe to 8000), seen.toSet())
        val g = r["github"] as JsonObject
        val m = r["mirror"] as JsonObject
        assertEquals(setOf("ok", "ms", "error"), g.keys)
        assertEquals("true", g.getValue("ok").jsonPrimitive.content)
        assertTrue(g.getValue("ms").jsonPrimitive.content.toLong() >= 0)
        assertEquals(JsonNull, g["error"])
        assertEquals("false", m.getValue("ok").jsonPrimitive.content)
        assertEquals(JsonNull, m["ms"])
        assertEquals("connection reset", m.getValue("error").jsonPrimitive.content)
        // 唯一可用的源成为首选，auto 下次先试它
        assertEquals("github", r.getValue("preferred").jsonPrimitive.content)
        assertEquals("github", DownloadSources.preferred())

        // 都不可用：不抛出，首选保持记住的源；非 200/206 记为错误
        DownloadSources.probe = { u, _ -> if (u == DownloadSources.PROBE_URL) 403 else throw IOException() }
        val down = DownloadSources.test()
        assertEquals("HTTP 403", (down["github"] as JsonObject).getValue("error").jsonPrimitive.content)
        assertEquals("IOException", (down["mirror"] as JsonObject).getValue("error").jsonPrimitive.content)
        assertEquals("github", down.getValue("preferred").jsonPrimitive.content)

        // 都可用：较快的为首选
        DownloadSources.probe = { u, _ ->
            if (u == DownloadSources.PROBE_URL) Thread.sleep(300)
            200
        }
        val both = DownloadSources.test()
        assertEquals("mirror", both.getValue("preferred").jsonPrimitive.content)
        assertEquals("mirror", DownloadSources.preferred())
        assertNull((both["mirror"] as JsonObject)["error"]?.takeIf { it !is JsonNull })

        // 镜像地址按配置
        network { put("mirror_url", "https://m.example") }
        seen.clear()
        DownloadSources.probe = { u, t -> seen.add(u to t); 200 }
        DownloadSources.test()
        assertTrue(seen.any { it.first == "https://m.example/${DownloadSources.PROBE_URL}" })
    }
}
