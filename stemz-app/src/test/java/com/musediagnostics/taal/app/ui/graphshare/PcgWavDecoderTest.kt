package com.musediagnostics.taal.app.ui.graphshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Plain-JVM tests for [PcgWavDecoder] against synthetic WAV bytes it builds itself — no real
 * recording, no Android runtime. Mirrors the same 44-byte-header / 16-bit LE PCM / bytes-24-27
 * sample-rate layout PcgScaleReviewFragment.loadFullWaveform decodes inline today.
 */
class PcgWavDecoderTest {

    /** Minimal synthetic WAV: a real 44-byte canonical header (only the sample-rate field is
     *  load-bearing for this decoder) followed by 16-bit LE PCM samples. */
    private fun buildWav(sampleRate: Int, samples: ShortArray): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)

        fun writeStr(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeInt(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff)
            out.write((v shr 24) and 0xff)
        }
        fun writeShort(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }

        writeStr("RIFF"); writeInt(36 + dataSize); writeStr("WAVE")
        writeStr("fmt "); writeInt(16); writeShort(1) // PCM
        writeShort(1) // mono
        writeInt(sampleRate)
        writeInt(sampleRate * 2) // byte rate
        writeShort(2) // block align
        writeShort(16) // bits per sample
        writeStr("data"); writeInt(dataSize)
        for (s in samples) writeShort(s.toInt() and 0xFFFF)

        return out.toByteArray()
    }

    @Test
    fun `decodes known samples back to the same normalized values`() {
        val raw = shortArrayOf(0, 16384, -16384, 32767, -32768)
        val bytes = buildWav(44100, raw)

        val decoded = PcgWavDecoder.decode(bytes)!!

        assertEquals(raw.size, decoded.samples.size)
        for (i in raw.indices) {
            assertEquals(raw[i] / 32768f, decoded.samples[i], 1e-5f)
        }
    }

    @Test
    fun `reads the header sample rate, not a hardcoded default`() {
        val bytes = buildWav(16000, shortArrayOf(0, 0, 0, 0))
        val decoded = PcgWavDecoder.decode(bytes)!!
        assertEquals(16000f, decoded.sampleRate, 1e-4f)
    }

    @Test
    fun `duration is rounded, not truncated`() {
        // 44100Hz, samples chosen so totalSamples / sampleRate = 1.6s -> rounds to 2s, not 1s.
        val sampleRate = 10
        val samples = ShortArray(16) // 16 / 10 = 1.6s
        val bytes = buildWav(sampleRate, samples)
        val decoded = PcgWavDecoder.decode(bytes)!!
        assertEquals(2f, decoded.durationSecs, 1e-4f)
    }

    @Test
    fun `a file too short to contain a header returns null instead of throwing`() {
        assertNull(PcgWavDecoder.decode(ByteArray(10)))
        assertNull(PcgWavDecoder.decode(ByteArray(0)))
    }

    @Test
    fun `a garbled zero sample rate in the header falls back to 44100Hz`() {
        // buildWav writes a real rate at bytes 24-27; zero it out afterward to simulate a
        // corrupt/garbled header field and confirm readSampleRateOrDefault's rate-must-be-
        // positive guard catches it instead of decoding at 0Hz.
        val bytes = buildWav(44100, shortArrayOf(1, 2, 3))
        bytes[24] = 0; bytes[25] = 0; bytes[26] = 0; bytes[27] = 0
        val decoded = PcgWavDecoder.decode(bytes)
        assertTrue(decoded != null)
        assertEquals(44100f, decoded!!.sampleRate, 1e-4f)
    }

    @Test
    fun `a truncated data section decodes only the complete samples present without crashing`() {
        // 5 declared samples but the byte stream is cut short — decode derives its sample count
        // from the (now smaller) actual byte size, not from what the header originally implied,
        // so it must produce a shorter-but-valid array rather than reading past the end.
        val full = buildWav(44100, shortArrayOf(1, 2, 3, 4, 5))
        val truncated = full.copyOf(full.size - 3)
        val decoded = PcgWavDecoder.decode(truncated)
        assertTrue(decoded != null)
        assertEquals(3, decoded!!.samples.size)
        assertEquals(1f / 32768f, decoded.samples[0], 1e-5f)
    }
}
