package com.musediagnostics.taal.app.ui.calibrated

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
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalRecorder
import com.musediagnostics.taal.core.RecorderState
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentCalibratedRecordingBinding
import com.musediagnostics.taal.app.dsp.HeartBpmCalculator
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedPaperSpeed
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Calibrated replica of [com.musediagnostics.taal.app.ui.recording.RecordingFragment]
 * (protected, not modified — ported by hand, see WAVEFORM_GRAPH_AND_GRID_REFERENCE.md §2 for
 * the exact behavior this mirrors). Same UI, filters, pre-amp, dual-file output and controls;
 * the only intended difference is that the waveform is drawn on a physically calibrated
 * mm-accurate ECG-paper grid (via [CalibratedWaveformView]) instead of MPAndroidChart's own
 * gridlines on a fixed 10s window.
 *
 * Fix A (reversing an earlier chat decision to restore production's adaptive warmup/peak
 * Y-axis): the axis is a single fixed full-scale, set once at chart setup and never touched
 * again for the rest of the session. Frame-measurement of a real recording showed the adaptive
 * scheme was the root cause of "graph is unstable / noisy / too small" — a loud transient in
 * the 2s warmup window (e.g. the stethoscope contact thud) could permanently lock an oversized
 * scale, and the axis could jump mid-recording. A fixed axis trades per-recording optimality
 * for stability: this is deliberately how Kardia's own ECG display behaves (fixed 10mm/mV,
 * never rescales) — see FIXED_FULL_SCALE's doc for the tuning methodology.
 */
class CalibratedRecordingFragment : Fragment() {

    private var _binding: FragmentCalibratedRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CalibratedRecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    private var audioTrack: AudioTrack? = null
    private val waveformEntries = ArrayList<Entry>()

    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    private var waveformDataSet: LineDataSet? = null
    private var totalSamplesProcessed = 0L  // Sample-accurate X position counter
    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // §4.1 — the visible window is derived from the grid's physical width, not a fixed
    // constant; this holds the most recently derived value (updated on layout/rotation).
    private var currentWindowSeconds = 4f

    // Fix C — derived so ~one min/max bucket lands per horizontal pixel at the current paper
    // speed, instead of a fixed constant. Recomputed only while idle (see onVisibleSecondsChanged
    // below) and held frozen for the whole recording session: the recorder mutates
    // LineDataSet.values in place against a monotonic sample-counter X axis, so changing the
    // bucket size mid-recording would space already-plotted points inconsistently with new ones.
    private var sessionBucketSize = DOWNSAMPLE_BUCKET_FALLBACK

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f

        // Pre-layout fallback only (pxPerMmX not yet known) — see deriveBucketSize/sessionBucketSize.
        // 2 points per bucket (min+max) at this bucket size ≈ same point budget as production's
        // "1 in 44 samples" decimation (44100 / 88 * 2 ≈ 1002 pts/sec), but peaks are preserved.
        private const val DOWNSAMPLE_BUCKET_FALLBACK = 88

        // Fix A — fixed display full-scale, in normalized sample units (-1..+1). The axis is
        // ±FIXED_FULL_SCALE and NEVER changes during a session (no warmup, no re-expansion).
        // Do NOT use ±1.0 — typical PCG content peaks well below full digital scale, so ±1.0
        // would make the trace smaller than before, not bigger.
        //
        // 0.30 is the task's given starting value, derived from the measured fact that the
        // player's existing filePeak×1.2 lock already lands at the visually-correct ~37% fill
        // (confirmed against Kardia's 38.6%). This constant has NOT been re-derived from a
        // logged median of real per-file peaks on this pass — that requires running the
        // temporary-logging step in CalibratedPlayerFragment against a set of real recordings
        // on device, which needs hardware this session doesn't have direct access to. Flagged
        // in this change's report; whoever has the device should confirm/tune this value next
        // using the same three-step method (log real peaks, take the median, hardcode it here).
        //
        // Clipping when a recording is unusually loud is expected and correct with a fixed
        // axis — Kardia does the same. Do not add a clipping indicator or re-expand the axis
        // in response.
        private const val FIXED_FULL_SCALE = 0.30f

        // Trace stroke width in dp. Production's thin/crisp value — "bigger" is achieved via
        // FIXED_FULL_SCALE (taller peaks), not a fatter stroke.
        private const val TRACE_LINE_WIDTH_DP = 1.5f
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
        _binding = FragmentCalibratedRecordingBinding.inflate(inflater, container, false)
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
        updateCalibrationCaption()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.uiState.value == CalibratedRecordingUiState.RECORDING) {
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
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart
        chart.setTouchEnabled(false) // no pan/zoom while recording — same as production

        // Fix A — fixed axis, set once, never touched again for the rest of the session.
        chart.axisLeft.axisMinimum = -FIXED_FULL_SCALE
        chart.axisLeft.axisMaximum = FIXED_FULL_SCALE

        // 25mm/s standard ECG paper speed (Fix B). Was SPEED_12_5 — at 12.5mm/s an S1/S2 pair
        // (~100ms apart) sits ~1.25mm apart and smears into an unreadable band; 25mm/s gives
        // ~2.5mm separation, closer to the reference figure this was compared against. Halving
        // the speed back would also halve the visible window, so both screens must be changed
        // together or a recording would look different live vs. in review. Grid square physical
        // size is unaffected — only what a square means in time changes.
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_25)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        waveformView.onVisibleSecondsChanged = { seconds ->
            currentWindowSeconds = seconds
            // Don't clobber an in-progress recording's dataset on rotation/resize — only
            // reset to the dummy grid-holder dataset when there's no real data yet.
            // updateWaveform() re-enforces the range every callback while recording is live.
            if (waveformDataSet == null) {
                // Fix C — only re-derive the bucket size while idle; a session in progress
                // must keep using whatever was frozen when it started (see sessionBucketSize doc).
                val derived = CalibratedWaveformView.deriveBucketSize(
                    INPUT_SAMPLE_RATE,
                    waveformView.paperView.currentScale().paperSpeed.mmPerSecond,
                    waveformView.paperView.currentScale().pxPerMmX
                )
                if (derived > 0) sessionBucketSize = derived
                resetChartToDummyData()
            } else {
                chart.setVisibleXRangeMaximum(seconds)
                chart.setVisibleXRangeMinimum(seconds)
                chart.invalidate()
            }
        }
        // If layout already happened (e.g. returning to this fragment), derive immediately.
        waveformView.recomputeVisibleSeconds()
        if (chart.data == null) resetChartToDummyData()
    }

    private fun resetChartToDummyData() {
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
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude (auto-scaled) · DPI: $status"
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
        val initLow = viewModel.customLowCut ?: 20f
        val initHigh = viewModel.customHighCut ?: 10000f

        viewModel.customLowCut = initLow
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
            val low = vals[0].toInt()
            val high = vals[1].toInt()
            binding.customLowCutInput.setText(low.toString())
            binding.customHighCutInput.setText(high.toString())
            viewModel.customLowCut = vals[0]
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
        binding.infoButton.setOnClickListener {
            val filterName = viewModel.currentFilter.value ?: "HEART"
            com.musediagnostics.taal.app.ui.recording.FilterPlacementDialog.newInstance(filterName)
                .show(parentFragmentManager, "filter_placement")
        }

        binding.recordButton.setOnClickListener {
            when (viewModel.uiState.value) {
                CalibratedRecordingUiState.IDLE -> checkPermissionAndRecord()
                CalibratedRecordingUiState.RECORDING -> stopRecording()
                else -> resetToIdle()
            }
        }

        // Dead in production too (STOPPED-state UI is never entered — stopRecording()
        // navigates directly to the player) — kept only so the layout/ID surface stays a
        // faithful replica, matching RecordingFragment.kt's own vestigial binding.
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
                findNavController().navigate(R.id.action_calibratedRecording_to_calibratedPlayer, bundle)
            }
        }

        binding.folderButton.setOnClickListener {
            findNavController().navigate(R.id.action_calibratedRecording_to_savedRecordings)
        }

        binding.settingsButton.setOnClickListener {
            findNavController().navigate(R.id.action_calibratedRecording_to_dpiCalibration)
        }
    }

    private fun resetToIdle() {
        viewModel.setUiState(CalibratedRecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        viewModel.currentFilteredPath = ""
        waveformEntries.clear()
        waveformDataSet = null
        totalSamplesProcessed = 0L

        resetChartToDummyData()
        binding.calibratedWaveformView.chart.moveViewToX(0f)

        binding.bpmText.text = "-- BPM"
        updateCalibrationCaption()
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

    private fun observeState() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                CalibratedRecordingUiState.IDLE -> {
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

                CalibratedRecordingUiState.RECORDING -> {
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
            val low = viewModel.customLowCut
            val high = viewModel.customHighCut
            val lowText = binding.customLowCutInput.text?.toString()?.trim()
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
            val rawFilePath = "${requireContext().filesDir}/cal_recording_${ts}_raw.wav"
            val filteredFilePath = "${requireContext().filesDir}/cal_recording_${ts}_filtered.wav"
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
                                RecorderState.RECORDING -> viewModel.setUiState(CalibratedRecordingUiState.RECORDING)
                                RecorderState.STOPPED -> viewModel.setUiState(CalibratedRecordingUiState.STOPPED)
                                else -> {}
                            }
                        }
                    }

                    override fun onRawProgressUpdate(data: FloatArray) {}

                    override fun onDeviceDisconnected() {
                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                stopAudioMonitor()
                                taalRecorder = null

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

                    override fun onSilentRecordingDetected(isFirstSinceConnect: Boolean) {
                        if (!isFirstSinceConnect) return
                        activity?.runOnUiThread {
                            val act = activity ?: return@runOnUiThread
                            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                            android.app.AlertDialog.Builder(act)
                                .setTitle("Ready to Capture")
                                .setMessage("Your TAAL device has been detected and is now ready. Please discard this recording and start a new one.")
                                .setPositiveButton("OK", null)
                                .setCancelable(false)
                                .show()
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

                        // Undo pre-amp gain before drawing, same as production (§4.4) — the
                        // trace reflects true acoustic level, not the amplified WAV level.
                        val preAmpDb = viewModel.preAmpDb.value ?: 5
                        val preAmpGain = Math.pow(10.0, preAmpDb / 20.0).toFloat()
                        val displayData = if (preAmpGain > 1.001f) {
                            FloatArray(data.size) { i -> data[i] / preAmpGain }
                        } else {
                            data
                        }

                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                updateWaveform(displayData)
                                val elapsed = timeStamp.toInt()
                                viewModel.updateTimer(elapsed)
                            }
                        }
                    }
                }
            }

            waveformEntries.clear()
            waveformDataSet = null
            totalSamplesProcessed = 0L
            bpmCalculator.reset()

            resetChartToDummyData()
            binding.calibratedWaveformView.chart.moveViewToX(0f)
            startAudioMonitor()
            taalRecorder?.start()

        } catch (e: Exception) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording Error").setMessage(e.message)
                .setPositiveButton("OKAY") { dialog, _ -> dialog.dismiss() }.show()
            viewModel.setUiState(CalibratedRecordingUiState.IDLE)
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
            findNavController().navigate(R.id.action_calibratedRecording_to_calibratedPlayer, bundle)
        }
    }

    /**
     * Calibrated counterpart of RecordingFragment's V7 updateWaveform(). Differences from
     * production:
     *  - X window (`currentWindowSeconds`) is derived from the grid, not a WINDOW_SECONDS
     *    constant.
     *  - Downsampling is min/max bucketed via CalibratedWaveformView, not "every Nth sample",
     *    so a transient can't fall entirely between two kept samples.
     *  - Y-axis is fixed (Fix A) — no warmup, no peak lock, no per-buffer peak tracking. The
     *    axis was set once in setupWaveformChart() and is never touched here.
     */
    private fun updateWaveform(data: FloatArray) {
        if (_binding == null || !isAdded) return
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart

        val bufferStartSample = totalSamplesProcessed
        val newEntries = CalibratedWaveformView.downsampleMinMax(data, sessionBucketSize) { j ->
            (bufferStartSample + j).toFloat() / INPUT_SAMPLE_RATE
        }
        waveformEntries.addAll(newEntries)
        totalSamplesProcessed += data.size

        val latestX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
        val windowSeconds = currentWindowSeconds

        val currentPage = (latestX / windowSeconds).toInt()
        val currentViewX = currentPage * windowSeconds

        val minXToKeep = (currentPage - 1) * windowSeconds
        if (minXToKeep > 0) {
            val iterator = waveformEntries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().x < minXToKeep) iterator.remove() else break
            }
        }

        val snapshot = ArrayList(waveformEntries)
        val ds = waveformDataSet

        if (ds == null || chart.data == null) {
            waveformDataSet = LineDataSet(snapshot, "Waveform").apply {
                color = ContextCompat.getColor(requireContext(), R.color.waveform_blue)
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = TRACE_LINE_WIDTH_DP
                mode = LineDataSet.Mode.LINEAR
                setDrawHighlightIndicators(false)
            }
            chart.data = LineData(waveformDataSet)
        } else {
            ds.values = snapshot
            chart.data?.notifyDataChanged()
        }

        chart.notifyDataSetChanged()

        chart.setVisibleXRangeMaximum(windowSeconds)
        chart.setVisibleXRangeMinimum(windowSeconds)
        chart.moveViewToX(currentViewX)
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
        updateCalibrationCaption()
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
