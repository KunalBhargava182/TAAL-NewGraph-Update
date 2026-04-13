package com.musediagnostics.taal.lungs.ui.placement

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.tabs.TabLayout
import com.musediagnostics.taal.lungs.R
import com.musediagnostics.taal.lungs.data.db.LungsDatabase
import com.musediagnostics.taal.lungs.data.repository.LungRecordingRepository
import com.musediagnostics.taal.lungs.databinding.FragmentPlacementBinding
import com.musediagnostics.taal.lungs.domain.LungPoint
import com.musediagnostics.taal.lungs.domain.LungPoints
import com.musediagnostics.taal.lungs.domain.LungRegion

class PlacementFragment : Fragment() {

    private var _binding: FragmentPlacementBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlacementViewModel by viewModels()

    private var patientId: Long = -1L
    private var patientSeqNum: Int = 1

    /** Prevents the tab-selected listener from calling setRegion during programmatic tab sync. */
    private var isSyncingTab = false

    /** Cancels stale overlay-rebuild runnables before a new one is posted. */
    private var pendingOverlayRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlacementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        patientId = arguments?.getLong("patientId") ?: -1L
        patientSeqNum = arguments?.getInt("patientSeqNum") ?: 1

        val db = LungsDatabase.getInstance(requireContext())
        val repo = LungRecordingRepository(db.lungRecordingDao())
        viewModel.init(patientId, repo)

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        // Next region button
        binding.nextRegionButton.setOnClickListener {
            val current = viewModel.currentRegionIndex.value ?: 0
            viewModel.setRegion(current + 1)
        }

        // Populate region tabs
        listOf("Ant. R", "Ant. L", "Post. R", "Post. L").forEach { label ->
            binding.regionTabs.addTab(binding.regionTabs.newTab().setText(label))
        }
        binding.regionTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (isSyncingTab) return
                viewModel.setRegion(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Observe region index → sync tab + update UI
        viewModel.currentRegionIndex.observe(viewLifecycleOwner) { idx ->
            isSyncingTab = true
            binding.regionTabs.getTabAt(idx)?.select()
            isSyncingTab = false
            updateRegionUI(LungRegion.entries[idx])
        }

        // Observe done-states → refresh overlay buttons
        viewModel.recordings.observe(viewLifecycleOwner) {
            val idx = viewModel.currentRegionIndex.value ?: 0
            updateRegionUI(LungRegion.entries[idx])
        }
    }

    private fun updateRegionUI(region: LungRegion) {
        val doneCodes = viewModel.recordings.value ?: emptyMap()
        val points = LungPoints.byRegion(region)
        val doneCount = points.count { doneCodes[it.code] == true }

        // Update labels
        binding.regionTitle.text = "Region ${region.index + 1} of 4: ${region.label}"
        binding.progressText.text = "$doneCount / 4 recorded"

        // Update anatomy placeholder image
        val drawableRes = resources.getIdentifier(
            region.drawableResName, "drawable", requireContext().packageName
        )
        if (drawableRes != 0) {
            binding.anatomyImage.setImageResource(drawableRes)
        }

        // Rebuild overlay buttons — cancel any queued post first so two rapid
        // observer firings don't pile up and double-add buttons.
        val extra = binding.anatomyContainer.childCount - 1
        if (extra > 0) binding.anatomyContainer.removeViews(1, extra)
        pendingOverlayRunnable?.let { binding.anatomyContainer.removeCallbacks(it) }
        val runnable = Runnable {
            if (_binding == null) return@Runnable
            val extra2 = binding.anatomyContainer.childCount - 1
            if (extra2 > 0) binding.anatomyContainer.removeViews(1, extra2)
            val containerW = binding.anatomyContainer.width.toFloat()
            val containerH = binding.anatomyContainer.height.toFloat()
            if (containerW > 0 && containerH > 0) {
                points.forEach { point ->
                    addPointButton(point, doneCodes[point.code] == true, containerW, containerH)
                }
            }
        }
        pendingOverlayRunnable = runnable
        binding.anatomyContainer.post(runnable)

        // Next region button state
        val allDone = doneCount == points.size
        val isLastRegion = region.index == LungRegion.entries.size - 1
        binding.nextRegionButton.isEnabled = allDone && !isLastRegion
        binding.nextRegionButton.alpha = if (allDone && !isLastRegion) 1f else 0.4f
        if (isLastRegion && allDone) {
            binding.nextRegionButton.text = getString(R.string.btn_finish)
            binding.nextRegionButton.isEnabled = true
            binding.nextRegionButton.alpha = 1f
            binding.nextRegionButton.setOnClickListener {
                // Pop all the way back to Home
                findNavController().popBackStack(R.id.homeFragment, false)
            }
        } else {
            binding.nextRegionButton.text = getString(R.string.btn_next_region)
        }
    }

    private fun addPointButton(
        point: LungPoint,
        isDone: Boolean,
        containerW: Float,
        containerH: Float
    ) {
        val btnSize = resources.getDimensionPixelSize(R.dimen.point_button_size)
        val btn = TextView(requireContext()).apply {
            text = if (isDone) "✓" else point.label.take(4)
            textSize = 9f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            background = ContextCompat.getDrawable(
                requireContext(),
                if (isDone) R.drawable.bg_point_button_done
                else R.drawable.bg_point_button_pending
            )
            isEnabled = !isDone
            alpha = if (isDone) 0.8f else 1.0f
        }

        val params = FrameLayout.LayoutParams(btnSize, btnSize).apply {
            leftMargin = (containerW * point.xFraction - btnSize / 2).toInt()
            topMargin = (containerH * point.yFraction - btnSize / 2).toInt()
        }

        // ── Touch handler ────────────────────────────────────────────────────────
        // Two behaviours are handled here:
        //   1. DRAG  — move the button to calibrate its x/y position on the image.
        //              On finger-up a Toast shows the new fractions to copy into LungPoint.kt.
        //              *** Temporarily DISABLED for testing — code is kept, just commented out. ***
        //
        //   2. TAP   — navigate to the recording screen for this point.
        //              Active only when the point is not yet recorded (!isDone).
        //
        // To re-enable drag calibration: uncomment the ACTION_MOVE block and
        // the hasDragged branch inside ACTION_UP below.
        // ─────────────────────────────────────────────────────────────────────

        // Variables used by the drag logic (kept so the commented code still compiles when restored)
        @Suppress("UNUSED_VARIABLE") var touchOffsetX = 0f
        @Suppress("UNUSED_VARIABLE") var touchOffsetY = 0f
        @Suppress("UNUSED_VARIABLE") var hasDragged = false

        btn.setOnTouchListener { v, event ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            when (event.action) {

                android.view.MotionEvent.ACTION_DOWN -> {
                    // Record where on the button the finger landed (needed by drag logic below)
                    touchOffsetX = event.x
                    touchOffsetY = event.y
                    hasDragged = false
                    true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    // ── DRAG DISABLED FOR TESTING ──────────────────────────────────────
                    // Uncomment this entire block to restore drag-to-calibrate behaviour.
                    //
                    // val containerLoc = IntArray(2)
                    // binding.anatomyContainer.getLocationOnScreen(containerLoc)
                    // val newLeft = (event.rawX - containerLoc[0] - touchOffsetX)
                    //     .toInt().coerceIn(0, containerW.toInt() - v.width)
                    // val newTop = (event.rawY - containerLoc[1] - touchOffsetY)
                    //     .toInt().coerceIn(0, containerH.toInt() - v.height)
                    // if (Math.abs(newLeft - lp.leftMargin) > 8 || Math.abs(newTop - lp.topMargin) > 8) {
                    //     hasDragged = true
                    // }
                    // lp.leftMargin = newLeft
                    // lp.topMargin = newTop
                    // v.layoutParams = lp
                    // ──────────────────────────────────────────────────────────────────
                    true
                }

                android.view.MotionEvent.ACTION_UP -> {
                    // ── DRAG TOAST DISABLED FOR TESTING ───────────────────────────────
                    // Uncomment this block (together with ACTION_MOVE above) to restore
                    // the calibration toast that prints the new x/y fractions.
                    //
                    // if (hasDragged) {
                    //     val newXFrac = (lp.leftMargin + v.width / 2f) / containerW
                    //     val newYFrac = (lp.topMargin + v.height / 2f) / containerH
                    //     android.widget.Toast.makeText(
                    //         requireContext(),
                    //         "${point.code}: x=${"%.2f".format(newXFrac)}f, y=${"%.2f".format(newYFrac)}f",
                    //         android.widget.Toast.LENGTH_LONG
                    //     ).show()
                    // } else
                    // ──────────────────────────────────────────────────────────────────

                    // TAP — navigate to recording screen (only for points not yet recorded)
                    if (!isDone) {
                        findNavController().navigate(
                            R.id.action_placement_to_recording,
                            Bundle().apply {
                                putLong("patientId", patientId)
                                putInt("patientSeqNum", patientSeqNum)
                                putString("pointCode", point.code)
                            }
                        )
                    }
                    true
                }

                else -> false
            }
        }

        binding.anatomyContainer.addView(btn, params)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
