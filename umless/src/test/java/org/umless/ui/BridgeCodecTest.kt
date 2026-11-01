package org.umless.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.umless.api.ApiException

class BridgeCodecTest {
    // 行分隔符 / 段分隔符 / NUL：在运行时构造，避免源码里出现不可见字符
    private val ls = 0x2028.toChar().toString()
    private val ps = 0x2029.toChar().toString()
    private val nul = 0.toChar().toString()
    private val bs = 0x5C.toChar().toString() // 反斜杠

    @Test
    fun parseArgs_arrays() {
        assertEquals(0, BridgeCodec.parseArgs(null).size)
        assertEquals(0, BridgeCodec.parseArgs("").size)
        assertEquals(0, BridgeCodec.parseArgs("[]").size)
        assertEquals(0, BridgeCodec.parseArgs("null").size)
        val args = BridgeCodec.parseArgs("""["2026-10-12", 30, {"title": "介绍", "duration": 90}, null, true]""")
        assertEquals(5, args.size)
        assertEquals("2026-10-12", args[0].jsonPrimitive.content)
        assertEquals(30, args[1].jsonPrimitive.content.toInt())
        assertEquals("介绍", args[2].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, args[3])
    }

    @Test
    fun parseArgs_rejectsNonArray() {
        for (bad in listOf("""{"a":1}""", "42", "[1,", "not json")) {
            try {
                BridgeCodec.parseArgs(bad)
                fail("应拒绝 $bad")
            } catch (_: ApiException) {
            }
        }
    }

    @Test
    fun jsString_escapesEverythingDangerous() {
        val nasty = "a\"b'c" + bs + "d\ne\r" + ls + "f" + ps + "g</script><!--" + nul
        val lit = BridgeCodec.jsString(nasty)
        assertTrue(lit.startsWith("\"") && lit.endsWith("\""))
        for (raw in listOf("\n", "\r", ls, ps, nul)) assertFalse(lit.contains(raw))
        assertTrue(lit.contains(bs + "u2028") && lit.contains(bs + "u2029"))
        // 仍是合法 JSON，解析后与原文一致
        assertEquals(nasty, Json.parseToJsonElement(lit).jsonPrimitive.content)
    }

    @Test
    fun jsId_numbersAndStrings() {
        assertEquals("7", BridgeCodec.jsId("7"))
        assertEquals("7", BridgeCodec.jsId("7.0"))
        assertEquals("123456789", BridgeCodec.jsId("123456789"))
        assertEquals("\"abc\"", BridgeCodec.jsId("abc"))
        assertEquals("\"1);alert(1\"", BridgeCodec.jsId("1);alert(1"))
        assertEquals("\"NaN\"", BridgeCodec.jsId("NaN"))
        assertEquals("\"1.5\"", BridgeCodec.jsId("1.5"))
    }

    @Test
    fun resolveScripts() {
        val result = buildJsonObject { put("text", "嗯\"你好\"$ls") }
        val ok = BridgeCodec.okScript("3", result)
        assertTrue(ok.startsWith("window.__umlessResolve&&window.__umlessResolve(3,true,\""))
        assertFalse(ok.contains(ls))
        // 第三个参数是 JS 字符串，内容是结果的 JSON
        val literal = ok.substringAfter("(3,true,").removeSuffix(")")
        val json = Json.parseToJsonElement(Json.parseToJsonElement(literal).jsonPrimitive.content)
        assertEquals(result, json)

        assertEquals("window.__umlessResolve&&window.__umlessResolve(4,true,\"null\")", BridgeCodec.okScript("4", JsonNull))

        val err = BridgeCodec.errorScript("5", "题目不能为空")
        val errLiteral = err.substringAfter("(5,false,").removeSuffix(")")
        val payload = Json.parseToJsonElement(Json.parseToJsonElement(errLiteral).jsonPrimitive.content).jsonObject
        assertEquals("题目不能为空", payload["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun okScript_arrayResult() {
        val arr = buildJsonArray { add(JsonPrimitive("deepseek-v4-flash")) }
        val literal = BridgeCodec.okScript("1", arr).substringAfter("(1,true,").removeSuffix(")")
        assertEquals("""["deepseek-v4-flash"]""", Json.parseToJsonElement(literal).jsonPrimitive.content)
    }

    @Test
    fun trustedUrls() {
        assertTrue(BridgeCodec.isTrustedUrl("https://appassets.androidplatform.net/web/index.html#overview"))
        assertTrue(BridgeCodec.isTrustedUrl("https://appassets.androidplatform.net"))
        assertFalse(BridgeCodec.isTrustedUrl(null))
        assertFalse(BridgeCodec.isTrustedUrl("http://appassets.androidplatform.net/web/index.html"))
        assertFalse(BridgeCodec.isTrustedUrl("https://appassets.androidplatform.net.evil.com/web/index.html"))
        assertFalse(BridgeCodec.isTrustedUrl("https://evil.com/?https://appassets.androidplatform.net/"))
        assertFalse(BridgeCodec.isTrustedUrl("file:///android_asset/web/index.html"))
    }

    @Test
    fun safePage() {
        assertEquals("settings/keyboard", BridgeCodec.safePage("settings/keyboard"))
        assertEquals("settings/keyboard", BridgeCodec.safePage("#settings/keyboard"))
        assertEquals("report/2026-10-12", BridgeCodec.safePage("/report/2026-10-12/"))
        assertEquals("onboarding", BridgeCodec.safePage(" onboarding "))
        assertNull(BridgeCodec.safePage(null))
        assertNull(BridgeCodec.safePage(""))
        assertNull(BridgeCodec.safePage("x';alert(1);'"))
        assertNull(BridgeCodec.safePage("settings/../../etc"))
        assertNull(BridgeCodec.safePage("a b"))
    }

    @Test
    fun routeFor() {
        assertEquals("settings/asr", BridgeCodec.routeFor("models"))
        assertNull(BridgeCodec.routeFor("permission"))
        assertEquals("settings/keyboard", BridgeCodec.routeFor("settings/keyboard"))
        assertNull(BridgeCodec.routeFor(null))
        assertNull(BridgeCodec.routeFor("<x>"))
    }
}
