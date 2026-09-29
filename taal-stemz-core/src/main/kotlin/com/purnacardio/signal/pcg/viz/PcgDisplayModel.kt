package com.purnacardio.signal.pcg.viz

import com.purnacardio.signal.pcg.SegmentationResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One contiguous run of a single cardiac state, in normalised x. */
data class StateBand(
    /** 0=S1, 1=systole, 2=S2, 3=diastole — matches [SegmentationResult.stateLabels]. */
    val state: Int,
    /** Left edge, 0..1 across the plotted window. */
    val xStart: Float,
    /** Right edge, 0..1. */
    val xEnd: Float
)

/**
 * Everything needed to draw one PCG strip, in **normalised coordinates** — x and y both 0..1.
 *
 * Renderer-agnostic on purpose. The same model drives the Android View, a Compose Canvas, a
 * server-side SVG or a test harness, and because all the geometry is decided here those renderers
 * cannot drift apart: each one only maps 0..1 onto its own rectangle and fills.
 *
 * It is also what makes the drawing testable without a device — the arithmetic that actually goes
 * wrong (rate conversion, window clipping, decimation) is all in [PcgDisplay.build], on the JVM.
 *
 * @property columns Number of waveform columns. One per device pixel is the useful choice.
 * @property top Upper edge of the waveform per column: 0 is the top of the lane, 0.5 the zero line.
 * @property bottom Lower edge per column. Always at or below [top].
 * @property bands State runs covering the window, in order, without gaps.
 * @property s1MarkersX Detected S1 peak positions, normalised x, ascending.
 * @property s2MarkersX Detected S2 peak positions, normalised x, ascending.
 * @property startSec Window start in the recording's own timebase.
 * @property endSec Window end.
 * @property clippedFraction Share of samples in the window that exceeded the display ceiling and
 *   were flattened. Small values are normal — see the scaling note on [PcgDisplay.build]. A large
 *   value means one transient dominates the recording, which is worth surfacing as a capture
 *   quality hint rather than silently drawing a hairline.
 */
class PcgDisplayModel(
    val columns: Int,
    val top: FloatArray,
    val bottom: FloatArray,
    val bands: List<StateBand>,
    val s1MarkersX: FloatArray,
    val s2MarkersX: FloatArray,
    val startSec: Double,
    val endSec: Double,
    val clippedFraction: Float
) {
    /** Seconds spanned by the plotted window. */
    val durationSec: Double get() = endSec - startSec

    /** True when there is nothing to draw — show an empty state rather than a flat line. */
    val isEmpty: Boolean get() = columns == 0 || bands.isEmpty()
}

/**
 * Builds a [PcgDisplayModel] from raw audio plus a [SegmentationResult].
 *
 * ## Why this is not a one-liner inside the view
 *
 * Three things here are easy to get wrong on a first attempt, and each produces a chart that looks
 * plausible while being wrong:
 *
 * 1. **Decimation.** A 20 s recording at 44.1 kHz is about 880,000 samples against maybe 1,000
 *    pixels. Drawing every Nth sample discards the peaks — and in a PCG the peaks *are* S1 and S2,
 *    so the heart sounds visibly shrink as the view gets narrower. Each column here keeps the true
 *    min and max of the samples falling inside it, so the envelope survives at any width.
 *
 * 2. **Amplitude scaling.** Scaling to the loudest sample hands the whole chart to one transient:
 *    a knock or a clothing rustle flattens the heartbeat to a hairline. This scales to the 99th
 *    percentile and lets the rest clip, which keeps ordinary content legible. It is a *display*
 *    choice only, independent of the normalisation the model applies to its own input.
 *
 * 3. **Rate conversion.** Three timebases meet here: the audio's sample rate, the segmenter's
 *    200 Hz state frames, and the 2 kHz indices its peak markers use. Confusing them shifts the
 *    bands against the waveform by a constant factor, which reads as "the model is inaccurate".
 */
object PcgDisplay {

    /** Percentile of |sample| that maps to full deflection. */
    private const val DISPLAY_PERCENTILE = 0.99f

    /** Cap on how many samples are inspected to estimate that percentile. */
    private const val PERCENTILE_SAMPLE_CAP = 20_000

    /**
     * @param audio Mono samples at [sampleRate]. Amplitude scale is irrelevant.
     * @param sampleRate The rate [audio] was captured at.
     * @param result The segmentation to overlay.
     * @param columns Waveform columns to produce; pass the view's pixel width. Below 1 gives an
     *   empty model.
     * @param windowStartSec Left edge of the window in the recording's timebase. Clamped.
     * @param windowEndSec Right edge. Defaults to the end of the recording. Clamped.
     */
    fun build(
        audio: FloatArray,
        sampleRate: Int,
        result: SegmentationResult,
        columns: Int,
        windowStartSec: Double = 0.0,
        windowEndSec: Double = Double.MAX_VALUE
    ): PcgDisplayModel {
        val totalSec = if (sampleRate > 0) audio.size.toDouble() / sampleRate else 0.0
        val start = windowStartSec.coerceIn(0.0, totalSec)
        val end = min(windowEndSec, totalSec).coerceAtLeast(start)

        if (columns < 1 || sampleRate <= 0 || audio.isEmpty() || end <= start) {
            return PcgDisplayModel(
                0, FloatArray(0), FloatArray(0), emptyList(),
                FloatArray(0), FloatArray(0), start, end, 0f
            )
        }

        val first = (start * sampleRate).toInt().coerceIn(0, audio.size - 1)
        val last = (end * sampleRate).toInt().coerceIn(first + 1, audio.size)
        val span = last - first

        val ceiling = displayCeiling(audio, first, last)

        val top = FloatArray(columns)
        val bottom = FloatArray(columns)
        var clipped = 0L
        for (c in 0 until columns) {
            // Column bounds in samples. Every sample belongs to exactly one column, and a column
            // narrower than one sample still reads the sample under it — so a zoomed-in view keeps
            // drawing instead of collapsing onto the centre line.
            val a = first + (span.toLong() * c / columns).toInt()
            val b = (first + (span.toLong() * (c + 1) / columns).toInt()).coerceAtLeast(a + 1)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in a until min(b, audio.size)) {
                val v = audio[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
                if (abs(v) > ceiling) clipped++
            }
            if (lo > hi) { lo = 0f; hi = 0f }
            // y grows downward, so a positive sample maps to a smaller y than the centre line.
            top[c] = (0.5f - (hi / ceiling).coerceIn(-1f, 1f) * 0.5f).coerceIn(0f, 1f)
            bottom[c] = (0.5f - (lo / ceiling).coerceIn(-1f, 1f) * 0.5f).coerceIn(0f, 1f)
        }

        return PcgDisplayModel(
            columns = columns,
            top = top,
            bottom = bottom,
            bands = bandsIn(result, start, end),
            s1MarkersX = markersIn(result.s1PeakSamples2k, start, end),
            s2MarkersX = markersIn(result.s2PeakSamples2k, start, end),
            startSec = start,
            endSec = end,
            clippedFraction = if (span > 0) (clipped.toDouble() / span).toFloat() else 0f
        )
    }

    /**
     * The amplitude mapping to full deflection: the 99th percentile of |sample|, never zero.
     *
     * Estimated from a strided subsample rather than by sorting the window. Sorting 880,000 floats
     * on every resize is a visible stall, and a percentile does not need every sample to be correct
     * to well inside one pixel.
     */
    private fun displayCeiling(audio: FloatArray, first: Int, last: Int): Float {
        val span = last - first
        val stride = max(1, span / PERCENTILE_SAMPLE_CAP)
        val n = (span + stride - 1) / stride
        if (n <= 0) return 1f
        val mags = FloatArray(n)
        var j = 0
        var i = first
        while (i < last && j < n) {
            mags[j++] = abs(audio[i])
            i += stride
        }
        if (j == 0) return 1f
        val head = if (j == n) mags else mags.copyOf(j)
        head.sort()
        val p = head[((head.size - 1) * DISPLAY_PERCENTILE).toInt().coerceIn(0, head.size - 1)]
        // An all-silent window would otherwise divide by zero and paint NaN across the lane.
        return if (p > 1e-12f) p else head.last().takeIf { it > 1e-12f } ?: 1f
    }

    /** State runs clipped to the window, as normalised x. */
    private fun bandsIn(result: SegmentationResult, start: Double, end: Double): List<StateBand> {
        val labels = result.stateLabels
        val fs = result.featFs
        if (labels.isEmpty() || fs <= 0 || end <= start) return emptyList()

        val fromFrame = (start * fs).toInt().coerceIn(0, labels.size - 1)
        val toFrame = (end * fs).toInt().coerceIn(fromFrame + 1, labels.size)
        val width = end - start

        val out = ArrayList<StateBand>()
        var runStart = fromFrame
        for (f in fromFrame + 1..toFrame) {
            if (f == toFrame || labels[f] != labels[runStart]) {
                // Clamp to the window: the first and last runs are usually cut mid-state.
                val x0 = (((runStart.toDouble() / fs) - start) / width).coerceIn(0.0, 1.0)
                val x1 = (((f.toDouble() / fs) - start) / width).coerceIn(0.0, 1.0)
                if (x1 > x0) out.add(StateBand(labels[runStart], x0.toFloat(), x1.toFloat()))
                runStart = f
            }
        }
        return out
    }

    /** Peak markers, held as 2 kHz sample indices, filtered to the window and normalised. */
    private fun markersIn(samples2k: List<Int>, start: Double, end: Double): FloatArray {
        val width = end - start
        if (width <= 0.0) return FloatArray(0)
        val xs = ArrayList<Float>(samples2k.size)
        for (s in samples2k) {
            val t = s / 2000.0
            if (t in start..end) xs.add(((t - start) / width).toFloat())
        }
        return xs.toFloatArray()
    }
}
