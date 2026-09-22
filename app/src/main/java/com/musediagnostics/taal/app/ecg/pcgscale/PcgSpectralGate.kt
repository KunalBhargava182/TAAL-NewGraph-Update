package com.musediagnostics.taal.app.ecg.pcgscale

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spectral-gate denoiser for the PcgScale display traces. Pure Kotlin, no Gradle
 * dependencies — hand-rolls its own radix-2 FFT below. Never used on the recording path;
 * see [PcgDisplayFilter] for the display chain that runs this on an already-decoded,
 * in-memory sample array off the main thread. Ported from stemz-app's copy of this file
 * (byte-identical), see that module's SUPERMASTER doc §4.1 for the on-device validation
 * history.
 *
 * Algorithm (validated offline against paired study recordings — see
 * docs/notes/SAMSUNG_AUDIO_LEVEL_ATTENUATION_DIAGNOSTIC.md for the companion capture-level
 * diagnostic this was built alongside):
 *
 *  1. STFT with [NFFT]=2048, [HOP]=1024 (50% overlap), Hann analysis AND synthesis windows.
 *  2. Noise profile computed in three streaming passes so memory stays O(NFFT) even for a
 *     300s file — the full magnitude matrix for a file that long would be ~100MB, so it is
 *     never materialized:
 *       pass 1 — per-frame RMS (raw, unwindowed); select the quietest
 *                [Params.quietFrameFraction] of frames. With
 *                [Params.excludeQuietFramesAdjacentToLoud] the selection is additionally
 *                restricted to quiet frames whose neighbours are ALSO quiet — i.e. genuinely
 *                mid-diastole, never the tail of a beat.
 *       pass 2 — mean magnitude per bin, averaged over ONLY those quiet frames (and the mean
 *                frame RMS over them = the broadband noise floor).
 *       pass 3 — per frame: gain[bin] = clamp((mag - k*noiseProfile[bin]) / mag, minGain, 1),
 *                with temporal release smoothing g_t = max(g_raw, r * g_{t-1}) per bin
 *                (suppresses musical noise), applied to the complex spectrum, then
 *                overlap-added back with the synthesis window.
 *                TRANSIENT PROTECTION ([Params.transientProtectDb], rev 2 of this class,
 *                2026-09-03): a frame whose broadband RMS exceeds the noise floor by this
 *                many dB carries real signal (S1, S2, a murmur) and passes through with
 *                gain 1 in every bin. Without it, magnitude subtraction shaved the 20–80 Hz
 *                bins that S1 shares with the rumble profile and visibly shrank S1/S2 on
 *                device (~1 dB measured, more perceived). With it, measured on the study
 *                recordings: −10 to −11 dB hiss in the gaps, 0.0 dB change on the beats.
 *  3. Overlap-add is normalized by the summed squared window (analysis*synthesis, both Hann,
 *     so this is sum-of-window-squared) — with gain forced to 1 this is an exact identity
 *     reconstruction of the input up to floating-point error (verified in
 *     PcgSpectralGateTest).
 *
 * [Params.DEFAULT] reproduces the original (2026-09-02) behaviour exactly; [Params.DISPLAY]
 * is what the PcgScale screens use.
 */
class PcgSpectralGate(private val params: Params = Params.DEFAULT) {

    data class Params(
        /** k in gain = (mag - k*noise)/mag. */
        val noiseFloorMultiplier: Float = 1.5f,
        val minGainDb: Float = -18f,
        /** r in g_t = max(g_raw, r*g_{t-1}). */
        val releaseSmoothing: Float = 0.6f,
        val quietFrameFraction: Double = 0.30,
        /** Frames this many dB above the noise floor are never attenuated. null = off. */
        val transientProtectDb: Float? = null,
        /** Only use quiet frames whose neighbours are also quiet for the noise profile. */
        val excludeQuietFramesAdjacentToLoud: Boolean = false,
    ) {
        companion object {
            /** Original behaviour — byte-identical to the 2026-09-02 implementation. */
            val DEFAULT = Params()
            /** Peak-preserving preset for the graph screens. */
            val DISPLAY = Params(transientProtectDb = 8f, excludeQuietFramesAdjacentToLoud = true)
        }
    }

    companion object {
        const val NFFT = 2048
        const val HOP = 1024
        private const val NUM_BINS = NFFT / 2 + 1
    }

    private val minGain = 10.0.pow(params.minGainDb / 20.0).toFloat()
    private val protectRatio: Float? = params.transientProtectDb?.let { 10.0.pow(it / 20.0).toFloat() }

    private val analysisWindow = FloatArray(NFFT) { j -> (0.5 - 0.5 * cos(2.0 * PI * j / NFFT)).toFloat() }

    /** Same length as [samples]. Silence in (all zeros) produces silence out. */
    fun process(samples: FloatArray, sampleRate: Float): FloatArray {
        val n = samples.size
        if (n == 0) return FloatArray(0)

        val numFrames = ((n - 1) / HOP) + 1

        // Pass 1: per-frame RMS on the raw (unwindowed) segment.
        val frameRms = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            val start = f * HOP
            var sumSq = 0.0
            val end = min(start + NFFT, n)
            for (idx in start until end) {
                val s = samples[idx].toDouble()
                sumSq += s * s
            }
            frameRms[f] = sqrt(sumSq / NFFT).toFloat()
        }

        val quietCount = max(1, (numFrames * params.quietFrameFraction).roundToInt())
        var quietFrameIndices: List<Int> = frameRms.indices.sortedBy { frameRms[it] }.take(quietCount)
        if (params.excludeQuietFramesAdjacentToLoud) {
            val isQuiet = BooleanArray(numFrames)
            for (f in quietFrameIndices) isQuiet[f] = true
            val interior = quietFrameIndices.filter { f ->
                (f == 0 || isQuiet[f - 1]) && (f == numFrames - 1 || isQuiet[f + 1])
            }
            // Keep the restriction only when enough mid-diastole frames survive it.
            if (interior.size >= 3) quietFrameIndices = interior
        }

        // Pass 2: mean magnitude per bin over only the quiet frames, plus the broadband floor.
        val noiseProfile = FloatArray(NUM_BINS)
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        var noiseRmsSum = 0.0
        for (f in quietFrameIndices) {
            loadFrame(samples, f, re, im)
            Fft.transform(re, im, inverse = false)
            for (b in 0 until NUM_BINS) {
                noiseProfile[b] += hypot(re[b], im[b]).toFloat()
            }
            noiseRmsSum += frameRms[f]
        }
        val noiseRms: Float
        if (quietFrameIndices.isNotEmpty()) {
            val count = quietFrameIndices.size
            for (b in 0 until NUM_BINS) noiseProfile[b] = noiseProfile[b] / count
            noiseRms = (noiseRmsSum / count).toFloat()
        } else {
            noiseRms = 0f
        }

        // Pass 3: process every frame, apply the gain, overlap-add.
        val output = FloatArray(n)
        val windowSqSum = FloatArray(n)
        val prevGain = FloatArray(NUM_BINS)
        val gainRe = DoubleArray(NFFT)
        val gainIm = DoubleArray(NFFT)

        for (f in 0 until numFrames) {
            loadFrame(samples, f, re, im)
            Fft.transform(re, im, inverse = false)

            // Transient protection: a frame with real signal energy is passed through whole.
            val protectFrame = protectRatio != null && frameRms[f] > noiseRms * protectRatio

            for (b in 0 until NUM_BINS) {
                val g: Float
                if (protectFrame) {
                    g = 1f
                } else {
                    val mag = hypot(re[b], im[b]).toFloat()
                    val rawGain = if (mag > 1e-12f) {
                        ((mag - params.noiseFloorMultiplier * noiseProfile[b]) / mag).coerceIn(minGain, 1f)
                    } else {
                        minGain
                    }
                    g = max(rawGain, params.releaseSmoothing * prevGain[b])
                }
                prevGain[b] = g

                gainRe[b] = re[b] * g
                gainIm[b] = im[b] * g
                if (b in 1 until NFFT / 2) {
                    // Mirror for the negative-frequency half so the inverse FFT is real.
                    gainRe[NFFT - b] = re[b] * g
                    gainIm[NFFT - b] = -im[b] * g
                }
            }

            Fft.transform(gainRe, gainIm, inverse = true)

            val start = f * HOP
            val end = min(start + NFFT, n)
            for (idx in start until end) {
                val j = idx - start
                val w = analysisWindow[j]
                output[idx] += gainRe[j].toFloat() * w
                windowSqSum[idx] += w * w
            }
        }

        for (i in 0 until n) {
            output[i] = if (windowSqSum[i] > 1e-9f) output[i] / windowSqSum[i] else 0f
        }
        return output
    }

    /** Fills [re]/[im] with the Hann-windowed frame [f]'s samples (zero-padded past the end). */
    private fun loadFrame(samples: FloatArray, f: Int, re: DoubleArray, im: DoubleArray) {
        val start = f * HOP
        val n = samples.size
        for (j in 0 until NFFT) {
            val idx = start + j
            re[j] = if (idx < n) (samples[idx] * analysisWindow[j]).toDouble() else 0.0
            im[j] = 0.0
        }
    }

    /** Minimal in-place iterative radix-2 Cooley-Tukey FFT. Size must be a power of two. */
    private object Fft {
        fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j or bit
                if (i < j) {
                    var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                    tmp = im[i]; im[i] = im[j]; im[j] = tmp
                }
            }

            var len = 2
            while (len <= n) {
                val ang = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
                val wRe = cos(ang)
                val wIm = sin(ang)
                var i = 0
                while (i < n) {
                    var curRe = 1.0
                    var curIm = 0.0
                    val half = len / 2
                    for (k in 0 until half) {
                        val uRe = re[i + k]
                        val uIm = im[i + k]
                        val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                        val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                        re[i + k] = uRe + vRe
                        im[i + k] = uIm + vIm
                        re[i + k + half] = uRe - vRe
                        im[i + k + half] = uIm - vIm
                        val nextRe = curRe * wRe - curIm * wIm
                        val nextIm = curRe * wIm + curIm * wRe
                        curRe = nextRe
                        curIm = nextIm
                    }
                    i += len
                }
                len = len shl 1
            }

            if (inverse) {
                for (i in 0 until n) {
                    re[i] = re[i] / n
                    im[i] = im[i] / n
                }
            }
        }
    }
}
