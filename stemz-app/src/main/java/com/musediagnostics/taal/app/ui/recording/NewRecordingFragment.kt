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
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalRecorder
import com.musediagnostics.taal.core.RecorderState
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentNewRecordingBinding
import com.musediagnostics.taal.app.dsp.HeartBpmCalculator
import kotlinx.coroutines.*

/**
 * Standalone, isolated recording screen built on the new UI layout.
 * NOT wired into the nav graph — for experimental development only.
 *
 * Waveform logic is a direct port from RecordingFragment (V7):
 *   - Sample-accurate X positioning via totalSamplesProcessed counterwired into the nav graph
 *   - Two-phase Y-axis warmup: accumulate peak for 1500ms then lock
 *   - Persistent LineDataSet reuse to avoid MPAndroidChart blank frames
 *   - Viewport panning via moveViewToX() over a fixed 0–300s axis range
 */
class NewRecordingFragment : Fragment() {

    private var _binding: FragmentNewRecordingBinding? = null
    private val binding get() = _binding!!

    // SDK + audio
    private var taalRecorder: TaalRecorder? = null
    private var audioTrack: AudioTrack? = null

    // Waveform — same state fields as RecordingFragment
    private var lineChart: LineChart? = null
    private val waveformEntries = ArrayList<Entry>()
    private var waveformDataSet: LineDataSet? = null
    private var peakAmplitude = 1.0f
    private var lastPeakUpdateTime = 0L
    private var warmupPeak = 0f
    private var warmupDone = false
    private var totalSamplesProcessed = 0L

    // UI state
    private var isRecording = false
    private var currentPreFilter = PreFilter.HEART
    private var currentPreAmp = 5

    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val WINDOW_SECONDS = 10f
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val DOWNSAMPLE_STEP = 44
        private const val WARMUP_MS = 1500L
        private const val HEADROOM = 1.5f
        private const val MIN_PEAK = 0.02f
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
        _binding = FragmentNewRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupWaveformChart()
        setupFilterButtons()
        setupRecordButton()
        setupPreAmpSlider()
    }

    // -------------------------------------------------------------------------
    // Waveform chart setup (identical logic to RecordingFragment.setupWaveformChart)
    // -------------------------------------------------------------------------

    private fun setupWaveformChart() {
        val chart = LineChart(requireContext())
        lineChart = chart

        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setNoDataText("")
        chart.isAutoScaleMinMaxEnabled = false
        chart.isDragEnabled = false
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.setDrawMarkers(false)
        chart.setMaxVisibleValueCount(0)
        chart.setDrawGridBackground(false)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = Color.parseColor("#EAEAEA")
            gridLineWidth = 1f
            granularity = 1f
            axisMinimum = 0f
            axisMaximum = 300f
            textColor = Color.parseColor("#999999")
            textSize = 8f
            setLabelCount(6, false)
            setAvoidFirstLastClipping(true)
            setDrawAxisLine(false)
            setDrawLabels(true)
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#EAEAEA")
            gridLineWidth = 1f
            axisMinimum = -1f
            axisMaximum = 1f
            setLabelCount(5, true)
            setDrawLabels(false)
            setDrawAxisLine(false)
        }

        chart.axisRight.isEnabled = false
        chart.setExtraOffsets(2f, 4f, 2f, 4f)
        chart.setLayerType(View.LAYER_TYPE_NONE, null)

        // Seed with empty data so grid renders immediately (same fix as RecordingFragment)
        chart.setVisibleXRangeMaximum(10f)
        chart.data = LineData()
        chart.invalidate()

        // Inject the chart view into the FrameLayout container
        binding.fragmentContainerViewWaveForm.addView(
            chart,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    // -------------------------------------------------------------------------
    // Filter buttons — ImageView alpha to indicate selected state
    // -------------------------------------------------------------------------

    private fun setupFilterButtons() {
        val filterMap = linkedMapOf(
            binding.imageViewFlagHeart to PreFilter.HEART,
            binding.imageViewFlagLungs to PreFilter.LUNGS,
            binding.imageViewFlagBowel to PreFilter.BOWEL,
            binding.imageViewFlagPregnancy to PreFilter.PREGNANCY,
            binding.imageViewFlagBody to PreFilter.FULL_BODY
        )

        // HEART selected by default
        applyFilterSelection(binding.imageViewFlagHeart, filterMap.keys)

        filterMap.forEach { (imageView, filter) ->
            imageView.setOnClickListener {
                if (!isRecording) {
                    currentPreFilter = filter
                    applyFilterSelection(imageView, filterMap.keys)
                }
            }
        }

        // Custom button — placeholder, no filter logic yet
        binding.imageViewFlagCustom.alpha = 0.3f
    }

    private fun applyFilterSelection(selected: ImageView, all: Collection<ImageView>) {
        all.forEach { it.alpha = 0.35f }
        selected.alpha = 1.0f
    }

    // -------------------------------------------------------------------------
    // Record button
    // -------------------------------------------------------------------------

    private fun setupRecordButton() {
        binding.cardViewRecordingBtn.setOnClickListener {
            if (isRecording) stopRecording() else checkPermissionAndRecord()
        }
    }

    // -------------------------------------------------------------------------
    // Pre-amp slider
    // -------------------------------------------------------------------------

    private fun setupPreAmpSlider() {
        binding.seekBarPreAmplification.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentPreAmp = progress
                taalRecorder?.setPreAmplification(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // -------------------------------------------------------------------------
    // Recording lifecycle
    // -------------------------------------------------------------------------

    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) startRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        try {
            val filePath = "${requireContext().filesDir}/new_rec_${System.currentTimeMillis()}.wav"

            taalRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(filePath)
                setRecordingTime(300)
                setPreFilter(currentPreFilter)
                setPreAmplification(currentPreAmp)

                onInfoListener = object : TaalRecorder.OnInfoListener {
                    override fun onStateChange(state: RecorderState) {
                        activity?.runOnUiThread {
                            when (state) {
                                RecorderState.RECORDING -> onRecordingStarted()
                                RecorderState.STOPPED   -> onRecordingStopped()
                                else -> {}
                            }
                        }
                    }

                    override fun onProgressUpdate(
                        sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
                    ) {
                        // Live monitoring — write directly on the IO thread
                        audioTrack?.let { track ->
                            val pcm = ShortArray(data.size) { i ->
                                (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                            track.write(pcm, 0, pcm.size)
                        }

                        // BPM calculation
                        val shouldCompute = bpmCalculator.addSamples(data)
                        if (shouldCompute) {
                            bpmScope.launch {
                                val bpm = bpmCalculator.computeBpm()
                                if (bpm > 0) {
                                    withContext(Dispatchers.Main) {
                                        if (isAdded && _binding != null) {
                                            binding.textViewBPM.text = "$bpm BPM"
                                            binding.textViewBPM.visibility = View.VISIBLE
                                        }
                                    }
                                }
                            }
                        }

                        // Waveform + timer on main thread
                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                updateWaveform(data)
                                binding.textViewRecordingTime.text = formatTime(timeStamp.toInt())
                            }
                        }
                    }
                }
            }

            // Reset waveform state — same as RecordingFragment.startRecording()
            waveformEntries.clear()
            waveformDataSet = null
            peakAmplitude = 1.0f
            warmupPeak = 0f
            warmupDone = false
            lastPeakUpdateTime = 0L
            totalSamplesProcessed = 0L
            bpmCalculator.reset()
            lineChart?.data = LineData()
            lineChart?.moveViewToX(0f)

            startAudioMonitor()
            taalRecorder?.start()

        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
        taalRecorder = null
    }

    private fun onRecordingStarted() {
        isRecording = true
        binding.textViewRecordingAction.text = "Stop Recording"
        binding.imageViewRecord.setImageResource(R.drawable.ic_stop_recording)
        binding.cardViewRecordingBtn.setCardBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.recording_red)
        )
    }

    private fun onRecordingStopped() {
        isRecording = false
        stopAudioMonitor()
        binding.textViewRecordingAction.text = "Start Recording"
        binding.imageViewRecord.setImageResource(R.drawable.ic_stethoscope_blue)
        binding.cardViewRecordingBtn.setCardBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.white)
        )
        binding.textViewBPM.visibility = View.GONE
    }

    // -------------------------------------------------------------------------
    // Live audio monitoring
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Waveform update (V7 — direct port from RecordingFragment.updateWaveform)
    // -------------------------------------------------------------------------

    private fun updateWaveform(data: FloatArray) {
        val chart = lineChart ?: return
        if (_binding == null || !isAdded) return

        // Absolute peak of this buffer for Y-axis scaling
        var bufferPeak = 0f
        for (sample in data) {
            val abs = Math.abs(sample)
            if (abs > bufferPeak) bufferPeak = abs
        }

        // Add entries with sample-accurate X positions
        val step = DOWNSAMPLE_STEP
        for (i in 0 until data.size step step) {
            val x = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
            waveformEntries.add(Entry(x, data[i]))
            totalSamplesProcessed += step
        }
        val currentX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE

        // Trim entries older than 20s behind current position (keeps ~2× the visible window)
        if (currentX > 30f) {
            val cutoff = currentX - 20f
            val trimIndex = waveformEntries.indexOfFirst { it.x >= cutoff }
            if (trimIndex > 0) waveformEntries.subList(0, trimIndex).clear()
        }

        val now = System.currentTimeMillis()

        if (!warmupDone) {
            // Phase 1: accumulate peak; Y-axis stays at ±1.0
            if (bufferPeak > warmupPeak) warmupPeak = bufferPeak
            if (lastPeakUpdateTime == 0L) lastPeakUpdateTime = now

            if (now - lastPeakUpdateTime >= WARMUP_MS) {
                warmupDone = true
                peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
                chart.axisLeft.axisMinimum = -peakAmplitude
                chart.axisLeft.axisMaximum = peakAmplitude
            }
        } else {
            // Phase 2: only expand when signal exceeds current scale
            val needed = bufferPeak * HEADROOM
            if (needed > peakAmplitude) {
                peakAmplitude = needed.coerceAtMost(1.0f)
                chart.axisLeft.axisMinimum = -peakAmplitude
                chart.axisLeft.axisMaximum = peakAmplitude
            }
        }

        // Reuse persistent dataset — never replace chart.data to avoid blank frames
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

        // Pan viewport to follow waveform
        if (currentX > WINDOW_SECONDS) chart.moveViewToX(currentX - WINDOW_SECONDS)
        else chart.moveViewToX(0f)
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun formatTime(totalSeconds: Int): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "%02d:%02d".format(m, s)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        bpmScope.cancel()
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
    }
}
