package org.umless.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * OpenAI 兼容的 /audio/transcriptions（OpenAI、Groq、硅基流动、自部署 FunASR 等），同桌面端 asr.OpenAIEngine。
 * 在调用方进程里直接请求（不经过 :voice 进程）。阻塞调用，请在 IO 线程使用。
 */
internal object OpenAIAsr {
    fun transcribe(samples: FloatArray, sampleRate: Int, baseUrl: String, apiKey: String, model: String): String {
        require(baseUrl.isNotBlank()) { "asr.openai.base_url is empty" }
        val boundary = "----umless" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream().apply {
            fun line(s: String) = write((s + "\r\n").toByteArray(Charsets.UTF_8))
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"model\"")
            line("")
            line(model)
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"")
            line("Content-Type: audio/wav")
            line("")
            write(Pcm.toWav16(samples, sampleRate))
            line("")
            line("--$boundary--")
        }.toByteArray()

        val conn = URL(baseUrl.trimEnd('/') + "/audio/transcriptions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw IOException("HTTP $code ${err.take(200)}")
            }
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val obj = Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("bad response")
            return obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        } finally {
            conn.disconnect()
        }
    }
}
