package org.umless.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.umless.core.TextFix
import org.umless.ime.VoiceTexts
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** 识别失败（message 已按界面语言翻译） */
class VoiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 绑定 :voice 进程里的 [VoiceService]（IVoiceEngine）。输入法与主 App 各持有一个实例。
 *
 * - 绑定有超时；:voice 进程被杀（DeadObjectException 等）时重新绑定并重试一次。
 * - 音频写到 cacheDir 的临时文件（float32 小端）交给 :voice 进程，用完即删。
 * - 引擎为 openai 时直接在调用方进程请求云端接口，不经过 :voice。
 * - SenseVoice（本地，或云端同名模型）输出的英文全大写，识别后按 spec/asr.md 修正大小写（[TextFix]），
 *   语音输入、练习、麦克风测试都经过这里，落库的原文也是修正后的。
 * - 5 分钟没有调用就解绑，:voice 进程随即释放模型（进程可被系统回收）。
 */
class VoiceClient(context: Context) {
    private val app = context.applicationContext
    private val lock = Any()
    private var engine: IVoiceEngine? = null
    private var connection: ServiceConnection? = null
    private var waiting: CompletableDeferred<IVoiceEngine>? = null

    private val main = Handler(Looper.getMainLooper())
    private val idleUnbind = Runnable { synchronized(lock) { unbindLocked() } }

    @Volatile
    private var tmpCleaned = false

    /** 当前配置的引擎名（写入记录用）：sensevoice / openai:<model> */
    val engineName: String
        get() = if (VoiceConfig.asrEngine == ENGINE_OPENAI) "$ENGINE_OPENAI:${VoiceConfig.openaiModel}" else SENSEVOICE

    /** 当前识别引擎是否可用：本地模型已下载，或已配置云端接口地址 */
    fun isModelReady(): Boolean = when (VoiceConfig.asrEngine) {
        ENGINE_OPENAI -> VoiceConfig.openaiBaseUrl.isNotBlank()
        else -> ModelManager.isDownloaded(SENSEVOICE)
    }

    /** 预加载当前配置的识别模型（模型未下载时返回错误信息，成功返回 null） */
    suspend fun preload(): String? {
        if (VoiceConfig.asrEngine == ENGINE_OPENAI) return null
        if (!ModelManager.isDownloaded(SENSEVOICE)) return VoiceTexts.t("voice.model_missing")
        touch()
        val dir = ModelManager.modelDir(SENSEVOICE).absolutePath
        val threads = VoiceConfig.numThreads
        return try {
            call(LOAD_TIMEOUT_MS) { it.preload(SENSEVOICE, dir, threads) }?.let { translate(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            UmlessLogSafe.w("预加载识别模型失败", e)
            e.message ?: VoiceTexts.t("voice.failed")
        } finally {
            touch()
        }
    }

    /** 识别一段 16k / 任意采样率的单声道 float PCM。失败抛出异常（message 已按界面语言翻译） */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int): Transcript {
        if (VoiceConfig.asrEngine == ENGINE_OPENAI) return transcribeOpenAI(samples, sampleRate)
        if (!ModelManager.isDownloaded(SENSEVOICE)) throw VoiceException(VoiceTexts.t("voice.model_missing"))
        touch()
        val dir = ModelManager.modelDir(SENSEVOICE).absolutePath
        val threads = VoiceConfig.numThreads
        val file = withContext(Dispatchers.IO) {
            tempFile().also { Pcm.writeFloatLE(it, samples) }
        }
        try {
            // 识别通常远快于实时；留足余量（含首次加载模型）
            val timeout = LOAD_TIMEOUT_MS + samples.size * 3000L / sampleRate.coerceAtLeast(1)
            val json = call(timeout) { e ->
                // 每次都确认模型已加载：:voice 进程可能被回收后重建
                val err = e.preload(SENSEVOICE, dir, threads)
                if (err != null) JsonObject(mapOf("error" to JsonPrimitive(err))).toString()
                else e.transcribe(file.absolutePath, sampleRate)
            }
            val obj = Json.parseToJsonElement(json) as? JsonObject
                ?: throw VoiceException(VoiceTexts.t("voice.failed"))
            obj["error"]?.let {
                val code = it.jsonPrimitive.content
                UmlessLogSafe.w("识别失败：$code")
                throw VoiceException(translate(code))
            }
            val text = obj["text"]?.jsonPrimitive?.content.orEmpty().trim()
            val ms = obj["asr_ms"]?.jsonPrimitive?.longOrNull ?: 0L
            return Transcript(fixCase(SENSEVOICE, text), ms, SENSEVOICE)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            touch()
        }
    }

    fun close() {
        main.removeCallbacks(idleUnbind)
        synchronized(lock) { unbindLocked() }
    }

    // ---------------------------------------------------------------- 云端识别

    private suspend fun transcribeOpenAI(samples: FloatArray, sampleRate: Int): Transcript {
        val base = VoiceConfig.openaiBaseUrl
        val key = VoiceConfig.openaiApiKey
        val model = VoiceConfig.openaiModel
        val t0 = SystemClock.elapsedRealtime()
        val text = try {
            blocking(OPENAI_TIMEOUT_MS) { OpenAIAsr.transcribe(samples, sampleRate, base, key, model) }
        } catch (e: TimeoutCancellationException) {
            throw VoiceException(VoiceTexts.t("voice.failed"), e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            UmlessLogSafe.w("云端识别失败", e)
            throw VoiceException("${VoiceTexts.t("voice.failed")}：${e.message ?: e.javaClass.simpleName}", e)
        }
        return Transcript(fixCase(model, text), SystemClock.elapsedRealtime() - t0, "$ENGINE_OPENAI:$model")
    }

    // ---------------------------------------------------------------- 绑定

    /** 在已连接的引擎上执行阻塞调用；:voice 进程已死时重新绑定并重试一次 */
    private suspend fun <T> call(timeoutMs: Long, block: (IVoiceEngine) -> T): T {
        var retried = false
        while (true) {
            val e = connect()
            try {
                return blocking(timeoutMs) { block(e) }
            } catch (ex: RemoteException) {
                reset(e)
                if (retried) throw VoiceException(VoiceTexts.t("voice.failed"), ex)
                retried = true
                UmlessLogSafe.w(":voice 进程连接断开，重新连接后重试", ex)
            } catch (ex: TimeoutCancellationException) {
                // 调用卡住：丢弃这个连接，下次重新绑定
                reset(e)
                throw VoiceException(VoiceTexts.t("voice.failed"), ex)
            }
        }
    }

    private suspend fun connect(): IVoiceEngine {
        val d: CompletableDeferred<IVoiceEngine>
        synchronized(lock) {
            val cur = engine
            if (cur != null && cur.asBinder().isBinderAlive) return cur
            engine = null
            d = waiting ?: CompletableDeferred<IVoiceEngine>().also {
                // :voice 进程被杀后 onServiceDisconnected 只清掉 engine，旧的绑定还在（等系统重建服务）。
                // 新绑定前先解绑旧的，否则旧连接永远不会被 unbind，一直把 :voice 进程拉着不放
                unbindLocked()
                waiting = it
                bindLocked(it)
            }
        }
        return try {
            withTimeout(BIND_TIMEOUT_MS) { d.await() }
        } catch (e: TimeoutCancellationException) {
            synchronized(lock) { if (waiting === d) unbindLocked() }
            throw VoiceException(VoiceTexts.t("voice.failed"), e)
        }
    }

    private fun bindLocked(d: CompletableDeferred<IVoiceEngine>) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val e = IVoiceEngine.Stub.asInterface(service)
                synchronized(lock) {
                    if (connection !== this) return
                    engine = e
                    waiting?.complete(e)
                    waiting = null
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // :voice 进程退出；BIND_AUTO_CREATE 下系统会重建并再次回调 onServiceConnected
                synchronized(lock) { if (connection === this) engine = null }
            }

            override fun onBindingDied(name: ComponentName?) {
                synchronized(lock) { if (connection === this) unbindLocked() }
            }

            override fun onNullBinding(name: ComponentName?) {
                synchronized(lock) { if (connection === this) unbindLocked() }
            }
        }
        connection = conn
        val ok = try {
            app.bindService(Intent(app, VoiceService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        if (!ok) {
            connection = null
            waiting = null
            runCatching { app.unbindService(conn) }
            d.completeExceptionally(VoiceException(VoiceTexts.t("voice.failed")))
        }
    }

    private fun unbindLocked() {
        val c = connection ?: return
        connection = null
        engine = null
        waiting?.completeExceptionally(VoiceException(VoiceTexts.t("voice.failed")))
        waiting = null
        runCatching { app.unbindService(c) }
    }

    private fun reset(e: IVoiceEngine) {
        synchronized(lock) { if (engine === e || engine == null) unbindLocked() }
    }

    private fun touch() {
        main.removeCallbacks(idleUnbind)
        main.postDelayed(idleUnbind, IDLE_UNBIND_MS)
    }

    // ---------------------------------------------------------------- 工具

    private fun tempFile(): File {
        val dir = File(app.cacheDir, "umless-voice").apply { mkdirs() }
        if (!tmpCleaned) { // 清理上次异常退出留下的临时文件
            tmpCleaned = true
            val old = System.currentTimeMillis() - 3600_000L
            dir.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
        }
        return File(dir, "${UUID.randomUUID()}.pcm")
    }

    /** 只处理 SenseVoice 的输出（模型名含 sensevoice）；修正出错时保留原文，不影响输入 */
    private fun fixCase(model: String, text: String): String {
        if (!model.contains(SENSEVOICE, ignoreCase = true)) return text
        return try {
            TextFix.fixCase(text, VoiceConfig.glossary)
        } catch (e: Throwable) {
            UmlessLogSafe.w("修正识别结果大小写失败", e)
            text
        }
    }

    private fun translate(code: String): String = when {
        code == VoiceService.MODEL_MISSING -> VoiceTexts.t("voice.model_missing")
        else -> VoiceTexts.t("voice.failed")
    }

    companion object {
        const val SENSEVOICE = "sensevoice"
        private const val ENGINE_OPENAI = "openai"
        private const val BIND_TIMEOUT_MS = 10_000L
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val OPENAI_TIMEOUT_MS = 90_000L
        private const val IDLE_UNBIND_MS = 5 * 60_000L

        /** 阻塞调用放到独立线程：超时或取消时调用方立即返回，不等卡住的 binder / 网络调用 */
        private val executor = Executors.newCachedThreadPool { r ->
            Thread(r, "umless-voice-client").apply { isDaemon = true }
        }

        private suspend fun <T> blocking(timeoutMs: Long, block: () -> T): T {
            val d = CompletableDeferred<T>()
            executor.execute {
                try {
                    d.complete(block())
                } catch (t: Throwable) {
                    d.completeExceptionally(t)
                }
            }
            return withTimeout(timeoutMs) { d.await() }
        }
    }
}

data class Transcript(val text: String, val asrMs: Long, val engine: String)
