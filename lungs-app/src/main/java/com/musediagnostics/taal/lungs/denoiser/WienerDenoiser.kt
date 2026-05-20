package com.musediagnostics.taal.lungs.denoiser

import kotlin.math.*

class WienerDenoiser {

    companion object {
        private const val ALPHA_S = 0.9          // power smoothing factor
        private const val ALPHA_D = 0.85         // noise update (signal absent)
        private const val L = 125                // sliding-min window (1.25s)
        private const val DELTA = 5.0            // signal presence threshold
        private const val BREATH_CORRECTION = 0.20f
        private const val BETA = 0.08f           // global spectral floor
        private const val WARMUP = 20            // first 20 frames pass-through
        private const val RELEASE = 0.2f
        // (upperHz, overEstimation, floor)
        private val BAND_PARAMS = arrayOf(
            Triple(300f,  1.00f, 0.20f),
            Triple(600f,  1.50f, 0.12f),
            Triple(1200f, 2.00f, 0.06f),
            Triple(2000f, 2.50f, 0.00f),
        )
    }

    // Input:  real[nBins][nFrames], imag[nBins][nFrames]  (from StftEngine)
    // Output: denoised real[nBins][nFrames], imag[nBins][nFrames]
    fun denoise(
        real: Array<FloatArray>,
        imag: Array<FloatArray>,
        sampleRate: Int = 8000,
        nFft: Int = 256
    ): Pair<Array<FloatArray>, Array<FloatArray>> {
        val nBins = real.size
        val nFrames = real[0].size

        // Power spectrum
        val power = Array(nBins) { k ->
            DoubleArray(nFrames) { t ->
                (real[k][t] * real[k][t] + imag[k][t] * imag[k][t]).toDouble()
            }
        }

        // IMCRA noise estimate
        val rawNoise = imcra(power, nBins, nFrames)

        // Apply breath correction (scale noise down by 0.20)
        val noiseEst = Array(nBins) { k ->
            FloatArray(nFrames) { t -> (rawNoise[k][t] * BREATH_CORRECTION).toFloat() }
        }

        // Magnitude and phase
        val mag = Array(nBins) { k ->
            FloatArray(nFrames) { t -> sqrt(real[k][t] * real[k][t] + imag[k][t] * imag[k][t]) }
        }
        val phs = Array(nBins) { k ->
            FloatArray(nFrames) { t -> atan2(imag[k][t], real[k][t]) }
        }

        // Apply Wiener filter to magnitude
        val denoisedMag = wienerFilter(mag, noiseEst, nBins, nFrames, sampleRate, nFft)

        // Reconstruct complex STFT (enhanced magnitude × original phase)
        val outReal = Array(nBins) { k -> FloatArray(nFrames) { t -> denoisedMag[k][t] * cos(phs[k][t]) } }
        val outImag = Array(nBins) { k -> FloatArray(nFrames) { t -> denoisedMag[k][t] * sin(phs[k][t]) } }
        return Pair(outReal, outImag)
    }

    // IMCRA: Improved Minima Controlled Recursive Averaging
    // Tracks the sliding minimum of smoothed power to estimate noise floor.
    private fun imcra(power: Array<DoubleArray>, nBins: Int, nFrames: Int): Array<DoubleArray> {
        val initFrames = minOf(10, nFrames)
        val noiseSeed = DoubleArray(nBins) { k -> (0 until initFrames).minOf { t -> power[k][t] } }

        val smoothPwr = Array(nBins) { DoubleArray(nFrames) }
        val noiseOut  = Array(nBins) { DoubleArray(nFrames) }

        // Monotonic deque per bin: stores (value, frameIndex) — gives O(1) sliding min
        val dqVal = Array(nBins) { ArrayDeque<Double>() }
        val dqIdx = Array(nBins) { ArrayDeque<Int>() }

        for (t in 0 until nFrames) {
            for (k in 0 until nBins) {
                // Smooth power
                smoothPwr[k][t] = if (t == 0) power[k][t]
                    else ALPHA_S * smoothPwr[k][t - 1] + (1.0 - ALPHA_S) * power[k][t]

                val sp = smoothPwr[k][t]

                // Maintain monotonic deque (pop larger values from back)
                while (dqVal[k].isNotEmpty() && dqVal[k].last() >= sp) {
                    dqVal[k].removeLast(); dqIdx[k].removeLast()
                }
                dqVal[k].addLast(sp); dqIdx[k].addLast(t)

                // Remove entries outside the window
                while (dqIdx[k].first() < t - L) {
                    dqVal[k].removeFirst(); dqIdx[k].removeFirst()
                }

                val minVal = dqVal[k].first()

                // Signal presence: if power is 5x the minimum, signal is likely present
                val signalPresent = if (sp / (minVal + 1e-10) > DELTA) 1.0 else 0.0

                // When signal present: freeze noise estimate (alpha=1.0 → no update)
                // When signal absent: track noise (alpha=0.85)
                val alpha = ALPHA_D + (1.0 - ALPHA_D) * signalPresent
                noiseOut[k][t] = if (t == 0) noiseSeed[k]
                    else alpha * noiseOut[k][t - 1] + (1.0 - alpha) * sp
            }
        }
        return noiseOut
    }

    // Wiener filter: per-frequency-bin adaptive gain
    private fun wienerFilter(
        mag: Array<FloatArray>,
        noiseEst: Array<FloatArray>,
        nBins: Int, nFrames: Int,
        sampleRate: Int, nFft: Int
    ): Array<FloatArray> {
        val result = Array(nBins) { k -> mag[k].clone() }

        for (k in 0 until nBins) {
            val freqHz = k.toFloat() * sampleRate / nFft
            val (overEst, bandFloor) = getBandParams(freqHz)
            if (overEst == 0f) continue   // below 100 Hz or above 2000 Hz: pass-through

            val attack = if (freqHz < 600f) 0.9f else 0.7f
            var prev = 1.0f

            for (t in 0 until nFrames) {
                if (t < WARMUP) { prev = 1.0f; continue }

                val effNoise = overEst * noiseEst[k][t]
                val noisyPwr = mag[k][t] * mag[k][t]
                val sigEst   = maxOf(noisyPwr - effNoise, 0f)
                val snrEst   = sigEst / (effNoise + 1e-10f)

                var gain = snrEst / (snrEst + 1f)
                gain = maxOf(gain, BETA)
                if (bandFloor > 0f) gain = maxOf(gain, bandFloor)

                // Asymmetric smoothing: slow attack, fast release
                val smooth = if (gain >= prev)
                    (1f - attack) * prev + attack * gain
                else
                    (1f - RELEASE) * prev + RELEASE * gain

                prev = smooth
                result[k][t] = smooth * mag[k][t]
            }
        }
        return result
    }

    private fun getBandParams(freqHz: Float): Pair<Float, Float> {
        if (freqHz < 100f) return Pair(0f, 0f)
        for ((upper, over, floor) in BAND_PARAMS) {
            if (freqHz < upper) return Pair(over, floor)
        }
        return Pair(0f, 0f)
    }
}
