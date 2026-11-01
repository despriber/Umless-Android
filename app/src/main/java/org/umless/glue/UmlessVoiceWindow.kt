package org.umless.glue

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.mechdancer.dependency.manager.must
import org.umless.ime.VoiceController
import org.umless.ime.VoiceController.State
import org.umless.ime.VoiceTexts
import splitties.dimensions.dp

/**
 * 工具栏麦克风按钮打开的语音面板：大按钮按住说话（与长按空格同一个 VoiceController，上滑取消），
 * 上方显示最近一次的原话与润色结果。返回键 / 标题栏返回按钮回到键盘。
 */
class UmlessVoiceWindow : InputWindow.ExtendedInputWindow<UmlessVoiceWindow>() {

    private val theme: Theme by manager.theme()
    private val voice: UmlessVoiceComponent by manager.must()

    override val title: String
        get() = context.getString(R.string.umless_voice)

    private val danger get() = if (theme.isDark) 0xfff47a7a.toInt() else 0xffc93636.toInt()

    private lateinit var hintText: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var rawText: TextView
    private lateinit var polishedLabel: TextView
    private lateinit var polishedText: TextView
    private lateinit var stateText: TextView
    private lateinit var button: FrameLayout
    private lateinit var buttonBg: GradientDrawable

    private var pressing = false
    private var downY = 0f

    private val listener: (State) -> Unit = { render(it) }

    override fun onCreateView(): View {
        val ctx = context
        hintText = TextView(ctx).apply {
            text = ctx.getString(R.string.umless_voice_panel_hint)
            textSize = 14f
            setTextColor(theme.altKeyTextColor)
        }
        rawText = body()
        polishedText = body()
        polishedLabel = label(ctx.getString(R.string.umless_voice_polished))
        resultBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(ctx.getString(R.string.umless_voice_raw)))
            addView(rawText)
            addView(polishedLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(polishedText)
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(hintText)
                addView(resultBox)
            })
        }
        stateText = TextView(ctx).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(theme.altKeyTextColor)
        }
        buttonBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.accentKeyBackgroundColor)
        }
        button = FrameLayout(ctx).apply {
            background = buttonBg
            contentDescription = ctx.getString(R.string.umless_voice_hold_to_talk)
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
                imageTintList = ColorStateList.valueOf(theme.accentKeyTextColor)
            }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
            setupTouch(this)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(stateText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(button, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
            })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch(v: View) {
        v.setOnTouchListener { view, e ->
            val c = voice.controller
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    downY = e.y
                    pressing = c.press(VoiceController.Source.Panel)
                }
                MotionEvent.ACTION_MOVE -> if (pressing) c.drag(e.y - downY, view.dp(60f))
                MotionEvent.ACTION_UP -> if (pressing) {
                    pressing = false
                    c.release()
                }
                MotionEvent.ACTION_CANCEL -> if (pressing) {
                    pressing = false
                    c.cancelIfListening(VoiceController.Source.Panel)
                }
            }
            true
        }
    }

    override fun onAttached() {
        val c = voice.controller
        c.addListener(listener)
        render(c.state)
    }

    override fun onDetached() {
        val c = voice.controller
        c.removeListener(listener)
        pressing = false
        c.cancelIfListening(VoiceController.Source.Panel)
    }

    private fun render(s: State) {
        if (!::button.isInitialized) return
        val listening = s as? State.Listening
        val busy = s is State.Transcribing || s is State.Polishing
        buttonBg.setColor(if (listening?.cancelArmed == true) danger else theme.accentKeyBackgroundColor)
        val scale = if (listening != null) 1.12f else 1f
        button.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        button.alpha = if (busy) 0.5f else 1f
        stateText.setTextColor(if (listening?.cancelArmed == true) danger else theme.altKeyTextColor)
        stateText.text = when (s) {
            is State.Listening -> if (s.cancelArmed) VoiceTexts.t("voice.release_to_cancel")
            else "${VoiceTexts.t("voice.listening")} · ${VoiceTexts.t("voice.slide_to_cancel")}"
            State.Transcribing -> VoiceTexts.t("voice.transcribing")
            State.Polishing -> VoiceTexts.t("voice.polishing")
            is State.Message -> s.text
            else -> context.getString(R.string.umless_voice_hold_to_talk)
        }
        val r = (s as? State.Committed)?.result ?: UmlessGlue.lastResult
        if (r == null) {
            hintText.visibility = View.VISIBLE
            resultBox.visibility = View.GONE
        } else {
            hintText.visibility = View.GONE
            resultBox.visibility = View.VISIBLE
            rawText.text = r.raw
            polishedText.text = r.polished ?: context.getString(R.string.umless_voice_not_polished)
            polishedText.alpha = if (r.polished == null) 0.6f else 1f
        }
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(theme.altKeyTextColor)
    }

    private fun body() = TextView(context).apply {
        textSize = 15f
        setTextColor(theme.keyTextColor)
        setTextIsSelectable(false)
        setPadding(0, dp(2), 0, 0)
    }
}
