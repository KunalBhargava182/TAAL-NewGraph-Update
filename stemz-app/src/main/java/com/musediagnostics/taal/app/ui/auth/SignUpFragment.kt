package com.musediagnostics.taal.app.ui.auth

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentSignUpBinding

class SignUpFragment : Fragment() {

    private var _binding: FragmentSignUpBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignUpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back button
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Sign in link -> go back to Sign In
        binding.signInLink.setOnClickListener {
            findNavController().navigateUp()
        }

        // Sign Up button
        binding.signUpButton.setOnClickListener {
            validateAndSignUp()
        }
    }

    private fun validateAndSignUp() {
        val name = binding.nameInput.text?.toString()?.trim() ?: ""
        val email = binding.emailInput.text?.toString()?.trim() ?: ""
        val phone = binding.phoneInput.text?.toString()?.trim() ?: ""

        when {
            name.isEmpty() -> {
                Toast.makeText(requireContext(), "Name is required", Toast.LENGTH_SHORT).show()
                binding.nameInput.requestFocus()
                return
            }
            email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                Toast.makeText(requireContext(), "Valid email is required", Toast.LENGTH_SHORT).show()
                binding.emailInput.requestFocus()
                return
            }
            phone.isEmpty() -> {
                Toast.makeText(requireContext(), "Phone number is required", Toast.LENGTH_SHORT).show()
                binding.phoneInput.requestFocus()
                return
            }
        }

        // Save user data to SharedPreferences
        val prefs = requireContext().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("user_name", name)
            .putString("user_email", email)
            .putString("user_phone", phone)
            .apply()

        Toast.makeText(requireContext(), "Account created successfully!", Toast.LENGTH_SHORT).show()

        // Navigate to Set PIN (Figma flow: Sign Up -> Set PIN)
        findNavController().navigate(R.id.action_signUp_to_setPin)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
