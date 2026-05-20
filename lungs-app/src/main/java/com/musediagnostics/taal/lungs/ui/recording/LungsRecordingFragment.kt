package com.musediagnostics.taal.lungs.ui.recording

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
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
import com.musediagnostics.taal.lungs.R
import com.musediagnostics.taal.lungs.databinding.FragmentLungsRecordingBinding
import com.musediagnostics.taal.lungs.domain.LungPoints
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver

class LungsRecordingFragment : Fragment() {

    private var _binding: FragmentLungsRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LungsRecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private val waveformEntries = ArrayList<Entry>()
    private var waveformDataSet: LineDataSet? = null
    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    // Waveform rendering state (same V7 pattern as taal-ui-kit)
    private var peakAmplitude = 1.0f
    private var warmupPeak = 0f
    private var warmupDone = false
    private var lastPeakUpdateTime = 0L
    private var totalSamplesProcessed = 0L

    private var autoStopTriggered = false

    // Nav args
    private var patientId: Long = -1L
    private var patientSeqNum: Int = 1
    private var sessionId: Long = -1L
    private var sessionNumber: Int = 1
    private var pointCode: String = ""

    companion object {
        private const val WINDOW_SECONDS = 10f
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val DOWNSAMPLE_STEP = 44
        private const val WARMUP_MS = 2000L
        private const val HEADROOM = 1.5f
        private const val MIN_PEAK = 0.02f
        private const val MAX_RECORDING_SECONDS = 20
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(requireContext(), "Audio permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLungsRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        patientId = arguments?.getLong("patientId") ?: -1L
        patientSeqNum = arguments?.getInt("patientSeqNum") ?: 1
        sessionId = arguments?.getLong("sessionId") ?: -1L
        sessionNumber = arguments?.getInt("sessionNumber") ?: 1
        pointCode = arguments?.getString("pointCode") ?: ""

        // Show point label
        val pointLabel = LungPoints.byCode(pointCode)?.label ?: pointCode.uppercase()
        binding.pointLabel.text = pointLabel
        binding.screenTitle.text = "Record: $pointLabel"

        // Load placement guide image (point_{pointCode}.png) if it exists in drawable
        val imgRes = resources.getIdentifier("point_${pointCode}", "drawable", requireContext().packageName)
        if (imgRes != 0) {
            binding.placementGuideImage.setImageResource(imgRes)
            binding.placementGuideImage.visibility = android.view.View.VISIBLE
        } else {
            binding.placementGuideImage.visibility = android.view.View.GONE
        }

        setupWaveformChart()
        setupPreAmpSlider()
        setupButtons()
        observeState()
        setupConnectionReceiver()
        checkDeviceConnectionStatus()
    }

    private fun setupWaveformChart() {
        val chart = binding.waveformChart
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setDrawGridBackground(true)
        chart.setGridBackgroundColor(Color.WHITE)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            gridLineWidth = 1f
            granularity = 1f
            axisMinimum = 0f
            setDrawAxisLine(false)
            setDrawLabels(false)
        }
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            axisMinimum = -1f
            axisMaximum = 1f
            setDrawLabels(false)
            setDrawAxisLine(false)
        }
        chart.axisRight.isEnabled = false
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)

        val dummy = LineDataSet(listOf(Entry(0f, 0f), Entry(WINDOW_SECONDS, 0f)), "").apply {
            color = Color.TRANSPARENT; setDrawCircles(false); setDrawValues(false)
        }
        chart.data = LineData(dummy)
        chart.invalidate()
    }

    private fun setupPreAmpSlider() {
        binding.ampSlider.value = (viewModel.preAmpDb.value ?: 5).toFloat()
        binding.ampLabel.text = "${viewModel.preAmpDb.value ?: 5} dB"
        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            viewModel.setPreAmp(db)
            binding.ampLabel.text = "$db dB"
            taalRecorder?.setPreAmplification(db)
        }
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        binding.recordButton.setOnClickListener {
            when (viewModel.uiState.value) {
                LungsRecordingUiState.IDLE -> checkPermissionAndRecord()
                LungsRecordingUiState.RECORDING -> stopRecording()
                else -> resetToIdle()
            }
        }
    }

    private fun observeState() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                LungsRecordingUiState.IDLE -> {
                    binding.actionText.text = getString(R.string.start_recording)
                    binding.recordButton.setImageResource(R.drawable.ic_recording_start1)
                    binding.ampSlider.isEnabled = true
                    binding.ampSliderContainer.alpha = 1f
                }
                LungsRecordingUiState.RECORDING -> {
                    binding.actionText.text = getString(R.string.stop_recording)
                    binding.recordButton.setImageResource(R.drawable.ic_recording_stop)
                    binding.ampSlider.isEnabled = false
                    binding.ampSliderContainer.alpha = 0.55f
                }
                else -> {}
            }
        }
        viewModel.timerSeconds.observe(viewLifecycleOwner) { s ->
            binding.timerText.text = viewModel.formatTimer(s)
        }
    }

    private fun setupConnectionReceiver() {
        connectionReceiver = TaalConnectionBroadcastReceiver(object :
            TaalConnectionBroadcastReceiver.TaalConnectionListener {
            override fun onTaalConnect() {
                activity?.runOnUiThread {
                    binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))
                }
            }
            override fun onTaalDisconnect() {
                activity?.runOnUiThread {
                    binding.deviceIcon.setColorFilter(Color.parseColor("#333333"))
                }
            }
        })
        connectionReceiver?.register(requireContext())
    }

    private fun checkDeviceConnectionStatus() {
        try {
            val usb = requireContext().getSystemService(android.content.Context.USB_SERVICE)
                    as android.hardware.usb.UsbManager
            val color = if (usb.deviceList.isNotEmpty()) "#128CB2" else "#333333"
            binding.deviceIcon.setColorFilter(Color.parseColor(color))
        } catch (_: Exception) {}
    }

    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) startRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        try {
            val ts = System.currentTimeMillis()
            val rawPath = "${requireContext().filesDir}/recording_${ts}_raw.wav"
            val filteredPath = "${requireContext().filesDir}/recording_${ts}_filtered.wav"
            viewModel.currentRecordingPath = rawPath
            viewModel.currentFilteredPath = filteredPath

            taalRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(rawPath)
                setFilteredAudioFilePath(filteredPath)
                setRecordingTime(300)
                setPlayback(false)
                setPreAmplification(viewModel.preAmpDb.value ?: 5)
                setPreFilter(PreFilter.LUNGS)

                onInfoListener = object : TaalRecorder.OnInfoListener {
                    override fun onStateChange(state: RecorderState) {
                        activity?.runOnUiThread {
                            when (state) {
                                RecorderState.RECORDING ->
                                    viewModel.setUiState(LungsRecordingUiState.RECORDING)
                                RecorderState.STOPPED ->
                                    viewModel.setUiState(LungsRecordingUiState.STOPPED)
                                else -> {}
                            }
                        }
                    }

                    override fun onProgressUpdate(
                        sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
                    ) {
                        // Feed audio monitor
                        audioTrack?.let { track ->
                            try {
                                val pcm = ShortArray(data.size) { i ->
                                    (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                                }
                                track.write(pcm, 0, pcm.size)
                            } catch (_: IllegalStateException) {
                                // AudioTrack was released while recording was stopping — safe to ignore
                            }
                        }

                        // Undo pre-amp gain before drawing
                        val preAmpGain =
                            Math.pow(10.0, (viewModel.preAmpDb.value ?: 5) / 20.0).toFloat()
                        val displayData =
                            if (preAmpGain > 1.001f) FloatArray(data.size) { i -> data[i] / preAmpGain }
                            else data

                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                updateWaveform(timeStamp, displayData)
                                viewModel.updateTimer(timeStamp.toInt())
                                if (timeStamp >= MAX_RECORDING_SECONDS && !autoStopTriggered) {
                                    autoStopTriggered = true
                                    stopRecording()
                                }
                            }
                        }
                    }
                }
            }

            // Reset waveform state
            autoStopTriggered = false
            waveformEntries.clear()
            waveformDataSet = null
            peakAmplitude = 1.0f; warmupPeak = 0f; warmupDone = false
            lastPeakUpdateTime = 0L; totalSamplesProcessed = 0L
            binding.waveformChart.data = LineData()
            binding.waveformChart.moveViewToX(0f)
            startAudioMonitor()
            taalRecorder?.start()

        } catch (e: Exception) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording Error").setMessage(e.message)
                .setPositiveButton("OK") { d, _ -> d.dismiss() }.show()
            viewModel.setUiState(LungsRecordingUiState.IDLE)
        }
    }

    private fun stopRecording() {
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
        taalRecorder = null

        val filteredPath = viewModel.currentFilteredPath
        val rawPath = viewModel.currentRecordingPath

        if (filteredPath.isNotEmpty()) {
            findNavController().navigate(
                R.id.action_lungs_recording_to_player,
                Bundle().apply {
                    putString("filePath", filteredPath)
                    putString("rawFilePath", rawPath)
                    putLong("patientId", patientId)
                    putInt("patientSeqNum", patientSeqNum)
                    putLong("sessionId", sessionId)
                    putInt("sessionNumber", sessionNumber)
                    putString("pointCode", pointCode)
                }
            )
        }
    }

    private fun resetToIdle() {
        autoStopTriggered = false
        viewModel.setUiState(LungsRecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        viewModel.currentFilteredPath = ""
        waveformEntries.clear()
        waveformDataSet = null
        peakAmplitude = 1.0f; warmupPeak = 0f; warmupDone = false
        lastPeakUpdateTime = 0L; totalSamplesProcessed = 0L
        val dummy = LineDataSet(listOf(Entry(0f, 0f), Entry(10f, 0f)), "").apply {
            color = Color.TRANSPARENT; setDrawCircles(false); setDrawValues(false)
        }
        binding.waveformChart.data = LineData(dummy)
        binding.waveformChart.moveViewToX(0f)
        binding.waveformChart.invalidate()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun updateWaveform(timestamp: Double, data: FloatArray) {
        if (_binding == null || !isAdded) return
        val chart = binding.waveformChart
        var bufferPeak = 0f
        for (sample in data) { val abs = Math.abs(sample); if (abs > bufferPeak) bufferPeak = abs }

        for (i in 0 until data.size step DOWNSAMPLE_STEP) {
            waveformEntries.add(Entry(totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE, data[i]))
            totalSamplesProcessed += DOWNSAMPLE_STEP
        }
        val latestX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
        val currentPage = (latestX / WINDOW_SECONDS).toInt()
        val minXToKeep = (currentPage - 1) * WINDOW_SECONDS
        if (minXToKeep > 0) {
            val it = waveformEntries.iterator()
            while (it.hasNext()) { if (it.next().x < minXToKeep) it.remove() else break }
        }

        val now = System.currentTimeMillis()
        if (!warmupDone) {
            if (bufferPeak > warmupPeak) warmupPeak = bufferPeak
            if (lastPeakUpdateTime == 0L) lastPeakUpdateTime = now
            if (now - lastPeakUpdateTime >= WARMUP_MS) {
                warmupDone = true
                peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
                chart.axisLeft.axisMinimum = -peakAmplitude
                chart.axisLeft.axisMaximum = peakAmplitude
            }
        }

        val snapshot = ArrayList(waveformEntries.toList())
        val ds = waveformDataSet
        if (ds == null || chart.data == null) {
            waveformDataSet = LineDataSet(snapshot, "Waveform").apply {
                color = ContextCompat.getColor(requireContext(), R.color.waveform_blue)
                setDrawCircles(false); setDrawValues(false)
                lineWidth = 1.5f; mode = LineDataSet.Mode.LINEAR
                setDrawHighlightIndicators(false)
            }
            chart.data = LineData(waveformDataSet)
        } else {
            ds.values = snapshot
            chart.data?.notifyDataChanged()
        }
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)
        chart.moveViewToX(currentPage * WINDOW_SECONDS)
        chart.invalidate()
    }

    private fun startAudioMonitor() {
        val minBuf = AudioTrack.getMinBufferSize(
            44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(44100)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }

    private fun stopAudioMonitor() {
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    override fun onResume() {
        super.onResume()
        checkDeviceConnectionStatus()
        if (taalRecorder == null) resetToIdle()
        viewModel.setPreAmp(5)
        binding.ampSlider.value = 5f
        binding.ampLabel.text = "5 dB"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        connectionReceiver?.unregister(requireContext())
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
    }
}
