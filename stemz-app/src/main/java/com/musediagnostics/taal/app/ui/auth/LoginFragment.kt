package com.musediagnostics.taal.app.ui.auth

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentLoginBinding

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val countryCodes = listOf(
        "+91 India",
        "+1 USA",
        "+44 UK",
        "+971 UAE",
        "+966 Saudi",
        "+61 Australia",
        "+86 China",
        "+81 Japan"
    )

    private var selectedCountryCode = "+91"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check if already logged in
        val prefs = requireActivity().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_logged_in", false)) {
            findNavController().navigate(R.id.action_login_to_recording)
            return
        }

        // Back button
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Country code picker on flag icon and dropdown icon
        val countryCodeClickListener = View.OnClickListener {
            showCountryCodePicker()
        }
        binding.flagIcon.setOnClickListener(countryCodeClickListener)
        binding.dropdownIcon.setOnClickListener(countryCodeClickListener)

        binding.continueButton.setOnClickListener {
            val phone = binding.phoneInput.text.toString().trim()
            if (phone.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Navigate to OTP screen with full phone number (country code + number)
            val fullPhone = "$selectedCountryCode$phone"
            val bundle = Bundle().apply {
                putString("phoneNumber", fullPhone)
            }
            findNavController().navigate(R.id.action_login_to_otp, bundle)
        }
    }

    private fun showCountryCodePicker() {
        val items = countryCodes.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Select Country Code")
            .setItems(items) { _, which ->
                val selected = countryCodes[which]
                selectedCountryCode = selected.substringBefore(" ")
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
