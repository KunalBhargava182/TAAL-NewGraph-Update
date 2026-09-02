package com.musediagnostics.taal.dsp

import kotlin.math.*

class AudioFilterEngine(private val sampleRate: Int = 44100) {

    // Preset filter definitions
    enum class PresetFilter(val lowCut: Double, val highCut: Double) {
        HEART(20.0, 250.0), LUNGS(100.0, 600.0), BOWEL(100.0, 600.0), // Same as lungs clinically
        PREGNANCY(20.0, 250.0), // Same as heart
        FULL_BODY(20.0, 600.0), NONE(20.0, 2000.0) // Full range but respect hard limit
    }

    // 5-band graphic EQ state
    data class GraphicEQState(
        val band20Hz: Float = 0f,   // -12dB to +12dB
        val band50Hz: Float = 0f,
        val band100Hz: Float = 0f,
        val band200Hz: Float = 0f,
        val band600Hz: Float = 0f
    )

    private var currentPreset: PresetFilter = PresetFilter.NONE
    private var eqState = GraphicEQState()
    private var preAmpGainDb: Float = 0f // 0-10dB
    private var humRumbleFilterEnabled: Boolean = false

    // Biquad filter coefficients (stored for efficiency)
    private data class BiquadCoeffs(
        var b0: Double = 0.0,
        var b1: Double = 0.0,
        var b2: Double = 0.0,
        var a1: Double = 0.0,
        var a2: Double = 0.0
    )

    private data class BiquadState(
        var x1: Double = 0.0, var x2: Double = 0.0, var y1: Double = 0.0, var y2: Double = 0.0
    )

    private val bandpassState = BiquadState()
    private val bandpassCoeffs = BiquadCoeffs()

    // Opt-in stage inserted after bandpass/EQ, before tanh — see setHumRumbleFilterEnabled().
    // Two cascaded sections form a 4th-order Butterworth high-pass at 25Hz (fixed section Qs
    // 0.5412/1.3066), then three RBJ-cookbook notches at 50/100/150Hz, Q=30. All fixed
    // frequencies relative to this engine's sampleRate, so coefficients are designed once at
    // construction, not on every updateFilters() call (unlike bandpass/EQ, which move with
    // user-selected preset/gain).
    private val humHpState1 = BiquadState()
    private val humHpCoeffs1 = BiquadCoeffs()
    private val humHpState2 = BiquadState()
    private val humHpCoeffs2 = BiquadCoeffs()
    private val humNotchState50 = BiquadState()
    private val humNotchCoeffs50 = BiquadCoeffs()
    private val humNotchState100 = BiquadState()
    private val humNotchCoeffs100 = BiquadCoeffs()
    private val humNotchState150 = BiquadState()
    private val humNotchCoeffs150 = BiquadCoeffs()

    private val eqBands = mapOf(
        20 to BiquadState(),
        50 to BiquadState(),
        100 to BiquadState(),
        200 to BiquadState(),
        600 to BiquadState()
    )

    private val eqCoeffs = mutableMapOf(
        20 to BiquadCoeffs(),
        50 to BiquadCoeffs(),
        100 to BiquadCoeffs(),
        200 to BiquadCoeffs(),
        600 to BiquadCoeffs()
    )

    init {
        updateFilters()
        designHumRumbleFilters()
    }

    fun setPresetFilter(preset: PresetFilter) {
        currentPreset = preset
        updateFilters()
    }

    /**
     * Opt-in hum/rumble stage — 4th-order 25Hz Butterworth HPF + 50/100/150Hz notches (Q=30),
     * inserted after bandpass/EQ, before tanh. Default false: zero behavioral change for every
     * existing consumer (production app, lungs-app, stemz, visualizer) until a caller opts in.
     * NOTE: when enabled, the filtered WAV (and therefore any saved recording) includes this
     * filtering — that is intended. If the device's true capture rate is 48kHz mislabeled as
     * 44.1kHz (open taal-core issue — see TaalAudioCapture.SAMPLE_RATE), the notch centers land
     * ~8.8% high; acceptable at Q=30 for now, revisit after the sample-rate root-cause task.
     */
    fun setHumRumbleFilterEnabled(enabled: Boolean) {
        humRumbleFilterEnabled = enabled
    }

    fun setCustomBandpass(lowCut: Double, highCut: Double) {
        val lo = lowCut.coerceAtLeast(1.0)
        val hi = highCut.coerceAtMost(24000.0)
        if (hi <= lo) return  // invalid range — bandwidth would be 0 → Q = ∞ → NaN coefficients
        designBandpass(bandpassCoeffs, lo, hi, sampleRate.toDouble())
    }

    fun setGraphicEQ(state: GraphicEQState) {
        eqState = state
        updateFilters()
    }

    fun setPreAmplification(gainDb: Float) {
        preAmpGainDb = gainDb.coerceIn(0f, 30f)

    }

    private fun updateFilters() {
        // Update bandpass coefficients
        designBandpass(
            bandpassCoeffs, currentPreset.lowCut, currentPreset.highCut, sampleRate.toDouble()
        )

        // Update graphic EQ peaking filters
        updatePeakingFilter(eqCoeffs[20]!!, 20.0, eqState.band20Hz)
        updatePeakingFilter(eqCoeffs[50]!!, 50.0, eqState.band50Hz)
        updatePeakingFilter(eqCoeffs[100]!!, 100.0, eqState.band100Hz)
        updatePeakingFilter(eqCoeffs[200]!!, 200.0, eqState.band200Hz)
        updatePeakingFilter(eqCoeffs[600]!!, 600.0, eqState.band600Hz)
    }

    private fun designBandpass(
        coeffs: BiquadCoeffs, lowCut: Double, highCut: Double, fs: Double
    ) {
        // Butterworth bandpass (2nd order)
        val centerFreq = sqrt(lowCut * highCut)
        val bandwidth = highCut - lowCut
        val Q = centerFreq / bandwidth

        val omega = 2.0 * PI * centerFreq / fs
        val cosOmega = cos(omega)
        val sinOmega = sin(omega)
        val alpha = sinOmega / (2.0 * Q)

        val a0 = 1.0 + alpha

        coeffs.b0 = alpha / a0
        coeffs.b1 = 0.0
        coeffs.b2 = -alpha / a0
        coeffs.a1 = -2.0 * cosOmega / a0
        coeffs.a2 = (1.0 - alpha) / a0
    }

    private fun designHumRumbleFilters() {
        val fs = sampleRate.toDouble()
        designHighPassSection(humHpCoeffs1, 25.0, 0.5412, fs)
        designHighPassSection(humHpCoeffs2, 25.0, 1.3066, fs)
        designNotch(humNotchCoeffs50, 50.0, 30.0, fs)
        designNotch(humNotchCoeffs100, 100.0, 30.0, fs)
        designNotch(humNotchCoeffs150, 150.0, 30.0, fs)
    }

    /** RBJ cookbook high-pass biquad section. */
    private fun designHighPassSection(coeffs: BiquadCoeffs, cutoffHz: Double, q: Double, fs: Double) {
        val omega = 2.0 * PI * cutoffHz / fs
        val cosOmega = cos(omega)
        val sinOmega = sin(omega)
        val alpha = sinOmega / (2.0 * q)
        val a0 = 1.0 + alpha

        coeffs.b0 = ((1.0 + cosOmega) / 2.0) / a0
        coeffs.b1 = (-(1.0 + cosOmega)) / a0
        coeffs.b2 = ((1.0 + cosOmega) / 2.0) / a0
        coeffs.a1 = (-2.0 * cosOmega) / a0
        coeffs.a2 = (1.0 - alpha) / a0
    }

    /** RBJ cookbook notch biquad. */
    private fun designNotch(coeffs: BiquadCoeffs, freqHz: Double, q: Double, fs: Double) {
        val omega = 2.0 * PI * freqHz / fs
        val cosOmega = cos(omega)
        val sinOmega = sin(omega)
        val alpha = sinOmega / (2.0 * q)
        val a0 = 1.0 + alpha

        coeffs.b0 = 1.0 / a0
        coeffs.b1 = (-2.0 * cosOmega) / a0
        coeffs.b2 = 1.0 / a0
        coeffs.a1 = (-2.0 * cosOmega) / a0
        coeffs.a2 = (1.0 - alpha) / a0
    }

    private fun updatePeakingFilter(
        coeffs: BiquadCoeffs, centerFreq: Double, gainDb: Float
    ) {
        if (abs(gainDb) < 0.01f) {
            // Bypass filter (unity gain)
            coeffs.b0 = 1.0
            coeffs.b1 = 0.0
            coeffs.b2 = 0.0
            coeffs.a1 = 0.0
            coeffs.a2 = 0.0
            return
        }

        val A = 10.0.pow(gainDb / 40.0) // Gain as amplitude
        val omega = 2.0 * PI * centerFreq / sampleRate
        val cosOmega = cos(omega)
        val sinOmega = sin(omega)
        val Q = 1.0 // Bandwidth factor
        val alpha = sinOmega / (2.0 * Q)

        val a0 = 1.0 + alpha / A

        coeffs.b0 = (1.0 + alpha * A) / a0
        coeffs.b1 = (-2.0 * cosOmega) / a0
        coeffs.b2 = (1.0 - alpha * A) / a0
        coeffs.a1 = (-2.0 * cosOmega) / a0
        coeffs.a2 = (1.0 - alpha / A) / a0
    }

    fun processBlock(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)

        // Apply pre-amplification
        val preAmpGain = 10.0.pow(preAmpGainDb / 20.0).toFloat()

        for (i in input.indices) {
            var sample = input[i].toDouble() * preAmpGain

            // Apply bandpass filter
            sample = processBiquad(sample, bandpassCoeffs, bandpassState)

            // Apply graphic EQ (cascade peaking filters)
            for ((freq, state) in eqBands) {
                sample = processBiquad(sample, eqCoeffs[freq]!!, state)
            }

            // Opt-in hum/rumble stage (default off — see setHumRumbleFilterEnabled doc).
            if (humRumbleFilterEnabled) {
                sample = processBiquad(sample, humHpCoeffs1, humHpState1)
                sample = processBiquad(sample, humHpCoeffs2, humHpState2)
                sample = processBiquad(sample, humNotchCoeffs50, humNotchState50)
                sample = processBiquad(sample, humNotchCoeffs100, humNotchState100)
                sample = processBiquad(sample, humNotchCoeffs150, humNotchState150)
            }

            // Hard limit at 2000Hz is built into filter design
            // Additional safety: clip to prevent overflow
            // output[i] = sample.toFloat().coerceIn(-1f, 1f)
            output[i] = tanh(sample).toFloat()

        }

        return output
    }

    private fun processBiquad(
        input: Double, coeffs: BiquadCoeffs, state: BiquadState
    ): Double {
        val output =
            coeffs.b0 * input + coeffs.b1 * state.x1 + coeffs.b2 * state.x2 - coeffs.a1 * state.y1 - coeffs.a2 * state.y2

        // Update state
        state.x2 = state.x1
        state.x1 = input
        state.y2 = state.y1
        state.y1 = output

        return output
    }
}
