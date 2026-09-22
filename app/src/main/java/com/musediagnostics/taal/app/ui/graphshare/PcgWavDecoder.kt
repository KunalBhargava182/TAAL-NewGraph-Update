package com.musediagnostics.taal.app.ui.graphshare

import kotlin.math.roundToInt

/**
 * Pure Kotlin WAV decode — no Android dependency, plain-JVM unit-testable. Same 16-bit LE PCM /
 * 44-byte-header decode PcgScaleReviewFragment.loadFullWaveform and PcgScalePlayerFragment's
 * copy each inline today — lifted out here so the graph export doesn't duplicate a fourth copy
 * of it. Deliberately NOT wired back into either fragment by this change — this is new code
 * only, so it cannot regress the two working screens.
 */
data class DecodedWav(
    val samples: FloatArray,
    val sampleRate: Float,
    val durationSecs: Float
) {
    // FloatArray doesn't get a sensible generated equals/hashCode; not needed for this data
    // class's actual use (constructed once, read, never compared) but overridden anyway to
    // avoid a silent equals() footgun if a future test does compare two instances.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedWav) return false
        return samples.contentEquals(other.samples) &&
            sampleRate == other.sampleRate &&
            durationSecs == other.durationSecs
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate.hashCode()
        result = 31 * result + durationSecs.hashCode()
        return result
    }
}

object PcgWavDecoder {
    private const val HEADER_BYTES = 44
    private const val DEFAULT_SAMPLE_RATE = 44100f

    /**
     * Decodes a canonical 16-bit mono PCM WAV (the only format this app ever writes) from raw
     * file bytes. Returns null instead of throwing for anything too short to be a real capture —
     * callers show a "couldn't prepare" message rather than crash, same posture as the existing
     * share/export code's try/catch-and-toast pattern.
     */
    fun decode(bytes: ByteArray): DecodedWav? {
        if (bytes.size <= HEADER_BYTES) return null

        val sampleRate = readSampleRateOrDefault(bytes)

        val dataSize = bytes.size - HEADER_BYTES
        val totalSamples = dataSize / 2
        if (totalSamples <= 0) return null

        val samples = FloatArray(totalSamples)
        var i = 0
        while (i < totalSamples) {
            val bytePos = HEADER_BYTES + i * 2
            if (bytePos + 1 >= bytes.size) break
            val low = bytes[bytePos].toInt() and 0xFF
            val high = bytes[bytePos + 1].toInt() shl 8
            samples[i] = (high or low).toShort().toFloat() / 32768f
            i++
        }

        // roundToInt, not truncate — matches PcgScalePlayerFragment/PcgScaleReviewFragment's
        // matching comment: truncation would report e.g. a 14.98s file as 14s.
        val durationSecs = (totalSamples / sampleRate).roundToInt().toFloat()
        return DecodedWav(samples, sampleRate, durationSecs)
    }

    /** WAV header sample rate lives at bytes 24-27, little-endian. Falls back to 44100Hz (this
     *  app's only actual capture rate) if the header is malformed or too short to read it from. */
    private fun readSampleRateOrDefault(bytes: ByteArray): Float {
        if (bytes.size < 28) return DEFAULT_SAMPLE_RATE
        val rate = (bytes[24].toInt() and 0xff) or
            ((bytes[25].toInt() and 0xff) shl 8) or
            ((bytes[26].toInt() and 0xff) shl 16) or
            ((bytes[27].toInt() and 0xff) shl 24)
        return if (rate > 0) rate.toFloat() else DEFAULT_SAMPLE_RATE
    }
}
