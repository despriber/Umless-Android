package org.umless.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.umless.api.ApiException
import java.io.File

class ConfigTest {
    private lateinit var platform: TestPlatform

    @Before
    fun setUp() {
        platform = TestEnv.setup()
    }

    private fun patch(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    private fun assertRejected(key: String, p: JsonObject) {
        try {
            UmlessConfig.savePatch(p)
            fail("expected rejection: $p")
        } catch (e: ApiException) {
            val prefix = I18n.t(key).substringBefore("{").substringBefore("：")
            assertTrue("${e.message} should start with $prefix", e.message!!.startsWith(prefix))
        }
    }

    @Test
    fun defaults() {
        val c = UmlessConfig.get()
        assertEquals(
            listOf("app", "audio", "asr", "llm", "polish", "scenes", "coach", "privacy", "glossary", "voice", "update"),
            c.keys.toList(),
        )
        assertEquals("", UmlessConfig.str("llm.base_url"))
        assertEquals("", UmlessConfig.str("llm.model"))
        assertEquals(20, UmlessConfig.int("llm.timeout_s"))
        assertEquals(0.2, UmlessConfig.double("llm.temperature"), 0.0)
        assertFalse(UmlessConfig.llmEnabled) // 没有接口地址
        assertEquals("sensevoice", UmlessConfig.asrEngine)
        assertEquals(4, UmlessConfig.numThreads)
        assertTrue(UmlessConfig.spaceLongPress && UmlessConfig.toolbarButton && UmlessConfig.haptic && UmlessConfig.skipIncognito)
        assertTrue(UmlessConfig.recordHistory)
        assertFalse(UmlessConfig.keepAudio)
        assertEquals(300, UmlessConfig.minHoldMs)
        // Android 配置没有桌面专属项
        for (k in listOf("hotkey", "output", "ui")) assertFalse(k in c)
        assertFalse("mic_always_on" in (c["audio"] as JsonObject))
        assertFalse("keep_audio" in (c["audio"] as JsonObject))
        assertEquals(setOf("space_long_press", "toolbar_button", "haptic", "min_hold_ms"), (c["voice"] as JsonObject).keys)

        assertEquals("chat", UmlessConfig.sceneOf("com.tencent.mm"))
        assertEquals("chat", UmlessConfig.sceneOf("COM.TENCENT.MM"))
        assertEquals("document", UmlessConfig.sceneOf("md.obsidian"))
        assertEquals("code", UmlessConfig.sceneOf("com.termux"))
        assertEquals("email", UmlessConfig.sceneOf("com.google.android.gm"))
        assertEquals("default", UmlessConfig.sceneOf("com.android.chrome"))
        assertEquals("default", UmlessConfig.sceneOf(null))
        assertEquals("moderate", UmlessConfig.intensityOf("chat"))
        assertEquals("restructure", UmlessConfig.intensityOf("document"))
        assertEquals("restructure", UmlessConfig.intensityOf("email"))
        assertEquals("faithful", UmlessConfig.intensityOf("search"))
        assertTrue(UmlessConfig.isExcluded("com.x8bit.bitwarden"))
        assertTrue(UmlessConfig.isExcluded("COM.ICBC"))
        assertTrue(UmlessConfig.isExcluded("com.eg.android.AlipayGphone"))
        assertFalse(UmlessConfig.isExcluded("com.tencent.mm"))
        assertFalse(UmlessConfig.isExcluded(null))
    }

    @Test
    fun savePatchMergesAndPersists() {
        var notified: JsonObject? = null
        UmlessConfig.addListener { notified = it }
        val out = UmlessConfig.savePatch(patch {
            put("llm", buildJsonObject { put("base_url", "https://api.deepseek.com/v1"); put("model", "deepseek-v4-flash") })
            put("scenes", buildJsonObject {
                put("intensity", buildJsonObject { put("chat", "faithful") })
                put("rules", buildJsonArray { add(buildJsonObject { put("app", "com.example.notes"); put("scene", "document") }) })
            })
            put("audio", buildJsonObject { put("volume", 40.0) })
            put("unknown_section", buildJsonObject { put("x", 1) })
        })
        assertEquals(out, notified)
        assertTrue(UmlessConfig.llmEnabled)
        assertEquals("deepseek-v4-flash", UmlessConfig.str("llm.model"))
        assertEquals(20, UmlessConfig.int("llm.timeout_s")) // 其他键保留默认
        assertEquals("faithful", UmlessConfig.intensityOf("chat"))
        assertEquals("restructure", UmlessConfig.intensityOf("document")) // 场景强度部分覆盖时其余补默认
        assertEquals("document", UmlessConfig.sceneOf("com.example.notes"))
        assertEquals("default", UmlessConfig.sceneOf("com.tencent.mm")) // 列表整体替换
        assertEquals(JsonPrimitive(40), UmlessConfig.path("audio.volume"))
        assertFalse("unknown_section" in UmlessConfig.get())

        // 写盘：文件里保留补丁，未知键也保留（与桌面端一样不丢用户写的内容）
        val file = File(platform.home, "config.json")
        val raw = Json.parseToJsonElement(file.readText()) as JsonObject
        assertTrue("unknown_section" in raw)
        assertFalse(File(file.path + ".tmp").exists())

        // 重新初始化后读回
        Umless.init(platform)
        assertEquals("deepseek-v4-flash", UmlessConfig.str("llm.model"))
        assertEquals("faithful", UmlessConfig.intensityOf("chat"))

        // scenes.enabled = false
        UmlessConfig.savePatch(patch { put("scenes", buildJsonObject { put("enabled", false) }) })
        assertEquals("default", UmlessConfig.sceneOf("com.example.notes"))
        assertEquals("moderate", UmlessConfig.intensityOf("chat"))
        assertEquals("faithful", UmlessConfig.intensityOf("search"))
    }

    @Test
    fun validation() {
        assertRejected("cfg.bad_format", patch { put("app", "x") })
        assertRejected("cfg.bad_format", patch { put("voice", JsonArray(emptyList())) })
        assertRejected("cfg.bad_language", patch { put("app", buildJsonObject { put("language", "fr") }) })
        assertRejected("cfg.bad_intensity", patch { put("polish", buildJsonObject { put("intensity", "max") }) })
        assertRejected("cfg.bad_scene_intensity", patch {
            put("scenes", buildJsonObject { put("intensity", buildJsonObject { put("game", "faithful") }) })
        })
        assertRejected("cfg.bad_scene_intensity", patch {
            put("scenes", buildJsonObject { put("intensity", buildJsonObject { put("chat", "loud") }) })
        })
        assertRejected("cfg.bad_rule", patch {
            put("scenes", buildJsonObject { put("rules", buildJsonArray { add(buildJsonObject { put("app", ""); put("scene", "chat") }) }) })
        })
        assertRejected("cfg.bad_rule", patch {
            put("scenes", buildJsonObject { put("rules", buildJsonArray { add(buildJsonObject { put("app", "a"); put("scene", "game") }) }) })
        })
        assertRejected("cfg.bad_engine", patch { put("asr", buildJsonObject { put("engine", "qwen3_asr") }) })
        assertRejected("cfg.bad_retention", patch { put("privacy", buildJsonObject { put("retention_days", -1) }) })
        assertRejected("cfg.bad_retention", patch { put("privacy", buildJsonObject { put("retention_days", 1.5) }) })
        assertRejected("cfg.bad_hold_ms", patch { put("voice", buildJsonObject { put("min_hold_ms", 99) }) })
        assertRejected("cfg.bad_hold_ms", patch { put("voice", buildJsonObject { put("min_hold_ms", 1501) }) })
        assertRejected("cfg.bad_hold_ms", patch { put("voice", buildJsonObject { put("min_hold_ms", 300.5) }) })
        assertRejected("cfg.bad_volume", patch { put("audio", buildJsonObject { put("volume", 101) }) })
        assertRejected("cfg.bad_volume", patch { put("audio", buildJsonObject { put("volume", true) }) })
        assertRejected("cfg.bad_sound_event", patch { put("audio", buildJsonObject { put("sounds", buildJsonObject { put("boom", "rise") }) }) })
        assertRejected("cfg.bad_sound", patch { put("audio", buildJsonObject { put("sounds", buildJsonObject { put("start", "bang") }) }) })

        // 合法值
        UmlessConfig.savePatch(patch {
            put("voice", buildJsonObject { put("min_hold_ms", 100) })
            put("asr", buildJsonObject { put("engine", "openai") })
            put("scenes", buildJsonObject {
                put("intensity", buildJsonObject { put("search", ""); put("chat", "") })
                put("rules", buildJsonArray { add(buildJsonObject { put("app", "com.android.chrome"); put("scene", "search") }) })
            })
            put("audio", buildJsonObject { put("sounds", buildJsonObject { put("start", " pop ") }) })
        })
        assertEquals(100, UmlessConfig.minHoldMs)
        assertEquals("openai", UmlessConfig.asrEngine)
        assertEquals("search", UmlessConfig.sceneOf("com.android.chrome"))
        assertEquals("pop", UmlessConfig.str("audio.sounds.start"))
        assertEquals("fall", UmlessConfig.str("audio.sounds.stop"))
    }

    @Test
    fun brokenFileFallsBackToDefaults() {
        File(platform.home, "config.json").writeText("{not json")
        Umless.init(platform)
        assertEquals("auto", UmlessConfig.str("app.language"))
        assertNull(UmlessConfig.path("nope.nope"))
    }

    @Test
    fun languageResolution() {
        platform.lang = "en"
        assertEquals("en", Umless.language())
        UmlessConfig.savePatch(patch { put("app", buildJsonObject { put("language", "zh") }) })
        assertEquals("zh", Umless.language())
        assertEquals("任务不存在", I18n.t("job.not_found"))
        UmlessConfig.savePatch(patch { put("app", buildJsonObject { put("language", "en") }) })
        assertEquals("Task not found", I18n.t("job.not_found"))
        assertEquals("Listening", I18n.t("voice.listening"))
        assertEquals("missing.key", I18n.t("missing.key"))
        assertEquals("Only 2 scoreable records that day; at least 3 are needed", I18n.t("coach.too_few", "n" to 2, "min" to 3))
        // 占位符缺参数时整句不替换（同 Python）
        assertEquals("Only {n} scoreable records that day; at least {min} are needed", I18n.t("coach.too_few", "n" to 2))
    }

    @Test
    fun i18nDictionariesConsistent() {
        assertEquals(I18n.zhKeys, I18n.enKeys)
        val ph = Regex("""\{(\w+)\}""")
        for (k in I18n.zhKeys) {
            assertEquals(k, ph.findAll(I18n.tr("zh", k)).map { it.value }.toSet(), ph.findAll(I18n.tr("en", k)).map { it.value }.toSet())
        }
        val voice = mapOf(
            "voice.listening" to ("正在聆听" to "Listening"),
            "voice.release_to_cancel" to ("松开取消" to "Release to cancel"),
            "voice.slide_to_cancel" to ("上滑取消" to "Slide up to cancel"),
            "voice.transcribing" to ("识别中" to "Transcribing"),
            "voice.polishing" to ("润色中" to "Polishing"),
            "voice.inserted" to ("已输入" to "Inserted"),
            "voice.no_speech" to ("未识别到内容" to "Didn't catch that"),
            "voice.model_missing" to ("请先在 Umless 中下载识别模型" to "Download the speech model in Umless first"),
            "voice.mic_permission" to ("请先在 Umless 中允许使用麦克风" to "Allow microphone access in Umless first"),
            "voice.mic_failed" to ("无法打开麦克风" to "Can't open the microphone"),
            "voice.polish_failed" to ("润色失败，已输入原文" to "Polish failed, raw text inserted"),
            "voice.polish_timeout" to ("润色超时，已输入原文" to "Polishing timed out, raw text inserted"),
            "voice.password_field" to ("密码框不支持语音输入" to "Voice input isn't available in password fields"),
            "voice.too_short" to ("按住说话，松开结束" to "Hold to talk, release to finish"),
            "voice.use_raw" to ("原话" to "Original"),
            "voice.undo" to ("撤销" to "Undo"),
            "voice.failed" to ("识别失败" to "Recognition failed"),
        )
        for ((k, v) in voice) {
            assertEquals(v.first, I18n.tr("zh", k))
            assertEquals(v.second, I18n.tr("en", k))
        }
    }

    @Test
    fun logExportMasksKeys() {
        UmlessConfig.savePatch(patch { put("llm", buildJsonObject { put("api_key", "sk-secret-123"); put("base_url", "https://x/v1") }) })
        UmlessLog.i("Test", "hello log")
        val txt = File(platform.home, "diag.txt")
        UmlessLog.export(txt)
        val content = txt.readText()
        assertTrue(content.contains("hello log"))
        assertTrue(content.contains("***"))
        assertFalse(content.contains("sk-secret-123"))
        val zip = File(platform.home, "diag.zip")
        UmlessLog.export(zip)
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.containsAll(listOf("info.txt", "config.json", "umless.log")))
    }
}
