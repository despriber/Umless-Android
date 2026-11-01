package org.umless.glue

import org.fcitx.fcitx5.android.data.theme.Theme

/**
 * Umless 键盘配色，取自桌面端网页界面的设计令牌（desktop/umless/ui/web/css/tokens.css）：
 * sage 暖中性灰阶（带一点绿）+ teal 主色。浅色：底 #eef1f0、按键 #ffffff、文字 #17201c、主色 #0f766e；
 * 深色：底 #181b1a、按键 #2c3230、文字 #eceeed、主色 #2dd4bf。
 * 已加入上游内置主题列表，并设为默认（ThemeManager / ThemePrefs）。
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
}
