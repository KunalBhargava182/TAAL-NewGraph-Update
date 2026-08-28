package com.musediagnostics.taal.app.ecg

/**
 * Standard ECG paper speed settings. The mm/s value is the only thing that
 * changes what a grid square *means* in time — it never changes a square's
 * physical 1mm size on screen.
 */
enum class PaperSpeed(val mmPerSecond: Float) {
    SPEED_12_5(12.5f),
    SPEED_25(25f),
    SPEED_50(50f)
}

/**
 * Standard ECG gain settings. The mm/mV value is the only thing that changes
 * what a grid square *means* in voltage — it never changes a square's
 * physical 1mm size on screen.
 */
enum class Gain(val mmPerMv: Float) {
    GAIN_5(5f),
    GAIN_10(10f),
    GAIN_20(20f)
}

/**
 * Pure unit-conversion math for ECG graph paper: pixels-per-physical-millimetre
 * on each axis, plus the paper-speed/gain settings that give those millimetres
 * clinical meaning. Deliberately has no Android View/Canvas dependency so it's
 * plain-JVM unit-testable, and so a trace renderer or a future PCG lane can
 * share the exact same math as the grid.
 *
 * Grid geometry (small/large square size in px) depends only on [pxPerMmX]/[pxPerMmY].
 * [paperSpeed] and [gain] affect only [secondsToPx]/[mvToPx] and their inverses —
 * changing either never resizes the grid.
 */
class MmScale(
    var pxPerMmX: Float,
    var pxPerMmY: Float,
    var paperSpeed: PaperSpeed = PaperSpeed.SPEED_25,
    var gain: Gain = Gain.GAIN_10
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
}
