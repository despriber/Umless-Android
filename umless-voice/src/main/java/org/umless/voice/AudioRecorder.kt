package org.umless.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.umless.ime.VoiceTexts

/** 没有 RECORD_AUDIO 权限（需要在 Umless 主 App 里授权） */
class MicPermissionException(message: String) : SecurityException(message)

/** 麦克风打不开（被占用、设备不支持等） */
class MicUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * 录音（AudioRecord，16kHz 单声道）。必须在有可见窗口的进程里使用（输入法或主 App），
 * 需要 RECORD_AUDIO 权限（由主 App 申请）。
 *
 * 后台线程每 40ms 读一块（PCM16 → float），记录每块的 RMS 供声波动画使用。所有方法线程安全；
 * [stop] 可重复调用，未在录音时返回空数组。传入 [context] 时会先检查权限并给出明确的错误。
 */
class AudioRecorder(private val context: Context? = null) {
    val sampleRate: Int = 16000

    private val lock = Any()
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    // 正在停止：读线程把缓冲区里剩下的音频读完再退出（冷启动时第一块数据可能很晚才到，不能丢）
    @Volatile
    private var stopping = false

    // 录到的音频块（由读线程追加，stop 时拼接）
    private val chunks = ArrayList<FloatArray>()
    private var total = 0

    @Volatile
    private var startedAt = 0L

    // 最近 3 块的 RMS（环形）与平滑后的音量
    private val recent = FloatArray(3)
    private var recentIdx = 0

    @Volatile
    private var smoothed = 0f

    /** 读音频时出错（设备被拔出、被系统收回等），stop 前可查看 */
    @Volatile
    var readError: Throwable? = null
        private set

    /** 开始录音；没有权限或麦克风被占用时抛出异常 */
    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            if (running) return
            if (context != null && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) throw MicPermissionException(VoiceTexts.t("voice.mic_permission"))

            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            if (minBuf <= 0) throw MicUnavailableException(VoiceTexts.t("voice.mic_failed"))
            val bufBytes = maxOf(minBuf, sampleRate / 5 * 2) // 至少 200ms
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, channel, encoding, bufBytes)
            } catch (e: SecurityException) {
                throw MicPermissionException(VoiceTexts.t("voice.mic_permission"))
            } catch (e: IllegalArgumentException) {
                throw MicUnavailableException(VoiceTexts.t("voice.mic_failed"), e)
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                throw MicUnavailableException(VoiceTexts.t("voice.mic_failed"))
            }
            try {
                rec.startRecording()
            } catch (e: IllegalStateException) {
                rec.release()
                throw MicUnavailableException(VoiceTexts.t("voice.mic_failed"), e)
            }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                throw MicUnavailableException(VoiceTexts.t("voice.mic_failed"))
            }
            synchronized(chunks) {
                chunks.clear()
                total = 0
                recent.fill(0f)
                recentIdx = 0
            }
            smoothed = 0f
            readError = null
            startedAt = SystemClock.elapsedRealtime()
            stopping = false
            running = true
            record = rec
            thread = Thread({ readLoop(rec) }, "umless-recorder").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun readLoop(rec: AudioRecord) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val buf = ShortArray(sampleRate / 25) // 40ms
        while (running) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (e: Throwable) {
                if (!stopping) readError = e
                break
            }
            if (n < 0) { // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT 等；停止后读到这里是正常结束
                if (running && !stopping) readError = MicUnavailableException("AudioRecord.read = $n")
                break
            }
            if (n == 0) {
                if (stopping) break // 缓冲区已读完
                continue
            }
            val f = FloatArray(n) { buf[it] / 32768f }
            val r = Pcm.rms(f)
            val kept = synchronized(chunks) {
                if (running) {
                    chunks.add(f)
                    total += n
                    recent[recentIdx] = r
                    recentIdx = (recentIdx + 1) % recent.size
                }
                running
            }
            if (!kept) break
        }
    }

    /** 停止并返回录到的音频 */
    fun stop(): FloatArray {
        val rec: AudioRecord
        val t: Thread?
        synchronized(lock) {
            rec = record ?: return FloatArray(0)
            t = thread
            record = null
            thread = null
            stopping = true
        }
        // 先停止录音，读线程把缓冲区里剩下的数据读完后自己退出；再结束收集
        try {
            rec.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            t?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(chunks) { running = false }
        rec.release()
        return synchronized(chunks) {
            val out = FloatArray(total)
            var pos = 0
            for (c in chunks) {
                c.copyInto(out, pos)
                pos += c.size
            }
            chunks.clear()
            total = 0
            out
        }
    }

    val isRecording: Boolean get() = running

    /** 最近的音量 0–1（上升快、下降慢），用于声波动画 */
    fun level(): Float {
        if (!running) return 0f
        val peak = synchronized(chunks) { recent.max() }
        val target = Pcm.levelOf(peak)
        val cur = smoothed
        val next = if (target > cur) target else cur * 0.75f + target * 0.25f
        smoothed = next
        return next
    }

    /** 已录时长（毫秒） */
    fun elapsedMs(): Long = if (running) SystemClock.elapsedRealtime() - startedAt else 0L
}
