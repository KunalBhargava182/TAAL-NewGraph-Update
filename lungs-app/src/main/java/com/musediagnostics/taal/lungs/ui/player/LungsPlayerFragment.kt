package com.musediagnostics.taal.lungs.ui.player

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.lungs.R
import com.musediagnostics.taal.lungs.data.db.LungsDatabase
import com.musediagnostics.taal.lungs.data.db.entity.LungRecordingEntity
import com.musediagnostics.taal.lungs.databinding.FragmentLungsPlayerBinding
import com.musediagnostics.taal.lungs.domain.LungPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LungsPlayerFragment : Fragment() {

    private var _binding: FragmentLungsPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLungsPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val rawFilePath = arguments?.getString("rawFilePath") ?: ""
        val patientId = arguments?.getLong("patientId") ?: -1L
        val patientSeqNum = arguments?.getInt("patientSeqNum") ?: 1
        val pointCode = arguments?.getString("pointCode") ?: ""
        val isReviewMode = arguments?.getBoolean("isReviewMode") ?: false

        val pointLabel = LungPoints.byCode(pointCode)?.label ?: pointCode.uppercase()

        binding.screenTitle.text = when {
            pointLabel.isNotEmpty() && isReviewMode -> "Review: $pointLabel"
            pointLabel.isNotEmpty() -> pointLabel
            else -> getString(R.string.player_title)
        }

        if (isReviewMode) applyReviewMode()

        setupWaveformChart()
        setupAmpSlider()

        if (filePath.isNotEmpty()) {
            loadFullWaveform(filePath)
            setupPlayer(filePath)
        }

        binding.backButton.setOnClickListener {
            // Navigating back = discard (same as pressing discard without confirming)
            findNavController().navigateUp()
        }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) return@setOnClickListener
            togglePlayback(filePath)
        }

        binding.saveButton.setOnClickListener {
            saveRecording(filePath, rawFilePath, patientId, patientSeqNum, pointCode, pointLabel)
        }

        binding.discardButton.setOnClickListener {
            confirmDiscard(filePath, rawFilePath)
        }
    }

    /** Hides the Save/Discard bar and re-anchors the play button to the screen bottom. */
    private fun applyReviewMode() {
        binding.saveDiscardBar.visibility = View.GONE
        val cs = ConstraintSet()
        cs.clone(binding.playerRoot)
        cs.clear(R.id.playButton, ConstraintSet.BOTTOM)
        cs.connect(R.id.playButton, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 32)
        cs.applyTo(binding.playerRoot)
    }

    private fun setupAmpSlider() {
        binding.ampSlider.addOnChangeListener { _, value, _ ->
            binding.ampLabel.text = "${value.toInt()} dB"
            player?.setPreAmplification(value)
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
            setDrawAxisLine(false)
            setDrawLabels(false)
        }
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            axisMinimum = -0.5f
            axisMaximum = 0.5f
            setDrawLabels(false)
            setDrawAxisLine(false)
        }
        chart.axisRight.isEnabled = false
    }

    private fun loadFullWaveform(filePath: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@launch
                val bytes = file.readBytes()
                val dataSize = bytes.size - 44
                val totalSamples = dataSize / 2
                val totalDurationSeconds = (totalSamples / INPUT_SAMPLE_RATE).toInt()
                val sampleStep = maxOf(1, totalSamples / 3000)
                val entries = ArrayList<Entry>()
                var sampleIndex = 0

                for (i in 44 until bytes.size - 1 step sampleStep * 2) {
                    val low = bytes[i].toInt() and 0xFF
                    val high = bytes[i + 1].toInt() shl 8
                    val sample = (high or low).toShort().toFloat() / 32768f
                    entries.add(Entry(sampleIndex.toFloat() / INPUT_SAMPLE_RATE, sample))
                    sampleIndex += sampleStep
                }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.timerText.text = "%02d:%02d".format(
                        totalDurationSeconds / 60, totalDurationSeconds % 60
                    )
                    val dataSet = LineDataSet(entries, "Waveform").apply {
                        color = Color.parseColor("#2D7DD2")
                        setDrawCircles(false); setDrawValues(false)
                        lineWidth = 2.5f; mode = LineDataSet.Mode.LINEAR
                    }
                    binding.waveformChart.apply {
                        data = LineData(dataSet)
                        setVisibleXRangeMaximum(4f)
                        centerViewTo(2f, 0f, YAxis.AxisDependency.LEFT)
                        setTouchEnabled(true)
                        isDragEnabled = true
                        setScaleEnabled(true)
                        invalidate()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun setupPlayer(filePath: String) {
        try {
            player = TaalPlayer(requireContext()).apply {
                setDataSource(filePath)
                // Skip filter for already-filtered files (avoid double-filtering)
                if (!File(filePath).name.contains("_filtered")) {
                    setPreFilter(PreFilter.LUNGS)
                }
                onPlaybackProgress = { timestamp, _ ->
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            val totalSecs = timestamp.toInt()
                            binding.timerText.text = "%02d:%02d".format(totalSecs / 60, totalSecs % 60)
                            val chart = binding.waveformChart
                            val halfRange = chart.visibleXRange / 2f
                            val centerX = if (timestamp.toFloat() < halfRange) halfRange else timestamp.toFloat()
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
                            binding.waveformChart.centerViewTo(
                                binding.waveformChart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT
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
            player?.stop(); isPlaying = false
            binding.actionText.text = getString(R.string.play_recording)
            binding.playButton.setImageResource(R.drawable.ic_play_circle)
        } else {
            try {
                player?.prepare(); player?.start(); isPlaying = true
                binding.actionText.text = getString(R.string.stop_recording)
                binding.playButton.setImageResource(R.drawable.ic_recording_stop)
            } catch (e: Exception) {
                isPlaying = false
                Toast.makeText(requireContext(), "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Auto-saves the recording with the naming convention: {seqNum:02d}_{pointCode}.wav
     * Stores it in filesDir/lungs/{seqNum:02d}/
     * Inserts a LungRecordingEntity into Room.
     * Pops the back stack back to PlacementFragment — Room's Flow will automatically
     * update the placement overlay to mark this point as done.
     */
    private fun saveRecording(
        filePath: String,
        rawFilePath: String,
        patientId: Long,
        patientSeqNum: Int,
        pointCode: String,
        @Suppress("UNUSED_PARAMETER") pointLabel: String
    ) {
        binding.saveButton.isEnabled = false
        binding.saveButton.text = getString(R.string.saving_recording)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val seqStr = "%02d".format(patientSeqNum)
                val destDir = File(requireContext().filesDir, "lungs/$seqStr")
                destDir.mkdirs()

                val destFile = File(destDir, "${seqStr}_${pointCode}.wav")
                val renamed = File(filePath).renameTo(destFile)
                if (!renamed) {
                    // Fallback: copy then delete
                    File(filePath).copyTo(destFile, overwrite = true)
                    File(filePath).delete()
                }

                // Delete raw temp file — not needed for lungs workflow
                if (rawFilePath.isNotEmpty()) {
                    try { File(rawFilePath).delete() } catch (_: Exception) {}
                }

                // Compute duration from WAV byte count
                val durationSeconds = try {
                    val size = destFile.length() - 44L
                    (size / 2L / 44100L).toInt()
                } catch (_: Exception) { 0 }

                // Insert into Room
                val db = LungsDatabase.getInstance(requireContext())
                db.lungRecordingDao().insert(
                    LungRecordingEntity(
                        patientId = patientId,
                        pointCode = pointCode,
                        filePath = destFile.absolutePath,
                        durationSeconds = durationSeconds
                    )
                )

                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    // Pop back to PlacementFragment — its ViewModel observes Room reactively
                    // and will auto-mark this point as done + advance region if needed.
                    findNavController().popBackStack(R.id.placementFragment, false)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    binding.saveButton.isEnabled = true
                    binding.saveButton.text = getString(R.string.btn_save)
                    Toast.makeText(
                        requireContext(),
                        "${getString(R.string.save_failed)}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun confirmDiscard(filePath: String, rawFilePath: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.discard_confirm_title))
            .setMessage(getString(R.string.discard_confirm_message))
            .setPositiveButton(getString(R.string.btn_discard_confirm)) { _, _ ->
                try { File(filePath).delete() } catch (_: Exception) {}
                if (rawFilePath.isNotEmpty()) try { File(rawFilePath).delete() } catch (_: Exception) {}
                findNavController().navigateUp()
            }
            .setNegativeButton(getString(R.string.btn_keep)) { d, _ -> d.dismiss() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            player?.onPlaybackProgress = null
            player?.onPlaybackComplete = null
            player?.stop()
            player?.release()
        } catch (_: Exception) {}
        _binding = null
    }
}
