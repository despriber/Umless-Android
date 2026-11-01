package org.umless.core

import org.umless.llm.LlmClient
import org.umless.llm.LlmConfig

/** 语音输入的润色（提示词来自共享规范 spec/prompts，与桌面端一致） */
object Polish {
    /**
     * 返回润色稿；LLM 关闭（或还没填写接口地址）时返回 null；失败抛出异常（调用方回退到原文）。
     * 模型返回空内容时也返回 null（与桌面端 `polish(...) or None` 一致）。scene 为 search 时强制忠实档。
     * 超时用 llm.timeout_s；调用方可再用 withTimeout 限制总等待时间。
     */
    suspend fun polish(raw: String, scene: String, intensity: String): String? {
        val cfg = LlmConfig.fromConfig()
        if (!cfg.enabled || cfg.baseUrl.isBlank()) return null
        val inten = if (scene == "search") "faithful" else intensity
        val out = LlmClient(cfg).polish(raw, UmlessConfig.glossary, scene, inten)
        return out.ifEmpty { null }
    }

    /** 润色用的模型名（写入 utterances.llm_model）；LLM 关闭时为 null */
    val model: String?
        get() = if (UmlessConfig.llmEnabled) UmlessConfig.str("llm.model") else null
}
