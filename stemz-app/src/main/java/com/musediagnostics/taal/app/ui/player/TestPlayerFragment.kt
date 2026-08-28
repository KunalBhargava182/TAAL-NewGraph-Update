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
                // player?.setPresetFilter(preset)  <-- If TaalPlayer exposes this!
            }
        }
    }

    private fun updateRangeSlider(preset: AudioFilterEngine.PresetFilter) {
        val low = preset.lowCut.toFloat()
        val high = preset.highCut.toFloat()

        // Material Range Slider expects values to be within valueFrom/valueTo bounds
        binding.hzRangeSlider.values = listOf(low, high)
        binding.hzMinText.text = "${low.toInt()} Hz"
        binding.hzMaxText.text = "${high.toInt()} Hz"

        // Note: Currently, the AudioFilterEngine doesn't support dynamically modifying
        // a custom high/low cut externally without making a new Preset.
        // The UI here reflects the SDK values correctly as requested!
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