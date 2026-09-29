package com.musediagnostics.taal.stemz.uikit.recording

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.musediagnostics.taal.stemz.uikit.R

/** Stethoscope-placement guide shown from the recorder's info ("i") button. */
internal class FilterPlacementDialog : DialogFragment() {

    companion object {
        fun newInstance(filterName: String) = FilterPlacementDialog().apply {
            arguments = Bundle().apply { putString("filterName", filterName) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).also {
            it.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.tsuk_dialog_filter_placement, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filterName = arguments?.getString("filterName") ?: "HEART"
        val images = resolveImages(filterName)

        // Title: e.g. "FULL_BODY" → "Full Body Placement"
        val title = filterName.split("_")
            .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } } + " Placement"
        view.findViewById<TextView>(R.id.placementTitle).text = title

        view.findViewById<ImageButton>(R.id.closeButton).setOnClickListener { dismiss() }

        val pager = view.findViewById<ViewPager2>(R.id.imagePager)
        val dotsContainer = view.findViewById<LinearLayout>(R.id.dotsContainer)
        val noImagesText = view.findViewById<TextView>(R.id.noImagesText)

        if (images.isEmpty()) {
            pager.visibility = View.GONE
            dotsContainer.visibility = View.GONE
            noImagesText.visibility = View.VISIBLE
            return
        }

        pager.adapter = PlacementImageAdapter(images)
        setupDots(dotsContainer, images.size)

        if (images.size > 1) {
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateDots(dotsContainer, position, images.size)
                }
            })
        } else {
            dotsContainer.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    // Explicit list (not a getIdentifier() name lookup): survives the integrating app's
    // resource shrinking and the SDK's tsuk_ resource prefix. Lite/Hard/Custom are all heart
    // recordings, so every filter shows the heart placement.
    @Suppress("UNUSED_PARAMETER")
    private fun resolveImages(filterName: String): List<Int> = listOf(R.drawable.tsuk_placement_heart_1)

    private fun setupDots(container: LinearLayout, count: Int) {
        container.removeAllViews()
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        repeat(count) { i ->
            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp8, dp8).apply {
                    marginStart = dp4
                    marginEnd = dp4
                }
                background = ContextCompat.getDrawable(
                    requireContext(),
                    if (i == 0) R.drawable.tsuk_dot_active else R.drawable.tsuk_dot_inactive
                )
            }
            container.addView(dot)
        }
    }

    private fun updateDots(container: LinearLayout, selected: Int, count: Int) {
        for (i in 0 until count) {
            container.getChildAt(i)?.background = ContextCompat.getDrawable(
                requireContext(),
                if (i == selected) R.drawable.tsuk_dot_active else R.drawable.tsuk_dot_inactive
            )
        }
    }

    // ── Inner adapter ─────────────────────────────────────────────────────────

    private class PlacementImageAdapter(
        private val images: List<Int>
    ) : RecyclerView.Adapter<PlacementImageAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.placementImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.tsuk_item_placement_image, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.imageView.setImageResource(images[position])
        }

        override fun getItemCount() = images.size
    }
}
