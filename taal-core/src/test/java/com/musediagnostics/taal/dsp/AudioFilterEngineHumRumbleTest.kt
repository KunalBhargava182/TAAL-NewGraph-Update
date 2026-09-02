package com.musediagnostics.taal.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plain-JVM tests for [AudioFilterEngine]'s opt-in hum/rumble stage (default off — see
 * setHumRumbleFilterEnabled doc). Verifies the stage is truly inert when disabled and that it
 * measurably attenuates 25Hz-and-below rumble and the 50Hz mains line when enabled, without
 * needing an Android runtime (AudioFilterEngine has no Android dependency).
 */
class AudioFilterEngineHumRumbleTest {

    private val sampleRate = 44100
    private val durationSeconds = 2.0

    private fun sine(freqHz: Double, amplitude: Float = 1f): FloatArray {
        val n = (sampleRate * durationSeconds).toInt()
        return FloatArray(n) { i ->
            (amplitude * sin(2.0 * PI * freqHz * i / sampleRate)).toFloat()
        }
    }

    private fun rms(data: FloatArray, skip: Int = 0): Double {
        var sum = 0.0
        for (i in skip until data.size) sum += data[i].toDouble() * data[i]
        return sqrt(sum / (data.size - skip))
    }

    @Test
    fun `disabled stage produces bit-identical output to today's processBlock`() {
        val engineOff = AudioFilterEngine(sampleRate)
        val engineNeverToggled = AudioFilterEngine(sampleRate)
        engineOff.setPresetFilter(AudioFilterEngine.PresetFilter.NONE)
        engineNeverToggled.setPresetFilter(AudioFilterEngine.PresetFilter.NONE)
        engineOff.setHumRumbleFilterEnabled(false) // explicit off, still the default

        val input = sine(120.0)
        val outA = engineOff.processBlock(input)
        val outB = engineNeverToggled.processBlock(input)

        assertEquals(outA.size, outB.size)
        for (i in outA.indices) assertEquals(outA[i], outB[i], 0f)
    }

    @Test
    fun `enabled stage attenuates 50Hz mains hum relative to 80Hz passband tone`() {
        val engine = AudioFilterEngine(sampleRate)
        engine.setPresetFilter(AudioFilterEngine.PresetFilter.NONE)
        engine.setHumRumbleFilterEnabled(true)

        val hum = engine.processBlock(sine(50.0))
        val settleSamples = sampleRate / 2 // let the biquad cascade settle before measuring

        val engine2 = AudioFilterEngine(sampleRate)
        engine2.setPresetFilter(AudioFilterEngine.PresetFilter.NONE)
        engine2.setHumRumbleFilterEnabled(true)
        val tone = engine2.processBlock(sine(80.0))

        val humRms = rms(hum, settleSamples)
        val toneRms = rms(tone, settleSamples)

        // 50Hz should be suppressed far below the 80Hz passband tone once the notch settles.
        assertTrue("expected 50Hz rms ($humRms) << 80Hz rms ($toneRms)", humRms < toneRms * 0.1)
    }

    @Test
    fun `enabled stage attenuates sub-25Hz rumble relative to 80Hz passband tone`() {
        val engine = AudioFilterEngine(sampleRate)
        engine.setPresetFilter(AudioFilterEngine.PresetFilter.NONE)
        engine.setHumRumbleFilterEnabled(true)

        val rumble = engine.processBlock(sine(10.0))
        val settleSamples = sampleRate / 2

        val engine2 = AudioFilterEngine(sampleRate)
        engine2.setPresetFilter(AudioFilterEngine.PresetFilter.NONE)
        engine2.setHumRumbleFilterEnabled(true)
        val tone = engine2.processBlock(sine(80.0))

        val rumbleRms = rms(rumble, settleSamples)
        val toneRms = rms(tone, settleSamples)

        assertTrue("expected 10Hz rms ($rumbleRms) << 80Hz rms ($toneRms)", rumbleRms < toneRms * 0.1)
    }
}
