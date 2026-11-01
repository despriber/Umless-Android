package org.umless.ui

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebScriptsTest {

    private val index = """
        <head>
          <link rel="stylesheet" href="css/mobile.css">
        </head>
        <body>
          <div id="app"></div>
          <script type="module" src="js/app.js"></script>
        </body>
    """.trimIndent()

    @Test
    fun injectShim_beforeModuleScript() {
        val out = WebScripts.injectShim(index)
        val shim = out.indexOf(WebScripts.SHIM_TAG)
        val module = out.indexOf("""<script type="module"""")
        assertTrue(shim in 0 until module)
        // 幂等
        assertEquals(out, WebScripts.injectShim(out))
    }

    @Test
    fun injectShim_fallbacks() {
        val noModule = "<html><head><title>x</title></head><body></body></html>"
        val out = WebScripts.injectShim(noModule)
        assertTrue(out.indexOf(WebScripts.SHIM_TAG) < out.indexOf("</head>"))
        assertTrue(WebScripts.injectShim("<p>bare</p>").startsWith(WebScripts.SHIM_TAG))
        val single = "<script defer type='module' src='js/app.js'></script>"
        assertTrue(WebScripts.injectShim(single).startsWith(WebScripts.SHIM_TAG))
    }

    @Test
    fun insetsScript_formatsCssPixels() {
        val s = WebScripts.insetsScript(24f, 0f, 47.6f, -3f)
        assertTrue(s.contains("'--sat','24.0px'"))
        assertTrue(s.contains("'--sar','0.0px'"))
        assertTrue(s.contains("'--sab','47.6px'"))
        assertTrue(s.contains("'--sal','0.0px'"))
    }

    @Test
    fun parseCssColor() {
        assertEquals(0xFFF8FAF9.toInt(), WebScripts.parseCssColor("rgb(248, 250, 249)"))
        assertEquals(0xFF181B1A.toInt(), WebScripts.parseCssColor("#181b1a"))
        assertEquals(0x80FFFFFF.toInt(), WebScripts.parseCssColor("rgba(255, 255, 255, 0.5)"))
        assertEquals(0xFF0D9488.toInt(), WebScripts.parseCssColor("rgb(13 148 136 / 100%)"))
        assertNull(WebScripts.parseCssColor("rgba(0, 0, 0, 0)"))
        assertNull(WebScripts.parseCssColor("transparent"))
        assertNull(WebScripts.parseCssColor(""))
        assertNull(WebScripts.parseCssColor(null))
    }

    @Test
    fun parseProbe_decodesTwice() {
        val inner = """{"t":24,"b":47.5,"dark":true,"top":"rgb(24, 27, 26)","bottom":""}"""
        val result = JsonPrimitive(inner).toString() // evaluateJavascript 返回的是 JSON 字符串字面量
        val p = WebScripts.parseProbe(result)
        assertNotNull(p)
        p!!
        assertEquals(24f, p.envTop)
        assertEquals(47.5f, p.envBottom)
        assertTrue(p.dark)
        assertEquals(0xFF181B1A.toInt(), p.topColor)
        assertNull(p.bottomColor)
        assertNull(WebScripts.parseProbe("null"))
        assertNull(WebScripts.parseProbe(null))
        assertNull(WebScripts.parseProbe("\"not json\""))
    }

    @Test
    fun needsNativeInsets() {
        // WebView 正确报告了 env()
        assertFalse(WebScripts.needsNativeInsets(24f, 48f, 24f, 48f))
        // 旧 WebView：env() 一律为 0
        assertTrue(WebScripts.needsNativeInsets(24f, 48f, 0f, 0f))
        // 只报告了刘海、没报告导航栏
        assertTrue(WebScripts.needsNativeInsets(24f, 48f, 24f, 0f))
        // 没有系统栏（如全屏）时无法判断，保持全面屏
        assertFalse(WebScripts.needsNativeInsets(0f, 0f, 0f, 0f))
    }

    @Test
    fun navigateScript_quotesPage() {
        val s = WebScripts.navigateScript("settings/keyboard")
        assertTrue(s.contains("var p=\"settings/keyboard\""))
    }

    @Test
    fun backScript_isExpression() {
        assertTrue(WebScripts.BACK.startsWith("(function(){") && WebScripts.BACK.endsWith("})()"))
    }

    @Test
    fun mimeTypes() {
        assertEquals("text/javascript", WebAssetHandler.mimeOf("js/app.js"))
        assertEquals("text/javascript", WebAssetHandler.mimeOf("js/locales/zh.JS"))
        assertEquals("text/css", WebAssetHandler.mimeOf("css/tokens.css"))
        assertEquals("text/html", WebAssetHandler.mimeOf("index.html"))
        assertEquals("image/svg+xml", WebAssetHandler.mimeOf("img/logo.svg"))
        assertEquals("application/octet-stream", WebAssetHandler.mimeOf("LICENSE"))
    }

    @Test
    fun coreStatusMapping() {
        fun status(enabled: Boolean, selected: Boolean, model: Boolean) =
            ImeState(enabled, selected).coreStatus(model)

        status(false, false, true).let {
            assertFalse(it["running"]!!.jsonPrimitive.boolean)
            assertFalse(it["ready"]!!.jsonPrimitive.boolean)
            assertEquals("commit", it["mode"]!!.jsonPrimitive.content)
            assertTrue(it.containsKey("hotkey"))
            assertEquals("null", it["hotkey"].toString())
        }
        status(true, false, true).let {
            assertTrue(it["running"]!!.jsonPrimitive.boolean)
            assertFalse(it["ready"]!!.jsonPrimitive.boolean)
        }
        assertFalse(status(true, true, false)["ready"]!!.jsonPrimitive.boolean)
        assertTrue(status(true, true, true)["ready"]!!.jsonPrimitive.boolean)

        val ime = ImeState(true, false).toJson()
        assertEquals(setOf("enabled", "selected"), ime.keys)
    }

    @Test
    fun imeHelpers() {
        val pkg = "org.umless.ime"
        assertTrue(ImeState.isOurs("org.umless.ime/org.fcitx.fcitx5.android.input.FcitxInputMethodService", pkg))
        assertTrue(ImeState.isOurs("org.umless.ime/.input.Service", pkg))
        assertFalse(ImeState.isOurs("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME", pkg))
        assertFalse(ImeState.isOurs("org.umless.ime.evil/x.Y", pkg))
        assertFalse(ImeState.isOurs(null, pkg))
        assertFalse(ImeState.isOurs("", pkg))
    }

    @Test
    fun safeWavName() {
        assertEquals("ding.wav", UserFiles.safeWavName("ding.wav"))
        assertEquals("提示音 1.wav", UserFiles.safeWavName("提示音 1.WAV"))
        assertEquals("a_b.wav", UserFiles.safeWavName("x/../a:b.mp3"))
        assertEquals("sound.wav", UserFiles.safeWavName(null))
        assertEquals("sound.wav", UserFiles.safeWavName("..."))
        assertEquals("noext.wav", UserFiles.safeWavName("noext"))
    }

    @Test
    fun micLevelJson() {
        val j = MicSession.levelJson(true, 12_449, 0.37421f)
        assertTrue(j["recording"]!!.jsonPrimitive.boolean)
        assertEquals("12.4", j["elapsed"]!!.jsonPrimitive.content)
        assertEquals("0.374", j["level"]!!.jsonPrimitive.content)
        val idle = MicSession.levelJson(false, 0, 2f)
        assertEquals("0.0", idle["elapsed"]!!.jsonPrimitive.content)
        assertEquals("1.0", idle["level"]!!.jsonPrimitive.content)
    }
}
