package com.musediagnostics.taal.app.ecg.pcgscale

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * Fork of [com.musediagnostics.taal.app.ecg.calibrated.CalibratedEcgPaperView] (protected,
 * not modified) for the PcgScale screens. Differences from the original, all deliberate:
 *
 *  - TIME-defined grid, not mm-defined: squares come from [PcgTimeScale] (large box = 1s,
 *    small box = 0.2s), so there is no DPI/mm calibration, no paper speed, no gain, no
 *    calibration pulse, and no XML attrs — this view is only ever constructed
 *    programmatically by [PcgScaleWaveformView].
 *  - The grid SCROLLS with the trace: [scrollOffsetSeconds] is the absolute recording time at
 *    the plot's left edge (kept in lockstep with the chart by PcgScaleWaveformView), and
 *    vertical lines + labels are positioned from it. This is the opposite of the Calibrated
 *    design (static grid, trace moves over it) because here a grid line must MEAN a specific
 *    second of the recording — the change this screen family exists for.
 *  - Whole-second ticks carry a printed time label along the bottom edge — no screen in the
 *    app had labeled time before this (every other chart has setDrawLabels(false)).
 *  - Drawn directly in [onDraw], no cached bitmap: the Calibrated view caches because its
 *    1mm grid is hundreds of lines and static; this grid is a few dozen lines and changes
 *    every scroll frame, so a cache would be rebuilt per frame anyway.
 *
 * Grid only — never a trace; the MPAndroidChart LineChart sits on top of it inside
 * PcgScaleWaveformView, exactly like the Calibrated stack-up.
 *
 * Vertical (amplitude) rows use the same pixel pitch as the time squares so grid cells render
 * square; rows carry no unit meaning (the Y axis is relative amplitude, auto-scaled to 60%
 * fill by [PcgAmplitudeScale]) and are centered on the trace's zero line (view mid-height).
 */
class PcgScaleEcgPaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // Same classic paper palette + alpha split as the Calibrated PAPER theme (Fix C
        // heritage: minors nearly invisible, majors read as ink).
        private const val PAPER_BG = "#FFFFFF"
        private const val PAPER_MINOR = "#F2B8B5"
        private const val PAPER_MAJOR = "#E0837F"
        private const val MINOR_ALPHA = 0.20f
        private const val MAJOR_ALPHA = 0.50f
        private const val LABEL_COLOR = "#999999"

        private const val MINOR_STROKE_DP = 0.7f
        private const val MAJOR_STROKE_DP = 1.2f
        private const val LABEL_TEXT_SP = 10f
        private const val LABEL_BOTTOM_MARGIN_DP = 2f

        /** "7s" under a minute, "1:07" from there up — package-level so it's plain-JVM testable. */
        fun formatTickLabel(wholeSeconds: Int): String =
            if (wholeSeconds < 60) "${wholeSeconds}s"
            else String.format("%d:%02d", wholeSeconds / 60, wholeSeconds % 60)
    }

    /**
     * The single source of horizontal truth, owned and injected by PcgScaleWaveformView after
     * layout. Null until then — the view draws plain paper with no grid rather than guessing.
     */
    var timeScale: PcgTimeScale? = null
        set(value) { field = value; invalidate() }

    /** Absolute recording time (seconds) at the plot's left edge. Set by PcgScaleWaveformView. */
    var scrollOffsetSeconds: Float = 0f
        set(value) { field = value.coerceAtLeast(0f); invalidate() }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val paperColor = Color.parseColor(PAPER_BG)

    private val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(PAPER_MINOR)
        alpha = (MINOR_ALPHA * 255f).roundToInt()
        strokeWidth = MINOR_STROKE_DP * density
        style = Paint.Style.STROKE
    }
    private val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(PAPER_MAJOR)
        alpha = (MAJOR_ALPHA * 255f).roundToInt()
        strokeWidth = MAJOR_STROKE_DP * density
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(LABEL_COLOR)
        textSize = LABEL_TEXT_SP * scaledDensity
        textAlign = Paint.Align.CENTER
    }

    /**
     * Overrides the minor/major gridline opacity (0f..1f) on top of [MINOR_ALPHA]/[MAJOR_ALPHA].
     * Never called by the on-screen Recorder/Player/Review screens, which keep exactly today's
     * look — this exists solely for [com.musediagnostics.taal.app.ui.graphshare.PcgGraphStripRenderer],
     * whose static PNG/PDF export wants a darker grid than the live in-app trace, per explicit
     * request (this is deliberately a method, not a property with a custom setter, so there is
     * no ambiguity about whether the on-screen default ever runs through it).
     */
    fun setGridAlpha(minorAlpha: Float, majorAlpha: Float) {
        minorPaint.alpha = (minorAlpha * 255f).roundToInt()
        majorPaint.alpha = (majorAlpha * 255f).roundToInt()
        invalidate()
    }

    /**
     * Overrides the minor/major gridline stroke width, in raw px (not dp — the export renders
     * into a fixed-px offscreen canvas, not a device-density surface, so there's no density to
     * scale from). Same export-only contract as [setGridAlpha]: on-screen screens never call
     * this and keep [MINOR_STROKE_DP]/[MAJOR_STROKE_DP] exactly as before. A low-alpha 0.7px
     * hairline (the on-screen default, live at typical device density) can read as effectively
     * invisible on a large static PNG/PDF, which alpha alone doesn't fix — width has to move too.
     */
    fun setGridStrokeWidthPx(minorPx: Float, majorPx: Float) {
        minorPaint.strokeWidth = minorPx
        majorPaint.strokeWidth = majorPx
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(paperColor)

        val scale = timeScale ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawAmplitudeRows(canvas, scale, w, h)
        drawTimeColumnsAndLabels(canvas, scale, w, h)
    }

    /** Static horizontal rows, centered on the zero line at mid-height, square with the time pitch. */
    private fun drawAmplitudeRows(canvas: Canvas, scale: PcgTimeScale, w: Float, h: Float) {
        val small = scale.smallSquarePx()
        if (small <= 1f) return
        val centerY = h / 2f
        var i = 0
        while (true) {
            val offset = i * small
            if (offset > centerY && offset > h - centerY) break
            val paint = if (i % PcgTimeScale.SMALL_SQUARES_PER_LARGE_SQUARE == 0) majorPaint else minorPaint
            // Snap to whole pixels — a hairline between two pixels antialiases soft/fat (Fix C heritage).
            val yUp = (centerY - offset).roundToInt().toFloat()
            val yDown = (centerY + offset).roundToInt().toFloat()
            if (yUp >= 0f) canvas.drawLine(0f, yUp, w, yUp, paint)
            if (i > 0 && yDown <= h) canvas.drawLine(0f, yDown, w, yDown, paint)
            i++
        }
    }

    /**
     * Scrolling vertical lines: every tick is a fixed absolute time (integer small-square
     * index, per [PcgTimeScale] — indices, not accumulated floats, so a long recording never
     * drifts). Whole-second ticks are heavier and labeled.
     */
    private fun drawTimeColumnsAndLabels(canvas: Canvas, scale: PcgTimeScale, w: Float, h: Float) {
        val labelBaselineY = h - LABEL_BOTTOM_MARGIN_DP * density
        var index = scale.firstTickIndexVisible(scrollOffsetSeconds)
        while (true) {
            val x = scale.tickPositionPx(index, scrollOffsetSeconds)
            if (x > w) break
            val snappedX = x.roundToInt().toFloat()
            val major = scale.isMajorTick(index)
            canvas.drawLine(snappedX, 0f, snappedX, h, if (major) majorPaint else minorPaint)
            if (major) {
                val wholeSeconds = (scale.tickTimeSeconds(index) + 0.5f).toInt()
                canvas.drawText(formatTickLabel(wholeSeconds), snappedX, labelBaselineY, labelPaint)
            }
            index++
        }
    }
}
