package com.musediagnostics.taal.stemz.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for [PcgTimeScale] — the PcgScale screens' fixed time mapping. These guard
 * the two invariants the whole screen family exists for:
 *  1. one large box is exactly 1 second (and a small box exactly 0.2s), at any pixel density;
 *  2. the tick/label positions derive from integer indices off this one object, so grid,
 *     trace window, and second-labels can never drift apart, however long the recording.
 */
class PcgTimeScaleTest {

    private val eps = 1e-4f

    @Test
    fun `fromPlotWidth derives pixelsPerSecond as width over visibleSeconds`() {
        val scale = PcgTimeScale.fromPlotWidth(1080f, 4f)!!
        assertEquals(270f, scale.pixelsPerSecond, eps)
    }

    @Test
    fun `fromPlotWidth returns null before layout gives a real width`() {
        assertNull(PcgTimeScale.fromPlotWidth(0f, 4f))
        assertNull(PcgTimeScale.fromPlotWidth(-5f, 4f))
        assertNull(PcgTimeScale.fromPlotWidth(1080f, 0f))
    }

    @Test
    fun `large box is exactly one second of pixels`() {
        val scale = PcgTimeScale(270f)
        assertEquals(scale.secondsToPx(1f), scale.largeSquarePx(), eps)
    }

    @Test
    fun `small box is exactly one fifth of a large box`() {
        val scale = PcgTimeScale(270f)
        assertEquals(scale.largeSquarePx() / 5f, scale.smallSquarePx(), eps)
        assertEquals(scale.secondsToPx(0.2f), scale.smallSquarePx(), eps)
    }

    @Test
    fun `secondsToPx and pxToSeconds are inverses`() {
        val scale = PcgTimeScale(313.7f) // deliberately awkward density
        assertEquals(2.7f, scale.pxToSeconds(scale.secondsToPx(2.7f)), eps)
        assertEquals(847f, scale.secondsToPx(scale.pxToSeconds(847f)), 1e-2f)
    }

    @Test
    fun `visibleSeconds round-trips the width the scale was built from`() {
        val scale = PcgTimeScale.fromPlotWidth(1080f, 4f)!!
        assertEquals(4f, scale.visibleSeconds(1080f), eps)
    }

    @Test
    fun `first visible tick at zero offset is tick zero`() {
        val scale = PcgTimeScale(270f)
        assertEquals(0, scale.firstTickIndexVisible(0f))
    }

    @Test
    fun `first visible tick lands on the tick at the exact offset, tolerant of float error`() {
        val scale = PcgTimeScale(270f)
        // Left edge exactly at t=1.0s → tick index 5 (the 1-second major) is the first visible.
        assertEquals(5, scale.firstTickIndexVisible(1.0f))
        // A hair under (accumulated float error from the chart) must not skip to 6.
        assertEquals(5, scale.firstTickIndexVisible(0.99999f))
        // Clearly past it → next tick.
        assertEquals(6, scale.firstTickIndexVisible(1.05f))
    }

    @Test
    fun `major ticks are exactly the whole seconds`() {
        val scale = PcgTimeScale(270f)
        assertTrue(scale.isMajorTick(0))
        assertTrue(scale.isMajorTick(5))
        assertTrue(scale.isMajorTick(150)) // t = 30s
        assertFalse(scale.isMajorTick(1))
        assertFalse(scale.isMajorTick(4))
        assertFalse(scale.isMajorTick(151))
    }

    @Test
    fun `tick times come from integer indices - no drift after five minutes`() {
        val scale = PcgTimeScale(270f)
        // 300s = the app's max recording length; tick 1500 must be exactly 300s.
        assertEquals(300f, scale.tickTimeSeconds(1500), eps)
        assertTrue(scale.isMajorTick(1500))
    }

    @Test
    fun `tick position is zero when the tick sits at the left edge and one small box for the next`() {
        val scale = PcgTimeScale(270f)
        assertEquals(0f, scale.tickPositionPx(5, 1.0f), eps)
        assertEquals(scale.smallSquarePx(), scale.tickPositionPx(6, 1.0f), 1e-2f)
    }

    @Test
    fun `grid trace and labels share one density - a second is the same px everywhere`() {
        val scale = PcgTimeScale.fromPlotWidth(1080f, 4f)!!
        // The px the chart advances per second (window lock), the large-box width the grid
        // draws, and the spacing between consecutive second-labels are all the same number.
        val chartPxPerSecond = 1080f / scale.visibleSeconds(1080f)
        val gridLargeBoxPx = scale.largeSquarePx()
        val labelSpacingPx = scale.tickPositionPx(10, 0f) - scale.tickPositionPx(5, 0f)
        assertEquals(chartPxPerSecond, gridLargeBoxPx, eps)
        assertEquals(gridLargeBoxPx, labelSpacingPx, 1e-2f)
    }
}
