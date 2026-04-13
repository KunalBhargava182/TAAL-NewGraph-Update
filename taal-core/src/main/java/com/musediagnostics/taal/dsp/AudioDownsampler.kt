package com.musediagnostics.taal.dsp

/**
 * AudioDownsampler
 *
 * Generic streaming downsampler: converts a continuous 44100 Hz audio stream
 * to any target sample rate below 44100 Hz.  Used by the AI testing pipeline
 * in RecordingFragment to produce multiple clinical-band WAV files for
 * offline AI model training and evaluation.
 *
 * This class supersedes the original HeartResampler, which was hardcoded to
 * 8000 Hz with the HEART bandpass.  AudioDownsampler supports any output rate
 * and any AudioFilterEngine preset, making it suitable for both HEART and
 * LUNGS downsampling pipelines.
 *
 * ── Processing chain (per call to process()) ──────────────────────────────
 *
 *   [44100 Hz FloatArray — raw, from onRawProgressUpdate]
 *       │
 *       ▼
 *   Anti-alias filter  →  preset bandpass (Butterworth 2nd-order)
 *       │
 *       │   Purpose: remove all energy above (outputSampleRate / 2) — the
 *       │   Nyquist limit — BEFORE downsampling.  Without this step, high-
 *       │   frequency content would fold back (alias) into the passband and
 *       │   corrupt the downsampled signal.
 *       │
 *       │   The preset bandpass also restricts the output to the clinically
 *       │   relevant frequency band, so the AI model receives only the
 *       │   content it was designed to analyse:
 *       │     HEART  → 20–250 Hz   (S1/S2 heart sounds)
 *       │     LUNGS  → 100–600 Hz  (breath sounds, crackles, wheezes)
 *       │
 *       ▼
 *   Linear interpolation downsample  →  ratio = 44100 / outputSampleRate
 *       │
 *       │   All supported ratios are non-integer (e.g. 44100/500 = 88.2),
 *       │   so simple decimation (keep every Nth sample) cannot be used —
 *       │   it only works for integer ratios.
 *       │
 *       │   Linear interpolation computes a weighted blend of two adjacent
 *       │   input samples for each output position, handling fractional
 *       │   offsets exactly.  The fractional state is carried across block
 *       │   boundaries so the stream is seamless regardless of how the
 *       │   audio driver chunks the data.
 *       │
 *       ▼
 *   [outputSampleRate Hz FloatArray]  →  caller converts to 16-bit PCM WAV
 *
 * ── Nyquist safety per supported rate ────────────────────────────────────
 *
 *   HEART preset (bandpass 20–250 Hz):
 *     500 Hz  → ratio  88.2  — Nyquist = 250 Hz = filter upper cutoff.
 *               Borderline: the filter's -3 dB point is exactly at Nyquist.
 *               Safe in practice because S1/S2 energy peaks at 20–150 Hz,
 *               well below the 250 Hz boundary.
 *    1000 Hz  → ratio  44.1  — Nyquist = 500 Hz  > filter cutoff 250 Hz  ✓
 *    4000 Hz  → ratio  11.025 — Nyquist = 2000 Hz > filter cutoff 250 Hz  ✓
 *    8000 Hz  → ratio   5.5125 — Nyquist = 4000 Hz > filter cutoff 250 Hz  ✓
 *
 *   LUNGS preset (bandpass 100–600 Hz):
 *    2000 Hz  → ratio  22.05  — Nyquist = 1000 Hz > filter cutoff 600 Hz  ✓
 *    3000 Hz  → ratio  14.7   — Nyquist = 1500 Hz > filter cutoff 600 Hz  ✓
 *    4000 Hz  → ratio  11.025 — Nyquist = 2000 Hz > filter cutoff 600 Hz  ✓
 *    8000 Hz  → ratio   5.5125 — Nyquist = 4000 Hz > filter cutoff 600 Hz  ✓
 *
 * ── Thread safety ─────────────────────────────────────────────────────────
 * NOT thread-safe.  All calls to process() and reset() must originate from
 * the same thread (the audio IO callback thread in RecordingFragment).
 */
class AudioDownsampler(
    /**
     * Target output sample rate in Hz.
     * Supported values: 500, 1000, 2000, 3000, 4000, 8000.
     * Must be less than INPUT_SAMPLE_RATE (44100).
     */
    val outputSampleRate: Int,

    /**
     * Preset bandpass used as the anti-alias filter.
     * Should match the clinical context of the recording:
     *   HEART  → use for heart-sound AI models
     *   LUNGS  → use for lung-sound AI models
     */
    private val filterPreset: AudioFilterEngine.PresetFilter
) {

    companion object {
        /**
         * Input sample rate — must match TaalAudioCapture.SAMPLE_RATE (44100).
         * All downsampling ratios are computed relative to this constant.
         */
        const val INPUT_SAMPLE_RATE = 44100
    }

    /**
     * Downsampling ratio: INPUT_SAMPLE_RATE / outputSampleRate.
     *
     * For every output sample we advance exactly this many positions through
     * the filtered input buffer.  The fractional part (.2, .1, .05, etc.) is
     * why interpolation is required and why fractionalPos must be carried
     * across block boundaries.
     */
    private val downsampleRatio: Double =
        INPUT_SAMPLE_RATE.toDouble() / outputSampleRate.toDouble()

    /**
     * Anti-alias filter using the specified preset at 0 dB pre-amp.
     *
     * The filter is recreated on each reset() call so that biquad state
     * registers (x1, x2, y1, y2) are wiped clean between recordings.
     * Reusing a warm filter from a previous recording would produce a
     * transient artifact at the start of the new file as the ring-down
     * decays.
     *
     * Pre-amp is left at 0 dB: the AI model receives normalised float
     * samples and gain staging is not relevant at this stage.
     */
    private var antiAliasFilter = createFreshFilter()

    /**
     * Fractional sample position — the key to seamless streaming.
     *
     * Tracks our exact position within the input stream between calls to
     * process().  When a block ends mid-sample (which happens every call
     * because the ratio is non-integer), this field stores the remaining
     * offset so the next call resumes at the correct sub-sample position
     * rather than restarting at 0.0.
     *
     * Without this carry-over, each block would produce a phase
     * discontinuity roughly every (bufferSize / downsampleRatio) output
     * samples — audible as clicking artefacts.
     *
     * Invariant: 0.0 ≤ fractionalPos < downsampleRatio
     */
    private var fractionalPos = 0.0

    /**
     * Apply anti-alias filter and downsample from 44100 Hz to outputSampleRate.
     *
     * ── Step-by-step math ─────────────────────────────────────────────────
     *
     *   Given a block of N filtered input samples starting at position P:
     *
     *     1. Run the block through the preset bandpass (anti-aliasing).
     *
     *     2. For each output sample at position P:
     *          i0   = floor(P)          — left neighbour
     *          i1   = i0 + 1            — right neighbour
     *          frac = P - i0            — fractional offset [0.0, 1.0)
     *          out  = in[i0]*(1-frac) + in[i1]*frac
     *
     *     3. Advance P by downsampleRatio for the next output sample.
     *
     *     4. When P ≥ N-1 (right neighbour out of bounds), stop.
     *        Carry remaining offset into the next call:
     *          fractionalPos = P - N
     *
     * @param inputData  44100 Hz raw samples in [-1.0, 1.0].
     *                   Typically the FloatArray from onRawProgressUpdate.
     * @return           Downsampled FloatArray at outputSampleRate.
     *                   Size ≈ inputData.size / downsampleRatio (±1 sample
     *                   due to fractional carry-over between calls).
     */
    fun process(inputData: FloatArray): FloatArray {
        if (inputData.isEmpty()) return FloatArray(0)

        // ── Step 1: Anti-alias filter ──────────────────────────────────────
        // Apply the preset bandpass to the full 44100 Hz block before
        // downsampling.  All content above the clinical band is attenuated,
        // ensuring it cannot alias into the output.
        val filtered = antiAliasFilter.processBlock(inputData)

        // ── Step 2: Allocate output buffer ─────────────────────────────────
        // Worst-case size: all remaining positions produce at least one output
        // sample.  Trimmed to the actual count with copyOf() at the end.
        val maxOutputSamples =
            ((filtered.size - fractionalPos) / downsampleRatio).toInt() + 1
        val output = FloatArray(maxOutputSamples.coerceAtLeast(0))
        var outIdx = 0

        // ── Step 3: Linear interpolation walk ─────────────────────────────
        // Advance through the filtered input at step downsampleRatio.
        // Stop one sample before the end so i1 always has a valid right
        // neighbour (prevents ArrayIndexOutOfBoundsException on the last sample).
        while (fractionalPos < filtered.size - 1) {
            val i0 = fractionalPos.toInt()   // left neighbour (floor)
            val i1 = i0 + 1                  // right neighbour
            val frac = fractionalPos - i0    // fractional offset in [0.0, 1.0)

            // Weighted blend — exact linear interpolation.
            // frac=0.0  → pure left sample; frac→1.0 → approaches right sample.
            output[outIdx++] =
                (filtered[i0] * (1.0 - frac) + filtered[i1] * frac).toFloat()

            fractionalPos += downsampleRatio
        }

        // ── Step 4: Carry fractional position into the next block ──────────
        // Subtract the consumed block length so fractionalPos represents an
        // offset into the *next* block.  Without this, each call would restart
        // at 0.0, introducing a phase discontinuity at every block boundary.
        fractionalPos -= filtered.size

        // Guard: floating-point subtraction can produce tiny negatives (e.g.
        // -1e-14) due to rounding.  A negative position would skip the first
        // sample of the next block, so clamp to zero.
        if (fractionalPos < 0.0) fractionalPos = 0.0

        return output.copyOf(outIdx)
    }

    /**
     * Reset all internal state between recordings.
     *
     * Must be called before each new recording session:
     *   - Clears fractionalPos so interpolation starts fresh at position 0.0.
     *   - Recreates the AudioFilterEngine instance to wipe biquad state
     *     registers (x1, x2, y1, y2).  Stale register values from a previous
     *     session would appear as a brief transient at the start of the next
     *     AI file.
     */
    fun reset() {
        fractionalPos = 0.0
        // AudioFilterEngine has no reset() method, so we discard the instance
        // and create a fresh one.  The old instance is garbage-collected.
        antiAliasFilter = createFreshFilter()
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun createFreshFilter(): AudioFilterEngine {
        return AudioFilterEngine().apply {
            setPresetFilter(filterPreset)
            // Pre-amp intentionally stays at 0 dB (default).
        }
    }
}
