package org.umless.ui

import org.umless.core.I18n
import org.umless.core.Umless
import java.util.Locale

/**
 * 平台接口返回给网页的文字。与桌面端同名的键走 [I18n]；Android 主 App 专用的少量文字放在这里，
 * 按 [Umless.language] 选中文 / 英文。
 */
internal object UiText {
    private val texts = mapOf(
        "mic_denied" to ("没有麦克风权限，请先允许 Umless 使用麦克风" to "Microphone permission is required. Please allow Umless to use the microphone"),
        "no_window" to ("请先打开 Umless" to "Please open Umless first"),
        "failed" to ("操作失败" to "Operation failed"),
        "mic_busy" to ("麦克风正在使用中" to "The microphone is busy"),
        "open_failed" to ("无法打开系统设置" to "Couldn't open system settings"),
        "pick_ime_in_settings" to ("请在「当前输入法 / 默认键盘」中选择 Umless 输入法" to "Choose Umless under \"Current keyboard / Default keyboard\""),
    )

    fun lang(): String = try {
        Umless.language()
    } catch (e: Throwable) {
        if (Locale.getDefault().language == "zh") "zh" else "en"
    }

    fun get(key: String): String {
        val (zh, en) = texts[key] ?: return key
        return if (lang() == "zh") zh else en
    }

    /** 桌面端同名键；取不到（未初始化等）时用 [fallback] */
    fun t(key: String, fallback: String, vararg args: Pair<String, Any?>): String = try {
        I18n.t(key, *args).takeIf { it.isNotBlank() && it != key } ?: fallback
    } catch (e: Throwable) {
        fallback
    }
}
