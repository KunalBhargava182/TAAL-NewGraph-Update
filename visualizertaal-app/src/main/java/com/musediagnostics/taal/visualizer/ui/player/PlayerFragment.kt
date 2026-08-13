package com.musediagnostics.taal.visualizer.ui.player

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.visualizer.R
import com.musediagnostics.taal.visualizer.data.db.VisualizerDatabase
import com.musediagnostics.taal.visualizer.data.db.entity.RecordingEntity
import com.musediagnostics.taal.visualizer.data.db.entity.displayName
import com.musediagnostics.taal.visualizer.data.repository.RecordingRepository
import com.musediagnostics.taal.visualizer.data.repository.SessionRepository
import com.musediagnostics.taal.visualizer.databinding.FragmentVtPlayerBinding
import com.musediagnostics.taal.visualizer.domain.PointSetType
import com.musediagnostics.taal.visualizer.util.DownloadsStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles both a fresh recording (Save/Discard visible, filePath = local temp WAV) and
 * review of a previously-saved recording (isReviewMode=true, Save/Discard hidden,
 * filePath may be a content:// MediaStore URI on API 29+ — resolved to a cache file
 * before playback since TaalPlayer reads via java.io.File).
 *
 * Every file that reaches this screen is already filtered (TaalRecorder filters in
 * real time on capture), so playback never re-applies a PreFilter here. Saving is
 * automatic and name-free: the point label + session number determine the filename,
 * so tapping Save just saves and moves on — no dialog.
 */
class PlayerFragment : Fragment() {

    private var _binding: FragmentVtPlayerBinding? = null
    private val binding get() = _binding!!

    private var player: TaalPlayer? = null
    private var isPlaying = false

    private data class PendingSave(
        val filePath: String,
        val rawFilePath: String,
        val pointSetType: PointSetType,
        val pointCode: String,
        val pointLabel: String,
        val sessionId: Long,
        val sessionNumber: Int,
        val returnTo: String
    )
    private var pendingSave: PendingSave? = null

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingSave
        pendingSave = null
        if (granted && pending != null) {
            performSave(pending)
        } else if (!granted) {
            binding.saveButton.isEnabled = true
            binding.saveButton.text = getString(R.string.btn_save)
            Toast.makeText(requireContext(), "Storage permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVtPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val argFilePath = arguments?.getString("filePath") ?: ""
        val rawFilePath = arguments?.getString("rawFilePath") ?: ""
        val pointSetType = PointSetType.valueOf(arguments?.getString("pointSet") ?: PointSetType.HEART.name)
        val pointCode = arguments?.getString("pointCode") ?: ""
        val sessionId = arguments?.getLong("sessionId") ?: -1L
        val sessionNumber = arguments?.getInt("sessionNumber") ?: 1
        val isReviewMode = arguments?.getBoolean("isReviewMode") ?: false
        val returnTo = arguments?.getString("returnTo") ?: "placement"

        val point = pointSetType.pointByCode(pointCode)
        val pointLabel = point?.label ?: pointCode.uppercase()

        binding.screenTitle.text = if (isReviewMode) "Review: $pointLabel" else pointLabel

        if (isReviewMode) applyReviewMode()

        setupWaveformChart()
        setupAmpSlider()

        val playablePath = when {
            argFilePath.isEmpty() -> ""
            isReviewMode -> DownloadsStorage.resolvePlayablePath(requireContext(), argFilePath)
            else -> argFilePath
        }

        if (playablePath.isNotEmpty()) {
            loadFullWaveform(playablePath)
            setupPlayer(playablePath)
        }

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        binding.playButton.setOnClickListener {
            if (playablePath.isEmpty()) return@setOnClickListener
            togglePlayback()
        }

        binding.saveButton.setOnClickListener {
            val pending = PendingSave(
                argFilePath, rawFilePath, pointSetType, pointCode, pointLabel, sessionId, sessionNumber, returnTo
            )
            if (DownloadsStorage.needsLegacyWritePermission(requireContext())) {
                pendingSave = pending
                requestStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                performSave(pending)
            }
        }

        binding.discardButton.setOnClickListener {
            confirmDiscard(argFilePath, rawFilePath)
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
                            binding.playButton.setImageResource(R.drawable.vt_ic_play_circle)
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

    private fun togglePlayback() {
        val activePlayer = player
        if (activePlayer == null) {
            Toast.makeText(requireContext(), "Cannot open recording", Toast.LENGTH_SHORT).show()
            return
        }
        if (isPlaying) {
            activePlayer.stop(); isPlaying = false
            binding.actionText.text = getString(R.string.play_recording)
            binding.playButton.setImageResource(R.drawable.vt_ic_play_circle)
        } else {
            try {
                activePlayer.prepare(); activePlayer.start(); isPlaying = true
                binding.actionText.text = getString(R.string.stop_recording)
                binding.playButton.setImageResource(R.drawable.vt_ic_recording_stop)
            } catch (e: Exception) {
                isPlaying = false
                Toast.makeText(requireContext(), "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Filtered-only save: filtered temp WAV -> public Downloads/Audios/{PointSet}/Session {N}/,
     * raw temp always discarded. Filename is {sessionDisplayName}_{pointLabel}.wav — so
     * a renamed session's saves (and re-records) automatically pick up the new name, and
     * re-recording the same point in the same session naturally overwrites the same file.
     */
    private fun performSave(pending: PendingSave) {
        binding.saveButton.isEnabled = false
        binding.saveButton.text = getString(R.string.saving_recording)
        val ctx = requireContext()

        val subFolders = listOf(pending.pointSetType.displayName, "Session ${pending.sessionNumber}")

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = VisualizerDatabase.getInstance(ctx)
                val sessionRepo = SessionRepository(db.sessionDao())
                val recordingRepo = RecordingRepository(db.recordingDao())

                val session = sessionRepo.getById(pending.sessionId)
                val sessionLabel = session?.displayName() ?: "Session ${pending.sessionNumber}"
                val fileName = "${DownloadsStorage.sanitizeFileNamePart(sessionLabel)}_" +
                    "${DownloadsStorage.sanitizeFileNamePart(pending.pointLabel)}.wav"

                val uriOrPath = DownloadsStorage.save(ctx, File(pending.filePath), fileName, subFolders)

                try { File(pending.filePath).delete() } catch (_: Exception) {}
                if (pending.rawFilePath.isNotEmpty()) try { File(pending.rawFilePath).delete() } catch (_: Exception) {}

                recordingRepo.insert(
                    RecordingEntity(
                        sessionId = pending.sessionId,
                        pointCode = pending.pointCode,
                        fileName = fileName,
                        uriOrPath = uriOrPath
                    )
                )
                val recordedCount = recordingRepo.getRecordingCountForSession(pending.sessionId)
                val sessionComplete = recordedCount >= pending.pointSetType.points.size

                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    val target = when {
                        sessionComplete -> R.id.pointSetHomeFragment
                        pending.returnTo == "review" -> R.id.reviewFragment
                        else -> R.id.placementFragment
                    }
                    if (!findNavController().popBackStack(target, false)) {
                        findNavController().popBackStack(R.id.placementFragment, false)
                    }
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
        MaterialAlertDialogBuilder(requireContext())
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
