package org.umless.voice

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * :voice 进程里的识别服务（sherpa-onnx + SenseVoice，从文件加载模型）。
 *
 * 这个进程不初始化 Umless（不读配置、不写日志文件），只按调用方给的参数加载模型、识别音频文件。
 * 所有入口都捕获异常，坏输入不会让进程崩溃；原生库崩溃时进程会死掉，由 VoiceClient 重新绑定并重试一次。
 * 模型在调用方请求、长时间无人使用、或所有调用方解绑（服务销毁）时释放。
 */
class VoiceService : Service() {

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null

    private val main = Handler(Looper.getMainLooper())
    private val idleRelease = Runnable {
        Thread({ releaseModel("idle") }, "umless-voice-release").start()
    }

    private val binder = object : IVoiceEngine.Stub() {
        override fun preload(engine: String?, modelDir: String?, numThreads: Int): String? = try {
            touch()
            load(engine.orEmpty(), modelDir.orEmpty(), numThreads)
        } catch (t: Throwable) {
            Log.e(TAG, "preload failed", t)
            "load_failed: ${t.message ?: t.javaClass.simpleName}"
        }

        override fun isReady(): Boolean = synchronized(lock) { recognizer != null }

        override fun transcribe(pcmPath: String?, sampleRate: Int): String = try {
            touch()
            decode(pcmPath.orEmpty(), sampleRate)
        } catch (t: Throwable) {
            Log.e(TAG, "transcribe failed", t)
            errorJson("decode_failed: ${t.message ?: t.javaClass.simpleName}")
        }

        override fun release() {
            releaseModel("request")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        main.removeCallbacks(idleRelease)
        releaseModel("destroy")
        super.onDestroy()
    }

    /** 每次调用后重新计时：IDLE_RELEASE_MS 内没人用就释放模型内存 */
    private fun touch() {
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    /** 返回 null 表示成功，否则为错误代码（由 VoiceClient 翻译） */
    private fun load(engine: String, modelDir: String, numThreads: Int): String? {
        if (engine != "sensevoice") return "unsupported_engine: $engine"
        val dir = File(modelDir)
        val model = File(dir, "model.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        if (!model.isFile || !tokens.isFile) return MODEL_MISSING
        val threads = numThreads.coerceIn(1, 4)
        val key = "$engine|${dir.absolutePath}|$threads|${model.length()}|${model.lastModified()}"
        synchronized(lock) {
            if (recognizer != null && loadedKey == key) return null
            recognizer?.release()
            recognizer = null
            loadedKey = null
            val t0 = SystemClock.elapsedRealtime()
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = model.absolutePath,
                        language = "zh",
                        useInverseTextNormalization = true,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            // assetManager 传 null：从文件加载
            recognizer = OfflineRecognizer(null, config)
            loadedKey = key
            Log.i(TAG, "SenseVoice loaded in ${SystemClock.elapsedRealtime() - t0}ms, threads=$threads")
        }
        return null
    }

    private fun decode(pcmPath: String, sampleRate: Int): String {
        if (sampleRate !in 8000..48000) return errorJson("bad_sample_rate: $sampleRate")
        val file = File(pcmPath)
        if (!file.isFile) return errorJson("audio_missing")
        val samples = Pcm.readFloatLE(file)
        if (samples.isEmpty()) return result("", 0)
        synchronized(lock) {
            val rec = recognizer ?: return errorJson(NOT_LOADED)
            val t0 = SystemClock.elapsedRealtime()
            val stream = rec.createStream()
            val text = try {
                stream.acceptWaveform(samples, sampleRate)
                rec.decode(stream)
                rec.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
            return result(text, SystemClock.elapsedRealtime() - t0)
        }
    }

    private fun releaseModel(reason: String) {
        synchronized(lock) {
            val r = recognizer ?: return
            recognizer = null
            loadedKey = null
            try {
                r.release()
            } catch (t: Throwable) {
                Log.w(TAG, "release failed", t)
            }
            Log.i(TAG, "model released ($reason)")
        }
    }

    private fun result(text: String, ms: Long) = buildJsonObject {
        put("text", text)
        put("asr_ms", ms)
    }.toString()

    private fun errorJson(code: String) = buildJsonObject { put("error", code) }.toString()

    companion object {
        private const val TAG = "UmlessVoiceService"
        private const val IDLE_RELEASE_MS = 10 * 60_000L
        const val MODEL_MISSING = "model_missing"
        const val NOT_LOADED = "not_loaded"
    }
}
