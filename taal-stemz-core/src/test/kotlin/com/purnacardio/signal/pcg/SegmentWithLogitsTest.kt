package com.purnacardio.signal.pcg

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The frame → sample arithmetic, end to end, with the network replaced by known logits.
 *
 * Every index the caller ultimately measures against an ECG comes out of this function, and each
 * one crosses a rate boundary: 200 Hz frames become 2 kHz samples become source-rate samples.
 * An off-by-one here is a 5 ms bias in EMAT that nothing downstream would flag.
 */
class SegmentWithLogitsTest {

    private val seg = CardiacSegmenter()
    private val spf = PcgFeatureExtractor.TARGET_SR / PcgFeatureExtractor.FEAT_FS  // 10

    // 20 frames of diastole, then three full cycles of 10/16/8/20. Starting mid-diastole rather
    // than on S1 is not incidental: onsets are detected as *transitions*, so a state present at
    // frame 0 has no onset to find and the first cycle would be lost.
    private val leadIn = 20
    private val cycle = intArrayOf(10, 16, 8, 20)
    private val cycleLen = cycle.sum()   // 54

    private fun labels(): IntArray {
        val out = mutableListOf<Int>()
        repeat(leadIn) { out.add(CardiacSegmenter.STATE_DIASTOLE) }
        repeat(3) {
            repeat(cycle[0]) { out.add(CardiacSegmenter.STATE_S1) }
            repeat(cycle[1]) { out.add(CardiacSegmenter.STATE_SYSTOLE) }
            repeat(cycle[2]) { out.add(CardiacSegmenter.STATE_S2) }
            repeat(cycle[3]) { out.add(CardiacSegmenter.STATE_DIASTOLE) }
        }
        return out.toIntArray()
    }

    private fun logitsFor(labels: IntArray): Array<FloatArray> =
        Array(CardiacSegmenter.NUM_CLASSES) { c ->
            FloatArray(labels.size) { t -> if (labels[t] == c) 1f else 0f }
        }

    /** 12 flat channels, with a single spike planted in ch0 (S1 band) and ch1 (S2 band). */
    private fun features(nFrames: Int, ch0Spikes: List<Int>, ch1Spikes: List<Int>): Array<FloatArray> {
        val f = Array(PcgFeatureExtractor.NUM_CHANNELS) { FloatArray(nFrames) }
        for (i in ch0Spikes) f[0][i] = 1f
        for (i in ch1Spikes) f[1][i] = 1f
        return f
    }

    @Test
    fun `onsets and peaks land on the right frames and convert to the right samples`() {
        val labels = labels()
        val n = labels.size
        assertEquals(leadIn + 3 * cycleLen, n)

        val s1Onsets = listOf(20, 74, 128)                 // leadIn, +54, +108
        val s2Onsets = listOf(46, 100, 154)                // onset + 10 + 16
        val s1PeakFrames = s1Onsets.map { it + 5 }         // inside each 10-frame S1
        val s2PeakFrames = s2Onsets.map { it + 3 }         // inside each 8-frame S2

        val result = seg.segmentWithLogits(
            logits = logitsFor(labels),
            features = features(n, s1PeakFrames, s2PeakFrames),
            durationSec = n.toDouble() / PcgFeatureExtractor.FEAT_FS
        )

        assertEquals(s1Onsets, result.s1OnsetFrames)
        assertEquals(s2Onsets, result.s2OnsetFrames)
        assertEquals(s1Onsets.map { it * spf }, result.s1OnsetSamples2k)
        assertEquals(s2Onsets.map { it * spf }, result.s2OnsetSamples2k)
        assertEquals(3, result.numCycles)
        assertEquals(PcgFeatureExtractor.FEAT_FS, result.featFs)

        // The peak is searched only within the state's own frames — channel 0 for S1 (the
        // 20-50 Hz envelope), channel 1 for S2 (50-100 Hz). Reading the wrong channel would
        // still return an in-range answer, so this is worth pinning explicitly.
        assertEquals(s1PeakFrames.map { it * spf }, result.s1PeakSamples2k)
        assertEquals(s2PeakFrames.map { it * spf }, result.s2PeakSamples2k)
    }

    @Test
    fun `sample indices convert back to the recording's own rate`() {
        val labels = labels()
        val n = labels.size
        val s1PeakFrames = listOf(25, 79, 133)
        val result = seg.segmentWithLogits(
            logitsFor(labels), features(n, s1PeakFrames, listOf(49, 103, 157)),
            n.toDouble() / PcgFeatureExtractor.FEAT_FS
        )

        val at44k = result.toS1Results(pcgSampleRate = 44100.0)
        assertEquals(3, at44k.size)

        // frame 20 -> sample 200 at 2 kHz -> 200 * 22.05 = 4410 at 44.1 kHz -> 100 ms.
        assertEquals(4410, at44k[0].onsetSampleIndex)
        assertEquals(0.1, at44k[0].onsetTimeSeconds, 1e-9)
        assertEquals(0.125, at44k[0].peakTimeSeconds, 1e-9)

        // peakAmplitude is not measured by the segmenter — it is a placeholder, and reading it
        // as a signal-strength figure would be a mistake.
        assertEquals(1.0, at44k[0].peakAmplitude, 1e-9)
    }

    @Test
    fun `a recording with no complete cycle reports none`() {
        // Diastole throughout: no S1 and no S2 transition, so nothing to count.
        val labels = IntArray(200) { CardiacSegmenter.STATE_DIASTOLE }
        val result = seg.segmentWithLogits(
            logitsFor(labels), features(200, emptyList(), emptyList()), 1.0
        )
        assertEquals(0, result.numCycles)
        assertEquals(emptyList(), result.s1OnsetFrames)
    }
}
