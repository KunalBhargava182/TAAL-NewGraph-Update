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
import com.musediagnostics.taal.app.databinding.FragmentSaveRecordingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SaveRecordingFragment : Fragment() {

    private var _binding: FragmentSaveRecordingBinding? = null
    private val binding get() = _binding!!

    // Argument paths promoted to fields so the permission callback can reach them
    private var filteredTempPath = ""
    private var rawTempPath = ""
    private var aiTestingTempPath = ""
    private var filterName = "HEART"
    /** Additional AI downsampling temp files — renamed into saved/ alongside the main files. */
    private var extraAiTempPaths: List<String> = emptyList()

    // Holds the safe file name between the internal save and the permission callback.
    // Only populated on API 24–28 when WRITE_EXTERNAL_STORAGE has not been granted yet.
    private var pendingSafeName: String? = null

    /**
     * Runtime permission launcher — only exercised on API 24–28.
     *
     * By the time this fires, the internal save (rename to filesDir/saved/) has
     * already completed successfully. We are here only to decide whether the
     * device-storage copy can happen.
     *
     * Grant  → copy the already-saved files to Music/Taal Saved Audios, then navigate.
     * Deny   → skip device copy, show message, then navigate.
     *          Internal files are safe regardless.
     */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val safeName = pendingSafeName ?: return@registerForActivityResult
        pendingSafeName = null

        if (granted) {
            val savedDir = File(requireContext().filesDir, "saved")
            val extraSaved = extraAiTempPaths.map { tempPath ->
                val suffix = File(tempPath).name.removePrefix("recording_").substringAfter('_')
                File(savedDir, "${safeName}_$suffix").absolutePath
            }
            lifecycleScope.launch(Dispatchers.IO) {
                // Copy the already-renamed files from internal storage to device storage
                copyAllToDeviceStorage(
                    requireContext(),
                    File(savedDir, "${safeName}_filtered.wav").absolutePath,
                    File(savedDir, "${safeName}_raw.wav").absolutePath,
                    File(savedDir, "${safeName}_8k_downsampling.wav").absolutePath,
                    extraSaved
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
        _binding = FragmentSaveRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        filteredTempPath  = arguments?.getString("filePath") ?: ""
        rawTempPath       = arguments?.getString("rawFilePath") ?: ""
        aiTestingTempPath = arguments?.getString("aiTestingFilePath") ?: ""
        filterName        = arguments?.getString("filterName") ?: "HEART"
        extraAiTempPaths  = arguments?.getStringArrayList("extraAiFilePaths")?.toList() ?: emptyList()

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

        lifecycleScope.launch(Dispatchers.IO) {

            // Step 1: Rename temp files into filesDir/saved/ (always happens first)
            val internalOk = saveInternally(ctx, safeName)

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext

                if (!internalOk) {
                    binding.saveButton.isEnabled = true
                    Toast.makeText(ctx, "Failed to save recording", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                // Step 2: Copy to device storage (Music/Taal Saved Audios)
                val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                        PackageManager.PERMISSION_GRANTED

                if (needsPermission) {
                    // Store safeName so the permission callback can locate the saved files
                    pendingSafeName = safeName
                    // Request permission — navigation happens inside the callback
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    // API 29+ (MediaStore, no permission needed) OR
                    // API 24–28 with permission already granted
                    val savedDir = File(ctx.filesDir, "saved")
                    val extraSaved = extraAiTempPaths.map { tempPath ->
                        val suffix = File(tempPath).name.removePrefix("recording_").substringAfter('_')
                        File(savedDir, "${safeName}_$suffix").absolutePath
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        copyAllToDeviceStorage(
                            ctx,
                            File(savedDir, "${safeName}_filtered.wav").absolutePath,
                            File(savedDir, "${safeName}_raw.wav").absolutePath,
                            File(savedDir, "${safeName}_8k_downsampling.wav").absolutePath,
                            extraSaved
                        )
                    }
                    navigateAfterSave()
                }
            }
        }
    }

    /**
     * Rename the three temp files into filesDir/saved/.
     * Returns true if the filtered file was moved successfully (minimum requirement).
     */
    private fun saveInternally(ctx: android.content.Context, safeName: String): Boolean {
        return try {
            val savedDir = File(ctx.filesDir, "saved").also { it.mkdirs() }

            File(filteredTempPath).takeIf { it.exists() }
                ?.renameTo(File(savedDir, "${safeName}_filtered.wav"))

            File(rawTempPath).takeIf { it.exists() }
                ?.renameTo(File(savedDir, "${safeName}_raw.wav"))

            if (aiTestingTempPath.isNotEmpty()) {
                File(aiTestingTempPath).takeIf { it.exists() }
                    ?.renameTo(File(savedDir, "${safeName}_8k_downsampling.wav"))
            }

            // Rename every additional AI downsampling file into saved/.
            // Suffix is extracted from the temp file name:
            //   "recording_{ts}_{suffix}.wav" → "{suffix}.wav"
            // Saved as "{safeName}_{suffix}.wav" (e.g. "MyRecording_4k_heart_downsampling.wav").
            for (tempPath in extraAiTempPaths) {
                val fileName = File(tempPath).name
                val suffix = fileName.removePrefix("recording_").substringAfter('_')
                File(tempPath).takeIf { it.exists() }
                    ?.renameTo(File(savedDir, "${safeName}_$suffix"))
            }

            // Write filter metadata for every variant so the recordings list shows the correct icon
            File(savedDir, "${safeName}_filtered.meta").writeText(filterName)
            File(savedDir, "${safeName}_raw.meta").writeText(filterName)
            File(savedDir, "${safeName}_8k_downsampling.meta").writeText(filterName)

            File(savedDir, "${safeName}_filtered.wav").exists()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ── Device storage copy ───────────────────────────────────────────────────

    /**
     * Copy all three files to "Music/Taal Saved Audios" on the device.
     *
     * This is best-effort — a failure here never affects the internal save.
     * Called from an IO coroutine; no main-thread access.
     */
    private fun copyAllToDeviceStorage(
        ctx: android.content.Context,
        filteredPath: String,
        rawPath: String,
        aiTestingPath: String,
        extraPaths: List<String> = emptyList()
    ) {
        copyOneFile(ctx, filteredPath)
        copyOneFile(ctx, rawPath)
        if (aiTestingPath.isNotEmpty()) copyOneFile(ctx, aiTestingPath)
        for (path in extraPaths) copyOneFile(ctx, path)
    }

    /**
     * Copy a single file into Music/Taal Saved Audios on the device.
     *
     * API 29+ — MediaStore:
     *   No WRITE_EXTERNAL_STORAGE needed.
     *   IS_PENDING=1 reserves the slot; IS_PENDING=0 makes it visible to all apps.
     *   RELATIVE_PATH places it at Music/Taal Saved Audios/.
     *
     * API 24–28 — Direct file write:
     *   Requires WRITE_EXTERNAL_STORAGE (declared in manifest, granted at runtime).
     *   Writes to Environment.DIRECTORY_MUSIC/Taal Saved Audios/.
     */
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
                // Clear IS_PENDING so the file becomes visible to file managers and other apps
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
            R.id.action_saveRecording_to_savedRecordings,
            null,
            androidx.navigation.NavOptions.Builder()
                .setPopUpTo(R.id.recordingFragment, false).build()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
