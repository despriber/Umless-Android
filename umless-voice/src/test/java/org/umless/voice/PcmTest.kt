package org.umless.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTest {
    @Test
    fun floatFileRoundTripSanitizes() {
        val f = File.createTempFile("pcm", ".pcm")
        try {
            Pcm.writeFloatLE(f, floatArrayOf(0f, 0.5f, -0.25f, 2f, Float.NaN, Float.NEGATIVE_INFINITY))
            assertEquals(24L, f.length())
            assertArrayEquals(floatArrayOf(0f, 0.5f, -0.25f, 1f, 0f, 0f), Pcm.readFloatLE(f), 0f)
            // 长度不是 4 的倍数时忽略尾部
            f.appendBytes(byteArrayOf(1, 2))
            assertEquals(6, Pcm.readFloatLE(f).size)
        } finally {
            f.delete()
        }
    }

    @Test
    fun largeFileRoundTrip() {
        val f = File.createTempFile("pcm", ".pcm")
        try {
            val samples = FloatArray(100_000) { (it % 200 - 100) / 100f }
            Pcm.writeFloatLE(f, samples)
            assertArrayEquals(samples, Pcm.readFloatLE(f), 0f)
        } finally {
            f.delete()
        }
    }

    @Test
    fun wavHeader() {
        val wav = Pcm.toWav16(floatArrayOf(0f, 1f, -1f), 16000)
        assertEquals(44 + 6, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + 6, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // 单声道
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(6, b.getInt(40))
        assertEquals(0, b.getShort(44).toInt())
        assertEquals(32767, b.getShort(46).toInt())
        assertEquals(-32767, b.getShort(48).toInt())
    }

    @Test
    fun levelMapping() {
        assertEquals(0f, Pcm.levelOf(0f), 0f)
        assertEquals(0f, Pcm.levelOf(0.001f), 1e-6f) // -60dB
        assertEquals(1f, Pcm.levelOf(0.316228f), 1e-4f) // -10dB
        assertEquals(0.6f, Pcm.levelOf(0.031623f), 1e-4f) // -30dB
        assertEquals(1f, Pcm.levelOf(1f), 0f)
        assertEquals(0.5f, Pcm.rms(floatArrayOf(0.5f, -0.5f)), 1e-6f)
    }
}
