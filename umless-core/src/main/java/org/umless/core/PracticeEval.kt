package org.umless.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.umless.coach.Practice

/** 口语练习评测（识别由调用方完成，这里做 LLM 评分 + 规则指标 + 落库），返回值同桌面端 get_practice(id) */
object PracticeEval {
    /**
     * 录音不足 3 秒、原文为空时抛出 org.umless.api.ApiException（practice.too_short / practice.no_speech）。
     * 大模型未配置或失败时照常落库，只给出流畅度和简洁度（与桌面端一致）。
     */
    suspend fun evaluate(
        topic: JsonObject,
        rawText: String,
        durationS: Double,
        progress: (Float?, String?) -> Unit,
    ): JsonElement = Practice.evaluate(topic, rawText, durationS, progress)
}
