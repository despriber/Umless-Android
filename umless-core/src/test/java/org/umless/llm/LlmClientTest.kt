package org.umless.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.umless.coach.ChatMessage
import org.umless.core.FakeLlm
import org.umless.core.I18n
import org.umless.core.Polish
import org.umless.core.TestEnv
import org.umless.core.UmlessConfig
import java.net.SocketTimeoutException

class LlmClientTest {
    @Before
    fun setUp() {
        TestEnv.setup()
    }

    private val cfg = LlmConfig(baseUrl = "https://llm.example/v1/", apiKey = "k", model = "m",
        extraBody = buildJsonObject { put("enable_thinking", false); put("temperature", 0.9) })

    private fun expectError(block: () -> Unit): String {
        try {
            block()
        } catch (e: LlmError) {
            return e.message!!
        }
        fail("expected LlmError")
        throw AssertionError()
    }

    @Test
    fun parseJsonVariants() {
        assertEquals(JsonPrimitive(1), LlmClient.parseJson("{\"a\": 1}")["a"])
        assertEquals(JsonPrimitive(1), LlmClient.parseJson("```json\n{\"a\": 1}\n```")["a"])
        assertEquals(JsonPrimitive(1), LlmClient.parseJson("```\n{\"a\": 1}```")["a"])
        assertEquals(JsonPrimitive(2), LlmClient.parseJson("好的，结果如下：{\"a\": {\"b\": 2}} 希望有帮助")["a"].let { (it as JsonObject)["b"] })
        assertEquals(I18n.t("llm.no_json"), expectError { LlmClient.parseJson("没有 JSON") })
        assertEquals(I18n.t("llm.bad_json"), expectError { LlmClient.parseJson("前缀 {bad json} 后缀") })
        assertEquals(I18n.t("llm.not_object"), expectError { LlmClient.parseJson("[1, 2]") })
    }

    @Test
    fun chatBuildsRequestAndStripsThink(): Unit = runBlocking {
        val fake = FakeLlm { _, _ -> FakeLlm.content("<think>想一想\n很多</think>\n  整理后的文字 ") }
        LlmClient.transport = fake
        val out = LlmClient(cfg).chat(listOf(ChatMessage("user", "hi")))
        assertEquals("整理后的文字", out)
        val (url, body) = fake.requests.single()
        assertEquals("https://llm.example/v1/chat/completions", url)
        assertEquals(listOf("model", "messages", "temperature", "stream", "enable_thinking"), body!!.keys.toList())
        assertEquals(JsonPrimitive(0.9), body["temperature"]) // extra_body 覆盖
        assertEquals(JsonPrimitive(false), body["stream"])
    }

    @Test
    fun chatJsonRetriesWithoutResponseFormatOn400(): Unit = runBlocking {
        val fake = FakeLlm { _, body ->
            if (body!!.containsKey("response_format")) org.umless.llm.HttpTransport.Response(400, "response_format not supported")
            else FakeLlm.content("{\"ok\": true}")
        }
        LlmClient.transport = fake
        val r = LlmClient(cfg).chatJson(listOf(ChatMessage("user", "hi")))
        assertEquals(JsonPrimitive(true), r["ok"])
        assertEquals(2, fake.requests.size)
        assertTrue(fake.requests[0].second!!.containsKey("response_format"))
        assertFalse(fake.requests[1].second!!.containsKey("response_format"))
        assertEquals(JsonPrimitive(0.9), fake.requests[1].second!!["temperature"]) // extra_body 在后，覆盖 0.3
    }

    @Test
    fun errors(): Unit = runBlocking {
        LlmClient.transport = FakeLlm { _, _ -> org.umless.llm.HttpTransport.Response(500, "x".repeat(300)) }
        val e500 = try { LlmClient(cfg).chatJson(listOf(ChatMessage("user", "hi"))); null } catch (e: LlmError) { e }
        assertEquals(I18n.t("llm.http_error", "status" to 500, "body" to "x".repeat(200)), e500!!.message)

        LlmClient.transport = FakeLlm { _, _ -> throw SocketTimeoutException("read timed out") }
        val et = try { LlmClient(cfg).chat(listOf(ChatMessage("user", "hi"))); null } catch (e: LlmError) { e }
        assertEquals(I18n.t("llm.timeout"), et!!.message)

        LlmClient.transport = FakeLlm { _, _ -> throw java.net.ConnectException("Connection refused") }
        val ec = try { LlmClient(cfg).chat(listOf(ChatMessage("user", "hi"))); null } catch (e: LlmError) { e }
        assertEquals(I18n.t("llm.connect_failed", "error" to "Connection refused"), ec!!.message)

        LlmClient.transport = FakeLlm { _, _ -> org.umless.llm.HttpTransport.Response(200, "{\"choices\": []}") }
        val eb = try { LlmClient(cfg).chat(listOf(ChatMessage("user", "hi"))); null } catch (e: LlmError) { e }
        assertTrue(eb!!.message!!.startsWith(I18n.t("llm.bad_response", "body" to "").trimEnd()))
    }

    @Test
    fun listModels(): Unit = runBlocking {
        LlmClient.transport = FakeLlm { url, _ ->
            assertTrue(url.endsWith("/v1/models"))
            org.umless.llm.HttpTransport.Response(200, "{\"data\": [{\"id\": \"b\"}, {\"id\": \"a\"}]}")
        }
        assertEquals(listOf("a", "b"), LlmClient(cfg).listModels())
        LlmClient.transport = FakeLlm { _, _ -> org.umless.llm.HttpTransport.Response(401, "no") }
        val e = try { LlmClient(cfg).listModels(); null } catch (e: LlmError) { e }
        assertTrue(e!!.message!!.startsWith(I18n.t("llm.list_failed", "error" to "").trimEnd()))
    }

    @Test
    fun polishRespectsConfig(): Unit = runBlocking {
        assertNull(Polish.polish("嗯你好", "chat", "moderate")) // 没有接口地址
        assertNull(Polish.model)
        UmlessConfig.savePatch(buildJsonObject {
            put("llm", buildJsonObject { put("base_url", "https://llm.example/v1"); put("model", "m1") })
            put("glossary", buildJsonObject { put("terms", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("Claude Code")))) })
        })
        val fake = FakeLlm { _, _ -> FakeLlm.content("你好") }
        LlmClient.transport = fake
        assertEquals("你好", Polish.polish("嗯你好", "search", "restructure"))
        assertEquals("m1", Polish.model)
        val system = ((fake.requests.single().second!!["messages"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject)["content"]
            .let { (it as JsonPrimitive).content }
        assertTrue(system.contains("整理强度：忠实")) // 搜索场景强制忠实档
        assertTrue(system.contains("术语表（识别结果中发音相近的词应纠正为这些写法）：Claude Code"))
        LlmClient.transport = FakeLlm { _, _ -> FakeLlm.content("") }
        assertNull(Polish.polish("嗯", "chat", "moderate"))
        UmlessConfig.savePatch(buildJsonObject { put("llm", buildJsonObject { put("enabled", false) }) })
        assertNull(Polish.polish("嗯你好", "chat", "moderate"))
        assertFalse(UmlessConfig.llmEnabled)
    }
}
