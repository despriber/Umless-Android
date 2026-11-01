package org.umless.ui

import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 提供 https://appassets.androidplatform.net/web/… → assets/web/…（构建时从 Umless 主仓库复制的网页界面）。
 * [injectShim] 为 true（WebView 不支持 document-start 脚本）时在 index.html 里插入 android-shim.js。
 */
internal class WebAssetHandler(
    private val assets: AssetManager,
    private val injectShim: Boolean,
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val clean = path.trimStart('/')
        if (clean.isEmpty() || clean.split('/').any { it == ".." }) return null
        val asset = "web/$clean"
        return try {
            val mime = mimeOf(clean)
            if (clean == "index.html") {
                var html = assets.open(asset).use { String(it.readBytes(), Charsets.UTF_8) }
                if (injectShim) html = WebScripts.injectShim(html)
                response(mime, ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
            } else {
                response(mime, assets.open(asset))
            }
        } catch (_: FileNotFoundException) {
            null
        } catch (e: IOException) {
            UiLog.w("WebAssets", "读取 $asset 失败", e)
            null
        }
    }

    private fun response(mime: String, data: java.io.InputStream) =
        WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("json")) "utf-8" else null, data).apply {
            // 资源随 App 更新；不缓存，避免升级后加载到旧脚本
            responseHeaders = mapOf("Cache-Control" to "no-cache")
        }

    companion object {
        /** ES 模块要求正确的 JavaScript MIME 类型，不能依赖系统猜测 */
        fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "wav" -> "audio/wav"
            "txt", "md" -> "text/plain"
            else -> "application/octet-stream"
        }

        fun empty(status: Int, reason: String) =
            WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}

/**
 * 原生避让系统栏时（见 UmlessActivity）露出的状态栏 / 导航栏区域：上方用页面背景色，下方用底部标签栏的颜色
 */
internal class BarBackground(var topColor: Int, var bottomColor: Int) : Drawable() {
    var bottomHeight = 0
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.color = topColor
        canvas.drawRect(b, paint)
        if (bottomHeight > 0) {
            paint.color = bottomColor
            canvas.drawRect(b.left.toFloat(), (b.bottom - bottomHeight).toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
