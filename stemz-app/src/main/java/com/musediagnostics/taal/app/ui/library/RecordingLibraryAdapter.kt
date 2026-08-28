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
import com.musediagnostics.taal.app.databinding.ItemRecordingCardBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingLibraryAdapter(
    private val onItemClick: (RecordingWithPatient) -> Unit,
    private val onEditClick: (RecordingWithPatient) -> Unit,
    private val onShareClick: (RecordingWithPatient) -> Unit,
    private val onDeleteClick: (RecordingWithPatient) -> Unit
) : ListAdapter<RecordingWithPatient, RecordingLibraryAdapter.RecordingViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val binding = ItemRecordingCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RecordingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RecordingViewHolder(
        private val binding: ItemRecordingCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecordingWithPatient) {
            val recording = item.recording
            val context = binding.root.context

            // Recording type name
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

            // Recording type icon and tint
            when (filterType) {
                "HEART" -> {
                    binding.recordingTypeIcon.setImageResource(R.drawable.ic_heart)
                    binding.recordingTypeIcon.setColorFilter(
                        ContextCompat.getColor(context, R.color.red_primary)
                    )
                }
                "LUNG", "LUNGS" -> {
                    binding.recordingTypeIcon.setImageResource(R.drawable.ic_lungs)
                    binding.recordingTypeIcon.setColorFilter(
                        ContextCompat.getColor(context, R.color.blue_primary)
                    )
                }
                "BOWEL" -> {
                    binding.recordingTypeIcon.setImageResource(R.drawable.ic_bowel)
                    binding.recordingTypeIcon.setColorFilter(
                        ContextCompat.getColor(context, R.color.orange_secondary)
                    )
                }
                "PREGNANCY" -> {
                    binding.recordingTypeIcon.setImageResource(R.drawable.ic_pregnancy)
                    binding.recordingTypeIcon.setColorFilter(
                        ContextCompat.getColor(context, R.color.teal_primary)
                    )
                }
                else -> {
                    binding.recordingTypeIcon.setImageResource(R.drawable.ic_heart)
                    binding.recordingTypeIcon.setColorFilter(
                        ContextCompat.getColor(context, R.color.teal_primary)
                    )
                }
            }

            // Date formatted + duration
            val dateFormat = SimpleDateFormat("MMMM d, yyyy - hh:mm a", Locale.getDefault())
            val dateStr = dateFormat.format(Date(recording.createdAt))
            val minutes = recording.durationSeconds / 60
            val seconds = recording.durationSeconds % 60
            val durationStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", 0, minutes, seconds)
            binding.recordingDateText.text = "$dateStr, $durationStr"

            // Incomplete badge - show if duration is 0 (incomplete recording)
            if (recording.durationSeconds == 0) {
                binding.incompleteBadge.visibility = View.VISIBLE
            } else {
                binding.incompleteBadge.visibility = View.GONE
            }

            // Card click -> navigate to player
            binding.root.setOnClickListener {
                onItemClick(item)
            }

            // Long press -> popup with Edit, Share, Delete
            binding.root.setOnLongClickListener { view ->
                showPopupMenu(view, item)
                true
            }

            // Share button click
            binding.shareButton.setOnClickListener {
                onShareClick(item)
            }
        }

        private fun showPopupMenu(view: View, item: RecordingWithPatient) {
            val popup = PopupMenu(view.context, view)
            popup.menuInflater.inflate(R.menu.menu_recording_overflow, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_edit -> {
                        onEditClick(item)
                        true
                    }
                    R.id.action_share -> {
                        onShareClick(item)
                        true
                    }
                    R.id.action_delete -> {
                        onDeleteClick(item)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RecordingWithPatient>() {
        override fun areItemsTheSame(
            oldItem: RecordingWithPatient,
            newItem: RecordingWithPatient
        ): Boolean {
            return oldItem.recording.id == newItem.recording.id
        }

        override fun areContentsTheSame(
            oldItem: RecordingWithPatient,
            newItem: RecordingWithPatient
        ): Boolean {
            return oldItem == newItem
        }
    }
}
