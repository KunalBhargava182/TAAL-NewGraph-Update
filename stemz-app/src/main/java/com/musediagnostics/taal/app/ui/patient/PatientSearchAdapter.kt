package com.musediagnostics.taal.app.ui.patient

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musediagnostics.taal.app.data.db.entity.PatientEntity
import com.musediagnostics.taal.app.databinding.ItemPatientSearchResultBinding

class PatientSearchAdapter(
    private val onPatientClick: (PatientEntity) -> Unit
) : ListAdapter<PatientEntity, PatientSearchAdapter.PatientViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatientViewHolder {
        val binding = ItemPatientSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PatientViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PatientViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PatientViewHolder(
        private val binding: ItemPatientSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(patient: PatientEntity) {
            binding.patientNameText.text = patient.fullName
            binding.patientIdText.text = if (patient.patientId.isNotBlank()) {
                "ID: ${patient.patientId}"
            } else {
                "ID: ${patient.id}"
            }

            binding.root.setOnClickListener {
                onPatientClick(patient)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PatientEntity>() {
        override fun areItemsTheSame(oldItem: PatientEntity, newItem: PatientEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PatientEntity, newItem: PatientEntity): Boolean {
            return oldItem == newItem
        }
    }
}
