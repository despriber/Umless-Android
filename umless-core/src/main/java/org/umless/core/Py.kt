package org.umless.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * 复刻 Python 语义的小工具（见 spec/metrics.md 第 0 节）：码点、HALF_EVEN 取整、Neumaier 求和、
 * float() 解析、真值判断、str() 与 json.dumps(ensure_ascii=False) 的输出格式。
 */
object Py {
    // ---------------------------------------------------------------- 码点

    fun codePoints(s: String): IntArray {
        val out = IntArray(s.codePointCount(0, s.length))
        var i = 0
        var k = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out[k++] = cp
            i += Character.charCount(cp)
        }
        return out
    }

    fun fromCodePoints(cps: IntArray, from: Int = 0, to: Int = cps.size): String {
        val sb = StringBuilder(to - from)
        for (k in from until to) sb.appendCodePoint(cps[k])
        return sb.toString()
    }

    /** Python len(str) */
    fun len(s: String): Int = s.codePointCount(0, s.length)

    /** Python s[:n] */
    fun take(s: String, n: Int): String {
        if (n <= 0) return ""
        val count = len(s)
        if (count <= n) return s
        return s.substring(0, s.offsetByCodePoints(0, n))
    }

    /** Python 字符串排序（按码点，而不是 UTF-16 码元） */
    val codePointOrder: Comparator<String> = Comparator { a, b ->
        val x = codePoints(a)
        val y = codePoints(b)
        val n = minOf(x.size, y.size)
        for (i in 0 until n) if (x[i] != y[i]) return@Comparator x[i].compareTo(y[i])
        x.size.compareTo(y.size)
    }

    // ---------------------------------------------------------------- 数值

    /** CPython ≥ 3.12 的 sum()：Neumaier 补偿求和 */
    fun sum(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        var f = xs[0]
        var c = 0.0
        for (k in 1 until xs.size) {
            val x = xs[k]
            val t = f + x
            c += if (abs(f) >= abs(x)) (f - t) + x else (x - t) + f
            f = t
        }
        if (c != 0.0 && c.isFinite()) f += c
        return f
    }

    /** Python round(x, n)：按 double 的精确二进制值做 HALF_EVEN */
    fun round(x: Double, n: Int): Double {
        if (!x.isFinite()) return x
        val r = BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toDouble()
        return if (r == 0.0 && (x < 0 || (x == 0.0 && 1.0 / x < 0))) -0.0 else r
    }

    /** Python round(x)（返回整数，HALF_EVEN） */
    fun roundInt(x: Double): Long {
        require(x.isFinite()) { "cannot convert float $x to integer" }
        return Math.rint(x).toLong()
    }

    /** Python f"{x:.0f}" */
    fun fmt0(x: Double): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        val s = BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).toPlainString()
        return if (s == "0" && (x < 0 || 1.0 / x < 0)) "-0" else s
    }

    /** Python repr(float)：最短往返表示，指数 < -4 或 ≥ 16 时用科学计数法 */
    fun repr(x: Double): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        if (x == 0.0) return if (1.0 / x < 0) "-0.0" else "0.0"
        val exact = BigDecimal(x)
        var digits: BigDecimal = exact
        for (p in 1..17) {
            val r = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
            if (r.toDouble() == x) {
                digits = r
                break
            }
        }
        val neg = digits.signum() < 0
        val unscaled = digits.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
        // 十进制指数：value = 0.d1d2... × 10^(exp+1)
        val exp = digits.precision() - digits.scale() - 1
        val sb = StringBuilder()
        if (neg) sb.append('-')
        if (exp < -4 || exp >= 16) {
            sb.append(unscaled[0])
            if (unscaled.length > 1) sb.append('.').append(unscaled, 1, unscaled.length)
            sb.append('e').append(if (exp < 0) '-' else '+')
            val e = abs(exp)
            if (e < 10) sb.append('0')
            sb.append(e)
        } else if (exp < 0) {
            sb.append("0.")
            repeat(-exp - 1) { sb.append('0') }
            sb.append(unscaled)
        } else {
            if (unscaled.length <= exp + 1) {
                sb.append(unscaled)
                repeat(exp + 1 - unscaled.length) { sb.append('0') }
                sb.append(".0")
            } else {
                sb.append(unscaled, 0, exp + 1).append('.').append(unscaled, exp + 1, unscaled.length)
            }
        }
        return sb.toString()
    }

    /** JSON 数字字面量是否为浮点（Python 解析后是 float 而不是 int） */
    fun isFloatLiteral(p: JsonPrimitive): Boolean {
        if (p.isString) return false
        val c = p.content
        return c.contains('.') || c.contains('e') || c.contains('E') || c == "NaN" ||
            c.contains("Infinity")
    }

    /**
     * Python float(v)：数字、布尔（1/0）、可解析的字符串（允许首尾空白、nan / inf、数字间下划线）；
     * 其余（null、数组、对象、无法解析的字符串）返回 null
     */
    fun toFloat(v: JsonElement?): Double? {
        if (v == null || v is JsonNull || v !is JsonPrimitive) return null
        if (!v.isString) {
            return when (v.content) {
                "true" -> 1.0
                "false" -> 0.0
                else -> v.content.toDoubleOrNull()
            }
        }
        return parseFloat(v.content)
    }

    private val FLOAT_RE = Regex("[+-]?(?:\\d+(?:_\\d+)*(?:\\.(?:\\d+(?:_\\d+)*)?)?|\\.\\d+(?:_\\d+)*)(?:[eE][+-]?\\d+(?:_\\d+)*)?")

    fun parseFloat(text: String): Double? {
        // Python 接受任意 Unicode 十进制数字
        val sb = StringBuilder()
        for (cp in codePoints(text.trim())) {
            val d = if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) Character.digit(cp, 10) else -1
            if (d >= 0) sb.append(('0' + d)) else sb.appendCodePoint(cp)
        }
        val s = sb.toString()
        val lower = s.lowercase(java.util.Locale.ROOT)
        val sign = if (lower.startsWith("-")) -1.0 else 1.0
        val body = lower.removePrefix("+").removePrefix("-")
        if (body == "nan") return Double.NaN
        if (body == "inf" || body == "infinity") return sign * Double.POSITIVE_INFINITY
        if (!FLOAT_RE.matches(s)) return null
        return s.replace("_", "").toDoubleOrNull()
    }

    /** Python 的 max(1.0, min(5.0, x))（NaN → 1.0，与 Python 的比较语义一致） */
    fun clamp(x: Double, lo: Double, hi: Double): Double {
        val a = if (x > lo) x else lo      // max(lo, x)：x > lo 才取 x，NaN 时取 lo
        return if (a < hi) a else hi       // min(hi, a)
    }

    // ---------------------------------------------------------------- 真值与字符串

    /** Python 的 bool(v) */
    fun truthy(v: JsonElement?): Boolean = when (v) {
        null, JsonNull -> false
        is JsonPrimitive -> if (v.isString) v.content.isNotEmpty() else when (v.content) {
            "true" -> true
            "false" -> false
            else -> v.content.toDoubleOrNull()?.let { it != 0.0 } ?: true
        }
        is JsonArray -> v.isNotEmpty()
        is JsonObject -> v.isNotEmpty()
    }

    /** Python 的 str(v) */
    fun str(v: JsonElement?): String = when (v) {
        null, JsonNull -> "None"
        is JsonPrimitive -> when {
            v.isString -> v.content
            v.content == "true" -> "True"
            v.content == "false" -> "False"
            isFloatLiteral(v) -> v.content.toDoubleOrNull()?.let(::repr) ?: v.content
            else -> v.content
        }
        else -> pyRepr(v)
    }

    private fun pyRepr(v: JsonElement): String = when (v) {
        JsonNull -> "None"
        is JsonPrimitive -> if (v.isString) "'" + v.content.replace("\\", "\\\\").replace("'", "\\'") + "'" else str(v)
        is JsonArray -> v.joinToString(", ", "[", "]") { pyRepr(it) }
        is JsonObject -> v.entries.joinToString(", ", "{", "}") { (k, x) -> pyRepr(JsonPrimitive(k)) + ": " + pyRepr(x) }
    }

    /** Python str.strip() */
    fun strip(s: String): String = s.trim()

    // ---------------------------------------------------------------- json.dumps(ensure_ascii=False)

    fun dumps(v: JsonElement?): String = StringBuilder().also { dumpTo(it, v ?: JsonNull) }.toString()

    private fun dumpTo(sb: StringBuilder, v: JsonElement) {
        when (v) {
            JsonNull -> sb.append("null")
            is JsonPrimitive -> when {
                v.isString -> quote(sb, v.content)
                v.content == "true" || v.content == "false" -> sb.append(v.content)
                isFloatLiteral(v) -> {
                    val d = v.content.toDoubleOrNull()
                    sb.append(
                        when {
                            d == null -> v.content
                            d.isNaN() -> "NaN"
                            d == Double.POSITIVE_INFINITY -> "Infinity"
                            d == Double.NEGATIVE_INFINITY -> "-Infinity"
                            else -> repr(d)
                        }
                    )
                }
                else -> sb.append(v.content)
            }
            is JsonArray -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(", "); dumpTo(sb, x) }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(", ")
                    first = false
                    quote(sb, k)
                    sb.append(": ")
                    dumpTo(sb, x)
                }
                sb.append('}')
            }
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }
}
