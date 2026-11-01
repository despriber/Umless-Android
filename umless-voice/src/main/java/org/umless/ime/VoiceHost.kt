package org.umless.ime

import android.view.inputmethod.EditorInfo

/**
 * 键盘一侧的能力，由 app 模块的 org.umless.glue.UmlessVoiceComponent 实现（它能访问上游的输入法服务与视图）。
 * :umless 里的语音流程（VoiceController）只通过这个接口操作键盘，不依赖上游类。
 * 所有方法都在主线程调用。
 */
interface VoiceHost {
    /** 当前输入框；键盘未连接输入框时为 null */
    val editorInfo: EditorInfo?

    /** 写入文字到光标处（先结束 fcitx 的预编辑）。返回是否成功 */
    fun commit(text: String): Boolean

    /**
     * 把刚写入的 [committed] 换成 [replacement]（空串即撤销）。只有光标紧跟在 committed 之后、
     * 且光标前的文字确实是 committed 时才执行，避免误删用户之后编辑过的内容。返回是否成功
     */
    fun replaceLastCommit(committed: String, replacement: String): Boolean

    /** 在键盘顶部（候选栏位置）显示状态条，level 为实时音量 0–1（仅 Listening 时有意义） */
    fun showStatus(state: VoiceStatus)

    fun hideStatus()

    /** 写入后短暂显示「原话 / 撤销」操作；点击时回调 [onAction] */
    fun showCommitActions(onAction: (CommitAction) -> Unit)

    /** 同上，只显示 [actions] 里的操作（没有润色稿时不显示「原话」）。默认实现忽略 actions */
    fun showCommitActions(actions: Set<CommitAction>, onAction: (CommitAction) -> Unit) =
        showCommitActions(onAction)

    fun hapticFeedback()
}

sealed interface VoiceStatus {
    /** 正在聆听；cancelArmed=true 表示手指已上滑，松开即取消 */
    data class Listening(val level: Float, val elapsedMs: Long, val cancelArmed: Boolean) : VoiceStatus
    data object Transcribing : VoiceStatus
    data object Polishing : VoiceStatus
    /**
     * 一次性提示（未识别到内容、模型未下载、润色失败已写入原文等），自动消失。
     * openUmless=true 时点击提示打开 Umless 主 App（模型未下载、缺少麦克风权限）
     */
    data class Message(val text: String, val isError: Boolean, val openUmless: Boolean = false) : VoiceStatus
}

enum class CommitAction { UseRaw, Undo }
