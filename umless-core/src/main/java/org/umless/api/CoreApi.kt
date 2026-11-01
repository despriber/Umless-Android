package org.umless.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umless.coach.Coach
import org.umless.coach.Metrics
import org.umless.coach.Practice
import org.umless.core.Clock
import org.umless.core.CoreHooks
import org.umless.core.DownloadSources
import org.umless.core.I18n.t
import org.umless.core.Jobs
import org.umless.core.Py
import org.umless.core.Umless
import org.umless.core.UmlessConfig
import org.umless.llm.LlmClient
import org.umless.llm.LlmConfig
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 与平台无关的后端接口，方法名、参数与返回结构同桌面端 webapi.py（见 Umless 主仓库 docs/ui-api.md）。
 * 平台相关的方法（输入法状态、麦克风权限、导出日志、模型下载、麦克风测试、练习录音、提示音试听等）由主 App 模块注册。
 */
object CoreApi {
    /** 与桌面端 webapi.PROVIDERS 相同；name 按界面语言替换 */
    private val PROVIDERS = listOf(
        Triple("ollama", "http://localhost:11434/v1", listOf("qwen3.5:2b", "qwen3.5:4b", "gemma3:4b")),
        Triple("deepseek", "https://api.deepseek.com/v1", listOf("deepseek-v4-flash", "deepseek-v4")),
        Triple("siliconflow", "https://api.siliconflow.cn/v1", listOf("Qwen/Qwen3-8B", "deepseek-ai/DeepSeek-V3.2")),
        Triple("dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen-flash", "qwen-plus")),
        Triple("zhipu", "https://open.bigmodel.cn/api/paas/v4", listOf("glm-4.5-flash", "glm-4.5-air")),
    )

    private var lastOutput: Pair<String, JsonObject>? = null

    // ------------------------------------------------------------ 参数

    private fun JsonArray.arg(i: Int): JsonElement? = getOrNull(i)?.takeIf { it !is JsonNull }

    /** Python 的 (x or "") */
    private fun JsonArray.str(i: Int): String = arg(i)?.takeIf { Py.truthy(it) }?.let { Py.str(it) } ?: ""

    /** Python 的 int(x or default) */
    private fun JsonArray.int(i: Int, default: Long? = null): Long {
        val v = arg(i)?.takeIf { Py.truthy(it) }
            ?: return default ?: throw ApiException("int() argument must be a number, not 'NoneType'")
        val p = v as? JsonPrimitive ?: throw ApiException("invalid int: $v")
        if (p.isString) return Py.strip(p.content).replace("_", "").toLongOrNull() ?: throw ApiException("invalid literal for int(): '${p.content}'")
        return when (p.content) {
            "true" -> 1
            "false" -> 0
            else -> p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.toLong() ?: throw ApiException("invalid int: ${p.content}")
        }
    }

    private fun reg(name: String, handler: suspend (JsonArray) -> JsonElement?) {
        UmlessApi.register(name) { args -> handler(args) ?: JsonNull }
    }

    private val db get() = Umless.db

    /** 让主 App 下次轮询 get_nav 时跳到某个页面（如键盘里的「设置」入口："settings/keyboard"） */
    fun requestNav(page: String) {
        db.setKv("nav", JsonPrimitive(page))
    }

    // ------------------------------------------------------------ 注册

    fun registerAll() {
        // ---- 1. 通用 ----
        reg("get_app_info") { appInfo() }
        reg("get_last_output") { lastOutput() }
        reg("list_sounds") {
            buildJsonObject {
                put("events", JsonArray(UmlessConfig.SOUND_EVENTS.map(::JsonPrimitive)))
                put("presets", JsonArray(UmlessConfig.SOUND_PRESETS.map(::JsonPrimitive)))
            }
        }
        reg("get_nav") {
            val page = db.getKv("nav")
            if (Py.truthy(page)) db.setKv("nav", null)
            page
        }
        reg("copy_text") { a ->
            Umless.env.copyText(a.str(0))
            null
        }
        reg("job_status") { a -> Jobs.status(a.str(0)) }

        // ---- 2. 概览 ----
        reg("get_overview") {
            Coach.refreshHabits()
            Coach.getOverview()
        }

        // ---- 3. 报告 ----
        reg("list_reports") {
            val data = Coach.listReports()
            val today = ((data["today"] as JsonObject)["date"] as JsonPrimitive).content
            JsonObject(LinkedHashMap(data).apply {
                put("items", JsonArray((data["items"] as JsonArray).filter { ((it as JsonObject)["date"] as JsonPrimitive).content != today }))
            })
        }
        reg("get_report") { a -> Coach.getReport(Py.str(a.arg(0))) }
        reg("run_diagnosis") { a ->
            val date = Py.str(a.arg(0))
            Jobs.start(message = t("api.organizing"), key = "diagnose:$date") { p -> Coach.diagnose(date, p) ?: JsonNull }
        }
        reg("get_trend") { a -> Coach.getTrend(a.int(0, 30).toInt()) }
        reg("get_habits") { a -> Coach.getHabits(a.arg(0)?.let(Py::str)) }
        reg("archive_habit") { a ->
            Coach.setHabitStatus(a.int(0), "archived")
            null
        }
        reg("restore_habit") { a ->
            val id = a.int(0)
            val row = db.query("SELECT achieved_at FROM habits WHERE id = ?", id).firstOrNull()
                ?: throw ApiException(t("api.habit_not_found"))
            Coach.setHabitStatus(id, if (row.str("achieved_at").isNullOrEmpty()) "active" else "achieved")
            null
        }
        reg("add_term") { a ->
            val right = Py.strip(a.str(0))
            if (right.isEmpty()) throw ApiException(t("api.term_empty"))
            val terms = UmlessConfig.glossary
            if (terms.none { it.lowercase(Locale.ROOT) == right.lowercase(Locale.ROOT) }) {
                UmlessConfig.savePatch(buildJsonObject {
                    put("glossary", buildJsonObject { put("terms", JsonArray((terms + right).map(::JsonPrimitive))) })
                })
            }
            null
        }
        reg("add_filler") { a ->
            val word = Py.strip(a.str(0))
            if (word.isEmpty()) throw ApiException(t("api.filler_empty"))
            if (Py.len(word) > 8) throw ApiException(t("api.filler_too_long"))
            if (db.addWords(listOf(word), "user").isEmpty()) db.setWordEnabled(word, true)
            Umless.recomputeLater()
            null
        }

        // ---- 4. 练习 ----
        reg("get_practice_topics") { practiceTopics() }
        reg("generate_topics") { a ->
            val topics = if (!Py.truthy(a.arg(0))) practiceTopics() else null
            if (topics != null) Jobs.start { topics }
            else Jobs.start(message = t("api.making_topics"), key = "topics") { p -> Practice.generateTopics(p) }
        }
        reg("list_practice") { a -> Practice.listPractice(a.int(0, 20).toInt()) }
        reg("get_practice") { a ->
            Practice.getPractice(a.int(0)) ?: throw ApiException(t("api.practice_not_found"))
        }

        // ---- 5. 历史 ----
        reg("list_history") { a ->
            Coach.listHistory(Py.strip(a.str(0)), a.int(1, 0).toInt(), a.int(2, 30).toInt())
        }
        reg("delete_history") { a ->
            val id = a.int(0)
            db.deleteUtterance(id)
            File(File(Umless.home, "audio"), "$id.wav").delete()
            null
        }

        // ---- 6. 设置 ----
        reg("get_settings") { settings() }
        reg("save_settings") { a ->
            val patch = a.arg(0) as? JsonObject ?: throw ApiException(t("cfg.bad_format"))
            UmlessConfig.savePatch(patch)
            settings()
        }
        reg("test_llm") { a -> testLlm(a.arg(0) as? JsonObject) }
        // 同时测试直连 GitHub 与镜像（模型、更新共用的下载源）
        reg("test_download_sources") { DownloadSources.test() }
        reg("list_llm_models") { a ->
            val cfg = llmCfg(buildJsonObject {
                a.arg(0)?.let { put("base_url", it) }
                a.arg(1)?.let { put("api_key", it) }
            })
            JsonArray(LlmClient(cfg, timeoutS = 10.0).listModels().map(::JsonPrimitive))
        }
        reg("get_lexicon") {
            Coach.ensureLexicon()
            JsonArray(db.lexicon(enabledOnly = false).map { r ->
                buildJsonObject {
                    put("word", r.str("word"))
                    put("source", r.str("source"))
                    put("enabled", r.bool("enabled"))
                }
            })
        }
        reg("remove_filler") { a ->
            val word = Py.str(a.arg(0))
            val row = db.query("SELECT source FROM lexicon WHERE word = ?", word).firstOrNull()
            // 内置词只能停用，否则下次启动会被补回
            if (row != null && row.str("source") == "builtin") db.setWordEnabled(word, false) else db.removeWord(word)
            Umless.recomputeLater()
            null
        }
        reg("toggle_filler") { a ->
            db.setWordEnabled(Py.str(a.arg(0)), Py.truthy(a.arg(1)))
            Umless.recomputeLater()
            null
        }
        reg("export_data") { exportData() }
        reg("clear_data") {
            db.clearAll()
            File(Umless.home, "audio").listFiles { f -> f.name.endsWith(".wav") }?.forEach { it.delete() }
            lastOutput = null
            null
        }
        reg("finish_onboarding") {
            UmlessConfig.savePatch(buildJsonObject { put("app", buildJsonObject { put("onboarded", true) }) })
            null
        }
    }

    // ------------------------------------------------------------ 实现

    fun appInfo(): JsonObject {
        val cfg = UmlessConfig.get()
        val setting = UmlessConfig.str("app.language", cfg)
        return buildJsonObject {
            put("version", Umless.env.appVersion())
            put("onboarded", UmlessConfig.bool("app.onboarded", cfg))
            put("tour_done", UmlessConfig.bool("app.tour_done", cfg))
            put("theme", UmlessConfig.str("app.theme", cfg))
            put("language", Umless.language())
            put("language_setting", if (setting in UmlessConfig.LANGUAGES) setting else "auto")
            put("hotkey", "") // Android 没有热键（长按空格说话）
            put("data_dir", Umless.home.path)
            put("core_running", true) // 键盘由系统管理，界面改用 get_ime_status()
            put("platform", "android")
        }
    }

    /** 最近一次输出。kv 里存原文与润色稿，原文标注在这里按当前口癖词表计算 */
    @Synchronized
    fun lastOutput(): JsonObject? {
        val data = db.getKv("last_output") as? JsonObject ?: return null
        if (!Py.truthy(data["text"])) return null
        val words = Coach.lexiconWords()
        val cacheKey = Py.dumps(data) + "\u0000" + words.joinToString("\u0000")
        lastOutput?.let { if (it.first == cacheKey) return it.second }
        val text = Py.str(data["text"])
        val raw = if (Py.truthy(data["raw"])) Py.str(data["raw"]) else text
        val polished = (data["polished"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val m = Metrics.analyze(raw, polished, null, words)
        val result = buildJsonObject {
            put("text", text)
            put("raw", raw)
            put("highlights", m.highlightsJson())
            put("filler_count", m.fillerCount)
            put("at", data["at"] ?: JsonNull)
        }
        lastOutput = cacheKey to result
        return result
    }

    private fun practiceTopics(): JsonObject? = Practice.getTopics(Umless.language())

    fun settings(): JsonObject = buildJsonObject {
        put("config", UmlessConfig.get())
        put("models", runCatching { CoreHooks.modelsInfo() }.getOrElse { buildJsonObject {} })
        put("providers", buildJsonArray {
            for ((id, url, models) in PROVIDERS) add(buildJsonObject {
                put("id", id)
                put("name", t("api.provider.$id"))
                put("base_url", url)
                put("models", JsonArray(models.map(::JsonPrimitive)))
            })
        })
        put("autostart", false)
    }

    /** 用界面传来的 base_url / api_key / model 覆盖当前配置（未传的用已保存的值） */
    private fun llmCfg(llm: JsonObject?): LlmConfig {
        val base = LlmConfig.fromConfig()
        val o = llm ?: JsonObject(emptyMap())
        fun s(k: String) = if (Py.truthy(o[k])) Py.str(o[k]) else null
        val key = o["api_key"]?.takeIf { it !is JsonNull }?.let(Py::str)
        return base.copy(
            baseUrl = Py.strip(s("base_url") ?: base.baseUrl),
            apiKey = Py.strip(key ?: base.apiKey),
            model = Py.strip(s("model") ?: base.model),
        )
    }

    suspend fun testLlm(llm: JsonObject?): JsonObject {
        val cfg = llmCfg(llm)
        val sentence = t("api.test_sentence") // 示例句子跟随界面语言
        fun fail(error: String) = buildJsonObject {
            put("ok", false); put("latency_ms", JsonNull); put("input", sentence); put("output", ""); put("error", error)
        }
        if (!cfg.baseUrl.startsWith("http://") && !cfg.baseUrl.startsWith("https://")) return fail(t("api.url_scheme"))
        val t0 = System.nanoTime()
        val out = try {
            LlmClient(cfg).polish(sentence, UmlessConfig.glossary)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(e.message ?: e.javaClass.simpleName)
        }
        return buildJsonObject {
            put("ok", out.isNotEmpty())
            put("latency_ms", (System.nanoTime() - t0) / 1_000_000)
            put("input", sentence)
            put("output", out)
            put("error", if (out.isNotEmpty()) null else t("api.empty_output"))
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val exportJson = Json { prettyPrint = true; prettyPrintIndent = " " }

    fun exportData(): JsonObject {
        val now = Clock.now()
        val data = JsonObject(linkedMapOf<String, JsonElement>(
            "app" to JsonPrimitive("Umless"),
            "version" to JsonPrimitive(Umless.env.appVersion()),
            "exported_at" to JsonPrimitive(Clock.isoOf(now)),
        ).apply { putAll(db.exportAll()) })
        val name = "umless-export-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))}.json"
        val text = exportJson.encodeToString(JsonObject.serializer(), data)
        val path = Umless.env.saveToDownloads(name, "application/json") { it.write(text.toByteArray(Charsets.UTF_8)) }
        return buildJsonObject { put("path", path) }
    }
}
