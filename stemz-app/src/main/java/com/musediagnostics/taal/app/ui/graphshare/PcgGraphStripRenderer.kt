package com.musediagnostics.taal.app.ui.graphshare

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.DisplayMetrics
import android.view.View
import com.github.mikephil.charting.data.Entry
import com.musediagnostics.taal.app.ecg.pcgscale.PcgScaleEcgPaperView
import com.musediagnostics.taal.app.ecg.pcgscale.PcgScaleWaveformView
import com.musediagnostics.taal.app.ecg.pcgscale.PcgTimeScale

/**
 * Draws the "whole recording as stacked rows" graph strip onto ANY [Canvas] — a Bitmap's for
 * PNG, a PdfDocument page's for PDF — so the two export formats can never visually disagree
 * (both call this same renderer; see [GraphShareExporter]).
 *
 * Reuses the exact on-screen drawing code rather than reimplementing it, same rationale as
 * [com.musediagnostics.taal.app.ui.segmentation.SegmentationPdfExporter]'s header comment:
 * measure/layout an unattached [PcgScaleEcgPaperView] per row and call its own `draw(canvas)` —
 * not a screenshot — so the grid in the export cannot drift from the grid the app actually
 * shows. Only the trace polyline is drawn by hand here, because on screen that is MPAndroidChart's
 * `LineChart`, which only ever renders its visible (4-second) window — see PcgScaleWaveformView's
 * class doc ("NO ZOOM, EVER... the whole recording deliberately never fits on screen").
 *
 * Density is forced to 160dpi via [Context.createConfigurationContext], the same trick
 * SegmentationPdfExporter uses, so the grid's dp-based stroke widths land in true pixels at
 * whatever [PcgStripLayout] resolution was requested, independent of the exporting device's
 * real display density.
 */
class PcgGraphStripRenderer(context: Context) {

    private val renderContext: Context = run {
        val config = Configuration(context.resources.configuration)
        config.densityDpi = DisplayMetrics.DENSITY_DEFAULT
        config.fontScale = 1f
        context.createConfigurationContext(config)
    }

    companion object {
        const val TRACE_COLOR = "#2D7DD2"
        const val TRACE_LINE_WIDTH_PX = 2.5f
        const val BACKGROUND_COLOR = Color.WHITE
        const val HEADER_TITLE_COLOR = Color.BLACK
        const val HEADER_SUBTITLE_COLOR = Color.DKGRAY
        const val FOOTER_COLOR = Color.GRAY
        const val CAPTION = "1 large box = 1 s · 1 small box = 0.2 s"

        // Darker AND thicker than PcgScaleEcgPaperView's on-screen defaults (MINOR_ALPHA=0.20f/
        // MAJOR_ALPHA=0.50f, MINOR_STROKE_DP=0.7f/MAJOR_STROKE_DP=1.2f) — per explicit request,
        // pushed hard rather than incrementally after a first bump (0.55/0.90, 1.4px/2.4px)
        // still wasn't visibly registering as "small boxes" on device. Export-only via
        // setGridAlpha()/setGridStrokeWidthPx(), never applied to the live Recorder/Player/
        // Review grid.
        const val EXPORT_MINOR_GRID_ALPHA = 0.85f
        const val EXPORT_MAJOR_GRID_ALPHA = 1.0f
        const val EXPORT_MINOR_STROKE_PX = 2.2f
        const val EXPORT_MAJOR_STROKE_PX = 3.4f
    }

    /**
     * Renders [layout]'s full strip: header band, every row's grid + trace, and the caption
     * footer. [samples]/[sampleRate] are whichever array the caller decided to plot (raw or
     * PcgDisplayFilter-conditioned — this class has no opinion on Clean Graph state, see
     * [GraphShareBundler]). [fullScale] is the single Y-axis half-range shared by every row,
     * same as the on-screen whole-file scale (PcgAmplitudeScale.computeFullScaleForFile).
     */
    fun render(
        canvas: Canvas,
        layout: PcgStripLayout,
        samples: FloatArray,
        sampleRate: Float,
        fullScale: Float,
        title: String,
        subtitle: String
    ) {
        canvas.drawColor(BACKGROUND_COLOR)
        drawHeader(canvas, layout, title, subtitle)
        for (row in layout.rows) {
            drawRow(canvas, layout, row, samples, sampleRate, fullScale)
        }
        drawFooter(canvas, layout)
    }

    /**
     * BUG FIXED 2026-09-08: title/subtitle used to be drawn at FIXED offsets (marginPx+24 /
     * marginPx+46) regardless of [PcgStripLayout.headerHeightPx]. That happens to fit the PNG
     * export's default 64px header, but the PDF export uses a shorter 40pt header
     * (GraphShareExporter.PDF_HEADER_HEIGHT_PT) — so the subtitle at y=46 landed 6pt INSIDE
     * row 0's area (which starts at headerHeightPx=40) and got erased by that row's own
     * clipped background paint, reading as the subtitle text "overlapping"/vanishing into the
     * first row's grid. Positions are now proportional to the ACTUAL headerHeightPx this
     * layout was given, so they always land inside the header band, on any page size.
     */
    private fun drawHeader(canvas: Canvas, layout: PcgStripLayout, title: String, subtitle: String) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HEADER_TITLE_COLOR
            textSize = 22f
            isFakeBoldText = true
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HEADER_SUBTITLE_COLOR
            textSize = 14f
        }
        val x = layout.marginPx
        // 45%/85% of the header band, not fixed px offsets — see the fix note above. Leaves a
        // deliberate ~15% buffer below the subtitle baseline before row 0 begins.
        val titleY = layout.marginPx + layout.headerHeightPx * 0.45f
        val subtitleY = layout.marginPx + layout.headerHeightPx * 0.85f
        canvas.drawText(title, x, titleY, titlePaint)
        canvas.drawText(subtitle, x, subtitleY, subtitlePaint)
    }

    private fun drawFooter(canvas: Canvas, layout: PcgStripLayout) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FOOTER_COLOR
            textSize = 13f
            textAlign = Paint.Align.CENTER
        }
        val centerX = layout.canvasWidthPx / 2f
        val y = layout.totalHeightPx - layout.marginPx - layout.footerHeightPx / 2f
        canvas.drawText(CAPTION, centerX, y, paint)
    }

    private fun drawRow(
        canvas: Canvas,
        layout: PcgStripLayout,
        row: PcgStripRow,
        samples: FloatArray,
        sampleRate: Float,
        fullScale: Float
    ) {
        val left = layout.marginPx
        val top = row.top
        val width = layout.plotWidthPx.toInt().coerceAtLeast(1)
        val height = layout.rowHeightPx.toInt().coerceAtLeast(1)

        canvas.save()
        canvas.translate(left, top)
        // PcgScaleEcgPaperView.onDraw starts with canvas.drawColor(paperColor) — drawColor
        // fills the canvas's current CLIP, ignoring the translate above (it isn't geometry).
        // Without this clip, each row's white paper background wipes out every row (and the
        // header) drawn before it, leaving only the LAST row visible in the finished image —
        // exactly the bug this fixes.
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        // Grid: the exact on-screen view, unattached, measured/laid out at this row's size and
        // handed this row's absolute-time scroll offset — see class doc.
        val paperView = PcgScaleEcgPaperView(renderContext)
        paperView.timeScale = PcgTimeScale(layout.pixelsPerSecond)
        paperView.scrollOffsetSeconds = row.startSec
        paperView.setGridAlpha(EXPORT_MINOR_GRID_ALPHA, EXPORT_MAJOR_GRID_ALPHA)
        paperView.setGridStrokeWidthPx(EXPORT_MINOR_STROKE_PX, EXPORT_MAJOR_STROKE_PX)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        paperView.measure(widthSpec, heightSpec)
        paperView.layout(0, 0, width, height)
        paperView.draw(canvas)

        // Trace: hand-drawn from the same downsampleMinMax entries the on-screen chart would
        // hold, clipped to this row's [startSec, endSec) and mapped the same way the chart's
        // axisLeft (-fullScale..+fullScale over the row height) would.
        drawTrace(canvas, row, samples, sampleRate, fullScale, width.toFloat(), height.toFloat())

        canvas.restore()
    }

    private fun drawTrace(
        canvas: Canvas,
        row: PcgStripRow,
        samples: FloatArray,
        sampleRate: Float,
        fullScale: Float,
        widthPx: Float,
        heightPx: Float
    ) {
        if (samples.isEmpty() || sampleRate <= 0f || fullScale <= 0f) return
        val startIdx = (row.startSec * sampleRate).toInt().coerceIn(0, samples.size)
        val endIdx = (row.endSec * sampleRate).toInt().coerceIn(startIdx, samples.size)
        if (endIdx <= startIdx) return

        val rowSamples = samples.copyOfRange(startIdx, endIdx)
        // Same ~2 points-per-pixel decimation the on-screen chart uses, so a sharp S1
        // transient can't vanish between two kept samples here either.
        val bucketSize = PcgScaleWaveformView.deriveBucketSizeForVisibleRange(
            rowSamples.size.toFloat(), widthPx
        ).let { if (it > 0) it else 1 }
        val entries: List<Entry> = PcgScaleWaveformView.downsampleMinMax(rowSamples, bucketSize) { idx ->
            idx.toFloat() / sampleRate // seconds relative to this row's own start
        }
        if (entries.isEmpty()) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(TRACE_COLOR)
            strokeWidth = TRACE_LINE_WIDTH_PX
            style = Paint.Style.STROKE
        }
        val centerY = heightPx / 2f
        val pxPerSecond = widthPx / row.durationSec.coerceAtLeast(1e-3f)

        var prevX = Float.NaN
        var prevY = Float.NaN
        for (entry in entries) {
            val x = entry.x * pxPerSecond
            val y = centerY - (entry.y / fullScale) * centerY
            if (!prevX.isNaN()) {
                canvas.drawLine(prevX, prevY, x, y, paint)
            }
            prevX = x
            prevY = y
        }
    }
}
