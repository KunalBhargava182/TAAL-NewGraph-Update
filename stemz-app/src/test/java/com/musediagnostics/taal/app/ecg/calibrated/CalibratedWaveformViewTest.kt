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

    // --- Fix D: deriveBucketSizeForVisibleRange (player zoom re-bucketing) ---

    @Test
    fun `derived visible-range bucket targets roughly one pair per horizontal pixel`() {
        val plotWidthPx = 1000f
        val visibleSampleCount = 44_100f // 1s of audio visible across 1000px
        val bucket = CalibratedWaveformView.deriveBucketSizeForVisibleRange(visibleSampleCount, plotWidthPx)
        val expected = (visibleSampleCount / (plotWidthPx * 2f)).roundToInt()
        assertEquals(expected, bucket)
        assertTrue(bucket > 0)
    }

    @Test
    fun `zooming in shrinks the derived bucket, zooming out grows it`() {
        val plotWidthPx = 1000f
        val zoomedInSamples = 4_410f    // ~0.1s visible - deep zoom
        val defaultSamples = 44_100f    // 1s visible - default
        val zoomedOutSamples = 441_000f // 10s visible - zoomed out

        val bucketZoomedIn = CalibratedWaveformView.deriveBucketSizeForVisibleRange(zoomedInSamples, plotWidthPx)
        val bucketDefault = CalibratedWaveformView.deriveBucketSizeForVisibleRange(defaultSamples, plotWidthPx)
        val bucketZoomedOut = CalibratedWaveformView.deriveBucketSizeForVisibleRange(zoomedOutSamples, plotWidthPx)

        assertTrue(bucketZoomedIn < bucketDefault)
        assertTrue(bucketDefault < bucketZoomedOut)
    }

    @Test
    fun `derived visible-range bucket never goes below 1`() {
        // Extreme deep zoom: a handful of samples spread across a wide plot.
        val bucket = CalibratedWaveformView.deriveBucketSizeForVisibleRange(4f, 2000f)
        assertEquals(1, bucket)
    }

    @Test
    fun `pre-layout fallback - invalid visible-range inputs return -1`() {
        assertEquals(-1, CalibratedWaveformView.deriveBucketSizeForVisibleRange(44_100f, 0f))
        assertEquals(-1, CalibratedWaveformView.deriveBucketSizeForVisibleRange(44_100f, -1f))
        assertEquals(-1, CalibratedWaveformView.deriveBucketSizeForVisibleRange(0f, 1000f))
        assertEquals(-1, CalibratedWaveformView.deriveBucketSizeForVisibleRange(-1f, 1000f))
    }

    @Test
    fun `zoom re-bucket budget ceiling clamps a long file but never shrinks below derived`() {
        val targetPointBudget = 3000
        val plotWidthPx = 1000f

        // Zoomed out on a very long file: derived-from-visible-range bucket could still be
        // small relative to the WHOLE file's total sample count, so re-bucketing the entire
        // file at that bucket would blow past the point budget -> ceiling must win.
        val longFileTotalSamples = 50_000_000
        val visibleSamples = 4_410_000f // 100s visible, zoomed out on a long file
        val derived = CalibratedWaveformView.deriveBucketSizeForVisibleRange(visibleSamples, plotWidthPx)
        val ceiling = maxOf(1, longFileTotalSamples / (targetPointBudget / 2))
        val final = if (derived > 0) maxOf(derived, ceiling) else ceiling
        assertEquals(ceiling, final)
        assertTrue(final > derived)

        // Short file viewed in a wider-than-the-file default window (e.g. a 1s recording shown
        // in a 4s-wide view) — the visible range exceeds the file's own duration, so the
        // derived bucket comes out larger than the budget would strictly require -> must NOT
        // be shrunk down to the ceiling; the ceiling only ever enlarges, never shrinks.
        val shortFileTotalSamples = 44_100 // 1s file
        val widerThanFileVisibleSamples = 176_400f // 4s visible window on a 1s file
        val derivedWideView = CalibratedWaveformView.deriveBucketSizeForVisibleRange(widerThanFileVisibleSamples, plotWidthPx)
        val ceilingShortFile = maxOf(1, shortFileTotalSamples / (targetPointBudget / 2))
        val finalWideView = if (derivedWideView > 0) maxOf(derivedWideView, ceilingShortFile) else ceilingShortFile
        assertEquals(derivedWideView, finalWideView)
        assertTrue(finalWideView >= derivedWideView)
        assertTrue(derivedWideView > ceilingShortFile) // confirms this case actually exercises the "don't shrink" branch
    }

    // --- ringTrimMinX (FullTimeOn live preview's ring-buffer trim bound) ---

    @Test
    fun `ring trim keeps everything while still inside the first window`() {
        // latestX hasn't reached a second window yet -> currentPage is 0 -> nothing trimmed.
        assertEquals(0f, CalibratedWaveformView.ringTrimMinX(latestX = 3f, windowSeconds = 4f), 1e-4f)
    }

    @Test
    fun `ring trim drops everything older than one window before the current page`() {
        // windowSeconds=4, latestX=9 -> currentPage=2 -> minXToKeep=(2-1)*4=4
        assertEquals(4f, CalibratedWaveformView.ringTrimMinX(latestX = 9f, windowSeconds = 4f), 1e-4f)
    }

    @Test
    fun `ring trim bound never grows unbounded as latestX advances indefinitely`() {
        // The whole point of the ring trim for an open-ended live session (§4.5): however long
        // latestX grows, the retained span (latestX - minXToKeep) stays bounded to at most
        // ~2 windows, not accumulating with elapsed time.
        val windowSeconds = 4f
        var latestX = 0f
        var maxRetainedSpan = 0f
        repeat(10_000) { // simulate a very long-running preview session advancing in small steps
            latestX += 0.037f // arbitrary sub-window step, doesn't divide windowSeconds evenly
            val minXToKeep = CalibratedWaveformView.ringTrimMinX(latestX, windowSeconds)
            val retainedSpan = latestX - minXToKeep
            if (retainedSpan > maxRetainedSpan) maxRetainedSpan = retainedSpan
        }
        assertTrue("retained span ballooned to $maxRetainedSpan for a $windowSeconds s window", maxRetainedSpan <= 2f * windowSeconds + 1e-3f)
    }

    @Test
    fun `ring trim never returns negative and treats non-positive window as untrimmed`() {
        assertEquals(0f, CalibratedWaveformView.ringTrimMinX(latestX = 100f, windowSeconds = 0f), 1e-4f)
        assertEquals(0f, CalibratedWaveformView.ringTrimMinX(latestX = 100f, windowSeconds = -1f), 1e-4f)
        assertTrue(CalibratedWaveformView.ringTrimMinX(latestX = 0f, windowSeconds = 4f) >= 0f)
    }
}
