package org.umless.glue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.data.theme.CustomThemeSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UmlessThemesTest {

    private val interval = UmlessThemes.RELOAD_INTERVAL_MS

    @Test
    fun reloadRules() {
        // 启动时刚读过：间隔内不重读，过了间隔再读
        assertFalse(UmlessThemes.shouldReload("a", emptySet(), 0, lastAt = 1_000, now = 1_000 + interval - 1))
        assertTrue(UmlessThemes.shouldReload("a", emptySet(), 0, lastAt = 1_000, now = 1_000 + interval))
        // 本进程里删掉的主题不再读
        assertFalse(UmlessThemes.shouldReload("a", setOf("a"), 0, lastAt = 0, now = 10 * interval))
        assertTrue(UmlessThemes.shouldReload("b", setOf("a"), 0, lastAt = 0, now = 10 * interval))
        // 次数上限
        assertTrue(UmlessThemes.shouldReload("a", emptySet(), UmlessThemes.MAX_RELOADS - 1, 0, 10 * interval))
        assertFalse(UmlessThemes.shouldReload("a", emptySet(), UmlessThemes.MAX_RELOADS, 0, 10 * interval))
    }

    @Test
    fun builtinNamesAreStable() {
        // 设置里按主题名保存所选主题，改名会让已保存的选择失效
        assertEquals("UmlessLight", UmlessThemes.Light.name)
        assertEquals("UmlessDark", UmlessThemes.Dark.name)
    }

    /**
     * 0.1.2 / 0.1.3 写下的带背景图自定义主题文件。字段名来自编译期生成的序列化器（正式包还有 -dontobfuscate），
     * 不随 R8 变化；这里锁定格式，防止以后改动让旧文件读不出来
     */
    private val savedByOldVersion = """
        {"name":"5f0c3c1e-3a4b-4c8e-9a51-0d1b0f2e7c11","isDark":false,
         "backgroundImage":{"croppedFilePath":"/storage/emulated/0/Android/data/org.umless.ime/files/theme/5f0c3c1e-3a4b-4c8e-9a51-0d1b0f2e7c11-cropped.png",
           "srcFilePath":"/storage/emulated/0/Android/data/org.umless.ime/files/theme/5f0c3c1e-3a4b-4c8e-9a51-0d1b0f2e7c11-src.jpg",
           "brightness":60,"cropRect":null,"cropRotation":90},
         "backgroundColor":-14540254,"barColor":1275068416,"keyboardColor":0,"keyBackgroundColor":1275068415,
         "keyTextColor":-1,"candidateTextColor":-1,"candidateLabelColor":-1,"candidateCommentColor":-905969665,
         "altKeyBackgroundColor":218103807,"altKeyTextColor":-905969665,"accentKeyBackgroundColor":-10577930,
         "accentKeyTextColor":-1,"keyPressHighlightColor":520093696,"keyShadowColor":0,
         "popupBackgroundColor":-13158601,"popupTextColor":-1,"spaceBarColor":1275068415,"dividerColor":536870911,
         "clipboardEntryColor":855638015,"genericActiveBackgroundColor":-10577930,"genericActiveForegroundColor":-1,
         "version":"2.1"}
    """.trimIndent()

    @Test
    fun customThemeFileFromOldVersionLoads() {
        val (theme, migrated) = Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, savedByOldVersion)
        assertFalse(migrated)
        assertEquals("5f0c3c1e-3a4b-4c8e-9a51-0d1b0f2e7c11", theme.name)
        assertNotNull(theme.backgroundImage)
        val bg = theme.backgroundImage!!
        assertTrue(bg.croppedFilePath.endsWith("-cropped.png"))
        assertEquals(60, bg.brightness)
        assertEquals(90, bg.cropRotation)
        // 写回去的字段名与旧文件完全一致
        val old = Json.parseToJsonElement(savedByOldVersion).jsonObject
        val written = Json.parseToJsonElement(Json.encodeToString(CustomThemeSerializer, theme)).jsonObject
        assertEquals(old.keys, written.keys)
        assertEquals(old["backgroundImage"]!!.jsonObject.keys, written["backgroundImage"]!!.jsonObject.keys)
        assertEquals(theme, Json.decodeFromString(CustomThemeSerializer, written.toString()))
    }
}
