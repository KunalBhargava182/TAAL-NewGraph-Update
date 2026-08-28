package com.musediagnostics.taal.app.ui.info

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.databinding.FragmentPrivacyPolicyBinding

class PrivacyPolicyFragment : Fragment() {

    private var _binding: FragmentPrivacyPolicyBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPrivacyPolicyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.privacyText.text = buildString {
            appendLine("Last updated: February 2026")
            appendLine()
            appendLine("Muse Diagnostics (\"we\", \"us\", or \"our\") operates the TAAL digital stethoscope application. This Privacy Policy describes how we collect, use, and protect your personal information.")
            appendLine()
            appendLine("1. Information We Collect")
            appendLine("We collect information you provide directly, including your name, email, phone number, and professional details. Audio recordings are stored locally on your device and are not transmitted to our servers unless you explicitly share them.")
            appendLine()
            appendLine("2. How We Use Your Information")
            appendLine("We use your information to provide and improve the TAAL application, authenticate your identity, and communicate important updates about the service.")
            appendLine()
            appendLine("3. Data Storage & Security")
            appendLine("All patient recordings and medical data are stored locally on your device. We use PIN and biometric authentication to protect access to the application. We do not store medical recordings on cloud servers.")
            appendLine()
            appendLine("4. Data Sharing")
            appendLine("We do not sell or share your personal information with third parties. Recordings are only shared when you explicitly choose to export or share them.")
            appendLine()
            appendLine("5. Your Rights")
            appendLine("You may request access to, correction of, or deletion of your personal data at any time by contacting us at support@musediagnostics.com.")
        }

        binding.termsText.text = buildString {
            appendLine("Last updated: February 2026")
            appendLine()
            appendLine("By using the TAAL application, you agree to the following terms and conditions.")
            appendLine()
            appendLine("1. Intended Use")
            appendLine("TAAL is designed as a digital stethoscope tool for healthcare professionals. It is not intended to replace professional medical diagnosis or treatment.")
            appendLine()
            appendLine("2. User Responsibilities")
            appendLine("You are responsible for maintaining the confidentiality of your PIN and account credentials. You agree to use the application in compliance with all applicable healthcare regulations.")
            appendLine()
            appendLine("3. Medical Disclaimer")
            appendLine("TAAL is an assistive tool and should not be used as the sole basis for medical decisions. Always use professional clinical judgment alongside the application's features.")
            appendLine()
            appendLine("4. Intellectual Property")
            appendLine("The TAAL application, including its design, code, and content, is the property of Muse Diagnostics. You may not reproduce or distribute the application without authorization.")
            appendLine()
            appendLine("5. Limitation of Liability")
            appendLine("Muse Diagnostics shall not be liable for any damages arising from the use or inability to use the application.")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
