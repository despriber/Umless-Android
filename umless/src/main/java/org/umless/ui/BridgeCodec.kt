package org.umless.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umless.api.ApiException

/**
 * JS 桥的编解码（纯函数，便于单元测试）。协议见 Umless 主仓库 docs/ui-api.md「Android 桥接」：
 * - JS → 原生：UmlessBridge.call(id, method, argsJson)，argsJson 为参数数组的 JSON
 * - 原生 → JS：window.__umlessResolve(id, ok, resultJson)；ok=false 时 resultJson 为 {"message": "…"}
 */
object BridgeCodec {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val HOST = "appassets.androidplatform.net"

    /** 参数数组；空串 / null 视为无参数，不是数组时报错 */
    fun parseArgs(argsJson: String?): JsonArray {
        if (argsJson.isNullOrBlank()) return JsonArray(emptyList())
        val el = try {
            Json.parseToJsonElement(argsJson)
        } catch (e: Exception) {
            throw ApiException("bad arguments: ${e.message}")
        }
        return when (el) {
            is JsonArray -> el
            JsonNull -> JsonArray(emptyList())
            else -> throw ApiException("bad arguments: expected an array")
        }
    }

    /**
     * 任意字符串 → JS 字符串字面量（JSON 字符串字面量，另把 U+2028 / U+2029 转义：
     * 它们在 JSON 中合法，但旧版 WebView 的 JS 引擎会当作换行而报语法错误）
     */
    fun jsString(s: String): String =
        JsonPrimitive(s).toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    /**
     * 调用 id：垫片传的是数字（经 Java 桥转成字符串，如 "7"，也可能是 "7.0"）。
     * 整数输出为数字字面量，其余一律当字符串字面量，保证不会被拼成任意脚本
     */
    fun jsId(id: String): String {
        val d = id.trim().toDoubleOrNull()
        if (d != null && d.isFinite() && d == Math.floor(d) && Math.abs(d) < 9.007199254740991E15) {
            return d.toLong().toString()
        }
        return jsString(id)
    }

    fun resolveScript(id: String, ok: Boolean, resultJson: String): String =
        "window.__umlessResolve&&window.__umlessResolve(${jsId(id)},$ok,${jsString(resultJson)})"

    fun okScript(id: String, result: JsonElement): String = resolveScript(id, true, result.toString())

    fun errorScript(id: String, message: String): String =
        resolveScript(id, false, buildJsonObject { put("message", message) }.toString())

    /** 只接受我们自己的资源域名（https://appassets.androidplatform.net/…）下的页面 */
    fun isTrustedUrl(url: String?): Boolean {
        if (url == null) return false
        return url == ORIGIN || url.startsWith("$ORIGIN/")
    }

    /** 深链接页面（intent extra "page"，如 settings/keyboard）：只允许路由字符，防止拼进脚本 */
    fun safePage(page: String?): String? {
        val p = page?.trim()?.removePrefix("#")?.trim('/') ?: return null
        return p.takeIf { it.isNotEmpty() && it.length <= 64 && PAGE_RE.matches(it) }
    }

    /** 键盘请求打开主 App 时的动作：去下载模型 / 授权麦克风（其余按路由处理） */
    const val ACTION_MODELS = "models"
    const val ACTION_PERMISSION = "permission"

    /** 深链接 → 网页路由：models → 设置「识别」分组；permission 不跳转（原生直接弹授权框） */
    fun routeFor(page: String?): String? = when (page?.trim()) {
        ACTION_MODELS -> "settings/asr"
        ACTION_PERMISSION -> null
        else -> safePage(page)
    }

    private val PAGE_RE = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*")
}
