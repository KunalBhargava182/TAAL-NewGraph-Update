package com.musediagnostics.taal.stemz.segmentation

import android.content.Context
import android.util.Log
import com.purnacardio.signal.pcg.Pcm
import com.purnacardio.signal.pcg.SegmentationResult
import com.purnacardio.signal.pcg.android.TcnSegmenterRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The one place in this codebase that knows about both the TAAL audio pipeline and the
 * PurnaCardio cardiac segmenter (`TcnSegmenterRunner` / `CardiacSegmenter`, in
 * `taal-segmentation-core`).
 *
 * Feed it the RAW TAAL recording — never the `_filtered.wav` companion file. The segmenter
 * band-passes 20-200 Hz internally as part of its own feature extraction; TAAL's
 * `setPreFilter()` output has already been through a bandpass + graphic EQ + pre-amp chain of
 * its own, and filtering audio that has already been filtered shifts the input away from what
 * the model was trained on. Pre-amp gain itself is not a problem the segmenter cares about — the
 * extractor normalises level internally — but clipping is real distortion, so every call here
 * checks for it and logs a warning rather than silently repairing it.
 *
 * `TcnSegmenterRunner` is not thread-safe (one `segment()` call in flight at a time) and costs
 * about 100ms to construct, so this class builds it lazily, once, and reuses it — every public
 * method here serialises through a [Mutex] and dispatches onto [Dispatchers.Default] itself, so
 * callers never need their own dispatcher hop and never need to worry about overlapping calls.
 */
class TaalCardiacSegmentation(context: Context) : Closeable {

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var runner: TcnSegmenterRunner? = null
    private var runnerInitAttempted = false

    /**
     * Segments the RAW TAAL recording at [rawWavFile]. Sample rate and channel count are read
     * from the file's own WAV header — never assumed to be 44,100 Hz mono, even though that is
     * what TAAL actually writes. If [rawWavFile]'s name contains "_filtered", a warning naming
     * the correct (raw) file is logged, but the file is still processed as given.
     */
    suspend fun segmentRawWav(rawWavFile: File, verboseLogging: Boolean = false): SegmentationOutcome {
        warnIfFilteredFile(rawWavFile)
        val wav = withContext(Dispatchers.Default) { readWavPcm16(rawWavFile) }
        return segment(wav.pcm16, wav.sampleRate, wav.channelCount, verboseLogging)
    }

    /**
     * Segments raw 16-bit PCM samples at their true [sampleRate]. Samples are interleaved if
     * [channelCount] is greater than 1 — channels are split via [Pcm.deinterleaveMono], never
     * averaged.
     */
    suspend fun segment(
        pcm16: ShortArray,
        sampleRate: Int,
        channelCount: Int = 1,
        verboseLogging: Boolean = false
    ): SegmentationOutcome = mutex.withLock {
        withContext(Dispatchers.Default) {
            val clippedFrac = clippedFraction(pcm16)
            warnIfClipped(clippedFrac)

            val framesPerChannel = if (channelCount > 0) pcm16.size / channelCount else pcm16.size
            val durationSec = framesPerChannel.toDouble() / sampleRate
            logCapture(sampleRate, channelCount, durationSec, peakDbfs(pcm16), rmsDbfs(pcm16), clippedFrac, verboseLogging)

            val activeRunner = ensureRunner()
            if (activeRunner == null) {
                return@withContext SegmentationOutcome.Unavailable.also { logOutcome(it, verboseLogging) }
            }

            val result = if (channelCount <= 1) {
                activeRunner.segment(pcm16, sampleRate)
            } else {
                val mono = Pcm.deinterleaveMono(Pcm.toFloat(pcm16), channelCount, channel = 0)
                activeRunner.segment(mono, sampleRate)
            }
            classify(result, verboseLogging)
        }
    }

    /**
     * Segments float PCG samples at their true [sampleRate]. Amplitude scale does not matter —
     * raw 16-bit-widened and normalised `[-1,1]` floats produce identical features — only
     * clipping (values at or beyond full scale) is checked and warned on.
     */
    suspend fun segment(
        pcg: FloatArray,
        sampleRate: Int,
        channelCount: Int = 1,
        verboseLogging: Boolean = false
    ): SegmentationOutcome = mutex.withLock {
        withContext(Dispatchers.Default) {
            val mono = if (channelCount > 1) Pcm.deinterleaveMono(pcg, channelCount, channel = 0) else pcg
            val clippedFrac = clippedFractionFloat(mono)
            warnIfClipped(clippedFrac)

            val durationSec = mono.size.toDouble() / sampleRate
            logCapture(sampleRate, channelCount, durationSec, peakDbfsFloat(mono), rmsDbfsFloat(mono), clippedFrac, verboseLogging)

            val activeRunner = ensureRunner()
            if (activeRunner == null) {
                return@withContext SegmentationOutcome.Unavailable.also { logOutcome(it, verboseLogging) }
            }

            classify(activeRunner.segment(mono, sampleRate), verboseLogging)
        }
    }

    override fun close() {
        runner?.close()
        runner = null
    }

    private fun ensureRunner(): TcnSegmenterRunner? {
        if (!runnerInitAttempted) {
            runnerInitAttempted = true
            runner = runCatching { TcnSegmenterRunner(appContext) }
                .onFailure { Log.w(TAG, "TcnSegmenterRunner failed to initialise — segmentation unavailable this session", it) }
                .getOrNull()
        }
        return runner
    }

    private fun classify(result: SegmentationResult?, verboseLogging: Boolean): SegmentationOutcome {
        // A non-null result is not a quality guarantee: the decoder imposes a legal cycle
        // structure on whatever it is given. confidencePerCycle is always empty, so the gate is
        // cycle density instead — not confidence, which nothing populates.
        val outcome = when {
            result == null -> SegmentationOutcome.NoHeartSounds
            result.numCycles < result.durationSec / 2.0 -> SegmentationOutcome.TooWeak(result)
            else -> SegmentationOutcome.Ok(result)
        }
        logOutcome(outcome, verboseLogging)
        return outcome
    }

    private fun warnIfClipped(clippedFrac: Double) {
        if (clippedFrac > CLIPPING_WARN_THRESHOLD) {
            Log.w(
                TAG,
                "TAAL capture is ${"%.2f".format(clippedFrac * 100)}% clipped at full scale — " +
                    "pre-amp gain may be too high for this environment. Consider lowering gain and retaking."
            )
        }
    }

    private fun warnIfFilteredFile(file: File) {
        if (file.name.contains("_filtered", ignoreCase = true)) {
            Log.w(
                TAG,
                "TaalCardiacSegmentation received '${file.name}', which looks like a FILTERED TAAL " +
                    "recording. Feed the RAW file instead (the same recording without '_filtered' in " +
                    "its name) — the segmenter already band-passes internally, and filtering twice " +
                    "shifts the input away from what the model was trained on."
            )
        }
    }

    private fun logCapture(
        sampleRate: Int,
        channelCount: Int,
        durationSec: Double,
        peakDbfs: Double,
        rmsDbfs: Double,
        clippedFrac: Double,
        verboseLogging: Boolean
    ) {
        if (!verboseLogging) return
        Log.d(
            TAG,
            "capture: sampleRate=$sampleRate channels=$channelCount durationSec=%.2f peakDbfs=%.1f rmsDbfs=%.1f clipped=%.3f%%"
                .format(durationSec, peakDbfs, rmsDbfs, clippedFrac * 100)
        )
    }

    private fun logOutcome(outcome: SegmentationOutcome, verboseLogging: Boolean) {
        if (!verboseLogging) return
        val extra = when (outcome) {
            is SegmentationOutcome.Ok -> "numCycles=${outcome.result.numCycles} durationSec=%.2f".format(outcome.result.durationSec)
            is SegmentationOutcome.TooWeak -> "numCycles=${outcome.result.numCycles} durationSec=%.2f".format(outcome.result.durationSec)
            SegmentationOutcome.NoHeartSounds, SegmentationOutcome.Unavailable -> ""
        }
        Log.d(TAG, "outcome=${outcome::class.simpleName} $extra")
    }

    companion object {
        private const val TAG = "TaalCardiacSegmentation"
        private const val CLIPPING_WARN_THRESHOLD = 0.001 // 0.1% of samples at full scale
    }
}

/**
 * Outcome of a segmentation attempt. `null` from the underlying library is a genuine clinical
 * result — "no heart sounds detected" — not an error, so it is modelled explicitly here rather
 * than surfaced as a crash or retried: the answer will not change for the same buffer.
 */
sealed interface SegmentationOutcome {
    /** A trustworthy segmentation. */
    data class Ok(val result: SegmentationResult) : SegmentationOutcome

    /** Decoded, but the cycle count is too low relative to duration to be trustworthy. */
    data class TooWeak(val result: SegmentationResult) : SegmentationOutcome

    /** No legal S1-systole-S2-diastole cycle was found. Retake — the input will not change. */
    data object NoHeartSounds : SegmentationOutcome

    /** The segmentation session never opened (see logs) — e.g. below the ONNX Runtime floor. */
    data object Unavailable : SegmentationOutcome
}

/** Mean instantaneous heart rate across S1-to-S1 peak intervals, or null with fewer than 2 beats. */
val SegmentationResult.heartRateBpm: Double?
    get() {
        val peaks = s1PeakSamples2k
        if (peaks.size < 2) return null
        val meanIntervalSec = peaks.zipWithNext { a, b -> (b - a) / 2000.0 }.average()
        return if (meanIntervalSec > 0) 60.0 / meanIntervalSec else null
    }

/**
 * S1-peak-to-S2-peak interval per cycle, in milliseconds. If the recording starts mid-systole,
 * the first S2 belongs to a cycle whose S1 happened before the recording started — pairing it
 * with this recording's first S1 would produce a negative interval, so any S2 peak at or before
 * the first S1 peak is dropped before pairing index-wise.
 */
val SegmentationResult.systolicIntervalsMs: List<Double>
    get() {
        val firstS1 = s1PeakSamples2k.minOrNull() ?: return emptyList()
        val alignedS2 = s2PeakSamples2k.filter { it > firstS1 }
        val n = minOf(s1PeakSamples2k.size, alignedS2.size)
        return (0 until n).map { (alignedS2[it] - s1PeakSamples2k[it]) / 2.0 }
    }

/** S1 peak times, in seconds from the start of the recording. */
val SegmentationResult.beatTimesSec: List<Double>
    get() = s1PeakSamples2k.map { it / 2000.0 }

/** The decoded state (0=S1, 1=systole, 2=S2, 3=diastole) at time [tSeconds], or null past the end. */
fun SegmentationResult.stateAt(tSeconds: Double): Int? {
    val frame = (tSeconds * featFs).toInt()
    return stateLabels.getOrNull(frame)
}

private data class WavAudio(val sampleRate: Int, val channelCount: Int, val pcm16: ShortArray)

/**
 * Scans RIFF chunks rather than assuming the canonical 44-byte header, so a WAV file with extra
 * metadata chunks before `data` still reads its true sample rate/channel count correctly.
 */
private fun readWavPcm16(file: File): WavAudio {
    RandomAccessFile(file, "r").use { raf ->
        val riff = ByteArray(12)
        raf.readFully(riff)
        require(
            riff[0] == 'R'.code.toByte() && riff[1] == 'I'.code.toByte() &&
                riff[2] == 'F'.code.toByte() && riff[3] == 'F'.code.toByte()
        ) { "${file.name} is not a RIFF/WAVE file" }

        var sampleRate = -1
        var channelCount = -1
        var bitsPerSample = -1
        var dataBytes: ByteArray? = null

        while (raf.filePointer <= raf.length() - 8) {
            val chunkId = ByteArray(4).also { raf.readFully(it) }
            val chunkSize = readLeInt(raf)
            when (String(chunkId, Charsets.US_ASCII)) {
                "fmt " -> {
                    val fmt = ByteArray(chunkSize).also { raf.readFully(it) }
                    channelCount = leShort(fmt, 2)
                    sampleRate = leInt(fmt, 4)
                    bitsPerSample = leShort(fmt, 14)
                }
                "data" -> {
                    dataBytes = ByteArray(chunkSize).also { raf.readFully(it) }
                }
                else -> raf.seek(raf.filePointer + chunkSize)
            }
            if (chunkSize % 2 == 1 && raf.filePointer < raf.length()) raf.seek(raf.filePointer + 1)
        }

        val data = requireNotNull(dataBytes) { "${file.name} has no data chunk" }
        require(sampleRate > 0) { "${file.name} has no fmt chunk" }
        require(bitsPerSample == 16) { "${file.name} is $bitsPerSample-bit; only 16-bit PCM is supported" }

        val shorts = ShortArray(data.size / 2)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) shorts[i] = buffer.short

        return WavAudio(sampleRate, channelCount, shorts)
    }
}

private fun readLeInt(raf: RandomAccessFile): Int {
    val b = ByteArray(4)
    raf.readFully(b)
    return leInt(b, 0)
}

private fun leInt(b: ByteArray, offset: Int): Int =
    (b[offset].toInt() and 0xFF) or
        ((b[offset + 1].toInt() and 0xFF) shl 8) or
        ((b[offset + 2].toInt() and 0xFF) shl 16) or
        ((b[offset + 3].toInt() and 0xFF) shl 24)

private fun leShort(b: ByteArray, offset: Int): Int =
    (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

private fun clippedFraction(pcm16: ShortArray): Double {
    if (pcm16.isEmpty()) return 0.0
    var clipped = 0
    for (s in pcm16) if (s <= -32767 || s >= 32767) clipped++
    return clipped.toDouble() / pcm16.size
}

private fun clippedFractionFloat(pcg: FloatArray): Double {
    if (pcg.isEmpty()) return 0.0
    var clipped = 0
    for (s in pcg) if (abs(s) >= 1.0f) clipped++
    return clipped.toDouble() / pcg.size
}

private fun peakDbfs(pcm16: ShortArray): Double {
    val peak = pcm16.maxOfOrNull { abs(it.toInt()) } ?: 0
    return if (peak == 0) Double.NEGATIVE_INFINITY else 20 * log10(peak / 32768.0)
}

private fun rmsDbfs(pcm16: ShortArray): Double {
    if (pcm16.isEmpty()) return Double.NEGATIVE_INFINITY
    val meanSquare = pcm16.sumOf { val v = it.toDouble() / 32768.0; v * v } / pcm16.size
    val rms = sqrt(meanSquare)
    return if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20 * log10(rms)
}

private fun peakDbfsFloat(pcg: FloatArray): Double {
    val peak = pcg.maxOfOrNull { abs(it) } ?: 0f
    return if (peak <= 0f) Double.NEGATIVE_INFINITY else 20 * log10(peak.toDouble())
}

private fun rmsDbfsFloat(pcg: FloatArray): Double {
    if (pcg.isEmpty()) return Double.NEGATIVE_INFINITY
    val meanSquare = pcg.sumOf { val v = it.toDouble(); v * v } / pcg.size
    val rms = sqrt(meanSquare)
    return if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20 * log10(rms)
}
