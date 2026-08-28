package com.purnacardio.signal.pcg

/**
 * Result of cardiac cycle segmentation using the TCN model.
 *
 * Contains per-frame state labels at 200Hz plus detected S1/S2 event locations
 * converted to sample indices at the model's internal sample rate (2000Hz).
 *
 * For interval measurements against an ECG (EMAT, QS2), [s1OnsetSamples2k] and
 * [s2OnsetSamples2k] are the timing markers to use. See [S1Result] for the flat
 * per-event form of the same data.
 *
 * @property stateLabels Per-frame state labels at [featFs] Hz.
 *   Values: 0=S1, 1=systole, 2=S2, 3=diastole.
 * @property featFs Feature frame rate in Hz (200).
 * @property s1OnsetFrames Frame indices where S1 begins (state transitions to 0).
 * @property s2OnsetFrames Frame indices where S2 begins (state transitions to 2).
 * @property s1OnsetSamples2k S1 onset locations as sample indices at 2000Hz.
 * @property s2OnsetSamples2k S2 onset locations as sample indices at 2000Hz.
 * @property s1PeakSamples2k S1 envelope peak locations as sample indices at 2000Hz.
 * @property s2PeakSamples2k S2 envelope peak locations as sample indices at 2000Hz.
 * @property numCycles Number of complete cardiac cycles detected.
 * @property durationSec Duration of the recording in seconds.
 * @property confidencePerCycle Per-cycle softmax confidence (mean across frames).
 */
data class SegmentationResult(
    val stateLabels: IntArray,
    val featFs: Int,
    val s1OnsetFrames: List<Int>,
    val s2OnsetFrames: List<Int>,
    val s1OnsetSamples2k: List<Int>,
    val s2OnsetSamples2k: List<Int>,
    val s1PeakSamples2k: List<Int>,
    val s2PeakSamples2k: List<Int>,
    val numCycles: Int,
    val durationSec: Double,
    val confidencePerCycle: List<Float> = emptyList()
) {
    companion object {
        fun empty() = SegmentationResult(
            stateLabels = IntArray(0),
            featFs = 200,
            s1OnsetFrames = emptyList(),
            s2OnsetFrames = emptyList(),
            s1OnsetSamples2k = emptyList(),
            s2OnsetSamples2k = emptyList(),
            s1PeakSamples2k = emptyList(),
            s2PeakSamples2k = emptyList(),
            numCycles = 0,
            durationSec = 0.0
        )
    }

    /**
     * Flatten the S1 events into [S1Result] objects indexed against the *original*
     * recording, so callers do not have to do the 2 kHz → source-rate conversion.
     *
     * @param pcgSampleRate The original PCG sample rate (e.g. 44100 from `AudioRecord`).
     * @return List of [S1Result] with onset/peak timing from the segmenter.
     */
    fun toS1Results(pcgSampleRate: Double = 44100.0): List<S1Result> {
        val scaleFactor = pcgSampleRate / 2000.0  // Convert 2kHz indices to pcgSampleRate
        return s1OnsetFrames.indices.map { i ->
            val onsetSample2k = s1OnsetSamples2k.getOrElse(i) { s1OnsetFrames[i] * (2000 / featFs) }
            val peakSample2k = s1PeakSamples2k.getOrElse(i) { onsetSample2k }
            S1Result(
                onsetSampleIndex = (onsetSample2k * scaleFactor).toInt(),
                peakSampleIndex = (peakSample2k * scaleFactor).toInt(),
                peakAmplitude = 1.0,  // Not available from segmenter; placeholder
                onsetTimeSeconds = onsetSample2k / 2000.0,
                peakTimeSeconds = peakSample2k / 2000.0
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SegmentationResult) return false
        return stateLabels.contentEquals(other.stateLabels) &&
            featFs == other.featFs && numCycles == other.numCycles
    }

    override fun hashCode(): Int {
        return stateLabels.contentHashCode() * 31 + featFs * 31 + numCycles
    }
}
