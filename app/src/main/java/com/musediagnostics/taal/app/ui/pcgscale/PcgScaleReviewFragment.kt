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
import com.musediagnostics.taal.app.databinding.FragmentPcgscaleReviewBinding
import com.musediagnostics.taal.app.ecg.pcgscale.PcgAmplitudeScale
import com.musediagnostics.taal.app.ecg.pcgscale.PcgScaleWaveformView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Fork of [PcgScalePlayerFragment] (not modified) for reviewing an ALREADY-SAVED recording —
 * reached from [com.musediagnostics.taal.app.ui.library.SavedRecordingsFragment], never from a
 * live recording, so there is nothing to save or discard: no isNewRecording branch, no
 * Save/Discard bar, no PlayerSaveDiscardDialog, no rawFilePath. Everything else — the
 * time-true grid, the range lock that makes zoom impossible, and the whole-file 60%-fill
 * RMS Y-axis computed once at load — is identical to PcgScalePlayerFragment, so a recording
 * looks the same whether you're reviewing it fresh off the recorder or opening it later from
 * the saved list.
 *
 * No preAmpDb is read: a saved file has no live session to recover the recorder's gain from,
 * so the whole-file RMS scale is computed on the file as decoded, same as
 * [com.musediagnostics.taal.app.ui.player.PlayerFragment]'s non-new-recording path.
 */
class PcgScaleReviewFragment : Fragment() {

    private var _binding: FragmentPcgscaleReviewBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    private var currentWindowSeconds = 4f

    // Smoothed camera-follow position during playback — see onPlaybackProgress. Reset to 0
    // wherever the chart snaps back to its start-centered framing.
    private var displayedPlaybackTime = 0f

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f

        // Same reasoning as PcgScalePlayerFragment.MAX_TOTAL_POINTS.
        private const val MAX_TOTAL_POINTS = 120_000

        private const val FOLLOW_SMOOTHING = 0.15f
        private const val TRACE_LINE_WIDTH_DP = 1.5f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPcgscaleReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val filterName = arguments?.getString("filterName") ?: "HEART"

        setupWaveformChart()
        setupAmpSlider()
        updateScaleCaption()

        if (filePath.isNotEmpty()) {
            val pixelsPerSecond = binding.pcgScaleWaveformView.currentTimeScale()?.pixelsPerSecond ?: -1f
            loadFullWaveform(filePath, pixelsPerSecond)
            setupPlayer(filePath, filterName)
        }

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.eqButton.setOnClickListener {
            val bundle = Bundle().apply { putString("filePath", filePath) }
            findNavController().navigate(R.id.action_pcgScaleReview_to_equalizer, bundle)
        }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
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

    /** Same decode/scale/bucket pipeline as PcgScalePlayerFragment.loadFullWaveform, minus the
     *  pre-amp undo step — a saved file has no known recorder gain to undo. */
    private fun loadFullWaveform(filePath: String, pixelsPerSecond: Float) {
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

            // Whole-file 60%-fill axis scale — "sampled mean peaks across the whole recording".
            val fullScale = PcgAmplitudeScale.computeFullScaleForFile(samples, fileSampleRate)

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
                renderWaveformEntries(ArrayList(entries), durationSecs)
            }
        }
    }

    private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int) {
        binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
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

                            // Same eased follow as PcgScalePlayerFragment. The window is
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
