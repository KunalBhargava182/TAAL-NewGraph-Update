package com.musediagnostics.taal.lungs.denoiser

import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import kotlin.math.*

class StftEngine {

    private val fft = FastFourierTransformer(DftNormalization.STANDARD)

    companion object {
        const val N_FFT = 256       // 129 frequency bins for CRN model
        const val WIN_SIZE = 200    // 25ms at 8kHz
        const val HOP = 80          // 10ms at 8kHz
        const val N_BINS = 129      // N_FFT/2 + 1
    }

    // Hann window: w[n] = 0.5 * (1 - cos(2π*n/(WIN_SIZE-1)))
    private val hannWindow = FloatArray(WIN_SIZE) { n ->
        (0.5 * (1.0 - cos(2.0 * PI * n / (WIN_SIZE - 1)))).toFloat()
    }

    // Returns Pair(real[129][nFrames], imag[129][nFrames])
    fun computeStft(audio: FloatArray): Pair<Array<FloatArray>, Array<FloatArray>> {
        // Pad audio so total length is multiple of HOP
        val padLen = HOP - (audio.size % HOP)
        val padded = FloatArray(audio.size + padLen) { i -> if (i < audio.size) audio[i] else 0f }

        val nFrames = (padded.size - WIN_SIZE) / HOP + 1
        val realOut = Array(N_BINS) { FloatArray(nFrames) }
        val imagOut = Array(N_BINS) { FloatArray(nFrames) }

        for (t in 0 until nFrames) {
            val start = t * HOP
            // Apply Hann window and zero-pad to N_FFT
            val frame = DoubleArray(N_FFT) { i ->
                if (i < WIN_SIZE) (padded[start + i] * hannWindow[i]).toDouble() else 0.0
            }
            val spectrum = fft.transform(frame, TransformType.FORWARD)
            for (k in 0 until N_BINS) {
                realOut[k][t] = spectrum[k].real.toFloat()
                imagOut[k][t] = spectrum[k].imaginary.toFloat()
            }
        }
        return Pair(realOut, imagOut)
    }

    // Overlap-add inverse STFT
    fun computeIstft(
        real: Array<FloatArray>,
        imag: Array<FloatArray>,
        originalLength: Int
    ): FloatArray {
        val nFrames = real[0].size
        val outputLen = nFrames * HOP + WIN_SIZE
        val output = FloatArray(outputLen)
        val windowSum = FloatArray(outputLen)

        for (t in 0 until nFrames) {
            // Build full N_FFT complex spectrum using conjugate symmetry
            val fullReal = DoubleArray(N_FFT)
            val fullImag = DoubleArray(N_FFT)
            for (k in 0 until N_BINS) {
                fullReal[k] = real[k][t].toDouble()
                fullImag[k] = imag[k][t].toDouble()
            }
            for (k in N_BINS until N_FFT) {
                val mirror = N_FFT - k
                fullReal[k] = fullReal[mirror]
                fullImag[k] = -fullImag[mirror]
            }
            val complexFrame = Array(N_FFT) { k ->
                org.apache.commons.math3.complex.Complex(fullReal[k], fullImag[k])
            }
            val timeFrame = fft.transform(complexFrame, TransformType.INVERSE)

            val start = t * HOP
            for (n in 0 until WIN_SIZE) {
                val sample = (timeFrame[n].real * hannWindow[n]).toFloat()
                output[start + n] += sample
                windowSum[start + n] += hannWindow[n] * hannWindow[n]
            }
        }

        // Normalise by sum-of-squared windows
        for (i in output.indices) {
            if (windowSum[i] > 1e-8f) output[i] /= windowSum[i]
        }
        return output.copyOf(originalLength)
    }

    fun magnitude(real: Array<FloatArray>, imag: Array<FloatArray>): Array<FloatArray> =
        Array(N_BINS) { k -> FloatArray(real[0].size) { t -> sqrt(real[k][t].pow(2) + imag[k][t].pow(2)) } }

    fun phase(real: Array<FloatArray>, imag: Array<FloatArray>): Array<FloatArray> =
        Array(N_BINS) { k -> FloatArray(real[0].size) { t -> atan2(imag[k][t], real[k][t]) } }

    fun fromMagnitudeAndPhase(
        mag: Array<FloatArray>,
        phase: Array<FloatArray>
    ): Pair<Array<FloatArray>, Array<FloatArray>> {
        val real = Array(N_BINS) { k -> FloatArray(mag[0].size) { t -> mag[k][t] * cos(phase[k][t]) } }
        val imag = Array(N_BINS) { k -> FloatArray(mag[0].size) { t -> mag[k][t] * sin(phase[k][t]) } }
        return Pair(real, imag)
    }
}
