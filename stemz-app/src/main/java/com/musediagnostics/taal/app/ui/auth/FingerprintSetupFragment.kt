package com.musediagnostics.taal.app.ui.auth

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentFingerprintSetupBinding

class FingerprintSetupFragment : Fragment() {

    private var _binding: FragmentFingerprintSetupBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFingerprintSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.skipText.setOnClickListener {
            findNavController().navigate(R.id.action_fingerprintSetup_to_recording)
        }

        // Tap fingerprint icons to trigger biometric prompt
        binding.fingerprintLargeIcon.setOnClickListener {
            triggerBiometricPrompt()
        }

        binding.fingerprintBottomIcon.setOnClickListener {
            triggerBiometricPrompt()
        }

        // Auto-trigger biometric prompt after short delay
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAdded && _binding != null) {
                triggerBiometricPrompt()
            }
        }, 500)
    }

    private fun triggerBiometricPrompt() {
        val biometricManager = BiometricManager.from(requireContext())
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                showBiometricPrompt()
            }
            else -> {
                Toast.makeText(requireContext(), "Fingerprint not available on this device", Toast.LENGTH_LONG).show()
                // Navigate forward anyway for devices without biometrics
                findNavController().navigate(R.id.action_fingerprintSetup_to_fingerprintConfirm)
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(requireContext())
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    findNavController().navigate(R.id.action_fingerprintSetup_to_fingerprintConfirm)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(requireContext(), "Authentication failed", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(requireContext(), "Error: $errString", Toast.LENGTH_SHORT).show()
                    }
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Set Fingerprint")
            .setSubtitle("Please place your finger to your phone")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
