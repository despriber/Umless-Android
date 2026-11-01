package org.umless.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 音频的小工具：float32 小端 PCM 文件（进程间传音频）、16 位 WAV（云端识别）、音量 */
internal object Pcm {
    /** 单个文件最多 10 分钟（按 48kHz 算），防止读入异常大的文件 */
    const val MAX_BYTES = 48_000L * 600 * 4

    fun writeFloatLE(file: File, samples: FloatArray) {
        file.outputStream().buffered(1 shl 16).use { out ->
            val buf = ByteBuffer.allocate(1 shl 14).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                if (!buf.hasRemaining()) {
                    out.write(buf.array(), 0, buf.position())
                    buf.clear()
                }
                buf.putFloat(s)
            }
            out.write(buf.array(), 0, buf.position())
        }
    }

    /** 读取 float32 小端 PCM；NaN / 无穷大置 0，超出 [-1, 1] 的截断 */
    fun readFloatLE(file: File): FloatArray {
        val len = file.length()
        require(len in 0..MAX_BYTES) { "audio too large: $len bytes" }
        val bytes = file.readBytes()
        val fb = ByteBuffer.wrap(bytes, 0, bytes.size - bytes.size % 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(fb.remaining())
        fb.get(out)
        for (i in out.indices) {
            val v = out[i]
            out[i] = if (v.isNaN() || v.isInfinite()) 0f else v.coerceIn(-1f, 1f)
        }
        return out
    }

    /** 16 位单声道 WAV（与桌面端 to_wav_bytes 的 PCM_16 一致） */
    fun toWav16(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataLen = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataLen)
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            .putShort(1).putShort(1) // PCM、单声道
            .putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16)
        h.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataLen)
        out.write(h.array())
        val body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = if (s.isNaN()) 0f else s.coerceIn(-1f, 1f)
            body.putShort((v * 32767f).roundToInt().toShort())
        }
        out.write(body.array())
        return out.toByteArray()
    }

    fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return sqrt(sum / (to - from)).toFloat()
    }

    /** RMS → 0–1 音量（-60dB ~ -10dB 线性映射），同桌面端 practice.level_of */
    fun levelOf(rms: Float): Float {
        if (rms <= 1e-6f) return 0f
        val db = 20 * log10(rms.toDouble())
        return ((db + 60) / 50).toFloat().coerceIn(0f, 1f)
    }
}
