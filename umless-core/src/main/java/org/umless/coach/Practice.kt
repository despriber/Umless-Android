package org.umless.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umless.api.ApiException
import org.umless.core.Clock
import org.umless.core.I18n.t
import org.umless.core.Py
import org.umless.core.Spec
import org.umless.core.Umless
import org.umless.core.UmlessConfig
import org.umless.data.Db
import org.umless.data.Row

/**
 * 口语练习，移植自桌面端 practice.py：每日出题（个人化 + 通用；没有 LLM 时用内置题目）与评测。
 * 录音与识别由调用方完成（主 App 的练习页），这里只做 LLM 评分 + 规则指标 + 落库。
 */
object Practice {
    // LLM 维度（1–5 分 × 20）+ 规则维度（0–100）
    val PRACTICE_WEIGHTS = linkedMapOf("structure" to 0.25, "logic" to 0.25, "wording" to 0.15, "fluency" to 0.20, "concision" to 0.15)

    private val db: Db get() = Umless.db

    /** 内置题目（spec/practice_fallback_topics.json），lang 为 zh / en */
    fun fallbackTopics(lang: String): List<JsonObject> {
        val all = Json.parseToJsonElement(Spec.text("practice_fallback_topics.json")) as JsonObject
        return (all[if (lang == "en") "en" else "zh"] as JsonArray).map { it as JsonObject }
    }

    // ================================================================ 出题

    /** 今天的题目；传入 lang 时，界面语言变了的旧题目视为没有（需要重新出题） */
    fun getTopics(lang: String? = null): JsonObject? {
        val data = db.getKv("practice_topics") as? JsonObject ?: return null
        if ((data["date"] as? JsonPrimitive)?.content != Coach.today()) return null
        if (lang != null && ((data["lang"] as? JsonPrimitive)?.content ?: "zh") != lang) return null
        return buildJsonObject {
            put("date", data["date"]!!)
            put("topics", data["topics"] ?: JsonArray(emptyList()))
        }
    }

    fun recentTexts(limit: Int = 25): List<String> {
        val rows = db.query(
            "SELECT output_text FROM utterances WHERE created_at >= ? ORDER BY id DESC LIMIT 200",
            Coach.shift(Coach.today(), -14) + "T00:00:00",
        )
        val texts = rows.map { Py.strip(it.str("output_text") ?: "") }.filter { Py.len(it) >= 15 }
            .sortedByDescending { Py.len(it) }
        return texts.take(limit).map { Py.take(it, 120) }
    }

    fun weakPoints(): List<String> {
        val points = Coach.getHabits("active").map { (it as JsonObject)["title"]!!.let(Py::str) }.toMutableList()
        val rows = db.query("SELECT data FROM diagnoses ORDER BY id DESC LIMIT 1")
        if (rows.isNotEmpty()) {
            val data = runCatching { Json.parseToJsonElement(rows[0].str("data") ?: "{}") as JsonObject }.getOrNull()
            val issues = data?.get("issues") as? JsonArray
            issues?.take(3)?.forEach { points.add(Py.str((it as JsonObject)["title"])) }
        }
        return points
    }

    fun cleanTopic(tp: JsonElement?, i: Int, lang: String = "zh"): JsonObject? {
        if (tp !is JsonObject || !Py.truthy(tp["title"])) return null
        val duration = tp["duration"]
        val k = if (lang == "en") 3 else 1 // 英文按字母计，长度上限放宽
        fun s(key: String) = if (Py.truthy(tp[key])) Py.str(tp[key]) else ""
        val category = if (Py.truthy(tp["category"])) Py.str(tp["category"]) else t("practice.category_general")
        val is90 = duration is JsonPrimitive && !duration.isString && duration.content != "true" && Py.toFloat(duration) == 90.0
        return buildJsonObject {
            put("id", "t$i")
            put("title", Py.take(Py.str(tp["title"]), 40 * k))
            put("category", Py.take(category, 6 * k))
            put("prompt", Py.take(s("prompt"), 200 * k))
            put("hint", Py.take(s("hint"), 60 * k))
            put("duration", if (is90) 90 else 60)
            put("personal", Py.truthy(tp["personal"]))
        }
    }

    suspend fun generateTopics(progress: Progress? = null): JsonObject {
        progress?.invoke(null, t("practice.making_topics"))
        val lang = Umless.language()
        var topics: List<JsonObject> = try {
            val client = Coach.coachClient()
            val messages = Prompts.buildTopicsMessages(recentTexts(), weakPoints(), lang)
            val raw = client.chatJson(messages, temperature = 0.8)
            val list = raw["topics"].takeIf { Py.truthy(it) } as? JsonArray ?: JsonArray(emptyList())
            list.mapIndexedNotNull { idx, tp -> cleanTopic(tp, idx + 1, lang) }.take(5)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (topics.isEmpty()) { // 没有配置 LLM 或调用失败时使用内置题目
            val picks = fallbackTopics(lang).shuffled()
            topics = picks.mapIndexed { idx, tp ->
                cleanTopic(JsonObject(LinkedHashMap(tp).apply { put("duration", JsonPrimitive(60)) }), idx + 1, lang)!!
            }
        }
        val data = buildJsonObject {
            put("date", Coach.today())
            put("topics", JsonArray(topics))
        }
        // 记下出题语言，切换界面语言后重新出题
        db.setKv("practice_topics", JsonObject(LinkedHashMap(data).apply { put("lang", JsonPrimitive(lang)) }))
        return data
    }

    // ================================================================ 评测

    /** 五个维度的分数（整数）与综合分：LLM 1–5 分 × 20，规则分取整，缺失维度按剩余权重归一 */
    fun practiceScores(llmScores: JsonObject, fluency: Double, concision: Double): Pair<JsonObject, Long> {
        val scores = LinkedHashMap<String, Long?>()
        for (k in listOf("structure", "logic", "wording")) {
            val v = Coach.score1to5(llmScores[k])
            scores[k] = v?.let { Py.roundInt(it * 20) }
        }
        scores["fluency"] = Py.roundInt(fluency)
        scores["concision"] = Py.roundInt(concision)
        val avail = scores.entries.filter { it.value != null }
        val overall = Py.roundInt(
            Py.sum(avail.map { PRACTICE_WEIGHTS.getValue(it.key) * it.value!! }) /
                Py.sum(avail.map { PRACTICE_WEIGHTS.getValue(it.key) })
        )
        return buildJsonObject { scores.forEach { (k, v) -> put(k, v) } } to overall
    }

    /** 评测一次练习（识别已由调用方完成），结果写入 practice 表，返回 getPractice(id) */
    suspend fun evaluate(topic: JsonObject, rawText: String, durationS: Double, progress: Progress? = null): JsonObject {
        val p = progress ?: { _, _ -> }
        if (durationS < 3) throw ApiException(t("practice.too_short"))
        if (rawText.isBlank()) throw ApiException(t("practice.no_speech"))

        p(0.35f, t("practice.evaluating"))
        var feedback = JsonObject(emptyMap())
        var error: String? = null
        try {
            val client = Coach.coachClient()
            feedback = client.chatJson(Prompts.buildPracticeMessages(topic, rawText, durationS, Umless.language()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        val clean = (if (Py.truthy(feedback["clean_text"])) Py.str(feedback["clean_text"]) else "").ifEmpty { null }
        val m = Metrics.analyze(rawText, clean, durationS, Coach.lexiconWords())

        val llmScores = feedback["scores"] as? JsonObject ?: JsonObject(emptyMap())
        val (scores, overall) = practiceScores(llmScores, m.fluency, m.concision)

        var improvements = Coach.strList(feedback["improvements"], 3, 300)
        if (error != null) improvements = listOf(t("practice.llm_failed", "error" to error))
        val data = buildJsonObject {
            put("scores", scores)
            put("strengths", JsonArray(Coach.strList(feedback["strengths"], 3, 300).map(::JsonPrimitive)))
            put("improvements", JsonArray(improvements.map(::JsonPrimitive)))
            put("outline", JsonArray(Coach.strList(feedback["outline"], 5, 200).map(::JsonPrimitive)))
            put("better_version", if (Py.truthy(feedback["better_version"])) Py.str(feedback["better_version"]) else "")
            put("highlights", m.highlightsJson())
            put("filler_count", m.fillerCount)
            put("cpm", m.cpm?.takeIf { it != 0.0 }?.let { Py.roundInt(it) })
            put("topic_detail", topic)
        }
        val title = if (Py.truthy(topic["title"])) Py.str(topic["title"]) else t("practice.free_topic")
        val category = if (Py.truthy(topic["category"])) Py.str(topic["category"]) else t("practice.category_custom")
        val pid = db.insert(
            "INSERT INTO practice (created_at, topic, category, duration_s, raw_text, overall, data) VALUES (?, ?, ?, ?, ?, ?, ?)",
            Clock.nowIso(), title, category, Py.round(durationS, 1), rawText, overall, Py.dumps(data),
        )
        return getPractice(pid)!!
    }

    fun getPractice(pid: Long): JsonObject? {
        val r = db.query("SELECT * FROM practice WHERE id = ?", pid).firstOrNull() ?: return null
        val data = runCatching { Json.parseToJsonElement(r.str("data") ?: "{}") as JsonObject }.getOrDefault(JsonObject(emptyMap()))
        return buildJsonObject {
            put("id", Row.valueJson(r["id"]))
            put("created_at", Row.valueJson(r["created_at"]))
            put("topic", Row.valueJson(r["topic"]))
            put("category", Row.valueJson(r["category"]))
            put("duration_s", Row.valueJson(r["duration_s"]))
            put("raw_text", Row.valueJson(r["raw_text"]))
            put("overall", Coach.r(r.double("overall")))
            for ((k, v) in data) if (k != "topic_detail") put(k, v)
        }
    }

    fun listPractice(limit: Int = 20): JsonArray {
        val rows = db.query(
            "SELECT id, created_at, topic, category, duration_s, overall FROM practice ORDER BY id DESC LIMIT ?", limit,
        )
        return JsonArray(rows.map { r ->
            val o = LinkedHashMap(r.toJson())
            o["overall"] = Coach.r(r.double("overall"))?.let(::JsonPrimitive) ?: JsonNull
            JsonObject(o)
        })
    }
}
