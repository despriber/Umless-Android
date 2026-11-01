package org.umless.coach

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.umless.api.ApiException
import org.umless.core.FakeLlm
import org.umless.core.I18n
import org.umless.core.Records
import org.umless.core.TestEnv
import org.umless.core.Umless
import org.umless.core.UmlessConfig
import org.umless.core.UtteranceRecord
import org.umless.llm.HttpTransport
import org.umless.llm.LlmClient
import java.time.LocalDateTime

class CoachFlowTest {
    @Before
    fun setUp() {
        TestEnv.setup()
        UmlessConfig.savePatch(buildJsonObject {
            put("llm", buildJsonObject { put("base_url", "https://llm.example/v1"); put("model", "polish-m") })
            put("coach", buildJsonObject { put("model", "coach-m") })
        })
    }

    private val raw = "嗯我们明天下午三点开会然后记得带上电脑就是这样"
    private val polished = "我们明天下午三点开会，记得带上电脑。"

    private fun say(at: LocalDateTime, rawText: String = raw, polishedText: String? = polished, app: String = "com.tencent.mm"): Long? {
        TestEnv.now = at
        return Records.save(
            UtteranceRecord(
                durationS = 6.0, app = app, asrEngine = "sensevoice", rawText = rawText, asrMs = 300,
                outputText = polishedText ?: rawText, llmModel = "polish-m", polishedText = polishedText, llmMs = 800,
                scene = UmlessConfig.sceneOf(app), intensity = "moderate",
            ),
            record = true,
        )
    }

    private val diagnosisJson = """
        {"summary": "整体清楚，但「然后」偏多。", "scores": {"logic": 3.5, "clarity": "4"},
         "strengths": ["先说结论", " ", "有条理"],
         "issues": [{"title": "「然后」作为填充词", "type": "filler", "evidence": ["然后记得带上电脑"], "advice": "停顿代替"},
                    {"title": "未知类型", "type": "weird", "evidence": [], "advice": ""}, {"type": "filler"}],
         "examples": [{"original": "嗯我们明天", "better": "我们明天", "why": "去掉口癖"}, {"original": "缺 better"}],
         "personal_fillers": ["这样子", "嗯"],
         "terms": [{"wrong": "cloud code", "right": "Claude Code"}],
         "habits": [{"kind": "word", "word": "然后", "title": "", "advice": "想停就停", "evidence": ["然后记得"]},
                    {"kind": "logic", "word": null, "title": "先说结论", "advice": "结论先行", "evidence": []},
                    {"kind": "bogus"}]}
    """.trimIndent()

    @Test
    fun diagnosisCreatesReportAndHabits(): Unit = runBlocking {
        for (h in 0 until 5) say(LocalDateTime.of(2026, 3, 9, 9 + h, 0, 0))
        TestEnv.now = LocalDateTime.of(2026, 3, 10, 10, 0, 0)
        val fake = FakeLlm { _, _ -> FakeLlm.content("```json\n$diagnosisJson\n```") }
        LlmClient.transport = fake

        assertEquals("2026-03-09", Coach.dueAutoDiagnosis())
        val progress = mutableListOf<String?>()
        val report = Coach.diagnose("2026-03-09") { _, m -> progress.add(m) }!!
        assertEquals(listOf(I18n.t("coach.collecting"), I18n.t("coach.analyzing"), I18n.t("coach.making_habits")), progress)

        // 请求：coach 模型、JSON 模式、取样的原话
        val (url, body) = fake.requests.single()
        assertEquals("https://llm.example/v1/chat/completions", url)
        assertEquals(JsonPrimitive("coach-m"), body!!["model"])
        assertEquals(JsonPrimitive(0.3), body["temperature"])
        assertNotNull(body["response_format"])
        val user = ((body["messages"] as JsonArray)[1] as JsonObject)["content"]!!.jsonPrimitive.content
        assertTrue(user.startsWith("日期：2026-03-09，共 5 条可评分的语音输入。"))
        assertTrue(user.contains("1. （com.tencent.mm）$raw"))

        // 报告
        assertEquals(JsonPrimitive(true), report["diagnosed"])
        assertEquals(JsonPrimitive(5), report["n"])
        assertEquals(JsonPrimitive("coach-m"), report["model"])
        val scores = report["scores"] as JsonObject
        assertEquals(JsonPrimitive(70), scores["logic"])
        assertEquals(JsonPrimitive(80), scores["clarity"])
        assertEquals(2, (report["strengths"] as JsonArray).size)
        val issues = report["issues"] as JsonArray
        assertEquals(2, issues.size)
        assertEquals(JsonPrimitive("wording"), (issues[1] as JsonObject)["type"])
        assertEquals(1, (report["examples"] as JsonArray).size)
        // 已在词表里的「嗯」被去掉
        assertEquals(listOf("这样子"), (report["personal_fillers"] as JsonArray).map { ((it as JsonObject)["word"] as JsonPrimitive).content })
        assertEquals(JsonPrimitive(false), ((report["terms"] as JsonArray)[0] as JsonObject)["added"])
        val stored = Umless.db.query("SELECT * FROM diagnoses")
        assertEquals(1, stored.size)
        assertEquals(70.0, stored[0].double("logic")!!, 0.0)
        val expectedOverall = Coach.overallScore(stored[0].double("fluency"), stored[0].double("concision"), 70.0, 80.0)
        assertEquals(expectedOverall!!, stored[0].double("overall")!!, 0.0)

        // 习惯卡：word:然后 与 logic；bogus 被丢弃
        val habits = Coach.getHabits("active")
        assertEquals(listOf("logic", "word:然后"), habits.map { ((it as JsonObject)["key"] as JsonPrimitive).content })
        val word = habits.map { it as JsonObject }.first { it["kind"] == JsonPrimitive("word") }
        assertEquals(JsonPrimitive(I18n.t("habit.word", "word" to "然后")), word["title"]) // 标题为空时用默认
        val baseline = (word["baseline"] as JsonPrimitive).content.toDouble()
        assertEquals(org.umless.core.Py.round(baseline / 2, 2), (word["target"] as JsonPrimitive).content.toDouble(), 0.0)
        assertEquals(JsonPrimitive("次/百字"), word["unit"])
        val logic = habits.map { it as JsonObject }.first { it["kind"] == JsonPrimitive("logic") }
        assertEquals(JsonPrimitive(70.0), logic["baseline"])
        assertEquals(JsonPrimitive(90.0), logic["target"])

        assertNull(Coach.dueAutoDiagnosis()) // 已诊断

        // 概览、报告列表、趋势、历史
        val overview = Coach.getOverview()
        assertEquals(JsonPrimitive(70), (overview["score"] as JsonObject)["logic"])
        assertEquals(JsonPrimitive("2026-03-09"), (overview["latest_diagnosis"] as JsonObject)["date"])
        assertEquals(2, (overview["habits"] as JsonArray).size)
        assertEquals(5, (overview["recent"] as JsonArray).size)
        val reports = Coach.listReports()
        assertEquals(1, (reports["items"] as JsonArray).size)
        val trend = Coach.getTrend(30)
        assertEquals(1, trend.size)
        assertEquals(JsonPrimitive(true), (trend[0] as JsonObject)["diagnosed"])
        val history = Coach.listHistory("开会", 0, 2)
        assertEquals(JsonPrimitive(5), history["total"])
        assertEquals(2, (history["items"] as JsonArray).size)
        val item = (history["items"] as JsonArray)[0] as JsonObject
        assertEquals(JsonPrimitive("chat"), item["scene"])
        assertTrue((item["filler_count"] as JsonPrimitive).content.toInt() >= 2)

        // 未诊断的日期
        val today = Coach.getReport("2026-03-10")
        assertNull(today) // 没有数据
        val empty = Coach.getReport("2026-03-01")
        assertNull(empty)

        // 4 天后：说了很多不带「然后」的话，习惯达成
        for (d in 10..12) for (h in 0 until 4) say(LocalDateTime.of(2026, 3, d, 9 + h, 0, 0), "我们明天下午三点开会记得带上电脑和充电器好吗", null)
        TestEnv.now = LocalDateTime.of(2026, 3, 13, 20, 0, 0)
        Coach.refreshHabits()
        val achieved = Coach.getHabits("achieved").map { ((it as JsonObject)["key"] as JsonPrimitive).content }
        assertEquals(listOf("word:然后"), achieved)
        assertEquals(JsonPrimitive(1.0), (Coach.getHabits("achieved")[0] as JsonObject)["progress"])
        Coach.setHabitStatus((Coach.getHabits("active")[0] as JsonObject)["id"]!!.jsonPrimitive.content.toLong(), "archived")
        assertEquals(1, Coach.getHabits("archived").size)
        assertEquals(2, Coach.getHabits(null).size)
    }

    @Test
    fun tooFewSamplesAndNoLlm(): Unit = runBlocking {
        say(LocalDateTime.of(2026, 3, 9, 9, 0, 0))
        try {
            Coach.diagnose("2026-03-09")
            fail()
        } catch (e: CoachError) {
            assertEquals(I18n.t("coach.too_few", "n" to 1, "min" to 3), e.message)
        }
        for (h in 1 until 4) say(LocalDateTime.of(2026, 3, 9, 9 + h, 0, 0))
        UmlessConfig.savePatch(buildJsonObject { put("llm", buildJsonObject { put("base_url", "") }) })
        try {
            Coach.diagnose("2026-03-09")
            fail()
        } catch (e: CoachError) {
            assertEquals(I18n.t("coach.no_llm"), e.message)
        }
        assertNull(Coach.dueAutoDiagnosis()) // 没有接口地址不自动诊断
    }

    @Test
    fun autoDiagnosisRetriesAndLimits(): Unit = runBlocking {
        for (h in 0 until 5) say(LocalDateTime.of(2026, 3, 9, 9 + h, 0, 0))
        TestEnv.now = LocalDateTime.of(2026, 3, 10, 8, 0, 0)
        LlmClient.transport = FakeLlm { _, _ -> HttpTransport.Response(500, "down") }
        assertNull(Coach.runAutoDiagnosisIfDue()) // 第 1 次失败
        assertNull(Coach.dueAutoDiagnosis()) // 1 小时内不重试
        TestEnv.now = TestEnv.now.plusMinutes(61)
        assertEquals("2026-03-09", Coach.dueAutoDiagnosis())
        assertNull(Coach.runAutoDiagnosisIfDue()) // 第 2 次
        TestEnv.now = TestEnv.now.plusMinutes(61)
        assertNull(Coach.runAutoDiagnosisIfDue()) // 第 3 次
        TestEnv.now = TestEnv.now.plusMinutes(61)
        assertNull(Coach.dueAutoDiagnosis()) // 同一天最多 3 次
        val attempt = Umless.db.getKv("auto_diagnosis") as JsonObject
        assertEquals(JsonPrimitive(3), attempt["count"])

        // 第二天诊断前一天成功
        for (h in 0 until 5) say(LocalDateTime.of(2026, 3, 10, 12 + h, 0, 0))
        TestEnv.now = LocalDateTime.of(2026, 3, 11, 8, 0, 0)
        LlmClient.transport = FakeLlm { _, _ -> FakeLlm.content(diagnosisJson) }
        val report = Coach.runAutoDiagnosisIfDue()
        assertNotNull(report)
        assertEquals(JsonPrimitive("2026-03-10"), report!!["date"])
        assertNull(Coach.runAutoDiagnosisIfDue())
    }

    @Test
    fun retentionPurge(): Unit = runBlocking {
        say(LocalDateTime.of(2026, 1, 1, 9, 0, 0))
        say(LocalDateTime.of(2026, 3, 9, 9, 0, 0))
        UmlessConfig.savePatch(buildJsonObject { put("privacy", buildJsonObject { put("retention_days", 30) }) })
        TestEnv.now = LocalDateTime.of(2026, 3, 10, 8, 0, 0)
        Coach.runAutoDiagnosisIfDue()
        assertEquals(1, Umless.db.query("SELECT * FROM utterances").size)
    }

    @Test
    fun recordsPrivacy() {
        val excluded = say(LocalDateTime.of(2026, 3, 10, 9, 0, 0), app = "com.x8bit.bitwarden")
        assertNull(excluded)
        assertNull(Umless.db.getKv("last_output"))
        assertNull(Records.save(UtteranceRecord(1.0, "a", "sensevoice", "  ", 1, ""), true)) // 空原文

        // record=false：什么都不写（包括 last_output）
        assertNull(Records.save(UtteranceRecord(2.0, "com.tencent.mm", "sensevoice", "嗯你好", 10, "你好", polishedText = "你好"), record = false))
        assertNull(Umless.db.getKv("last_output"))
        assertEquals(0, Umless.db.query("SELECT * FROM utterances").size)

        // Umless 自己界面里的试说：只写 last_output
        Records.saveLastOutputOnly("嗯你好", "你好", "你好")
        val last = Umless.db.getKv("last_output") as JsonObject
        assertEquals(JsonPrimitive("你好"), last["text"])
        assertEquals(JsonPrimitive("嗯你好"), last["raw"])
        assertEquals(JsonPrimitive("你好"), last["polished"])
        assertEquals(0, Umless.db.query("SELECT * FROM utterances").size)

        UmlessConfig.savePatch(buildJsonObject { put("privacy", buildJsonObject { put("record_history", false) }) })
        assertNull(say(LocalDateTime.of(2026, 3, 10, 9, 0, 0)))
        UmlessConfig.savePatch(buildJsonObject { put("privacy", buildJsonObject { put("record_history", true) }) })
        val id = say(LocalDateTime.of(2026, 3, 10, 9, 0, 0))!!
        val m = Umless.db.query("SELECT * FROM metrics WHERE utterance_id = ?", id).single()
        assertEquals(1L, m.long("scoreable"))
        val u = Umless.db.query("SELECT * FROM utterances WHERE id = ?", id).single()
        assertEquals("2026-03-10T09:00:00", u.str("created_at"))
        assertEquals("chat", u.str("scene"))
        // 日志里没有原文
        val log = Umless.home.resolve("logs/umless.log").readText()
        assertFalse(log.contains(raw))
        assertTrue(log.contains("原文 ${org.umless.core.Py.len(raw)} 字"))
    }

    @Test
    fun backfillRecomputesWhenLexiconChanges() {
        val id = say(LocalDateTime.of(2026, 3, 10, 9, 0, 0))!!
        assertEquals(1, Coach.backfillMetrics()) // 首次：没有 metrics_hash，全部重算
        assertEquals(0, Coach.backfillMetrics()) // 词表未变、指标齐全
        Umless.db.addWords(listOf("这样"), "user")
        assertEquals(1, Coach.backfillMetrics())
        val fillers = Umless.db.query("SELECT fillers FROM metrics WHERE utterance_id = ?", id)[0].str("fillers")!!
        assertTrue(fillers.contains("这样"))
    }

    @Test
    fun practiceEvaluate(): Unit = runBlocking {
        val topic = buildJsonObject { put("id", "t1"); put("title", "汇报本周进展"); put("category", "工作汇报"); put("duration", 60) }
        val rawText = "嗯这周主要是把登录模块做完了然后下周开始做支付相关的功能"
        LlmClient.transport = FakeLlm { _, body ->
            val user = ((body!!["messages"] as JsonArray)[1] as JsonObject)["content"]!!.jsonPrimitive.content
            assertTrue(user.startsWith("题目：{\"title\": \"汇报本周进展\"}\n用时：62 秒（建议 60 秒）"))
            FakeLlm.content(
                """{"clean_text": "这周主要把登录模块做完了，下周开始做支付相关的功能。", "scores": {"structure": 3.5, "logic": 4, "wording": 3},
                   "strengths": ["结论清楚"], "improvements": ["补充风险"], "outline": ["结论：登录完成"], "better_version": "示范"}"""
            )
        }
        val progress = mutableListOf<Float?>()
        val r = org.umless.core.PracticeEval.evaluate(topic, rawText, 62.5, { p, _ -> progress.add(p) }) as JsonObject
        assertEquals(listOf(0.35f), progress)
        val scores = r["scores"] as JsonObject
        assertEquals(JsonPrimitive(70), scores["structure"])
        assertEquals(JsonPrimitive(80), scores["logic"])
        assertEquals(JsonPrimitive(60), scores["wording"])
        assertEquals(setOf("id", "created_at", "topic", "category", "duration_s", "raw_text", "overall", "scores", "strengths",
            "improvements", "outline", "better_version", "highlights", "filler_count", "cpm"), r.keys)
        assertEquals(JsonPrimitive("汇报本周进展"), r["topic"])
        assertEquals(JsonPrimitive(62.5), r["duration_s"])
        assertEquals(JsonPrimitive(2), r["filler_count"])
        val m = Metrics.analyze(rawText, "这周主要把登录模块做完了，下周开始做支付相关的功能。", 62.5, Coach.lexiconWords())
        val llm = buildJsonObject { put("structure", 3.5); put("logic", 4); put("wording", 3) }
        val (expectedScores, overall) = Practice.practiceScores(llm, m.fluency, m.concision)
        assertEquals(expectedScores, scores)
        assertEquals(JsonPrimitive(overall), r["overall"])
        val list = Practice.listPractice(20)
        assertEquals(1, list.size)
        assertEquals(setOf("id", "created_at", "topic", "category", "duration_s", "overall"), (list[0] as JsonObject).keys)

        // LLM 失败：只有规则分，改进建议说明原因
        LlmClient.transport = FakeLlm { _, _ -> HttpTransport.Response(500, "down") }
        val r2 = org.umless.core.PracticeEval.evaluate(buildJsonObject { put("title", "") }, rawText, 10.0, { _, _ -> }) as JsonObject
        assertEquals(JsonNull, (r2["scores"] as JsonObject)["structure"])
        assertTrue(((r2["improvements"] as JsonArray)[0] as JsonPrimitive).content.startsWith(I18n.t("practice.llm_failed", "error" to "").substringBefore("（")))
        assertEquals(JsonPrimitive(I18n.t("practice.free_topic")), r2["topic"])
        assertEquals(JsonPrimitive(I18n.t("practice.category_custom")), r2["category"])

        try {
            org.umless.core.PracticeEval.evaluate(topic, rawText, 2.0, { _, _ -> })
            fail()
        } catch (e: ApiException) {
            assertEquals(I18n.t("practice.too_short"), e.message)
        }
        try {
            org.umless.core.PracticeEval.evaluate(topic, " ", 20.0, { _, _ -> })
            fail()
        } catch (e: ApiException) {
            assertEquals(I18n.t("practice.no_speech"), e.message)
        }
    }

    @Test
    fun topicsFallbackAndLlm(): Unit = runBlocking {
        UmlessConfig.savePatch(buildJsonObject { put("llm", buildJsonObject { put("base_url", "") }) })
        val data = Practice.generateTopics()
        val topics = data["topics"] as JsonArray
        assertEquals(5, topics.size)
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), topics.map { ((it as JsonObject)["id"] as JsonPrimitive).content })
        assertTrue(topics.all { (it as JsonObject)["duration"] == JsonPrimitive(60) })
        assertEquals(data, Practice.getTopics("zh"))
        assertNull(Practice.getTopics("en")) // 界面语言变了：视为没有
        TestEnv.now = TestEnv.now.plusDays(1)
        assertNull(Practice.getTopics("zh")) // 不是今天的

        UmlessConfig.savePatch(buildJsonObject {
            put("llm", buildJsonObject { put("base_url", "https://llm.example/v1") })
            put("app", buildJsonObject { put("language", "en") })
        })
        say(TestEnv.now.minusHours(1), "我们这周把登录模块做完了下周开始做支付相关的功能", null)
        val fake = FakeLlm { _, _ ->
            FakeLlm.content("""{"topics": [{"title": "Explain your login module", "category": "Project intro", "prompt": "p", "hint": "h", "duration": 90, "personal": true},
                {"category": "no title"}, {"title": "Status update", "duration": "90"}]}""")
        }
        LlmClient.transport = fake
        val gen = Practice.generateTopics()
        val t = (gen["topics"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("t1", "t3"), t.map { (it["id"] as JsonPrimitive).content })
        assertEquals(JsonPrimitive(90), t[0]["duration"])
        assertEquals(JsonPrimitive(60), t[1]["duration"]) // 字符串 "90" 不算
        assertEquals(JsonPrimitive("General"), t[1]["category"])
        assertEquals(JsonPrimitive(true), t[0]["personal"])
        val body = fake.requests.single().second!!
        assertEquals(JsonPrimitive(0.8), body["temperature"])
        val user = ((body["messages"] as JsonArray)[1] as JsonObject)["content"]!!.jsonPrimitive.content
        assertTrue(user.contains("- 我们这周把登录模块做完了下周开始做支付相关的功能"))
        assertNotNull(Practice.getTopics("en"))
    }
}
