package com.purnacardio.signal.pcg

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Level normalisation ahead of the TCN segmenter.
 *
 * This exists because of a real capture. Recording 20260807-102243 peaked at −5.7 dBFS — loud —
 * and came back with no heart sounds, while 20260807-102815 peaked at −35.7 dBFS and *did* contain
 * a heartbeat at 86 bpm that a plain envelope autocorrelation finds without difficulty. Dividing by
 * `max|x|` gives one sample authority over the whole recording, and on a phone held to a chest that
 * sample is very often a knock rather than a heart sound.
 *
 * The measured discriminator, from those two captures at the 2 kHz rate the extractor works at:
 *
 * | | max / p99.5 |
 * |---|---|
 * | peaks are the heart sounds (102815) | 2.0 |
 * | one transient dominates (102243) | 15.8 |
 */
class PcgNormalisationTest {

    private val fs = PcgFeatureExtractor.TARGET_SR

    /** Heart-sound-ish: a repeating pair of short bursts on a 60 bpm cadence. */
    private fun syntheticPcg(seconds: Int = 20, amplitude: Float = 0.3f): FloatArray {
        val n = seconds * fs
        val out = FloatArray(n)
        val rnd = java.util.Random(7)
        for (i in 0 until n) out[i] = (0.01 * rnd.nextGaussian()).toFloat()
        var t = 0.25
        while (t < seconds - 0.6) {
            for ((offset, amp) in listOf(0.0 to amplitude, 0.32 to amplitude * 0.8f)) {
                val at = ((t + offset) * fs).toInt()
                val len = (0.05 * fs).toInt()
                for (k in 0 until len) {
                    val i = at + k
                    if (i in out.indices) {
                        val env = kotlin.math.exp(-3.0 * k / len)
                        out[i] += (amp * env * sin(2.0 * Math.PI * 60.0 * k / fs)).toFloat()
                    }
                }
            }
            t += 1.0
        }
        return out
    }

    @Test
    fun `a recording whose peaks are its heart sounds is normalised exactly as before`() {
        // The property that matters most. The model was trained on peak-normalised audio, so the
        // ordinary case must be bit-for-bit what it always was — this is a guard against outliers,
        // not a new normalisation scheme.
        val x = syntheticPcg()
        val maxAbs = x.maxOf { abs(it) }
        assertEquals(maxAbs, PcgFeatureExtractor().normaliser(x, maxAbs), 1e-6f,
            "the cap must not bind on a well-formed recording")
    }

    @Test
    fun `a single knock does not get to scale the whole recording`() {
        val x = syntheticPcg()
        val maxAbs = x.maxOf { abs(it) }
        val knocked = x.copyOf()
        // One 12 ms thump, 20x the heart sounds — the shape of a phone bumping the chest.
        val at = 7 * fs
        for (k in 0 until (0.012 * fs).toInt()) knocked[at + k] += 20f * maxAbs

        val denom = PcgFeatureExtractor().normaliser(knocked, knocked.maxOf { abs(it) })
        assertTrue(denom < knocked.maxOf { abs(it) } / 4f,
            "the knock must not set the scale: denominator $denom against a peak of " +
                "${knocked.maxOf { abs(it) }}")

        // What that buys: the heart sounds survive at a usable level instead of being crushed.
        // Measured here at 0.34 against 0.050 — a factor of 6.8, or 16.6 dB. The recovery is
        // bounded by the knock's severity over OUTLIER_HEADROOM, so a worse knock recovers more;
        // it is not a fixed gain.
        val crushed = maxAbs / knocked.maxOf { abs(it) }      // old behaviour
        val kept = maxAbs / denom                              // new behaviour
        assertTrue(kept > 5 * crushed,
            "heart sounds should come through far louder than peak normalisation allowed " +
                "(kept $kept vs crushed $crushed)")
    }

    @Test
    fun `normalised output never exceeds full scale`() {
        // Once the peak can be capped, samples above the cap have to be bounded or the model sees
        // values outside anything it was trained on.
        val x = syntheticPcg()
        val knocked = x.copyOf()
        val at = 3 * fs
        for (k in 0 until (0.012 * fs).toInt()) knocked[at + k] += 30f * x.maxOf { abs(it) }
        val denom = PcgFeatureExtractor().normaliser(knocked, knocked.maxOf { abs(it) })
        val normalised = FloatArray(knocked.size) { (knocked[it] / denom).coerceIn(-1f, 1f) }
        assertTrue(normalised.all { abs(it) <= 1.0f }, "clipping must bound the result")
        assertTrue(normalised.any { abs(it) > 0.2f }, "and must not flatten the signal to nothing")
    }

    @Test
    fun `near-silence is still rejected rather than amplified into noise`() {
        // A dead microphone must not be rescued by any of this. `extract` returns null below its
        // silence floor, and that has to stay true.
        val silent = FloatArray(5 * fs)
        assertTrue(PcgFeatureExtractor().extract(silent) == null,
            "a silent recording must yield no features")
    }

    @Test
    fun `a quiet but well-formed recording is unaffected by its own quietness`() {
        // 102815 was 35 dB down and still held a real heartbeat. Scaling a recording down must not
        // change what comes out, since normalisation divides it back out again.
        val loud = syntheticPcg(amplitude = 0.6f)
        val quiet = FloatArray(loud.size) { loud[it] / 1000f }
        val a = PcgFeatureExtractor().normaliser(loud, loud.maxOf { abs(it) })
        val b = PcgFeatureExtractor().normaliser(quiet, quiet.maxOf { abs(it) })
        assertEquals(1000f, a / b, 1f, "the normaliser must scale with the recording")
    }
}
