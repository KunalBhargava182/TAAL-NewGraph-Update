package com.musediagnostics.taal.lungs.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.musediagnostics.taal.lungs.R
import com.musediagnostics.taal.lungs.data.db.LungsDatabase
import com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity
import com.musediagnostics.taal.lungs.data.repository.LungPatientRepository
import com.musediagnostics.taal.lungs.data.repository.LungRecordingRepository
import com.musediagnostics.taal.lungs.databinding.FragmentSavedPatientsBinding
import com.musediagnostics.taal.lungs.databinding.ItemPatientBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SavedPatientsFragment : Fragment() {

    private var _binding: FragmentSavedPatientsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSavedPatientsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        val db = LungsDatabase.getInstance(requireContext())
        val patientRepo = LungPatientRepository(db.lungPatientDao())
        val recordingRepo = LungRecordingRepository(db.lungRecordingDao())

        val adapter = PatientAdapter(recordingRepo) { patient ->
            findNavController().navigate(
                R.id.action_saved_to_session,
                Bundle().apply {
                    putLong("patientId", patient.id)
                    putInt("patientSeqNum", patient.sequenceNumber)
                }
            )
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            patientRepo.getAllPatients().collectLatest { patients ->
                adapter.submitList(patients)
                binding.emptyText.visibility = if (patients.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (patients.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class PatientAdapter(
    private val recordingRepo: LungRecordingRepository,
    private val onClick: (LungPatientEntity) -> Unit
) : RecyclerView.Adapter<PatientAdapter.VH>() {

    private var items: List<LungPatientEntity> = emptyList()

    fun submitList(list: List<LungPatientEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPatientBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemPatientBinding) : RecyclerView.ViewHolder(b.root) {
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun bind(patient: LungPatientEntity) {
            b.tvPatientId.text = "Patient %02d".format(patient.sequenceNumber)
            b.tvPatientDetails.text = "${patient.sex}, ${patient.age} yrs • BMI ${"%.1f".format(patient.bmi)}"
            b.tvRecordingsCount.text = "-- / 16"
            b.root.setOnClickListener { onClick(patient) }

            scope.launch {
                val count = recordingRepo.getRecordingCountForPatient(patient.id)
                b.tvRecordingsCount.text = "$count / 16 recorded"
            }
        }
    }
}
