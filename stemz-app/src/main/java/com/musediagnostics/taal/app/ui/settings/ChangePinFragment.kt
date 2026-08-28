package com.musediagnostics.taal.app.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.databinding.FragmentChangePinBinding

class ChangePinFragment : Fragment() {

    private var _binding: FragmentChangePinBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChangePinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.savePinButton.setOnClickListener {
            savePin()
        }
    }

    private fun savePin() {
        val currentPin = binding.currentPinInput.text?.toString() ?: ""
        val newPin = binding.newPinInput.text?.toString() ?: ""
        val confirmPin = binding.confirmPinInput.text?.toString() ?: ""

        val prefs = requireContext().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
        val savedPin = prefs.getString("user_pin", "0000") ?: "0000"

        when {
            currentPin != savedPin -> {
                binding.currentPinLayout.error = "Incorrect current PIN"
                return
            }
            newPin.length != 4 -> {
                binding.newPinLayout.error = "PIN must be 4 digits"
                return
            }
            newPin != confirmPin -> {
                binding.confirmPinLayout.error = "PINs do not match"
                return
            }
        }

        // Clear errors
        binding.currentPinLayout.error = null
        binding.newPinLayout.error = null
        binding.confirmPinLayout.error = null

        // Save new PIN
        prefs.edit().putString("user_pin", newPin).apply()
        Toast.makeText(requireContext(), "PIN changed successfully", Toast.LENGTH_SHORT).show()
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
