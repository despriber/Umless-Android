package org.umless.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 输入框里语音流程关心的几项（从 [EditorInfo] 抽出来，单元测试不需要 Android）。
 * 常量是编译期常量，会被内联，JVM 测试里可以直接用。
 */
data class EditorFacts(
    val packageName: String?,
    val inputType: Int,
    val imeOptions: Int,
) {
    private val inputClass get() = inputType and InputType.TYPE_MASK_CLASS
    private val variation get() = inputType and InputType.TYPE_MASK_VARIATION
    private val action get() = imeOptions and EditorInfo.IME_MASK_ACTION

    /** 密码类输入框：不录音、不显示麦克风按钮、不记录 */
    val isPassword: Boolean
        get() = when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }

    /** 无痕（浏览器无痕标签页等）：照常输入，按设置决定是否记录 */
    val noPersonalizedLearning: Boolean
        get() = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    /** 搜索框 / 网址栏 / 列表过滤，以及数字、电话、日期等非文本框：只用「忠实」档，不分段 */
    val isSearchLike: Boolean
        get() = inputClass != InputType.TYPE_CLASS_TEXT ||
                variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_FILTER ||
                action == EditorInfo.IME_ACTION_SEARCH

    val isEmailSubject: Boolean
        get() = inputClass == InputType.TYPE_CLASS_TEXT &&
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT

    companion object {
        fun from(info: EditorInfo?): EditorFacts? =
            info?.let { EditorFacts(it.packageName, it.inputType, it.imeOptions) }
    }
}

/** 一次语音输入用的场景与润色强度 */
data class SceneChoice(val scene: String, val intensity: String)

/**
 * 场景判断（设计文档 3.3）：搜索 / 网址 → search + faithful；邮件主题 → email；
 * 其余按包名规则（邮件 App 的正文也由包名规则得到 email）。密码框由调用方先拦截。
 */
fun chooseScene(facts: EditorFacts, sceneOf: (String?) -> String, intensityOf: (String) -> String): SceneChoice {
    if (facts.isSearchLike) return SceneChoice("search", "faithful")
    val scene = if (facts.isEmailSubject) "email" else sceneOf(facts.packageName)
    return SceneChoice(scene, intensityOf(scene))
}
