package org.umless.update

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 更新用到的网络请求（只有 GET）。单元测试换成假的实现；调用方在 IO 线程上调用 */
interface UpdateHttp {
    /** GET 文本（更新清单）；非 2xx 抛出 [HttpStatusException]，网络错误抛出 IOException */
    fun getText(url: String, timeoutMs: Int): String

    /**
     * GET 二进制（安装包）；[offset] > 0 时带 `Range: bytes=offset-` 续传。
     * 返回的 [Body] 由调用方关闭；非 2xx 抛出 [HttpStatusException]
     */
    fun open(url: String, offset: Long, connectTimeoutMs: Int, readTimeoutMs: Int): Body

    /** [status] 200 = 从头开始的完整内容，206 = 从 offset 开始的续传内容；[length] 为本次响应的字节数，未知时 -1 */
    class Body(
        val status: Int,
        val length: Long,
        val stream: InputStream,
        private val onClose: () -> Unit = {},
    ) : Closeable {
        val partial: Boolean get() = status == 206

        override fun close() {
            try {
                stream.close()
            } catch (_: IOException) {
            }
            onClose()
        }
    }
}

class HttpStatusException(val status: Int) : IOException("HTTP $status")

/** HttpURLConnection 实现：手动跟随跳转（GitHub → objects.githubusercontent.com，可能跨域名、跨协议） */
object UrlConnectionUpdateHttp : UpdateHttp {
    private const val MAX_REDIRECTS = 5
    private const val USER_AGENT = "Umless-Android"

    override fun getText(url: String, timeoutMs: Int): String {
        val conn = connect(url, emptyMap(), timeoutMs, timeoutMs)
        try {
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    override fun open(url: String, offset: Long, connectTimeoutMs: Int, readTimeoutMs: Int): UpdateHttp.Body {
        // 不要压缩：Content-Length 与续传的字节位置都按原始文件计算
        val headers = mutableMapOf("Accept-Encoding" to "identity")
        if (offset > 0) headers["Range"] = "bytes=$offset-"
        val conn = connect(url, headers, connectTimeoutMs, readTimeoutMs)
        return try {
            UpdateHttp.Body(conn.responseCode, conn.contentLengthLong, conn.inputStream) { conn.disconnect() }
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    private fun connect(url: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IOException("HTTP $code without Location")
                if (++redirects > MAX_REDIRECTS) throw IOException("too many redirects")
                current = URL(current, loc)
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw HttpStatusException(code)
            }
            return conn
        }
    }
}
