package com.musediagnostics.taal.app.ecg.calibrated

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.musediagnostics.taal.app.R
import kotlin.math.roundToInt

enum class CalibratedEcgTheme { PAPER, MONITOR }

/**
 * Fork of [com.musediagnostics.taal.app.ecg.EcgPaperView] (protected, not modified) for the
 * calibrated recorder/player screens. Draws a millimetre-accurate minor/major grid (1mm/5mm
 * squares), with optional 3-second tick marks and a calibration pulse, cached to a Bitmap and
 * blitted in [onDraw]. Grid only — never a trace; a chart sits on top of it inside
 * CalibratedWaveformView.
 *
 * All unit math lives in [CalibratedMmScale] so it can be reused by a trace renderer.
 */
class CalibratedEcgPaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MM_PER_INCH = 25.4f
        private const val LARGE_SQUARES_PER_TIME_TICK = 15
        private const val CALIBRATION_LEAD_IN_SMALL_SQUARES = 1
        private const val CALIBRATION_WIDTH_SMALL_SQUARES = 5
        private const val CALIBRATION_LEAD_OUT_SMALL_SQUARES = 1
        private const val TICK_LENGTH_MM = 4f

        // Classic paper theme (exact values as specified)
        private const val PAPER_BG = "#FFF8F5"
        private const val PAPER_MINOR = "#F2B8B5"
        private const val PAPER_MAJOR = "#E0837F"
        private const val PAPER_MINOR_STROKE_MM = 0.1f
        private const val PAPER_MAJOR_STROKE_MM = 0.3f

        // Monitor theme: near-black paper, dim green grid
        private const val MONITOR_BG = "#05100A"
        private const val MONITOR_MINOR = "#123D22"
        private const val MONITOR_MAJOR = "#1F7A45"
        private const val MONITOR_MINOR_STROKE_MM = 0.1f
        private const val MONITOR_MAJOR_STROKE_MM = 0.3f
    }

    private var pxPerMmXOverride: Float? = null
    private var pxPerMmYOverride: Float? = null

    private val mmScale: CalibratedMmScale = CalibratedMmScale(
        pxPerMmX = computeDefaultPxPerMmX(),
        pxPerMmY = computeDefaultPxPerMmY()
    )

    var theme: CalibratedEcgTheme = CalibratedEcgTheme.PAPER
        set(value) { field = value; applyThemeDefaults(); rebuildGridIfPossible() }

    var showCalibrationPulse: Boolean = true
        set(value) { field = value; rebuildGridIfPossible() }

    var showTimeTicks: Boolean = true
        set(value) { field = value; rebuildGridIfPossible() }

    private var paperColor: Int = Color.parseColor(PAPER_BG)
    private var minorGridColor: Int = Color.parseColor(PAPER_MINOR)
    private var majorGridColor: Int = Color.parseColor(PAPER_MAJOR)
    private var minorStrokeMm: Float = PAPER_MINOR_STROKE_MM
    private var majorStrokeMm: Float = PAPER_MAJOR_STROKE_MM

    // Fix C — Kardia-style grid. Measured Kardia's own grid at Δ96/255 per-line contrast but
    // only 0.7% total ink coverage of the plot area, vs. this view's earlier 7.6% at a more
    // uniform fade. The finding is counter-intuitive: Kardia's lines are individually *higher*
    // contrast, not lower — its minors are nearly invisible and only the 5mm majors read as
    // ink, so the aggregate ink area stays tiny. A uniform alpha reduction can't reproduce
    // that; the minor/major split has to be aggressive. Applied to the minor/major PAINTS
    // only (never to paperColor or View.alpha — those would let whatever sits behind this
    // view bleed through and turn the paper fill grey).
    var minorGridAlpha: Float = 0.20f
        set(value) { field = value.coerceIn(0f, 1f); rebuildGridIfPossible() }
    var majorGridAlpha: Float = 0.50f
        set(value) { field = value.coerceIn(0f, 1f); rebuildGridIfPossible() }

    private var explicitPaperColor: Int? = null
    private var explicitMinorColor: Int? = null
    private var explicitMajorColor: Int? = null

    private var gridBitmap: Bitmap? = null
    private var gridDirty = true

    init {
        attrs?.let { readAttrs(it, defStyleAttr) } ?: applyThemeDefaults()
    }

    private fun readAttrs(attrs: AttributeSet, defStyleAttr: Int) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.CalibratedEcgPaperView, defStyleAttr, 0)
        try {
            theme = when (ta.getInt(R.styleable.CalibratedEcgPaperView_calEcgTheme, 0)) {
                1 -> CalibratedEcgTheme.MONITOR
                else -> CalibratedEcgTheme.PAPER
            }
            mmScale.paperSpeed = when (ta.getInt(R.styleable.CalibratedEcgPaperView_calEcgPaperSpeed, 1)) {
                0 -> CalibratedPaperSpeed.SPEED_12_5
                2 -> CalibratedPaperSpeed.SPEED_50
                else -> CalibratedPaperSpeed.SPEED_25
            }
            mmScale.gain = when (ta.getInt(R.styleable.CalibratedEcgPaperView_calEcgGain, 1)) {
                0 -> CalibratedGain.GAIN_5
                2 -> CalibratedGain.GAIN_20
                else -> CalibratedGain.GAIN_10
            }
            showCalibrationPulse = ta.getBoolean(R.styleable.CalibratedEcgPaperView_calEcgShowCalibrationPulse, true)
            showTimeTicks = ta.getBoolean(R.styleable.CalibratedEcgPaperView_calEcgShowTimeTicks, true)
            minorGridAlpha = ta.getFloat(R.styleable.CalibratedEcgPaperView_calEcgGridAlphaMinor, minorGridAlpha)
            majorGridAlpha = ta.getFloat(R.styleable.CalibratedEcgPaperView_calEcgGridAlphaMajor, majorGridAlpha)

            applyThemeDefaults()

            if (ta.hasValue(R.styleable.CalibratedEcgPaperView_calEcgPaperColor)) {
                explicitPaperColor = ta.getColor(R.styleable.CalibratedEcgPaperView_calEcgPaperColor, paperColor)
            }
            if (ta.hasValue(R.styleable.CalibratedEcgPaperView_calEcgMinorGridColor)) {
                explicitMinorColor = ta.getColor(R.styleable.CalibratedEcgPaperView_calEcgMinorGridColor, minorGridColor)
            }
            if (ta.hasValue(R.styleable.CalibratedEcgPaperView_calEcgMajorGridColor)) {
                explicitMajorColor = ta.getColor(R.styleable.CalibratedEcgPaperView_calEcgMajorGridColor, majorGridColor)
            }
            applyColorOverrides()
        } finally {
            ta.recycle()
        }
    }

    private fun applyThemeDefaults() {
        when (theme) {
            CalibratedEcgTheme.PAPER -> {
                paperColor = Color.parseColor(PAPER_BG)
                minorGridColor = Color.parseColor(PAPER_MINOR)
                majorGridColor = Color.parseColor(PAPER_MAJOR)
                minorStrokeMm = PAPER_MINOR_STROKE_MM
                majorStrokeMm = PAPER_MAJOR_STROKE_MM
            }
            CalibratedEcgTheme.MONITOR -> {
                paperColor = Color.parseColor(MONITOR_BG)
                minorGridColor = Color.parseColor(MONITOR_MINOR)
                majorGridColor = Color.parseColor(MONITOR_MAJOR)
                minorStrokeMm = MONITOR_MINOR_STROKE_MM
                majorStrokeMm = MONITOR_MAJOR_STROKE_MM
            }
        }
        applyColorOverrides()
    }

    private fun applyColorOverrides() {
        explicitPaperColor?.let { paperColor = it }
        explicitMinorColor?.let { minorGridColor = it }
        explicitMajorColor?.let { majorGridColor = it }
    }

    /** Overrides OEM-reported DPI, since it's often wrong. Pass physical pixels-per-mm. */
    fun setPixelsPerMm(x: Float, y: Float) {
        pxPerMmXOverride = x
        pxPerMmYOverride = y
        mmScale.pxPerMmX = x
        mmScale.pxPerMmY = y
        rebuildGridIfPossible()
    }

    fun setPaperSpeed(speed: CalibratedPaperSpeed) {
        mmScale.paperSpeed = speed
        rebuildGridIfPossible()
    }

    fun setGain(gain: CalibratedGain) {
        mmScale.gain = gain
        rebuildGridIfPossible()
    }

    /** Read-only access to the current scale, e.g. for a trace renderer or window-derivation. */
    fun currentScale(): CalibratedMmScale = mmScale

    private fun computeDefaultPxPerMmX(): Float {
        val metrics = resources.displayMetrics
        // Layout Editor preview devices often report xdpi/ydpi as 0 — fall back
        // rather than let the grid come out blank/NaN in isInEditMode().
        if (isInEditMode && metrics.xdpi <= 0f) return fallbackPxPerMm(metrics)
        val value = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1f, metrics)
        return if (value > 0f && !value.isNaN()) value else fallbackPxPerMm(metrics)
    }

    private fun computeDefaultPxPerMmY(): Float {
        val metrics = resources.displayMetrics
        // TypedValue.applyDimension always keys off xdpi internally, even for
        // "vertical" style units — so the Y axis is computed from ydpi directly.
        if (isInEditMode && metrics.ydpi <= 0f) return fallbackPxPerMm(metrics)
        val value = metrics.ydpi / MM_PER_INCH
        return if (value > 0f && !value.isNaN()) value else fallbackPxPerMm(metrics)
    }

    private fun fallbackPxPerMm(metrics: android.util.DisplayMetrics): Float {
        // isInEditMode()/some emulators report xdpi/ydpi as 0. Approximate from
        // density (density * 160 ~= dpi) so the Layout Editor preview still renders.
        val approxDpi = metrics.density * 160f
        return if (approxDpi > 0f) approxDpi / MM_PER_INCH else 4f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gridDirty = true
        rebuildGridIfPossible()
    }

    private fun rebuildGridIfPossible() {
        if (width <= 0 || height <= 0) {
            gridDirty = true
            return
        }
        gridBitmap = buildGridBitmap(width, height)
        gridDirty = false
        invalidate()
    }

    private fun buildGridBitmap(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(paperColor)

        val pxPerMmX = mmScale.pxPerMmX
        val pxPerMmY = mmScale.pxPerMmY
        if (pxPerMmX <= 0f || pxPerMmY <= 0f) return bitmap

        val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = minorGridColor
            alpha = (minorGridAlpha * 255f).roundToInt().coerceIn(0, 255)
            strokeWidth = minorStrokeMm * pxPerMmX
            style = Paint.Style.STROKE
        }
        val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = majorGridColor
            alpha = (majorGridAlpha * 255f).roundToInt().coerceIn(0, 255)
            strokeWidth = majorStrokeMm * pxPerMmX
            style = Paint.Style.STROKE
        }

        val widthF = w.toFloat()
        val heightF = h.toFloat()
        val colCount = (widthF / pxPerMmX).toInt() + 1
        val rowCount = (heightF / pxPerMmY).toInt() + 1

        // Minor grid first (every 1mm line), major grid drawn on top at every
        // 5th line with a heavier stroke — origin + index * pxPerMm kept in
        // float throughout so no rounding error accumulates across lines.
        canvas.drawLines(verticalLines(colCount, pxPerMmX, heightF), minorPaint)
        canvas.drawLines(horizontalLines(rowCount, pxPerMmY, widthF), minorPaint)
        canvas.drawLines(verticalLines(colCount, pxPerMmX, heightF, step = CalibratedMmScale.SMALL_SQUARES_PER_LARGE_SQUARE), majorPaint)
        canvas.drawLines(horizontalLines(rowCount, pxPerMmY, widthF, step = CalibratedMmScale.SMALL_SQUARES_PER_LARGE_SQUARE), majorPaint)

        if (showTimeTicks) {
            drawTimeTicks(canvas, colCount, pxPerMmX, pxPerMmY, majorPaint)
        }
        if (showCalibrationPulse) {
            drawCalibrationPulse(canvas, heightF, majorPaint)
        }

        return bitmap
    }

    private fun verticalLines(count: Int, pxPerMm: Float, height: Float, step: Int = 1): FloatArray {
        val indices = 0 until count
        val selected = indices.filter { it % step == 0 }
        val pts = FloatArray(selected.size * 4)
        selected.forEachIndexed { pos, i ->
            // Fix C — snap to a whole pixel. At a hairline stroke width, a line sitting between
            // two pixels gets antialiased across both and reads as soft/fat instead of crisp.
            val x = (i * pxPerMm).roundToInt().toFloat()
            val o = pos * 4
            pts[o] = x; pts[o + 1] = 0f; pts[o + 2] = x; pts[o + 3] = height
        }
        return pts
    }

    private fun horizontalLines(count: Int, pxPerMm: Float, width: Float, step: Int = 1): FloatArray {
        val indices = 0 until count
        val selected = indices.filter { it % step == 0 }
        val pts = FloatArray(selected.size * 4)
        selected.forEachIndexed { pos, i ->
            // Fix C — snap to a whole pixel, same reasoning as verticalLines above.
            val y = (i * pxPerMm).roundToInt().toFloat()
            val o = pos * 4
            pts[o] = 0f; pts[o + 1] = y; pts[o + 2] = width; pts[o + 3] = y
        }
        return pts
    }

    private fun drawTimeTicks(canvas: Canvas, colCount: Int, pxPerMmX: Float, pxPerMmY: Float, paint: Paint) {
        val tickLenPx = TICK_LENGTH_MM * pxPerMmY
        val largeSquarePx = mmScale.largeSquarePxX()
        val tickSpacingPx = largeSquarePx * LARGE_SQUARES_PER_TIME_TICK
        var x = 0f
        val maxX = (colCount - 1) * pxPerMmX
        while (x <= maxX) {
            canvas.drawLine(x, 0f, x, tickLenPx, paint)
            x += tickSpacingPx
        }
    }

    private fun drawCalibrationPulse(canvas: Canvas, heightF: Float, paint: Paint) {
        val small = mmScale.smallSquarePxX()
        val pulseHeightPx = mmScale.mvToPx(1f)
        val baselineY = heightF / 2f
        val topY = baselineY - pulseHeightPx

        val leadInEnd = small * CALIBRATION_LEAD_IN_SMALL_SQUARES
        val riseX = leadInEnd
        val topEndX = riseX + small * CALIBRATION_WIDTH_SMALL_SQUARES
        val leadOutEndX = topEndX + small * CALIBRATION_LEAD_OUT_SMALL_SQUARES

        canvas.drawLine(0f, baselineY, leadInEnd, baselineY, paint)     // lead-in
        canvas.drawLine(riseX, baselineY, riseX, topY, paint)           // rise
        canvas.drawLine(riseX, topY, topEndX, topY, paint)              // top (1mV, 5 small squares wide)
        canvas.drawLine(topEndX, topY, topEndX, baselineY, paint)       // fall
        canvas.drawLine(topEndX, baselineY, leadOutEndX, baselineY, paint) // lead-out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridDirty) rebuildGridIfPossible()
        gridBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }
}
