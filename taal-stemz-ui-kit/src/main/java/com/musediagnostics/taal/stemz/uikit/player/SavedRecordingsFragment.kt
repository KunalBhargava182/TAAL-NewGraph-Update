package com.musediagnostics.taal.stemz.uikit.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.musediagnostics.taal.stemz.uikit.R
import com.musediagnostics.taal.stemz.uikit.TaalStemzUiKit
import com.musediagnostics.taal.stemz.uikit.databinding.TsukFragmentSavedRecordingsBinding
import com.musediagnostics.taal.stemz.uikit.share.GraphShareBundler
import com.musediagnostics.taal.stemz.uikit.share.GraphShareResult
import com.musediagnostics.taal.stemz.uikit.share.RecordingDisplayName
import com.musediagnostics.taal.stemz.uikit.share.ShareAction
import com.musediagnostics.taal.stemz.uikit.share.ShareRequest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Saved Recordings list (every "_filtered.wav" in filesDir/saved, newest first). Per row:
 *  - Play  → the saved-recording review screen (PcgScale graph, Clean Graph, Analyze).
 *  - Share → the .wav plus a PDF of the whole recording drawn on the PcgScale grid
 *            (Clean Graph ON), via the system share sheet.
 *  - Delete → removes both the _filtered.wav and its _raw.wav after confirmation.
 */
internal class SavedRecordingsFragment : Fragment() {

    private var _binding: TsukFragmentSavedRecordingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = TsukFragmentSavedRecordingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            // When this list is the first screen (TaalSavedRecordingsActivity) there is nothing
            // to go back to inside the flow, so leave the activity.
            if (!findNavController().navigateUp()) requireActivity().finish()
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
                    val bundle = Bundle().apply {
                        putString("filePath", file.absolutePath)
                        putString("filterName", RecordingDisplayName.extractFilterName(file.nameWithoutExtension))
                    }
                    findNavController().navigate(R.id.action_savedRecordings_to_pcgScaleReview, bundle)
                },
                onShare = { file -> shareRecordingWithGraph(file) },
                onDelete = { file -> confirmDelete(file) }
            )
        }
    }

    /** Builds the wav + graph-PDF bundle off the main thread (spinner shown), then opens the share sheet. */
    private fun shareRecordingWithGraph(file: File) {
        if (_binding == null) return
        binding.shareGraphProgressOverlay.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = GraphShareBundler.buildShareRequest(requireContext().applicationContext, file)
            if (_binding == null) return@launch
            binding.shareGraphProgressOverlay.visibility = View.GONE
            when (result) {
                is GraphShareResult.Success -> launchShareRequest(result.request)
                is GraphShareResult.Failure -> Toast.makeText(
                    requireContext(), result.reason, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun launchShareRequest(request: ShareRequest) {
        val authority = TaalStemzUiKit.fileProviderAuthority(requireContext())
        val uris: List<Uri> = request.attachmentPaths.map { path ->
            FileProvider.getUriForFile(requireContext(), authority, File(path))
        }
        if (uris.isEmpty()) return

        val intent = when (request.action) {
            ShareAction.SEND -> Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
            ShareAction.SEND_MULTIPLE -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }.apply {
            type = request.mimeType
            request.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            request.title?.let { putExtra(Intent.EXTRA_TITLE, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, request.chooserTitle))
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
