package org.umless.coach

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umless.core.Py
import kotlin.math.exp

/**
 * 逐条规则指标：口癖、重复、改口、语速、改写幅度，以及原文分段标注。移植自桌面端 metrics.py，
 * 算法细节见 spec/metrics.md；常量与 spec/constants.json 一致（单元测试核对）。
 *
 * 核心思路：用字符级 diff 对比原文与润色稿，判断原文每个字是否被保留。口癖词只有"被润色删掉"才算口癖；
 * 没有润色稿时退化为只统计纯语气词。所有"字"都按 Unicode 码点计。
 */
object Metrics {
    const val METRICS_VERSION = 1
    const val MIN_SCOREABLE_CHARS = 15 // 太短的句子只统计口癖，不参与评分

    // 内置口癖词表：语气词 + 常见口头禅（口头禅在正常语义下也会出现，因此依赖"是否被删"判断）
    val BUILTIN_FILLERS = listOf(
        "嗯", "呃", "额", "啊", "哦", "噢", "诶", "欸", "唉",
        "然后", "就是", "就是说", "那个", "这个", "其实", "反正", "对吧", "的话",
        "怎么说呢", "所以说", "然后呢", "那么", "基本上", "相当于说",
    )

    // 纯语气词：没有润色稿时仍可直接计数
    val INTERJECTIONS = listOf("嗯", "呃", "额", "哦", "噢", "诶", "欸", "唉")
    val CORRECTION_MARKERS = listOf("不对", "哦不", "我是说", "我的意思是", "应该是", "准确地说", "说错了", "不是不是")
    const val REPEAT_MAX_LEN = 8 // 相邻重复检测的最长片段
    const val REPEAT_MIN_LEN_WITHOUT_POLISH = 2
    const val FLUENCY_K = 0.09 // 流畅度衰减系数
    const val CONCISION_K = 0.12 // 简洁度衰减系数

    private val interjectionSet = INTERJECTIONS.toSet()

    /** 有效字符：字母（L*）、数字（N*），不含标点、空白、符号、组合附加符号 */
    fun isContent(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    /** 有效字数（概览"节省时间"的 _content_len，与 re.sub(r"[\s\W_]", "", text) 一致） */
    fun contentLen(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var n = 0
        for (cp in Py.codePoints(text)) if (isContent(cp)) n++
        return n
    }

    class Normalized(val chars: IntArray, val index: IntArray)

    /** 去掉标点空白并逐码点小写，返回规范化文本及其每个字在原文中的码点下标 */
    fun normalize(text: String): Normalized {
        val cps = Py.codePoints(text)
        val chars = IntArray(cps.size)
        val index = IntArray(cps.size)
        var n = 0
        for (i in cps.indices) {
            val cp = cps[i]
            if (isContent(cp)) {
                chars[n] = Character.toLowerCase(cp)
                index[n] = i
                n++
            }
        }
        return Normalized(chars.copyOf(n), index.copyOf(n))
    }

    private fun findAll(text: IntArray, word: IntArray): List<Int> {
        val out = ArrayList<Int>()
        if (word.isEmpty()) return out
        var start = 0
        while (start + word.size <= text.size) {
            var found = -1
            var i = start
            while (i + word.size <= text.size) {
                var ok = true
                for (k in word.indices) if (text[i + k] != word[k]) { ok = false; break }
                if (ok) { found = i; break }
                i++
            }
            if (found < 0) break
            out.add(found)
            start = found + 1
        }
        return out
    }

    private fun regionEquals(a: IntArray, i: Int, j: Int, len: Int): Boolean {
        for (k in 0 until len) if (a[i + k] != a[j + k]) return false
        return true
    }

    fun analyze(rawText: String, polishedText: String?, durationS: Double? = null, lexicon: List<String>? = null): Analysis {
        val norm = normalize(rawText)
        val raw = norm.chars
        val n = raw.size
        val hasPolish = !polishedText.isNullOrEmpty()
        val kept: BooleanArray
        val similarity: Double?
        if (hasPolish) {
            val b = normalize(polishedText).chars
            kept = BooleanArray(n)
            var matches = 0
            for (blk in Difflib.matchingBlocks(raw, b)) {
                for (i in blk[0] until blk[0] + blk[2]) kept[i] = true
                matches += blk[2]
            }
            val total = n + b.size
            similarity = if (total > 0) 2.0 * matches / total else 1.0
        } else {
            kept = BooleanArray(n) { true }
            similarity = null
        }

        val labels = Array(n) { if (kept[it]) "keep" else "cut" }
        fun removed(a: Int, b: Int): Boolean {
            for (i in a until b) if (kept[i]) return false
            return true
        }

        // ---- 口癖：长词优先匹配，已标注的位置不重复计数 ----
        // 去重保留首次出现的顺序，再按原始词长度降序稳定排序
        val words = LinkedHashSet(lexicon ?: BUILTIN_FILLERS).toList().sortedByDescending { Py.len(it) }
        val fillers = LinkedHashMap<String, Int>()
        val taken = BooleanArray(n)
        for (w in words) {
            val wn = normalize(w).chars
            if (wn.isEmpty()) continue
            val wnStr by lazy { Py.fromCodePoints(wn) }
            for (i in findAll(raw, wn)) {
                val j = i + wn.size
                if ((i until j).any { taken[it] }) continue
                val ok = if (hasPolish) removed(i, j) else wnStr in interjectionSet
                if (ok) {
                    fillers[w] = (fillers[w] ?: 0) + 1
                    for (k in i until j) {
                        taken[k] = true
                        labels[k] = "filler"
                    }
                }
            }
        }

        // ---- 改口标记 ----
        var corrections = 0
        if (hasPolish) {
            for (marker in CORRECTION_MARKERS) {
                val mk = Py.codePoints(marker)
                for (i in findAll(raw, mk)) {
                    val j = i + mk.size
                    if ((i until j).none { taken[it] } && removed(i, j)) {
                        corrections++
                        for (k in i until j) {
                            taken[k] = true
                            labels[k] = "correction"
                        }
                    }
                }
            }
        }

        // ---- 相邻重复：如"我觉得我觉得""这这"；其中一份被删才算 ----
        var repeats = 0
        var i = 0
        while (i < n) {
            var found = 0
            var length = minOf(REPEAT_MAX_LEN, (n - i) / 2)
            while (length >= 1) {
                if (regionEquals(raw, i, i + length, length)) {
                    val firstCut = removed(i, i + length)
                    val secondCut = removed(i + length, i + 2 * length)
                    if (hasPolish && (firstCut || secondCut)) found = length
                    else if (!hasPolish && length >= REPEAT_MIN_LEN_WITHOUT_POLISH) found = length
                    if (found != 0) break
                }
                length--
            }
            if (found != 0) {
                repeats++
                // 标注被删掉的那一份（没有润色稿时标注后一份）
                val start = if (hasPolish && removed(i, i + found)) i else i + found
                for (k in start until start + found) {
                    if (labels[k] == "cut" || labels[k] == "keep") labels[k] = "repeat"
                }
                i += 2 * found
            } else {
                i++
            }
        }

        val fillerCount = fillers.values.sum()
        fun per100(c: Int): Double = if (n != 0) c.toDouble() / n * 100 else 0.0
        return Analysis(
            chars = n,
            fillers = fillers,
            fillerCount = fillerCount,
            repeatCount = repeats,
            correctionCount = corrections,
            rewriteRatio = similarity?.let { Py.round(1 - it, 3) },
            cpm = if (durationS != null && durationS != 0.0) Py.round(n / durationS * 60, 1) else null,
            fluency = fluencyScore(per100(fillerCount)),
            concision = concisionScore(per100(repeats + corrections)),
            scoreable = n >= MIN_SCOREABLE_CHARS,
            highlights = segments(rawText, norm.index, labels),
            version = METRICS_VERSION,
        )
    }

    /** 每百字口癖数 → 流畅度。0 → 100，3 → 76，5 → 64，10 → 41 */
    fun fluencyScore(fillersPer100: Double): Double = Py.round(100 * exp(-FLUENCY_K * fillersPer100), 1)

    /** 每百字（重复 + 改口）数 → 简洁度。0 → 100，2 → 79，5 → 55 */
    fun concisionScore(issuesPer100: Double): Double = Py.round(100 * exp(-CONCISION_K * issuesPer100), 1)

    /** 把原文切成 [文本, 类型] 段；标点与空白一律视为保留 */
    private fun segments(rawText: String, index: IntArray, labels: Array<String>): List<Pair<String, String>> {
        val cps = Py.codePoints(rawText)
        val perChar = Array(cps.size) { "keep" }
        for (k in index.indices) perChar[index[k]] = labels[k]
        val segs = ArrayList<Pair<StringBuilder, String>>()
        for (p in cps.indices) {
            val label = perChar[p]
            if (segs.isNotEmpty() && segs.last().second == label) segs.last().first.appendCodePoint(cps[p])
            else segs.add(StringBuilder().appendCodePoint(cps[p]) to label)
        }
        return segs.map { it.first.toString() to it.second }
    }

    /** 汇总多条记录的指标（按字数加权，先汇总计数再算分）。只统计可评分的记录 */
    fun aggregate(input: List<MetricRow>): Aggregate {
        val rows = input.filter { it.scoreable }
        val chars = rows.sumOf { it.chars }
        val fillers = LinkedHashMap<String, Int>()
        for (r in rows) for ((w, c) in r.fillers) fillers[w] = (fillers[w] ?: 0) + c
        val fillerCount = fillers.values.sum()
        val issues = rows.sumOf { it.repeatCount + it.correctionCount }
        val ratios = rows.mapNotNull { it.rewriteRatio }
        fun per100(c: Int): Double = if (chars != 0) c.toDouble() / chars * 100 else 0.0
        return Aggregate(
            n = rows.size,
            chars = chars,
            fillers = fillers,
            fillerCount = fillerCount,
            fluency = if (chars != 0) fluencyScore(per100(fillerCount)) else null,
            concision = if (chars != 0) concisionScore(per100(issues)) else null,
            rewriteRatio = if (ratios.isNotEmpty()) Py.round(Py.sum(ratios) / ratios.size, 3) else null,
            fillerStats = fillers.entries
                .map { (w, c) -> FillerStat(w, c, Py.round(per100(c), 2)) }
                .sortedByDescending { it.count },
        )
    }
}

/** analyze() 的结果，字段与桌面端字典一致 */
data class Analysis(
    val chars: Int,
    val fillers: LinkedHashMap<String, Int>,
    val fillerCount: Int,
    val repeatCount: Int,
    val correctionCount: Int,
    val rewriteRatio: Double?,
    val cpm: Double?,
    val fluency: Double,
    val concision: Double,
    val scoreable: Boolean,
    val highlights: List<Pair<String, String>>,
    val version: Int,
) {
    fun fillersJson(): JsonObject = buildJsonObject { fillers.forEach { (w, c) -> put(w, c) } }

    fun highlightsJson(): JsonArray = highlightsToJson(highlights)

    fun toJson(): JsonObject = buildJsonObject {
        put("chars", chars)
        put("fillers", fillersJson())
        put("filler_count", fillerCount)
        put("repeat_count", repeatCount)
        put("correction_count", correctionCount)
        put("rewrite_ratio", rewriteRatio)
        put("cpm", cpm)
        put("fluency", fluency)
        put("concision", concision)
        put("scoreable", scoreable)
        put("highlights", highlightsJson())
        put("version", version)
    }

    fun toRow() = MetricRow(chars, fillers, repeatCount, correctionCount, rewriteRatio, scoreable)

    companion object {
        fun highlightsToJson(h: List<Pair<String, String>>): JsonArray = buildJsonArray {
            for ((text, label) in h) add(buildJsonArray { add(JsonPrimitive(text)); add(JsonPrimitive(label)) })
        }
    }
}

/** metrics.aggregate 的输入：一条记录的指标（来自数据库或 analyze） */
data class MetricRow(
    val chars: Int,
    val fillers: Map<String, Int>,
    val repeatCount: Int,
    val correctionCount: Int,
    val rewriteRatio: Double?,
    val scoreable: Boolean,
)

data class FillerStat(val word: String, val count: Int, val per100: Double) {
    fun toJson(): JsonObject = buildJsonObject {
        put("word", word)
        put("count", count)
        put("per100", per100)
    }
}

data class Aggregate(
    val n: Int,
    val chars: Int,
    val fillers: LinkedHashMap<String, Int>,
    val fillerCount: Int,
    val fluency: Double?,
    val concision: Double?,
    val rewriteRatio: Double?,
    val fillerStats: List<FillerStat>,
) {
    fun toJson(): JsonObject = buildJsonObject { fill(this) }

    internal fun fill(b: kotlinx.serialization.json.JsonObjectBuilder) = with(b) {
        put("n", n)
        put("chars", chars)
        put("fillers", buildJsonObject { fillers.forEach { (w, c) -> put(w, c) } })
        put("filler_count", fillerCount)
        put("fluency", fluency)
        put("concision", concision)
        put("rewrite_ratio", rewriteRatio)
        put("filler_stats", JsonArray(fillerStats.map { it.toJson() }))
    }

    fun fillerStatsJson(): JsonArray = JsonArray(fillerStats.map { it.toJson() })
}

/** coach.stats_of：aggregate + 重复 / 改口次数 + 每百字口癖（没有字数时为整数 0） */
data class Stats(
    val agg: Aggregate,
    val repeatCount: Int,
    val correctionCount: Int,
    val fillersPer100: Number,
) {
    val n get() = agg.n
    val chars get() = agg.chars
    val fluency get() = agg.fluency
    val concision get() = agg.concision

    fun toJson(): JsonObject = buildJsonObject {
        agg.fill(this)
        put("repeat_count", repeatCount)
        put("correction_count", correctionCount)
        put("fillers_per100", fillersPer100)
    }
}
