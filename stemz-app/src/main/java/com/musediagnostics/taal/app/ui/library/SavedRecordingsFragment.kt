package com.musediagnostics.taal.app.ui.library

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentSavedRecordingsBinding
import java.io.File

class SavedRecordingsFragment : Fragment() {

    private var _binding: FragmentSavedRecordingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSavedRecordingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.recordingsList.layoutManager = LinearLayoutManager(requireContext())
        loadRecordings()
    }

    override fun onResume() {
        super.onResume()
        loadRecordings()
    }

    private fun loadRecordings() {
        val savedDir = File(requireContext().filesDir, "saved")
        val files = savedDir.listFiles { f -> f.name.endsWith("_filtered.wav") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.recordingsList.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.recordingsList.visibility = View.VISIBLE
            binding.recordingsList.adapter = SavedRecordingAdapter(
                files,
                onPlay = { file ->
                    val filterName = extractFilterName(file.nameWithoutExtension)
                    val bundle = Bundle().apply {
                        putString("filePath", file.absolutePath)
                        putBoolean("isNewRecording", false)
                        putString("filterName", filterName)
                    }
                    findNavController().navigate(R.id.action_savedRecordings_to_player, bundle)
                },
                onShare = { file -> shareRecording(file) },
                onDelete = { file -> confirmDelete(file) }
            )
        }
    }

    private fun shareRecording(file: File) {
        // The receiving app reads the filename off the actual file it gets via the content
        // URI (EXTRA_TITLE/EXTRA_SUBJECT are not filenames, just text fields some apps show
        // elsewhere) — the on-disk name is "{FILTER}_{userInput}_filtered.wav", which would
        // show the recipient filter/suffix cruft instead of the name the user actually typed
        // when saving. So share a cleanly-named copy instead of the original file.
        val filterName = extractFilterName(file.nameWithoutExtension)
        val displayName = file.nameWithoutExtension
            .removePrefix("${filterName}_")
            .removeSuffix("_filtered")

        val shareDir = File(file.parentFile, ".share_tmp").also { it.mkdirs() }
        shareDir.listFiles()?.forEach { it.delete() } // drop any leftover from a previous share
        val shareFile = File(shareDir, "$displayName.wav")
        try {
            file.copyTo(shareFile, overwrite = true)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Unable to prepare file for sharing", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            shareFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            // Generic type, not "audio/wav" — several apps (WhatsApp included) treat an
            // "audio/*" share as a voice-note/media attachment and transcode it (e.g. to
            // AAC) instead of passing the original bytes through. A generic type routes
            // it through their "send as document/file" path instead, which doesn't
            // recompress. The ".wav" in the filename still tells the receiving app what
            // it is.
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, displayName)
            putExtra(Intent.EXTRA_TITLE, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, displayName))
    }

    private fun confirmDelete(file: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Recording")
            .setMessage("Are you sure you want to permanently delete this recording?")
            .setPositiveButton("Delete") { _, _ ->
                // Delete filtered file
                file.delete()
                // Delete matching raw file (same base name, different suffix)
                val rawFile = File(file.parent, file.name.replace("_filtered.wav", "_raw.wav"))
                if (rawFile.exists()) rawFile.delete()
                // Reload the list
                loadRecordings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Filename format: "{FILTER}_{userInput}_filtered" — extract the leading filter token.
    // HEART_HARD must be checked before HEART — otherwise a "HEART_HARD_..." name would
    // match the "HEART_" prefix first and leave "HARD_" stuck in the display name (same
    // bug already fixed in SavedRecordingAdapter.kt's own copy of this list).
    private fun extractFilterName(fileNameWithoutExtension: String): String {
        val known = listOf("FULL_BODY", "PREGNANCY", "CUSTOM", "LUNGS", "BOWEL", "HEART_HARD", "HEART")
        return known.firstOrNull { fileNameWithoutExtension.startsWith("${it}_") } ?: "HEART"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
