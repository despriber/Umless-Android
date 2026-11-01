package org.umless.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umless.api.ApiException
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 配置：home/config.json。结构与桌面端 Config.to_dict() 相同（app / audio / asr / llm / polish / scenes /
 * coach / privacy / glossary …），另加 Android 专属项：voice.space_long_press、voice.toolbar_button、
 * voice.haptic、privacy.skip_incognito；场景规则与黑名单里的 app 是 Android 包名。
 * 读取时与默认值深度合并；保存用深度合并补丁（列表整体替换），与桌面端 save_patch 语义一致。
 */
object UmlessConfig {
    val SCENES = listOf("chat", "document", "code", "email", "default", "search") // search 仅 Android（搜索框 / 网址栏）
    val INTENSITIES = listOf("faithful", "moderate", "restructure")
    val LANGUAGES = listOf("auto", "zh", "en")
    val ENGINES = listOf("sensevoice", "openai")
    val SOUND_EVENTS = listOf("start", "stop", "done", "error") // 开始录音 / 结束录音 / 文字已输出 / 出错或没听清
    val SOUND_PRESETS = listOf("rise", "fall", "pop", "drop", "chime", "tick", "soft", "classic", "low")
    val HOLD_MS_RANGE = 100..1500 // 轻点与按住的分界

    private val CHAT_APPS = listOf(
        "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.wework", "com.alibaba.android.rimet",
        "com.ss.android.lark", "org.telegram.messenger", "com.whatsapp", "com.Slack", "com.discord",
    )
    private val DOCUMENT_APPS = listOf(
        "cn.wps.moffice_eng", "notion.id", "md.obsidian", "com.coloros.note", "com.miui.notes",
        "com.samsung.android.app.notes", "com.google.android.keep", "com.evernote", "com.youdao.note",
    )
    private val CODE_APPS = listOf("com.termux")
    private val EMAIL_APPS = listOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.tencent.androidqqmail", "com.netease.mail",
    )

    /** 默认不记录的应用：密码管理器、常见银行与支付 App */
    val DEFAULT_EXCLUDE_APPS = listOf(
        "com.x8bit.bitwarden", "com.agilebits.onepassword", "keepass2android.keepass2android",
        "com.kunzisoft.keepass.free", "com.lastpass.lpandroid", "com.dashlane",
        "com.chinamworld.main", "com.icbc", "cmb.pb", "com.chinamworld.bocmbci", "com.android.bankabc",
        "com.bankcomm.Bankcomm", "com.eg.android.AlipayGphone", "com.unionpay",
    )

    val DEFAULTS: JsonObject = buildJsonObject {
        put("app", buildJsonObject {
            put("onboarded", false)
            put("tour_done", false)
            put("theme", "system")
            put("language", "auto")
        })
        // 提示音（桌面端同结构）；Android 没有热键、输出方式、提示条，也不保存录音（无 mic_always_on / keep_audio）
        put("audio", buildJsonObject {
            put("beep", true)
            put("volume", 60)
            put("sounds", buildJsonObject {
                put("start", "rise"); put("stop", "fall"); put("done", ""); put("error", "low")
            })
        })
        put("asr", buildJsonObject {
            put("engine", "sensevoice")
            put("num_threads", 4)
            put("openai", buildJsonObject {
                put("base_url", "https://api.siliconflow.cn/v1")
                put("api_key", "")
                put("model", "FunAudioLLM/SenseVoiceSmall")
            })
        })
        // 手机上没有本地 Ollama：接口地址与模型留空，由新手引导填写
        put("llm", buildJsonObject {
            put("enabled", true)
            put("base_url", "")
            put("api_key", "")
            put("model", "")
            put("temperature", 0.2)
            put("timeout_s", 20)
            put("extra_body", buildJsonObject { })
        })
        put("polish", buildJsonObject { put("intensity", "moderate") })
        put("scenes", buildJsonObject {
            put("enabled", true)
            put("intensity", buildJsonObject {
                put("chat", ""); put("document", "restructure"); put("code", ""); put("email", "restructure"); put("default", "")
            })
            put("rules", buildJsonArray {
                for ((scene, apps) in listOf("chat" to CHAT_APPS, "document" to DOCUMENT_APPS, "code" to CODE_APPS, "email" to EMAIL_APPS)) {
                    for (a in apps) add(buildJsonObject { put("app", a); put("scene", scene) })
                }
            })
        })
        put("coach", buildJsonObject {
            put("enabled", true)
            put("auto_daily", true)
            put("min_utterances", 5)
            put("model", "")
        })
        put("privacy", buildJsonObject {
            put("record_history", true)
            put("exclude_apps", JsonArray(DEFAULT_EXCLUDE_APPS.map(::JsonPrimitive)))
            put("retention_days", 0)
            put("skip_incognito", true) // 无痕 / 隐私输入框里照常输入，但不记录
        })
        put("glossary", buildJsonObject { put("terms", JsonArray(emptyList())) })
        // Android：语音入口与反馈
        put("voice", buildJsonObject {
            put("space_long_press", true)
            put("toolbar_button", true)
            put("haptic", true)
            put("min_hold_ms", 300)
        })
        // 应用更新（spec/update.md）：skipped_version、manifest_url 不在设置界面显示
        put("update", buildJsonObject {
            put("auto_check", true)
            put("skipped_version", "") // 用户点过「跳过此版本」的版本号，自动检查不再提示它
            put("manifest_url", "")    // 覆盖清单地址（测试或自建镜像），空 = GitHub Releases
        })
    }

    // 任意键的字典：用户的值整体使用（extra_body）或与默认值合并（scenes.intensity、audio.sounds）
    private val OPEN_DICTS = setOf("llm.extra_body", "scenes.intensity", "audio.sounds")

    private val json = Json { prettyPrint = true }
    private val lock = Any()
    private var file: File? = null
    private var cached: JsonObject = DEFAULTS
    private var stamp = 0L to 0L
    private var lastCheck = 0L
    private val listeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()

    internal val isLoaded: Boolean get() = file != null

    /** 由 Umless.init 调用 */
    internal fun load(f: File) {
        synchronized(lock) {
            file = f
            reload()
        }
    }

    private fun stampOf(f: File) = f.lastModified() to f.length()

    private fun readRaw(): JsonObject {
        val f = file ?: return JsonObject(emptyMap())
        if (!f.exists()) return JsonObject(emptyMap())
        return try {
            Json.parseToJsonElement(f.readText(Charsets.UTF_8)) as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: Exception) {
            UmlessLog.w("Config", "配置文件无法解析，使用默认值", e)
            JsonObject(emptyMap())
        }
    }

    private fun reload() {
        val f = file ?: return
        cached = withDefaults(readRaw())
        stamp = stampOf(f)
        lastCheck = System.currentTimeMillis()
    }

    /** 当前完整配置（已与默认值合并） */
    fun get(): JsonObject = synchronized(lock) {
        val f = file
        val now = System.currentTimeMillis()
        // 其他进程（或手动）改了文件时重新读取；最多每秒检查一次
        if (f != null && now - lastCheck > 1000) {
            lastCheck = now
            if (stampOf(f) != stamp) reload()
        }
        cached
    }

    /** 校验并深度合并补丁、写盘，返回新配置；校验失败抛出 org.umless.api.ApiException */
    fun savePatch(patch: JsonObject): JsonObject {
        val result = synchronized(lock) {
            val normalized = validate(patch)
            val merged = mergePatch(readRaw(), normalized)
            val f = file
            if (f != null) {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                tmp.writeText(json.encodeToString(JsonObject.serializer(), merged), Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    if (!tmp.renameTo(f)) throw ApiException("cannot write ${f.path}")
                }
            }
            cached = withDefaults(merged)
            if (f != null) stamp = stampOf(f)
            cached
        }
        for (l in listeners) {
            try {
                l(result)
            } catch (e: Exception) {
                UmlessLog.e("Config", "配置监听回调失败", e)
            }
        }
        return result
    }

    /** 配置变化监听（任何进程内的 savePatch 都会触发） */
    fun addListener(listener: (JsonObject) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (JsonObject) -> Unit) {
        listeners.remove(listener)
    }

    // ---- 语音输入流程常用的读取 ----
    /** 润色是否可用：开关打开且已填写接口地址 */
    val llmEnabled: Boolean get() = bool("llm.enabled") && str("llm.base_url").isNotBlank()
    val asrEngine: String get() = str("asr.engine").takeIf { it in ENGINES } ?: "sensevoice"   // sensevoice / openai
    val numThreads: Int get() = int("asr.num_threads", 4)
    val recordHistory: Boolean get() = bool("privacy.record_history")
    val skipIncognito: Boolean get() = bool("privacy.skip_incognito")
    val spaceLongPress: Boolean get() = bool("voice.space_long_press")
    val toolbarButton: Boolean get() = bool("voice.toolbar_button")
    val haptic: Boolean get() = bool("voice.haptic")
    /** Android 不保存录音（配置里没有 audio.keep_audio），恒为 false */
    val keepAudio: Boolean get() = false

    /** 按住多久算"按住说话"（毫秒），更短视为轻点 */
    val minHoldMs: Int get() = int("voice.min_hold_ms", 300)

    val glossary: List<String> get() = strings("glossary.terms")

    // ---- 应用更新 ----
    val updateAutoCheck: Boolean get() = bool("update.auto_check")
    val updateSkippedVersion: String get() = str("update.skipped_version").trim()
    val updateManifestUrl: String get() = str("update.manifest_url").trim()

    /** 包名 → 场景（chat / document / code / email / default）；scenes.enabled=false 时为 default */
    fun sceneOf(packageName: String?): String {
        if (!bool("scenes.enabled") || packageName.isNullOrEmpty()) return "default"
        val name = packageName.lowercase(Locale.ROOT)
        for (rule in (path("scenes.rules") as? JsonArray).orEmpty()) {
            val r = rule as? JsonObject ?: continue
            val app = Py.str(r["app"] ?: JsonPrimitive("")).lowercase(Locale.ROOT)
            val scene = (r["scene"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (app == name && scene in SCENES) return scene!!
        }
        return "default"
    }

    /** 场景 → 润色强度（faithful / moderate / restructure），场景未单独设置时用全局强度；search 固定为忠实 */
    fun intensityOf(scene: String): String {
        if (scene == "search") return "faithful"
        if (bool("scenes.enabled")) {
            val override = (path("scenes.intensity.$scene") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (override in INTENSITIES) return override!!
        }
        return str("polish.intensity").takeIf { it in INTENSITIES } ?: "moderate"
    }

    fun isExcluded(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        val name = packageName.lowercase(Locale.ROOT)
        return strings("privacy.exclude_apps").any { it.lowercase(Locale.ROOT) == name }
    }

    // ---- 按路径读取（如 "llm.base_url"） ----

    fun path(p: String, cfg: JsonObject = get()): JsonElement? {
        var cur: JsonElement? = cfg
        for (part in p.split('.')) cur = (cur as? JsonObject)?.get(part) ?: return null
        return cur
    }

    fun str(p: String, cfg: JsonObject = get()): String =
        (path(p, cfg) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""

    fun bool(p: String, cfg: JsonObject = get()): Boolean = Py.truthy(path(p, cfg))

    fun int(p: String, default: Int = 0, cfg: JsonObject = get()): Int =
        Py.toFloat(path(p, cfg))?.takeIf { it.isFinite() }?.toInt() ?: default

    fun double(p: String, default: Double = 0.0, cfg: JsonObject = get()): Double =
        Py.toFloat(path(p, cfg))?.takeIf { !it.isNaN() } ?: default

    fun strings(p: String, cfg: JsonObject = get()): List<String> =
        (path(p, cfg) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { x -> x !is JsonNull }?.content }.orEmpty()

    // ---- 合并 ----

    /** 与默认值合并：只保留默认值里声明过的键（同桌面端 _pick），未知键忽略 */
    fun withDefaults(raw: JsonObject): JsonObject = pick(DEFAULTS, raw, "")

    private fun pick(defaults: JsonObject, raw: JsonObject, prefix: String): JsonObject = buildJsonObject {
        for ((k, dv) in defaults) {
            val key = if (prefix.isEmpty()) k else "$prefix.$k"
            val uv = raw[k]
            val value: JsonElement = when {
                uv == null -> dv
                key == "llm.extra_body" -> if (uv is JsonObject) uv else dv
                key == "scenes.intensity" -> if (uv is JsonObject) JsonObject(LinkedHashMap(dv as JsonObject).apply { putAll(uv) }) else dv
                key == "audio.sounds" -> if (uv is JsonObject) buildJsonObject {
                    for ((ev, d) in dv as JsonObject) put(ev, uv[ev]?.let { JsonPrimitive(Py.str(it)) } ?: d)
                } else dv
                key == "audio.volume" -> Py.toFloat(uv)?.takeIf { it.isFinite() }?.let { JsonPrimitive(it.toInt().coerceIn(0, 100)) } ?: dv
                dv is JsonObject -> if (uv is JsonObject) pick(dv, uv, key) else dv
                else -> uv
            }
            put(k, value)
        }
    }

    /** 深度合并补丁（字典递归合并，其余值包括列表整体替换），保留文件里的未知键 */
    fun mergePatch(base: JsonObject, patch: JsonObject): JsonObject {
        val out = LinkedHashMap(base)
        for ((k, v) in patch) {
            val old = out[k]
            out[k] = if (v is JsonObject && old is JsonObject) mergePatch(old, v) else v
        }
        return JsonObject(out)
    }

    // ---- 校验（移植 webapi._validate，只保留 Android 上存在的项） ----

    private fun bad(key: String, vararg args: Pair<String, Any?>): Nothing = throw ApiException(I18n.t(key, *args))

    private fun isInt(v: JsonElement?): Boolean =
        v is JsonPrimitive && !v.isString && v !is JsonNull && v.content != "true" && v.content != "false" && !Py.isFloatLiteral(v)

    /** 提示音取值：预设 id、空串，或存在的 .wav 绝对路径（同桌面端 _check_sound） */
    fun checkSound(sound: JsonElement?): String {
        val value = (sound as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (value != null && (value.isEmpty() || value in SOUND_PRESETS || isWavFile(value))) return value
        if (value != null && value.lowercase(Locale.ROOT).endsWith(".wav") && File(value).isAbsolute) {
            bad("cfg.sound_missing", "path" to value)
        }
        bad("cfg.bad_sound", "value" to Py.str(sound))
    }

    private fun isWavFile(v: String): Boolean {
        val f = File(v)
        return v.lowercase(Locale.ROOT).endsWith(".wav") && f.isAbsolute && f.isFile
    }

    /** 校验补丁，返回规范化后的补丁（音量转为整数、提示音去空白） */
    fun validate(patch: JsonObject): JsonObject {
        for (section in listOf("app", "audio", "polish", "scenes", "asr", "privacy", "voice", "llm", "coach", "glossary", "update")) {
            if (section in patch && patch[section] !is JsonObject) bad("cfg.bad_format")
        }
        fun sec(name: String) = patch[name] as? JsonObject ?: JsonObject(emptyMap())

        val lang = sec("app")["language"]
        if (lang != null && lang !is JsonNull && !(lang is JsonPrimitive && lang.isString && lang.content in LANGUAGES)) {
            bad("cfg.bad_language", "value" to Py.str(lang))
        }
        fun checkHold(v: JsonElement?) {
            if (v == null) return
            val ms = if (isInt(v)) (v as JsonPrimitive).content.toLongOrNull() else null
            if (ms == null || ms !in HOLD_MS_RANGE.first..HOLD_MS_RANGE.last) {
                bad("cfg.bad_hold_ms", "min" to HOLD_MS_RANGE.first, "max" to HOLD_MS_RANGE.last)
            }
        }
        checkHold(sec("voice")["min_hold_ms"])

        var audio = sec("audio")
        audio["volume"]?.let { vol ->
            val d = (vol as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull && it.content != "true" && it.content != "false" }
                ?.content?.toDoubleOrNull()
            if (d == null || d < 0 || d > 100 || d != Math.floor(d)) bad("cfg.bad_volume")
            audio = JsonObject(LinkedHashMap(audio).apply { put("volume", JsonPrimitive(d.toInt())) })
        }
        audio["sounds"]?.let { sounds ->
            if (sounds !is JsonObject) bad("cfg.bad_format")
            val out = LinkedHashMap<String, JsonElement>()
            for ((event, value) in sounds) {
                if (event !in SOUND_EVENTS) bad("cfg.bad_sound_event", "event" to event)
                out[event] = JsonPrimitive(checkSound(value))
            }
            audio = JsonObject(LinkedHashMap(audio).apply { put("sounds", JsonObject(out)) })
        }

        val intensity = sec("polish")["intensity"]
        if (intensity != null && intensity !is JsonNull && !(intensity is JsonPrimitive && intensity.isString && intensity.content in INTENSITIES)) {
            bad("cfg.bad_intensity", "value" to Py.str(intensity))
        }
        val scenes = sec("scenes")
        val si = scenes["intensity"]
        if (si != null && si !is JsonNull) {
            if (si !is JsonObject) bad("cfg.bad_format")
            for ((scene, v) in si) {
                val ok = scene in SCENES && (!Py.truthy(v) || (v is JsonPrimitive && v.isString && v.content in INTENSITIES))
                if (!ok) bad("cfg.bad_scene_intensity", "scene" to scene, "value" to Py.str(v))
            }
        }
        val rules = scenes["rules"]
        if (rules != null && rules !is JsonNull) {
            if (rules !is JsonArray) bad("cfg.bad_rule")
            for (rule in rules) {
                val r = rule as? JsonObject ?: bad("cfg.bad_rule")
                val scene = (r["scene"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!Py.truthy(r["app"]) || scene !in SCENES) bad("cfg.bad_rule")
            }
        }
        val engine = sec("asr")["engine"]
        if (engine != null && engine !is JsonNull && !(engine is JsonPrimitive && engine.isString && engine.content in ENGINES)) {
            bad("cfg.bad_engine", "engine" to Py.str(engine))
        }
        val days = sec("privacy")["retention_days"]
        if (days != null && days !is JsonNull && (!isInt(days) || ((days as JsonPrimitive).content.toLongOrNull() ?: -1) < 0)) {
            bad("cfg.bad_retention")
        }
        val apps = sec("privacy")["exclude_apps"]
        if (apps != null && apps !is JsonArray) bad("cfg.bad_format")
        val terms = sec("glossary")["terms"]
        if (terms != null && terms !is JsonArray) bad("cfg.bad_format")
        // 更新：auto_check 是布尔值；skipped_version 是字符串（可为空）；manifest_url 为空或 http(s) 地址
        val update = sec("update")
        update["auto_check"]?.let { v ->
            if (!(v is JsonPrimitive && !v.isString && (v.content == "true" || v.content == "false"))) bad("cfg.bad_format")
        }
        update["skipped_version"]?.let { v ->
            if (v !is JsonNull && !(v is JsonPrimitive && v.isString)) bad("cfg.bad_format")
        }
        update["manifest_url"]?.let { v ->
            val url = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: if (v is JsonNull) "" else bad("cfg.bad_format")
            if (url.isNotEmpty() && !url.startsWith("https://") && !url.startsWith("http://")) bad("cfg.bad_format")
        }

        return if ("audio" in patch) JsonObject(LinkedHashMap(patch).apply { put("audio", audio) }) else patch
    }
}
