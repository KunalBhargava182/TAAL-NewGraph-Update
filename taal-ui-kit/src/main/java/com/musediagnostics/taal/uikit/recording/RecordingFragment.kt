package com.musediagnostics.taal.uikit.recording

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import com.musediagnostics.taal.uikit.R
import com.musediagnostics.taal.uikit.TaalRecorderActivity
import com.musediagnostics.taal.uikit.databinding.FragmentRecordingBinding
import com.musediagnostics.taal.uikit.dsp.HeartBpmCalculator
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingFragment : Fragment() {

    private var _binding: FragmentRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    private var audioTrack: AudioTrack? = null
    private val waveformEntries = ArrayList<Entry>()

    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    private var waveformDataSet: LineDataSet? = null
    private var peakAmplitude = 1.0f
    private var lastPeakUpdateTime = 0L
    private var warmupPeak = 0f
    private var warmupDone = false
    private var recordingStartTime = 0L
    private var chartInitialized = false
    private var totalSamplesProcessed = 0L
    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val WINDOW_SECONDS = 10f
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val DOWNSAMPLE_STEP = 44
        private const val WARMUP_MS = 2000L
        private const val HEADROOM = 1.5f
        private const val MIN_PEAK = 0.02f
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(requireContext(), "Audio permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Apply initial values from activity intent if available
        val activity = requireActivity() as? TaalRecorderActivity
        activity?.let {
            val initFilter = it.intent.getStringExtra(TaalRecorderActivity.EXTRA_PRE_FILTER) ?: "HEART"
            val initPreAmp = it.intent.getIntExtra(TaalRecorderActivity.EXTRA_PRE_AMPLIFICATION, 5)
            viewModel.setFilter(initFilter)
            viewModel.setPreAmp(initPreAmp)
        }

        setupWaveformChart()
        setupFilterButtons()
        setupCustomRangePanel()
        setupPreAmpSlider()
        setupButtons()
        observeState()
        setupConnectionReceiver()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.uiState.value == RecordingUiState.RECORDING) {
                        Toast.makeText(
                            requireContext(),
                            "Stop the recording before going back",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
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
            setLabelCount(10, false)
            setDrawAxisLine(false)
            setDrawLabels(false)
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            gridLineWidth = 1f
            axisMinimum = -1f
            axisMaximum = 1f
            setLabelCount(5, true)
            setDrawLabels(false)
            setDrawAxisLine(false)
        }

        chart.axisRight.isEnabled = false
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)

        val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(WINDOW_SECONDS, 0f)), "").apply {
            color = Color.TRANSPARENT
            setDrawCircles(false)
            setDrawValues(false)
        }
        chart.data = LineData(dummyDataSet)
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

    private fun setupFilterButtons() {
        val presetFilters = mapOf(
            binding.filterHeart to "HEART",
            binding.filterLungs to "LUNGS",
            binding.filterBowel to "BOWEL",
            binding.filterPregnancy to "PREGNANCY",
            binding.filterInfo to "FULL_BODY"
        )
        val allButtons = presetFilters.keys + binding.filterCustom

        val currentFilter = viewModel.currentFilter.value ?: "HEART"
        if (currentFilter == "CUSTOM") {
            binding.filterCustom.isSelected = true
            binding.customRangePanel.visibility = View.VISIBLE
        } else {
            val btn = presetFilters.entries.find { it.value == currentFilter }?.key
            (btn ?: binding.filterHeart).isSelected = true
            binding.customRangePanel.visibility = View.GONE
        }

        presetFilters.forEach { (button, name) ->
            button.setOnClickListener {
                allButtons.forEach { it.isSelected = false }
                button.isSelected = true
                viewModel.setFilter(name)
                binding.customRangePanel.visibility = View.GONE
                dismissKeyboard()
            }
        }

        binding.filterCustom.setOnClickListener {
            allButtons.forEach { it.isSelected = false }
            binding.filterCustom.isSelected = true
            viewModel.setFilter("CUSTOM")
            binding.customRangePanel.visibility = View.VISIBLE
        }
    }

    private fun setupCustomRangePanel() {
        val initLow  = viewModel.customLowCut  ?: 20f
        val initHigh = viewModel.customHighCut ?: 10000f

        viewModel.customLowCut  = initLow
        viewModel.customHighCut = initHigh

        binding.customRangeSlider.values = listOf(
            initLow.coerceIn(0f, 24000f),
            initHigh.coerceIn(0f, 24000f)
        )
        binding.customLowCutInput.setText(initLow.toInt().toString())
        binding.customHighCutInput.setText(initHigh.toInt().toString())

        var isUpdating = false

        binding.customRangeSlider.addOnChangeListener { _, _, _ ->
            if (isUpdating) return@addOnChangeListener
            isUpdating = true
            val vals = binding.customRangeSlider.values
            binding.customLowCutInput.setText(vals[0].toInt().toString())
            binding.customHighCutInput.setText(vals[1].toInt().toString())
            viewModel.customLowCut  = vals[0]
            viewModel.customHighCut = vals[1]
            isUpdating = false
        }

        binding.customLowCutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                viewModel.customLowCut = v.coerceIn(1f, 24000f)
                val currentHigh = binding.customRangeSlider.values[1]
                if (v in 1f..24000f && v < currentHigh) {
                    isUpdating = true
                    binding.customRangeSlider.values = listOf(v, currentHigh)
                    isUpdating = false
                }
            }
        })

        binding.customHighCutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                viewModel.customHighCut = v.coerceIn(1f, 24000f)
                val currentLow = binding.customRangeSlider.values[0]
                if (v in 1f..24000f && v > currentLow) {
                    isUpdating = true
                    binding.customRangeSlider.values = listOf(currentLow, v)
                    isUpdating = false
                }
            }
        })
    }

    private fun dismissKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)
        requireView().clearFocus()
    }

    private fun setFilterButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        listOf(
            binding.filterHeart,
            binding.filterLungs,
            binding.filterBowel,
            binding.filterPregnancy,
            binding.filterInfo,
            binding.filterCustom
        ).forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
        if (!enabled) {
            binding.customRangePanel.visibility = View.GONE
        } else if (viewModel.currentFilter.value == "CUSTOM") {
            binding.customRangePanel.visibility = View.VISIBLE
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
            val usbManager =
                requireContext().getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            if (usbManager.deviceList.isNotEmpty()) {
                binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))
            } else {
                binding.deviceIcon.setColorFilter(Color.parseColor("#333333"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupButtons() {
        binding.menuButton.visibility = View.GONE
        binding.settingsButton.visibility = View.GONE

        binding.infoButton.setOnClickListener {
            val filterName = viewModel.currentFilter.value ?: "HEART"
            FilterPlacementDialog.newInstance(filterName)
                .show(parentFragmentManager, "filter_placement")
        }

        binding.folderButton.setOnClickListener {
            findNavController().navigate(R.id.action_recording_to_savedRecordings)
        }

        binding.recordButton.setOnClickListener {
            when (viewModel.uiState.value) {
                RecordingUiState.IDLE -> checkPermissionAndRecord()
                RecordingUiState.RECORDING -> stopRecording()
                else -> resetToIdle()
            }
        }

        binding.playPauseButton.setOnClickListener {
            val filteredPath = viewModel.currentFilteredPath
            val rawPath = viewModel.currentRecordingPath
            val filterName = viewModel.currentFilter.value ?: "HEART"
            if (filteredPath.isNotEmpty()) {
                val bundle = Bundle().apply {
                    putString("filePath", filteredPath)
                    putString("rawFilePath", rawPath)
                    putBoolean("isNewRecording", false)
                    putString("filterName", filterName)
                }
                findNavController().navigate(R.id.action_recording_to_player, bundle)
            }
        }
    }

    private fun resetToIdle() {
        viewModel.setUiState(RecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        viewModel.currentFilteredPath = ""
        waveformEntries.clear()
        waveformDataSet = null
        chartInitialized = false
        peakAmplitude = 1.0f
        warmupPeak = 0f
        warmupDone = false
        lastPeakUpdateTime = 0L
        totalSamplesProcessed = 0L
        bpmCalculator.reset()

        val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(10f, 0f)), "").apply {
            color = Color.TRANSPARENT
            setDrawCircles(false)
            setDrawValues(false)
        }
        binding.waveformChart.data = LineData(dummyDataSet)
        binding.waveformChart.moveViewToX(0f)
        binding.waveformChart.invalidate()

        binding.bpmText.text = "-- BPM"
    }

    private fun observeState() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                RecordingUiState.IDLE -> {
                    binding.actionText.text = getString(R.string.start_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.VISIBLE
                    binding.preRecordingButtons.visibility = View.VISIBLE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordButton.setImageResource(R.drawable.ic_recording_start1)
                    binding.timerText.text = getString(R.string.timer_default)
                    binding.ampSlider.isEnabled = true
                    binding.ampSliderContainer.alpha = 1f
                    setFilterButtonsEnabled(true)
                }

                RecordingUiState.RECORDING -> {
                    binding.actionText.text = getString(R.string.stop_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.GONE
                    binding.preRecordingButtons.visibility = View.GONE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordButton.setImageResource(R.drawable.ic_recording_stop)
                    binding.ampSlider.isEnabled = false
                    binding.ampSliderContainer.alpha = 0.55f
                    setFilterButtonsEnabled(false)
                }

                else -> {}
            }
        }

        viewModel.timerSeconds.observe(viewLifecycleOwner) { seconds ->
            binding.timerText.text = viewModel.formatTimer(seconds)
        }

        viewModel.bpm.observe(viewLifecycleOwner) { bpm ->
            binding.bpmText.text = if (bpm > 0) getString(R.string.bpm_format, bpm) else "-- BPM"
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
        dismissKeyboard()

        val filterName = viewModel.currentFilter.value ?: "HEART"

        if (filterName == "CUSTOM") {
            val low  = viewModel.customLowCut
            val high = viewModel.customHighCut
            val lowText  = binding.customLowCutInput.text?.toString()?.trim()
            val highText = binding.customHighCutInput.text?.toString()?.trim()

            val message = when {
                lowText.isNullOrEmpty() && highText.isNullOrEmpty() ->
                    "Low Cut and High Cut cannot be blank.\nPlease enter valid frequency values."
                lowText.isNullOrEmpty() ->
                    "Low Cut cannot be blank."
                highText.isNullOrEmpty() ->
                    "High Cut cannot be blank."
                low == null || low <= 0f ->
                    "Low Cut cannot be 0 Hz. Please enter a frequency greater than 0 Hz."
                high == null || high <= 0f ->
                    "High Cut cannot be 0 Hz. Please enter a frequency greater than 0 Hz."
                low >= high ->
                    "Low Cut (${low.toInt()} Hz) must be less than High Cut (${high.toInt()} Hz)."
                else -> null
            }

            if (message != null) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Custom Filter")
                    .setMessage(message)
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .show()
                return
            }
        }

        try {
            val ts = System.currentTimeMillis()
            val rawFilePath = "${requireContext().filesDir}/recording_${ts}_raw.wav"
            val filteredFilePath = "${requireContext().filesDir}/recording_${ts}_filtered.wav"
            viewModel.currentRecordingPath = rawFilePath
            viewModel.currentFilteredPath = filteredFilePath

            taalRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(rawFilePath)
                setFilteredAudioFilePath(filteredFilePath)
                setRecordingTime(300)
                setPlayback(false)
                setPreAmplification(viewModel.preAmpDb.value ?: 5)
                if (filterName == "CUSTOM") {
                    setCustomBandpass(
                        viewModel.customLowCut!!.toDouble(),
                        viewModel.customHighCut!!.toDouble()
                    )
                } else {
                    setPreFilter(PreFilter.valueOf(filterName))
                }

                onInfoListener = object : TaalRecorder.OnInfoListener {
                    override fun onStateChange(state: RecorderState) {
                        activity?.runOnUiThread {
                            when (state) {
                                RecorderState.RECORDING -> viewModel.setUiState(RecordingUiState.RECORDING)
                                RecorderState.STOPPED -> viewModel.setUiState(RecordingUiState.STOPPED)
                                else -> {}
                            }
                        }
                    }

                    override fun onProgressUpdate(
                        sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
                    ) {
                        audioTrack?.let { track ->
                            val pcm = ShortArray(data.size) { i ->
                                (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                            track.write(pcm, 0, pcm.size)
                        }

                        val shouldCompute = bpmCalculator.addSamples(data)
                        if (shouldCompute) {
                            bpmScope.launch {
                                val bpm = bpmCalculator.computeBpm()
                                if (bpm > 0) {
                                    withContext(Dispatchers.Main) {
                                        if (isAdded && _binding != null) {
                                            viewModel.setBpm(bpm)
                                        }
                                    }
                                }
                            }
                        }

                        // Undo pre-amp gain before drawing the waveform so the graph always
                        // reflects the acoustic signal level, not the amplified version.
                        val preAmpDb = viewModel.preAmpDb.value ?: 5
                        val preAmpGain = Math.pow(10.0, preAmpDb / 20.0).toFloat()
                        val displayData = if (preAmpGain > 1.001f) {
                            FloatArray(data.size) { i -> data[i] / preAmpGain }
                        } else {
                            data
                        }

                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                updateWaveform(timeStamp, displayData)
                                val elapsed = timeStamp.toInt()
                                viewModel.updateTimer(elapsed)
                            }
                        }
                    }
                }
            }

            recordingStartTime = System.currentTimeMillis()
            waveformEntries.clear()
            waveformDataSet = null
            chartInitialized = false
            peakAmplitude = 1.0f
            warmupPeak = 0f
            warmupDone = false
            lastPeakUpdateTime = 0L
            totalSamplesProcessed = 0L
            bpmCalculator.reset()
            binding.waveformChart.data = LineData()
            binding.waveformChart.moveViewToX(0f)
            startAudioMonitor()
            taalRecorder?.start()

        } catch (e: Exception) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording Error").setMessage(e.message)
                .setPositiveButton("OKAY") { dialog, _ -> dialog.dismiss() }.show()
            viewModel.setUiState(RecordingUiState.IDLE)
        }
    }

    private fun startAudioMonitor() {
        val minBuf = AudioTrack.getMinBufferSize(
            44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            44100,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
            AudioTrack.MODE_STREAM
        ).apply { play() }
    }

    private fun stopAudioMonitor() {
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private fun stopRecording() {
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
        taalRecorder = null

        val filteredPath = viewModel.currentFilteredPath
        val rawPath = viewModel.currentRecordingPath
        val filterName = viewModel.currentFilter.value ?: "HEART"

        if (filteredPath.isNotEmpty()) {
            val bundle = Bundle().apply {
                putString("filePath", filteredPath)
                putString("rawFilePath", rawPath)
                putBoolean("isNewRecording", true)
                putString("filterName", filterName)
            }
            findNavController().navigate(R.id.action_recording_to_player, bundle)
        }
    }

    private fun updateWaveform(timestamp: Double, data: FloatArray) {
        if (_binding == null || !isAdded) return
        val chart = binding.waveformChart

        var bufferPeak = 0f
        for (sample in data) {
            val abs = Math.abs(sample)
            if (abs > bufferPeak) bufferPeak = abs
        }

        val step = DOWNSAMPLE_STEP
        for (i in 0 until data.size step step) {
            val currentX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
            waveformEntries.add(Entry(currentX, data[i]))
            totalSamplesProcessed += step
        }

        val latestX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE

        // Page-based memory cleanup: keep current page and previous page only
        val currentPage = (latestX / WINDOW_SECONDS).toInt()
        val minXToKeep = (currentPage - 1) * WINDOW_SECONDS
        if (minXToKeep > 0) {
            val iterator = waveformEntries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().x < minXToKeep) {
                    iterator.remove()
                } else {
                    break
                }
            }
        }

        val now = System.currentTimeMillis()

        if (!warmupDone) {
            if (bufferPeak > warmupPeak) warmupPeak = bufferPeak
            if (lastPeakUpdateTime == 0L) lastPeakUpdateTime = now

            // Once warmup duration has passed, lock the Y-axis scale permanently
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
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 1.5f
                mode = LineDataSet.Mode.LINEAR
                setDrawHighlightIndicators(false)
            }
            chart.data = LineData(waveformDataSet)
        } else {
            ds.values = snapshot
            chart.data?.notifyDataChanged()
        }
        chart.notifyDataSetChanged()

        // Re-enforce the strict 10-second view width
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)

        // Snap the view to the start of the current 10-second page
        chart.moveViewToX(currentPage * WINDOW_SECONDS)

        chart.invalidate()
    }

    override fun onResume() {
        super.onResume()

        checkDeviceConnectionStatus()
        if (taalRecorder == null) {
            resetToIdle()
        }
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
        bpmScope.cancel()
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
    }
}
