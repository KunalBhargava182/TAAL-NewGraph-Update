package com.musediagnostics.taal.app.ui.recording

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
// import com.musediagnostics.taal.dsp.AudioDownsampler  // AI downsampling disabled
// import com.musediagnostics.taal.dsp.AudioFilterEngine  // AI downsampling disabled
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import com.musediagnostics.taal.app.R
import java.io.File
// import java.io.FileOutputStream  // AI downsampling disabled
// import java.io.RandomAccessFile  // AI downsampling disabled
import com.musediagnostics.taal.app.databinding.FragmentProductionRecordingBinding
import com.musediagnostics.taal.app.dsp.HeartBpmCalculator
import com.musediagnostics.taal.app.ui.MainActivity
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProductionRecordingFragment : Fragment() {

    private var _binding: FragmentProductionRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    private var audioTrack: AudioTrack? = null
    private val waveformEntries = ArrayList<Entry>()

    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    // Persistent dataset — reused every callback to avoid MPAndroidChart's
    // mOffsetsCalculated=false reset that causes a blank frame each time chart.data is replaced.
    private var waveformDataSet: LineDataSet? = null
    private var peakAmplitude = 1.0f       // Y-axis half-range; set from warmup then locked
    private var lastPeakUpdateTime = 0L
    private var warmupPeak = 0f           // Accumulatesf absolute-peak over the warmup window
    private var warmupDone = false        // Latches true after WARMUP_MS of signal observed
    private var recordingStartTime = 0L
    private var chartInitialized = false
    private var totalSamplesProcessed = 0L  // Sample-accurate X position counter
    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── AI testing pipeline (DISABLED) ───────────────────────────────────────
    // private val heartResampler8k = AudioDownsampler(8000, AudioFilterEngine.PresetFilter.HEART)
    // @Volatile private var aiTestingFos: FileOutputStream? = null
    // @Volatile private var aiTestingBytesWritten = 0
    // private var activeDownsamplingStreams: List<DownsamplingStream> = emptyList()

    companion object {
        private const val WINDOW_SECONDS = 10f
        private const val INPUT_SAMPLE_RATE = 44100f

        // Fixed downsample step: 44100 / 44 ≈ 1002 points/sec — good visual resolution
        private const val DOWNSAMPLE_STEP = 44

        // WARMUP: measure the signal's true peak over the first 1500ms, then lock in the Y-axis.
        private const val WARMUP_MS = 2000

        // After warmup, Y-axis = peakAmplitude × HEADROOM so waveform fills ~65% of height.
        private const val HEADROOM = 1.5f

        // Minimum axis half-range — prevents over-zooming on near-silence
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
        _binding = FragmentProductionRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupWaveformChart()
        setupFilterButtons()
        setupPreAmpSlider()
        setupButtons()
        observeState()
        setupConnectionReceiver()

        // Block the back button while recording is active.
        // The callback is tied to viewLifecycleOwner so it auto-removes on destroy.
        // Note: Home button and recent-apps swipe cannot be blocked — Android OS restriction.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.uiState.value == RecordingUiState.RECORDING) {
                        // Swallow the event — user must stop recording first
                        Toast.makeText(
                            requireContext(),
                            "Stop the recording before going back",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        // Not recording — let normal back navigation happen
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
            // REMOVED axisMinimum and axisMaximum entirely here
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

        // Lock viewport size to exactly 10 seconds
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
        // Init slider to viewModel's current value (survives config changes)
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

        // Default: HEART selected, custom panel hidden
        binding.filterHeart.isSelected = true
        viewModel.setFilter("HEART")
        binding.customRangePanel.visibility = View.GONE

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

        setupCustomRangePanel()
    }

    private fun dismissKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)
        requireView().clearFocus()
    }

    private fun setupCustomRangePanel() {
        // Restore from ViewModel if available, otherwise use sensible defaults
        val initLow  = viewModel.customLowCut  ?: 20f
        val initHigh = viewModel.customHighCut ?: 10000f

        // Seed ViewModel immediately so values are valid even before the user touches anything.
        // (setText below fires BEFORE the TextWatcher is attached, so the watcher never sees
        // these initial values — we must write them to the ViewModel explicitly here.)
        viewModel.customLowCut  = initLow
        viewModel.customHighCut = initHigh

        binding.customRangeSlider.values = listOf(
            initLow.coerceIn(0f, 24000f),
            initHigh.coerceIn(0f, 24000f)
        )
        binding.customLowCutInput.setText(initLow.toInt().toString())
        binding.customHighCutInput.setText(initHigh.toInt().toString())

        var isUpdating = false

        // Slider → text fields
        binding.customRangeSlider.addOnChangeListener { _, _, _ ->
            if (isUpdating) return@addOnChangeListener
            isUpdating = true
            val vals = binding.customRangeSlider.values
            val low  = vals[0].toInt()
            val high = vals[1].toInt()
            binding.customLowCutInput.setText(low.toString())
            binding.customHighCutInput.setText(high.toString())
            viewModel.customLowCut  = vals[0]
            viewModel.customHighCut = vals[1]
            isUpdating = false
        }

        // Low cut text → slider + ViewModel
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

        // High cut text → slider + ViewModel
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

    private fun setupConnectionReceiver() {
        connectionReceiver = TaalConnectionBroadcastReceiver(object :
            TaalConnectionBroadcastReceiver.TaalConnectionListener {
            override fun onTaalConnect() {
                activity?.runOnUiThread {
                    // Device connected: change icon to Teal and keep it that way
                    binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))
                }
            }

            override fun onTaalDisconnect() {
                activity?.runOnUiThread {
                    // Device disconnected: change icon back to Gray/Black
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

            // If the device list is not empty, a USB device (your TAAL stethoscope) is connected
            if (usbManager.deviceList.isNotEmpty()) {
                binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2")) // Teal
            } else {
                binding.deviceIcon.setColorFilter(Color.parseColor("#333333")) // Black/Gray
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupButtons() {
        binding.infoButton.setOnClickListener {
            val filterName = viewModel.currentFilter.value ?: "HEART"
            FilterPlacementDialog.newInstance(filterName)
                .show(parentFragmentManager, "filter_placement")
        }

        binding.recordButton.setOnClickListener {
            when (viewModel.uiState.value) {
                RecordingUiState.IDLE -> {
                    checkPermissionAndRecord()
                }

                RecordingUiState.RECORDING -> {
                    stopRecording()
                }

                // RecordingUiState.STOPPED -> {
                //     showSaveDialog()
                // }

                else -> {
                    resetToIdle()
                }
            }
        }

        // binding.saveCheckButton.setOnClickListener { showSaveDialog() }
        // binding.trashButton.setOnClickListener {
        //     resetToIdle()
        //     Toast.makeText(requireContext(), "Recording discarded", Toast.LENGTH_SHORT).show()
        // }

        binding.playPauseButton.setOnClickListener {
            val filteredPath = viewModel.currentFilteredPath
            val rawPath = viewModel.currentRecordingPath
            val filterName = viewModel.currentFilter.value ?: "HEART"
            if (filteredPath.isNotEmpty()) {
                val bundle = Bundle().apply {
                    putString("filePath", filteredPath)
                    putString("rawFilePath", rawPath)
                    putString("filterName", filterName)
                }
                findNavController().navigate(R.id.action_recording_to_player, bundle)
            }
        }

        binding.folderButton.setOnClickListener {
            findNavController().navigate(R.id.action_recording_to_savedRecordings)
        }

        binding.settingsButton.setOnClickListener {
            findNavController().navigate(R.id.action_recording_to_newRecording)
        }
    }

    /** Reset everything back to initial idle state */
    private fun resetToIdle() {
        viewModel.setUiState(RecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        viewModel.currentFilteredPath = ""
        // try { aiTestingFos?.close() } catch (_: Exception) {}  // AI downsampling disabled
        // aiTestingFos = null
        // aiTestingBytesWritten = 0
        // heartResampler8k.reset()
        // viewModel.currentAiTestingPath = ""
        // activeDownsamplingStreams.forEach { it.safeClose() }
        // activeDownsamplingStreams = emptyList()
        // viewModel.extraAiFilePaths = emptyList()
        waveformEntries.clear()
        waveformDataSet = null
        chartInitialized = false
        peakAmplitude = 1.0f
        warmupPeak = 0f
        warmupDone = false
        lastPeakUpdateTime = 0L
        totalSamplesProcessed = 0L

        // Re-apply dummy data so the perfect grid stays visible on reset
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

    /**
     * Enable or disable all five filter buttons together.
     * Alpha is dimmed when disabled so the user gets a clear visual cue
     * that filters are locked during recording.
     */
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
        // Hide the custom range panel while recording; restore on idle if custom is still selected
        if (!enabled) {
            binding.customRangePanel.visibility = View.GONE
        } else if (viewModel.currentFilter.value == "CUSTOM") {
            binding.customRangePanel.visibility = View.VISIBLE
        }
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
                    // Unlock amp controls
                    binding.ampSlider.isEnabled = true
                    binding.ampSliderContainer.alpha = 1f
                    // Re-enable filter buttons now that recording has stopped
                    setFilterButtonsEnabled(true)
                }

                RecordingUiState.RECORDING -> {
                    binding.actionText.text = getString(R.string.stop_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    // Hide bottom bar so chart expands to fill the freed space
                    binding.bottomBar.visibility = View.GONE
                    binding.preRecordingButtons.visibility = View.GONE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordButton.setImageResource(R.drawable.ic_recording_stop)
                    // Lock amp controls
                    binding.ampSlider.isEnabled = false
                    binding.ampSliderContainer.alpha = 0.55f
                    // Disable filter buttons — filter cannot change mid-recording
                    setFilterButtonsEnabled(false)
                }

                // RecordingUiState.STOPPED -> {
                //     binding.actionText.text = "Save or Discard"
                //     binding.recordButton.visibility = View.GONE
                //     binding.preRecordingButtons.visibility = View.GONE
                //     binding.recordingButtons.visibility = View.VISIBLE
                //     // binding.recordingIndicator.visibility = View.GONE  (Removed)
                //     binding.waveformChart.apply {
                //         setTouchEnabled(true)
                //         isDragEnabled = true
                //         setScaleEnabled(true)
                //         setPinchZoom(true)
                //     }
                // }

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

        // Validate custom filter before touching the recorder
        if (filterName == "CUSTOM") {
            val low  = viewModel.customLowCut
            val high = viewModel.customHighCut
            val lowText  = binding.customLowCutInput.text?.toString()?.trim()
            val highText = binding.customHighCutInput.text?.toString()?.trim()

            val message = when {
                lowText.isNullOrEmpty() && highText.isNullOrEmpty() ->
                    "Low Cut and High Cut cannot be blank.\nPlease enter valid frequency values (e.g. Low Cut: 20 Hz, High Cut: 1000 Hz)."
                lowText.isNullOrEmpty() ->
                    "Low Cut cannot be blank.\nPlease enter a frequency greater than 0 Hz."
                highText.isNullOrEmpty() ->
                    "High Cut cannot be blank.\nPlease enter a frequency greater than 0 Hz."
                low == null || low <= 0f ->
                    "Low Cut cannot be 0 Hz.\nA value of 0 Hz disables the filter entirely. Please enter a frequency greater than 0 Hz (e.g. 20 Hz)."
                high == null || high <= 0f ->
                    "High Cut cannot be 0 Hz.\nA value of 0 Hz disables the filter entirely. Please enter a frequency greater than 0 Hz (e.g. 1000 Hz)."
                low >= high ->
                    "Low Cut (${low.toInt()} Hz) must be less than High Cut (${high.toInt()} Hz).\nPlease adjust the values so the passband is valid."
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
                setRecordingTime(300) // 5 minutes max
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
                                RecorderState.RECORDING -> {
                                    viewModel.setUiState(RecordingUiState.RECORDING)
                                }

                                RecorderState.STOPPED -> {
                                    viewModel.setUiState(RecordingUiState.STOPPED)
                                }

                                else -> {}
                            }
                        }
                    }

                    override fun onRawProgressUpdate(data: FloatArray) {
                        // AI downsampling streams disabled — no extra files written
                    }

                    override fun onDeviceDisconnected() {
                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                stopAudioMonitor()
                                taalRecorder = null

                                // Discard the partial recording — device disconnected mid-capture
                                if (viewModel.currentRecordingPath.isNotEmpty()) {
                                    try { File(viewModel.currentRecordingPath).delete() } catch (_: Exception) {}
                                }
                                if (viewModel.currentFilteredPath.isNotEmpty()) {
                                    try { File(viewModel.currentFilteredPath).delete() } catch (_: Exception) {}
                                }

                                Toast.makeText(
                                    requireContext(),
                                    "Device disconnected. Please connect the device.",
                                    Toast.LENGTH_LONG
                                ).show()
                                resetToIdle()
                            }
                        }
                    }

                    // onSilentRecordingDetected defaults to a no-op in TaalRecorder.OnInfoListener.
                    // The "Ready to Capture" dialog this used to show proved unreliable/
                    // false-positive in testing (a documented Samsung SM-A066B false-positive —
                    // see PcgScaleRecordingFragment.kt's matching removal) and was removed here
                    // for the same reason, now that this screen is the live startDestination.

                    override fun onProgressUpdate(
                        sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
                    ) {
                        // Live audio monitoring — write PCM directly on the IO thread
                        audioTrack?.let { track ->
                            val pcm = ShortArray(data.size) { i ->
                                (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                            track.write(pcm, 0, pcm.size)
                        }

                        // Feed BPM calculator (fast - just buffer copy)
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

            // ── AI downsampling streams (DISABLED) ──────────────────────────────
            // activeDownsamplingStreams.forEach { it.safeClose() }
            // activeDownsamplingStreams = emptyList()
            // try { aiTestingFos?.close() } catch (_: Exception) {}
            // aiTestingFos = null
            // aiTestingBytesWritten = 0
            // heartResampler8k.reset()
            // viewModel.currentAiTestingPath = ""
            // (filter-conditional HEART/LUNGS downsampling streams omitted)

            binding.waveformChart.data = LineData()
            binding.waveformChart.moveViewToX(0f)
            startAudioMonitor()
            taalRecorder?.start()

        } catch (e: Exception) {
            // Centered dialog for modern Android versions
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording Error").setMessage(e.message)
                .setPositiveButton("OKAY") { dialog, _ ->
                    dialog.dismiss()
                }.show()
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
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
    }

    private fun stopRecording() {
        stopAudioMonitor()
        try {
            taalRecorder?.stop()
        } catch (_: Exception) {
        }
        taalRecorder = null

        // finalizeAiWav()                                      // AI downsampling disabled
        // activeDownsamplingStreams.forEach { it.finalize() }  // AI downsampling disabled
        // activeDownsamplingStreams = emptyList()

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

    /**
     * V7 — Sample-accurate X positioning + stable Y-axis.
     *
     * X-axis: use a monotonically incrementing sample counter (totalSamplesProcessed)
     * divided by the sample rate to get jitter-free X positions. The chart has a fixed
     * 0–300s range pre-drawn; we pan it with moveViewToX() for smooth scrolling.
     *
     * Y-axis (two-phase):
     *   Phase 1 — WARMUP (first 1500ms): accumulate true peak; axis stays ±1.0.
     *   Phase 2 — LOCKED: axis only expands when signal exceeds current scale.
     *   The Y-axis is only written when peakAmplitude actually changes, eliminating
     *   the constant min/max updates that caused the grid to flicker.
     */
    private fun updateWaveform(timestamp: Double, data: FloatArray) {
        if (_binding == null || !isAdded) return
        val chart = binding.waveformChart

        var bufferPeak = 0f
        for (sample in data) {
            val abs = Math.abs(sample)
            if (abs > bufferPeak) bufferPeak = abs
        }

        // CONTINUOUS X LOGIC: X grows infinitely.
        val step = DOWNSAMPLE_STEP
        for (i in 0 until data.size step step) {
            val currentX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
            waveformEntries.add(Entry(currentX, data[i]))
            totalSamplesProcessed += step
        }

        val latestX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE

        // PAGE CALCULATION: Determine which 10-second block we are currently in
        val currentPage = (latestX / WINDOW_SECONDS).toInt()
        val currentViewX = currentPage * WINDOW_SECONDS

        // MEMORY CLEANUP: Keep data for the current page and the immediate previous page.
        val minXToKeep = (currentPage - 1) * WINDOW_SECONDS
        if (minXToKeep > 0) {
            val iterator = waveformEntries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().x < minXToKeep) {
                    iterator.remove()
                } else {
                    break // Stop iterating once we reach visible data
                }
            }
        }

        val now = System.currentTimeMillis()
        if (!warmupDone) {
            if (bufferPeak > warmupPeak) warmupPeak = bufferPeak
            if (lastPeakUpdateTime == 0L) lastPeakUpdateTime = now

            // Once the warmup duration has passed, lock the Y-axis scale
            if (now - lastPeakUpdateTime >= WARMUP_MS) {
                warmupDone = true
                peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
                chart.axisLeft.axisMinimum = -peakAmplitude
                chart.axisLeft.axisMaximum = peakAmplitude
            }
        }
// The 'else' block that previously expanded the axis has been removed.

        val snapshot = ArrayList(waveformEntries)
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

        // Notify the chart that the underlying data arrays have changed
        chart.notifyDataSetChanged()

        // Re-enforce the strict 10-second view width
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)

        // PUSH CAMERA: Snap the view to the start of the current 10-second page
        chart.moveViewToX(currentViewX)

        // CRITICAL FIX: Force the chart to physically redraw the new pixels on the screen!
        chart.invalidate()
    }
    // private fun showSaveDialog() {
    //     val dialog = SaveAlertDialog { saveType ->
    //         when (saveType) {
    //             SaveAlertDialog.SaveType.SAVE      -> navigateToAddPatient()
    //             SaveAlertDialog.SaveType.EMERGENCY -> showEmergencySaveDialog()
    //             SaveAlertDialog.SaveType.DISCARD   -> resetToIdle()
    //         }
    //     }
    //     dialog.show(parentFragmentManager, "save_alert")
    // }

    // private fun showEmergencySaveDialog() {
    //     val dialog = EmergencySaveDialog { fileName ->
    //         if (fileName != null) {
    //             viewModel.currentRecordingPath.let { path ->
    //                 if (path.isNotEmpty()) {
    //                     val file = java.io.File(path)
    //                     val newFile = java.io.File(file.parentFile, "EMERGENCY_${fileName}_${System.currentTimeMillis()}.wav")
    //                     file.renameTo(newFile)
    //                     viewModel.currentRecordingPath = newFile.absolutePath
    //                 }
    //             }
    //             Toast.makeText(requireContext(), getString(R.string.recording_saved), Toast.LENGTH_SHORT).show()
    //             resetToIdle()
    //         }
    //     }
    //     dialog.show(parentFragmentManager, "emergency_save")
    // }

    // navigateToAddPatient() removed — AddPatientFragment and action_recording_to_addPatient
    // no longer exist (whole flow deleted, 2026-09-09).

    // ── DownsamplingStream inner class (DISABLED — AI downsampling disabled) ──
    //
    // private inner class DownsamplingStream(
    //     private val resampler: AudioDownsampler,
    //     val filePath: String
    // ) {
    //     @Volatile var fos: FileOutputStream? = null
    //     @Volatile var bytesWritten: Int = 0
    //     fun open() { ... }
    //     fun process(data: FloatArray) { ... }
    //     fun finalize() { ... }
    //     fun safeClose() { ... }
    // }

    // ── AI testing WAV helpers (DISABLED — AI downsampling disabled) ─────────
    //
    // private fun writeAiWavHeader(fos: FileOutputStream, sampleRate: Int) { ... }
    // private fun finalizeAiWav() { ... }
    // private fun floatsTo16BitPcmBytes(floats: FloatArray): ByteArray { ... }

    override fun onResume() {
        super.onResume()

        checkDeviceConnectionStatus()
        if (taalRecorder == null) {
            resetToIdle()
        }
        // Reset pre-amp slider to default 5dB on every resume
        viewModel.setPreAmp(5)
        binding.ampSlider.value = 5f
        binding.ampLabel.text = "5 dB"
        // Recorder is null but state is RECORDING → we stopped and navigated to PlayerFragment,
        // then the user pressed Discard and popped back here. Reset to idle.
//        if (taalRecorder == null && viewModel.uiState.value == RecordingUiState.RECORDING) {
//            resetToIdle()
//        }
        // Commented out: old Save/Discard flow via AddPatientFragment
        // if (viewModel.uiState.value == RecordingUiState.STOPPED) {
        //     resetToIdle()
        // }

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
        try {
            taalRecorder?.stop()
        } catch (_: Exception) {
        }
        // try { aiTestingFos?.close() } catch (_: Exception) {}  // AI downsampling disabled
        // activeDownsamplingStreams.forEach { it.safeClose() }   // AI downsampling disabled
        // activeDownsamplingStreams = emptyList()                 // AI downsampling disabled
    }
}
