package org.umless.ime

import org.umless.core.I18n
import org.umless.core.Umless

/**
 * 键盘状态条用到的文字：优先用 I18n（跟随 Umless 界面语言），取不到时（未初始化、缺少键）用内置文字兜底，
 * 保证键盘上永远不会因为文字缺失而崩溃或显示键名。
 */
object VoiceTexts {
    private val zh = mapOf(
        "voice.listening" to "正在聆听",
        "voice.release_to_cancel" to "松开取消",
        "voice.slide_to_cancel" to "上滑取消",
        "voice.transcribing" to "识别中",
        "voice.polishing" to "润色中",
        "voice.inserted" to "已写入",
        "voice.no_speech" to "未识别到内容",
        "voice.model_missing" to "识别模型未下载，点此打开 Umless 下载",
        "voice.mic_permission" to "需要麦克风权限，点此打开 Umless 授权",
        "voice.mic_failed" to "无法打开麦克风",
        "voice.polish_failed" to "润色失败，已写入原文",
        "voice.polish_timeout" to "润色超时，已写入原文",
        "voice.password_field" to "密码框不能使用语音输入",
        "voice.too_short" to "说话时间太短",
        "voice.use_raw" to "原话",
        "voice.undo" to "撤销",
        "voice.failed" to "识别失败",
    )
    private val en = mapOf(
        "voice.listening" to "Listening",
        "voice.release_to_cancel" to "Release to cancel",
        "voice.slide_to_cancel" to "Slide up to cancel",
        "voice.transcribing" to "Transcribing",
        "voice.polishing" to "Polishing",
        "voice.inserted" to "Inserted",
        "voice.no_speech" to "Didn't catch that",
        "voice.model_missing" to "Speech model not downloaded. Tap to open Umless",
        "voice.mic_permission" to "Microphone permission needed. Tap to open Umless",
        "voice.mic_failed" to "Can't open the microphone",
        "voice.polish_failed" to "Polish failed, raw text inserted",
        "voice.polish_timeout" to "Polish timed out, raw text inserted",
        "voice.password_field" to "Voice input is off in password fields",
        "voice.too_short" to "Too short",
        "voice.use_raw" to "Original",
        "voice.undo" to "Undo",
        "voice.failed" to "Recognition failed",
    )

    fun t(key: String): String {
        val s = runCatching { I18n.t(key) }.getOrNull()
        if (!s.isNullOrBlank() && s != key) return s
        return fallback(key)
    }

    /** 不在 I18n 键表里的文字（中英两种） */
    fun pick(zhText: String, enText: String): String = if (isEnglish()) enText else zhText

    private fun fallback(key: String): String = (if (isEnglish()) en[key] else zh[key]) ?: zh[key] ?: key

    private fun isEnglish(): Boolean = runCatching { Umless.language() }.getOrNull() == "en"
}
