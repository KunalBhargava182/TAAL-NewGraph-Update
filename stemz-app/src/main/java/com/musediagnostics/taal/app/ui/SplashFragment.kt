package com.musediagnostics.taal.app.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentSplashBinding

class SplashFragment : Fragment() {

    private var _binding: FragmentSplashBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSplashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navigate after 2-second delay
        Handler(Looper.getMainLooper()).postDelayed({
            if (_binding == null) return@postDelayed

            val prefs = requireContext().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
            val isLoggedIn = prefs.getBoolean("is_logged_in", false)

            val pinSet = prefs.getBoolean("pin_set", false)

            if (isLoggedIn) {
                findNavController().navigate(R.id.action_splash_to_recording)
            } else if (pinSet) {
                // PIN was set previously, go to PIN login
                findNavController().navigate(R.id.action_splash_to_pinLogin)
            } else {
                findNavController().navigate(R.id.action_splash_to_signIn)
            }
        }, 2000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
