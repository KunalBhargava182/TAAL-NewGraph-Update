package com.purnacardio.signal.pcg

/**
 * One S1 event, expressed against the *original* recording rather than the model's
 * internal 2 kHz rate.
 *
 * This is a plain timing DTO — it carries no detection logic. It exists because callers
 * measuring intervals against an ECG (EMAT = Q→S1, QS2 = Q→S2) want sample indices in the
 * rate they recorded at, not in the segmenter's resampled domain. Produced by
 * [SegmentationResult.toS1Results].
 *
 * @property onsetSampleIndex S1 onset, as a sample index at the original recording rate.
 * @property peakSampleIndex S1 envelope peak, as a sample index at the original rate.
 * @property peakAmplitude Envelope amplitude at the peak. The segmenter does not measure
 *   this, so it is always 1.0 on results built by [SegmentationResult.toS1Results] — do
 *   not read it as a signal-strength figure.
 * @property onsetTimeSeconds S1 onset in seconds from the start of the recording.
 * @property peakTimeSeconds S1 peak in seconds from the start of the recording.
 */
data class S1Result(
    val onsetSampleIndex: Int,
    val peakSampleIndex: Int,
    val peakAmplitude: Double,
    val onsetTimeSeconds: Double,
    val peakTimeSeconds: Double
)
