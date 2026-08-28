package com.musediagnostics.taal.app.ui.auth

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentPinConfirmedBinding

class PinConfirmedFragment : Fragment() {

    private var _binding: FragmentPinConfirmedBinding? = null
    private val binding get() = _binding!!

    private lateinit var pinDots: List<View>
    private var firstPin: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPinConfirmedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Retrieve the first PIN from arguments
        firstPin = arguments?.getString("firstPin") ?: ""

        pinDots = listOf(binding.pinDot1, binding.pinDot2, binding.pinDot3, binding.pinDot4)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Tap on dots area to show keyboard
        binding.pinDotsRow.setOnClickListener {
            binding.pinInput.requestFocus()
            showKeyboard()
        }

        // Listen to hidden input text changes
        binding.pinInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val length = s?.length ?: 0
                updateDots(length)
            }
        })

        binding.confirmButton.setOnClickListener {
            validateAndConfirmPin()
        }

        // Auto-show keyboard
        binding.pinInput.requestFocus()
        binding.pinInput.post { showKeyboard() }
    }

    private fun validateAndConfirmPin() {
        val enteredPin = binding.pinInput.text.toString()

        if (enteredPin.length < 4) {
            Toast.makeText(requireContext(), "Please enter a 4-digit PIN", Toast.LENGTH_SHORT).show()
            return
        }

        if (enteredPin != firstPin) {
            Toast.makeText(requireContext(), "PINs do not match. Please try again.", Toast.LENGTH_SHORT).show()
            binding.pinInput.text?.clear()
            updateDots(0)
            return
        }

        // PINs match - save to SharedPreferences
        val prefs = requireContext().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("user_pin", enteredPin)
            .putBoolean("pin_set", true)
            .apply()

        // Navigate to fingerprint setup
        findNavController().navigate(R.id.action_pinConfirmed_to_fingerprint)
    }

    private fun updateDots(filledCount: Int) {
        for (i in pinDots.indices) {
            pinDots[i].setBackgroundResource(
                if (i < filledCount) R.drawable.bg_pin_dot_filled
                else R.drawable.bg_pin_dot_empty
            )
        }
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.pinInput, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
