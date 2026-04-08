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
    private val onShare: (File) -> Unit
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

        // Display name without extension
        val displayName = file.nameWithoutExtension
        b.fileName.text = displayName

        // Duration from WAV header (dataSize / 2 / 44100)
        val durationSecs = getWavDuration(file)
        val durationStr = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)

        // Last modified date
        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            .format(Date(file.lastModified()))
        b.fileMeta.text = "$durationStr  •  $dateStr"

        // Filter icon: prefer sidecar .meta file, fall back to name prefix for older recordings
        val filterName = readFilterMeta(file) ?: when {
            displayName.startsWith("LUNGS", ignoreCase = true) -> "LUNGS"
            displayName.startsWith("BOWEL", ignoreCase = true) -> "BOWEL"
            displayName.startsWith("PREGNANCY", ignoreCase = true) -> "PREGNANCY"
            displayName.startsWith("FULL_BODY", ignoreCase = true) -> "FULL_BODY"
            else -> "HEART"
        }
        val icon = when (filterName.uppercase()) {
            "LUNGS" -> R.drawable.ic_lungs
            "BOWEL" -> R.drawable.ic_bowel
            "PREGNANCY" -> R.drawable.ic_pregnancy
            "FULL_BODY" -> R.drawable.ic_accessibility
            else -> R.drawable.ic_heart
        }
        b.filterIcon.setImageResource(icon)

        b.playButton.setOnClickListener { onPlay(file) }
        b.root.setOnClickListener { onPlay(file) }
        b.root.setOnLongClickListener {
            onShare(file)
            true
        }
    }

    override fun getItemCount() = files.size

    private fun readFilterMeta(wavFile: File): String? {
        return try {
            val meta = File(wavFile.parent, "${wavFile.nameWithoutExtension}.meta")
            if (meta.exists()) meta.readText().trim().uppercase() else null
        } catch (_: Exception) {
            null
        }
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
