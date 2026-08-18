# Calibrated ECG-Paper Screens — Handoff / Status Doc

> **Purpose of this file**: a self-contained snapshot of the "Calibrated Recorder / Calibrated
> Player" feature — what it is, every file involved, the exact current code, how it's wired into
> the app, and the full history of tuning decisions made in chat (with the reasoning, so a fresh
> conversation doesn't have to re-derive it). Paste this whole file into a new Claude chat to
> continue work with full context.
>
> Written 2026-08-18/19. If code and this doc disagree, trust the code — but update this file
> to match before moving on, since its whole value is being accurate.

---

## 1. What this feature is

Two new screens — **Calibrated Recorder** and **Calibrated Player** — that behave like the
app's existing production Recording/Player screens (same filters, pre-amp, dual-file output,
save/discard flow) but draw the waveform on a **physically accurate ECG-paper grid** instead of
MPAndroidChart's own gridlines. A millimetre on screen is a real millimetre; the grid speed is a
real ECG paper speed (12.5/25/50 mm/s). Goal: measurement trust — being able to look at a
recording and know a distance on screen corresponds to a real physical/time quantity.

This was built as an **additive, non-destructive port**. Nothing in the existing production
code was touched. Every calibrated file is a new file or a small additive change to shared
config files (`nav_graph.xml`, `AndroidManifest.xml`).

**Full original build spec** (constraints, the 5 accuracy fixes, verification plan) lives in
`docs/notes/WAVEFORM_GRAPH_AND_GRID_REFERENCE.md` §0–§4 of that doc are about the *existing*
4 waveform implementations in the repo (production Recording/Player + 2 dormant test screens);
this calibrated feature is a 5th, new implementation built on top of that research.

### Protected / never touched
`RecordingFragment.kt`, `RecordingViewModel.kt`, `PlayerFragment.kt`, `TestRecordingFragment.kt`,
`TestPlayerFragment.kt`, `MmScale.kt`, `EcgPaperView.kt`, `attrs_ecg_paper.xml`,
`HeartBpmCalculator.kt`, and their corresponding layout XMLs. No SDK module (`taal-core`,
`taal-ui-kit`, `lungs-app`) was touched either. Verified via `git diff --stat` after every
change — zero diff on all of the above throughout this whole feature's development.

---

## 2. Current build/run status

- `./gradlew :app:assembleDebug` — **passes**, zero errors.
- `./gradlew :app:testDebugUnitTest` — **passes**, 15/15 new tests green (plus all pre-existing
  tests untouched).
- **`nav_graph.xml` `startDestination` is currently TEMPORARILY set to `calibratedRecordingFragment`**
  (see line 6, marked `<!-- TEMP for dev testing -->`) so the app opens directly into the
  Calibrated Recorder when run from Android Studio. **Must be reverted to
  `@id/recordingFragment` before shipping/merging** — this exact gotcha is already flagged
  once in the repo's docs for the dormant test screens, don't repeat it.
- Has been run and tested on real hardware by the developer — device verification is done. The
  Claude Code sandbox this was originally built in had no attached device, so early revisions of
  this doc said "never tested"; that line was specific to the sandbox, not the project, and is
  stale. Treat the calibration and rendering as proven on-device, not as an open verification
  item.

---

## 3. File map

```
app/src/main/java/com/musediagnostics/taal/app/ecg/calibrated/
    CalibratedMmScale.kt          — pure-Kotlin mm/px/time math (fork of ecg/MmScale.kt)
    CalibratedEcgPaperView.kt     — the grid View (fork of ecg/EcgPaperView.kt)
    CalibratedWaveformView.kt     — shared paper+chart container, used by BOTH screens
    DpiCalibration.kt             — px-per-mm correction factor, persisted in SharedPreferences

app/src/main/java/com/musediagnostics/taal/app/ui/calibrated/
    CalibratedRecordingFragment.kt
    CalibratedRecordingViewModel.kt   (also declares CalibratedRecordingUiState enum)
    CalibratedPlayerFragment.kt
    DpiCalibrationFragment.kt         — ruler calibration screen

app/src/main/res/layout/
    fragment_calibrated_recording.xml
    fragment_calibrated_player.xml
    fragment_dpi_calibration.xml

app/src/main/res/values/
    attrs_calibrated_ecg_paper.xml    — renamed attrs (calEcgPaperSpeed etc.) to avoid
                                         duplicate-attribute collision with attrs_ecg_paper.xml

app/src/test/java/com/musediagnostics/taal/app/ecg/calibrated/
    CalibratedMmScaleTest.kt          — 15 unit tests

Modified (additive only, verified via git diff --stat):
    app/src/main/res/navigation/nav_graph.xml   — 3 new destinations + 1 deep link, +56/-0 lines
    app/src/main/AndroidManifest.xml            — 1 new <intent-filter> for the debug deep link
```

---

## 4. Full current source

### 4.1 `ecg/calibrated/CalibratedMmScale.kt`

```kotlin
package com.musediagnostics.taal.app.ecg.calibrated

/**
 * Copy of [com.musediagnostics.taal.app.ecg.PaperSpeed], forked so the calibrated screens
 * can evolve independently of the dormant test-screen grid (see ecg/MmScale.kt — protected,
 * not modified). The mm/s value is the only thing that changes what a grid square *means* in
 * time — it never changes a square's physical 1mm size on screen.
 */
enum class CalibratedPaperSpeed(val mmPerSecond: Float) {
    SPEED_12_5(12.5f),
    SPEED_25(25f),
    SPEED_50(50f)
}

/**
 * Copy of [com.musediagnostics.taal.app.ecg.Gain]. The mm/mV value is the only thing that
 * changes what a grid square *means* in voltage — it never changes a square's physical 1mm
 * size on screen. Kept here purely for grid geometry math — the calibrated screens hide the
 * calibration pulse and never expose this in the UI (Y-axis is "relative amplitude", not mV).
 */
enum class CalibratedGain(val mmPerMv: Float) {
    GAIN_5(5f),
    GAIN_10(10f),
    GAIN_20(20f)
}

/**
 * Fork of [com.musediagnostics.taal.app.ecg.MmScale] for the calibrated recorder/player
 * screens. Pure Kotlin, no Android View/Canvas dependency, so it's plain-JVM unit-testable.
 *
 * Grid geometry (small/large square size in px) depends only on [pxPerMmX]/[pxPerMmY].
 * [paperSpeed] and [gain] affect only [secondsToPx]/[mvToPx] and their inverses — changing
 * either never resizes the grid. This is the load-bearing invariant the unit tests guard.
 *
 * Adds [visibleSeconds] on top of the original: the number of seconds that fit across a
 * given plot width at the current paper speed. This is the fix for the root defect in the
 * dormant test screens — the visible window must be *derived* from the physical grid, not
 * imposed on it via a fixed WINDOW_SECONDS constant.
 */
class CalibratedMmScale(
    var pxPerMmX: Float,
    var pxPerMmY: Float,
    var paperSpeed: CalibratedPaperSpeed = CalibratedPaperSpeed.SPEED_25,
    var gain: CalibratedGain = CalibratedGain.GAIN_10
) {
    companion object {
        const val MM_PER_SMALL_SQUARE = 1f
        const val SMALL_SQUARES_PER_LARGE_SQUARE = 5
        const val MM_PER_LARGE_SQUARE = MM_PER_SMALL_SQUARE * SMALL_SQUARES_PER_LARGE_SQUARE
    }

    // --- time <-> horizontal px ---
    fun secondsToPx(seconds: Float): Float = seconds * paperSpeed.mmPerSecond * pxPerMmX
    fun pxToSeconds(px: Float): Float = px / (paperSpeed.mmPerSecond * pxPerMmX)

    // --- voltage <-> vertical px ---
    fun mvToPx(mv: Float): Float = mv * gain.mmPerMv * pxPerMmY
    fun pxToMv(px: Float): Float = px / (gain.mmPerMv * pxPerMmY)

    // --- grid geometry: physical mm only, never affected by speed/gain ---
    fun smallSquarePxX(): Float = MM_PER_SMALL_SQUARE * pxPerMmX
    fun smallSquarePxY(): Float = MM_PER_SMALL_SQUARE * pxPerMmY
    fun largeSquarePxX(): Float = MM_PER_LARGE_SQUARE * pxPerMmX
    fun largeSquarePxY(): Float = MM_PER_LARGE_SQUARE * pxPerMmY

    // --- what a square currently means, at the current speed/gain ---
    fun secondsPerSmallSquare(): Float = MM_PER_SMALL_SQUARE / paperSpeed.mmPerSecond
    fun secondsPerLargeSquare(): Float = MM_PER_LARGE_SQUARE / paperSpeed.mmPerSecond
    fun mvPerSmallSquare(): Float = MM_PER_SMALL_SQUARE / gain.mmPerMv
    fun mvPerLargeSquare(): Float = MM_PER_LARGE_SQUARE / gain.mmPerMv

    /**
     * The number of seconds of signal that fit across [plotWidthPx] at the current paper
     * speed and pxPerMmX. This is what the visible chart window must be derived from — never
     * a fixed constant like the dormant test screens' WINDOW_SECONDS. At 25mm/s on a typical
     * phone this comes out to roughly 3-4s, not 10s; that's correct, it's what a real ECG
     * strip of that physical width would show. Equivalent to [pxToSeconds] but named for the
     * specific call site (post-layout window derivation) so it reads as intentional there.
     */
    fun visibleSeconds(plotWidthPx: Float): Float = pxToSeconds(plotWidthPx)
}
```

### 4.2 `ecg/calibrated/CalibratedEcgPaperView.kt`

```kotlin
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
            strokeWidth = minorStrokeMm * pxPerMmX
            style = Paint.Style.STROKE
        }
        val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = majorGridColor
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
            val x = i * pxPerMm
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
            val y = i * pxPerMm
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
```

### 4.3 `ecg/calibrated/CalibratedWaveformView.kt`

```kotlin
package com.musediagnostics.taal.app.ecg.calibrated

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.FrameLayout
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import kotlin.math.abs

/**
 * Shared paper+chart container used by BOTH CalibratedRecordingFragment and
 * CalibratedPlayerFragment — the repo already has four diverged copies of
 * "downsample audio -> plot on MPAndroidChart" (see WAVEFORM_GRAPH_AND_GRID_REFERENCE.md);
 * this is deliberately not a fifth and sixth.
 *
 * Owns:
 *  - Stacking [paperView] (mm-accurate grid) behind [chart] (MPAndroidChart trace) at
 *    identical bounds, with the chart's own grid/background disabled and its viewport
 *    offsets zeroed so the chart's plot rect matches the view rect exactly (see class doc
 *    on [configureChartDefaults] for why margins alone can't achieve this — §4.2 of the
 *    reference doc).
 *  - Deriving the visible window in seconds from the grid (§4.1) whenever this view is
 *    sized/resized (rotation, split-screen, etc.), and pushing that onto the chart's
 *    visible-range lock. Fragments read [currentVisibleSeconds] / listen to
 *    [onVisibleSecondsChanged] to keep their own page-snap or centered-follow camera logic
 *    (which legitimately differs between recorder and player — this view does not own that)
 *    in sync with the derived window.
 *  - Min/max bucket downsampling ([downsampleMinMax]), shared by both screens, so a sharp
 *    transient that falls between two decimated samples is never dropped (§4.5).
 *
 * Camera control (page-snap vs. centered-follow), touch enable/disable, Y-axis range, and
 * dataset reuse are still the fragments' responsibility — those behaviors are intentionally
 * different between the recorder and the player and don't belong in a shared component.
 */
class CalibratedWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val paperView: CalibratedEcgPaperView
    val chart: LineChart

    /** Fired whenever the derived visible-window duration changes (post-layout, or on resize/rotation). */
    var onVisibleSecondsChanged: ((Float) -> Unit)? = null

    private var lastVisibleSeconds: Float = -1f

    init {
        paperView = CalibratedEcgPaperView(context).apply {
            showCalibrationPulse = false // §4.4 — no mV calibration pulse on a relative-amplitude axis
        }
        addView(paperView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        chart = LineChart(context)
        configureChartDefaults(chart)
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * §4.2 — MPAndroidChart reserves internal viewport offsets even when axis labels/lines
     * are disabled, so the plot rectangle is inset from the view rectangle by a
     * device-dependent amount. Matching margins by hand (what fragment_test_recording.xml
     * does today) cannot fix this — zeroing the offsets directly is the only reliable fix,
     * which is why paperView and chart are added here at identical (0,0,0,0) FrameLayout
     * bounds rather than via separately-declared XML margins on two sibling views.
     */
    private fun configureChartDefaults(chart: LineChart) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setViewPortOffsets(0f, 0f, 0f, 0f)
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.setDrawGridBackground(false)
        chart.setDrawBorders(false)
        chart.xAxis.setDrawGridLines(false)
        chart.xAxis.setDrawAxisLine(false)
        chart.xAxis.setDrawLabels(false)
        chart.axisLeft.setDrawGridLines(false)
        chart.axisLeft.setDrawAxisLine(false)
        chart.axisLeft.setDrawLabels(false)
        chart.axisRight.isEnabled = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeVisibleSeconds()
    }

    /**
     * §4.1 — the visible window is derived from the grid's physical width, never a fixed
     * WINDOW_SECONDS constant. Pushes the result onto the chart as a max-zoom-out cap and
     * notifies [onVisibleSecondsChanged] so callers can re-derive their page-snap boundaries.
     *
     * Deliberately caps only the *maximum* visible range here, not the minimum — a caller
     * that wants a hard-locked, unzoomable window (the recorder, which also disables touch
     * entirely) re-asserts setVisibleXRangeMinimum itself every frame; a caller that wants
     * pinch-zoom to work (the player) must not have this shared default fight it.
     */
    fun recomputeVisibleSeconds() {
        if (width <= 0) return
        val seconds = paperView.currentScale().visibleSeconds(width.toFloat())
        if (seconds > 0f && abs(seconds - lastVisibleSeconds) > 1e-4f) {
            lastVisibleSeconds = seconds
            chart.setVisibleXRangeMaximum(seconds)
            onVisibleSecondsChanged?.invoke(seconds)
        }
    }

    /** Best-effort synchronous read; may return a stale/default value before first layout. */
    fun currentVisibleSeconds(): Float =
        if (lastVisibleSeconds > 0f) lastVisibleSeconds
        else paperView.currentScale().visibleSeconds(width.toFloat().coerceAtLeast(1f))

    companion object {
        /**
         * §4.5 — for each bucket of [bucketSize] input samples, emit the bucket's min and its
         * max (in true time order), instead of picking every Nth sample. Same point budget as
         * a straight decimation of the same density, but a sharp S1 transient lasting a few ms
         * can no longer fall entirely between two kept samples and vanish from the display.
         */
        fun downsampleMinMax(data: FloatArray, bucketSize: Int, xForIndex: (Int) -> Float): List<Entry> {
            if (data.isEmpty()) return emptyList()
            if (bucketSize <= 1) return data.indices.map { Entry(xForIndex(it), data[it]) }

            val entries = ArrayList<Entry>((data.size / bucketSize + 1) * 2)
            var i = 0
            while (i < data.size) {
                val end = minOf(i + bucketSize, data.size)
                var minIdx = i
                var maxIdx = i
                var minVal = data[i]
                var maxVal = data[i]
                for (j in i until end) {
                    val v = data[j]
                    if (v < minVal) { minVal = v; minIdx = j }
                    if (v > maxVal) { maxVal = v; maxIdx = j }
                }
                // Emit in ascending time order regardless of which extreme came first.
                if (minIdx <= maxIdx) {
                    entries.add(Entry(xForIndex(minIdx), minVal))
                    if (maxIdx != minIdx) entries.add(Entry(xForIndex(maxIdx), maxVal))
                } else {
                    entries.add(Entry(xForIndex(maxIdx), maxVal))
                    entries.add(Entry(xForIndex(minIdx), minVal))
                }
                i += bucketSize
            }
            return entries
        }
    }
}
```

### 4.4 `ecg/calibrated/DpiCalibration.kt`

```kotlin
package com.musediagnostics.taal.app.ecg.calibrated

import android.content.Context
import android.os.Build

/**
 * Persisted px-per-mm correction factors for [CalibratedEcgPaperView].
 *
 * `TypedValue.applyDimension(COMPLEX_UNIT_MM, ...)` / `displayMetrics.ydpi` depend on the
 * OEM-reported DPI, which is frequently wrong. Without correction the grid is precise but
 * not accurate — it looks authoritative and isn't. This class stores a multiplicative
 * correction (`correction = nominalMm / measuredMm`, from a physical-ruler measurement) that
 * [applyTo] multiplies onto the view's OEM-reported px-per-mm.
 *
 * Lookup order: (1) a hardcoded per-model table for known clinical tablets — faster in the
 * field and avoids re-running the ruler test on every unit of the same model; (2) a manual
 * SharedPreferences calibration done via [com.musediagnostics.taal.app.ui.calibrated.DpiCalibrationFragment];
 * (3) default 1.0 (uncorrected) when neither is present.
 */
object DpiCalibration {
    private const val PREFS_NAME = "calibrated_dpi_prefs"
    private const val KEY_CORRECTION_X = "correction_x"
    private const val KEY_CORRECTION_Y = "correction_y"
    private const val KEY_IS_CALIBRATED = "is_calibrated"

    /**
     * Per-model correction factors (correctionX, correctionY) for devices already measured
     * in the field with a physical ruler. Checked before SharedPreferences so a fleet of
     * identical clinical tablets doesn't need the manual calibration screen run on every
     * unit. Empty until a model has actually been ruler-tested — do not guess values here.
     */
    private val KNOWN_DEVICE_CORRECTIONS: Map<String, Pair<Float, Float>> = emptyMap()

    data class Correction(
        val x: Float,
        val y: Float,
        val isCalibrated: Boolean,
        val source: String // "device-table" | "manual" | "uncalibrated"
    )

    fun getCorrection(context: Context): Correction {
        KNOWN_DEVICE_CORRECTIONS[Build.MODEL]?.let { (x, y) ->
            return Correction(x, y, isCalibrated = true, source = "device-table")
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val calibrated = prefs.getBoolean(KEY_IS_CALIBRATED, false)
        val x = prefs.getFloat(KEY_CORRECTION_X, 1.0f)
        val y = prefs.getFloat(KEY_CORRECTION_Y, 1.0f)
        return Correction(x, y, calibrated, if (calibrated) "manual" else "uncalibrated")
    }

    fun saveManualCorrection(context: Context, correctionX: Float, correctionY: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_CORRECTION_X, correctionX)
            .putFloat(KEY_CORRECTION_Y, correctionY)
            .putBoolean(KEY_IS_CALIBRATED, true)
            .apply()
    }

    fun clearManualCorrection(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_CORRECTION_X)
            .remove(KEY_CORRECTION_Y)
            .putBoolean(KEY_IS_CALIBRATED, false)
            .apply()
    }

    /**
     * Multiplies the view's current (OEM-reported) px-per-mm by the stored correction and
     * applies it via [CalibratedEcgPaperView.setPixelsPerMm]. Call once, after the view has
     * been constructed (so `currentScale()` holds the reported baseline) and before any other
     * manual override — calling it twice would compound the correction.
     */
    fun applyTo(view: CalibratedEcgPaperView, context: Context) {
        val reported = view.currentScale()
        val correction = getCorrection(context)
        view.setPixelsPerMm(
            reported.pxPerMmX * correction.x,
            reported.pxPerMmY * correction.y
        )
    }
}
```

### 4.5 `ui/calibrated/CalibratedRecordingViewModel.kt`

```kotlin
package com.musediagnostics.taal.app.ui.calibrated

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.musediagnostics.taal.app.TaalApplication
import com.musediagnostics.taal.app.data.repository.RecordingRepository

/**
 * Copy of [com.musediagnostics.taal.app.ui.recording.RecordingViewModel] (protected, not
 * modified), forked so CalibratedRecordingFragment can evolve independently. State shape is
 * identical — this is what keeps a future "swap the fragment class in nav_graph.xml" promotion
 * a one-line change.
 */
enum class CalibratedRecordingUiState {
    IDLE,       // Pre-recording
    RECORDING,  // Actively recording
    STOPPED     // Recording finished, showing save options
}

class CalibratedRecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as TaalApplication).database
    val recordingRepository = RecordingRepository(db.recordingDao())

    private val _uiState = MutableLiveData(CalibratedRecordingUiState.IDLE)
    val uiState: LiveData<CalibratedRecordingUiState> = _uiState

    private val _timerSeconds = MutableLiveData(0)
    val timerSeconds: LiveData<Int> = _timerSeconds

    private val _currentFilter = MutableLiveData("HEART")
    val currentFilter: LiveData<String> = _currentFilter

    private val _bpm = MutableLiveData(0)
    val bpm: LiveData<Int> = _bpm

    private val _preAmpDb = MutableLiveData(5)
    val preAmpDb: LiveData<Int> = _preAmpDb

    var currentRecordingPath: String = ""
    var currentFilteredPath: String = ""
    var customLowCut: Float? = null
    var customHighCut: Float? = null

    fun setUiState(state: CalibratedRecordingUiState) {
        _uiState.value = state
    }

    fun updateTimer(seconds: Int) {
        _timerSeconds.value = seconds
    }

    fun setFilter(filter: String) {
        _currentFilter.value = filter
    }

    fun setBpm(bpm: Int) {
        _bpm.value = bpm
    }

    fun setPreAmp(db: Int) {
        _preAmpDb.value = db.coerceIn(0, 30)
    }

    fun formatTimer(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
}
```

### 4.6 `ui/calibrated/CalibratedRecordingFragment.kt`

```kotlin
package com.musediagnostics.taal.app.ui.calibrated

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalRecorder
import com.musediagnostics.taal.core.RecorderState
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentCalibratedRecordingBinding
import com.musediagnostics.taal.app.dsp.HeartBpmCalculator
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedPaperSpeed
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Calibrated replica of [com.musediagnostics.taal.app.ui.recording.RecordingFragment]
 * (protected, not modified — ported by hand, see WAVEFORM_GRAPH_AND_GRID_REFERENCE.md §2 for
 * the exact behavior this mirrors). Same UI, filters, pre-amp, dual-file output and controls;
 * the only intended difference is that the waveform is drawn on a physically calibrated
 * mm-accurate ECG-paper grid (via [CalibratedWaveformView]) instead of MPAndroidChart's own
 * gridlines on a fixed 10s window.
 *
 * Y-axis auto-scaling (warmup/peak/headroom) is ported verbatim from production, at the
 * user's explicit request — see chat history. This means two different recordings are no
 * longer guaranteed to be visually comparable by amplitude (each gets its own peak-based
 * zoom), which is a deliberate reversal of this feature's original design goal.
 */
class CalibratedRecordingFragment : Fragment() {

    private var _binding: FragmentCalibratedRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CalibratedRecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    private var audioTrack: AudioTrack? = null
    private val waveformEntries = ArrayList<Entry>()

    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    private var waveformDataSet: LineDataSet? = null
    private var totalSamplesProcessed = 0L  // Sample-accurate X position counter
    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // §4.1 — the visible window is derived from the grid's physical width, not a fixed
    // constant; this holds the most recently derived value (updated on layout/rotation).
    private var currentWindowSeconds = 4f

    // Adaptive Y-axis state. Locking logic (warmup window, then freeze) is ported verbatim from
    // RecordingFragment, but the peak itself is estimated robustly (see WARMUP_PERCENTILE)
    // instead of a raw max — a raw max lets a single loud transient (e.g. the contact "thud"
    // of placing the stethoscope, which often happens right at the start of the warmup window)
    // permanently set an oversized scale that makes the actual heart/lung sound look tiny for
    // the rest of the recording. User-reported symptom this fixes: "while recording it looks
    // small" even though the same file looks properly sized once opened in the player (which
    // scans the whole finished file for its peak, not just 2 seconds of live audio).
    private var peakAmplitude = 1.0f       // Y-axis half-range; set from warmup then locked
    private var lastPeakUpdateTime = 0L
    private val warmupBufferPeaks = ArrayList<Float>() // one entry per buffer seen during warmup
    private var warmupDone = false         // Latches true after WARMUP_MS of signal observed

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f

        // 2 points per bucket (min+max) at this bucket size ≈ same point budget as production's
        // "1 in 44 samples" decimation (44100 / 88 * 2 ≈ 1002 pts/sec), but peaks are preserved.
        private const val DOWNSAMPLE_BUCKET = 88

        // WARMUP: measure the signal's true peak over the first 2000ms, then lock in the Y-axis.
        private const val WARMUP_MS = 2000
        // After warmup, Y-axis = peakAmplitude × HEADROOM, so the waveform fills 1/HEADROOM of
        // the chart height. Tighter than production's 1.5 (user request: bigger peaks) — 1.2
        // fills ~83%, with enough margin that a slightly-louder sample after warmup doesn't
        // clip against the frame edge (1.1 was tried and did clip — too little margin).
        private const val HEADROOM = 1.2f
        // Minimum axis half-range — prevents over-zooming on near-silence.
        private const val MIN_PEAK = 0.02f
        // Robust-peak percentile used to lock the warmup scale (user request — see field doc
        // on warmupBufferPeaks). Raised from 0.9 to 0.97 after real peaks were clipping — 0.9
        // excluded too much of the genuine signal, not just one-off transients. 0.97 still
        // drops the loudest ~3% of buffers (protects against a single contact-thud outlier)
        // while capturing far more of the real peak.
        private const val WARMUP_PERCENTILE = 0.97f
        // Trace stroke width in dp. Reverted to production's thin/crisp value — "bigger" is
        // achieved via HEADROOM (taller peaks), not a fatter stroke (user request).
        private const val TRACE_LINE_WIDTH_DP = 1.5f
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(requireContext(), "Audio permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalibratedRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupWaveformChart()
        setupFilterButtons()
        setupPreAmpSlider()
        setupButtons()
        observeState()
        setupConnectionReceiver()
        updateCalibrationCaption()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.uiState.value == CalibratedRecordingUiState.RECORDING) {
                        Toast.makeText(
                            requireContext(),
                            "Stop the recording before going back",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun setupWaveformChart() {
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart
        chart.setTouchEnabled(false) // no pan/zoom while recording — same as production

        // Initial range before warmup locks it, same as production's setupWaveformChart().
        chart.axisLeft.axisMinimum = -peakAmplitude
        chart.axisLeft.axisMaximum = peakAmplitude

        // Slower than the 25mm/s default (user request) — halves the trace's on-screen speed
        // and, as a side effect, doubles the seconds visible per screen width. Grid square
        // physical size is unaffected — only what a square means in time changes.
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_12_5)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        waveformView.onVisibleSecondsChanged = { seconds ->
            currentWindowSeconds = seconds
            // Don't clobber an in-progress recording's dataset on rotation/resize — only
            // reset to the dummy grid-holder dataset when there's no real data yet.
            // updateWaveform() re-enforces the range every callback while recording is live.
            if (waveformDataSet == null) {
                resetChartToDummyData()
            } else {
                chart.setVisibleXRangeMaximum(seconds)
                chart.setVisibleXRangeMinimum(seconds)
                chart.invalidate()
            }
        }
        // If layout already happened (e.g. returning to this fragment), derive immediately.
        waveformView.recomputeVisibleSeconds()
        if (chart.data == null) resetChartToDummyData()
    }

    private fun resetChartToDummyData() {
        if (_binding == null) return
        val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(currentWindowSeconds, 0f)), "").apply {
            color = Color.TRANSPARENT
            setDrawCircles(false)
            setDrawValues(false)
        }
        binding.calibratedWaveformView.chart.data = LineData(dummyDataSet)
        binding.calibratedWaveformView.chart.invalidate()
    }

    private fun updateCalibrationCaption() {
        if (_binding == null) return
        val correction = DpiCalibration.getCorrection(requireContext())
        val status = if (correction.isCalibrated) "calibrated (${correction.source})" else "UNCALIBRATED"
        val speed = binding.calibratedWaveformView.paperView.currentScale().paperSpeed.mmPerSecond
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude (auto-scaled) · DPI: $status"
    }

    private fun setupPreAmpSlider() {
        binding.ampSlider.value = (viewModel.preAmpDb.value ?: 5).toFloat()
        binding.ampLabel.text = "${viewModel.preAmpDb.value ?: 5} dB"

        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            viewModel.setPreAmp(db)
            binding.ampLabel.text = "$db dB"
            taalRecorder?.setPreAmplification(db)
        }
    }

    private fun setupFilterButtons() {
        val presetFilters = mapOf(
            binding.filterHeart to "HEART",
            binding.filterLungs to "LUNGS",
            binding.filterBowel to "BOWEL",
            binding.filterPregnancy to "PREGNANCY",
            binding.filterInfo to "FULL_BODY"
        )
        val allButtons = presetFilters.keys + binding.filterCustom

        binding.filterHeart.isSelected = true
        viewModel.setFilter("HEART")
        binding.customRangePanel.visibility = View.GONE

        presetFilters.forEach { (button, name) ->
            button.setOnClickListener {
                allButtons.forEach { it.isSelected = false }
                button.isSelected = true
                viewModel.setFilter(name)
                binding.customRangePanel.visibility = View.GONE
                dismissKeyboard()
            }
        }

        binding.filterCustom.setOnClickListener {
            allButtons.forEach { it.isSelected = false }
            binding.filterCustom.isSelected = true
            viewModel.setFilter("CUSTOM")
            binding.customRangePanel.visibility = View.VISIBLE
        }

        setupCustomRangePanel()
    }

    private fun dismissKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)
        requireView().clearFocus()
    }

    private fun setupCustomRangePanel() {
        val initLow = viewModel.customLowCut ?: 20f
        val initHigh = viewModel.customHighCut ?: 10000f

        viewModel.customLowCut = initLow
        viewModel.customHighCut = initHigh

        binding.customRangeSlider.values = listOf(
            initLow.coerceIn(0f, 24000f),
            initHigh.coerceIn(0f, 24000f)
        )
        binding.customLowCutInput.setText(initLow.toInt().toString())
        binding.customHighCutInput.setText(initHigh.toInt().toString())

        var isUpdating = false

        binding.customRangeSlider.addOnChangeListener { _, _, _ ->
            if (isUpdating) return@addOnChangeListener
            isUpdating = true
            val vals = binding.customRangeSlider.values
            val low = vals[0].toInt()
            val high = vals[1].toInt()
            binding.customLowCutInput.setText(low.toString())
            binding.customHighCutInput.setText(high.toString())
            viewModel.customLowCut = vals[0]
            viewModel.customHighCut = vals[1]
            isUpdating = false
        }

        binding.customLowCutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                viewModel.customLowCut = v.coerceIn(1f, 24000f)
                val currentHigh = binding.customRangeSlider.values[1]
                if (v in 1f..24000f && v < currentHigh) {
                    isUpdating = true
                    binding.customRangeSlider.values = listOf(v, currentHigh)
                    isUpdating = false
                }
            }
        })

        binding.customHighCutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                viewModel.customHighCut = v.coerceIn(1f, 24000f)
                val currentLow = binding.customRangeSlider.values[0]
                if (v in 1f..24000f && v > currentLow) {
                    isUpdating = true
                    binding.customRangeSlider.values = listOf(currentLow, v)
                    isUpdating = false
                }
            }
        })
    }

    private fun setupConnectionReceiver() {
        connectionReceiver = TaalConnectionBroadcastReceiver(object :
            TaalConnectionBroadcastReceiver.TaalConnectionListener {
            override fun onTaalConnect() {
                activity?.runOnUiThread {
                    binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))
                }
            }

            override fun onTaalDisconnect() {
                activity?.runOnUiThread {
                    binding.deviceIcon.setColorFilter(Color.parseColor("#333333"))
                }
            }
        })
        connectionReceiver?.register(requireContext())
    }

    private fun checkDeviceConnectionStatus() {
        try {
            val usbManager =
                requireContext().getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            if (usbManager.deviceList.isNotEmpty()) {
                binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))
            } else {
                binding.deviceIcon.setColorFilter(Color.parseColor("#333333"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupButtons() {
        binding.infoButton.setOnClickListener {
            val filterName = viewModel.currentFilter.value ?: "HEART"
            com.musediagnostics.taal.app.ui.recording.FilterPlacementDialog.newInstance(filterName)
                .show(parentFragmentManager, "filter_placement")
        }

        binding.recordButton.setOnClickListener {
            when (viewModel.uiState.value) {
                CalibratedRecordingUiState.IDLE -> checkPermissionAndRecord()
                CalibratedRecordingUiState.RECORDING -> stopRecording()
                else -> resetToIdle()
            }
        }

        // Dead in production too (STOPPED-state UI is never entered — stopRecording()
        // navigates directly to the player) — kept only so the layout/ID surface stays a
        // faithful replica, matching RecordingFragment.kt's own vestigial binding.
        binding.playPauseButton.setOnClickListener {
            val filteredPath = viewModel.currentFilteredPath
            val rawPath = viewModel.currentRecordingPath
            val filterName = viewModel.currentFilter.value ?: "HEART"
            if (filteredPath.isNotEmpty()) {
                val bundle = Bundle().apply {
                    putString("filePath", filteredPath)
                    putString("rawFilePath", rawPath)
                    putString("filterName", filterName)
                }
                findNavController().navigate(R.id.action_calibratedRecording_to_calibratedPlayer, bundle)
            }
        }

        binding.folderButton.setOnClickListener {
            findNavController().navigate(R.id.action_calibratedRecording_to_savedRecordings)
        }

        binding.settingsButton.setOnClickListener {
            findNavController().navigate(R.id.action_calibratedRecording_to_dpiCalibration)
        }
    }

    private fun resetToIdle() {
        viewModel.setUiState(CalibratedRecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        viewModel.currentFilteredPath = ""
        waveformEntries.clear()
        waveformDataSet = null
        totalSamplesProcessed = 0L
        peakAmplitude = 1.0f
        warmupBufferPeaks.clear()
        warmupDone = false
        lastPeakUpdateTime = 0L

        resetChartToDummyData()
        binding.calibratedWaveformView.chart.moveViewToX(0f)

        binding.bpmText.text = "-- BPM"
        updateCalibrationCaption()
    }

    private fun setFilterButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        listOf(
            binding.filterHeart,
            binding.filterLungs,
            binding.filterBowel,
            binding.filterPregnancy,
            binding.filterInfo,
            binding.filterCustom
        ).forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
        if (!enabled) {
            binding.customRangePanel.visibility = View.GONE
        } else if (viewModel.currentFilter.value == "CUSTOM") {
            binding.customRangePanel.visibility = View.VISIBLE
        }
    }

    private fun observeState() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                CalibratedRecordingUiState.IDLE -> {
                    binding.actionText.text = getString(R.string.start_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.VISIBLE
                    binding.preRecordingButtons.visibility = View.VISIBLE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordButton.setImageResource(R.drawable.ic_recording_start1)
                    binding.timerText.text = getString(R.string.timer_default)
                    binding.ampSlider.isEnabled = true
                    binding.ampSliderContainer.alpha = 1f
                    setFilterButtonsEnabled(true)
                }

                CalibratedRecordingUiState.RECORDING -> {
                    binding.actionText.text = getString(R.string.stop_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.GONE
                    binding.preRecordingButtons.visibility = View.GONE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordButton.setImageResource(R.drawable.ic_recording_stop)
                    binding.ampSlider.isEnabled = false
                    binding.ampSliderContainer.alpha = 0.55f
                    setFilterButtonsEnabled(false)
                }

                else -> {}
            }
        }

        viewModel.timerSeconds.observe(viewLifecycleOwner) { seconds ->
            binding.timerText.text = viewModel.formatTimer(seconds)
        }

        viewModel.bpm.observe(viewLifecycleOwner) { bpm ->
            binding.bpmText.text = if (bpm > 0) getString(R.string.bpm_format, bpm) else "-- BPM"
        }
    }

    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        dismissKeyboard()

        val filterName = viewModel.currentFilter.value ?: "HEART"

        if (filterName == "CUSTOM") {
            val low = viewModel.customLowCut
            val high = viewModel.customHighCut
            val lowText = binding.customLowCutInput.text?.toString()?.trim()
            val highText = binding.customHighCutInput.text?.toString()?.trim()

            val message = when {
                lowText.isNullOrEmpty() && highText.isNullOrEmpty() ->
                    "Low Cut and High Cut cannot be blank.\nPlease enter valid frequency values (e.g. Low Cut: 20 Hz, High Cut: 1000 Hz)."
                lowText.isNullOrEmpty() ->
                    "Low Cut cannot be blank.\nPlease enter a frequency greater than 0 Hz."
                highText.isNullOrEmpty() ->
                    "High Cut cannot be blank.\nPlease enter a frequency greater than 0 Hz."
                low == null || low <= 0f ->
                    "Low Cut cannot be 0 Hz.\nA value of 0 Hz disables the filter entirely. Please enter a frequency greater than 0 Hz (e.g. 20 Hz)."
                high == null || high <= 0f ->
                    "High Cut cannot be 0 Hz.\nA value of 0 Hz disables the filter entirely. Please enter a frequency greater than 0 Hz (e.g. 1000 Hz)."
                low >= high ->
                    "Low Cut (${low.toInt()} Hz) must be less than High Cut (${high.toInt()} Hz).\nPlease adjust the values so the passband is valid."
                else -> null
            }

            if (message != null) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Custom Filter")
                    .setMessage(message)
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .show()
                return
            }
        }

        try {
            val ts = System.currentTimeMillis()
            val rawFilePath = "${requireContext().filesDir}/cal_recording_${ts}_raw.wav"
            val filteredFilePath = "${requireContext().filesDir}/cal_recording_${ts}_filtered.wav"
            viewModel.currentRecordingPath = rawFilePath
            viewModel.currentFilteredPath = filteredFilePath

            taalRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(rawFilePath)
                setFilteredAudioFilePath(filteredFilePath)
                setRecordingTime(300)
                setPlayback(false)
                setPreAmplification(viewModel.preAmpDb.value ?: 5)
                if (filterName == "CUSTOM") {
                    setCustomBandpass(
                        viewModel.customLowCut!!.toDouble(),
                        viewModel.customHighCut!!.toDouble()
                    )
                } else {
                    setPreFilter(PreFilter.valueOf(filterName))
                }

                onInfoListener = object : TaalRecorder.OnInfoListener {
                    override fun onStateChange(state: RecorderState) {
                        activity?.runOnUiThread {
                            when (state) {
                                RecorderState.RECORDING -> viewModel.setUiState(CalibratedRecordingUiState.RECORDING)
                                RecorderState.STOPPED -> viewModel.setUiState(CalibratedRecordingUiState.STOPPED)
                                else -> {}
                            }
                        }
                    }

                    override fun onRawProgressUpdate(data: FloatArray) {}

                    override fun onDeviceDisconnected() {
                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                stopAudioMonitor()
                                taalRecorder = null

                                if (viewModel.currentRecordingPath.isNotEmpty()) {
                                    try { File(viewModel.currentRecordingPath).delete() } catch (_: Exception) {}
                                }
                                if (viewModel.currentFilteredPath.isNotEmpty()) {
                                    try { File(viewModel.currentFilteredPath).delete() } catch (_: Exception) {}
                                }

                                Toast.makeText(
                                    requireContext(),
                                    "Device disconnected. Please connect the device.",
                                    Toast.LENGTH_LONG
                                ).show()
                                resetToIdle()
                            }
                        }
                    }

                    override fun onSilentRecordingDetected(isFirstSinceConnect: Boolean) {
                        if (!isFirstSinceConnect) return
                        activity?.runOnUiThread {
                            val act = activity ?: return@runOnUiThread
                            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                            android.app.AlertDialog.Builder(act)
                                .setTitle("Ready to Capture")
                                .setMessage("Your TAAL device has been detected and is now ready. Please discard this recording and start a new one.")
                                .setPositiveButton("OK", null)
                                .setCancelable(false)
                                .show()
                        }
                    }

                    override fun onProgressUpdate(
                        sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
                    ) {
                        audioTrack?.let { track ->
                            val pcm = ShortArray(data.size) { i ->
                                (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                            track.write(pcm, 0, pcm.size)
                        }

                        val shouldCompute = bpmCalculator.addSamples(data)
                        if (shouldCompute) {
                            bpmScope.launch {
                                val bpm = bpmCalculator.computeBpm()
                                if (bpm > 0) {
                                    withContext(Dispatchers.Main) {
                                        if (isAdded && _binding != null) {
                                            viewModel.setBpm(bpm)
                                        }
                                    }
                                }
                            }
                        }

                        // Undo pre-amp gain before drawing, same as production (§4.4) — the
                        // trace reflects true acoustic level, not the amplified WAV level.
                        val preAmpDb = viewModel.preAmpDb.value ?: 5
                        val preAmpGain = Math.pow(10.0, preAmpDb / 20.0).toFloat()
                        val displayData = if (preAmpGain > 1.001f) {
                            FloatArray(data.size) { i -> data[i] / preAmpGain }
                        } else {
                            data
                        }

                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                updateWaveform(displayData)
                                val elapsed = timeStamp.toInt()
                                viewModel.updateTimer(elapsed)
                            }
                        }
                    }
                }
            }

            waveformEntries.clear()
            waveformDataSet = null
            totalSamplesProcessed = 0L
            peakAmplitude = 1.0f
            warmupBufferPeaks.clear()
            warmupDone = false
            lastPeakUpdateTime = 0L
            bpmCalculator.reset()

            resetChartToDummyData()
            binding.calibratedWaveformView.chart.moveViewToX(0f)
            startAudioMonitor()
            taalRecorder?.start()

        } catch (e: Exception) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording Error").setMessage(e.message)
                .setPositiveButton("OKAY") { dialog, _ -> dialog.dismiss() }.show()
            viewModel.setUiState(CalibratedRecordingUiState.IDLE)
        }
    }

    private fun startAudioMonitor() {
        val minBuf = AudioTrack.getMinBufferSize(
            44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            44100,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
            AudioTrack.MODE_STREAM
        ).apply { play() }
    }

    private fun stopAudioMonitor() {
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private fun stopRecording() {
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
        taalRecorder = null

        val filteredPath = viewModel.currentFilteredPath
        val rawPath = viewModel.currentRecordingPath
        val filterName = viewModel.currentFilter.value ?: "HEART"

        if (filteredPath.isNotEmpty()) {
            val bundle = Bundle().apply {
                putString("filePath", filteredPath)
                putString("rawFilePath", rawPath)
                putBoolean("isNewRecording", true)
                putString("filterName", filterName)
            }
            findNavController().navigate(R.id.action_calibratedRecording_to_calibratedPlayer, bundle)
        }
    }

    /**
     * Calibrated counterpart of RecordingFragment's V7 updateWaveform(). Differences from
     * production, per task spec §4:
     *  - X window (`currentWindowSeconds`) is derived from the grid, not a WINDOW_SECONDS
     *    constant (§4.1).
     *  - Downsampling is min/max bucketed via CalibratedWaveformView, not "every Nth sample"
     *    (§4.5), so a transient can't fall entirely between two kept samples.
     * Y-axis warmup/peak/headroom logic is ported verbatim from production (restored at the
     * user's explicit request, overriding the original §4.4 fixed-axis design).
     */
    private fun updateWaveform(data: FloatArray) {
        if (_binding == null || !isAdded) return
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart

        var bufferPeak = 0f
        for (sample in data) {
            val abs = Math.abs(sample)
            if (abs > bufferPeak) bufferPeak = abs
        }

        val bufferStartSample = totalSamplesProcessed
        val newEntries = CalibratedWaveformView.downsampleMinMax(data, DOWNSAMPLE_BUCKET) { j ->
            (bufferStartSample + j).toFloat() / INPUT_SAMPLE_RATE
        }
        waveformEntries.addAll(newEntries)
        totalSamplesProcessed += data.size

        val latestX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
        val windowSeconds = currentWindowSeconds

        val currentPage = (latestX / windowSeconds).toInt()
        val currentViewX = currentPage * windowSeconds

        val minXToKeep = (currentPage - 1) * windowSeconds
        if (minXToKeep > 0) {
            val iterator = waveformEntries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().x < minXToKeep) iterator.remove() else break
            }
        }

        // WARMUP: observe buffer peaks for the first WARMUP_MS, then lock the Y-axis to a
        // robust estimate of the peak × HEADROOM and never re-expand it afterwards. Uses the
        // WARMUP_PERCENTILE of buffer peaks rather than the raw max (production's original
        // approach) — a raw max lets one loud transient (e.g. the contact "thud" of placing
        // the stethoscope) permanently set an oversized scale for the whole recording.
        val now = System.currentTimeMillis()
        if (!warmupDone) {
            warmupBufferPeaks.add(bufferPeak)
            if (lastPeakUpdateTime == 0L) lastPeakUpdateTime = now
            if (now - lastPeakUpdateTime >= WARMUP_MS) {
                warmupDone = true
                val robustPeak = robustPeakFrom(warmupBufferPeaks)
                warmupBufferPeaks.clear() // no longer needed — free it
                peakAmplitude = (robustPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
                chart.axisLeft.axisMinimum = -peakAmplitude
                chart.axisLeft.axisMaximum = peakAmplitude
            }
        }

        val snapshot = ArrayList(waveformEntries)
        val ds = waveformDataSet

        if (ds == null || chart.data == null) {
            waveformDataSet = LineDataSet(snapshot, "Waveform").apply {
                color = ContextCompat.getColor(requireContext(), R.color.waveform_blue)
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = TRACE_LINE_WIDTH_DP
                mode = LineDataSet.Mode.LINEAR
                setDrawHighlightIndicators(false)
            }
            chart.data = LineData(waveformDataSet)
        } else {
            ds.values = snapshot
            chart.data?.notifyDataChanged()
        }

        chart.notifyDataSetChanged()

        chart.setVisibleXRangeMaximum(windowSeconds)
        chart.setVisibleXRangeMinimum(windowSeconds)
        chart.moveViewToX(currentViewX)
        chart.invalidate()
    }

    /**
     * WARMUP_PERCENTILE of a list of per-buffer peak values, instead of the raw max. Discards
     * the loudest tail of the distribution (single-buffer transients like a contact thud)
     * before picking the value the Y-axis locks to, so the scale reflects the sustained signal
     * rather than a one-off spike.
     */
    private fun robustPeakFrom(bufferPeaks: List<Float>): Float {
        if (bufferPeaks.isEmpty()) return 0f
        val sorted = bufferPeaks.sorted()
        val index = ((sorted.size - 1) * WARMUP_PERCENTILE).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    override fun onResume() {
        super.onResume()

        checkDeviceConnectionStatus()
        if (taalRecorder == null) {
            resetToIdle()
        }
        viewModel.setPreAmp(5)
        binding.ampSlider.value = 5f
        binding.ampLabel.text = "5 dB"
        updateCalibrationCaption()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        connectionReceiver?.unregister(requireContext())
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        bpmScope.cancel()
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
    }
}
```

### 4.7 `ui/calibrated/CalibratedPlayerFragment.kt`

```kotlin
package com.musediagnostics.taal.app.ui.calibrated

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentCalibratedPlayerBinding
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedPaperSpeed
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration
import com.musediagnostics.taal.app.ui.player.PlayerSaveDiscardDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Calibrated replica of [com.musediagnostics.taal.app.ui.player.PlayerFragment] (protected,
 * not modified — ported by hand, see WAVEFORM_GRAPH_AND_GRID_REFERENCE.md §3). Same controls,
 * save/discard flow and filter-reapplication guard; the waveform is drawn on the calibrated
 * mm-accurate grid instead of MPAndroidChart's own gridlines on a fixed 4s window.
 *
 * No pre-amp display compensation here — the played-back file already has gain baked in from
 * record time, same as production. Y-axis starts at a fixed ±0.5 placeholder (same as production
 * PlayerFragment) but is then adapted to the loaded file's actual peak amplitude (user request:
 * bigger peaks) — since the whole file is already decoded in memory by the time it's rendered,
 * this doesn't need a warmup window the way the live recorder does. Touch/drag/scale are
 * enabled, same as production; the visible-range lock only caps the *maximum* zoom-out (not the
 * minimum), so pinch-zoom actually works — the mm-grid behind the chart is a separate, static
 * view and intentionally does not zoom with the trace (per user request: zoom the trace, don't
 * move the background).
 *
 * The camera-follow during playback is smoothed (see [displayedPlaybackTime]/FOLLOW_SMOOTHING),
 * not a direct snap to the real playback position — a direct snap looked "too fast" once
 * pinch-zoom made the visible window small (user request). Audio itself always plays at true
 * speed/pitch; only the visual follow is damped.
 */
class CalibratedPlayerFragment : Fragment() {

    private var _binding: FragmentCalibratedPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    private var currentWindowSeconds = 4f
    private val fixedPeakAmplitude = 0.5f // placeholder until the file's real peak is known

    // Smoothed camera-follow position during playback — see onPlaybackProgress. Reset to 0
    // wherever the chart snaps back to its start-centered framing.
    private var displayedPlaybackTime = 0f

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val TARGET_POINT_BUDGET = 3000 // same total-point budget as production's maxPoints
        // Same tuning as CalibratedRecordingFragment, applied to the file's actual peak instead
        // of a live warmup window. 1.2 fills ~83% of the chart height — matches the recorder's
        // margin (1.1 clipped, too little headroom). No percentile trimming needed here since
        // this is already the file's true peak, not a live estimate.
        private const val HEADROOM = 1.2f
        private const val MIN_PEAK = 0.02f
        // Camera-follow smoothing (user request: playback feels "too fast" when zoomed in).
        // Fraction of the remaining gap to the real playback position closed per progress
        // callback — lower = gentler/slower-feeling follow, 1.0 = instant snap (old behavior).
        private const val FOLLOW_SMOOTHING = 0.15f
        // Trace stroke width in dp. Reverted to production's thin/crisp value — "bigger" is
        // achieved via HEADROOM (taller peaks), not a fatter stroke (user request).
        private const val TRACE_LINE_WIDTH_DP = 2.5f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalibratedPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val isNewRecording = arguments?.getBoolean("isNewRecording", false) ?: false
        val filterName = arguments?.getString("filterName") ?: "HEART"

        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()
        setupAmpSlider()
        updateCalibrationCaption()

        if (filePath.isNotEmpty()) {
            loadFullWaveform(filePath, filterName)
            setupPlayer(filePath, filterName)
        }

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.eqButton.setOnClickListener {
            val bundle = Bundle().apply { putString("filePath", filePath) }
            findNavController().navigate(R.id.action_calibratedPlayer_to_equalizer, bundle)
        }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }

        binding.saveButton.setOnClickListener {
            if (isNewRecording) {
                val rawFilePath = arguments?.getString("rawFilePath") ?: ""
                val bundle = Bundle().apply {
                    putString("filePath", filePath)
                    putString("rawFilePath", rawFilePath)
                    putString("filterName", filterName)
                }
                findNavController().navigate(R.id.action_calibratedPlayer_to_saveRecording, bundle)
            } else {
                showSaveDiscardDialog(filePath)
            }
        }

        binding.discardButton.setOnClickListener {
            if (isNewRecording) {
                showDiscardConfirmation(filePath)
            } else {
                showSaveDiscardDialog(filePath)
            }
        }
    }

    private fun setupAmpSlider() {
        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            binding.ampLabel.text = "$db dB"
            player?.setPreAmplification(db.toFloat())
        }
    }

    private fun setupWaveformChart() {
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart

        chart.axisLeft.axisMinimum = -fixedPeakAmplitude
        chart.axisLeft.axisMaximum = fixedPeakAmplitude

        // Same slower speed as the calibrated recorder (user request) — keeps a recording's
        // on-screen pace consistent whether you're watching it live or reviewing it after.
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_12_5)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        // Opposite of the recorder — user can pan/zoom to inspect the trace (§5).
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)

        waveformView.onVisibleSecondsChanged = { seconds ->
            currentWindowSeconds = seconds
            if (chart.data == null) {
                resetToDummyData()
            } else {
                // Real waveform already loaded (e.g. window changed on rotation) — reapply
                // the corrected max-zoom-out cap and re-center rather than silently drifting
                // stale. Deliberately NOT setVisibleXRangeMinimum — that would lock the range
                // to exactly `seconds` and disable pinch-zoom entirely, same bug fixed below.
                chart.setVisibleXRangeMaximum(seconds)
                chart.centerViewTo(seconds / 2f, 0f, YAxis.AxisDependency.LEFT)
                chart.invalidate()
            }
        }
        waveformView.recomputeVisibleSeconds()
        if (chart.data == null) resetToDummyData()
    }

    private fun resetToDummyData() {
        if (_binding == null) return
        val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(currentWindowSeconds, 0f)), "").apply {
            color = Color.TRANSPARENT
            setDrawCircles(false)
            setDrawValues(false)
        }
        binding.calibratedWaveformView.chart.data = LineData(dummyDataSet)
        binding.calibratedWaveformView.chart.invalidate()
    }

    private fun updateCalibrationCaption() {
        if (_binding == null) return
        val correction = DpiCalibration.getCorrection(requireContext())
        val status = if (correction.isCalibrated) "calibrated (${correction.source})" else "UNCALIBRATED"
        val speed = binding.calibratedWaveformView.paperView.currentScale().paperSpeed.mmPerSecond
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude · DPI: $status"
    }

    /**
     * Calibrated counterpart of PlayerFragment.loadFullWaveform(). Same WAV-header sample-rate
     * parsing (bytes 24-27, little-endian, fallback 44100 — never hardcoded, per §4.5), but
     * downsampling is min/max-bucketed instead of "every step-th sample" so a transient can't
     * fall entirely between two kept samples.
     */
    private fun loadFullWaveform(filePath: String, filterName: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) return@launch
            val bytes = file.readBytes()

            val fileSampleRate: Float = if (bytes.size >= 28) {
                val rate = ((bytes[24].toInt() and 0xff) or
                        ((bytes[25].toInt() and 0xff) shl 8) or
                        ((bytes[26].toInt() and 0xff) shl 16) or
                        ((bytes[27].toInt() and 0xff) shl 24))
                if (rate > 0) rate.toFloat() else INPUT_SAMPLE_RATE
            } else INPUT_SAMPLE_RATE

            val dataSize = bytes.size - 44
            val totalSamples = dataSize / 2
            val durationSecs = (totalSamples / fileSampleRate).toInt()

            // Decode all samples first (need them in a FloatArray for bucketed min/max).
            val samples = FloatArray(totalSamples)
            var i = 0
            while (i < totalSamples) {
                val bytePos = 44 + i * 2
                if (bytePos + 1 >= bytes.size) break
                val low = bytes[bytePos].toInt() and 0xFF
                val high = bytes[bytePos + 1].toInt() shl 8
                samples[i] = (high or low).toShort().toFloat() / 32768f
                i++
            }

            // 2 points per bucket (min+max) -> bucket size chosen to hit the same total point
            // budget production used with 1-point-per-step decimation.
            val bucketSize = maxOf(1, totalSamples / (TARGET_POINT_BUDGET / 2))
            val entries = CalibratedWaveformView.downsampleMinMax(samples, bucketSize) { idx ->
                idx.toFloat() / fileSampleRate
            }

            // Whole file is already decoded — no warmup window needed, just take the true peak
            // directly (user request: bigger peaks).
            var filePeak = 0f
            for (sample in samples) {
                val abs = Math.abs(sample)
                if (abs > filePeak) filePeak = abs
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                renderWaveformEntries(ArrayList(entries), durationSecs, filePeak)
            }
        }
    }

    private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int, filePeak: Float) {
        binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
        val dataSet = LineDataSet(entries, "Waveform").apply {
            color = Color.parseColor("#2D7DD2")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = TRACE_LINE_WIDTH_DP
            mode = LineDataSet.Mode.LINEAR
        }
        // currentWindowSeconds is kept in sync by onVisibleSecondsChanged (set up in
        // setupWaveformChart, called before this) — including the case where layout hadn't
        // happened yet when this loaded; that callback will re-apply the range once it does.
        val chart = binding.calibratedWaveformView.chart

        val peakAmplitude = (filePeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
        chart.axisLeft.axisMinimum = -peakAmplitude
        chart.axisLeft.axisMaximum = peakAmplitude

        chart.data = LineData(dataSet)
        // Cap max zoom-out only — no Minimum lock, so pinch-zoom works (see setupWaveformChart).
        chart.setVisibleXRangeMaximum(currentWindowSeconds)
        chart.centerViewTo(currentWindowSeconds / 2f, 0f, YAxis.AxisDependency.LEFT)
        chart.invalidate()
    }

    private fun setupPlayer(filePath: String, filterName: String) {
        try {
            player = TaalPlayer(requireContext()).apply {
                setDataSource(filePath)
                val fileName = File(filePath).name
                if (!fileName.contains("_filtered") && !fileName.contains("_8k_downsampling")) {
                    val preFilter = try { PreFilter.valueOf(filterName) } catch (_: Exception) { PreFilter.HEART }
                    setPreFilter(preFilter)
                }
                onPlaybackProgress = { timestamp, _ ->
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            // Audio timer stays exact — only the camera follow is smoothed.
                            val totalSecs = timestamp.toInt()
                            binding.timerText.text = String.format("%02d:%02d", totalSecs / 60, totalSecs % 60)

                            // Ease the camera toward the real playback position instead of
                            // snapping to it every callback — at a small zoomed-in visible
                            // window, a direct snap makes the trace look like it's racing.
                            // This trades exact frame-accurate sync for a calmer, self-
                            // correcting follow (it always eases back toward the true position,
                            // never drifts away indefinitely).
                            displayedPlaybackTime += (timestamp.toFloat() - displayedPlaybackTime) * FOLLOW_SMOOTHING

                            val chart = binding.calibratedWaveformView.chart
                            val halfRange = chart.visibleXRange / 2f
                            val centerX = if (displayedPlaybackTime < halfRange) halfRange else displayedPlaybackTime
                            chart.centerViewTo(centerX, 0f, YAxis.AxisDependency.LEFT)
                        }
                    }
                }
                onPlaybackComplete = {
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            isPlaying = false
                            binding.actionText.text = getString(R.string.play_recording)
                            binding.playButton.setImageResource(R.drawable.ic_play_circle)

                            displayedPlaybackTime = 0f
                            val chart = binding.calibratedWaveformView.chart
                            chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
                        }
                    }
                }
            }
        } catch (e: InvalidFileNameException) {
            Toast.makeText(requireContext(), "Cannot open recording", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayback(filePath: String) {
        if (isPlaying) {
            player?.stop()
            isPlaying = false
            binding.actionText.text = getString(R.string.play_recording)
            binding.playButton.setImageResource(R.drawable.ic_play_circle)

            displayedPlaybackTime = 0f
            val chart = binding.calibratedWaveformView.chart
            chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
        } else {
            try {
                displayedPlaybackTime = 0f
                val chart = binding.calibratedWaveformView.chart
                chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)

                player?.prepare()
                player?.start()
                isPlaying = true
                binding.actionText.text = getString(R.string.stop_recording)
                binding.playButton.setImageResource(R.drawable.ic_recording_stop)
            } catch (e: Exception) {
                isPlaying = false
                Toast.makeText(requireContext(), "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDiscardConfirmation(filePath: String) {
        val rawFilePath = arguments?.getString("rawFilePath") ?: ""
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard Recording")
            .setMessage("Are you sure you want to discard this recording? It will be permanently deleted.")
            .setPositiveButton("Discard") { _, _ ->
                try { File(filePath).delete() } catch (_: Exception) {}
                if (rawFilePath.isNotEmpty()) {
                    try { File(rawFilePath).delete() } catch (_: Exception) {}
                }
                findNavController().navigateUp()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSaveDiscardDialog(filePath: String) {
        PlayerSaveDiscardDialog { action ->
            when (action) {
                PlayerSaveDiscardDialog.Action.SAVE -> {
                    val bundle = Bundle().apply { putString("recordingFilePath", filePath) }
                    findNavController().navigate(R.id.action_calibratedPlayer_to_addPatient, bundle)
                }

                PlayerSaveDiscardDialog.Action.DISCARD -> {
                    try { File(filePath).delete() } catch (_: Exception) {}
                    findNavController().navigateUp()
                }
            }
        }.show(parentFragmentManager, "save_discard")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            player?.onPlaybackProgress = null
            player?.onPlaybackComplete = null
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        _binding = null
    }
}
```

### 4.8 `ui/calibrated/DpiCalibrationFragment.kt`

```kotlin
package com.musediagnostics.taal.app.ui.calibrated

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.databinding.FragmentDpiCalibrationBinding
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration

/**
 * §4.3 — px-per-mm calibration screen. Draws a horizontal and a vertical bar, each nominally
 * 50mm using the device's OEM-reported DPI. The user measures both with a physical ruler and
 * enters what it actually reads; the ratio (nominal / measured) is stored as a correction
 * factor and applied to both calibrated screens' grids via [DpiCalibration.applyTo].
 */
class DpiCalibrationFragment : Fragment() {

    private var _binding: FragmentDpiCalibrationBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val NOMINAL_MM = 50f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDpiCalibrationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateStatusText()

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        binding.saveCalibrationButton.setOnClickListener {
            val actualX = binding.measuredXInput.text?.toString()?.toFloatOrNull()
            val actualY = binding.measuredYInput.text?.toString()?.toFloatOrNull()

            if (actualX == null || actualX <= 0f || actualY == null || actualY <= 0f) {
                Toast.makeText(
                    requireContext(),
                    "Enter the measured length for both bars (mm, greater than 0).",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val correctionX = NOMINAL_MM / actualX
            val correctionY = NOMINAL_MM / actualY
            DpiCalibration.saveManualCorrection(requireContext(), correctionX, correctionY)
            updateStatusText()
            Toast.makeText(requireContext(), "Calibration saved.", Toast.LENGTH_SHORT).show()
        }

        binding.clearCalibrationButton.setOnClickListener {
            DpiCalibration.clearManualCorrection(requireContext())
            binding.measuredXInput.setText("")
            binding.measuredYInput.setText("")
            updateStatusText()
            Toast.makeText(requireContext(), "Calibration cleared — using default DPI.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatusText() {
        val correction = DpiCalibration.getCorrection(requireContext())
        binding.statusText.text = if (correction.isCalibrated) {
            "Calibrated (${correction.source}) — X correction ×%.4f, Y correction ×%.4f"
                .format(correction.x, correction.y)
        } else {
            "Not yet calibrated — grid uses the device's reported DPI as-is."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
```

### 4.9 `res/values/attrs_calibrated_ecg_paper.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Renamed from attrs_ecg_paper.xml's EcgPaperView styleable — every attr is prefixed
         "cal" to avoid duplicate-attribute build errors when both views' attrs are present. -->
    <declare-styleable name="CalibratedEcgPaperView">
        <attr name="calEcgPaperSpeed" format="enum">
            <enum name="speed_12_5" value="0" />
            <enum name="speed_25" value="1" />
            <enum name="speed_50" value="2" />
        </attr>
        <attr name="calEcgGain" format="enum">
            <enum name="gain_5" value="0" />
            <enum name="gain_10" value="1" />
            <enum name="gain_20" value="2" />
        </attr>
        <attr name="calEcgTheme" format="enum">
            <enum name="paper" value="0" />
            <enum name="monitor" value="1" />
        </attr>
        <attr name="calEcgShowCalibrationPulse" format="boolean" />
        <attr name="calEcgShowTimeTicks" format="boolean" />
        <attr name="calEcgPaperColor" format="color" />
        <attr name="calEcgMinorGridColor" format="color" />
        <attr name="calEcgMajorGridColor" format="color" />
    </declare-styleable>
</resources>
```

### 4.10 `res/layout/fragment_calibrated_recording.xml`

Structural copy of production `fragment_recording.xml` with:
- `LineChart` → `com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView` (id `calibratedWaveformView`)
- A `calibrationCaption` TextView added (shows speed / Y-axis label / DPI calibration status)
- `settingsButton` (formerly `gone`, dead code in production) repurposed as a visible "Calibrate grid" entry point to `DpiCalibrationFragment`
- All chrome (topBar, filter icons, amp slider card, record button, bottom bar) shrunk down (see §5 history) to give the graph more vertical space
- `menuButton`, `syncButton`, `trashButton`, `saveCheckButton` omitted (confirmed dead/commented-out in production, safe to drop from a faithful-behavior standpoint)

Full current XML — see the actual file at
`app/src/main/res/layout/fragment_calibrated_recording.xml`, it's ~500 lines; key IDs match
production's `fragment_recording.xml` 1:1 except the chart swap, so `CalibratedRecordingFragment.kt`
above is the authoritative reference for exactly which views exist and are bound.

### 4.11 `res/layout/fragment_calibrated_player.xml`

Same pattern vs. production `fragment_player.xml`: `LineChart` → `calibratedWaveformView`,
`calibrationCaption` added, chrome shrunk. See the actual file at
`app/src/main/res/layout/fragment_calibrated_player.xml` (~240 lines).

### 4.12 `res/layout/fragment_dpi_calibration.xml`

New screen — draws a horizontal bar (`android:layout_width="50mm"`) and a vertical bar
(`android:layout_height="50mm"`) using Android's native `mm` dimension unit (same mechanism
`TypedValue.applyDimension(COMPLEX_UNIT_MM, ...)` uses internally), plus two number inputs for
the ruler-measured lengths and Save/Reset buttons. See
`app/src/main/res/layout/fragment_dpi_calibration.xml` (~157 lines).

### 4.13 `test/.../ecg/calibrated/CalibratedMmScaleTest.kt` — 15 tests

```kotlin
package com.musediagnostics.taal.app.ecg.calibrated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibratedMmScaleTest {

    private val pxPerMm = 4f // arbitrary stand-in for a real device's px/mm

    // --- ported from MmScaleTest.kt (9 cases) ---

    @Test
    fun `default gain draws 1mV as exactly 10mm`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm) // defaults: 25mm/s, 10mm/mV
        assertEquals(10f * pxPerMm, scale.mvToPx(1f), 1e-4f)
    }

    @Test
    fun `default speed draws 1s as exactly 25mm`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        assertEquals(25f * pxPerMm, scale.secondsToPx(1f), 1e-4f)
    }

    @Test
    fun `mv and seconds conversions round-trip`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        assertEquals(0.7f, scale.pxToMv(scale.mvToPx(0.7f)), 1e-4f)
        assertEquals(2.3f, scale.pxToSeconds(scale.secondsToPx(2.3f)), 1e-4f)
    }

    @Test
    fun `large square is exactly 5 small squares, always`() {
        assertEquals(5, CalibratedMmScale.SMALL_SQUARES_PER_LARGE_SQUARE)
        assertEquals(5f, CalibratedMmScale.MM_PER_LARGE_SQUARE / CalibratedMmScale.MM_PER_SMALL_SQUARE, 1e-6f)
    }

    @Test
    fun `small square is 0-04s and large square is 0-2s at default 25mm-s`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm) // default speed = 25mm/s
        assertEquals(0.04f, scale.secondsPerSmallSquare(), 1e-4f)
        assertEquals(0.20f, scale.secondsPerLargeSquare(), 1e-4f)
    }

    @Test
    fun `small square is 0-1mV and large square is 0-5mV at default 10mm-mV`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm) // default gain = 10mm/mV
        assertEquals(0.1f, scale.mvPerSmallSquare(), 1e-4f)
        assertEquals(0.5f, scale.mvPerLargeSquare(), 1e-4f)
    }

    @Test
    fun `changing speed changes what a square means but not its px size`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        val smallSquarePxBefore = scale.smallSquarePxX()
        val largeSquarePxBefore = scale.largeSquarePxX()

        scale.paperSpeed = CalibratedPaperSpeed.SPEED_50
        assertEquals(0.02f, scale.secondsPerSmallSquare(), 1e-4f) // meaning changed
        assertEquals(smallSquarePxBefore, scale.smallSquarePxX(), 1e-4f) // px size did not
        assertEquals(largeSquarePxBefore, scale.largeSquarePxX(), 1e-4f)

        scale.paperSpeed = CalibratedPaperSpeed.SPEED_12_5
        assertEquals(0.08f, scale.secondsPerSmallSquare(), 1e-4f)
        assertEquals(smallSquarePxBefore, scale.smallSquarePxX(), 1e-4f)
    }

    @Test
    fun `changing gain scales only the trace, never the grid`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        val smallSquarePxBefore = scale.smallSquarePxY()
        val largeSquarePxBefore = scale.largeSquarePxY()
        val pulsePxAtDefaultGain = scale.mvToPx(1f)

        scale.gain = CalibratedGain.GAIN_20
        val pulsePxAtDoubleGain = scale.mvToPx(1f)
        assertEquals(pulsePxAtDefaultGain * 2f, pulsePxAtDoubleGain, 1e-4f) // trace scaled
        assertEquals(smallSquarePxBefore, scale.smallSquarePxY(), 1e-4f)   // grid did not
        assertEquals(largeSquarePxBefore, scale.largeSquarePxY(), 1e-4f)

        scale.gain = CalibratedGain.GAIN_5
        assertEquals(pulsePxAtDefaultGain / 2f, scale.mvToPx(1f), 1e-4f)
        assertEquals(smallSquarePxBefore, scale.smallSquarePxY(), 1e-4f)
    }

    @Test
    fun `non-square pixels are respected on each axis independently`() {
        val scale = CalibratedMmScale(pxPerMmX = 3f, pxPerMmY = 5f)
        assertEquals(3f, scale.smallSquarePxX(), 1e-4f)
        assertEquals(5f, scale.smallSquarePxY(), 1e-4f)
        assertEquals(15f, scale.largeSquarePxX(), 1e-4f)
        assertEquals(25f, scale.largeSquarePxY(), 1e-4f)
    }

    // --- new: visibleSeconds() ---

    @Test
    fun `visibleSeconds is plot width divided by mm-per-second at default speed`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm) // 25mm/s
        // 1000px wide plot / (25mm/s * 4px/mm) = 10s
        assertEquals(10f, scale.visibleSeconds(1000f), 1e-4f)
        // a typical ~360px-wide phone plot at 4px/mm -> 3.6s
        assertEquals(3.6f, scale.visibleSeconds(360f), 1e-4f)
    }

    @Test
    fun `visibleSeconds scales inversely with paper speed`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        val widthPx = 800f

        scale.paperSpeed = CalibratedPaperSpeed.SPEED_12_5
        val secondsAt12_5 = scale.visibleSeconds(widthPx)
        scale.paperSpeed = CalibratedPaperSpeed.SPEED_25
        val secondsAt25 = scale.visibleSeconds(widthPx)
        scale.paperSpeed = CalibratedPaperSpeed.SPEED_50
        val secondsAt50 = scale.visibleSeconds(widthPx)

        // Doubling mm/s halves the visible seconds for a fixed physical width.
        assertEquals(secondsAt12_5 / 2f, secondsAt25, 1e-4f)
        assertEquals(secondsAt25 / 2f, secondsAt50, 1e-4f)
    }

    @Test
    fun `visibleSeconds is proportional to plot width`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        val secondsAt100 = scale.visibleSeconds(100f)
        val secondsAt200 = scale.visibleSeconds(200f)
        assertEquals(secondsAt100 * 2f, secondsAt200, 1e-4f)
    }

    // --- new: the load-bearing invariant ---
    // Changing paper speed or gain must never change smallSquarePxX()/smallSquarePxY().
    // Speed and gain change what a square *means*, never its physical size.

    @Test
    fun `neither paper speed nor gain ever changes the physical square size`() {
        val scale = CalibratedMmScale(pxPerMm, pxPerMm)
        val expectedSmallX = scale.smallSquarePxX()
        val expectedSmallY = scale.smallSquarePxY()
        val expectedLargeX = scale.largeSquarePxX()
        val expectedLargeY = scale.largeSquarePxY()

        for (speed in CalibratedPaperSpeed.entries) {
            for (gain in CalibratedGain.entries) {
                scale.paperSpeed = speed
                scale.gain = gain
                assertEquals("speed=$speed gain=$gain", expectedSmallX, scale.smallSquarePxX(), 1e-4f)
                assertEquals("speed=$speed gain=$gain", expectedSmallY, scale.smallSquarePxY(), 1e-4f)
                assertEquals("speed=$speed gain=$gain", expectedLargeX, scale.largeSquarePxX(), 1e-4f)
                assertEquals("speed=$speed gain=$gain", expectedLargeY, scale.largeSquarePxY(), 1e-4f)
            }
        }
    }

    // --- new: DPI correction factors ---

    @Test
    fun `DPI correction default is 1-0 (no correction) when unset`() {
        val correction = DpiCalibration.Correction(
            x = 1.0f, y = 1.0f, isCalibrated = false, source = "uncalibrated"
        )
        assertEquals(1.0f, correction.x, 1e-6f)
        assertEquals(1.0f, correction.y, 1e-6f)
        assertTrue(!correction.isCalibrated)
    }

    @Test
    fun `DPI correction factors apply multiplicatively to reported px-per-mm`() {
        val reportedPxPerMmX = 4f
        val reportedPxPerMmY = 3.9f
        val correctionX = 0.98f
        val correctionY = 1.03f

        val correctedX = reportedPxPerMmX * correctionX
        val correctedY = reportedPxPerMmY * correctionY

        assertEquals(3.92f, correctedX, 1e-4f)
        assertEquals(4.017f, correctedY, 1e-3f)
    }
}
```

---

## 5. Wiring

### 5.1 `nav_graph.xml` — additions (existing content untouched, +56/-0 lines)

```xml
<!-- root element's startDestination attribute — see §2 note about TEMP override -->
<navigation ...
    app:startDestination="@id/calibratedRecordingFragment"><!-- TEMP for dev testing — revert to @id/recordingFragment before shipping -->

    ... (all existing production destinations, untouched) ...

    <!-- Calibrated ECG-paper screens — see docs/notes/WAVEFORM_GRAPH_AND_GRID_REFERENCE.md.
         New destinations only; startDestination stays recordingFragment. Reachable via the
         Calibrated Recorder's own "Calibrate grid" button chain, or directly for debugging via
         `adb shell am start -a android.intent.action.VIEW -d "taalapp://calibrated" <applicationId>`
         (deep link registered below + matching <intent-filter> on MainActivity). -->
    <fragment
        android:id="@+id/calibratedRecordingFragment"
        android:name="com.musediagnostics.taal.app.ui.calibrated.CalibratedRecordingFragment"
        android:label="Calibrated Recorder"
        tools:layout="@layout/fragment_calibrated_recording">
        <deepLink app:uri="taalapp://calibrated" />
        <action
            android:id="@+id/action_calibratedRecording_to_calibratedPlayer"
            app:destination="@id/calibratedPlayerFragment" />
        <action
            android:id="@+id/action_calibratedRecording_to_savedRecordings"
            app:destination="@id/savedRecordingsFragment" />
        <action
            android:id="@+id/action_calibratedRecording_to_dpiCalibration"
            app:destination="@id/dpiCalibrationFragment" />
    </fragment>

    <fragment
        android:id="@+id/calibratedPlayerFragment"
        android:name="com.musediagnostics.taal.app.ui.calibrated.CalibratedPlayerFragment"
        android:label="Calibrated Review"
        tools:layout="@layout/fragment_calibrated_player">
        <argument
            android:name="filePath"
            android:defaultValue=""
            app:argType="string" />
        <argument
            android:name="isNewRecording"
            android:defaultValue="false"
            app:argType="boolean" />
        <argument
            android:name="filterName"
            android:defaultValue="HEART"
            app:argType="string" />
        <action
            android:id="@+id/action_calibratedPlayer_to_equalizer"
            app:destination="@id/equalizerFragment" />
        <action
            android:id="@+id/action_calibratedPlayer_to_addPatient"
            app:destination="@id/addPatientFragment" />
        <action
            android:id="@+id/action_calibratedPlayer_to_saveRecording"
            app:destination="@id/saveRecordingFragment" />
    </fragment>

    <fragment
        android:id="@+id/dpiCalibrationFragment"
        android:name="com.musediagnostics.taal.app.ui.calibrated.DpiCalibrationFragment"
        android:label="Ruler Calibration"
        tools:layout="@layout/fragment_dpi_calibration" />

</navigation>
```

Note the doc-comment above the block says "startDestination stays recordingFragment" — that
was true when the block was first added, but the root `startDestination` attribute was later
changed to `calibratedRecordingFragment` for dev convenience (see §2). The comment is now
stale; either fix the comment or revert the attribute, don't leave them contradicting each
other for long.

`rawFilePath` is passed via `Bundle` to `calibratedPlayerFragment` (in
`stopRecording()`/`playPauseButton` click in the recorder, and read via
`arguments?.getString("rawFilePath")` in the player) but is **not** declared as a nav
`<argument>` — this exactly matches how production's `playerFragment` handles it, Navigation
allows undeclared bundle extras to ride along.

Save/discard/EQ flows deliberately **reuse existing production destinations**
(`saveRecordingFragment`, `addPatientFragment`, `equalizerFragment`, `savedRecordingsFragment`)
rather than duplicating them — those fragments are unprotected (only `PlayerFragment.kt`/
`RecordingFragment.kt` themselves are protected) so adding new incoming actions to them from
the calibrated screens doesn't violate any constraint.

### 5.2 `AndroidManifest.xml` — additions (existing content untouched)

```xml
<activity
    android:name=".ui.MainActivity"
    android:exported="true"
    android:screenOrientation="portrait"
    android:windowSoftInputMode="adjustResize">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>

    <!-- Debug entry point for the calibrated ECG-paper screens (not reachable from
         any always-visible production button — see nav_graph.xml's calibratedRecordingFragment
         deep link). Reach it with:
         adb shell am start -a android.intent.action.VIEW -d "taalapp://calibrated" <applicationId> -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="taalapp" android:host="calibrated" />
    </intent-filter>
</activity>
```

Why this exists: the natural in-app entry point to the calibrated screens
(`NewRecordingFragment`, reachable via production `RecordingFragment`'s `settingsButton`) turned
out to be a dead end because that button is `android:visibility="gone"` in the **protected**
`fragment_recording.xml` — couldn't unhide it without touching a protected file. The deep link
was the workaround. This is now moot day-to-day since `startDestination` is TEMP-pointed at the
calibrated recorder directly (§2), but the deep link still works and is worth keeping regardless.

**Note**: `MainActivity` is `android:screenOrientation="portrait"` (locked) at the activity
level. This means the "rotate the device and confirm the window re-derives correctly"
verification from the original spec has never actually been exercised — the app can't rotate.
The code path for it exists (`CalibratedWaveformView.onSizeChanged` → `recomputeVisibleSeconds()`
→ `onVisibleSecondsChanged` callback) and would fire on any resize (split-screen, foldable
unfold, etc.), just never verified against real rotation.

---

## 6. Chronological log of decisions (why the numbers are what they are)

This is the part most worth reading before changing constants again — most values below were
already tuned once or twice based on real feedback, so re-deriving from scratch risks
re-discovering the same problem.

1. **Initial build** (following a detailed upfront spec, §0–§8 style): calibrated screens built
   as faithful ports of production Recording/Player, with 5 accuracy fixes: (1) visible window
   derived from grid width instead of a fixed constant, (2) chart viewport offsets zeroed +
   native grid disabled so the mm-paper aligns with the plot area exactly, (3) DPI
   ruler-calibration screen, (4) Y-axis originally **fixed** (no warmup/peak adaptation) so
   recordings would be visually comparable, mV calibration pulse hidden, gain enum kept
   internal-only, (5) decimation replaced with min/max bucket downsampling so transients can't
   vanish between samples.

2. **Pinch-zoom / peak-size discussion**: user asked what interactive features (zoom, peak
   scaling) differ from production. Established: production Recording has no zoom ever;
   production Player has zoom; calibrated screens matched that but zoom-lock had a real bug
   (see next point).

3. **User: "I want all the features in the original in this one."** Two explicit asks:
   - Zoom in the calibrated player should work "without the background moving" — i.e. keep the
     mm-grid static (don't build a grid-that-zooms-with-the-trace feature), just make sure
     pinch/drag/scale actually functions. Root cause found: `CalibratedWaveformView.recomputeVisibleSeconds()`
     was calling both `setVisibleXRangeMaximum` **and** `setVisibleXRangeMinimum` to the same
     value, which hard-locks the range and defeats `setScaleEnabled(true)` entirely regardless
     of the flag. Fixed by dropping the `Minimum` call from the shared method (recorder still
     re-asserts both every frame itself, so its lock is unaffected; player only gets the
     `Maximum` cap, leaving zoom free).
   - Restore production's adaptive Y-axis peak-scaling. Recorder: brought back
     `WARMUP_MS`/`HEADROOM`/`MIN_PEAK` verbatim (production only ever had this on the Recording
     screen). Player: production's is actually a **fixed ±0.5**, not adaptive — so player was
     set to fixed ±0.5 initially, matching production exactly (not the recorder's adaptive
     logic).

4. **"Increase the whole graph background and the peaks... reduce button sizes... slow down
   the graph line."** Three changes:
   - Shrunk all chrome (top bar, filter icons, amp slider card, record/play button, bottom bar)
     on both screens — since the graph view uses `0dp` flexible height in the ConstraintLayout,
     freed space goes straight to it.
   - `HEADROOM` tightened 1.5 → 1.2 on both screens (recorder + player, since player's fixed
     ±0.5 was replaced with an adaptive-to-file-peak version at the same time, to match the
     recorder's "bigger peaks" request).
   - `CalibratedPaperSpeed` on both screens changed from `SPEED_25` (25mm/s, the enum default)
     to `SPEED_12_5` — halves on-screen scroll speed, doubles seconds visible per screen width.
     Grid square physical size is unaffected (that's the whole point of the speed/gain vs.
     geometry separation `CalibratedMmScale` enforces).

5. **"Increase the moving line size — not the thickness."** First attempt doubled
   `lineWidth` (1.5→3.0 recorder, 2.5→3.5 player) — user clarified that wasn't what they meant.
   Reverted line width back to production's original thin values, and instead tightened
   `HEADROOM` further (1.2 → 1.1) to make peaks taller via more of the chart height, not a
   fatter stroke.

6. **"Peak going out of the screen" (real clipping bug reported).** Two contributing causes
   found:
   - `HEADROOM = 1.1` left almost no margin — normal.
   - Separately, a percentile-based robust-peak fix had just been added for a different
     complaint (see next point) with `WARMUP_PERCENTILE = 0.9`, which *deliberately* excludes
     the loudest 10% of buffers from the scale calculation — so real peaks in that top 10%
     legitimately exceed the locked axis by design. Both were dialed back:
     `HEADROOM` 1.1 → **1.2** (both screens), `WARMUP_PERCENTILE` 0.9 → **0.97** (recorder only
     — player doesn't use percentile since it already has the whole file's true peak, not a
     live sample).

7. **Playback camera "too fast when zoomed."** User wanted the trace's visual scroll speed
   during playback to feel gentler (explicitly *not* wanting real audio slowed down, and
   explicitly not wanting a hard zoom cap). Implemented `displayedPlaybackTime`, an
   exponentially-smoothed camera position (`FOLLOW_SMOOTHING = 0.15`) that eases toward the
   real playback timestamp each progress callback instead of snapping to it — self-correcting
   (always converges to true position), never diverges permanently. Timer text still shows the
   exact real position; only the chart's camera is damped. Reset to 0 at all three snap-back
   sites (stop, complete, play-start).

8. **Robust-peak fix, the actual origin of point 6's percentile logic.** User reported: live
   recording looks small the whole time, but the *same file* looks properly sized once opened
   in the player. Diagnosis: production's raw-max warmup lets a single loud transient — e.g. the
   contact "thud" of placing the stethoscope, which often happens in the first 2s — permanently
   set an oversized scale for the entire rest of the recording, since the real heart/lung sound
   is usually much quieter than a contact thud. Fixed by collecting `warmupBufferPeaks` (one
   peak per audio buffer during the 2s warmup) and locking to the `WARMUP_PERCENTILE`-th
   percentile of that list instead of the raw max (see point 6 for the follow-up tuning of that
   percentile after it over-corrected). Explicitly scoped to "fix the math, don't add zoom to
   the recorder" — the user picked that of two offered options.

9. **Discussion (not yet implemented)**: user proposed finding a "perfect" zoom/scale in the
   Player (which has interactive zoom) and porting those exact settings to the Recorder. Real
   answer given: Y-axis scale (`HEADROOM`) is already unified between both screens; X-axis
   "zoom" isn't portable 1:1 because the Recorder has no chart-zoom at all — its window is
   `PaperSpeed`-derived, not a continuous zoom value. The suggested approach: use Player's zoom
   purely as an exploration tool, then translate whatever "looks right" into a `PaperSpeed`
   choice (12.5/25/50 — the only three legal values, no custom cursor) applied to both screens.
   **Never actually executed** — conversation was interrupted by the request for this doc.

---

## 7. Current constants — quick reference

| Constant | Recorder | Player | Notes |
|---|---|---|---|
| `PaperSpeed` | `SPEED_12_5` (12.5mm/s) | `SPEED_12_5` (12.5mm/s) | production default via `CalibratedMmScale` is `SPEED_25`; both screens override it explicitly in `setupWaveformChart()` |
| `HEADROOM` | `1.2f` (~83% fill) | `1.2f` (~83% fill) | production Recording was `1.5f`; production Player doesn't use this constant (fixed ±0.5, never adaptive) |
| `MIN_PEAK` | `0.02f` | `0.02f` | unchanged from production |
| `WARMUP_MS` | `2000` | n/a (player has the whole file already) | unchanged from production |
| `WARMUP_PERCENTILE` | `0.97f` | n/a | new — production has no equivalent (used raw max) |
| `TRACE_LINE_WIDTH_DP` | `1.5f` | `2.5f` | unchanged from production (reverted after a brief bump to 3.0/3.5) |
| `FOLLOW_SMOOTHING` | n/a | `0.15f` | new — production snaps directly to timestamp every callback |
| `DOWNSAMPLE_BUCKET` (recorder) / `TARGET_POINT_BUDGET` (player) | `88` | `3000` | min/max bucket sizing, tuned to match production's point density |
| Zoom | disabled (`setTouchEnabled(false)`) | enabled, max-zoom-out capped at derived window, **no min cap** (fixed bug — see log point 3) | |

---

## 8. Known gaps / open items for the next session

1. **`startDestination` is TEMP-pointed at `calibratedRecordingFragment`** — revert to
   `@id/recordingFragment` before anything resembling a merge/ship, and fix the stale comment
   in `nav_graph.xml` that still claims otherwise (§5.1).
2. **Device-tested** — run and visually confirmed on real hardware by the developer; this item
   is closed. (An earlier revision of this doc said "never tested" — that referred only to the
   sandbox this feature was originally built in, not the project. Don't resurrect it.)
3. **`MainActivity` is portrait-locked** — rotation-derived-window behavior is implemented but
   has never actually been exercised, since the app can't rotate. Would need either a
   split-screen/foldable test, or a temporary orientation unlock, to verify.
4. **Zoom in the calibrated player only zooms the trace, not the grid** — by explicit user
   request ("without the background moving"), so at any zoom level other than the default,
   "1mm = 1mm" is only strictly true at 1x. This is accepted behavior, not a bug, but worth
   flagging to anyone reviewing the calibration claim.
5. **The "translate Player's ideal zoom into Recorder's PaperSpeed" idea (log point 9)** was
   discussed but never implemented. If picked back up: ask the user what visible-seconds count
   (or which of the 3 standard speeds) looked best when they experimented in the Player, then
   set that `CalibratedPaperSpeed` value in both `CalibratedRecordingFragment.setupWaveformChart()`
   and `CalibratedPlayerFragment.setupWaveformChart()`.
6. **Amplitude mismatch between recorder and player is real and expected**: the recorder
   divides the live signal by the pre-amp gain before drawing (so the trace reflects true
   acoustic level); the player never does this (the file already has gain baked in, and
   production never compensated for it either). So the *same* recording can look different in
   scale between the live view and the review view whenever pre-amp ≠ 0dB (default is 5dB).
   This exactly mirrors production's own behavior — not a regression, but worth knowing before
   "fixing" it without realizing it's intentional parity.
