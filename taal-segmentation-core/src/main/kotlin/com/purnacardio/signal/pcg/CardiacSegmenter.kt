package com.purnacardio.signal.pcg

/**
 * Cardiac cycle segmenter using TCN model (C-200 architecture, 87K params).
 *
 * Full 4-state segmentation (S1, systole, S2, diastole) with 5ms onset resolution
 * at a 200Hz frame rate.
 *
 * ## Architecture
 * - [PcgFeatureExtractor]: 12-channel feature extraction (pure Kotlin, no Android deps)
 * - ONNX Runtime: TCN inference (407KB model)
 * - [constrainedDecode]: Legal transition + minimum duration enforcement
 *
 * ## ONNX Integration
 * This class carries no Android dependency and holds no inference session, so it stays
 * JVM-unit-testable. Model loading and the forward pass belong to the caller. On Android,
 * use `TcnSegmenterRunner` from the `pcg-segmentation-android` module, which does exactly
 * that and nothing else. To host inference yourself (server-side, or another runtime),
 * call [extractFeatures], run the model, then [segmentWithLogits].
 *
 * @param featureExtractor The 12-channel feature extractor.
 */
class CardiacSegmenter(
    private val featureExtractor: PcgFeatureExtractor = PcgFeatureExtractor()
) {
    companion object {
        const val STATE_S1 = 0
        const val STATE_SYSTOLE = 1
        const val STATE_S2 = 2
        const val STATE_DIASTOLE = 3
        const val FEAT_FS = PcgFeatureExtractor.FEAT_FS  // 200
        const val NUM_CLASSES = 4
        private const val SPF = PcgFeatureExtractor.SPF  // 10

        private val LEGAL_NEXT = mapOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)
        private val MIN_FRAMES = mapOf(0 to 10, 1 to 16, 2 to 8, 3 to 20)
    }

    /**
     * Extract features from raw PCG audio.
     *
     * @param pcgAudio Raw PCG audio (any sample rate).
     * @param pcgSampleRate Sample rate of input (e.g. 44100 for TAAL).
     * @return 12-channel feature array at 200Hz, or null if too short/silent.
     *         Shape: Array(12) { FloatArray(nFrames) }
     */
    /** Resampled 2kHz audio from the most recent extractFeatures call.
     *  Retained so downstream DSP (e.g. Hilbert S1 peak detection) can use it
     *  without resampling again. Null until extractFeatures is called. */
    var lastAudio2k: FloatArray? = null
        private set

    fun extractFeatures(pcgAudio: FloatArray, pcgSampleRate: Int = 44100): Array<FloatArray>? {
        val audio2k = featureExtractor.resample(pcgAudio, pcgSampleRate, PcgFeatureExtractor.TARGET_SR)
        lastAudio2k = audio2k
        return featureExtractor.extract(audio2k)
    }

    /**
     * Decode ONNX logits into a [SegmentationResult].
     *
     * Call this after running ONNX inference externally:
     * ```kotlin
     * val features = segmenter.extractFeatures(pcgAudio)
     * val logits = onnxSession.run(features)  // shape: (4, T)
     * val result = segmenter.segmentWithLogits(logits, features, pcgAudio.size / 44100.0)
     * ```
     *
     * @param logits Model output, shape Array(4) { FloatArray(T) }
     * @param features The 12-channel features (for peak detection in envelopes)
     * @param durationSec Recording duration in seconds
     * @return Full segmentation result
     */
    fun segmentWithLogits(
        logits: Array<FloatArray>,
        features: Array<FloatArray>,
        durationSec: Double
    ): SegmentationResult {
        val seqLength = logits[0].size
        val decoded = constrainedDecode(logits, seqLength)

        val s1Onsets = findStateOnsets(decoded, STATE_S1)
        val s2Onsets = findStateOnsets(decoded, STATE_S2)

        val envLow = features[0]  // 20-50Hz = S1 band
        val envMid = features[1]  // 50-100Hz = S2 band

        val s1Peaks = s1Onsets.map { onset ->
            val end = findStateEnd(decoded, onset, STATE_S1)
            findPeakInRange(envLow, onset, end) * SPF
        }
        val s2Peaks = s2Onsets.map { onset ->
            val end = findStateEnd(decoded, onset, STATE_S2)
            findPeakInRange(envMid, onset, end) * SPF
        }

        return SegmentationResult(
            stateLabels = decoded,
            featFs = FEAT_FS,
            s1OnsetFrames = s1Onsets,
            s2OnsetFrames = s2Onsets,
            s1OnsetSamples2k = s1Onsets.map { it * SPF },
            s2OnsetSamples2k = s2Onsets.map { it * SPF },
            s1PeakSamples2k = s1Peaks,
            s2PeakSamples2k = s2Peaks,
            numCycles = minOf(s1Onsets.size, s2Onsets.size),
            durationSec = durationSec
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // DECODE
    // ═══════════════════════════════════════════════════════════════

    /** Argmax → legal transitions → minimum duration. */
    internal fun constrainedDecode(logits: Array<FloatArray>, seqLength: Int): IntArray {
        // Argmax
        val raw = IntArray(seqLength) { t ->
            var maxVal = Float.NEGATIVE_INFINITY; var maxIdx = 0
            for (c in 0 until NUM_CLASSES) {
                if (logits[c][t] > maxVal) { maxVal = logits[c][t]; maxIdx = c }
            }
            maxIdx
        }

        // Legal transitions
        val cleaned = IntArray(seqLength)
        cleaned[0] = raw[0]
        for (t in 1 until seqLength) {
            cleaned[t] = if (raw[t] == cleaned[t - 1] || raw[t] == LEGAL_NEXT[cleaned[t - 1]]) {
                raw[t]
            } else {
                cleaned[t - 1]
            }
        }

        // Minimum duration
        val result = cleaned.copyOf()
        var segStart = 0
        for (t in 1..seqLength) {
            if (t == seqLength || result[t] != result[t - 1]) {
                val segLen = t - segStart
                val minDur = MIN_FRAMES[result[segStart]] ?: 10
                if (segLen < minDur && segStart > 0) {
                    val prevSt = result[segStart - 1]
                    for (f in segStart until t) result[f] = prevSt
                }
                if (t < seqLength) segStart = t
            }
        }
        return result
    }

    private fun findStateOnsets(decoded: IntArray, targetState: Int): List<Int> {
        val onsets = mutableListOf<Int>()
        for (t in 1 until decoded.size) {
            if (decoded[t] == targetState && decoded[t - 1] != targetState) onsets.add(t)
        }
        return onsets
    }

    private fun findStateEnd(decoded: IntArray, onset: Int, state: Int): Int {
        for (t in onset + 1 until decoded.size) if (decoded[t] != state) return t
        return decoded.size
    }

    private fun findPeakInRange(envelope: FloatArray, start: Int, end: Int): Int {
        var peakIdx = start; var peakVal = Float.NEGATIVE_INFINITY
        for (t in start until minOf(end, envelope.size)) {
            if (envelope[t] > peakVal) { peakVal = envelope[t]; peakIdx = t }
        }
        return peakIdx
    }
}
