package org.umless.ui

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Umless 键盘的状态：已在系统设置里启用 / 已切换为当前输入法 */
data class ImeState(val enabled: Boolean, val selected: Boolean) {
    fun toJson(): JsonObject = buildJsonObject {
        put("enabled", enabled)
        put("selected", selected)
    }

    /**
     * Android 上 core_status 的含义（桌面端是托盘常驻进程）：running = 键盘已启用；
     * ready 还要求已切换为当前输入法、且识别模型可用；没有热键，输出方式固定为直接写入输入框
     */
    fun coreStatus(modelReady: Boolean): JsonObject = buildJsonObject {
        put("running", enabled)
        put("ready", enabled && selected && modelReady)
        put("hotkey", JsonNull)
        put("mode", "commit")
    }

    companion object {
        /** 我们的输入法就是本应用包内的 FcitxInputMethodService，按包名判断，不写死上游类名 */
        fun of(context: Context): ImeState {
            val pkg = context.packageName
            val imm = context.getSystemService<InputMethodManager>()
            val enabled = imm?.enabledInputMethodList.orEmpty().any { it.packageName == pkg }
            val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            return ImeState(enabled, enabled && isOurs(current, pkg))
        }

        /** DEFAULT_INPUT_METHOD 形如 org.umless.ime/org.fcitx.fcitx5.android.input.FcitxInputMethodService */
        fun isOurs(defaultInputMethod: String?, packageName: String): Boolean {
            if (defaultInputMethod.isNullOrBlank()) return false
            val slash = defaultInputMethod.indexOf('/')
            return slash > 0 && defaultInputMethod.substring(0, slash).trim() == packageName
        }
    }
}
