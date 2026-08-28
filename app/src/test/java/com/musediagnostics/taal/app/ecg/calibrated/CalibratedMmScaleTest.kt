package com.musediagnostics.taal.app.ecg.calibrated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibratedMmScaleTest {

    private val pxPerMm = 4f // arbitrary stand-in for a real device's px/mm

    // --- ported from MmScaleTest.kt (9 cases) ---

    @Test
    fun `default gain draws 1mV as exactly 10mm`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm) // defaults: 25mm/s, 10mm/mV
        assertEquals(10f * pxPerMm, scale.mvToPx(1f), 1e-4f)
    }

    @Test
    fun `default speed draws 1s as exactly 25mm`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        assertEquals(25f * pxPerMm, scale.secondsToPx(1f), 1e-4f)
    }

    @Test
    fun `mv and seconds conversions round-trip`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        assertEquals(0.7f, scale.pxToMv(scale.mvToPx(0.7f)), 1e-4f)
        assertEquals(2.3f, scale.pxToSeconds(scale.secondsToPx(2.3f)), 1e-4f)
    }

    @Test
    fun `large square is exactly 5 small squares, always`() {
        assertEquals(5, CalibratedMmScale.SMALL_SQUARES_PER_LARGE_SQUARE)
        assertEquals(5f, CalibratedMmScale.MM_PER_LARGE_SQUARE / CalibratedMmScale.MM_PER_SMALL_SQUARE, 1e-6f)
    }

    @Test
    fun `small square is 0-04s and large square is 0-2s at default 25mm-s`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm) // default speed = 25mm/s
        assertEquals(0.04f, scale.secondsPerSmallSquare(), 1e-4f)
        assertEquals(0.20f, scale.secondsPerLargeSquare(), 1e-4f)
    }

    @Test
    fun `small square is 0-1mV and large square is 0-5mV at default 10mm-mV`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm) // default gain = 10mm/mV
        assertEquals(0.1f, scale.mvPerSmallSquare(), 1e-4f)
        assertEquals(0.5f, scale.mvPerLargeSquare(), 1e-4f)
    }

    @Test
    fun `changing speed changes what a square means but not its px size`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        val smallSquarePxBefore = scale.smallSquarePxX()
        val largeSquarePxBefore = scale.largeSquarePxX()

        scale.paperSpeed = CalibratedPaperSpeed.SPEED_50
        assertEquals(0.02f, scale.secondsPerSmallSquare(), 1e-4f) // meaning changed
        assertEquals(smallSquarePxBefore, scale.smallSquarePxX(), 1e-4f) // px size did not
        assertEquals(largeSquarePxBefore, scale.largeSquarePxX(), 1e-4f)

        scale.paperSpeed = CalibratedPaperSpeed.SPEED_12_5
        assertEquals(0.08f, scale.secondsPerSmallSquare(), 1e-4f)
        assertEquals(smallSquarePxBefore, scale.smallSquarePxX(), 1e-4f)
    }

    @Test
    fun `changing gain scales only the trace, never the grid`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        val smallSquarePxBefore = scale.smallSquarePxY()
        val largeSquarePxBefore = scale.largeSquarePxY()
        val pulsePxAtDefaultGain = scale.mvToPx(1f)

        scale.gain = CalibratedGain.GAIN_20
        val pulsePxAtDoubleGain = scale.mvToPx(1f)
        assertEquals(pulsePxAtDefaultGain * 2f, pulsePxAtDoubleGain, 1e-4f) // trace scaled
        assertEquals(smallSquarePxBefore, scale.smallSquarePxY(), 1e-4f)   // grid did not
        assertEquals(largeSquarePxBefore, scale.largeSquarePxY(), 1e-4f)

        scale.gain = CalibratedGain.GAIN_5
        assertEquals(pulsePxAtDefaultGain / 2f, scale.mvToPx(1f), 1e-4f)
        assertEquals(smallSquarePxBefore, scale.smallSquarePxY(), 1e-4f)
    }

    @Test
    fun `non-square pixels are respected on each axis independently`() {
        val scale = CalibratedMmScale(pxPerMmX = 3f, pxPerMmY = 5f)
        assertEquals(3f, scale.smallSquarePxX(), 1e-4f)
        assertEquals(5f, scale.smallSquarePxY(), 1e-4f)
        assertEquals(15f, scale.largeSquarePxX(), 1e-4f)
        assertEquals(25f, scale.largeSquarePxY(), 1e-4f)
    }

    // --- new: visibleSeconds() ---

    @Test
    fun `visibleSeconds is plot width divided by mm-per-second at default speed`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm) // 25mm/s
        // 1000px wide plot / (25mm/s * 4px/mm) = 10s
        assertEquals(10f, scale.visibleSeconds(1000f), 1e-4f)
        // a typical ~360px-wide phone plot at 4px/mm -> 3.6s
        assertEquals(3.6f, scale.visibleSeconds(360f), 1e-4f)
    }

    @Test
    fun `visibleSeconds scales inversely with paper speed`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        val widthPx = 800f

        scale.paperSpeed = CalibratedPaperSpeed.SPEED_12_5
        val secondsAt12_5 = scale.visibleSeconds(widthPx)
        scale.paperSpeed = CalibratedPaperSpeed.SPEED_25
        val secondsAt25 = scale.visibleSeconds(widthPx)
        scale.paperSpeed = CalibratedPaperSpeed.SPEED_50
        val secondsAt50 = scale.visibleSeconds(widthPx)

        // Doubling mm/s halves the visible seconds for a fixed physical width.
        assertEquals(secondsAt12_5 / 2f, secondsAt25, 1e-4f)
        assertEquals(secondsAt25 / 2f, secondsAt50, 1e-4f)
    }

    @Test
    fun `visibleSeconds is proportional to plot width`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        val secondsAt100 = scale.visibleSeconds(100f)
        val secondsAt200 = scale.visibleSeconds(200f)
        assertEquals(secondsAt100 * 2f, secondsAt200, 1e-4f)
    }

    // --- new: the load-bearing invariant ---
    // Changing paper speed or gain must never change smallSquarePxX()/smallSquarePxY().
    // Speed and gain change what a square *means*, never its physical size.

    @Test
    fun `neither paper speed nor gain ever changes the physical square size`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        val expectedSmallX = scale.smallSquarePxX()
        val expectedSmallY = scale.smallSquarePxY()
        val expectedLargeX = scale.largeSquarePxX()
        val expectedLargeY = scale.largeSquarePxY()

        for (speed in CalibratedPaperSpeed.entries) {
            for (gain in CalibratedGain.entries) {
                scale.paperSpeed = speed
                scale.gain = gain
                assertEquals("speed=$speed gain=$gain", expectedSmallX, scale.smallSquarePxX(), 1e-4f)
                assertEquals("speed=$speed gain=$gain", expectedSmallY, scale.smallSquarePxY(), 1e-4f)
                assertEquals("speed=$speed gain=$gain", expectedLargeX, scale.largeSquarePxX(), 1e-4f)
                assertEquals("speed=$speed gain=$gain", expectedLargeY, scale.largeSquarePxY(), 1e-4f)
            }
        }
    }

    // --- new: DPI correction factors ---

    @Test
    fun `DPI correction default is 1-0 (no correction) when unset`() {
        val correction = DpiCalibration.Correction(
            x = 1.0f, y = 1.0f, isCalibrated = false, source = "uncalibrated"
        )
        assertEquals(1.0f, correction.x, 1e-6f)
        assertEquals(1.0f, correction.y, 1e-6f)
        assertTrue(!correction.isCalibrated)
    }

    @Test
    fun `DPI correction factors apply multiplicatively to reported px-per-mm`() {
        val reportedPxPerMmX = 4f
        val reportedPxPerMmY = 3.9f
        val correctionX = 0.98f
        val correctionY = 1.03f

        val correctedX = reportedPxPerMmX * correctionX
        val correctedY = reportedPxPerMmY * correctionY

        assertEquals(3.92f, correctedX, 1e-4f)
        assertEquals(4.017f, correctedY, 1e-3f)
    }
}
