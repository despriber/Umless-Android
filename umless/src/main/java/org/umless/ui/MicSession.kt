package org.umless.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umless.core.Umless
import org.umless.voice.AudioRecorder
import org.umless.voice.VoiceClient
import kotlin.math.round

/**
 * 主 App 的录音：练习与麦克风测试共用一个 [AudioRecorder]（同桌面端 webapi 的 self._mic），
 * mic_level 读取它的实时音量。录音在主 App 进程里进行（有可见窗口），识别交给 :voice 进程。
 */
internal object MicSession {
    private val recorder by lazy { AudioRecorder(Umless.context) }

    val sampleRate: Int get() = recorder.sampleRate

    @get:Synchronized
    val isRecording: Boolean get() = recorder.isRecording

    /** 开始录音；已在录音（如网页刷新后遗留的练习录音）时先丢弃旧的 */
    @Synchronized
    fun start() {
        if (recorder.isRecording) recorder.stop()
        recorder.start()
    }

    /** 停止并返回音频；没在录音时返回 null */
    @Synchronized
    fun stop(): FloatArray? = if (recorder.isRecording) recorder.stop() else null

    @Synchronized
    fun level(): Float = if (recorder.isRecording) recorder.level() else 0f

    /** mic_level() 的返回值：{"recording", "elapsed"（秒）, "level"（0–1）} */
    @Synchronized
    fun levelJson(): JsonObject {
        val rec = recorder.isRecording
        return levelJson(rec, if (rec) recorder.elapsedMs() else 0L, if (rec) recorder.level() else 0f)
    }

    fun levelJson(recording: Boolean, elapsedMs: Long, level: Float): JsonObject = buildJsonObject {
        put("recording", recording)
        put("elapsed", round(elapsedMs / 100.0) / 10.0)
        put("level", round(level.coerceIn(0f, 1f) * 1000.0) / 1000.0)
    }
}

/** 主 App 的 [VoiceClient]：按需绑定 :voice 进程，空闲一段时间后自己解绑（见 VoiceClient） */
internal object AppVoice {
    val client: VoiceClient by lazy { VoiceClient(Umless.context) }

    suspend fun <T> use(block: suspend (VoiceClient) -> T): T = block(client)
}
