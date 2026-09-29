package com.musediagnostics.taal.stemz.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * DISPLAY-ONLY signal conditioning for the PcgScale graphs (and the segmentation report's
 * chart). Never touches recorded files, playback audio, or the segmentation algorithm's
 * input — it only decides what the trace looks like. Pure Kotlin, no Gradle dependencies,
 * plain-JVM unit-testable.
 *
 * Why this exists (2026-09-03, from paired OnePlus/Samsung study recordings): the "noise"
 * that makes the graph read as a dense block is (a) 20–80 Hz rumble and 50 Hz mains hum
 * sitting INSIDE the heart passband, (b) broadband hiss in the diastolic gaps, and, on the
 * Samsung, (c) isolated full-scale USB-dropout glitches that draw as maxed-out spikes with
 * no audible sound. The chain here addresses each without touching the beats:
 *
 *  1. [PcgClickRemover] — isolated single-hop outliers (≥6× the surrounding 100 ms median,
 *     or full-scale runs shorter than 10 ms) are interpolated out. A real S1/S2 burst lasts
 *     30–100 ms and is surrounded by its own energy, so it can never look "isolated".
 *  2. ZERO-PHASE band-pass [Params.highPassHz]–[Params.lowPassHz] (default 20–500 Hz: rumble
 *     out below, murmur energy kept up to 500 Hz) plus zero-phase notches at 50/100/150 Hz.
 *     Zero-phase (forward+backward, [filtfilt]) matters for a chart: every feature stays at
 *     its true time, so the segmentation overlay and the 1 s grid boxes remain exact. The
 *     live recorder uses the causal [PcgLiveDisplayFilter] instead (a display can't see the
 *     future); its small phase lag at the band edges is invisible on a scrolling trace.
 *  3. Optional [PcgSpectralGate] with the [PcgSpectralGate.Params.DISPLAY] preset: the
 *     noise profile comes from genuinely quiet MID-DIASTOLE frames, and any frame carrying
 *     real energy (≥8 dB above that floor — S1, S2, murmurs) passes through untouched, so
 *     the gate can only ever act between events. Measured on the study recordings: −10 to
 *     −11 dB hiss with 0.0 dB (not ~1 dB as before) loss on the S1/S2 peaks.
 *
 * The magnitude response of the offline path (2nd-order Butterworth run forward+backward =
 * 4th-order zero-phase) and of the live path (two cascaded 2nd-order sections = 4th-order
 * causal) are matched, so live and review traces have the same spectral shape.
 */
object PcgDisplayFilter {

    data class Params(
        val highPassHz: Float = 20f,
        /**
         * Causal ([PcgLiveDisplayFilter]) high-pass order, 2 or 4. Ignored by the zero-phase
         * path, which always runs one Butterworth section forward+backward (4th-order
         * magnitude, no phase shift). A causal 4th-order corner has real group-delay
         * dispersion around 2–4× its corner frequency — exactly where S1 lives when the
         * corner is 20 Hz — and measurably smeared S1 onsets on device (−1.4/−2.0 dB on the
         * OnePlus/Samsung study recordings). 2nd-order at 10 Hz keeps that to −0.2/−1.0 dB
         * (the Samsung remainder is hum removed from the beats by the notches, not skew).
         */
        val highPassOrder: Int = 4,
        val lowPassHz: Float = 500f,
        val notchHz: List<Float> = listOf(50f, 100f, 150f),
        val notchQ: Float = 30f,
        val removeClicks: Boolean = true,
        /** null = no spectral gate (band-pass + notches + click removal only). */
        val spectralGate: PcgSpectralGate.Params? = PcgSpectralGate.Params.DISPLAY,
    ) {
        companion object {
            val DEFAULT = Params()
            /**
             * What the live recorder can do causally: no gate, and a deliberately gentler
             * high-pass (10 Hz, 2nd order) than the offline 20 Hz zero-phase one — see
             * [highPassOrder]. taal-core's own HEART band-pass already sits at 20 Hz on the
             * stream the recorder draws, so this only needs to catch the sub-20 Hz rumble
             * that leaks through that filter's shallow skirt.
             */
            val LIVE = Params(highPassHz = 10f, highPassOrder = 2, spectralGate = null)
        }
    }

    /** Reflective edge padding for [filtfilt] — long enough for the 20 Hz high-pass transient to die. */
    private const val FILTFILT_PAD_SECONDS = 0.25f

    /** Full offline chain on an already-decoded array. Returns a NEW array of the same length. */
    fun processOffline(samples: FloatArray, sampleRate: Float, params: Params = Params.DEFAULT): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0f) return samples.copyOf()
        var y = samples.copyOf()
        if (params.removeClicks) PcgClickRemover.removeClicks(y, sampleRate)
        y = zeroPhase(y, sampleRate, params)
        val gate = params.spectralGate
        if (gate != null) y = PcgSpectralGate(gate).process(y, sampleRate)
        return y
    }

    /** Zero-phase band-pass + notches only (no click removal, no gate). Same length out. */
    fun zeroPhase(samples: FloatArray, sampleRate: Float, params: Params = Params.DEFAULT): FloatArray {
        if (samples.size < 3) return samples.copyOf()
        val sections = ArrayList<BiquadCoeffs>()
        // A single Q=1/√2 section applied forward then backward has a 4th-order Butterworth
        // magnitude response — the zero-phase twin of the live filter's two-section cascade.
        if (params.highPassHz > 0f) sections += BiquadCoeffs.highPass(params.highPassHz, sampleRate, BUTTERWORTH_Q2)
        if (params.lowPassHz > 0f && params.lowPassHz < sampleRate / 2f) {
            sections += BiquadCoeffs.lowPass(params.lowPassHz, sampleRate, BUTTERWORTH_Q2)
        }
        for (f in params.notchHz) if (f > 0f && f < sampleRate / 2f) sections += BiquadCoeffs.notch(f, sampleRate, params.notchQ)
        return filtfilt(samples, sections, (FILTFILT_PAD_SECONDS * sampleRate).toInt())
    }

    /**
     * Forward-backward filtering with odd (reflective) edge extension, so the result has zero
     * phase shift and no start-up transient at the edges. Each section starts from rest;
     * the padding absorbs the settling.
     */
    internal fun filtfilt(x: FloatArray, sections: List<BiquadCoeffs>, padSamples: Int): FloatArray {
        val n = x.size
        if (n < 3 || sections.isEmpty()) return x.copyOf()
        val pad = padSamples.coerceIn(0, n - 1)
        val ext = FloatArray(n + 2 * pad)
        val first = x[0]
        val last = x[n - 1]
        for (i in 0 until pad) ext[i] = 2f * first - x[pad - i]
        System.arraycopy(x, 0, ext, pad, n)
        for (i in 0 until pad) ext[pad + n + i] = 2f * last - x[n - 2 - i]

        for (c in sections) runCausal(ext, c, BiquadState())
        ext.reverse()
        for (c in sections) runCausal(ext, c, BiquadState())
        ext.reverse()
        return ext.copyOfRange(pad, pad + n)
    }

    private fun runCausal(buf: FloatArray, c: BiquadCoeffs, s: BiquadState) {
        for (i in buf.indices) buf[i] = s.process(buf[i], c)
    }

    /** Q of a 2nd-order Butterworth section. */
    const val BUTTERWORTH_Q2 = 0.70710678f
    /** Section Qs of a 4th-order Butterworth split into two cascaded 2nd-order sections. */
    const val BUTTERWORTH_Q4_A = 0.54119610f
    const val BUTTERWORTH_Q4_B = 1.30656296f
}

/**
 * Causal, stateful twin of [PcgDisplayFilter.zeroPhase] for the LIVE recorder display:
 * per-buffer click removal, then a 4th-order Butterworth high-pass and low-pass (two
 * cascaded sections each) and the 50/100/150 Hz notches, with filter state carried across
 * buffers so there is no per-buffer transient. One instance per recording session — create
 * a fresh one at every start so state never leaks between sessions.
 */
class PcgLiveDisplayFilter(
    private val sampleRate: Float,
    private val params: PcgDisplayFilter.Params = PcgDisplayFilter.Params.LIVE
) {
    private val sections: List<BiquadCoeffs> = buildList {
        if (params.highPassHz > 0f) {
            if (params.highPassOrder >= 4) {
                add(BiquadCoeffs.highPass(params.highPassHz, sampleRate, PcgDisplayFilter.BUTTERWORTH_Q4_A))
                add(BiquadCoeffs.highPass(params.highPassHz, sampleRate, PcgDisplayFilter.BUTTERWORTH_Q4_B))
            } else {
                add(BiquadCoeffs.highPass(params.highPassHz, sampleRate, PcgDisplayFilter.BUTTERWORTH_Q2))
            }
        }
        if (params.lowPassHz > 0f && params.lowPassHz < sampleRate / 2f) {
            add(BiquadCoeffs.lowPass(params.lowPassHz, sampleRate, PcgDisplayFilter.BUTTERWORTH_Q4_A))
            add(BiquadCoeffs.lowPass(params.lowPassHz, sampleRate, PcgDisplayFilter.BUTTERWORTH_Q4_B))
        }
        for (f in params.notchHz) if (f > 0f && f < sampleRate / 2f) add(BiquadCoeffs.notch(f, sampleRate, params.notchQ))
    }
    private val states = List(sections.size) { BiquadState() }

    /** Returns a NEW array; the input buffer is left untouched. */
    fun process(buffer: FloatArray): FloatArray {
        val y = buffer.copyOf()
        if (params.removeClicks) PcgClickRemover.removeClicks(y, sampleRate)
        for (k in sections.indices) {
            val c = sections[k]
            val s = states[k]
            for (i in y.indices) y[i] = s.process(y[i], c)
        }
        return y
    }
}

/** RBJ Audio-EQ-Cookbook biquad coefficients, normalized so a0 = 1. */
class BiquadCoeffs(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float) {
    companion object {
        fun lowPass(freqHz: Float, sampleRate: Float, q: Float): BiquadCoeffs {
            val w0 = 2.0 * PI * freqHz / sampleRate
            val c = cos(w0); val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return BiquadCoeffs(
                (((1.0 - c) / 2.0) / a0).toFloat(), ((1.0 - c) / a0).toFloat(), (((1.0 - c) / 2.0) / a0).toFloat(),
                ((-2.0 * c) / a0).toFloat(), ((1.0 - alpha) / a0).toFloat()
            )
        }

        fun highPass(freqHz: Float, sampleRate: Float, q: Float): BiquadCoeffs {
            val w0 = 2.0 * PI * freqHz / sampleRate
            val c = cos(w0); val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return BiquadCoeffs(
                (((1.0 + c) / 2.0) / a0).toFloat(), ((-(1.0 + c)) / a0).toFloat(), (((1.0 + c) / 2.0) / a0).toFloat(),
                ((-2.0 * c) / a0).toFloat(), ((1.0 - alpha) / a0).toFloat()
            )
        }

        fun notch(freqHz: Float, sampleRate: Float, q: Float): BiquadCoeffs {
            val w0 = 2.0 * PI * freqHz / sampleRate
            val c = cos(w0); val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return BiquadCoeffs(
                (1.0 / a0).toFloat(), ((-2.0 * c) / a0).toFloat(), (1.0 / a0).toFloat(),
                ((-2.0 * c) / a0).toFloat(), ((1.0 - alpha) / a0).toFloat()
            )
        }
    }
}

/** Direct-form-I state in doubles (float state accumulates drift on long recordings). */
class BiquadState {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    fun process(x: Float, c: BiquadCoeffs): Float {
        val xd = x.toDouble()
        val y = c.b0 * xd + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1; x1 = xd; y2 = y1; y1 = y
        return y.toFloat()
    }
}

/**
 * Removes USB-dropout glitches: isolated single-hop amplitude outliers that draw as
 * maxed-out spikes but carry no audible sound (seen on the study Samsung). Works in 2 ms
 * hops: a hop is a click when its peak is ≥[RATIO]× the median peak of the surrounding
 * ±50 ms AND both immediate neighbours are below half of it (a real S1/S2 burst is 30–100 ms
 * wide and fails the isolation test), or when it is at digital full scale as part of a run
 * shorter than [MAX_CLIP_RUN_HOPS] (a genuinely clipped loud beat runs far longer and is
 * left alone). Flagged hops (with one hop of margin) are replaced by a straight line between
 * the surrounding samples. Operates in place; returns the number of hops replaced.
 */
object PcgClickRemover {
    const val HOP_SECONDS = 0.002f
    const val CONTEXT_SECONDS = 0.05f
    const val RATIO = 6f
    const val NEIGHBOR_RATIO = 0.5f
    const val ABS_CLIP = 0.98f
    const val MAX_CLIP_RUN_HOPS = 5
    private const val MIN_REFERENCE = 1e-5f

    fun removeClicks(x: FloatArray, sampleRate: Float): Int {
        val hop = max(1, (HOP_SECONDS * sampleRate).toInt())
        val n = x.size / hop
        if (n < 3) return 0
        val hopMax = FloatArray(n)
        for (h in 0 until n) {
            var m = 0f
            val start = h * hop
            for (i in start until start + hop) { val a = abs(x[i]); if (a > m) m = a }
            hopMax[h] = m
        }
        val ctx = max(1, (CONTEXT_SECONDS / HOP_SECONDS).toInt())
        val scratch = FloatArray(2 * ctx + 1)
        val flagged = BooleanArray(n)

        for (i in 0 until n) {
            var m = 0
            for (j in max(0, i - ctx) until min(n, i + ctx + 1)) if (j != i) scratch[m++] = hopMax[j]
            java.util.Arrays.sort(scratch, 0, m)
            val ref = if (m == 0) 0f else if (m % 2 == 1) scratch[m / 2] else (scratch[m / 2 - 1] + scratch[m / 2]) / 2f
            val v = hopMax[i]
            val isolated = v > RATIO * max(ref, MIN_REFERENCE) &&
                (i == 0 || hopMax[i - 1] < NEIGHBOR_RATIO * v) &&
                (i == n - 1 || hopMax[i + 1] < NEIGHBOR_RATIO * v)
            if (isolated) flagged[i] = true
        }
        // Full-scale runs: only short ones are glitches.
        var i = 0
        while (i < n) {
            if (hopMax[i] >= ABS_CLIP) {
                var j = i
                while (j + 1 < n && hopMax[j + 1] >= ABS_CLIP) j++
                if (j - i + 1 <= MAX_CLIP_RUN_HOPS) for (k in i..j) flagged[k] = true
                i = j + 1
            } else i++
        }

        var replacedHops = 0
        i = 0
        while (i < n) {
            if (!flagged[i]) { i++; continue }
            var j = i
            while (j + 1 < n && flagged[j + 1]) j++
            val a = max(0, (i - 1) * hop)
            val b = min(x.size, (j + 2) * hop)
            val va = if (a > 0) x[a - 1] else 0f
            val vb = if (b < x.size) x[b] else 0f
            val span = (b - a + 1).toFloat()
            for (k in a until b) x[k] = va + (vb - va) * ((k - a + 1) / span)
            replacedHops += j - i + 1
            i = j + 1
        }
        return replacedHops
    }
}
