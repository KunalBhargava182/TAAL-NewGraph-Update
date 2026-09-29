package com.purnacardio.signal.pcg

import kotlin.math.*

/**
 * 12-channel PCG feature extraction at 200Hz, matching the Python Pipeline C-200.
 *
 * Pure Kotlin — no Android dependencies. Can be used in signal-processing module
 * or unit-tested independently.
 *
 * ## Channels
 * | Ch  | Description                        |
 * |-----|------------------------------------|
 * | 0   | 20-50Hz Hilbert envelope (S1-band) |
 * | 1   | 50-100Hz Hilbert envelope (S2-band)|
 * | 2   | 100-200Hz Hilbert envelope         |
 * | 3   | Spectral ratio ch0/(ch1+eps)       |
 * | 4   | d/dt ch0 (S1 onset sharpness)      |
 * | 5   | d/dt ch1 (S2 onset sharpness)      |
 * | 6   | HR estimate from autocorrelation   |
 * | 7-10| 4 coarse mel bands (100-500Hz)     |
 * | 11  | Mean delta across mel bands        |
 */
class PcgFeatureExtractor(
    private val targetSr: Int = TARGET_SR,
    private val featFs: Int = FEAT_FS
) {
    companion object {
        const val TARGET_SR = 2000
        const val FEAT_FS = 200
        const val SPF = TARGET_SR / FEAT_FS  // 10
        const val NUM_CHANNELS = 12
        private const val EPS = 1e-8f

        /** Robust scale reference: high enough to sit above S1/S2, below a rare transient. */
        private const val NORMALISER_PERCENTILE = 0.995f

        /**
         * How far above that reference the peak may sit before it is treated as an outlier.
         *
         * A recording whose peaks are genuinely its heart sounds measures about 2.0, so 4 leaves a
         * factor of two of ordinary headroom — a louder beat or a deep breath will not trip it —
         * while the 15.8 measured on a knock-dominated capture is caught comfortably.
         */
        private const val OUTLIER_HEADROOM = 4.0f

        // Mel FFT config
        private const val MEL_NFFT = 128
        private const val MEL_HOP = SPF
        private const val MEL_NBINS = MEL_NFFT / 2 + 1

        // ── Pre-computed Butterworth 4th order bandpass SOS coefficients ──
        // scipy.signal.butter(4, [flo, fhi], btype='band', fs=2000, output='sos')

        private val SOS_20_50 = arrayOf(
            doubleArrayOf(4.372688797820071e-06, 8.745377595640141e-06, 4.372688797820071e-06, 1.0, -1.886956052183046, 0.9009372762241582),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.9259899215349412, 0.9323844832162169),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.927893794501295, 0.9506434225620732),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9746628424034367, 0.9787859403048573)
        )

        private val SOS_50_100 = arrayOf(
            doubleArrayOf(3.123897691708261e-05, 6.247795383416522e-05, 3.123897691708261e-05, 1.0, -1.7867084943628362, 0.8471188937926489),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.84748005482431, 0.8823405750772274),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.834546897615787, 0.9246025259154645),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.934114003125909, 0.9593668357918754)
        )

        private val SOS_100_200 = arrayOf(
            doubleArrayOf(0.00041659920440659937, 0.0008331984088131987, 0.00041659920440659937, 1.0, -1.4941763257252205, 0.7165735039076229),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.6448831345368808, 0.7756657764367223),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.5176347349891066, 0.8576289712605754),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.8210534220090282, 0.9193949437789302)
        )

        private val ALL_SOS = arrayOf(SOS_20_50, SOS_50_100, SOS_100_200)

        // ── Pre-computed mel filterbank (4 bands, 100-500Hz, n_fft=128, sr=2000) ──
        private val MEL_BANDS = arrayOf(
            Pair(7, floatArrayOf(0.00146484375f, 0.00390625f, 0.00634765625f, 0.0087890625f, 0.01123046875f, 0.011328125f, 0.008886719f, 0.006445312f, 0.004003906f, 0.0015625f)),
            Pair(12, floatArrayOf(0.0011718750f, 0.0036132813f, 0.006054687f, 0.008496094f, 0.0109375f, 0.011621093f, 0.009179687f, 0.006738281f, 0.004296875f, 0.0018554688f)),
            Pair(17, floatArrayOf(0.0008789062f, 0.0033203126f, 0.005761719f, 0.008203125f, 0.010644531f, 0.011914062f, 0.009472656f, 0.00703125f, 0.004589844f, 0.0021484375f)),
            Pair(22, floatArrayOf(0.0005859375f, 0.0030273437f, 0.005468750f, 0.007910157f, 0.010351563f, 0.01220703125f, 0.009765625f, 0.00732421875f, 0.0048828125f, 0.00244140625f))
        )
    }

    /**
     * Extract 12 channels at 200Hz from audio at [TARGET_SR] (2000Hz).
     *
     * @param audio Audio resampled to 2000Hz.
     * @return Array of 12 FloatArrays, each of length nFrames, or null if too short/silent.
     */
    /**
     * The value the recording is divided by before feature extraction.
     *
     * **Why not simply the peak.** Dividing by `max|x|` hands the entire scaling decision to one
     * sample. On a phone held against a chest, the largest sample is very often a knock or a
     * clothing rustle rather than a heart sound, and everything real is then scaled down with it —
     * a recording whose content is 30 dB below its own loudest transient arrives at the model as
     * near-silence, which is indistinguishable from a mic that recorded nothing.
     *
     * **Why not simply a percentile either.** S1 and S2 *are* the peaks of a good recording; they
     * occupy a large enough share of the samples that any percentile low enough to be robust also
     * sits inside them, and dividing by it would push the heart sounds far past full scale.
     *
     * So: the peak, **capped** at [OUTLIER_HEADROOM] times the 99.5th percentile. Measured on the
     * first two real captures, that ratio separates the two cases cleanly —
     *
     *   a recording whose peaks ARE the heart sounds   max/p99.5 = 2.0
     *   a recording dominated by one transient          max/p99.5 = 15.8
     *
     * — so on a well-formed recording the cap never binds and the behaviour is exactly what it was
     * before, while an outlier-dominated one is scaled by its own bulk and the transient clips.
     * Preserving the old behaviour in the ordinary case matters: the model was trained on
     * peak-normalised audio, and this must not quietly move the input distribution underneath it.
     */
    internal fun normaliser(audio: FloatArray, maxAbs: Float): Float {
        val mags = FloatArray(audio.size) { abs(audio[it]) }
        mags.sort()
        val idx = ((mags.size - 1) * NORMALISER_PERCENTILE).toInt().coerceIn(0, mags.size - 1)
        val p = mags[idx]
        if (p <= 0f) return maxAbs
        return min(maxAbs, OUTLIER_HEADROOM * p)
    }

    fun extract(audio: FloatArray): Array<FloatArray>? {
        if (audio.size < targetSr) return null

        val maxAbs = audio.maxOf { abs(it) }
        if (maxAbs < 1e-10f) return null
        val denom = normaliser(audio, maxAbs)
        val normalized = FloatArray(audio.size) { (audio[it] / denom).coerceIn(-1f, 1f) }

        val nFrames = audio.size / SPF

        // Ch 0-2: Band envelopes
        val envelopes = Array(3) { b ->
            val filtered = sosFilter(normalized, ALL_SOS[b])
            val envelope = hilbertEnvelope(filtered)
            maxPoolDownsample(envelope, nFrames, SPF)
        }

        // Ch 3: Spectral ratio
        val ratio = FloatArray(nFrames) { envelopes[0][it] / (envelopes[1][it] + EPS) }

        // Ch 4-5: Derivatives
        val dLow = gradient(envelopes[0])
        val dMid = gradient(envelopes[1])

        // Ch 6: HR
        val hrChannel = computeHrChannel(envelopes[0], nFrames)

        // Ch 7-10: Mel
        val melBands = computeCoarseMel(normalized, nFrames)

        // Ch 11: Delta mel mean
        val deltaMelMean = FloatArray(nFrames)
        if (nFrames > 2) {
            for (f in 1 until nFrames - 1) {
                var sum = 0f
                for (b in 0 until 4) sum += melBands[b][f + 1] - melBands[b][f - 1]
                deltaMelMean[f] = sum / 8f
            }
            deltaMelMean[0] = deltaMelMean[1]
            deltaMelMean[nFrames - 1] = deltaMelMean[nFrames - 2]
        }

        val features = arrayOf(
            envelopes[0], envelopes[1], envelopes[2],
            ratio, dLow, dMid, hrChannel,
            melBands[0], melBands[1], melBands[2], melBands[3],
            deltaMelMean
        )

        // Normalize
        for (ch in 0..2) percentileNormalize(features[ch])
        clipAndScale(features[3])
        groupNormalize(features, 4, 5)
        for (ch in 7..11) percentileNormalize(features[ch])

        return features
    }

    // ═══════════════════════════════════════════════════════════════
    // DSP
    // ═══════════════════════════════════════════════════════════════

    /** Cascaded SOS IIR filter (Direct Form II Transposed). */
    internal fun sosFilter(signal: FloatArray, sos: Array<DoubleArray>): FloatArray {
        var x = DoubleArray(signal.size) { signal[it].toDouble() }

        for (section in sos) {
            val b0 = section[0]; val b1 = section[1]; val b2 = section[2]
            val a1 = section[4]; val a2 = section[5]

            val y = DoubleArray(x.size)
            var w1 = 0.0; var w2 = 0.0

            for (n in x.indices) {
                val w0 = x[n] - a1 * w1 - a2 * w2
                y[n] = b0 * w0 + b1 * w1 + b2 * w2
                w2 = w1; w1 = w0
            }
            x = y
        }
        return FloatArray(x.size) { x[it].toFloat() }
    }

    /** Hilbert envelope via 63-tap FIR approximation. */
    internal fun hilbertEnvelope(signal: FloatArray): FloatArray {
        val n = signal.size
        val halfLen = 31
        val h = DoubleArray(2 * halfLen + 1)
        for (k in 1..halfLen) {
            if (k % 2 == 1) {
                val v = 2.0 / (PI * k)
                h[halfLen + k] = v
                h[halfLen - k] = -v
            }
        }

        val hilbert = FloatArray(n)
        for (i in signal.indices) {
            var sum = 0.0
            for (k in -halfLen..halfLen) {
                val idx = i - k
                if (idx in signal.indices) sum += signal[idx].toDouble() * h[halfLen + k]
            }
            hilbert[i] = sum.toFloat()
        }

        return FloatArray(n) { sqrt(signal[it] * signal[it] + hilbert[it] * hilbert[it]) }
    }

    private fun maxPoolDownsample(signal: FloatArray, nFrames: Int, hop: Int): FloatArray {
        return FloatArray(nFrames) { f ->
            var maxVal = 0f
            val start = f * hop
            val end = minOf(start + hop, signal.size)
            for (s in start until end) if (signal[s] > maxVal) maxVal = signal[s]
            maxVal
        }
    }

    /** 4-band coarse mel spectrogram using 128-point FFT. */
    internal fun computeCoarseMel(signal: FloatArray, nFrames: Int): Array<FloatArray> {
        val bands = Array(4) { FloatArray(nFrames) }
        val window = FloatArray(MEL_NFFT) { (0.5 * (1.0 - cos(2.0 * PI * it / MEL_NFFT))).toFloat() }

        val padded = FloatArray(signal.size + MEL_NFFT)
        System.arraycopy(signal, 0, padded, MEL_NFFT / 2, signal.size)

        val fftReal = DoubleArray(MEL_NFFT)
        val fftImag = DoubleArray(MEL_NFFT)

        for (f in 0 until nFrames) {
            val center = f * MEL_HOP + MEL_NFFT / 2

            for (i in 0 until MEL_NFFT) {
                val idx = center - MEL_NFFT / 2 + i
                fftReal[i] = if (idx in padded.indices) (padded[idx] * window[i]).toDouble() else 0.0
                fftImag[i] = 0.0
            }

            fft(fftReal, fftImag, MEL_NFFT)

            for (b in 0 until 4) {
                val (startBin, weights) = MEL_BANDS[b]
                var energy = 0.0
                for (w in weights.indices) {
                    val bin = startBin + w
                    if (bin < MEL_NBINS) {
                        val power = fftReal[bin] * fftReal[bin] + fftImag[bin] * fftImag[bin]
                        energy += power * weights[w]
                    }
                }
                bands[b][f] = (10.0 * log10(energy + 1e-10)).toFloat()
            }
        }
        return bands
    }

    /** Radix-2 Cooley-Tukey FFT (in-place). N must be power of 2. */
    internal fun fft(real: DoubleArray, imag: DoubleArray, n: Int) {
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
            var k = n / 2
            while (k <= j) { j -= k; k /= 2 }
            j += k
        }

        var step = 1
        while (step < n) {
            val halfStep = step; step *= 2
            val angle = -PI / halfStep
            val wR = cos(angle); val wI = sin(angle)
            for (group in 0 until n step step) {
                var twR = 1.0; var twI = 0.0
                for (pair in 0 until halfStep) {
                    val i1 = group + pair; val i2 = i1 + halfStep
                    val tR = twR * real[i2] - twI * imag[i2]
                    val tI = twR * imag[i2] + twI * real[i2]
                    real[i2] = real[i1] - tR; imag[i2] = imag[i1] - tI
                    real[i1] += tR; imag[i1] += tI
                    val newTwR = twR * wR - twI * wI
                    twI = twR * wI + twI * wR; twR = newTwR
                }
            }
        }
    }

    private fun computeHrChannel(envLow: FloatArray, nFrames: Int): FloatArray {
        val hr = FloatArray(nFrames)
        val windowFrames = featFs * 4
        val updateInterval = featFs

        var lastHrNorm = 0.5f
        var start = 0
        while (start + windowFrames <= nFrames) {
            val segment = envLow.sliceArray(start until start + windowFrames)
            val mean = segment.average().toFloat()
            val centered = FloatArray(segment.size) { segment[it] - mean }

            val minLag = (0.33 * featFs).toInt()
            val maxLag = (1.5 * featFs).toInt()

            var bestLag = minLag
            var bestCorr = Float.NEGATIVE_INFINITY
            for (lag in minLag until minOf(maxLag, windowFrames)) {
                var corr = 0f
                for (i in 0 until windowFrames - lag) corr += centered[i] * centered[i + lag]
                if (corr > bestCorr) { bestCorr = corr; bestLag = lag }
            }

            val hrBpm = 60f * featFs / bestLag
            lastHrNorm = ((hrBpm - 40f) / 140f).coerceIn(0f, 1f)

            val fillEnd = minOf(start + updateInterval, nFrames)
            for (f in start until fillEnd) hr[f] = lastHrNorm
            start += updateInterval
        }
        for (f in start until nFrames) hr[f] = lastHrNorm
        return hr
    }

    private fun gradient(signal: FloatArray): FloatArray {
        val n = signal.size
        if (n < 2) return FloatArray(n)
        val grad = FloatArray(n)
        grad[0] = signal[1] - signal[0]
        for (i in 1 until n - 1) grad[i] = (signal[i + 1] - signal[i - 1]) / 2f
        grad[n - 1] = signal[n - 1] - signal[n - 2]
        return grad
    }

    private fun percentileNormalize(channel: FloatArray) {
        val sorted = channel.sorted()
        val n = sorted.size; if (n < 2) return
        val p2 = sorted[(n * 0.02).toInt()]
        val p98 = sorted[(n * 0.98).toInt()]
        val range = p98 - p2
        if (range > 1e-10f) for (i in channel.indices) channel[i] = (channel[i] - p2) / range
        else channel.fill(0f)
    }

    private fun clipAndScale(channel: FloatArray) {
        val sorted = channel.sorted()
        val p98 = sorted[(sorted.size * 0.98).toInt()]
        for (i in channel.indices) channel[i] = channel[i].coerceIn(0f, p98)
        val maxVal = channel.maxOrNull() ?: return
        if (maxVal > 1e-10f) for (i in channel.indices) channel[i] /= maxVal
    }

    private fun groupNormalize(features: Array<FloatArray>, chStart: Int, chEnd: Int) {
        val allVals = mutableListOf<Float>()
        for (ch in chStart..chEnd) allVals.addAll(features[ch].toList())
        allVals.sort()
        val n = allVals.size; if (n < 2) return
        val p2 = allVals[(n * 0.02).toInt()]
        val p98 = allVals[(n * 0.98).toInt()]
        val range = p98 - p2
        if (range > 1e-10f) {
            for (ch in chStart..chEnd)
                for (i in features[ch].indices) features[ch][i] = (features[ch][i] - p2) / range
        } else {
            for (ch in chStart..chEnd) features[ch].fill(0f)
        }
    }

    /** Linear interpolation resampler. */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).toInt()
        return FloatArray(outLen) { i ->
            val srcIdx = i * ratio
            val idx0 = srcIdx.toInt().coerceIn(0, input.size - 2)
            val frac = (srcIdx - idx0).toFloat()
            input[idx0] * (1f - frac) + input[idx0 + 1] * frac
        }
    }
}
