package com.musediagnostics.taal.visualizer.ui.choose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.visualizer.R
import com.musediagnostics.taal.visualizer.databinding.FragmentChooseRecordingTypeBinding
import com.musediagnostics.taal.visualizer.domain.PointSetType

class ChooseRecordingTypeFragment : Fragment() {

    private var _binding: FragmentChooseRecordingTypeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChooseRecordingTypeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        binding.btnRecordHeart.setOnClickListener { openPointSetHome(PointSetType.HEART) }
        binding.btnRecordLungs.setOnClickListener { openPointSetHome(PointSetType.LUNGS) }
    }

    private fun openPointSetHome(pointSet: PointSetType) {
        findNavController().navigate(
            R.id.action_choose_to_pointSetHome,
            Bundle().apply { putString("pointSet", pointSet.name) }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
