package com.musediagnostics.taal.uikit.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.musediagnostics.taal.uikit.R
import com.musediagnostics.taal.uikit.databinding.ItemSavedRecordingBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedRecordingAdapter(
    private val files: List<File>,
    private val onPlay: (File) -> Unit
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

        val displayName = file.nameWithoutExtension
        b.fileName.text = displayName

        val durationSecs = getWavDuration(file)
        val durationStr = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            .format(Date(file.lastModified()))
        b.fileMeta.text = "$durationStr  •  $dateStr"

        val metaFile = File(file.parent, "$displayName.meta")
        val filter = if (metaFile.exists()) metaFile.readText().trim().uppercase() else ""
        val icon = when (filter) {
            "LUNGS" -> R.drawable.ic_lungs
            "BOWEL" -> R.drawable.ic_bowel
            "PREGNANCY" -> R.drawable.ic_pregnancy
            "FULL_BODY" -> R.drawable.ic_accessibility
            else -> R.drawable.ic_heart
        }
        b.filterIcon.setImageResource(icon)

        b.playButton.setOnClickListener { onPlay(file) }
        b.root.setOnClickListener { onPlay(file) }
    }

    override fun getItemCount() = files.size

    private fun getWavDuration(file: File): Int {
        return try {
            val dataSize = file.length() - 44
            if (dataSize <= 0) return 0
            (dataSize / 2 / 44100).toInt()
        } catch (_: Exception) {
            0
        }
    }
}
