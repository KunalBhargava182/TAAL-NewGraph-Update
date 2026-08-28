package com.purnacardio.signal.pcg.android

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.purnacardio.signal.pcg.SegmentationResult
import com.purnacardio.signal.pcg.viz.PcgDisplay
import com.purnacardio.signal.pcg.viz.PcgDisplayModel
import com.purnacardio.signal.pcg.viz.SegmentationPalette

/**
 * Draws a PCG recording with its segmentation: the waveform on state bands, S1 and S2 markers, and
 * a legend.
 *
 * A plain [View], deliberately — it adds no dependency beyond `android.graphics`, works in XML
 * layouts and in Compose (via `AndroidView`), and renders identically in both. All geometry comes
 * from [PcgDisplay], so what you see here is what an exported SVG or a PDF would show.
 *
 * ## Use
 *
 * ```kotlin
 * val chart = PcgSegmentationView(context)
 * chart.setRecording(audio, sampleRate = 44_100, result = segmentationResult)
 * ```
 *
 * or from XML:
 *
 * ```xml
 * <com.purnacardio.signal.pcg.android.PcgSegmentationView
 *     android:id="@+id/pcgChart"
 *     android:layout_width="match_parent"
 *     android:layout_height="200dp" />
 * ```
 *
 * ## Long recordings
 *
 * [setRecording] walks the audio once to build the display model. That is a few milliseconds for a
 * 20 s capture and is fine on the main thread. For a multi-minute recording, or if you are
 * rebuilding on every frame of a zoom gesture, build the model off the main thread and hand it over
 * with [setDisplayModel] instead.
 *
 * ## Windowing
 *
 * [setWindowSeconds] narrows the view to part of the recording, which is how you would drive a
 * zoom or a scrubber. The chart always redraws from the original audio, so zooming in reveals real
 * detail rather than stretching pixels.
 */
class PcgSegmentationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** Height of the legend strip. */
        const val LEGEND_DP = 30f
        /** Height of the marker gutter under the trace. */
        const val MARKER_DP = 9f
        /** Height of the time-axis tick-label row, under the marker gutter. */
        const val AXIS_DP = 14f
        const val LEGEND_TEXT_SP = 12f
        const val SWATCH_DP = 14f
        /** A band narrower than this is clamped to this width so it never disappears. */
        const val MIN_BAND_WIDTH_DP = 1.5f
        /** Minimum on-screen gap between adjacent time-axis tick labels. */
        const val MIN_TICK_SPACING_DP = 30f
        /** Candidate time-axis tick steps, seconds — smallest that clears [MIN_TICK_SPACING_DP] wins. */
        val TICK_STEP_CANDIDATES = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0)
    }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density

    /**
     * Colours. Defaults to the variant matching the device's light/dark setting; assign a specific
     * one if your app does not follow the system theme.
     */
    var palette: SegmentationPalette.Variant = defaultPalette(context)
        set(value) { field = value; invalidate() }

    /** Whether to draw the legend strip. Turn it off only if you draw your own key elsewhere. */
    var showLegend: Boolean = true
        set(value) { field = value; rebuild(); invalidate() }

    /** Whether to draw second gridlines and the time-axis tick labels below the lane. */
    var showSecondGrid: Boolean = true
        set(value) { field = value; rebuild(); invalidate() }

    private var audio: FloatArray? = null
    private var sampleRate: Int = 0
    private var result: SegmentationResult? = null
    private var windowStart = 0.0
    private var windowEnd = Double.MAX_VALUE

    private var model: PcgDisplayModel? = null

    // Paints are allocated once. onDraw runs on every frame of a scroll and must not allocate.
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // FILL_AND_STROKE with a hairline (strokeWidth 0 — exactly one physical pixel regardless of
    // scale) keeps the envelope's translucent fill (band colour reads through it) while adding a
    // crisp 1px edge, so the trace stays legible as a line rather than smearing into a block at
    // high cycle density.
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 0f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LEGEND_TEXT_SP * scaledDensity
    }
    private val wavePath = Path()
    private val markerPath = Path()

    /**
     * Supply a recording and its segmentation. Safe to call repeatedly.
     *
     * @param audio mono samples; amplitude scale is irrelevant.
     * @param sampleRate the rate [audio] was captured at.
     * @param result the segmentation, or null to clear the chart.
     */
    fun setRecording(audio: FloatArray, sampleRate: Int, result: SegmentationResult?) {
        this.audio = audio
        this.sampleRate = sampleRate
        this.result = result
        rebuild()
        invalidate()
    }

    /** Hand over a model built elsewhere — see the note on long recordings. */
    fun setDisplayModel(model: PcgDisplayModel?) {
        this.model = model
        this.audio = null
        this.result = null
        invalidate()
    }

    /** Narrow the view to part of the recording. Pass no arguments to show all of it. */
    fun setWindowSeconds(startSec: Double = 0.0, endSec: Double = Double.MAX_VALUE) {
        windowStart = startSec
        windowEnd = endSec
        rebuild()
        invalidate()
    }

    /** The model currently drawn, if any — useful for reading `clippedFraction`. */
    fun displayModel(): PcgDisplayModel? = model

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = defaultPalette(context)
    }

    private fun laneHeight(): Float {
        val legend = if (showLegend) dp(LEGEND_DP) else 0f
        val axis = if (showSecondGrid) dp(AXIS_DP) else 0f
        return (height - legend - dp(MARKER_DP) - axis).coerceAtLeast(1f)
    }

    private fun rebuild() {
        val a = audio
        val r = result
        val w = width
        if (a == null || r == null || w <= 0) return
        model = PcgDisplay.build(a, sampleRate, r, columns = w, windowStartSec = windowStart,
            windowEndSec = windowEnd)
        rebuildWavePath()
    }

    /**
     * The trace as one closed path: across the upper edge, back along the lower.
     *
     * Built here rather than in onDraw because it is the expensive part — one point per pixel
     * column — and it only changes when the data or the size does.
     */
    private fun rebuildWavePath() {
        val m = model ?: return
        val lane = laneHeight()
        wavePath.reset()
        if (m.columns <= 0) return
        // moveTo on the first point: lineTo into an empty Path implies a start at (0,0) and would
        // drag a stray edge in from the corner.
        wavePath.moveTo(0f, m.top[0] * lane)
        for (c in 1 until m.columns) wavePath.lineTo(c.toFloat(), m.top[c] * lane)
        for (c in m.columns - 1 downTo 0) wavePath.lineTo(c.toFloat(), m.bottom[c] * lane)
        wavePath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = model
        val w = width.toFloat()
        val lane = laneHeight()

        canvas.drawColor(palette.surface)

        if (m == null || m.isEmpty) {
            textPaint.color = palette.onSurfaceMuted
            canvas.drawText("No segmentation", dp(8f), height / 2f, textPaint)
            return
        }

        // 1. state bands, behind everything. Width is clamped to a minimum so a band shorter
        // than ~1.5px (common at 20+ cycles across a phone-width chart) still paints rather than
        // vanishing — only the width is clamped, the true start position (xStart) is never moved.
        for (b in m.bands) {
            bandPaint.color = palette.fillFor(b.state)
            drawClampedRect(canvas, b.xStart * w, b.xEnd * w, 0f, lane, bandPaint)
        }
        // 1b. an accent rule along the top of the two heart sounds. Shape, not just hue, is what
        // keeps S1 and S2 apart in greyscale, on a projector, or for a colour-deficient reader.
        // Same width clamp as the band fill, so the rule survives at the same narrow widths.
        val rule = dp(2f)
        for (b in m.bands) {
            if (!SegmentationPalette.isSound.getOrElse(b.state) { false }) continue
            bandPaint.color = palette.accentFor(b.state)
            drawClampedRect(canvas, b.xStart * w, b.xEnd * w, 0f, rule, bandPaint)
        }

        // 2. one-second gridlines, so the reader can judge rate without a ruler
        if (showSecondGrid && m.durationSec > 0) {
            linePaint.color = palette.grid
            var t = kotlin.math.ceil(m.startSec)
            while (t < m.endSec) {
                val x = ((t - m.startSec) / m.durationSec).toFloat() * w
                canvas.drawLine(x, 0f, x, lane, linePaint)
                t += 1.0
            }
        }

        // 3. baseline — the faint zero-amplitude centre line
        linePaint.color = palette.baseline
        canvas.drawLine(0f, lane / 2f, w, lane / 2f, linePaint)

        // 4. the trace
        wavePaint.color = palette.waveform
        canvas.drawPath(wavePath, wavePaint)

        // 5. S1 and S2 peak markers in the gutter below the lane
        drawMarkers(canvas, m.s1MarkersX, palette.accentFor(0), lane, w)
        drawMarkers(canvas, m.s2MarkersX, palette.accentFor(2), lane, w)

        // 6. time axis — without it the chart has no scale. Tick spacing adapts to duration so
        // labels never collide (1s ticks when zoomed in, wider steps across a whole recording).
        if (showSecondGrid) drawTimeAxis(canvas, lane + dp(MARKER_DP), w, m)

        if (showLegend) drawLegend(canvas, lane + dp(MARKER_DP) + (if (showSecondGrid) dp(AXIS_DP) else 0f))
    }

    /** Draws `[left, right)` at `[top, bottom)`, widening `right` only if narrower than the visible minimum. */
    private fun drawClampedRect(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, paint: Paint) {
        val minWidth = dp(MIN_BAND_WIDTH_DP)
        val clampedRight = if (right - left < minWidth) left + minWidth else right
        canvas.drawRect(left, top, clampedRight, bottom, paint)
    }

    /**
     * Second ticks with labels along the bottom of the lane. Step widens (1s / 2s / 5s / ...)
     * until consecutive labels are guaranteed not to crowd, based on the window currently shown.
     */
    private fun drawTimeAxis(canvas: Canvas, top: Float, w: Float, m: PcgDisplayModel) {
        if (m.durationSec <= 0 || w <= 0f) return
        val step = pickTickStepSeconds(m.durationSec, w)
        textPaint.color = palette.onSurfaceMuted
        val baselineY = top + dp(AXIS_DP) * 0.78f
        var t = kotlin.math.ceil(m.startSec / step) * step
        while (t < m.endSec) {
            val x = ((t - m.startSec) / m.durationSec).toFloat() * w
            val label = "${Math.round(t)}s"
            val labelWidth = textPaint.measureText(label)
            val drawX = (x - labelWidth / 2f).coerceIn(0f, (w - labelWidth).coerceAtLeast(0f))
            canvas.drawText(label, drawX, baselineY, textPaint)
            t += step
        }
    }

    /** Smallest step (seconds) from a fixed candidate list whose on-screen spacing clears the minimum. */
    private fun pickTickStepSeconds(durationSec: Double, widthPx: Float): Double {
        val minSpacingPx = dp(MIN_TICK_SPACING_DP)
        for (step in TICK_STEP_CANDIDATES) {
            if ((step / durationSec) * widthPx >= minSpacingPx) return step
        }
        return TICK_STEP_CANDIDATES.last()
    }

    private fun drawMarkers(canvas: Canvas, xs: FloatArray, color: Int, lane: Float, w: Float) {
        if (xs.isEmpty()) return
        markerPaint.color = color
        val h = dp(MARKER_DP) * 0.8f
        val halfW = dp(3.2f)
        for (x0 in xs) {
            val x = x0 * w
            markerPath.reset()
            markerPath.moveTo(x, lane + 1f)
            markerPath.lineTo(x - halfW, lane + 1f + h)
            markerPath.lineTo(x + halfW, lane + 1f + h)
            markerPath.close()
            canvas.drawPath(markerPath, markerPaint)
        }
    }

    /**
     * A compact key in cardiac cycle order.
     *
     * Order is not cosmetic: S1 → systole → S2 → diastole is the order the bands appear on screen,
     * so the legend can be read against the chart without hunting.
     */
    private fun drawLegend(canvas: Canvas, top: Float) {
        val entries = SegmentationPalette.legend(palette)
        val sw = dp(SWATCH_DP)
        val gap = dp(8f)
        val itemGap = dp(16f)
        val cy = top + dp(LEGEND_DP) / 2f
        textPaint.color = palette.onSurfaceMuted

        var x = 0f
        for (e in entries) {
            // swatch: the band wash over the chart surface, with its accent as a hairline, so the
            // key matches the fill even where the wash alone would be too faint to identify
            bandPaint.color = palette.surface
            canvas.drawRect(x, cy - sw / 2f, x + sw, cy + sw / 2f, bandPaint)
            bandPaint.color = e.swatch
            canvas.drawRect(x, cy - sw / 2f, x + sw, cy + sw / 2f, bandPaint)
            linePaint.color = e.accent
            canvas.drawLine(x, cy + sw / 2f, x + sw, cy + sw / 2f, linePaint)

            x += sw + gap
            val tw = textPaint.measureText(e.label)
            canvas.drawText(e.label, x, cy + textPaint.textSize / 3f, textPaint)
            x += tw + itemGap
            if (x > width) return   // narrow view: draw what fits rather than overflowing
        }
    }

    override fun getContentDescription(): CharSequence {
        val m = model ?: return "Phonocardiogram, no segmentation available"
        val cycles = m.s1MarkersX.size
        return "Phonocardiogram with cardiac segmentation, " +
            "${"%.1f".format(m.durationSec)} seconds, $cycles heart cycles shown"
    }
}

/** Light or dark palette, following the device's current configuration. */
private fun defaultPalette(context: Context): SegmentationPalette.Variant {
    val night = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    return if (night) SegmentationPalette.dark else SegmentationPalette.light
}
