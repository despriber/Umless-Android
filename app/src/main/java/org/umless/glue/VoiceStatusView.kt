package org.umless.glue

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.umless.ime.CommitAction
import org.umless.ime.VoiceStatus
import org.umless.ime.VoiceTexts
import splitties.dimensions.dp
import kotlin.math.max

/**
 * 键盘顶部（候选栏位置）的语音状态条，盖在工具栏上方：
 * 聆听时显示声波 + 计时 + 「上滑取消 / 松开取消」；识别 / 润色时显示转圈；提示自动消失；写入后显示「原话 / 撤销」。
 * 颜色取自当前 fcitx 主题，浅色 / 深色都协调。
 */
@SuppressLint("ViewConstructor")
class VoiceStatusView(context: Context, theme: Theme, keyBorder: Boolean) : LinearLayout(context) {

    /** 键盘窗口隐藏（onFinishInputView 后整个输入法窗口不可见） */
    var onWindowHidden: (() -> Unit)? = null

    /** 点击带 openUmless 的提示 */
    var onOpenUmless: ((VoiceStatus.Message) -> Unit)? = null

    private val accent = theme.accentKeyBackgroundColor
    private val danger = if (theme.isDark) 0xfff47a7a.toInt() else 0xffc93636.toInt()
    private val textColor = theme.candidateTextColor
    private val subColor = theme.candidateCommentColor
    private val chipBg = theme.keyBackgroundColor
    private val chipText = theme.keyTextColor

    private val wave = WaveformView(context).apply { color = accent }

    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(accent)
    }

    private val indicator = FrameLayout(context).apply {
        addView(wave, FrameLayout.LayoutParams(dp(44), dp(24), Gravity.CENTER))
        addView(spinner, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
    }

    private val title = TextView(context).apply {
        textSize = 14f
        setTextColor(textColor)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val hint = TextView(context).apply {
        textSize = 12f
        setTextColor(subColor)
        maxLines = 1
    }

    private val timer = TextView(context).apply {
        textSize = 13f
        setTextColor(subColor)
        fontFeatureSettings = "tnum"
    }

    private val chips = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val hideRunnable = Runnable { hide() }
    private var message: VoiceStatus.Message? = null
    private var listening = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(if (keyBorder) theme.backgroundColor else theme.barColor)
        setPadding(dp(8), 0, dp(8), 0)
        visibility = GONE
        // 盖住下面的候选栏 / 工具栏，避免误触
        isClickable = true
        addView(indicator, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        addView(timer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        addView(chips, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        setOnClickListener {
            val m = message ?: return@setOnClickListener
            if (m.openUmless) {
                hide()
                onOpenUmless?.invoke(m)
            }
        }
    }

    fun show(s: VoiceStatus) {
        removeCallbacks(hideRunnable)
        visibility = VISIBLE
        when (s) {
            is VoiceStatus.Listening -> {
                if (!listening) wave.reset()
                listening = true
                message = null
                chips.visibility = GONE
                indicator.visibility = VISIBLE
                wave.visibility = VISIBLE
                spinner.visibility = GONE
                val c = if (s.cancelArmed) danger else accent
                wave.color = c
                wave.push(s.level)
                title.text = VoiceTexts.t(if (s.cancelArmed) "voice.release_to_cancel" else "voice.listening")
                title.setTextColor(if (s.cancelArmed) danger else textColor)
                hint.text = VoiceTexts.t("voice.slide_to_cancel")
                hint.visibility = if (s.cancelArmed) GONE else VISIBLE
                timer.visibility = VISIBLE
                timer.text = formatElapsed(s.elapsedMs)
            }
            VoiceStatus.Transcribing, VoiceStatus.Polishing -> {
                listening = false
                message = null
                chips.visibility = GONE
                indicator.visibility = VISIBLE
                wave.visibility = GONE
                spinner.visibility = VISIBLE
                title.text = VoiceTexts.t(if (s == VoiceStatus.Polishing) "voice.polishing" else "voice.transcribing")
                title.setTextColor(textColor)
                hint.visibility = GONE
                timer.visibility = GONE
            }
            is VoiceStatus.Message -> {
                listening = false
                message = s
                chips.visibility = GONE
                indicator.visibility = GONE
                title.text = if (s.openUmless) "${s.text}  ›" else s.text
                title.setTextColor(if (s.isError) danger else textColor)
                hint.visibility = GONE
                timer.visibility = GONE
                postDelayed(hideRunnable, if (s.isError) ERROR_MS else MESSAGE_MS)
            }
        }
    }

    /** 「原话 / 撤销」；由 VoiceController 在约 4 秒后收起，这里另设一个兜底的自动隐藏 */
    fun showActions(actions: Set<CommitAction>, onAction: (CommitAction) -> Unit) {
        removeCallbacks(hideRunnable)
        visibility = VISIBLE
        chips.removeAllViews()
        for (a in CommitAction.entries) {
            if (a !in actions) continue
            val label = VoiceTexts.t(if (a == CommitAction.UseRaw) "voice.use_raw" else "voice.undo")
            chips.addView(chip(label) { onAction(a) }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(28)).apply {
                marginStart = dp(8)
            })
        }
        chips.visibility = VISIBLE
        postDelayed(hideRunnable, ACTIONS_FALLBACK_MS)
    }

    fun hide() {
        removeCallbacks(hideRunnable)
        visibility = GONE
        message = null
        listening = false
        chips.removeAllViews()
        wave.reset()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            hide()
            onWindowHidden?.invoke()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRunnable)
        super.onDetachedFromWindow()
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(chipText)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(chipBg)
        }
        setOnClickListener { onClick() }
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val MESSAGE_MS = 2000L
        private const val ERROR_MS = 3500L
        private const val ACTIONS_FALLBACK_MS = 6000L
    }
}

/** 声波：最近若干次音量画成竖条，最新的在最右 */
class WaveformView(context: Context) : View(context) {
    var color: Int = 0xff0f766e.toInt()
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@WaveformView.color }
    private val levels = FloatArray(BARS)
    private val rect = RectF()
    private val barWidth = dp(3f)
    private val gap = dp(2f)

    fun push(level: Float) {
        System.arraycopy(levels, 1, levels, 0, BARS - 1)
        levels[BARS - 1] = level.coerceIn(0f, 1f)
        invalidate()
    }

    fun reset() {
        levels.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val total = BARS * barWidth + (BARS - 1) * gap
        var x = (width - total) / 2f
        val cy = height / 2f
        for (l in levels) {
            val h = max(barWidth, height * (0.15f + 0.85f * l))
            rect.set(x, cy - h / 2, x + barWidth, cy + h / 2)
            canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint)
            x += barWidth + gap
        }
    }

    companion object {
        private const val BARS = 9
    }
}
