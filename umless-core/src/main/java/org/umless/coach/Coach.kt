package org.umless.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umless.core.Clock
import org.umless.core.I18n.t
import org.umless.core.Py
import org.umless.core.Umless
import org.umless.core.UmlessConfig
import org.umless.core.UmlessLog
import org.umless.data.Db
import org.umless.data.Row
import org.umless.llm.LlmClient
import org.umless.llm.LlmConfig
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale

/** 教练相关的失败（message 已翻译），例如样本不足、没有配置大模型 */
class CoachError(message: String) : RuntimeException(message)

/** 进度回调：比例（0–1 或 null）与说明 */
typealias Progress = (Float?, String?) -> Unit

/** 诊断取样的输入行 */
data class SampleRow(val id: Long, val chars: Int, val scoreable: Boolean, val app: String?, val rawText: String)

/**
 * 表达教练，移植自桌面端 coach.py：逐条指标落库、每日诊断、习惯卡，以及概览 / 报告 / 趋势 / 历史的数据组装。
 *
 * 分数统一为 0–100：流畅度、简洁度来自规则；逻辑性、清晰度来自 LLM 的 1–5 分 × 20。
 * 综合分只在诊断过的日期计算（缺某个维度时按剩余维度重新归一）。
 */
object Coach {
    val WEIGHTS = linkedMapOf("fluency" to 0.30, "concision" to 0.20, "logic" to 0.25, "clarity" to 0.25)
    val ISSUE_TYPES = setOf("filler", "repeat", "correction", "structure", "redundancy", "wording")
    val HABIT_KINDS = setOf("word", "repeat", "correction", "logic", "clarity")
    const val MAX_ACTIVE_HABITS = 3
    const val MIN_DIAGNOSIS_SAMPLES = 3
    const val TYPING_CPM = 40 // 估算"节省时间"用的打字速度（字 / 分钟）
    const val DIAGNOSIS_BUDGET = 8000 // 送给 LLM 的逐字稿总字数上限
    const val DIAGNOSIS_TEXT_MAX = 300

    private const val TAG = "Coach"
    private val db: Db get() = Umless.db

    // ================================================================ 工具

    fun today(): String = Clock.today()

    fun dayRange(d: String): Pair<String, String> = "${d}T00:00:00" to "${Clock.shift(d, 1)}T00:00:00"

    fun shift(d: String, days: Int): String = Clock.shift(d, days.toLong())

    /** 展示用整数分（HALF_EVEN） */
    fun r(x: Double?): Long? = x?.let { Py.roundInt(it) }

    private fun rj(x: Double?): JsonElement = r(x)?.let { JsonPrimitive(it) } ?: JsonNull

    fun overallScore(fluency: Double? = null, concision: Double? = null, logic: Double? = null, clarity: Double? = null): Double? {
        val vals = linkedMapOf("fluency" to fluency, "concision" to concision, "logic" to logic, "clarity" to clarity)
        val avail = vals.entries.filter { it.value != null }
        if (avail.isEmpty()) return null
        val total = Py.sum(avail.map { WEIGHTS.getValue(it.key) })
        return Py.round(Py.sum(avail.map { WEIGHTS.getValue(it.key) * it.value!! }) / total, 1)
    }

    fun contentLen(text: String?): Int = Metrics.contentLen(text)

    private fun Row.j(name: String): JsonElement = Row.valueJson(this[name])

    private fun parseObject(text: String?): JsonObject =
        text?.takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())

    private fun parseArray(text: String?): JsonArray =
        text?.takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            ?: JsonArray(emptyList())

    fun fillersOf(o: JsonObject): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for ((k, v) in o) out[k] = Py.toFloat(v)?.toInt() ?: 0
        return out
    }

    /** 数据库行 → aggregate 需要的指标；没有指标时返回 null */
    fun metricRow(row: Row): MetricRow? {
        val chars = row.int("chars") ?: return null
        return MetricRow(
            chars = chars,
            fillers = fillersOf(parseObject(row.str("fillers") ?: "{}")),
            repeatCount = row.int("repeat_count") ?: 0,
            correctionCount = row.int("correction_count") ?: 0,
            rewriteRatio = row.double("rewrite_ratio"),
            scoreable = row.bool("scoreable"),
        )
    }

    fun statsOfMetrics(input: List<MetricRow?>): Stats {
        val ms = input.filterNotNull()
        val agg = Metrics.aggregate(ms)
        val scoreable = ms.filter { it.scoreable }
        return Stats(
            agg = agg,
            repeatCount = scoreable.sumOf { it.repeatCount },
            correctionCount = scoreable.sumOf { it.correctionCount },
            fillersPer100 = if (agg.chars != 0) Py.round(agg.fillerCount.toDouble() / agg.chars * 100, 2) else 0,
        )
    }

    fun statsOf(rows: List<Row>): Stats = statsOfMetrics(rows.map(::metricRow))

    /** [startDay, endDay] 闭区间内的记录（含指标） */
    fun rowsBetween(startDay: String, endDay: String): List<Row> =
        db.utterancesBetween(dayRange(startDay).first, dayRange(endDay).second)

    // ================================================================ 指标

    fun lexiconWords(): List<String> {
        if (db.query("SELECT 1 FROM lexicon LIMIT 1").isEmpty()) ensureLexicon() // 新库：先放入内置口癖词
        return db.lexicon(enabledOnly = true).mapNotNull { it.str("word") }
    }

    fun ensureLexicon() {
        db.addWords(Metrics.BUILTIN_FILLERS, "builtin")
    }

    fun computeMetrics(uid: Long, rawText: String, polishedText: String?, durationS: Double?, lexicon: List<String>? = null): Analysis {
        val lex = lexicon ?: lexiconWords()
        val m = Metrics.analyze(rawText, polishedText, durationS, lex)
        db.saveMetrics(uid, m)
        return m
    }

    fun lexiconHash(words: List<String>): String {
        val text = words.sortedWith(Py.codePointOrder).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    /** 补算缺失或过期的指标；口癖词表变化时全部重算。返回处理条数 */
    @Synchronized
    fun backfillMetrics(): Int {
        ensureLexicon()
        val lex = lexiconWords()
        val h = lexiconHash(lex) + "-v${Metrics.METRICS_VERSION}"
        val stored = (db.getKv("metrics_hash") as? JsonPrimitive)?.content
        val rows = if (stored != h) db.query("SELECT * FROM utterances") else db.utterancesMissingMetrics(Metrics.METRICS_VERSION)
        for (r in rows) {
            computeMetrics(r.long("id")!!, r.str("raw_text") ?: "", r.str("polished_text"), r.double("duration_s"), lex)
        }
        db.setKv("metrics_hash", JsonPrimitive(h))
        if (rows.isNotEmpty()) UmlessLog.i(TAG, "重算指标 ${rows.size} 条")
        return rows.size
    }

    // ================================================================ 历史

    fun historyItem(row: Row): JsonObject = buildJsonObject {
        put("id", row.j("id"))
        put("created_at", row.j("created_at"))
        put("duration_s", row.j("duration_s"))
        put("app", row.j("app"))
        put("scene", row.str("scene")?.takeIf { it.isNotEmpty() } ?: "default")
        put("intensity", row.j("intensity"))
        put("output_text", row.j("output_text"))
        put("raw_text", row.j("raw_text"))
        val hl = row.str("highlights")
        put("highlights", if (!hl.isNullOrEmpty()) Json.parseToJsonElement(hl) else buildJsonArray {
            add(buildJsonArray { add(row.j("raw_text")); add(JsonPrimitive("keep")) })
        })
        put("filler_count", row.long("filler_count") ?: 0L)
        put("repeat_count", row.long("repeat_count") ?: 0L)
        put("correction_count", row.long("correction_count") ?: 0L)
        put("fluency", rj(row.double("fluency")))
        put("rewrite_ratio", row.j("rewrite_ratio"))
        put("asr_ms", row.j("asr_ms"))
        put("llm_ms", row.j("llm_ms"))
    }

    private const val HISTORY_SELECT =
        "SELECT u.*, m.chars, m.fillers, m.filler_count, m.repeat_count, m.correction_count, " +
            "m.rewrite_ratio, m.cpm, m.fluency, m.concision, m.scoreable, m.highlights " +
            "FROM utterances u LEFT JOIN metrics m ON m.utterance_id = u.id "

    fun listHistory(query: String = "", offset: Int = 0, limit: Int = 30): JsonObject {
        var where = ""
        var params = emptyList<Any?>()
        if (query.isNotEmpty()) {
            val like = "%$query%"
            where = "WHERE u.raw_text LIKE ? OR u.output_text LIKE ? OR u.app LIKE ? "
            params = listOf(like, like, like)
        }
        val total = db.query("SELECT COUNT(*) AS n FROM utterances u $where", *params.toTypedArray())[0].long("n") ?: 0
        val rows = db.query(HISTORY_SELECT + where + "ORDER BY u.id DESC LIMIT ? OFFSET ?", *(params + listOf(limit, offset)).toTypedArray())
        return buildJsonObject {
            put("total", total)
            put("items", JsonArray(rows.map(::historyItem)))
        }
    }

    // ================================================================ 诊断

    fun latestDiagnosis(d: String): Row? =
        db.query("SELECT * FROM diagnoses WHERE date = ? ORDER BY id DESC LIMIT 1", d).firstOrNull()

    /** 挑选送给 LLM 的逐字稿：总字数受限时优先保留较长的句子（超出预算的跳过），再按 id 排序 */
    fun sampleForLlm(rows: List<SampleRow>): List<SampleUtterance> {
        val items = rows.filter { it.scoreable }
        val chosen = ArrayList<Pair<Long, SampleUtterance>>()
        var used = 0
        for (r in items.sortedByDescending { it.chars }) {
            val text = Py.take(r.rawText, DIAGNOSIS_TEXT_MAX)
            val len = Py.len(text)
            if (used + len > DIAGNOSIS_BUDGET) continue
            chosen.add(r.id to SampleUtterance(r.app, text))
            used += len
        }
        return chosen.sortedBy { it.first }.map { it.second }
    }

    private fun sampleRow(r: Row) = SampleRow(
        id = r.long("id") ?: 0, chars = r.int("chars") ?: 0, scoreable = r.bool("scoreable"),
        app = r.str("app"), rawText = r.str("raw_text") ?: "",
    )

    /** _str_list：列表项 str() + strip()，去掉空串，截断到 maxlen，最多 limit 条 */
    fun strList(v: JsonElement?, limit: Int, maxlen: Int = 200): List<String> {
        if (v !is JsonArray) return emptyList()
        return v.map { Py.strip(Py.str(it)) }.filter { it.isNotEmpty() }.map { Py.take(it, maxlen) }.take(limit)
    }

    private fun strListJson(v: JsonElement?, limit: Int, maxlen: Int = 200) = JsonArray(strList(v, limit, maxlen).map(::JsonPrimitive))

    /** LLM 给的 1–5 分：能被 float() 解析的夹到 [1, 5]，其他返回 null */
    fun score1to5(v: JsonElement?): Double? = Py.toFloat(v)?.let { Py.clamp(it, 1.0, 5.0) }

    private fun items(v: JsonElement?): List<JsonElement> =
        if (Py.truthy(v) && v is JsonArray) v else emptyList()

    fun sanitizeDiagnosis(raw: JsonObject, lexicon: List<String>, glossary: List<String>): JsonObject {
        val scores = raw["scores"] as? JsonObject ?: JsonObject(emptyMap())
        val issues = ArrayList<JsonObject>()
        for (it in items(raw["issues"])) {
            if (it !is JsonObject || !Py.truthy(it["title"])) continue
            val type = (it["type"] as? JsonPrimitive)?.takeIf { p -> p.isString && p.content in ISSUE_TYPES }?.content ?: "wording"
            issues.add(buildJsonObject {
                put("title", Py.take(Py.str(it["title"]), 60))
                put("type", type)
                put("evidence", strListJson(it["evidence"], 3))
                put("advice", Py.take(strOr(it["advice"]), 300))
            })
        }
        val examples = items(raw["examples"]).filter { e ->
            e is JsonObject && Py.truthy(e["original"]) && Py.truthy(e["better"])
        }.map { e ->
            e as JsonObject
            buildJsonObject {
                put("original", Py.str(e["original"]))
                put("better", Py.str(e["better"]))
                put("why", strOr(e["why"]))
            }
        }.take(4)
        val lex = lexicon.toSet()
        val fillers = strList(raw["personal_fillers"], 5, 8).filter { it !in lex }
        val gl = glossary.map { it.lowercase(Locale.ROOT) }.toSet()
        val terms = items(raw["terms"]).filter { tm ->
            tm is JsonObject && Py.truthy(tm["wrong"]) && Py.truthy(tm["right"]) &&
                Py.str(tm["right"]).lowercase(Locale.ROOT) !in gl
        }.map { tm ->
            tm as JsonObject
            buildJsonObject {
                put("wrong", Py.take(Py.str(tm["wrong"]), 40))
                put("right", Py.take(Py.str(tm["right"]), 40))
            }
        }.take(5)
        val habits = ArrayList<JsonObject>()
        for (h in items(raw["habits"])) {
            if (h !is JsonObject) continue
            val kind = (h["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (kind !in HABIT_KINDS) continue
            val word = if (kind == "word") Py.strip(strOr(h["word"])) else null
            if (kind == "word" && word.isNullOrEmpty()) continue
            habits.add(buildJsonObject {
                put("kind", kind)
                put("word", word)
                put("title", Py.take(strOr(h["title"]), 30))
                put("advice", Py.take(strOr(h["advice"]), 200))
                put("evidence", strListJson(h["evidence"], 3))
            })
        }
        return buildJsonObject {
            put("summary", Py.take(strOr(raw["summary"]), 600))
            put("logic", score1to5(scores["logic"]))
            put("clarity", score1to5(scores["clarity"]))
            put("strengths", strListJson(raw["strengths"], 3))
            put("issues", JsonArray(issues.take(4)))
            put("examples", JsonArray(examples))
            put("personal_fillers", JsonArray(fillers.map(::JsonPrimitive)))
            put("terms", JsonArray(terms))
            put("habits", JsonArray(habits.take(2)))
        }
    }

    /** Python 的 str(v or "") */
    private fun strOr(v: JsonElement?): String = if (Py.truthy(v)) Py.str(v) else ""

    /** 诊断与练习评测用的客户端：coach.model 为空时与润色相同，超时至少 120 秒 */
    fun coachClient(cfg: JsonObject = UmlessConfig.get()): LlmClient {
        val llm = LlmConfig.fromConfig(cfg)
        if (llm.baseUrl.isEmpty()) throw CoachError(t("coach.no_llm"))
        val model = UmlessConfig.str("coach.model", cfg).ifEmpty { llm.model }
        return LlmClient(llm, model = model, timeoutS = maxOf(llm.timeoutS, 120.0))
    }

    /** 诊断某一天，结果写库并更新习惯卡。返回 getReport(d) */
    suspend fun diagnose(d: String, progress: Progress? = null): JsonObject? {
        val p = progress ?: { _, _ -> }
        p(0.05f, t("coach.collecting"))
        backfillMetrics()
        val rows = rowsBetween(d, d)
        val stats = statsOf(rows)
        if (stats.n < MIN_DIAGNOSIS_SAMPLES) throw CoachError(t("coach.too_few", "n" to stats.n, "min" to MIN_DIAGNOSIS_SAMPLES))

        val cfg = UmlessConfig.get()
        val glossary = UmlessConfig.strings("glossary.terms", cfg)
        val lexicon = lexiconWords()
        val active = getHabits("active").map { ((it as JsonObject)["title"] as JsonPrimitive).content }
        // 点评文字跟随界面语言，引用的原话保持用户说话的语言
        val lang = Umless.resolveLanguage(UmlessConfig.str("app.language", cfg))
        val messages = Prompts.buildDiagnosisMessages(d, sampleForLlm(rows.map(::sampleRow)), stats.toJson(), lexicon, glossary, active, lang)
        val client = coachClient(cfg)
        p(null, t("coach.analyzing"))
        val data = sanitizeDiagnosis(client.chatJson(messages), lexicon, glossary)

        val logic = Py.toFloat(data["logic"])?.let { it * 20 }
        val clarity = Py.toFloat(data["clarity"])?.let { it * 20 }
        val overall = overallScore(stats.fluency, stats.concision, logic, clarity)
        p(0.9f, t("coach.making_habits"))
        db.update("DELETE FROM diagnoses WHERE date = ?", d)
        db.insert(
            "INSERT INTO diagnoses (date, created_at, model, n, logic, clarity, fluency, concision, overall, data) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            d, Clock.nowIso(), client.model, stats.n, logic, clarity, stats.fluency, stats.concision, overall, Py.dumps(data),
        )
        createHabits(data["habits"] as JsonArray, d)
        refreshHabits()
        UmlessLog.i(TAG, "诊断 $d 完成：综合 $overall，逻辑 $logic，清晰 $clarity")
        return getReport(d)
    }

    fun getReport(d: String): JsonObject? {
        val rows = rowsBetween(d, d)
        val diag = latestDiagnosis(d)
        if (rows.isEmpty() && diag == null) return null
        val stats = statsOf(rows)
        val report = linkedMapOf<String, JsonElement>(
            "date" to JsonPrimitive(d), "n" to JsonPrimitive(stats.n), "diagnosed" to JsonPrimitive(false),
            "created_at" to JsonNull, "model" to JsonNull,
            "scores" to buildJsonObject {
                put("overall", JsonNull); put("fluency", rj(stats.fluency)); put("concision", rj(stats.concision))
                put("logic", JsonNull); put("clarity", JsonNull)
            },
            "summary" to JsonPrimitive(""), "strengths" to JsonArray(emptyList()), "issues" to JsonArray(emptyList()),
            "examples" to JsonArray(emptyList()), "personal_fillers" to JsonArray(emptyList()), "terms" to JsonArray(emptyList()),
            "filler_stats" to stats.agg.fillerStatsJson(),
            "rewrite_ratio" to (stats.agg.rewriteRatio?.let(::JsonPrimitive) ?: JsonNull),
        )
        if (diag != null) {
            val data = parseObject(diag.str("data"))
            val lex = db.lexicon(enabledOnly = false).mapNotNull { it.str("word") }.toSet()
            val gl = UmlessConfig.glossary.map { it.lowercase(Locale.ROOT) }.toSet()
            report["n"] = diag.j("n")
            report["diagnosed"] = JsonPrimitive(true)
            report["created_at"] = diag.j("created_at")
            report["model"] = diag.j("model")
            report["scores"] = buildJsonObject {
                put("overall", rj(diag.double("overall"))); put("fluency", rj(diag.double("fluency")))
                put("concision", rj(diag.double("concision"))); put("logic", rj(diag.double("logic")))
                put("clarity", rj(diag.double("clarity")))
            }
            report["summary"] = data["summary"] ?: JsonPrimitive("")
            report["strengths"] = data["strengths"] ?: JsonArray(emptyList())
            report["issues"] = data["issues"] ?: JsonArray(emptyList())
            report["examples"] = data["examples"] ?: JsonArray(emptyList())
            report["personal_fillers"] = JsonArray((data["personal_fillers"] as? JsonArray).orEmpty().map { w ->
                buildJsonObject { put("word", w); put("added", Py.str(w) in lex) }
            })
            report["terms"] = JsonArray((data["terms"] as? JsonArray).orEmpty().map { tm ->
                val o = tm as JsonObject
                JsonObject(LinkedHashMap(o).apply {
                    put("added", JsonPrimitive(Py.str(o["right"]).lowercase(Locale.ROOT) in gl))
                })
            })
        }
        return JsonObject(report)
    }

    private const val LATEST_PER_DAY = "SELECT * FROM diagnoses WHERE id IN (SELECT MAX(id) FROM diagnoses GROUP BY date) "

    fun listReports(): JsonObject {
        val td = today()
        val rows = db.query(
            "SELECT date, overall, n FROM diagnoses WHERE id IN (SELECT MAX(id) FROM diagnoses GROUP BY date) ORDER BY date DESC"
        )
        val todayStats = statsOf(rowsBetween(td, td))
        return buildJsonObject {
            put("today", buildJsonObject {
                put("date", td)
                put("n", todayStats.n)
                put("diagnosed", latestDiagnosis(td) != null)
            })
            put("items", JsonArray(rows.map { r ->
                buildJsonObject { put("date", r.j("date")); put("overall", rj(r.double("overall"))); put("n", r.j("n")) }
            }))
        }
    }

    fun getTrend(days: Int = 30): JsonArray {
        val end = today()
        val start = shift(end, -(days - 1))
        val byDay = LinkedHashMap<String, MutableList<Row>>()
        for (r in rowsBetween(start, end)) byDay.getOrPut((r.str("created_at") ?: "").take(10)) { ArrayList() }.add(r)
        val diags = db.query(LATEST_PER_DAY + "AND date >= ? AND date <= ?", start, end).associateBy { it.str("date") ?: "" }
        val out = ArrayList<JsonObject>()
        for (d in (byDay.keys + diags.keys).toSortedSet(Py.codePointOrder)) {
            val rows = byDay[d].orEmpty()
            val s = statsOf(rows)
            val g = diags[d]
            out.add(buildJsonObject {
                put("date", d)
                put("overall", if (g != null) rj(g.double("overall")) else JsonNull)
                put("fluency", rj(if (g != null) g.double("fluency") else s.fluency))
                put("concision", rj(if (g != null) g.double("concision") else s.concision))
                put("logic", if (g != null) rj(g.double("logic")) else JsonNull)
                put("clarity", if (g != null) rj(g.double("clarity")) else JsonNull)
                put("count", rows.size)
                put("diagnosed", g != null)
            })
        }
        return JsonArray(out)
    }

    fun getOverview(): JsonObject {
        val td = today()
        val todayRows = rowsBetween(td, td)
        val ts = statsOf(todayRows)
        val spoken = Py.sum(todayRows.map { it.double("duration_s") ?: 0.0 }) / 60
        val typed = todayRows.sumOf { contentLen(it.str("output_text")) }.toDouble() / TYPING_CPM

        val diagRows = db.query(LATEST_PER_DAY + "ORDER BY date DESC LIMIT 2")
        val score: JsonObject
        val latest: JsonElement
        val week = statsOf(rowsBetween(shift(td, -6), td))
        if (diagRows.isNotEmpty()) {
            val g = diagRows[0]
            var delta: JsonElement = JsonNull
            val o0 = g.double("overall")
            val o1 = diagRows.getOrNull(1)?.double("overall")
            if (diagRows.size > 1 && o0 != null && o1 != null) delta = rj(o0 - o1)
            score = buildJsonObject {
                put("overall", rj(o0)); put("fluency", rj(g.double("fluency"))); put("concision", rj(g.double("concision")))
                put("logic", rj(g.double("logic"))); put("clarity", rj(g.double("clarity")))
                put("delta", delta); put("date", g.j("date"))
            }
            latest = buildJsonObject {
                put("date", g.j("date"))
                put("summary", parseObject(g.str("data"))["summary"] ?: JsonPrimitive(""))
            }
        } else {
            // 从未诊断：用最近 7 天的规则指标
            score = buildJsonObject {
                put("overall", JsonNull); put("fluency", rj(week.fluency)); put("concision", rj(week.concision))
                put("logic", JsonNull); put("clarity", JsonNull); put("delta", JsonNull); put("date", td)
            }
            latest = JsonNull
        }
        val recent = db.query(HISTORY_SELECT + "ORDER BY u.id DESC LIMIT 5")
        return buildJsonObject {
            put("today", buildJsonObject {
                put("count", todayRows.size)
                put("chars", ts.chars)
                put("minutes_spoken", Py.round(spoken, 1))
                put("minutes_saved", Py.round(maxOf(0.0, typed - spoken), 1))
                put("fluency", rj(ts.fluency))
                put("concision", rj(ts.concision))
            })
            put("score", score)
            put("trend", getTrend(30))
            put("habits", getHabits("active"))
            put("latest_diagnosis", latest)
            put("top_fillers", JsonArray(week.agg.fillerStats.take(5).map { it.toJson() }))
            put("recent", JsonArray(recent.map(::historyItem)))
        }
    }

    // ================================================================ 习惯卡

    fun habitKey(kind: String, word: String?): String = if (kind == "word") "word:$word" else kind

    fun lowerIsBetter(kind: String): Boolean = kind == "word" || kind == "repeat" || kind == "correction"

    /** 截至 endDay 的最近 7 天指标值及样本数。规则类为每百字次数，LLM 类为平均分 */
    fun habitValue(kind: String, word: String?, endDay: String): Pair<Double?, Int> {
        val start = shift(endDay, -6)
        if (kind == "logic" || kind == "clarity") {
            val rows = db.query(
                "SELECT $kind AS v FROM diagnoses WHERE id IN (SELECT MAX(id) FROM diagnoses GROUP BY date) " +
                    "AND date >= ? AND date <= ? AND $kind IS NOT NULL",
                start, endDay,
            )
            val vals = rows.mapNotNull { it.double("v") }
            return (if (vals.isNotEmpty()) Py.round(Py.sum(vals) / vals.size, 1) else null) to vals.size
        }
        val ms = rowsBetween(start, endDay).mapNotNull(::metricRow).filter { it.scoreable }
        val chars = ms.sumOf { it.chars }
        if (chars == 0) return null to 0
        val count = when (kind) {
            "word" -> ms.sumOf { it.fillers[word] ?: 0 }
            "repeat" -> ms.sumOf { it.repeatCount }
            else -> ms.sumOf { it.correctionCount }
        }
        return Py.round(count.toDouble() / chars * 100, 2) to ms.size
    }

    /** 由基线算目标；不值得练（或没有基线）时返回 null */
    fun habitTarget(kind: String, baseline: Double?): Double? {
        if (baseline == null) return null
        if (lowerIsBetter(kind)) {
            if (baseline < 0.3) return null // 本来就很少，没必要练
            return Py.round(baseline / 2, 2)
        }
        if (baseline >= 90) return null
        return minOf(100.0, baseline + 20)
    }

    /** 是否达成：达到目标、样本足够（规则类 ≥10 条，LLM 类 ≥2 次诊断）、创建满 3 天 */
    fun habitReached(kind: String, current: Double, target: Double, n: Int, createdDay: String, todayDay: String): Boolean {
        val lower = lowerIsBetter(kind)
        val reached = if (lower) current <= target else current >= target
        val enough = n >= (if (lower) 10 else 2)
        val oldEnough = Py.codePointOrder.compare(createdDay, shift(todayDay, -3)) <= 0
        return reached && enough && oldEnough
    }

    fun createHabits(suggestions: JsonArray, d: String) {
        var active = db.query("SELECT COUNT(*) AS n FROM habits WHERE status = 'active'")[0].long("n") ?: 0
        for (s in suggestions) {
            if (active >= MAX_ACTIVE_HABITS) return
            s as JsonObject
            val kind = Py.str(s["kind"])
            val word = (s["word"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val key = habitKey(kind, word)
            if (db.query("SELECT 1 FROM habits WHERE key = ?", key).isNotEmpty()) continue
            if (kind == "word" && word !in lexiconWords()) {
                db.addWords(listOf(word!!), "llm") // 要练的口头禅必须能被统计到
                backfillMetrics()
            }
            val baseline = habitValue(kind, word, d).first
            val target = habitTarget(kind, baseline) ?: continue
            val title = Py.str(s["title"]).takeIf { Py.truthy(s["title"]) }
                ?: if (kind == "word") t("habit.word", "word" to word) else t("habit.$kind")
            val now = Clock.nowIso()
            db.insert(
                "INSERT INTO habits (key, title, kind, word, advice, evidence, baseline, target, current, status, " +
                    "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?)",
                key, title, kind, word, (s["advice"] as? JsonPrimitive)?.content, Py.dumps(s["evidence"] ?: JsonArray(emptyList())),
                baseline, target, baseline, now, now,
            )
            active++
        }
    }

    /** 更新进行中习惯的当前值，达标则标记为已达成 */
    fun refreshHabits() {
        val td = today()
        for (h in db.query("SELECT * FROM habits WHERE status = 'active'")) {
            val kind = h.str("kind") ?: continue
            val (current, n) = habitValue(kind, h.str("word"), td)
            if (current == null) continue
            var status = "active"
            var achievedAt: String? = null
            val target = h.double("target")
            if (target != null && habitReached(kind, current, target, n, (h.str("created_at") ?: "").take(10), td)) {
                status = "achieved"
                achievedAt = Clock.nowIso()
            }
            db.update(
                "UPDATE habits SET current = ?, status = ?, achieved_at = ?, updated_at = ? WHERE id = ?",
                current, status, achievedAt, Clock.nowIso(), h.long("id"),
            )
        }
    }

    /** 进度 0–1：从基线到目标完成了多少；已达成固定为 1 */
    fun habitProgress(kind: String, base: Double?, target: Double?, cur: Double?, status: String?): Double {
        val lower = lowerIsBetter(kind)
        var progress = 0.0
        if (base != null && target != null && cur != null && base != target) {
            progress = if (lower) (base - cur) / (base - target) else (cur - base) / (target - base)
        }
        if (status == "achieved") progress = 1.0
        return Py.round(minOf(1.0, maxOf(0.0, progress)), 2)
    }

    fun habitDict(h: Row): JsonObject {
        val kind = h.str("kind") ?: ""
        val lower = lowerIsBetter(kind)
        val base = h.double("baseline")
        val target = h.double("target")
        val cur = h.double("current")
        return buildJsonObject {
            put("id", h.j("id")); put("key", h.j("key")); put("title", h.j("title")); put("kind", h.j("kind"))
            put("word", h.j("word"))
            put("advice", h.str("advice") ?: "")
            put("evidence", parseArray(h.str("evidence") ?: "[]"))
            put("baseline", h.j("baseline")); put("target", h.j("target")); put("current", h.j("current"))
            put("unit", if (lower) "次/百字" else "分")
            put("lower_is_better", lower)
            put("progress", habitProgress(kind, base, target, cur, h.str("status")))
            put("status", h.j("status")); put("created_at", h.j("created_at")); put("achieved_at", h.j("achieved_at"))
        }
    }

    fun getHabits(status: String? = null): JsonArray {
        val rows = if (!status.isNullOrEmpty()) db.query("SELECT * FROM habits WHERE status = ? ORDER BY id DESC", status)
        else db.query("SELECT * FROM habits ORDER BY status = 'active' DESC, id DESC")
        return JsonArray(rows.map(::habitDict))
    }

    fun setHabitStatus(hid: Long, status: String) {
        db.update("UPDATE habits SET status = ?, updated_at = ? WHERE id = ?", status, Clock.nowIso(), hid)
    }

    // ================================================================ 自动诊断

    /** 需要自动诊断的日期（前一天），没有则返回 null。失败后间隔 1 小时重试，最多 3 次 */
    fun dueAutoDiagnosis(): String? {
        val cfg = UmlessConfig.get()
        if (!(UmlessConfig.bool("coach.enabled", cfg) && UmlessConfig.bool("coach.auto_daily", cfg) &&
                UmlessConfig.str("llm.base_url", cfg).isNotEmpty())
        ) return null
        val d = shift(today(), -1)
        if (latestDiagnosis(d) != null) return null
        val attempt = db.getKv("auto_diagnosis") as? JsonObject ?: JsonObject(emptyMap())
        if ((attempt["date"] as? JsonPrimitive)?.content == d) {
            if ((Py.toFloat(attempt["count"]) ?: 0.0) >= 3) return null
            val last = runCatching { Clock.parse((attempt["at"] as JsonPrimitive).content) }.getOrNull()
            if (last != null && Duration.between(last, Clock.now()) < Duration.ofHours(1)) return null
        }
        val (start, end) = dayRange(d)
        val n = db.query(
            "SELECT COUNT(*) AS n FROM utterances u JOIN metrics m ON m.utterance_id = u.id " +
                "WHERE m.scoreable = 1 AND u.created_at >= ? AND u.created_at < ?",
            start, end,
        )[0].long("n") ?: 0
        return if (n >= maxOf(UmlessConfig.int("coach.min_utterances", 5, cfg), MIN_DIAGNOSIS_SAMPLES)) d else null
    }

    /** 到期则诊断前一天；返回新生成的报告，没有诊断或失败时返回 null（失败只记日志） */
    suspend fun runAutoDiagnosis(): JsonObject? {
        val d = dueAutoDiagnosis() ?: return null
        val attempt = db.getKv("auto_diagnosis") as? JsonObject ?: JsonObject(emptyMap())
        val count = if ((attempt["date"] as? JsonPrimitive)?.content == d) (Py.toFloat(attempt["count"])?.toInt() ?: 0) + 1 else 1
        db.setKv("auto_diagnosis", buildJsonObject { put("date", d); put("at", Clock.nowIso()); put("count", count) })
        return try {
            diagnose(d)
        } catch (e: Exception) {
            UmlessLog.e(TAG, "自动诊断 $d 失败（第 $count 次）", e)
            null
        }
    }

    /**
     * 定期维护（主 App 打开或定时触发时调用，相当于桌面端常驻进程的 _maintenance）：补算指标、
     * 清理过期记录、到期时自动诊断前一天、刷新习惯卡。返回本次新生成的昨日报告（可用于通知），否则 null。
     */
    suspend fun runAutoDiagnosisIfDue(): JsonObject? {
        var report: JsonObject? = null
        try {
            backfillMetrics()
            val days = UmlessConfig.int("privacy.retention_days")
            val td = today()
            if (days > 0 && (db.getKv("purged_on") as? JsonPrimitive)?.content != td) {
                val n = db.purgeOlderThan(days)
                db.setKv("purged_on", JsonPrimitive(td))
                if (n > 0) UmlessLog.i(TAG, "已删除 $n 条超过 $days 天的记录")
            }
            val before = latestDiagnosis(shift(td, -1))
            report = runAutoDiagnosis()?.takeIf { before == null }
            refreshHabits()
        } catch (e: Exception) {
            UmlessLog.e(TAG, "定期维护失败", e)
        }
        return report
    }
}
