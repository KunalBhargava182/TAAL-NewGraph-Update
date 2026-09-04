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
import com.musediagnostics.taal.app.ecg.pcgscale.PcgDisplayFilter
import com.musediagnostics.taal.app.ecg.pcgscale.PcgScaleWaveformView
import com.musediagnostics.taal.app.ui.segmentation.SegmentationFeature
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

    // Denoise toggle state. Both the as-decoded and (once computed) the display-filtered
    // sample arrays are kept in memory so toggling back is instant — only the FIRST enable
    // pays the PcgDisplayFilter.processOffline() cost. Playback always plays the ORIGINAL
    // file (see setupPlayer/togglePlayback, untouched) — the filter affects the display
    // trace only. Same mechanism as app's copy of this fragment; Hum filter intentionally
    // not re-added alongside it.
    private var originalSamples: FloatArray? = null
    private var gatedSamples: FloatArray? = null
    private var fileSampleRateForGate: Float = INPUT_SAMPLE_RATE
    private var recordingDurationSecs: Int = 0
    private var denoiseEnabled = false

    private var pixelsPerSecondForRender: Float = -1f

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f

        // Same reasoning as PcgScalePlayerFragment.MAX_TOTAL_POINTS.
        private const val MAX_TOTAL_POINTS = 120_000

        private const val FOLLOW_SMOOTHING = 0.15f
        private const val TRACE_LINE_WIDTH_DP = 1.5f

        // FIX 2026-09-02: shared tag — see taal-core's capture/playback logging.
        private const val TAG = "TAAL_AUDIO_DEBUG"
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

        // This screen is only ever reached from Saved Recordings, so filePath always points
        // at an already-saved file — show the name the user actually typed when saving,
        // same as production PlayerFragment does for a saved recording.
        if (filePath.isNotEmpty()) {
            binding.screenTitle.text = savedRecordingDisplayName(File(filePath))
        }

        // Same gate as production PlayerFragment's "Analyze Heart Sounds" button: this screen
        // is only ever reached from Saved Recordings, so filePath is always inside
        // filesDir/saved/ already — just confirm the saved _raw.wav companion actually exists.
        if (SegmentationFeature.ENABLED && filePath.contains("_filtered.wav")) {
            val savedRawPath = filePath.replace("_filtered.wav", "_raw.wav")
            if (File(savedRawPath).exists()) {
                binding.analyzeButton.visibility = View.VISIBLE
                binding.analyzeButton.setOnClickListener {
                    val bundle = Bundle().apply { putString("rawFilePath", savedRawPath) }
                    findNavController().navigate(R.id.action_pcgScaleReview_to_segmentationReport, bundle)
                }
            }
        }

        setupWaveformChart()
        setupAmpSlider()
        setupDenoiseSwitch()
        updateScaleCaption()

        if (filePath.isNotEmpty()) {
            val pixelsPerSecond = binding.pcgScaleWaveformView.currentTimeScale()?.pixelsPerSecond ?: -1f
            loadFullWaveform(filePath, pixelsPerSecond)
            setupPlayer(filePath, filterName)
        }

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
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

    /** Display-only PcgDisplayFilter denoise toggle — see field docs above. Disabled until
     *  the file finishes decoding (there is nothing to filter yet). */
    private fun setupDenoiseSwitch() {
        binding.denoiseSwitch.isEnabled = false
        binding.denoiseSwitch.setOnCheckedChangeListener { _, checked ->
            onDenoiseToggled(checked)
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
        binding.scaleCaption.text = "1 large box = 1 s · 1 small box = 0.2 s"
    }

    private data class RenderPayload(val fullScale: Float, val entries: ArrayList<Entry>)

    /** Whole-file 60%-fill axis scale + downsample bucket (same math
     *  PcgScalePlayerFragment.loadFullWaveform uses). */
    private fun computeRenderPayload(samples: FloatArray, sampleRate: Float): RenderPayload {
        val fullScale = PcgAmplitudeScale.computeFullScaleForFile(samples, sampleRate)
        val totalSamples = samples.size
        val derivedBucket = if (pixelsPerSecondForRender > 0f) {
            maxOf(1, (sampleRate / (pixelsPerSecondForRender * 2f)).roundToInt())
        } else -1
        val ceilingBucket = maxOf(1, totalSamples / (MAX_TOTAL_POINTS / 2))
        val bucketSize = if (derivedBucket > 0) maxOf(derivedBucket, ceilingBucket) else ceilingBucket
        val entries = PcgScaleWaveformView.downsampleMinMax(samples, bucketSize) { idx ->
            idx.toFloat() / sampleRate
        }
        // FIX 2026-09-02: the display maths, made visible. fullScale is the Y-axis half-range
        // PcgAmplitudeScale derived from this data; comparing it against the signal's own
        // peak/RMS shows whether the trace is filling the graph as intended, and whether the
        // MIN_FULL_SCALE clamp is what's setting the axis.
        var pk = 0f
        var sumSq = 0.0
        for (s in samples) {
            val a = kotlin.math.abs(s)
            if (a > pk) pk = a
            sumSq += s.toDouble() * s.toDouble()
        }
        val rms = if (samples.isNotEmpty()) kotlin.math.sqrt(sumSq / samples.size) else 0.0
        val fillPct = if (fullScale > 0f) 100.0 * pk / fullScale else 0.0
        android.util.Log.i(TAG, "REVIEW computeRenderPayload — samples=$totalSamples " +
            "rate=${sampleRate.toInt()}Hz dataPeak=$pk dataRms=$rms " +
            "fullScale=$fullScale peakFillOfAxis=${"%.1f".format(fillPct)}% " +
            "bucketSize=$bucketSize (derived=$derivedBucket ceiling=$ceilingBucket) " +
            "entries=${entries.size} denoise=$denoiseEnabled")
        return RenderPayload(fullScale, ArrayList(entries))
    }

    private fun applyRenderPayload(payload: RenderPayload, durationSecs: Int) {
        if (_binding == null) return
        val chart = binding.pcgScaleWaveformView.chart
        chart.axisLeft.axisMinimum = -payload.fullScale
        chart.axisLeft.axisMaximum = payload.fullScale
        renderWaveformEntries(payload.entries, durationSecs)
        updateScaleCaption()
    }

    /** Decode/scale/bucket pipeline, same as PcgScalePlayerFragment.loadFullWaveform minus the
     *  pre-amp undo step (a saved file has no known recorder gain to undo). Also caches the
     *  decoded samples for the Denoise toggle (see field docs). */
    private fun loadFullWaveform(filePath: String, pixelsPerSecond: Float) {
        pixelsPerSecondForRender = pixelsPerSecond
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) {
                // FIX 2026-09-02: this silent return is why a missing file shows an empty
                // graph with no explanation.
                android.util.Log.e(TAG, "════════ REVIEW LOAD FAILED ════════ file does not exist: $filePath")
                return@launch
            }
            android.util.Log.i(TAG, "════════ REVIEW LOAD ════════ file=${file.name} " +
                "bytes=${file.length()} path=${file.parent}")
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

            // FIX 2026-09-02: decode results — ties the on-screen trace back to the file,
            // and to the capture session that wrote it.
            android.util.Log.i(TAG, "REVIEW decoded — headerRate=${fileSampleRate.toInt()}Hz " +
                "dataBytes=$dataSize totalSamples=$totalSamples durationSecs=$durationSecs")

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                originalSamples = samples
                fileSampleRateForGate = fileSampleRate
                recordingDurationSecs = durationSecs
                binding.denoiseSwitch.isEnabled = true
                // Default ON (2026-09-04 request) — go straight through the same path a
                // manual toggle takes (binding.denoiseSwitch.isChecked already starts true
                // per the layout, but setting it wouldn't fire the listener since it's not
                // actually changing) so the file opens already denoised.
                onDenoiseToggled(true)
            }
        }
    }

    /**
     * Denoise toggle: ON with no cached filtered version yet runs [PcgDisplayFilter] (click
     * removal, zero-phase 20–500 Hz band + hum notches, transient-protected gate) on the
     * already-decoded original samples on Dispatchers.Default (CPU-bound work), showing
     * waveformLoadingIndicator for the few seconds a 300s file can take. Every other
     * transition (ON with a cache hit, or OFF back to the original) is synchronous — both
     * arrays are already in memory, so it's just a re-bucket + re-render, "instant" per spec.
     * Playback is untouched either way — see the field doc on [originalSamples].
     */
    private fun onDenoiseToggled(enabled: Boolean) {
        val original = originalSamples ?: return
        denoiseEnabled = enabled
        val durationSecs = recordingDurationSecs
        android.util.Log.i(TAG, "REVIEW denoise toggled -> $enabled " +
            "(cached=${gatedSamples != null}, samples=${original.size})")

        if (!enabled) {
            applyRenderPayload(computeRenderPayload(original, fileSampleRateForGate), durationSecs)
            return
        }

        val cached = gatedSamples
        if (cached != null) {
            applyRenderPayload(computeRenderPayload(cached, fileSampleRateForGate), durationSecs)
            return
        }

        binding.waveformLoadingIndicator.visibility = View.VISIBLE
        binding.denoiseSwitch.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val gateStartMs = System.currentTimeMillis()
            val gated = PcgDisplayFilter.processOffline(original, fileSampleRateForGate)
            android.util.Log.i(TAG, "REVIEW display filter (despike + zero-phase band + gate) computed in " +
                "${System.currentTimeMillis() - gateStartMs}ms (${original.size} samples)")
            val payload = computeRenderPayload(gated, fileSampleRateForGate)
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                gatedSamples = gated
                binding.waveformLoadingIndicator.visibility = View.GONE
                binding.denoiseSwitch.isEnabled = true
                applyRenderPayload(payload, durationSecs)
            }
        }
    }

    private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int) {
        binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
        // Ledger Fix 3, re-applied (this line was removed 2026-08-28 only to keep the build
        // green after update1 reverted the totalDurationSeconds property it depends on).
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
                // FIX 2026-09-02: see the same note in PcgScalePlayerFragment — makes
                // double-filtering on playback visible.
                val skipFilter = fileName.contains("_filtered") || fileName.contains("_8k_downsampling")
                android.util.Log.i(TAG, "REVIEW setupPlayer — file=$fileName " +
                    "preFilterOnPlayback=${if (skipFilter) "SKIPPED (already-filtered file)" else filterName}")
                if (!skipFilter) {
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
        android.util.Log.i(TAG, "REVIEW play button — ${if (isPlaying) "STOPPING" else "STARTING"} playback")
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

    /**
     * Filename format: "{FILTER}_{userInput}_filtered.wav" — same convention (including
     * HEART_HARD, checked before HEART) as SavedRecordingAdapter's list screen, so the title
     * matches what the user tapped there.
     */
    private fun savedRecordingDisplayName(file: File): String {
        val baseName = file.nameWithoutExtension.removeSuffix("_filtered")
        val known = listOf("FULL_BODY", "PREGNANCY", "CUSTOM", "LUNGS", "BOWEL", "HEART_HARD", "HEART")
        val filterPrefix = known.firstOrNull { baseName.startsWith("${it}_") }
        return filterPrefix?.let { baseName.removePrefix("${it}_") } ?: baseName
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
