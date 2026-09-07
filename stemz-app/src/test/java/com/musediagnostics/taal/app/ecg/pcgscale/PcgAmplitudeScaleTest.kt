package com.musediagnostics.taal.app.ecg.pcgscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Plain-JVM tests for [PcgAmplitudeScale] — the 60%-fill axis scaler, rev 3 semantics merged
 * with ledger Fix 1: "energy picks, amplitude calibrates, Kth-largest + median protect".
 * These guard the behaviors the change request asked for by name: maxima (the DRAWN peaks)
 * at 60% of the height, a transient unable to inflate the scale — even in the history-free
 * FIRST window (ledger Fix 1's case) — quiet/outlier windows unable to dilute it, and a
 * stable clamped result for silence.
 *
 * Tests use a 1000 Hz sample rate so hop (50 samples) and window (5000 samples) stay cheap;
 * the class derives both from whatever rate it's given, so the math is rate-independent.
 */
class PcgAmplitudeScaleTest {

    private val rate = 1000f

    // 25 Hz default: at the 1000 Hz test rate that's 40 samples/cycle, so every 50-sample
    // hop contains an exact-peak sample — a higher test frequency would undersample the
    // peak (~0.95A at 100 Hz) and trip the tight tolerances below for no real reason.
    private fun sine(amplitude: Float, seconds: Float, freqHz: Float = 25f, sr: Float = rate): FloatArray {
        val n = (seconds * sr).toInt()
        return FloatArray(n) { i -> (amplitude * sin(2.0 * PI * freqHz * i / sr)).toFloat() }
    }

    @Test
    fun `before the first 5s window closes, the target is the neutral initial value`() {
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(sine(0.5f, 3f)) // 3s < one window
        assertEquals(0, scale.closedWindowCount())
        assertEquals(PcgAmplitudeScale.DEFAULT_INITIAL_FULL_SCALE, scale.targetFullScale(), 1e-6f)
    }

    @Test
    fun `the DRAWN peak is what calibrates - steady sine targets its peak over the fill fraction`() {
        val amplitude = 0.3f
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(sine(amplitude, 10f)) // two full windows
        assertEquals(2, scale.closedWindowCount())
        // Rev 2 targeted RMS (A/√2) here and under-filled by the crest factor; rev 3 targets
        // the drawn peak amplitude itself.
        assertEquals(amplitude / PcgAmplitudeScale.TARGET_FILL_FRACTION, scale.targetFullScale(), 0.01f)
    }

    @Test
    fun `a signal at the computed scale draws its maxima at exactly 60 percent of the axis`() {
        val amplitude = 0.3f
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(sine(amplitude, 10f))
        val drawnFill = amplitude / scale.targetFullScale()
        assertEquals(PcgAmplitudeScale.TARGET_FILL_FRACTION, drawnFill, 0.02f)
    }

    @Test
    fun `fill is uniform across signal shapes - burst and sine with equal peaks scale the same`() {
        // The rev-2 non-uniformity: equal drawn peaks, different crest factors → different
        // fill. Rev 3 must give both the same axis. Burst: 100ms of tone per second, silence
        // between (heart-sound-like, ~5 loud hops per 5s window — enough for the 3rd-largest
        // selection to still land on a genuine burst hop); sine: continuous.
        val amplitude = 0.3f
        val continuous = sine(amplitude, 10f)
        val burst = FloatArray(continuous.size) { i ->
            if ((i % 1000) < 100) continuous[i] else 0f
        }
        val a = PcgAmplitudeScale(rate).also { it.addSamples(continuous) }
        val b = PcgAmplitudeScale(rate).also { it.addSamples(burst) }
        assertEquals(a.targetFullScale(), b.targetFullScale(), 0.02f)
    }

    @Test
    fun `ledger Fix 1 - a spike in the history-free FIRST window cannot calibrate`() {
        // The exact case the ledger measured failing pre-fix (2.56x overshoot): one window,
        // no cross-window statistic to dilute against. The Kth-largest-by-RMS selection must
        // reject it alone: the spike elevates only its own hop, which can win 1st place at
        // most — never 3rd.
        val samples = sine(0.05f, 5f) // exactly one window
        samples[1234] = 1.0f
        val spiked = PcgAmplitudeScale(rate).also { it.addSamples(samples) }
        val clean = PcgAmplitudeScale(rate).also { it.addSamples(sine(0.05f, 5f)) }
        assertEquals(1, spiked.closedWindowCount())
        assertEquals(clean.targetFullScale(), spiked.targetFullScale(), 0.01f)
    }

    @Test
    fun `a single-sample spike in a longer recording cannot calibrate either`() {
        val samples = sine(0.05f, 20f) // four windows
        samples[7777] = 1.0f
        val spiked = PcgAmplitudeScale(rate).also { it.addSamples(samples) }
        val clean = PcgAmplitudeScale(rate).also { it.addSamples(sine(0.05f, 20f)) }
        assertEquals(clean.targetFullScale(), spiked.targetFullScale(), 0.01f)
    }

    @Test
    fun `one loud outlier window is rejected by the median, not averaged in`() {
        // Three normal windows + one 4x-louder window (a cough / contact bump) — every hop
        // in the loud window is genuinely loud, so Kth-largest selection alone can't reject
        // it; the cross-window MEDIAN must. The rev-2 mean would have inflated ~75%.
        val normal = sine(0.2f, 5f)
        val loud = sine(0.8f, 5f)
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(normal); scale.addSamples(normal); scale.addSamples(loud); scale.addSamples(normal)
        assertEquals(0.2f / PcgAmplitudeScale.TARGET_FILL_FRACTION, scale.targetFullScale(), 0.02f)
    }

    @Test
    fun `silence clamps to the minimum full scale, never a degenerate axis`() {
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(FloatArray((6f * rate).toInt())) // 6s of zeros → one closed window
        assertTrue(scale.closedWindowCount() >= 1)
        assertEquals(PcgAmplitudeScale.MIN_FULL_SCALE, scale.targetFullScale(), 1e-6f)
    }

    @Test
    fun `full-scale input clamps to the maximum`() {
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(sine(1.0f, 6f)) // peak 1.0 → /0.6 = 1.67 → clamp 1.0
        assertEquals(PcgAmplitudeScale.MAX_FULL_SCALE, scale.targetFullScale(), 1e-6f)
    }

    @Test
    fun `whole-file computation matches streaming the same samples`() {
        val samples = sine(0.25f, 10f) // exact multiple of the window length
        val streaming = PcgAmplitudeScale(rate).also { it.addSamples(samples) }
        val batch = PcgAmplitudeScale.computeFullScaleForFile(samples, rate)
        assertEquals(streaming.targetFullScale(), batch, 1e-4f)
    }

    @Test
    fun `whole-file computation measures files shorter than one window via the partial flush`() {
        val samples = sine(0.3f, 2f) // < 5s — live streaming would still be on initial here
        val batch = PcgAmplitudeScale.computeFullScaleForFile(samples, rate)
        assertEquals(0.3f / PcgAmplitudeScale.TARGET_FILL_FRACTION, batch, 0.01f)
    }

    @Test
    fun `min-clamp is reported so a too-quiet device is diagnosable on screen`() {
        val quiet = PcgAmplitudeScale(rate)
        quiet.addSamples(sine(0.002f, 6f)) // peak 0.002 → /0.6 ≈ 0.0033 < MIN_FULL_SCALE (0.005)
        assertTrue(quiet.isClampedAtMin())
        assertEquals(PcgAmplitudeScale.MIN_FULL_SCALE, quiet.targetFullScale(), 1e-6f)

        val normal = PcgAmplitudeScale(rate)
        normal.addSamples(sine(0.3f, 6f))
        assertTrue(!normal.isClampedAtMin())
    }

    @Test
    fun `typicalPeakAmplitude reports the measured statistic and is rate-independent`() {
        val amplitude = 0.3f
        val at1k = PcgAmplitudeScale(1000f).also { it.addSamples(sine(amplitude, 6f)) }
        assertEquals(amplitude, at1k.typicalPeakAmplitude(), 0.01f)

        // Same signal content at a 48k-style rate (the Samsung case) → same statistic, so
        // the 60%-fill result does not depend on which rate the device's audio stack runs at.
        val at48k = PcgAmplitudeScale(48000f).also { it.addSamples(sine(amplitude, 6f, sr = 48000f)) }
        assertEquals(at1k.typicalPeakAmplitude(), at48k.typicalPeakAmplitude(), 0.01f)
        assertEquals(at1k.targetFullScale(), at48k.targetFullScale(), 0.01f)
    }

    @Test
    fun `smoothed value converges to the target without overshooting`() {
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(sine(0.3f, 6f))
        val target = scale.targetFullScale()
        var previousGap = Float.MAX_VALUE
        var value = 0f
        repeat(100) {
            value = scale.smoothedFullScale()
            val gap = abs(target - value)
            assertTrue("gap grew: $gap > $previousGap", gap <= previousGap + 1e-6f)
            previousGap = gap
        }
        assertEquals(target, value, 0.01f)
    }

    @Test
    fun `reset returns everything to the session-start state`() {
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(sine(0.5f, 10f))
        assertTrue(scale.closedWindowCount() > 0)
        scale.reset()
        assertEquals(0, scale.closedWindowCount())
        assertEquals(PcgAmplitudeScale.DEFAULT_INITIAL_FULL_SCALE, scale.targetFullScale(), 1e-6f)
    }
}
