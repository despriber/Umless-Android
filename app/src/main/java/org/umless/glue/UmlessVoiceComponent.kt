package org.umless.glue

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.umless.ime.AndroidVoice
import org.umless.ime.CommitAction
import org.umless.ime.EditorFacts
import org.umless.ime.VoiceController
import org.umless.ime.VoiceHost
import org.umless.ime.VoiceStatus
import org.umless.ime.VoiceTexts
import splitties.dimensions.dp
import java.lang.ref.WeakReference
import kotlin.math.max

/**
 * Umless 语音在输入法视图里的落脚点（InputView 的依赖域组件，随 InputView 重建）：
 * 实现 [VoiceHost]（写入 / 撤销 / 状态条 / 震动），持有 [VoiceController]，
 * 处理长按空格（触摸钩子：松开结束、上滑取消），并按需预加载识别模型（见 [preload]）。
 */
class UmlessVoiceComponent : UniqueComponent<UmlessVoiceComponent>(), Dependent,
    ManagedHandler by managedHandler(), InputBroadcastReceiver, VoiceHost {

    private val service by manager.inputMethodService()
    private val context by manager.context()
    private val theme by manager.theme()
    private val inputView by manager.inputView()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var _controller: VoiceController? = null

    val controller: VoiceController
        get() = _controller ?: AndroidVoice.controller(service, this, UmlessGlue.client(service), scope).also {
            _controller = it
            it.addListener { s ->
                if (s is VoiceController.State.Committed) UmlessGlue.lastResult = s.result
                // 开始说话时立即加载模型（空闲后可能已释放）：加载与说话同时进行，松开后基本不用等
                if (s is VoiceController.State.Listening) {
                    lastVoiceAt = SystemClock.elapsedRealtime()
                    preloadNow()
                }
            }
        }

    private var statusView: VoiceStatusView? = null
    private var editorKey: Triple<String?, Int, Int>? = null
    private var disposed = false

    // 长按空格：按下时记录位置，长按触发后由触摸钩子接收移动 / 松开
    private var spaceDown = false
    private var spaceDownY = 0f
    private var spaceCancelThreshold = 0f
    private var spaceSession = false

    override fun onScopeSetupFinished(scope: DynamicScope) {
        current = WeakReference(this)
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        // 同一个输入框的 restartInput（微信、浏览器等常见）不算换输入框，不取消进行中的识别与「原话 / 撤销」
        val key = editorKey(info)
        if (key != editorKey) {
            editorKey = key
            _controller?.onEditorChanged()
        }
        if (!UmlessGlue.ready) return
        ensureStatusView()
        preload(info, capFlags)
    }

    // ---------------------------------------------------------------- 长按空格

    /** CommonKeyActionListener：空格长按行为为 Umless 语音时调用 */
    fun onSpaceLongPress() {
        if (disposed || !spaceDown || spaceSession) return
        if (!UmlessGlue.ready || !UmlessGlue.spaceLongPressEnabled) return
        ensureStatusView()
        spaceSession = controller.press(VoiceController.Source.Space)
    }

    private fun handleSpaceTouch(v: View, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                spaceDown = true
                spaceSession = false
                spaceDownY = e.y
                // 上滑超过约一个按键高度（至少 56dp）进入「松开取消」
                spaceCancelThreshold = max(v.height.toFloat(), v.dp(56f))
            }
            MotionEvent.ACTION_MOVE -> if (spaceSession) {
                controller.drag(e.y - spaceDownY, spaceCancelThreshold)
            }
            MotionEvent.ACTION_UP -> {
                spaceDown = false
                if (spaceSession) {
                    spaceSession = false
                    controller.release()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                spaceDown = false
                if (spaceSession) {
                    spaceSession = false
                    controller.cancelIfListening(VoiceController.Source.Space)
                }
            }
        }
    }

    // ---------------------------------------------------------------- VoiceHost

    override val editorInfo: EditorInfo?
        get() = if (service.currentInputConnection != null) service.currentInputEditorInfo else null

    override fun commit(text: String): Boolean {
        if (service.currentInputConnection == null) return false
        // 先清掉 fcitx 的预编辑（commitText 会替换输入框里的预编辑文字）
        service.postFcitxJob { reset() }
        service.commitText(text)
        return true
    }

    override fun replaceLastCommit(committed: String, replacement: String): Boolean {
        val ic = service.currentInputConnection ?: return false
        if (committed.isEmpty()) return false
        if (!ic.getSelectedText(0).isNullOrEmpty()) return false
        val before = ic.getTextBeforeCursor(committed.length, 0)?.toString() ?: return false
        if (before != committed) return false
        ic.beginBatchEdit()
        try {
            ic.deleteSurroundingText(committed.length, 0)
            if (replacement.isNotEmpty()) ic.commitText(replacement, 1)
        } finally {
            ic.endBatchEdit()
        }
        return true
    }

    override fun showStatus(state: VoiceStatus) {
        ensureStatusView()
        statusView?.show(state)
    }

    override fun hideStatus() {
        statusView?.hide()
    }

    override fun showCommitActions(onAction: (CommitAction) -> Unit) =
        showCommitActions(CommitAction.entries.toSet(), onAction)

    override fun showCommitActions(actions: Set<CommitAction>, onAction: (CommitAction) -> Unit) {
        statusView?.showActions(actions, onAction)
    }

    override fun hapticFeedback() {
        (statusView ?: inputView).performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    // ---------------------------------------------------------------- 内部

    /** 状态条盖在键盘顶部 40dp（工具栏 / 候选栏的位置）上，不改动工具栏本身 */
    private fun ensureStatusView() {
        if (statusView != null || disposed) return
        val parent = inputView.keyboardView as? ConstraintLayout ?: return
        val v = VoiceStatusView(context, theme, ThemeManager.prefs.keyBorder.getValue())
        v.onWindowHidden = { _controller?.onEditorChanged() }
        v.onOpenUmless = { m ->
            val page = if (m.text == VoiceTexts.t("voice.model_missing")) UmlessGlue.PAGE_MODELS
            else UmlessGlue.PAGE_PERMISSION
            UmlessGlue.openUmless(service, page)
        }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}

            // InputView 被替换（换主题、改设置）时整个视图树被移除：取消进行中的工作
            override fun onViewDetachedFromWindow(view: View) = dispose()
        })
        parent.addView(v, ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            parent.dp(KawaiiBarComponent.HEIGHT)
        ).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        })
        statusView = v
    }

    /**
     * 键盘显示时预加载识别模型（30 秒内只触发一次），但只在最近 [RECENT_USE_MS] 内用过语音时：
     * 模型约占 250MB，常驻会让系统更容易杀掉其他应用；不常用语音的人不为它付出内存。
     */
    private fun preload(info: EditorInfo, capFlags: CapabilityFlags) {
        val facts = EditorFacts.from(info) ?: return
        if (facts.isPassword || capFlags.has(CapabilityFlag.Password)) return
        if (!UmlessGlue.spaceLongPressEnabled && !UmlessGlue.toolbarButtonVisible(facts, false)) return
        val now = SystemClock.elapsedRealtime()
        if (lastVoiceAt == 0L || now - lastVoiceAt > RECENT_USE_MS) return
        if (now - lastPreloadAt < PRELOAD_INTERVAL_MS) return
        preloadNow()
    }

    private fun preloadNow() {
        lastPreloadAt = SystemClock.elapsedRealtime()
        val client = UmlessGlue.client(service)
        scope.launch {
            try {
                client.preload()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    private fun dispose() {
        if (disposed) return
        disposed = true
        _controller?.dispose()
        scope.cancel()
        if (current?.get() === this) current = null
    }

    companion object {
        private const val PRELOAD_INTERVAL_MS = 30_000L
        private const val RECENT_USE_MS = 15 * 60_000L

        /** 最近一次开始说话的时间（进程内共享，InputView 重建后仍有效） */
        @Volatile
        private var lastVoiceAt = 0L

        private fun editorKey(info: EditorInfo) = Triple(info.packageName, info.fieldId, info.inputType)

        @Volatile
        private var lastPreloadAt = 0L

        @Volatile
        private var current: WeakReference<UmlessVoiceComponent>? = null

        /**
         * 空格键的触摸钩子（BaseKeyboard 里 setOnTouchListener 调用，返回 false 不拦截事件）。
         * 长按触发后上游不再分发 Move，这里直接拿原始事件判断上滑与松开。
         */
        @JvmStatic
        fun onSpaceTouch(v: View, e: MotionEvent) {
            current?.get()?.takeIf { !it.disposed }?.handleSpaceTouch(v, e)
        }

        /** 工具栏麦克风按钮是否显示（KawaiiBarComponent.onStartInput） */
        fun toolbarButtonVisible(info: EditorInfo, capFlags: CapabilityFlags): Boolean =
            UmlessGlue.ready && UmlessGlue.toolbarButtonVisible(EditorFacts.from(info), capFlags.has(CapabilityFlag.Password))
    }
}
