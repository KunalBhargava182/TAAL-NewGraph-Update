package com.musediagnostics.taal.lungs.ui.denoiser

import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
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
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musediagnostics.taal.lungs.R
import com.musediagnostics.taal.lungs.data.db.LungsDatabase
import com.musediagnostics.taal.lungs.data.db.entity.LungRecordingEntity
import com.musediagnostics.taal.lungs.data.repository.LungRecordingRepository
import com.musediagnostics.taal.lungs.databinding.FragmentDenoiserBinding
import com.musediagnostics.taal.lungs.databinding.ItemDenoiserRowBinding
import com.musediagnostics.taal.lungs.denoiser.LungsDenoiser
import com.musediagnostics.taal.lungs.domain.LungPoint
import com.musediagnostics.taal.lungs.domain.LungPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DenoiserItem(
    val point: LungPoint,
    val recordingFilePath: String?,
    val denoisedFilePath: String?,
    val isDone: Boolean
)

class DenoiserFragment : Fragment() {

    private var _binding: FragmentDenoiserBinding? = null
    private val binding get() = _binding!!

    private var patientId: Long = -1L
    private var patientSeqNum: Int = 1
    private var currentRecordings: List<LungRecordingEntity> = emptyList()
    private var pendingDownloadItem: DenoiserItem? = null

    private lateinit var denoiserAdapter: DenoiserAdapter

    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingDownloadItem?.let { downloadDenoised(it) }
            } else {
                Toast.makeText(requireContext(), "Storage permission denied", Toast.LENGTH_SHORT).show()
            }
            pendingDownloadItem = null
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDenoiserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        patientId = arguments?.getLong("patientId") ?: -1L
        patientSeqNum = arguments?.getInt("patientSeqNum") ?: 1

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        denoiserAdapter = DenoiserAdapter(
            onDenoise = { item -> startDenoising(item) },
            onPlay = { item -> playDenoised(item) },
            onShare = { item -> shareDenoised(item) },
            onDownload = { item -> downloadDenoised(item) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = denoiserAdapter
        binding.recyclerView.itemAnimator = null

        val db = LungsDatabase.getInstance(requireContext())
        val recordingRepo = LungRecordingRepository(db.lungRecordingDao())

        viewLifecycleOwner.lifecycleScope.launch {
            recordingRepo.getRecordingsForPatient(patientId).collectLatest { recordings ->
                currentRecordings = recordings
                val filesDir = requireContext().filesDir
                val items = withContext(Dispatchers.IO) {
                    buildDenoiserList(recordings, filesDir)
                }
                denoiserAdapter.submitList(items)
            }
        }
    }

    private fun buildDenoiserList(
        recordings: List<LungRecordingEntity>,
        filesDir: File
    ): List<DenoiserItem> {
        val seqStr = "%02d".format(patientSeqNum)
        val recordingMap = recordings.associateBy { it.pointCode }
        return LungPoints.all.map { point ->
            val recording = recordingMap[point.code]
            val sessionNumber = recording?.let {
                File(it.filePath).parentFile?.name?.toIntOrNull() ?: 1
            } ?: 1
            val denoisedPath = if (recording != null) {
                "${filesDir.absolutePath}/lungs/$seqStr/denoised/${seqStr}.${sessionNumber}_${point.code}_denoised.wav"
            } else null
            val isDone = denoisedPath != null && File(denoisedPath).exists()
            DenoiserItem(
                point = point,
                recordingFilePath = recording?.filePath,
                denoisedFilePath = denoisedPath,
                isDone = isDone
            )
        }
    }

    private fun startDenoising(item: DenoiserItem) {
        val recordingPath = item.recordingFilePath ?: return
        val denoisedPath = item.denoisedFilePath ?: return
        val ctx = requireContext()
        denoiserAdapter.setProcessing(item.point.code, true)

        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    LungsDenoiser().denoiseWav(recordingPath, denoisedPath)
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }

            if (!isAdded || _binding == null) return@launch

            denoiserAdapter.setProcessing(item.point.code, false)

            if (success) {
                Toast.makeText(ctx, "Done", Toast.LENGTH_SHORT).show()
                val updated = withContext(Dispatchers.IO) {
                    buildDenoiserList(currentRecordings, ctx.filesDir)
                }
                denoiserAdapter.submitList(updated)
            } else {
                Toast.makeText(ctx, "Failed — original kept", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playDenoised(item: DenoiserItem) {
        val path = item.denoisedFilePath ?: return
        val bundle = Bundle().apply {
            putString("filePath", path)
            putString("rawFilePath", "")
            putLong("patientId", patientId)
            putInt("patientSeqNum", patientSeqNum)
            putString("pointCode", item.point.code)
            putBoolean("isReviewMode", true)
        }
        findNavController().navigate(R.id.action_denoiser_to_player, bundle)
    }

    private fun shareDenoised(item: DenoiserItem) {
        val path = item.denoisedFilePath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "File not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, file.name))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadDenoised(item: DenoiserItem) {
        val path = item.denoisedFilePath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "File not found", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val granted = ContextCompat.checkSelfPermission(requireContext(), permission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingDownloadItem = item
                requestStoragePermission.launch(permission)
                return
            }
        }

        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                            put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val resolver = ctx.contentResolver
                        val uri = resolver.insert(
                            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            values
                        ) ?: return@withContext false
                        resolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().copyTo(out)
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        true
                    } else {
                        @Suppress("DEPRECATION")
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        )
                        downloadsDir.mkdirs()
                        file.copyTo(File(downloadsDir, file.name), overwrite = true)
                        true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }

            if (!isAdded || _binding == null) return@launch

            if (success) {
                Toast.makeText(ctx, "Saved to Downloads: ${file.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class DenoiserAdapter(
    private val onDenoise: (DenoiserItem) -> Unit,
    private val onPlay: (DenoiserItem) -> Unit,
    private val onShare: (DenoiserItem) -> Unit,
    private val onDownload: (DenoiserItem) -> Unit
) : ListAdapter<DenoiserItem, DenoiserAdapter.ViewHolder>(DiffCb()) {

    private val processingSet = mutableSetOf<String>()

    fun setProcessing(pointCode: String, processing: Boolean) {
        if (processing) processingSet.add(pointCode) else processingSet.remove(pointCode)
        val index = currentList.indexOfFirst { it.point.code == pointCode }
        if (index != -1) notifyItemChanged(index)
    }

    inner class ViewHolder(val binding: ItemDenoiserRowBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemDenoiserRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val ctx = b.root.context
        val isProcessing = processingSet.contains(item.point.code)

        b.tvPointLabel.text = item.point.label
        b.tvRegionLabel.text = item.point.region.label

        when {
            item.isDone -> {
                b.statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.success_green)
                )
                b.btnShare.visibility = View.VISIBLE
                b.btnDownload.visibility = View.VISIBLE
                b.btnShare.setOnClickListener { onShare(item) }
                b.btnDownload.setOnClickListener { onDownload(item) }
                b.btnAction.text = "▶ Play"
                b.btnAction.isEnabled = true
                b.btnAction.setOnClickListener { onPlay(item) }
            }
            isProcessing -> {
                b.statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.divider)
                )
                b.btnShare.visibility = View.GONE
                b.btnDownload.visibility = View.GONE
                b.btnAction.text = "Processing..."
                b.btnAction.isEnabled = false
                b.btnAction.setOnClickListener(null)
            }
            item.recordingFilePath != null -> {
                b.statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.divider)
                )
                b.btnShare.visibility = View.GONE
                b.btnDownload.visibility = View.GONE
                b.btnAction.text = "Denoise"
                b.btnAction.isEnabled = true
                b.btnAction.setOnClickListener { onDenoise(item) }
            }
            else -> {
                b.statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.divider)
                )
                b.btnShare.visibility = View.GONE
                b.btnDownload.visibility = View.GONE
                b.btnAction.text = "Denoise"
                b.btnAction.isEnabled = false
                b.btnAction.setOnClickListener(null)
            }
        }
    }

    class DiffCb : DiffUtil.ItemCallback<DenoiserItem>() {
        override fun areItemsTheSame(a: DenoiserItem, b: DenoiserItem) =
            a.point.code == b.point.code
        override fun areContentsTheSame(a: DenoiserItem, b: DenoiserItem) = a == b
    }
}
