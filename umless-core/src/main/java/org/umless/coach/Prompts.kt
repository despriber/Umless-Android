package org.umless.coach

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.umless.core.Py
import org.umless.core.Spec

/** 发给 LLM 的一条消息 */
data class ChatMessage(val role: String, val content: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}

fun List<ChatMessage>.toJson(): JsonArray = JsonArray(map { it.toJson() })

/** 诊断提示词里的一条逐字稿 */
data class SampleUtterance(val app: String?, val rawText: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("app", app)
        put("raw_text", rawText)
    }
}

/**
 * 所有 LLM 提示词的拼装，移植自桌面端 prompts.py。提示词文本来自共享规范 spec/prompts 目录下的 .txt 文件（原样使用，不 trim），
 * 拼装规则见 spec/prompts/README.md。另有 Android 专用的搜索场景 scene_search.txt（强制忠实档）。
 */
object Prompts {
    val SCENES = listOf("chat", "document", "code", "email", "default", "search")
    val INTENSITIES = listOf("faithful", "moderate", "restructure")

    private fun p(name: String) = Spec.text("prompts/$name.txt")

    /** lang=en 时在 system prompt 末尾追加输出语言要求；其余情况原样返回 */
    private fun withLang(system: String, lang: String?, enRule: String) =
        if (lang == "en") "$system\n\n$enRule" else system

    // ---------------------------------------------------------------- 润色

    fun buildMessages(rawText: String, glossary: List<String>, scene: String = "default", intensity: String = "moderate"): List<ChatMessage> {
        // 搜索框 / 网址栏只用忠实档、不分段（桌面端没有这个场景）
        val inten = if (scene == "search") "faithful" else if (intensity in INTENSITIES) intensity else "moderate"
        val sc = if (scene in SCENES) scene else "default"
        val parts = mutableListOf(p("polish_base"), p("intensity_$inten"), p("scene_$sc"))
        if (glossary.isNotEmpty()) parts.add("术语表（识别结果中发音相近的词应纠正为这些写法）：" + glossary.joinToString("、"))
        return listOf(
            ChatMessage("system", parts.joinToString("\n\n")),
            ChatMessage("user", "<transcript>\n$rawText\n</transcript>"),
        )
    }

    // ---------------------------------------------------------------- 每日诊断

    /** stats 为 coach.stats_of 的字典（JSON）；数值按 Python str() 格式化，null 写成 None */
    fun buildDiagnosisMessages(
        date: String,
        utterances: List<SampleUtterance>,
        stats: JsonObject,
        lexicon: List<String>,
        glossary: List<String>,
        activeHabits: List<String>,
        lang: String = "zh",
    ): List<ChatMessage> {
        fun s(key: String): String = Py.str(stats[key])
        val n = if ("n" in stats) s("n") else utterances.size.toString()
        val lines = mutableListOf("日期：$date，共 $n 条可评分的语音输入。")
        lines.add(
            "规则指标：流畅度 ${s("fluency")}（满分 100），简洁度 ${s("concision")}，" +
                "每百字口癖 ${s("fillers_per100")} 次，重复 ${s("repeat_count")} 次，" +
                "改口 ${s("correction_count")} 次。"
        )
        val fs = stats["filler_stats"]
        if (Py.truthy(fs) && fs is JsonArray) {
            val top = fs.take(8).joinToString("、") {
                val o = it as JsonObject
                "${Py.str(o["word"])}×${Py.str(o["count"])}"
            }
            lines.add("口癖统计：$top")
        }
        lines.add("已知口癖词表：" + (if (lexicon.isNotEmpty()) lexicon.joinToString("、") else "（空）"))
        lines.add("术语表：" + (if (glossary.isNotEmpty()) glossary.joinToString("、") else "（空）"))
        lines.add("进行中的习惯：" + (if (activeHabits.isNotEmpty()) activeHabits.joinToString("、") else "（无）"))
        lines.add("")
        lines.add("<utterances>")
        utterances.forEachIndexed { i, u ->
            val app = if (!u.app.isNullOrEmpty()) "（${u.app}）" else ""
            lines.add("${i + 1}. $app${u.rawText}")
        }
        lines.add("</utterances>")
        return listOf(
            ChatMessage("system", withLang(p("diagnosis_system"), lang, p("diagnosis_en"))),
            ChatMessage("user", lines.joinToString("\n")),
        )
    }

    // ---------------------------------------------------------------- 口语练习

    fun buildTopicsMessages(recentTexts: List<String>, weakPoints: List<String>, lang: String = "zh"): List<ChatMessage> {
        val body = mutableListOf("用户最近说过的内容（节选）：")
        if (recentTexts.isEmpty()) body.add("（无）") else recentTexts.forEach { body.add("- $it") }
        if (weakPoints.isNotEmpty()) body.add("用户近期需要改进的方面：" + weakPoints.joinToString("；"))
        return listOf(
            ChatMessage("system", withLang(p("topics_system"), lang, p("topics_en"))),
            ChatMessage("user", body.joinToString("\n")),
        )
    }

    fun buildPracticeMessages(topic: JsonObject, rawText: String, durationS: Double, lang: String = "zh"): List<ChatMessage> {
        val t = buildJsonObject {
            for (k in listOf("title", "prompt", "hint")) {
                val v = topic[k]
                if (Py.truthy(v)) put(k, v!!)
            }
        }
        val duration: JsonElement = if ("duration" in topic) topic["duration"] ?: JsonNull else JsonPrimitive(60)
        val user = "题目：${Py.dumps(t)}\n" +
            "用时：${Py.fmt0(durationS)} 秒（建议 ${Py.str(duration)} 秒）\n\n" +
            "<transcript>\n$rawText\n</transcript>"
        return listOf(
            ChatMessage("system", withLang(p("practice_system"), lang, p("practice_en"))),
            ChatMessage("user", user),
        )
    }

    /** 测试用：把 JSON 数组里的字符串取出来 */
    internal fun strings(e: JsonElement?): List<String> =
        (e as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
}
