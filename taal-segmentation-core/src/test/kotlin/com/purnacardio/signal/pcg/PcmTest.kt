package com.purnacardio.signal.pcg

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `AudioRecord` boundary — where integrations actually go wrong.
 */
class PcmTest {

    @Test
    fun `amplitude scale does not change the features`() {
        // The claim that lets an integrator stop worrying about it: the extractor normalises
        // internally, so raw 16-bit values and [-1,1] floats are the same input. If this ever
        // stops holding, every caller who picked the other convention silently changes behaviour.
        val fs = PcgFeatureExtractor.TARGET_SR
        val pcm = ShortArray(5 * fs) { (12000.0 * kotlin.math.sin(2.0 * Math.PI * 60.0 * it / fs)).toInt().toShort() }

        val raw = assertNotNull(PcgFeatureExtractor().extract(Pcm.toFloat(pcm)))
        val unit = assertNotNull(PcgFeatureExtractor().extract(Pcm.toFloatNormalised(pcm)))

        for (ch in raw.indices) {
            for (i in raw[ch].indices) {
                assertTrue(kotlin.math.abs(raw[ch][i] - unit[ch][i]) < 1e-4f,
                    "channel $ch frame $i diverged: ${raw[ch][i]} vs ${unit[ch][i]}")
            }
        }
    }

    @Test
    fun `stereo is split, not averaged`() {
        // Averaging two chest positions is not the same recording as either one. The caller picks
        // a channel; this only lays them out.
        val interleaved = floatArrayOf(1f, -1f, 2f, -2f, 3f, -3f)
        assertContentEquals(floatArrayOf(1f, 2f, 3f), Pcm.deinterleaveMono(interleaved, 2, 0))
        assertContentEquals(floatArrayOf(-1f, -2f, -3f), Pcm.deinterleaveMono(interleaved, 2, 1))
    }

    @Test
    fun `mono passes through untouched`() {
        val mono = floatArrayOf(1f, 2f, 3f)
        assertTrue(Pcm.deinterleaveMono(mono, 1) === mono)
    }

    @Test
    fun `a trailing partial frame is dropped rather than read past`() {
        val ragged = floatArrayOf(1f, -1f, 2f)   // one and a half stereo frames
        assertContentEquals(floatArrayOf(1f), Pcm.deinterleaveMono(ragged, 2, 0))
    }

    @Test
    fun `an out-of-range channel fails loudly`() {
        assertFailsWith<IllegalArgumentException> { Pcm.deinterleaveMono(FloatArray(4), 2, 2) }
        assertFailsWith<IllegalArgumentException> { Pcm.deinterleaveMono(FloatArray(4), 0, 0) }
    }

    @Test
    fun `widening 16-bit PCM preserves the values`() {
        val pcm = shortArrayOf(0, 32767, -32768, 100)
        assertContentEquals(floatArrayOf(0f, 32767f, -32768f, 100f), Pcm.toFloat(pcm))
        assertEquals(-1f, Pcm.toFloatNormalised(pcm)[2])
    }
}
