// Full-screen landscape chart. Reached only from SegmentationReportFragment's "Full Segmentation"
// button, and only when there is a SegmentationResult already computed (Ok/TooWeak) — it reads
// that result from the shared SegmentationViewModel rather than re-running inference, since
// rotating into landscape recreates the Activity (the manifest locks MainActivity to portrait,
// with no android:configChanges to handle orientation itself).
package com.musediagnostics.taal.stemz.uikit.segmentation

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.stemz.uikit.R
import com.musediagnostics.taal.stemz.uikit.databinding.TsukFragmentSegmentationFullscreenBinding
import com.musediagnostics.taal.stemz.segmentation.SegmentationOutcome

internal class SegmentationFullScreenFragment : Fragment() {

    private var _binding: TsukFragmentSegmentationFullscreenBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SegmentationViewModel by activityViewModels()

    private var resultDurationSec = 0.0
    private var currentWindowStart = 0.0
    private var currentWindowSizeSec = 0.0
    private var dragStartX = 0f
    private var dragStartWindowStart = 0.0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = TsukFragmentSegmentationFullscreenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val audio = viewModel.audio
        val result = when (val outcome = viewModel.outcome) {
            is SegmentationOutcome.Ok -> outcome.result
            is SegmentationOutcome.TooWeak -> outcome.result
            else -> null
        }
        if (audio == null || result == null) {
            // The button that opens this screen is gated on having a result — reaching here
            // without one means the ViewModel got cleared some other way. Nothing to show.
            findNavController().navigateUp()
            return
        }

        resultDurationSec = result.durationSec
        currentWindowSizeSec = resultDurationSec
        currentWindowStart = 0.0

        binding.fullScreenChart.setRecording(audio, viewModel.sampleRate, result)

        binding.closeButton.setOnClickListener { findNavController().navigateUp() }
        binding.zoomFullButton.setOnClickListener { setZoom(resultDurationSec, binding.zoomFullButton) }
        binding.zoom10Button.setOnClickListener { setZoom(10.0, binding.zoom10Button) }
        binding.zoom5Button.setOnClickListener { setZoom(5.0, binding.zoom5Button) }
        binding.fullScreenChart.setOnTouchListener { v, event -> handlePan(v, event) }
        markZoomSelected(binding.zoomFullButton) // "Full" is the default view on load
    }

    /** Sets the zoom level, centred on whatever is currently in view rather than jumping to 0. */
    private fun setZoom(sizeSec: Double, selectedButton: Button) {
        if (resultDurationSec <= 0) return
        val clampedSize = sizeSec.coerceIn(MIN_WINDOW_SEC, resultDurationSec)
        val center = currentWindowStart + currentWindowSizeSec / 2.0
        val maxStart = (resultDurationSec - clampedSize).coerceAtLeast(0.0)
        val newStart = (center - clampedSize / 2.0).coerceIn(0.0, maxStart)
        currentWindowSizeSec = clampedSize
        currentWindowStart = newStart
        binding.fullScreenChart.setWindowSeconds(newStart, newStart + clampedSize)
        markZoomSelected(selectedButton)
    }

    /** Highlights whichever zoom level is active — teal chip on the selected one, dimmed text on the rest. */
    private fun markZoomSelected(selectedButton: Button) {
        for (button in listOf(binding.zoomFullButton, binding.zoom10Button, binding.zoom5Button)) {
            val isSelected = button === selectedButton
            button.setBackgroundResource(
                if (isSelected) R.drawable.tsuk_bg_chip_selected else android.R.color.transparent
            )
            button.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#99FFFFFF"))
        }
    }

    /** Horizontal drag scrubs the window when zoomed in; a no-op at the Full zoom level. */
    private fun handlePan(view: View, event: MotionEvent): Boolean {
        if (currentWindowSizeSec >= resultDurationSec) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                dragStartWindowStart = currentWindowStart
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dxPx = event.x - dragStartX
                val secPerPx = currentWindowSizeSec / view.width.toDouble()
                val maxStart = (resultDurationSec - currentWindowSizeSec).coerceAtLeast(0.0)
                val newStart = (dragStartWindowStart - dxPx * secPerPx).coerceIn(0.0, maxStart)
                currentWindowStart = newStart
                binding.fullScreenChart.setWindowSeconds(newStart, newStart + currentWindowSizeSec)
                true
            }
            else -> false
        }
    }

    override fun onResume() {
        super.onResume()
        // Captured only once — the ViewModel survives the Activity recreation this very call can
        // trigger, so the second (and later) time onResume runs here the value is already set and
        // must not be overwritten with the now-current LANDSCAPE value.
        if (viewModel.originalOrientationForFullScreen == null) {
            viewModel.originalOrientationForFullScreen = requireActivity().requestedOrientation
        }
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * Restores both the requested orientation and the system bars.
     *
     * Restores the exact captured value (normally SCREEN_ORIENTATION_PORTRAIT, since the
     * activity is manifest-locked to portrait). Previously this translated a captured PORTRAIT
     * back to SCREEN_ORIENTATION_UNSPECIFIED on the theory that it would "let the manifest value
     * govern again" — but UNSPECIFIED does not re-apply the manifest's screenOrientation, it
     * tells the system "no preference," which left the whole app free to sit in whatever
     * orientation the device was physically in when leaving this screen (landscape). Restoring
     * the captured value directly avoids that.
     */
    private fun restoreOrientationAndChrome() {
        val original = viewModel.originalOrientationForFullScreen
        if (original != null) {
            requireActivity().requestedOrientation = original
            viewModel.originalOrientationForFullScreen = null
        }
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // isChangingConfigurations is true when this teardown is the Activity recreating for the
        // orientation change WE just requested — restoring here would immediately fight our own
        // landscape lock and loop. Only restore when actually leaving the screen for good.
        if (!requireActivity().isChangingConfigurations) {
            restoreOrientationAndChrome()
        }
        _binding = null
    }

    companion object {
        private const val MIN_WINDOW_SEC = 1.0
    }
}
