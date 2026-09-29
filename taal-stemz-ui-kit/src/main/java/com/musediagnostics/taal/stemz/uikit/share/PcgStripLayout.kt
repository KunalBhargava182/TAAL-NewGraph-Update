package com.musediagnostics.taal.stemz.uikit.share

import kotlin.math.ceil
import kotlin.math.max

/**
 * Pure Kotlin geometry for the "whole recording as stacked rows" graph export — no Android
 * dependency, plain-JVM unit-testable, same reasoning as [com.musediagnostics.taal.app.ecg.pcgscale.PcgTimeScale]'s
 * own doc comment for keeping tick/geometry math out of any View.
 *
 * The recording is split into fixed-duration rows (row 0 = [0, secondsPerRow), row 1 =
 * [secondsPerRow, 2*secondsPerRow), ...), each rendered at the SAME pixels-per-second as every
 * other row and as the on-screen PcgScale grid's own math (1 large box = 1s, always) — only the
 * number of rows changes with recording length, never the time scale. The last row is a partial
 * row when [durationSecs] isn't an exact multiple of [secondsPerRow]; it is still allocated full
 * row height so every row's grid renders identically, it just has less trace data in it.
 *
 * [PcgWavDecoder] and [PcgGraphStripRenderer] are the Android-facing consumers of this class.
 */
internal data class PcgStripRow(
    val index: Int,
    /** Absolute recording time (seconds) at this row's left edge — feeds directly into
     *  PcgScaleEcgPaperView.scrollOffsetSeconds for this row. */
    val startSec: Float,
    /** Absolute recording time (seconds) at this row's right edge. May be less than
     *  startSec + secondsPerRow for the final, partial row. */
    val endSec: Float,
    /** This row's plot area top, in px, within the full strip canvas. */
    val top: Float,
    /** This row's plot area bottom, in px, within the full strip canvas. */
    val bottom: Float
) {
    val durationSec: Float get() = endSec - startSec
}

internal class PcgStripLayout(
    val durationSecs: Float,
    val secondsPerRow: Float = DEFAULT_SECONDS_PER_ROW,
    val canvasWidthPx: Float,
    val marginPx: Float = DEFAULT_MARGIN_PX,
    val headerHeightPx: Float = DEFAULT_HEADER_HEIGHT_PX,
    val rowHeightPx: Float = DEFAULT_ROW_HEIGHT_PX,
    val rowSpacingPx: Float = DEFAULT_ROW_SPACING_PX,
    val footerHeightPx: Float = DEFAULT_FOOTER_HEIGHT_PX,
    /** Global row index this layout's LOCAL row 0 corresponds to — used by the PDF exporter to
     *  build one layout instance per printed page while keeping every row's [PcgStripRow.startSec]
     *  an absolute recording time (needed to clip the right slice of samples) even though that
     *  row is positioned at the top of its own page, not the top of the whole recording. The PNG
     *  export (one single-page layout) leaves this at its default 0. */
    val firstRowIndex: Int = 0,
    /** Overrides the duration-derived row count — used by the PDF exporter to size a layout to
     *  exactly the rows that belong on one page. Null (the default, used by the PNG export's
     *  single whole-recording layout) derives the count from [durationSecs]/[secondsPerRow]. */
    rowCountOverride: Int? = null
) {
    companion object {
        const val DEFAULT_SECONDS_PER_ROW = 5f
        const val DEFAULT_MARGIN_PX = 24f
        const val DEFAULT_HEADER_HEIGHT_PX = 64f
        const val DEFAULT_ROW_HEIGHT_PX = 220f
        const val DEFAULT_ROW_SPACING_PX = 20f
        const val DEFAULT_FOOTER_HEIGHT_PX = 40f

        /** Rows needed to cover [durationSecs] at [secondsPerRow] each — at least 1, so a
         *  zero/negative/garbage duration still produces a single (empty) row rather than an
         *  unusable empty layout. */
        fun rowCountFor(durationSecs: Float, secondsPerRow: Float): Int {
            if (secondsPerRow <= 0f) return 1
            if (durationSecs <= 0f) return 1
            return max(1, ceil((durationSecs / secondsPerRow).toDouble() - 1e-4).toInt())
        }

        /**
         * Groups row indices [0, rowCount) into PDF pages of at most [rowsPerPage] rows each,
         * in order, no row skipped or duplicated. E.g. rowCount=7, rowsPerPage=3 -> [0..2, 3..5, 6..6].
         */
        fun paginate(rowCount: Int, rowsPerPage: Int): List<IntRange> {
            if (rowCount <= 0) return emptyList()
            val perPage = max(1, rowsPerPage)
            val pages = ArrayList<IntRange>(ceil(rowCount.toDouble() / perPage).toInt())
            var start = 0
            while (start < rowCount) {
                val end = minOf(start + perPage, rowCount) - 1
                pages.add(start..end)
                start += perPage
            }
            return pages
        }
    }

    /** Plot width in px, after left/right margins. */
    val plotWidthPx: Float = max(0f, canvasWidthPx - 2f * marginPx)

    /** Pixels-per-second, derived once from the plot width and the fixed per-row duration — the
     *  single source of horizontal truth for every row, mirroring PcgTimeScale.fromPlotWidth's
     *  own "derived, never a free-floating constant" rule. */
    val pixelsPerSecond: Float = if (secondsPerRow > 0f) plotWidthPx / secondsPerRow else 0f

    val rowCount: Int = rowCountOverride ?: rowCountFor(durationSecs, secondsPerRow)

    /** Each row's absolute time range and vertical plot-area bounds within THIS layout's canvas
     *  (which may be one page of a multi-page PDF, not the whole recording — see
     *  [firstRowIndex]). Rows tile [0, durationSecs) with no gap or overlap when this is the
     *  single whole-recording layout; the final row is clamped to durationSecs rather than
     *  overrunning it. */
    val rows: List<PcgStripRow> = buildList {
        for (localIndex in 0 until rowCount) {
            val globalIndex = firstRowIndex + localIndex
            val start = globalIndex * secondsPerRow
            val end = minOf(start + secondsPerRow, if (durationSecs > 0f) durationSecs else secondsPerRow)
            val top = marginPx + headerHeightPx + localIndex * (rowHeightPx + rowSpacingPx)
            add(PcgStripRow(globalIndex, start, end, top, top + rowHeightPx))
        }
    }

    /** Total canvas height needed to fit the header, every row, and the footer. */
    val totalHeightPx: Float =
        marginPx + headerHeightPx +
            rowCount * rowHeightPx + max(0, rowCount - 1) * rowSpacingPx +
            footerHeightPx + marginPx
}
