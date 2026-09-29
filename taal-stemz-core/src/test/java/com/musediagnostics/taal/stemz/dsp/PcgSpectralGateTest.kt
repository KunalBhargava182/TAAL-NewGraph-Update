package com.musediagnostics.taal.stemz.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class PcgSpectralGateTest {

    private fun rms(data: FloatArray): Double {
        var sum = 0.0
        for (v in data) sum += v.toDouble() * v
        return sqrt(sum / data.size)
    }

    private fun toDb(ratio: Double): Double = 20.0 * log10(ratio)

    @Test
    fun `noise profile of zero reconstructs input within 1e-3 RMS error`() {
        // A leading silent stretch guarantees the quietest-30%-of-frames noise profile is
        // exactly zero, which forces gain=1 for every non-silent bin/frame (see class doc) —
        // a legitimate way to exercise the STFT/overlap-add machinery for perfect
        // reconstruction using only the public process() API.
        val sampleRate = 8000f
        val silenceSamples = 20000 // 2.5s
        val toneSamples = 24000 // 3s
        val n = silenceSamples + toneSamples
        val input = FloatArray(n) { i ->
            if (i < silenceSamples) 0f
            else (0.3 * sin(2.0 * PI * 80.0 * (i - silenceSamples) / sampleRate)).toFloat()
        }

        val output = PcgSpectralGate().process(input, sampleRate)

        assertEquals(input.size, output.size)
        val diff = FloatArray(n) { input[it] - output[it] }
        assertTrue("reconstruction RMS error ${rms(diff)} too high", rms(diff) < 1e-3)
    }

    @Test
    fun `pulsed 80Hz tone in white noise gains at least 10dB SNR and preserves amplitude within 1_5dB`() {
        // Heart sounds are pulsatile (S1/S2 separated by quiet diastole), not a continuous
        // tone, so the synthetic signal mirrors that: an 80Hz burst alternating with a longer
        // noise-only gap, matching the offline-validated real-recording shape this algorithm
        // was designed against. Realistic 44.1kHz sample rate — at a much lower rate the
        // 2048-sample analysis window spans a large fraction of the burst itself, dominating
        // the measurement with frame-boundary transition artifacts that aren't representative
        // of normal use.
        val sampleRate = 44100f
        val burstSamples = 13230 // 300ms
        val gapSamples = 22050 // 500ms
        val cycles = 6
        val cycleLen = burstSamples + gapSamples
        val n = cycleLen * cycles

        val clean = FloatArray(n)
        val noise = FloatArray(n)
        val random = Random(42)
        for (i in 0 until n) {
            val phase = i % cycleLen
            clean[i] = if (phase < burstSamples) {
                (0.2 * sin(2.0 * PI * 80.0 * i / sampleRate)).toFloat()
            } else 0f
            noise[i] = (random.nextGaussian() * 0.01).toFloat()
        }
        val combined = FloatArray(n) { clean[it] + noise[it] }

        val output = PcgSpectralGate().process(combined, sampleRate)
        assertEquals(combined.size, output.size)

        val burstIndices = (0 until n).filter { it % cycleLen < burstSamples }
        // SNR is measured as (tone amplitude during bursts) vs (noise floor during quiet
        // gaps) — the standard way to evaluate a noise gate: does it preserve the wanted
        // signal while suppressing the floor between events. Skip the first 40% of each gap
        // so the release-smoothing decay tail (deliberately slow, to avoid musical noise —
        // see PcgSpectralGate's class doc) doesn't bias the "settled" measurement.
        val gapIndices = (0 until n).filter { (it % cycleLen) >= burstSamples + (gapSamples * 0.4).toInt() }

        val cleanBurst = FloatArray(burstIndices.size) { clean[burstIndices[it]] }
        val outputBurst = FloatArray(burstIndices.size) { output[burstIndices[it]] }
        val combinedGap = FloatArray(gapIndices.size) { combined[gapIndices[it]] }
        val outputGap = FloatArray(gapIndices.size) { output[gapIndices[it]] }

        val rmsSignal = rms(cleanBurst)
        val rmsNoiseBefore = rms(combinedGap)
        val rmsNoiseAfter = rms(outputGap)

        val snrBeforeDb = toDb(rmsSignal / rmsNoiseBefore)
        val snrAfterDb = toDb(rmsSignal / rmsNoiseAfter)
        val improvementDb = snrAfterDb - snrBeforeDb

        assertTrue(
            "expected >=10dB SNR improvement, got $improvementDb (before=$snrBeforeDb after=$snrAfterDb)",
            improvementDb >= 10.0
        )

        val amplitudeDeltaDb = toDb(rms(outputBurst) / rmsSignal)
        assertTrue(
            "expected tone amplitude within 1.5dB, got $amplitudeDeltaDb dB",
            abs(amplitudeDeltaDb) <= 1.5
        )
    }

    @Test
    fun `all-zeros in produces all-zeros out`() {
        val input = FloatArray(5000) { 0f }
        val output = PcgSpectralGate().process(input, 44100f)
        assertEquals(input.size, output.size)
        for (v in output) assertEquals(0f, v, 0f)
    }

    @Test
    fun `output length always equals input length`() {
        val gate = PcgSpectralGate()
        for (size in intArrayOf(1, 100, 2048, 2049, 5000, 90000)) {
            val input = FloatArray(size) { (it % 7).toFloat() / 10f }
            val output = gate.process(input, 44100f)
            assertEquals("mismatch for size=$size", size, output.size)
        }
    }

    @Test
    fun `DISPLAY preset - transient protection leaves the beats untouched while still gating the gaps`() {
        // Same pulsed-tone shape as the SNR test above. With transient protection every
        // burst frame sits far above the noise floor and must pass through with gain 1, so
        // the burst RMS is unchanged (the original preset shaved ~1 dB off it); the gaps
        // must still be gated hard.
        val sampleRate = 44100f
        val burstSamples = 13230
        val gapSamples = 22050
        val cycles = 6
        val cycleLen = burstSamples + gapSamples
        val n = cycleLen * cycles
        val random = Random(7)
        val combined = FloatArray(n) { i ->
            val phase = i % cycleLen
            val tone = if (phase < burstSamples) (0.2 * sin(2.0 * PI * 80.0 * i / sampleRate)).toFloat() else 0f
            tone + (random.nextGaussian() * 0.01).toFloat()
        }

        val output = PcgSpectralGate(PcgSpectralGate.Params.DISPLAY).process(combined, sampleRate)

        // Skip the first/last 10% of each burst so frames straddling the on/off edge (partly
        // gap, partly tone) don't dominate a measurement about the beat body.
        val burstIndices = (0 until n).filter { val p = it % cycleLen; p in (burstSamples / 10) until (burstSamples * 9 / 10) }
        val gapIndices = (0 until n).filter { (it % cycleLen) >= burstSamples + (gapSamples * 0.4).toInt() }

        val burstBefore = rms(FloatArray(burstIndices.size) { combined[burstIndices[it]] })
        val burstAfter = rms(FloatArray(burstIndices.size) { output[burstIndices[it]] })
        val gapBefore = rms(FloatArray(gapIndices.size) { combined[gapIndices[it]] })
        val gapAfter = rms(FloatArray(gapIndices.size) { output[gapIndices[it]] })

        val burstDeltaDb = toDb(burstAfter / burstBefore)
        assertTrue("beat body changed by $burstDeltaDb dB (must be within 0.3 dB)", abs(burstDeltaDb) <= 0.3)
        val gapReductionDb = toDb(gapBefore / gapAfter)
        assertTrue("gap noise only reduced by $gapReductionDb dB (need >= 8)", gapReductionDb >= 8.0)
    }

    @Test
    fun `DEFAULT params still reproduce the original behaviour`() {
        val input = FloatArray(30000) { i -> (0.1 * sin(2.0 * PI * 120.0 * i / 8000f)).toFloat() + ((i * 7919) % 101 - 50) / 5000f }
        val a = PcgSpectralGate().process(input, 8000f)
        val b = PcgSpectralGate(PcgSpectralGate.Params.DEFAULT).process(input, 8000f)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }
}
