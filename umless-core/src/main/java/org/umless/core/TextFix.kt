package org.umless.core

/**
 * 识别结果后处理：修正 SenseVoice 输出的全大写英文（"HELLO WORLD" → "Hello world"）。移植自桌面端 textfix.py，
 * 规则见 spec/asr.md；常量与 spec/constants.json 的 asr 段一致（单元测试核对）。只处理 ASCII 字母，中文与其他文字原样保留。
 *
 * 下标直接用 UTF-16 下标：所有判断只看 ASCII、U+0080–U+02FF 与"≥ U+2E80"，代理项本身也 ≥ U+2E80，与按码点判断结果相同。
 */
object TextFix {
    // 常见英文短词（≤3 个字母、小写）：全大写时按单词处理改成小写；不在表里的短词视为缩写保留大写
    val COMMON_WORDS = listOf(
        "a", "ago", "ah", "all", "am", "an", "and", "any", "are", "as", "ask", "at",
        "bad", "be", "big", "but", "buy", "by", "can", "day", "did", "do", "few", "for",
        "get", "go", "got", "had", "has", "he", "her", "hey", "hi", "him", "his", "how",
        "if", "in", "is", "it", "its", "let", "lot", "man", "may", "me", "my", "new",
        "no", "nor", "not", "now", "of", "off", "oh", "old", "on", "one", "or", "our",
        "out", "own", "put", "run", "say", "see", "set", "she", "so", "the", "to", "too",
        "try", "two", "up", "us", "use", "was", "way", "we", "who", "why", "yes", "yet", "you",
    )
    const val SHORT_MAX = 3 // 不超过这个长度的全大写词才可能是缩写
    const val CJK_MIN = 0x2E80 // 码点 ≥ 此值视为中文语境（汉字、全角标点、假名、谚文、emoji 等）

    private val common = COMMON_WORDS.toHashSet()
    private const val WS = " \t\n\r\u000C\u000B" // 只认 ASCII 空白
    private const val SENTENCE_END = ".!?。！？"
    private val TOKEN = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*")

    private fun Char.isAsciiLetter() = this in 'A'..'Z' || this in 'a'..'z'
    private fun Char.isAsciiAlnum() = isAsciiLetter() || this in '0'..'9'
    private fun Char.isWs() = this in WS
    private fun Char.asciiLower() = if (this in 'A'..'Z') this + 32 else this
    private fun String.asciiLower() = String(CharArray(length) { this[it].asciiLower() })

    /** 紧贴单词的字符是数字、下划线或拉丁扩展字母：说明是型号 / 标识符 / 外文单词的一部分，不改大小写 */
    private fun Char.glued() = this in '0'..'9' || this == '_' || code in 0x80..0x2FF

    private class Span(var start: Int, var end: Int)

    /** 术语 [words] 在 [i] 处的匹配终点；不匹配返回 -1。只做 ASCII 大小写不敏感，词间是 ≥1 个 ASCII 空白 */
    private fun matchAt(text: String, i: Int, words: List<String>): Int {
        var p = i
        for ((k, w) in words.withIndex()) {
            if (k > 0) {
                if (p >= text.length || !text[p].isWs()) return -1
                while (p < text.length && text[p].isWs()) p++
            }
            if (p + w.length > text.length) return -1
            for (j in w.indices) if (text[p + j].asciiLower() != w[j].asciiLower()) return -1
            p += w.length
        }
        return p
    }

    /** 按术语表恢复大小写，返回新文本和被替换的区间（左闭右开） */
    private fun applyGlossary(input: String, glossary: List<String>): Pair<String, List<Span>> {
        val terms = glossary.map { g -> g.trim { it.isWs() } }
            .filter { g -> g.any { it.isAsciiLetter() } }
            .sortedByDescending { it.codePointCount(0, it.length) } // 长词优先；稳定排序，同长度保持原顺序
        var text = input
        val spans = mutableListOf<Span>()
        for (term in terms) {
            val words = term.split(Regex("[ \t\n\r\u000C\u000B]+"))
            var s = 0
            while (s < text.length) {
                val e = matchAt(text, s, words)
                val ok = e >= 0 &&
                    (s == 0 || !text[s - 1].isAsciiAlnum()) && (e == text.length || !text[e].isAsciiAlnum()) &&
                    spans.none { s < it.end && it.start < e }
                if (!ok) {
                    s++
                    continue
                }
                val delta = term.length - (e - s)
                text = text.substring(0, s) + term + text.substring(e)
                for (sp in spans) if (sp.start >= e) { sp.start += delta; sp.end += delta }
                spans.add(Span(s, s + term.length))
                s += term.length
            }
        }
        return text to spans
    }

    /** 从下标 i 起按 step 方向跳过空白后的第一个字符；到头返回 null */
    private fun neighbor(text: String, start: Int, step: Int): Char? {
        var i = start
        while (i in text.indices && text[i].isWs()) i += step
        return if (i in text.indices) text[i] else null
    }

    /** 单词孤立地夹在中文里（如"A 股""IT 部门"）：两侧都不是英文单词，且至少一侧是中文语境字符 */
    private fun inCjk(text: String, s: Int, e: Int): Boolean {
        val left = neighbor(text, s - 1, -1)
        val right = neighbor(text, e, 1)
        if (left?.isAsciiLetter() == true || right?.isAsciiLetter() == true) return false
        return (left != null && left.code >= CJK_MIN) || (right != null && right.code >= CJK_MIN)
    }

    private fun sentenceStart(text: String, s: Int): Boolean {
        val prev = neighbor(text, s - 1, -1)
        return prev == null || prev in SENTENCE_END
    }

    private fun fixWord(text: String, s: Int, e: Int): String {
        val w = text.substring(s, e)
        if (w.any { it in 'a'..'z' }) return w // 已有小写字母：模型或用户给出的大小写，不动
        if ((s > 0 && text[s - 1].glued()) || (e < text.length && text[e].glued())) return w
        if (w == "I" || (w[0] == 'I' && (w[1] == '\'' || w[1] == '’'))) return "I" + w.substring(1).asciiLower() // I、I'm、I'll …
        val plain = '\'' !in w && '’' !in w
        if (plain && w.length <= SHORT_MAX && (w.asciiLower() !in common || inCjk(text, s, e))) {
            return w // 缩写（GPU、API、OK），或夹在中文里的单个短词
        }
        val lower = w.asciiLower()
        return if (sentenceStart(text, s)) lower[0].uppercaseChar() + lower.substring(1) else lower
    }

    /** 修正全大写英文：先按术语表恢复写法，再把普通单词改成小写、句首大写，缩写保留大写 */
    fun fixCase(input: String, glossary: List<String>): String {
        if (input.none { it.isAsciiLetter() }) return input
        val (text, spans) = applyGlossary(input, glossary)
        val out = StringBuilder(text.length)
        var last = 0
        for (m in TOKEN.findAll(text)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (spans.any { s < it.end && it.start < e }) continue // 术语表替换出来的词
            out.append(text, last, s).append(fixWord(text, s, e))
            last = e
        }
        out.append(text, last, text.length)
        return out.toString()
    }
}
