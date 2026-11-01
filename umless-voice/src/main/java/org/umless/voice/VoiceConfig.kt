package org.umless.voice

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.umless.core.UmlessConfig

/**
 * 安全地读取 UmlessConfig：配置读失败时用默认值，不让键盘因为配置问题崩溃。
 * 只在 Umless 已初始化的进程里使用（输入法、主 App）。
 */
internal object VoiceConfig {
    fun <T> safe(default: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        default
    }

    private fun at(vararg path: String): JsonElement? = safe(null) {
        var cur: JsonElement? = UmlessConfig.get()
        for (p in path) cur = (cur as? JsonObject)?.get(p)
        cur
    }

    fun string(default: String, vararg path: String): String =
        (at(*path) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default

    fun double(default: Double, vararg path: String): Double =
        (at(*path) as? JsonPrimitive)?.doubleOrNull ?: default

    fun long(default: Long, vararg path: String): Long =
        (at(*path) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: default

    fun bool(default: Boolean, vararg path: String): Boolean =
        (at(*path) as? JsonPrimitive)?.booleanOrNull ?: default

    /** 识别引擎：sensevoice / openai */
    val asrEngine: String get() = safe("sensevoice") { UmlessConfig.asrEngine }

    /** 手机上线程数最多 4 */
    val numThreads: Int get() = safe(2) { UmlessConfig.numThreads }.coerceIn(1, 4)

    val openaiBaseUrl: String get() = string("", "asr", "openai", "base_url")
    val openaiApiKey: String get() = string("", "asr", "openai", "api_key")
    val openaiModel: String get() = string("FunAudioLLM/SenseVoiceSmall", "asr", "openai", "model")

    /** 术语表（识别后恢复术语大小写用） */
    val glossary: List<String> get() = safe(emptyList()) { UmlessConfig.glossary }
}
