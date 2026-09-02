// Report screen for the raw-TAAL-audio -> TaalCardiacSegmentation pipeline. Reached only from
// PlayerFragment's "Analyze Heart Sounds" button on an already-SAVED recording — the rawFilePath
// argument always points at a saved {name}_raw.wav on disk, never a temp file. Whole feature is
// gated by SegmentationFeature.ENABLED at the PlayerFragment entry point.
package com.musediagnostics.taal.app.ui.segmentation

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentSegmentationReportBinding
import com.musediagnostics.taal.segmentation.SegmentationOutcome
import com.musediagnostics.taal.segmentation.TaalCardiacSegmentation
import com.musediagnostics.taal.segmentation.heartRateBpm
import com.musediagnostics.taal.segmentation.systolicIntervalsMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SegmentationReportFragment : Fragment() {

    private var _binding: FragmentSegmentationReportBinding? = null
    private val binding get() = _binding!!

    private var segmenter: TaalCardiacSegmentation? = null

    private var currentOutcome: SegmentationOutcome? = null
    private var currentAudio: FloatArray? = null
    private var currentSampleRate: Int = 0

    private var resultDurationSec = 0.0
    private var currentWindowStart = 0.0
    private var currentWindowSizeSec = 0.0

    private val sharedViewModel: SegmentationViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSegmentationReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener { goToSavedRecordings() }

        // The device/gesture back action must land in the same place as the on-screen
        // back button (Saved Recordings, not Player) — without this callback it would
        // fall through to the default navigateUp() behavior instead.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goToSavedRecordings()
        })

        val rawFilePath = arguments?.getString("rawFilePath") ?: ""
        val rawFile = File(rawFilePath)
        if (rawFilePath.isEmpty() || !rawFile.exists()) {
            binding.progressBar.visibility = View.GONE
            binding.statusText.text = "No saved recording found to analyze"
            return
        }

        segmenter = TaalCardiacSegmentation(requireContext().applicationContext)

        viewLifecycleOwner.lifecycleScope.launch {
            val (audio, sampleRate) = withContext(Dispatchers.Default) { readWavAsFloatArray(rawFile) }
            val outcome = segmenter?.segmentRawWav(rawFile, verboseLogging = true)
            if (_binding == null || outcome == null) return@launch
            showResult(outcome, audio, sampleRate)
        }
    }

    private fun showResult(outcome: SegmentationOutcome, audio: FloatArray, sampleRate: Int) {
        binding.progressBar.visibility = View.GONE
        binding.statusText.visibility = View.GONE

        currentOutcome = outcome
        currentAudio = audio
        currentSampleRate = sampleRate

        val result = when (outcome) {
            is SegmentationOutcome.Ok -> outcome.result
            is SegmentationOutcome.TooWeak -> outcome.result
            SegmentationOutcome.NoHeartSounds, SegmentationOutcome.Unavailable -> null
        }

        showResultStatus(outcome)

        if (result != null) {
            resultDurationSec = result.durationSec
            currentWindowSizeSec = resultDurationSec
            currentWindowStart = 0.0

            // Pass the same audio that was fed to segmentation — the overlay is aligned by time,
            // so a different array would misplace the bands while still looking plausible.
            binding.pcgChart.setRecording(audio, sampleRate, result)
            binding.chartCard.visibility = View.VISIBLE

            binding.zoomFullButton.setOnClickListener { setZoom(resultDurationSec, binding.zoomFullButton) }
            binding.zoom10Button.setOnClickListener { setZoom(10.0, binding.zoom10Button) }
            binding.zoom5Button.setOnClickListener { setZoom(5.0, binding.zoom5Button) }
            markZoomSelected(binding.zoomFullButton) // "Full" is the default view on load

            // Share with SegmentationFullScreenFragment via the activity-scoped ViewModel, so
            // rotating into landscape there doesn't need to re-run inference.
            sharedViewModel.audio = audio
            sharedViewModel.sampleRate = sampleRate
            sharedViewModel.outcome = outcome

            binding.fullScreenButton.visibility = View.VISIBLE
            binding.fullScreenButton.setOnClickListener {
                findNavController().navigate(R.id.action_segmentationReport_to_segmentationFullScreen)
            }

            binding.statGrid.visibility = View.VISIBLE
            binding.heartRateValue.text = result.heartRateBpm?.let { "%.0f bpm".format(it) } ?: "—"
            binding.cyclesValue.text = result.numCycles.toString()
            binding.durationValue.text = "%.1fs".format(result.durationSec)
            binding.systolicValue.text = result.systolicIntervalsMs.takeIf { it.isNotEmpty() }
                ?.let { "%.0f ms".format(it.average()) } ?: "—"

            // Nothing to plot for NoHeartSounds/Unavailable, so the PDF (which is the chart plus
            // this same data) is only offered when there's an actual result.
            // Hidden per request (2026-09-02) — kept wired (listener still set), not removed.
            binding.downloadButton.visibility = View.GONE
            binding.downloadButton.setOnClickListener { savePdfToDownloads() }
        }
    }

    /** Zoom re-centres on whatever is currently in view rather than jumping back to 0. */
    private fun setZoom(sizeSec: Double, selectedButton: Button) {
        if (resultDurationSec <= 0) return
        val clampedSize = sizeSec.coerceIn(1.0, resultDurationSec)
        val center = currentWindowStart + currentWindowSizeSec / 2.0
        val maxStart = (resultDurationSec - clampedSize).coerceAtLeast(0.0)
        val newStart = (center - clampedSize / 2.0).coerceIn(0.0, maxStart)
        currentWindowSizeSec = clampedSize
        currentWindowStart = newStart
        binding.pcgChart.setWindowSeconds(newStart, newStart + clampedSize)
        markZoomSelected(selectedButton)
    }

    /** Highlights whichever zoom level is active, matching the app's existing chip selection look. */
    private fun markZoomSelected(selectedButton: Button) {
        val ctx = requireContext()
        for (button in listOf(binding.zoomFullButton, binding.zoom10Button, binding.zoom5Button)) {
            val isSelected = button === selectedButton
            button.setBackgroundResource(
                if (isSelected) R.drawable.bg_chip_selected else android.R.color.transparent
            )
            button.setTextColor(
                ContextCompat.getColor(ctx, if (isSelected) R.color.white else R.color.text_secondary)
            )
        }
    }

    private fun showResultStatus(outcome: SegmentationOutcome) {
        binding.resultStatusRow.visibility = View.VISIBLE
        val ctx = requireContext()

        when (outcome) {
            is SegmentationOutcome.Ok -> {
                binding.resultStatusIcon.setImageResource(R.drawable.ic_check_circle)
                binding.resultStatusIcon.imageTintList =
                    ContextCompat.getColorStateList(ctx, R.color.success_green)
                binding.resultStatusHeadline.text = "Trustworthy segmentation"
                binding.resultStatusHeadline.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                binding.resultStatusSubtext.visibility = View.GONE
                // Hidden per request (2026-09-02) — success-state row only; the Low
                // confidence / No heart sounds / Unavailable rows below stay visible
                // since those carry actionable info. Kept wired, not removed.
                binding.resultStatusRow.visibility = View.GONE
            }
            is SegmentationOutcome.TooWeak -> {
                binding.resultStatusIcon.setImageResource(R.drawable.ic_info)
                binding.resultStatusIcon.imageTintList =
                    ContextCompat.getColorStateList(ctx, R.color.warning_orange)
                binding.resultStatusHeadline.text = "Low confidence"
                binding.resultStatusHeadline.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                binding.resultStatusSubtext.text =
                    "Few complete cycles relative to duration — consider retaking with better placement."
                binding.resultStatusSubtext.visibility = View.VISIBLE
            }
            SegmentationOutcome.NoHeartSounds -> {
                binding.resultStatusIcon.setImageResource(R.drawable.ic_info)
                binding.resultStatusIcon.imageTintList =
                    ContextCompat.getColorStateList(ctx, R.color.text_secondary)
                binding.resultStatusHeadline.text = "No heart sounds detected"
                binding.resultStatusHeadline.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                binding.resultStatusSubtext.text =
                    "Please retake — check stethoscope placement and pre-amp gain."
                binding.resultStatusSubtext.visibility = View.VISIBLE
            }
            SegmentationOutcome.Unavailable -> {
                binding.resultStatusIcon.setImageResource(R.drawable.ic_info)
                binding.resultStatusIcon.imageTintList =
                    ContextCompat.getColorStateList(ctx, R.color.text_secondary)
                binding.resultStatusHeadline.text = "Segmentation unavailable"
                binding.resultStatusHeadline.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                binding.resultStatusSubtext.text =
                    "The segmentation engine could not initialise on this device this session."
                binding.resultStatusSubtext.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Renders the chart (via [writeSegmentationPdf], off the main thread) plus the metadata into
     * a PDF and saves it the same way the old `.txt` export did — only the MIME type and file
     * extension change. API 29+: MediaStore Downloads collection, no permission needed. API 24-28:
     * direct file write into the public Downloads directory. WRITE_EXTERNAL_STORAGE is already
     * declared+requested elsewhere in this app for the same API range, so no extra runtime
     * request is added here — if it's missing the write throws and we just toast.
     */
    private fun savePdfToDownloads() {
        val outcome = currentOutcome ?: return
        val audio = currentAudio ?: return
        val sampleRate = currentSampleRate
        val fileName = "taal_segmentation_report_${System.currentTimeMillis()}.pdf"
        val appContext = requireContext().applicationContext

        binding.downloadButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = requireContext().contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri == null) {
                        toast("Could not save report")
                        return@launch
                    }
                    resolver.openOutputStream(uri)?.use { out ->
                        writeSegmentationPdf(appContext, outcome, audio, sampleRate, out)
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    folder.mkdirs()
                    File(folder, fileName).outputStream().use { out ->
                        writeSegmentationPdf(appContext, outcome, audio, sampleRate, out)
                    }
                }
                toast("Report saved to Downloads")
            } catch (e: Exception) {
                toast("Could not save report: ${e.message}")
            } finally {
                if (_binding != null) binding.downloadButton.isEnabled = true
            }
        }
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    // Always lands on Saved Recordings, not just "up" — this screen is only ever reached
    // via PlayerFragment (see this file's header comment), so a plain navigateUp() would
    // land back on Player instead. Falls back to a direct navigate() if Saved Recordings
    // isn't on the back stack for some reason. Shared by the on-screen back button and the
    // device/gesture back callback so both behave identically.
    private fun goToSavedRecordings() {
        val nav = findNavController()
        if (!nav.popBackStack(R.id.savedRecordingsFragment, false)) {
            nav.navigate(R.id.savedRecordingsFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        segmenter?.close()
        segmenter = null
        _binding = null
    }
}

/**
 * Independent of TaalCardiacSegmentation's own (private) WAV parsing — this is only for the
 * chart, mirrors the same simple 44-byte-header read PlayerFragment already uses elsewhere in
 * this app. Reads the true sample rate from the header rather than assuming 44,100 Hz.
 */
private fun readWavAsFloatArray(file: File): Pair<FloatArray, Int> {
    val bytes = file.readBytes()
    val sampleRate = if (bytes.size >= 28) {
        ((bytes[24].toInt() and 0xff) or
            ((bytes[25].toInt() and 0xff) shl 8) or
            ((bytes[26].toInt() and 0xff) shl 16) or
            ((bytes[27].toInt() and 0xff) shl 24)).let { if (it > 0) it else 44100 }
    } else 44100

    val dataSize = (bytes.size - 44).coerceAtLeast(0)
    val totalSamples = dataSize / 2
    val audio = FloatArray(totalSamples)
    for (i in 0 until totalSamples) {
        val bytePos = 44 + i * 2
        val low = bytes[bytePos].toInt() and 0xFF
        val high = bytes[bytePos + 1].toInt() shl 8
        audio[i] = (high or low).toShort().toFloat() / 32768f
    }
    return audio to sampleRate
}
