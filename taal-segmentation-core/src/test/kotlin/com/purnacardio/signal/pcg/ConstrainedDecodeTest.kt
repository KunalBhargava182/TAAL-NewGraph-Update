package com.purnacardio.signal.pcg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The constrained decoder — the part of segmentation that is *not* the neural network.
 *
 * The model emits per-frame class scores with no notion that a heart cycle has an order or that
 * S1 cannot last 5 ms. Raw argmax therefore produces sequences no heart can make. These tests pin
 * the two rules that fix that, and the two places where the rules deliberately do not fire.
 *
 * They are the reference for anyone reimplementing the decoder in another language: matching
 * these behaviours is what "the same algorithm" means.
 */
class ConstrainedDecodeTest {

    private val seg = CardiacSegmenter()

    /** One-hot logits for a desired label sequence — argmax reproduces it exactly. */
    private fun logitsFor(labels: IntArray): Array<FloatArray> =
        Array(CardiacSegmenter.NUM_CLASSES) { c ->
            FloatArray(labels.size) { t -> if (labels[t] == c) 1f else 0f }
        }

    private fun repeat(vararg runs: Pair<Int, Int>): IntArray {
        val out = mutableListOf<Int>()
        for ((state, n) in runs) kotlin.repeat(n) { out.add(state) }
        return out.toIntArray()
    }

    @Test
    fun `a physiological sequence passes through untouched`() {
        // S1 10 / systole 16 / S2 8 / diastole 20 frames at 200 Hz — each exactly at its minimum,
        // so nothing may be rewritten. 54 frames = 270 ms = 222 bpm; the minimums are a floor on
        // what is anatomically possible, not a typical resting cycle.
        val labels = repeat(0 to 10, 1 to 16, 2 to 8, 3 to 20, 0 to 10, 1 to 16, 2 to 8, 3 to 20)
        val decoded = seg.constrainedDecode(logitsFor(labels), labels.size)
        assertTrue(labels.contentEquals(decoded), "a legal, long-enough sequence must be preserved")
    }

    @Test
    fun `a state the cycle cannot reach from here is refused`() {
        // S1 -> S2 skips systole. The decoder holds the previous state rather than following the
        // model, so no S2 onset is ever emitted from this recording.
        val labels = repeat(0 to 30, 2 to 30)
        val decoded = seg.constrainedDecode(logitsFor(labels), labels.size)
        assertTrue(decoded.all { it == CardiacSegmenter.STATE_S1 },
            "an illegal transition must be held at the previous state, got ${decoded.toList()}")
    }

    @Test
    fun `a segment too short to be real is absorbed into the state before it`() {
        // 5 frames = 25 ms of systole. Systole's floor is 16 frames (80 ms), so this is noise.
        val labels = repeat(0 to 20, 1 to 5, 2 to 20)
        val decoded = seg.constrainedDecode(logitsFor(labels), labels.size)

        assertEquals(List(25) { 0 }, decoded.take(25).toList(),
            "the 5-frame systole must be rewritten to the S1 that preceded it")
        assertEquals(List(20) { 2 }, decoded.drop(25).toList(),
            "the following S2 must survive unchanged")
    }

    @Test
    fun `a short leading segment is kept, because a recording may start mid-sound`() {
        // The minimum-duration rule is guarded on `segStart > 0`. That is deliberate: frame 0 is
        // wherever the user happened to press record, so a 3-frame S2 at the very start is a
        // truncated S2, not a spurious one, and there is no earlier state to absorb it into.
        val labels = repeat(2 to 3, 3 to 30)
        val decoded = seg.constrainedDecode(logitsFor(labels), labels.size)
        assertEquals(List(3) { 2 }, decoded.take(3).toList(),
            "a truncated first segment must not be rewritten")
    }

    @Test
    fun `decoding reads the highest-scoring class, not the first`() {
        // Frame 0 is a near tie: class 0 scores 4.9 and class 3 scores 5.0. Scanning classes in
        // order and keeping the first maximum would answer 0; the correct answer is 3.
        // The S1 run is 29 frames because a shorter one would be absorbed by the minimum-duration
        // rule and this test would then be measuring that instead.
        val n = 30
        val logits = Array(4) { FloatArray(n) }
        logits[0][0] = 4.9f; logits[3][0] = 5f
        for (t in 1 until n) { logits[0][t] = 9f; logits[3][t] = 1f }   // 3 -> 0 is a legal step

        val decoded = seg.constrainedDecode(logits, n)
        assertEquals(CardiacSegmenter.STATE_DIASTOLE, decoded[0], "frame 0 must resolve to class 3")
        assertEquals(List(n - 1) { CardiacSegmenter.STATE_S1 }, decoded.drop(1).toList())
    }
}
