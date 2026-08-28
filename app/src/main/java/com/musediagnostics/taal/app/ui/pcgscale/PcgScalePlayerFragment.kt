package com.musediagnostics.taal.app.ui.pcgscale

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
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
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentPcgscalePlayerBinding
import com.musediagnostics.taal.app.ecg.pcgscale.PcgAmplitudeScale
import com.musediagnostics.taal.app.ecg.pcgscale.PcgScaleWaveformView
import com.musediagnostics.taal.app.ui.player.PlayerSaveDiscardDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Fork of [com.musediagnostics.taal.app.ui.calibrated.CalibratedPlayerFragment] (not
 * modified) for the PcgScale screens. Same player controls, save/discard flow and filter
 * guard; the differences, all deliberate and all confined to the graph:
 *
 *  - TIME grid with second labels ([com.musediagnostics.taal.app.ecg.pcgscale.PcgTimeScale]:
 *    large box = 1s, small box = 0.2s), scrolling in lockstep with the trace.
 *  - NO ZOOM, EVER: drag/scroll only. The visible window is range-locked to the grid-derived
 *    value on both bounds, so the horizontal scale physically cannot change — feature widths
 *    measured against the boxes are trustworthy at any scroll position. This deletes the
 *    Calibrated player's pinch-zoom, GraphCalibration Apply/Reset panel, and the whole Fix-D
 *    re-bucketing machinery (with the window fixed, the load-time bucket is always right).
 *    The whole recording deliberately never fits on screen — scroll through it instead.
 *  - Y-axis is computed ONCE at load from the whole decoded file (rev 3: median of
 *    per-5s-window calibrating-hop DRAWN peaks, scaled so those maxima fill ~60% of the
 *    half-height) — replacing FIXED_FULL_SCALE. See [PcgAmplitudeScale]'s doc for how
 *    "60% excluding noise" is made concrete.
 *
 * The Apply button slot from the forked layout is repurposed back to its production meaning:
 * Save — a NEW recording goes through SaveRecordingFragment (ledger Fix 2, re-applied),
 * an existing one through the save/discard dialog. This screen has no calibration to apply.
 */
class PcgScalePlayerFragment : Fragment() {

    private var _binding: FragmentPcgscalePlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    private var currentWindowSeconds = 4f

    // Smoothed camera-follow position during playback — see onPlaybackProgress. Reset to 0
    // wherever the chart snaps back to its start-centered framing.
    private var displayedPlaybackTime = 0f

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f

        // Total-point ceiling across the WHOLE decoded file. Deliberately much larger than the
        // Calibrated player's 3000: that budget assumed Fix-D re-bucketing would restore
        // density wherever the user zoomed, which this screen doesn't have — here the bucket
        // is fixed at load, so per-pixel quality must hold at EVERY scroll position of the
        // fixed window. MPAndroidChart only renders the visible X range, so draw cost stays
        // bounded by the window; this ceiling bounds memory (~120k Entry ≈ a few MB) and, at
        // the 300s max recording length, still leaves ~1.5 points per pixel in a 4s window.
        private const val MAX_TOTAL_POINTS = 120_000

        // Same smoothing as the Calibrated player.
        private const val FOLLOW_SMOOTHING = 0.15f
        // Halved from the Calibrated player's 3.0 (explicit user request — ledger Fix 4, re-applied).
        private const val TRACE_LINE_WIDTH_DP = 1.5f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPcgscalePlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val isNewRecording = arguments?.getBoolean("isNewRecording", false) ?: false
        val filterName = arguments?.getString("filterName") ?: "HEART"
        // The dB the recorder actually used for this file, if known (0 = not passed / unknown,
        // meaning no compensation is applied) — same contract as the Calibrated fork.
        val recordedPreAmpDb = arguments?.getInt("preAmpDb", 0) ?: 0

        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()
        setupAmpSlider()
        updateScaleCaption()

        if (filePath.isNotEmpty()) {
            // Main-thread snapshot of the horizontal density before the IO coroutine (Fix C
            // heritage) — null before first layout; loadFullWaveform falls back to the
            // point-budget bucket in that case.
            val pixelsPerSecond = binding.pcgScaleWaveformView.currentTimeScale()?.pixelsPerSecond ?: -1f
            loadFullWaveform(filePath, pixelsPerSecond, recordedPreAmpDb)
            setupPlayer(filePath, filterName)
        }

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.eqButton.setOnClickListener {
            val bundle = Bundle().apply { putString("filePath", filePath) }
            findNavController().navigate(R.id.action_pcgScalePlayer_to_equalizer, bundle)
        }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }

        // Production meaning restored (the Calibrated fork had repurposed this slot for graph
        // calibration, which this screen doesn't have): same branch as PlayerFragment's
        // saveButton — a new recording goes through SaveRecordingFragment first (the step that
        // actually renames the temp WAVs into filesDir/saved/, which is what
        // SavedRecordingsFragment lists), an existing recording goes straight to the
        // save/discard dialog → Add Patient. Ledger Fix 2, re-applied.
        binding.saveButton.setOnClickListener {
            if (isNewRecording) {
                val rawFilePath = arguments?.getString("rawFilePath") ?: ""
                val bundle = Bundle().apply {
                    putString("filePath", filePath)
                    putString("rawFilePath", rawFilePath)
                    putString("filterName", filterName)
                    putInt("popUpToDestination", R.id.pcgScaleRecordingFragment)
                }
                findNavController().navigate(R.id.action_pcgScalePlayer_to_saveRecording, bundle)
            } else {
                showSaveDiscardDialog(filePath)
            }
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
        val waveformView = binding.pcgScaleWaveformView
        val chart = waveformView.chart

        // Neutral axis until the whole-file RMS scale lands (loadFullWaveform applies it once).
        chart.axisLeft.axisMinimum = -PcgAmplitudeScale.DEFAULT_INITIAL_FULL_SCALE
        chart.axisLeft.axisMaximum = PcgAmplitudeScale.DEFAULT_INITIAL_FULL_SCALE

        // Scroll-only: drag pans through the recording; scaling is off AND the range lock in
        // PcgScaleWaveformView pins the window on both bounds, so time cannot distort even if
        // a scale gesture slipped through. NOTE: the waveform view owns the chart's gesture
        // listener (grid sync) — do not call chart.setOnChartGestureListener here.
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleXEnabled(false)
        chart.setScaleYEnabled(false)

        waveformView.onVisibleSecondsChanged = { seconds ->
            currentWindowSeconds = seconds
            if (chart.data == null) {
                resetToDummyData()
            } else {
                // Window changed on rotation/resize — re-pin the lock and re-center.
                waveformView.applyRangeLock()
                chart.centerViewTo(seconds / 2f, 0f, YAxis.AxisDependency.LEFT)
                waveformView.syncGridToChart()
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
        binding.pcgScaleWaveformView.chart.data = LineData(dummyDataSet)
        binding.pcgScaleWaveformView.syncGridToChart()
        binding.pcgScaleWaveformView.chart.invalidate()
    }

    private fun updateScaleCaption() {
        if (_binding == null) return
        binding.scaleCaption.text = "1 large box = 1 s · 1 small box = 0.2 s · Y: auto (60% fill, RMS) · scroll to browse"
    }

    /**
     * PcgScale counterpart of the Calibrated fork's loadFullWaveform(). Same WAV-header
     * sample-rate parsing (bytes 24-27, little-endian, fallback 44100) and pre-amp undo;
     * then, new here:
     *  - the whole-file 60%-fill axis scale is computed from the decoded (compensated)
     *    samples and applied once, before the trace is shown;
     *  - the min/max bucket comes from the fixed pixels-per-second density (no Fix-D
     *    re-bucketing exists or is needed — the window never changes).
     */
    private fun loadFullWaveform(filePath: String, pixelsPerSecond: Float, recordedPreAmpDb: Int) {
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

            // Undo the recorder's pre-amp gain BEFORE both the RMS scale computation and the
            // entry building — the 60% fill must be measured on the same data that is drawn.
            if (recordedPreAmpDb > 0) {
                val preAmpGain = Math.pow(10.0, recordedPreAmpDb / 20.0).toFloat()
                if (preAmpGain > 1.001f) {
                    for (j in samples.indices) samples[j] = samples[j] / preAmpGain
                }
            }

            // Whole-file 60%-fill axis scale — "sampled mean peaks across the whole recording".
            // Streamed through an instance (rather than computeFullScaleForFile) so the
            // diagnostics below can also read typicalPeakAmplitude/isClampedAtMin for the caption.
            val fileScale = PcgAmplitudeScale(fileSampleRate)
            fileScale.addSamples(samples)
            fileScale.flushPartialWindow()
            val fullScale = fileScale.targetFullScale()

            // Fixed-density bucket: sampleRate / (pixelsPerSecond * 2) puts ~one min/max pair
            // per horizontal pixel of the fixed window (algebraically identical to
            // deriveBucketSizeForVisibleRange for this window). The MAX_TOTAL_POINTS ceiling
            // bounds memory on long files — see that constant's doc.
            val derivedBucket = if (pixelsPerSecond > 0f) {
                maxOf(1, (fileSampleRate / (pixelsPerSecond * 2f)).roundToInt())
            } else -1
            val ceilingBucket = maxOf(1, totalSamples / (MAX_TOTAL_POINTS / 2))
            val bucketSize = if (derivedBucket > 0) maxOf(derivedBucket, ceilingBucket) else ceilingBucket
            val entries = PcgScaleWaveformView.downsampleMinMax(samples, bucketSize) { idx ->
                idx.toFloat() / fileSampleRate
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                val chart = binding.pcgScaleWaveformView.chart
                chart.axisLeft.axisMinimum = -fullScale
                chart.axisLeft.axisMaximum = fullScale
                // Same on-device diagnostics idea as the recorder's live caption: the file's
                // real sample rate (from the WAV header) and the applied scale, with an
                // explicit marker when the clamp floor — not the measurement — set the axis
                // (i.e. a very quiet recording that CANNOT reach 60% fill; seen on a study
                // Samsung unit's input path).
                val clampNote = if (fileScale.isClampedAtMin()) " (MIN-CLAMPED: file very quiet)" else ""
                binding.scaleCaption.text = String.format(
                    "1 large box = 1 s · sr=%d Hz · Y=±%.3f%s · scroll to browse",
                    fileSampleRate.toInt(), fullScale, clampNote
                )
                renderWaveformEntries(ArrayList(entries), durationSecs)
            }
        }
    }

    private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int) {
        binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
        // Ledger Fix 3, re-applied: give the grid the file's real end so it can never be
        // scrolled/flung past where the trace stops — see PcgScaleWaveformView.syncGridToChart.
        binding.pcgScaleWaveformView.totalDurationSeconds = durationSecs.toFloat()
        val dataSet = LineDataSet(entries, "Waveform").apply {
            color = Color.parseColor("#2D7DD2")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = TRACE_LINE_WIDTH_DP
            mode = LineDataSet.Mode.LINEAR
        }
        val waveformView = binding.pcgScaleWaveformView
        val chart = waveformView.chart
        chart.data = LineData(dataSet)
        // Range locks are stored against the data's axis range — re-pin now that real data is in.
        waveformView.applyRangeLock()
        chart.centerViewTo(currentWindowSeconds / 2f, 0f, YAxis.AxisDependency.LEFT)
        waveformView.syncGridToChart()
        chart.invalidate()
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

                            // Same eased follow as the Calibrated player. The window is
                            // range-locked, so this only translates — never rescales — time.
                            displayedPlaybackTime += (timestamp.toFloat() - displayedPlaybackTime) * FOLLOW_SMOOTHING

                            val waveformView = binding.pcgScaleWaveformView
                            val chart = waveformView.chart
                            val halfRange = chart.visibleXRange / 2f
                            val centerX = if (displayedPlaybackTime < halfRange) halfRange else displayedPlaybackTime
                            chart.centerViewTo(centerX, 0f, YAxis.AxisDependency.LEFT)
                            // Programmatic camera move — sync the grid's labels by hand.
                            waveformView.syncGridToChart()
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
                            val waveformView = binding.pcgScaleWaveformView
                            val chart = waveformView.chart
                            chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
                            waveformView.syncGridToChart()
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
            val waveformView = binding.pcgScaleWaveformView
            val chart = waveformView.chart
            chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
            waveformView.syncGridToChart()
        } else {
            try {
                displayedPlaybackTime = 0f
                val waveformView = binding.pcgScaleWaveformView
                val chart = waveformView.chart
                chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
                waveformView.syncGridToChart()

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
                    findNavController().navigate(R.id.action_pcgScalePlayer_to_addPatient, bundle)
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
