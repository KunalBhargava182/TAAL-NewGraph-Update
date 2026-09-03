package com.musediagnostics.taal.app.ecg.pcgscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plain-JVM tests for the display-only conditioning chain ([PcgDisplayFilter],
 * [PcgLiveDisplayFilter], [PcgClickRemover]). Guards the properties the graph depends on:
 * zero phase (features stay at their true time), the 20–500 Hz band and 50 Hz notch,
 * glitch removal that cannot touch a real beat, and beat-peak preservation through the
 * whole chain including the gate.
 */
class PcgDisplayFilterTest {

    private val sr = 44100f

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun db(r: Double) = 20.0 * log10(r)

    private fun tone(freq: Double, amp: Float, seconds: Float): FloatArray =
        FloatArray((seconds * sr).toInt()) { i -> (amp * sin(2.0 * PI * freq * i / sr)).toFloat() }

    /** RMS over the middle of the signal, away from any edge effects. */
    private fun midRms(x: FloatArray): Double = rms(x, x.size / 4, x.size * 3 / 4)

    @Test
    fun `zero-phase filtering keeps a burst centred where it was`() {
        // 120 Hz tone under a symmetric Gaussian envelope centred at 1.0 s.
        val n = (2f * sr).toInt()
        val centre = sr.toInt()
        val sigma = 0.015 * sr
        val x = FloatArray(n) { i ->
            val env = exp(-0.5 * ((i - centre) / sigma) * ((i - centre) / sigma))
            (0.3 * env * sin(2.0 * PI * 120.0 * i / sr)).toFloat()
        }
        val y = PcgDisplayFilter.zeroPhase(x, sr)
        // Energy centroid of the envelope — argmax on a carrier flips between equal-height
        // peaks half a cycle apart and says nothing about timing; the centroid does.
        val centroidIn = energyCentroid(x)
        val centroidOut = energyCentroid(y)
        assertTrue("burst centroid moved from $centroidIn to $centroidOut", abs(centroidIn - centroidOut) <= 5.0)
        // and the causal live filter, by contrast, lags — proving the zero-phase claim isn't vacuous
        val live = PcgLiveDisplayFilter(sr).process(x)
        val centroidLive = energyCentroid(live)
        assertTrue("live filter should lag the input, got $centroidLive vs $centroidIn", centroidLive > centroidIn + 5.0)
    }

    private fun energyCentroid(x: FloatArray): Double {
        var num = 0.0; var den = 0.0
        for (i in x.indices) { val e = x[i].toDouble() * x[i]; num += i * e; den += e }
        return if (den > 0) num / den else 0.0
    }

    @Test
    fun `band-pass removes sub-20 Hz rumble and above-500 Hz content but keeps the heart band`() {
        val rumble = PcgDisplayFilter.zeroPhase(tone(10.0, 0.2f, 3f), sr)
        val hiss = PcgDisplayFilter.zeroPhase(tone(1500.0, 0.2f, 3f), sr)
        val heart = PcgDisplayFilter.zeroPhase(tone(120.0, 0.2f, 3f), sr)
        val ref = midRms(tone(120.0, 0.2f, 3f))
        assertTrue("10 Hz not attenuated: ${db(midRms(rumble) / ref)} dB", db(midRms(rumble) / ref) <= -20.0)
        assertTrue("1500 Hz not attenuated: ${db(midRms(hiss) / ref)} dB", db(midRms(hiss) / ref) <= -20.0)
        assertTrue("120 Hz altered: ${db(midRms(heart) / ref)} dB", abs(db(midRms(heart) / ref)) <= 1.0)
    }

    @Test
    fun `50 Hz mains hum is notched, nearby heart content is not`() {
        val ref = midRms(tone(60.0, 0.2f, 3f))
        val hum = PcgDisplayFilter.zeroPhase(tone(50.0, 0.2f, 3f), sr)
        val near = PcgDisplayFilter.zeroPhase(tone(60.0, 0.2f, 3f), sr)
        assertTrue("50 Hz not notched: ${db(midRms(hum) / ref)} dB", db(midRms(hum) / ref) <= -20.0)
        assertTrue("60 Hz altered: ${db(midRms(near) / ref)} dB", abs(db(midRms(near) / ref)) <= 1.0)
    }

    @Test
    fun `click remover takes out an isolated full-scale glitch`() {
        val x = tone(120.0, 0.005f, 3f)
        val at = 60000
        x[at] = 0.9f
        val removed = PcgClickRemover.removeClicks(x, sr)
        assertTrue("nothing removed", removed >= 1)
        var localMax = 0f
        for (i in at - 300 until at + 300) localMax = maxOf(localMax, abs(x[i]))
        assertTrue("glitch survived: $localMax", localMax < 0.05f)
    }

    @Test
    fun `click remover leaves a real S1 burst and a genuinely clipped loud beat alone`() {
        // Quiet background with one 60 ms burst at 0.3 — beat-shaped, never "isolated".
        val x = tone(120.0, 0.005f, 3f)
        val start = 50000
        val len = (0.06f * sr).toInt()
        for (i in 0 until len) x[start + i] = (0.3 * sin(2.0 * PI * 80.0 * i / sr)).toFloat()
        val before = rms(x, start, start + len)
        val removed = PcgClickRemover.removeClicks(x, sr)
        assertEquals("burst was treated as a click", 0, removed)
        assertEquals(before, rms(x, start, start + len), 1e-6)

        // A 40 ms run at digital full scale is a clipped loud beat, not a glitch.
        val y = tone(120.0, 0.005f, 3f)
        val clipLen = (0.04f * sr).toInt()
        for (i in 0 until clipLen) y[start + i] = if ((i / 100) % 2 == 0) 1.0f else -1.0f
        assertEquals("clipped beat was treated as a click", 0, PcgClickRemover.removeClicks(y, sr))
    }

    @Test
    fun `full offline chain preserves beat amplitude within 1 dB and cuts gap noise by at least 12 dB`() {
        val burstSamples = 13230
        val gapSamples = 22050
        val cycles = 6
        val cycleLen = burstSamples + gapSamples
        val n = cycleLen * cycles
        val random = Random(3)
        // Raised-cosine 20 ms on/off ramps: a real S1 has a soft onset. A hard rectangular
        // edge would pre-ring symmetrically through the ZERO-PHASE filter into the tail of
        // the previous gap and pollute the gap measurement with edge energy, not noise.
        val ramp = (0.02f * sr).toInt()
        val clean = FloatArray(n) { i ->
            val p = i % cycleLen
            if (p < burstSamples) {
                val env = when {
                    p < ramp -> 0.5 - 0.5 * kotlin.math.cos(PI * p / ramp)
                    p >= burstSamples - ramp -> 0.5 - 0.5 * kotlin.math.cos(PI * (burstSamples - 1 - p) / ramp)
                    else -> 1.0
                }
                (0.2 * env * sin(2.0 * PI * 80.0 * i / sr)).toFloat()
            } else 0f
        }
        val combined = FloatArray(n) { clean[it] + (random.nextGaussian() * 0.01).toFloat() }

        val out = PcgDisplayFilter.processOffline(combined, sr)
        assertEquals(n, out.size)

        val burstIdx = (0 until n).filter { val p = it % cycleLen; p in (burstSamples / 10) until (burstSamples * 9 / 10) }
        val gapIdx = (0 until n).filter { (it % cycleLen) >= burstSamples + (gapSamples * 0.4).toInt() }
        val burstIn = rms(FloatArray(burstIdx.size) { clean[burstIdx[it]] })
        val burstOut = rms(FloatArray(burstIdx.size) { out[burstIdx[it]] })
        val gapIn = rms(FloatArray(gapIdx.size) { combined[gapIdx[it]] })
        val gapOut = rms(FloatArray(gapIdx.size) { out[gapIdx[it]] })

        assertTrue("beat amplitude changed by ${db(burstOut / burstIn)} dB", abs(db(burstOut / burstIn)) <= 1.0)
        assertTrue("gap noise only cut by ${db(gapIn / gapOut)} dB", db(gapIn / gapOut) >= 12.0)
    }

    @Test
    fun `output length always equals input length, including degenerate sizes`() {
        for (size in intArrayOf(0, 1, 2, 5, 100, 4097, 50000)) {
            val x = FloatArray(size) { (it % 13 - 6) / 100f }
            assertEquals("offline size=$size", size, PcgDisplayFilter.processOffline(x, sr).size)
            assertEquals("zeroPhase size=$size", size, PcgDisplayFilter.zeroPhase(x, sr).size)
            assertEquals("live size=$size", size, PcgLiveDisplayFilter(sr).process(x).size)
        }
    }

    @Test
    fun `live filter passes the heart band and rejects rumble once settled`() {
        val live = PcgLiveDisplayFilter(sr)
        val bufferLen = 4096
        var last = FloatArray(0)
        val heart = tone(120.0, 0.2f, 3f)
        var pos = 0
        while (pos + bufferLen <= heart.size) {
            last = live.process(heart.copyOfRange(pos, pos + bufferLen)); pos += bufferLen
        }
        val ref = rms(heart, 0, bufferLen)
        assertTrue("120 Hz altered live: ${db(rms(last) / ref)} dB", abs(db(rms(last) / ref)) <= 1.0)

        // The live high-pass is deliberately gentle (2nd order at 10 Hz — see Params.LIVE),
        // so probe well below its corner: 2.5 Hz sits two octaves down (~ -24 dB).
        val live2 = PcgLiveDisplayFilter(sr)
        val rumble = tone(2.5, 0.2f, 4f)
        pos = 0
        while (pos + bufferLen <= rumble.size) {
            last = live2.process(rumble.copyOfRange(pos, pos + bufferLen)); pos += bufferLen
        }
        assertTrue("2.5 Hz not rejected live: ${db(rms(last) / ref)} dB", db(rms(last) / ref) <= -18.0)

        // And the live corner must NOT smear S1's low end: 40 Hz within 1 dB.
        val live3 = PcgLiveDisplayFilter(sr)
        val lowS1 = tone(40.0, 0.2f, 3f)
        pos = 0
        while (pos + bufferLen <= lowS1.size) {
            last = live3.process(lowS1.copyOfRange(pos, pos + bufferLen)); pos += bufferLen
        }
        assertTrue("40 Hz altered live: ${db(rms(last) / ref)} dB", abs(db(rms(last) / ref)) <= 1.0)
    }
}
