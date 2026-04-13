package com.musediagnostics.taal.lungs.ui.session

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.musediagnostics.taal.lungs.R
import com.musediagnostics.taal.lungs.data.db.LungsDatabase
import com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity
import com.musediagnostics.taal.lungs.data.db.entity.LungRecordingEntity
import com.musediagnostics.taal.lungs.data.repository.LungPatientRepository
import com.musediagnostics.taal.lungs.data.repository.LungRecordingRepository
import com.musediagnostics.taal.lungs.databinding.FragmentPatientSessionBinding
import com.musediagnostics.taal.lungs.databinding.ItemRecordingBinding
import com.musediagnostics.taal.lungs.databinding.ItemSessionHeaderBinding
import com.musediagnostics.taal.lungs.domain.LungPoint
import com.musediagnostics.taal.lungs.domain.LungPoints
import com.musediagnostics.taal.lungs.domain.LungRegion
import com.musediagnostics.taal.lungs.drive.DriveUploadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One row in the session recordings list. */
sealed class SessionListItem {
    data class Header(val region: LungRegion, val doneCount: Int) : SessionListItem()
    data class PointRow(val point: LungPoint, val recording: LungRecordingEntity?) : SessionListItem()
}

class PatientSessionFragment : Fragment() {

    private var _binding: FragmentPatientSessionBinding? = null
    private val binding get() = _binding!!

    private var patientId: Long = -1L
    private var patientSeqNum: Int = 1
    private var currentPatient: LungPatientEntity? = null

    private lateinit var adapter: SessionAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPatientSessionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        patientId = arguments?.getLong("patientId") ?: -1L
        patientSeqNum = arguments?.getInt("patientSeqNum") ?: 1

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        binding.btnShareReport.setOnClickListener { shareReport() }

        binding.btnUploadDrive.setOnClickListener { startDriveUpload() }

        binding.btnContinueRecording.setOnClickListener {
            findNavController().navigate(
                R.id.action_session_to_placement,
                Bundle().apply {
                    putLong("patientId", patientId)
                    putInt("patientSeqNum", patientSeqNum)
                }
            )
        }

        adapter = SessionAdapter(
            onPlay = { recording, point -> openPlayer(recording, point) },
            onShare = { recording -> shareRecording(recording) },
            onDelete = { recording -> confirmDelete(recording) }
        )
        binding.recyclerRecordings.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRecordings.adapter = adapter
        binding.recyclerRecordings.itemAnimator = null

        val db = LungsDatabase.getInstance(requireContext())
        val patientRepo = LungPatientRepository(db.lungPatientDao())
        val recordingRepo = LungRecordingRepository(db.lungRecordingDao())

        // Load patient info once
        viewLifecycleOwner.lifecycleScope.launch {
            val patient = withContext(Dispatchers.IO) { patientRepo.getById(patientId) }
            currentPatient = patient
            patient?.let { displayPatient(it) }
        }

        // Observe recordings reactively (handles deletions too)
        viewLifecycleOwner.lifecycleScope.launch {
            recordingRepo.getRecordingsForPatient(patientId).collectLatest { recordings ->
                val items = buildSessionList(recordings)
                adapter.submitList(items)
                val count = recordings.size
                binding.tvRecordingCount.text = if (count == 16)
                    getString(R.string.session_complete)
                else
                    getString(R.string.session_partial, count)
                binding.btnContinueRecording.visibility =
                    if (count < 16) View.VISIBLE else View.GONE
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Patient info

    private fun displayPatient(p: LungPatientEntity) {
        binding.screenTitle.text = "Patient %02d — Session".format(p.sequenceNumber)
        binding.tvPatientTitle.text = "Patient %02d".format(p.sequenceNumber)
        binding.tvSex.text = p.sex
        binding.tvAge.text = "${p.age} yrs"
        binding.tvBmi.text = "%.1f".format(p.bmi)
        binding.tvChest.text = "%.1f".format(p.chestCircumferenceCm)
        binding.tvHeight.text = "%.1f".format(p.heightCm)
        binding.tvWeight.text = "%.1f".format(p.weightKg)
        val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        binding.tvCreatedAt.text = "Recorded: ${fmt.format(Date(p.createdAt))}"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // List building

    private fun buildSessionList(recordings: List<LungRecordingEntity>): List<SessionListItem> {
        val map = recordings.associateBy { it.pointCode }
        val items = mutableListOf<SessionListItem>()
        LungRegion.entries.forEach { region ->
            val points = LungPoints.byRegion(region)
            val doneCount = points.count { map.containsKey(it.code) }
            items.add(SessionListItem.Header(region, doneCount))
            points.forEach { point ->
                items.add(SessionListItem.PointRow(point, map[point.code]))
            }
        }
        return items
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actions

    private fun openPlayer(recording: LungRecordingEntity, point: LungPoint) {
        findNavController().navigate(
            R.id.action_session_to_player,
            Bundle().apply {
                putString("filePath", recording.filePath)
                putString("rawFilePath", "")
                putLong("patientId", patientId)
                putInt("patientSeqNum", patientSeqNum)
                putString("pointCode", point.code)
                putBoolean("isReviewMode", true)
            }
        )
    }

    private fun shareRecording(recording: LungRecordingEntity) {
        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(requireContext(), getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
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

    private fun confirmDelete(recording: LungRecordingEntity) {
        val fileName = File(recording.filePath).name
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_recording_title))
            .setMessage("$fileName ${getString(R.string.delete_recording_message)}")
            .setPositiveButton(getString(R.string.btn_delete_confirm)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val db = LungsDatabase.getInstance(requireContext())
                    db.lungRecordingDao().deleteById(recording.id)
                    try { File(recording.filePath).delete() } catch (_: Exception) {}
                }
            }
            .setNegativeButton(getString(R.string.btn_keep)) { d, _ -> d.dismiss() }
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drive upload

    private fun startDriveUpload() {
        val patient = currentPatient ?: run {
            Toast.makeText(requireContext(), "Patient data not loaded yet", Toast.LENGTH_SHORT).show()
            return
        }

        val recordings = adapter.currentItems()
            .filterIsInstance<SessionListItem.PointRow>()
            .mapNotNull { it.recording }

        if (recordings.isEmpty()) {
            Toast.makeText(requireContext(), "No recordings to upload", Toast.LENGTH_SHORT).show()
            return
        }

        // Progress text view inside a non-dismissable dialog
        val progressTextView = TextView(requireContext()).apply {
            text = getString(R.string.upload_preparing)
            setPadding(64, 40, 64, 24)
            textSize = 14f
        }
        val progressDialog: AlertDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.upload_title))
            .setView(progressTextView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        binding.btnUploadDrive.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val helper = DriveUploadHelper(requireContext())
                val folderUrl = helper.uploadSession(patient, recordings) { uploaded, total ->
                    // Called on Main thread by DriveUploadHelper
                    if (isAdded && progressDialog.isShowing) {
                        progressTextView.text = getString(R.string.upload_progress, uploaded, total)
                    }
                }

                progressDialog.dismiss()

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.upload_complete))
                    .setMessage(
                        "${recordings.size} recordings + report uploaded.\n\nFolder saved in Google Drive."
                    )
                    .setPositiveButton(getString(R.string.open_in_drive)) { d, _ ->
                        d.dismiss()
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(folderUrl)))
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton(android.R.string.ok) { d, _ -> d.dismiss() }
                    .show()

            } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
                if (isAdded) {
                    progressDialog.dismiss()
                    val reason = e.details?.errors?.firstOrNull()?.reason ?: e.details?.message ?: e.message
                    Toast.makeText(
                        requireContext(),
                        "${getString(R.string.upload_failed)} (${e.statusCode}): $reason",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        "${getString(R.string.upload_failed)}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                if (isAdded) binding.btnUploadDrive.isEnabled = true
            }
        }
    }

    private fun shareReport() {
        val patient = currentPatient ?: return
        val sb = StringBuilder()
        sb.appendLine("=== LUNGS AUSCULTATION REPORT ===")
        sb.appendLine()
        sb.appendLine("Patient ID : %02d".format(patient.sequenceNumber))
        sb.appendLine("Sex        : ${patient.sex}")
        sb.appendLine("Age        : ${patient.age} yrs")
        sb.appendLine("BMI        : ${"%.1f".format(patient.bmi)}")
        sb.appendLine("Chest      : ${"%.1f".format(patient.chestCircumferenceCm)} cm")
        sb.appendLine("Height     : ${"%.1f".format(patient.heightCm)} cm")
        sb.appendLine("Weight     : ${"%.1f".format(patient.weightKg)} kg")
        val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        sb.appendLine("Date       : ${fmt.format(Date(patient.createdAt))}")
        sb.appendLine()
        sb.appendLine("--- RECORDINGS ---")
        val items = adapter.currentItems()
        items.forEach { item ->
            when (item) {
                is SessionListItem.Header ->
                    sb.appendLine("\n${item.region.label.uppercase()} (${item.doneCount}/4)")
                is SessionListItem.PointRow -> {
                    val rec = item.recording
                    if (rec != null) {
                        val dur = "%d:%02d".format(rec.durationSeconds / 60, rec.durationSeconds % 60)
                        sb.appendLine("  [✓] ${item.point.label} (${item.point.code})  –  $dur")
                    } else {
                        sb.appendLine("  [ ] ${item.point.label} (${item.point.code})  –  not recorded")
                    }
                }
            }
        }
        sb.appendLine()
        sb.appendLine("Generated by Lungs Auscultation App")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Patient %02d Auscultation Report".format(patient.sequenceNumber))
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.btn_share_report)))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter

private class SessionAdapter(
    private val onPlay: (LungRecordingEntity, LungPoint) -> Unit,
    private val onShare: (LungRecordingEntity) -> Unit,
    private val onDelete: (LungRecordingEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_POINT = 1
    }

    private var items: List<SessionListItem> = emptyList()

    fun submitList(list: List<SessionListItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun currentItems(): List<SessionListItem> = items

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is SessionListItem.Header -> TYPE_HEADER
        is SessionListItem.PointRow -> TYPE_POINT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemSessionHeaderBinding.inflate(inflater, parent, false))
            else -> PointVH(ItemRecordingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SessionListItem.Header -> (holder as HeaderVH).bind(item)
            is SessionListItem.PointRow -> (holder as PointVH).bind(item)
        }
    }

    // ── Header ViewHolder ────────────────────────────────────────────────────

    inner class HeaderVH(private val b: ItemSessionHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: SessionListItem.Header) {
            b.tvRegionLabel.text = item.region.label
            b.tvRegionCount.text = "${item.doneCount} / 4"
            b.tvRegionCount.setTextColor(
                ContextCompat.getColor(
                    b.root.context,
                    if (item.doneCount == 4) R.color.success_green else R.color.text_secondary
                )
            )
        }
    }

    // ── Point ViewHolder ─────────────────────────────────────────────────────

    inner class PointVH(private val b: ItemRecordingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: SessionListItem.PointRow) {
            val ctx = b.root.context
            val point = item.point
            val rec = item.recording

            b.tvPointLabel.text = "${point.label}  ·  ${point.code}"

            if (rec != null) {
                // Recorded
                val fileName = File(rec.filePath).name
                val dur = "%d:%02d".format(rec.durationSeconds / 60, rec.durationSeconds % 60)
                b.tvSubtitle.text = "$fileName  •  $dur"
                b.tvSubtitle.visibility = View.VISIBLE
                b.tvNotRecorded.visibility = View.GONE
                b.actionButtons.visibility = View.VISIBLE
                b.statusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.success_green)
                    )

                b.btnPlay.setOnClickListener { onPlay(rec, point) }
                b.btnShare.setOnClickListener { onShare(rec) }
                b.btnDelete.setOnClickListener { onDelete(rec) }
            } else {
                // Not yet recorded
                b.tvSubtitle.visibility = View.GONE
                b.tvNotRecorded.visibility = View.VISIBLE
                b.actionButtons.visibility = View.GONE
                b.statusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.divider)
                    )
            }
        }
    }
}
