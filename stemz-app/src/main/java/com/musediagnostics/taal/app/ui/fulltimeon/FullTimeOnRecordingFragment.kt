package com.musediagnostics.taal.app.ui.fulltimeon

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
import com.musediagnostics.taal.app.databinding.FragmentFulltimeonRecordingBinding
import com.musediagnostics.taal.app.dsp.HeartBpmCalculator
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedPaperSpeed
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration
import com.musediagnostics.taal.app.ecg.calibrated.GraphCalibration
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Clone of [com.musediagnostics.taal.app.ui.calibrated.CalibratedRecordingFragment] under a new
 * screen name (user request, 2026-08-20) — behavior is currently identical to that fragment,
 * just in its own package/nav destinations so it can be changed independently without touching
 * the Calibrated screens. See docs/notes/CALIBRATED_SCREENS_HANDOFF.md for the full history of
 * everything this was copied from.
 *
 * Reuses the same shared calibrated-graph infrastructure (`CalibratedWaveformView`,
 * `DpiCalibration`, `GraphCalibration`) rather than forking those too — they're generic
 * paper/mm-scale/calibration plumbing, not specific to the "Calibrated" screen identity, so a
 * calibration Applied from either screen pair currently affects both.
 *
 * Added 2026-08-21 — always-on live preview + speaker mute toggle. The graph (and BPM) run
 * continuously from the moment this screen is visible ([PREVIEW][FullTimeOnRecordingUiState]),
 * writing nothing durable to disk, so the clinician can confirm placement before pressing
 * Record. [taal-core]'s `TaalRecorder` has no listen-only/no-file mode (checked directly —
 * `start()` requires a mandatory `.wav` raw-file path that `TaalAudioCapture` unconditionally
 * writes to), so preview uses a second `TaalRecorder` instance ([previewRecorder]) pointed at a
 * fixed temp file in [android.content.Context.getCacheDir] — the *filtered* WAV is skipped
 * entirely (never calling `setFilteredAudioFilePath`), since `TaalRecorder.start()` only opens
 * that file if a path was set. The temp file is overwritten on every preview start and deleted
 * on every preview stop/pause/destroy; it is never referenced by any save/discard path or the
 * recordings library. Pressing Record stops preview and starts a real, unmodified recording
 * session — the real-recording code path below this comment is otherwise untouched by this
 * change, so the produced WAV files are byte-identical to before.
 *
 * The speaker toggle gates a monitor path that already existed here before this change
 * ([startAudioMonitor]/[feedSpeaker]) — muted by default, never persisted across screen visits.
 */
class FullTimeOnRecordingFragment : Fragment() {

    private var _binding: FragmentFulltimeonRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FullTimeOnRecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    private var audioTrack: AudioTrack? = null
    private val waveformEntries = ArrayList<Entry>()

    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    private var waveformDataSet: LineDataSet? = null
    private var totalSamplesProcessed = 0L  // Sample-accurate X position counter
    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // The visible window is derived from the grid's physical width, not a fixed constant; this
    // holds the most recently derived value (updated on layout/rotation).
    private var currentWindowSeconds = 4f

    // Derived so ~one min/max bucket lands per horizontal pixel at the current paper speed,
    // instead of a fixed constant. Recomputed only while idle (see onVisibleSecondsChanged
    // below) and held frozen for the whole recording session: the recorder mutates
    // LineDataSet.values in place against a monotonic sample-counter X axis, so changing the
    // bucket size mid-recording would space already-plotted points inconsistently with new ones.
    private var sessionBucketSize = DOWNSAMPLE_BUCKET_FALLBACK

    // Per-device calibration override (Time Zoom, set from the Player) — null means "use the
    // grid-derived default window." Re-read in onResume() so returning from the Player after
    // Apply/Reset reflects immediately. X (visibleSeconds) only now — Y is no longer read from
    // this; see peakAmplitude below (user request: match production RecordingFragment's actual
    // sizing behavior instead of a fixed/overridable constant).
    private var calibrationOverride: GraphCalibration.Override? = null

    // Ported verbatim from production RecordingFragment (user request, 2026-08-21: "the graph
    // of RecordingFragment like the size, and how do we set after the peaks") — two-phase
    // warmup/lock Y-axis, replacing FullTimeOn's previous fixed-axis approach. Phase 1 (first
    // WARMUP_MS of a session): axis stays ±1.0 while the true peak is observed. Phase 2: axis
    // locks to peakAmplitude and is never touched again until the next session's reset. Reset
    // points mirror production's exactly: resetToIdle() (idle/disconnect/preview-restart) and
    // startRecording()'s own pre-flight reset — a fresh warmup starts each time, and the grid
    // background/paper is completely unaffected (this is purely a trace-scale computation).
    private var peakAmplitude = 1.0f
    private var warmupPeak = 0f
    private var warmupDone = false
    private var lastPeakUpdateTime = 0L

    // Live preview — a second, independent TaalRecorder pointed at a throwaway cache file (see
    // class doc for why this is the only option the SDK supports). Never active at the same
    // time as [taalRecorder] — startRecording() always stops this first.
    private var previewRecorder: TaalRecorder? = null
    private lateinit var previewTempFile: File
    // Distinguishes an intentional stopPreview() from the recorder's own internal
    // PREVIEW_SESSION_SECONDS timeout — see the preview OnInfoListener's onStateChange.
    private var previewStoppedIntentionally = false

    // Speaker toggle — muted by default, reset every screen entry (never persisted, see class
    // doc). Read by feedSpeaker(), which both the preview and real-recording listeners call.
    private var isSpeakerMuted = true

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f

        // Pre-layout fallback only (pxPerMmX not yet known) — see deriveBucketSize/sessionBucketSize.
        private const val DOWNSAMPLE_BUCKET_FALLBACK = 88

        // Y-axis warmup/lock constants, copied verbatim from production RecordingFragment's
        // values (WARMUP_MS/HEADROOM/MIN_PEAK) — see peakAmplitude's doc above.
        private const val WARMUP_MS = 2000
        private const val HEADROOM = 1.5f
        private const val MIN_PEAK = 0.02f

        // Default visible time window in seconds — copied verbatim from production
        // RecordingFragment's own WINDOW_SECONDS (user request, 2026-08-21: match production's
        // speed/shape/working exactly). Used only when no calibration override is saved; a
        // saved Time Zoom override (pinch + Apply on the Player) still takes priority over
        // this, applies live to the Recorder too, and Reset returns here — see
        // onVisibleSecondsChanged below.
        private const val DEFAULT_WINDOW_SECONDS = 10f

        // Pre-amp slider should only change loudness, never the live graph's size — the trace
        // reflects true acoustic level, not the amplified WAV level.
        private const val COMPENSATE_PREAMP_IN_DISPLAY = true

        // Trace stroke width in dp.
        private const val TRACE_LINE_WIDTH_DP = 2.0f

        // How long a single preview capture session runs before TaalRecorder's own internal
        // duration cap silently stops it (see TaalAudioCapture's `endTime` check) and it's
        // seamlessly restarted (see the preview OnInfoListener's onStateChange). Bounds how
        // large the throwaway cache file can grow if a screen is left open indefinitely —
        // 10 minutes ≈ 53MB of raw PCM, not something worth carrying for hours.
        private const val PREVIEW_SESSION_SECONDS = 600
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
        _binding = FragmentFulltimeonRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewTempFile = File(requireContext().cacheDir, "fto_live_preview.wav")

        setupWaveformChart()
        setupFilterButtons()
        setupPreAmpSlider()
        setupButtons()
        setupSpeakerToggle()
        observeState()
        setupConnectionReceiver()
        updateCalibrationCaption()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.uiState.value == FullTimeOnRecordingUiState.RECORDING) {
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

        calibrationOverride = GraphCalibration.getOverride(requireContext())
        resetYAxisForNewSession()

        // 50mm/s. Reverted from a couple of failed speed guesses (25, then 12.5) once the real
        // cause of "feels too fast" turned out to be something else entirely: with the window
        // width grid-derived (see onVisibleSecondsChanged below, before this fix), the page
        // flips as often as every ~2 seconds on a typical phone at any paper speed — paper
        // speed alone barely moves that. DEFAULT_WINDOW_SECONDS below is the actual fix; paper
        // speed only governs what a grid square *means* now, not how often the page turns.
        // Must match FullTimeOnPlayerFragment exactly, so a recording looks the same live as it
        // does in review.
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_50)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        waveformView.onVisibleSecondsChanged = { _ ->
            // The actual "too fast" fix (user request, 2026-08-21): default to production
            // RecordingFragment's own fixed DEFAULT_WINDOW_SECONDS (10s) instead of the grid's
            // physically-derived `seconds` (which on a typical phone is only ~2s wide — the
            // page was flipping 5x more often than production's). A saved Time Zoom override
            // still wins over both, same as before — see FullTimeOnPlayerFragment's identical
            // substitution. `seconds` itself is intentionally unused as a fallback now; it's
            // still the parameter this callback fires with (grid/layout derivation still runs,
            // e.g. on rotation), just no longer what picks the *default*.
            currentWindowSeconds = calibrationOverride?.visibleSeconds ?: DEFAULT_WINDOW_SECONDS
            // Don't clobber an in-progress recording's dataset on rotation/resize — only
            // reset to the dummy grid-holder dataset when there's no real data yet.
            // updateWaveform() re-enforces the range every callback while recording is live.
            if (waveformDataSet == null) {
                // Only re-derive the bucket size while idle; a session in progress must keep
                // using whatever was frozen when it started (see sessionBucketSize doc).
                //
                // Derived from the *effective* window (currentWindowSeconds, which already
                // folds in any calibration override above), not from paper speed alone —
                // deriveBucketSize() assumes the grid's native, un-overridden pixel density,
                // so a saved override that narrows the window (more zoomed in) would leave the
                // bucket sized for the wider native view: each min/max pair stretched across
                // several pixels instead of one, reading as noisy/jagged.
                val plotWidthPx = waveformView.chart.width.toFloat()
                val visibleSampleCount = currentWindowSeconds * INPUT_SAMPLE_RATE
                val derived = if (plotWidthPx > 0f) {
                    CalibratedWaveformView.deriveBucketSizeForVisibleRange(visibleSampleCount, plotWidthPx)
                } else {
                    CalibratedWaveformView.deriveBucketSize(
                        INPUT_SAMPLE_RATE,
                        waveformView.paperView.currentScale().paperSpeed.mmPerSecond,
                        waveformView.paperView.currentScale().pxPerMmX
                    )
                }
                if (derived > 0) sessionBucketSize = derived
                resetChartToDummyData()
            } else {
                // currentWindowSeconds (already resolved above), not raw `seconds` — a
                // layout/rotation re-derivation must not silently override the fixed default
                // or an active calibration override mid-session.
                chart.setVisibleXRangeMaximum(currentWindowSeconds)
                chart.setVisibleXRangeMinimum(currentWindowSeconds)
                chart.invalidate()
            }
        }
        // If layout already happened (e.g. returning to this fragment), derive immediately.
        waveformView.recomputeVisibleSeconds()
        if (chart.data == null) resetChartToDummyData()
    }

    /**
     * Y-axis reset for the start of a new PREVIEW or RECORDING session — call sites:
     * setupWaveformChart() (fragment creation), resetToIdle() (idle/disconnect/preview
     * restart), startRecording()'s pre-flight reset, and onResume() (returning from the
     * Player). A saved calibration override wins outright: skip the warmup entirely and lock
     * straight to it, exactly like a saved value did before production's adaptive behavior was
     * ported in (user request, 2026-08-21 — Apply/Reset must actually take effect on this
     * screen, not just get silently ignored). With no override, production's own two-phase
     * warmup/lock behavior runs unmodified — see peakAmplitude's field doc.
     */
    private fun resetYAxisForNewSession() {
        if (_binding == null) return
        val chart = binding.calibratedWaveformView.chart
        val savedYFullScale = calibrationOverride?.yFullScale
        warmupPeak = 0f
        lastPeakUpdateTime = 0L
        if (savedYFullScale != null && savedYFullScale > 0f) {
            peakAmplitude = savedYFullScale
            warmupDone = true // override wins — no warmup, no auto re-lock this session
        } else {
            peakAmplitude = 1.0f
            warmupDone = false
        }
        chart.axisLeft.axisMinimum = -peakAmplitude
        chart.axisLeft.axisMaximum = peakAmplitude
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
        // "auto" not "fixed" — Y-axis now warms up and locks to the signal each session,
        // matching production RecordingFragment, instead of a constant/overridable scale.
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude (auto) · DPI: $status"
    }

    private fun setupPreAmpSlider() {
        binding.ampSlider.value = (viewModel.preAmpDb.value ?: 5).toFloat()
        binding.ampLabel.text = "${viewModel.preAmpDb.value ?: 5} dB"

        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            viewModel.setPreAmp(db)
            binding.ampLabel.text = "$db dB"
            // Also push to previewRecorder (not just taalRecorder) — while previewing with the
            // speaker unmuted, dragging this slider should change what's actually heard live,
            // so the clinician can dial in the right dB by ear before pressing Record (user
            // request). Both are never non-null at the same time, so at most one call is real.
            taalRecorder?.setPreAmplification(db)
            previewRecorder?.setPreAmplification(db)
        }
    }

    /** Muted by default (see [isSpeakerMuted] doc) — click toggles, icon/contentDescription reflect state. */
    private fun setupSpeakerToggle() {
        updateSpeakerToggleIcon()
        binding.speakerToggle.setOnClickListener {
            isSpeakerMuted = !isSpeakerMuted
            updateSpeakerToggleIcon()
        }
    }

    private fun updateSpeakerToggleIcon() {
        if (_binding == null) return
        if (isSpeakerMuted) {
            binding.speakerToggle.setImageResource(R.drawable.ic_speaker_muted)
            binding.speakerToggle.contentDescription = getString(R.string.speaker_muted_description)
        } else {
            binding.speakerToggle.setImageResource(R.drawable.ic_volume_up)
            binding.speakerToggle.contentDescription = getString(R.string.speaker_unmuted_description)
        }
    }

    /**
     * Output-only — never affects what's written to disk or what the graph draws, just whether
     * the pre-existing speaker monitor ([startAudioMonitor]) actually plays this buffer. Shared
     * by both the preview and real-recording [TaalRecorder.OnInfoListener]s.
     */
    /**
     * [forceUnmuted] — the speaker toggle only means anything during PREVIEW (user request,
     * 2026-08-22): once RECORDING actually starts, audio plays unconditionally, same as it
     * always did before the mute toggle existed. The real-recording listener passes true here;
     * the preview listener leaves it false and respects [isSpeakerMuted] as before.
     */
    private fun feedSpeaker(data: FloatArray, forceUnmuted: Boolean = false) {
        if (isSpeakerMuted && !forceUnmuted) return
        audioTrack?.let { track ->
            val pcm = ShortArray(data.size) { i ->
                (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            track.write(pcm, 0, pcm.size)
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
                    // Event-driven retry only (never a timer/loop) — if preview failed to
                    // start earlier because no device was attached, the device physically
                    // reappearing is exactly the signal to try again. Silent: the one clear
                    // failure message was already shown when this first failed; retrying here
                    // must not add a second one on every reconnect.
                    if (isAdded && _binding != null &&
                        viewModel.uiState.value == FullTimeOnRecordingUiState.PREVIEW &&
                        previewRecorder == null && taalRecorder == null
                    ) {
                        startPreview(showErrorOnFailure = false)
                    }
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
                FullTimeOnRecordingUiState.IDLE, FullTimeOnRecordingUiState.PREVIEW -> checkPermissionAndRecord()
                FullTimeOnRecordingUiState.RECORDING -> stopRecording()
                else -> resetToIdle()
            }
        }

        // Dead in production too (STOPPED-state UI is never entered — stopRecording()
        // navigates directly to the player) — kept only so the layout/ID surface stays a
        // faithful replica.
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
                findNavController().navigate(R.id.action_fullTimeOnRecording_to_fullTimeOnPlayer, bundle)
            }
        }

        binding.folderButton.setOnClickListener {
            findNavController().navigate(R.id.action_fullTimeOnRecording_to_savedRecordings)
        }

        binding.settingsButton.setOnClickListener {
            findNavController().navigate(R.id.action_fullTimeOnRecording_to_dpiCalibration)
        }
    }

    private fun resetToIdle() {
        viewModel.setUiState(FullTimeOnRecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        viewModel.currentFilteredPath = ""
        waveformEntries.clear()
        waveformDataSet = null
        totalSamplesProcessed = 0L
        // Fresh Y-axis for the next session — override-aware (see resetYAxisForNewSession doc).
        resetYAxisForNewSession()

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
                // PREVIEW looks identical to IDLE — Record is still the primary action, filters
                // and pre-amp are still editable, timer stays at its default (never runs in
                // preview). IDLE itself is transient (see enum doc) — real devices land in
                // PREVIEW within the same onResume call that first sets it.
                FullTimeOnRecordingUiState.IDLE, FullTimeOnRecordingUiState.PREVIEW -> {
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
                    // No live graph in PREVIEW anymore (user request) — audio/speaker/BPM keep
                    // working, but the grid just shows this message until Record is pressed.
                    binding.previewMessage.visibility = View.VISIBLE
                    // The mute toggle only does anything during PREVIEW (user request) —
                    // recording is always audible regardless of it, see feedSpeaker's doc.
                    binding.speakerToggle.visibility = View.VISIBLE
                }

                FullTimeOnRecordingUiState.RECORDING -> {
                    binding.actionText.text = getString(R.string.stop_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.GONE
                    binding.preRecordingButtons.visibility = View.GONE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordButton.setImageResource(R.drawable.ic_recording_stop)
                    binding.ampSlider.isEnabled = false
                    binding.ampSliderContainer.alpha = 0.55f
                    setFilterButtonsEnabled(false)
                    binding.previewMessage.visibility = View.GONE
                    // Hidden while recording — it wouldn't do anything, audio is always on.
                    binding.speakerToggle.visibility = View.GONE
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

        // PREVIEW → RECORDING (§4.2): stop the preview stream before claiming the device for
        // real. TaalAudioCapture.stopRecording() releases the AudioRecord synchronously (not
        // deferred), so this brief gap is safe — the real TaalRecorder.start() below reliably
        // gets a clean claim on the USB device, and nothing about the real-recording path past
        // this point is changed by this feature.
        stopPreview()

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
            val rawFilePath = "${requireContext().filesDir}/fto_recording_${ts}_raw.wav"
            val filteredFilePath = "${requireContext().filesDir}/fto_recording_${ts}_filtered.wav"
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
                                RecorderState.RECORDING -> viewModel.setUiState(FullTimeOnRecordingUiState.RECORDING)
                                RecorderState.STOPPED -> viewModel.setUiState(FullTimeOnRecordingUiState.STOPPED)
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
                                // Attempt to resume the always-on preview right away rather
                                // than waiting for the next onResume (the fragment stays
                                // visible through this whole flow) — fails silently since the
                                // device just disconnected; setupConnectionReceiver's
                                // onTaalConnect() retries once it's replugged. showErrorOnFailure
                                // is false so this doesn't add a second toast on top of the one
                                // just shown above.
                                startPreview(showErrorOnFailure = false)
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
                        // Always audible while actually recording (user request) — the mute
                        // toggle only applies during PREVIEW, see feedSpeaker's doc.
                        feedSpeaker(data, forceUnmuted = true)

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

                        // Undo pre-amp gain before drawing (COMPENSATE_PREAMP_IN_DISPLAY, on by
                        // default) — the trace reflects true acoustic level, not the amplified
                        // WAV level, so the pre-amp slider only changes loudness, never the
                        // live graph's size.
                        val preAmpDb = viewModel.preAmpDb.value ?: 5
                        val preAmpGain = Math.pow(10.0, preAmpDb / 20.0).toFloat()
                        val displayData = if (COMPENSATE_PREAMP_IN_DISPLAY && preAmpGain > 1.001f) {
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
            // Fresh Y-axis for this recording — override-aware (see resetYAxisForNewSession
            // doc): a saved Peak Size calibration wins here too, not just in preview, so a
            // recording actually comes out the size the user calibrated for.
            resetYAxisForNewSession()

            resetChartToDummyData()
            binding.calibratedWaveformView.chart.moveViewToX(0f)
            startAudioMonitor()
            taalRecorder?.start()

        } catch (e: Exception) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording Error").setMessage(e.message)
                .setPositiveButton("OKAY") { dialog, _ -> dialog.dismiss() }.show()
            viewModel.setUiState(FullTimeOnRecordingUiState.IDLE)
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
                // So the Player can undo this recording's actual pre-amp gain and show the
                // same true-acoustic-level trace the recorder showed live.
                putInt("preAmpDb", viewModel.preAmpDb.value ?: 5)
            }
            findNavController().navigate(R.id.action_fullTimeOnRecording_to_fullTimeOnPlayer, bundle)
        }
        // Deliberately NOT restarting preview here — this fragment is about to navigate away
        // to the Player. Preview restarts on its own the next time onResume() runs, which
        // happens the moment the user comes back (§4.5's "returning from the Player must land
        // back in PREVIEW cleanly").
    }

    /**
     * Starts (or restarts) the live preview stream — see the class doc for why this uses a
     * second [TaalRecorder] pointed at a throwaway cache file rather than any listen-only SDK
     * mode (checked directly against taal-core's API surface; none exists). No-ops safely if
     * a real recording is in progress or preview is already running.
     *
     * [showErrorOnFailure] is false for the silent, event-driven retries this triggers on its
     * own (device reconnect via [setupConnectionReceiver], or the natural
     * [PREVIEW_SESSION_SECONDS] restart below) — true only for the original user-visible
     * attempt from [onResume], so a missing device produces exactly one message, never a retry
     * storm (§4.1 of the spec this was built against).
     */
    private fun startPreview(showErrorOnFailure: Boolean = true) {
        if (_binding == null || !isAdded) return
        if (taalRecorder != null) return
        if (previewRecorder != null) return

        resetToIdle()
        bpmCalculator.reset()
        viewModel.setUiState(FullTimeOnRecordingUiState.PREVIEW)

        val filterName = viewModel.currentFilter.value ?: "HEART"

        try {
            previewRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(previewTempFile.absolutePath)
                // Deliberately NOT calling setFilteredAudioFilePath() — TaalRecorder.start()
                // only opens/writes that second file if a path was set (verified directly in
                // taal-core), so preview only ever produces the one throwaway raw file.
                setRecordingTime(PREVIEW_SESSION_SECONDS)
                setPlayback(false)
                setPreAmplification(viewModel.preAmpDb.value ?: 5)

                val low = viewModel.customLowCut
                val high = viewModel.customHighCut
                if (filterName == "CUSTOM" && low != null && high != null && low > 0f && high > low) {
                    setCustomBandpass(low.toDouble(), high.toDouble())
                } else {
                    // Falls back to HEART for an unrecognized or not-yet-valid custom filter
                    // rather than popping the real recording flow's validation dialog — that
                    // dialog is for a user-initiated Record press, not an automatic screen-open
                    // side effect (§4.1: preview must never surprise the user with a popup).
                    setPreFilter(try { PreFilter.valueOf(filterName) } catch (_: Exception) { PreFilter.HEART })
                }

                onInfoListener = buildPreviewInfoListener()
            }

            startAudioMonitor()
            previewRecorder?.start()
        } catch (e: Exception) {
            previewRecorder = null
            if (showErrorOnFailure) {
                Toast.makeText(requireContext(), e.message ?: "TAAL device not connected", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Stops the preview stream (if any) and deletes its temp file — never surfaced anywhere else. */
    private fun stopPreview() {
        val recorder = previewRecorder ?: return
        previewStoppedIntentionally = true
        try { recorder.stop() } catch (_: Exception) {}
        previewRecorder = null
        stopAudioMonitor()
        try { previewTempFile.delete() } catch (_: Exception) {}
    }

    /**
     * Separate from the real recording's listener so a preview session can never accidentally
     * flip [viewModel]'s state to RECORDING or trigger real-recording-only side effects (file
     * cleanup on disconnect, the "silent recording" popup, timer updates) — see each override.
     */
    private fun buildPreviewInfoListener(): TaalRecorder.OnInfoListener {
        return object : TaalRecorder.OnInfoListener {
            override fun onStateChange(state: RecorderState) {
                activity?.runOnUiThread {
                    if (state != RecorderState.STOPPED) return@runOnUiThread // ignore RECORDING/INITIAL — not UI-visible for preview
                    if (previewStoppedIntentionally) {
                        previewStoppedIntentionally = false
                        return@runOnUiThread
                    }
                    // Reached PREVIEW_SESSION_SECONDS on its own — device is presumably still
                    // connected, so restart silently. Not a failure, nothing to show.
                    previewRecorder = null
                    if (isAdded && _binding != null && viewModel.uiState.value == FullTimeOnRecordingUiState.PREVIEW) {
                        startPreview(showErrorOnFailure = false)
                    }
                }
            }

            override fun onRawProgressUpdate(data: FloatArray) {}

            override fun onDeviceDisconnected() {
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        previewRecorder = null
                        stopAudioMonitor()
                        try { previewTempFile.delete() } catch (_: Exception) {}
                        Toast.makeText(
                            requireContext(),
                            "Device disconnected. Please connect the device.",
                            Toast.LENGTH_LONG
                        ).show()
                        // State stays PREVIEW — setupConnectionReceiver's onTaalConnect() retries
                        // once the device is replugged; no timer-based retry loop.
                    }
                }
            }

            // No onSilentRecordingDetected override — preview restarts silently every
            // PREVIEW_SESSION_SECONDS by design (see onStateChange above), and the base
            // interface's default no-op is exactly right here: popping "Ready to Capture /
            // please discard and start a new one" every ~10 minutes during ordinary preview
            // would be nonsensical (that dialog is about a just-completed real recording).

            override fun onProgressUpdate(
                sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
            ) {
                // No graph during PREVIEW anymore (user request, 2026-08-21) — audio keeps
                // streaming (feedSpeaker + BPM) so the mute toggle and BPM readout still work,
                // but nothing is drawn; previewMessage stays up until RECORDING actually
                // starts. Timer also intentionally not updated — stays 00:00:00 in PREVIEW.
                feedSpeaker(data)

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
            }
        }
    }

    /**
     * Clone of CalibratedRecordingFragment's V7-style updateWaveform(): X window derived from
     * the grid (or an active calibration override), min/max bucket downsampling, fixed Y-axis
     * (no warmup, no peak lock) — set once in setupWaveformChart() and never touched here.
     */
    private fun updateWaveform(data: FloatArray) {
        if (_binding == null || !isAdded) return
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart

        var bufferPeak = 0f
        for (sample in data) {
            val abs = Math.abs(sample)
            if (abs > bufferPeak) bufferPeak = abs
        }

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

        // Bounds memory to ~2 windows' worth of points indefinitely — this is what keeps an
        // open-ended live preview session from accumulating points forever (§4.5's "ring
        // buffer sized to the visible window"); it already applied equally to recording, which
        // is naturally bounded by its own duration cap. Formula factored out to
        // CalibratedWaveformView.ringTrimMinX() (additive-only there) so it's unit-testable.
        val minXToKeep = CalibratedWaveformView.ringTrimMinX(latestX, windowSeconds)
        if (minXToKeep > 0) {
            val iterator = waveformEntries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().x < minXToKeep) iterator.remove() else break
            }
        }

        // Ported verbatim from production RecordingFragment (user request — see peakAmplitude's
        // field doc): two-phase warmup/lock. Phase 1 (first WARMUP_MS of this session):
        // accumulate the true peak, axis stays ±1.0. Phase 2: lock once and never touch the
        // axis again until the next session's reset (resetToIdle() / startRecording()). Applies
        // identically whether this buffer came from PREVIEW or RECORDING — both share this same
        // updateWaveform() pipeline and both get their own fresh warmup at session start.
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
        // Muted by default on every screen entry — deliberately never persisted (§4.3): an app
        // that starts making noise on its own is a worse failure than one extra tap.
        isSpeakerMuted = true
        updateSpeakerToggleIcon()

        // Re-read the calibration override in case the Player's Apply/Reset changed it since
        // this fragment was created — MUST happen before resetToIdle()/startPreview() below,
        // since resetYAxisForNewSession() (called from both) reads calibrationOverride to
        // decide the Y-axis for the session that's about to start. Getting this order wrong is
        // exactly how a just-Applied Peak Size silently failed to show up back here.
        calibrationOverride = GraphCalibration.getOverride(requireContext())

        if (taalRecorder == null) {
            resetToIdle()
            // Always-on preview (§4.1) — entered automatically the moment this screen becomes
            // visible, including the very first time and every return trip from the Player.
            // No-ops safely if already running; shows one message and waits for a reconnect
            // event if no device is attached (see startPreview's doc).
            startPreview()
        }
        viewModel.setPreAmp(5)
        binding.ampSlider.value = 5f
        binding.ampLabel.text = "5 dB"

        // Re-fires onVisibleSecondsChanged, which reads the refreshed override for X.
        binding.calibratedWaveformView.recomputeVisibleSeconds()
        binding.calibratedWaveformView.chart.invalidate()

        updateCalibrationCaption()
    }

    override fun onPause() {
        super.onPause()
        // Never leave the preview USB stream or speaker AudioTrack running in the background
        // (§4.5) — battery, device heat, not holding the stethoscope handle open against other
        // apps. Safe no-op if a real recording is in progress (stopPreview only touches
        // previewRecorder, never taalRecorder) or preview wasn't running.
        stopPreview()
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
        // Defensive — onPause() already stops preview in the ordinary lifecycle, but cover the
        // case of this fragment being destroyed without an intervening onPause.
        try { previewRecorder?.stop() } catch (_: Exception) {}
        previewRecorder = null
        if (::previewTempFile.isInitialized) {
            try { previewTempFile.delete() } catch (_: Exception) {}
        }
    }
}
