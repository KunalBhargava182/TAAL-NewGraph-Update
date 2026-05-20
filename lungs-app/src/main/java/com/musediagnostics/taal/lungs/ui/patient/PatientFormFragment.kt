package com.musediagnostics.taal.lungs.ui.patient

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.lungs.R
import com.musediagnostics.taal.lungs.data.db.LungsDatabase
import com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity
import com.musediagnostics.taal.lungs.data.db.entity.LungSessionEntity
import com.musediagnostics.taal.lungs.data.repository.LungPatientRepository
import com.musediagnostics.taal.lungs.data.repository.LungSessionRepository
import com.musediagnostics.taal.lungs.databinding.FragmentPatientFormBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PatientFormFragment : Fragment() {

    private var _binding: FragmentPatientFormBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PatientFormViewModel by viewModels()

    // -1L = new patient mode; any other value = edit mode
    private var editPatientId: Long = -1L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPatientFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editPatientId = arguments?.getLong("patientId") ?: -1L
        val isEditMode = editPatientId != -1L

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        val db = LungsDatabase.getInstance(requireContext())
        val repo = LungPatientRepository(db.lungPatientDao())
        val sessionRepo = LungSessionRepository(db.lungSessionDao())

        if (isEditMode) {
            // ── Edit mode ─────────────────────────────────────────────────────
            binding.btnNext.text = getString(R.string.btn_save_changes)

            viewLifecycleOwner.lifecycleScope.launch {
                val patient = withContext(Dispatchers.IO) { repo.getById(editPatientId) }
                if (patient == null) {
                    toast("Patient not found")
                    findNavController().navigateUp()
                    return@launch
                }
                populateForm(patient)
            }
        } else {
            // ── New patient mode ───────────────────────────────────────────────
            viewLifecycleOwner.lifecycleScope.launch {
                val nextSeq = withContext(Dispatchers.IO) { repo.getNextSequenceNumber() }
                binding.patientIdValue.text = "%02d".format(nextSeq)
            }
        }

        // Height watcher → update ViewModel
        binding.etHeight.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.setHeight(s.toString().toFloatOrNull())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Weight watcher → update ViewModel
        binding.etWeight.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.setWeight(s.toString().toFloatOrNull())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Observe BMI
        viewModel.bmi.observe(viewLifecycleOwner) { bmi ->
            binding.tvBmi.text = if (bmi != null) "%.1f".format(bmi) else "--"
        }

        // Inch → cm converter toggle
        binding.btnInchConverter.setOnClickListener {
            val isVisible = binding.inchConverterRow.visibility == View.VISIBLE
            binding.inchConverterRow.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        // Live inch → cm conversion
        binding.etInches.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val inches = s.toString().toFloatOrNull()
                if (inches != null) {
                    val cm = inches * 2.54f
                    binding.tvConvertedCm.text = "%.1f cm".format(cm)
                    binding.etChest.setText("%.1f".format(cm))
                } else {
                    binding.tvConvertedCm.text = "-- cm"
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnNext.setOnClickListener {
            if (isEditMode) saveEdit(repo) else validateAndProceed(repo, sessionRepo)
        }
    }

    /** Pre-fill all fields with the existing patient's data. */
    private fun populateForm(patient: LungPatientEntity) {
        binding.patientIdValue.text = "%02d".format(patient.sequenceNumber)

        when (patient.sex) {
            "Male"   -> binding.chipGroupSex.check(R.id.chipMale)
            "Female" -> binding.chipGroupSex.check(R.id.chipFemale)
            "Other"  -> binding.chipGroupSex.check(R.id.chipOther)
        }
        binding.etAge.setText(patient.age.toString())
        binding.etChest.setText("%.1f".format(patient.chestCircumferenceCm))
        binding.etHeight.setText("%.1f".format(patient.heightCm))
        binding.etWeight.setText("%.1f".format(patient.weightKg))

        // Seed ViewModel so BMI MediatorLiveData fires immediately
        viewModel.setHeight(patient.heightCm)
        viewModel.setWeight(patient.weightKg)
    }

    /** Validate → UPDATE existing patient → pop back to session screen. */
    private fun saveEdit(repo: LungPatientRepository) {
        val sex = when (binding.chipGroupSex.checkedChipId) {
            R.id.chipMale   -> "Male"
            R.id.chipFemale -> "Female"
            R.id.chipOther  -> "Other"
            else -> null
        }
        val age    = binding.etAge.text.toString().toIntOrNull()
        val chest  = binding.etChest.text.toString().toFloatOrNull()
        val height = binding.etHeight.text.toString().toFloatOrNull()
        val weight = binding.etWeight.text.toString().toFloatOrNull()

        if (sex == null)               { toast("Please select sex"); return }
        if (age == null || age <= 0)   { toast("Please enter a valid age"); return }
        if (chest == null || chest <= 0)  { toast("Please enter chest circumference"); return }
        if (height == null || height <= 0) { toast("Please enter height"); return }
        if (weight == null || weight <= 0) { toast("Please enter weight"); return }

        val bmi = weight / ((height / 100f) * (height / 100f))

        binding.btnNext.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val existing = withContext(Dispatchers.IO) { repo.getById(editPatientId) }
                if (!isAdded || _binding == null) return@launch
                if (existing == null) {
                    toast("Patient not found")
                    return@launch
                }
                val updated = existing.copy(
                    sex = sex,
                    age = age,
                    chestCircumferenceCm = chest,
                    heightCm = height,
                    weightKg = weight,
                    bmi = bmi
                )
                withContext(Dispatchers.IO) { repo.update(updated) }
                if (!isAdded || _binding == null) return@launch
                findNavController().navigateUp()
            } finally {
                if (isAdded) binding.btnNext.isEnabled = true
            }
        }
    }

    /** Validate → INSERT new patient + Session 1 → navigate to placement. */
    private fun validateAndProceed(repo: LungPatientRepository, sessionRepo: LungSessionRepository) {
        val sex = when (binding.chipGroupSex.checkedChipId) {
            R.id.chipMale   -> "Male"
            R.id.chipFemale -> "Female"
            R.id.chipOther  -> "Other"
            else -> null
        }
        val age    = binding.etAge.text.toString().toIntOrNull()
        val chest  = binding.etChest.text.toString().toFloatOrNull()
        val height = binding.etHeight.text.toString().toFloatOrNull()
        val weight = binding.etWeight.text.toString().toFloatOrNull()

        if (sex == null)               { toast("Please select sex"); return }
        if (age == null || age <= 0)   { toast("Please enter a valid age"); return }
        if (chest == null || chest <= 0)  { toast("Please enter chest circumference"); return }
        if (height == null || height <= 0) { toast("Please enter height"); return }
        if (weight == null || weight <= 0) { toast("Please enter weight"); return }

        val bmi = weight / ((height / 100f) * (height / 100f))

        binding.btnNext.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val seqNum = withContext(Dispatchers.IO) { repo.getNextSequenceNumber() }
                if (!isAdded || _binding == null) return@launch
                val patient = LungPatientEntity(
                    sequenceNumber = seqNum,
                    sex = sex,
                    age = age,
                    chestCircumferenceCm = chest,
                    heightCm = height,
                    weightKg = weight,
                    bmi = bmi
                )
                val newId = withContext(Dispatchers.IO) { repo.insert(patient) }
                if (!isAdded || _binding == null) return@launch
                val sessionId = withContext(Dispatchers.IO) {
                    sessionRepo.insert(LungSessionEntity(patientId = newId, sessionNumber = 1))
                }
                if (!isAdded || _binding == null) return@launch
                findNavController().navigate(
                    R.id.action_patient_form_to_placement,
                    Bundle().apply {
                        putLong("patientId", newId)
                        putInt("patientSeqNum", seqNum)
                        putLong("sessionId", sessionId)
                        putInt("sessionNumber", 1)
                    },
                    NavOptions.Builder()
                        .setPopUpTo(R.id.homeFragment, false)
                        .build()
                )
            } finally {
                if (isAdded) binding.btnNext.isEnabled = true
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
