package com.musediagnostics.taal.app.ui.recording

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.data.db.TaalDatabase
import com.musediagnostics.taal.app.data.repository.PatientRepository
import com.musediagnostics.taal.app.data.repository.RecordingRepository
import com.musediagnostics.taal.app.databinding.FragmentEditRecordingBinding
import com.musediagnostics.taal.app.util.WavCropper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditRecordingFragment : Fragment() {

    private var _binding: FragmentEditRecordingBinding? = null
    private val binding get() = _binding!!

    private var recordingId = -1L  // stored as Long; received as Int from nav args
    private var filePath = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recordingId = (arguments?.getInt("recordingId", -1) ?: -1).toLong()
        filePath = arguments?.getString("filePath") ?: ""

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        setupSliders()
        loadRecordingData()
        loadWaveformPreview()

        binding.applyButton.setOnClickListener {
            applyChanges()
        }
    }

    private fun setupSliders() {
        binding.amplifySlider.addOnChangeListener { _, value, _ ->
            binding.amplifyLabel.text = String.format("Amplify (%ddB)", value.toInt())
        }

        binding.amplifyCheckbox.setOnCheckedChangeListener { _, isChecked ->
            binding.amplifySlider.isEnabled = isChecked
        }
        binding.amplifySlider.isEnabled = false

        binding.noiseReductionCheckbox.setOnCheckedChangeListener { _, isChecked ->
            binding.noiseReductionSlider.isEnabled = isChecked
        }
        binding.noiseReductionSlider.isEnabled = false
    }

    private fun loadRecordingData() {
        if (recordingId <= 0) return

        val db = TaalDatabase.getInstance(requireContext())
        val recordingRepo = RecordingRepository(db.recordingDao())
        val patientRepo = PatientRepository(db.patientDao())

        lifecycleScope.launch {
            val recording = withContext(Dispatchers.IO) {
                recordingRepo.getRecordingById(recordingId)
            } ?: return@launch

            filePath = recording.filePath

            binding.bpmBadge.text = String.format("%d BPM", recording.bpm)

            val patient = if (recording.patientId != null) {
                withContext(Dispatchers.IO) {
                    patientRepo.getPatientById(recording.patientId)
                }
            } else null

            if (patient != null) {
                binding.patientNameText.text = patient.fullName
                binding.patientIdText.text = "ID: ${patient.patientId}"
                binding.nameEditText.setText(patient.fullName)
                binding.patientIdEditText.setText(patient.patientId)
            } else {
                binding.patientNameText.text = recording.fileName
                binding.patientIdText.text = ""
            }
        }
    }

    private fun loadWaveformPreview() {
        if (filePath.isEmpty()) return

        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                WavCropper.getWaveformData(filePath, 300)
            }

            if (_binding == null || data.isEmpty()) return@launch

            val chart = binding.waveformPreview
            chart.description.isEnabled = false
            chart.legend.isEnabled = false
            chart.setTouchEnabled(false)
            chart.setDrawGridBackground(true)
            chart.setGridBackgroundColor(Color.parseColor("#F8F8F8"))
            chart.xAxis.isEnabled = false
            chart.axisLeft.isEnabled = false
            chart.axisRight.isEnabled = false

            val entries = data.mapIndexed { i, v -> Entry(i.toFloat(), v) }
            val dataSet = LineDataSet(entries, "").apply {
                color = Color.parseColor("#2D7DD2")
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 1f
            }
            chart.data = LineData(dataSet)
            chart.invalidate()
        }
    }

    private fun applyChanges() {
        val amplifyEnabled = binding.amplifyCheckbox.isChecked
        val amplifyDb = binding.amplifySlider.value.toInt()
        val noiseEnabled = binding.noiseReductionCheckbox.isChecked
        val noiseLevel = binding.noiseReductionSlider.value.toInt()

        val message = buildString {
            if (amplifyEnabled) append("Amplify: ${amplifyDb}dB ")
            if (noiseEnabled) append("Noise Reduction: $noiseLevel ")
            if (!amplifyEnabled && !noiseEnabled) append("No changes applied")
        }

        Toast.makeText(requireContext(), message.trim(), Toast.LENGTH_SHORT).show()

        // Update patient details if changed
        if (recordingId > 0) {
            val db = TaalDatabase.getInstance(requireContext())
            val recordingRepo = RecordingRepository(db.recordingDao())

            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val recording = recordingRepo.getRecordingById(recordingId)
                    if (recording != null) {
                        recordingRepo.updateRecording(
                            recording.copy(
                                preAmplification = if (amplifyEnabled) amplifyDb.toFloat() else recording.preAmplification
                            )
                        )
                    }
                }
                findNavController().navigateUp()
            }
        } else {
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
