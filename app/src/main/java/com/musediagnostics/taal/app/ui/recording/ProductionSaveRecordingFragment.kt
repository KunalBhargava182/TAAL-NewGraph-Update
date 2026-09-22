package com.musediagnostics.taal.app.ui.recording

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentProductionSaveRecordingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Production's own Save screen — a dedicated fork of [SaveRecordingFragment] (2026-09-16) so
 * the two whole flows (production vs. PcgScale) never share a destination past the
 * recorder/player. Identical rename/copy-to-device-storage logic; only the post-save
 * navigation target differs (goes to [ProductionSavedRecordingsFragment], not the shared
 * PcgScale-oriented savedRecordingsFragment). [SaveRecordingFragment] itself is untouched and
 * still serves PcgScale/Calibrated/FullTimeOn.
 */
class ProductionSaveRecordingFragment : Fragment() {

    private var _binding: FragmentProductionSaveRecordingBinding? = null
    private val binding get() = _binding!!

    private var filteredTempPath = ""
    private var rawTempPath = ""
    private var filterName = "HEART"
    private var popUpToDestinationId = R.id.recordingFragment

    private var pendingSafeName: String? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val safeName = pendingSafeName ?: return@registerForActivityResult
        pendingSafeName = null

        if (granted) {
            val savedDir = File(requireContext().filesDir, "saved")
            lifecycleScope.launch(Dispatchers.IO) {
                copyAllToDeviceStorage(
                    requireContext(),
                    File(savedDir, "${safeName}_filtered.wav").absolutePath,
                    File(savedDir, "${safeName}_raw.wav").absolutePath
                )
            }
        } else {
            Toast.makeText(
                requireContext(),
                "Storage permission denied — files saved in app storage only",
                Toast.LENGTH_LONG
            ).show()
        }

        navigateAfterSave()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProductionSaveRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        filteredTempPath = arguments?.getString("filePath") ?: ""
        rawTempPath      = arguments?.getString("rawFilePath") ?: ""
        filterName       = arguments?.getString("filterName") ?: "HEART"
        popUpToDestinationId = arguments?.getInt("popUpToDestination", R.id.recordingFragment) ?: R.id.recordingFragment

        binding.filterChip.visibility = View.GONE

        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        binding.fileNameInput.setText(timeStr)
        binding.fileNameInput.setSelection(timeStr.length)

        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.cancelButton.setOnClickListener { findNavController().navigateUp() }

        binding.saveButton.setOnClickListener {
            val name = binding.fileNameInput.text?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "Please enter a file name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.saveButton.isEnabled = false
            triggerSave(name)
        }
    }

    // ── Save flow ─────────────────────────────────────────────────────────────

    private fun triggerSave(name: String) {
        val ctx = requireContext()
        val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val capturedFilter = filterName
        val fullSaveName = "${capturedFilter}_${safeName}"

        lifecycleScope.launch(Dispatchers.IO) {

            val internalOk = saveInternally(ctx, fullSaveName)

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext

                if (!internalOk) {
                    binding.saveButton.isEnabled = true
                    Toast.makeText(ctx, "Failed to save recording", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                        PackageManager.PERMISSION_GRANTED

                if (needsPermission) {
                    pendingSafeName = fullSaveName
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    val savedDir = File(ctx.filesDir, "saved")
                    lifecycleScope.launch(Dispatchers.IO) {
                        copyAllToDeviceStorage(
                            ctx,
                            File(savedDir, "${fullSaveName}_filtered.wav").absolutePath,
                            File(savedDir, "${fullSaveName}_raw.wav").absolutePath
                        )
                    }
                    navigateAfterSave()
                }
            }
        }
    }

    private fun saveInternally(ctx: android.content.Context, fullSaveName: String): Boolean {
        return try {
            val savedDir = File(ctx.filesDir, "saved").also { it.mkdirs() }

            moveFile(File(filteredTempPath), File(savedDir, "${fullSaveName}_filtered.wav"))
            moveFile(File(rawTempPath),      File(savedDir, "${fullSaveName}_raw.wav"))

            File(savedDir, "${fullSaveName}_filtered.wav").exists()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun moveFile(src: File, dst: File) {
        if (!src.exists()) return
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    // ── Device storage copy ───────────────────────────────────────────────────

    private fun copyAllToDeviceStorage(
        ctx: android.content.Context,
        filteredPath: String,
        rawPath: String
    ) {
        copyOneFile(ctx, filteredPath)
        copyOneFile(ctx, rawPath)
    }

    private fun copyOneFile(ctx: android.content.Context, sourcePath: String) {
        val source = File(sourcePath)
        if (!source.exists()) return
        val fileName = source.name

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Taal Saved Audios")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val folder = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "Taal Saved Audios"
                )
                folder.mkdirs()
                source.copyTo(File(folder, fileName), overwrite = true)
            }
        } catch (_: Exception) {
            // Non-fatal — internal copy already safe in filesDir/saved/
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun navigateAfterSave() {
        if (!isAdded || _binding == null) return
        Toast.makeText(requireContext(), "Recording saved!", Toast.LENGTH_SHORT).show()
        findNavController().navigate(
            R.id.action_productionSaveRecording_to_productionSavedRecordings,
            null,
            androidx.navigation.NavOptions.Builder()
                .setPopUpTo(popUpToDestinationId, false).build()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
