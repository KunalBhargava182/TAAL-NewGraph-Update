package com.musediagnostics.taal.app.ui.calibrated

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.databinding.FragmentDpiCalibrationBinding
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration

/**
 * §4.3 — px-per-mm calibration screen. Draws a horizontal and a vertical bar, each nominally
 * 50mm using the device's OEM-reported DPI. The user measures both with a physical ruler and
 * enters what it actually reads; the ratio (nominal / measured) is stored as a correction
 * factor and applied to both calibrated screens' grids via [DpiCalibration.applyTo].
 */
class DpiCalibrationFragment : Fragment() {

    private var _binding: FragmentDpiCalibrationBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val NOMINAL_MM = 50f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDpiCalibrationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateStatusText()

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        binding.saveCalibrationButton.setOnClickListener {
            val actualX = binding.measuredXInput.text?.toString()?.toFloatOrNull()
            val actualY = binding.measuredYInput.text?.toString()?.toFloatOrNull()

            if (actualX == null || actualX <= 0f || actualY == null || actualY <= 0f) {
                Toast.makeText(
                    requireContext(),
                    "Enter the measured length for both bars (mm, greater than 0).",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val correctionX = NOMINAL_MM / actualX
            val correctionY = NOMINAL_MM / actualY
            DpiCalibration.saveManualCorrection(requireContext(), correctionX, correctionY)
            updateStatusText()
            Toast.makeText(requireContext(), "Calibration saved.", Toast.LENGTH_SHORT).show()
        }

        binding.clearCalibrationButton.setOnClickListener {
            DpiCalibration.clearManualCorrection(requireContext())
            binding.measuredXInput.setText("")
            binding.measuredYInput.setText("")
            updateStatusText()
            Toast.makeText(requireContext(), "Calibration cleared — using default DPI.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatusText() {
        val correction = DpiCalibration.getCorrection(requireContext())
        binding.statusText.text = if (correction.isCalibrated) {
            "Calibrated (${correction.source}) — X correction ×%.4f, Y correction ×%.4f"
                .format(correction.x, correction.y)
        } else {
            "Not yet calibrated — grid uses the device's reported DPI as-is."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
