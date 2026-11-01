package org.umless.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umless.coach.ChatMessage
import org.umless.coach.Prompts
import org.umless.coach.toJson
import org.umless.core.I18n
import org.umless.core.Py
import org.umless.core.UmlessConfig
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** LLM 调用失败；message 已按界面语言翻译。status 为 HTTP 状态码（有的话） */
class LlmError(message: String, val status: Int? = null) : RuntimeException(message)

/** llm.* 配置 */
data class LlmConfig(
    val enabled: Boolean = true,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.2,
    val timeoutS: Double = 20.0,
    val extraBody: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromConfig(cfg: JsonObject = UmlessConfig.get()): LlmConfig = LlmConfig(
            enabled = UmlessConfig.bool("llm.enabled", cfg),
            baseUrl = UmlessConfig.str("llm.base_url", cfg),
            apiKey = UmlessConfig.str("llm.api_key", cfg),
            model = UmlessConfig.str("llm.model", cfg),
            temperature = UmlessConfig.double("llm.temperature", 0.2, cfg),
            timeoutS = UmlessConfig.double("llm.timeout_s", 20.0, cfg),
            extraBody = UmlessConfig.path("llm.extra_body", cfg) as? JsonObject ?: JsonObject(emptyMap()),
        )
    }
}

/** HTTP 层（测试替换成假的大模型） */
interface HttpTransport {
    class Response(val status: Int, val body: String)

    /** 超时抛出 SocketTimeoutException，连接失败抛出 IOException */
    suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): Response
}

/** HttpURLConnection 实现；协程取消时断开连接 */
object UrlConnectionTransport : HttpTransport {
    override suspend fun request(
        method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int,
    ): HttpTransport.Response = coroutineScope {
        val conn = URL(url).openConnection() as HttpURLConnection
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                conn.disconnect()
            }
        }
        try {
            runInterruptible(Dispatchers.IO) {
                conn.requestMethod = method
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val status = conn.responseCode
                val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
                val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                HttpTransport.Response(status, text)
            }
        } finally {
            watcher.cancel()
            conn.disconnect()
        }
    }
}

/**
 * 任意 OpenAI 兼容的 /chat/completions 接口，移植自桌面端 llm.py：
 * chat 用于润色；chatJson 用于诊断、出题、练习评测等需要结构化输出的任务。
 */
class LlmClient(private val cfg: LlmConfig, model: String? = null, timeoutS: Double? = null) {
    val model: String = model?.takeIf { it.isNotEmpty() } ?: cfg.model
    private val timeoutMs: Int = (((timeoutS?.takeIf { it > 0 } ?: cfg.timeoutS).takeIf { it > 0 } ?: 20.0) * 1000).toInt()

    companion object {
        /** 测试可替换 */
        @Volatile
        var transport: HttpTransport = UrlConnectionTransport

        // 部分模型（Qwen3、DeepSeek-R1 等）即使关闭思考也可能输出 <think> 块
        private val THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        private val FENCE = Regex("^```(?:json)?\\s*|\\s*```$", RegexOption.DOT_MATCHES_ALL)

        private fun loads(text: String): JsonElement? = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }

        /** 解析模型返回的 JSON 对象：去掉 ``` 围栏；前后有说明文字时截取最外层的大括号 */
        fun parseJson(input: String): JsonObject {
            val text = FENCE.replace(input.trim(), "")
            val data = loads(text) ?: run {
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start == -1 || end <= start) throw LlmError(I18n.t("llm.no_json"))
                loads(text.substring(start, end + 1)) ?: throw LlmError(I18n.t("llm.bad_json"))
            }
            return data as? JsonObject ?: throw LlmError(I18n.t("llm.not_object"))
        }

        private fun errorText(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private fun headers(): Map<String, String> {
        val h = linkedMapOf("Content-Type" to "application/json")
        if (cfg.apiKey.isNotEmpty()) h["Authorization"] = "Bearer ${cfg.apiKey}"
        return h
    }

    private fun url(path: String) = cfg.baseUrl.trimEnd('/') + path

    private suspend fun send(method: String, path: String, body: String?): HttpTransport.Response = try {
        transport.request(method, url(path), headers(), body, timeoutMs)
    } catch (e: SocketTimeoutException) {
        throw LlmError(I18n.t("llm.timeout"))
    } catch (e: IOException) {
        throw LlmError(I18n.t("llm.connect_failed", "error" to errorText(e)))
    } catch (e: IllegalArgumentException) { // 地址格式不对
        throw LlmError(I18n.t("llm.connect_failed", "error" to errorText(e)))
    }

    suspend fun chat(messages: List<ChatMessage>, temperature: Double? = null, extra: JsonObject = JsonObject(emptyMap())): String {
        val body = LinkedHashMap<String, JsonElement>()
        body["model"] = JsonPrimitive(model)
        body["messages"] = messages.toJson()
        body["temperature"] = JsonPrimitive(temperature ?: cfg.temperature)
        body["stream"] = JsonPrimitive(false)
        body.putAll(cfg.extraBody)
        body.putAll(extra)
        val r = send("POST", "/chat/completions", JsonObject(body).toString())
        if (r.status >= 400) {
            throw LlmError(I18n.t("llm.http_error", "status" to r.status, "body" to Py.take(r.body, 200)), r.status)
        }
        val content = try {
            val root = Json.parseToJsonElement(r.body) as JsonObject
            val msg = ((root["choices"] as JsonArray)[0] as JsonObject)["message"] as JsonObject
            when (val c = msg["content"]) {
                null -> throw IllegalStateException("content")
                JsonNull -> ""
                is JsonPrimitive -> c.content
                else -> c.toString()
            }
        } catch (e: Exception) {
            throw LlmError(I18n.t("llm.bad_response", "body" to Py.take(r.body, 200)))
        }
        return THINK.replace(content, "").trim()
    }

    /** 要求返回 JSON 对象。先用 response_format，接口不支持（400 / 422）时去掉重试 */
    suspend fun chatJson(messages: List<ChatMessage>, temperature: Double = 0.3): JsonObject {
        val text = try {
            chat(messages, temperature, buildJsonObject { put("response_format", buildJsonObject { put("type", "json_object") }) })
        } catch (e: LlmError) {
            if (e.status != 400 && e.status != 422) throw e
            chat(messages, temperature)
        }
        return parseJson(text)
    }

    suspend fun listModels(): List<String> {
        val r = try {
            send("GET", "/models", null)
        } catch (e: LlmError) {
            throw LlmError(I18n.t("llm.list_failed", "error" to e.message))
        }
        if (r.status >= 400) throw LlmError(I18n.t("llm.list_failed", "error" to "HTTP ${r.status}"))
        try {
            val data = (Json.parseToJsonElement(r.body) as JsonObject)["data"] as? JsonArray ?: JsonArray(emptyList())
            return data.map { ((it as JsonObject)["id"] as JsonPrimitive).content }.sortedWith(Py.codePointOrder)
        } catch (e: Exception) {
            throw LlmError(I18n.t("llm.list_failed", "error" to errorText(e)))
        }
    }

    /** 语音输入的润色 */
    suspend fun polish(rawText: String, glossary: List<String>, scene: String = "default", intensity: String = "moderate"): String =
        chat(Prompts.buildMessages(rawText, glossary, scene, intensity))
}
