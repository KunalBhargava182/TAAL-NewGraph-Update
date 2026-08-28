package com.musediagnostics.taal.app.ecg.pcgscale

import kotlin.math.max
import kotlin.math.sqrt

/**
 * RMS-derived Y-axis full-scale for the PcgScale screens: the axis is sized so the heart
 * sounds' typical maxima visually fill ~[TARGET_FILL_FRACTION] (60%) of the graph's half
 * height — excluding noise. Pure Kotlin, no Android dependency, plain-JVM unit-testable.
 *
 * How "60% excluding noise" is made concrete (each stage rejects a different failure mode):
 *
 *  1. Short-hop RMS ([RMS_HOP_SECONDS] = 50ms): energy over a hop, not instantaneous sample
 *     values — a single-sample click or electrical spike is diluted by the ~2200 samples
 *     around it and cannot set the scale (the exact failure of the old warmup max(abs) lock,
 *     where one stethoscope contact thud pegged the axis for the whole session).
 *  2. Peak-of-hops per window ([PEAK_WINDOW_SECONDS] = 5s): within each 5-second window keep
 *     the LOUDEST hop RMS — that's the heart-sound maxima (S1/S2 bursts), not the quiet
 *     floor between beats a plain average would sink to.
 *  3. Mean across windows: the running mean of those per-window peaks. One anomalously loud
 *     5s window (coughing, bumping the chest piece) is averaged against every normal window
 *     instead of winning outright.
 *  4. fullScale = meanPeak / [TARGET_FILL_FRACTION], clamped to
 *     [[MIN_FULL_SCALE], [MAX_FULL_SCALE]] — a signal whose typical maxima sit at meanPeak
 *     then draws at 60% of the half-height. The clamp floor (same value as production's old
 *     MIN_PEAK) stops near-silence from computing a degenerate axis that would blow noise up
 *     to full height.
 *
 * Live use (recorder): call [addSamples] with each display buffer, then [smoothedFullScale]
 * once per UI update — the returned value eases toward the current target by
 * [SMOOTHING_PER_UPDATE] per call (same easing idea as CalibratedPlayerFragment's
 * FOLLOW_SMOOTHING camera), so the axis glides rather than popping when a 5s window closes.
 * The first window hasn't closed for the first 5s of a recording; until then the target is
 * [initialFullScale] (default [DEFAULT_INITIAL_FULL_SCALE], the same neutral 0.10 the
 * Calibrated screens start from), so the very start of a recording looks familiar, then
 * settles onto the measured scale.
 *
 * Completed-file use (player): [computeFullScaleForFile] runs stages 1–4 over the whole
 * decoded recording in one pass and returns the final axis bound — no smoothing needed, the
 * value is applied once before the trace is shown.
 *
 * This object holds no OS resources — no threads, no handlers, no audio sessions. It is pure
 * state mutated from the caller's thread, so there is nothing to release in onPause/onDestroy;
 * dropping the reference (or calling [reset] for a new session) is the entire lifecycle.
 */
class PcgAmplitudeScale(
    private val sampleRate: Float,
    private val initialFullScale: Float = DEFAULT_INITIAL_FULL_SCALE
) {

    companion object {
        const val TARGET_FILL_FRACTION = 0.60f
        const val RMS_HOP_SECONDS = 0.05f
        const val PEAK_WINDOW_SECONDS = 5f

        // Same floor as production RecordingFragment's MIN_PEAK — below this the input is
        // treated as noise/silence and the axis refuses to shrink further.
        const val MIN_FULL_SCALE = 0.02f
        const val MAX_FULL_SCALE = 1.0f

        // Fraction of the remaining gap to the target closed per smoothedFullScale() call.
        // Called once per audio-buffer UI update (~tens of Hz), so a new target is reached in
        // well under a second, without a visible snap.
        const val SMOOTHING_PER_UPDATE = 0.15f

        // The neutral session-start value, matching the Calibrated screens' FIXED_FULL_SCALE
        // so the first seconds of a PcgScale recording look like the screens users know.
        const val DEFAULT_INITIAL_FULL_SCALE = 0.10f

        /**
         * Whole-recording variant for the player: mean of per-5s-window peak hop-RMS across
         * the entire file, converted to an axis full-scale. A trailing partial window (the
         * last few seconds of the file) is included so short files (< 5s) still measure.
         */
        fun computeFullScaleForFile(samples: FloatArray, sampleRate: Float): Float {
            if (samples.isEmpty() || sampleRate <= 0f) return DEFAULT_INITIAL_FULL_SCALE
            val scale = PcgAmplitudeScale(sampleRate)
            scale.addSamples(samples)
            scale.flushPartialWindow()
            return scale.targetFullScale()
        }
    }

    private val hopSamples = max(1, (RMS_HOP_SECONDS * sampleRate).toInt())
    private val windowSamples = max(hopSamples, (PEAK_WINDOW_SECONDS * sampleRate).toInt())

    // Current hop accumulator.
    private var hopSumSquares = 0.0
    private var hopCount = 0

    // Current 5s window state.
    private var windowPeakRms = 0f
    private var windowSampleCount = 0

    // Closed-window statistics: running mean of per-window peak RMS.
    private var closedWindowCount = 0
    private var peakRmsSum = 0.0

    // Eased display value, advanced by smoothedFullScale().
    private var smoothedValue = initialFullScale

    /** Feed samples in normalized -1..+1 units (the same units the chart's Y axis uses). */
    fun addSamples(data: FloatArray) {
        for (v in data) {
            hopSumSquares += (v * v).toDouble()
            hopCount++
            windowSampleCount++
            if (hopCount >= hopSamples) closeHop()
            if (windowSampleCount >= windowSamples) closeWindow()
        }
    }

    private fun closeHop() {
        val rms = sqrt(hopSumSquares / hopCount).toFloat()
        if (rms > windowPeakRms) windowPeakRms = rms
        hopSumSquares = 0.0
        hopCount = 0
    }

    private fun closeWindow() {
        peakRmsSum += windowPeakRms.toDouble()
        closedWindowCount++
        windowPeakRms = 0f
        windowSampleCount = 0
    }

    /**
     * Close whatever partial hop/window is pending — used by [computeFullScaleForFile] so the
     * tail of a file (or an entire sub-5s file) contributes. Not called in the live path,
     * where the next buffer will keep filling the open window.
     */
    fun flushPartialWindow() {
        if (hopCount > 0) closeHop()
        if (windowSampleCount > 0) closeWindow()
    }

    /** How many full 5s windows have closed — 0 means the target is still [initialFullScale]. */
    fun closedWindowCount(): Int = closedWindowCount

    /** The measured statistic itself (mean of per-window peak hop-RMS), 0 until a window closes. Diagnostic. */
    fun meanPeakRms(): Float =
        if (closedWindowCount == 0) 0f else (peakRmsSum / closedWindowCount).toFloat()

    /**
     * True when the measured signal is so quiet that the un-clamped 60%-fill scale would be
     * below [MIN_FULL_SCALE] — i.e. the clamp floor, not the measurement, is what's setting
     * the axis, and the trace will fill LESS than 60% of the height. Surfaced in the on-screen
     * caption so a device whose USB audio path runs quiet (seen on study Samsung units) is
     * diagnosable at a glance instead of just "the scaling doesn't work".
     */
    fun isClampedAtMin(): Boolean =
        closedWindowCount > 0 && (meanPeakRms() / TARGET_FILL_FRACTION) < MIN_FULL_SCALE

    /**
     * The axis full-scale the measurements currently call for (un-smoothed). Until the first
     * window closes this is [initialFullScale].
     */
    fun targetFullScale(): Float {
        if (closedWindowCount == 0) return initialFullScale
        val meanPeakRms = (peakRmsSum / closedWindowCount).toFloat()
        return (meanPeakRms / TARGET_FILL_FRACTION).coerceIn(MIN_FULL_SCALE, MAX_FULL_SCALE)
    }

    /**
     * The value the recorder should actually put on the axis this UI update: one easing step
     * from the last returned value toward [targetFullScale]. Call exactly once per UI update.
     */
    fun smoothedFullScale(): Float {
        smoothedValue += (targetFullScale() - smoothedValue) * SMOOTHING_PER_UPDATE
        return smoothedValue
    }

    /** Start-of-session reset — new recording, new statistics, axis back at the neutral start. */
    fun reset() {
        hopSumSquares = 0.0
        hopCount = 0
        windowPeakRms = 0f
        windowSampleCount = 0
        closedWindowCount = 0
        peakRmsSum = 0.0
        smoothedValue = initialFullScale
    }
}
