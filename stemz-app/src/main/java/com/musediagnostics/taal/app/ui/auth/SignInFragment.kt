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
import com.musediagnostics.taal.app.databinding.FragmentSignInBinding

class SignInFragment : Fragment() {

    private var _binding: FragmentSignInBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignInBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check if already logged in
        val prefs = requireActivity().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_logged_in", false)) {
            findNavController().navigate(R.id.action_signIn_to_recording)
            return
        }

        binding.googleButton.setOnClickListener {
            Toast.makeText(requireContext(), "Google Sign-In coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.otpButton.setOnClickListener {
            findNavController().navigate(R.id.action_signIn_to_enterMobile)
        }

        binding.signUpLink.setOnClickListener {
            findNavController().navigate(R.id.action_signIn_to_signUp)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
