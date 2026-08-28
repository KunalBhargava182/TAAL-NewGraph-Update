package com.musediagnostics.taal.app.ecg

import org.junit.Assert.assertEquals
import org.junit.Test

class MmScaleTest {

    private val pxPerMm = 4f // arbitrary stand-in for a real device's px/mm

    @Test
    fun `default gain draws 1mV as exactly 10mm`() {
        val scale = MmScale(pxPerMm, pxPerMm) // defaults: 25mm/s, 10mm/mV
        assertEquals(10f * pxPerMm, scale.mvToPx(1f), 1e-4f)
    }

    @Test
    fun `default speed draws 1s as exactly 25mm`() {
        val scale = MmScale(pxPerMm, pxPerMm)
        assertEquals(25f * pxPerMm, scale.secondsToPx(1f), 1e-4f)
    }

    @Test
    fun `mv and seconds conversions round-trip`() {
        val scale = MmScale(pxPerMm, pxPerMm)
        assertEquals(0.7f, scale.pxToMv(scale.mvToPx(0.7f)), 1e-4f)
        assertEquals(2.3f, scale.pxToSeconds(scale.secondsToPx(2.3f)), 1e-4f)
    }

    @Test
    fun `large square is exactly 5 small squares, always`() {
        assertEquals(5, MmScale.SMALL_SQUARES_PER_LARGE_SQUARE)
        assertEquals(5f, MmScale.MM_PER_LARGE_SQUARE / MmScale.MM_PER_SMALL_SQUARE, 1e-6f)
    }

    @Test
    fun `small square is 0-04s and large square is 0-2s at default 25mm-s`() {
        val scale = MmScale(pxPerMm, pxPerMm) // default speed = 25mm/s
        assertEquals(0.04f, scale.secondsPerSmallSquare(), 1e-4f)
        assertEquals(0.20f, scale.secondsPerLargeSquare(), 1e-4f)
    }

    @Test
    fun `small square is 0-1mV and large square is 0-5mV at default 10mm-mV`() {
        val scale = MmScale(pxPerMm, pxPerMm) // default gain = 10mm/mV
        assertEquals(0.1f, scale.mvPerSmallSquare(), 1e-4f)
        assertEquals(0.5f, scale.mvPerLargeSquare(), 1e-4f)
    }

    @Test
    fun `changing speed changes what a square means but not its px size`() {
        val scale = MmScale(pxPerMm, pxPerMm)
        val smallSquarePxBefore = scale.smallSquarePxX()
        val largeSquarePxBefore = scale.largeSquarePxX()

        scale.paperSpeed = PaperSpeed.SPEED_50
        assertEquals(0.02f, scale.secondsPerSmallSquare(), 1e-4f) // meaning changed
        assertEquals(smallSquarePxBefore, scale.smallSquarePxX(), 1e-4f) // px size did not
        assertEquals(largeSquarePxBefore, scale.largeSquarePxX(), 1e-4f)

        scale.paperSpeed = PaperSpeed.SPEED_12_5
        assertEquals(0.08f, scale.secondsPerSmallSquare(), 1e-4f)
        assertEquals(smallSquarePxBefore, scale.smallSquarePxX(), 1e-4f)
    }

    @Test
    fun `changing gain scales only the trace, never the grid`() {
        val scale = MmScale(pxPerMm, pxPerMm)
        val smallSquarePxBefore = scale.smallSquarePxY()
        val largeSquarePxBefore = scale.largeSquarePxY()
        val pulsePxAtDefaultGain = scale.mvToPx(1f)

        scale.gain = Gain.GAIN_20
        val pulsePxAtDoubleGain = scale.mvToPx(1f)
        assertEquals(pulsePxAtDefaultGain * 2f, pulsePxAtDoubleGain, 1e-4f) // trace scaled
        assertEquals(smallSquarePxBefore, scale.smallSquarePxY(), 1e-4f)   // grid did not
        assertEquals(largeSquarePxBefore, scale.largeSquarePxY(), 1e-4f)

        scale.gain = Gain.GAIN_5
        assertEquals(pulsePxAtDefaultGain / 2f, scale.mvToPx(1f), 1e-4f)
        assertEquals(smallSquarePxBefore, scale.smallSquarePxY(), 1e-4f)
    }

    @Test
    fun `non-square pixels are respected on each axis independently`() {
        val scale = MmScale(pxPerMmX = 3f, pxPerMmY = 5f)
        assertEquals(3f, scale.smallSquarePxX(), 1e-4f)
        assertEquals(5f, scale.smallSquarePxY(), 1e-4f)
        assertEquals(15f, scale.largeSquarePxX(), 1e-4f)
        assertEquals(25f, scale.largeSquarePxY(), 1e-4f)
    }
}
