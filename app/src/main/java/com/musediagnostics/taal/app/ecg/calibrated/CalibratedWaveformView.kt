package com.musediagnostics.taal.app.ecg.calibrated

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.FrameLayout
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import kotlin.math.abs
import kotlin.math.roundToInt

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
         * Fix C — derives a min/max bucket size so roughly one bucket (2 points: min+max)
         * lands per horizontal pixel, instead of a fixed constant tuned for one specific paper
         * speed. A fixed bucket size stops matching pixel density the moment paper speed
         * changes — too small a bucket over-samples and the trace reads as a filled band
         * rather than a line (what happened when Fix B doubled paper speed against a bucket
         * still tuned for the old, slower speed); too large under-samples and looks jagged.
         *
         * Returns -1 if [pxPerMmX] (or the other inputs) aren't valid yet — callers must fall
         * back to a pre-tuned constant in that case rather than dividing by zero.
         */
        fun deriveBucketSize(sampleRate: Float, paperSpeedMmPerSecond: Float, pxPerMmX: Float): Int {
            if (sampleRate <= 0f || paperSpeedMmPerSecond <= 0f || pxPerMmX <= 0f) return -1
            val pixelsPerSecond = paperSpeedMmPerSecond * pxPerMmX
            if (pixelsPerSecond <= 0f) return -1
            return maxOf(1, (sampleRate / (pixelsPerSecond * 2f)).roundToInt())
        }

        /**
         * Fix D — variant of [deriveBucketSize] for the player's zoom-driven re-bucketing.
         * [deriveBucketSize] derives from paper speed, which only describes the load-time 1x
         * view; once the user pinch-zooms, the number of samples actually visible across the
         * plot width changes, and a bucket size still tuned for the 1x view either shows a
         * static-looking sawtooth (bucket far larger than what's now visible) or wastes work
         * (bucket far smaller). This derives directly from whatever is currently visible.
         *
         * Returns -1 if either input isn't valid (e.g. plot not laid out yet) — callers must
         * fall back rather than divide by zero.
         */
        fun deriveBucketSizeForVisibleRange(visibleSampleCount: Float, plotWidthPx: Float): Int {
            if (visibleSampleCount <= 0f || plotWidthPx <= 0f) return -1
            return maxOf(1, (visibleSampleCount / (plotWidthPx * 2f)).roundToInt())
        }

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
