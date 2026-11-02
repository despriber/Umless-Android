/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
import androidx.core.content.edit
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference

class ManagedThemePreference(
    sharedPreferences: SharedPreferences,
    key: String,
    defaultValue: Theme,
) : ManagedPreference<Theme>(
    sharedPreferences, key, defaultValue
) {

    override fun setValue(value: Theme) {
        sharedPreferences.edit { putString(key, value.name) }
    }

    override fun getValue(): Theme =
        sharedPreferences.getString(key, null)?.let { name ->
            ThemeManager.getAllThemes().find { it.name == name }
                // Umless: 用户选的主题暂时找不到（如更新 / 开机后自定义主题还读不出来）时重新读取一次；
                // 仍找不到只临时用默认主题，不改写已保存的选择
                ?: ThemeManager.reloadForMissingTheme(name)
        } ?: defaultValue

    /** Umless: 已保存的主题名，从未选过时为 null */
    fun persistedName(): String? = sharedPreferences.getString(key, null)

    override fun putValueTo(editor: SharedPreferences.Editor) {
        // Umless: 原样复制已保存的主题名，主题暂时找不到时不要写成默认主题
        editor.putString(key, persistedName() ?: getValue().name)
    }

}