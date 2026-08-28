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

    private var filteredTempPath = ""
    private var rawTempPath = ""
    // private var aiTestingTempPath = ""  // AI downsampling disabled
    private var filterName = "HEART"
    // Which recorder screen's back-stack entry to pop up to after saving — lets each recorder
    // family (production, PcgScale, ...) share this screen while still landing SavedRecordingsFragment
    // right on top of its own recorder instead of a different family's (which wouldn't be on
    // the back stack, silently no-opping the popUpTo). Defaults to production's recordingFragment.
    private var popUpToDestinationId = R.id.recordingFragment
    // private var extraAiTempPaths: List<String> = emptyList()  // AI downsampling disabled

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
            // pendingSafeName holds fullSaveName ("{FILTER}_{userInput}")
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
        _binding = FragmentSaveRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        filteredTempPath = arguments?.getString("filePath") ?: ""
        rawTempPath      = arguments?.getString("rawFilePath") ?: ""
        // aiTestingTempPath = arguments?.getString("aiTestingFilePath") ?: ""  // AI downsampling disabled
        filterName       = arguments?.getString("filterName") ?: "HEART"
        popUpToDestinationId = arguments?.getInt("popUpToDestination", R.id.recordingFragment) ?: R.id.recordingFragment
        // extraAiTempPaths = arguments?.getStringArrayList("extraAiFilePaths")?.toList() ?: emptyList()  // AI downsampling disabled

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
        // Capture filterName on the main thread before switching to IO — avoids any
        // visibility issues with the plain var being read from a background thread.
        val capturedFilter = filterName
        // Embed filter as a filename prefix so the icon is derivable from the name
        // alone, with no sidecar .meta file that can go missing.
        val fullSaveName = "${capturedFilter}_${safeName}"

        lifecycleScope.launch(Dispatchers.IO) {

            // Step 1: Rename temp files into filesDir/saved/ (always happens first)
            val internalOk = saveInternally(ctx, fullSaveName)

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

    // fullSaveName = "{FILTER}_{userInput}", e.g. "LUNGS_20240321_123456"
    // Filter is embedded as a prefix so the icon is always derivable from the filename.
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

    // renameTo can silently fail (returns false) when source and destination are on
    // different mount points on some Android devices. Fall back to copy + delete so the
    // file always ends up in the right place.
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
        // aiTestingPath: String,  // AI downsampling disabled
        // extraPaths: List<String> = emptyList()  // AI downsampling disabled
    ) {
        copyOneFile(ctx, filteredPath)
        copyOneFile(ctx, rawPath)
        // if (aiTestingPath.isNotEmpty()) copyOneFile(ctx, aiTestingPath)  // AI downsampling disabled
        // for (path in extraPaths) copyOneFile(ctx, path)  // AI downsampling disabled
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
                .setPopUpTo(popUpToDestinationId, false).build()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
