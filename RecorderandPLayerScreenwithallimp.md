# TAAL SDK — Recording & Player Screen Implementation Guide

This document gives you the complete, ready-to-use implementation of the Recording Screen and Player Screen built on top of `taal-core`. Use this when you want to integrate TAAL's clinically accurate waveform display and audio flow into your own app without pulling in `taal-ui-kit`.

---

## What You Get From taal-core

`taal-core` provides the low-level audio engine only:

| Class | Role |
|---|---|
| `TaalRecorder` | Captures USB audio, applies DSP in real-time, writes dual WAV files |
| `TaalPlayer` | Reads WAV file, applies DSP, plays via `AudioTrack` |
| `PreFilter` | Enum: `HEART`, `LUNGS`, `BOWEL`, `PREGNANCY`, `FULL_BODY` |
| `AudioFilterEngine` | Internal biquad bandpass + 5-band graphic EQ |
| `TaalDisconnectedException` | Thrown by `TaalRecorder.start()` if USB device is not connected |
| `InvalidFileNameException` | Thrown by `TaalPlayer.setDataSource()` for non-WAV files |

What `taal-core` does **not** provide: any UI, graph, BPM logic, or file management. That is all in this document.

---

## Audio Specifications

| Spec | Value |
|---|---|
| Sample rate | 44,100 Hz |
| Bit depth | 16-bit PCM |
| Channels | Mono |
| Format | WAV |
| Max frequency | 2,000 Hz (hard-coded in filter design) |
| Pre-amp range | 0–30 dB |

### Filter Frequency Bands

| PreFilter     | Low Cut | High Cut | Clinical Use         |
|---------------|---------|----------|----------------------|
| HEART         | 20 Hz   | 250 Hz   | Heart sounds (S1/S2) |
| LUNGS         | 100 Hz  | 600 Hz   | Lung auscultation    |
| BOWEL         | 100 Hz  | 600 Hz   | Bowel sounds         |
| PREGNANCY     | 20 Hz   | 250 Hz   | Fetal heart          |
| FULL_BODY     | 20 Hz   | 600 Hz   | General              |
| CUSTOM_FILTER | Custom  | Custom   | Custom               |

---

## Dependencies Required

Add these to your `build.gradle.kts` (app module):

```kotlin
// Waveform graph — mandatory for the waveform display
implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

// ViewModel + LiveData
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// Navigation (if using fragment navigation)
implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

// Material (for dialogs)
implementation("com.google.android.material:material:1.11.0")
```

Add JitPack to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

---

## Permissions

In `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

Request `RECORD_AUDIO` at runtime before calling `TaalRecorder.start()`.

---

## Recording Flow Overview

```
User taps Record
        │
        ▼
TaalRecorder.start()
        │ (USB check → throws TaalDisconnectedException if not connected)
        │
        ▼
  Two WAV files created simultaneously:
  ┌────────────────────────┐  ┌──────────────────────────────┐
  │ recording_{ts}_raw.wav │  │ recording_{ts}_filtered.wav  │
  │ (raw USB audio)        │  │ (DSP-filtered in real-time)  │
  └────────────────────────┘  └──────────────────────────────┘
        │
        ▼
  onProgressUpdate fires with DSP-filtered FloatArray
        │
        ├──► updateWaveform()  (ECG-style 10s scrolling graph)
        ├──► HeartBpmCalculator.addSamples()  (BPM every 5s)
        └──► AudioTrack.write()  (live monitor playback, optional)
        │
User taps Stop
        │
        ▼
Navigate to PlayerFragment
  with filePath = _filtered.wav (already DSP-applied)
       rawFilePath = _raw.wav
```

The player skips re-applying the preset filter for `_filtered.wav` files because DSP was already applied during recording. This prevents double-filtering.

---

## Part 1 — Recording Screen

### 1.1 — RecordingUiState

```kotlin
enum class RecordingUiState {
    IDLE,       // Pre-recording: shows start button
    RECORDING,  // Actively recording: shows stop button
    STOPPED     // Recording finished: unused in the current flow (navigation happens immediately)
}
```

---

### 1.2 — RecordingViewModel

```kotlin
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class RecordingViewModel : ViewModel() {

    private val _uiState = MutableLiveData(RecordingUiState.IDLE)
    val uiState: LiveData<RecordingUiState> = _uiState

    private val _timerSeconds = MutableLiveData(0)
    val timerSeconds: LiveData<Int> = _timerSeconds

    private val _currentFilter = MutableLiveData("HEART")
    val currentFilter: LiveData<String> = _currentFilter

    private val _bpm = MutableLiveData(0)
    val bpm: LiveData<Int> = _bpm

    private val _preAmpDb = MutableLiveData(5)
    val preAmpDb: LiveData<Int> = _preAmpDb

    // Plain vars — not LiveData — reset manually between recordings
    var currentRecordingPath: String = ""   // raw WAV path
    var currentFilteredPath: String = ""    // filtered WAV path

    fun setUiState(state: RecordingUiState) { _uiState.value = state }
    fun updateTimer(seconds: Int) { _timerSeconds.value = seconds }
    fun setFilter(filter: String) { _currentFilter.value = filter }
    fun setBpm(bpm: Int) { _bpm.value = bpm }
    fun setPreAmp(db: Int) { _preAmpDb.value = db.coerceIn(0, 30) }

    fun formatTimer(seconds: Int): String {
        val clamped = seconds.coerceAtLeast(0)
        val hours = clamped / 3600
        val minutes = (clamped % 3600) / 60
        val secs = clamped % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
}
```

---

### 1.3 — HeartBpmCalculator

This runs autocorrelation on the audio to detect heart rate. `addSamples()` is fast (just accumulates). `computeBpm()` is the heavy work — always call it on a background thread.

```kotlin
import kotlin.math.*

class HeartBpmCalculator(private val sampleRate: Int = 44100) {

    private val analysisRate = 1000
    private val downsampleFactor = sampleRate / analysisRate

    private val bufferDurationSec = 4.0
    private val bufferSize = (analysisRate * bufferDurationSec).toInt()
    private val audioBuffer = FloatArray(bufferSize)
    private var writePos = 0
    private var samplesCollected = 0

    private var dsAccum = 0f
    private var dsCount = 0

    @Volatile var lastBpm = 0
        private set

    @Volatile private var lastUpdateTime = 0L
    @Volatile private var computing = false

    /**
     * Feed audio data. Returns true when enough data is ready and 5s have
     * elapsed since the last compute. Call computeBpm() on a background thread
     * when this returns true.
     */
    fun addSamples(data: FloatArray): Boolean {
        for (sample in data) {
            dsAccum += abs(sample)
            dsCount++
            if (dsCount >= downsampleFactor) {
                audioBuffer[writePos] = dsAccum / dsCount
                writePos = (writePos + 1) % bufferSize
                samplesCollected++
                dsAccum = 0f
                dsCount = 0
            }
        }
        val now = System.currentTimeMillis()
        return !computing &&
               now - lastUpdateTime >= 5000L &&
               samplesCollected >= analysisRate * 3
    }

    /** MUST be called on a background thread. Returns 0 if BPM cannot be determined. */
    fun computeBpm(): Int {
        if (computing) return lastBpm
        computing = true
        lastUpdateTime = System.currentTimeMillis()
        try {
            val length = minOf(samplesCollected, bufferSize)
            val analysis = FloatArray(length)
            val startPos = (writePos - length + bufferSize) % bufferSize
            for (i in 0 until length) {
                analysis[i] = audioBuffer[(startPos + i) % bufferSize]
            }
            val bpm = calculateBpm(analysis)
            if (bpm > 0) lastBpm = bpm
            return lastBpm
        } catch (_: Exception) {
            return lastBpm
        } finally {
            computing = false
        }
    }

    private fun calculateBpm(envelope: FloatArray): Int {
        if (envelope.size < analysisRate * 2) return 0
        val maxVal = envelope.max()
        if (maxVal < 0.0001f) return 0
        val normalized = FloatArray(envelope.size) { envelope[it] / maxVal }

        // Smooth with sliding window to reduce noise
        val windowSize = 30
        val smoothed = FloatArray(normalized.size)
        var runningSum = 0f
        for (i in 0 until minOf(windowSize, normalized.size)) runningSum += normalized[i]
        for (i in normalized.indices) {
            val wEnd = i + windowSize
            if (wEnd < normalized.size) runningSum += normalized[wEnd]
            if (i > 0 && i - 1 < normalized.size) runningSum -= normalized[i - 1]
            val cnt = minOf(windowSize, normalized.size - i)
            smoothed[i] = if (cnt > 0) runningSum / cnt else 0f
        }

        // Autocorrelation lag search: 40 BPM (1.5s lag) to 150 BPM (0.4s lag)
        val minLag = (0.4 * analysisRate).toInt()
        val maxLag = (1.5 * analysisRate).toInt()
        val n = smoothed.size
        if (maxLag >= n) return 0

        var maxCorr = -1f
        var bestLag = 0
        for (lag in minLag..minOf(maxLag, n - 1)) {
            var sum = 0f
            val limit = n - lag
            for (t in 0 until limit) sum += smoothed[t] * smoothed[t + lag]
            val corr = sum / limit
            if (corr > maxCorr) { maxCorr = corr; bestLag = lag }
        }

        var autoZero = 0f
        for (t in smoothed.indices) autoZero += smoothed[t] * smoothed[t]
        autoZero /= n
        val normalizedCorr = if (autoZero > 0) maxCorr / autoZero else 0f

        // Reject weak correlations — signal too noisy or not heart audio
        if (normalizedCorr < 0.3f || bestLag == 0) return 0

        val period = bestLag.toFloat() / analysisRate
        val bpm = (60f / period).roundToInt()
        return if (bpm in 40..200) bpm else 0
    }

    fun reset() {
        audioBuffer.fill(0f)
        writePos = 0; samplesCollected = 0; lastBpm = 0
        lastUpdateTime = 0L; dsAccum = 0f; dsCount = 0; computing = false
    }
}
```

---

### 1.4 — RecordingFragment (Full Implementation)

```kotlin
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
    private var audioTrack: AudioTrack? = null            // for live monitor output
    private val waveformEntries = ArrayList<Entry>()

    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    // ── Waveform rendering state ──────────────────────────────────────────────
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
        // Waveform scrolls in 10-second pages
        private const val WINDOW_SECONDS = 10f
        private const val INPUT_SAMPLE_RATE = 44100f

        // Take 1 point every 44 input samples → ~1002 points/second displayed
        private const val DOWNSAMPLE_STEP = 44

        // Phase 1: first WARMUP_MS accumulate the peak without scaling
        // Phase 2: scale is locked permanently to warmupPeak × HEADROOM
        private const val WARMUP_MS = 2000L
        private const val HEADROOM = 1.5f
        private const val MIN_PEAK = 0.02f
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(requireContext(), "Audio permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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
    }

    // ── Chart setup ───────────────────────────────────────────────────────────

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

        // Lock view to exactly 10 seconds wide — no pinch/zoom during recording
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)

        // Invisible dummy dataset so the chart renders before recording starts
        val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(WINDOW_SECONDS, 0f)), "").apply {
            color = Color.TRANSPARENT
            setDrawCircles(false)
            setDrawValues(false)
        }
        chart.data = LineData(dummyDataSet)
        chart.invalidate()
    }

    // ── Pre-amp slider ────────────────────────────────────────────────────────

    private fun setupPreAmpSlider() {
        // Slider range must be 0..30 in your layout (valueFrom=0, valueTo=30)
        binding.ampSlider.value = (viewModel.preAmpDb.value ?: 5).toFloat()
        binding.ampLabel.text = "${viewModel.preAmpDb.value ?: 5} dB"

        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            viewModel.setPreAmp(db)
            binding.ampLabel.text = "$db dB"
            taalRecorder?.setPreAmplification(db)    // live update during recording
        }
    }

    // ── Filter chip buttons ───────────────────────────────────────────────────

    private fun setupFilterButtons() {
        val filters = mapOf(
            binding.filterHeart    to "HEART",
            binding.filterLungs    to "LUNGS",
            binding.filterBowel    to "BOWEL",
            binding.filterPregnancy to "PREGNANCY",
            binding.filterFullBody  to "FULL_BODY"
        )
        val currentFilter = viewModel.currentFilter.value ?: "HEART"
        filters.entries.find { it.value == currentFilter }?.key?.isSelected = true

        filters.forEach { (button, filterName) ->
            button.setOnClickListener {
                filters.keys.forEach { it.isSelected = false }
                button.isSelected = true
                viewModel.setFilter(filterName)
            }
        }
    }

    // ── USB connection icon ───────────────────────────────────────────────────

    private fun setupConnectionReceiver() {
        connectionReceiver = TaalConnectionBroadcastReceiver(object :
            TaalConnectionBroadcastReceiver.TaalConnectionListener {
            override fun onTaalConnect() {
                activity?.runOnUiThread {
                    binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))  // teal = connected
                }
            }
            override fun onTaalDisconnect() {
                activity?.runOnUiThread {
                    binding.deviceIcon.setColorFilter(Color.parseColor("#333333"))  // grey = disconnected
                }
            }
        })
        connectionReceiver?.register(requireContext())
    }

    private fun checkDeviceConnectionStatus() {
        try {
            val usbManager = requireContext().getSystemService(android.content.Context.USB_SERVICE)
                    as android.hardware.usb.UsbManager
            val connected = usbManager.deviceList.isNotEmpty()
            binding.deviceIcon.setColorFilter(
                Color.parseColor(if (connected) "#128CB2" else "#333333")
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ── Button click handlers ─────────────────────────────────────────────────

    private fun setupButtons() {
        binding.recordButton.setOnClickListener {
            when (viewModel.uiState.value) {
                RecordingUiState.IDLE      -> checkPermissionAndRecord()
                RecordingUiState.RECORDING -> stopRecording()
                else                       -> resetToIdle()
            }
        }

        // Play button only visible after a recording has been made in this session
        binding.playPauseButton.setOnClickListener {
            val filteredPath = viewModel.currentFilteredPath
            val rawPath      = viewModel.currentRecordingPath
            val filterName   = viewModel.currentFilter.value ?: "HEART"
            if (filteredPath.isNotEmpty()) {
                val bundle = Bundle().apply {
                    putString("filePath",        filteredPath)
                    putString("rawFilePath",     rawPath)
                    putBoolean("isNewRecording", false)
                    putString("filterName",      filterName)
                }
                findNavController().navigate(R.id.action_recording_to_player, bundle)
            }
        }
    }

    private fun resetToIdle() {
        viewModel.setUiState(RecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        viewModel.currentFilteredPath  = ""
        waveformEntries.clear()
        waveformDataSet = null
        chartInitialized = false
        peakAmplitude = 1.0f
        warmupPeak = 0f
        warmupDone = false
        lastPeakUpdateTime = 0L
        totalSamplesProcessed = 0L

        val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(10f, 0f)), "").apply {
            color = Color.TRANSPARENT; setDrawCircles(false); setDrawValues(false)
        }
        binding.waveformChart.data = LineData(dummyDataSet)
        binding.waveformChart.moveViewToX(0f)
        binding.waveformChart.invalidate()
        binding.bpmText.text = "-- BPM"
    }

    // ── UI state observer ─────────────────────────────────────────────────────

    private fun observeState() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                RecordingUiState.IDLE -> {
                    binding.actionText.text = "Start Recording"
                    binding.recordButton.setImageResource(R.drawable.ic_recording_start1)
                    binding.ampSlider.isEnabled = true
                    binding.ampSliderContainer.alpha = 1f
                    // Show your pre-recording controls here
                }
                RecordingUiState.RECORDING -> {
                    binding.actionText.text = "Stop Recording"
                    binding.recordButton.setImageResource(R.drawable.ic_recording_stop)
                    binding.ampSlider.isEnabled = false  // lock slider during recording
                    binding.ampSliderContainer.alpha = 0.55f
                    // Hide your pre-recording controls here
                }
                else -> {}
            }
        }

        viewModel.timerSeconds.observe(viewLifecycleOwner) { seconds ->
            binding.timerText.text = viewModel.formatTimer(seconds)
        }

        viewModel.bpm.observe(viewLifecycleOwner) { bpm ->
            binding.bpmText.text = if (bpm > 0) "$bpm BPM" else "-- BPM"
        }
    }

    // ── Permission + start ────────────────────────────────────────────────────

    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        try {
            val ts = System.currentTimeMillis()
            val rawFilePath      = "${requireContext().filesDir}/recording_${ts}_raw.wav"
            val filteredFilePath = "${requireContext().filesDir}/recording_${ts}_filtered.wav"
            viewModel.currentRecordingPath = rawFilePath
            viewModel.currentFilteredPath  = filteredFilePath

            val filterName = viewModel.currentFilter.value ?: "HEART"
            val preFilter  = PreFilter.valueOf(filterName)

            taalRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(rawFilePath)
                setFilteredAudioFilePath(filteredFilePath)
                setRecordingTime(300)                              // max 5 minutes
                setPlayback(false)                                 // we handle AudioTrack ourselves
                setPreAmplification(viewModel.preAmpDb.value ?: 5)
                setPreFilter(preFilter)

                onInfoListener = object : TaalRecorder.OnInfoListener {
                    override fun onStateChange(state: RecorderState) {
                        activity?.runOnUiThread {
                            when (state) {
                                RecorderState.RECORDING -> viewModel.setUiState(RecordingUiState.RECORDING)
                                RecorderState.STOPPED   -> viewModel.setUiState(RecordingUiState.STOPPED)
                                else -> {}
                            }
                        }
                    }

                    override fun onProgressUpdate(
                        sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
                    ) {
                        // ① Feed to live audio monitor output (optional — remove if not needed)
                        audioTrack?.let { track ->
                            val pcm = ShortArray(data.size) { i ->
                                (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                            track.write(pcm, 0, pcm.size)
                        }

                        // ② Feed to BPM calculator (fast — just accumulates)
                        val shouldCompute = bpmCalculator.addSamples(data)
                        if (shouldCompute) {
                            bpmScope.launch {
                                val bpm = bpmCalculator.computeBpm()
                                if (bpm > 0) {
                                    withContext(Dispatchers.Main) {
                                        if (isAdded && _binding != null) viewModel.setBpm(bpm)
                                    }
                                }
                            }
                        }

                        // ③ Waveform: undo pre-amp gain before drawing
                        //    The graph must show acoustic signal level, not the amplified version.
                        val preAmpDb   = viewModel.preAmpDb.value ?: 5
                        val preAmpGain = Math.pow(10.0, preAmpDb / 20.0).toFloat()
                        val displayData = if (preAmpGain > 1.001f) {
                            FloatArray(data.size) { i -> data[i] / preAmpGain }
                        } else {
                            data
                        }

                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                updateWaveform(timeStamp, displayData)
                                viewModel.updateTimer(timeStamp.toInt())
                            }
                        }
                    }
                }
            }

            // Reset all waveform state before starting
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

            startAudioMonitor()   // optional live playback
            taalRecorder?.start() // throws TaalDisconnectedException if USB not present

        } catch (e: Exception) {
            // Show error dialog — most common cause is TaalDisconnectedException
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording Error")
                .setMessage(e.message)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
            viewModel.setUiState(RecordingUiState.IDLE)
        }
    }

    // ── Optional: live audio monitor during recording ─────────────────────────

    private fun startAudioMonitor() {
        val minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC, 44100,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2, AudioTrack.MODE_STREAM
        ).apply { play() }
    }

    private fun stopAudioMonitor() {
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    // ── Stop recording ────────────────────────────────────────────────────────

    private fun stopRecording() {
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
        taalRecorder = null

        val filteredPath = viewModel.currentFilteredPath
        val rawPath      = viewModel.currentRecordingPath
        val filterName   = viewModel.currentFilter.value ?: "HEART"

        if (filteredPath.isNotEmpty()) {
            // Navigate immediately to player — the filtered file is ready on disk
            val bundle = Bundle().apply {
                putString("filePath",        filteredPath)
                putString("rawFilePath",     rawPath)
                putBoolean("isNewRecording", true)
                putString("filterName",      filterName)
            }
            findNavController().navigate(R.id.action_recording_to_player, bundle)
        }
    }

    // ── Waveform rendering (clinically accurate) ──────────────────────────────
    //
    // Design goals:
    //   • ECG-style 10-second scrolling window, page-by-page (not continuous scroll)
    //   • Y-axis auto-scales during a 2-second warmup, then locks permanently
    //   • Memory-safe: only keeps current page + previous page in RAM
    //   • No chart data replacement (avoids blank flash frames)
    //   • Sample-accurate X positioning (uses actual sample count, not timestamp)

    private fun updateWaveform(timestamp: Double, data: FloatArray) {
        if (_binding == null || !isAdded) return
        val chart = binding.waveformChart

        // Find the peak amplitude in this buffer (for warmup auto-scaling)
        var bufferPeak = 0f
        for (sample in data) {
            val abs = Math.abs(sample)
            if (abs > bufferPeak) bufferPeak = abs
        }

        // Downsample and accumulate waveform entries
        // DOWNSAMPLE_STEP=44 → ~1002 display points per second at 44100 Hz
        val step = DOWNSAMPLE_STEP
        for (i in 0 until data.size step step) {
            val currentX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
            waveformEntries.add(Entry(currentX, data[i]))
            totalSamplesProcessed += step
        }

        val latestX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE

        // Page-based memory cleanup — keep current 10s page and one previous page only
        val currentPage = (latestX / WINDOW_SECONDS).toInt()
        val minXToKeep = (currentPage - 1) * WINDOW_SECONDS
        if (minXToKeep > 0) {
            val iterator = waveformEntries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().x < minXToKeep) iterator.remove() else break
            }
        }

        val now = System.currentTimeMillis()

        // Phase 1: warmup — find the true peak of the signal over the first 2 seconds
        // Phase 2: lock the Y-axis permanently at warmupPeak × 1.5 headroom
        if (!warmupDone) {
            if (bufferPeak > warmupPeak) warmupPeak = bufferPeak
            if (lastPeakUpdateTime == 0L) lastPeakUpdateTime = now
            if (now - lastPeakUpdateTime >= WARMUP_MS) {
                warmupDone = true
                peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
                chart.axisLeft.axisMinimum = -peakAmplitude
                chart.axisLeft.axisMaximum =  peakAmplitude
            }
        }

        // Reuse the existing LineDataSet — replacing chart.data causes a blank frame flash
        val snapshot = ArrayList(waveformEntries.toList())
        val ds = waveformDataSet
        if (ds == null || chart.data == null) {
            waveformDataSet = LineDataSet(snapshot, "Waveform").apply {
                color = Color.parseColor("#1976D2")   // Replace with your brand color
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

        // Re-enforce 10s window (must be set every frame — notifyDataSetChanged can reset it)
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)

        // Snap view to the start of the current 10-second page (ECG page flip behavior)
        chart.moveViewToX(currentPage * WINDOW_SECONDS)

        chart.invalidate()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        checkDeviceConnectionStatus()
        if (taalRecorder == null) resetToIdle()
        // Always reset pre-amp to 5 dB on resume (clinical safety default)
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
```

---

## Part 2 — Player Screen

### 2.1 — PlayerFragment (Full Implementation)

The player receives the `_filtered.wav` path and renders the full waveform at load time. Playback scrolls the waveform in sync. The Save/Discard bar is shown only for freshly recorded files (`isNewRecording = true`).

```kotlin
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
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalPlayer
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
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath      = arguments?.getString("filePath")      ?: ""
        val rawFilePath   = arguments?.getString("rawFilePath")   ?: ""
        val isNewRecording = arguments?.getBoolean("isNewRecording", false) ?: false
        val filterName    = arguments?.getString("filterName")    ?: "HEART"

        // Save/Discard bar is only shown for fresh recordings, not when browsing saved files
        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()
        setupAmpSlider()

        if (filePath.isNotEmpty()) {
            loadFullWaveform(filePath)
            setupPlayer(filePath, filterName)
        }

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }

        binding.saveButton.setOnClickListener {
            showSaveDialog(filePath, rawFilePath, filterName)
        }

        binding.discardButton.setOnClickListener {
            confirmDiscard(filePath, rawFilePath)
        }
    }

    // ── Chart setup ───────────────────────────────────────────────────────────

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
            axisMaximum =  0.5f
            setDrawLabels(false)
            setDrawAxisLine(false)
        }

        chart.axisRight.isEnabled = false
    }

    // ── Pre-amp slider (real-time during playback) ────────────────────────────

    private fun setupAmpSlider() {
        // Slider range must be 0..30 in your layout
        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            binding.ampLabel.text = "$db dB"
            player?.setPreAmplification(db.toFloat())  // live update — TaalPlayer applies it immediately
        }
    }

    // ── Load full waveform from WAV bytes ─────────────────────────────────────
    //
    // WAV format: 44-byte header, then interleaved 16-bit little-endian PCM samples.
    // We read the raw bytes directly — no TaalPlayer needed for the graph.
    // sampleStep limits total drawn points to ~3000 for performance regardless of duration.

    private fun loadFullWaveform(filePath: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@launch

                val bytes = file.readBytes()
                val dataSize = bytes.size - 44           // subtract WAV header
                val totalSamples = dataSize / 2          // 2 bytes per 16-bit sample
                val totalDurationSeconds = (totalSamples / INPUT_SAMPLE_RATE).toInt()

                // Downsample to ~3000 display points regardless of recording length
                val sampleStep = maxOf(1, totalSamples / 3000)
                val entries = ArrayList<Entry>()
                var sampleIndex = 0

                for (i in 44 until bytes.size - 1 step sampleStep * 2) {
                    val low  = bytes[i].toInt() and 0xFF
                    val high = bytes[i + 1].toInt() shl 8
                    val sample = (high or low).toShort().toFloat() / 32768f
                    entries.add(Entry(sampleIndex.toFloat() / INPUT_SAMPLE_RATE, sample))
                    sampleIndex += sampleStep
                }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext

                    binding.timerText.text = String.format(
                        "%02d:%02d", totalDurationSeconds / 60, totalDurationSeconds % 60
                    )

                    val dataSet = LineDataSet(entries, "Waveform").apply {
                        color = Color.parseColor("#2D7DD2")
                        setDrawCircles(false)
                        setDrawValues(false)
                        lineWidth = 2.5f
                        mode = LineDataSet.Mode.LINEAR
                    }
                    binding.waveformChart.apply {
                        data = LineData(dataSet)
                        setVisibleXRangeMaximum(4f)       // show 4-second window by default
                        centerViewTo(2f, 0f, YAxis.AxisDependency.LEFT)
                        setTouchEnabled(true)
                        isDragEnabled = true
                        setScaleEnabled(true)
                        invalidate()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ── TaalPlayer setup ──────────────────────────────────────────────────────

    private fun setupPlayer(filePath: String, filterName: String) {
        try {
            player = TaalPlayer(requireContext()).apply {
                setDataSource(filePath)

                // Critical: _filtered.wav files were already DSP-processed during recording.
                // Do NOT call setPreFilter() on them — that would apply the bandpass filter twice,
                // destroying the clinical frequency response.
                if (!File(filePath).name.contains("_filtered")) {
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
                            // Scroll waveform to follow playback position
                            val chart    = binding.waveformChart
                            val halfRange = chart.visibleXRange / 2f
                            val centerX   = if (timestamp.toFloat() < halfRange) halfRange else timestamp.toFloat()
                            chart.centerViewTo(centerX, 0f, YAxis.AxisDependency.LEFT)
                        }
                    }
                }

                onPlaybackComplete = {
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            isPlaying = false
                            binding.actionText.text = "Play Recording"
                            binding.playButton.setImageResource(R.drawable.ic_play_circle)
                            // Snap waveform back to start
                            binding.waveformChart.centerViewTo(
                                binding.waveformChart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT
                            )
                        }
                    }
                }
            }
        } catch (e: InvalidFileNameException) {
            Toast.makeText(requireContext(), "Cannot open recording — only .wav files are supported", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Playback toggle ───────────────────────────────────────────────────────
    //
    // TaalPlayer must be prepare()d before each play() call. It is recreated (via setupPlayer)
    // whenever the fragment opens, so prepare() → start() is always safe here.

    private fun togglePlayback(filePath: String) {
        if (isPlaying) {
            player?.stop()
            isPlaying = false
            binding.actionText.text = "Play Recording"
            binding.playButton.setImageResource(R.drawable.ic_play_circle)
            binding.waveformChart.centerViewTo(
                binding.waveformChart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT
            )
        } else {
            try {
                binding.waveformChart.centerViewTo(
                    binding.waveformChart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT
                )
                player?.prepare()
                player?.start()
                isPlaying = true
                binding.actionText.text = "Stop Playback"
                binding.playButton.setImageResource(R.drawable.ic_recording_stop)
            } catch (e: Exception) {
                isPlaying = false
                Toast.makeText(requireContext(), "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Save dialog ───────────────────────────────────────────────────────────

    private fun showSaveDialog(filePath: String, rawFilePath: String, filterName: String) {
        // Implement your own save dialog. The save logic:
        //   1. Generate a safe filename (e.g. "{filterName}_{yyyyMMdd_HHmmss}")
        //   2. Create filesDir/saved/ directory
        //   3. Rename (or copy+delete) tempFile → savedDir/{name}_filtered.wav
        //   4. Rename rawFilePath → savedDir/{name}_raw.wav  (best-effort)
        //   5. Write a .meta file with the filterName for each variant
        //   6. Delete temp files on success
        //
        // See TaalSaveDialog.kt in taal-ui-kit for the full reference implementation.
    }

    // ── Discard ───────────────────────────────────────────────────────────────

    private fun confirmDiscard(filePath: String, rawFilePath: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard Recording?")
            .setMessage("This recording will be permanently deleted.")
            .setPositiveButton("Discard") { _, _ ->
                try { File(filePath).delete() } catch (_: Exception) {}
                if (rawFilePath.isNotEmpty()) {
                    try { File(rawFilePath).delete() } catch (_: Exception) {}
                }
                findNavController().navigateUp()
            }
            .setNegativeButton("Keep") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
```

---

### 2.2 — Save Dialog Reference Implementation

This is the full logic for the save dialog that renames temp files to permanent saved files.

```kotlin
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaalSaveDialog(
    private val tempFilePath: String,
    private val rawTempFilePath: String = "",
    private val filterName: String,
    private val onSaved: (finalPath: String) -> Unit,
    private val onCancelled: () -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_taal_save, null)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Default filename: "{yyyyMMdd_HHmmss}"
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        view.findViewById<TextInputEditText>(R.id.fileNameInput).setText(dateStr)

        view.findViewById<Button>(R.id.saveButton).setOnClickListener {
            val name = view.findViewById<TextInputEditText>(R.id.fileNameInput)
                .text?.toString()?.trim()

            if (name.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "Please enter a file name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val tempFile = File(tempFilePath)
            if (!tempFile.exists()) {
                Toast.makeText(requireContext(), "Recording file not found", Toast.LENGTH_SHORT).show()
                dismiss(); onCancelled(); return@setOnClickListener
            }

            // Strip characters that are illegal in filenames
            val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val savedDir = File(requireContext().filesDir, "saved").also { it.mkdirs() }

            // Save filtered WAV
            val filteredFile = File(savedDir, "${safeName}_filtered.wav")
            val savedFilteredFile = if (tempFile.renameTo(filteredFile)) {
                filteredFile
            } else {
                try {
                    tempFile.copyTo(filteredFile, overwrite = true)
                    tempFile.delete()
                    filteredFile
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to save file", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            // Save raw WAV (best-effort — failure here does not block the save)
            if (rawTempFilePath.isNotEmpty()) {
                val rawTemp = File(rawTempFilePath)
                val rawFile = File(savedDir, "${safeName}_raw.wav")
                if (rawTemp.exists()) {
                    if (!rawTemp.renameTo(rawFile)) {
                        try { rawTemp.copyTo(rawFile, overwrite = true); rawTemp.delete() } catch (_: Exception) {}
                    }
                }
            }

            // Write a .meta sidecar file containing the filter name for each WAV variant.
            // Used by the saved recordings list to show the correct filter icon.
            try {
                for (suffix in listOf("_filtered", "_raw")) {
                    File(savedDir, "$safeName$suffix.meta").writeText(filterName)
                }
            } catch (_: Exception) {}

            dismiss()
            onSaved(savedFilteredFile.absolutePath)
        }

        view.findViewById<Button>(R.id.cancelButton).setOnClickListener {
            dismiss()
            onCancelled()
        }

        return dialog
    }
}
```

---

## Part 3 — Critical Design Notes

### Why the waveform is built the way it is

This is the most important part to understand. The waveform rendering is not a simple plot — it is engineered for clinical reliability.

#### Y-Axis Auto-Scale (Warmup Phase)

Raw stethoscope audio varies dramatically in amplitude between patients, body sites, and clothing. A fixed Y-axis like `-1.0 / +1.0` would either clip loud signals or show nothing for quiet signals.

The approach taken:
- **Phase 1 (0–2 seconds)**: accumulate the peak amplitude seen in each audio buffer. The Y-axis stays at ±1.0.
- **Phase 2 (after 2 seconds)**: lock the Y-axis permanently at `warmupPeak × 1.5`. This `1.5` headroom prevents clipping on slight increases in signal after the warmup period.

```kotlin
// Phase 1: accumulate
if (bufferPeak > warmupPeak) warmupPeak = bufferPeak

// Phase 2: lock
if (now - lastPeakUpdateTime >= WARMUP_MS) {
    warmupDone = true
    peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
    chart.axisLeft.axisMinimum = -peakAmplitude
    chart.axisLeft.axisMaximum =  peakAmplitude
}
```

Once locked, the axis does not expand or contract. This is intentional — a clinician watching the graph in real-time needs a stable baseline. If the axis kept rescaling, it would be impossible to visually detect amplitude changes (which are clinically meaningful).

#### Page-Based Scrolling (Not Continuous Scroll)

The waveform scrolls in 10-second pages, just like an ECG strip recorder. When the graph fills 10 seconds, it jumps to the next page. This is achieved by:

```kotlin
val currentPage = (latestX / WINDOW_SECONDS).toInt()
chart.moveViewToX(currentPage * WINDOW_SECONDS)
```

Continuous scrolling (moveViewToX on every frame) would make it impossible for a clinician to read the waveform in real-time, as the signal would always be on the far right edge.

#### No Dataset Replacement During Recording

```kotlin
// CORRECT: update values in the existing dataset
ds.values = snapshot
chart.data?.notifyDataChanged()

// WRONG: this causes a blank flash every frame
chart.data = LineData(newDataSet)
```

Replacing `chart.data` on every audio buffer update causes a single-frame blank because MPAndroidChart re-initializes its renderer. On a 44100 Hz stream this happens ~40 times per second, creating visible flickering.

#### Pre-Amp Removal Before Drawing

```kotlin
val preAmpGain = Math.pow(10.0, preAmpDb / 20.0).toFloat()
val displayData = FloatArray(data.size) { i -> data[i] / preAmpGain }
```

`onProgressUpdate` delivers the amplified, filtered audio. But the graph should show the acoustic signal level — not the boosted version. If a clinician sets 20 dB pre-amp and the graph shows the amplified signal, the waveform amplitude becomes meaningless as a clinical measurement. This division is done before passing data to `updateWaveform()`.

#### Memory Safety (Page Cleanup)

Without cleanup, `waveformEntries` would grow indefinitely. Entries from more than one page ago are removed on every frame:

```kotlin
val minXToKeep = (currentPage - 1) * WINDOW_SECONDS
waveformEntries.removeAll { it.x < minXToKeep }
```

This keeps at most ~20,000 entries in RAM (current 10s page + previous 10s page).

---

### Why dual files (raw + filtered)?

| File | What it contains | Used for |
|---|---|---|
| `_raw.wav` | Unprocessed USB audio | AI model input, future re-processing with different filters |
| `_filtered.wav` | DSP-filtered + amplified audio | Playback, waveform display, sharing |

When the player receives a `_filtered.wav`, it must **not** call `setPreFilter()` again on the `TaalPlayer`. The filter was already applied during recording. Double-filtering would produce the wrong frequency response:

```kotlin
// This is the guard in PlayerFragment:
if (!File(filePath).name.contains("_filtered")) {
    setPreFilter(preFilter)   // only apply for raw files
}
```

---

### TaalPlayer lifecycle (must be followed exactly)

```kotlin
// Correct sequence for each playback:
player = TaalPlayer(context)
player.setDataSource(filePath)
// optionally: player.setPreFilter(filter)
// optionally: player.setPreAmplification(db)
player.prepare()   // creates and configures AudioTrack
player.start()     // starts coroutine that reads+plays audio

// To stop:
player.stop()

// When done (fragment destroyed):
player.stop()
player.release()   // releases AudioTrack
```

`TaalPlayer` is always released and recreated per fragment view. Never reuse a released `TaalPlayer`.

---

## Part 4 — Layout Requirements

Your layout files need these views (IDs must match). Here is the minimum set:

### fragment_recording.xml

```xml
<!-- Waveform chart — REQUIRED. Must be com.github.mikephil.charting.charts.LineChart -->
<com.github.mikephil.charting.charts.LineChart
    android:id="@+id/waveformChart"
    android:layout_width="match_parent"
    android:layout_height="200dp" />

<!-- Timer display -->
<TextView android:id="@+id/timerText" ... />

<!-- BPM display -->
<TextView android:id="@+id/bpmText" ... />

<!-- Record/Stop button -->
<ImageButton android:id="@+id/recordButton" ... />

<!-- Label above record button -->
<TextView android:id="@+id/actionText" ... />

<!-- Pre-amp slider (valueFrom="0", valueTo="30", stepSize="1") -->
<com.google.android.material.slider.Slider
    android:id="@+id/ampSlider"
    app:valueFrom="0"
    app:valueTo="30"
    app:stepSize="1" />

<!-- Pre-amp dB label -->
<TextView android:id="@+id/ampLabel" ... />

<!-- Container for the slider — we change its alpha when recording -->
<LinearLayout android:id="@+id/ampSliderContainer" ...>

<!-- USB device connection icon -->
<ImageView android:id="@+id/deviceIcon" ... />

<!-- Filter selection buttons -->
<Button android:id="@+id/filterHeart" ... />
<Button android:id="@+id/filterLungs" ... />
<Button android:id="@+id/filterBowel" ... />
<Button android:id="@+id/filterPregnancy" ... />
<Button android:id="@+id/filterFullBody" ... />

<!-- Navigation to saved recordings list -->
<ImageButton android:id="@+id/folderButton" ... />

<!-- Play last recording button (shown after recording stops) -->
<ImageButton android:id="@+id/playPauseButton" ... />

<!-- Containers that toggle visibility -->
<LinearLayout android:id="@+id/bottomBar" ... />
<LinearLayout android:id="@+id/preRecordingButtons" ... />
<LinearLayout android:id="@+id/recordingButtons" ... />
```

### fragment_player.xml

```xml
<com.github.mikephil.charting.charts.LineChart
    android:id="@+id/waveformChart"
    android:layout_width="match_parent"
    android:layout_height="200dp" />

<TextView android:id="@+id/timerText" ... />
<TextView android:id="@+id/actionText" ... />

<ImageButton android:id="@+id/playButton" ... />
<ImageButton android:id="@+id/backButton" ... />

<!-- Only visible for new recordings (isNewRecording = true) -->
<LinearLayout android:id="@+id/saveDiscardBar" android:visibility="gone" ...>
    <Button android:id="@+id/saveButton" ... />
    <Button android:id="@+id/discardButton" ... />
</LinearLayout>

<!-- Pre-amp slider for playback (valueFrom="0", valueTo="30") -->
<com.google.android.material.slider.Slider
    android:id="@+id/ampSlider"
    app:valueFrom="0"
    app:valueTo="30"
    app:stepSize="1" />
<TextView android:id="@+id/ampLabel" ... />
```

---

## Part 5 — Minimum Integration Checklist

- [ ] `taal-core-release.aar` added to `app/libs/`
- [ ] `implementation(files("libs/taal-core-release.aar"))` in app `build.gradle.kts`
- [ ] JitPack repo added (for MPAndroidChart transitive dependency)
- [ ] `com.github.PhilJay:MPAndroidChart:v3.1.0` added as dependency
- [ ] `RECORD_AUDIO` permission in manifest
- [ ] `RECORD_AUDIO` runtime permission requested before `TaalRecorder.start()`
- [ ] Navigation graph has `action_recording_to_player` with `filePath`, `rawFilePath`, `isNewRecording`, `filterName` arguments
- [ ] `LineChart` view in both fragment layouts with the correct IDs
- [ ] `Slider` in both fragments with `valueFrom=0`, `valueTo=30`, `stepSize=1`
- [ ] `TaalPlayer.prepare()` called before every `TaalPlayer.start()`
- [ ] `TaalPlayer.stop()` + `TaalPlayer.release()` called in `onDestroyView()`
- [ ] Pre-amp slider reset to 5 dB in `onResume()` (clinical safety default)
