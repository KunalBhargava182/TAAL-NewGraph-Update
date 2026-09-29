package com.purnacardio.signal.pcg.viz

import com.purnacardio.signal.pcg.SegmentationResult
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chart's geometry.
 *
 * Everything here is a failure that *looks fine on screen*: bands offset from the waveform by a
 * constant factor, heart sounds shrinking as the view narrows, a recording flattened to a hairline
 * by one knock. None of it throws, so none of it shows up without an assertion.
 */
class PcgDisplayModelTest {

    private val sr = 1000

    /** 4 s of labels at 200 Hz: 0.5 s S1, 1.0 s systole, 0.5 s S2, 2.0 s diastole. */
    private fun labels(): IntArray {
        val l = IntArray(800)
        for (f in l.indices) l[f] = when {
            f < 100 -> 0
            f < 300 -> 1
            f < 400 -> 2
            else -> 3
        }
        return l
    }

    private fun result(
        stateLabels: IntArray = labels(),
        s1Peaks: List<Int> = emptyList(),
        s2Peaks: List<Int> = emptyList()
    ) = SegmentationResult(
        stateLabels = stateLabels, featFs = 200,
        s1OnsetFrames = emptyList(), s2OnsetFrames = emptyList(),
        s1OnsetSamples2k = emptyList(), s2OnsetSamples2k = emptyList(),
        s1PeakSamples2k = s1Peaks, s2PeakSamples2k = s2Peaks,
        numCycles = 1, durationSec = 4.0
    )

    private fun silence(seconds: Int = 4) = FloatArray(seconds * sr)

    @Test
    fun `state bands land where the labels say, in cycle order`() {
        val m = PcgDisplay.build(silence(), sr, result(), columns = 100)

        assertEquals(4, m.bands.size, "one band per state run")
        assertEquals(listOf(0, 1, 2, 3), m.bands.map { it.state })

        // 0.5 s of S1 in a 4 s window is the first eighth of the chart.
        assertEquals(0f, m.bands[0].xStart, 1e-5f)
        assertEquals(0.125f, m.bands[0].xEnd, 1e-5f)
        assertEquals(0.375f, m.bands[1].xEnd, 1e-5f)
        assertEquals(0.5f, m.bands[2].xEnd, 1e-5f)
        assertEquals(1f, m.bands[3].xEnd, 1e-5f)
    }

    @Test
    fun `bands tile the window without gaps or overlaps`() {
        // A gap paints the chart background through the band row and reads as a fifth state.
        val m = PcgDisplay.build(silence(), sr, result(), columns = 100)
        for (i in 1 until m.bands.size) {
            assertEquals(m.bands[i - 1].xEnd, m.bands[i].xStart, 1e-6f,
                "band $i does not start where band ${i - 1} ends")
        }
    }

    @Test
    fun `peak markers cross from 2 kHz indices to the right place on screen`() {
        // 2000 at 2 kHz is t = 1.0 s, which is a quarter of the way across a 4 s window.
        val m = PcgDisplay.build(silence(), sr, result(s1Peaks = listOf(2000), s2Peaks = listOf(6000)),
            columns = 100)
        assertEquals(1, m.s1MarkersX.size)
        assertEquals(0.25f, m.s1MarkersX[0], 1e-5f)
        assertEquals(0.75f, m.s2MarkersX[0], 1e-5f)
    }

    @Test
    fun `a window shows only its own slice, rescaled to fill the chart`() {
        val m = PcgDisplay.build(
            silence(), sr, result(s1Peaks = listOf(1000, 2000, 6000)), columns = 100,
            windowStartSec = 1.0, windowEndSec = 3.0
        )
        assertEquals(1.0, m.startSec, 1e-9)
        assertEquals(3.0, m.endSec, 1e-9)

        // t=0.5 s is outside; t=1.0 s sits on the left edge; t=3.0 s on the right.
        assertEquals(listOf(0f, 1f), m.s1MarkersX.toList())

        // The systole run (0.5-1.5 s) is cut by the window and must start at the left edge.
        assertEquals(0f, m.bands.first().xStart, 1e-6f)
        assertEquals(1f, m.bands.last().xEnd, 1e-6f)
        assertTrue(m.bands.all { it.xStart >= 0f && it.xEnd <= 1f }, "bands must stay inside")
    }

    @Test
    fun `a heart sound survives being squeezed into few columns`() {
        // The reason for min/max decimation. One short transient, and a column count far below the
        // sample count: sampling every Nth sample would step straight over it and the S1 would
        // vanish as the view got narrower.
        val audio = silence(1)
        audio[517] = 1f

        for (columns in intArrayOf(8, 60, 400, 1000)) {
            val m = PcgDisplay.build(audio, sr, result(), columns)
            assertEquals(0f, m.top.min(), 1e-5f,
                "the transient must reach full deflection at $columns columns")
        }
    }

    @Test
    fun `one knock does not flatten the recording to a hairline`() {
        // Peak scaling would divide everything by the knock and leave the heartbeat invisible.
        val audio = FloatArray(sr) { (0.1 * sin(2.0 * PI * 5.0 * it / sr)).toFloat() }
        audio[400] = 1.0f     // a transient ten times the content

        val m = PcgDisplay.build(audio, sr, result(), columns = 200)

        // The ordinary content still uses essentially the whole lane...
        assertTrue(m.top.min() < 0.05f,
            "content should reach near full deflection, got ${m.top.min()}")
        // ...and the knock is the thing that gives way, not the signal.
        assertTrue(m.clippedFraction > 0f, "the transient should clip")
        assertTrue(m.clippedFraction < 0.05f,
            "only a sliver should clip, got ${m.clippedFraction}")
    }

    @Test
    fun `silence draws a flat line rather than NaN`() {
        // Dividing by a zero ceiling would paint NaN, which Canvas renders as nothing at all —
        // an empty chart that looks like a layout bug rather than a silent recording.
        val m = PcgDisplay.build(silence(), sr, result(), columns = 50)
        assertTrue(m.top.all { it.isFinite() } && m.bottom.all { it.isFinite() })
        assertTrue(m.top.all { it == 0.5f } && m.bottom.all { it == 0.5f },
            "silence belongs on the baseline")
        assertEquals(0f, m.clippedFraction)
    }

    @Test
    fun `y is measured downward, so a positive sample sits above the centre line`() {
        val audio = silence(1)
        audio[100] = 1f      // positive peak
        audio[600] = -1f     // negative peak
        val m = PcgDisplay.build(audio, sr, result(), columns = 10)
        assertEquals(0f, m.top[1], 1e-5f, "the positive peak belongs at the top of the lane")
        assertEquals(1f, m.bottom[6], 1e-5f, "the negative peak belongs at the bottom")
    }

    @Test
    fun `degenerate inputs give an empty model instead of throwing`() {
        assertTrue(PcgDisplay.build(silence(), sr, result(), columns = 0).isEmpty)
        assertTrue(PcgDisplay.build(FloatArray(0), sr, result(), columns = 100).isEmpty)
        assertTrue(PcgDisplay.build(silence(), 0, result(), columns = 100).isEmpty)
        assertTrue(PcgDisplay.build(silence(), sr, result(IntArray(0)), columns = 100).isEmpty)
        // A window past the end of the recording, which a scrubber will produce.
        assertTrue(PcgDisplay.build(silence(), sr, result(), 100, 9.0, 10.0).isEmpty)
    }

    @Test
    fun `every column is filled even when zoomed past one sample per column`() {
        // A deep zoom asks for more columns than there are samples. Columns must still resolve to
        // a real sample rather than collapsing to an unpainted centre line.
        val audio = FloatArray(100) { if (it % 2 == 0) 0.5f else -0.5f }
        val m = PcgDisplay.build(audio, sr, result(), columns = 400)
        assertEquals(400, m.columns)
        assertTrue(m.top.all { it.isFinite() } && m.bottom.all { it.isFinite() })
        assertTrue(m.top.all { it in 0f..1f } && m.bottom.all { it in 0f..1f },
            "edges must stay inside the lane")
        // Zoomed this far in, a column can hold a single positive sample, so both of its edges sit
        // above the centre line. What must always hold is that the upper edge is not below the
        // lower one — that would draw the trace inside out.
        assertTrue((0 until m.columns).all { m.top[it] <= m.bottom[it] },
            "upper edge crossed below the lower edge")
    }

    @Test
    fun `the palette exposes one entry per state, in cycle order`() {
        val legend = SegmentationPalette.legend(SegmentationPalette.light)
        assertEquals(listOf(0, 1, 2, 3), legend.map { it.state })
        assertEquals(listOf("S1", "Systole", "S2", "Diastole"), legend.map { it.label })
        // Every colour must be opaque enough to see: a fully transparent swatch is a missing key.
        assertTrue(legend.all { (it.swatch ushr 24) > 0x10 }, "band washes must be visible")
        assertTrue(legend.all { (it.accent ushr 24) == 0xFF }, "accents must be opaque")
    }

    @Test
    fun `both palette variants define all four states`() {
        for (v in listOf(SegmentationPalette.light, SegmentationPalette.dark)) {
            assertEquals(4, v.bandFill.size)
            assertEquals(4, v.accent.size)
            // Out-of-range states must not crash a renderer mid-draw.
            assertEquals(v.fillFor(3), v.fillFor(99))
        }
    }
}
