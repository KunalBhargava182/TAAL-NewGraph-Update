package com.purnacardio.signal.pcg

/**
 * Conversions from what `AudioRecord` hands you to what the segmenter takes.
 *
 * ## Scale does not matter, and that is deliberate
 *
 * [PcgFeatureExtractor] normalises internally, so feeding it raw 16-bit values (±32767)
 * and feeding it the same audio divided by 32768 produce identical features. Both are
 * correct. This helper exists so an integrator does not have to guess whether a scaling
 * step is required and then silently pick a wrong one — not because the model is
 * sensitive to level.
 *
 * What *does* matter is that the samples are a single channel. See [deinterleaveMono].
 */
object Pcm {

    /** Widen 16-bit PCM to float, preserving scale. */
    fun toFloat(pcm16: ShortArray): FloatArray =
        FloatArray(pcm16.size) { pcm16[it].toFloat() }

    /**
     * Widen 16-bit PCM to float in the conventional [-1, 1) range.
     *
     * Equivalent to [toFloat] as far as the segmenter is concerned; use whichever matches
     * the rest of your pipeline.
     */
    fun toFloatNormalised(pcm16: ShortArray): FloatArray =
        FloatArray(pcm16.size) { pcm16[it] / 32768f }

    /**
     * Pull one channel out of interleaved multi-channel PCM.
     *
     * Passing interleaved stereo straight in is a real integration failure and not an
     * obvious one: the samples still look like audio, but every other one belongs to a
     * different microphone, so the effective sample rate is halved and the band-pass
     * filters land an octave away from where they were designed. Segmentation degrades
     * without any error being raised.
     *
     * @param interleaved Frames laid out as ch0, ch1, …, chN, ch0, ch1, …
     * @param channelCount Channels per frame. 1 returns the input unchanged.
     * @param channel Zero-based channel to keep.
     */
    fun deinterleaveMono(interleaved: FloatArray, channelCount: Int, channel: Int = 0): FloatArray {
        require(channelCount >= 1) { "channelCount must be >= 1, was $channelCount" }
        require(channel in 0 until channelCount) {
            "channel $channel out of range for $channelCount channels"
        }
        if (channelCount == 1) return interleaved
        val frames = interleaved.size / channelCount
        return FloatArray(frames) { interleaved[it * channelCount + channel] }
    }
}
