package com.musediagnostics.taal.dsp

/**
 * HeartResampler
 *
 * Converts a continuous stream of 44100 Hz audio into 8000 Hz audio for offline
 * AI model testing. This class is STRICTLY ISOLATED from the main recording and
 * playback pipeline — it has no side-effects on TaalRecorder or TaalPlayer, and
 * neither of those classes references it.
 *
 * ── Processing chain (per call to process()) ─────────────────────────────────
 *
 *   [44100 Hz FloatArray from onProgressUpdate]
 *       │
 *       ▼
 *   Anti-alias filter  →  HEART bandpass (Butterworth 2nd-order, 20–250 Hz)
 *       │
 *       │   Why HEART as anti-alias?
 *       │   The Nyquist theorem requires removing all frequencies above
 *       │   (outputRate / 2) = 4000 Hz before downsampling to 8000 Hz.
 *       │   The HEART bandpass is already limited to 250 Hz — far below the
 *       │   4000 Hz threshold — so it acts as a conservative anti-aliasing guard
 *       │   and simultaneously isolates the clinically relevant heart sounds the
 *       │   AI model was trained on.
 *       │
 *       ▼
 *   Linear interpolation downsample  →  ratio = 44100 / 8000 = 5.5125
 *       │
 *       │   Why linear interpolation, not simple decimation?
 *       │   5.5125 is non-integer. Decimation (keep every Nth sample) only works
 *       │   for integer ratios. Linear interpolation computes a weighted blend of
 *       │   two adjacent input samples for each output position, handling
 *       │   fractional offsets correctly. State is preserved across block
 *       │   boundaries so the stream is seamless.
 *       │
 *       ▼
 *   [8000 Hz FloatArray]  →  caller writes to 16-bit PCM WAV
 *
 * ── Thread safety ─────────────────────────────────────────────────────────────
 * This class is NOT thread-safe. All calls to process() and reset() must come
 * from the same thread (the audio IO callback thread in RecordingFragment).
 */
class HeartResampler {

    companion object {
        /** Input sample rate — must match TaalAudioCapture.SAMPLE_RATE */
        const val INPUT_SAMPLE_RATE = 44100

        /** Target output sample rate for AI model consumption */
        const val OUTPUT_SAMPLE_RATE = 8000

        /**
         * Downsampling ratio: 44100 / 8000 = 5.5125
         *
         * For every output sample we advance 5.5125 positions through the
         * input buffer. The non-integer fraction (.5125) is why we must use
         * interpolation and carry fractional state across block boundaries.
         */
        const val DOWNSAMPLE_RATIO = INPUT_SAMPLE_RATE.toDouble() / OUTPUT_SAMPLE_RATE.toDouble()
    }

    /**
     * Anti-aliasing filter — HEART bandpass (20–250 Hz, 0 dB pre-amp).
     *
     * Recreated on each call to reset() so that the biquad state registers
     * (x1, x2, y1, y2) are wiped clean between recordings.  Reusing a
     * "warm" filter from a previous recording would produce a transient
     * artifact at the start of the new file as the filter ring-down decays.
     *
     * Pre-amplification is intentionally left at 0 dB: the AI model receives
     * normalized float samples and gain staging is irrelevant here.
     */
    private var antiAliasFilter = createFreshFilter()

    /**
     * Fractional sample position — the key to seamless streaming interpolation.
     *
     * Between calls to process(), the next output sample may need input samples
     * that straddle the boundary of the current block and the next.  This field
     * tracks our exact position in the input stream so each call resumes exactly
     * where the previous one stopped.
     *
     * Invariant: 0.0 ≤ fractionalPos < DOWNSAMPLE_RATIO  (enforced at block end)
     */
    private var fractionalPos = 0.0

    /**
     * Apply anti-alias filter and downsample from 44100 Hz to 8000 Hz.
     *
     * ── Step-by-step math ──────────────────────────────────────────────────
     *
     * Given a block of N input samples starting at fractional position P:
     *
     *   1. Run the block through the HEART bandpass filter (anti-aliasing).
     *
     *   2. For each output sample at position P:
     *        i0   = floor(P)           — left neighbour index
     *        i1   = i0 + 1             — right neighbour index
     *        frac = P - i0             — fractional offset in [0.0, 1.0)
     *        out  = in[i0] * (1 - frac) + in[i1] * frac
     *
     *   3. Advance P by DOWNSAMPLE_RATIO (5.5125) for the next output sample.
     *
     *   4. When P ≥ N-1 (right neighbour would be out of bounds), stop.
     *      Carry the remaining fractional offset into the next call:
     *        fractionalPos = P - N
     *
     * ── Edge case: very short buffers ──────────────────────────────────────
     * If the block is smaller than the current fractionalPos (can happen if
     * the audio driver returns a tiny buffer), this method returns an empty
     * array and advances fractionalPos accordingly.
     *
     * @param inputData 44100 Hz samples in range [-1.0, 1.0].
     *                  Typically the FloatArray delivered by onProgressUpdate.
     * @return 8000 Hz samples. Size ≈ inputData.size / 5.5125 (varies by ±1
     *         sample due to fractional carry-over between calls).
     */
    fun process(inputData: FloatArray): FloatArray {
        if (inputData.isEmpty()) return FloatArray(0)

        // ── Step 1: Anti-alias filter ──────────────────────────────────────
        // Apply HEART bandpass to the full 44100 Hz block.
        // All content above 250 Hz is attenuated before downsampling —
        // well below the 4000 Hz Nyquist limit for 8000 Hz output.
        val filtered = antiAliasFilter.processBlock(inputData)

        // ── Step 2: Allocate output buffer ─────────────────────────────────
        // Worst-case size: all remaining positions fit at least one output
        // sample.  We trim to the actual count with copyOf() at the end.
        val maxOutputSamples = ((filtered.size - fractionalPos) / DOWNSAMPLE_RATIO).toInt() + 1
        val output = FloatArray(maxOutputSamples.coerceAtLeast(0))
        var outIdx = 0

        // ── Step 3: Linear interpolation ──────────────────────────────────
        // Walk the filtered input at step DOWNSAMPLE_RATIO from fractionalPos.
        // Stop one sample before the end so i1 always has a valid right neighbour.
        while (fractionalPos < filtered.size - 1) {
            val i0 = fractionalPos.toInt()   // left neighbour (floor)
            val i1 = i0 + 1                  // right neighbour (ceil)
            // How far between i0 and i1 we are: range [0.0, 1.0)
            val frac = fractionalPos - i0

            // Weighted blend — exact linear interpolation between neighbours.
            // When frac = 0.0  → output = filtered[i0]  (exact left sample)
            // When frac = 0.99 → output ≈ filtered[i1]  (approaching right)
            output[outIdx++] = (filtered[i0] * (1.0 - frac) + filtered[i1] * frac).toFloat()

            // Advance by the downsample ratio for the next output position
            fractionalPos += DOWNSAMPLE_RATIO
        }

        // ── Step 4: Carry forward the fractional position ──────────────────
        // Subtract the length of the consumed block so fractionalPos represents
        // the offset into the *next* block.  Without this, each call would
        // restart at 0.0 and introduce a phase discontinuity (~93 ms gaps).
        fractionalPos -= filtered.size

        // Guard: floating-point subtraction can produce tiny negatives (e.g. -1e-14)
        // due to rounding.  A negative position would skip the first sample of the
        // next block, so clamp to zero.
        if (fractionalPos < 0.0) fractionalPos = 0.0

        return output.copyOf(outIdx)
    }

    /**
     * Reset all internal state between recordings.
     *
     * Must be called before each new recording session:
     *  • Clears fractionalPos so interpolation starts fresh at position 0.0.
     *  • Recreates the AudioFilterEngine instance to wipe biquad state registers
     *    (x1, x2, y1, y2).  Stale register values from a previous session would
     *    appear as a brief transient at the start of the new AI file.
     */
    fun reset() {
        fractionalPos = 0.0
        // AudioFilterEngine exposes no reset() method, so we discard the instance
        // entirely and create a fresh one.  The old instance is garbage-collected.
        antiAliasFilter = createFreshFilter()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun createFreshFilter(): AudioFilterEngine {
        return AudioFilterEngine().apply {
            // HEART: Butterworth bandpass 20–250 Hz at 44100 Hz sample rate.
            // This preset is identical to TaalRecorder's HEART preset, ensuring
            // the AI file contains the same frequency band the model expects.
            setPresetFilter(AudioFilterEngine.PresetFilter.HEART)
            // Pre-amp stays at 0 dB (default) — no gain needed for AI pipeline.
        }
    }
}
