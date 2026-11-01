package org.umless.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.umless.api.ApiException
import org.umless.api.ApiHandler
import org.umless.api.UmlessApi
import org.umless.core.Jobs
import org.umless.core.PracticeEval
import org.umless.core.Umless
import org.umless.core.UmlessLog
import org.umless.voice.ModelManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.round

/**
 * 需要 Context / Activity 的后端接口（与平台无关的接口由 :umless-core 注册）。
 * 方法名、参数、返回值与 docs/ui-api.md 一致；Android 专用的方法见其中「Android 桥接」。
 *
 * 必须在 Umless.init 之后调用 [register]：同名方法以这里为准；标为「缺省」的方法只在别处没有注册时补上。
 */
object PlatformApi {
    private const val TAG = "PlatformApi"

    /** 麦克风测试录音时长，同桌面端 MIC_TEST_S；录音阶段 progress 0–0.8，识别阶段 ≥ 0.85 */
    private const val MIC_TEST_MS = 4000L
    private const val MIC_REC_PROGRESS = 0.8f
    private const val UPSTREAM_SETTINGS = "org.fcitx.fcitx5.android.ui.main.MainActivity"

    @Volatile
    private var activityProvider: () -> UmlessActivity? = { null }

    @Volatile
    private var registered = false

    /** 当前练习题目（practice_start 设置，practice_stop / practice_cancel 清除） */
    @Volatile
    private var practiceTopic: JsonObject? = null

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val context: Context get() = Umless.context

    fun register(activityProvider: () -> UmlessActivity?) {
        this.activityProvider = activityProvider
        synchronized(this) {
            if (registered) return
            registered = true
        }

        // ---- Android 专用 ----
        reg("get_ime_status") { ImeState.of(context).toJson() }
        reg("open_ime_settings") { start(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)); JsonNull }
        reg("show_ime_picker") { showImePicker(); JsonNull }
        reg("request_mic_permission") { buildJsonObject { put("granted", requestMic()) } }
        reg("export_logs") { exportLogs() }
        reg("pick_sound_file") { pickSoundFile() }
        reg("open_keyboard_settings") {
            start(Intent().setComponent(ComponentName(context.packageName, UPSTREAM_SETTINGS)))
            JsonNull
        }

        // ---- 与桌面端同名、实现依赖平台的 ----
        reg("core_status") { coreStatus() }
        reg("start_core") { startCore(); JsonNull }
        reg("download_model") { args -> downloadModel(args.string(0)) }
        reg("mic_test") { ensureMic(); micTest() }
        reg("practice_start") { args -> practiceStart(args.getOrNull(0)); JsonNull }
        reg("mic_level") { MicSession.levelJson() }
        reg("practice_stop") { practiceStop() }
        reg("practice_cancel") { practiceCancel(); JsonNull }

        // ---- 缺省：桌面端专属或手机上没有意义的方法（界面在 Android 上不会调用），优雅地什么也不做 ----
        regDefault("open_path") { JsonNull }
        regDefault("get_nav") { JsonNull }
        regDefault("set_autostart") { JsonNull }
        regDefault("capture_hotkey") { JsonNull }
        regDefault("cancel_hotkey_capture") { JsonNull }
        regDefault("copy_text") { args -> copyText(args.string(0).orEmpty()); JsonNull }
        // 试听提示音：手机端的提示音播放由语音模块负责；没有实现时静默
        regDefault("preview_sound") { JsonNull }
    }

    private fun reg(name: String, handler: ApiHandler) = UmlessApi.register(name, handler)

    private fun regDefault(name: String, handler: ApiHandler) {
        if (!UmlessApi.has(name)) UmlessApi.register(name, handler)
    }

    private fun JsonArray.string(i: Int): String? = (getOrNull(i) as? JsonPrimitive)?.contentOrNull

    /* ---------------------------------------------------------------- 系统界面 */

    /** 优先用当前 Activity 启动（保持在同一任务里），没有时用 Application + NEW_TASK */
    private suspend fun start(intent: Intent) = withContext(Dispatchers.Main) {
        val act = activityProvider()
        try {
            if (act != null && !act.isFinishing) act.startActivity(intent)
            else context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            UiLog.w(TAG, "无法打开 ${intent.action ?: intent.component}", e)
            throw ApiException(UiText.get("open_failed"))
        } catch (e: SecurityException) {
            UiLog.w(TAG, "无法打开 ${intent.action ?: intent.component}", e)
            throw ApiException(UiText.get("open_failed"))
        }
    }

    /**
     * 弹出输入法选择框；系统没有弹出时（部分 ROM 会静默忽略），改为打开系统的输入法设置，
     * 并提示用户在「当前输入法 / 默认键盘」里选择 Umless
     */
    private suspend fun showImePicker() = withContext(Dispatchers.Main) {
        val shown = activityProvider()?.takeUnless { it.isFinishing }?.showImePicker() ?: false
        if (!shown) {
            UiLog.w(TAG, "输入法选择框没有弹出，改为打开系统输入法设置")
            Toast.makeText(context, UiText.get("pick_ime_in_settings"), Toast.LENGTH_LONG).show()
            start(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    private suspend fun copyText(text: String) = withContext(Dispatchers.Main) {
        context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("Umless", text))
    }

    /* ---------------------------------------------------------------- 键盘状态 */

    /** 本地模型已下载，或已配置云端识别接口 */
    private fun modelReady(): Boolean = try {
        AppVoice.client.isModelReady()
    } catch (e: Exception) {
        false
    }

    private fun coreStatus(): JsonObject = ImeState.of(context).coreStatus(modelReady())

    /** 桌面端是启动托盘进程；手机上引导用户启用 / 切换到 Umless 键盘 */
    private suspend fun startCore() {
        val ime = ImeState.of(context)
        when {
            !ime.enabled -> start(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            !ime.selected -> showImePicker()
        }
    }

    /* ---------------------------------------------------------------- 麦克风 */

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private suspend fun requestMic(): Boolean {
        if (hasMic()) return true
        val act = activityProvider() ?: throw ApiException(UiText.get("no_window"))
        return withContext(Dispatchers.Main) { act.requestMicPermission() }
    }

    /**
     * 录音前确保有麦克风权限：没有就当场弹授权框（被永久拒绝时打开应用详情）。
     * 网页只在新手引导里调用 request_mic_permission，那一步可以跳过；跳过后练习 / 麦克风测试
     * 只会提示「没有权限」，用户在主 App 里没有别的入口去授权
     */
    private suspend fun ensureMic() {
        if (!requestMic()) throw ApiException(UiText.get("mic_denied"))
    }

    private fun micTest(): JsonObject {
        return Jobs.start(message = UiText.t("api.mic_opening", "正在打开麦克风…")) { progress ->
            MicSession.start()
            var peak = 0f
            var samples = FloatArray(0)
            try {
                val t0 = System.currentTimeMillis()
                while (true) {
                    val el = System.currentTimeMillis() - t0
                    if (el >= MIC_TEST_MS) break
                    peak = max(peak, MicSession.level())
                    progress(el.toFloat() / MIC_TEST_MS * MIC_REC_PROGRESS, UiText.t("api.mic_listening", "正在聆听…"))
                    delay(80)
                }
            } finally {
                samples = MicSession.stop() ?: FloatArray(0)
            }
            val recognizing = UiText.t("api.recognizing", "正在识别…")
            progress(0.85f, recognizing)
            // Android 新手引导里「麦克风」在「下载模型」之前：模型还没下载时只测音量（text 为空），
            // 不能抛出「请先下载识别模型」让这一步失败
            val canTranscribe = samples.isNotEmpty() && modelReady()
            // 首次加载模型时保持 0.85，不能退回 null（前端按 progress 判断阶段）
            val text = if (!canTranscribe) "" else AppVoice.use { client ->
                progress(0.9f, recognizing)
                client.transcribe(samples, MicSession.sampleRate).text
            }
            buildJsonObject {
                put("text", text)
                put("peak", round(peak * 100.0) / 100.0)
                // 模型还没下载时只测了音量，界面据此提示「下载模型后再测」而不是「没有识别到内容」
                put("model_ready", canTranscribe || modelReady())
            }
        }
    }

    private suspend fun practiceStart(arg: JsonElement?) {
        val topic = arg as? JsonObject
        val title = (topic?.get("title") as? JsonPrimitive)?.contentOrNull?.trim()
        if (topic == null || title.isNullOrEmpty()) throw ApiException(UiText.t("api.topic_empty", "题目不能为空"))
        ensureMic()
        practiceTopic = JsonObject(
            mapOf(
                "duration" to JsonPrimitive(60),
                "category" to JsonPrimitive(UiText.t("practice.category_custom", "自定义")),
            ) + topic
        )
        try {
            MicSession.start()
        } catch (e: Exception) {
            practiceTopic = null
            UiLog.w(TAG, "练习录音启动失败", e)
            throw ApiException(e.message?.takeIf { it.isNotBlank() } ?: UiText.get("mic_busy"))
        }
        // 录音期间预先加载识别模型，结束后少等一会儿
        bgScope.launch { runCatching { AppVoice.use { it.preload() } } }
    }

    private fun practiceStop(): JsonObject {
        val topic = practiceTopic
        if (topic == null || !MicSession.isRecording) throw ApiException(UiText.t("api.not_recording", "当前没有在录音"))
        val samples = MicSession.stop() ?: FloatArray(0)
        practiceTopic = null
        val durationS = samples.size.toDouble() / MicSession.sampleRate
        return Jobs.start(message = UiText.t("api.recognizing", "正在识别…")) { progress ->
            progress(null, UiText.t("practice.recognizing", "正在识别…"))
            val text = if (samples.isEmpty()) "" else AppVoice.use { it.transcribe(samples, MicSession.sampleRate).text }
            PracticeEval.evaluate(topic, text, durationS, progress)
        }
    }

    private fun practiceCancel() {
        MicSession.stop()
        practiceTopic = null
    }

    /* ---------------------------------------------------------------- 模型与日志 */

    private fun downloadModel(name: String?): JsonObject {
        val model = name?.trim().orEmpty()
        val known = model.isNotEmpty() && try {
            ModelManager.sizeMb(model) > 0
        } catch (e: Exception) {
            false
        }
        if (!known) throw ApiException(UiText.t("api.unknown_model", "未知的模型：$model", "name" to model))
        return Jobs.start(message = UiText.t("api.preparing_download", "准备下载…"), key = "download:$model") { progress ->
            ModelManager.download(model, progress)
            buildJsonObject { put("name", model) }
        }
    }

    /** 打包日志（不含输入内容）并保存到「下载」目录；返回 {"path"}（docs/ui-api.md 7.4） */
    private fun exportLogs(): JsonObject {
        val dir = File(context.cacheDir, "umless-exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "umless-logs-$stamp.zip")
        try {
            UmlessLog.export(file)
            return buildJsonObject { put("path", UserFiles.saveToDownloads(context, file, "application/zip")) }
        } finally {
            file.delete()
        }
    }

    /** 系统文件选择器（只允许 WAV），复制到 home/sounds/ 后返回路径；取消时 null（docs/ui-api.md 7.3） */
    private suspend fun pickSoundFile(): JsonElement {
        val act = activityProvider() ?: throw ApiException(UiText.get("no_window"))
        val uri = withContext(Dispatchers.Main) { act.pickDocument(WAV_TYPES) } ?: return JsonNull
        return JsonPrimitive(UserFiles.importSound(context, uri, Umless.home))
    }

    private val WAV_TYPES = arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
}
