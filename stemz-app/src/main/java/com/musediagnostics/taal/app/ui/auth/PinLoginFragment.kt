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
import com.musediagnostics.taal.app.databinding.FragmentPinLoginBinding

class PinLoginFragment : Fragment() {

    private var _binding: FragmentPinLoginBinding? = null
    private val binding get() = _binding!!

    private lateinit var pinDots: List<View>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPinLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pinDots = listOf(binding.pinDot1, binding.pinDot2, binding.pinDot3, binding.pinDot4)

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

                // Auto-verify when 4 digits entered
                if (length == 4) {
                    validatePin(s.toString())
                }
            }
        })

        // Auto-show keyboard
        binding.pinInput.requestFocus()
        binding.pinInput.post { showKeyboard() }
    }

    private fun validatePin(enteredPin: String) {
        val prefs = requireContext().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
        val savedPin = prefs.getString("user_pin", null)

        if (savedPin == null || enteredPin != savedPin) {
            Toast.makeText(requireContext(), "Incorrect PIN. Please try again.", Toast.LENGTH_SHORT).show()
            binding.pinInput.text?.clear()
            updateDots(0)
            return
        }

        // PIN correct - mark as logged in
        prefs.edit().putBoolean("is_logged_in", true).apply()
        Toast.makeText(requireContext(), "Welcome back!", Toast.LENGTH_SHORT).show()
        findNavController().navigate(R.id.action_pinLogin_to_recording)
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
