package com.musediagnostics.taal.app.ui.recording

//THIS IS NOT IN USE (ONLY TESTING BY KUNAL)


import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalRecorder
import com.musediagnostics.taal.core.RecorderState
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentTestRecordingBinding
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.*

class TestRecordingFragment : Fragment() {

    private var _binding: FragmentTestRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    private var audioTrack: AudioTrack? = null
    private val waveformEntries = ArrayList<Entry>()
    private var waveformDataSet: LineDataSet? = null

    private var isRecording = false
    private var totalSamplesProcessed = 0L

    // Connection listener
    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val DOWNSAMPLE_STEP = 44
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else Toast.makeText(
                requireContext(), "Permission needed", Toast.LENGTH_SHORT
            ).show()
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTestRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupWaveformChart()
        setupButtons()
        setupConnectionReceiver()
    }

    private fun setupConnectionReceiver() {
        connectionReceiver = TaalConnectionBroadcastReceiver(object :
            TaalConnectionBroadcastReceiver.TaalConnectionListener {
            override fun onTaalConnect() {
                activity?.runOnUiThread {
                    binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2")) // Teal
                }
            }

            override fun onTaalDisconnect() {
                activity?.runOnUiThread {
                    binding.deviceIcon.setColorFilter(Color.parseColor("#333333")) // Gray/Black
                }
            }
        })
        connectionReceiver?.register(requireContext())
    }

    private fun setupWaveformChart() {
        val chart = binding.waveformChart
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        // No grid/background of its own — EcgPaperView underneath (see
        // fragment_test_recording.xml) draws the real millimetre-accurate grid;
        // this chart only plots the trace on a transparent surface on top of it.
        chart.setDrawGridBackground(false)
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
            setDrawAxisLine(false)
            setDrawLabels(false)
        }

        chart.axisLeft.apply {
            setDrawGridLines(false)
            axisMinimum = -1f
            axisMaximum = 1f
            setDrawLabels(false)
            setDrawAxisLine(false)
        }

        chart.axisRight.isEnabled = false
        chart.setVisibleXRangeMaximum(10f)
        chart.data = LineData()
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        binding.recordButton.setOnClickListener {
            if (isRecording) stopRecording() else checkPermissionAndRecord()
        }

        // Simple visual feedback for filters
        val filters = listOf(
            binding.filterHeart,
            binding.filterLungs,
            binding.filterBowel,
            binding.filterPregnancy,
            binding.filterInfo
        )
        filters.forEach { btn ->
            btn.setOnClickListener {
                filters.forEach { it.isSelected = false; it.setColorFilter(Color.GRAY) }
                btn.isSelected = true
                btn.setColorFilter(Color.parseColor("#F44336")) // Highlight selected in red
            }
        }
    }

    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        val filePath = "${requireContext().filesDir}/recording_${System.currentTimeMillis()}.wav"
        viewModel.currentRecordingPath = filePath

        // GET THE DB SLIDER VALUE BEFORE STARTING
        val gainDb = binding.gainSlider.value.toInt()

        // Prevent slider change during recording
        binding.gainSlider.isEnabled = false

        taalRecorder = TaalRecorder(requireContext()).apply {
            setRawAudioFilePath(filePath)
            setRecordingTime(300)
            setPlayback(false)
            setPreAmplification(gainDb) // Applied correctly
            setPreFilter(PreFilter.HEART) // Modify based on selected filter icon if needed

            onInfoListener = object : TaalRecorder.OnInfoListener {
                override fun onStateChange(state: RecorderState) {}
                override fun onProgressUpdate(
                    sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
                ) {
                    activity?.runOnUiThread {
                        if (isAdded) {
                            updateWaveform(data)
                            val elapsed = timeStamp.toInt()
                            binding.timerText.text =
                                String.format("%02d:%02d", elapsed / 60, elapsed % 60)
                        }
                    }
                }
            }
        }

        waveformEntries.clear()
        totalSamplesProcessed = 0L
        binding.waveformChart.data = LineData()

        try {
            taalRecorder?.start()
        } catch (e: Exception) {
            binding.gainSlider.isEnabled = true
            Toast.makeText(requireContext(), e.message ?: "Recording error", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        binding.actionText.text = "Stop Recording"
        binding.recordButton.setImageResource(R.drawable.ic_stop) // Use a square stop icon if you have one
    }

    private fun updateWaveform(data: FloatArray) {
        val chart = binding.waveformChart
        val step = DOWNSAMPLE_STEP

        for (i in 0 until data.size step step) {
            val x = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
            waveformEntries.add(Entry(x, data[i]))
            totalSamplesProcessed += step
        }

        val currentX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE

        // Keep 10 seconds of data visible
        if (currentX > 10f) {
            val trimIndex = waveformEntries.indexOfFirst { it.x >= (currentX - 10f) }
            if (trimIndex > 0) waveformEntries.subList(0, trimIndex).clear()
        }

        if (waveformDataSet == null) {
            waveformDataSet = LineDataSet(ArrayList(waveformEntries), "Waveform").apply {
                color = Color.parseColor("#128CB2") // Clean Teal
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 1.5f
                mode = LineDataSet.Mode.LINEAR
            }
            chart.data = LineData(waveformDataSet)
        } else {
            waveformDataSet?.values = ArrayList(waveformEntries)
            chart.data?.notifyDataChanged()
        }

        chart.notifyDataSetChanged()
        chart.moveViewToX(currentX - 10f)
    }

    private fun stopRecording() {
        taalRecorder?.stop()
        taalRecorder = null
        isRecording = false
        binding.gainSlider.isEnabled = true

        // Use the modern direct-navigation approach specified in your prompt
        val bundle = Bundle().apply {
            putString("filePath", viewModel.currentRecordingPath)
            putBoolean("isNewRecording", true)
        }
        findNavController().navigate(R.id.action_testRecording_to_testPlayer, bundle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        connectionReceiver?.let { it.unregister(requireContext()) }
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        taalRecorder?.stop()
    }
}