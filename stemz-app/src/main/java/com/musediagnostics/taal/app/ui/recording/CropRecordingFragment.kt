package com.musediagnostics.taal.app.ui.recording

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentCropRecordingBinding
import com.musediagnostics.taal.app.util.WavCropper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CropRecordingFragment : Fragment() {

    private var _binding: FragmentCropRecordingBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    private var filePath = ""
    private var durationSeconds = 0f
    private var waveformData = FloatArray(0)
    private var cropWaveformView: CropWaveformView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCropRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        filePath = arguments?.getString("filePath") ?: ""
        if (filePath.isEmpty()) {
            Toast.makeText(requireContext(), "No file to crop", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }

        setupNavBar()
        setupPlayback()
        setupConfirmCrop()
        loadWaveform()
    }

    private fun setupNavBar() {
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun loadWaveform() {
        CoroutineScope(Dispatchers.IO).launch {
            durationSeconds = WavCropper.getDurationSeconds(filePath)
            waveformData = WavCropper.getWaveformData(filePath, 800)

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext

                updateTimeLabels(0f, durationSeconds)
                binding.timerText.text = formatTime(durationSeconds)
                setupCropWaveform()
            }
        }
    }

    private fun setupCropWaveform() {
        val area = binding.cropWaveformArea
        val parent = area.parent as ViewGroup

        cropWaveformView = CropWaveformView(
            requireContext(),
            waveformData,
            durationSeconds
        ) { startSec, endSec ->
            updateTimeLabels(startSec, endSec)
        }.also { v ->
            v.id = area.id
            v.layoutParams = area.layoutParams
            v.background = area.background
        }

        val index = parent.indexOfChild(area)
        parent.removeView(area)
        parent.addView(cropWaveformView, index)
    }

    private fun updateTimeLabels(startSec: Float, endSec: Float) {
        binding.cropStartTime.text = formatTimeShort(startSec)
        binding.cropEndTime.text = formatTimeShort(endSec)
    }

    private fun setupPlayback() {
        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) return@setOnClickListener
            togglePlayback()
        }
    }

    private fun togglePlayback() {
        if (isPlaying) {
            player?.stop()
            isPlaying = false
            binding.playText.text = getString(R.string.play_recording)
            binding.playButton.setImageResource(R.drawable.ic_play)
        } else {
            if (player == null) {
                player = TaalPlayer(requireContext()).apply {
                    setDataSource(filePath)
                    onPlaybackProgress = { timestamp, _ ->
                        activity?.runOnUiThread {
                            binding.timerText.text = formatTime(timestamp.toFloat())
                            cropWaveformView?.setPlaybackPosition(
                                timestamp.toFloat() / durationSeconds
                            )
                        }
                    }
                    onPlaybackComplete = {
                        activity?.runOnUiThread {
                            isPlaying = false
                            binding.playText.text = getString(R.string.play_recording)
                            binding.playButton.setImageResource(R.drawable.ic_play)
                        }
                    }
                }
            }
            player?.prepare()
            player?.start()
            isPlaying = true
            binding.playText.text = getString(R.string.stop_recording)
            binding.playButton.setImageResource(R.drawable.ic_stop)
        }
    }

    private fun setupConfirmCrop() {
        binding.confirmCropButton.setOnClickListener {
            val view = cropWaveformView ?: return@setOnClickListener
            val startSec = view.getCropStartSeconds()
            val endSec = view.getCropEndSeconds()

            if (endSec - startSec < 0.5f) {
                Toast.makeText(requireContext(), "Selection too short", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            performCrop(startSec, endSec)
        }
    }

    private fun performCrop(startSec: Float, endSec: Float) {
        CoroutineScope(Dispatchers.IO).launch {
            val outputPath = filePath.replace(".wav", "_cropped.wav")
            val success = WavCropper.cropWav(filePath, outputPath, startSec, endSec)

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                if (success) {
                    Toast.makeText(requireContext(), getString(R.string.crop_saved), Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.crop_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatTime(seconds: Float): String {
        val secs = seconds.toInt()
        return String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
    }

    private fun formatTimeShort(seconds: Float): String {
        val secs = seconds.toInt()
        return String.format("%02d:%02d", secs / 60, secs % 60)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        _binding = null
    }

    /**
     * Custom waveform view with draggable crop start/end handles.
     * Displays full recording waveform and allows user to select a region.
     */
    class CropWaveformView(
        context: Context,
        private val waveformData: FloatArray,
        private val durationSeconds: Float,
        private val onCropChanged: (Float, Float) -> Unit
    ) : View(context) {

        // Crop range as fractions [0..1]
        private var cropStart = 0f
        private var cropEnd = 1f
        private var playbackPosition = -1f

        private val handleWidth = 36f
        private val handleTouchWidth = 72f
        private val padding = 20f

        private var dragging: DragTarget = DragTarget.NONE

        private enum class DragTarget { NONE, START, END }

        // Paints
        private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2ABFBF")
            style = Paint.Style.FILL
        }

        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AA000000")
            style = Paint.Style.FILL
        }

        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2ABFBF")
            style = Paint.Style.FILL
        }

        private val handleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2A2A3E")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E85555")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3A3A50")
            style = Paint.Style.STROKE
            strokeWidth = 1f
            pathEffect = DashPathEffect(floatArrayOf(6f, 3f), 0f)
        }

        fun setPlaybackPosition(fraction: Float) {
            playbackPosition = fraction
            invalidate()
        }

        fun getCropStartSeconds(): Float = cropStart * durationSeconds
        fun getCropEndSeconds(): Float = cropEnd * durationSeconds

        private fun fractionToX(fraction: Float): Float {
            return padding + fraction * (width - 2 * padding)
        }

        private fun xToFraction(x: Float): Float {
            return ((x - padding) / (width - 2 * padding)).coerceIn(0f, 1f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width == 0 || height == 0 || waveformData.isEmpty()) return

            val centerY = height / 2f
            val waveHeight = (height - 2 * padding) / 2f
            val chartLeft = padding
            val chartRight = width - padding
            val chartWidth = chartRight - chartLeft

            // Draw grid
            canvas.drawLine(chartLeft, centerY, chartRight, centerY, zeroLinePaint)
            for (i in 1..4) {
                val y = centerY - waveHeight * i / 4
                canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
                val y2 = centerY + waveHeight * i / 4
                canvas.drawLine(chartLeft, y2, chartRight, y2, gridPaint)
            }

            // Draw waveform bars
            val barWidth = chartWidth / waveformData.size
            for (i in waveformData.indices) {
                val x = chartLeft + i * barWidth
                val amplitude = waveformData[i] * waveHeight
                canvas.drawRect(
                    x, centerY - amplitude,
                    x + barWidth * 0.8f, centerY + amplitude,
                    waveformPaint
                )
            }

            // Dim areas outside crop selection
            val startX = fractionToX(cropStart)
            val endX = fractionToX(cropEnd)

            canvas.drawRect(chartLeft, 0f, startX, height.toFloat(), dimPaint)
            canvas.drawRect(endX, 0f, chartRight, height.toFloat(), dimPaint)

            // Draw crop handles
            drawHandle(canvas, startX, true)
            drawHandle(canvas, endX, false)

            // Draw playhead
            if (playbackPosition in 0f..1f) {
                val px = fractionToX(playbackPosition)
                canvas.drawLine(px, padding, px, height - padding, playheadPaint)
            }
        }

        private fun drawHandle(canvas: Canvas, x: Float, isStart: Boolean) {
            val handleRect = RectF(
                if (isStart) x - handleWidth else x,
                padding,
                if (isStart) x else x + handleWidth,
                height - padding
            )

            // Draw handle background
            canvas.drawRoundRect(handleRect, 8f, 8f, handlePaint)

            // Draw grip lines on handle
            val cx = handleRect.centerX()
            val cy = handleRect.centerY()
            for (offset in listOf(-6f, 0f, 6f)) {
                canvas.drawLine(cx + offset, cy - 12f, cx + offset, cy + 12f, handleLinePaint)
            }

            // Draw vertical line at crop edge
            canvas.drawLine(x, padding, x, height - padding, handlePaint.apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
            })
            handlePaint.style = Paint.Style.FILL
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val startX = fractionToX(cropStart)
                    val endX = fractionToX(cropEnd)

                    dragging = when {
                        Math.abs(event.x - startX) < handleTouchWidth -> DragTarget.START
                        Math.abs(event.x - endX) < handleTouchWidth -> DragTarget.END
                        else -> DragTarget.NONE
                    }

                    if (dragging != DragTarget.NONE) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    return dragging != DragTarget.NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    val fraction = xToFraction(event.x)
                    when (dragging) {
                        DragTarget.START -> {
                            cropStart = fraction.coerceAtMost(cropEnd - 0.02f)
                            onCropChanged(getCropStartSeconds(), getCropEndSeconds())
                            invalidate()
                            return true
                        }
                        DragTarget.END -> {
                            cropEnd = fraction.coerceAtLeast(cropStart + 0.02f)
                            onCropChanged(getCropStartSeconds(), getCropEndSeconds())
                            invalidate()
                            return true
                        }
                        DragTarget.NONE -> {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    dragging = DragTarget.NONE
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
