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
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.lungs.R
import com.musediagnostics.taal.lungs.data.db.LungsDatabase
import com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity
import com.musediagnostics.taal.lungs.data.repository.LungPatientRepository
import com.musediagnostics.taal.lungs.databinding.FragmentPatientFormBinding
import kotlinx.coroutines.launch

class PatientFormFragment : Fragment() {

    private var _binding: FragmentPatientFormBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PatientFormViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPatientFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        // Pre-load next patient sequence number
        val db = LungsDatabase.getInstance(requireContext())
        val repo = LungPatientRepository(db.lungPatientDao())
        viewLifecycleOwner.lifecycleScope.launch {
            val nextSeq = repo.getNextSequenceNumber()
            binding.patientIdValue.text = "%02d".format(nextSeq)
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
                    // Auto-fill the chest circumference field
                    binding.etChest.setText("%.1f".format(cm))
                } else {
                    binding.tvConvertedCm.text = "-- cm"
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnNext.setOnClickListener { validateAndProceed(repo) }
    }

    private fun validateAndProceed(repo: LungPatientRepository) {
        val selectedSexId = binding.chipGroupSex.checkedChipId
        val sex = when (selectedSexId) {
            R.id.chipMale -> "Male"
            R.id.chipFemale -> "Female"
            R.id.chipOther -> "Other"
            else -> null
        }
        val age = binding.etAge.text.toString().toIntOrNull()
        val chest = binding.etChest.text.toString().toFloatOrNull()
        val height = binding.etHeight.text.toString().toFloatOrNull()
        val weight = binding.etWeight.text.toString().toFloatOrNull()

        if (sex == null) { toast("Please select sex"); return }
        if (age == null || age <= 0) { toast("Please enter a valid age"); return }
        if (chest == null || chest <= 0) { toast("Please enter chest circumference"); return }
        if (height == null || height <= 0) { toast("Please enter height"); return }
        if (weight == null || weight <= 0) { toast("Please enter weight"); return }

        val bmi = weight / ((height / 100f) * (height / 100f))

        binding.btnNext.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val seqNum = repo.getNextSequenceNumber()
            val patient = LungPatientEntity(
                sequenceNumber = seqNum,
                sex = sex,
                age = age,
                chestCircumferenceCm = chest,
                heightCm = height,
                weightKg = weight,
                bmi = bmi
            )
            val newId = repo.insert(patient)

            findNavController().navigate(
                R.id.action_patient_form_to_placement,
                Bundle().apply {
                    putLong("patientId", newId)
                    putInt("patientSeqNum", seqNum)
                }
            )
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
