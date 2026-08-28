package com.musediagnostics.taal.app.ui.auth

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
import com.musediagnostics.taal.app.databinding.FragmentFingerprintConfirmBinding

class FingerprintConfirmFragment : Fragment() {

    private var _binding: FragmentFingerprintConfirmBinding? = null
    private val binding get() = _binding!!

    private val autoNavigateHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFingerprintConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Save fingerprint enabled to SharedPreferences
        val prefs = requireContext().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("fingerprint_enabled", true).apply()

        // Auto-navigate to recording after 2 seconds
        autoNavigateHandler.postDelayed({
            if (isAdded && _binding != null) {
                findNavController().navigate(R.id.action_fingerprintConfirm_to_recording)
            }
        }, 2000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        autoNavigateHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}
