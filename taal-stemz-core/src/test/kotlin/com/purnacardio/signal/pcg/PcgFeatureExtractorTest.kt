package com.purnacardio.signal.pcg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 12-channel input contract.
 *
 * The model was trained on features produced by the Python reference implementation, so the
 * channel count, channel *order*, and frame rate are not free choices — they are the model's
 * input signature. A reordered channel produces confident nonsense with no error raised.
 */
class PcgFeatureExtractorTest {

    private val fs = PcgFeatureExtractor.TARGET_SR   // 2000
    private val extractor = PcgFeatureExtractor()

    private fun tone(seconds: Double, hz: Double, amp: Float = 0.5f): FloatArray {
        val n = (seconds * fs).toInt()
        return FloatArray(n) { (amp * cos(2.0 * PI * hz * it / fs)).toFloat() }
    }

    @Test
    fun `the model gets 12 channels at 200 Hz, one frame per 10 samples`() {
        val audio = tone(seconds = 5.0, hz = 60.0)
        val feats = assertNotNull(extractor.extract(audio), "5 s of tone must produce features")

        assertEquals(PcgFeatureExtractor.NUM_CHANNELS, feats.size)
        assertEquals(12, feats.size, "the ONNX model's input channel count is fixed at 12")

        val expectedFrames = audio.size / PcgFeatureExtractor.SPF   // 10000 / 10
        assertEquals(1000, expectedFrames)
        for ((i, ch) in feats.withIndex()) {
            assertEquals(expectedFrames, ch.size, "channel $i has the wrong frame count")
        }
    }

    @Test
    fun `features are finite, which is what the tensor boundary requires`() {
        // NaN reaching ONNX Runtime does not throw; it propagates to the logits and argmax then
        // picks whichever class compares first. The failure is silent and looks like a bad model.
        val feats = assertNotNull(extractor.extract(tone(5.0, 60.0)))
        for ((i, ch) in feats.withIndex()) {
            assertTrue(ch.all { it.isFinite() }, "channel $i contains NaN or infinity")
        }
    }

    @Test
    fun `too short to segment returns null rather than a padded guess`() {
        assertNull(extractor.extract(tone(0.49, 60.0)), "under 1 s at 2 kHz must be refused")
        assertNotNull(extractor.extract(tone(1.0, 60.0)), "exactly 1 s is the boundary and is accepted")
    }

    @Test
    fun `a dead microphone returns null instead of amplified noise`() {
        assertNull(extractor.extract(FloatArray(5 * fs)), "digital silence must yield no features")
    }

    @Test
    fun `resampling lands on the expected length`() {
        // 44.1 kHz is what AudioRecord gives; 2 kHz is what the filters were designed at.
        val oneSecondAt44k = FloatArray(44100) { 0.1f }
        assertEquals(2000, extractor.resample(oneSecondAt44k, 44100, 2000).size)

        // Identity is returned by reference when no conversion is needed.
        val already2k = FloatArray(2000)
        assertTrue(extractor.resample(already2k, 2000, 2000) === already2k)
    }

    @Test
    fun `the FFT agrees with the frequency it is given`() {
        // The mel channels (7-10) rest entirely on this. A transposed or mis-scaled FFT would
        // still yield plausible-looking features, so it is pinned against a known answer.
        val n = 128
        val bin = 10
        val re = DoubleArray(n) { cos(2.0 * PI * bin * it / n) }
        val im = DoubleArray(n)
        extractor.fft(re, im, n)

        val mags = DoubleArray(n / 2 + 1) { abs(re[it]) + abs(im[it]) }
        val peak = mags.indices.maxByOrNull { mags[it] }
        assertEquals(bin, peak, "a pure tone at bin $bin must peak at bin $bin")
    }

    @Test
    fun `the Hilbert envelope recovers the amplitude of a tone`() {
        // Channels 0-2 are envelopes, and this 63-tap FIR is how they are formed. It is an
        // approximation, so the point is not that it is exact but that it tracks amplitude
        // rather than the waveform: a broken one oscillates at the carrier frequency instead.
        val amp = 0.5f
        val env = extractor.hilbertEnvelope(tone(1.0, 200.0, amp))
        val middle = env.copyOfRange(env.size / 4, 3 * env.size / 4)
        val mean = middle.average()
        println("hilbert envelope of a $amp tone: mean=$mean min=${middle.min()} max=${middle.max()}")
        assertTrue(abs(mean - amp) < 0.05, "envelope mean $mean should sit near the amplitude $amp")
        assertTrue(middle.max() - middle.min() < 0.2f,
            "a working envelope is flat for a constant-amplitude tone; " +
                "ripple was ${middle.max() - middle.min()}")
    }
}
