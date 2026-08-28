package com.musediagnostics.taal.app.ui.library

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.TaalApplication
import com.musediagnostics.taal.app.data.db.entity.RecordingWithPatient
import com.musediagnostics.taal.app.data.repository.RecordingRepository
import com.musediagnostics.taal.app.databinding.FragmentRecordingLibraryBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class RecordingLibraryFragment : Fragment() {

    private var _binding: FragmentRecordingLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var patientAdapter: PatientLibraryAdapter
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var patientRepository: com.musediagnostics.taal.app.data.repository.PatientRepository
    private val expandedPatientIds = mutableSetOf<Long>()
    private var allRecordings: List<RecordingWithPatient> = emptyList()

    private var currentSearchQuery: String = ""
    private var currentFilterType: String? = null // null means "All"
    private var currentSortOrder: SortOrder = SortOrder.NEWEST_FIRST
    private var collectJob: Job? = null

    private enum class SortOrder {
        NEWEST_FIRST,
        OLDEST_FIRST,
        NAME_AZ,
        NAME_ZA
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = TaalApplication.instance.database
        recordingRepository = RecordingRepository(db.recordingDao())
        patientRepository = com.musediagnostics.taal.app.data.repository.PatientRepository(db.patientDao())

        setupPatientInfo()
        setupRecyclerView()
        setupSearch()
        setupFilterButton()
        setupFilterChips()
        setupBackButton()
        observeRecordings()
    }

    private fun setupPatientInfo() {
        // Patient info will be populated from the first recording's patient data
        // or from arguments if passed via navigation
        val patientName = arguments?.getString("patientName")
        val patientId = arguments?.getString("patientId")
        val visitId = arguments?.getString("visitId")
        val age = arguments?.getString("age")
        val gender = arguments?.getString("gender")
        val phone = arguments?.getString("phone")

        if (!patientName.isNullOrBlank()) {
            binding.patientNameText.text = patientName
        }

        if (!patientId.isNullOrBlank() || !visitId.isNullOrBlank()) {
            binding.patientInfoText.text = getString(
                R.string.patient_info_format,
                patientId ?: "---",
                visitId ?: "---"
            )
        } else {
            binding.patientInfoText.visibility = View.GONE
        }

        if (!age.isNullOrBlank() || !gender.isNullOrBlank() || !phone.isNullOrBlank()) {
            binding.demographicsText.text = getString(
                R.string.demographics_format,
                age ?: "--",
                gender ?: "--",
                phone ?: "--"
            )
        } else {
            binding.demographicsText.visibility = View.GONE
        }
    }

    private fun setupRecyclerView() {
        patientAdapter = PatientLibraryAdapter(
            onRecordingClick = { item -> navigateToPlayer(item) },
            onEditClick = { item -> showRenameDialog(item) },
            onShareClick = { item -> shareRecording(item) },
            onDeleteClick = { item -> showDeleteConfirmation(item) },
            onToggleExpand = { position -> toggleExpand(position) }
        )

        binding.recordingsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = patientAdapter
        }
    }

    private fun toggleExpand(position: Int) {
        val item = patientAdapter.currentList.getOrNull(position) ?: return
        if (item is PatientLibraryItem.PatientHeader) {
            if (expandedPatientIds.contains(item.patient.id)) {
                expandedPatientIds.remove(item.patient.id)
            } else {
                expandedPatientIds.add(item.patient.id)
            }
            buildAndSubmitList()
        }
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                observeRecordings()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFilterButton() {
        binding.filterButton.setOnClickListener { view ->
            val popup = PopupMenu(requireContext(), view)
            popup.menu.add(0, 0, 0, getString(R.string.sort_newest_first))
            popup.menu.add(0, 1, 1, getString(R.string.sort_oldest_first))
            popup.menu.add(0, 2, 2, getString(R.string.sort_name_az))
            popup.menu.add(0, 3, 3, getString(R.string.sort_name_za))

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    0 -> currentSortOrder = SortOrder.NEWEST_FIRST
                    1 -> currentSortOrder = SortOrder.OLDEST_FIRST
                    2 -> currentSortOrder = SortOrder.NAME_AZ
                    3 -> currentSortOrder = SortOrder.NAME_ZA
                }
                buildAndSubmitList()
                true
            }
            popup.show()
        }
    }

    private fun setupFilterChips() {
        binding.filterChips.setOnCheckedChangeListener { _, checkedId ->
            currentFilterType = when (checkedId) {
                R.id.filterAll -> null
                R.id.filterHeartChip -> "HEART"
                R.id.filterLungChip -> "LUNG"
                R.id.filterBowelChip -> "BOWEL"
                R.id.filterPregnancyChip -> "PREGNANCY"
                R.id.filterFullBodyChip -> "FULL BODY"
                else -> null
            }
            buildAndSubmitList()
        }
    }

    private fun setupBackButton() {
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun observeRecordings() {
        collectJob?.cancel()
        collectJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val flow = if (currentSearchQuery.isBlank()) {
                    recordingRepository.getRecordingsWithPatients()
                } else {
                    recordingRepository.searchRecordingsWithPatients(currentSearchQuery)
                }

                flow.collectLatest { recordings ->
                    allRecordings = recordings
                    buildAndSubmitList()
                }
            }
        }
    }

    private fun buildAndSubmitList() {
        val recordings = filterByType(allRecordings)
        val sorted = sortRecordings(recordings)

        // Group recordings by patientId
        val grouped = sorted.groupBy { it.recording.patientId ?: -1L }
        val items = mutableListOf<PatientLibraryItem>()

        for ((patientId, patientRecordings) in grouped) {
            if (patientId > 0 && patientRecordings.isNotEmpty()) {
                val firstRec = patientRecordings.first()
                val patientEntity = com.musediagnostics.taal.app.data.db.entity.PatientEntity(
                    id = patientId,
                    fullName = firstRec.patientName ?: "Unknown",
                    patientId = firstRec.patientIdentifier ?: ""
                )
                val isExpanded = expandedPatientIds.contains(patientId)
                items.add(PatientLibraryItem.PatientHeader(patientEntity, patientRecordings.size, isExpanded))

                if (isExpanded) {
                    for (rec in patientRecordings) {
                        items.add(PatientLibraryItem.RecordingItem(rec))
                    }
                }
            } else {
                // Recordings without a patient - show them directly
                for (rec in patientRecordings) {
                    items.add(PatientLibraryItem.RecordingItem(rec))
                }
            }
        }

        patientAdapter.submitList(items)
        updatePatientInfoFromRecordings(allRecordings)
        binding.emptyStateText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.recordingsRecyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun filterByType(list: List<RecordingWithPatient>): List<RecordingWithPatient> {
        val filter = currentFilterType ?: return list
        return list.filter { item ->
            val type = item.recording.filterType.uppercase()
            when (filter) {
                "LUNG" -> type == "LUNG" || type == "LUNGS"
                "FULL BODY" -> type == "FULL BODY" || type == "FULL_BODY"
                else -> type == filter
            }
        }
    }

    private fun sortRecordings(list: List<RecordingWithPatient>): List<RecordingWithPatient> {
        return when (currentSortOrder) {
            SortOrder.NEWEST_FIRST -> list.sortedByDescending { it.recording.createdAt }
            SortOrder.OLDEST_FIRST -> list.sortedBy { it.recording.createdAt }
            SortOrder.NAME_AZ -> list.sortedBy { (it.patientName ?: "").lowercase() }
            SortOrder.NAME_ZA -> list.sortedByDescending { (it.patientName ?: "").lowercase() }
        }
    }

    private fun updatePatientInfoFromRecordings(recordings: List<RecordingWithPatient>) {
        // If patient info wasn't set from arguments, try to populate from first recording
        if (arguments?.getString("patientName").isNullOrBlank() && recordings.isNotEmpty()) {
            val first = recordings.first()
            if (!first.patientName.isNullOrBlank()) {
                binding.patientNameText.text = first.patientName
            }
            if (!first.patientIdentifier.isNullOrBlank()) {
                binding.patientInfoText.visibility = View.VISIBLE
                binding.patientInfoText.text = getString(
                    R.string.patient_info_format,
                    first.patientIdentifier,
                    "---"
                )
            }
        }
    }

    private fun navigateToPlayer(item: RecordingWithPatient) {
        // Guard against double-tap or tapping during a navigation transition.
        // If the current destination is no longer this fragment, the action ID
        // won't exist on it and navigate() would throw an IllegalArgumentException.
        if (findNavController().currentDestination?.id != R.id.recordingLibraryFragment) return
        val bundle = Bundle().apply {
            putInt("recordingId", item.recording.id.toInt())
            putString("filePath", item.recording.filePath)
        }
        findNavController().navigate(R.id.action_library_to_player, bundle)
    }

    private fun shareRecording(item: RecordingWithPatient) {
        try {
            val file = File(item.recording.filePath)
            if (!file.exists()) {
                Toast.makeText(requireContext(), "Recording file not found", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Unable to share recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameDialog(item: RecordingWithPatient) {
        val editText = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            setText(item.recording.fileName.ifBlank {
                item.recording.filePath.substringAfterLast("/")
            })
            setTextColor(resources.getColor(R.color.text_primary, null))
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rename_recording))
            .setView(editText)
            .setPositiveButton(getString(R.string.rename)) { _, _ ->
                val newName = editText.text?.toString()?.trim() ?: ""
                if (newName.isNotBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        recordingRepository.renameRecording(item.recording.id, newName)
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.recording_renamed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDeleteConfirmation(item: RecordingWithPatient) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_recording))
            .setMessage(getString(R.string.delete_recording_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    recordingRepository.deleteRecording(item.recording)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.recording_deleted),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        collectJob?.cancel()
        _binding = null
    }
}
