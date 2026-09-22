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
import com.musediagnostics.taal.app.databinding.FragmentProductionSavedRecordingsBinding
import java.io.File

/**
 * Production's own Saved Recordings list — a dedicated fork of [SavedRecordingsFragment]
 * (2026-09-16) so the two whole flows never share a destination past the recorder/player.
 * Same filesystem listing (same filesDir/saved/ pool — a recording made by either recorder
 * family shows up in both lists), same audio-only share behavior; no Clean Graph, no
 * segmentation gate here (that lives on [com.musediagnostics.taal.app.ui.player.ProductionReviewFragment],
 * which this list opens instead of PcgScaleReviewFragment), and no share-with-graph button.
 * [SavedRecordingsFragment] itself is untouched and still serves the PcgScale flow.
 */
class ProductionSavedRecordingsFragment : Fragment() {

    private var _binding: FragmentProductionSavedRecordingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProductionSavedRecordingsBinding.inflate(inflater, container, false)
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
            binding.recordingsList.adapter = ProductionSavedRecordingAdapter(
                files,
                onPlay = { file ->
                    val filterName = extractFilterName(file.nameWithoutExtension)
                    val bundle = Bundle().apply {
                        putString("filePath", file.absolutePath)
                        putString("filterName", filterName)
                    }
                    findNavController().navigate(R.id.action_productionSavedRecordings_to_productionReview, bundle)
                },
                onShare = { file -> shareRecording(file) },
                onDelete = { file -> confirmDelete(file) }
            )
        }
    }

    private fun shareRecording(file: File) {
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
            // AAC) instead of passing the original bytes through.
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
                file.delete()
                val rawFile = File(file.parent, file.name.replace("_filtered.wav", "_raw.wav"))
                if (rawFile.exists()) rawFile.delete()
                loadRecordings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun extractFilterName(fileNameWithoutExtension: String): String {
        val known = listOf("FULL_BODY", "PREGNANCY", "CUSTOM", "LUNGS", "BOWEL", "HEART")
        return known.firstOrNull { fileNameWithoutExtension.startsWith("${it}_") } ?: "HEART"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
