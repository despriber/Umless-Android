package org.umless.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.fail
import org.umless.data.JdbcSqlDatabase
import org.umless.data.SqlDatabase
import org.umless.llm.HttpTransport
import org.umless.llm.LlmClient
import org.umless.llm.UrlConnectionTransport
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.time.LocalDateTime

/** JVM 测试用的平台：临时目录、sqlite-jdbc、classpath 资源 */
class TestPlatform(override val home: File, var lang: String = "zh") : UmlessPlatform {
    val copied = mutableListOf<String>()

    override fun openDatabase(file: File): SqlDatabase = JdbcSqlDatabase(file)
    override fun openAsset(path: String): InputStream =
        javaClass.classLoader!!.getResourceAsStream(path) ?: error("missing resource $path")
    override fun systemLanguage() = lang
    override fun appVersion() = "0.0.0-test"
    override fun exportsDir(): File = File(home, "exports").apply { mkdirs() }
    override fun copyText(text: String) { copied.add(text) }
    override fun deviceInfo() = mapOf("device" to "jvm")
    override fun logcat(priority: Int, tag: String, msg: String, e: Throwable?) {}
}

/** 每个测试一个干净的数据目录；时间固定，可手动推进 */
object TestEnv {
    var now: LocalDateTime = LocalDateTime.of(2026, 3, 10, 10, 0, 0)

    fun setup(lang: String = "zh"): TestPlatform {
        val dir = Files.createTempDirectory("umless-test").toFile()
        dir.deleteOnExit()
        now = LocalDateTime.of(2026, 3, 10, 10, 0, 0)
        Clock.provider = { now }
        LlmClient.transport = UrlConnectionTransport
        Jobs.scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val p = TestPlatform(dir, lang)
        Umless.init(p, warmup = false)
        return p
    }
}

/** 假的大模型：按顺序返回预设的 content，记录请求体 */
class FakeLlm(private val handler: (url: String, body: JsonObject?) -> HttpTransport.Response) : HttpTransport {
    val requests = mutableListOf<Pair<String, JsonObject?>>()

    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpTransport.Response {
        val b = body?.let { Json.parseToJsonElement(it) as JsonObject }
        synchronized(requests) { requests.add(url to b) }
        return handler(url, b)
    }

    companion object {
        fun content(text: String): HttpTransport.Response = HttpTransport.Response(
            200, JsonObject(mapOf("choices" to JsonArray(listOf(JsonObject(mapOf(
                "message" to JsonObject(mapOf("content" to JsonPrimitive(text))),
            )))))).toString(),
        )
    }
}

/** spec/ 资源 */
object SpecFiles {
    fun list(dir: String): List<Pair<String, JsonObject>> {
        val url = javaClass.classLoader!!.getResource("spec/$dir") ?: error("missing spec/$dir (syncUmlessShared?)")
        val files = File(url.toURI()).listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        return files.map { it.name to Json.parseToJsonElement(it.readText(Charsets.UTF_8)) as JsonObject }
    }
}

/** 严格比较：int 与 float 区分、double 严格相等；对象不看键顺序，数组看顺序 */
object JsonAssert {
    fun diff(expected: JsonElement, actual: JsonElement, path: String = "$"): String? {
        when (expected) {
            JsonNull -> return if (actual is JsonNull) null else "$path: expected null, got $actual"
            is JsonPrimitive -> {
                if (actual !is JsonPrimitive || actual is JsonNull) return "$path: expected $expected, got $actual"
                if (expected.isString || actual.isString) {
                    return if (expected.isString && actual.isString && expected.content == actual.content) null
                    else "$path: expected $expected, got $actual"
                }
                val eb = expected.content == "true" || expected.content == "false"
                val ab = actual.content == "true" || actual.content == "false"
                if (eb || ab) return if (expected.content == actual.content) null else "$path: expected $expected, got $actual"
                val ef = Py.isFloatLiteral(expected)
                val af = Py.isFloatLiteral(actual)
                if (ef != af) return "$path: expected ${if (ef) "float" else "int"} $expected, got $actual"
                return if (ef) {
                    if (expected.content.toDouble() == actual.content.toDouble()) null else "$path: expected $expected, got $actual"
                } else {
                    if (expected.content.toBigInteger() == actual.content.toBigInteger()) null else "$path: expected $expected, got $actual"
                }
            }
            is JsonArray -> {
                if (actual !is JsonArray) return "$path: expected array, got $actual"
                if (expected.size != actual.size) return "$path: expected ${expected.size} items, got ${actual.size}: $actual"
                expected.indices.forEach { i -> diff(expected[i], actual[i], "$path[$i]")?.let { return it } }
                return null
            }
            is JsonObject -> {
                if (actual !is JsonObject) return "$path: expected object, got $actual"
                if (expected.keys != actual.keys) return "$path: keys ${expected.keys} != ${actual.keys}"
                for ((k, v) in expected) diff(v, actual.getValue(k), "$path.$k")?.let { return it }
                return null
            }
        }
    }

    fun assertEquals(name: String, expected: JsonElement, actual: JsonElement) {
        diff(expected, actual)?.let { fail("[$name] $it") }
    }
}
