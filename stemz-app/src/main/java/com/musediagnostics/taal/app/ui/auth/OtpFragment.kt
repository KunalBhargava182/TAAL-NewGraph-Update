package com.musediagnostics.taal.app.ui.auth

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentOtpBinding

class OtpFragment : Fragment() {

    private var _binding: FragmentOtpBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOtpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Auto-focus next digit on input
        setupOtpAutoFocus(binding.otpDigit1, binding.otpDigit2)
        setupOtpAutoFocus(binding.otpDigit2, binding.otpDigit3)
        setupOtpAutoFocus(binding.otpDigit3, binding.otpDigit4)
        setupOtpAutoFocus(binding.otpDigit4, null)

        // Auto-focus back on delete
        setupOtpBackspace(binding.otpDigit2, binding.otpDigit1)
        setupOtpBackspace(binding.otpDigit3, binding.otpDigit2)
        setupOtpBackspace(binding.otpDigit4, binding.otpDigit3)

        binding.verifyButton.setOnClickListener {
            val otp = "${binding.otpDigit1.text}${binding.otpDigit2.text}${binding.otpDigit3.text}${binding.otpDigit4.text}"
            if (otp.length < 4) {
                Toast.makeText(requireContext(), "Please enter 4-digit code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Mock auth: any 4-digit code is accepted
            val prefs = requireActivity().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_logged_in", true).apply()

            Toast.makeText(requireContext(), "Verification successful!", Toast.LENGTH_SHORT).show()

            // Navigate to Set PIN (Figma flow: OTP -> Set PIN)
            findNavController().navigate(R.id.action_otp_to_setPin)
        }

        binding.resendOtp.setOnClickListener {
            Toast.makeText(requireContext(), "OTP resent (mock)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupOtpAutoFocus(current: EditText, next: EditText?) {
        current.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s?.length == 1) {
                    next?.requestFocus()
                }
            }
        })
    }

    private fun setupOtpBackspace(current: EditText, previous: EditText) {
        current.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DEL &&
                event.action == android.view.KeyEvent.ACTION_DOWN &&
                current.text.isEmpty()
            ) {
                previous.requestFocus()
                previous.text.clear()
                true
            } else {
                false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
