package com.example.heartsounds

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.purnacardio.signal.pcg.CardiacSegmenter
import com.purnacardio.signal.pcg.Pcm
import com.purnacardio.signal.pcg.SegmentationResult
import com.purnacardio.signal.pcg.android.TcnSegmenterRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * End-to-end reference: record 20 s from the phone microphone, segment it, read the results.
 *
 * Copy-paste-and-edit, not a library class — it deliberately does the whole thing in one file so
 * the shape is visible. Production code should own recording and analysis separately.
 */
class HeartSoundAnalyser(context: Context) : Closeable {

    private val appContext = context.applicationContext

    /**
     * Built once and kept. Opening the ONNX session costs ~100 ms and reads a 407 KB asset, so a
     * per-capture instance pays that every time.
     *
     * Null-tolerant on purpose: construction can fail on an unsupported ABI or a stripped asset,
     * and a capture flow that crashes because segmentation is unavailable is worse than one that
     * degrades to "no reading".
     */
    private val segmenter: TcnSegmenterRunner? by lazy {
        runCatching { TcnSegmenterRunner(appContext) }
            .onFailure { Log.e(TAG, "segmenter unavailable", it) }
            .getOrNull()
    }

    // ── Recording ────────────────────────────────────────────────────────────

    /**
     * Record [seconds] of mono PCM.
     *
     * `UNPROCESSED` first: `MIC` applies AGC and noise suppression, both tuned for speech, and
     * heart sounds fit a noise suppressor's profile of what to remove almost exactly. The fallback
     * is real — not every device offers `UNPROCESSED` — so [Capture.unprocessed] records which one
     * was used. When a recording segments badly that flag is the first thing worth knowing, and it
     * cannot be recovered afterwards.
     */
    fun record(seconds: Int = 20): Capture? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // A denied permission yields zeros rather than an exception, which would reach the
            // segmenter as "silent recording" and be indistinguishable from a dead microphone.
            Log.e(TAG, "RECORD_AUDIO not granted")
            return null
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null

        var unprocessed = true
        var recorder = open(MediaRecorder.AudioSource.UNPROCESSED, minBuffer * 4)
        if (recorder == null) {
            Log.w(TAG, "UNPROCESSED unavailable — falling back to MIC (AGC may be active)")
            unprocessed = false
            recorder = open(MediaRecorder.AudioSource.MIC, minBuffer * 4) ?: return null
        }

        val samples = ShortArray(SAMPLE_RATE * seconds)
        return try {
            recorder.startRecording()
            var read = 0
            while (read < samples.size) {
                val n = recorder.read(samples, read, samples.size - read)
                if (n <= 0) break
                read += n
            }
            if (read < SAMPLE_RATE) null   // under a second is not worth segmenting
            else Capture(samples.copyOf(read), SAMPLE_RATE, unprocessed)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun open(source: Int, bufferBytes: Int): AudioRecord? =
        runCatching {
            AudioRecord(
                source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()

    // ── Segmentation ─────────────────────────────────────────────────────────

    /** Off the main thread: a 20 s recording takes hundreds of milliseconds. */
    suspend fun segment(capture: Capture): Outcome = withContext(Dispatchers.Default) {
        val runner = segmenter ?: return@withContext Outcome.Unavailable

        // Mono here, so `Pcm.toFloat` is enough. For CHANNEL_IN_STEREO the samples are
        // interleaved and must be split first — the segmenter cannot detect that itself:
        //   val mono = Pcm.deinterleaveMono(Pcm.toFloat(samples), channelCount = 2, channel = 0)
        val audio = Pcm.toFloat(capture.samples)

        val result = runner.segment(audio, capture.sampleRate)
            ?: return@withContext Outcome.NoHeartSounds

        // A result is not a quality guarantee — the decoder imposes a legal cycle structure on
        // whatever it is given. Expect roughly one S1 per 0.6-1.0 s; far fewer means it found a
        // few cycles in noise.
        if (result.numCycles < capture.durationSec / 2) Outcome.TooWeak(result)
        else Outcome.Ok(result)
    }

    // ── Reading the result ───────────────────────────────────────────────────

    /**
     * The two things most integrations actually want: beat times and systolic intervals.
     *
     * Peaks rather than onsets: the onset is the decoder's 5 ms frame boundary, while the peak is
     * the band-envelope maximum at 0.5 ms. Our bench work found the peak materially more precise
     * for interval measurement. Use onsets for display and windowing.
     */
    fun beatTimesSec(result: SegmentationResult): List<Double> =
        result.s1PeakSamples2k.map { it / 2000.0 }

    fun heartRateBpm(result: SegmentationResult): Double? {
        val beats = beatTimesSec(result)
        if (beats.size < 2) return null
        val intervals = beats.zipWithNext { a, b -> b - a }
        return 60.0 / intervals.average()
    }

    /** S1 peak → S2 peak, per cycle, in milliseconds. */
    fun systolicIntervalsMs(result: SegmentationResult): List<Double> =
        result.s1PeakSamples2k.zip(result.s2PeakSamples2k) { s1, s2 -> (s2 - s1) / 2.0 }

    /** Per-frame labels are at 200 Hz — 5 ms each — if you want to draw the segmentation. */
    fun stateAtSecond(result: SegmentationResult, t: Double): Int? {
        val frame = (t * result.featFs).toInt()
        return result.stateLabels.getOrNull(frame)
    }

    fun stateName(state: Int): String = when (state) {
        CardiacSegmenter.STATE_S1 -> "S1"
        CardiacSegmenter.STATE_SYSTOLE -> "systole"
        CardiacSegmenter.STATE_S2 -> "S2"
        CardiacSegmenter.STATE_DIASTOLE -> "diastole"
        else -> "unknown"
    }

    override fun close() {
        segmenter?.close()
    }

    // ── Types ────────────────────────────────────────────────────────────────

    data class Capture(
        val samples: ShortArray,
        val sampleRate: Int,
        /** False means AGC and noise suppression may have been applied. Log it. */
        val unprocessed: Boolean
    ) {
        val durationSec: Double get() = samples.size.toDouble() / sampleRate
    }

    sealed interface Outcome {
        data class Ok(val result: SegmentationResult) : Outcome

        /** Enough cycles to decode, too few to trust. Offer a retake. */
        data class TooWeak(val result: SegmentationResult) : Outcome

        /**
         * Too short, too quiet, or nothing cardiac in it. This is a clinical outcome, not an
         * error: do not show a crash dialog, and do not retry the same buffer — the answer will
         * not change. Ask for a new recording.
         */
        data object NoHeartSounds : Outcome

        /** The ONNX session could not be opened at all. A device or packaging problem. */
        data object Unavailable : Outcome
    }

    companion object {
        private const val TAG = "HeartSoundAnalyser"
        private const val SAMPLE_RATE = 44_100
    }
}

/**
 * Usage:
 *
 * ```kotlin
 * val analyser = HeartSoundAnalyser(context)
 * try {
 *     val capture = analyser.record(seconds = 20) ?: return
 *     when (val outcome = analyser.segment(capture)) {
 *         is HeartSoundAnalyser.Outcome.Ok -> {
 *             val r = outcome.result
 *             Log.i("HS", "${r.numCycles} cycles, ${analyser.heartRateBpm(r)} bpm")
 *             Log.i("HS", "systole: ${analyser.systolicIntervalsMs(r)} ms")
 *         }
 *         is HeartSoundAnalyser.Outcome.TooWeak      -> promptRetake("Signal too weak")
 *         HeartSoundAnalyser.Outcome.NoHeartSounds   -> promptRetake("No heart sounds detected")
 *         HeartSoundAnalyser.Outcome.Unavailable     -> showUnsupportedDevice()
 *     }
 * } finally {
 *     analyser.close()
 * }
 * ```
 *
 * Retake is a primary flow, not an error path: roughly a quarter of captures fail quality control.
 * Our own app stops after three attempts — users with a high BMI do not improve with practice, and
 * an unbounded retry loop turns a measurement limit into a personal failure.
 */
private object Usage
