# TAAL Demo App — Master Handoff (Full Code Edition)

> **Purpose**: a single, self-contained file for a fresh Claude session with zero prior context.
> This version embeds **full source code**, not summaries — written so this one file, sent alone,
> is enough to understand and continue the work. Covers `taal-core`, `taal-ui-kit`, the `app`
> module's architecture/navigation, and — in full detail — every waveform/ECG-paper-grid
> implementation in the repo, including the complete current source of the **Calibrated** and
> **FullTimeOn** screen systems (the most recent, most actively-evolving work).
>
> **Deliberately excluded from this file** (by request): `lungs-app`, `visualizertaal-app`, the
> PCG heart-sound segmentation modules/feature, and `stemz-app`. Those exist in the repo but
> aren't covered here.
>
> **This is a snapshot, not a live view.** `nav_graph.xml`'s `startDestination` above all is under
> active, uncommitted, sometimes-concurrent editing — see §8 for what to re-check before trusting
> anything here as current. All code below was read directly from the live files on disk on
> 2026-08-14 (not copied from other docs without verification — several of those other docs
> turned out to be stale relative to the actual source, most notably `CalibratedEcgPaperView.kt`,
> which had gained a Kardia-style alpha/pixel-snap grid system and a pure-white background that
> an earlier doc didn't know about). Where this doc and the code disagree, the code wins.

---

## Table of contents

1. What this app is, and the repo's shape
2. `taal-core` — the audio engine
3. `taal-ui-kit` — reusable UI on top of taal-core
4. The `app` module — architecture, navigation, screen inventory
5. The waveform/graph system — overview of all 6 implementations
6. Screen A — `RecordingFragment.kt` (production, full code)
7. Screen B — `PlayerFragment.kt` (production, full code)
8. Screen C — `TestRecordingFragment.kt` + `EcgPaperView`/`MmScale` (full code)
9. Screen D — `TestPlayerFragment.kt` (full code)
10. Screen E — Calibrated Recorder/Player (full code + full decision history)
11. Screen F — FullTimeOn Recorder/Player (full code + full decision history)
12. Nav wiring for Calibrated/FullTimeOn
13. Cross-cutting "if you change X, check Y" table
14. Timeline
15. Read this before touching anything — current-state checklist

---

## 1. What this app is, and the repo's shape

**TAAL** is MUSE Diagnostics' digital stethoscope product. This repo (root project name "TAAL
SDK") contains the audio-capture/DSP SDK, a UI-kit layer built on it, and the main clinical
Android app. Package family: `com.musediagnostics.taal.*`.

Modules covered in this doc: `:taal-core` (Android library, pure audio engine, no UI),
`:taal-ui-kit` (Android library, reusable recording/playback UI on `taal-core`), `:app` (Android
application, the main clinical app — **this doc's main focus**).

`app` depends on `:taal-core` directly. **It does not depend on `:taal-ui-kit`** — every
recording/player screen in `app` (production, dormant test, Calibrated, FullTimeOn — six
independent implementations in total, see §5) is its own hand-maintained implementation built
directly on `taal-core`.

**Critical fact about repo state**: `git log` shows `app/src/main/res/navigation/nav_graph.xml`
was last **committed** 2026-03-13. Everything in §10–§11 of this doc (Calibrated, FullTimeOn)
exists only as **uncommitted working-tree changes**, with no version-control safety net if two
sessions edit the same file at once — this has already happened at least once (§15).

---

## 2. `taal-core` — the audio engine (`com.musediagnostics.taal`)

- `TaalRecorder.kt` — public recorder API. Wraps `TaalAudioCapture` + `AudioFilterEngine`.
  0–30 dB pre-amp. `OnInfoListener` interface:
  ```kotlin
  interface OnInfoListener {
      fun onStateChange(state: RecorderState)
      fun onProgressUpdate(sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray)
      fun onRawProgressUpdate(data: FloatArray) {}                              // default no-op
      fun onDeviceDisconnected() {}                                             // default no-op
      fun onSilentRecordingDetected(isFirstSinceConnect: Boolean) {}            // default no-op
  }
  ```
- `TaalPlayer.kt` — WAV playback via `AudioTrack`, real-time DSP, `onPlaybackProgress`/
  `onPlaybackComplete` callbacks.
- `core/TaalAudioCapture.kt` — `AudioRecord`-based capture, 44,100 Hz mono 16-bit, writes WAV,
  USB connection check, mid-recording disconnect polling every 500ms.
- `dsp/AudioFilterEngine.kt` — Biquad bandpass (preset filters: HEART/LUNGS/BOWEL/PREGNANCY/
  FULL_BODY) + 5-band graphic EQ + pre-amp.
- `PreFilter` enum, `TaalDisconnectedException`, `TaalNotAvailableForUseException`,
  `InvalidFileNameException`.

**No listen-only/no-file recording mode exists.** `TaalRecorder.start()` always requires a
`.wav` raw-file path (`setRawAudioFilePath`), written unconditionally by `TaalAudioCapture`. This
was confirmed by direct source investigation while building FullTimeOn's always-on preview
(§11) — any "preview without saving" feature must use a real (throwaway) file; there is no
parameter or mode that skips writing it.

**Reliability fixes (Aug 2026)**: conditional USB device lock (was unconditionally re-locking on
every `start()`, which caused a flat first recording after connecting — Android's native
`restoreRecord_l` teardown/rebuild is unpredictably slow); mid-recording disconnect detection +
cleanup (discards partial files, tells the user); silent-recording detection based on the
**filtered+amplified** signal's peak (`TaalRecorder.maxFilteredPeakSeen`, threshold `0.01f`) —
not the raw mic signal, which caused false positives on a quiet-but-validly-pre-amped signal —
gated to first-recording-since-connect only (a "please reconnect and check placement" variant for
later recordings proved unreliable in testing and was dropped); removed a stray
`captureJob?.cancel()` in `stopRecording()` that silently swallowed the stop-callback's tail
block (Kotlin coroutine cancellation takes effect at the next suspension point, which was exactly
where `onStateChange`/`onDeviceDisconnected`/`onCaptureCompleted` fired).

---

## 3. `taal-ui-kit` — reusable UI on top of taal-core (`com.musediagnostics.taal.uikit`)

Reached near-parity with `app`'s own recording flow over time: Custom filter (RangeSlider + Hz
inputs), `FilterPlacementDialog`, back-press lock during recording, filter buttons
disabled/dimmed while recording, Share/Delete on saved recordings. `app` does **not** depend on
this module (see §1) — it's for third-party integrators embedding TAAL recording/playback in
their own apps.

Save flow: `SaveRecordingFragment` (live, nav-wired via `nav_uikit.xml`) saves to
`filesDir/saved/` and best-effort copies to the device's `Music/Taal Saved Audios` folder via
MediaStore (API 29+) or direct file write + `WRITE_EXTERNAL_STORAGE` (API 24-28). `TaalSaveDialog.kt`
still exists in the tree but is dead code — zero references from any Kotlin file in the module.

---

## 4. The `app` module — architecture, navigation, screen inventory

**Namespace**: `com.musediagnostics.taal.app`. **applicationId**: `com.musediagnostics.taal`.
**minSdk 24** (this module; note that if the PCG segmentation feature — excluded from this doc —
is ever wired back in, it raises `app`'s own minSdk to 26, a deliberate, separately-tracked
decision, not something to assume is currently in effect here).

### 4.1 Navigation entry point — the single most volatile fact in this repo

`app/src/main/res/navigation/nav_graph.xml`'s `app:startDestination` is not a stable fact — it
has been flipped between `recordingFragment` (the real, shipping default), `calibratedRecordingFragment`,
and `fullTimeOnRecordingFragment` repeatedly during concurrent development, sometimes by
different people/sessions working on unrelated features. **Always check the live value and its
adjacent comment before assuming anything**:

```bash
grep -n "startDestination" app/src/main/res/navigation/nav_graph.xml
```

The line carries a `<!-- TEMP for dev testing — revert to @id/recordingFragment before shipping -->`
comment when it's pointed away from production. **The correct shipping value is always
`@id/recordingFragment`.**

The full production auth chain (`SplashFragment`, `SignInFragment`, `LoginFragment`,
`OtpFragment`, `SetPinFragment`, `PinLoginFragment`) is fully built and wired with nav
actions/`popUpTo`s, but has never been the `startDestination` in any state observed so far — it
is built but currently unreachable/orphaned, not a bug introduced by any of the work in this doc.

### 4.2 Screen inventory

| Screen(s) | Status | Package | Notes |
|---|---|---|---|
| `SplashFragment` → auth chain | Built, **unreachable** | `ui/auth/*` | See above |
| `RecordingFragment` / `PlayerFragment` | **Live, production** | `ui/recording/`, `ui/player/` | The real screens users see. Full code in §6–§7. |
| `EqualizerFragment` | Live, production | `ui/player/` | Parametric EQ, app-only feature, not covered further here |
| `TestRecordingFragment` / `TestPlayerFragment` | **Dormant** (not reachable from any button/menu) | `ui/recording/`, `ui/player/` | Marked `// THIS IS NOT IN USE (ONLY TESTING BY KUNAL)` in source. Full code in §8–§9. Hosts `EcgPaperView`. |
| `CalibratedRecordingFragment` / `CalibratedPlayerFragment` / `DpiCalibrationFragment` | **Built, tested, real** — reachable only via a temporary `startDestination` flip or a debug deep link | `ui/calibrated/` | mm-accurate ECG paper + per-device calibration. Full code in §10. |
| `FullTimeOnRecordingFragment` / `FullTimeOnPlayerFragment` | **Built, tested, real** — clone of Calibrated with always-on preview | `ui/fulltimeon/` | Full code in §11. |
| `RecordingLibraryFragment`, `AddPatientFragment`, `EditRecordingFragment`, `CropRecordingFragment` | Live, production | `ui/library/`, `ui/patient/`, `ui/player/`, `ui/recording/` | Not graph-related, not covered further |
| `AboutUsFragment`, `FaqFragment`, `PrivacyPolicyFragment`, `SubscriptionFragment`, `UserManualFragment` | Live | `ui/info/` | Informational only |

---

## 5. The waveform/graph system — overview

There is **no shared waveform/chart module** anywhere in `app`. Every screen pair below is an
independently-diverged, hand-copied implementation of "downsample audio → plot on
MPAndroidChart, with some kind of grid behind it." A fix to one **never** applies to another.
There are **six** such implementations in `app`:

| # | Screen | Status | Grid style |
|---|---|---|---|
| A | `RecordingFragment` (production) | Live, reachable | MPAndroidChart's own light-gray gridlines |
| B | `PlayerFragment` (production) | Live, reachable | MPAndroidChart's own light-gray gridlines |
| C | `TestRecordingFragment` (dormant) | Not reachable from UI | `EcgPaperView` (mm-accurate ECG paper) behind a transparent chart |
| D | `TestPlayerFragment` (dormant) | Only reachable from C | MPAndroidChart's own light-gray gridlines (no `EcgPaperView`) |
| E | Calibrated Recorder/Player | Built/tested, reachable via temp startDestination or deep link | `CalibratedEcgPaperView` (a second, independent mm-accurate ECG paper fork) |
| F | FullTimeOn Recorder/Player | Built/tested, reachable via temp startDestination | Shares E's `CalibratedEcgPaperView`/`CalibratedWaveformView` infrastructure |

If asked to "fix the waveform graph" without a screen specified: ask which one, or default to
production A+B (the only ones a real user can reach today). Full code for every screen follows.

---

## 6. Screen A — `RecordingFragment.kt` (production, live)

**File**: `app/src/main/java/com/musediagnostics/taal/app/ui/recording/RecordingFragment.kt`
**Layout**: `fragment_recording.xml` (`@id/waveformChart` only, no ECG paper view — plain
MPAndroidChart with `#F0F0F0` gridlines).

**Key facts**:
- `WINDOW_SECONDS = 10f` — fixed 10s visible window, locked (`setVisibleXRangeMaximum` **and**
  `Minimum` both set to this).
- `DOWNSAMPLE_STEP = 44` — 1 in 44 samples plotted (~1002 pts/sec), a raw sample pick, **not**
  RMS or min/max reduction.
- `WARMUP_MS = 2000`, `HEADROOM = 1.5f`, `MIN_PEAK = 0.02f` — Y-axis two-phase warmup/lock.
- **Y-axis locks after warmup and never re-expands.** During the first `WARMUP_MS` of a session,
  the true per-buffer peak (`max(abs(sample))`, not RMS) is tracked without touching the axis
  (stays ±1.0). Once warmup elapses, the axis locks permanently to
  `(warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)` and is never touched again — if the signal
  gets louder later, the trace clips visually by design (the `else` branch that used to
  re-expand the axis has been deliberately removed — see the comment right above
  `updateWaveform()`).
- **X-axis is a monotonic sample counter** (`totalSamplesProcessed / INPUT_SAMPLE_RATE`), not the
  callback's `timeStamp` (which jitters). **Camera is page-snap, not smooth scroll** — at each
  10s boundary the camera jumps instantly to the next page.
- **Pre-amp display compensation**: the trace shows `data[i] / preAmpGain` (10^(dB/20)) — the
  saved WAV keeps the amplified signal, but what's drawn is de-amplified back to true acoustic
  level. Only this screen does this.
- **Memory cleanup**: keeps current page + previous page only (~20s max). **Dataset reuse**:
  `LineDataSet.values` mutated in place rather than replacing `chart.data` (which causes a
  one-frame blank in MPAndroidChart).
- Line color `R.color.waveform_blue` (`#2D7DD2`), width 1.5f.

Full source (997 lines, package/imports at top):

```kotlin
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
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import com.musediagnostics.taal.app.R
import java.io.File
import com.musediagnostics.taal.app.databinding.FragmentRecordingBinding
import com.musediagnostics.taal.app.dsp.HeartBpmCalculator
import com.musediagnostics.taal.app.ui.MainActivity
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

    // Persistent dataset — reused every callback to avoid MPAndroidChart's
    // mOffsetsCalculated=false reset that causes a blank frame each time chart.data is replaced.
    private var waveformDataSet: LineDataSet? = null
    private var peakAmplitude = 1.0f       // Y-axis half-range; set from warmup then locked
    private var lastPeakUpdateTime = 0L
    private var warmupPeak = 0f           // Accumulates absolute-peak over the warmup window
    private var warmupDone = false        // Latches true after WARMUP_MS of signal observed
    private var recordingStartTime = 0L
    private var chartInitialized = false
    private var totalSamplesProcessed = 0L  // Sample-accurate X position counter
    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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
        _binding = FragmentRecordingBinding.inflate(inflater, container, false)
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
            val low  = vals[0].toInt()
            val high = vals[1].toInt()
            binding.customLowCutInput.setText(low.toString())
            binding.customHighCutInput.setText(high.toString())
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

                else -> {
                    resetToIdle()
                }
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

                    override fun onSilentRecordingDetected(isFirstSinceConnect: Boolean) {
                        // Only shown for the first recording since this physical USB
                        // connection began — the known cold-start quirk on some
                        // budget-chipset phones.
                        if (!isFirstSinceConnect) return

                        // Fires after the recording has already finished — by now the
                        // user may already be on PlayerFragment reviewing it, so this
                        // dialog is anchored to the Activity window (not this fragment's
                        // view) so it shows on top regardless of which screen is current.
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

            binding.waveformChart.data = LineData()
            binding.waveformChart.moveViewToX(0f)
            startAudioMonitor()
            taalRecorder?.start()

        } catch (e: Exception) {
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
     * divided by the sample rate to get jitter-free X positions.
     *
     * Y-axis (two-phase):
     *   Phase 1 — WARMUP (first 1500ms): accumulate true peak; axis stays ±1.0.
     *   Phase 2 — LOCKED: axis only expands when signal exceeds current scale.
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
                    break
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

        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)
        chart.moveViewToX(currentViewX)
        chart.invalidate()
    }

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
    }
}
```

---

## 7. Screen B — `PlayerFragment.kt` (production, live)

**File**: `app/src/main/java/com/musediagnostics/taal/app/ui/player/PlayerFragment.kt`
**Layout**: `fragment_player.xml` (`@id/waveformChart` only).

**Key facts**:
- Fixed Y-axis ±0.5 (no warmup, never adaptive, set once).
- 4-second default visible window (`setVisibleXRangeMaximum(4f)`), user **can** pan/zoom (unlike
  Recording, which locks touch off entirely).
- Loads the whole file once on the IO thread; **reads the real sample rate from the WAV header**
  (bytes 24–27, little-endian), not hardcoded — matters because AI-testing files can be at 8kHz
  and a hardcoded 44100 would show the wrong duration.
- Downsamples to at most 3,000 points for display (picks every `step`-th sample, not min/max).
- Line color `#2D7DD2` (literal, not the resource), width 2.5f.
- Continuous centered-follow camera during playback (not page-snap); left edge pinned at 0 for
  the first `halfRange` seconds so it never shows negative time.
- A `HEADROOM` constant exists in this file but is **dead/unused** — confirmed directly, not
  applied anywhere.
- (This live copy of the file has an `if (SegmentationFeature.ENABLED)` block wiring an
  "Analyze Heart Sounds" button — that's the PCG segmentation feature, excluded from this doc's
  scope; it's shown below only because it's literally present in the file, but ignore it.)

Full source:

```kotlin
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
import com.musediagnostics.taal.app.databinding.FragmentPlayerBinding
import com.musediagnostics.taal.app.ui.segmentation.SegmentationFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val HEADROOM = 1.5f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val isNewRecording = arguments?.getBoolean("isNewRecording", false) ?: false
        val filterName = arguments?.getString("filterName") ?: "HEART"

        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        // (PCG segmentation entry-point gating — out of scope for this doc, see note above.)
        if (SegmentationFeature.ENABLED) {
            val savedDir = File(requireContext().filesDir, "saved")
            val isInSavedDir = File(filePath).parentFile?.absolutePath == savedDir.absolutePath
            if (isInSavedDir && filePath.contains("_filtered.wav")) {
                val savedRawPath = filePath.replace("_filtered.wav", "_raw.wav")
                if (File(savedRawPath).exists()) {
                    binding.analyzeButton.visibility = View.VISIBLE
                    binding.analyzeButton.setOnClickListener {
                        val bundle = Bundle().apply { putString("rawFilePath", savedRawPath) }
                        findNavController().navigate(R.id.action_player_to_segmentationReport, bundle)
                    }
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
            val bundle = Bundle().apply {
                putString("filePath", filePath)
            }
            findNavController().navigate(R.id.action_player_to_equalizer, bundle)
        }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }

        binding.saveButton.setOnClickListener {
            if (isNewRecording) {
                val rawFilePath = arguments?.getString("rawFilePath") ?: ""
                val bundle = Bundle().apply {
                    putString("filePath", filePath)
                    putString("rawFilePath", rawFilePath)
                    putString("filterName", filterName)
                }
                findNavController().navigate(R.id.action_player_to_saveRecording, bundle)
            } else {
                showSaveDiscardDialog(filePath)
            }
        }

        binding.discardButton.setOnClickListener {
            if (isNewRecording) {
                showDiscardConfirmation(filePath)
            } else {
                showSaveDiscardDialog(filePath)
            }
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

            // Read the actual sample rate from the WAV header (bytes 24–27, little-endian).
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

    private fun showDiscardConfirmation(filePath: String) {
        val rawFilePath = arguments?.getString("rawFilePath") ?: ""
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard Recording")
            .setMessage("Are you sure you want to discard this recording? It will be permanently deleted.")
            .setPositiveButton("Discard") { _, _ ->
                try { java.io.File(filePath).delete() } catch (_: Exception) {}
                if (rawFilePath.isNotEmpty()) {
                    try { java.io.File(rawFilePath).delete() } catch (_: Exception) {}
                }
                findNavController().navigateUp()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSaveDiscardDialog(filePath: String) {
        PlayerSaveDiscardDialog { action ->
            when (action) {
                PlayerSaveDiscardDialog.Action.SAVE -> {
                    val bundle = Bundle().apply { putString("recordingFilePath", filePath) }
                    findNavController().navigate(R.id.action_player_to_addPatient, bundle)
                }

                PlayerSaveDiscardDialog.Action.DISCARD -> {
                    try {
                        java.io.File(filePath).delete()
                    } catch (_: Exception) {
                    }
                    findNavController().navigateUp()
                }
            }
        }.show(parentFragmentManager, "save_discard")
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
```

---

## 8. Screen C — `TestRecordingFragment.kt` + `EcgPaperView`/`MmScale`

Marked `// THIS IS NOT IN USE (ONLY TESTING BY KUNAL)` in source; not reachable from any app
button/menu. This is the screen this session's `EcgPaperView` work was built for and wired into.

**Diverges from Screen A**: single recording file (no raw/filtered split), no pre-amp display
compensation, **fixed** Y-axis (`-1f`/`1f`, no warmup at all), continuous camera (not page-snap).
`startRecording()` wraps `taalRecorder?.start()` in try/catch — added specifically to stop
`TaalDisconnectedException` from crashing the whole app when no USB device is attached (a real
bug found and fixed while building the ECG paper view).

### 8.1 `ecg/MmScale.kt` — plain-Kotlin unit math, no Android deps

```kotlin
package com.musediagnostics.taal.app.ecg

/**
 * Standard ECG paper speed settings. The mm/s value is the only thing that
 * changes what a grid square *means* in time — it never changes a square's
 * physical 1mm size on screen.
 */
enum class PaperSpeed(val mmPerSecond: Float) {
    SPEED_12_5(12.5f),
    SPEED_25(25f),
    SPEED_50(50f)
}

/**
 * Standard ECG gain settings. The mm/mV value is the only thing that changes
 * what a grid square *means* in voltage — it never changes a square's
 * physical 1mm size on screen.
 */
enum class Gain(val mmPerMv: Float) {
    GAIN_5(5f),
    GAIN_10(10f),
    GAIN_20(20f)
}

/**
 * Pure unit-conversion math for ECG graph paper: pixels-per-physical-millimetre
 * on each axis, plus the paper-speed/gain settings that give those millimetres
 * clinical meaning. Deliberately has no Android View/Canvas dependency so it's
 * plain-JVM unit-testable, and so a trace renderer or a future PCG lane can
 * share the exact same math as the grid.
 *
 * Grid geometry (small/large square size in px) depends only on [pxPerMmX]/[pxPerMmY].
 * [paperSpeed] and [gain] affect only [secondsToPx]/[mvToPx] and their inverses —
 * changing either never resizes the grid.
 */
class MmScale(
    var pxPerMmX: Float,
    var pxPerMmY: Float,
    var paperSpeed: PaperSpeed = PaperSpeed.SPEED_25,
    var gain: Gain = Gain.GAIN_10
) {
    companion object {
        const val MM_PER_SMALL_SQUARE = 1f
        const val SMALL_SQUARES_PER_LARGE_SQUARE = 5
        const val MM_PER_LARGE_SQUARE = MM_PER_SMALL_SQUARE * SMALL_SQUARES_PER_LARGE_SQUARE
    }

    // --- time <-> horizontal px ---
    fun secondsToPx(seconds: Float): Float = seconds * paperSpeed.mmPerSecond * pxPerMmX
    fun pxToSeconds(px: Float): Float = px / (paperSpeed.mmPerSecond * pxPerMmX)

    // --- voltage <-> vertical px ---
    fun mvToPx(mv: Float): Float = mv * gain.mmPerMv * pxPerMmY
    fun pxToMv(px: Float): Float = px / (gain.mmPerMv * pxPerMmY)

    // --- grid geometry: physical mm only, never affected by speed/gain ---
    fun smallSquarePxX(): Float = MM_PER_SMALL_SQUARE * pxPerMmX
    fun smallSquarePxY(): Float = MM_PER_SMALL_SQUARE * pxPerMmY
    fun largeSquarePxX(): Float = MM_PER_LARGE_SQUARE * pxPerMmX
    fun largeSquarePxY(): Float = MM_PER_LARGE_SQUARE * pxPerMmY

    // --- what a square currently means, at the current speed/gain ---
    fun secondsPerSmallSquare(): Float = MM_PER_SMALL_SQUARE / paperSpeed.mmPerSecond
    fun secondsPerLargeSquare(): Float = MM_PER_LARGE_SQUARE / paperSpeed.mmPerSecond
    fun mvPerSmallSquare(): Float = MM_PER_SMALL_SQUARE / gain.mmPerMv
    fun mvPerLargeSquare(): Float = MM_PER_LARGE_SQUARE / gain.mmPerMv
}
```

At defaults: 1 small square = 0.04s/0.1mV, 1 large square (5 small squares) = 0.2s/0.5mV, 1mV =
10mm, 1s = 25mm. Unit-tested (`MmScaleTest.kt`, 9 tests) — this exact invariant (grid size never
changes with speed/gain) is what the tests pin.

### 8.2 `ecg/EcgPaperView.kt` — the custom View

```kotlin
package com.musediagnostics.taal.app.ecg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.musediagnostics.taal.app.R

enum class EcgTheme { PAPER, MONITOR }

/**
 * Medically-proportioned ECG graph-paper background. Draws a millimetre-accurate
 * minor/major grid (1mm / 5mm squares), with optional 3-second tick marks and a
 * calibration pulse, cached to a Bitmap and blitted in [onDraw]. No trace/data
 * rendering — this is paper only; overlay a chart/trace view on top of it.
 *
 * All unit math (what a square means in seconds/mV, independent of its fixed
 * physical mm size) lives in [MmScale] so it can be reused by a trace renderer.
 */
class EcgPaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MM_PER_INCH = 25.4f
        private const val LARGE_SQUARES_PER_TIME_TICK = 15
        private const val CALIBRATION_LEAD_IN_SMALL_SQUARES = 1
        private const val CALIBRATION_WIDTH_SMALL_SQUARES = 5
        private const val CALIBRATION_LEAD_OUT_SMALL_SQUARES = 1
        private const val TICK_LENGTH_MM = 4f

        // Classic paper theme (exact values as specified)
        private const val PAPER_BG = "#FFF8F5"
        private const val PAPER_MINOR = "#F2B8B5"
        private const val PAPER_MAJOR = "#E0837F"
        private const val PAPER_MINOR_STROKE_MM = 0.1f
        private const val PAPER_MAJOR_STROKE_MM = 0.3f

        // Monitor theme: near-black paper, dim green grid
        private const val MONITOR_BG = "#05100A"
        private const val MONITOR_MINOR = "#123D22"
        private const val MONITOR_MAJOR = "#1F7A45"
        private const val MONITOR_MINOR_STROKE_MM = 0.1f
        private const val MONITOR_MAJOR_STROKE_MM = 0.3f
    }

    private var pxPerMmXOverride: Float? = null
    private var pxPerMmYOverride: Float? = null

    private val mmScale: MmScale = MmScale(
        pxPerMmX = computeDefaultPxPerMmX(),
        pxPerMmY = computeDefaultPxPerMmY()
    )

    var theme: EcgTheme = EcgTheme.PAPER
        set(value) { field = value; applyThemeDefaults(); rebuildGridIfPossible() }

    var showCalibrationPulse: Boolean = true
        set(value) { field = value; rebuildGridIfPossible() }

    var showTimeTicks: Boolean = true
        set(value) { field = value; rebuildGridIfPossible() }

    private var paperColor: Int = Color.parseColor(PAPER_BG)
    private var minorGridColor: Int = Color.parseColor(PAPER_MINOR)
    private var majorGridColor: Int = Color.parseColor(PAPER_MAJOR)
    private var minorStrokeMm: Float = PAPER_MINOR_STROKE_MM
    private var majorStrokeMm: Float = PAPER_MAJOR_STROKE_MM

    private var explicitPaperColor: Int? = null
    private var explicitMinorColor: Int? = null
    private var explicitMajorColor: Int? = null

    private var gridBitmap: Bitmap? = null
    private var gridDirty = true

    init {
        attrs?.let { readAttrs(it, defStyleAttr) } ?: applyThemeDefaults()
    }

    private fun readAttrs(attrs: AttributeSet, defStyleAttr: Int) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.EcgPaperView, defStyleAttr, 0)
        try {
            theme = when (ta.getInt(R.styleable.EcgPaperView_ecgTheme, 0)) {
                1 -> EcgTheme.MONITOR
                else -> EcgTheme.PAPER
            }
            mmScale.paperSpeed = when (ta.getInt(R.styleable.EcgPaperView_ecgPaperSpeed, 1)) {
                0 -> PaperSpeed.SPEED_12_5
                2 -> PaperSpeed.SPEED_50
                else -> PaperSpeed.SPEED_25
            }
            mmScale.gain = when (ta.getInt(R.styleable.EcgPaperView_ecgGain, 1)) {
                0 -> Gain.GAIN_5
                2 -> Gain.GAIN_20
                else -> Gain.GAIN_10
            }
            showCalibrationPulse = ta.getBoolean(R.styleable.EcgPaperView_ecgShowCalibrationPulse, true)
            showTimeTicks = ta.getBoolean(R.styleable.EcgPaperView_ecgShowTimeTicks, true)

            applyThemeDefaults()

            if (ta.hasValue(R.styleable.EcgPaperView_ecgPaperColor)) {
                explicitPaperColor = ta.getColor(R.styleable.EcgPaperView_ecgPaperColor, paperColor)
            }
            if (ta.hasValue(R.styleable.EcgPaperView_ecgMinorGridColor)) {
                explicitMinorColor = ta.getColor(R.styleable.EcgPaperView_ecgMinorGridColor, minorGridColor)
            }
            if (ta.hasValue(R.styleable.EcgPaperView_ecgMajorGridColor)) {
                explicitMajorColor = ta.getColor(R.styleable.EcgPaperView_ecgMajorGridColor, majorGridColor)
            }
            applyColorOverrides()
        } finally {
            ta.recycle()
        }
    }

    private fun applyThemeDefaults() {
        when (theme) {
            EcgTheme.PAPER -> {
                paperColor = Color.parseColor(PAPER_BG)
                minorGridColor = Color.parseColor(PAPER_MINOR)
                majorGridColor = Color.parseColor(PAPER_MAJOR)
                minorStrokeMm = PAPER_MINOR_STROKE_MM
                majorStrokeMm = PAPER_MAJOR_STROKE_MM
            }
            EcgTheme.MONITOR -> {
                paperColor = Color.parseColor(MONITOR_BG)
                minorGridColor = Color.parseColor(MONITOR_MINOR)
                majorGridColor = Color.parseColor(MONITOR_MAJOR)
                minorStrokeMm = MONITOR_MINOR_STROKE_MM
                majorStrokeMm = MONITOR_MAJOR_STROKE_MM
            }
        }
        applyColorOverrides()
    }

    private fun applyColorOverrides() {
        explicitPaperColor?.let { paperColor = it }
        explicitMinorColor?.let { minorGridColor = it }
        explicitMajorColor?.let { majorGridColor = it }
    }

    /** Overrides OEM-reported DPI, since it's often wrong. Pass physical pixels-per-mm. */
    fun setPixelsPerMm(x: Float, y: Float) {
        pxPerMmXOverride = x
        pxPerMmYOverride = y
        mmScale.pxPerMmX = x
        mmScale.pxPerMmY = y
        rebuildGridIfPossible()
    }

    fun setPaperSpeed(speed: PaperSpeed) {
        mmScale.paperSpeed = speed
        rebuildGridIfPossible()
    }

    fun setGain(gain: Gain) {
        mmScale.gain = gain
        rebuildGridIfPossible()
    }

    /** Read-only access to the current scale, e.g. for a trace renderer drawn on top. */
    fun currentScale(): MmScale = mmScale

    private fun computeDefaultPxPerMmX(): Float {
        val metrics = resources.displayMetrics
        // Layout Editor preview devices often report xdpi/ydpi as 0 — fall back
        // rather than let the grid come out blank/NaN in isInEditMode().
        if (isInEditMode && metrics.xdpi <= 0f) return fallbackPxPerMm(metrics)
        val value = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1f, metrics)
        return if (value > 0f && !value.isNaN()) value else fallbackPxPerMm(metrics)
    }

    private fun computeDefaultPxPerMmY(): Float {
        val metrics = resources.displayMetrics
        // TypedValue.applyDimension always keys off xdpi internally, even for
        // "vertical" style units — so the Y axis is computed from ydpi directly.
        if (isInEditMode && metrics.ydpi <= 0f) return fallbackPxPerMm(metrics)
        val value = metrics.ydpi / MM_PER_INCH
        return if (value > 0f && !value.isNaN()) value else fallbackPxPerMm(metrics)
    }

    private fun fallbackPxPerMm(metrics: android.util.DisplayMetrics): Float {
        // isInEditMode()/some emulators report xdpi/ydpi as 0. Approximate from
        // density (density * 160 ~= dpi) so the Layout Editor preview still renders.
        val approxDpi = metrics.density * 160f
        return if (approxDpi > 0f) approxDpi / MM_PER_INCH else 4f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gridDirty = true
        rebuildGridIfPossible()
    }

    private fun rebuildGridIfPossible() {
        if (width <= 0 || height <= 0) {
            gridDirty = true
            return
        }
        gridBitmap = buildGridBitmap(width, height)
        gridDirty = false
        invalidate()
    }

    private fun buildGridBitmap(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(paperColor)

        val pxPerMmX = mmScale.pxPerMmX
        val pxPerMmY = mmScale.pxPerMmY
        if (pxPerMmX <= 0f || pxPerMmY <= 0f) return bitmap

        val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = minorGridColor
            strokeWidth = minorStrokeMm * pxPerMmX
            style = Paint.Style.STROKE
        }
        val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = majorGridColor
            strokeWidth = majorStrokeMm * pxPerMmX
            style = Paint.Style.STROKE
        }

        val widthF = w.toFloat()
        val heightF = h.toFloat()
        val colCount = (widthF / pxPerMmX).toInt() + 1
        val rowCount = (heightF / pxPerMmY).toInt() + 1

        // Minor grid first (every 1mm line), major grid drawn on top at every
        // 5th line with a heavier stroke — origin + index * pxPerMm kept in
        // float throughout so no rounding error accumulates across lines.
        canvas.drawLines(verticalLines(colCount, pxPerMmX, heightF), minorPaint)
        canvas.drawLines(horizontalLines(rowCount, pxPerMmY, widthF), minorPaint)
        canvas.drawLines(verticalLines(colCount, pxPerMmX, heightF, step = MmScale.SMALL_SQUARES_PER_LARGE_SQUARE), majorPaint)
        canvas.drawLines(horizontalLines(rowCount, pxPerMmY, widthF, step = MmScale.SMALL_SQUARES_PER_LARGE_SQUARE), majorPaint)

        if (showTimeTicks) {
            drawTimeTicks(canvas, colCount, pxPerMmX, pxPerMmY, majorPaint)
        }
        if (showCalibrationPulse) {
            drawCalibrationPulse(canvas, heightF, majorPaint)
        }

        return bitmap
    }

    private fun verticalLines(count: Int, pxPerMm: Float, height: Float, step: Int = 1): FloatArray {
        val indices = 0 until count
        val selected = indices.filter { it % step == 0 }
        val pts = FloatArray(selected.size * 4)
        selected.forEachIndexed { pos, i ->
            val x = i * pxPerMm
            val o = pos * 4
            pts[o] = x; pts[o + 1] = 0f; pts[o + 2] = x; pts[o + 3] = height
        }
        return pts
    }

    private fun horizontalLines(count: Int, pxPerMm: Float, width: Float, step: Int = 1): FloatArray {
        val indices = 0 until count
        val selected = indices.filter { it % step == 0 }
        val pts = FloatArray(selected.size * 4)
        selected.forEachIndexed { pos, i ->
            val y = i * pxPerMm
            val o = pos * 4
            pts[o] = 0f; pts[o + 1] = y; pts[o + 2] = width; pts[o + 3] = y
        }
        return pts
    }

    private fun drawTimeTicks(canvas: Canvas, colCount: Int, pxPerMmX: Float, pxPerMmY: Float, paint: Paint) {
        val tickLenPx = TICK_LENGTH_MM * pxPerMmY
        val largeSquarePx = mmScale.largeSquarePxX()
        val tickSpacingPx = largeSquarePx * LARGE_SQUARES_PER_TIME_TICK
        var x = 0f
        val maxX = (colCount - 1) * pxPerMmX
        while (x <= maxX) {
            canvas.drawLine(x, 0f, x, tickLenPx, paint)
            x += tickSpacingPx
        }
    }

    private fun drawCalibrationPulse(canvas: Canvas, heightF: Float, paint: Paint) {
        val small = mmScale.smallSquarePxX()
        val pulseHeightPx = mmScale.mvToPx(1f)
        val baselineY = heightF / 2f
        val topY = baselineY - pulseHeightPx

        val leadInEnd = small * CALIBRATION_LEAD_IN_SMALL_SQUARES
        val riseX = leadInEnd
        val topEndX = riseX + small * CALIBRATION_WIDTH_SMALL_SQUARES
        val leadOutEndX = topEndX + small * CALIBRATION_LEAD_OUT_SMALL_SQUARES

        canvas.drawLine(0f, baselineY, leadInEnd, baselineY, paint)     // lead-in
        canvas.drawLine(riseX, baselineY, riseX, topY, paint)           // rise
        canvas.drawLine(riseX, topY, topEndX, topY, paint)              // top (1mV, 5 small squares wide)
        canvas.drawLine(topEndX, topY, topEndX, baselineY, paint)       // fall
        canvas.drawLine(topEndX, baselineY, leadOutEndX, baselineY, paint) // lead-out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridDirty) rebuildGridIfPossible()
        gridBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }
}
```

### 8.3 `res/values/attrs_ecg_paper.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <declare-styleable name="EcgPaperView">
        <attr name="ecgPaperSpeed" format="enum">
            <enum name="speed_12_5" value="0" />
            <enum name="speed_25" value="1" />
            <enum name="speed_50" value="2" />
        </attr>
        <attr name="ecgGain" format="enum">
            <enum name="gain_5" value="0" />
            <enum name="gain_10" value="1" />
            <enum name="gain_20" value="2" />
        </attr>
        <attr name="ecgTheme" format="enum">
            <enum name="paper" value="0" />
            <enum name="monitor" value="1" />
        </attr>
        <attr name="ecgShowCalibrationPulse" format="boolean" />
        <attr name="ecgShowTimeTicks" format="boolean" />
        <attr name="ecgPaperColor" format="color" />
        <attr name="ecgMinorGridColor" format="color" />
        <attr name="ecgMajorGridColor" format="color" />
    </declare-styleable>
</resources>
```

### 8.4 `ui/recording/TestRecordingFragment.kt` — full source

```kotlin
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
```

### 8.5 How it's wired into the layout — `fragment_test_recording.xml`

The `EcgPaperView` sits behind `waveformChart`, constrained to identical bounds — declared first
in the ConstraintLayout so it draws underneath:

```xml
<com.musediagnostics.taal.app.ecg.EcgPaperView
    android:id="@+id/ecgPaperBackground"
    android:layout_width="0dp"
    android:layout_height="0dp"
    android:layout_marginHorizontal="16dp"
    android:layout_marginVertical="16dp"
    app:layout_constraintBottom_toBottomOf="@id/waveformChart"
    app:layout_constraintEnd_toEndOf="@id/waveformChart"
    app:layout_constraintStart_toStartOf="@id/waveformChart"
    app:layout_constraintTop_toTopOf="@id/waveformChart" />

<com.github.mikephil.charting.charts.LineChart
    android:id="@+id/waveformChart"
    android:layout_width="match_parent"
    android:layout_height="0dp"
    android:layout_marginHorizontal="16dp"
    android:layout_marginVertical="16dp"
    app:layout_constraintBottom_toTopOf="@id/gainContainer"
    app:layout_constraintTop_toBottomOf="@id/filterRow" />
```

**Fragile point**: the two views' margins (`16dp`/`16dp`) are declared **separately** on each
view, not inherited — `EcgPaperView` is constrained to `waveformChart`'s edges, but its own
margins must be kept in sync by hand. Change one view's margin without the other and the paper
grid will no longer line up exactly behind the chart's plot area. (Calibrated's
`CalibratedWaveformView`, §10, solves this properly by stacking both views inside one
`FrameLayout` at identical `(0,0,0,0)` bounds instead of separate XML margins — see §10.3.)

**If you ever add `EcgPaperView` behind the production Recording/Player charts (A/B), you must
copy the "turn off the chart's own grid" step too** (`setDrawGridBackground(false)`,
`xAxis.setDrawGridLines(false)`, `axisLeft.setDrawGridLines(false)`) — otherwise you get two
overlapping, misaligned grids at once.

Verified: 9/9 `MmScaleTest.kt` unit tests pass; visually confirmed on a real device via a
throwaway self-instrumenting `androidTest` harness (not through any app screen, precisely because
`TestRecordingFragment` isn't reachable from the UI).

---

## 9. Screen D — `TestPlayerFragment.kt` (dormant, only reachable from C)

A *third* independent player implementation. No `EcgPaperView` — still MPAndroidChart's own
`#F0F0F0` grid. **Hardcodes `INPUT_SAMPLE_RATE = 44100f`** — does not read the WAV header's real
rate the way production `PlayerFragment` does; an 8kHz test file would display at the wrong
duration here (a real, previously-undocumented divergence). Fixed Y-axis `-1f`/`1f`. No
visible-range lock (`Float.MAX_VALUE`, effectively unbounded). Trace color `#128CB2` (teal,
matches this screen's other accents), width 1.0f.

**File**: `app/src/main/java/com/musediagnostics/taal/app/ui/player/TestPlayerFragment.kt`

```kotlin
package com.musediagnostics.taal.app.ui.player

//THIS IS NOT IN USE (ONLY TESTING BY KUNAL)

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
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.dsp.AudioFilterEngine
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentTestPlayerBinding
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TestPlayerFragment : Fragment() {

    private var _binding: FragmentTestPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    companion object {
        const val INPUT_SAMPLE_RATE = 44100f
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTestPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val isNewRecording = arguments?.getBoolean("isNewRecording", false) ?: false

        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()
        setupConnectionReceiver()
        setupFilters()

        if (filePath.isNotEmpty()) {
            loadFullWaveform(filePath)
            setupPlayer(filePath)
        }

        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.playButton.setOnClickListener { togglePlayback() }

        binding.saveButton.setOnClickListener {
            val bundle = Bundle().apply { putString("recordingFilePath", filePath) }
            findNavController().navigate(R.id.action_testPlayer_to_addPatient, bundle)
        }

        binding.discardButton.setOnClickListener {
            try { File(filePath).delete() } catch (_: Exception) {}
            findNavController().navigateUp()
        }
    }

    private fun setupFilters() {
        val filters = mapOf(
            binding.filterHeart to AudioFilterEngine.PresetFilter.HEART,
            binding.filterLungs to AudioFilterEngine.PresetFilter.LUNGS,
            binding.filterBowel to AudioFilterEngine.PresetFilter.BOWEL,
            binding.filterPregnancy to AudioFilterEngine.PresetFilter.PREGNANCY,
            binding.filterInfo to AudioFilterEngine.PresetFilter.FULL_BODY
        )

        // Pre-select Heart
        updateRangeSlider(AudioFilterEngine.PresetFilter.HEART)
        binding.filterHeart.setColorFilter(Color.parseColor("#F44336"))

        filters.forEach { (btn, preset) ->
            btn.setOnClickListener {
                filters.keys.forEach { it.setColorFilter(Color.GRAY) }
                btn.setColorFilter(Color.parseColor("#F44336"))
                updateRangeSlider(preset)
            }
        }
    }

    private fun updateRangeSlider(preset: AudioFilterEngine.PresetFilter) {
        val low = preset.lowCut.toFloat()
        val high = preset.highCut.toFloat()

        binding.hzRangeSlider.values = listOf(low, high)
        binding.hzMinText.text = "${low.toInt()} Hz"
        binding.hzMaxText.text = "${high.toInt()} Hz"
    }

    private fun setupConnectionReceiver() {
        connectionReceiver = TaalConnectionBroadcastReceiver(object : TaalConnectionBroadcastReceiver.TaalConnectionListener {
            override fun onTaalConnect() {
                activity?.runOnUiThread { binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2")) }
            }
            override fun onTaalDisconnect() {
                activity?.runOnUiThread { binding.deviceIcon.setColorFilter(Color.parseColor("#333333")) }
            }
        })
        connectionReceiver?.register(requireContext())
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
            axisMinimum = -1f
            axisMaximum = 1f
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
                val sampleStep = maxOf(1, totalSamples / 3000)

                val entries = ArrayList<Entry>()
                var sampleIndex = 0
                for (i in 44 until bytes.size - 1 step sampleStep * 2) {
                    val low = bytes[i].toInt() and 0xFF
                    val high = bytes[i + 1].toInt() shl 8
                    val sample = (high or low).toShort().toFloat() / 32768f
                    entries.add(Entry((sampleIndex.toFloat() / INPUT_SAMPLE_RATE), sample))
                    sampleIndex += sampleStep
                }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    val dataSet = LineDataSet(entries, "Waveform").apply {
                        color = Color.parseColor("#128CB2")
                        setDrawCircles(false)
                        setDrawValues(false)
                        lineWidth = 1.0f
                        mode = LineDataSet.Mode.LINEAR
                    }
                    binding.waveformChart.apply {
                        data = LineData(dataSet)
                        setVisibleXRangeMaximum(Float.MAX_VALUE)
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
        player = TaalPlayer(requireContext()).apply {
            setDataSource(filePath)
            onPlaybackProgress = { timestamp, _ ->
                activity?.runOnUiThread {
                    val totalSecs = timestamp.toInt()
                    binding.timerText.text = String.format("%02d:%02d", totalSecs / 60, totalSecs % 60)
                }
            }
            onPlaybackComplete = {
                activity?.runOnUiThread {
                    isPlaying = false
                    binding.actionText.text = "Play Recording"
                    binding.playButton.setImageResource(R.drawable.ic_play)
                }
            }
        }
    }

    private fun togglePlayback() {
        if (isPlaying) {
            player?.stop()
            isPlaying = false
            binding.actionText.text = "Play Recording"
            binding.playButton.setImageResource(R.drawable.ic_play)
        } else {
            player?.prepare()
            player?.start()
            isPlaying = true
            binding.actionText.text = "Stop Recording"
            binding.playButton.setImageResource(R.drawable.ic_stop)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.stop()
        player?.release()
        connectionReceiver?.let { it.unregister(requireContext()) }
        _binding = null
    }
}
```

---

## 10. Screen E — Calibrated Recorder/Player (built 2026-08-18/19/20, real, tested)

**What it is**: replicas of production Recording/Player (same filters, pre-amp, dual-file
output, save/discard) but drawing on a **physically accurate ECG-paper grid** — a real
millimetre on screen is a real millimetre, at a real clinical paper speed. Built as a fully
additive port; nothing in production A/B, or in screen C/D's `EcgPaperView`/`MmScale`, was
touched (verified via `git diff --stat` after every change — zero diff on those files throughout
this whole feature's development).

**Files**:
```
app/src/main/java/com/musediagnostics/taal/app/ecg/calibrated/
    CalibratedMmScale.kt
    CalibratedEcgPaperView.kt
    CalibratedWaveformView.kt
    DpiCalibration.kt
    GraphCalibration.kt

app/src/main/java/com/musediagnostics/taal/app/ui/calibrated/
    CalibratedRecordingFragment.kt
    CalibratedRecordingViewModel.kt
    CalibratedPlayerFragment.kt
    DpiCalibrationFragment.kt
```

**Two separate calibration systems — do not confuse them**:
- **`DpiCalibration`** — physical accuracy. OEM-reported DPI is often wrong, so
  `DpiCalibrationFragment` draws two nominally-50mm bars; the user measures both with a real
  ruler and enters the actual length; the ratio is stored (`SharedPreferences`,
  `calibrated_dpi_prefs`) as a multiplicative correction applied via
  `CalibratedEcgPaperView.setPixelsPerMm`.
- **`GraphCalibration`** — visual preference, added 2026-08-20. Pinch-zoom the Calibrated Player
  (X for time, Y for peak size), then press **Apply**: saves `visibleSeconds`/`yFullScale` to
  `SharedPreferences` (`calibrated_graph_prefs`), read by both Calibrated Recorder and Player at
  chart setup, substituting it for their built-in defaults. Deliberately persists
  `visibleSeconds`/`yFullScale` (physical, file-independent quantities), not raw
  `scaleX`/`scaleY` (which are relative to whatever file happens to be loaded — confirmed via
  live testing that the same `scaleX` meant a different number of visible seconds on a
  different-length file).

### 10.1 `ecg/calibrated/CalibratedMmScale.kt`

```kotlin
package com.musediagnostics.taal.app.ecg.calibrated

/**
 * Copy of [com.musediagnostics.taal.app.ecg.PaperSpeed], forked so the calibrated screens
 * can evolve independently of the dormant test-screen grid (see ecg/MmScale.kt — protected,
 * not modified). The mm/s value is the only thing that changes what a grid square *means* in
 * time — it never changes a square's physical 1mm size on screen.
 */
enum class CalibratedPaperSpeed(val mmPerSecond: Float) {
    SPEED_12_5(12.5f),
    SPEED_25(25f),
    SPEED_50(50f)
}

/**
 * Copy of [com.musediagnostics.taal.app.ecg.Gain]. The mm/mV value is the only thing that
 * changes what a grid square *means* in voltage — it never changes a square's physical 1mm
 * size on screen. Kept here purely for grid geometry math — the calibrated screens hide the
 * calibration pulse and never expose this in the UI (Y-axis is "relative amplitude", not mV).
 */
enum class CalibratedGain(val mmPerMv: Float) {
    GAIN_5(5f),
    GAIN_10(10f),
    GAIN_20(20f)
}

/**
 * Fork of [com.musediagnostics.taal.app.ecg.MmScale] for the calibrated recorder/player
 * screens. Pure Kotlin, no Android View/Canvas dependency, so it's plain-JVM unit-testable.
 *
 * Grid geometry (small/large square size in px) depends only on [pxPerMmX]/[pxPerMmY].
 * [paperSpeed] and [gain] affect only [secondsToPx]/[mvToPx] and their inverses — changing
 * either never resizes the grid. This is the load-bearing invariant the unit tests guard.
 *
 * Adds [visibleSeconds] on top of the original: the number of seconds that fit across a
 * given plot width at the current paper speed. This is the fix for the root defect in the
 * dormant test screens — the visible window must be *derived* from the physical grid, not
 * imposed on it via a fixed WINDOW_SECONDS constant.
 */
class CalibratedMmScale(
    var pxPerMmX: Float,
    var pxPerMmY: Float,
    var paperSpeed: CalibratedPaperSpeed = CalibratedPaperSpeed.SPEED_25,
    var gain: CalibratedGain = CalibratedGain.GAIN_10
) {
    companion object {
        const val MM_PER_SMALL_SQUARE = 1f
        const val SMALL_SQUARES_PER_LARGE_SQUARE = 5
        const val MM_PER_LARGE_SQUARE = MM_PER_SMALL_SQUARE * SMALL_SQUARES_PER_LARGE_SQUARE
    }

    // --- time <-> horizontal px ---
    fun secondsToPx(seconds: Float): Float = seconds * paperSpeed.mmPerSecond * pxPerMmX
    fun pxToSeconds(px: Float): Float = px / (paperSpeed.mmPerSecond * pxPerMmX)

    // --- voltage <-> vertical px ---
    fun mvToPx(mv: Float): Float = mv * gain.mmPerMv * pxPerMmY
    fun pxToMv(px: Float): Float = px / (gain.mmPerMv * pxPerMmY)

    // --- grid geometry: physical mm only, never affected by speed/gain ---
    fun smallSquarePxX(): Float = MM_PER_SMALL_SQUARE * pxPerMmX
    fun smallSquarePxY(): Float = MM_PER_SMALL_SQUARE * pxPerMmY
    fun largeSquarePxX(): Float = MM_PER_LARGE_SQUARE * pxPerMmX
    fun largeSquarePxY(): Float = MM_PER_LARGE_SQUARE * pxPerMmY

    // --- what a square currently means, at the current speed/gain ---
    fun secondsPerSmallSquare(): Float = MM_PER_SMALL_SQUARE / paperSpeed.mmPerSecond
    fun secondsPerLargeSquare(): Float = MM_PER_LARGE_SQUARE / paperSpeed.mmPerSecond
    fun mvPerSmallSquare(): Float = MM_PER_SMALL_SQUARE / gain.mmPerMv
    fun mvPerLargeSquare(): Float = MM_PER_LARGE_SQUARE / gain.mmPerMv

    /**
     * The number of seconds of signal that fit across [plotWidthPx] at the current paper
     * speed and pxPerMmX. This is what the visible chart window must be derived from — never
     * a fixed constant like the dormant test screens' WINDOW_SECONDS. At 25mm/s on a typical
     * phone this comes out to roughly 3-4s, not 10s; that's correct, it's what a real ECG
     * strip of that physical width would show.
     */
    fun visibleSeconds(plotWidthPx: Float): Float = pxToSeconds(plotWidthPx)
}
```

### 10.2 `ecg/calibrated/CalibratedEcgPaperView.kt`

**Note**: this is the file that had drifted furthest from its own handoff doc by the time this
was written — it has since gained a pure-white paper background (was `#FFF8F5`) and a
Kardia-style alpha/pixel-snap grid tuning system. What's below is the **verified live source**,
read directly from disk, not copied from another doc.

```kotlin
package com.musediagnostics.taal.app.ecg.calibrated

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.musediagnostics.taal.app.R
import kotlin.math.roundToInt

enum class CalibratedEcgTheme { PAPER, MONITOR }

/**
 * Fork of [com.musediagnostics.taal.app.ecg.EcgPaperView] (protected, not modified) for the
 * calibrated recorder/player screens. Draws a millimetre-accurate minor/major grid (1mm/5mm
 * squares), with optional 3-second tick marks and a calibration pulse, cached to a Bitmap and
 * blitted in [onDraw]. Grid only — never a trace; a chart sits on top of it inside
 * CalibratedWaveformView.
 *
 * All unit math lives in [CalibratedMmScale] so it can be reused by a trace renderer.
 */
class CalibratedEcgPaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MM_PER_INCH = 25.4f
        private const val LARGE_SQUARES_PER_TIME_TICK = 15
        private const val CALIBRATION_LEAD_IN_SMALL_SQUARES = 1
        private const val CALIBRATION_WIDTH_SMALL_SQUARES = 5
        private const val CALIBRATION_LEAD_OUT_SMALL_SQUARES = 1
        private const val TICK_LENGTH_MM = 4f

        // Classic paper theme (exact values as specified)
        // PAPER_BG lightened to pure white (user request) — was #FFF8F5, a warm off-white.
        private const val PAPER_BG = "#FFFFFF"
        private const val PAPER_MINOR = "#F2B8B5"
        private const val PAPER_MAJOR = "#E0837F"
        private const val PAPER_MINOR_STROKE_MM = 0.1f
        private const val PAPER_MAJOR_STROKE_MM = 0.3f

        // Monitor theme: near-black paper, dim green grid
        private const val MONITOR_BG = "#05100A"
        private const val MONITOR_MINOR = "#123D22"
        private const val MONITOR_MAJOR = "#1F7A45"
        private const val MONITOR_MINOR_STROKE_MM = 0.1f
        private const val MONITOR_MAJOR_STROKE_MM = 0.3f
    }

    private var pxPerMmXOverride: Float? = null
    private var pxPerMmYOverride: Float? = null

    private val mmScale: CalibratedMmScale = CalibratedMmScale(
        pxPerMmX = computeDefaultPxPerMmX(),
        pxPerMmY = computeDefaultPxPerMmY()
    )

    var theme: CalibratedEcgTheme = CalibratedEcgTheme.PAPER
        set(value) { field = value; applyThemeDefaults(); rebuildGridIfPossible() }

    var showCalibrationPulse: Boolean = true
        set(value) { field = value; rebuildGridIfPossible() }

    var showTimeTicks: Boolean = true
        set(value) { field = value; rebuildGridIfPossible() }

    private var paperColor: Int = Color.parseColor(PAPER_BG)
    private var minorGridColor: Int = Color.parseColor(PAPER_MINOR)
    private var majorGridColor: Int = Color.parseColor(PAPER_MAJOR)
    private var minorStrokeMm: Float = PAPER_MINOR_STROKE_MM
    private var majorStrokeMm: Float = PAPER_MAJOR_STROKE_MM

    // Fix C — Kardia-style grid. Measured Kardia's own grid at Δ96/255 per-line contrast but
    // only 0.7% total ink coverage of the plot area, vs. this view's earlier 7.6% at a more
    // uniform fade. The finding is counter-intuitive: Kardia's lines are individually *higher*
    // contrast, not lower — its minors are nearly invisible and only the 5mm majors read as
    // ink, so the aggregate ink area stays tiny. A uniform alpha reduction can't reproduce
    // that; the minor/major split has to be aggressive. Applied to the minor/major PAINTS
    // only (never to paperColor or View.alpha — those would let whatever sits behind this
    // view bleed through and turn the paper fill grey).
    var minorGridAlpha: Float = 0.20f
        set(value) { field = value.coerceIn(0f, 1f); rebuildGridIfPossible() }
    var majorGridAlpha: Float = 0.50f
        set(value) { field = value.coerceIn(0f, 1f); rebuildGridIfPossible() }

    private var explicitPaperColor: Int? = null
    private var explicitMinorColor: Int? = null
    private var explicitMajorColor: Int? = null

    private var gridBitmap: Bitmap? = null
    private var gridDirty = true

    init {
        attrs?.let { readAttrs(it, defStyleAttr) } ?: applyThemeDefaults()
    }

    private fun readAttrs(attrs: AttributeSet, defStyleAttr: Int) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.CalibratedEcgPaperView, defStyleAttr, 0)
        try {
            theme = when (ta.getInt(R.styleable.CalibratedEcgPaperView_calEcgTheme, 0)) {
                1 -> CalibratedEcgTheme.MONITOR
                else -> CalibratedEcgTheme.PAPER
            }
            mmScale.paperSpeed = when (ta.getInt(R.styleable.CalibratedEcgPaperView_calEcgPaperSpeed, 1)) {
                0 -> CalibratedPaperSpeed.SPEED_12_5
                2 -> CalibratedPaperSpeed.SPEED_50
                else -> CalibratedPaperSpeed.SPEED_25
            }
            mmScale.gain = when (ta.getInt(R.styleable.CalibratedEcgPaperView_calEcgGain, 1)) {
                0 -> CalibratedGain.GAIN_5
                2 -> CalibratedGain.GAIN_20
                else -> CalibratedGain.GAIN_10
            }
            showCalibrationPulse = ta.getBoolean(R.styleable.CalibratedEcgPaperView_calEcgShowCalibrationPulse, true)
            showTimeTicks = ta.getBoolean(R.styleable.CalibratedEcgPaperView_calEcgShowTimeTicks, true)
            minorGridAlpha = ta.getFloat(R.styleable.CalibratedEcgPaperView_calEcgGridAlphaMinor, minorGridAlpha)
            majorGridAlpha = ta.getFloat(R.styleable.CalibratedEcgPaperView_calEcgGridAlphaMajor, majorGridAlpha)

            applyThemeDefaults()

            if (ta.hasValue(R.styleable.CalibratedEcgPaperView_calEcgPaperColor)) {
                explicitPaperColor = ta.getColor(R.styleable.CalibratedEcgPaperView_calEcgPaperColor, paperColor)
            }
            if (ta.hasValue(R.styleable.CalibratedEcgPaperView_calEcgMinorGridColor)) {
                explicitMinorColor = ta.getColor(R.styleable.CalibratedEcgPaperView_calEcgMinorGridColor, minorGridColor)
            }
            if (ta.hasValue(R.styleable.CalibratedEcgPaperView_calEcgMajorGridColor)) {
                explicitMajorColor = ta.getColor(R.styleable.CalibratedEcgPaperView_calEcgMajorGridColor, majorGridColor)
            }
            applyColorOverrides()
        } finally {
            ta.recycle()
        }
    }

    private fun applyThemeDefaults() {
        when (theme) {
            CalibratedEcgTheme.PAPER -> {
                paperColor = Color.parseColor(PAPER_BG)
                minorGridColor = Color.parseColor(PAPER_MINOR)
                majorGridColor = Color.parseColor(PAPER_MAJOR)
                minorStrokeMm = PAPER_MINOR_STROKE_MM
                majorStrokeMm = PAPER_MAJOR_STROKE_MM
            }
            CalibratedEcgTheme.MONITOR -> {
                paperColor = Color.parseColor(MONITOR_BG)
                minorGridColor = Color.parseColor(MONITOR_MINOR)
                majorGridColor = Color.parseColor(MONITOR_MAJOR)
                minorStrokeMm = MONITOR_MINOR_STROKE_MM
                majorStrokeMm = MONITOR_MAJOR_STROKE_MM
            }
        }
        applyColorOverrides()
    }

    private fun applyColorOverrides() {
        explicitPaperColor?.let { paperColor = it }
        explicitMinorColor?.let { minorGridColor = it }
        explicitMajorColor?.let { majorGridColor = it }
    }

    /** Overrides OEM-reported DPI, since it's often wrong. Pass physical pixels-per-mm. */
    fun setPixelsPerMm(x: Float, y: Float) {
        pxPerMmXOverride = x
        pxPerMmYOverride = y
        mmScale.pxPerMmX = x
        mmScale.pxPerMmY = y
        rebuildGridIfPossible()
    }

    fun setPaperSpeed(speed: CalibratedPaperSpeed) {
        mmScale.paperSpeed = speed
        rebuildGridIfPossible()
    }

    fun setGain(gain: CalibratedGain) {
        mmScale.gain = gain
        rebuildGridIfPossible()
    }

    /** Read-only access to the current scale, e.g. for a trace renderer or window-derivation. */
    fun currentScale(): CalibratedMmScale = mmScale

    private fun computeDefaultPxPerMmX(): Float {
        val metrics = resources.displayMetrics
        if (isInEditMode && metrics.xdpi <= 0f) return fallbackPxPerMm(metrics)
        val value = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1f, metrics)
        return if (value > 0f && !value.isNaN()) value else fallbackPxPerMm(metrics)
    }

    private fun computeDefaultPxPerMmY(): Float {
        val metrics = resources.displayMetrics
        if (isInEditMode && metrics.ydpi <= 0f) return fallbackPxPerMm(metrics)
        val value = metrics.ydpi / MM_PER_INCH
        return if (value > 0f && !value.isNaN()) value else fallbackPxPerMm(metrics)
    }

    private fun fallbackPxPerMm(metrics: android.util.DisplayMetrics): Float {
        val approxDpi = metrics.density * 160f
        return if (approxDpi > 0f) approxDpi / MM_PER_INCH else 4f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gridDirty = true
        rebuildGridIfPossible()
    }

    private fun rebuildGridIfPossible() {
        if (width <= 0 || height <= 0) {
            gridDirty = true
            return
        }
        gridBitmap = buildGridBitmap(width, height)
        gridDirty = false
        invalidate()
    }

    private fun buildGridBitmap(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(paperColor)

        val pxPerMmX = mmScale.pxPerMmX
        val pxPerMmY = mmScale.pxPerMmY
        if (pxPerMmX <= 0f || pxPerMmY <= 0f) return bitmap

        val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = minorGridColor
            alpha = (minorGridAlpha * 255f).roundToInt().coerceIn(0, 255)
            strokeWidth = minorStrokeMm * pxPerMmX
            style = Paint.Style.STROKE
        }
        val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = majorGridColor
            alpha = (majorGridAlpha * 255f).roundToInt().coerceIn(0, 255)
            strokeWidth = majorStrokeMm * pxPerMmX
            style = Paint.Style.STROKE
        }

        val widthF = w.toFloat()
        val heightF = h.toFloat()
        val colCount = (widthF / pxPerMmX).toInt() + 1
        val rowCount = (heightF / pxPerMmY).toInt() + 1

        canvas.drawLines(verticalLines(colCount, pxPerMmX, heightF), minorPaint)
        canvas.drawLines(horizontalLines(rowCount, pxPerMmY, widthF), minorPaint)
        canvas.drawLines(verticalLines(colCount, pxPerMmX, heightF, step = CalibratedMmScale.SMALL_SQUARES_PER_LARGE_SQUARE), majorPaint)
        canvas.drawLines(horizontalLines(rowCount, pxPerMmY, widthF, step = CalibratedMmScale.SMALL_SQUARES_PER_LARGE_SQUARE), majorPaint)

        if (showTimeTicks) {
            drawTimeTicks(canvas, colCount, pxPerMmX, pxPerMmY, majorPaint)
        }
        if (showCalibrationPulse) {
            drawCalibrationPulse(canvas, heightF, majorPaint)
        }

        return bitmap
    }

    private fun verticalLines(count: Int, pxPerMm: Float, height: Float, step: Int = 1): FloatArray {
        val indices = 0 until count
        val selected = indices.filter { it % step == 0 }
        val pts = FloatArray(selected.size * 4)
        selected.forEachIndexed { pos, i ->
            // Fix C — snap to a whole pixel. At a hairline stroke width, a line sitting between
            // two pixels gets antialiased across both and reads as soft/fat instead of crisp.
            val x = (i * pxPerMm).roundToInt().toFloat()
            val o = pos * 4
            pts[o] = x; pts[o + 1] = 0f; pts[o + 2] = x; pts[o + 3] = height
        }
        return pts
    }

    private fun horizontalLines(count: Int, pxPerMm: Float, width: Float, step: Int = 1): FloatArray {
        val indices = 0 until count
        val selected = indices.filter { it % step == 0 }
        val pts = FloatArray(selected.size * 4)
        selected.forEachIndexed { pos, i ->
            val y = (i * pxPerMm).roundToInt().toFloat()
            val o = pos * 4
            pts[o] = 0f; pts[o + 1] = y; pts[o + 2] = width; pts[o + 3] = y
        }
        return pts
    }

    private fun drawTimeTicks(canvas: Canvas, colCount: Int, pxPerMmX: Float, pxPerMmY: Float, paint: Paint) {
        val tickLenPx = TICK_LENGTH_MM * pxPerMmY
        val largeSquarePx = mmScale.largeSquarePxX()
        val tickSpacingPx = largeSquarePx * LARGE_SQUARES_PER_TIME_TICK
        var x = 0f
        val maxX = (colCount - 1) * pxPerMmX
        while (x <= maxX) {
            canvas.drawLine(x, 0f, x, tickLenPx, paint)
            x += tickSpacingPx
        }
    }

    private fun drawCalibrationPulse(canvas: Canvas, heightF: Float, paint: Paint) {
        val small = mmScale.smallSquarePxX()
        val pulseHeightPx = mmScale.mvToPx(1f)
        val baselineY = heightF / 2f
        val topY = baselineY - pulseHeightPx

        val leadInEnd = small * CALIBRATION_LEAD_IN_SMALL_SQUARES
        val riseX = leadInEnd
        val topEndX = riseX + small * CALIBRATION_WIDTH_SMALL_SQUARES
        val leadOutEndX = topEndX + small * CALIBRATION_LEAD_OUT_SMALL_SQUARES

        canvas.drawLine(0f, baselineY, leadInEnd, baselineY, paint)
        canvas.drawLine(riseX, baselineY, riseX, topY, paint)
        canvas.drawLine(riseX, topY, topEndX, topY, paint)
        canvas.drawLine(topEndX, topY, topEndX, baselineY, paint)
        canvas.drawLine(topEndX, baselineY, leadOutEndX, baselineY, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridDirty) rebuildGridIfPossible()
        gridBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }
}
```

### 10.3 `ecg/calibrated/CalibratedWaveformView.kt` — the shared paper+chart container

```kotlin
package com.musediagnostics.taal.app.ecg.calibrated

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.FrameLayout
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shared paper+chart container used by BOTH CalibratedRecordingFragment and
 * CalibratedPlayerFragment (and later, FullTimeOnRecordingFragment/FullTimeOnPlayerFragment) —
 * the repo already has four diverged copies of "downsample audio -> plot on MPAndroidChart"
 * (see §5); this is deliberately not a fifth and sixth.
 *
 * Owns:
 *  - Stacking [paperView] (mm-accurate grid) behind [chart] (MPAndroidChart trace) at
 *    identical bounds, with the chart's own grid/background disabled and its viewport
 *    offsets zeroed so the chart's plot rect matches the view rect exactly.
 *  - Deriving the visible window in seconds from the grid whenever this view is
 *    sized/resized (rotation, split-screen, etc.), and pushing that onto the chart's
 *    visible-range lock.
 *  - Min/max bucket downsampling ([downsampleMinMax]), shared by both screens, so a sharp
 *    transient that falls between two decimated samples is never dropped.
 *
 * Camera control (page-snap vs. centered-follow), touch enable/disable, Y-axis range, and
 * dataset reuse are still the fragments' responsibility.
 */
class CalibratedWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val paperView: CalibratedEcgPaperView
    val chart: LineChart

    /** Fired whenever the derived visible-window duration changes (post-layout, or on resize/rotation). */
    var onVisibleSecondsChanged: ((Float) -> Unit)? = null

    private var lastVisibleSeconds: Float = -1f

    init {
        paperView = CalibratedEcgPaperView(context).apply {
            showCalibrationPulse = false // no mV calibration pulse on a relative-amplitude axis
        }
        addView(paperView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        chart = LineChart(context)
        configureChartDefaults(chart)
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * MPAndroidChart reserves internal viewport offsets even when axis labels/lines are
     * disabled, so the plot rectangle is inset from the view rectangle by a device-dependent
     * amount. Matching margins by hand (what fragment_test_recording.xml does for screen C)
     * cannot fix this — zeroing the offsets directly is the only reliable fix, which is why
     * paperView and chart are added here at identical (0,0,0,0) FrameLayout bounds rather than
     * via separately-declared XML margins on two sibling views.
     */
    private fun configureChartDefaults(chart: LineChart) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setViewPortOffsets(0f, 0f, 0f, 0f)
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.setDrawGridBackground(false)
        chart.setDrawBorders(false)
        chart.xAxis.setDrawGridLines(false)
        chart.xAxis.setDrawAxisLine(false)
        chart.xAxis.setDrawLabels(false)
        chart.axisLeft.setDrawGridLines(false)
        chart.axisLeft.setDrawAxisLine(false)
        chart.axisLeft.setDrawLabels(false)
        chart.axisRight.isEnabled = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeVisibleSeconds()
    }

    /**
     * The visible window is derived from the grid's physical width, never a fixed
     * WINDOW_SECONDS constant. Pushes the result onto the chart as a max-zoom-out cap and
     * notifies [onVisibleSecondsChanged] so callers can re-derive their page-snap boundaries.
     *
     * Deliberately caps only the *maximum* visible range here, not the minimum — a caller
     * that wants a hard-locked, unzoomable window (the recorder, which also disables touch
     * entirely) re-asserts setVisibleXRangeMinimum itself every frame; a caller that wants
     * pinch-zoom to work (the player) must not have this shared default fight it.
     */
    fun recomputeVisibleSeconds() {
        if (width <= 0) return
        val seconds = paperView.currentScale().visibleSeconds(width.toFloat())
        if (seconds > 0f && abs(seconds - lastVisibleSeconds) > 1e-4f) {
            lastVisibleSeconds = seconds
            chart.setVisibleXRangeMaximum(seconds)
            onVisibleSecondsChanged?.invoke(seconds)
        }
    }

    /** Best-effort synchronous read; may return a stale/default value before first layout. */
    fun currentVisibleSeconds(): Float =
        if (lastVisibleSeconds > 0f) lastVisibleSeconds
        else paperView.currentScale().visibleSeconds(width.toFloat().coerceAtLeast(1f))

    companion object {
        /**
         * Pure, additive helper — new symbol, not called by any existing code path (Calibrated
         * screens keep their own unchanged inline copy of this formula; only
         * FullTimeOnRecordingFragment's live-preview pipeline calls this). Added so the
         * page-based ring-trim bound used by a scrolling live trace (preview or recording) is
         * unit-testable without an Android runtime.
         *
         * Returns the minimum X (seconds) an entry must have to survive the trim — entries
         * older than one full window before the current page are dropped, bounding memory to
         * ~2 windows' worth of points indefinitely, however long the live session runs.
         */
        fun ringTrimMinX(latestX: Float, windowSeconds: Float): Float {
            if (windowSeconds <= 0f) return 0f
            val currentPage = (latestX / windowSeconds).toInt()
            val minXToKeep = (currentPage - 1) * windowSeconds
            return if (minXToKeep > 0f) minXToKeep else 0f
        }

        /**
         * Fix C — derives a min/max bucket size so roughly one bucket (2 points: min+max)
         * lands per horizontal pixel, instead of a fixed constant tuned for one specific paper
         * speed. A fixed bucket size stops matching pixel density the moment paper speed
         * changes — too small a bucket over-samples and the trace reads as a filled band
         * rather than a line; too large under-samples and looks jagged.
         *
         * Returns -1 if [pxPerMmX] (or the other inputs) aren't valid yet — callers must fall
         * back to a pre-tuned constant in that case rather than dividing by zero.
         */
        fun deriveBucketSize(sampleRate: Float, paperSpeedMmPerSecond: Float, pxPerMmX: Float): Int {
            if (sampleRate <= 0f || paperSpeedMmPerSecond <= 0f || pxPerMmX <= 0f) return -1
            val pixelsPerSecond = paperSpeedMmPerSecond * pxPerMmX
            if (pixelsPerSecond <= 0f) return -1
            return maxOf(1, (sampleRate / (pixelsPerSecond * 2f)).roundToInt())
        }

        /**
         * Fix D — variant of [deriveBucketSize] for the player's zoom-driven re-bucketing.
         * [deriveBucketSize] derives from paper speed, which only describes the load-time 1x
         * view; once the user pinch-zooms, the number of samples actually visible across the
         * plot width changes, and a bucket size still tuned for the 1x view either shows a
         * static-looking sawtooth (bucket far larger than what's now visible) or wastes work
         * (bucket far smaller). This derives directly from whatever is currently visible.
         *
         * Returns -1 if either input isn't valid (e.g. plot not laid out yet) — callers must
         * fall back rather than divide by zero.
         */
        fun deriveBucketSizeForVisibleRange(visibleSampleCount: Float, plotWidthPx: Float): Int {
            if (visibleSampleCount <= 0f || plotWidthPx <= 0f) return -1
            return maxOf(1, (visibleSampleCount / (plotWidthPx * 2f)).roundToInt())
        }

        /**
         * For each bucket of [bucketSize] input samples, emit the bucket's min and its
         * max (in true time order), instead of picking every Nth sample. Same point budget as
         * a straight decimation of the same density, but a sharp S1 transient lasting a few ms
         * can no longer fall entirely between two kept samples and vanish from the display.
         */
        fun downsampleMinMax(data: FloatArray, bucketSize: Int, xForIndex: (Int) -> Float): List<Entry> {
            if (data.isEmpty()) return emptyList()
            if (bucketSize <= 1) return data.indices.map { Entry(xForIndex(it), data[it]) }

            val entries = ArrayList<Entry>((data.size / bucketSize + 1) * 2)
            var i = 0
            while (i < data.size) {
                val end = minOf(i + bucketSize, data.size)
                var minIdx = i
                var maxIdx = i
                var minVal = data[i]
                var maxVal = data[i]
                for (j in i until end) {
                    val v = data[j]
                    if (v < minVal) { minVal = v; minIdx = j }
                    if (v > maxVal) { maxVal = v; maxIdx = j }
                }
                // Emit in ascending time order regardless of which extreme came first.
                if (minIdx <= maxIdx) {
                    entries.add(Entry(xForIndex(minIdx), minVal))
                    if (maxIdx != minIdx) entries.add(Entry(xForIndex(maxIdx), maxVal))
                } else {
                    entries.add(Entry(xForIndex(maxIdx), maxVal))
                    entries.add(Entry(xForIndex(minIdx), minVal))
                }
                i += bucketSize
            }
            return entries
        }
    }
}
```

### 10.4 `ecg/calibrated/DpiCalibration.kt`

```kotlin
package com.musediagnostics.taal.app.ecg.calibrated

import android.content.Context
import android.os.Build

/**
 * Persisted px-per-mm correction factors for [CalibratedEcgPaperView].
 *
 * `TypedValue.applyDimension(COMPLEX_UNIT_MM, ...)` / `displayMetrics.ydpi` depend on the
 * OEM-reported DPI, which is frequently wrong. Without correction the grid is precise but
 * not accurate — it looks authoritative and isn't. This class stores a multiplicative
 * correction (`correction = nominalMm / measuredMm`, from a physical-ruler measurement) that
 * [applyTo] multiplies onto the view's OEM-reported px-per-mm.
 *
 * Lookup order: (1) a hardcoded per-model table for known clinical tablets; (2) a manual
 * SharedPreferences calibration done via [com.musediagnostics.taal.app.ui.calibrated.DpiCalibrationFragment];
 * (3) default 1.0 (uncorrected) when neither is present.
 */
object DpiCalibration {
    private const val PREFS_NAME = "calibrated_dpi_prefs"
    private const val KEY_CORRECTION_X = "correction_x"
    private const val KEY_CORRECTION_Y = "correction_y"
    private const val KEY_IS_CALIBRATED = "is_calibrated"

    /**
     * Per-model correction factors (correctionX, correctionY) for devices already measured
     * in the field with a physical ruler. Empty until a model has actually been ruler-tested —
     * do not guess values here.
     */
    private val KNOWN_DEVICE_CORRECTIONS: Map<String, Pair<Float, Float>> = emptyMap()

    data class Correction(
        val x: Float,
        val y: Float,
        val isCalibrated: Boolean,
        val source: String // "device-table" | "manual" | "uncalibrated"
    )

    fun getCorrection(context: Context): Correction {
        KNOWN_DEVICE_CORRECTIONS[Build.MODEL]?.let { (x, y) ->
            return Correction(x, y, isCalibrated = true, source = "device-table")
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val calibrated = prefs.getBoolean(KEY_IS_CALIBRATED, false)
        val x = prefs.getFloat(KEY_CORRECTION_X, 1.0f)
        val y = prefs.getFloat(KEY_CORRECTION_Y, 1.0f)
        return Correction(x, y, calibrated, if (calibrated) "manual" else "uncalibrated")
    }

    fun saveManualCorrection(context: Context, correctionX: Float, correctionY: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_CORRECTION_X, correctionX)
            .putFloat(KEY_CORRECTION_Y, correctionY)
            .putBoolean(KEY_IS_CALIBRATED, true)
            .apply()
    }

    fun clearManualCorrection(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_CORRECTION_X)
            .remove(KEY_CORRECTION_Y)
            .putBoolean(KEY_IS_CALIBRATED, false)
            .apply()
    }

    /**
     * Multiplies the view's current (OEM-reported) px-per-mm by the stored correction and
     * applies it via [CalibratedEcgPaperView.setPixelsPerMm]. Call once, after the view has
     * been constructed (so `currentScale()` holds the reported baseline) and before any other
     * manual override — calling it twice would compound the correction.
     */
    fun applyTo(view: CalibratedEcgPaperView, context: Context) {
        val reported = view.currentScale()
        val correction = getCorrection(context)
        view.setPixelsPerMm(
            reported.pxPerMmX * correction.x,
            reported.pxPerMmY * correction.y
        )
    }
}
```

### 10.5 `ui/calibrated/DpiCalibrationFragment.kt`

```kotlin
package com.musediagnostics.taal.app.ui.calibrated

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.databinding.FragmentDpiCalibrationBinding
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration

/**
 * px-per-mm calibration screen. Draws a horizontal and a vertical bar, each nominally
 * 50mm using the device's OEM-reported DPI. The user measures both with a physical ruler and
 * enters what it actually reads; the ratio (nominal / measured) is stored as a correction
 * factor and applied to both calibrated screens' grids via [DpiCalibration.applyTo].
 */
class DpiCalibrationFragment : Fragment() {

    private var _binding: FragmentDpiCalibrationBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val NOMINAL_MM = 50f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDpiCalibrationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateStatusText()

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        binding.saveCalibrationButton.setOnClickListener {
            val actualX = binding.measuredXInput.text?.toString()?.toFloatOrNull()
            val actualY = binding.measuredYInput.text?.toString()?.toFloatOrNull()

            if (actualX == null || actualX <= 0f || actualY == null || actualY <= 0f) {
                Toast.makeText(
                    requireContext(),
                    "Enter the measured length for both bars (mm, greater than 0).",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val correctionX = NOMINAL_MM / actualX
            val correctionY = NOMINAL_MM / actualY
            DpiCalibration.saveManualCorrection(requireContext(), correctionX, correctionY)
            updateStatusText()
            Toast.makeText(requireContext(), "Calibration saved.", Toast.LENGTH_SHORT).show()
        }

        binding.clearCalibrationButton.setOnClickListener {
            DpiCalibration.clearManualCorrection(requireContext())
            binding.measuredXInput.setText("")
            binding.measuredYInput.setText("")
            updateStatusText()
            Toast.makeText(requireContext(), "Calibration cleared — using default DPI.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatusText() {
        val correction = DpiCalibration.getCorrection(requireContext())
        binding.statusText.text = if (correction.isCalibrated) {
            "Calibrated (${correction.source}) — X correction ×%.4f, Y correction ×%.4f"
                .format(correction.x, correction.y)
        } else {
            "Not yet calibrated — grid uses the device's reported DPI as-is."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
```

### 10.6 `ecg/calibrated/GraphCalibration.kt`

```kotlin
package com.musediagnostics.taal.app.ecg.calibrated

import android.content.Context

/**
 * Persisted per-device override for the calibrated screens' default zoom/scale — "Peak Size"
 * and "Time Zoom", set from the Player's calibration panel and shared by both the Player and
 * the Recorder (so live recording and review keep matching sizes on that device).
 *
 * Same `SharedPreferences`-backed object pattern as [DpiCalibration], but a distinct, unrelated
 * concern — this is a *visual preference* (how big things look on this specific screen), not a
 * physical-accuracy correction like px-per-mm.
 *
 * We deliberately persist [Override.visibleSeconds] and [Override.yFullScale] — physical,
 * portable quantities read back directly from the chart — rather than MPAndroidChart's raw
 * pinch-zoom scale factors (`viewPortHandler.scaleX`/`scaleY`). Those scale factors are
 * relative to whatever file happens to be loaded at the time (confirmed via a live-tuning
 * session: the same `scaleX` value meant a different number of visible seconds depending on
 * file length), so persisting them and replaying them against a different recording later
 * would not reproduce the same visual result. `visibleSeconds`/`yFullScale` have no such
 * dependency — they mean the same thing regardless of which file is open.
 */
object GraphCalibration {
    private const val PREFS_NAME = "calibrated_graph_prefs"
    private const val KEY_HAS_OVERRIDE = "has_override"
    private const val KEY_VISIBLE_SECONDS = "visible_seconds"
    private const val KEY_Y_FULL_SCALE = "y_full_scale"

    data class Override(val visibleSeconds: Float, val yFullScale: Float)

    /** Null when no calibration has been applied on this device — callers fall back to their own built-in default. */
    fun getOverride(context: Context): Override? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_OVERRIDE, false)) return null
        val visibleSeconds = prefs.getFloat(KEY_VISIBLE_SECONDS, 0f)
        val yFullScale = prefs.getFloat(KEY_Y_FULL_SCALE, 0f)
        if (visibleSeconds <= 0f || yFullScale <= 0f) return null // corrupt/stale — ignore rather than crash
        return Override(visibleSeconds, yFullScale)
    }

    fun saveOverride(context: Context, visibleSeconds: Float, yFullScale: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_VISIBLE_SECONDS, visibleSeconds)
            .putFloat(KEY_Y_FULL_SCALE, yFullScale)
            .putBoolean(KEY_HAS_OVERRIDE, true)
            .apply()
    }

    fun clearOverride(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_VISIBLE_SECONDS)
            .remove(KEY_Y_FULL_SCALE)
            .putBoolean(KEY_HAS_OVERRIDE, false)
            .apply()
    }
}
```

### 10.7 `ui/calibrated/CalibratedRecordingViewModel.kt`

```kotlin
package com.musediagnostics.taal.app.ui.calibrated

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.musediagnostics.taal.app.TaalApplication
import com.musediagnostics.taal.app.data.repository.RecordingRepository

/**
 * Copy of [com.musediagnostics.taal.app.ui.recording.RecordingViewModel] (protected, not
 * modified), forked so CalibratedRecordingFragment can evolve independently. State shape is
 * identical — this is what keeps a future "swap the fragment class in nav_graph.xml" promotion
 * a one-line change.
 */
enum class CalibratedRecordingUiState {
    IDLE,       // Pre-recording
    RECORDING,  // Actively recording
    STOPPED     // Recording finished, showing save options
}

class CalibratedRecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as TaalApplication).database
    val recordingRepository = RecordingRepository(db.recordingDao())

    private val _uiState = MutableLiveData(CalibratedRecordingUiState.IDLE)
    val uiState: LiveData<CalibratedRecordingUiState> = _uiState

    private val _timerSeconds = MutableLiveData(0)
    val timerSeconds: LiveData<Int> = _timerSeconds

    private val _currentFilter = MutableLiveData("HEART")
    val currentFilter: LiveData<String> = _currentFilter

    private val _bpm = MutableLiveData(0)
    val bpm: LiveData<Int> = _bpm

    private val _preAmpDb = MutableLiveData(5)
    val preAmpDb: LiveData<Int> = _preAmpDb

    var currentRecordingPath: String = ""
    var currentFilteredPath: String = ""
    var customLowCut: Float? = null
    var customHighCut: Float? = null

    fun setUiState(state: CalibratedRecordingUiState) {
        _uiState.value = state
    }

    fun updateTimer(seconds: Int) {
        _timerSeconds.value = seconds
    }

    fun setFilter(filter: String) {
        _currentFilter.value = filter
    }

    fun setBpm(bpm: Int) {
        _bpm.value = bpm
    }

    fun setPreAmp(db: Int) {
        _preAmpDb.value = db.coerceIn(0, 30)
    }

    fun formatTimer(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
}
```

### 10.8 `ui/calibrated/CalibratedRecordingFragment.kt`

**Fix A** (reversing an earlier decision to restore production's adaptive warmup/peak Y-axis):
the axis is a single fixed full-scale, set once and never touched again for the rest of the
session. Frame-measurement of a real recording showed the adaptive scheme was the root cause of
"graph is unstable/noisy/too small" — a loud transient in the 2s warmup window (e.g. the
stethoscope contact thud) could permanently lock an oversized scale, and the axis could jump
mid-recording. A fixed axis trades per-recording optimality for stability — deliberately how
Kardia's own ECG display behaves (fixed 10mm/mV, never rescales).

```kotlin
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
import com.musediagnostics.taal.app.ecg.calibrated.GraphCalibration
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    // The visible window is derived from the grid's physical width, not a fixed
    // constant; this holds the most recently derived value (updated on layout/rotation).
    private var currentWindowSeconds = 4f

    // Fix C — derived so ~one min/max bucket lands per horizontal pixel at the current paper
    // speed, instead of a fixed constant. Recomputed only while idle and held frozen for the
    // whole recording session: the recorder mutates LineDataSet.values in place against a
    // monotonic sample-counter X axis, so changing the bucket size mid-recording would space
    // already-plotted points inconsistently with new ones.
    private var sessionBucketSize = DOWNSAMPLE_BUCKET_FALLBACK

    // Per-device calibration override (Peak Size / Time Zoom panel, set from the Player) —
    // null means "use the built-in FIXED_FULL_SCALE/grid-derived defaults." Re-read in
    // onResume() so returning from the Player after Apply/Reset reflects immediately.
    private var calibrationOverride: GraphCalibration.Override? = null

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f

        // Pre-layout fallback only (pxPerMmX not yet known).
        // 2 points per bucket (min+max) at this bucket size ≈ same point budget as production's
        // "1 in 44 samples" decimation (44100 / 88 * 2 ≈ 1002 pts/sec), but peaks are preserved.
        private const val DOWNSAMPLE_BUCKET_FALLBACK = 88

        // Fix A — fixed display full-scale, in normalized sample units (-1..+1). The axis is
        // ±FIXED_FULL_SCALE and NEVER changes during a session (no warmup, no re-expansion).
        // Do NOT use ±1.0 — typical PCG content peaks well below full digital scale.
        //
        // History: 0.30 -> 0.15 -> 0.013 (measured) -> 0.0065 -> 0.013 -> 0.30 (temp check) ->
        // 0.013 -> 0.30 -> 0.20 -> 0.10 (current — user: "a bit bigger" still). Deliberately NOT
        // re-optimized for any one device — per-device calibration (pinch + Apply, see
        // GraphCalibration) is what does that now; this is just the small, neutral default
        // every device starts from. Must stay identical to CalibratedPlayerFragment's copy.
        private const val FIXED_FULL_SCALE = 0.10f

        // The pre-amp slider was visibly resizing the live trace as it moved, which is wrong:
        // the slider should only change loudness. See onProgressUpdate's comment for why
        // this can't yet guarantee perfect live/review parity at non-default pre-amp.
        private const val COMPENSATE_PREAMP_IN_DISPLAY = true

        // Trace stroke width in dp. Bumped from production's 1.5dp — a fixed stroke width
        // stays the same physical thickness at any zoom level, but reads as relatively
        // thinner once FIXED_FULL_SCALE/pinch make the peaks bigger.
        private const val TRACE_LINE_WIDTH_DP = 2.0f
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

        // Fix A baseline, overridden below if this device has a saved calibration (set from
        // the Player's Peak Size / Time Zoom panel) — set once, never touched again for the
        // rest of the session outside onResume()'s re-read.
        calibrationOverride = GraphCalibration.getOverride(requireContext())
        val effectiveYFullScale = calibrationOverride?.yFullScale ?: FIXED_FULL_SCALE
        chart.axisLeft.axisMinimum = -effectiveYFullScale
        chart.axisLeft.axisMaximum = effectiveYFullScale

        // 50mm/s — the fastest standard clinical ECG speed. Was SPEED_25; a live-tuning
        // session showed the user pinch-zooming in on the Player's time axis until only
        // ~0.87s was visible (~83mm/s equivalent) before it looked right. Both screens must
        // be changed together or a recording looks different live vs. in review.
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_50)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        waveformView.onVisibleSecondsChanged = { seconds ->
            // A saved Time Zoom override replaces the grid-derived default outright.
            currentWindowSeconds = calibrationOverride?.visibleSeconds ?: seconds
            // Don't clobber an in-progress recording's dataset on rotation/resize — only
            // reset to the dummy grid-holder dataset when there's no real data yet.
            if (waveformDataSet == null) {
                // Fix C — only re-derive the bucket size while idle; a session in progress
                // must keep using whatever was frozen when it started.
                //
                // Derived from the *effective* window (currentWindowSeconds, which already
                // folds in any calibration override above), not from paper speed alone —
                // a saved override that narrows the window left the bucket sized for the
                // wider native view: each min/max pair stretched across several pixels
                // instead of one, which is what "noisy/jagged" turned out to be (same class
                // of bug Fix D already fixed for the Player's pinch-zoom).
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
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude (fixed) · DPI: $status"
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
                    putInt("preAmpDb", viewModel.preAmpDb.value ?: 5)
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

                        // Undo pre-amp gain before drawing (COMPENSATE_PREAMP_IN_DISPLAY, on by
                        // default) — the trace reflects true acoustic level, not the amplified
                        // WAV level. See that constant's doc for the tradeoff this re-opens
                        // with the player (which has no way to undo gain it doesn't know was
                        // applied to a given saved file).
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
                // So the Player can undo this recording's actual pre-amp gain and show the
                // same true-acoustic-level trace the recorder showed live.
                putInt("preAmpDb", viewModel.preAmpDb.value ?: 5)
            }
            findNavController().navigate(R.id.action_calibratedRecording_to_calibratedPlayer, bundle)
        }
    }

    /**
     * Calibrated counterpart of RecordingFragment's V7 updateWaveform(). Differences from
     * production:
     *  - X window (`currentWindowSeconds`) is derived from the grid, not a WINDOW_SECONDS
     *    constant.
     *  - Downsampling is min/max bucketed via CalibratedWaveformView, not "every Nth sample".
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

        // Re-read the calibration override in case the Player's panel changed it since this
        // fragment was created (e.g. Apply/Reset pressed there, then navigated back here).
        calibrationOverride = GraphCalibration.getOverride(requireContext())
        val chart = binding.calibratedWaveformView.chart
        val effectiveYFullScale = calibrationOverride?.yFullScale ?: FIXED_FULL_SCALE
        chart.axisLeft.axisMinimum = -effectiveYFullScale
        chart.axisLeft.axisMaximum = effectiveYFullScale
        // Re-fires onVisibleSecondsChanged, which now reads the refreshed override for X.
        binding.calibratedWaveformView.recomputeVisibleSeconds()
        chart.invalidate()

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
```

### 10.9 `ui/calibrated/CalibratedPlayerFragment.kt`

**Fix A/B**: Y-axis is `FIXED_FULL_SCALE`, the same constant `CalibratedRecordingFragment` uses,
set once and never touched again — no per-file peak adaptation. Two screens sharing one fixed
constant is what makes live and review render the same recording identically. **Fix D**: the
min/max bucket used to draw the trace is re-derived from the *currently visible* X range whenever
a pinch/drag gesture ends, not fixed at load time — frame-by-frame inspection during zoomed
playback had shown a regular synthetic sawtooth instead of a waveform.

```kotlin
package com.musediagnostics.taal.app.ui.calibrated

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentCalibratedPlayerBinding
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedPaperSpeed
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration
import com.musediagnostics.taal.app.ecg.calibrated.GraphCalibration
import com.musediagnostics.taal.app.ui.player.PlayerSaveDiscardDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CalibratedPlayerFragment : Fragment() {

    private var _binding: FragmentCalibratedPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    private var currentWindowSeconds = 4f

    // Smoothed camera-follow position during playback — see onPlaybackProgress. Reset to 0
    // wherever the chart snaps back to its start-centered framing.
    private var displayedPlaybackTime = 0f

    // Fix D — kept in memory for zoom-driven re-bucketing (rebucketForCurrentZoom). Never
    // re-read from disk after the initial load.
    private var decodedSamples: FloatArray? = null
    private var decodedSampleRate: Float = INPUT_SAMPLE_RATE
    private var waveformDataSet: LineDataSet? = null // persistent ref, mutated in place on re-bucket
    private var lastAppliedBucketSize = -1
    private var rebucketJob: Job? = null

    // Per-device calibration override (set by pinching the graph, then pressing applyButton) —
    // null means "use the built-in FIXED_FULL_SCALE/grid-derived defaults." Read once at setup,
    // kept in sync by the Apply/Reset handlers.
    private var calibrationOverride: GraphCalibration.Override? = null

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val TARGET_POINT_BUDGET = 3000 // same total-point budget as production's maxPoints
        // Fix A/B — must be kept identical to CalibratedRecordingFragment.FIXED_FULL_SCALE, or
        // the same recording renders at different scales live vs. in review.
        private const val FIXED_FULL_SCALE = 0.10f
        // Camera-follow smoothing (playback feels "too fast" when zoomed in). Fraction of the
        // remaining gap to the real playback position closed per progress callback — lower =
        // gentler/slower-feeling follow, 1.0 = instant snap (old behavior).
        private const val FOLLOW_SMOOTHING = 0.15f
        // Trace stroke width in dp. A fixed stroke width stays the same physical thickness at
        // any zoom level, but reads as relatively thinner once FIXED_FULL_SCALE/pinch make the
        // peaks bigger, so it needed to grow a bit too.
        private const val TRACE_LINE_WIDTH_DP = 3.0f

        // Floor used by forceVisibleSeconds() to relax the max-zoom-in bound back to
        // effectively unlimited after forcing an exact Time Zoom width.
        private const val MIN_VISIBLE_SECONDS = 0.3f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalibratedPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val isNewRecording = arguments?.getBoolean("isNewRecording", false) ?: false
        val filterName = arguments?.getString("filterName") ?: "HEART"
        // The dB the recorder actually used for this file, if known (0 = not passed / unknown).
        val recordedPreAmpDb = arguments?.getInt("preAmpDb", 0) ?: 0

        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()
        setupAmpSlider()
        setupGraphCalibrationPanel()
        updateCalibrationCaption()

        if (filePath.isNotEmpty()) {
            // Capture the scale on the main thread before loadFullWaveform's IO coroutine
            // reads it (Fix C) — setupWaveformChart() above already applied paper speed + DPI
            // correction synchronously, so this snapshot is final for the rest of this load.
            val scale = binding.calibratedWaveformView.paperView.currentScale()
            loadFullWaveform(filePath, filterName, scale.paperSpeed.mmPerSecond, scale.pxPerMmX, recordedPreAmpDb)
            setupPlayer(filePath, filterName)
        }

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.eqButton.setOnClickListener {
            val bundle = Bundle().apply { putString("filePath", filePath) }
            findNavController().navigate(R.id.action_calibratedPlayer_to_equalizer, bundle)
        }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }

        // Repurposed from the ported production Save button — this build isn't persisting
        // recordings, it's for tuning graph size on-device. Apply saves whatever pinch-zoom
        // state the graph is currently showing as this device's default and goes straight
        // back to the Recorder to see it applied live.
        binding.applyButton.setOnClickListener {
            val chart = binding.calibratedWaveformView.chart
            val effectiveVisibleSeconds = chart.highestVisibleX - chart.lowestVisibleX
            val effectiveYFullScale = currentEffectiveYFullScale()
            if (effectiveVisibleSeconds > 0f && effectiveYFullScale > 0f) {
                GraphCalibration.saveOverride(requireContext(), effectiveVisibleSeconds, effectiveYFullScale)
                calibrationOverride = GraphCalibration.Override(effectiveVisibleSeconds, effectiveYFullScale)
            }
            findNavController().navigateUp()
        }

        binding.discardButton.setOnClickListener {
            if (isNewRecording) {
                showDiscardConfirmation(filePath)
            } else {
                showSaveDiscardDialog(filePath)
            }
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
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart

        calibrationOverride = GraphCalibration.getOverride(requireContext())
        val effectiveYFullScale = calibrationOverride?.yFullScale ?: FIXED_FULL_SCALE
        chart.axisLeft.axisMinimum = -effectiveYFullScale
        chart.axisLeft.axisMaximum = effectiveYFullScale

        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_50)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        // Opposite of the recorder — user can pan/zoom to inspect the trace with two fingers,
        // in both directions: horizontal pinch for time (Time Zoom), vertical pinch for
        // peak height (Peak Size). MPAndroidChart scales Y via its own touch-matrix
        // (viewPortHandler.scaleY), a separate mechanism from axisMinimum/axisMaximum —
        // currentEffectiveYFullScale() folds the two together.
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleXEnabled(true)
        chart.setScaleYEnabled(true)

        // Fix D — re-bucket for the new zoom/pan level once the gesture settles. Debounced by
        // construction: onChartGestureEnd fires once per discrete gesture, not per frame.
        chart.setOnChartGestureListener(object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {
                rebucketForCurrentZoom()
            }
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) {}
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {}
        })

        waveformView.onVisibleSecondsChanged = { seconds ->
            currentWindowSeconds = calibrationOverride?.visibleSeconds ?: seconds
            if (chart.data == null) {
                resetToDummyData()
            } else {
                // Deliberately NOT setVisibleXRangeMinimum — that would lock the range to
                // exactly `seconds` and disable pinch-zoom entirely.
                chart.setVisibleXRangeMaximum(seconds)
                chart.centerViewTo(seconds / 2f, 0f, YAxis.AxisDependency.LEFT)
                chart.invalidate()
            }
        }
        waveformView.recomputeVisibleSeconds()
        if (chart.data == null) resetToDummyData()
    }

    private fun resetToDummyData() {
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
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude (fixed) · DPI: $status"
    }

    /**
     * Graph calibration is pinch-only — this just wires the status readout and Reset. Applying
     * a calibration happens via [R.id.applyButton] in the bottom bar, which reads whatever the
     * pinch-tuned view currently shows.
     */
    private fun setupGraphCalibrationPanel() {
        updateCalibrationStatusText()
        binding.resetCalibrationButton.setOnClickListener {
            GraphCalibration.clearOverride(requireContext())
            calibrationOverride = null
            applyBuiltInDefaultScale()
            updateCalibrationStatusText()
            Toast.makeText(requireContext(), "Reset to default", Toast.LENGTH_SHORT).show()
        }
    }

    /** Re-applies FIXED_FULL_SCALE and the grid-derived default window, bypassing any override — used by Reset. */
    private fun applyBuiltInDefaultScale() {
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart
        // Clears any pinch-driven X/Y viewport zoom before reapplying the built-in defaults —
        // otherwise a prior vertical pinch would still be layered on top of the reset axis
        // bounds and Reset wouldn't actually look reset.
        chart.fitScreen()
        chart.axisLeft.axisMinimum = -FIXED_FULL_SCALE
        chart.axisLeft.axisMaximum = FIXED_FULL_SCALE
        chart.notifyDataSetChanged()
        waveformView.recomputeVisibleSeconds()
        // onVisibleSecondsChanged only reapplies setVisibleXRangeMaximum (a zoom-OUT cap) — if
        // the user had pinched/slid to a *wider* view than the default before hitting Reset,
        // that alone wouldn't visually snap back. Force it.
        forceVisibleSeconds(currentWindowSeconds)
        rebucketForCurrentZoom()
        chart.invalidate()
    }

    /**
     * The Y full-scale actually being shown right now, folding together the fixed axis bounds
     * and any pinch-driven vertical zoom on top of them (chart.viewPortHandler.scaleY) —
     * mirrors how X already reads its true state via chart.highestVisibleX/lowestVisibleX.
     */
    private fun currentEffectiveYFullScale(): Float {
        val chart = binding.calibratedWaveformView.chart
        val scaleY = chart.viewPortHandler.scaleY.coerceAtLeast(0.01f)
        return chart.axisLeft.axisMaximum / scaleY
    }

    /**
     * Forces the chart to display exactly [seconds] of width right now, regardless of whether
     * that's narrower or wider than the current view. `setVisibleXRangeMaximum` alone only sets
     * a zoom-out ceiling — it can't widen an already-narrower view. Both bounds are pinned to
     * [seconds] momentarily, then the lower bound is relaxed back to effectively unlimited so
     * pinch-zoom-in still works after.
     */
    private fun forceVisibleSeconds(seconds: Float) {
        val chart = binding.calibratedWaveformView.chart
        chart.setVisibleXRangeMinimum(seconds)
        chart.setVisibleXRangeMaximum(seconds)
        chart.centerViewTo(seconds / 2f, 0f, YAxis.AxisDependency.LEFT)
        chart.setVisibleXRangeMinimum(MIN_VISIBLE_SECONDS * 0.1f)
        chart.invalidate()
    }

    private fun updateCalibrationStatusText() {
        if (_binding == null) return
        binding.graphCalibrationStatus.text = if (calibrationOverride != null) {
            "Calibrated on this device"
        } else {
            "Not calibrated on this device — using default"
        }
    }

    /**
     * Calibrated counterpart of PlayerFragment.loadFullWaveform(). Same WAV-header sample-rate
     * parsing, but downsampling is min/max-bucketed instead of "every step-th sample".
     *
     * [recordedPreAmpDb] undoes the same gain the recorder applied when this file was made. 0
     * means unknown/not passed — no compensation applied. Only works for files that arrived
     * with that bundle arg; a recording reopened later from the library still won't self-correct.
     */
    private fun loadFullWaveform(
        filePath: String, filterName: String, paperSpeedMmPerSecond: Float, pxPerMmX: Float, recordedPreAmpDb: Int
    ) {
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

            val samples = FloatArray(totalSamples)
            var i = 0
            while (i < totalSamples) {
                val bytePos = 44 + i * 2
                if (bytePos + 1 >= bytes.size) break
                val low = bytes[bytePos].toInt() and 0xFF
                val high = bytes[bytePos + 1].toInt() shl 8
                samples[i] = (high or low).toShort().toFloat() / 32768f
                i++
            }

            // Undo the recorder's pre-amp gain, before entries/bucket computation and before
            // decodedSamples is stored, so Fix D's zoom re-bucketing also re-buckets from the
            // compensated data.
            if (recordedPreAmpDb > 0) {
                val preAmpGain = Math.pow(10.0, recordedPreAmpDb / 20.0).toFloat()
                if (preAmpGain > 1.001f) {
                    for (j in samples.indices) samples[j] = samples[j] / preAmpGain
                }
            }

            // Fix C — derive so ~one min/max pair lands per horizontal pixel at the current
            // paper speed. Apply TARGET_POINT_BUDGET as a ceiling only.
            val derivedBucket = CalibratedWaveformView.deriveBucketSize(fileSampleRate, paperSpeedMmPerSecond, pxPerMmX)
            val budgetCeilingBucket = maxOf(1, totalSamples / (TARGET_POINT_BUDGET / 2))
            val bucketSize = if (derivedBucket > 0) maxOf(derivedBucket, budgetCeilingBucket) else budgetCeilingBucket
            val entries = CalibratedWaveformView.downsampleMinMax(samples, bucketSize) { idx ->
                idx.toFloat() / fileSampleRate
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                decodedSamples = samples
                decodedSampleRate = fileSampleRate
                lastAppliedBucketSize = bucketSize
                renderWaveformEntries(ArrayList(entries), durationSecs)
            }
        }
    }

    private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int) {
        binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
        val dataSet = LineDataSet(entries, "Waveform").apply {
            color = Color.parseColor("#2D7DD2")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = TRACE_LINE_WIDTH_DP
            mode = LineDataSet.Mode.LINEAR
        }
        waveformDataSet = dataSet
        val chart = binding.calibratedWaveformView.chart
        chart.data = LineData(dataSet)
        chart.setVisibleXRangeMaximum(currentWindowSeconds)
        chart.centerViewTo(currentWindowSeconds / 2f, 0f, YAxis.AxisDependency.LEFT)
        chart.invalidate()
    }

    /**
     * Fix D — recomputes the min/max bucket from whatever X range is currently visible and
     * re-buckets the whole decoded file at that density, targeting ~one min/max pair per
     * horizontal pixel. Runs off the main thread; only the dataset swap happens on Main.
     */
    private fun rebucketForCurrentZoom() {
        val samples = decodedSamples ?: return
        val ds = waveformDataSet ?: return
        val chart = binding.calibratedWaveformView.chart
        val plotWidthPx = chart.width.toFloat()
        if (plotWidthPx <= 0f) return

        val visibleSeconds = (chart.highestVisibleX - chart.lowestVisibleX).coerceAtLeast(0f)
        if (visibleSeconds <= 0f) return
        val visibleSampleCount = visibleSeconds * decodedSampleRate

        val derivedBucket = CalibratedWaveformView.deriveBucketSizeForVisibleRange(visibleSampleCount, plotWidthPx)
        if (derivedBucket <= 0) return
        val ceilingBucket = maxOf(1, samples.size / (TARGET_POINT_BUDGET / 2))
        val finalBucket = maxOf(derivedBucket, ceilingBucket)
        if (finalBucket == lastAppliedBucketSize) return
        lastAppliedBucketSize = finalBucket

        val sampleRate = decodedSampleRate
        rebucketJob?.cancel()
        rebucketJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val entries = CalibratedWaveformView.downsampleMinMax(samples, finalBucket) { idx ->
                idx.toFloat() / sampleRate
            }
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                // Mutate in place rather than replacing chart.data — a replace would reset
                // the viewport and undo the zoom/pan the user just performed.
                ds.values = ArrayList(entries)
                binding.calibratedWaveformView.chart.data?.notifyDataChanged()
                binding.calibratedWaveformView.chart.notifyDataSetChanged()
                binding.calibratedWaveformView.chart.invalidate()
            }
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
                            binding.timerText.text = String.format("%02d:%02d", totalSecs / 60, totalSecs % 60)

                            // Ease the camera toward the real playback position instead of
                            // snapping to it every callback.
                            displayedPlaybackTime += (timestamp.toFloat() - displayedPlaybackTime) * FOLLOW_SMOOTHING

                            val chart = binding.calibratedWaveformView.chart
                            val halfRange = chart.visibleXRange / 2f
                            val centerX = if (displayedPlaybackTime < halfRange) halfRange else displayedPlaybackTime
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

                            displayedPlaybackTime = 0f
                            val chart = binding.calibratedWaveformView.chart
                            chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
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

            displayedPlaybackTime = 0f
            val chart = binding.calibratedWaveformView.chart
            chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
        } else {
            try {
                displayedPlaybackTime = 0f
                val chart = binding.calibratedWaveformView.chart
                chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)

                player?.prepare()
                player?.start()
                isPlaying = true
                binding.actionText.text = getString(R.string.stop_recording)
                binding.playButton.setImageResource(R.drawable.ic_recording_stop)
            } catch (e: Exception) {
                isPlaying = false
                Toast.makeText(requireContext(), "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDiscardConfirmation(filePath: String) {
        val rawFilePath = arguments?.getString("rawFilePath") ?: ""
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard Recording")
            .setMessage("Are you sure you want to discard this recording? It will be permanently deleted.")
            .setPositiveButton("Discard") { _, _ ->
                try { File(filePath).delete() } catch (_: Exception) {}
                if (rawFilePath.isNotEmpty()) {
                    try { File(rawFilePath).delete() } catch (_: Exception) {}
                }
                findNavController().navigateUp()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSaveDiscardDialog(filePath: String) {
        PlayerSaveDiscardDialog { action ->
            when (action) {
                PlayerSaveDiscardDialog.Action.SAVE -> {
                    val bundle = Bundle().apply { putString("recordingFilePath", filePath) }
                    findNavController().navigate(R.id.action_calibratedPlayer_to_addPatient, bundle)
                }

                PlayerSaveDiscardDialog.Action.DISCARD -> {
                    try { File(filePath).delete() } catch (_: Exception) {}
                    findNavController().navigateUp()
                }
            }
        }.show(parentFragmentManager, "save_discard")
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
```

### 10.10 Screen E — decision history (why the numbers are what they are)

Worth reading before changing any Calibrated constant again — most values were already tuned
once or twice based on real feedback:

1. **Initial build**: faithful ports of production Recording/Player, with 5 accuracy fixes: (1)
   visible window derived from grid width instead of a fixed constant, (2) chart viewport
   offsets zeroed + native grid disabled so the mm-paper aligns with the plot area exactly, (3)
   DPI ruler-calibration screen, (4) Y-axis originally fixed (no warmup/peak adaptation), mV
   calibration pulse hidden, (5) decimation replaced with min/max bucket downsampling.
2. **Pinch-zoom/peak-size discussion**: established production Recording has no zoom ever;
   production Player has zoom; Calibrated matched that but zoom-lock had a real bug (next point).
3. **"I want all the features in the original in this one."** Two fixes: zoom-without-moving-
   background — root cause was `recomputeVisibleSeconds()` calling both
   `setVisibleXRangeMaximum` **and** `Minimum` to the same value, hard-locking the range and
   defeating `setScaleEnabled(true)` regardless of the flag; fixed by dropping the `Minimum` call
   from the shared method. And: restore production's adaptive Y-axis — Recorder got
   `WARMUP_MS`/`HEADROOM`/`MIN_PEAK` back; Player was set to fixed ±0.5 (matching production
   exactly, which is also fixed, not adaptive).
4. **"Increase background/peaks, reduce buttons, slow the graph line."** Shrunk chrome on both
   screens; `HEADROOM` tightened 1.5→1.2; `CalibratedPaperSpeed` changed 25→12.5 on both screens
   (halves scroll speed, doubles seconds visible — grid square physical size unaffected).
5. **"Increase the moving line size — not the thickness."** First attempt doubled `lineWidth` —
   wrong interpretation. Reverted, instead tightened `HEADROOM` further (1.2→1.1) to make peaks
   taller via more chart height, not a fatter stroke.
6. **"Peak going out of the screen" (real clipping bug).** Two causes: `HEADROOM=1.1` left
   almost no margin; separately, a percentile-based robust-peak fix (`WARMUP_PERCENTILE=0.9`,
   deliberately excluding the loudest 10% of buffers) meant real peaks in that top 10%
   legitimately exceeded the locked axis by design. Both dialed back: `HEADROOM` → 1.2 (both
   screens), `WARMUP_PERCENTILE` → 0.97 (Recorder only — Player has the whole file's true peak).
7. **Playback camera "too fast when zoomed."** Implemented `displayedPlaybackTime`, an
   exponentially-smoothed camera position (`FOLLOW_SMOOTHING=0.15`) that eases toward the real
   playback timestamp instead of snapping. Timer text stays exact; only the camera is damped.
8. **Robust-peak fix (origin of point 6's percentile logic).** User reported: live recording
   looks small the whole time, but the *same file* looks properly sized once opened in the
   player. Diagnosis: production's raw-max warmup lets a single loud transient (e.g. the contact
   "thud" of placing the stethoscope, often in the first 2s) permanently set an oversized scale
   for the whole recording. Fixed by collecting `warmupBufferPeaks` and locking to the
   `WARMUP_PERCENTILE`-th percentile instead of the raw max.
9. **Discussion, never executed this way**: porting Player's "ideal" pinch-tuned zoom into the
   Recorder as a `PaperSpeed` choice. Superseded entirely by §10.6/§10.9's `GraphCalibration`
   feature (a direct per-device override, not a `PaperSpeed` translation) added 2026-08-20.

**The Fixed-scale + Kardia grid task** (not logged step-by-step above) then replaced the entire
adaptive `HEADROOM`/`WARMUP_MS`/`WARMUP_PERCENTILE`/`MIN_PEAK` scheme with the single fixed
`FIXED_FULL_SCALE` constant described in §10.8/§10.9 (Fix A/B), added the Kardia-style
alpha/pixel-snap grid described in §10.2 (Fix C), and fixed the Player's zoom-driven re-bucketing
bug (Fix D, §10.9). `FIXED_FULL_SCALE` was then hand-tuned via live logcat analysis to `0.013f`
on one physical phone (Samsung SM-A066B) — and looked wrong on a second phone (OnePlus 7T,
different pixel density/width). **That mismatch is the direct reason `GraphCalibration` exists**:
a single hardcoded constant cannot look right on every screen, so instead of re-tuning forever,
each device calibrates itself once and remembers it.

**`GraphCalibration`'s own journey** (2026-08-20): built as a slider-panel design first ("Peak
Size"/"Time Zoom" sliders + Apply/Reset), hit two real bugs — a one-directional Time Zoom slider
(`setVisibleXRangeMaximum` alone only sets a zoom-out ceiling, can't widen an already-narrower
view; fixed by `forceVisibleSeconds()` momentarily pinning both bounds to the target then
relaxing the minimum back), and a Peak Size slider that moved the label but never the trace
(missing `chart.notifyDataSetChanged()` call after changing axis bounds) — then was simplified
entirely per user request ("remove the save button and make it Apply... hide the idea of sliders,
best if the user just uses the graph and pinch to zoom") to the current pinch-only design: the
slider panel was deleted, the bottom bar's `saveButton` was repurposed into `applyButton`, and
all slider-mapping helper functions were removed as dead code. A follow-up bug (`sessionBucketSize`
derived from paper-speed-only, not the *effective* window once an override could narrow it — same
class of bug as Fix D, just on the Recorder this time) was found and fixed the same way Fix D
fixed the Player. `FIXED_FULL_SCALE` was then retuned by direct request now that per-device
calibration exists to correct whatever the default looks like: `0.013f` → `0.30f` → `0.20f` →
`0.10f` (current) — deliberately not re-optimized for any device at any of these values.
`TRACE_LINE_WIDTH_DP` was bumped (Recorder 1.5→2.0, Player 2.5→3.0) once peaks visually grew and
a fixed-width stroke started reading as relatively thinner.

**Known accepted (not-a-bug) limitations**: zoom on the Calibrated Player rescales only the
trace, never the grid (explicit request — "without the background moving"), so "1mm=1mm" is
strictly true only at default zoom. Pre-amp gain is only compensated for on the Player when
reviewing a *just-recorded* file (via the `preAmpDb` nav-bundle arg); a file reopened later from
the library has no persisted pre-amp metadata and won't self-correct — mirrors the same
limitation already present in production A/B.

---

## 11. Screen F — FullTimeOn Recorder/Player (built 2026-08-20/21/25, real, tested)

**What it is**: created as an exact clone of Calibrated Recorder/Player (own package
`ui.fulltimeon`, own layouts, own nav destinations), then evolved independently. Reuses
Calibrated's shared infra unmodified: `CalibratedWaveformView`, `CalibratedEcgPaperView`,
`CalibratedMmScale`, `DpiCalibration`, `GraphCalibration` — a calibration Applied from either
screen pair's Player currently affects **both** pairs (they share the same
`SharedPreferences`-backed `GraphCalibration` store).

**The headline feature — always-on live preview + speaker mute (added 2026-08-21)**: the graph
runs live from the moment the screen opens, before Record is pressed, so a clinician can confirm
stethoscope placement by listening. Since `taal-core` has no listen-only mode (§2), a **second,
independent `TaalRecorder` instance** (`previewRecorder`) records to a fixed-name throwaway file
in `cacheDir` (`fto_live_preview.wav`), overwritten every preview start, deleted on every
stop/pause/destroy, never referenced by any save/discard path. `setFilteredAudioFilePath()` is
deliberately never called for preview, so only the one throwaway raw file is ever produced.
Pressing Record stops preview and starts a real, completely unmodified recording — the resulting
WAV files are byte-identical to before this feature existed.

**State machine** (`FullTimeOnRecordingUiState`: `IDLE`/`PREVIEW`/`RECORDING`/`STOPPED`):
```
IDLE -> PREVIEW        (every onResume: resetToIdle() then startPreview())
PREVIEW -> RECORDING   (pressing Record)
RECORDING -> STOPPED   (stopRecording()'s synchronous TaalRecorder callback)
STOPPED -> IDLE        (the next resetToIdle())
```
Externally reads as `IDLE→PREVIEW→RECORDING→PREVIEW`; the `STOPPED` hop is real but momentary and
never rendered (navigates to the Player immediately).

**What was tried and dropped**: the original design rendered the live waveform during PREVIEW
too. User feedback (2026-08-21): drop it — PREVIEW should show only a centered message ("Listen
for correct placement, then press Record to see the graph"), rely on audio/BPM only. RECORDING is
where the graph lives, unchanged from before.

**Y-axis/window sizing — ported verbatim from production**: `FullTimeOnRecordingFragment` got
production `RecordingFragment`'s real two-phase warmup/lock (`WARMUP_MS=2000`, `HEADROOM=1.5f`,
`MIN_PEAK=0.02f`), and `FullTimeOnPlayerFragment` got production `PlayerFragment`'s real flat
`FIXED_Y_FULL_SCALE=0.5f`. Window defaults copied verbatim too: `DEFAULT_WINDOW_SECONDS=10f`
Recorder, `4f` Player. A saved `GraphCalibration` override still wins over either default.

**The "too fast" saga (three wrong guesses, then the real cause)**: paper speed changed
25→12.5 (caused a wagon-wheel/stroboscopic illusion on **both** screens) → reverted to 50 → tried
12.5 fully. Real cause: the grid-derived visible window was only ~2 seconds wide on a typical
phone **regardless of paper speed** — page flipping ~5x more often than production's flat 10s/4s
windows. Paper speed was never the actual lever for "how often the page turns" once the window
itself is a fixed constant. Fixed by adding `DEFAULT_WINDOW_SECONDS`; paper speed reverted to
`SPEED_50` (now purely cosmetic).

**Bug: calibration override silently stopped applying.** The Y-axis-porting change replaced the
Y-axis setup unconditionally on both fragments, and neither read `calibrationOverride?.yFullScale`
anymore. Fixed: Player restored `calibrationOverride?.yFullScale ?: FIXED_Y_FULL_SCALE`; Recorder
got `resetYAxisForNewSession()` — skips the warmup entirely and locks straight to a saved
override if one exists, runs production's real warmup unmodified otherwise. A second bug in the
same area: `onResume()` was re-reading the override *after* already calling the functions that
consume it — fixed by reordering.

**Verification**: 53/53 unit tests (38 pre-existing + 4 new `ringTrimMinX` + 11
`FullTimeOnRecordingTransitionsTest`), tested live on real hardware throughout. One additive
change to shared code: `CalibratedWaveformView.ringTrimMinX()` (§10.3) — used only by FullTimeOn.

### 11.1 `ui/fulltimeon/FullTimeOnRecordingViewModel.kt`

```kotlin
package com.musediagnostics.taal.app.ui.fulltimeon

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.musediagnostics.taal.app.TaalApplication
import com.musediagnostics.taal.app.data.repository.RecordingRepository

/**
 * Clone of [com.musediagnostics.taal.app.ui.calibrated.CalibratedRecordingViewModel] under a
 * new screen name — same state shape, own package so it can evolve independently.
 */
enum class FullTimeOnRecordingUiState {
    IDLE,       // Transient — construction-time only, immediately replaced by PREVIEW on first onResume
    PREVIEW,    // Live graph + BPM streaming from a temp file that is never surfaced; nothing saved
    RECORDING,  // Actively recording to the real output files
    STOPPED     // Recording finished, showing save options
}

/**
 * The FullTimeOn Recorder's actual state graph, as a pure, unit-testable lookup — mirrors
 * exactly what FullTimeOnRecordingFragment does:
 *  - `IDLE -> PREVIEW`: every `onResume()` calls `resetToIdle()` immediately followed by
 *    `startPreview()`.
 *  - `PREVIEW -> IDLE`: internal/transient — `startPreview()` itself starts with `resetToIdle()`.
 *  - `PREVIEW -> RECORDING`: pressing Record; TaalRecorder's `onStateChange(RECORDING)` sets it.
 *  - `RECORDING -> STOPPED`: `stopRecording()`'s synchronous `onStateChange(STOPPED)` callback —
 *    the "STOPPED-state UI" itself is dead/never rendered (navigates to the Player immediately).
 *  - `STOPPED -> IDLE`: the next `resetToIdle()`.
 *
 * The externally observable cycle is `IDLE→PREVIEW→RECORDING→PREVIEW`; concretely it is
 * `IDLE -> PREVIEW -> RECORDING -> STOPPED -> IDLE -> PREVIEW`.
 */
object FullTimeOnRecordingTransitions {
    private val legalEdges: Set<Pair<FullTimeOnRecordingUiState, FullTimeOnRecordingUiState>> = setOf(
        FullTimeOnRecordingUiState.IDLE to FullTimeOnRecordingUiState.PREVIEW,
        FullTimeOnRecordingUiState.PREVIEW to FullTimeOnRecordingUiState.IDLE,
        FullTimeOnRecordingUiState.PREVIEW to FullTimeOnRecordingUiState.RECORDING,
        FullTimeOnRecordingUiState.RECORDING to FullTimeOnRecordingUiState.STOPPED,
        FullTimeOnRecordingUiState.STOPPED to FullTimeOnRecordingUiState.IDLE,
    )

    fun isLegal(from: FullTimeOnRecordingUiState, to: FullTimeOnRecordingUiState): Boolean =
        from != to && (from to to) in legalEdges
}

class FullTimeOnRecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as TaalApplication).database
    val recordingRepository = RecordingRepository(db.recordingDao())

    private val _uiState = MutableLiveData(FullTimeOnRecordingUiState.IDLE)
    val uiState: LiveData<FullTimeOnRecordingUiState> = _uiState

    private val _timerSeconds = MutableLiveData(0)
    val timerSeconds: LiveData<Int> = _timerSeconds

    private val _currentFilter = MutableLiveData("HEART")
    val currentFilter: LiveData<String> = _currentFilter

    private val _bpm = MutableLiveData(0)
    val bpm: LiveData<Int> = _bpm

    private val _preAmpDb = MutableLiveData(5)
    val preAmpDb: LiveData<Int> = _preAmpDb

    var currentRecordingPath: String = ""
    var currentFilteredPath: String = ""
    var customLowCut: Float? = null
    var customHighCut: Float? = null

    fun setUiState(state: FullTimeOnRecordingUiState) {
        _uiState.value = state
    }

    fun updateTimer(seconds: Int) {
        _timerSeconds.value = seconds
    }

    fun setFilter(filter: String) {
        _currentFilter.value = filter
    }

    fun setBpm(bpm: Int) {
        _bpm.value = bpm
    }

    fun setPreAmp(db: Int) {
        _preAmpDb.value = db.coerceIn(0, 30)
    }

    fun formatTimer(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
}
```

### 11.2 `FullTimeOnRecordingTransitionsTest.kt`

```kotlin
package com.musediagnostics.taal.app.ui.fulltimeon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullTimeOnRecordingTransitionsTest {

    @Test
    fun `IDLE to PREVIEW is legal - every onResume`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.IDLE, FullTimeOnRecordingUiState.PREVIEW))
    }

    @Test
    fun `PREVIEW to RECORDING is legal - pressing Record`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.PREVIEW, FullTimeOnRecordingUiState.RECORDING))
    }

    @Test
    fun `RECORDING to STOPPED is legal - stopRecording's synchronous TaalRecorder callback`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.RECORDING, FullTimeOnRecordingUiState.STOPPED))
    }

    @Test
    fun `STOPPED to IDLE is legal - the next resetToIdle()`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.STOPPED, FullTimeOnRecordingUiState.IDLE))
    }

    @Test
    fun `PREVIEW to IDLE is legal - startPreview's own internal resetToIdle() step`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.PREVIEW, FullTimeOnRecordingUiState.IDLE))
    }

    @Test
    fun `the required conceptual cycle IDLE to PREVIEW to RECORDING to PREVIEW is reachable via legal edges only`() {
        val chain = listOf(
            FullTimeOnRecordingUiState.IDLE,
            FullTimeOnRecordingUiState.PREVIEW,
            FullTimeOnRecordingUiState.RECORDING,
            FullTimeOnRecordingUiState.STOPPED,
            FullTimeOnRecordingUiState.IDLE,
            FullTimeOnRecordingUiState.PREVIEW,
        )
        for (i in 0 until chain.size - 1) {
            assertTrue(
                "expected ${chain[i]} -> ${chain[i + 1]} to be legal",
                FullTimeOnRecordingTransitions.isLegal(chain[i], chain[i + 1])
            )
        }
    }

    @Test
    fun `cannot skip preview - IDLE straight to RECORDING is illegal`() {
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.IDLE, FullTimeOnRecordingUiState.RECORDING))
    }

    @Test
    fun `cannot skip the stop hop - RECORDING straight to IDLE is illegal`() {
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.RECORDING, FullTimeOnRecordingUiState.IDLE))
    }

    @Test
    fun `cannot skip the stop hop - RECORDING straight to PREVIEW is illegal`() {
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.RECORDING, FullTimeOnRecordingUiState.PREVIEW))
    }

    @Test
    fun `STOPPED cannot go straight back to RECORDING or PREVIEW`() {
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.STOPPED, FullTimeOnRecordingUiState.RECORDING))
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.STOPPED, FullTimeOnRecordingUiState.PREVIEW))
    }

    @Test
    fun `a state never legally transitions to itself`() {
        for (state in FullTimeOnRecordingUiState.entries) {
            assertFalse("$state -> $state should not be a legal transition", FullTimeOnRecordingTransitions.isLegal(state, state))
        }
    }
}
```

### 11.3 `ui/fulltimeon/FullTimeOnRecordingFragment.kt` — full source

```kotlin
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
 * Clone of CalibratedRecordingFragment under a new screen name — behavior was initially
 * identical, then diverged (always-on preview + speaker mute, production Y-axis/window
 * porting). Reuses the same shared calibrated-graph infrastructure (CalibratedWaveformView,
 * DpiCalibration, GraphCalibration) rather than forking those too.
 *
 * Always-on live preview + speaker mute toggle. The graph (and BPM) run continuously from the
 * moment this screen is visible (PREVIEW), writing nothing durable to disk, so the clinician
 * can confirm placement before pressing Record. taal-core's TaalRecorder has no listen-only/
 * no-file mode (checked directly — start() requires a mandatory .wav raw-file path that
 * TaalAudioCapture unconditionally writes to), so preview uses a second TaalRecorder instance
 * (previewRecorder) pointed at a fixed temp file in getCacheDir() — the filtered WAV is skipped
 * entirely (never calling setFilteredAudioFilePath), since TaalRecorder.start() only opens that
 * file if a path was set. The temp file is overwritten on every preview start and deleted on
 * every preview stop/pause/destroy; it is never referenced by any save/discard path or the
 * recordings library. Pressing Record stops preview and starts a real, unmodified recording
 * session — the real-recording code path is otherwise untouched by this change, so the produced
 * WAV files are byte-identical to before.
 *
 * The speaker toggle gates a monitor path that already existed here before this change
 * (startAudioMonitor/feedSpeaker) — muted by default, never persisted across screen visits.
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
    // instead of a fixed constant. Recomputed only while idle and held frozen for the whole
    // recording session: the recorder mutates LineDataSet.values in place against a monotonic
    // sample-counter X axis, so changing the bucket size mid-recording would space
    // already-plotted points inconsistently with new ones.
    private var sessionBucketSize = DOWNSAMPLE_BUCKET_FALLBACK

    // Per-device calibration override (Time Zoom, set from the Player) — null means "use the
    // grid-derived default window." Re-read in onResume() so returning from the Player after
    // Apply/Reset reflects immediately. X (visibleSeconds) only now — Y is no longer read from
    // this; see peakAmplitude below (match production RecordingFragment's actual sizing
    // behavior instead of a fixed/overridable constant).
    private var calibrationOverride: GraphCalibration.Override? = null

    // Ported verbatim from production RecordingFragment — two-phase warmup/lock Y-axis,
    // replacing FullTimeOn's previous fixed-axis approach. Phase 1 (first WARMUP_MS of a
    // session): axis stays ±1.0 while the true peak is observed. Phase 2: axis locks to
    // peakAmplitude and is never touched again until the next session's reset. Reset points
    // mirror production's exactly: resetToIdle() (idle/disconnect/preview-restart) and
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

    // Speaker toggle — muted by default, reset every screen entry (never persisted). Read by
    // feedSpeaker(), which both the preview and real-recording listeners call.
    private var isSpeakerMuted = true

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f

        // Pre-layout fallback only (pxPerMmX not yet known).
        private const val DOWNSAMPLE_BUCKET_FALLBACK = 88

        // Y-axis warmup/lock constants, copied verbatim from production RecordingFragment's
        // values.
        private const val WARMUP_MS = 2000
        private const val HEADROOM = 1.5f
        private const val MIN_PEAK = 0.02f

        // Default visible time window in seconds — copied verbatim from production
        // RecordingFragment's own WINDOW_SECONDS. Used only when no calibration override is
        // saved; a saved Time Zoom override (pinch + Apply on the Player) still takes priority
        // over this, applies live to the Recorder too, and Reset returns here.
        private const val DEFAULT_WINDOW_SECONDS = 10f

        // Pre-amp slider should only change loudness, never the live graph's size — the trace
        // reflects true acoustic level, not the amplified WAV level.
        private const val COMPENSATE_PREAMP_IN_DISPLAY = true

        // Trace stroke width in dp.
        private const val TRACE_LINE_WIDTH_DP = 2.0f

        // How long a single preview capture session runs before TaalRecorder's own internal
        // duration cap silently stops it and it's seamlessly restarted. Bounds how large the
        // throwaway cache file can grow if a screen is left open indefinitely — 10 minutes ≈
        // 53MB of raw PCM, not something worth carrying for hours.
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
            // The actual "too fast" fix: default to production RecordingFragment's own fixed
            // DEFAULT_WINDOW_SECONDS (10s) instead of the grid's physically-derived `seconds`
            // (which on a typical phone is only ~2s wide — the page was flipping 5x more often
            // than production's). A saved Time Zoom override still wins over both, same as
            // before. `seconds` itself is intentionally unused as a fallback now; it's still
            // the parameter this callback fires with (grid/layout derivation still runs, e.g.
            // on rotation), just no longer what picks the *default*.
            currentWindowSeconds = calibrationOverride?.visibleSeconds ?: DEFAULT_WINDOW_SECONDS
            // Don't clobber an in-progress recording's dataset on rotation/resize — only
            // reset to the dummy grid-holder dataset when there's no real data yet.
            if (waveformDataSet == null) {
                // Only re-derive the bucket size while idle; a session in progress must keep
                // using whatever was frozen when it started.
                //
                // Derived from the *effective* window (currentWindowSeconds, which already
                // folds in any calibration override above), not from paper speed alone —
                // a saved override that narrows the window (more zoomed in) would leave the
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
     * straight to it. With no override, production's own two-phase warmup/lock behavior runs
     * unmodified — see peakAmplitude's field doc.
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
            // so the clinician can dial in the right dB by ear before pressing Record. Both are
            // never non-null at the same time, so at most one call is real.
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
     * the pre-existing speaker monitor (startAudioMonitor) actually plays this buffer. Shared
     * by both the preview and real-recording OnInfoListeners.
     *
     * [forceUnmuted] — the speaker toggle only means anything during PREVIEW: once RECORDING
     * actually starts, audio plays unconditionally, same as it always did before the mute
     * toggle existed. The real-recording listener passes true here; the preview listener
     * leaves it false and respects [isSpeakerMuted] as before.
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

        // Dead in production too (STOPPED-state UI is never entered) — kept only so the
        // layout/ID surface stays a faithful replica.
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
        // Fresh Y-axis for the next session — override-aware.
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
                // preview). IDLE itself is transient — real devices land in PREVIEW within the
                // same onResume call that first sets it.
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
                    // No live graph in PREVIEW anymore — audio/speaker/BPM keep working, but
                    // the grid just shows this message until Record is pressed.
                    binding.previewMessage.visibility = View.VISIBLE
                    // The mute toggle only does anything during PREVIEW — recording is always
                    // audible regardless of it, see feedSpeaker's doc.
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

        // PREVIEW → RECORDING: stop the preview stream before claiming the device for real.
        // TaalAudioCapture.stopRecording() releases the AudioRecord synchronously (not
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
                        // Always audible while actually recording — the mute toggle only
                        // applies during PREVIEW, see feedSpeaker's doc.
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
            // Fresh Y-axis for this recording — override-aware: a saved Peak Size calibration
            // wins here too, not just in preview, so a recording actually comes out the size
            // the user calibrated for.
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
        // happens the moment the user comes back.
    }

    /**
     * Starts (or restarts) the live preview stream — see the class doc for why this uses a
     * second TaalRecorder pointed at a throwaway cache file rather than any listen-only SDK
     * mode (checked directly against taal-core's API surface; none exists). No-ops safely if
     * a real recording is in progress or preview is already running.
     *
     * [showErrorOnFailure] is false for the silent, event-driven retries this triggers on its
     * own (device reconnect via setupConnectionReceiver, or the natural
     * PREVIEW_SESSION_SECONDS restart below) — true only for the original user-visible attempt
     * from onResume, so a missing device produces exactly one message, never a retry storm.
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
                    // side effect.
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
            // PREVIEW_SESSION_SECONDS by design, and the base interface's default no-op is
            // exactly right here: popping "Ready to Capture / please discard and start a new
            // one" every ~10 minutes during ordinary preview would be nonsensical.

            override fun onProgressUpdate(
                sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
            ) {
                // No graph during PREVIEW anymore — audio keeps streaming (feedSpeaker + BPM)
                // so the mute toggle and BPM readout still work, but nothing is drawn;
                // previewMessage stays up until RECORDING actually starts. Timer also
                // intentionally not updated — stays 00:00:00 in PREVIEW.
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
        // open-ended live preview session from accumulating points forever; it already applied
        // equally to recording, which is naturally bounded by its own duration cap. Formula
        // factored out to CalibratedWaveformView.ringTrimMinX() so it's unit-testable.
        val minXToKeep = CalibratedWaveformView.ringTrimMinX(latestX, windowSeconds)
        if (minXToKeep > 0) {
            val iterator = waveformEntries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().x < minXToKeep) iterator.remove() else break
            }
        }

        // Ported verbatim from production RecordingFragment: two-phase warmup/lock. Phase 1
        // (first WARMUP_MS of this session): accumulate the true peak, axis stays ±1.0. Phase
        // 2: lock once and never touch the axis again until the next session's reset. Applies
        // identically whether this buffer came from PREVIEW or RECORDING — both share this
        // same updateWaveform() pipeline and both get their own fresh warmup at session start.
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
        // Muted by default on every screen entry — deliberately never persisted: an app that
        // starts making noise on its own is a worse failure than one extra tap.
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
            // Always-on preview — entered automatically the moment this screen becomes
            // visible, including the very first time and every return trip from the Player.
            // No-ops safely if already running; shows one message and waits for a reconnect
            // event if no device is attached.
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
        // Never leave the preview USB stream or speaker AudioTrack running in the background —
        // battery, device heat, not holding the stethoscope handle open against other apps.
        // Safe no-op if a real recording is in progress (stopPreview only touches
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
```

### 11.4 `ui/fulltimeon/FullTimeOnPlayerFragment.kt` — full source (646 lines)

Clone of `CalibratedPlayerFragment` (§10.9) under a new screen name — behavior is identical to
that fragment (fixed-scale axis, Fix D zoom re-bucketing, pinch/Apply/Reset graph calibration).

```kotlin
package com.musediagnostics.taal.app.ui.fulltimeon

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentFulltimeonPlayerBinding
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedPaperSpeed
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration
import com.musediagnostics.taal.app.ecg.calibrated.GraphCalibration
import com.musediagnostics.taal.app.ui.player.PlayerSaveDiscardDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FullTimeOnPlayerFragment : Fragment() {

    private var _binding: FragmentFulltimeonPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    private var currentWindowSeconds = 4f

    // Smoothed camera-follow position during playback — see onPlaybackProgress. Reset to 0
    // wherever the chart snaps back to its start-centered framing.
    private var displayedPlaybackTime = 0f

    // Kept in memory for zoom-driven re-bucketing (rebucketForCurrentZoom). Never re-read from
    // disk after the initial load.
    private var decodedSamples: FloatArray? = null
    private var decodedSampleRate: Float = INPUT_SAMPLE_RATE
    private var waveformDataSet: LineDataSet? = null // persistent ref, mutated in place on re-bucket
    private var lastAppliedBucketSize = -1
    private var rebucketJob: Job? = null

    // Full length of the loaded file — the max-zoom-out cap uses this so pinching all the way
    // out can "squeeze" to see the entire recording at once, not just the default window (zoom
    // was one-directional before — could only go bigger/narrower, never smaller/wider than the
    // default).
    private var loadedDurationSecs = 0

    // Per-device calibration override (set by pinching the graph, then pressing applyButton) —
    // null means "use the grid-derived default window." Read once at setup, kept in sync by the
    // Apply/Reset handlers. X (visibleSeconds) only now — Y is a fixed constant matching
    // production PlayerFragment exactly (see FIXED_Y_FULL_SCALE), not read from here anymore.
    private var calibrationOverride: GraphCalibration.Override? = null

    // True once the user has actually pinched/panned since this screen loaded (or since the
    // last Apply/Reset). There's no separate "Done" button — Apply is the only action — so
    // pressing it without having touched the graph at all must be a no-op: nothing to save,
    // nothing changed. Set in onChartGestureEnd, cleared after a real Apply and after Reset.
    private var hasUserAdjustedView = false

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val TARGET_POINT_BUDGET = 3000 // same total-point budget as production's maxPoints
        // Ported verbatim from production PlayerFragment — a plain fixed constant, never
        // adaptive, no per-file peak scan (confirmed directly against PlayerFragment.kt: it
        // hardcodes ±0.5 at setup and never touches axisMinimum/Maximum again). Must stay
        // identical to FullTimeOnRecordingFragment's own locked value only coincidentally — the
        // two screens use different mechanisms now (Recorder: warmup/lock; Player: this
        // constant), exactly mirroring production's own Recording/Player split.
        private const val FIXED_Y_FULL_SCALE = 0.5f

        // Default visible time window in seconds — copied verbatim from production
        // PlayerFragment's own hardcoded setVisibleXRangeMaximum(4f). Used only when no
        // calibration override is saved; a saved Time Zoom override (pinch + Apply) still
        // takes priority, applies live to the Recorder too, and Reset returns here.
        private const val DEFAULT_WINDOW_SECONDS = 4f

        // Camera-follow smoothing. Fraction of the remaining gap to the real playback position
        // closed per progress callback — lower = gentler/slower-feeling follow, 1.0 = instant
        // snap.
        private const val FOLLOW_SMOOTHING = 0.15f
        // Trace stroke width in dp.
        private const val TRACE_LINE_WIDTH_DP = 3.0f

        // Floor used by forceVisibleSeconds() to relax the max-zoom-in bound back to
        // effectively unlimited after forcing an exact Time Zoom width — see that function.
        private const val MIN_VISIBLE_SECONDS = 0.3f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFulltimeonPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val isNewRecording = arguments?.getBoolean("isNewRecording", false) ?: false
        val filterName = arguments?.getString("filterName") ?: "HEART"
        // The dB the recorder actually used for this file, if known (0 = not passed / unknown,
        // meaning no compensation is applied) — see loadFullWaveform's doc for why this exists.
        val recordedPreAmpDb = arguments?.getInt("preAmpDb", 0) ?: 0

        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()
        setupAmpSlider()
        setupGraphCalibrationPanel()
        updateCalibrationCaption()

        if (filePath.isNotEmpty()) {
            // Capture the scale on the main thread before loadFullWaveform's IO coroutine
            // reads it — setupWaveformChart() above already applied paper speed + DPI
            // correction synchronously, so this snapshot is final for the rest of this load.
            val scale = binding.calibratedWaveformView.paperView.currentScale()
            loadFullWaveform(filePath, filterName, scale.paperSpeed.mmPerSecond, scale.pxPerMmX, recordedPreAmpDb)
            setupPlayer(filePath, filterName)
        }

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.eqButton.setOnClickListener {
            val bundle = Bundle().apply { putString("filePath", filePath) }
            findNavController().navigate(R.id.action_fullTimeOnPlayer_to_equalizer, bundle)
        }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }

        // Apply saves whatever pinch-zoom state the graph is currently showing as this device's
        // default and goes straight back to the Recorder to see it applied live. There's no
        // separate "Done" button, so if the user never touched the graph at all, Apply must not
        // silently (re-)save the same default as if something had changed — it still navigates
        // back, just skips the save.
        binding.applyButton.setOnClickListener {
            if (hasUserAdjustedView) {
                val chart = binding.calibratedWaveformView.chart
                val effectiveVisibleSeconds = chart.highestVisibleX - chart.lowestVisibleX
                val effectiveYFullScale = currentEffectiveYFullScale()
                if (effectiveVisibleSeconds > 0f && effectiveYFullScale > 0f) {
                    GraphCalibration.saveOverride(requireContext(), effectiveVisibleSeconds, effectiveYFullScale)
                    calibrationOverride = GraphCalibration.Override(effectiveVisibleSeconds, effectiveYFullScale)
                }
                hasUserAdjustedView = false
            }
            findNavController().navigateUp()
        }

        binding.discardButton.setOnClickListener {
            if (isNewRecording) {
                showDiscardConfirmation(filePath)
            } else {
                showSaveDiscardDialog(filePath)
            }
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
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart

        calibrationOverride = GraphCalibration.getOverride(requireContext())

        // Fixed ±0.5 by default, exactly like production PlayerFragment's own setup — never
        // adaptive, no per-file peak scan. A saved calibration (pinch + Apply) overrides that
        // default and applies here and on the Recorder both — this read was accidentally
        // dropped when FIXED_Y_FULL_SCALE was ported in; restored.
        val effectiveYFullScale = calibrationOverride?.yFullScale ?: FIXED_Y_FULL_SCALE
        chart.axisLeft.axisMinimum = -effectiveYFullScale
        chart.axisLeft.axisMaximum = effectiveYFullScale

        // 50mm/s — see FullTimeOnRecordingFragment's copy of this comment for the full
        // reasoning (reverted after two failed speed guesses; DEFAULT_WINDOW_SECONDS below is
        // the actual fix for "too fast"). Must match FullTimeOnRecordingFragment exactly, so a
        // recording looks the same live as it does in review.
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_50)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        // User can pan/zoom to inspect the trace with two fingers, in both directions:
        // horizontal pinch for time (Time Zoom), vertical pinch for peak height (Peak Size).
        // MPAndroidChart scales Y via its own touch-matrix (viewPortHandler.scaleY), a separate
        // mechanism from the fixed axis bounds — currentEffectiveYFullScale() folds the two
        // together (divide by scaleY) so Apply always reads the true effective state regardless
        // of how the user got there.
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleXEnabled(true)
        chart.setScaleYEnabled(true)

        // Re-bucket for the new zoom/pan level once the gesture settles. Debounced by
        // construction: onChartGestureEnd fires once per discrete gesture, not per frame, so
        // this never runs mid-pinch and can't cause scroll stutter.
        chart.setOnChartGestureListener(object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {
                hasUserAdjustedView = true
                rebucketForCurrentZoom()
            }
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) {}
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {}
        })

        waveformView.onVisibleSecondsChanged = { _ ->
            // The actual "too fast" fix: default to production PlayerFragment's own fixed
            // DEFAULT_WINDOW_SECONDS (4s) instead of the grid's physically-derived `seconds`. A
            // saved Time Zoom override still wins over both — physical derivation still runs
            // every time (e.g. on rotation), it's just superseded either way. `seconds` is
            // intentionally unused as a fallback now.
            currentWindowSeconds = calibrationOverride?.visibleSeconds ?: DEFAULT_WINDOW_SECONDS
            if (chart.data == null) {
                resetToDummyData()
            } else {
                // Real waveform already loaded (e.g. window changed on rotation) — reapply
                // the corrected max-zoom-out cap and re-center rather than silently drifting
                // stale. Deliberately NOT setVisibleXRangeMinimum — that would lock the range
                // to exactly `currentWindowSeconds` and disable pinch-zoom entirely. The ceiling
                // itself is never smaller than the whole file (see renderWaveformEntries) so
                // squeezing all the way out to see the full recording always stays possible.
                chart.setVisibleXRangeMaximum(maxOf(currentWindowSeconds, loadedDurationSecs.toFloat()))
                chart.centerViewTo(currentWindowSeconds / 2f, 0f, YAxis.AxisDependency.LEFT)
                chart.invalidate()
            }
        }
        waveformView.recomputeVisibleSeconds()
        if (chart.data == null) resetToDummyData()
    }

    private fun resetToDummyData() {
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
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude (fixed) · DPI: $status"
    }

    /**
     * Graph calibration is pinch-only — this just wires the status readout and Reset. Applying
     * a calibration happens via [R.id.applyButton] in the bottom bar, which reads whatever the
     * pinch-tuned view currently shows.
     */
    private fun setupGraphCalibrationPanel() {
        updateCalibrationStatusText()
        binding.resetCalibrationButton.setOnClickListener {
            GraphCalibration.clearOverride(requireContext())
            calibrationOverride = null
            applyBuiltInDefaultScale()
            // Back to the untouched default — a bare Apply right after this must still no-op.
            hasUserAdjustedView = false
            updateCalibrationStatusText()
            Toast.makeText(requireContext(), "Reset to default", Toast.LENGTH_SHORT).show()
        }
    }

    /** Re-applies FIXED_Y_FULL_SCALE and the grid-derived default window, bypassing any override — used by Reset. */
    private fun applyBuiltInDefaultScale() {
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart
        // Clears any pinch-driven X/Y viewport zoom (scaleX/scaleY) before reapplying the
        // built-in defaults below — otherwise a prior vertical pinch would still be layered on
        // top of the reset axis bounds and Reset wouldn't actually look reset.
        chart.fitScreen()
        chart.axisLeft.axisMinimum = -FIXED_Y_FULL_SCALE
        chart.axisLeft.axisMaximum = FIXED_Y_FULL_SCALE
        // Axis bounds alone don't move the already-plotted trace without this.
        chart.notifyDataSetChanged()
        // Re-fires onVisibleSecondsChanged with calibrationOverride already cleared above, so
        // currentWindowSeconds lands back on the physical grid-derived value.
        waveformView.recomputeVisibleSeconds()
        // onVisibleSecondsChanged only reapplies setVisibleXRangeMaximum (a zoom-OUT cap, see
        // forceVisibleSeconds) — if the user had pinched/slid to a *wider* view than the
        // default before hitting Reset, that alone wouldn't visually snap back. Force it.
        forceVisibleSeconds(currentWindowSeconds)
        // forceVisibleSeconds's own setVisibleXRangeMaximum(seconds) call just narrowed the
        // zoom-out ceiling back down to the default window as a side effect of forcing the
        // view there — widen it back to the full file so squeezing out to see the whole
        // recording is still possible after Reset, not just before it.
        chart.setVisibleXRangeMaximum(maxOf(currentWindowSeconds, loadedDurationSecs.toFloat()))
        rebucketForCurrentZoom()
        chart.invalidate()
    }

    /**
     * The Y full-scale actually being shown right now, folding together the fixed axis bounds
     * (chart.axisLeft.axisMaximum) and any pinch-driven vertical zoom on top of them
     * (chart.viewPortHandler.scaleY) — mirrors how X already reads its true state via
     * chart.highestVisibleX/lowestVisibleX rather than raw axis bounds. Relies on the Y axis
     * always being centered at 0 (every centerViewTo(...) call in this fragment passes 0f for y).
     */
    private fun currentEffectiveYFullScale(): Float {
        val chart = binding.calibratedWaveformView.chart
        val scaleY = chart.viewPortHandler.scaleY.coerceAtLeast(0.01f)
        return chart.axisLeft.axisMaximum / scaleY
    }

    /**
     * Forces the chart to display exactly [seconds] of width right now, regardless of whether
     * that's narrower or wider than the current view. `setVisibleXRangeMaximum` alone only sets
     * a zoom-out ceiling — it can't widen an already-narrower view. Both bounds are pinned to
     * [seconds] momentarily (forcing scaleX to exactly the target in either direction), then the
     * lower bound is relaxed back to effectively unlimited so pinch-zoom-in still works after.
     */
    private fun forceVisibleSeconds(seconds: Float) {
        val chart = binding.calibratedWaveformView.chart
        chart.setVisibleXRangeMinimum(seconds)
        chart.setVisibleXRangeMaximum(seconds)
        chart.centerViewTo(seconds / 2f, 0f, YAxis.AxisDependency.LEFT)
        chart.setVisibleXRangeMinimum(MIN_VISIBLE_SECONDS * 0.1f)
        chart.invalidate()
    }

    private fun updateCalibrationStatusText() {
        if (_binding == null) return
        binding.graphCalibrationStatus.text = if (calibrationOverride != null) {
            "Calibrated on this device"
        } else {
            "Not calibrated on this device — using default"
        }
    }

    /**
     * Clone of CalibratedPlayerFragment.loadFullWaveform(). Same WAV-header sample-rate parsing
     * (bytes 24-27, little-endian, fallback 44100), min/max-bucketed downsampling.
     *
     * [recordedPreAmpDb] undoes the same gain the recorder applied when this file was made, so a
     * recording looks the same size in the Player as it did live. 0 means unknown/not passed —
     * no compensation applied. Only works for files that arrived with that bundle arg (i.e.
     * reviewing a just-recorded file) — a recording reopened later from the library still won't
     * self-correct.
     */
    private fun loadFullWaveform(
        filePath: String, filterName: String, paperSpeedMmPerSecond: Float, pxPerMmX: Float, recordedPreAmpDb: Int
    ) {
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

            // Decode all samples first (need them in a FloatArray for bucketed min/max).
            val samples = FloatArray(totalSamples)
            var i = 0
            while (i < totalSamples) {
                val bytePos = 44 + i * 2
                if (bytePos + 1 >= bytes.size) break
                val low = bytes[bytePos].toInt() and 0xFF
                val high = bytes[bytePos + 1].toInt() shl 8
                samples[i] = (high or low).toShort().toFloat() / 32768f
                i++
            }

            // Undo the recorder's pre-amp gain, same formula FullTimeOnRecordingFragment uses
            // live — makes this file render at the same size it showed on the recording screen,
            // regardless of what dB was used.
            if (recordedPreAmpDb > 0) {
                val preAmpGain = Math.pow(10.0, recordedPreAmpDb / 20.0).toFloat()
                if (preAmpGain > 1.001f) {
                    for (j in samples.indices) samples[j] = samples[j] / preAmpGain
                }
            }

            // Derive so ~one min/max pair lands per horizontal pixel at the current paper
            // speed. Apply TARGET_POINT_BUDGET as a ceiling only: enlarge the bucket if the
            // derived value would produce more than the budget on a long file, but never
            // shrink below it.
            val derivedBucket = CalibratedWaveformView.deriveBucketSize(fileSampleRate, paperSpeedMmPerSecond, pxPerMmX)
            val budgetCeilingBucket = maxOf(1, totalSamples / (TARGET_POINT_BUDGET / 2))
            val bucketSize = if (derivedBucket > 0) maxOf(derivedBucket, budgetCeilingBucket) else budgetCeilingBucket
            val entries = CalibratedWaveformView.downsampleMinMax(samples, bucketSize) { idx ->
                idx.toFloat() / fileSampleRate
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                decodedSamples = samples
                decodedSampleRate = fileSampleRate
                lastAppliedBucketSize = bucketSize
                renderWaveformEntries(ArrayList(entries), durationSecs)
            }
        }
    }

    private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int) {
        binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
        loadedDurationSecs = durationSecs
        val dataSet = LineDataSet(entries, "Waveform").apply {
            color = Color.parseColor("#2D7DD2")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = TRACE_LINE_WIDTH_DP
            mode = LineDataSet.Mode.LINEAR
        }
        waveformDataSet = dataSet // persistent ref, mutated in place on re-bucket
        // currentWindowSeconds is kept in sync by onVisibleSecondsChanged (set up in
        // setupWaveformChart, called before this) — including the case where layout hadn't
        // happened yet when this loaded; that callback will re-apply the range once it does.
        // Y-axis is fixed — already set once in setupWaveformChart(), not touched here.
        val chart = binding.calibratedWaveformView.chart
        chart.data = LineData(dataSet)
        // Cap max zoom-out only — no Minimum lock, so pinch-zoom works (see setupWaveformChart).
        // The ceiling is never smaller than the whole file (pinching out used to stop at the
        // default window — "large only" — with no way to squeeze further; now it can go all
        // the way out to the full recording).
        val maxVisibleSeconds = maxOf(currentWindowSeconds, loadedDurationSecs.toFloat())
        chart.setVisibleXRangeMaximum(maxVisibleSeconds)
        chart.centerViewTo(currentWindowSeconds / 2f, 0f, YAxis.AxisDependency.LEFT)
        chart.invalidate()
    }

    /**
     * Recomputes the min/max bucket from whatever X range is currently visible (post-zoom/pan)
     * and re-buckets the whole decoded file at that density, targeting ~one min/max pair per
     * horizontal pixel. Re-buckets the whole file (not just the visible slice) so panning
     * within an unchanged zoom level doesn't need to re-run this — only an actual zoom change
     * does, since [lastAppliedBucketSize] short-circuits a no-op. Runs off the main thread since
     * re-bucketing a long file is real work; only the dataset swap happens on Main.
     */
    private fun rebucketForCurrentZoom() {
        val samples = decodedSamples ?: return
        val ds = waveformDataSet ?: return
        val chart = binding.calibratedWaveformView.chart
        val plotWidthPx = chart.width.toFloat()
        if (plotWidthPx <= 0f) return

        val visibleSeconds = (chart.highestVisibleX - chart.lowestVisibleX).coerceAtLeast(0f)
        if (visibleSeconds <= 0f) return
        val visibleSampleCount = visibleSeconds * decodedSampleRate

        val derivedBucket = CalibratedWaveformView.deriveBucketSizeForVisibleRange(visibleSampleCount, plotWidthPx)
        if (derivedBucket <= 0) return
        // Same budget-ceiling pattern as the load-time bucket — enlarge to stay within
        // TARGET_POINT_BUDGET on a long file, never shrink below the derived value.
        val ceilingBucket = maxOf(1, samples.size / (TARGET_POINT_BUDGET / 2))
        val finalBucket = maxOf(derivedBucket, ceilingBucket)
        if (finalBucket == lastAppliedBucketSize) return
        lastAppliedBucketSize = finalBucket

        val sampleRate = decodedSampleRate
        rebucketJob?.cancel()
        rebucketJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val entries = CalibratedWaveformView.downsampleMinMax(samples, finalBucket) { idx ->
                idx.toFloat() / sampleRate
            }
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                // Mutate in place (same pattern as the recorder) rather than replacing
                // chart.data — a replace would reset the viewport and undo the zoom/pan the
                // user just performed.
                ds.values = ArrayList(entries)
                binding.calibratedWaveformView.chart.data?.notifyDataChanged()
                binding.calibratedWaveformView.chart.notifyDataSetChanged()
                binding.calibratedWaveformView.chart.invalidate()
            }
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
                            // Audio timer stays exact — only the camera follow is smoothed.
                            val totalSecs = timestamp.toInt()
                            binding.timerText.text = String.format("%02d:%02d", totalSecs / 60, totalSecs % 60)

                            // Ease the camera toward the real playback position instead of
                            // snapping to it every callback.
                            displayedPlaybackTime += (timestamp.toFloat() - displayedPlaybackTime) * FOLLOW_SMOOTHING

                            val chart = binding.calibratedWaveformView.chart
                            val halfRange = chart.visibleXRange / 2f
                            val centerX = if (displayedPlaybackTime < halfRange) halfRange else displayedPlaybackTime
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

                            displayedPlaybackTime = 0f
                            val chart = binding.calibratedWaveformView.chart
                            chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
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

            displayedPlaybackTime = 0f
            val chart = binding.calibratedWaveformView.chart
            chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
        } else {
            try {
                displayedPlaybackTime = 0f
                val chart = binding.calibratedWaveformView.chart
                chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)

                player?.prepare()
                player?.start()
                isPlaying = true
                binding.actionText.text = getString(R.string.stop_recording)
                binding.playButton.setImageResource(R.drawable.ic_recording_stop)
            } catch (e: Exception) {
                isPlaying = false
                Toast.makeText(requireContext(), "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDiscardConfirmation(filePath: String) {
        val rawFilePath = arguments?.getString("rawFilePath") ?: ""
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard Recording")
            .setMessage("Are you sure you want to discard this recording? It will be permanently deleted.")
            .setPositiveButton("Discard") { _, _ ->
                try { File(filePath).delete() } catch (_: Exception) {}
                if (rawFilePath.isNotEmpty()) {
                    try { File(rawFilePath).delete() } catch (_: Exception) {}
                }
                findNavController().navigateUp()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSaveDiscardDialog(filePath: String) {
        PlayerSaveDiscardDialog { action ->
            when (action) {
                PlayerSaveDiscardDialog.Action.SAVE -> {
                    val bundle = Bundle().apply { putString("recordingFilePath", filePath) }
                    findNavController().navigate(R.id.action_fullTimeOnPlayer_to_addPatient, bundle)
                }

                PlayerSaveDiscardDialog.Action.DISCARD -> {
                    try { File(filePath).delete() } catch (_: Exception) {}
                    findNavController().navigateUp()
                }
            }
        }.show(parentFragmentManager, "save_discard")
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
```

### 11.5 Screen F — additional decision-log detail beyond what §11 already covered

**Squeeze/zoom-out on the Player** (separate from Screen E's identical fix, ported here too):
pinch-zoom only ever let you zoom *in* — the ceiling (`setVisibleXRangeMaximum`) was capped at
the default window. Fixed by tracking `loadedDurationSecs` and using
`maxOf(currentWindowSeconds, loadedDurationSecs)` as the ceiling everywhere it's set
(`onVisibleSecondsChanged`'s else-branch, `renderWaveformEntries`, and a second bug found in the
same pass — `applyBuiltInDefaultScale()` via `forceVisibleSeconds()`'s own
`setVisibleXRangeMaximum` call, which had been silently re-narrowing the ceiling back down as a
side effect of Reset, undoing the fix the moment Reset was pressed).

**Apply as a no-op when nothing changed**: there's no separate "Done" button (Apply is the only
action), so pressing it without ever touching the graph must not silently re-save. Implemented
`hasUserAdjustedView` (Player only): set `true` inside `onChartGestureEnd`, checked by the Apply
handler (skips the save entirely, but still navigates back), reset to `false` after a real Apply
and after Reset.

**Bug: the mute toggle was muting real recordings too.** The mute gate (`isSpeakerMuted`, added
for PREVIEW) had been applied inside the shared `feedSpeaker()` helper, which both the preview
*and* the real-recording listener called — so muting during preview also silently muted a real
recording's live monitor, which was never the intent (recording audio was always unconditional
before the mute feature existed). Fixed by giving `feedSpeaker()` a `forceUnmuted: Boolean =
false` parameter; the real-recording listener now calls `feedSpeaker(data, forceUnmuted = true)`.
Also hid `speakerToggle` during `RECORDING` since it has no effect there.

**Known gaps**: no Player-side speaker/mute control (`TaalPlayer`'s internal `AudioTrack` is a
private field with no exposed volume/mute method; skipped rather than reworking `TaalPlayer`'s
audio path). Preview writes a real (if temp) file continuously — unavoidable per §2's "no
listen-only mode" fact; bounded to ~10 minutes per session, deleted aggressively. BPM still
computes during PREVIEW even though the graph doesn't render — not requested to be removed when
the live-preview-graph idea was dropped, left running as a reasonable auxiliary signal.
`resetToIdle()` runs twice in a row inside `onResume()`/`startPreview()` — harmless/idempotent, a
pre-existing redundancy, not worth optimizing. Amplitude mismatch between live recording and
review at non-default pre-amp — same already-accepted limitation as the Calibrated screens (§10):
the Player has no persisted per-file pre-amp metadata for recordings reopened later from the
library. `startDestination` churn — flipped between `recordingFragment`,
`calibratedRecordingFragment`, and `fullTimeOnRecordingFragment` several times during dev,
including once by unrelated concurrent work — always check the live comment in `nav_graph.xml`.

---

## 12. Nav wiring for Calibrated/FullTimeOn

### 12.1 `nav_graph.xml` additions for Calibrated (existing content untouched)

```xml
<fragment
    android:id="@+id/calibratedRecordingFragment"
    android:name="com.musediagnostics.taal.app.ui.calibrated.CalibratedRecordingFragment"
    android:label="Calibrated Recorder"
    tools:layout="@layout/fragment_calibrated_recording">
    <deepLink app:uri="taalapp://calibrated" />
    <action
        android:id="@+id/action_calibratedRecording_to_calibratedPlayer"
        app:destination="@id/calibratedPlayerFragment" />
    <action
        android:id="@+id/action_calibratedRecording_to_savedRecordings"
        app:destination="@id/savedRecordingsFragment" />
    <action
        android:id="@+id/action_calibratedRecording_to_dpiCalibration"
        app:destination="@id/dpiCalibrationFragment" />
</fragment>

<fragment
    android:id="@+id/calibratedPlayerFragment"
    android:name="com.musediagnostics.taal.app.ui.calibrated.CalibratedPlayerFragment"
    android:label="Calibrated Review"
    tools:layout="@layout/fragment_calibrated_player">
    <argument android:name="filePath" android:defaultValue="" app:argType="string" />
    <argument android:name="isNewRecording" android:defaultValue="false" app:argType="boolean" />
    <argument android:name="filterName" android:defaultValue="HEART" app:argType="string" />
    <action android:id="@+id/action_calibratedPlayer_to_equalizer" app:destination="@id/equalizerFragment" />
    <action android:id="@+id/action_calibratedPlayer_to_addPatient" app:destination="@id/addPatientFragment" />
    <action android:id="@+id/action_calibratedPlayer_to_saveRecording" app:destination="@id/saveRecordingFragment" />
</fragment>

<fragment
    android:id="@+id/dpiCalibrationFragment"
    android:name="com.musediagnostics.taal.app.ui.calibrated.DpiCalibrationFragment"
    android:label="Ruler Calibration"
    tools:layout="@layout/fragment_dpi_calibration" />
```

`rawFilePath` rides along as an undeclared `Bundle` extra to `calibratedPlayerFragment` (matches
how production's `playerFragment` handles it — Navigation allows undeclared bundle extras).
Save/discard/EQ flows deliberately **reuse existing production destinations**
(`saveRecordingFragment`, `addPatientFragment`, `equalizerFragment`, `savedRecordingsFragment`)
rather than duplicating them — those fragments are unprotected (only `PlayerFragment.kt`/
`RecordingFragment.kt` themselves are protected).

**Debug deep link**: `MainActivity`'s manifest gained one `<intent-filter>` for
`taalapp://calibrated` — reach the Calibrated Recorder directly without touching
`startDestination` at all:
```bash
adb shell am start -a android.intent.action.VIEW -d "taalapp://calibrated" <applicationId>
```
This exists because the natural in-app entry point (a `settingsButton` on production
`RecordingFragment`) turned out to be a dead end — that button is `visibility="gone"` in the
protected `fragment_recording.xml` and couldn't be unhidden without touching a protected file.

### 12.2 `nav_graph.xml` additions for FullTimeOn

```xml
<!-- FullTimeOn screens — clone of the Calibrated screens above under a new name, currently
     identical behavior, own destinations so it can evolve independently. -->
<fragment
    android:id="@+id/fullTimeOnRecordingFragment"
    android:name="com.musediagnostics.taal.app.ui.fulltimeon.FullTimeOnRecordingFragment"
    android:label="FullTimeOn Recorder"
    tools:layout="@layout/fragment_fulltimeon_recording">
    <action
        android:id="@+id/action_fullTimeOnRecording_to_fullTimeOnPlayer"
        app:destination="@id/fullTimeOnPlayerFragment" />
    <action
        android:id="@+id/action_fullTimeOnRecording_to_savedRecordings"
        app:destination="@id/savedRecordingsFragment" />
    <action
        android:id="@+id/action_fullTimeOnRecording_to_dpiCalibration"
        app:destination="@id/dpiCalibrationFragment" />
</fragment>

<fragment
    android:id="@+id/fullTimeOnPlayerFragment"
    android:name="com.musediagnostics.taal.app.ui.fulltimeon.FullTimeOnPlayerFragment"
    android:label="FullTimeOn Review"
    tools:layout="@layout/fragment_fulltimeon_player">
    <argument android:name="filePath" android:defaultValue="" app:argType="string" />
    <argument android:name="isNewRecording" android:defaultValue="false" app:argType="boolean" />
    <argument android:name="filterName" android:defaultValue="HEART" app:argType="string" />
    <argument android:name="preAmpDb" android:defaultValue="0" app:argType="integer" />
    <action android:id="@+id/action_fullTimeOnPlayer_to_equalizer" app:destination="@id/equalizerFragment" />
    <action android:id="@+id/action_fullTimeOnPlayer_to_addPatient" app:destination="@id/addPatientFragment" />
    <action android:id="@+id/action_fullTimeOnPlayer_to_saveRecording" app:destination="@id/saveRecordingFragment" />
</fragment>
```

Both share `savedRecordingsFragment`, `dpiCalibrationFragment`, `equalizerFragment`,
`addPatientFragment`, `saveRecordingFragment` with the rest of the app and with Calibrated — no
duplicates created for those. `FullTimeOn` shares Calibrated's `dpiCalibrationFragment` deep
link/destination — no separate one was created.

---

## 13. Cross-cutting "if you change X, check Y" table

| You want to change... | Also check | Why |
|---|---|---|
| Visible window seconds (Screen A) | `WINDOW_SECONDS` const, dummy dataset endpoints, `setVisibleXRangeMaximum/Minimum`, page-calc (`currentPage`), memory-cleanup (`minXToKeep`) | All derive from the same constant inside `RecordingFragment.kt`; none of it is shared with Player or any other screen |
| Downsample density | `DOWNSAMPLE_STEP` is declared **separately** in Screen A and Screen C — changing one does not change the other. Unrelated to `HeartBpmCalculator`'s own internal `downsampleFactor = 44` (different subsystem, computes BPM not the trace — coincidentally the same number) | Independently diverged code |
| Y-axis warmup behavior | `WARMUP_MS`/`HEADROOM`/`MIN_PEAK` exist in production `RecordingFragment.kt` AND (ported verbatim) in `FullTimeOnRecordingFragment.kt`. Screen C has no equivalent (axis hardcoded ±1.0 forever). Screen E uses a completely different mechanism (`FIXED_FULL_SCALE`, no warmup at all) | Three different Y-axis philosophies across six screens |
| Pre-amp display compensation | In `RecordingFragment.kt`, `CalibratedRecordingFragment.kt` (`COMPENSATE_PREAMP_IN_DISPLAY`), and `FullTimeOnRecordingFragment.kt` (same flag, ported). Screen C and all Player screens do **not** apply this — Players play back a file that already has gain baked in | Live-only concern, three separate copies of the same idea |
| WAV header / sample-rate parsing | Only `PlayerFragment.kt` (B), `CalibratedPlayerFragment.kt` (E), `FullTimeOnPlayerFragment.kt` (F) read the real header rate. `TestPlayerFragment.kt` (D) hardcodes 44100 — a known divergence | D was never updated to match B's fix |
| Adding the ECG paper grid to production A/B | Must also: (1) `chart.setDrawGridBackground(false)` + both axes' `setDrawGridLines(false)`; (2) add the paper view behind the chart with **matching** constraints AND margins (or better, adopt `CalibratedWaveformView`'s FrameLayout-stacking approach instead of separate XML margins); (3) decide whether the fixed-mm calibration-pulse/time-tick rules make sense at Player's 4s window vs Recording's 10s window | Two overlapping misaligned grids otherwise |
| Grid square physical size vs. what it means (`MmScale`/`CalibratedMmScale`) | Deliberately decoupled — `pxPerMmX/Y` (screen physicality) vs `paperSpeed`/`gain` (clinical meaning). Don't "simplify" by making speed/gain resize the grid | That's the exact invariant `MmScaleTest.kt`/`CalibratedMmScaleTest.kt` guard against |
| Bucket-size derivation for zoom (`deriveBucketSizeForVisibleRange`) | This exact class of bug ("bucket must track the *effective*, possibly-overridden window, not just native paper speed") was found and fixed **twice** independently — once in the Calibrated/FullTimeOn Player's pinch-zoom (Fix D), once later in the Calibrated/FullTimeOn Recorder once `GraphCalibration` overrides could narrow the window below native derivation | Same bug class, two screens, fixed separately |
| Line/trace color | Screen A: `R.color.waveform_blue` (`#2D7DD2`, resource). Screen B: same hex as a **literal**, not the resource. Screens C/D: `#128CB2` literal (different teal). Screens E/F: `R.color.waveform_blue` again (resource) | No single "the waveform color" exists across the app |
| `nav_graph.xml` `startDestination` | Contested between at least three concurrent efforts — always `grep` the live value + comment, never assume | See §15 |
| `CalibratedWaveformView`/`GraphCalibration`/`DpiCalibration` | Shared by **both** Calibrated and FullTimeOn — a change here affects both screen pairs simultaneously, including a saved calibration override | Deliberately shared infra, not per-screen-pair forks |

---

## 14. Timeline

| When | What |
|---|---|
| 2026-02 | Original `taal-sdk-project` rebuild from decompiled legacy code — became today's `taal-core`/`taal-ui-kit` |
| 2026-03-13 | Last actual git commit touching `nav_graph.xml` (everything since is uncommitted working-tree state) |
| 2026-03 – 2026-07 | Various UI-kit parity work, tablet audio-routing fix, SDK integration guide updates |
| 2026-08-14 (an earlier session) | `RECORDING_RELIABILITY_FIXES_2026-08.md` work — conditional USB lock, mid-recording disconnect handling, filtered-signal-based silent-recording detection (§2) |
| 2026-08-14 (this session) | `EcgPaperView`/`MmScale` built (first in `taal-ui-kit` by mistake, moved to `app` per explicit correction) and wired into `TestRecordingFragment` (Screen C); this handoff written |
| 2026-08-18/19 | Calibrated Recorder/Player (Screen E) built from scratch — mm-accurate grid, 5 accuracy fixes, DPI ruler calibration |
| 2026-08-20 | FullTimeOn (Screen F) cloned from Calibrated; `GraphCalibration` per-device pinch+Apply feature added to Calibrated, later shared by FullTimeOn; Fixed-scale + Kardia-grid task replaces Calibrated's adaptive Y-axis with `FIXED_FULL_SCALE` |
| 2026-08-21 | FullTimeOn's always-on live preview + speaker mute added, then simplified (dropped live graph during preview); Y-axis/window ported verbatim from production into FullTimeOn; several bugs found/fixed same day (calibration override not applying, mute toggle affecting real recordings) |
| 2026-08-22 | Mute-toggle/PREVIEW-only scoping fix |
| 2026-08-25 | FullTimeOn handoff doc's most recent recorded updates |

**Note on dates**: some source docs (FullTimeOn §6-11, Calibrated §9) carry dates from apparently
concurrent sessions working on this same repo. Trust the dates in each source doc over any single
session's own sense of "today."

---

## 15. Read this before touching anything — current-state checklist

1. **`nav_graph.xml`'s `startDestination`** — check live, don't trust this doc or any memory:
   ```bash
   grep -n "startDestination" app/src/main/res/navigation/nav_graph.xml
   ```
   If you change it for testing, **revert it to `@id/recordingFragment` when done** — this has
   been the single most repeated gotcha across every feature described above.
2. **Everything in §10–§12 is uncommitted.** Run `git status`/`git diff --stat` before assuming
   any of it is safe to discard, and be careful with any destructive git operation — `nav_graph.xml`
   specifically hasn't been committed since 2026-03-13, and at least one confirmed instance of
   concurrent editing on that exact file has already happened.
3. **Which of the 6 waveform implementations (§5) you're actually being asked about** — always
   ask if unclear, per §5's closing note.
4. **Never touch `RecordingFragment.kt`, `RecordingViewModel.kt`, `PlayerFragment.kt`,
   `TestRecordingFragment.kt`, `TestPlayerFragment.kt`, `MmScale.kt`, `EcgPaperView.kt`,
   `attrs_ecg_paper.xml`, `HeartBpmCalculator.kt`, `taal-core`, or `taal-ui-kit`** while working
   on Calibrated/FullTimeOn features — every one of those efforts explicitly protected this set
   and verified zero diff on it throughout their own development. The same courtesy is expected
   of future work.
5. **Two calibration systems, don't confuse them** (§10): `DpiCalibration` = physical accuracy
   (ruler-measured px-per-mm correction). `GraphCalibration` = visual preference (pinch+Apply
   Peak Size/Time Zoom override). Both are `SharedPreferences`-backed, both are shared between
   Calibrated and FullTimeOn, neither affects the other.
6. **`CalibratedEcgPaperView.kt` and other Calibrated/FullTimeOn files drift fast** — this doc's
   §10.2 code was verified directly against the live file on 2026-08-14 specifically because an
   earlier reference doc had already gone stale on it (missing the white-background change and
   the Kardia-style alpha/pixel-snap grid system). If resuming this work later, re-read the
   actual source files before trusting even this doc's code blocks as current — they were
   accurate at write time, not guaranteed accurate forever.

