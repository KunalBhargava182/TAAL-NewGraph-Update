package com.musediagnostics.taal.app.ui.library

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
                    // Reviewing goes through the PcgScale review screen (same time-true grid
                    // and RMS-scaled trace as the PcgScale recorder that produces every
                    // recording now) rather than the older production PlayerFragment.
                    val bundle = Bundle().apply {
                        putString("filePath", file.absolutePath)
                        putString("filterName", filterName)
                    }
                    findNavController().navigate(R.id.action_savedRecordings_to_pcgScaleReview, bundle)
                },
                onShare = { file -> shareRecording(file) },
                onDelete = { file -> confirmDelete(file) }
            )
        }
    }

    private fun shareRecording(file: File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            putExtra(Intent.EXTRA_TITLE, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, file.nameWithoutExtension))
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
    private fun extractFilterName(fileNameWithoutExtension: String): String {
        val known = listOf("FULL_BODY", "PREGNANCY", "CUSTOM", "LUNGS", "BOWEL", "HEART")
        return known.firstOrNull { fileNameWithoutExtension.startsWith("${it}_") } ?: "HEART"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
