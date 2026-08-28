package com.musediagnostics.taal.app.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.data.db.entity.RecordingWithPatient
import com.musediagnostics.taal.app.databinding.ItemPatientHeaderBinding
import com.musediagnostics.taal.app.databinding.ItemRecordingCardBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PatientLibraryAdapter(
    private val onRecordingClick: (RecordingWithPatient) -> Unit,
    private val onEditClick: (RecordingWithPatient) -> Unit,
    private val onShareClick: (RecordingWithPatient) -> Unit,
    private val onDeleteClick: (RecordingWithPatient) -> Unit,
    private val onToggleExpand: (Int) -> Unit
) : ListAdapter<PatientLibraryItem, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        const val TYPE_PATIENT = 0
        const val TYPE_RECORDING = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is PatientLibraryItem.PatientHeader -> TYPE_PATIENT
            is PatientLibraryItem.RecordingItem -> TYPE_RECORDING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_PATIENT -> {
                val binding = ItemPatientHeaderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                PatientViewHolder(binding)
            }
            else -> {
                val binding = ItemRecordingCardBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                RecordingViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is PatientLibraryItem.PatientHeader -> (holder as PatientViewHolder).bind(item, position)
            is PatientLibraryItem.RecordingItem -> (holder as RecordingViewHolder).bind(item.recording)
        }
    }

    inner class PatientViewHolder(
        private val binding: ItemPatientHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PatientLibraryItem.PatientHeader, position: Int) {
            binding.patientName.text = item.patient.fullName.ifBlank { "Unknown Patient" }
            binding.patientId.text = if (item.patient.patientId.isNotBlank())
                "ID: ${item.patient.patientId}" else ""
            binding.recordingCount.text = "${item.recordingCount} recordings"

            // Rotate chevron based on expanded state
            binding.expandIcon.rotation = if (item.isExpanded) 90f else 0f

            binding.root.setOnClickListener {
                onToggleExpand(position)
            }
        }
    }

    inner class RecordingViewHolder(
        private val binding: ItemRecordingCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecordingWithPatient) {
            val recording = item.recording
            val context = binding.root.context

            val filterType = recording.filterType.uppercase()
            val recordingName = when (filterType) {
                "HEART" -> context.getString(R.string.heart_recording)
                "LUNG", "LUNGS" -> context.getString(R.string.lungs_recording)
                "BOWEL" -> context.getString(R.string.filter_bowel) + " Recording"
                "PREGNANCY" -> context.getString(R.string.filter_pregnancy) + " Recording"
                "FULL BODY", "FULL_BODY" -> context.getString(R.string.filter_full_body) + " Recording"
                else -> recording.fileName.ifBlank { recording.filePath.substringAfterLast("/") }
            }
            binding.recordingNameText.text = recordingName

            when (filterType) {
                "HEART" -> {
                    binding.recordingTypeIcon.setImageResource(R.drawable.ic_heart)
                    binding.recordingTypeIcon.setColorFilter(ContextCompat.getColor(context, R.color.red_primary))
                }
                "LUNG", "LUNGS" -> {
                    binding.recordingTypeIcon.setImageResource(R.drawable.ic_lungs)
                    binding.recordingTypeIcon.setColorFilter(ContextCompat.getColor(context, R.color.blue_primary))
                }
                "BOWEL" -> {
                    binding.recordingTypeIcon.setImageResource(R.drawable.ic_bowel)
                    binding.recordingTypeIcon.setColorFilter(ContextCompat.getColor(context, R.color.orange_secondary))
                }
                "PREGNANCY" -> {
                    binding.recordingTypeIcon.setImageResource(R.drawable.ic_pregnancy)
                    binding.recordingTypeIcon.setColorFilter(ContextCompat.getColor(context, R.color.teal_primary))
                }
                else -> {
                    binding.recordingTypeIcon.setImageResource(R.drawable.ic_heart)
                    binding.recordingTypeIcon.setColorFilter(ContextCompat.getColor(context, R.color.teal_primary))
                }
            }

            val dateFormat = SimpleDateFormat("MMMM d, yyyy - hh:mm a", Locale.getDefault())
            val dateStr = dateFormat.format(Date(recording.createdAt))
            val minutes = recording.durationSeconds / 60
            val seconds = recording.durationSeconds % 60
            val durationStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", 0, minutes, seconds)
            binding.recordingDateText.text = "$dateStr, $durationStr"

            if (recording.durationSeconds == 0) {
                binding.incompleteBadge.visibility = View.VISIBLE
            } else {
                binding.incompleteBadge.visibility = View.GONE
            }

            binding.root.setOnClickListener { onRecordingClick(item) }
            binding.root.setOnLongClickListener { view ->
                showPopupMenu(view, item)
                true
            }
            binding.shareButton.setOnClickListener { onShareClick(item) }
        }

        private fun showPopupMenu(view: View, item: RecordingWithPatient) {
            val popup = PopupMenu(view.context, view)
            popup.menuInflater.inflate(R.menu.menu_recording_overflow, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_edit -> { onEditClick(item); true }
                    R.id.action_share -> { onShareClick(item); true }
                    R.id.action_delete -> { onDeleteClick(item); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PatientLibraryItem>() {
        override fun areItemsTheSame(oldItem: PatientLibraryItem, newItem: PatientLibraryItem): Boolean {
            return when {
                oldItem is PatientLibraryItem.PatientHeader && newItem is PatientLibraryItem.PatientHeader ->
                    oldItem.patient.id == newItem.patient.id
                oldItem is PatientLibraryItem.RecordingItem && newItem is PatientLibraryItem.RecordingItem ->
                    oldItem.recording.recording.id == newItem.recording.recording.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: PatientLibraryItem, newItem: PatientLibraryItem): Boolean {
            return oldItem == newItem
        }
    }
}
