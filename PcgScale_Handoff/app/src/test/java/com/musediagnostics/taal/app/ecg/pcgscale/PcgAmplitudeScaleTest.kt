package com.musediagnostics.taal.app.ecg.pcgscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plain-JVM tests for [PcgAmplitudeScale] — the 60%-fill RMS axis scaler. These guard the
 * behaviors the change request asked for by name: RMS-based (not raw average, not raw peak),
 * mean of per-window peaks (so one loud transient can't inflate the scale), and a stable
 * clamped result for silence.
 *
 * Tests use a 1000 Hz sample rate so hop (50 samples) and window (5000 samples) stay cheap;
 * the class derives both from whatever rate it's given, so the math is rate-independent.
 */
class PcgAmplitudeScaleTest {

    private val rate = 1000f

    private fun sine(amplitude: Float, seconds: Float, freqHz: Float = 100f): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { i -> (amplitude * sin(2.0 * PI * freqHz * i / rate)).toFloat() }
    }

    @Test
    fun `before the first 5s window closes, the target is the neutral initial value`() {
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(sine(0.5f, 3f)) // 3s < one window
        assertEquals(0, scale.closedWindowCount())
        assertEquals(PcgAmplitudeScale.DEFAULT_INITIAL_FULL_SCALE, scale.targetFullScale(), 1e-6f)
    }

    @Test
    fun `steady sine lands at its RMS divided by the fill fraction`() {
        val amplitude = 0.3f
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(sine(amplitude, 10f)) // two full windows
        assertEquals(2, scale.closedWindowCount())
        val expectedRms = amplitude / sqrt(2f)
        val expected = expectedRms / PcgAmplitudeScale.TARGET_FILL_FRACTION
        assertEquals(expected, scale.targetFullScale(), 0.01f)
    }

    @Test
    fun `a signal at the computed scale draws at 60 percent of the axis`() {
        val amplitude = 0.3f
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(sine(amplitude, 10f))
        val fill = (amplitude / sqrt(2f)) / scale.targetFullScale()
        assertEquals(PcgAmplitudeScale.TARGET_FILL_FRACTION, fill, 0.02f)
    }

    @Test
    fun `one single-sample spike cannot peg the scale - RMS dilutes it`() {
        // Quiet 0.05 sine with one full-scale (1.0) single-sample click — the failure mode
        // that permanently pegged the old max(abs) warmup lock.
        val samples = sine(0.05f, 10f)
        samples[1234] = 1.0f
        val scale = PcgAmplitudeScale(rate)
        scale.addSamples(samples)

        val quietOnly = PcgAmplitudeScale(rate).also { it.addSamples(sine(0.05f, 10f)) }
        val spiked = scale.targetFullScale()
        val clean = quietOnly.targetFullScale()

        // The old peak logic would have answered ~1.0/0.6 (clamped to 1.0). RMS keeps the
        // spiked result in the same neighborhood as the clean one.
        assertTrue("spiked=$spiked clean=$clean", spiked < clean * 2f)
        assertTrue(spiked < 0.2f)
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
        scale.addSamples(sine(1.0f, 6f)) // RMS .707 → /0.6 = 1.18 → clamp 1.0
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
        val expected = (0.3f / sqrt(2f)) / PcgAmplitudeScale.TARGET_FILL_FRACTION
        assertEquals(expected, batch, 0.01f)
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
