package org.umless.glue

import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.umless.core.Umless
import org.umless.core.UmlessLog
import timber.log.Timber

/**
 * Umless 键盘配色，取自桌面端网页界面的设计令牌（desktop/umless/ui/web/css/tokens.css）：
 * sage 暖中性灰阶（带一点绿）+ teal 主色。浅色：底 #eef1f0、按键 #ffffff、文字 #17201c、主色 #0f766e；
 * 深色：底 #181b1a、按键 #2c3230、文字 #eceeed、主色 #2dd4bf。
 * 已加入上游内置主题列表，并设为默认（ThemeManager / ThemePrefs）。
 *
 * 另外负责「用户所选主题暂时找不到」时的重试规则与诊断日志：上游按主题名保存选择，启动时只读一次自定义主题，
 * 读不出来（更新 / 开机后外部存储还没就绪等）就整个进程都用默认主题。现在只临时用默认主题，不改写保存的选择，
 * 之后再读取（ThemeManager.reloadForMissingTheme / retryMissingTheme）。
 */
object UmlessThemes {

    val Light = Theme.Builtin(
        name = "UmlessLight",
        isDark = false,
        backgroundColor = 0xffeef1f0,       // --bg-base
        barColor = 0xffe6ebe9,
        keyboardColor = 0xffeef1f0,
        keyBackgroundColor = 0xffffffff,    // --surface
        keyTextColor = 0xff17201c,          // --text
        candidateTextColor = 0xff17201c,
        candidateLabelColor = 0xff17201c,
        candidateCommentColor = 0xff7a837f, // --text-3
        altKeyBackgroundColor = 0xffdde3e0,
        altKeyTextColor = 0xff7a837f,
        accentKeyBackgroundColor = 0xff0f766e, // --accent-strong
        accentKeyTextColor = 0xffffffff,       // --on-accent
        keyPressHighlightColor = 0x1f10241e,
        keyShadowColor = 0xffc3cbc7,
        popupBackgroundColor = 0xffdde3e0,
        popupTextColor = 0xff17201c,
        spaceBarColor = 0xffcfd7d3,
        dividerColor = 0x1f10241e,          // --line
        clipboardEntryColor = 0xffffffff,
        genericActiveBackgroundColor = 0xff7fd0c3, // --heat-2
        genericActiveForegroundColor = 0xff17201c
    )

    val Dark = Theme.Builtin(
        name = "UmlessDark",
        isDark = true,
        backgroundColor = 0xff181b1a,       // --bg-layer
        barColor = 0xff141716,
        keyboardColor = 0xff181b1a,
        keyBackgroundColor = 0xff2c3230,    // --surface-3
        keyTextColor = 0xffeceeed,          // --text
        candidateTextColor = 0xffeceeed,
        candidateLabelColor = 0xffeceeed,
        candidateCommentColor = 0xff838c88, // --text-3
        altKeyBackgroundColor = 0xff222725,
        altKeyTextColor = 0xffaab3af,       // --text-2
        accentKeyBackgroundColor = 0xff2dd4bf, // --accent-strong
        accentKeyTextColor = 0xff042f2e,       // --on-accent
        keyPressHighlightColor = 0x33ffffff,
        keyShadowColor = 0xff0c0e0d,
        popupBackgroundColor = 0xff3a413e,  // --seg-active
        popupTextColor = 0xffeceeed,
        spaceBarColor = 0xff3a413e,
        dividerColor = 0x1fffffff,
        clipboardEntryColor = 0xff2c3230,
        genericActiveBackgroundColor = 0xff22a596, // --heat-3
        genericActiveForegroundColor = 0xff042f2e
    )

    /** 两次重新读取自定义主题至少间隔（毫秒） */
    const val RELOAD_INTERVAL_MS = 3_000L

    /** 每个进程最多重新读取几次（主题确实被删掉时不要一直读） */
    const val MAX_RELOADS = 20

    /** 是否重新读取自定义主题：本进程里删掉的不读；距上次至少 [RELOAD_INTERVAL_MS]；最多 [MAX_RELOADS] 次 */
    fun shouldReload(name: String, deleted: Set<String>, attempts: Int, lastAt: Long, now: Long): Boolean =
        name !in deleted && attempts < MAX_RELOADS && now - lastAt >= RELOAD_INTERVAL_MS

    private const val TAG = "Theme"

    private fun log(msg: String) {
        if (Umless.isInitialized) UmlessLog.w(TAG, msg) else Timber.w(msg)
    }

    /** ThemeManager.reloadForMissingTheme 之后调用 */
    fun onReloadForMissing(name: String, found: Boolean, attempt: Int) {
        log(
            if (found) "已重新读取到所选主题 $name（第 $attempt 次）"
            else "所选主题 $name 仍找不到，暂用默认主题（第 $attempt 次）；主题目录 ${ThemeFilesManager.describeDir()}"
        )
    }

    /** Umless 初始化后记录一次主题状态，方便从导出的日志判断主题为什么变了 */
    fun logStartupState() {
        try {
            val p = ThemeManager.prefs
            val active = ThemeManager.activeTheme.name
            val msg = "主题：当前 $active，跟随系统 ${p.followSystemDayNightTheme.getValue()}，" +
                    "已保存 normal=${p.normalModeTheme.persistedName()} light=${p.lightModeTheme.persistedName()} " +
                    "dark=${p.darkModeTheme.persistedName()}，期望 ${ThemeManager.currentThemePreference().persistedName()}；" +
                    "主题目录 ${ThemeFilesManager.describeDir()}"
            if (Umless.isInitialized) UmlessLog.i(TAG, msg) else Timber.i(msg)
        } catch (t: Throwable) {
            Timber.w(t, "log theme state failed")
        }
    }
}
