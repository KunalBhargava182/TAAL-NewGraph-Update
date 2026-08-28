package com.purnacardio.signal.pcg.android

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.purnacardio.signal.pcg.CardiacSegmenter
import com.purnacardio.signal.pcg.Pcm
import com.purnacardio.signal.pcg.SegmentationResult
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * ONNX Runtime host for the TCN cardiac segmenter. This is the entry point for Android.
 *
 * The split matters: [CardiacSegmenter] does pure-Kotlin feature extraction and constrained
 * decoding, and only the tensor forward-pass needs a runtime. So the segmentation *logic* stays
 * JVM-testable in `pcg-segmentation-core`, and this class does nothing but move arrays across
 * the ONNX boundary.
 *
 * ## Lifecycle
 *
 * Construction opens an `OrtSession`, which costs roughly 100 ms and reads a 407 KB asset.
 * **Build one and keep it** for as long as you will segment recordings; do not construct one per
 * capture. It is [Closeable] — close it when the owning screen or service goes away.
 *
 * Construction can throw (missing asset, unsupported ABI, corrupt model). Wrap it, and treat
 * failure as "segmentation unavailable" rather than crashing the capture flow:
 *
 * ```kotlin
 * private val segmenter: TcnSegmenterRunner? by lazy {
 *     runCatching { TcnSegmenterRunner(context) }
 *         .onFailure { Log.e(TAG, "segmenter unavailable", it) }
 *         .getOrNull()
 * }
 * ```
 *
 * ## Threading
 *
 * Not thread-safe: one in-flight [segment] call at a time. Inference on a 20 s recording is
 * hundreds of milliseconds, so call it off the main thread (`Dispatchers.Default`).
 *
 * @param context any Context; only `assets` is used and no reference is retained.
 * @param modelAsset asset filename. The default ships inside this library's AAR — override only
 *   if you are supplying your own trained model with the same 12-channel input contract.
 */
class TcnSegmenterRunner(
    context: Context,
    modelAsset: String = DEFAULT_MODEL_ASSET,
    private val segmenter: CardiacSegmenter = CardiacSegmenter()
) : Closeable {

    companion object {
        private const val TAG = "TcnSegmenter"
        private const val NUM_CLASSES = CardiacSegmenter.NUM_CLASSES

        /** Packaged in this library's assets; no app-side copying required. */
        const val DEFAULT_MODEL_ASSET = "tcn_c200_cardiac_seg.onnx"

        /** Shortest recording the model will accept, in seconds. Below this, [segment] returns null. */
        const val MIN_DURATION_SEC = 1.0
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = context.assets.open(modelAsset).use { input ->
        env.createSession(input.readBytes(), OrtSession.SessionOptions())
    }

    /**
     * Segment a PCG recording.
     *
     * @param pcg **mono** audio at any sample rate; the extractor resamples to 2 kHz internally.
     *   Amplitude scale is irrelevant — raw 16-bit values and [-1, 1] floats give identical
     *   results. Interleaved stereo is not detected and will silently degrade output; split it
     *   first with [Pcm.deinterleaveMono].
     * @param sampleRate the rate [pcg] was captured at, e.g. 44100.
     * @return null when the recording is too short ([MIN_DURATION_SEC]), too quiet, or inference
     *   fails — callers must treat that as "no heart sounds", not as an error to surface raw.
     *   A returned result is not a quality guarantee: check [SegmentationResult.numCycles]
     *   against the recording length before trusting it.
     */
    fun segment(pcg: FloatArray, sampleRate: Int): SegmentationResult? {
        val feats = segmenter.extractFeatures(pcg, sampleRate) ?: return null
        val channels = feats.size
        val frames = feats.firstOrNull()?.size ?: return null
        if (frames < 32) return null

        // model expects [1, channels, frames]
        val flat = FloatArray(channels * frames)
        for (c in 0 until channels) System.arraycopy(feats[c], 0, flat, c * frames, frames)

        return try {
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(flat), longArrayOf(1, channels.toLong(), frames.toLong())
            ).use { input ->
                session.run(mapOf(session.inputNames.first() to input)).use { out ->
                    // Model emits [1, classes, frames]; segmentWithLogits indexes [class][frame]
                    // (it reads seqLength from logits[0].size), so no transposition is needed.
                    @Suppress("UNCHECKED_CAST")
                    val logits = (out[0].value as Array<Array<FloatArray>>)[0]
                    require(logits.size == NUM_CLASSES) {
                        "expected $NUM_CLASSES classes, model returned ${logits.size}"
                    }
                    val durationSec = pcg.size.toDouble() / sampleRate
                    segmenter.segmentWithLogits(logits, feats, durationSec)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "TCN inference failed", t)
            null
        }
    }

    /**
     * Convenience overload for 16-bit PCM straight out of `AudioRecord`.
     *
     * Only valid for **mono** buffers. For `CHANNEL_IN_STEREO`, convert with [Pcm.toFloat] and
     * split with [Pcm.deinterleaveMono] yourself — this overload cannot tell the difference.
     */
    fun segment(pcm16: ShortArray, sampleRate: Int): SegmentationResult? =
        segment(Pcm.toFloat(pcm16), sampleRate)

    override fun close() {
        runCatching { session.close() }
        // OrtEnvironment is process-wide; do not close it here.
    }
}
