package com.musediagnostics.taal.app.ecg.pcgscale

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Amplitude-derived Y-axis full-scale for the PcgScale screens: the axis is sized so the
 * heart sounds' typical DRAWN maxima fill ~[TARGET_FILL_FRACTION] (50%) of the graph's half
 * height — excluding noise. Pure Kotlin, no Android dependency, plain-JVM unit-testable.
 *
 * REV 3 (2026-08-28) — calibrate on the drawn quantity, not on RMS, and merge the ledger's
 * Fix 1 (see docs/notes/PCGSCALE_LOCAL_FIXES_LEDGER.md). Rev 2 set the axis from mean peak
 * hop-RMS; on device that under-filled and, worse, under-filled by a DIFFERENT amount per
 * recording, because the trace draws raw sample peaks and a burst's peak sits 2–3× above its
 * 50ms RMS (crest factor) — a ratio that varies with signal character (crisp S1 vs rumble vs
 * lung sounds). Targeting 60% on RMS therefore lands the visible envelope anywhere from ~40%
 * to clipping depending on the signal ("improved but not 60%, and not uniform"). The fix is
 * a division of labor:
 *
 *   ENERGY PICKS, AMPLITUDE CALIBRATES, MEDIAN + Kth-LARGEST PROTECT.
 *
 *  1. Short hops ([RMS_HOP_SECONDS] = 50ms): for each hop compute BOTH its RMS and its peak
 *     |sample|. A single-sample click's RMS is diluted ~1/sqrt(hopSamples), so it competes
 *     poorly in the energy ranking below.
 *  2. Per 5s window ([PEAK_WINDOW_SECONDS]): rank hops by RMS and take the
 *     [OUTLIER_REJECTION_K]th-largest (3rd) as the window's calibrating hop — this is the
 *     ledger's Fix 1, re-applied. A real heart-sound window has S1/S2 recurring 5–8 times,
 *     so the 3rd-loudest hop is still a genuine beat; a one-off transient (contact thud)
 *     elevates only 1–2 hops and can never reach 3rd place — even in the recording's very
 *     FIRST window, where no cross-window statistic exists yet to dilute it (measured
 *     pre-fix: a 0.05-amplitude signal with one 1.0 spike overshot 2.56×). The window's
 *     value is that calibrating hop's PEAK AMPLITUDE — the very thing the trace draws.
 *  3. Across windows: the MEDIAN of the per-window values (not the mean). A whole poisoned
 *     window (cough, repositioning bump) is rejected outright instead of averaged in, and
 *     quiet settling windows can't dilute the statistic — the two remaining sources of
 *     run-to-run non-uniformity in rev 2.
 *  4. fullScale = medianWindowPeak / [TARGET_FILL_FRACTION], clamped to
 *     [[MIN_FULL_SCALE], [MAX_FULL_SCALE]]. Because the statistic is now a drawn peak
 *     (2–3× the old RMS number), quiet devices hit the MIN clamp far less often than rev 2.
 *
 * Live use (recorder): [addSamples] per display buffer, [smoothedFullScale] once per UI
 * update — eases toward the target (same idea as the player camera's FOLLOW_SMOOTHING) so
 * the axis glides rather than popping when a window closes. Until the first window closes
 * the target is [initialFullScale] (the Calibrated screens' neutral 0.10), so a session
 * starts looking familiar and settles onto the measured scale.
 *
 * Completed-file use (player/review): [computeFullScaleForFile] runs the same stages over
 * the whole decoded recording — with many windows, the median is at full strength.
 *
 * This object holds no OS resources — no threads, handlers, or audio sessions. Pure state
 * mutated from the caller's thread; dropping the reference (or [reset] for a new session) is
 * the entire lifecycle.
 */
class PcgAmplitudeScale(
    private val sampleRate: Float,
    private val initialFullScale: Float = DEFAULT_INITIAL_FULL_SCALE
) {

    companion object {
        const val TARGET_FILL_FRACTION = 0.50f
        const val RMS_HOP_SECONDS = 0.05f
        const val PEAK_WINDOW_SECONDS = 5f

        // A window's calibrating hop is its Kth-largest by RMS, not its max — ledger Fix 1,
        // see class doc stage 2. K=3 tolerates up to two transient-elevated hops per window.
        const val OUTLIER_REJECTION_K = 3

        // Below this the input is treated as noise/silence and the axis refuses to shrink
        // further (prevents blowing pure noise up to 60%). Lowered from 0.02 to 0.005
        // (2026-08-28) after the study Samsung unit reported a still-small trace on rev 3:
        // its natural peak/TARGET_FILL_FRACTION target was landing BELOW the old 0.02 floor,
        // so the floor itself — not the measurement — was forcing an oversized axis (e.g. a
        // real peak of 0.008 naturally targets 0.0133, but the old floor clamped the axis up
        // to 0.02, drawing only 0.008/0.02 = 40% instead of the intended 60%). If a device
        // still shows "(MIN-CLAMPED)" with real heart sounds on the chest at this new floor,
        // lowering it further — or raising that device's input gain — is a deliberate policy
        // decision, not a bug fix.
        const val MIN_FULL_SCALE = 0.005f
        const val MAX_FULL_SCALE = 1.0f

        // Fraction of the remaining gap to the target closed per smoothedFullScale() call
        // (called once per audio-buffer UI update), so a new target lands without a visible snap.
        const val SMOOTHING_PER_UPDATE = 0.15f

        // The neutral session-start value, matching the Calibrated screens' FIXED_FULL_SCALE.
        const val DEFAULT_INITIAL_FULL_SCALE = 0.10f

        /**
         * Whole-recording variant for the player/review screens: median of per-5s-window
         * calibrating-hop peaks across the entire file, converted to an axis full-scale. A
         * trailing partial window is included so short files (< 5s) still measure.
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

    // Current hop accumulators: energy AND drawn peak.
    private var hopSumSquares = 0.0
    private var hopMaxAbs = 0f
    private var hopCount = 0

    // Current 5s window state: the K hops with the largest RMS seen so far this window, as
    // (rms, peakAmplitude) pairs. Unsorted; the pair with the minimum rms is the running
    // Kth-largest, which is all closeWindow() needs.
    private val topHops = ArrayList<Pair<Float, Float>>(OUTLIER_REJECTION_K + 1)
    private var windowSampleCount = 0

    // One value per closed window: the calibrating hop's peak amplitude. Bounded in practice
    // by the 300s max recording length → ≤ 60 entries; the median sort below is trivial.
    private val windowPeaks = ArrayList<Float>()

    // Eased display value, advanced by smoothedFullScale().
    private var smoothedValue = initialFullScale

    /** Feed samples in normalized -1..+1 units (the same units the chart's Y axis uses). */
    fun addSamples(data: FloatArray) {
        for (v in data) {
            hopSumSquares += (v * v).toDouble()
            val a = abs(v)
            if (a > hopMaxAbs) hopMaxAbs = a
            hopCount++
            windowSampleCount++
            if (hopCount >= hopSamples) closeHop()
            if (windowSampleCount >= windowSamples) closeWindow()
        }
    }

    private fun closeHop() {
        val rms = sqrt(hopSumSquares / hopCount).toFloat()
        // Keep the K loudest-by-energy hops; each remembers its own drawn peak.
        topHops.add(rms to hopMaxAbs)
        if (topHops.size > OUTLIER_REJECTION_K) {
            topHops.removeAt(topHops.indices.minByOrNull { topHops[it].first }!!)
        }
        hopSumSquares = 0.0
        hopMaxAbs = 0f
        hopCount = 0
    }

    private fun closeWindow() {
        // The Kth-largest-RMS hop calibrates; ITS peak amplitude is what the trace draws at
        // that moment (fewer than K hops only in a flushed sub-150ms tail — take what exists).
        val calibrator = topHops.minByOrNull { it.first }
        windowPeaks.add(calibrator?.second ?: 0f)
        topHops.clear()
        windowSampleCount = 0
    }

    /**
     * Close whatever partial hop/window is pending — used by [computeFullScaleForFile] so
     * the tail of a file (or an entire sub-5s file) contributes. Not called in the live
     * path, where the next buffer keeps filling the open window.
     */
    fun flushPartialWindow() {
        if (hopCount > 0) closeHop()
        if (windowSampleCount > 0) closeWindow()
    }

    /** How many full 5s windows have closed — 0 means the target is still [initialFullScale]. */
    fun closedWindowCount(): Int = windowPeaks.size

    /**
     * The measured statistic itself: the median across windows of the calibrating hop's
     * drawn peak amplitude. 0 until a window closes. Shown in the on-device diagnostics caption.
     */
    fun typicalPeakAmplitude(): Float {
        if (windowPeaks.isEmpty()) return 0f
        val sorted = windowPeaks.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    /**
     * True when the measured signal is so quiet that the un-clamped 60%-fill scale would be
     * below [MIN_FULL_SCALE] — i.e. the clamp floor, not the measurement, is setting the
     * axis, and the trace will fill LESS than 60% of the height. Surfaced in the on-screen
     * caption so a device whose USB audio path runs quiet is diagnosable at a glance.
     */
    fun isClampedAtMin(): Boolean =
        windowPeaks.isNotEmpty() && (typicalPeakAmplitude() / TARGET_FILL_FRACTION) < MIN_FULL_SCALE

    /**
     * The axis full-scale the measurements currently call for (un-smoothed). Until the first
     * window closes this is [initialFullScale]. A signal whose typical drawn maxima sit at
     * [typicalPeakAmplitude] then fills exactly [TARGET_FILL_FRACTION] of the half-height.
     */
    fun targetFullScale(): Float {
        if (windowPeaks.isEmpty()) return initialFullScale
        return (typicalPeakAmplitude() / TARGET_FILL_FRACTION).coerceIn(MIN_FULL_SCALE, MAX_FULL_SCALE)
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
        hopMaxAbs = 0f
        hopCount = 0
        topHops.clear()
        windowSampleCount = 0
        windowPeaks.clear()
        smoothedValue = initialFullScale
    }
}
