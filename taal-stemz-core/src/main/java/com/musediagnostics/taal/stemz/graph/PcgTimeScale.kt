package com.musediagnostics.taal.stemz.graph

import kotlin.math.ceil

/**
 * Fixed time scale for the PcgScale screens: one LARGE grid box = exactly 1 second, one SMALL
 * grid box = exactly 0.2 seconds, always. Pure Kotlin, no Android dependency, plain-JVM
 * unit-testable.
 *
 * Deliberately NOT a fork of [com.musediagnostics.taal.app.ecg.calibrated.CalibratedMmScale]
 * (protected invariants live there — grid geometry from pxPerMm, meaning from paperSpeed).
 * This class has no mm, no DPI, no paper speed: the squares are defined in TIME, and their
 * pixel size falls out of a single [pixelsPerSecond] chosen once from the plot width. No legal
 * CalibratedPaperSpeed value can produce 1s/0.2s squares (that needs 5 mm/s, which the enum
 * doesn't have), which is why this is a new concept rather than a new enum case — adding a
 * non-clinical speed to the calibrated enum would dilute what that enum means.
 *
 * [pixelsPerSecond] is fixed for the lifetime of the scale and is never changed by any
 * gesture. Zoom does not exist on the PcgScale screens; the only way this value changes is a
 * layout resize (rotation/split-screen), which replaces the whole scale via [fromPlotWidth] —
 * time is still undistorted afterwards, just rendered at a different density.
 *
 * The tick/label math ([firstTickIndexVisible], [tickTimeSeconds], [isMajorTick]) lives here
 * rather than in the paper view so the "labels drift from the grid" failure mode is guarded by
 * plain-JVM tests: the grid lines, the trace's window, and the second-labels all derive from
 * this one object — never three independently-computed values (the exact historical defect of
 * the dormant test screens, see CalibratedMmScale's visibleSeconds doc).
 */
class PcgTimeScale(val pixelsPerSecond: Float) {

    companion object {
        const val SECONDS_PER_LARGE_SQUARE = 1f
        const val SMALL_SQUARES_PER_LARGE_SQUARE = 5
        const val SECONDS_PER_SMALL_SQUARE = SECONDS_PER_LARGE_SQUARE / SMALL_SQUARES_PER_LARGE_SQUARE

        // How many seconds of signal the plot shows at once by default. 4s = four large boxes
        // across the screen — wide enough to hold several full cardiac cycles, narrow enough
        // that S1/S2 widths are readable. A fragment can pass a different value to
        // [fromPlotWidth] before first layout if a screen ever wants a different default.
        const val DEFAULT_VISIBLE_SECONDS = 4f

        /**
         * The one place a [pixelsPerSecond] is ever chosen: from the plot's laid-out width and
         * the number of seconds it should span. Returns null until layout gives a real width.
         */
        fun fromPlotWidth(plotWidthPx: Float, visibleSeconds: Float = DEFAULT_VISIBLE_SECONDS): PcgTimeScale? {
            if (plotWidthPx <= 0f || visibleSeconds <= 0f) return null
            return PcgTimeScale(plotWidthPx / visibleSeconds)
        }
    }

    fun secondsToPx(seconds: Float): Float = seconds * pixelsPerSecond
    fun pxToSeconds(px: Float): Float = px / pixelsPerSecond

    fun smallSquarePx(): Float = SECONDS_PER_SMALL_SQUARE * pixelsPerSecond
    fun largeSquarePx(): Float = SECONDS_PER_LARGE_SQUARE * pixelsPerSecond

    /** Seconds of signal that fit across [plotWidthPx] — the chart's visible window is derived from this, never a constant. */
    fun visibleSeconds(plotWidthPx: Float): Float = pxToSeconds(plotWidthPx)

    // ---- Tick/label math (ticks are indexed in SMALL squares from t=0) ----

    /**
     * Index of the first small-square tick at or after [scrollOffsetSeconds] (the absolute
     * time at the plot's left edge). Integer indices, not accumulated floats, so a long
     * recording never drifts its ticks off the grid.
     */
    fun firstTickIndexVisible(scrollOffsetSeconds: Float): Int =
        ceil((scrollOffsetSeconds / SECONDS_PER_SMALL_SQUARE) - 1e-4f).toInt().coerceAtLeast(0)

    /** Absolute recording time of tick [index]. */
    fun tickTimeSeconds(index: Int): Float = index * SECONDS_PER_SMALL_SQUARE

    /** True for whole-second ticks — drawn heavier and labeled with the second count. */
    fun isMajorTick(index: Int): Boolean = index % SMALL_SQUARES_PER_LARGE_SQUARE == 0

    /** X position in the plot, in px, of tick [index] when the left edge shows [scrollOffsetSeconds]. */
    fun tickPositionPx(index: Int, scrollOffsetSeconds: Float): Float =
        (tickTimeSeconds(index) - scrollOffsetSeconds) * pixelsPerSecond
}
