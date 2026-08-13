package com.musediagnostics.taal.visualizer.ui.review

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.musediagnostics.taal.visualizer.R
import com.musediagnostics.taal.visualizer.data.db.VisualizerDatabase
import com.musediagnostics.taal.visualizer.data.db.entity.RecordingEntity
import com.musediagnostics.taal.visualizer.data.db.entity.SessionEntity
import com.musediagnostics.taal.visualizer.data.db.entity.displayName
import com.musediagnostics.taal.visualizer.data.repository.RecordingRepository
import com.musediagnostics.taal.visualizer.data.repository.SessionRenameCoordinator
import com.musediagnostics.taal.visualizer.data.repository.SessionRepository
import com.musediagnostics.taal.visualizer.databinding.FragmentReviewBinding
import com.musediagnostics.taal.visualizer.databinding.ItemReviewHeaderBinding
import com.musediagnostics.taal.visualizer.databinding.ItemReviewRowBinding
import com.musediagnostics.taal.visualizer.domain.PointSetType
import com.musediagnostics.taal.visualizer.domain.RecordingPoint
import com.musediagnostics.taal.visualizer.util.DownloadsStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in the session detail list. */
sealed class ReviewListItem {
    data class Header(val region: String, val doneCount: Int, val total: Int) : ReviewListItem()
    data class PointRow(val point: RecordingPoint, val recording: RecordingEntity?) : ReviewListItem()
}

/** Session detail screen: play/share/delete/re-record per point, backed by the database. */
class ReviewFragment : Fragment() {

    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding!!

    private lateinit var pointSetType: PointSetType
    private var sessionId: Long = -1L
    private var sessionNumber: Int = 1
    private lateinit var recordingRepo: RecordingRepository
    private lateinit var sessionRepo: SessionRepository
    private lateinit var renameCoordinator: SessionRenameCoordinator
    private lateinit var adapter: ReviewAdapter
    private var currentSession: SessionEntity? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pointSetType = PointSetType.valueOf(arguments?.getString("pointSet") ?: PointSetType.HEART.name)
        sessionId = arguments?.getLong("sessionId") ?: -1L
        sessionNumber = arguments?.getInt("sessionNumber") ?: 1
        val db = VisualizerDatabase.getInstance(requireContext())
        recordingRepo = RecordingRepository(db.recordingDao())
        sessionRepo = SessionRepository(db.sessionDao())
        renameCoordinator = SessionRenameCoordinator(requireContext().applicationContext, sessionRepo, recordingRepo)

        binding.screenTitle.text = "${pointSetType.displayName} — Session $sessionNumber"
        refreshSessionTitle()

        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.btnEditSessionName.setOnClickListener { showRenameDialog() }

        adapter = ReviewAdapter(
            onPlay = { recording, point -> openPlayer(recording, point) },
            onRecord = { point -> rerecord(point) },
            onShare = { recording -> shareRecording(recording) },
            onDelete = { recording -> confirmDelete(recording) }
        )
        binding.recyclerReview.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerReview.adapter = adapter
        binding.recyclerReview.itemAnimator = null

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                recordingRepo.getRecordingsForSession(sessionId).collect { recordings ->
                    adapter.submitList(buildReviewList(recordings))
                    val total = pointSetType.points.size
                    binding.tvRecordingCount.text = "${recordings.size} / $total recorded"
                }
            }
        }
    }

    private fun refreshSessionTitle() {
        viewLifecycleOwner.lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { sessionRepo.getById(sessionId) }
            if (_binding == null || session == null) return@launch
            currentSession = session
            binding.screenTitle.text = "${pointSetType.displayName} — ${session.displayName()}"
        }
    }

    private fun showRenameDialog() {
        val session = currentSession ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_rename_session, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.sessionNameInput)
        nameInput.setText(session.customName.orEmpty())
        nameInput.setSelection(nameInput.text?.length ?: 0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rename_session_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.btn_save_name)) { _, _ ->
                val newName = nameInput.text?.toString().orEmpty()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    renameCoordinator.renameSession(sessionId, newName)
                    withContext(Dispatchers.Main) { refreshSessionTitle() }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun buildReviewList(recordings: List<RecordingEntity>): List<ReviewListItem> {
        val byCode = recordings.associateBy { it.pointCode }
        val regions = pointSetType.regions
        val items = mutableListOf<ReviewListItem>()
        if (regions.isEmpty()) {
            pointSetType.points.forEach { point -> items.add(ReviewListItem.PointRow(point, byCode[point.code])) }
        } else {
            regions.forEach { region ->
                val points = pointSetType.points.filter { it.region == region }
                val doneCount = points.count { byCode.containsKey(it.code) }
                items.add(ReviewListItem.Header(region, doneCount, points.size))
                points.forEach { point -> items.add(ReviewListItem.PointRow(point, byCode[point.code])) }
            }
        }
        return items
    }

    private fun openPlayer(recording: RecordingEntity, point: RecordingPoint) {
        if (!isAdded || _binding == null) return
        findNavController().navigate(
            R.id.action_review_to_player,
            Bundle().apply {
                putString("filePath", recording.uriOrPath)
                putString("rawFilePath", "")
                putString("pointSet", pointSetType.name)
                putString("pointCode", point.code)
                putLong("sessionId", sessionId)
                putInt("sessionNumber", sessionNumber)
                putBoolean("isReviewMode", true)
            }
        )
    }

    private fun rerecord(point: RecordingPoint) {
        findNavController().navigate(
            R.id.action_review_to_recording,
            Bundle().apply {
                putString("pointSet", pointSetType.name)
                putString("pointCode", point.code)
                putLong("sessionId", sessionId)
                putInt("sessionNumber", sessionNumber)
                putString("returnTo", "review")
            }
        )
    }

    private fun shareRecording(recording: RecordingEntity) {
        try {
            val uri = DownloadsStorage.shareUri(requireContext(), recording.uriOrPath)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, recording.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, recording.fileName))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(recording: RecordingEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_recording_title))
            .setMessage("${recording.fileName} ${getString(R.string.delete_recording_message)}")
            .setPositiveButton(getString(R.string.btn_delete_confirm)) { _, _ ->
                DownloadsStorage.delete(requireContext(), recording.uriOrPath)
                viewLifecycleOwner.lifecycleScope.launch { recordingRepo.deleteById(recording.id) }
            }
            .setNegativeButton(getString(R.string.btn_keep)) { d, _ -> d.dismiss() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class ReviewAdapter(
    private val onPlay: (RecordingEntity, RecordingPoint) -> Unit,
    /** Used both to record a not-yet-done point and to re-record an already-saved one. */
    private val onRecord: (RecordingPoint) -> Unit,
    private val onShare: (RecordingEntity) -> Unit,
    private val onDelete: (RecordingEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_POINT = 1
    }

    private var items: List<ReviewListItem> = emptyList()

    fun submitList(list: List<ReviewListItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is ReviewListItem.Header -> TYPE_HEADER
        is ReviewListItem.PointRow -> TYPE_POINT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemReviewHeaderBinding.inflate(inflater, parent, false))
            else -> PointVH(ItemReviewRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ReviewListItem.Header -> (holder as HeaderVH).bind(item)
            is ReviewListItem.PointRow -> (holder as PointVH).bind(item)
        }
    }

    inner class HeaderVH(private val b: ItemReviewHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ReviewListItem.Header) {
            b.tvRegionLabel.text = item.region
            b.tvRegionCount.text = "${item.doneCount} / ${item.total}"
            b.tvRegionCount.setTextColor(
                ContextCompat.getColor(
                    b.root.context,
                    if (item.doneCount == item.total) R.color.success_green else R.color.text_secondary
                )
            )
        }
    }

    inner class PointVH(private val b: ItemReviewRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ReviewListItem.PointRow) {
            val ctx = b.root.context
            val point = item.point
            val rec = item.recording

            b.tvPointLabel.text = point.label

            if (rec != null) {
                b.tvSubtitle.text = rec.fileName
                b.tvSubtitle.visibility = View.VISIBLE
                b.btnRecordPoint.visibility = View.GONE
                b.actionButtons.visibility = View.VISIBLE
                b.statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.success_green)
                )

                b.btnPlay.setOnClickListener { onPlay(rec, point) }
                b.btnRerecord.setOnClickListener { onRecord(point) }
                b.btnShare.setOnClickListener { onShare(rec) }
                b.btnDelete.setOnClickListener { onDelete(rec) }
            } else {
                b.tvSubtitle.visibility = View.GONE
                b.actionButtons.visibility = View.GONE
                b.btnRecordPoint.visibility = View.VISIBLE
                b.statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.divider)
                )
                b.btnRecordPoint.setOnClickListener { onRecord(point) }
            }
        }
    }
}
