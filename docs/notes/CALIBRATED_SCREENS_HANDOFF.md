# Calibrated ECG-Paper Screens — Handoff / Status Doc

> **Purpose of this file**: a self-contained snapshot of the "Calibrated Recorder / Calibrated
> Player" feature — what it is, every file involved, the exact current code, how it's wired into
> the app, and the fu/resumell history of tuning decisions made in chat (with the reasoning, so a fresh
> conversation doesn't have to re-derive it). Paste this whole file into a new Claude chat to
> continue work with full context.
>
> Written 2026-08-18/19. Updated 2026-08-20 with the per-device graph calibration feature
> (pinch-to-zoom + Apply/Reset, `GraphCalibration.kt`) — see **§9** for that feature's full
> story, including the before/in-between/now comparison table, and **§4.6/§4.7** for the
> up-to-date full source of both fragments (spliced from disk, not retyped).
>
> If code and this doc disagree, trust the code — but update this file to match before moving
> on, since its whole value is being accurate.

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
- `./gradlew :app:testDebugUnitTest` — **passes**, 38/38 tests green (15 original calibrated-
  screens tests + `CalibratedWaveformViewTest` + `GraphCalibrationTest`, plus all pre-existing
  app tests untouched).
- **`FIXED_FULL_SCALE` is currently `0.10f`** (both fragments, must stay identical — see §9's
  table). It has changed several more times since this doc's §6/§7 below were written; §9 is
  the up-to-date source for current tuning values, §6/§7 are a historical record only.
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
    GraphCalibration.kt           — NEW (§9) — per-device Peak Size/Time Zoom override,
                                     persisted in SharedPreferences, read/written by both screens

app/src/main/java/com/musediagnostics/taal/app/ui/calibrated/
    CalibratedRecordingFragment.kt
    CalibratedRecordingViewModel.kt   (also declares CalibratedRecordingUiState enum)
    CalibratedPlayerFragment.kt
    DpiCalibrationFragment.kt         — ruler calibration screen

app/src/main/res/layout/
    fragment_calibrated_recording.xml
    fragment_calibrated_player.xml    — §9: Save button repurposed to `applyButton`, slider
                                         panel replaced by a compact status+Reset row
    fragment_dpi_calibration.xml

app/src/main/res/values/
    attrs_calibrated_ecg_paper.xml    — renamed attrs (calEcgPaperSpeed etc.) to avoid
                                         duplicate-attribute collision with attrs_ecg_paper.xml

app/src/test/java/com/musediagnostics/taal/app/ecg/calibrated/
    CalibratedMmScaleTest.kt          — 15 unit tests
    CalibratedWaveformViewTest.kt     — 10 unit tests (bucket-size derivation, incl. Fix D)
    GraphCalibrationTest.kt           — NEW (§9) — 4 unit tests (data-shape only; getOverride/
                                         saveOverride/clearOverride need a real Context, which
                                         this plain-JVM test module doesn't have — see §9)

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
import com.musediagnostics.taal.app.ecg.calibrated.GraphCalibration
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
 * Fix A (reversing an earlier chat decision to restore production's adaptive warmup/peak
 * Y-axis): the axis is a single fixed full-scale, set once at chart setup and never touched
 * again for the rest of the session. Frame-measurement of a real recording showed the adaptive
 * scheme was the root cause of "graph is unstable / noisy / too small" — a loud transient in
 * the 2s warmup window (e.g. the stethoscope contact thud) could permanently lock an oversized
 * scale, and the axis could jump mid-recording. A fixed axis trades per-recording optimality
 * for stability: this is deliberately how Kardia's own ECG display behaves (fixed 10mm/mV,
 * never rescales) — see FIXED_FULL_SCALE's doc for the tuning methodology.
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

    // Fix C — derived so ~one min/max bucket lands per horizontal pixel at the current paper
    // speed, instead of a fixed constant. Recomputed only while idle (see onVisibleSecondsChanged
    // below) and held frozen for the whole recording session: the recorder mutates
    // LineDataSet.values in place against a monotonic sample-counter X axis, so changing the
    // bucket size mid-recording would space already-plotted points inconsistently with new ones.
    private var sessionBucketSize = DOWNSAMPLE_BUCKET_FALLBACK

    // Per-device calibration override (Peak Size / Time Zoom panel, set from the Player) —
    // null means "use the built-in FIXED_FULL_SCALE/grid-derived defaults." Re-read in
    // onResume() so returning from the Player after Apply/Reset reflects immediately.
    private var calibrationOverride: GraphCalibration.Override? = null

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f

        // Pre-layout fallback only (pxPerMmX not yet known) — see deriveBucketSize/sessionBucketSize.
        // 2 points per bucket (min+max) at this bucket size ≈ same point budget as production's
        // "1 in 44 samples" decimation (44100 / 88 * 2 ≈ 1002 pts/sec), but peaks are preserved.
        private const val DOWNSAMPLE_BUCKET_FALLBACK = 88

        // Fix A — fixed display full-scale, in normalized sample units (-1..+1). The axis is
        // ±FIXED_FULL_SCALE and NEVER changes during a session (no warmup, no re-expansion).
        // Do NOT use ±1.0 — typical PCG content peaks well below full digital scale, so ±1.0
        // would make the trace smaller than before, not bigger.
        //
        // 0.30 is the task's given starting value, derived from the measured fact that the
        // player's existing filePeak×1.2 lock already lands at the visually-correct ~37% fill
        // (confirmed against Kardia's 38.6%). This constant has NOT been re-derived from a
        // logged median of real per-file peaks on this pass — that requires running the
        // temporary-logging step in CalibratedPlayerFragment against a set of real recordings
        // on device, which needs hardware this session doesn't have direct access to. Flagged
        // in this change's report; whoever has the device should confirm/tune this value next
        // using the same three-step method (log real peaks, take the median, hardcode it here).
        //
        // Clipping when a recording is unusually loud is expected and correct with a fixed
        // axis — Kardia does the same. Do not add a clipping indicator or re-expand the axis
        // in response.
        //
        // 0.30 -> 0.15 -> 0.013 (measured, see CalZoomTuning log analysis in chat history) ->
        // 0.0065 -> 0.013 -> 0.30 (temporary check) -> 0.013 -> 0.30 -> 0.20 -> 0.10 (user:
        // "a bit bigger" still). Still deliberately NOT re-optimized for any one device —
        // per-device calibration (pinch + Apply, see GraphCalibration) is what does that now;
        // this is just the small, neutral default every device starts from. Must stay
        // identical to CalibratedPlayerFragment's copy of this constant (Fix B parity).
        private const val FIXED_FULL_SCALE = 0.10f

        // Turned back on (user request) — the pre-amp slider was visibly resizing the live
        // trace as it moved, which is wrong: the slider should only change loudness. Fix B had
        // set this false for live/review parity (recorder and player showing the same file at
        // the same size), but that parity was already only ever exact at the default 5dB —
        // the player has no record of what dB was used for a given file, so a recording made
        // at a non-default dB will still show at a different absolute size when reviewed later
        // regardless of this flag. Fixing that fully needs pre-amp stored as file metadata,
        // which is a separate, bigger change. See onProgressUpdate's comment.
        private const val COMPENSATE_PREAMP_IN_DISPLAY = true

        // Trace stroke width in dp. Bumped up from production's 1.5dp (user request) — a fixed
        // stroke width stays the same physical thickness at any zoom level, but reads as
        // relatively thinner once FIXED_FULL_SCALE/pinch make the peaks bigger, so it needed to
        // grow a bit too to hold up against a larger trace.
        private const val TRACE_LINE_WIDTH_DP = 2.0f
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

        // Fix A baseline, overridden below if this device has a saved calibration (set from
        // the Player's Peak Size / Time Zoom panel) — set once, never touched again for the
        // rest of the session outside onResume()'s re-read.
        calibrationOverride = GraphCalibration.getOverride(requireContext())
        val effectiveYFullScale = calibrationOverride?.yFullScale ?: FIXED_FULL_SCALE
        chart.axisLeft.axisMinimum = -effectiveYFullScale
        chart.axisLeft.axisMaximum = effectiveYFullScale

        // 50mm/s — the fastest standard clinical ECG speed. Was SPEED_25; a live-tuning
        // session showed the user pinch-zooming in on the Player's time axis until only
        // ~0.87s was visible (~83mm/s equivalent) before it looked right — beyond even 50mm/s,
        // but 50 is the closest we get without leaving standard clinical speeds behind. Pinch-
        // zoom is still there in the Player for the extra step to ~0.87s if needed. Both
        // screens must be changed together or a recording looks different live vs. in review.
        // Grid square physical size is unaffected — only what a square means in time changes.
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_50)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        waveformView.onVisibleSecondsChanged = { seconds ->
            // A saved Time Zoom override replaces the grid-derived default outright — see
            // CalibratedPlayerFragment's identical substitution.
            currentWindowSeconds = calibrationOverride?.visibleSeconds ?: seconds
            // Don't clobber an in-progress recording's dataset on rotation/resize — only
            // reset to the dummy grid-holder dataset when there's no real data yet.
            // updateWaveform() re-enforces the range every callback while recording is live.
            if (waveformDataSet == null) {
                // Fix C — only re-derive the bucket size while idle; a session in progress
                // must keep using whatever was frozen when it started (see sessionBucketSize doc).
                //
                // Derived from the *effective* window (currentWindowSeconds, which already
                // folds in any calibration override above), not from paper speed alone —
                // deriveBucketSize() assumes the grid's native, un-overridden pixel density,
                // so a saved override that narrows the window (more zoomed in) left the bucket
                // sized for the wider native view: each min/max pair then got stretched across
                // several pixels instead of one, which is what "noisy/jagged" turned out to be
                // (same class of bug Fix D already fixed for the Player's pinch-zoom).
                val plotWidthPx = waveformView.chart.width.toFloat()
                val visibleSampleCount = currentWindowSeconds * INPUT_SAMPLE_RATE
                val derived = if (plotWidthPx > 0f) {
                    CalibratedWaveformView.deriveBucketSizeForVisibleRange(visibleSampleCount, plotWidthPx)
                } else {
                    CalibratedWaveformView.deriveBucketSize(
                        INPUT_SAMPLE_RATE,
                        waveformView.paperView.currentScale().paperSpeed.mmPerSecond,
                        waveformView.paperView.currentScale().pxPerMmX
                    )
                }
                if (derived > 0) sessionBucketSize = derived
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
        // Fix E — no longer auto-scaled (Fix A removed the warmup/peak lock); axis is fixed.
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude (fixed) · DPI: $status"
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
                    putInt("preAmpDb", viewModel.preAmpDb.value ?: 5)
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

                        // Undo pre-amp gain before drawing (COMPENSATE_PREAMP_IN_DISPLAY, on by
                        // default) — the trace reflects true acoustic level, not the amplified
                        // WAV level, so the pre-amp slider only changes loudness, never the
                        // live graph's size. See that constant's doc for the tradeoff this
                        // re-opens with the player (which has no way to undo gain it doesn't
                        // know was applied to a given saved file).
                        val preAmpDb = viewModel.preAmpDb.value ?: 5
                        val preAmpGain = Math.pow(10.0, preAmpDb / 20.0).toFloat()
                        val displayData = if (COMPENSATE_PREAMP_IN_DISPLAY && preAmpGain > 1.001f) {
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
                // So the Player can undo this recording's actual pre-amp gain and show the
                // same true-acoustic-level trace the recorder showed live — see
                // COMPENSATE_PREAMP_IN_DISPLAY's doc for why the recorder alone isn't enough.
                putInt("preAmpDb", viewModel.preAmpDb.value ?: 5)
            }
            findNavController().navigate(R.id.action_calibratedRecording_to_calibratedPlayer, bundle)
        }
    }

    /**
     * Calibrated counterpart of RecordingFragment's V7 updateWaveform(). Differences from
     * production:
     *  - X window (`currentWindowSeconds`) is derived from the grid, not a WINDOW_SECONDS
     *    constant.
     *  - Downsampling is min/max bucketed via CalibratedWaveformView, not "every Nth sample",
     *    so a transient can't fall entirely between two kept samples.
     *  - Y-axis is fixed (Fix A) — no warmup, no peak lock, no per-buffer peak tracking. The
     *    axis was set once in setupWaveformChart() and is never touched here.
     */
    private fun updateWaveform(data: FloatArray) {
        if (_binding == null || !isAdded) return
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart

        val bufferStartSample = totalSamplesProcessed
        val newEntries = CalibratedWaveformView.downsampleMinMax(data, sessionBucketSize) { j ->
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

    override fun onResume() {
        super.onResume()

        checkDeviceConnectionStatus()
        if (taalRecorder == null) {
            resetToIdle()
        }
        viewModel.setPreAmp(5)
        binding.ampSlider.value = 5f
        binding.ampLabel.text = "5 dB"

        // Re-read the calibration override in case the Player's panel changed it since this
        // fragment was created (e.g. Apply/Reset pressed there, then navigated back here).
        calibrationOverride = GraphCalibration.getOverride(requireContext())
        val chart = binding.calibratedWaveformView.chart
        val effectiveYFullScale = calibrationOverride?.yFullScale ?: FIXED_FULL_SCALE
        chart.axisLeft.axisMinimum = -effectiveYFullScale
        chart.axisLeft.axisMaximum = effectiveYFullScale
        // Re-fires onVisibleSecondsChanged, which now reads the refreshed override for X.
        binding.calibratedWaveformView.recomputeVisibleSeconds()
        chart.invalidate()

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
import android.view.MotionEvent
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
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentCalibratedPlayerBinding
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedPaperSpeed
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration
import com.musediagnostics.taal.app.ecg.calibrated.GraphCalibration
import com.musediagnostics.taal.app.ui.player.PlayerSaveDiscardDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * record time, same as production.
 *
 * Fix A/B: Y-axis is [FIXED_FULL_SCALE], the same constant CalibratedRecordingFragment uses,
 * set once in setupWaveformChart() and never touched again — no per-file peak adaptation. Two
 * screens sharing one fixed constant is what makes live and review render the same recording
 * identically (the acceptance test for Fix B); a per-file peak lock (this screen's earlier
 * approach) can't guarantee that against a recorder that isn't looking at the whole file.
 *
 * Touch/drag/scale are enabled, same as production; the visible-range lock only caps the
 * *maximum* zoom-out (not the minimum), so pinch-zoom actually works — the mm-grid behind the
 * chart is a separate, static view and intentionally does not zoom with the trace (per user
 * request: zoom the trace, don't move the background).
 *
 * The camera-follow during playback is smoothed (see [displayedPlaybackTime]/FOLLOW_SMOOTHING),
 * not a direct snap to the real playback position — a direct snap looked "too fast" once
 * pinch-zoom made the visible window small (user request). Audio itself always plays at true
 * speed/pitch; only the visual follow is damped.
 *
 * Fix D: the min/max bucket used to draw the trace is re-derived from the *currently visible*
 * X range whenever a pinch/drag gesture ends (see [rebucketForCurrentZoom]), not fixed at
 * load time. Frame-by-frame inspection during zoomed playback showed a regular synthetic
 * sawtooth instead of a waveform — the load-time bucket (sized for the full 1x view) was being
 * stretched wide by zoom instead of showing real samples at the new density. The decoded file
 * ([decodedSamples]) is kept in memory and re-bucketed from; the WAV is never re-read.
 */
class CalibratedPlayerFragment : Fragment() {

    private var _binding: FragmentCalibratedPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    private var currentWindowSeconds = 4f

    // Smoothed camera-follow position during playback — see onPlaybackProgress. Reset to 0
    // wherever the chart snaps back to its start-centered framing.
    private var displayedPlaybackTime = 0f

    // Fix D — kept in memory for zoom-driven re-bucketing (rebucketForCurrentZoom). Never
    // re-read from disk after the initial load.
    private var decodedSamples: FloatArray? = null
    private var decodedSampleRate: Float = INPUT_SAMPLE_RATE
    private var waveformDataSet: LineDataSet? = null // persistent ref, mutated in place on re-bucket
    private var lastAppliedBucketSize = -1
    private var rebucketJob: Job? = null

    // Per-device calibration override (set by pinching the graph, then pressing applyButton) —
    // null means "use the built-in FIXED_FULL_SCALE/grid-derived defaults." Read once at setup,
    // kept in sync by the Apply/Reset handlers so a rotation shortly after either reflects the
    // latest state without needing the fragment recreated.
    private var calibrationOverride: GraphCalibration.Override? = null

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val TARGET_POINT_BUDGET = 3000 // same total-point budget as production's maxPoints
        // Fix A/B — must be kept identical to CalibratedRecordingFragment.FIXED_FULL_SCALE, or
        // the same recording renders at different scales live vs. in review. See that
        // fragment's doc comment for the tuning methodology and this value's history — small,
        // un-tuned baseline (user request), since per-device calibration (pinch + Apply) is now
        // how each phone gets sized correctly, not this constant.
        private const val FIXED_FULL_SCALE = 0.10f
        // Camera-follow smoothing (user request: playback feels "too fast" when zoomed in).
        // Fraction of the remaining gap to the real playback position closed per progress
        // callback — lower = gentler/slower-feeling follow, 1.0 = instant snap (old behavior).
        private const val FOLLOW_SMOOTHING = 0.15f
        // Trace stroke width in dp. Bumped up further (user request) — a fixed stroke width
        // stays the same physical thickness at any zoom level, but reads as relatively thinner
        // once FIXED_FULL_SCALE/pinch make the peaks bigger, so it needed to grow a bit too.
        private const val TRACE_LINE_WIDTH_DP = 3.0f

        // Floor used by forceVisibleSeconds() to relax the max-zoom-in bound back to
        // effectively unlimited after forcing an exact Time Zoom width — see that function.
        private const val MIN_VISIBLE_SECONDS = 0.3f
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
        // The dB the recorder actually used for this file, if known (0 = not passed / unknown,
        // meaning no compensation is applied) — see loadFullWaveform's doc for why this exists.
        val recordedPreAmpDb = arguments?.getInt("preAmpDb", 0) ?: 0

        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()
        setupAmpSlider()
        setupGraphCalibrationPanel()
        updateCalibrationCaption()

        if (filePath.isNotEmpty()) {
            // Capture the scale on the main thread before loadFullWaveform's IO coroutine
            // reads it (Fix C) — setupWaveformChart() above already applied paper speed + DPI
            // correction synchronously, so this snapshot is final for the rest of this load.
            val scale = binding.calibratedWaveformView.paperView.currentScale()
            loadFullWaveform(filePath, filterName, scale.paperSpeed.mmPerSecond, scale.pxPerMmX, recordedPreAmpDb)
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

        // Repurposed from the ported production Save button — this build isn't persisting
        // recordings, it's for tuning graph size on-device (user request). Apply saves
        // whatever pinch-zoom state the graph is currently showing as this device's default
        // and goes straight back to the Recorder to see it applied live.
        binding.applyButton.setOnClickListener {
            val chart = binding.calibratedWaveformView.chart
            val effectiveVisibleSeconds = chart.highestVisibleX - chart.lowestVisibleX
            val effectiveYFullScale = currentEffectiveYFullScale()
            if (effectiveVisibleSeconds > 0f && effectiveYFullScale > 0f) {
                GraphCalibration.saveOverride(requireContext(), effectiveVisibleSeconds, effectiveYFullScale)
                calibrationOverride = GraphCalibration.Override(effectiveVisibleSeconds, effectiveYFullScale)
            }
            findNavController().navigateUp()
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

        // Fix A/B baseline, overridden below if this device has a saved calibration (Peak
        // Size / Time Zoom panel) — set once, never touched again outside the panel's own
        // live-drag handlers and Apply/Reset.
        calibrationOverride = GraphCalibration.getOverride(requireContext())
        val effectiveYFullScale = calibrationOverride?.yFullScale ?: FIXED_FULL_SCALE
        chart.axisLeft.axisMinimum = -effectiveYFullScale
        chart.axisLeft.axisMaximum = effectiveYFullScale

        // 50mm/s — must match CalibratedRecordingFragment exactly, so a recording looks the
        // same live as it does in review. See that fragment's comment for why 50 (not 25).
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_50)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        // Opposite of the recorder — user can pan/zoom to inspect the trace with two fingers,
        // in both directions (§5): horizontal pinch for time (Time Zoom), vertical pinch for
        // peak height (Peak Size). MPAndroidChart scales Y via its own touch-matrix
        // (viewPortHandler.scaleY), a separate mechanism from the Peak Size slider's
        // axisMinimum/axisMaximum — currentEffectiveYFullScale()/the slider handler fold the
        // two together (divide/multiply by scaleY) so either control always reflects and
        // composes correctly with whatever the other one just did.
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleXEnabled(true)
        chart.setScaleYEnabled(true)

        // Fix D — re-bucket for the new zoom/pan level once the gesture settles. Debounced by
        // construction: onChartGestureEnd fires once per discrete gesture, not per frame, so
        // this never runs mid-pinch and can't cause scroll stutter.
        chart.setOnChartGestureListener(object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {
                rebucketForCurrentZoom()
            }
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) {}
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {}
        })

        waveformView.onVisibleSecondsChanged = { seconds ->
            // A saved Time Zoom override replaces the grid-derived default outright (that's
            // the point of calibrating) — physical derivation still runs every time (e.g. on
            // rotation), it's just superseded whenever an override is active.
            currentWindowSeconds = calibrationOverride?.visibleSeconds ?: seconds
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
        // Fix E — matches CalibratedRecordingFragment's caption exactly (both use a fixed axis).
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude (fixed) · DPI: $status"
    }

    /**
     * Graph calibration is pinch-only now (user request: sliders were a second, redundant way
     * to do what two fingers on the graph already do better) — this just wires the status
     * readout and Reset. Applying a calibration happens via [R.id.applyButton] in the bottom
     * bar (see onViewCreated), which reads whatever the pinch-tuned view currently shows.
     */
    private fun setupGraphCalibrationPanel() {
        updateCalibrationStatusText()
        binding.resetCalibrationButton.setOnClickListener {
            GraphCalibration.clearOverride(requireContext())
            calibrationOverride = null
            applyBuiltInDefaultScale()
            updateCalibrationStatusText()
            Toast.makeText(requireContext(), "Reset to default", Toast.LENGTH_SHORT).show()
        }
    }

    /** Re-applies FIXED_FULL_SCALE and the grid-derived default window, bypassing any override — used by Reset. */
    private fun applyBuiltInDefaultScale() {
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart
        // Clears any pinch-driven X/Y viewport zoom (scaleX/scaleY) before reapplying the
        // built-in defaults below — otherwise a prior vertical pinch would still be layered on
        // top of the reset axis bounds and Reset wouldn't actually look reset.
        chart.fitScreen()
        chart.axisLeft.axisMinimum = -FIXED_FULL_SCALE
        chart.axisLeft.axisMaximum = FIXED_FULL_SCALE
        // Same reason as the Peak Size slider handler — axis bounds alone don't move the
        // already-plotted trace without this.
        chart.notifyDataSetChanged()
        // Re-fires onVisibleSecondsChanged with calibrationOverride already cleared above, so
        // currentWindowSeconds lands back on the physical grid-derived value.
        waveformView.recomputeVisibleSeconds()
        // onVisibleSecondsChanged only reapplies setVisibleXRangeMaximum (a zoom-OUT cap, see
        // forceVisibleSeconds) — if the user had pinched/slid to a *wider* view than the
        // default before hitting Reset, that alone wouldn't visually snap back. Force it.
        forceVisibleSeconds(currentWindowSeconds)
        rebucketForCurrentZoom()
        chart.invalidate()
    }

    /**
     * The Y full-scale actually being shown right now, folding together the fixed axis bounds
     * (chart.axisLeft.axisMaximum) and any pinch-driven vertical zoom on top of them
     * (chart.viewPortHandler.scaleY) — mirrors how X already reads its true state via
     * chart.highestVisibleX/lowestVisibleX rather than raw axis bounds. Relies on the Y axis
     * always being centered at 0 (every centerViewTo(...) call in this fragment passes 0f for y).
     */
    private fun currentEffectiveYFullScale(): Float {
        val chart = binding.calibratedWaveformView.chart
        val scaleY = chart.viewPortHandler.scaleY.coerceAtLeast(0.01f)
        return chart.axisLeft.axisMaximum / scaleY
    }

    /**
     * Forces the chart to display exactly [seconds] of width right now, regardless of whether
     * that's narrower or wider than the current view.
     *
     * `setVisibleXRangeMaximum` alone only sets a *zoom-out ceiling* (a floor on scaleX) — it
     * forces the view narrower if it's currently too wide, but does nothing if the requested
     * width is *wider* than the current zoom (that only relaxes the ceiling, it doesn't pull
     * the current view back out). Dragging the Time Zoom slider toward "Wide" needs the view to
     * actually widen live, so both bounds are pinned to [seconds] momentarily — which forces
     * scaleX to exactly the target in either direction — then the lower bound (max zoom-in) is
     * relaxed straight back to effectively unlimited so pinch-zoom-in still works afterward.
     * Same technique as Reset, just packaged for reuse — not left permanently locked, unlike
     * the Fix D bug this deliberately avoids re-introducing during ordinary pinch/pan.
     */
    private fun forceVisibleSeconds(seconds: Float) {
        val chart = binding.calibratedWaveformView.chart
        chart.setVisibleXRangeMinimum(seconds)
        chart.setVisibleXRangeMaximum(seconds)
        chart.centerViewTo(seconds / 2f, 0f, YAxis.AxisDependency.LEFT)
        chart.setVisibleXRangeMinimum(MIN_VISIBLE_SECONDS * 0.1f)
        chart.invalidate()
    }

    private fun updateCalibrationStatusText() {
        if (_binding == null) return
        binding.graphCalibrationStatus.text = if (calibrationOverride != null) {
            "Calibrated on this device"
        } else {
            "Not calibrated on this device — using default"
        }
    }

    /**
     * Calibrated counterpart of PlayerFragment.loadFullWaveform(). Same WAV-header sample-rate
     * parsing (bytes 24-27, little-endian, fallback 44100 — never hardcoded, per §4.5), but
     * downsampling is min/max-bucketed instead of "every step-th sample" so a transient can't
     * fall entirely between two kept samples.
     *
     * [paperSpeedMmPerSecond]/[pxPerMmX] are a main-thread snapshot of the paper's current
     * scale (Fix C) — passed in rather than read from `binding` inside the IO coroutine below.
     *
     * [recordedPreAmpDb] undoes the same gain the recorder applied when this file was made
     * (user request: a recording should look the same size in the Player as it did live,
     * whatever dB it was recorded at). 0 means unknown/not passed — no compensation applied,
     * same as before this existed. This only works for files that arrived with that bundle
     * arg (i.e. reviewing a just-recorded file); the app doesn't persist pre-amp per saved
     * file, so a recording reopened later from the library still won't self-correct.
     */
    private fun loadFullWaveform(
        filePath: String, filterName: String, paperSpeedMmPerSecond: Float, pxPerMmX: Float, recordedPreAmpDb: Int
    ) {
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

            // Undo the recorder's pre-amp gain, same formula CalibratedRecordingFragment uses
            // live — makes this file render at the same size it showed on the recording screen,
            // regardless of what dB was used. Mutates samples in place, before entries/bucket
            // computation and before decodedSamples is stored, so Fix D's zoom re-bucketing
            // also re-buckets from the compensated data.
            if (recordedPreAmpDb > 0) {
                val preAmpGain = Math.pow(10.0, recordedPreAmpDb / 20.0).toFloat()
                if (preAmpGain > 1.001f) {
                    for (j in samples.indices) samples[j] = samples[j] / preAmpGain
                }
            }

            // Fix C — derive so ~one min/max pair lands per horizontal pixel at the current
            // paper speed, instead of a fixed constant tuned for one specific speed. Apply
            // TARGET_POINT_BUDGET as a ceiling only: enlarge the bucket if the derived value
            // would produce more than the budget on a long file, but never shrink below it.
            val derivedBucket = CalibratedWaveformView.deriveBucketSize(fileSampleRate, paperSpeedMmPerSecond, pxPerMmX)
            val budgetCeilingBucket = maxOf(1, totalSamples / (TARGET_POINT_BUDGET / 2))
            val bucketSize = if (derivedBucket > 0) maxOf(derivedBucket, budgetCeilingBucket) else budgetCeilingBucket
            val entries = CalibratedWaveformView.downsampleMinMax(samples, bucketSize) { idx ->
                idx.toFloat() / fileSampleRate
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                // Fix D — keep the decoded file in memory for zoom-driven re-bucketing, and
                // remember the bucket size just used so the first gesture-end after load
                // doesn't redundantly re-derive an unchanged value.
                decodedSamples = samples
                decodedSampleRate = fileSampleRate
                lastAppliedBucketSize = bucketSize
                renderWaveformEntries(ArrayList(entries), durationSecs)
            }
        }
    }

    private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int) {
        binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
        val dataSet = LineDataSet(entries, "Waveform").apply {
            color = Color.parseColor("#2D7DD2")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = TRACE_LINE_WIDTH_DP
            mode = LineDataSet.Mode.LINEAR
        }
        waveformDataSet = dataSet // Fix D — persistent ref, mutated in place on re-bucket
        // currentWindowSeconds is kept in sync by onVisibleSecondsChanged (set up in
        // setupWaveformChart, called before this) — including the case where layout hadn't
        // happened yet when this loaded; that callback will re-apply the range once it does.
        // Y-axis is fixed (Fix A/B) — already set once in setupWaveformChart(), not touched here.
        val chart = binding.calibratedWaveformView.chart
        chart.data = LineData(dataSet)
        // Cap max zoom-out only — no Minimum lock, so pinch-zoom works (see setupWaveformChart).
        chart.setVisibleXRangeMaximum(currentWindowSeconds)
        chart.centerViewTo(currentWindowSeconds / 2f, 0f, YAxis.AxisDependency.LEFT)
        chart.invalidate()
    }

    /**
     * Fix D — recomputes the min/max bucket from whatever X range is currently visible
     * (post-zoom/pan) and re-buckets the whole decoded file at that density, targeting ~one
     * min/max pair per horizontal pixel. Re-buckets the whole file (not just the visible
     * slice) so panning within an unchanged zoom level doesn't need to re-run this — only an
     * actual zoom change does, since [lastAppliedBucketSize] short-circuits a no-op. Runs off
     * the main thread since re-bucketing a long file is real work; only the dataset swap
     * happens on Main.
     */
    private fun rebucketForCurrentZoom() {
        val samples = decodedSamples ?: return
        val ds = waveformDataSet ?: return
        val chart = binding.calibratedWaveformView.chart
        val plotWidthPx = chart.width.toFloat()
        if (plotWidthPx <= 0f) return

        val visibleSeconds = (chart.highestVisibleX - chart.lowestVisibleX).coerceAtLeast(0f)
        if (visibleSeconds <= 0f) return
        val visibleSampleCount = visibleSeconds * decodedSampleRate

        val derivedBucket = CalibratedWaveformView.deriveBucketSizeForVisibleRange(visibleSampleCount, plotWidthPx)
        if (derivedBucket <= 0) return
        // Same budget-ceiling pattern as the load-time bucket (§ loadFullWaveform) — enlarge to
        // stay within TARGET_POINT_BUDGET on a long file, never shrink below the derived value.
        val ceilingBucket = maxOf(1, samples.size / (TARGET_POINT_BUDGET / 2))
        val finalBucket = maxOf(derivedBucket, ceilingBucket)
        if (finalBucket == lastAppliedBucketSize) return
        lastAppliedBucketSize = finalBucket

        val sampleRate = decodedSampleRate
        rebucketJob?.cancel()
        rebucketJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val entries = CalibratedWaveformView.downsampleMinMax(samples, finalBucket) { idx ->
                idx.toFloat() / sampleRate
            }
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                // Mutate in place (same pattern as the recorder) rather than replacing
                // chart.data — a replace would reset the viewport and undo the zoom/pan the
                // user just performed.
                ds.values = ArrayList(entries)
                binding.calibratedWaveformView.chart.data?.notifyDataChanged()
                binding.calibratedWaveformView.chart.notifyDataSetChanged()
                binding.calibratedWaveformView.chart.invalidate()
            }
        }
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
`calibrationCaption` added, chrome shrunk. **Updated by §9** (2026-08-20): the old `tuneButton`
+ `graphCalibrationPanel` card (Peak Size/Time Zoom sliders + separate Apply/Reset buttons) was
removed; in its place, a compact `graphCalibrationRow` (LinearLayout: `graphCalibrationStatus`
TextView + small `resetCalibrationButton`) sits between `calibrationCaption` and
`calibratedWaveformView`. The bottom bar's `saveButton` was renamed `applyButton` (text "Apply")
— `discardButton` is unchanged. Full current XML is short enough to inline here in full (was
previously just described, not dumped):

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#F8F9FA">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/topBar"
        android:layout_width="match_parent"
        android:layout_height="40dp"
        android:background="@color/white"
        android:elevation="2dp"
        android:paddingStart="@dimen/spacing_md"
        android:paddingEnd="@dimen/spacing_md"
        app:layout_constraintTop_toTopOf="parent">

        <ImageButton
            android:id="@+id/backButton"
            android:layout_width="26dp"
            android:layout_height="26dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Back"
            android:scaleType="centerInside"
            android:src="@drawable/ic_arrow_back"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <TextView
            android:id="@+id/screenTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Calibrated Review"
            android:textColor="@color/text_primary"
            android:textSize="14sp"
            android:textStyle="bold"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <ImageButton
            android:id="@+id/eqButton"
            android:layout_width="28dp"
            android:layout_height="28dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Equalizer"
            android:src="@drawable/ic_equalizer"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

    </androidx.constraintlayout.widget.ConstraintLayout>

    <TextView
        android:id="@+id/timerText"
        style="@style/TaalText.Timer"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:text="@string/timer_default"
        android:textSize="15sp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/topBar" />

    <!-- Compact Amp Slider Card -->
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/ampSliderContainer"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="4dp"
        android:layout_marginEnd="16dp"
        app:cardBackgroundColor="@color/white"
        app:cardCornerRadius="10dp"
        app:cardElevation="2dp"
        app:strokeColor="#E0E0E0"
        app:strokeWidth="1dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/timerText">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:paddingStart="10dp"
                android:paddingTop="0dp"
                android:paddingEnd="10dp"
                android:paddingBottom="0dp">

                <ImageView
                    android:layout_width="14dp"
                    android:layout_height="14dp"
                    android:contentDescription="Amplification"
                    android:src="@drawable/ic_volume_up"
                    app:tint="#128CB2" />

                <com.google.android.material.slider.Slider
                    android:id="@+id/ampSlider"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="4dp"
                    android:layout_marginEnd="4dp"
                    android:layout_weight="1"
                    android:stepSize="1"
                    android:value="5"
                    android:valueFrom="0"
                    android:valueTo="30"
                    app:haloColor="#1A128CB2"
                    app:labelBehavior="gone"
                    app:thumbColor="#128CB2"
                    app:thumbRadius="5dp"
                    app:trackColorActive="#128CB2"
                    app:trackColorInactive="#C8E6F5"
                    app:trackHeight="2dp" />

                <TextView
                    android:id="@+id/ampLabel"
                    android:layout_width="40dp"
                    android:layout_height="wrap_content"
                    android:gravity="end"
                    android:text="5 dB"
                    android:textColor="#128CB2"
                    android:textSize="11sp"
                    android:textStyle="bold" />

            </LinearLayout>
        </LinearLayout>

    </com.google.android.material.card.MaterialCardView>

    <!-- §4.4: axis labeling / calibration status caption — see fragment_calibrated_recording.xml -->
    <TextView
        android:id="@+id/calibrationCaption"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="2dp"
        android:layout_marginEnd="16dp"
        android:gravity="center"
        android:text="25 mm/s · Y: relative amplitude · DPI: uncalibrated"
        android:textColor="#999999"
        android:textSize="9sp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/ampSliderContainer" />

    <!-- Per-device graph calibration status — pinch the graph below to adjust; this row is
         just the current-state readout + a way back to the built-in default. -->
    <LinearLayout
        android:id="@+id/graphCalibrationRow"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="4dp"
        android:layout_marginEnd="16dp"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/calibrationCaption">

        <TextView
            android:id="@+id/graphCalibrationStatus"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Not calibrated on this device — using default"
            android:textColor="#999999"
            android:textSize="9sp" />

        <Button
            android:id="@+id/resetCalibrationButton"
            android:layout_width="wrap_content"
            android:layout_height="24dp"
            android:minWidth="0dp"
            android:minHeight="0dp"
            android:background="@drawable/bg_button_outlined"
            android:paddingHorizontal="10dp"
            android:paddingVertical="0dp"
            android:text="Reset"
            android:textAllCaps="false"
            android:textSize="10sp" />
    </LinearLayout>

    <com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
        android:id="@+id/calibratedWaveformView"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="2dp"
        android:layout_marginBottom="2dp"
        app:layout_constraintBottom_toTopOf="@id/actionText"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/graphCalibrationRow" />

    <ProgressBar
        android:id="@+id/waveformLoadingIndicator"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:indeterminateTint="#128CB2"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="@id/calibratedWaveformView"
        app:layout_constraintEnd_toEndOf="@id/calibratedWaveformView"
        app:layout_constraintStart_toStartOf="@id/calibratedWaveformView"
        app:layout_constraintTop_toTopOf="@id/calibratedWaveformView" />

    <TextView
        android:id="@+id/actionText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="4dp"
        android:text="@string/play_recording"
        android:textColor="@color/text_primary"
        android:textSize="12sp"
        app:layout_constraintBottom_toTopOf="@id/playButton"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <ImageButton
        android:id="@+id/playButton"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:layout_marginBottom="10dp"
        android:background="@drawable/bg_record_button"
        android:contentDescription="Play"
        android:elevation="8dp"
        android:padding="0dp"
        android:scaleType="fitCenter"
        android:src="@drawable/ic_play_circle"
        app:layout_constraintBottom_toTopOf="@id/saveDiscardBar"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:tint="@color/white" />

    <LinearLayout
        android:id="@+id/saveDiscardBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginHorizontal="16dp"
        android:layout_marginBottom="12dp"
        android:orientation="horizontal"
        app:layout_constraintBottom_toBottomOf="parent">

        <Button
            android:id="@+id/discardButton"
            android:layout_width="0dp"
            android:layout_height="38dp"
            android:layout_marginEnd="8dp"
            android:layout_weight="1"
            android:background="@drawable/bg_button_outlined"
            android:text="Discard"
            android:textAllCaps="false"
            android:textSize="13sp"
            android:textStyle="bold" />

        <Button
            android:id="@+id/applyButton"
            android:layout_width="0dp"
            android:layout_height="38dp"
            android:layout_marginStart="8dp"
            android:layout_weight="1"
            android:background="@drawable/bg_button_teal"
            android:text="Apply"
            android:textAllCaps="false"
            android:textColor="@color/white"
            android:textSize="13sp"
            android:textStyle="bold" />
    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

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

> **This table is obsolete** — it describes the adaptive `HEADROOM`/`WARMUP_MS`/
> `WARMUP_PERCENTILE`/`MIN_PEAK` scheme from §6 point 8, which the later "Fixed-scale + Kardia
> grid" task (not logged in §6 — that log stops at point 9) removed entirely in favor of a
> single fixed `FIXED_FULL_SCALE` constant (Fix A). None of those four constants exist in the
> code anymore. **Kept below for historical reference only — see §9's table for accurate,
> current values.**
>
> | Constant | Recorder | Player | Notes |
> |---|---|---|---|
> | `PaperSpeed` | `SPEED_12_5` (12.5mm/s) | `SPEED_12_5` (12.5mm/s) | production default via `CalibratedMmScale` is `SPEED_25`; both screens override it explicitly in `setupWaveformChart()` |
> | `HEADROOM` | `1.2f` (~83% fill) | `1.2f` (~83% fill) | production Recording was `1.5f`; production Player doesn't use this constant (fixed ±0.5, never adaptive) |
> | `MIN_PEAK` | `0.02f` | `0.02f` | unchanged from production |
> | `WARMUP_MS` | `2000` | n/a (player has the whole file already) | unchanged from production |
> | `WARMUP_PERCENTILE` | `0.97f` | n/a | new — production has no equivalent (used raw max) |
> | `TRACE_LINE_WIDTH_DP` | `1.5f` | `2.5f` | unchanged from production (reverted after a brief bump to 3.0/3.5) |
> | `FOLLOW_SMOOTHING` | n/a | `0.15f` | new — production snaps directly to timestamp every callback |
> | `DOWNSAMPLE_BUCKET` (recorder) / `TARGET_POINT_BUDGET` (player) | `88` | `3000` | min/max bucket sizing, tuned to match production's point density |
> | Zoom | disabled (`setTouchEnabled(false)`) | enabled, max-zoom-out capped at derived window, **no min cap** (fixed bug — see log point 3) | |

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
   discussed but never implemented. ~~If picked back up: ask the user what visible-seconds
   count...~~ **Superseded, see §9**: implemented as a full feature 2026-08-20 — not a
   `PaperSpeed` translation, but a direct per-device override (`GraphCalibration`) of the
   effective Y full-scale and visible-seconds window, set by pinching the Player and pressing
   Apply, consumed by both screens.
6. **Amplitude mismatch between recorder and player is real and expected**: the recorder
   divides the live signal by the pre-amp gain before drawing (so the trace reflects true
   acoustic level); the player never does this (the file already has gain baked in, and
   production never compensated for it either). So the *same* recording can look different in
   scale between the live view and the review view whenever pre-amp ≠ 0dB (default is 5dB).
   This exactly mirrors production's own behavior — not a regression, but worth knowing before
   "fixing" it without realizing it's intentional parity. **Partially addressed since**: the
   Player now receives `preAmpDb` via nav bundle for a *just-recorded* file and undoes the same
   gain on load (`loadFullWaveform`'s `recordedPreAmpDb` param) — see §9's journey and
   `CalibratedPlayerFragment.kt` §4.7. Still not fixed for a recording reopened later from the
   saved-recordings library (pre-amp isn't persisted as file metadata) — that part of this gap
   remains open.

---

## 9. Per-Device Graph Calibration Feature (added 2026-08-20)

This section is the up-to-date source of truth for everything below — §6 and §7 above predate
this feature entirely and are kept only as historical record (§6 stops at "point 9: never
actually executed", which this feature is the eventual, differently-shaped implementation of).

### 9.1 State immediately before this feature existed

Right after the separate "Fixed-scale live waveform + Kardia-style grid" task (five fixes,
never logged in §6 above — that gap is itself worth knowing about):
- **Fix A**: Y-axis became a single fixed full-scale (`FIXED_FULL_SCALE`, normalized ±value),
  set once at chart setup and never touched again during a session — replacing the earlier
  adaptive `HEADROOM`/`WARMUP_MS`/`WARMUP_PERCENTILE` scheme (§6 point 8) entirely, on the
  reasoning that a fixed axis trades per-recording optimality for stability (deliberately how
  Kardia's own ECG display works).
- **Fix B**: live/review pre-amp display parity — `CalibratedPlayerFragment` given the same
  fixed axis constant as the Recorder, so the same recording renders at the same size live and
  in review (at default pre-amp).
- **Fix C**: Kardia-style grid — faint minor lines, bold major lines, pixel-snapped.
- **Fix D**: zoom-driven re-bucketing bug in the Player fixed (`rebucketForCurrentZoom`) — the
  min/max downsample bucket is re-derived from whatever X range is *currently visible*, not
  frozen at load time, so pinch-zoomed playback shows real samples instead of a stretched
  synthetic sawtooth.
- **Fix E**: status captions updated to describe the fixed axis.

After those fixes, `FIXED_FULL_SCALE` was hand-tuned via live on-device logcat analysis to
`0.013f` on one physical phone (Samsung SM-A066B/"A06") — and it looked genuinely wrong on a
second phone (OnePlus 7T, different pixel density/width). **That mismatch is the direct reason
this feature exists**: a single hardcoded constant cannot look right on every screen, so instead
of re-tuning forever, let each device calibrate itself once and remember it.

### 9.2 What was asked for (paraphrased from chat)

> Give users a default setting like before. Once a good recording is done, let them pinch-zoom
> and/or drag a slider on the Player screen until the graph looks right for their phone. Apply
> makes that the new default (for **both** Recorder and Player — same reason `FIXED_FULL_SCALE`
> was already a single shared constant) until changed. A Reset button reverts to the built-in
> default. The setting must be remembered (persisted) until Reset is pressed.

### 9.3 Full chronological journey (this feature's own history)

1. **Plan approved** (`GraphCalibration.kt` object + a toggle-able calibration panel on the
   Player with two labeled sliders — "Peak Size" small↔large, "Time Zoom" wide↔narrow — plus
   Apply/Reset buttons and a status line). Implemented: `GraphCalibration.kt` created;
   `CalibratedPlayerFragment` wired with `tuneButton` (toggles the panel), `peakSizeSlider`,
   `timeZoomSlider`, `applyCalibrationButton`, `resetCalibrationButton`; both fragments read
   `GraphCalibration.getOverride(context)` at chart setup, substituting it for
   `FIXED_FULL_SCALE`/the grid-derived default window when present.
2. **Bug — Time Zoom slider only worked one direction.** Dragging toward "Wide" visibly did
   nothing. Root cause: `chart.setVisibleXRangeMaximum(seconds)` alone only sets a *zoom-out
   ceiling* (a floor on `scaleX`) — it forces the view narrower if it's currently too wide, but
   does nothing if the requested width is *wider* than the current zoom (that only relaxes the
   ceiling, it doesn't pull the view back out). Fixed by adding `forceVisibleSeconds(seconds)`:
   momentarily pin *both* `setVisibleXRangeMinimum` and `setVisibleXRangeMaximum` to the exact
   target (forcing `scaleX` to that value in either direction), re-center, then relax the
   minimum bound back to effectively unlimited so pinch-zoom-in still works afterward. Reused
   for Reset too (a prior wider pinch/slide needed the same forcing to snap back).
3. **User: "graph should also adjust as I drag the slider so the user can see what's going
   on."** Confirmed as the same class of fix as #2; verified working after the fix above.
4. **User: "make it work together — pinch should move the slider and vice versa" + "small and
   large seems not to work."** Two separate real bugs:
   - The Peak Size slider set `chart.axisLeft.axisMinimum`/`axisMaximum` directly, but never
     called `chart.notifyDataSetChanged()`. Axis bounds alone only update the axis's own
     bookkeeping (labels, if drawn) — the *trace's* pixel-transform matrix isn't recomputed
     until something calls `notifyDataSetChanged()`/`calculateOffsets()`, so the peaks visually
     never resized even though the slider moved. Fixed by adding the missing call.
   - Pinch was, at the time, restricted to X only (`setScaleYEnabled(false)`) specifically to
     avoid a second, independent Y-scaling mechanism (MPAndroidChart's own touch-matrix
     `scaleY`) fighting the slider's axis-bounds approach. `onChartGestureEnd` was wired to sync
     the Time Zoom slider's position from the current pinch/pan state whenever the panel was
     open.
5. **User: "I lost my fingers for small/large."** Disabling Y-pinch in step 4 was too blunt —
   the user actually wanted *both* mechanisms live at once, composing correctly. Re-enabled
   `setScaleYEnabled(true)`, then reconciled the two Y mechanisms properly:
   - `currentEffectiveYFullScale()` = `chart.axisLeft.axisMaximum / chart.viewPortHandler.scaleY`
     — the *true* currently-displayed Y range, folding the fixed axis bounds together with
     whatever pinch-driven `scaleY` is layered on top (mirrors how X already reads its true
     state via `chart.highestVisibleX`/`lowestVisibleX` rather than raw axis bounds, relying on
     the Y axis always being centered at 0 via every `centerViewTo(x, 0f, ...)` call in the
     fragment).
   - The Peak Size slider's handler multiplies its target by the *current* `scaleY` before
     writing `axisMaximum`, so it always lands on exactly the value it displays regardless of
     whether the user pinched Y before or after touching it.
   - `applyBuiltInDefaultScale()` (Reset) now calls `chart.fitScreen()` first, clearing any
     pinch-driven X/Y viewport zoom, before reapplying the built-in axis bounds — otherwise a
     prior vertical pinch would still be layered on top of the "reset" state.
6. **User: redesign request** — *"remove the save button and make it record button... once
   apply it should go to recording screen. Add the apply button in the front itself and hide
   the idea of sliders — best if the user just uses the graph and pinch to zoom, then Apply and
   see it in the record screen."* Large simplification:
   - `tuneButton` and the entire slider-panel `MaterialCardView` (Peak Size/Time Zoom sliders,
     their own Apply/Reset row) removed from `fragment_calibrated_player.xml`.
   - Replaced with a compact, always-visible `graphCalibrationRow` (status text + a small
     `resetCalibrationButton`) — no toggle needed since there's nothing to hide anymore.
   - The bottom bar's `saveButton` (`"Save"`, previously navigated to `SaveRecordingFragment` or
     showed a save/discard dialog) was repurposed into `applyButton` (`"Apply"`): it now reads
     the current pinch-tuned view (`chart.highestVisibleX - chart.lowestVisibleX` and
     `currentEffectiveYFullScale()`), calls `GraphCalibration.saveOverride(...)`, and calls
     `findNavController().navigateUp()` — which always lands back on the Recorder, since that's
     the only screen that navigates to the Calibrated Player.
   - All slider-mapping helper functions (`sliderToYFullScale`, `yFullScaleToSlider`,
     `sliderToVisibleSeconds`, `visibleSecondsToSlider`) and `syncCalibrationSlidersToCurrentState()`
     were deleted as dead code; `MAX_Y_FULL_SCALE`/`MIN_Y_FULL_SCALE`/`MAX_VISIBLE_SECONDS`
     constants removed (only `MIN_VISIBLE_SECONDS` survives, still used by
     `forceVisibleSeconds()`'s relax-the-floor step).
   - `discardButton` unchanged (still deletes temp files + navigates up for a new recording).
7. **User: "graph size is bigger... looks very noisy and bad" after Apply.** Real bug, same
   class as Fix D but on the **Recorder** this time, only exposed once an override could make
   the effective window differ from the grid's native derivation. `CalibratedRecordingFragment`
   derived `sessionBucketSize` (the live min/max downsample density) purely from the paper's
   *native*, un-overridden pixel-per-second rate (`deriveBucketSize(sampleRate, paperSpeed,
   pxPerMmX)`) — never from the actual effective window an active override might have narrowed.
   A saved Time Zoom override that narrowed the window (more zoomed in) left the bucket sized
   for the old, wider native view: each min/max pair then spanned several screen pixels instead
   of roughly one, reading as jagged/noisy. Fixed by deriving the bucket from the *effective*
   window instead — `CalibratedWaveformView.deriveBucketSizeForVisibleRange(currentWindowSeconds
   * sampleRate, plotWidthPx)`, the exact same function Fix D already used for the Player's
   pinch-zoom — with the old paper-speed-only derivation kept only as a pre-layout fallback
   (`plotWidthPx <= 0f`).
8. **`FIXED_FULL_SCALE` retuned by direct request**, now that per-device calibration exists to
   fix up whatever the default looks like: `0.013f` (the old, one-device-tuned value that
   started this whole feature) → **`0.30f`** ("make the graph normal again, not 0.013" — small
   on first launch, pinch/Apply is what should make it look correct) → **`0.20f`** ("make it a
   bit big") → **`0.10f`** ("make it 0.20 to 0.10", still bigger). Deliberately **not**
   re-optimized for any specific device at any of these values — that's what calibration is for
   now; this constant is only ever the small, neutral starting point every device shares.
9. **`TRACE_LINE_WIDTH_DP` bumped** ("check if the thickness reduces when we hit apply, it's a
   bit thin"). Investigated first: line width is a fixed dp constant set once at dataset
   creation, untouched by Apply/Reset/zoom code — it does **not** literally shrink. What's real:
   a fixed-width stroke reads as *relatively* thinner once `FIXED_FULL_SCALE`/pinch make the
   peaks occupy more of the screen. Bumped on request: Recorder `1.5f → 2.0f`, Player
   `2.5f → 3.0f`.

### 9.4 Full source: `ecg/calibrated/GraphCalibration.kt`

```kotlin
package com.musediagnostics.taal.app.ecg.calibrated

import android.content.Context

/**
 * Persisted per-device override for the calibrated screens' default zoom/scale — "Peak Size"
 * and "Time Zoom", set from the Player's calibration panel and shared by both the Player and
 * the Recorder (so live recording and review keep matching sizes on that device).
 *
 * Same `SharedPreferences`-backed object pattern as [DpiCalibration], but a distinct, unrelated
 * concern — this is a *visual preference* (how big things look on this specific screen), not a
 * physical-accuracy correction like px-per-mm.
 *
 * We deliberately persist [Override.visibleSeconds] and [Override.yFullScale] — physical,
 * portable quantities read back directly from the chart — rather than MPAndroidChart's raw
 * pinch-zoom scale factors (`viewPortHandler.scaleX`/`scaleY`). Those scale factors are
 * relative to whatever file happens to be loaded at the time (confirmed via a live-tuning
 * session: the same `scaleX` value meant a different number of visible seconds depending on
 * file length), so persisting them and replaying them against a different recording later
 * would not reproduce the same visual result. `visibleSeconds`/`yFullScale` have no such
 * dependency — they mean the same thing regardless of which file is open.
 */
object GraphCalibration {
    private const val PREFS_NAME = "calibrated_graph_prefs"
    private const val KEY_HAS_OVERRIDE = "has_override"
    private const val KEY_VISIBLE_SECONDS = "visible_seconds"
    private const val KEY_Y_FULL_SCALE = "y_full_scale"

    data class Override(val visibleSeconds: Float, val yFullScale: Float)

    /** Null when no calibration has been applied on this device — callers fall back to their own built-in default. */
    fun getOverride(context: Context): Override? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_OVERRIDE, false)) return null
        val visibleSeconds = prefs.getFloat(KEY_VISIBLE_SECONDS, 0f)
        val yFullScale = prefs.getFloat(KEY_Y_FULL_SCALE, 0f)
        if (visibleSeconds <= 0f || yFullScale <= 0f) return null // corrupt/stale — ignore rather than crash
        return Override(visibleSeconds, yFullScale)
    }

    fun saveOverride(context: Context, visibleSeconds: Float, yFullScale: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_VISIBLE_SECONDS, visibleSeconds)
            .putFloat(KEY_Y_FULL_SCALE, yFullScale)
            .putBoolean(KEY_HAS_OVERRIDE, true)
            .apply()
    }

    fun clearOverride(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_VISIBLE_SECONDS)
            .remove(KEY_Y_FULL_SCALE)
            .putBoolean(KEY_HAS_OVERRIDE, false)
            .apply()
    }
}
```

### 9.5 How it actually works today (mechanics reference)

- **`CalibratedPlayerFragment.setupWaveformChart()`** reads `GraphCalibration.getOverride(context)`
  once at setup, storing it in the `calibrationOverride` field. If present, its `yFullScale`
  replaces `FIXED_FULL_SCALE` for the initial axis bounds, and its `visibleSeconds` replaces the
  grid-derived default inside `onVisibleSecondsChanged` (which still runs every time, e.g. on
  rotation — the override just supersedes its result whenever active).
- **Pinch** (both X via `setScaleXEnabled(true)` and Y via `setScaleYEnabled(true)`) works
  live, no extra wiring — MPAndroidChart's own touch matrix handles it; `onChartGestureEnd`
  triggers Fix D's `rebucketForCurrentZoom()` afterward so trace density matches the new zoom.
- **`currentEffectiveYFullScale()`** — `chart.axisLeft.axisMaximum / chart.viewPortHandler.scaleY`
  — is the single source of truth for "what Y range is actually showing right now," used by
  both the Apply button and (previously) the slider sync.
- **`applyButton`** (bottom bar, was `saveButton`) reads `chart.highestVisibleX -
  chart.lowestVisibleX` for X and `currentEffectiveYFullScale()` for Y, calls
  `GraphCalibration.saveOverride(context, visibleSeconds, yFullScale)`, updates the in-memory
  `calibrationOverride` field, and navigates up (back to the Recorder).
- **`resetCalibrationButton`** calls `GraphCalibration.clearOverride(context)`, sets
  `calibrationOverride = null`, then `applyBuiltInDefaultScale()`: `chart.fitScreen()` (clears
  pinch-driven X/Y zoom) → reapply `±FIXED_FULL_SCALE` → `notifyDataSetChanged()` → 
  `recomputeVisibleSeconds()` (re-derives the grid-native window, now unopposed since the
  override is cleared) → `forceVisibleSeconds(currentWindowSeconds)` (actually snaps the view,
  since `setVisibleXRangeMaximum` alone can't widen an already-narrower view) →
  `rebucketForCurrentZoom()` → `invalidate()`.
- **`CalibratedRecordingFragment.setupWaveformChart()`** does the same override substitution for
  its initial axis and window, and **also derives `sessionBucketSize` from the effective window**
  (not just paper speed — see journey point 7). **`onResume()`** re-reads the override and
  re-applies axis + window, so coming back from the Player after Apply/Reset reflects
  immediately without the fragment needing to be recreated.
- **Tests**: `GraphCalibrationTest.kt` — plain-JVM, so it can only test `Override`'s data shape
  and the save/get/clear validity rule as an isolated boolean check (`visibleSeconds<=0 ||
  yFullScale<=0`), not the real `Context`-backed methods (no Robolectric/Mockito in this
  module) — same scope limitation as the pre-existing `DpiCalibration` tests in
  `CalibratedMmScaleTest.kt`.

### 9.6 Comparison table — Original vs In-Between vs Now

"Original" = right after Fix A–E, before this feature existed at all. "In-Between" = the
slider-panel design (journey points 1–5). "Now" = current, pinch-only design (journey points
6–9).

| Aspect | Original (pre-feature) | In-Between (slider panel) | Now (pinch + Apply) |
|---|---|---|---|
| Default Y scale (`FIXED_FULL_SCALE`) | `0.013f` — hand-tuned on one specific device (Samsung A06), wrong on others | same `0.013f` baseline, but now *overridable* per device | `0.10f` — small, neutral, deliberately not tuned for any device (`0.30f → 0.20f → 0.10f` by request) |
| Per-device size adjustment | **None** — one constant, shared by every install | Peak Size / Time Zoom **sliders**, plus pinch (with bugs — see journey 2–4) | **Pinch only** (both axes), no sliders |
| Y-axis pinch-zoom | N/A (no calibration UI yet); underlying `setScaleEnabled(true)` was already on but nothing read it back | **Disabled** partway through (`setScaleYEnabled(false)`) to avoid fighting the slider | **Enabled**, reconciled with the axis-bounds mechanism via `currentEffectiveYFullScale()` |
| X-axis pinch-zoom | Worked (production behavior, Fix D re-buckets it) | Worked, plus a **broken** one-directional slider (`forceVisibleSeconds` didn't exist yet) | Works; slider removed, pinch is now the only X control besides the caption |
| Sync between pinch and any UI control | N/A | Attempted via `onChartGestureEnd` → `syncCalibrationSlidersToCurrentState()`, only for Time Zoom at first, later both | N/A — nothing to sync anymore; the graph itself *is* the control |
| Peak Size slider actually resizing the trace | N/A | **Broken at first** — set axis bounds but never called `notifyDataSetChanged()`, so the trace didn't move even though the label/state did | N/A — slider removed |
| Calibration panel UI | Doesn't exist | Toggle button (`tuneButton`) + collapsible `MaterialCardView` with 2 sliders + status text + its own Apply/Reset row | Compact **always-visible** row: status text + small `Reset` button — no toggle needed |
| Bottom bar "Save" button | `"Save"` — production-style flow: new recording → navigate to `SaveRecordingFragment`; existing recording → save/discard dialog | Unchanged — Save still did the old thing; calibration Apply was a *separate* button inside the panel | **Repurposed to `"Apply"`** — saves the current pinch-tuned calibration and navigates back to the Recorder. No save-to-library flow left on this screen. |
| What "Apply" does | N/A | Reads `chart.axisLeft.axisMaximum` directly for Y (didn't account for pinch `scaleY` — would've been wrong once Y-pinch was later re-enabled) | Reads `currentEffectiveYFullScale()` (axis ÷ scaleY, correct regardless of how you got there) + `highestVisibleX - lowestVisibleX`; saves; **navigates to the Recorder** |
| Reset | N/A | Reapplied `FIXED_FULL_SCALE` + grid window; did **not** clear pinch-driven zoom (`viewPortHandler` state), so a prior pinch could still show through | Calls `chart.fitScreen()` first — actually clears all pinch/pan state — then reapplies the built-in default cleanly |
| Recorder bucket density (live noise/jaggedness) | Not an issue — window was always grid-native by construction (no override could exist) | **Latent bug**, not yet triggered (no override with a materially different window had been Applied yet) | **Bug found and fixed** — `sessionBucketSize` now derives from the *effective* (possibly overridden) window, not just paper speed |
| Persistence | N/A | `GraphCalibration` SharedPreferences, same as now | Same — unchanged since it was first built |
| Trace line width | Recorder `1.5f` / Player `2.5f` | Unchanged | Recorder `2.0f` / Player `3.0f` (bumped — fixed width reads relatively thinner at higher zoom) |
| Live-vs-review consistency across devices | Guaranteed identical (same shared constant) but only ever "correct" on the one device it was tuned against | Same guarantee, now correctable per device via the override both screens read | Same guarantee; correctable per device; default itself is now intentionally neutral instead of pre-tuned |

---
