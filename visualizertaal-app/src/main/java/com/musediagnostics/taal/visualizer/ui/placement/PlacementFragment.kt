package com.musediagnostics.taal.visualizer.ui.placement

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.tabs.TabLayout
import com.musediagnostics.taal.visualizer.R
import com.musediagnostics.taal.visualizer.data.db.VisualizerDatabase
import com.musediagnostics.taal.visualizer.data.repository.RecordingRepository
import com.musediagnostics.taal.visualizer.databinding.FragmentPlacementBinding
import com.musediagnostics.taal.visualizer.domain.PointSetType
import com.musediagnostics.taal.visualizer.domain.RecordingPoint
import kotlinx.coroutines.launch

class PlacementFragment : Fragment() {

    private var _binding: FragmentPlacementBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlacementViewModel by viewModels()

    private lateinit var pointSetType: PointSetType
    private var sessionId: Long = -1L
    private var sessionNumber: Int = 1
    private lateinit var recordingRepo: RecordingRepository

    private var recordedCodes: Set<String> = emptySet()
    /** Tracks the previous emission so a genuinely new save (not just recomposition) triggers auto-advance. */
    private var previousRecordedCodes: Set<String> = emptySet()
    private var currentPoints: List<RecordingPoint> = emptyList()

    private var isSyncingRegionTab = false
    private var isSyncingPointTab = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlacementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pointSetType = PointSetType.valueOf(arguments?.getString("pointSet") ?: PointSetType.HEART.name)
        sessionId = arguments?.getLong("sessionId") ?: -1L
        sessionNumber = arguments?.getInt("sessionNumber") ?: 1
        recordingRepo = RecordingRepository(VisualizerDatabase.getInstance(requireContext()).recordingDao())

        binding.screenTitle.text = "${pointSetType.displayName} — Session $sessionNumber"
        binding.instructionText.text = getString(R.string.placement_instruction)

        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.reviewButton.setOnClickListener {
            findNavController().navigate(
                R.id.action_placement_to_review,
                Bundle().apply {
                    putString("pointSet", pointSetType.name)
                    putLong("sessionId", sessionId)
                    putInt("sessionNumber", sessionNumber)
                }
            )
        }

        val regions = pointSetType.regions
        if (regions.isEmpty()) {
            binding.regionTabs.visibility = View.GONE
            setupPointTabs(pointSetType.points)
        } else {
            binding.regionTabs.visibility = View.VISIBLE
            setupRegionTabs(regions)
        }

        binding.recordButton.setOnClickListener {
            val code = viewModel.currentPointCode ?: return@setOnClickListener
            findNavController().navigate(
                R.id.action_placement_to_recording,
                Bundle().apply {
                    putString("pointSet", pointSetType.name)
                    putString("pointCode", code)
                    putLong("sessionId", sessionId)
                    putInt("sessionNumber", sessionNumber)
                }
            )
        }

        // Registered once here (not per region switch) so it always reads the
        // live currentPoints field instead of closing over a stale list.
        binding.pointTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (isSyncingPointTab) return
                currentPoints.getOrNull(tab.position)?.let { selectPoint(it) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                recordingRepo.getRecordingsForSession(sessionId).collect { recordings ->
                    val newRecordedCodes = recordings.map { it.pointCode }.toSet()
                    val newlyDone = newRecordedCodes - previousRecordedCodes
                    recordedCodes = newRecordedCodes
                    refreshDoneState()
                    if (newlyDone.isNotEmpty()) advanceToNextUndonePoint()
                    previousRecordedCodes = newRecordedCodes
                }
            }
        }
    }

    private fun setupRegionTabs(regions: List<String>) {
        binding.regionTabs.removeAllTabs()
        regions.forEach { region -> binding.regionTabs.addTab(binding.regionTabs.newTab().setText(region)) }
        binding.regionTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (isSyncingRegionTab) return
                viewModel.currentRegionIndex = tab.position
                viewModel.currentPointCode = null
                setupPointTabs(pointSetType.points.filter { it.region == regions[tab.position] })
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        val startIndex = viewModel.currentRegionIndex.coerceIn(0, regions.size - 1)
        isSyncingRegionTab = true
        binding.regionTabs.getTabAt(startIndex)?.select()
        isSyncingRegionTab = false
        setupPointTabs(pointSetType.points.filter { it.region == regions[startIndex] })
    }

    private fun setupPointTabs(points: List<RecordingPoint>) {
        currentPoints = points
        binding.pointTabs.removeAllTabs()
        points.forEach { point -> binding.pointTabs.addTab(binding.pointTabs.newTab().setText(point.label)) }

        if (points.isEmpty()) return
        val savedCode = viewModel.currentPointCode
        val startIndex = points.indexOfFirst { it.code == savedCode }.let { if (it >= 0) it else 0 }
        isSyncingPointTab = true
        binding.pointTabs.getTabAt(startIndex)?.select()
        isSyncingPointTab = false
        selectPoint(points[startIndex])
        refreshDoneState()
    }

    private fun selectPoint(point: RecordingPoint) {
        viewModel.currentPointCode = point.code
        binding.pointLabel.text = point.label

        val imgRes = resources.getIdentifier("point_${point.code}", "drawable", requireContext().packageName)
        if (imgRes != 0) {
            binding.placementImage.setImageResource(imgRes)
            binding.placementImage.visibility = View.VISIBLE
        } else {
            binding.placementImage.visibility = View.GONE
        }

        updateRecordButtonLabel(point.code)
    }

    private fun refreshDoneState() {
        if (_binding == null) return
        for (i in 0 until binding.pointTabs.tabCount) {
            val point = currentPoints.getOrNull(i) ?: continue
            val done = recordedCodes.contains(point.code)
            binding.pointTabs.getTabAt(i)?.text = if (done) "✓ ${point.label}" else point.label
        }
        viewModel.currentPointCode?.let { updateRecordButtonLabel(it) }
    }

    /** After a point is saved, jump to the next unrecorded point — same region first, then the next region. */
    private fun advanceToNextUndonePoint() {
        if (_binding == null) return
        val nextInRegion = currentPoints.indexOfFirst { !recordedCodes.contains(it.code) }
        if (nextInRegion >= 0) {
            if (binding.pointTabs.selectedTabPosition != nextInRegion) {
                binding.pointTabs.getTabAt(nextInRegion)?.select()
            }
            return
        }
        val regions = pointSetType.regions
        if (regions.isEmpty()) return
        val currentRegionIdx = viewModel.currentRegionIndex
        for (offset in 1..regions.size) {
            val idx = (currentRegionIdx + offset) % regions.size
            val regionPoints = pointSetType.points.filter { it.region == regions[idx] }
            if (regionPoints.any { !recordedCodes.contains(it.code) }) {
                if (binding.regionTabs.selectedTabPosition != idx) {
                    binding.regionTabs.getTabAt(idx)?.select()
                }
                return
            }
        }
    }

    private fun updateRecordButtonLabel(code: String) {
        val isDone = recordedCodes.contains(code)
        binding.recordButton.text = if (isDone) getString(R.string.btn_rerecord) else getString(R.string.btn_record)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
