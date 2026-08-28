package com.musediagnostics.taal.app.ecg.pcgscale

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fork of [com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView] (protected,
 * not modified) for the PcgScale screens: stacks [paperView] (time-defined 1s/0.2s grid)
 * behind [chart] (MPAndroidChart trace) at identical (0,0,0,0) bounds, with the chart's own
 * grid/background disabled and its viewport offsets zeroed so the plot rect matches the view
 * rect exactly (§4.2 of the waveform reference — margins alone can't achieve this).
 *
 * Differences from the Calibrated original, all deliberate:
 *
 *  - The visible window derives from [PcgTimeScale] (fixed pixels-per-second chosen once from
 *    the plot width), not from a mm/paper-speed grid. Time NEVER distorts: this view locks
 *    BOTH setVisibleXRangeMaximum and Minimum to the derived window, which pins the chart's
 *    horizontal scale outright — pinch-zoom is impossible by construction, not by convention.
 *    (The Calibrated player deliberately avoided the Minimum lock to keep pinch-zoom alive;
 *    this screen family wants the exact opposite.)
 *  - The grid scrolls WITH the trace: this view owns the chart's OnChartGestureListener and
 *    mirrors chart.lowestVisibleX onto paperView.scrollOffsetSeconds on every translate/fling
 *    frame, so grid lines and labels stay pinned to their absolute recording times. Fragments
 *    that move the camera programmatically (page-snap while recording, smoothed follow during
 *    playback) must call [syncGridToChart] after each move — the gesture listener only sees
 *    user gestures. Because this view owns the one gesture-listener slot MPAndroidChart has,
 *    fragments must NOT call chart.setOnChartGestureListener themselves — use
 *    [onGestureEnd]/[onGestureTranslate] instead if they need gesture hooks.
 *  - No Fix-D re-bucketing machinery: with zoom impossible, the bucket derived for the fixed
 *    window at load/session start stays correct forever, so the bug class ("bucket must track
 *    the effective window") has no trigger left. The bucket helpers below are forked copies of
 *    the Calibrated statics so deleting either package never breaks the other.
 */
class PcgScaleWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val paperView: PcgScaleEcgPaperView
    val chart: LineChart

    /** Fired whenever the derived visible-window duration changes (post-layout, or on resize/rotation). */
    var onVisibleSecondsChanged: ((Float) -> Unit)? = null

    /** Optional fragment hooks, forwarded from the gesture listener this view owns. */
    var onGestureEnd: (() -> Unit)? = null
    var onGestureTranslate: (() -> Unit)? = null

    /**
     * Seconds the plot spans. Read at (re)layout when the [PcgTimeScale] is built; set it
     * before first layout if a screen wants a non-default window. Changing it after layout
     * takes effect on the next size change only — by design, nothing rescales time mid-session.
     */
    var defaultVisibleSeconds: Float = PcgTimeScale.DEFAULT_VISIBLE_SECONDS

    private var timeScale: PcgTimeScale? = null
    private var lastVisibleSeconds: Float = -1f

    init {
        paperView = PcgScaleEcgPaperView(context)
        addView(paperView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        chart = LineChart(context)
        configureChartDefaults(chart)
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        chart.setOnChartGestureListener(object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, g: ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, g: ChartTouchListener.ChartGesture?) {
                syncGridToChart()
                onGestureEnd?.invoke()
            }
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, vx: Float, vy: Float) {}
            override fun onChartScale(me: MotionEvent?, sx: Float, sy: Float) {}
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {
                // Fires per drag frame AND per fling-deceleration frame — the grid tracks the
                // trace continuously through a scroll, never just at gesture end.
                syncGridToChart()
                onGestureTranslate?.invoke()
            }
        })
    }

    /** §4.2 — identical to the Calibrated original: zero the viewport offsets so plot rect == view rect. */
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
     * (Re)build the [PcgTimeScale] from the laid-out width, hand it to the grid, and lock the
     * chart's horizontal range to exactly the derived window (both bounds — see class doc).
     * The window is derived from the time scale, never a free-floating constant, so grid,
     * trace, and labels stay single-sourced.
     */
    fun recomputeVisibleSeconds() {
        if (width <= 0) return
        val scale = PcgTimeScale.fromPlotWidth(width.toFloat(), defaultVisibleSeconds) ?: return
        timeScale = scale
        paperView.timeScale = scale
        val seconds = scale.visibleSeconds(width.toFloat())
        if (seconds > 0f && abs(seconds - lastVisibleSeconds) > 1e-4f) {
            lastVisibleSeconds = seconds
            applyRangeLock()
            onVisibleSecondsChanged?.invoke(seconds)
        }
    }

    /**
     * Pin the chart to exactly the derived window. MPAndroidChart stores range locks against
     * the data's axis range, so fragments must call this again right after installing a new
     * LineData (the recorder's per-update path and the player's load path both do).
     */
    fun applyRangeLock() {
        val seconds = lastVisibleSeconds
        if (seconds <= 0f) return
        chart.setVisibleXRangeMaximum(seconds)
        chart.setVisibleXRangeMinimum(seconds)
    }

    /** Mirror the chart's current left edge onto the grid so lines/labels stay time-pinned. */
    fun syncGridToChart() {
        paperView.scrollOffsetSeconds = chart.lowestVisibleX
    }

    /** Best-effort synchronous read; may return the default before first layout. */
    fun currentVisibleSeconds(): Float =
        if (lastVisibleSeconds > 0f) lastVisibleSeconds else defaultVisibleSeconds

    /** The current time scale, or null before first layout. */
    fun currentTimeScale(): PcgTimeScale? = timeScale

    companion object {
        /**
         * Forked copy of CalibratedWaveformView.ringTrimMinX (see that doc) — the page-based
         * ring-trim bound for a scrolling live trace, kept local so this package is
         * self-contained and delete-safe in both directions.
         */
        fun ringTrimMinX(latestX: Float, windowSeconds: Float): Float {
            if (windowSeconds <= 0f) return 0f
            val currentPage = (latestX / windowSeconds).toInt()
            val minXToKeep = (currentPage - 1) * windowSeconds
            return if (minXToKeep > 0f) minXToKeep else 0f
        }

        /**
         * Forked copy of CalibratedWaveformView.deriveBucketSizeForVisibleRange (Fix C/D
         * heritage): ~one min/max bucket (2 points) per horizontal pixel for whatever window
         * is in force. On these screens the window never changes after layout, so this is
         * derived once per session/load and stays correct. Returns -1 if inputs aren't valid
         * yet — callers must fall back rather than divide by zero.
         */
        fun deriveBucketSizeForVisibleRange(visibleSampleCount: Float, plotWidthPx: Float): Int {
            if (visibleSampleCount <= 0f || plotWidthPx <= 0f) return -1
            return maxOf(1, (visibleSampleCount / (plotWidthPx * 2f)).roundToInt())
        }

        /**
         * Forked copy of CalibratedWaveformView.downsampleMinMax (§4.5): per bucket emit min
         * and max in true time order, so a sharp S1 transient can never fall entirely between
         * two kept samples and vanish from the display.
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
