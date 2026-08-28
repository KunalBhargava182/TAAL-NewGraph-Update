package com.musediagnostics.taal.app.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.dsp.AudioFilterEngine
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentEqualizerBinding

class EqualizerFragment : Fragment() {

    private var _binding: FragmentEqualizerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    data class EqPoint(
        var frequencyHz: Float,
        var gainDb: Float
    )

    private val eqPoints = mutableListOf(
        EqPoint(20f, 0f),
        EqPoint(50f, 0f),
        EqPoint(100f, 0f),
        EqPoint(250f, 0f),
        EqPoint(1000f, 0f)
    )

    // 3 custom preset slots
    private val customPresets = arrayOf(
        floatArrayOf(0f, 0f, 0f, 0f, 0f),
        floatArrayOf(0f, 0f, 0f, 0f, 0f),
        floatArrayOf(0f, 0f, 0f, 0f, 0f)
    )

    private var eqCurveView: EqCurveView? = null
    private var selectedFilterIndex = -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEqualizerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""

        setupEqCurve()
        setupNavBar()
        setupFilterRow()
        setupPresetsPopup()
        setupPlayback(filePath)
    }

    private fun setupEqCurve() {
        val curveArea = binding.eqCurveArea
        val parent = curveArea.parent as ViewGroup

        // EqCurveView includes built-in spectrum visualization overlay
        eqCurveView = EqCurveView(requireContext(), eqPoints).also { eqView ->
            eqView.id = curveArea.id
            eqView.layoutParams = curveArea.layoutParams
            eqView.background = curveArea.background
        }

        val index = parent.indexOfChild(curveArea)
        parent.removeView(curveArea)
        parent.addView(eqCurveView, index)
    }

    /** Apply current EQ settings to the TaalPlayer's real DSP engine */
    private fun applyEqToPlayer() {
        player?.let { p ->
            // Map the 5 UI EQ points (20, 50, 100, 250, 1000 Hz) to SDK bands (20, 50, 100, 200, 600 Hz)
            val eqState = AudioFilterEngine.GraphicEQState(
                band20Hz = eqPoints[0].gainDb,           // 20Hz → 20Hz band
                band50Hz = eqPoints[1].gainDb,            // 50Hz → 50Hz band
                band100Hz = eqPoints[2].gainDb,           // 100Hz → 100Hz band
                band200Hz = eqPoints[3].gainDb,           // 250Hz → 200Hz band (closest)
                band600Hz = eqPoints[4].gainDb             // 1000Hz → 600Hz band (closest)
            )
            p.setGraphicEQ(eqState)
        }
    }

    private fun setupNavBar() {
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.resetButton.setOnClickListener {
            eqPoints.forEach { it.gainDb = 0f }
            eqCurveView?.invalidate()
            applyEqToPlayer()
            Toast.makeText(requireContext(), "EQ Reset", Toast.LENGTH_SHORT).show()
        }

        binding.saveButton.setOnClickListener {
            Toast.makeText(requireContext(), "EQ Preset Saved", Toast.LENGTH_SHORT).show()
        }

        binding.syncButton.setOnClickListener {
            Toast.makeText(requireContext(), "Syncing...", Toast.LENGTH_SHORT).show()
        }

        binding.deviceButton.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.usb_not_connected), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFilterRow() {
        val filterButtons = listOf(
            binding.filterHeart,
            binding.filterLungs,
            binding.filterBowel,
            binding.filterPregnancy,
            binding.filterAccessibility
        )

        filterButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                val wasSelected = button.isSelected
                filterButtons.forEach { it.isSelected = false }
                if (!wasSelected) {
                    button.isSelected = true
                    selectedFilterIndex = index
                } else {
                    selectedFilterIndex = -1
                }
            }
        }
    }

    private fun setupPresetsPopup() {
        binding.presetsButton.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.menu_eq_presets, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                val presetIndex = when (item.itemId) {
                    R.id.preset_custom_1 -> 0
                    R.id.preset_custom_2 -> 1
                    R.id.preset_custom_3 -> 2
                    else -> -1
                }
                if (presetIndex >= 0) {
                    loadPreset(presetIndex)
                    true
                } else false
            }
            popup.show()
        }
    }

    private fun loadPreset(index: Int) {
        val preset = customPresets[index]
        for (i in eqPoints.indices) {
            eqPoints[i].gainDb = preset[i]
        }
        eqCurveView?.invalidate()
        applyEqToPlayer()
        Toast.makeText(requireContext(), "Loaded Custom ${index + 1}", Toast.LENGTH_SHORT).show()
    }

    private fun setupPlayback(filePath: String) {
        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }
    }

    private fun togglePlayback(filePath: String) {
        if (isPlaying) {
            player?.stop()
            isPlaying = false
            binding.playText.text = getString(R.string.play_recording)
            binding.playButton.setImageResource(R.drawable.ic_play)
        } else {
            // Always release and recreate TaalPlayer for each play to avoid AudioTrack state issues
            try { player?.release() } catch (_: Exception) {}
            player = null

            try {
                player = TaalPlayer(requireContext()).apply {
                    setDataSource(filePath)
                    onPlaybackProgress = { timestamp, data ->
                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                val secs = timestamp.toInt()
                                binding.timerText.text = String.format(
                                    "%02d:%02d:%02d",
                                    secs / 3600, (secs % 3600) / 60, secs % 60
                                )
                                eqCurveView?.updateSpectrum(data)
                            }
                        }
                    }
                    onPlaybackComplete = {
                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                isPlaying = false
                                binding.playText.text = getString(R.string.play_recording)
                                binding.playButton.setImageResource(R.drawable.ic_play)
                            }
                        }
                    }
                }
            } catch (e: InvalidFileNameException) {
                Toast.makeText(
                    requireContext(),
                    "Cannot open recording: file not found or unsupported format",
                    Toast.LENGTH_LONG
                ).show()
                return
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error loading recording: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Apply current EQ settings before playing
            applyEqToPlayer()

            try {
                player?.prepare()
                player?.start()
                isPlaying = true
                binding.playText.text = getString(R.string.stop_recording)
                binding.playButton.setImageResource(R.drawable.ic_stop)
            } catch (e: Exception) {
                isPlaying = false
                try { player?.release() } catch (_: Exception) {}
                player = null
                Toast.makeText(
                    requireContext(),
                    "Playback error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            player?.onPlaybackProgress = null
            player?.onPlaybackComplete = null
            player?.stop()
        } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        eqCurveView = null
        _binding = null
    }

    /**
     * Custom View: Parametric EQ curve with draggable control points
     * AND built-in real-time spectrum visualization overlay.
     * Spectrum is rendered INSIDE the EQ area, behind the curve.
     */
    inner class EqCurveView(
        context: Context,
        private val points: MutableList<EqPoint>
    ) : View(context) {

        private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2ABFBF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#442ABFBF")
            style = Paint.Style.FILL
        }

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2A2A3E")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2ABFBF")
            style = Paint.Style.FILL
        }

        private val pointBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3A3A50")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
        }

        private val gainLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7777AA")
            textSize = 20f
            textAlign = Paint.Align.LEFT
        }

        private val freqLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7777AA")
            textSize = 18f
            textAlign = Paint.Align.CENTER
        }

        // Spectrum visualization paints (rendered INSIDE the EQ area)
        private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1AFFFFFF")
            style = Paint.Style.FILL
        }

        private val spectrumLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#50FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        private val curvePath = Path()
        private val fillPath = Path()
        private val spectrumPath = Path()
        private val spectrumFillPath = Path()
        private var dragPointIndex = -1
        private val pointRadius = 22f
        private val touchRadius = 52f

        private val minFreq = 10f
        private val maxFreq = 2000f
        private val minGain = -12f
        private val maxGain = 12f
        private val padding = 40f
        private val bottomPadding = 28f

        // Spectrum data (updated from playback)
        private var spectrumMagnitudes = FloatArray(0)
        private val numBands = 64

        fun updateSpectrum(audioData: FloatArray) {
            if (audioData.isEmpty()) return

            val fftSize = audioData.size
            val bandMags = FloatArray(numBands)
            val sMinFreq = 10f
            val sMaxFreq = 2000f
            val logMin = Math.log10(sMinFreq.toDouble())
            val logMax = Math.log10(sMaxFreq.toDouble())

            for (band in 0 until numBands) {
                val freqLow = Math.pow(10.0, logMin + (logMax - logMin) * band / numBands).toFloat()
                val freqHigh = Math.pow(10.0, logMin + (logMax - logMin) * (band + 1) / numBands).toFloat()

                val idxLow = ((freqLow / 44100f) * fftSize).toInt().coerceIn(0, fftSize - 1)
                val idxHigh = ((freqHigh / 44100f) * fftSize).toInt().coerceIn(idxLow + 1, fftSize)

                var sum = 0f
                var count = 0
                for (i in idxLow until minOf(idxHigh, fftSize)) {
                    sum += audioData[i] * audioData[i]
                    count++
                }
                if (count > 0) {
                    val rms = Math.sqrt((sum / count).toDouble()).toFloat()
                    val db = if (rms > 0.0001f) (20f * Math.log10(rms.toDouble()).toFloat()) else -60f
                    bandMags[band] = ((db + 60f) / 60f).coerceIn(0f, 1f)
                }
            }

            spectrumMagnitudes = bandMags
            postInvalidate()
        }

        private fun freqToX(freq: Float): Float {
            val logMin = Math.log10(minFreq.toDouble()).toFloat()
            val logMax = Math.log10(maxFreq.toDouble()).toFloat()
            val logFreq = Math.log10(freq.toDouble()).toFloat()
            return padding + (logFreq - logMin) / (logMax - logMin) * (width - 2 * padding)
        }

        private fun xToFreq(x: Float): Float {
            val logMin = Math.log10(minFreq.toDouble()).toFloat()
            val logMax = Math.log10(maxFreq.toDouble()).toFloat()
            val logFreq = logMin + (x - padding) / (width - 2 * padding) * (logMax - logMin)
            return Math.pow(10.0, logFreq.toDouble()).toFloat().coerceIn(minFreq, maxFreq)
        }

        private fun gainToY(gain: Float): Float {
            return padding + (1f - (gain - minGain) / (maxGain - minGain)) * (height - padding - bottomPadding)
        }

        private fun yToGain(y: Float): Float {
            val gain = maxGain - (y - padding) / (height - padding - bottomPadding) * (maxGain - minGain)
            return gain.coerceIn(minGain, maxGain)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width == 0 || height == 0) return

            drawGrid(canvas)
            drawSpectrum(canvas)    // Spectrum INSIDE the EQ area (behind curve)
            drawCurve(canvas)
            drawPoints(canvas)
        }

        private fun drawGrid(canvas: Canvas) {
            val gainSteps = listOf(-12f, -6f, 0f, 6f, 12f)
            for (gain in gainSteps) {
                val y = gainToY(gain)
                if (gain == 0f) {
                    canvas.drawLine(padding, y, width - padding, y, zeroLinePaint)
                } else {
                    canvas.drawLine(padding, y, width - padding, y, gridPaint)
                }
                canvas.drawText("${gain.toInt()}dB", width - padding + 4, y + 6, gainLabelPaint)
            }

            val freqSteps = listOf(10f, 20f, 50f, 100f, 250f, 500f, 1000f, 2000f)
            for (freq in freqSteps) {
                val x = freqToX(freq)
                canvas.drawLine(x, padding, x, height - bottomPadding, gridPaint)
                val label = if (freq >= 1000f) "${(freq / 1000).toInt()}kHz" else "${freq.toInt()}Hz"
                canvas.drawText(label, x, height - 4f, freqLabelPaint)
            }
        }

        /** Draw real-time spectrum as a wave INSIDE the EQ view, behind the curve */
        private fun drawSpectrum(canvas: Canvas) {
            if (spectrumMagnitudes.isEmpty()) return

            spectrumPath.reset()
            spectrumFillPath.reset()

            val chartBottom = height - bottomPadding

            for (i in spectrumMagnitudes.indices) {
                val freq = Math.pow(10.0,
                    Math.log10(10.0) + (Math.log10(2000.0) - Math.log10(10.0)) * (i + 0.5) / numBands
                ).toFloat()

                val x = freqToX(freq.coerceIn(minFreq, maxFreq))
                val magnitude = spectrumMagnitudes[i]
                val y = chartBottom - (magnitude * (chartBottom - padding))

                if (i == 0) {
                    spectrumPath.moveTo(x, y)
                    spectrumFillPath.moveTo(x, chartBottom)
                    spectrumFillPath.lineTo(x, y)
                } else {
                    spectrumPath.lineTo(x, y)
                    spectrumFillPath.lineTo(x, y)
                }
            }

            val lastFreq = Math.pow(10.0,
                Math.log10(10.0) + (Math.log10(2000.0) - Math.log10(10.0)) * (numBands - 0.5) / numBands
            ).toFloat()
            val lastX = freqToX(lastFreq.coerceIn(minFreq, maxFreq))
            spectrumFillPath.lineTo(lastX, chartBottom)
            spectrumFillPath.close()

            canvas.drawPath(spectrumFillPath, spectrumPaint)
            canvas.drawPath(spectrumPath, spectrumLinePaint)
        }

        private fun drawCurve(canvas: Canvas) {
            if (points.isEmpty()) return

            curvePath.reset()
            fillPath.reset()

            val curvePoints = mutableListOf<Pair<Float, Float>>()
            curvePoints.add(Pair(padding, gainToY(points.first().gainDb)))
            for (p in points) {
                curvePoints.add(Pair(freqToX(p.frequencyHz), gainToY(p.gainDb)))
            }
            curvePoints.add(Pair(width - padding, gainToY(points.last().gainDb)))

            if (curvePoints.size >= 2) {
                curvePath.moveTo(curvePoints[0].first, curvePoints[0].second)
                fillPath.moveTo(curvePoints[0].first, curvePoints[0].second)

                for (i in 1 until curvePoints.size) {
                    val prev = curvePoints[i - 1]
                    val curr = curvePoints[i]
                    val midX = (prev.first + curr.first) / 2f
                    curvePath.cubicTo(midX, prev.second, midX, curr.second, curr.first, curr.second)
                    fillPath.cubicTo(midX, prev.second, midX, curr.second, curr.first, curr.second)
                }

                val zeroY = gainToY(0f)
                fillPath.lineTo(width - padding, zeroY)
                fillPath.lineTo(padding, zeroY)
                fillPath.close()

                canvas.drawPath(fillPath, fillPaint)
                canvas.drawPath(curvePath, curvePaint)
            }
        }

        private fun drawPoints(canvas: Canvas) {
            for (p in points) {
                val x = freqToX(p.frequencyHz)
                val y = gainToY(p.gainDb)
                canvas.drawCircle(x, y, pointRadius, pointBorderPaint)
                canvas.drawCircle(x, y, pointRadius - 4f, pointPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragPointIndex = -1
                    var minDist = touchRadius
                    for (i in points.indices) {
                        val px = freqToX(points[i].frequencyHz)
                        val py = gainToY(points[i].gainDb)
                        val dist = Math.sqrt(
                            ((event.x - px) * (event.x - px) +
                                    (event.y - py) * (event.y - py)).toDouble()
                        ).toFloat()
                        if (dist < minDist) {
                            minDist = dist
                            dragPointIndex = i
                        }
                    }
                    if (dragPointIndex >= 0) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    return dragPointIndex >= 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragPointIndex >= 0) {
                        points[dragPointIndex].gainDb = yToGain(event.y)
                        val newFreq = xToFreq(event.x)
                        val lowerBound = if (dragPointIndex > 0)
                            points[dragPointIndex - 1].frequencyHz * 1.2f else minFreq
                        val upperBound = if (dragPointIndex < points.size - 1)
                            points[dragPointIndex + 1].frequencyHz / 1.2f else maxFreq
                        points[dragPointIndex].frequencyHz = newFreq.coerceIn(lowerBound, upperBound)
                        invalidate()
                        // Apply EQ changes in real-time while dragging
                        applyEqToPlayer()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    dragPointIndex = -1
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
