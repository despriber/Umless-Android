package org.umless.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * GitHub 下载源（Umless 主仓库 spec/update.md「下载镜像」）：识别模型、更新清单与安装包共用。
 *
 * 配置 network.download_source：
 * - "github"：只直连 GitHub；"mirror"：只走镜像（network.mirror_url + 原地址）；
 * - "auto"（默认）：先试上次成功的源（kv download_source_last，从未成功过时先镜像），失败再换另一个。
 * 只有 github.com 的地址才走镜像；其他地址（如自定义的 update.manifest_url）总是直连。
 */
object DownloadSources {
    const val AUTO = "auto"
    const val GITHUB = "github"
    const val MIRROR = "mirror"
    val MODES = listOf(AUTO, GITHUB, MIRROR)

    const val DEFAULT_MIRROR = "https://gh-proxy.com/"

    /** 数据库 kv：auto 模式下上次成功的源（"github" / "mirror"） */
    const val KV_LAST = "download_source_last"

    /** sherpa-onnx 官方模型发布地址（ModelManager 下载用） */
    const val MODEL_RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    /** 测速用的地址：SenseVoice 模型压缩包（与 ModelManager.MODELS 的 sensevoice 一致），只请求前 1 KiB */
    const val PROBE_URL = "$MODEL_RELEASE/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2"
    const val PROBE_TIMEOUT_MS = 8_000

    private const val TAG = "Download"

    /** 一个候选地址；[source] 为 "github" / "mirror"，非 GitHub 地址为 null（不参与记忆） */
    data class Candidate(val source: String?, val url: String)

    /**
     * 测速请求：GET [url] 的前 1 KiB（Range: bytes=0-1023），跟随跳转，返回最终的 HTTP 状态码；
     * 网络错误抛出 IOException。单元测试可替换
     */
    @Volatile
    var probe: (url: String, timeoutMs: Int) -> Int = ::httpProbe

    /** 是否 github.com 上的地址（只有这些走镜像） */
    fun isGithub(url: String): Boolean = try {
        val u = URI(url)
        u.scheme.equals("https", true) && u.host.equals("github.com", true)
    } catch (_: Exception) {
        false
    }

    /** 规范化镜像地址：http(s):// 开头且有主机名，末尾补 "/"；不合法返回 null */
    fun normalizeMirror(raw: String): String? {
        val s = raw.trim()
        if (!s.startsWith("https://", true) && !s.startsWith("http://", true)) return null
        val host = try {
            URI(s).host
        } catch (_: Exception) {
            null
        }
        if (host.isNullOrEmpty() || s.any { it.isWhitespace() }) return null
        return if (s.endsWith("/")) s else "$s/"
    }

    /** auto 模式下先试的源：上次成功的，从未成功过时为镜像 */
    fun preferred(): String {
        val last = runCatching { (Umless.db.getKv(KV_LAST) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
        return if (last == GITHUB || last == MIRROR) last else MIRROR
    }

    /** 某个源下载成功后调用：记住它，auto 模式下次先试它（非 GitHub 地址不记） */
    fun succeeded(source: String?) {
        if (source != GITHUB && source != MIRROR) return
        if (preferred() == source) return
        runCatching { Umless.db.setKv(KV_LAST, JsonPrimitive(source)) }
            .onFailure { UmlessLog.w(TAG, "记录下载源失败", it) }
    }

    /** [url] 按当前配置依次要尝试的地址 */
    fun candidates(url: String): List<Candidate> {
        if (!isGithub(url)) return listOf(Candidate(null, url))
        val github = Candidate(GITHUB, url)
        val mirror = Candidate(MIRROR, UmlessConfig.mirrorUrl + url)
        return when (UmlessConfig.downloadSource) {
            GITHUB -> listOf(github)
            MIRROR -> listOf(mirror)
            else -> if (preferred() == GITHUB) listOf(github, mirror) else listOf(mirror, github)
        }
    }

    /* ---------------------------------------------------------------- 测速 */

    /**
     * test_download_sources()：同时请求两个源的前 1 KiB（约 8 秒超时），不因网络错误抛出。
     * 返回 {"github": {ok, ms, error}, "mirror": {...}, "preferred"}；
     * preferred 为可用源中较快的一个（同时记为 auto 下次先试的源），都不可用时为当前记住的源
     */
    suspend fun test(): JsonObject = coroutineScope {
        val github = async(Dispatchers.IO) { probeOne(PROBE_URL) }
        val mirror = async(Dispatchers.IO) { probeOne(UmlessConfig.mirrorUrl + PROBE_URL) }
        val g = github.await()
        val m = mirror.await()
        val best = listOf(GITHUB to g, MIRROR to m).filter { it.second.ok }.minByOrNull { it.second.ms ?: Long.MAX_VALUE }?.first
        if (best != null) succeeded(best)
        UmlessLog.i(TAG, "下载源测速：github ${g.summary()}，mirror ${m.summary()}")
        buildJsonObject {
            put(GITHUB, g.toJson())
            put(MIRROR, m.toJson())
            put("preferred", best ?: preferred())
        }
    }

    private class ProbeResult(val ok: Boolean, val ms: Long?, val error: String?) {
        fun toJson() = buildJsonObject {
            put("ok", ok)
            put("ms", ms?.let(::JsonPrimitive) ?: JsonNull)
            put("error", error)
        }

        fun summary() = if (ok) "${ms}ms" else "失败（$error）"
    }

    private fun probeOne(url: String): ProbeResult {
        val start = System.nanoTime()
        return try {
            val status = probe(url, PROBE_TIMEOUT_MS)
            val ms = (System.nanoTime() - start) / 1_000_000
            if (status == 200 || status == 206) ProbeResult(true, ms, null) else ProbeResult(false, null, "HTTP $status")
        } catch (e: Exception) {
            ProbeResult(false, null, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
    }

    /** HttpURLConnection 测速：手动跟随跳转（可能跨域名、跨协议），总时长不超过 [timeoutMs] */
    private fun httpProbe(url: String, timeoutMs: Int): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        fun remaining(): Int = (deadline - System.currentTimeMillis()).toInt().also {
            if (it <= 0) throw IOException("timeout")
        }
        var current = URL(url)
        var redirects = 0
        while (true) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = remaining()
                readTimeout = remaining()
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Umless-Android")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=0-1023")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("HTTP $code without Location")
                    if (++redirects > 5) throw IOException("too many redirects")
                    current = URL(current, loc)
                    continue
                }
                if (code == 200 || code == 206) {
                    // 读到 1 KiB 才算可用（只返回响应头的源不算）
                    conn.readTimeout = remaining()
                    conn.inputStream.use { input ->
                        val buf = ByteArray(1024)
                        var got = 0
                        while (got < buf.size) {
                            val n = input.read(buf, got, buf.size - got)
                            if (n < 0) break
                            got += n
                        }
                        if (got == 0) throw IOException("empty response")
                    }
                }
                return code
            } finally {
                conn.disconnect()
            }
        }
    }
}
