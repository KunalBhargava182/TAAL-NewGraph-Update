package com.musediagnostics.taal.app.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.ItemSavedRecordingBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedRecordingAdapter(
    private val files: List<File>,
    private val onPlay: (File) -> Unit,
    private val onShare: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<SavedRecordingAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemSavedRecordingBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSavedRecordingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        val b = holder.binding

        // Filename format: "{FILTER}_{userInput}_filtered.wav"
        // Extract filter from the prefix, then show only the user's input as the name.
        val baseName = file.nameWithoutExtension          // e.g. "LUNGS_20240321_filtered"
        val filterName = extractFilter(baseName)          // e.g. "LUNGS"
        val displayName = baseName
            .removePrefix("${filterName}_")              // strip "LUNGS_"
            .removeSuffix("_filtered")                   // strip "_filtered"
        b.fileName.text = displayName

        val durationSecs = getWavDuration(file)
        val durationStr = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)

        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            .format(Date(file.lastModified()))
        b.fileMeta.text = "$durationStr  •  $dateStr"

        val icon = when (filterName) {
            "LUNGS"     -> R.drawable.ic_lungs
            "BOWEL"     -> R.drawable.ic_bowel
            "PREGNANCY" -> R.drawable.ic_pregnancy
            "FULL_BODY" -> R.drawable.ic_accessibility
            "CUSTOM"    -> R.drawable.ic_custom_filter
            else        -> R.drawable.ic_heart
        }
        b.filterIcon.setImageResource(icon)

        b.root.setOnClickListener { onPlay(file) }
        b.playButton.setOnClickListener { onPlay(file) }
        b.shareButton.setOnClickListener { onShare(file) }
        b.deleteButton.setOnClickListener { onDelete(file) }
    }

    override fun getItemCount() = files.size

    private fun extractFilter(baseName: String): String {
        val known = listOf("FULL_BODY", "PREGNANCY", "CUSTOM", "LUNGS", "BOWEL", "HEART")
        return known.firstOrNull { baseName.startsWith("${it}_") } ?: "HEART"
    }

    private fun getWavDuration(file: File): Int {
        return try {
            val size = file.length()
            if (size < 44) return 0
            val dataSize = size - 44
            val totalSamples = dataSize / 2
            (totalSamples / 44100).toInt()
        } catch (_: Exception) {
            0
        }
    }
}
