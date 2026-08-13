package com.musediagnostics.taal.visualizer.ui.home

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.uikit.TaalPlayerActivity
import com.musediagnostics.taal.uikit.TaalRecorderActivity
import com.musediagnostics.taal.visualizer.R
import com.musediagnostics.taal.visualizer.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val recorderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val filePath = result.data?.getStringExtra(TaalRecorderActivity.RESULT_FILE_PATH)
            if (!filePath.isNullOrEmpty()) {
                startActivity(TaalPlayerActivity.getIntent(requireContext(), filePath))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBasicTaal.setOnClickListener {
            recorderLauncher.launch(
                TaalRecorderActivity.getIntent(
                    context = requireContext(),
                    preFilter = "HEART",
                    preAmplification = 5,
                    recordingTimeSeconds = 300
                )
            )
        }

        binding.btnVisualizerTaal.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_choose)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
