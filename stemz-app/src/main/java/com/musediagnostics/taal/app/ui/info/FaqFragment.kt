package com.musediagnostics.taal.app.ui.info

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentFaqBinding

class FaqFragment : Fragment() {

    private var _binding: FragmentFaqBinding? = null
    private val binding get() = _binding!!

    data class FaqItem(val question: String, val answer: String, var expanded: Boolean = false)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFaqBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        val faqs = listOf(
            FaqItem("What is TAAL?", "TAAL is a digital stethoscope application that allows healthcare professionals to record, analyze, and share auscultation sounds using the TAAL device."),
            FaqItem("How do I connect the TAAL device?", "Connect the TAAL device via USB to your phone. The app will automatically detect it and display a connection indicator in the top bar."),
            FaqItem("How do I record heart sounds?", "Navigate to the Recording screen, select the Heart filter, and tap the record button. The recording will capture and filter the audio in real-time."),
            FaqItem("Can I share recordings?", "Yes, recordings can be shared from the Player screen. Tap the share icon to export the recording as a WAV file."),
            FaqItem("How does the equalizer work?", "The equalizer allows you to adjust frequency bands to enhance specific sounds. Use the preset filters or drag the EQ curve points to customize."),
            FaqItem("Is my data secure?", "All recordings are stored locally on your device. PIN and fingerprint authentication protect access to the app."),
            FaqItem("How do I crop a recording?", "Open a recording in the Player, then tap the crop icon. Drag the start and end handles to select the portion you want to keep."),
            FaqItem("What file format are recordings saved in?", "Recordings are saved as WAV files (16-bit PCM, 44.1kHz, mono) for maximum audio quality.")
        )

        binding.faqRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.faqRecyclerView.adapter = FaqAdapter(faqs)
    }

    inner class FaqAdapter(private val items: List<FaqItem>) :
        RecyclerView.Adapter<FaqAdapter.FaqViewHolder>() {

        inner class FaqViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val question: TextView = itemView.findViewById(R.id.questionText)
            val answer: TextView = itemView.findViewById(R.id.answerText)
            val expandIcon: ImageView = itemView.findViewById(R.id.expandIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FaqViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_faq, parent, false)
            return FaqViewHolder(view)
        }

        override fun onBindViewHolder(holder: FaqViewHolder, position: Int) {
            val item = items[position]
            holder.question.text = item.question
            holder.answer.text = item.answer
            holder.answer.visibility = if (item.expanded) View.VISIBLE else View.GONE
            holder.expandIcon.rotation = if (item.expanded) 180f else 0f

            holder.itemView.setOnClickListener {
                item.expanded = !item.expanded
                notifyItemChanged(position)
            }
        }

        override fun getItemCount() = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
