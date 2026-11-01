package org.umless.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** 主 App 往网页里执行的脚本与结果解析（纯函数，便于单元测试） */
object WebScripts {
    const val SHIM_TAG = """<script src="js/android-shim.js"></script>"""

    /**
     * 不支持 document-start 脚本的旧 WebView：在 index.html 的模块脚本之前插入垫片
     * （普通脚本同步执行，先于延迟执行的模块脚本）。已有垫片时原样返回
     */
    fun injectShim(html: String): String {
        if (html.contains("android-shim.js")) return html
        val module = Regex("""<script\b[^>]*type\s*=\s*["']module["']""", RegexOption.IGNORE_CASE).find(html)
        val at = module?.range?.first
            ?: html.indexOf("</head>", ignoreCase = true).takeIf { it >= 0 }
            ?: return SHIM_TAG + "\n" + html
        return html.substring(0, at) + SHIM_TAG + "\n  " + html.substring(at)
    }

    /**
     * 安全区（CSS 像素）写入 CSS 变量 --sat / --sar / --sab / --sal：网页内容需要自己避让的系统栏区域。
     * 原生已用内边距避让（WebView 不支持 env(safe-area-inset-*) 时）则全为 0，见 UmlessActivity
     */
    fun insetsScript(top: Float, right: Float, bottom: Float, left: Float): String {
        fun px(v: Float) = String.format(Locale.US, "%.1fpx", v.coerceAtLeast(0f))
        return "(function(){var s=document.documentElement.style;" +
            "s.setProperty('--sat','${px(top)}');s.setProperty('--sar','${px(right)}');" +
            "s.setProperty('--sab','${px(bottom)}');s.setProperty('--sal','${px(left)}');})()"
    }

    /** 读取 env(safe-area-inset-top/bottom) 的实际值、当前深浅色和页面上下两端的背景色 */
    const val PROBE = """(function(){try{
var d=document.createElement('div');
d.style.cssText='position:fixed;left:0;top:0;width:0;height:0;visibility:hidden;pointer-events:none;padding:env(safe-area-inset-top,0px) 0 env(safe-area-inset-bottom,0px) 0';
document.documentElement.appendChild(d);
var cs=getComputedStyle(d),t=parseFloat(cs.paddingTop)||0,b=parseFloat(cs.paddingBottom)||0;d.remove();
var th=document.documentElement.dataset.theme,dark=th==='dark'||(th!=='light'&&matchMedia('(prefers-color-scheme: dark)').matches);
function bg(q){var e=document.querySelector(q);if(!e)return '';var c=getComputedStyle(e).backgroundColor;return /rgba?\(0, 0, 0, 0\)|transparent/.test(c)?'':c;}
var mobile=matchMedia('(max-width: 719px)').matches;
return JSON.stringify({t:t,b:b,dark:dark,top:bg('.main')||bg('body'),bottom:(mobile&&bg('.sidebar'))||bg('.main')||bg('body')});
}catch(e){return null}})()"""

    data class Probe(
        val envTop: Float,
        val envBottom: Float,
        val dark: Boolean,
        val topColor: Int?,
        val bottomColor: Int?,
    )

    /** evaluateJavascript 的返回值是「返回值的 JSON」：这里返回的是 JSON 字符串，需要解两次 */
    fun parseProbe(result: String?): Probe? {
        if (result.isNullOrBlank() || result == "null") return null
        return try {
            val inner = Json.parseToJsonElement(result).jsonPrimitive.content
            val o: JsonObject = Json.parseToJsonElement(inner).jsonObject
            Probe(
                envTop = o["t"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f,
                envBottom = o["b"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f,
                dark = o["dark"]?.jsonPrimitive?.booleanOrNull ?: false,
                topColor = parseCssColor(o["top"]?.jsonPrimitive?.content),
                bottomColor = parseCssColor(o["bottom"]?.jsonPrimitive?.content),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 页面加载后检查 WebView 是否把系统栏区域报告给了 env(safe-area-inset-*)（较新的 WebView 才支持）：
     * 系统栏有高度而 env() 明显偏小时，改由原生内边距避让。参数都是 CSS 像素
     */
    fun needsNativeInsets(expectedTop: Float, expectedBottom: Float, envTop: Float, envBottom: Float): Boolean =
        (expectedTop >= 4f && envTop < expectedTop / 2) || (expectedBottom >= 4f && envBottom < expectedBottom / 2)

    /** getComputedStyle 的颜色：rgb(r, g, b) / rgba(r, g, b, a) / #rrggbb → ARGB；透明或无法解析返回 null */
    fun parseCssColor(css: String?): Int? {
        val s = css?.trim()?.lowercase(Locale.US) ?: return null
        if (s.startsWith("#") && s.length == 7) {
            return s.substring(1).toIntOrNull(16)?.let { it or (0xFF shl 24) }
        }
        val m = Regex("""rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:\s*[,/]\s*([\d.]+%?))?\s*\)""").matchEntire(s)
            ?: return null
        val (r, g, b) = m.destructured.toList().take(3).map { it.toFloat().toInt().coerceIn(0, 255) }
        val aStr = m.groupValues[4]
        val a = when {
            aStr.isEmpty() -> 1f
            aStr.endsWith("%") -> aStr.dropLast(1).toFloat() / 100f
            else -> aStr.toFloat()
        }
        if (a <= 0f) return null
        val ai = Math.round(a.coerceIn(0f, 1f) * 255)
        return (ai shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * 返回键：先交给网页（可选钩子 window.__umless.back()，返回 true 表示已处理，如关闭弹窗），
     * 否则由原生按 WebView 历史后退或退出
     */
    const val BACK = "(function(){try{var u=window.__umless;if(u&&typeof u.back==='function')return u.back()===true;}catch(e){}return false})()"

    /** App 回到前台：页面可见时补派发一次 visibilitychange（网页据此刷新键盘状态） */
    const val VISIBLE = "(function(){if(!document.hidden)document.dispatchEvent(new Event('visibilitychange'))})()"

    /** 深链接跳转（page 已经过 BridgeCodec.safePage 校验） */
    fun navigateScript(page: String): String =
        "(function(){var p=${BridgeCodec.jsString(page)};var u=window.__umless;" +
            "if(u&&u.ready&&typeof u.go==='function')u.go(p);else location.hash='#'+p;})()"
}
