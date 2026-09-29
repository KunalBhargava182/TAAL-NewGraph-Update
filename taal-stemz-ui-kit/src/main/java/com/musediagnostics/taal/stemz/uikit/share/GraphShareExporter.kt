package com.musediagnostics.taal.stemz.uikit.share

import android.content.Context
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * PDF export adapter over [PcgGraphStripRenderer] — the graph share bundle is wav + pdf only
 * (no PNG, per explicit request; the PNG path this object used to also offer was removed along
 * with its only caller, GraphShareBundler). Runs on [Dispatchers.Default]: rendering a 300s
 * recording's full strip is CPU/graphics work, never called from the main thread, same posture
 * as [com.musediagnostics.taal.app.ui.segmentation.writeSegmentationPdf].
 */
internal object GraphShareExporter {

    // A4 landscape @ 72dpi, matching SegmentationPdfExporter's page choice.
    private const val PDF_PAGE_WIDTH_PT = 842
    private const val PDF_PAGE_HEIGHT_PT = 595
    private const val PDF_MARGIN_PT = 24f
    // Was 40f; PcgGraphStripRenderer.drawHeader now positions text proportionally within
    // whatever this is, but a little extra room keeps the subtitle's descent comfortably clear
    // of row 0 rather than right at the edge of it.
    private const val PDF_HEADER_HEIGHT_PT = 46f
    private const val PDF_ROW_SPACING_PT = 10f
    private const val PDF_FOOTER_HEIGHT_PT = 20f

    suspend fun writePdf(
        context: Context,
        samples: FloatArray,
        sampleRate: Float,
        fullScale: Float,
        durationSecs: Float,
        title: String,
        subtitle: String,
        out: OutputStream
    ) = withContext(Dispatchers.Default) {
        val renderer = PcgGraphStripRenderer(context)
        val document = PdfDocument()
        try {
            val plotWidthPt = PDF_PAGE_WIDTH_PT - 2 * PDF_MARGIN_PT
            val secondsPerRow = PcgStripLayout.DEFAULT_SECONDS_PER_ROW
            val usableHeightPt = PDF_PAGE_HEIGHT_PT - 2 * PDF_MARGIN_PT - PDF_HEADER_HEIGHT_PT - PDF_FOOTER_HEIGHT_PT
            val rowHeightPt = min(PcgStripLayout.DEFAULT_ROW_HEIGHT_PX, 140f)
            val rowsPerPage = max(1, ((usableHeightPt + PDF_ROW_SPACING_PT) / (rowHeightPt + PDF_ROW_SPACING_PT)).toInt())

            val totalRowCount = PcgStripLayout.rowCountFor(durationSecs, secondsPerRow)
            val pages = PcgStripLayout.paginate(totalRowCount, rowsPerPage)

            for ((pageIndex, rowRange) in pages.withIndex()) {
                val pageLayout = PcgStripLayout(
                    durationSecs = durationSecs,
                    secondsPerRow = secondsPerRow,
                    canvasWidthPx = plotWidthPt + 2 * PDF_MARGIN_PT,
                    marginPx = PDF_MARGIN_PT,
                    headerHeightPx = PDF_HEADER_HEIGHT_PT,
                    rowHeightPx = rowHeightPt,
                    rowSpacingPx = PDF_ROW_SPACING_PT,
                    footerHeightPx = PDF_FOOTER_HEIGHT_PT,
                    firstRowIndex = rowRange.first,
                    rowCountOverride = rowRange.last - rowRange.first + 1
                )
                val pageInfo = PdfDocument.PageInfo.Builder(
                    PDF_PAGE_WIDTH_PT, PDF_PAGE_HEIGHT_PT, pageIndex + 1
                ).create()
                val page = document.startPage(pageInfo)
                val pageTitle = if (pages.size > 1) "$title (page ${pageIndex + 1}/${pages.size})" else title
                renderer.render(page.canvas, pageLayout, samples, sampleRate, fullScale, pageTitle, subtitle)
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }
}
