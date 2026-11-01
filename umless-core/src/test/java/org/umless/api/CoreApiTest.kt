package org.umless.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.umless.core.CoreHooks
import org.umless.core.FakeLlm
import org.umless.core.I18n
import org.umless.core.Records
import org.umless.core.TestEnv
import org.umless.core.TestPlatform
import org.umless.core.Umless
import org.umless.core.UmlessConfig
import org.umless.core.UtteranceRecord
import org.umless.llm.LlmClient
import java.io.File

class CoreApiTest {
    private lateinit var platform: TestPlatform

    @Before
    fun setUp() {
        platform = TestEnv.setup()
    }

    private fun call(name: String, vararg args: JsonElement): JsonElement = runBlocking { UmlessApi.call(name, JsonArray(args.toList())) }

    private fun str(s: String) = JsonPrimitive(s)

    private fun expectApiError(name: String, vararg args: JsonElement): String {
        try {
            call(name, *args)
        } catch (e: Exception) {
            return e.message!!
        }
        fail("expected error from $name")
        throw AssertionError()
    }

    private fun waitJob(job: JsonElement): JsonObject {
        val id = ((job as JsonObject)["job_id"] as JsonPrimitive).content
        repeat(500) {
            val s = call("job_status", str(id)) as JsonObject
            if (s["state"] != JsonPrimitive("running")) return s
            Thread.sleep(10)
        }
        error("job still running")
    }

    @Test
    fun registersAllPlatformIndependentMethods() {
        val expected = setOf(
            "get_app_info", "get_last_output", "list_sounds", "get_overview", "list_reports", "get_report", "run_diagnosis",
            "get_trend", "get_habits", "archive_habit", "restore_habit", "add_term", "add_filler", "get_practice_topics",
            "generate_topics", "list_practice", "get_practice", "list_history", "delete_history", "get_settings",
            "save_settings", "test_llm", "list_llm_models", "get_lexicon", "remove_filler", "toggle_filler", "export_data",
            "clear_data", "finish_onboarding", "job_status", "get_nav", "copy_text",
        )
        assertTrue(UmlessApi.names().containsAll(expected))
        // 平台相关的方法由主 App 模块注册
        for (n in listOf("get_ime_status", "download_model", "mic_test", "practice_start", "export_logs", "preview_sound")) {
            assertFalse(n, UmlessApi.has(n))
        }
        assertTrue(expectApiError("nope").contains("nope"))
    }

    @Test
    fun appInfoAndSettings() {
        val info = call("get_app_info") as JsonObject
        assertEquals(
            listOf("version", "onboarded", "tour_done", "theme", "language", "language_setting", "hotkey", "data_dir", "core_running", "platform"),
            info.keys.toList(),
        )
        assertEquals(str("android"), info["platform"])
        assertEquals(str(""), info["hotkey"])
        assertEquals(JsonPrimitive(true), info["core_running"])
        assertEquals(str("auto"), info["language_setting"])
        assertEquals(str("zh"), info["language"])

        CoreHooks.modelsInfo = { buildJsonObject { put("sensevoice", buildJsonObject { put("downloaded", true); put("size_mb", 166) }) } }
        val s = call("get_settings") as JsonObject
        assertEquals(listOf("config", "models", "providers", "autostart"), s.keys.toList())
        assertEquals(JsonPrimitive(false), s["autostart"])
        assertTrue("sensevoice" in (s["models"] as JsonObject))
        assertEquals(5, (s["providers"] as JsonArray).size)
        assertEquals(str("硅基流动"), ((s["providers"] as JsonArray)[2] as JsonObject)["name"])
        CoreHooks.modelsInfo = { buildJsonObject {} }

        val saved = call("save_settings", buildJsonObject { put("voice", buildJsonObject { put("haptic", false) }) }) as JsonObject
        assertEquals(JsonPrimitive(false), ((saved["config"] as JsonObject)["voice"] as JsonObject)["haptic"])
        assertFalse(UmlessConfig.haptic)
        assertEquals(I18n.t("cfg.bad_format"), expectApiError("save_settings", str("x")))
        assertEquals(I18n.t("cfg.bad_engine", "engine" to "qwen3_asr"),
            expectApiError("save_settings", buildJsonObject { put("asr", buildJsonObject { put("engine", "qwen3_asr") }) }))

        call("finish_onboarding")
        assertEquals(JsonPrimitive(true), (call("get_app_info") as JsonObject)["onboarded"])
        val sounds = call("list_sounds") as JsonObject
        assertEquals(4, (sounds["events"] as JsonArray).size)
    }

    @Test
    fun lastOutputLexiconAndTerms() {
        assertEquals(JsonNull, call("get_last_output"))
        Records.saveLastOutputOnly("嗯我明天想请假", "我明天想请假", "我明天想请假")
        val out = call("get_last_output") as JsonObject
        assertEquals(listOf("text", "raw", "highlights", "filler_count", "at"), out.keys.toList())
        assertEquals(Json.parseToJsonElement("[[\"嗯\", \"filler\"], [\"我明天想请假\", \"keep\"]]"), out["highlights"])
        assertEquals(JsonPrimitive(1), out["filler_count"])

        // 停用「嗯」后标注随词表更新
        call("toggle_filler", str("嗯"), JsonPrimitive(false))
        assertEquals(JsonPrimitive(0), (call("get_last_output") as JsonObject)["filler_count"])
        call("remove_filler", str("嗯")) // 内置词只能停用
        val lex = (call("get_lexicon") as JsonArray).map { it as JsonObject }
        assertEquals(JsonPrimitive(false), lex.first { it["word"] == str("嗯") }["enabled"])
        call("add_filler", str(" 我觉得吧 "))
        assertTrue((call("get_lexicon") as JsonArray).any { (it as JsonObject)["word"] == str("我觉得吧") && it["source"] == str("user") })
        call("remove_filler", str("我觉得吧"))
        assertFalse((call("get_lexicon") as JsonArray).any { (it as JsonObject)["word"] == str("我觉得吧") })
        assertEquals(I18n.t("api.filler_empty"), expectApiError("add_filler", str(" ")))
        assertEquals(I18n.t("api.filler_too_long"), expectApiError("add_filler", str("一二三四五六七八九")))

        call("add_term", str("Claude Code"))
        call("add_term", str("claude code"))
        assertEquals(listOf("Claude Code"), UmlessConfig.glossary)
        assertEquals(I18n.t("api.term_empty"), expectApiError("add_term", JsonNull))
    }

    @Test
    fun historyNavCopyExportClear() {
        TestEnv.now = TestEnv.now.minusHours(1)
        val id = Records.save(UtteranceRecord(5.0, "com.tencent.mm", "sensevoice", "嗯我们明天下午三点开会然后记得带上电脑", 100, "我们明天下午三点开会，记得带上电脑。",
            polishedText = "我们明天下午三点开会，记得带上电脑。", scene = "chat", intensity = "moderate"), true)!!
        val h = call("list_history", str(""), JsonPrimitive(0), JsonPrimitive(30)) as JsonObject
        assertEquals(JsonPrimitive(1), h["total"])
        val item = (h["items"] as JsonArray)[0] as JsonObject
        assertEquals(
            listOf("id", "created_at", "duration_s", "app", "scene", "intensity", "output_text", "raw_text", "highlights",
                "filler_count", "repeat_count", "correction_count", "fluency", "rewrite_ratio", "asr_ms", "llm_ms"),
            item.keys.toList(),
        )
        assertEquals(JsonPrimitive(1), (call("list_history", JsonNull, JsonNull, JsonNull) as JsonObject)["total"])
        assertEquals(JsonPrimitive(0), (call("list_history", str("不存在的词"), JsonPrimitive(0), JsonPrimitive(30)) as JsonObject)["total"])

        val overview = call("get_overview") as JsonObject
        assertEquals(JsonPrimitive(1), (overview["today"] as JsonObject)["count"])
        val reports = call("list_reports") as JsonObject
        assertEquals(JsonPrimitive(1), (reports["today"] as JsonObject)["n"])
        assertNotNull(call("get_report", str(TestEnv.now.toLocalDate().toString())) as? JsonObject)
        assertEquals(1, (call("get_trend", JsonPrimitive(7)) as JsonArray).size)
        assertEquals(JsonArray(emptyList()), call("get_habits", JsonNull))

        // 导航（键盘请求打开设置）
        assertEquals(JsonNull, call("get_nav"))
        CoreApi.requestNav("settings/keyboard")
        assertEquals(str("settings/keyboard"), call("get_nav"))
        assertEquals(JsonNull, call("get_nav"))

        call("copy_text", str("复制我"))
        assertEquals(listOf("复制我"), platform.copied)

        val path = ((call("export_data") as JsonObject)["path"] as JsonPrimitive).content
        val exported = Json.parseToJsonElement(File(path).readText()) as JsonObject
        assertEquals(listOf("app", "version", "exported_at", "utterances", "metrics", "lexicon", "diagnoses", "habits", "practice"), exported.keys.toList())
        assertEquals(1, (exported["utterances"] as JsonArray).size)
        assertTrue(File(path).name.startsWith("umless-export-"))

        call("delete_history", JsonPrimitive(id))
        assertEquals(JsonPrimitive(0), (call("list_history", str(""), JsonPrimitive(0), JsonPrimitive(30)) as JsonObject)["total"])
        call("clear_data")
        assertEquals(JsonNull, call("get_last_output"))
        assertEquals(I18n.t("api.practice_not_found"), expectApiError("get_practice", JsonPrimitive(99)))
        assertEquals(I18n.t("api.habit_not_found"), expectApiError("restore_habit", JsonPrimitive(99)))
    }

    @Test
    fun jobsThroughApi() {
        // 没有大模型：出题用内置题目
        val topics = waitJob(call("generate_topics", JsonPrimitive(false)))
        assertEquals(str("done"), topics["state"])
        assertEquals(5, (((topics["result"] as JsonObject)["topics"]) as JsonArray).size)
        assertEquals(topics["result"], call("get_practice_topics"))
        // 已有今天的题目且不要求刷新：直接返回
        assertEquals(topics["result"], waitJob(call("generate_topics", JsonPrimitive(false)))["result"])

        val diag = waitJob(call("run_diagnosis", str("2026-03-09")))
        assertEquals(str("error"), diag["state"])
        assertEquals(str(I18n.t("coach.too_few", "n" to 0, "min" to 3)), diag["error"])

        val missing = call("job_status", str("nope")) as JsonObject
        assertEquals(str(I18n.t("job.not_found")), missing["error"])
    }

    @Test
    fun testLlmAndModels() {
        val bad = runBlocking { CoreApi.testLlm(buildJsonObject { put("base_url", "ftp://x") }) }
        assertEquals(JsonPrimitive(false), bad["ok"])
        assertEquals(str(I18n.t("api.url_scheme")), bad["error"])
        assertEquals(str(I18n.t("api.test_sentence")), bad["input"])

        val fake = FakeLlm { url, _ ->
            if (url.endsWith("/models")) org.umless.llm.HttpTransport.Response(200, "{\"data\": [{\"id\": \"z\"}, {\"id\": \"m\"}]}")
            else FakeLlm.content("我明天想请个假去医院复查一下")
        }
        LlmClient.transport = fake
        val ok = call("test_llm", buildJsonObject { put("base_url", " https://api.x/v1 "); put("api_key", "k"); put("model", "m") }) as JsonObject
        assertEquals(JsonPrimitive(true), ok["ok"])
        assertEquals(str("我明天想请个假去医院复查一下"), ok["output"])
        assertEquals(JsonNull, ok["error"])
        assertEquals("https://api.x/v1/chat/completions", fake.requests[0].first)
        assertEquals(JsonArray(listOf(str("m"), str("z"))), call("list_llm_models", str("https://api.x/v1"), str("k")))
    }
}
