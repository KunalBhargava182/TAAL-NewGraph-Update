// Builds the PDF report: platform android.graphics.pdf.PdfDocument only, no third-party library.
// The chart is drawn by handing PcgSegmentationView's own draw() the PDF page's Canvas directly —
// not a bitmap screenshot — so the PDF is vector-quality and, since it goes through the exact same
// PcgDisplay/SegmentationPalette code the app screen uses, cannot visually drift from what the app
// shows.
package com.musediagnostics.taal.stemz.uikit.segmentation

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.DisplayMetrics
import android.view.View
import com.musediagnostics.taal.stemz.uikit.segmentation.PcgSegmentationView
import com.purnacardio.signal.pcg.viz.SegmentationPalette
import com.musediagnostics.taal.stemz.segmentation.SegmentationOutcome
import com.musediagnostics.taal.stemz.segmentation.heartRateBpm
import com.musediagnostics.taal.stemz.segmentation.systolicIntervalsMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PAGE_WIDTH_PT = 842 // A4 landscape @ 72dpi
private const val PAGE_HEIGHT_PT = 595
private const val MARGIN_PT = 36f
private const val CHART_HEIGHT_PT = PAGE_HEIGHT_PT * 0.40f

/**
 * Renders the segmentation report as a single-page PDF and writes it to [out]. Runs on
 * [Dispatchers.Default] — a PDF draw of a long recording is not instant, so this must never be
 * called from the main thread.
 */
internal suspend fun writeSegmentationPdf(
    context: Context,
    outcome: SegmentationOutcome,
    audio: FloatArray,
    sampleRate: Int,
    out: OutputStream
) = withContext(Dispatchers.Default) {
    val document = PdfDocument()
    try {
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, 1).create()
        val page = document.startPage(pageInfo)
        drawReportPage(context, page.canvas, outcome, audio, sampleRate)
        document.finishPage(page)
        document.writeTo(out)
    } finally {
        document.close()
    }
}

private fun drawReportPage(
    context: Context,
    canvas: Canvas,
    outcome: SegmentationOutcome,
    audio: FloatArray,
    sampleRate: Int
) {
    canvas.drawColor(Color.WHITE)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 16f
        isFakeBoldText = true
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 10f
    }
    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 8f
    }

    var y = MARGIN_PT + 12f
    canvas.drawText("TAAL Heart Sound Segmentation Report", MARGIN_PT, y, titlePaint)
    y += 22f

    val result = when (outcome) {
        is SegmentationOutcome.Ok -> outcome.result
        is SegmentationOutcome.TooWeak -> outcome.result
        SegmentationOutcome.NoHeartSounds, SegmentationOutcome.Unavailable -> null
    }

    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    val metaLines = buildList {
        add("Generated: $timestamp")
        when {
            result != null -> {
                add("Duration: ${"%.1f".format(result.durationSec)}s")
                add("Cardiac cycles detected: ${result.numCycles}")
                add(
                    "Heart rate: ${
                        result.heartRateBpm?.let { "%.1f bpm".format(it) } ?: "n/a (fewer than 2 beats)"
                    }"
                )
                add(
                    "Avg systolic interval: ${
                        result.systolicIntervalsMs.takeIf { it.isNotEmpty() }
                            ?.let { "%.1f ms".format(it.average()) } ?: "n/a"
                    }"
                )
                if (outcome is SegmentationOutcome.TooWeak) {
                    add("Note: LOW CONFIDENCE — few complete cycles relative to duration")
                }
            }
            outcome == SegmentationOutcome.NoHeartSounds -> add("Result: NO HEART SOUNDS DETECTED")
            outcome == SegmentationOutcome.Unavailable -> add("Result: SEGMENTATION UNAVAILABLE")
        }
    }
    for (line in metaLines) {
        canvas.drawText(line, MARGIN_PT, y, bodyPaint)
        y += 14f
    }

    if (result != null) {
        y += 12f
        val chartWidth = (PAGE_WIDTH_PT - 2 * MARGIN_PT).toInt()
        val chartHeight = CHART_HEIGHT_PT.toInt()

        val chart = buildOffscreenChart(context, chartWidth, chartHeight)
        chart.setRecording(audio, sampleRate, result)

        canvas.save()
        canvas.translate(MARGIN_PT, y)
        chart.draw(canvas)
        canvas.restore()
    }

    canvas.drawText(
        "Research/test output only — not a medical device.",
        MARGIN_PT,
        PAGE_HEIGHT_PT - 16f,
        footerPaint
    )
}

/**
 * A [PcgSegmentationView] instance built purely for this PDF draw — never attached to a window.
 * The Context's density is forced to 1.0 (DENSITY_DEFAULT / 160dpi) so the view's internal `dp()`
 * conversions become a 1:1 mapping onto PDF points; without this, the device's real display
 * density (commonly 2.5–3x) would blow the legend/axis/marker-gutter sizing up several times past
 * what fits in [CHART_HEIGHT_PT].
 */
private fun buildOffscreenChart(context: Context, width: Int, height: Int): PcgSegmentationView {
    val config = Configuration(context.resources.configuration)
    config.densityDpi = DisplayMetrics.DENSITY_DEFAULT
    config.fontScale = 1f
    val pdfContext = context.createConfigurationContext(config)

    val chart = PcgSegmentationView(pdfContext)
    chart.palette = SegmentationPalette.light // "the variant to print" — see its own KDoc

    val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
    val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
    chart.measure(widthSpec, heightSpec)
    chart.layout(0, 0, width, height)
    return chart
}
