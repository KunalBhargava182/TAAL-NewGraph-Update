package com.musediagnostics.taal.visualizer.ui.pointsethome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import com.musediagnostics.taal.visualizer.data.db.entity.SessionEntity
import com.musediagnostics.taal.visualizer.data.db.entity.displayName
import com.musediagnostics.taal.visualizer.data.repository.RecordingRepository
import com.musediagnostics.taal.visualizer.data.repository.SessionRenameCoordinator
import com.musediagnostics.taal.visualizer.data.repository.SessionRepository
import com.musediagnostics.taal.visualizer.databinding.FragmentPointSetHomeBinding
import com.musediagnostics.taal.visualizer.databinding.ItemSessionCardBinding
import com.musediagnostics.taal.visualizer.domain.PointSetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The per-type "folder": start/continue a session, and browse past sessions. */
class PointSetHomeFragment : Fragment() {

    private var _binding: FragmentPointSetHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var pointSetType: PointSetType
    private lateinit var sessionRepo: SessionRepository
    private lateinit var recordingRepo: RecordingRepository
    private lateinit var renameCoordinator: SessionRenameCoordinator
    private lateinit var adapter: SessionAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPointSetHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pointSetType = PointSetType.valueOf(arguments?.getString("pointSet") ?: PointSetType.HEART.name)
        binding.screenTitle.text = pointSetType.displayName

        val db = VisualizerDatabase.getInstance(requireContext())
        sessionRepo = SessionRepository(db.sessionDao())
        recordingRepo = RecordingRepository(db.recordingDao())
        renameCoordinator = SessionRenameCoordinator(requireContext().applicationContext, sessionRepo, recordingRepo)

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        adapter = SessionAdapter(
            totalPoints = pointSetType.points.size,
            onClick = { session -> openSession(session) },
            onEdit = { session -> showRenameDialog(session) }
        )
        binding.recyclerSessions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSessions.adapter = adapter
        binding.recyclerSessions.itemAnimator = null

        binding.btnStartOrContinue.setOnClickListener { startOrContinue() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionRepo.getSessionsForPointSet(pointSetType).collect { sessions ->
                    if (_binding == null) return@collect
                    binding.tvEmptyState.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                    val counts = withContext(Dispatchers.IO) {
                        sessions.associate { it.id to recordingRepo.getRecordingCountForSession(it.id) }
                    }
                    if (_binding == null) return@collect
                    adapter.submitList(sessions, counts)
                    updateStartButton(sessions, counts)
                }
            }
        }
    }

    private fun updateStartButton(sessions: List<SessionEntity>, counts: Map<Long, Int>) {
        val latest = sessions.maxByOrNull { it.sessionNumber }
        binding.btnStartOrContinue.text = if (latest == null) {
            getString(R.string.btn_start_session, 1)
        } else if ((counts[latest.id] ?: 0) < pointSetType.points.size) {
            getString(R.string.btn_continue_session, latest.sessionNumber)
        } else {
            getString(R.string.btn_start_session, latest.sessionNumber + 1)
        }
    }

    private fun startOrContinue() {
        binding.btnStartOrContinue.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) {
                sessionRepo.getOrCreateActiveSession(pointSetType) { sessionId ->
                    recordingRepo.getRecordingCountForSession(sessionId)
                }
            }
            if (!isAdded || _binding == null) return@launch
            binding.btnStartOrContinue.isEnabled = true
            findNavController().navigate(
                R.id.action_pointSetHome_to_placement,
                Bundle().apply {
                    putString("pointSet", pointSetType.name)
                    putLong("sessionId", session.id)
                    putInt("sessionNumber", session.sessionNumber)
                }
            )
        }
    }

    private fun openSession(session: SessionEntity) {
        findNavController().navigate(
            R.id.action_pointSetHome_to_review,
            Bundle().apply {
                putString("pointSet", pointSetType.name)
                putLong("sessionId", session.id)
                putInt("sessionNumber", session.sessionNumber)
            }
        )
    }

    private fun showRenameDialog(session: SessionEntity) {
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
                    renameCoordinator.renameSession(session.id, newName)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class SessionAdapter(
    private val totalPoints: Int,
    private val onClick: (SessionEntity) -> Unit,
    private val onEdit: (SessionEntity) -> Unit
) : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

    private var items: List<SessionEntity> = emptyList()
    private var counts: Map<Long, Int> = emptyMap()

    fun submitList(list: List<SessionEntity>, countsByUpdate: Map<Long, Int>) {
        items = list
        counts = countsByUpdate
        notifyDataSetChanged()
    }

    inner class ViewHolder(val b: ItemSessionCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemSessionCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = items[position]
        val count = counts[session.id] ?: 0
        holder.b.tvSessionTitle.text = session.displayName()
        holder.b.tvSessionCount.text = "$count / $totalPoints"
        holder.b.root.setOnClickListener { onClick(session) }
        holder.b.btnEditSession.setOnClickListener { onEdit(session) }
    }
}
