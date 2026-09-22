package com.musediagnostics.taal.app.ui.player

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentProductionReviewBinding
import com.musediagnostics.taal.app.ui.segmentation.SegmentationFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Production Review — reads an ALREADY-SAVED recording, reached from
 * [com.musediagnostics.taal.app.ui.library.ProductionSavedRecordingsFragment]. Split out of the
 * old PlayerFragment's isNewRecording=false branch (2026-09-16), mirroring the
 * PcgScalePlayer/PcgScaleReview split: no Save/Discard bar, no PlayerSaveDiscardDialog — a
 * saved file is already saved, there's nothing left to do here but play it, tweak EQ, and
 * (if a raw companion exists) analyze it. Same V7 MPAndroidChart rendering as
 * [ProductionPlayerFragment] — deliberately NOT the PcgScale time-grid/Clean-Graph style, since
 * this screen exists specifically to keep the original production look available.
 */
class ProductionReviewFragment : Fragment() {

    private var _binding: FragmentProductionReviewBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProductionReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val filterName = arguments?.getString("filterName") ?: "HEART"

        // This screen is only ever reached from Saved Recordings, so filePath always points
        // at an already-saved file — show the name the user actually typed when saving.
        if (filePath.isNotEmpty()) {
            binding.screenTitle.text = savedRecordingDisplayName(File(filePath))
        }

        // Always potentially available here (unlike ProductionPlayerFragment) — filePath is
        // guaranteed inside filesDir/saved/ on this screen, so a saved _raw.wav companion
        // either exists or it doesn't; there's no "not saved yet" case to gate against.
        if (SegmentationFeature.ENABLED && filePath.contains("_filtered.wav")) {
            val savedRawPath = filePath.replace("_filtered.wav", "_raw.wav")
            if (File(savedRawPath).exists()) {
                binding.analyzeButton.visibility = View.VISIBLE
                binding.analyzeButton.setOnClickListener {
                    val bundle = Bundle().apply {
                        putString("rawFilePath", savedRawPath)
                        // So the report's back button returns to THIS flow's saved-recordings
                        // list, not PcgScale's — see SegmentationReportFragment.goToSavedRecordings.
                        putInt("returnDestination", R.id.productionSavedRecordingsFragment)
                    }
                    findNavController().navigate(R.id.action_productionReview_to_segmentationReport, bundle)
                }
            }
        }

        setupWaveformChart()
        setupAmpSlider()

        if (filePath.isNotEmpty()) {
            loadFullWaveform(filePath, filterName)
            setupPlayer(filePath, filterName)
        }

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.eqButton.setOnClickListener {
            val bundle = Bundle().apply { putString("filePath", filePath) }
            findNavController().navigate(R.id.action_productionReview_to_equalizer, bundle)
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
        val chart = binding.waveformChart
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawGridBackground(true)
        chart.setGridBackgroundColor(Color.WHITE)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            gridLineWidth = 1f
            setDrawAxisLine(false)
            setDrawLabels(false)
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            gridLineWidth = 1f
            axisMinimum = -0.5f
            axisMaximum = 0.5f
            setDrawLabels(false)
            setDrawAxisLine(false)
        }

        chart.axisRight.isEnabled = false

        val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(4f, 0f)), "").apply {
            color = Color.TRANSPARENT
            setDrawCircles(false)
            setDrawValues(false)
        }
        chart.data = LineData(dummyDataSet)
        chart.invalidate()
    }

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
            val maxPoints = 3000
            val step = maxOf(1, totalSamples / maxPoints)
            val entries = ArrayList<Entry>()
            var i = 0
            while (i < totalSamples) {
                val bytePos = 44 + i * 2
                if (bytePos + 1 >= bytes.size) break
                val low = bytes[bytePos].toInt() and 0xFF
                val high = bytes[bytePos + 1].toInt() shl 8
                val sample = (high or low).toShort().toFloat() / 32768f
                entries.add(Entry(i.toFloat() / fileSampleRate, sample))
                i += step
            }
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                renderWaveformEntries(entries, durationSecs)
            }
        }
    }

    private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int) {
        binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
        val dataSet = LineDataSet(entries, "Waveform").apply {
            color = Color.parseColor("#2D7DD2")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2.5f
            mode = LineDataSet.Mode.LINEAR
        }
        binding.waveformChart.apply {
            data = LineData(dataSet)
            setVisibleXRangeMaximum(4f)
            centerViewTo(2f, 0f, com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT)
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            invalidate()
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
                            val totalSecs = timestamp.toInt()
                            binding.timerText.text = String.format(
                                "%02d:%02d", totalSecs / 60, totalSecs % 60
                            )

                            val chart = binding.waveformChart
                            val currentVisibleRange = chart.visibleXRange
                            val halfRange = currentVisibleRange / 2f
                            val centerX =
                                if (timestamp.toFloat() < halfRange) halfRange else timestamp.toFloat()

                            chart.centerViewTo(
                                centerX,
                                0f,
                                com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
                            )
                        }
                    }
                }
                onPlaybackComplete = {
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            isPlaying = false
                            binding.actionText.text = getString(R.string.play_recording)
                            binding.playButton.setImageResource(R.drawable.ic_play_circle)

                            val chart = binding.waveformChart
                            chart.centerViewTo(
                                chart.visibleXRange / 2f,
                                0f,
                                com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
                            )
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

            val chart = binding.waveformChart
            chart.centerViewTo(
                chart.visibleXRange / 2f,
                0f,
                com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
            )
        } else {
            try {
                val chart = binding.waveformChart
                chart.centerViewTo(
                    chart.visibleXRange / 2f,
                    0f,
                    com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
                )

                player?.prepare()
                player?.start()
                isPlaying = true
                binding.actionText.text = getString(R.string.stop_recording)
                binding.playButton.setImageResource(R.drawable.ic_recording_stop)
            } catch (e: Exception) {
                isPlaying = false
                Toast.makeText(requireContext(), "Playback error: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    /**
     * Filename format: "{FILTER}_{userInput}_filtered.wav" — same convention as
     * ProductionSavedRecordingAdapter's list screen, so the title matches what the user
     * tapped there.
     */
    private fun savedRecordingDisplayName(file: File): String {
        val baseName = file.nameWithoutExtension.removeSuffix("_filtered")
        val known = listOf("FULL_BODY", "PREGNANCY", "CUSTOM", "LUNGS", "BOWEL", "HEART")
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
