package com.musediagnostics.taal.app.ui.pcgscale

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
import com.musediagnostics.taal.app.databinding.FragmentPcgscaleRecordingBinding
import com.musediagnostics.taal.app.dsp.HeartBpmCalculator
import com.musediagnostics.taal.app.ecg.pcgscale.PcgAmplitudeScale
import com.musediagnostics.taal.app.ecg.pcgscale.PcgScaleWaveformView
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Fork of [com.musediagnostics.taal.app.ui.calibrated.CalibratedRecordingFragment] (not
 * modified) for the PcgScale screens. Same UI, filters, pre-amp, dual-file output, controls
 * and lifecycle shape; the differences, all deliberate and all confined to how the graph is
 * drawn:
 *
 *  - TIME grid, not mm grid: the trace sits on [PcgScaleWaveformView]'s 1-large-box-per-second
 *    grid ([com.musediagnostics.taal.app.ecg.pcgscale.PcgTimeScale]) with second labels along
 *    the bottom. No DpiCalibration, no paper speed, no GraphCalibration override — none of
 *    those concepts exist on this screen family.
 *  - Y-axis is RMS-auto-scaled to ~60% fill via [PcgAmplitudeScale], replacing both the fixed
 *    FIXED_FULL_SCALE scheme (Calibrated) and the old warmup/peak lock (production). The axis
 *    updates as each 5-second RMS window closes, eased so it glides rather than pops — see
 *    PcgAmplitudeScale's class doc for exactly how "60% excluding noise" is computed and why
 *    a single loud transient can no longer peg the scale.
 *
 * The amplitude scale is pure state fed from the audio callback's existing UI hop — it holds
 * no thread, handler, or session, so there is nothing extra to release in onPause/onDestroy;
 * the recorder teardown contract is byte-for-byte the Calibrated one.
 */
class PcgScaleRecordingFragment : Fragment() {

    private var _binding: FragmentPcgscaleRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PcgScaleRecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    private var audioTrack: AudioTrack? = null
    private val waveformEntries = ArrayList<Entry>()

    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    private var waveformDataSet: LineDataSet? = null
    private var totalSamplesProcessed = 0L  // Sample-accurate X position counter
    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Derived from the PcgTimeScale on layout — never a free constant (§4.1 heritage).
    private var currentWindowSeconds = 4f

    // Fix C heritage — ~one min/max bucket per horizontal pixel for the session's window.
    // Frozen for the whole recording session (the X axis is a monotonic sample counter, so a
    // mid-session bucket change would space old and new points inconsistently). With zoom
    // impossible on these screens the window can't change under it either.
    private var sessionBucketSize = DOWNSAMPLE_BUCKET_FALLBACK

    // The 60%-fill RMS scaler. Reset at every session start; fed in updateWaveform. Rebuilt
    // (var, not val) if the device reports a different real sample rate — see
    // onSampleRateReported.
    private var amplitudeScale = PcgAmplitudeScale(ASSUMED_SAMPLE_RATE)
    private var appliedFullScale = PcgAmplitudeScale.DEFAULT_INITIAL_FULL_SCALE

    // The rate everything time-derived actually uses. Starts at the 44.1k assumption and is
    // corrected from onProgressUpdate's reported sampleRate on the FIRST buffer of a session.
    // Samsung USB-audio stacks commonly run 48000, not 44100 — with a hardcoded 44100 (what
    // Calibrated/production do) every live X position lands ~8.8% late and a "1 second" box
    // spans ~1.09s of real signal, which reads as "the graph scaling doesn't work" on exactly
    // those devices while OnePlus (44.1k) looks perfect. The player was never affected — it
    // parses the real rate from the WAV header.
    private var actualSampleRate = ASSUMED_SAMPLE_RATE

    companion object {
        // Pre-callback assumption only — corrected by onSampleRateReported. Never use this
        // directly for X mapping or RMS windows; use actualSampleRate.
        private const val ASSUMED_SAMPLE_RATE = 44100f

        // Pre-layout fallback only (plot width not yet known) — same value/reasoning as the
        // Calibrated fork's DOWNSAMPLE_BUCKET_FALLBACK.
        private const val DOWNSAMPLE_BUCKET_FALLBACK = 88

        // Only push a new axis bound to the chart when it moved by more than this fraction —
        // the eased value converges asymptotically and sub-0.5% steps are invisible anyway.
        private const val AXIS_UPDATE_EPSILON_FRACTION = 0.005f

        // Same display-only pre-amp compensation as the Calibrated fork — the trace reflects
        // true acoustic level; the slider only changes loudness. See that fork's constant doc.
        private const val COMPENSATE_PREAMP_IN_DISPLAY = true

        // Halved from the Calibrated fork's 2.0 (explicit user request — ledger Fix 4, re-applied).
        private const val TRACE_LINE_WIDTH_DP = 1.0f
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
        _binding = FragmentPcgscaleRecordingBinding.inflate(inflater, container, false)
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
        updateScaleCaption()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.uiState.value == PcgScaleRecordingUiState.RECORDING) {
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
        val waveformView = binding.pcgScaleWaveformView
        val chart = waveformView.chart
        chart.setTouchEnabled(false) // no pan/zoom while recording — same as production

        // Session-start axis: the amplitude scale's neutral initial value; updateWaveform
        // glides it onto the measured 60%-fill scale as RMS windows close.
        appliedFullScale = PcgAmplitudeScale.DEFAULT_INITIAL_FULL_SCALE
        chart.axisLeft.axisMinimum = -appliedFullScale
        chart.axisLeft.axisMaximum = appliedFullScale

        waveformView.onVisibleSecondsChanged = { seconds ->
            currentWindowSeconds = seconds
            // Don't clobber an in-progress recording's dataset on rotation/resize — only
            // reset to the dummy grid-holder dataset when there's no real data yet.
            if (waveformDataSet == null) {
                // Derive the bucket for the (fixed) effective window — same "effective window,
                // not native density" rule the Calibrated screens learned twice (Fix D class).
                // Re-derived once more if the device reports a different real sample rate
                // (onSampleRateReported), which happens before any real entries exist.
                val plotWidthPx = waveformView.chart.width.toFloat()
                val visibleSampleCount = currentWindowSeconds * actualSampleRate
                val derived = PcgScaleWaveformView.deriveBucketSizeForVisibleRange(visibleSampleCount, plotWidthPx)
                if (derived > 0) sessionBucketSize = derived
                resetChartToDummyData()
            } else {
                waveformView.applyRangeLock()
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
        binding.pcgScaleWaveformView.chart.data = LineData(dummyDataSet)
        binding.pcgScaleWaveformView.syncGridToChart()
        binding.pcgScaleWaveformView.chart.invalidate()
    }

    /**
     * Idle: the static scale legend. Recording: live diagnostics instead — the reported
     * sample rate, the measured mean peak RMS, and the applied axis scale, with an explicit
     * "min-clamped" marker when the clamp floor (not the measurement) is setting the axis.
     * Added after a study Samsung unit showed "scaling doesn't work" with no way to tell
     * from the screen whether time (wrong sample rate) or height (quiet input hitting
     * MIN_FULL_SCALE) was at fault — this caption answers that at a glance on-device.
     */
    private fun updateScaleCaption() {
        if (_binding == null) return
        binding.scaleCaption.text =
            if (viewModel.uiState.value == PcgScaleRecordingUiState.RECORDING) {
                val clampNote = if (amplitudeScale.isClampedAtMin()) " (MIN-CLAMPED: input very quiet)" else ""
                String.format(
                    "sr=%d Hz · peak=%.4f · Y=±%.3f%s",
                    actualSampleRate.toInt(), amplitudeScale.typicalPeakAmplitude(), appliedFullScale, clampNote
                )
            } else {
                "1 large box = 1 s · 1 small box = 0.2 s · Y: auto (60% fill, RMS)"
            }
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
                PcgScaleRecordingUiState.IDLE -> checkPermissionAndRecord()
                PcgScaleRecordingUiState.RECORDING -> stopRecording()
                else -> resetToIdle()
            }
        }

        // Dead in production too (STOPPED-state UI is never entered — stopRecording()
        // navigates directly to the player) — kept only so the layout/ID surface stays a
        // faithful replica of the Calibrated fork it came from.
        binding.playPauseButton.setOnClickListener {
            val filteredPath = viewModel.currentFilteredPath
            val rawPath = viewModel.currentRecordingPath
            val filterName = viewModel.currentFilter.value ?: "HEART"
            if (filteredPath.isNotEmpty()) {
                val bundle = Bundle().apply {
                    putString("filePath", filteredPath)
                    putString("rawFilePath", rawPath)
                    putString("filterName", filterName)
                    putInt("preAmpDb", viewModel.preAmpDb.value ?: 5)
                }
                findNavController().navigate(R.id.action_pcgScaleRecording_to_pcgScalePlayer, bundle)
            }
        }

        binding.folderButton.setOnClickListener {
            findNavController().navigate(R.id.action_pcgScaleRecording_to_savedRecordings)
        }
    }

    private fun resetToIdle() {
        viewModel.setUiState(PcgScaleRecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        viewModel.currentFilteredPath = ""
        waveformEntries.clear()
        waveformDataSet = null
        totalSamplesProcessed = 0L
        amplitudeScale.reset()
        appliedFullScale = PcgAmplitudeScale.DEFAULT_INITIAL_FULL_SCALE
        binding.pcgScaleWaveformView.chart.axisLeft.axisMinimum = -appliedFullScale
        binding.pcgScaleWaveformView.chart.axisLeft.axisMaximum = appliedFullScale

        resetChartToDummyData()
        binding.pcgScaleWaveformView.chart.moveViewToX(0f)
        binding.pcgScaleWaveformView.syncGridToChart()

        binding.bpmText.text = "-- BPM"
        updateScaleCaption()
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
        // Feature A: hum filter state is fixed per session, same as the preset filter buttons.
        binding.humFilterSwitch.isEnabled = enabled
        binding.humFilterSwitch.alpha = alpha
    }

    private fun observeState() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                PcgScaleRecordingUiState.IDLE -> {
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

                PcgScaleRecordingUiState.RECORDING -> {
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
            // ~1 Hz while recording — cheap place to refresh the live diagnostics caption.
            if (viewModel.uiState.value == PcgScaleRecordingUiState.RECORDING) updateScaleCaption()
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
            val rawFilePath = "${requireContext().filesDir}/pcg_recording_${ts}_raw.wav"
            val filteredFilePath = "${requireContext().filesDir}/pcg_recording_${ts}_filtered.wav"
            viewModel.currentRecordingPath = rawFilePath
            viewModel.currentFilteredPath = filteredFilePath

            taalRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(rawFilePath)
                setFilteredAudioFilePath(filteredFilePath)
                setRecordingTime(300)
                setPlayback(false)
                setPreAmplification(viewModel.preAmpDb.value ?: 5)
                // Feature A: opt-in hum/rumble filter, default off. Not yet persisted across
                // sessions — read fresh from the switch every recording.
                setHumRumbleFilterEnabled(binding.humFilterSwitch.isChecked)
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
                                RecorderState.RECORDING -> viewModel.setUiState(PcgScaleRecordingUiState.RECORDING)
                                RecorderState.STOPPED -> viewModel.setUiState(PcgScaleRecordingUiState.STOPPED)
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

                    // DISABLED 2026-09-02 — this silence detector false-positives on this
                    // hardware, so the dialog fired on good recordings and told the user to
                    // throw them away.
                    //
                    // TaalRecorder flags a recording as silent when the FILTERED peak never
                    // exceeds SILENT_RECORDING_PEAK_THRESHOLD (0.01). Measured on the Samsung
                    // SM-A066B study phone: a clean chest recording with clearly audible heart
                    // sounds gave raw sessionPeak=0.00418 -> filtered maxFilteredPeak=0.00365,
                    // i.e. ~2.7x BELOW the threshold, so silentVerdict=true on a perfectly
                    // valid recording. The fixed threshold was evidently calibrated on a
                    // louder handset — this phone's USB capture level is roughly 5x lower.
                    // See docs/notes/SAMSUNG_AUDIO_LEVEL_ATTENUATION_DIAGNOSTIC.md and
                    // docs/notes/AUDIO_DIAGNOSTIC_REMEDIATION_TRACKER.md.
                    //
                    // Re-enable only once the threshold is derived from a measured noise floor
                    // (or normalised for the device's capture level) instead of a constant.
                    // The interface default is a no-op, so leaving this commented out simply
                    // means no silence dialog on this screen.
                    //
                    // override fun onSilentRecordingDetected(isFirstSinceConnect: Boolean) {
                    //     if (!isFirstSinceConnect) return
                    //     activity?.runOnUiThread {
                    //         val act = activity ?: return@runOnUiThread
                    //         if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                    //         android.app.AlertDialog.Builder(act)
                    //             .setTitle("Ready to Capture")
                    //             .setMessage("Your TAAL device has been detected and is now ready. Please discard this recording and start a new one.")
                    //             .setPositiveButton("OK", null)
                    //             .setCancelable(false)
                    //             .show()
                    //     }
                    // }

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

                        // Undo pre-amp gain before drawing AND before RMS measurement — the
                        // 60%-fill scale must be computed from the same true-acoustic-level
                        // data the trace draws, or the fill fraction would depend on the
                        // slider position. Same tradeoff note as the Calibrated fork's
                        // COMPENSATE_PREAMP_IN_DISPLAY doc.
                        val preAmpDb = viewModel.preAmpDb.value ?: 5
                        val preAmpGain = Math.pow(10.0, preAmpDb / 20.0).toFloat()
                        val displayData = if (COMPENSATE_PREAMP_IN_DISPLAY && preAmpGain > 1.001f) {
                            FloatArray(data.size) { i -> data[i] / preAmpGain }
                        } else {
                            data
                        }

                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                onSampleRateReported(sampleRate)
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
            amplitudeScale.reset()
            appliedFullScale = PcgAmplitudeScale.DEFAULT_INITIAL_FULL_SCALE
            binding.pcgScaleWaveformView.chart.axisLeft.axisMinimum = -appliedFullScale
            binding.pcgScaleWaveformView.chart.axisLeft.axisMaximum = appliedFullScale

            resetChartToDummyData()
            binding.pcgScaleWaveformView.chart.moveViewToX(0f)
            binding.pcgScaleWaveformView.syncGridToChart()
            startAudioMonitor()
            taalRecorder?.start()

        } catch (e: Exception) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording Error").setMessage(e.message)
                .setPositiveButton("OKAY") { dialog, _ -> dialog.dismiss() }.show()
            viewModel.setUiState(PcgScaleRecordingUiState.IDLE)
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
                // So the Player can undo this recording's actual pre-amp gain and both draw
                // and RMS-measure the same true-acoustic-level trace the recorder showed live.
                putInt("preAmpDb", viewModel.preAmpDb.value ?: 5)
            }
            findNavController().navigate(R.id.action_pcgScaleRecording_to_pcgScalePlayer, bundle)
        }
    }

    /**
     * Adopt the sample rate the recorder actually reports (first buffer of a session) in
     * place of the 44.1k assumption. Everything derived from the rate is rebuilt: the RMS
     * scaler's hop/window sizes and the min/max bucket density. Runs on the UI thread before
     * updateWaveform ever plots a real entry, so no already-plotted X positions need fixing;
     * a rate change mid-session (never observed — USB audio rate is fixed per stream) would
     * only mis-place the handful of entries already drawn, not corrupt state.
     */
    private fun onSampleRateReported(reportedRate: Int) {
        if (reportedRate <= 0) return
        val rate = reportedRate.toFloat()
        if (rate == actualSampleRate) return
        actualSampleRate = rate
        amplitudeScale = PcgAmplitudeScale(rate)
        val plotWidthPx = binding.pcgScaleWaveformView.chart.width.toFloat()
        val derived = PcgScaleWaveformView.deriveBucketSizeForVisibleRange(
            currentWindowSeconds * rate, plotWidthPx
        )
        if (derived > 0) sessionBucketSize = derived
    }

    /**
     * PcgScale counterpart of the Calibrated fork's updateWaveform(). Differences:
     *  - X window comes from the PcgTimeScale (fixed 1s/0.2s squares), and the grid is
     *    re-synced to the chart's left edge after every page snap so the second labels stay
     *    pinned to real recording time.
     *  - Y-axis is fed live: this buffer goes into [amplitudeScale], and the eased 60%-fill
     *    full-scale is applied whenever it has moved by more than a fraction of a percent.
     */
    private fun updateWaveform(data: FloatArray) {
        if (_binding == null || !isAdded) return
        val waveformView = binding.pcgScaleWaveformView
        val chart = waveformView.chart

        // Feed the RMS scaler and glide the axis toward the measured 60%-fill scale.
        amplitudeScale.addSamples(data)
        val fullScale = amplitudeScale.smoothedFullScale()
        if (abs(fullScale - appliedFullScale) > appliedFullScale * AXIS_UPDATE_EPSILON_FRACTION) {
            appliedFullScale = fullScale
            chart.axisLeft.axisMinimum = -fullScale
            chart.axisLeft.axisMaximum = fullScale
        }

        val bufferStartSample = totalSamplesProcessed
        val newEntries = PcgScaleWaveformView.downsampleMinMax(data, sessionBucketSize) { j ->
            (bufferStartSample + j).toFloat() / actualSampleRate
        }
        waveformEntries.addAll(newEntries)
        totalSamplesProcessed += data.size

        val latestX = totalSamplesProcessed.toFloat() / actualSampleRate
        val windowSeconds = currentWindowSeconds

        val currentPage = (latestX / windowSeconds).toInt()
        val currentViewX = currentPage * windowSeconds

        val minXToKeep = PcgScaleWaveformView.ringTrimMinX(latestX, windowSeconds)
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

        waveformView.applyRangeLock()
        chart.moveViewToX(currentViewX)
        // Programmatic camera move — the gesture listener can't see it, sync by hand so the
        // grid's second labels track the page snap.
        waveformView.syncGridToChart()
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

        binding.pcgScaleWaveformView.recomputeVisibleSeconds()
        binding.pcgScaleWaveformView.chart.invalidate()

        updateScaleCaption()
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
