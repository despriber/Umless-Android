/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.ThemeManager.activeTheme
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.isDarkMode
import org.umless.glue.UmlessThemes

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    val BuiltinThemes = listOf(
        // Umless: 默认配色
        UmlessThemes.Light,
        UmlessThemes.Dark,
        ThemePreset.MaterialLight,
        ThemePreset.MaterialDark,
        ThemePreset.PixelLight,
        ThemePreset.PixelDark,
        ThemePreset.NordLight,
        ThemePreset.NordDark,
        ThemePreset.DeepBlue,
        ThemePreset.Monokai,
        ThemePreset.AMOLEDBlack,
    )

    // Umless: 默认主题
    val DefaultTheme = UmlessThemes.Light

    private var monetThemes = listOf(ThemeMonet.getLight(), ThemeMonet.getDark())

    private val customThemes: MutableList<Theme.Custom> = ThemeFilesManager.listThemes()

    fun getTheme(name: String) =
        customThemes.find { it.name == name } ?: BuiltinThemes.find { it.name == name }

    fun getAllThemes() = customThemes + monetThemes + BuiltinThemes

    // Umless: 用户所选主题暂时找不到时重新读取自定义主题（ManagedThemePreference.getValue 调用），
    // 频率与次数见 UmlessThemes.shouldReload；本进程里删掉的主题不再读取
    private var reloadAttempts = 0
    private var lastReloadAt = SystemClock.elapsedRealtime()
    private val deletedThemeNames = mutableSetOf<String>()

    fun reloadForMissingTheme(name: String): Theme? {
        val now = SystemClock.elapsedRealtime()
        if (!UmlessThemes.shouldReload(name, deletedThemeNames, reloadAttempts, lastReloadAt, now)) return null
        reloadAttempts++
        lastReloadAt = now
        customThemes.clear()
        customThemes.addAll(ThemeFilesManager.listThemes())
        val found = getAllThemes().find { it.name == name }
        UmlessThemes.onReloadForMissing(name, found != null, reloadAttempts)
        return found
    }

    /** Umless: 键盘显示时调用：正在用的是临时默认主题（用户的主题当时没读出来）就再试一次 */
    fun retryMissingTheme() {
        if (!::_activeTheme.isInitialized) return
        val name = currentThemePreference().persistedName() ?: return
        if (_activeTheme.name == name) return
        activeTheme = evaluateActiveTheme()
    }

    fun refreshThemes() {
        customThemes.clear()
        customThemes.addAll(ThemeFilesManager.listThemes())
        activeTheme = evaluateActiveTheme()
    }

    /**
     * [backing property](https://kotlinlang.org/docs/properties.html#backing-properties)
     * of [activeTheme]; holds the [Theme] object currently in use
     */
    private lateinit var _activeTheme: Theme

    var activeTheme: Theme
        get() = _activeTheme
        private set(value) {
            if (_activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private var isDarkMode = false

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onThemeChange(_activeTheme) }
    }

    val prefs = AppPrefs.getInstance().registerProvider(::ThemePrefs)

    fun saveTheme(theme: Theme.Custom) {
        ThemeFilesManager.saveThemeFiles(theme)
        // Umless: 见 reloadForMissingTheme
        deletedThemeNames.remove(theme.name)
        customThemes.indexOfFirst { it.name == theme.name }.also {
            if (it >= 0) customThemes[it] = theme else customThemes.add(0, theme)
        }
        if (activeTheme.name == theme.name) {
            activeTheme = theme
        }
    }

    fun deleteTheme(name: String) {
        customThemes.find { it.name == name }?.also {
            ThemeFilesManager.deleteThemeFiles(it)
            customThemes.remove(it)
            // Umless: 见 reloadForMissingTheme
            deletedThemeNames.add(name)
        }
        if (activeTheme.name == name) {
            activeTheme = evaluateActiveTheme()
        }
    }

    fun setNormalModeTheme(theme: Theme) {
        // `normalModeTheme.setValue(theme)` would trigger `onThemePrefsChange` listener,
        // which calls `fireChange()`.
        // `activateTheme`'s setter would also trigger `fireChange()` when theme actually changes.
        // write to backing property directly to avoid unnecessary `fireChange()`
        _activeTheme = theme
        prefs.normalModeTheme.setValue(theme)
    }

    // Umless: 拆出当前生效的主题设置项（retryMissingTheme 也用）
    fun currentThemePreference(): ManagedThemePreference =
        if (prefs.followSystemDayNightTheme.getValue()) {
            if (isDarkMode) prefs.darkModeTheme else prefs.lightModeTheme
        } else {
            prefs.normalModeTheme
        }

    private fun evaluateActiveTheme(): Theme = currentThemePreference().getValue()

    @Keep
    private val onThemePrefsChange = ManagedPreferenceProvider.OnChangeListener { key ->
        if (prefs.dayNightModePrefNames.contains(key)) {
            activeTheme = evaluateActiveTheme()
        } else {
            fireChange()
        }
    }

    fun init(configuration: Configuration) {
        isDarkMode = configuration.isDarkMode()
        // fire all `OnThemeChangedListener`s on theme preferences change
        prefs.registerOnChangeListener(onThemePrefsChange)
        _activeTheme = evaluateActiveTheme()
    }

    fun onSystemPlatteChange(newConfig: Configuration) {
        isDarkMode = newConfig.isDarkMode()
        monetThemes = listOf(ThemeMonet.getLight(), ThemeMonet.getDark())
        // `ManagedThemePreference` finds a theme with same name in `getAllThemes()`
        // thus `evaluateActiveTheme()` should be called after updating `monetThemes`
        activeTheme = evaluateActiveTheme()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            prefs.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }

}