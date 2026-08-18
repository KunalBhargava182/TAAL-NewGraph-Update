package com.musediagnostics.taal.app.ui.calibrated

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
import com.musediagnostics.taal.app.databinding.FragmentCalibratedPlayerBinding
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedPaperSpeed
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration
import com.musediagnostics.taal.app.ui.player.PlayerSaveDiscardDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Calibrated replica of [com.musediagnostics.taal.app.ui.player.PlayerFragment] (protected,
 * not modified — ported by hand, see WAVEFORM_GRAPH_AND_GRID_REFERENCE.md §3). Same controls,
 * save/discard flow and filter-reapplication guard; the waveform is drawn on the calibrated
 * mm-accurate grid instead of MPAndroidChart's own gridlines on a fixed 4s window.
 *
 * No pre-amp display compensation here — the played-back file already has gain baked in from
 * record time, same as production. Y-axis starts at a fixed ±0.5 placeholder (same as production
 * PlayerFragment) but is then adapted to the loaded file's actual peak amplitude (user request:
 * bigger peaks) — since the whole file is already decoded in memory by the time it's rendered,
 * this doesn't need a warmup window the way the live recorder does. Touch/drag/scale are
 * enabled, same as production; the visible-range lock only caps the *maximum* zoom-out (not the
 * minimum), so pinch-zoom actually works — the mm-grid behind the chart is a separate, static
 * view and intentionally does not zoom with the trace (per user request: zoom the trace, don't
 * move the background).
 *
 * The camera-follow during playback is smoothed (see [displayedPlaybackTime]/FOLLOW_SMOOTHING),
 * not a direct snap to the real playback position — a direct snap looked "too fast" once
 * pinch-zoom made the visible window small (user request). Audio itself always plays at true
 * speed/pitch; only the visual follow is damped.
 */
class CalibratedPlayerFragment : Fragment() {

    private var _binding: FragmentCalibratedPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    private var currentWindowSeconds = 4f
    private val fixedPeakAmplitude = 0.5f // placeholder until the file's real peak is known

    // Smoothed camera-follow position during playback — see onPlaybackProgress. Reset to 0
    // wherever the chart snaps back to its start-centered framing.
    private var displayedPlaybackTime = 0f

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val TARGET_POINT_BUDGET = 3000 // same total-point budget as production's maxPoints
        // Same tuning as CalibratedRecordingFragment, applied to the file's actual peak instead
        // of a live warmup window. 1.2 fills ~83% of the chart height — matches the recorder's
        // margin (1.1 clipped, too little headroom). No percentile trimming needed here since
        // this is already the file's true peak, not a live estimate.
        private const val HEADROOM = 1.2f
        private const val MIN_PEAK = 0.02f
        // Camera-follow smoothing (user request: playback feels "too fast" when zoomed in).
        // Fraction of the remaining gap to the real playback position closed per progress
        // callback — lower = gentler/slower-feeling follow, 1.0 = instant snap (old behavior).
        private const val FOLLOW_SMOOTHING = 0.15f
        // Trace stroke width in dp. Reverted to production's thin/crisp value — "bigger" is
        // achieved via HEADROOM (taller peaks), not a fatter stroke (user request).
        private const val TRACE_LINE_WIDTH_DP = 2.5f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalibratedPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val isNewRecording = arguments?.getBoolean("isNewRecording", false) ?: false
        val filterName = arguments?.getString("filterName") ?: "HEART"

        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()
        setupAmpSlider()
        updateCalibrationCaption()

        if (filePath.isNotEmpty()) {
            loadFullWaveform(filePath, filterName)
            setupPlayer(filePath, filterName)
        }

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.eqButton.setOnClickListener {
            val bundle = Bundle().apply { putString("filePath", filePath) }
            findNavController().navigate(R.id.action_calibratedPlayer_to_equalizer, bundle)
        }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }

        binding.saveButton.setOnClickListener {
            if (isNewRecording) {
                val rawFilePath = arguments?.getString("rawFilePath") ?: ""
                val bundle = Bundle().apply {
                    putString("filePath", filePath)
                    putString("rawFilePath", rawFilePath)
                    putString("filterName", filterName)
                }
                findNavController().navigate(R.id.action_calibratedPlayer_to_saveRecording, bundle)
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
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart

        chart.axisLeft.axisMinimum = -fixedPeakAmplitude
        chart.axisLeft.axisMaximum = fixedPeakAmplitude

        // 25mm/s (Fix B) — must match CalibratedRecordingFragment exactly, so a recording
        // looks the same live as it does in review. See that fragment's comment for why.
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_25)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        // Opposite of the recorder — user can pan/zoom to inspect the trace (§5).
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)

        waveformView.onVisibleSecondsChanged = { seconds ->
            currentWindowSeconds = seconds
            if (chart.data == null) {
                resetToDummyData()
            } else {
                // Real waveform already loaded (e.g. window changed on rotation) — reapply
                // the corrected max-zoom-out cap and re-center rather than silently drifting
                // stale. Deliberately NOT setVisibleXRangeMinimum — that would lock the range
                // to exactly `seconds` and disable pinch-zoom entirely, same bug fixed below.
                chart.setVisibleXRangeMaximum(seconds)
                chart.centerViewTo(seconds / 2f, 0f, YAxis.AxisDependency.LEFT)
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
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude · DPI: $status"
    }

    /**
     * Calibrated counterpart of PlayerFragment.loadFullWaveform(). Same WAV-header sample-rate
     * parsing (bytes 24-27, little-endian, fallback 44100 — never hardcoded, per §4.5), but
     * downsampling is min/max-bucketed instead of "every step-th sample" so a transient can't
     * fall entirely between two kept samples.
     */
    private fun loadFullWaveform(filePath: String, filterName: String) {
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

            // 2 points per bucket (min+max) -> bucket size chosen to hit the same total point
            // budget production used with 1-point-per-step decimation.
            val bucketSize = maxOf(1, totalSamples / (TARGET_POINT_BUDGET / 2))
            val entries = CalibratedWaveformView.downsampleMinMax(samples, bucketSize) { idx ->
                idx.toFloat() / fileSampleRate
            }

            // Whole file is already decoded — no warmup window needed, just take the true peak
            // directly (user request: bigger peaks).
            var filePeak = 0f
            for (sample in samples) {
                val abs = Math.abs(sample)
                if (abs > filePeak) filePeak = abs
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                renderWaveformEntries(ArrayList(entries), durationSecs, filePeak)
            }
        }
    }

    private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int, filePeak: Float) {
        binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
        val dataSet = LineDataSet(entries, "Waveform").apply {
            color = Color.parseColor("#2D7DD2")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = TRACE_LINE_WIDTH_DP
            mode = LineDataSet.Mode.LINEAR
        }
        // currentWindowSeconds is kept in sync by onVisibleSecondsChanged (set up in
        // setupWaveformChart, called before this) — including the case where layout hadn't
        // happened yet when this loaded; that callback will re-apply the range once it does.
        val chart = binding.calibratedWaveformView.chart

        val peakAmplitude = (filePeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
        chart.axisLeft.axisMinimum = -peakAmplitude
        chart.axisLeft.axisMaximum = peakAmplitude

        chart.data = LineData(dataSet)
        // Cap max zoom-out only — no Minimum lock, so pinch-zoom works (see setupWaveformChart).
        chart.setVisibleXRangeMaximum(currentWindowSeconds)
        chart.centerViewTo(currentWindowSeconds / 2f, 0f, YAxis.AxisDependency.LEFT)
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

                            // Ease the camera toward the real playback position instead of
                            // snapping to it every callback — at a small zoomed-in visible
                            // window, a direct snap makes the trace look like it's racing.
                            // This trades exact frame-accurate sync for a calmer, self-
                            // correcting follow (it always eases back toward the true position,
                            // never drifts away indefinitely).
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
                PlayerSaveDiscardDialog.Action.SAVE -> {
                    val bundle = Bundle().apply { putString("recordingFilePath", filePath) }
                    findNavController().navigate(R.id.action_calibratedPlayer_to_addPatient, bundle)
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
