package com.musediagnostics.taal.stemz

import com.musediagnostics.taal.stemz.dsp.AudioFilterEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the stemz filter set: Lite = 20–250 Hz, Hard = 20–200 Hz, and nothing else. */
class PreFilterTest {

    @Test
    fun `Lite is the 20 to 250 Hz heart band`() {
        val dsp = PreFilter.LITE.toDspFilter()
        assertEquals(20.0, dsp.lowCut, 0.0)
        assertEquals(250.0, dsp.highCut, 0.0)
    }

    @Test
    fun `Hard is the narrower 20 to 200 Hz band`() {
        val dsp = PreFilter.HARD.toDspFilter()
        assertEquals(20.0, dsp.lowCut, 0.0)
        assertEquals(200.0, dsp.highCut, 0.0)
    }

    @Test
    fun `only Lite and Hard are exposed`() {
        assertEquals(listOf(PreFilter.LITE, PreFilter.HARD), PreFilter.entries)
    }

    @Test
    fun `auto-stop defaults to 15 seconds with a 300 second ceiling`() {
        assertEquals(15, TaalRecorder.DEFAULT_RECORDING_TIME_SECONDS)
        assertEquals(300, TaalRecorder.MAX_RECORDING_TIME_SECONDS)
    }

    @Test
    fun `engine processes a block without NaN for each preset`() {
        for (preset in listOf(AudioFilterEngine.PresetFilter.LITE, AudioFilterEngine.PresetFilter.HARD)) {
            val engine = AudioFilterEngine().apply { setPresetFilter(preset) }
            val input = FloatArray(4410) { i -> kotlin.math.sin(2 * Math.PI * 100 * i / 44100).toFloat() * 0.5f }
            val out = engine.processBlock(input)
            assert(out.none { it.isNaN() }) { "NaN output for $preset" }
        }
    }
}
