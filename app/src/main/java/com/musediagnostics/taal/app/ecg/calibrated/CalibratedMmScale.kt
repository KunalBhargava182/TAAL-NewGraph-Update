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
