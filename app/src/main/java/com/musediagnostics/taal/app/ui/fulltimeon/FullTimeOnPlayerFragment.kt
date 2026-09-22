package com.musediagnostics.taal.app.ui.fulltimeon

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
import com.musediagnostics.taal.app.databinding.FragmentFulltimeonPlayerBinding
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
 * Clone of [com.musediagnostics.taal.app.ui.calibrated.CalibratedPlayerFragment] under a new
 * screen name (user request, 2026-08-20) — behavior is currently identical to that fragment,
 * just in its own package/nav destinations so it can be changed independently without touching
 * the Calibrated screens. See docs/notes/CALIBRATED_SCREENS_HANDOFF.md for the full history of
 * everything this was copied from (fixed-scale axis, Fix D zoom re-bucketing, pinch/Apply/Reset
 * graph calibration).
 */
class FullTimeOnPlayerFragment : Fragment() {

    private var _binding: FragmentFulltimeonPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    private var currentWindowSeconds = 4f

    // Smoothed camera-follow position during playback — see onPlaybackProgress. Reset to 0
    // wherever the chart snaps back to its start-centered framing.
    private var displayedPlaybackTime = 0f

    // Kept in memory for zoom-driven re-bucketing (rebucketForCurrentZoom). Never re-read from
    // disk after the initial load.
    private var decodedSamples: FloatArray? = null
    private var decodedSampleRate: Float = INPUT_SAMPLE_RATE
    private var waveformDataSet: LineDataSet? = null // persistent ref, mutated in place on re-bucket
    private var lastAppliedBucketSize = -1
    private var rebucketJob: Job? = null

    // Full length of the loaded file — the max-zoom-out cap (below) uses this so pinching all
    // the way out can "squeeze" to see the entire recording at once, not just the default
    // window (user request: zoom was one-directional — could only go bigger/narrower, never
    // smaller/wider than the default).
    private var loadedDurationSecs = 0

    // Per-device calibration override (set by pinching the graph, then pressing applyButton) —
    // null means "use the grid-derived default window." Read once at setup, kept in sync by the
    // Apply/Reset handlers. X (visibleSeconds) only now — Y is a fixed constant matching
    // production PlayerFragment exactly (see FIXED_Y_FULL_SCALE), not read from here anymore
    // (user request: match production's actual sizing behavior).
    private var calibrationOverride: GraphCalibration.Override? = null

    // True once the user has actually pinched/panned since this screen loaded (or since the
    // last Apply/Reset). There's no separate "Done" button — Apply is the only action — so
    // pressing it without having touched the graph at all must be a no-op (user request):
    // nothing to save, nothing changed. Set in onChartGestureEnd, cleared after a real Apply
    // and after Reset.
    private var hasUserAdjustedView = false

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val TARGET_POINT_BUDGET = 3000 // same total-point budget as production's maxPoints
        // Ported verbatim from production PlayerFragment (user request, 2026-08-21) — a plain
        // fixed constant, never adaptive, no per-file peak scan (confirmed directly against
        // PlayerFragment.kt: it hardcodes ±0.5 at setup and never touches axisMinimum/Maximum
        // again). Must stay identical to FullTimeOnRecordingFragment's own locked value once its
        // warmup completes only coincidentally — the two screens use different mechanisms now
        // (Recorder: warmup/lock; Player: this constant), exactly mirroring production's own
        // Recording/Player split.
        private const val FIXED_Y_FULL_SCALE = 0.5f

        // Default visible time window in seconds — copied verbatim from production
        // PlayerFragment's own hardcoded setVisibleXRangeMaximum(4f) (user request,
        // 2026-08-21: match production's speed/shape/working exactly). Used only when no
        // calibration override is saved; a saved Time Zoom override (pinch + Apply) still
        // takes priority, applies live to the Recorder too, and Reset returns here.
        private const val DEFAULT_WINDOW_SECONDS = 4f

        // Camera-follow smoothing. Fraction of the remaining gap to the real playback position
        // closed per progress callback — lower = gentler/slower-feeling follow, 1.0 = instant
        // snap.
        private const val FOLLOW_SMOOTHING = 0.15f
        // Trace stroke width in dp.
        private const val TRACE_LINE_WIDTH_DP = 3.0f

        // Floor used by forceVisibleSeconds() to relax the max-zoom-in bound back to
        // effectively unlimited after forcing an exact Time Zoom width — see that function.
        private const val MIN_VISIBLE_SECONDS = 0.3f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFulltimeonPlayerBinding.inflate(inflater, container, false)
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
            // reads it — setupWaveformChart() above already applied paper speed + DPI
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
            findNavController().navigate(R.id.action_fullTimeOnPlayer_to_equalizer, bundle)
        }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }

        // Apply saves whatever pinch-zoom state the graph is currently showing as this device's
        // default and goes straight back to the Recorder to see it applied live. There's no
        // separate "Done" button, so if the user never touched the graph at all, Apply must not
        // silently (re-)save the same default as if something had changed (user request) —
        // it still navigates back, just skips the save.
        binding.applyButton.setOnClickListener {
            if (hasUserAdjustedView) {
                val chart = binding.calibratedWaveformView.chart
                val effectiveVisibleSeconds = chart.highestVisibleX - chart.lowestVisibleX
                val effectiveYFullScale = currentEffectiveYFullScale()
                if (effectiveVisibleSeconds > 0f && effectiveYFullScale > 0f) {
                    GraphCalibration.saveOverride(requireContext(), effectiveVisibleSeconds, effectiveYFullScale)
                    calibrationOverride = GraphCalibration.Override(effectiveVisibleSeconds, effectiveYFullScale)
                }
                hasUserAdjustedView = false
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

        calibrationOverride = GraphCalibration.getOverride(requireContext())

        // Fixed ±0.5 by default, exactly like production PlayerFragment's own setup — never
        // adaptive, no per-file peak scan. A saved calibration (pinch + Apply) overrides that
        // default and applies here and on the Recorder both (user request, 2026-08-21) — this
        // read was accidentally dropped when FIXED_Y_FULL_SCALE was ported in; restored.
        val effectiveYFullScale = calibrationOverride?.yFullScale ?: FIXED_Y_FULL_SCALE
        chart.axisLeft.axisMinimum = -effectiveYFullScale
        chart.axisLeft.axisMaximum = effectiveYFullScale

        // 50mm/s — see FullTimeOnRecordingFragment's copy of this comment for the full
        // reasoning (reverted after two failed speed guesses; DEFAULT_WINDOW_SECONDS below is
        // the actual fix for "too fast"). Must match FullTimeOnRecordingFragment exactly, so a
        // recording looks the same live as it does in review.
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_50)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        // User can pan/zoom to inspect the trace with two fingers, in both directions:
        // horizontal pinch for time (Time Zoom), vertical pinch for peak height (Peak Size).
        // MPAndroidChart scales Y via its own touch-matrix (viewPortHandler.scaleY), a separate
        // mechanism from the fixed axis bounds — currentEffectiveYFullScale() folds the two
        // together (divide by scaleY) so Apply always reads the true effective state regardless
        // of how the user got there.
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleXEnabled(true)
        chart.setScaleYEnabled(true)

        // Re-bucket for the new zoom/pan level once the gesture settles. Debounced by
        // construction: onChartGestureEnd fires once per discrete gesture, not per frame, so
        // this never runs mid-pinch and can't cause scroll stutter.
        chart.setOnChartGestureListener(object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {
                hasUserAdjustedView = true
                rebucketForCurrentZoom()
            }
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) {}
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {}
        })

        waveformView.onVisibleSecondsChanged = { _ ->
            // The actual "too fast" fix (user request, 2026-08-21): default to production
            // PlayerFragment's own fixed DEFAULT_WINDOW_SECONDS (4s) instead of the grid's
            // physically-derived `seconds`. A saved Time Zoom override still wins over both —
            // physical derivation still runs every time (e.g. on rotation), it's just
            // superseded either way. `seconds` is intentionally unused as a fallback now.
            currentWindowSeconds = calibrationOverride?.visibleSeconds ?: DEFAULT_WINDOW_SECONDS
            if (chart.data == null) {
                resetToDummyData()
            } else {
                // Real waveform already loaded (e.g. window changed on rotation) — reapply
                // the corrected max-zoom-out cap and re-center rather than silently drifting
                // stale. Deliberately NOT setVisibleXRangeMinimum — that would lock the range
                // to exactly `currentWindowSeconds` and disable pinch-zoom entirely. The ceiling
                // itself is never smaller than the whole file (see renderWaveformEntries) so
                // squeezing all the way out to see the full recording always stays possible.
                chart.setVisibleXRangeMaximum(maxOf(currentWindowSeconds, loadedDurationSecs.toFloat()))
                chart.centerViewTo(currentWindowSeconds / 2f, 0f, YAxis.AxisDependency.LEFT)
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
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude (fixed) · DPI: $status"
    }

    /**
     * Graph calibration is pinch-only — this just wires the status readout and Reset. Applying
     * a calibration happens via [R.id.applyButton] in the bottom bar (see onViewCreated), which
     * reads whatever the pinch-tuned view currently shows.
     */
    private fun setupGraphCalibrationPanel() {
        updateCalibrationStatusText()
        binding.resetCalibrationButton.setOnClickListener {
            GraphCalibration.clearOverride(requireContext())
            calibrationOverride = null
            applyBuiltInDefaultScale()
            // Back to the untouched default — a bare Apply right after this must still no-op.
            hasUserAdjustedView = false
            updateCalibrationStatusText()
            Toast.makeText(requireContext(), "Reset to default", Toast.LENGTH_SHORT).show()
        }
    }

    /** Re-applies FIXED_Y_FULL_SCALE and the grid-derived default window, bypassing any override — used by Reset. */
    private fun applyBuiltInDefaultScale() {
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart
        // Clears any pinch-driven X/Y viewport zoom (scaleX/scaleY) before reapplying the
        // built-in defaults below — otherwise a prior vertical pinch would still be layered on
        // top of the reset axis bounds and Reset wouldn't actually look reset.
        chart.fitScreen()
        chart.axisLeft.axisMinimum = -FIXED_Y_FULL_SCALE
        chart.axisLeft.axisMaximum = FIXED_Y_FULL_SCALE
        // Axis bounds alone don't move the already-plotted trace without this.
        chart.notifyDataSetChanged()
        // Re-fires onVisibleSecondsChanged with calibrationOverride already cleared above, so
        // currentWindowSeconds lands back on the physical grid-derived value.
        waveformView.recomputeVisibleSeconds()
        // onVisibleSecondsChanged only reapplies setVisibleXRangeMaximum (a zoom-OUT cap, see
        // forceVisibleSeconds) — if the user had pinched/slid to a *wider* view than the
        // default before hitting Reset, that alone wouldn't visually snap back. Force it.
        forceVisibleSeconds(currentWindowSeconds)
        // forceVisibleSeconds's own setVisibleXRangeMaximum(seconds) call just narrowed the
        // zoom-out ceiling back down to the default window as a side effect of forcing the
        // view there — widen it back to the full file so squeezing out to see the whole
        // recording is still possible after Reset, not just before it.
        chart.setVisibleXRangeMaximum(maxOf(currentWindowSeconds, loadedDurationSecs.toFloat()))
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
     * that's narrower or wider than the current view. `setVisibleXRangeMaximum` alone only sets
     * a zoom-out ceiling — it can't widen an already-narrower view. Both bounds are pinned to
     * [seconds] momentarily (forcing scaleX to exactly the target in either direction), then the
     * lower bound is relaxed back to effectively unlimited so pinch-zoom-in still works after.
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
     * Clone of CalibratedPlayerFragment.loadFullWaveform(). Same WAV-header sample-rate parsing
     * (bytes 24-27, little-endian, fallback 44100), min/max-bucketed downsampling.
     *
     * [recordedPreAmpDb] undoes the same gain the recorder applied when this file was made, so a
     * recording looks the same size in the Player as it did live. 0 means unknown/not passed —
     * no compensation applied. Only works for files that arrived with that bundle arg (i.e.
     * reviewing a just-recorded file) — a recording reopened later from the library still won't
     * self-correct.
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

            // Undo the recorder's pre-amp gain, same formula FullTimeOnRecordingFragment uses
            // live — makes this file render at the same size it showed on the recording screen,
            // regardless of what dB was used.
            if (recordedPreAmpDb > 0) {
                val preAmpGain = Math.pow(10.0, recordedPreAmpDb / 20.0).toFloat()
                if (preAmpGain > 1.001f) {
                    for (j in samples.indices) samples[j] = samples[j] / preAmpGain
                }
            }

            // Derive so ~one min/max pair lands per horizontal pixel at the current paper
            // speed. Apply TARGET_POINT_BUDGET as a ceiling only: enlarge the bucket if the
            // derived value would produce more than the budget on a long file, but never
            // shrink below it.
            val derivedBucket = CalibratedWaveformView.deriveBucketSize(fileSampleRate, paperSpeedMmPerSecond, pxPerMmX)
            val budgetCeilingBucket = maxOf(1, totalSamples / (TARGET_POINT_BUDGET / 2))
            val bucketSize = if (derivedBucket > 0) maxOf(derivedBucket, budgetCeilingBucket) else budgetCeilingBucket
            val entries = CalibratedWaveformView.downsampleMinMax(samples, bucketSize) { idx ->
                idx.toFloat() / fileSampleRate
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                // Keep the decoded file in memory for zoom-driven re-bucketing, and remember
                // the bucket size just used so the first gesture-end after load doesn't
                // redundantly re-derive an unchanged value.
                decodedSamples = samples
                decodedSampleRate = fileSampleRate
                lastAppliedBucketSize = bucketSize
                renderWaveformEntries(ArrayList(entries), durationSecs)
            }
        }
    }

    private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int) {
        binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
        loadedDurationSecs = durationSecs
        val dataSet = LineDataSet(entries, "Waveform").apply {
            color = Color.parseColor("#2D7DD2")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = TRACE_LINE_WIDTH_DP
            mode = LineDataSet.Mode.LINEAR
        }
        waveformDataSet = dataSet // persistent ref, mutated in place on re-bucket
        // currentWindowSeconds is kept in sync by onVisibleSecondsChanged (set up in
        // setupWaveformChart, called before this) — including the case where layout hadn't
        // happened yet when this loaded; that callback will re-apply the range once it does.
        // Y-axis is fixed — already set once in setupWaveformChart(), not touched here.
        val chart = binding.calibratedWaveformView.chart
        chart.data = LineData(dataSet)
        // Cap max zoom-out only — no Minimum lock, so pinch-zoom works (see setupWaveformChart).
        // The ceiling is never smaller than the whole file (user request: pinching out used to
        // stop at the default window — "large only" — with no way to squeeze further; now it
        // can go all the way out to the full recording).
        val maxVisibleSeconds = maxOf(currentWindowSeconds, loadedDurationSecs.toFloat())
        chart.setVisibleXRangeMaximum(maxVisibleSeconds)
        chart.centerViewTo(currentWindowSeconds / 2f, 0f, YAxis.AxisDependency.LEFT)
        chart.invalidate()
    }

    /**
     * Recomputes the min/max bucket from whatever X range is currently visible (post-zoom/pan)
     * and re-buckets the whole decoded file at that density, targeting ~one min/max pair per
     * horizontal pixel. Re-buckets the whole file (not just the visible slice) so panning
     * within an unchanged zoom level doesn't need to re-run this — only an actual zoom change
     * does, since [lastAppliedBucketSize] short-circuits a no-op. Runs off the main thread since
     * re-bucketing a long file is real work; only the dataset swap happens on Main.
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
        // Same budget-ceiling pattern as the load-time bucket — enlarge to stay within
        // TARGET_POINT_BUDGET on a long file, never shrink below the derived value.
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
                            // snapping to it every callback.
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
                // filePath already points inside filesDir/saved/ here (this branch only runs
                // when isNewRecording == false, i.e. an already-saved recording reopened) —
                // there is nothing left to save, so this just confirms and backs out.
                PlayerSaveDiscardDialog.Action.SAVE -> {
                    Toast.makeText(requireContext(), "Recording already saved", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
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
