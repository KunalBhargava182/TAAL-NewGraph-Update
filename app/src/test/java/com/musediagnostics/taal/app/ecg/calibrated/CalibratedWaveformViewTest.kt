package com.musediagnostics.taal.app.ecg.calibrated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Fix C — unit tests for [CalibratedWaveformView.deriveBucketSize], the derived-downsample-
 * bucket helper. Plain-JVM: the function under test is a pure companion member with no Android
 * View/Canvas dependency, so no Robolectric/instrumentation is needed.
 */
class CalibratedWaveformViewTest {

    private val sampleRate = 44100f
    private val pxPerMm = 4f // arbitrary stand-in for a real device's px/mm, matches CalibratedMmScaleTest

    @Test
    fun `derived bucket targets roughly one pair per horizontal pixel at 25mm-s`() {
        val bucket = CalibratedWaveformView.deriveBucketSize(sampleRate, 25f, pxPerMm)
        // pixelsPerSecond = 25 * 4 = 100; bucket = round(44100 / (100 * 2)) = round(220.5) = 220 or 221
        val expected = (sampleRate / (25f * pxPerMm * 2f)).roundToInt()
        assertEquals(expected, bucket)
        assertTrue(bucket > 0)
    }

    @Test
    fun `derived bucket at all three standard paper speeds is correct and scales with speed`() {
        val bucketAt12_5 = CalibratedWaveformView.deriveBucketSize(sampleRate, 12.5f, pxPerMm)
        val bucketAt25 = CalibratedWaveformView.deriveBucketSize(sampleRate, 25f, pxPerMm)
        val bucketAt50 = CalibratedWaveformView.deriveBucketSize(sampleRate, 50f, pxPerMm)

        assertEquals((sampleRate / (12.5f * pxPerMm * 2f)).roundToInt(), bucketAt12_5)
        assertEquals((sampleRate / (25f * pxPerMm * 2f)).roundToInt(), bucketAt25)
        assertEquals((sampleRate / (50f * pxPerMm * 2f)).roundToInt(), bucketAt50)

        // Doubling paper speed doubles pixels-per-second, so the bucket (samples per pixel-pair)
        // roughly halves — faster paper speed needs finer time resolution per bucket.
        assertTrue(bucketAt25 < bucketAt12_5)
        assertTrue(bucketAt50 < bucketAt25)
    }

    @Test
    fun `derived bucket never goes below 1`() {
        // Extreme case: absurdly high px-per-mm would derive a sub-1 bucket without the floor.
        val bucket = CalibratedWaveformView.deriveBucketSize(sampleRate, 50f, 5000f)
        assertEquals(1, bucket)
    }

    @Test
    fun `pre-layout fallback - invalid inputs return -1, not a crash or divide-by-zero`() {
        assertEquals(-1, CalibratedWaveformView.deriveBucketSize(sampleRate, 25f, 0f))
        assertEquals(-1, CalibratedWaveformView.deriveBucketSize(sampleRate, 25f, -1f))
        assertEquals(-1, CalibratedWaveformView.deriveBucketSize(sampleRate, 0f, pxPerMm))
        assertEquals(-1, CalibratedWaveformView.deriveBucketSize(0f, 25f, pxPerMm))
    }

    @Test
    fun `player budget ceiling clamps a long file but never shrinks below the derived bucket`() {
        val targetPointBudget = 3000

        // Long file: derived bucket would blow past the point budget -> ceiling must win (enlarge).
        val longFileSamples = 50_000_000 // ~19 minutes at 44.1kHz
        val derivedForLongFile = CalibratedWaveformView.deriveBucketSize(sampleRate, 25f, pxPerMm)
        val ceilingForLongFile = maxOf(1, longFileSamples / (targetPointBudget / 2))
        val finalForLongFile = if (derivedForLongFile > 0) maxOf(derivedForLongFile, ceilingForLongFile) else ceilingForLongFile
        assertEquals(ceilingForLongFile, finalForLongFile)
        assertTrue(finalForLongFile > derivedForLongFile)

        // Short file: derived bucket already respects the budget -> must NOT be shrunk below derived.
        val shortFileSamples = 44_100 // 1 second
        val derivedForShortFile = CalibratedWaveformView.deriveBucketSize(sampleRate, 25f, pxPerMm)
        val ceilingForShortFile = maxOf(1, shortFileSamples / (targetPointBudget / 2))
        val finalForShortFile = if (derivedForShortFile > 0) maxOf(derivedForShortFile, ceilingForShortFile) else ceilingForShortFile
        assertEquals(derivedForShortFile, finalForShortFile)
        assertTrue(finalForShortFile >= derivedForShortFile)
    }
}
