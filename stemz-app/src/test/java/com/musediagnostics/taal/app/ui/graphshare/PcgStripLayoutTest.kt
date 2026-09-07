package com.musediagnostics.taal.app.ui.graphshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for [PcgStripLayout] — pure Kotlin, no Android runtime needed (same reasoning
 * as PcgTimeScaleTest for the on-screen grid this export mirrors).
 */
class PcgStripLayoutTest {

    @Test
    fun `exact multiple of secondsPerRow produces exactly that many rows`() {
        // 15s recording, 5s rows -> exactly 3 rows, no trailing empty row.
        val layout = PcgStripLayout(durationSecs = 15f, secondsPerRow = 5f, canvasWidthPx = 1000f)
        assertEquals(3, layout.rowCount)
        assertEquals(3, layout.rows.size)
    }

    @Test
    fun `partial final row is still allocated`() {
        // 14s at 5s rows -> rows covering 0-5, 5-10, 10-14 (the third is partial).
        val layout = PcgStripLayout(durationSecs = 14f, secondsPerRow = 5f, canvasWidthPx = 1000f)
        assertEquals(3, layout.rowCount)
        val last = layout.rows.last()
        assertEquals(10f, last.startSec, 1e-4f)
        assertEquals(14f, last.endSec, 1e-4f)
        assertTrue("partial row should be shorter than a full row", last.durationSec < 5f)
    }

    @Test
    fun `rows tile the recording with no gap or overlap`() {
        val layout = PcgStripLayout(durationSecs = 23f, secondsPerRow = 5f, canvasWidthPx = 1000f)
        for (i in 0 until layout.rows.size - 1) {
            assertEquals(
                "row $i's end must exactly meet row ${i + 1}'s start",
                layout.rows[i + 1].startSec, layout.rows[i].startSec + 5f, 1e-4f
            )
        }
        assertEquals(0f, layout.rows.first().startSec, 1e-4f)
        assertEquals(23f, layout.rows.last().endSec, 1e-4f)
    }

    @Test
    fun `zero or negative duration still produces exactly one row, not zero`() {
        assertEquals(1, PcgStripLayout(durationSecs = 0f, canvasWidthPx = 1000f).rowCount)
        assertEquals(1, PcgStripLayout(durationSecs = -5f, canvasWidthPx = 1000f).rowCount)
    }

    @Test
    fun `pixelsPerSecond is derived from plot width and never a free-floating constant`() {
        val layout = PcgStripLayout(
            durationSecs = 5f, secondsPerRow = 5f, canvasWidthPx = 1024f, marginPx = 24f
        )
        val expectedPlotWidth = 1024f - 2 * 24f
        assertEquals(expectedPlotWidth, layout.plotWidthPx, 1e-4f)
        assertEquals(expectedPlotWidth / 5f, layout.pixelsPerSecond, 1e-4f)
    }

    @Test
    fun `rows never overlap vertically and are ordered top to bottom`() {
        val layout = PcgStripLayout(
            durationSecs = 20f, secondsPerRow = 5f, canvasWidthPx = 1000f,
            rowHeightPx = 100f, rowSpacingPx = 10f
        )
        for (i in 0 until layout.rows.size - 1) {
            assertTrue(
                "row $i must end before row ${i + 1} begins",
                layout.rows[i].bottom <= layout.rows[i + 1].top
            )
        }
    }

    @Test
    fun `totalHeightPx fits every row plus header, footer and margins`() {
        val layout = PcgStripLayout(
            durationSecs = 20f, secondsPerRow = 5f, canvasWidthPx = 1000f,
            rowHeightPx = 100f, rowSpacingPx = 10f, marginPx = 24f,
            headerHeightPx = 60f, footerHeightPx = 30f
        )
        assertTrue(layout.totalHeightPx >= layout.rows.last().bottom + layout.footerHeightPx)
    }

    // ---- rowCountFor ----

    @Test
    fun `rowCountFor matches rowCount for a range of durations`() {
        assertEquals(1, PcgStripLayout.rowCountFor(5f, 5f))
        assertEquals(2, PcgStripLayout.rowCountFor(5.01f, 5f))
        assertEquals(3, PcgStripLayout.rowCountFor(15f, 5f))
        assertEquals(1, PcgStripLayout.rowCountFor(0.5f, 5f))
    }

    // ---- paginate ----

    @Test
    fun `paginate groups every row exactly once, in order`() {
        val pages = PcgStripLayout.paginate(rowCount = 7, rowsPerPage = 3)
        assertEquals(listOf(0..2, 3..5, 6..6), pages)
    }

    @Test
    fun `paginate with rowCount smaller than a page produces a single page`() {
        assertEquals(listOf(0..2), PcgStripLayout.paginate(rowCount = 3, rowsPerPage = 10))
    }

    @Test
    fun `paginate of zero rows produces no pages`() {
        assertTrue(PcgStripLayout.paginate(rowCount = 0, rowsPerPage = 3).isEmpty())
    }

    @Test
    fun `firstRowIndex lets a page-scoped layout keep absolute row times`() {
        // Page 2 of a 5s-per-row strip, rows 3..5 (global indices) -> local row 0 must still
        // report absolute startSec = 15s, not 0s, so sample clipping in the renderer stays correct.
        val pageLayout = PcgStripLayout(
            durationSecs = 30f, secondsPerRow = 5f, canvasWidthPx = 1000f,
            firstRowIndex = 3, rowCountOverride = 3
        )
        assertEquals(3, pageLayout.rows.size)
        assertEquals(15f, pageLayout.rows[0].startSec, 1e-4f)
        assertEquals(20f, pageLayout.rows[1].startSec, 1e-4f)
        assertEquals(25f, pageLayout.rows[2].startSec, 1e-4f)
        // But local (on-page) vertical position still starts at the top of THIS layout.
        assertEquals(pageLayout.marginPx + pageLayout.headerHeightPx, pageLayout.rows[0].top, 1e-4f)
    }
}
