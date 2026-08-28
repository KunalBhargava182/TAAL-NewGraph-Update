package com.musediagnostics.taal.app.ui.info

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentUserManualBinding

class UserManualFragment : Fragment() {

    private var _binding: FragmentUserManualBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUserManualBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        val sections = listOf(
            "Getting Started" to "Connect the TAAL device to your phone via USB. The app will automatically detect the device. Create an account or log in to begin recording.",
            "Recording" to "Tap the record button to start capturing audio. Select a filter type (Heart, Lungs, Bowel, Pregnancy, or Full Body) to apply real-time filtering. The timer shows the recording duration.",
            "Saving Recordings" to "After recording, you can save the file with a custom name, add patient information, and optionally mark it as an emergency recording.",
            "Playing Recordings" to "Open a saved recording from the library. Use play/pause to control playback. The waveform display shows the audio visually with a progress indicator.",
            "Equalizer" to "Access the equalizer from the player screen. Drag the EQ curve points to adjust frequency response. Use preset filters for Heart, Lungs, or Bowel sounds.",
            "Crop Tool" to "Open the crop tool from the player screen. Drag the start and end handles to select a portion of the recording. Tap the checkmark to save the cropped version.",
            "Recording Library" to "View all saved recordings organized by date. Search by patient name or ID. Sort by date or name. Access edit, rename, and delete options from the overflow menu.",
            "Patient Management" to "Add patient details including name, ID, and contact information. Patient records are linked to their recordings for easy retrieval.",
            "Security" to "Set up a 4-digit PIN for quick login. Enable fingerprint authentication for added security. Change your PIN from the Settings menu."
        )

        sections.forEach { (title, body) ->
            addSection(binding.manualContent, title, body)
        }
    }

    private fun addSection(container: LinearLayout, title: String, body: String) {
        val ctx = requireContext()
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp24 = (24 * resources.displayMetrics.density).toInt()

        val titleView = TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(resources.getColor(R.color.teal_primary, null))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp24, 0, dp8)
        }

        val bodyView = TextView(ctx).apply {
            text = body
            textSize = 13f
            setTextColor(resources.getColor(R.color.text_secondary, null))
            setLineSpacing(4f, 1f)
        }

        container.addView(titleView)
        container.addView(bodyView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
