package org.umless.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.umless.core.JsonAssert
import org.umless.core.Py
import org.umless.core.SpecFiles
import org.umless.core.Spec
import org.umless.core.TextFix

/** 共享规范 spec/cases 下的全部黄金样例（期望值由桌面端 Python 实现生成），逐条严格比对 */
class SpecCasesTest {
    private fun JsonElement?.d(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDouble()
    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonElement?.strs(): List<String> = (this as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun runAll(cases: List<Pair<String, () -> Unit>>): Int {
        val failures = mutableListOf<String>()
        for ((name, run) in cases) {
            try {
                run()
            } catch (e: Throwable) {
                failures.add("$name: ${e.message}")
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size}/${cases.size} cases failed:\n" + failures.joinToString("\n"))
        return cases.size
    }

    private fun metricRow(o: JsonObject): MetricRow? {
        val chars = o["chars"].d() ?: return null
        return MetricRow(
            chars = chars.toInt(),
            fillers = Coach.fillersOf(o["fillers"] as? JsonObject ?: JsonObject(emptyMap())),
            repeatCount = o["repeat_count"].d()!!.toInt(),
            correctionCount = o["correction_count"].d()!!.toInt(),
            rewriteRatio = o["rewrite_ratio"].d(),
            scoreable = Py.truthy(o["scoreable"]),
        )
    }

    private fun num(x: Double?): JsonElement = x?.let(::JsonPrimitive) ?: JsonNull

    @Test
    fun metricsCases() {
        val files = SpecFiles.list("cases/metrics")
        assertTrue("no metrics cases", files.size >= 50)
        val n = runAll(files.map { (file, c) ->
            val name = c["name"].s() ?: file
            name to {
                val lexicon = c["lexicon"].takeIf { it !is JsonNull }?.strs()
                val m = Metrics.analyze(c["raw"].s()!!, c["polished"].s(), c["duration_s"].d(), lexicon)
                JsonAssert.assertEquals(name, c["expected"]!!, m.toJson())
            }
        })
        println("spec/cases/metrics: $n passed")
    }

    @Test
    fun scoresCases() {
        val files = SpecFiles.list("cases/scores")
        var total = 0
        val kinds = mutableSetOf<String>()
        for ((file, doc) in files) {
            val kind = doc["kind"].s()!!
            kinds.add(kind)
            val cases = (doc["cases"] as JsonArray).map { it as JsonObject }
            total += runAll(cases.map { c ->
                val name = "$kind.${c["name"].s()}"
                val input = c["input"] as JsonObject
                val expected = c["expected"]!!
                name to {
                    val actual: JsonElement = when (kind) {
                        "aggregate" -> Metrics.aggregate((input["rows"] as JsonArray).mapNotNull { metricRow(it as JsonObject) }).toJson()
                        "stats_of" -> Coach.statsOfMetrics((input["rows"] as JsonArray).map { metricRow(it as JsonObject) }).toJson()
                        "fluency_score" -> JsonPrimitive(Metrics.fluencyScore(input["fillers_per100"].d()!!))
                        "concision_score" -> JsonPrimitive(Metrics.concisionScore(input["issues_per100"].d()!!))
                        "content_len" -> JsonPrimitive(Coach.contentLen(input["text"].s()))
                        "overall_score" -> num(
                            Coach.overallScore(input["fluency"].d(), input["concision"].d(), input["logic"].d(), input["clarity"].d())
                        )
                        "round_int" -> Coach.r(input["x"].d())?.let(::JsonPrimitive) ?: JsonNull
                        "habit_target" -> num(Coach.habitTarget(input["kind"].s()!!, input["baseline"].d()))
                        "habit_reached" -> JsonPrimitive(
                            Coach.habitReached(
                                input["kind"].s()!!, input["current"].d()!!, input["target"].d()!!, input["n"].d()!!.toInt(),
                                input["created_day"].s()!!, input["today_day"].s()!!,
                            )
                        )
                        "habit_progress" -> JsonPrimitive(
                            Coach.habitProgress(input["kind"].s()!!, input["baseline"].d(), input["target"].d(), input["current"].d(), input["status"].s())
                        )
                        "practice_scores" -> {
                            val (scores, overall) = Practice.practiceScores(
                                input["llm_scores"] as JsonObject, input["fluency"].d()!!, input["concision"].d()!!,
                            )
                            buildJsonObject { put("scores", scores); put("overall", overall) }
                        }
                        "sample_for_llm" -> JsonArray(
                            Coach.sampleForLlm((input["rows"] as JsonArray).map {
                                val o = it as JsonObject
                                SampleRow(o["id"].d()!!.toLong(), o["chars"].d()!!.toInt(), Py.truthy(o["scoreable"]), o["app"].s(), o["raw_text"].s()!!)
                            }).map { it.toJson() }
                        )
                        else -> throw AssertionError("unknown kind $kind in $file")
                    }
                    JsonAssert.assertEquals(name, expected, actual)
                }
            })
        }
        assertEquals(
            setOf("aggregate", "stats_of", "fluency_score", "concision_score", "content_len", "overall_score", "round_int",
                "habit_target", "habit_reached", "habit_progress", "practice_scores", "sample_for_llm"),
            kinds,
        )
        println("spec/cases/scores: $total passed")
    }

    @Test
    fun promptCases() {
        val files = SpecFiles.list("cases/prompts")
        val n = runAll(files.map { (file, c) ->
            val name = c["name"].s() ?: file
            name to {
                val i = c["input"] as JsonObject
                val messages = when (val b = c["builder"].s()) {
                    "build_messages" -> Prompts.buildMessages(i["raw_text"].s()!!, i["glossary"].strs(), i["scene"].s()!!, i["intensity"].s()!!)
                    "build_diagnosis_messages" -> Prompts.buildDiagnosisMessages(
                        i["date"].s()!!,
                        (i["utterances"] as JsonArray).map { u -> SampleUtterance((u as JsonObject)["app"].s(), u["raw_text"].s()!!) },
                        i["stats"] as JsonObject, i["lexicon"].strs(), i["glossary"].strs(), i["active_habits"].strs(), i["lang"].s()!!,
                    )
                    "build_topics_messages" -> Prompts.buildTopicsMessages(i["recent_texts"].strs(), i["weak_points"].strs(), i["lang"].s()!!)
                    "build_practice_messages" -> Prompts.buildPracticeMessages(i["topic"] as JsonObject, i["raw_text"].s()!!, i["duration_s"].d()!!, i["lang"].s()!!)
                    else -> throw AssertionError("unknown builder $b")
                }
                JsonAssert.assertEquals(name, c["expected"]!!, messages.toJson())
            }
        })
        println("spec/cases/prompts: $n passed")
    }

    /** 搜索场景（仅 Android）：scene_search.txt + 强制忠实档 */
    @Test
    fun searchScenePrompt() {
        val m = Prompts.buildMessages("嗯 北京天气", emptyList(), "search", "restructure")
        val expected = listOf(Spec.text("prompts/polish_base.txt"), Spec.text("prompts/intensity_faithful.txt"), Spec.text("prompts/scene_search.txt"))
            .joinToString("\n\n")
        assertEquals(expected, m[0].content)
        assertEquals("<transcript>\n嗯 北京天气\n</transcript>", m[1].content)
    }

    /** Kotlin 里的常量与 spec/constants.json 一致 */
    @Test
    fun constantsMatchSpec() {
        val c = Json.parseToJsonElement(Spec.text("constants.json")) as JsonObject
        assertEquals(Metrics.METRICS_VERSION, c["metrics_version"].d()!!.toInt())
        assertEquals(Metrics.MIN_SCOREABLE_CHARS, c["min_scoreable_chars"].d()!!.toInt())
        assertEquals(Metrics.BUILTIN_FILLERS, c["builtin_fillers"].strs())
        assertEquals(Metrics.INTERJECTIONS.toSet(), c["interjections"].strs().toSet())
        assertEquals(Metrics.CORRECTION_MARKERS, c["correction_markers"].strs())
        val rep = c["repeat"] as JsonObject
        assertEquals(Metrics.REPEAT_MAX_LEN, rep["max_len"].d()!!.toInt())
        assertEquals(Metrics.REPEAT_MIN_LEN_WITHOUT_POLISH, rep["min_len_without_polish"].d()!!.toInt())
        val f = c["score_formula"] as JsonObject
        assertEquals(Metrics.FLUENCY_K, f["fluency_k"].d()!!, 0.0)
        assertEquals(Metrics.CONCISION_K, f["concision_k"].d()!!, 0.0)
        val ow = c["overall_weights"] as JsonObject
        assertEquals(ow.keys.toList(), Coach.WEIGHTS.keys.toList())
        ow.forEach { (k, v) -> assertEquals(v.d()!!, Coach.WEIGHTS.getValue(k), 0.0) }
        val pw = c["practice_weights"] as JsonObject
        assertEquals(pw.keys.toList(), Practice.PRACTICE_WEIGHTS.keys.toList())
        pw.forEach { (k, v) -> assertEquals(v.d()!!, Practice.PRACTICE_WEIGHTS.getValue(k), 0.0) }
        val coach = c["coach"] as JsonObject
        assertEquals(Coach.MAX_ACTIVE_HABITS, coach["max_active_habits"].d()!!.toInt())
        assertEquals(Coach.MIN_DIAGNOSIS_SAMPLES, coach["min_diagnosis_samples"].d()!!.toInt())
        assertEquals(Coach.TYPING_CPM, coach["typing_cpm"].d()!!.toInt())
        assertEquals(Coach.DIAGNOSIS_BUDGET, coach["diagnosis_budget"].d()!!.toInt())
        assertEquals(Coach.DIAGNOSIS_TEXT_MAX, coach["diagnosis_text_max"].d()!!.toInt())
        val score = c["llm_score"] as JsonObject
        assertEquals(1.0, score["min"].d()!!, 0.0)
        assertEquals(5.0, score["max"].d()!!, 0.0)
        assertEquals(20.0, score["scale"].d()!!, 0.0)
        val asr = c["asr"] as JsonObject
        assertEquals(TextFix.COMMON_WORDS, asr["common_words"].strs())
        assertEquals(TextFix.SHORT_MAX, asr["short_max"].d()!!.toInt())
        assertEquals(TextFix.CJK_MIN, asr["cjk_min_codepoint"].d()!!.toInt())
    }

    /** 识别结果后处理：fix_case 黄金样例，并检查幂等 */
    @Test
    fun asrCases() {
        val files = SpecFiles.list("cases/asr")
        assertTrue("no asr cases", files.size >= 30)
        val n = runAll(files.map { (file, c) ->
            val name = c["name"].s() ?: file
            name to {
                val glossary = c["glossary"].strs()
                val got = TextFix.fixCase(c["input"].s()!!, glossary)
                assertEquals(name, c["expected"].s(), got)
                assertEquals("$name (idempotent)", got, TextFix.fixCase(got, glossary))
            }
        })
        println("spec/cases/asr: $n passed")
    }

    @Test
    fun fallbackTopicsLoad() {
        assertEquals(5, Practice.fallbackTopics("zh").size)
        assertEquals(5, Practice.fallbackTopics("en").size)
        assertEquals("介绍你最近在做的一件事", Practice.fallbackTopics("zh")[0]["title"].s())
    }
}
