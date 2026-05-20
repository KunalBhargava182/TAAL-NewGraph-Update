package com.musediagnostics.taal.lungs.ui.session

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.musediagnostics.taal.lungs.R
import com.musediagnostics.taal.lungs.data.db.LungsDatabase
import com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity
import com.musediagnostics.taal.lungs.data.db.entity.LungSessionEntity
import com.musediagnostics.taal.lungs.data.repository.LungPatientRepository
import com.musediagnostics.taal.lungs.data.repository.LungRecordingRepository
import com.musediagnostics.taal.lungs.data.repository.LungSessionRepository
import com.musediagnostics.taal.lungs.databinding.FragmentPatientSessionsListBinding
import com.musediagnostics.taal.lungs.databinding.ItemSessionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PatientSessionsListFragment : Fragment() {

    private var _binding: FragmentPatientSessionsListBinding? = null
    private val binding get() = _binding!!

    private var patientId: Long = -1L
    private var patientSeqNum: Int = 1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPatientSessionsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        patientId = arguments?.getLong("patientId") ?: -1L
        patientSeqNum = arguments?.getInt("patientSeqNum") ?: 1

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        val db = LungsDatabase.getInstance(requireContext())
        val patientRepo = LungPatientRepository(db.lungPatientDao())
        val sessionRepo = LungSessionRepository(db.lungSessionDao())
        val recordingRepo = LungRecordingRepository(db.lungRecordingDao())

        // Load patient info
        viewLifecycleOwner.lifecycleScope.launch {
            val patient = withContext(Dispatchers.IO) { patientRepo.getById(patientId) }
            patient?.let { displayPatient(it) }
        }

        binding.btnEditPatient.setOnClickListener {
            findNavController().navigate(
                R.id.action_sessions_list_to_edit_patient,
                Bundle().apply { putLong("patientId", patientId) }
            )
        }

        // Record Again — create new session and go straight to placement
        binding.btnRecordAgain.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val (newSessionId, newSessionNumber) = withContext(Dispatchers.IO) {
                    val count = sessionRepo.getSessionCountForPatient(patientId)
                    val newNumber = count + 1
                    val newSession = LungSessionEntity(patientId = patientId, sessionNumber = newNumber)
                    val insertedId = sessionRepo.insert(newSession)
                    insertedId to newNumber
                }
                if (!isAdded || _binding == null) return@launch
                findNavController().navigate(
                    R.id.action_sessions_list_to_placement,
                    Bundle().apply {
                        putLong("patientId", patientId)
                        putInt("patientSeqNum", patientSeqNum)
                        putLong("sessionId", newSessionId)
                        putInt("sessionNumber", newSessionNumber)
                    }
                )
            }
        }

        // Sessions list
        val adapter = SessionListAdapter(recordingRepo) { session ->
            findNavController().navigate(
                R.id.action_sessions_list_to_session,
                Bundle().apply {
                    putLong("patientId", patientId)
                    putInt("patientSeqNum", patientSeqNum)
                    putLong("sessionId", session.id)
                    putInt("sessionNumber", session.sessionNumber)
                }
            )
        }
        binding.recyclerSessions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSessions.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            sessionRepo.getSessionsForPatient(patientId).collectLatest { sessions ->
                adapter.submitList(sessions)
            }
        }
    }

    private fun displayPatient(p: LungPatientEntity) {
        binding.screenTitle.text = "Patient %02d".format(p.sequenceNumber)
        binding.tvPatientTitle.text = "Patient %02d".format(p.sequenceNumber)
        binding.tvSex.text = p.sex
        binding.tvAge.text = "${p.age} yrs"
        binding.tvBmi.text = "%.1f".format(p.bmi)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class SessionListAdapter(
    private val recordingRepo: LungRecordingRepository,
    private val onClick: (LungSessionEntity) -> Unit
) : RecyclerView.Adapter<SessionListAdapter.VH>() {

    private var items: List<LungSessionEntity> = emptyList()
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    fun submitList(list: List<LungSessionEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(private val b: ItemSessionBinding) : RecyclerView.ViewHolder(b.root) {
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun bind(session: LungSessionEntity) {
            b.tvSessionBadge.text = "S${session.sessionNumber}"
            b.tvSessionTitle.text = "Session ${session.sessionNumber}"
            b.tvSessionDate.text = dateFmt.format(Date(session.createdAt))
            b.tvSessionRecordingCount.text = "-- / 16"
            b.root.setOnClickListener { onClick(session) }

            scope.launch {
                val count = recordingRepo.getRecordingCountForSession(session.id)
                b.tvSessionRecordingCount.text = "$count / 16"
            }
        }
    }
}
