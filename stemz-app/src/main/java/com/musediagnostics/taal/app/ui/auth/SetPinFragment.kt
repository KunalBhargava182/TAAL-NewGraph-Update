package com.musediagnostics.taal.app.ui.auth

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentSetPinBinding

class SetPinFragment : Fragment() {

    private var _binding: FragmentSetPinBinding? = null
    private val binding get() = _binding!!

    private lateinit var pinDots: List<View>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetPinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pinDots = listOf(binding.pinDot1, binding.pinDot2, binding.pinDot3, binding.pinDot4)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.skipText.setOnClickListener {
            findNavController().navigate(R.id.action_setPin_to_recording)
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

                if (length == 4) {
                    // Auto-navigate to Confirm PIN with the entered PIN
                    val pin = s.toString()
                    val bundle = bundleOf("firstPin" to pin)
                    findNavController().navigate(R.id.action_setPin_to_pinConfirmed, bundle)
                }
            }
        })

        // Auto-show keyboard
        binding.pinInput.requestFocus()
        binding.pinInput.post { showKeyboard() }
    }

    override fun onResume() {
        super.onResume()
        // Clear input when returning to this screen
        binding.pinInput.text?.clear()
        updateDots(0)
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
