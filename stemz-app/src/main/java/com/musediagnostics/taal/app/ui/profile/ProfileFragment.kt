package com.musediagnostics.taal.app.ui.profile

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentProfileBinding
import java.io.File

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    // Photo picker launcher
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveProfilePhoto(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadProfile()
        loadProfilePhoto()
        setupButtons()
        setupDropdowns()
    }

    private fun loadProfile() {
        val prefs = requireContext().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
        binding.nameInput.setText(prefs.getString("profile_name", ""))
        binding.emailInput.setText(prefs.getString("profile_email", ""))
        binding.phoneInput.setText(prefs.getString("profile_phone", ""))
        binding.dobInput.setText(prefs.getString("profile_dob", ""))
        binding.countryInput.setText(prefs.getString("profile_country", ""))
        binding.professionDropdown.setText(prefs.getString("profile_profession", ""), false)
        binding.degreeDropdown.setText(prefs.getString("profile_degree", ""), false)
        binding.specialtyDropdown.setText(prefs.getString("profile_specialty", ""), false)
    }

    private fun loadProfilePhoto() {
        val photoPath = requireContext().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
            .getString("profile_photo_path", null)
        if (photoPath != null) {
            val file = File(photoPath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    binding.profilePhoto.setImageBitmap(bitmap)
                    binding.profilePhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                    binding.profilePhoto.setPadding(0, 0, 0, 0)
                }
            }
        }
    }

    private fun saveProfilePhoto(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri) ?: return
            val photoFile = File(requireContext().filesDir, "profile_photo.jpg")
            photoFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            // Save photo path to prefs
            requireContext().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
                .edit().putString("profile_photo_path", photoFile.absolutePath).apply()

            // Update UI
            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
            if (bitmap != null) {
                binding.profilePhoto.setImageBitmap(bitmap)
                binding.profilePhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                binding.profilePhoto.setPadding(0, 0, 0, 0)
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to save photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Tap on photo or camera overlay to pick photo
        binding.profilePhoto.setOnClickListener {
            photoPickerLauncher.launch("image/*")
        }
        binding.cameraOverlay.setOnClickListener {
            photoPickerLauncher.launch("image/*")
        }

        binding.saveProfileButton.setOnClickListener {
            saveProfile()
        }
    }

    private fun setupDropdowns() {
        val professions = arrayOf("Doctor", "Nurse", "Paramedic", "Specialist", "Resident", "Other")
        binding.professionDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, professions)
        )

        val degrees = arrayOf("MBBS", "MD", "MS", "DNB", "DM", "MCh", "BDS", "MDS", "BAMS", "BHMS", "Other")
        binding.degreeDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, degrees)
        )

        val specialties = arrayOf("Cardiology", "Pulmonology", "Gastroenterology", "Pediatrics", "General Medicine", "Surgery", "Obstetrics", "Emergency Medicine", "Other")
        binding.specialtyDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, specialties)
        )
    }

    private fun saveProfile() {
        val name = binding.nameInput.text?.toString()?.trim() ?: ""
        val email = binding.emailInput.text?.toString()?.trim() ?: ""
        val phone = binding.phoneInput.text?.toString()?.trim() ?: ""
        val dob = binding.dobInput.text?.toString()?.trim() ?: ""
        val country = binding.countryInput.text?.toString()?.trim() ?: ""
        val profession = binding.professionDropdown.text?.toString()?.trim() ?: ""
        val degree = binding.degreeDropdown.text?.toString()?.trim() ?: ""
        val specialty = binding.specialtyDropdown.text?.toString()?.trim() ?: ""

        val prefs = requireContext().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("profile_name", name)
            putString("profile_email", email)
            putString("profile_phone", phone)
            putString("profile_dob", dob)
            putString("profile_country", country)
            putString("profile_profession", profession)
            putString("profile_degree", degree)
            putString("profile_specialty", specialty)
            apply()
        }

        // Update drawer header name and photo immediately
        updateDrawerHeader(name)

        Toast.makeText(requireContext(), "Profile saved", Toast.LENGTH_SHORT).show()
        findNavController().navigateUp()
    }

    /** Update the navigation drawer header with the new profile name and photo */
    private fun updateDrawerHeader(name: String) {
        try {
            val mainActivity = activity as? com.musediagnostics.taal.app.ui.MainActivity ?: return
            val navView = mainActivity.findViewById<com.google.android.material.navigation.NavigationView>(R.id.navView)
            val headerView = navView?.getHeaderView(0) ?: return

            // Update name
            val profileNameView = headerView.findViewById<TextView>(R.id.profileName)
            profileNameView?.text = if (name.isNotBlank()) name else "Doctor"

            // Update photo
            val photoPath = requireContext().getSharedPreferences("taal_prefs", Context.MODE_PRIVATE)
                .getString("profile_photo_path", null)
            if (photoPath != null) {
                val file = File(photoPath)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    val profileImageView = headerView.findViewById<ImageView>(R.id.profileImage)
                    if (bitmap != null && profileImageView != null) {
                        profileImageView.setImageBitmap(bitmap)
                        profileImageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        profileImageView.setPadding(0, 0, 0, 0)
                    }
                }
            }
        } catch (_: Exception) {
            // Don't crash if drawer update fails
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
