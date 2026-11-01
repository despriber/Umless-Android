package org.umless.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import org.umless.core.Polish
import org.umless.core.Records
import org.umless.core.Umless
import org.umless.core.UmlessConfig
import org.umless.voice.AudioRecorder
import org.umless.voice.Pcm
import org.umless.voice.UmlessLogSafe
import org.umless.voice.VoiceClient
import org.umless.voice.VoiceConfig
import java.io.File

/** 生产环境的 [VoiceController]：AudioRecorder + VoiceClient + Polish + Records + UmlessConfig */
object AndroidVoice {
    fun controller(context: Context, host: VoiceHost, client: VoiceClient, scope: CoroutineScope): VoiceController {
        val app = context.applicationContext
        val audio = AudioRecorder(app)
        return VoiceController(
            host = host,
            recorder = object : VoiceRecorder {
                override val sampleRate: Int get() = audio.sampleRate
                override fun start() = audio.start()
                override fun stop(): FloatArray = audio.stop()
                override fun level(): Float = audio.level()
            },
            transcriber = Transcriber { samples, sr -> client.transcribe(samples, sr) },
            polisher = Polisher { raw, scene, intensity -> Polish.polish(raw, scene, intensity) },
            records = RecordSink { record, mode, samples, sr ->
                if (mode == SaveMode.LastOutputOnly) {
                    Records.saveLastOutputOnly(record.rawText, record.polishedText, record.outputText)
                    return@RecordSink
                }
                val id = Records.save(record, true)
                if (id != null && VoiceConfig.safe(false) { UmlessConfig.keepAudio }) {
                    val dir = File(Umless.home, "audio").apply { mkdirs() }
                    File(dir, "$id.wav").writeBytes(Pcm.toWav16(samples, sr))
                }
            },
            env = CoreVoiceEnv(app, client),
            clock = Clock { SystemClock.elapsedRealtime() },
            scope = scope,
        )
    }
}

/** 从 UmlessConfig 读取（读失败用默认值，键盘不因配置问题崩溃） */
class CoreVoiceEnv(private val context: Context, private val client: VoiceClient) : VoiceEnv {
    private fun <T> safe(default: T, block: () -> T): T = VoiceConfig.safe(default, block)

    override val minHoldMs: Long
        get() = VoiceConfig.long(300, "voice", "min_hold_ms").coerceIn(0, 5000)
    override val polishTimeoutMs: Long
        get() = (VoiceConfig.double(20.0, "llm", "timeout_s") * 1000).toLong().coerceIn(1000, 300_000)
    override val llmEnabled: Boolean get() = safe(false) { UmlessConfig.llmEnabled }
    override val llmModel: String? get() = safe(null) { Polish.model }
    override val haptic: Boolean get() = safe(true) { UmlessConfig.haptic }
    override val recordHistory: Boolean get() = safe(false) { UmlessConfig.recordHistory }
    override val skipIncognito: Boolean get() = safe(true) { UmlessConfig.skipIncognito }
    override val ownPackage: String? get() = context.packageName

    override fun sceneOf(packageName: String?): String = safe("default") { UmlessConfig.sceneOf(packageName) }
    override fun intensityOf(scene: String): String = safe("moderate") { UmlessConfig.intensityOf(scene) }
    override fun isExcluded(packageName: String?): Boolean = safe(false) { UmlessConfig.isExcluded(packageName) }

    override fun modelReady(): Boolean = Umless.isInitialized && safe(false) { client.isModelReady() }

    override fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun t(key: String): String = VoiceTexts.t(key)

    override fun log(message: String, error: Throwable?) = UmlessLogSafe.w(message, error)
}
