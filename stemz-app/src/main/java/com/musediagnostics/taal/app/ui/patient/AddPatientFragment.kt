package com.musediagnostics.taal.app.ui.patient

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentAddPatientBinding
import com.musediagnostics.taal.app.ui.MainActivity
import java.util.Calendar

class AddPatientFragment : Fragment() {

    private var _binding: FragmentAddPatientBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PatientViewModel by viewModels()

    private var recordingFilePath: String = ""
    private lateinit var searchAdapter: PatientSearchAdapter

    // Selected values from tag dropdowns
    private var selectedSex: String = ""
    private var selectedDob: String = ""
    private var selectedAge: String = ""
    private var selectedWeight: String = ""
    private var selectedHeight: String = ""
    private var selectedDiffDiagnosis: String = ""
    private var selectedTentDiagnosis: String = ""
    private var selectedHistory: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddPatientBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recordingFilePath = arguments?.getString("recordingFilePath") ?: ""

        setupFileInfo()
        setupSearch()
        setupButtons()
        setupTagDropdowns()
        observeResults()
    }

    private fun setupFileInfo() {
        // Display file name from recording path
        if (recordingFilePath.isNotBlank()) {
            val fileName = recordingFilePath.substringAfterLast("/")
            binding.fileNameText.text = fileName
        }
    }

    private fun setupSearch() {
        // Initialize the search results adapter
        searchAdapter = PatientSearchAdapter { patient ->
            // Patient selected from search - attach recording to existing patient
            if (recordingFilePath.isNotBlank()) {
                viewModel.attachRecordingToExistingPatient(patient.id, recordingFilePath)
            }
            binding.searchInput.text?.clear()
            binding.searchResultsContainer.visibility = View.GONE
        }

        binding.patientSearchResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                viewModel.search(query)
                if (query.isNotBlank()) {
                    binding.searchResultsContainer.visibility = View.VISIBLE
                } else {
                    binding.searchResultsContainer.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        viewModel.searchResults.observe(viewLifecycleOwner) { patients ->
            if (binding.searchInput.text?.isNotBlank() == true) {
                binding.searchResultsContainer.visibility = View.VISIBLE
                if (patients.isNotEmpty()) {
                    searchAdapter.submitList(patients)
                    binding.patientSearchResults.visibility = View.VISIBLE
                } else {
                    searchAdapter.submitList(emptyList())
                    binding.patientSearchResults.visibility = View.GONE
                }
            } else {
                binding.searchResultsContainer.visibility = View.GONE
                searchAdapter.submitList(emptyList())
            }
        }

        // "Add New Patient" option clears search and focuses name input
        binding.addNewPatientOption.setOnClickListener {
            binding.searchInput.text?.clear()
            binding.searchResultsContainer.visibility = View.GONE
            binding.nameInput.requestFocus()
        }
    }

    private fun setupButtons() {
        // Menu button opens navigation drawer
        binding.menuButton.setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }

        // Discard button navigates back
        binding.discardButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Save button
        binding.savePatientButton.setOnClickListener {
            val fullName = binding.nameInput.text?.toString()?.trim() ?: ""
            val patientId = binding.patientIdInput.text?.toString()?.trim() ?: ""
            val phone = binding.phoneInput.text?.toString()?.trim() ?: ""
            val notes = binding.notesInput.text?.toString()?.trim() ?: ""

            viewModel.saveNewPatientWithRecording(
                fullName = fullName,
                patientId = patientId,
                phone = phone,
                email = "",
                dateOfBirth = selectedDob,
                biologicalSex = selectedSex,
                recordingFilePath = recordingFilePath
            )
        }

        // Edit file name button
        binding.editFileNameButton.setOnClickListener {
            // Could show a rename dialog - placeholder for now
            Toast.makeText(requireContext(), getString(R.string.rename_recording), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTagDropdowns() {
        // Differential Diagnosis dropdown
        binding.diffDiagnosisDropdown.setOnClickListener {
            showListDialog(
                title = getString(R.string.differential_diagnosis),
                options = arrayOf("Normal", "Murmur", "Arrhythmia", "Gallop", "Rub", "Other"),
                currentValue = selectedDiffDiagnosis
            ) { selected ->
                selectedDiffDiagnosis = selected
                binding.diffDiagnosisText.text = selected
            }
        }

        // Tentative Diagnosis dropdown
        binding.tentDiagnosisDropdown.setOnClickListener {
            showListDialog(
                title = getString(R.string.tentative_diagnosis),
                options = arrayOf("Normal", "Abnormal", "Inconclusive", "Other"),
                currentValue = selectedTentDiagnosis
            ) { selected ->
                selectedTentDiagnosis = selected
                binding.tentDiagnosisText.text = selected
            }
        }

        // Biological Sex dropdown
        binding.sexDropdown.setOnClickListener {
            showListDialog(
                title = getString(R.string.biological_sex),
                options = arrayOf("Male", "Female", "Other"),
                currentValue = selectedSex
            ) { selected ->
                selectedSex = selected
                binding.sexText.text = selected
            }
        }

        // DOB dropdown - opens date picker
        binding.dobDropdown.setOnClickListener {
            val calendar = Calendar.getInstance()
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    val dob = String.format("%02d/%02d/%04d", dayOfMonth, month + 1, year)
                    selectedDob = dob
                    binding.dobText.text = dob
                },
                calendar.get(Calendar.YEAR) - 30,
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // Age dropdown
        binding.ageDropdown.setOnClickListener {
            val ageOptions = (0..120).map { it.toString() }.toTypedArray()
            showListDialog(
                title = getString(R.string.age_label),
                options = ageOptions,
                currentValue = selectedAge
            ) { selected ->
                selectedAge = selected
                binding.ageText.text = selected
            }
        }

        // Weight dropdown
        binding.weightDropdown.setOnClickListener {
            val weightOptions = (1..200).map { "$it kg" }.toTypedArray()
            showListDialog(
                title = getString(R.string.weight_label),
                options = weightOptions,
                currentValue = selectedWeight
            ) { selected ->
                selectedWeight = selected
                binding.weightText.text = selected
            }
        }

        // Height dropdown
        binding.heightDropdown.setOnClickListener {
            val heightOptions = (30..250).map { "$it cm" }.toTypedArray()
            showListDialog(
                title = getString(R.string.height_label),
                options = heightOptions,
                currentValue = selectedHeight
            ) { selected ->
                selectedHeight = selected
                binding.heightText.text = selected
            }
        }

        // History dropdown
        binding.historyDropdown.setOnClickListener {
            showListDialog(
                title = getString(R.string.history_label),
                options = arrayOf("None", "Hypertension", "Diabetes", "Asthma", "Heart Disease", "Other"),
                currentValue = selectedHistory
            ) { selected ->
                selectedHistory = selected
                binding.historyText.text = selected
            }
        }
    }

    private fun showListDialog(
        title: String,
        options: Array<String>,
        currentValue: String,
        onSelected: (String) -> Unit
    ) {
        val checkedItem = options.indexOf(currentValue).coerceAtLeast(-1)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                onSelected(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeResults() {
        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is PatientViewModel.SaveResult.Success -> {
                    Toast.makeText(requireContext(), getString(R.string.recording_saved), Toast.LENGTH_SHORT).show()
                    // Navigate back to recording screen
                    findNavController().popBackStack(R.id.recordingFragment, false)
                }
                is PatientViewModel.SaveResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
