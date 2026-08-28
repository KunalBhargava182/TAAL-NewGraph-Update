package com.musediagnostics.taal.app.dsp

import kotlin.math.*

/**
 * Heart sound BPM calculator using autocorrelation on PCG data.
 * Processes audio buffers to estimate heart rate from S1/S2 heart sounds.
 *
 * All heavy computation is designed to run on a background thread.
 * The caller should invoke processAudioBlock on the UI thread (fast buffer copy only)
 * and calculateBpmAsync on a background thread.
 */
class HeartBpmCalculator(private val sampleRate: Int = 44100) {

    // We downsample to 1000 Hz for analysis — heart sounds are < 200Hz
    private val analysisRate = 1000
    private val downsampleFactor = sampleRate / analysisRate  // 44

    // Circular buffer at downsampled rate: ~4 seconds = 4000 samples
    private val bufferDurationSec = 4.0
    private val bufferSize = (analysisRate * bufferDurationSec).toInt() // 4000
    private val audioBuffer = FloatArray(bufferSize)
    private var writePos = 0
    private var samplesCollected = 0

    // Accumulator for downsampling
    private var dsAccum = 0f
    private var dsCount = 0

    @Volatile
    var lastBpm = 0
        private set

    @Volatile
    private var lastUpdateTime = 0L

    @Volatile
    private var computing = false

    /**
     * Feed new audio data from the UI thread. This is very fast — just accumulates samples.
     * Returns true if enough data has been collected and it's time to compute BPM.
     */
    fun addSamples(data: FloatArray): Boolean {
        for (sample in data) {
            dsAccum += abs(sample)  // Pre-rectify during downsample
            dsCount++
            if (dsCount >= downsampleFactor) {
                audioBuffer[writePos] = dsAccum / dsCount
                writePos = (writePos + 1) % bufferSize
                samplesCollected++
                dsAccum = 0f
                dsCount = 0
            }
        }

        val now = System.currentTimeMillis()
        return !computing &&
               now - lastUpdateTime >= 5000L &&
               samplesCollected >= analysisRate * 3
    }

    /**
     * Run the actual BPM computation. MUST be called on a background thread.
     * Returns the computed BPM or 0 if invalid.
     */
    fun computeBpm(): Int {
        if (computing) return lastBpm
        computing = true
        lastUpdateTime = System.currentTimeMillis()

        try {
            // Extract the buffer in order
            val length = minOf(samplesCollected, bufferSize)
            val analysis = FloatArray(length)
            val startPos = (writePos - length + bufferSize) % bufferSize
            for (i in 0 until length) {
                analysis[i] = audioBuffer[(startPos + i) % bufferSize]
            }

            val bpm = calculateBpm(analysis)
            if (bpm > 0) {
                lastBpm = bpm
            }
            return lastBpm
        } catch (_: Exception) {
            return lastBpm
        } finally {
            computing = false
        }
    }

    private fun calculateBpm(envelope: FloatArray): Int {
        // envelope is already rectified + downsampled to 1000 Hz
        if (envelope.size < analysisRate * 2) return 0

        // Step 1: Normalize to [0,1]
        val maxVal = envelope.max()
        if (maxVal < 0.0001f) return 0
        val normalized = FloatArray(envelope.size) { envelope[it] / maxVal }

        // Step 2: Smooth with moving average (~30ms window at 1000Hz = 30 samples)
        val windowSize = 30
        val smoothed = FloatArray(normalized.size)
        var runningSum = 0f
        for (i in 0 until minOf(windowSize, normalized.size)) {
            runningSum += normalized[i]
        }
        for (i in normalized.indices) {
            val wEnd = i + windowSize
            if (wEnd < normalized.size) {
                runningSum += normalized[wEnd]
            }
            if (i > 0 && i - 1 < normalized.size) {
                runningSum -= normalized[i - 1]
            }
            val cnt = minOf(windowSize, normalized.size - i)
            smoothed[i] = if (cnt > 0) runningSum / cnt else 0f
        }

        // Step 3: Autocorrelation for lag range 0.4s to 1.5s at 1000Hz
        val minLag = (0.4 * analysisRate).toInt()   // 400 samples = 150 BPM
        val maxLag = (1.5 * analysisRate).toInt()    // 1500 samples = 40 BPM
        val n = smoothed.size

        if (maxLag >= n) return 0

        var maxCorr = -1f
        var bestLag = 0

        // Autocorrelation at 1000Hz: ~1100 lags × ~3000 samples = ~3.3M ops (fast!)
        for (lag in minLag..minOf(maxLag, n - 1)) {
            var sum = 0f
            val limit = n - lag
            for (t in 0 until limit) {
                sum += smoothed[t] * smoothed[t + lag]
            }
            val corr = sum / limit
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }

        // Normalize
        var autoZero = 0f
        for (t in smoothed.indices) {
            autoZero += smoothed[t] * smoothed[t]
        }
        autoZero /= n
        val normalizedCorr = if (autoZero > 0) maxCorr / autoZero else 0f

        // Validate
        if (normalizedCorr < 0.3f) return 0
        if (bestLag == 0) return 0

        val period = bestLag.toFloat() / analysisRate
        val bpm = (60f / period).roundToInt()

        return if (bpm in 40..200) bpm else 0
    }

    fun reset() {
        audioBuffer.fill(0f)
        writePos = 0
        samplesCollected = 0
        lastBpm = 0
        lastUpdateTime = 0L
        dsAccum = 0f
        dsCount = 0
        computing = false
    }
}
