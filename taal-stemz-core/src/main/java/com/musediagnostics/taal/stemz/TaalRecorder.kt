package com.musediagnostics.taal.stemz

import android.content.Context
import android.util.Log
import com.musediagnostics.taal.stemz.core.RecorderState
import com.musediagnostics.taal.stemz.core.TaalAudioCapture
import com.musediagnostics.taal.stemz.dsp.AudioFilterEngine
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Records audio from the TAAL USB stethoscope into two WAV files at once: the raw capture
 * ([setRawAudioFilePath]) and a filtered + pre-amplified copy ([setFilteredAudioFilePath])
 * written in real time from the same samples the live waveform shows.
 *
 * Stemz edition, differences from taal-core's TaalRecorder:
 *  - Filters are [PreFilter.LITE] (20–250 Hz, the default) and [PreFilter.HARD] (20–200 Hz),
 *    plus [setCustomBandpass].
 *  - Auto-stop defaults to [DEFAULT_RECORDING_TIME_SECONDS] (15 s), max [MAX_RECORDING_TIME_SECONDS].
 *  - When the time limit stops the recording on its own, the filtered file is finalized exactly
 *    like a manual [stop] (taal-core left its WAV header unfinalized in that case).
 */
class TaalRecorder(private val context: Context) {

    companion object {
        /** Auto-stop used unless [setRecordingTime] is called. */
        const val DEFAULT_RECORDING_TIME_SECONDS = 15
        /** Hard ceiling for [setRecordingTime]. */
        const val MAX_RECORDING_TIME_SECONDS = 300

        // A recording whose peak never exceeds this, measured on the *filtered and
        // pre-amplified* signal (what the waveform/playback show), is treated as silent.
        private const val SILENT_RECORDING_PEAK_THRESHOLD = 0.01f

        private const val TAG = "TAAL_AUDIO_DEBUG"
    }

    private val audioCapture = TaalAudioCapture(context)
    private val filterEngine = AudioFilterEngine()

    private var rawAudioFilePath: String? = null
    private var filteredAudioFilePath: String? = null
    private var recordingTime: Int = DEFAULT_RECORDING_TIME_SECONDS
    private var filterDescription: String = PreFilter.LITE.name
    private var preAmplificationDb: Int = 0
    private var currentState: RecorderState = RecorderState.INITIAL

    @Volatile private var filteredFos: FileOutputStream? = null
    @Volatile private var filteredBytesWritten = 0
    @Volatile private var maxFilteredPeakSeen = 0f

    var onInfoListener: OnInfoListener? = null
    var onLiveStreamListener: OnLiveStreamListener? = null

    init {
        // Make the engine match the documented default (LITE) from the start.
        filterEngine.setPresetFilter(PreFilter.LITE.toDspFilter())

        audioCapture.onStateChange = { state ->
            currentState = state
            // Finalize the filtered file whenever capture stops — manual stop(), time limit,
            // or device unplug. finalizeFilteredFile() is idempotent.
            if (state == RecorderState.STOPPED) finalizeFilteredFile()
            onInfoListener?.onStateChange(state)
        }

        audioCapture.onDeviceDisconnected = {
            onInfoListener?.onDeviceDisconnected()
        }

        audioCapture.onCaptureCompleted = { isFirstSinceConnect ->
            val silent = maxFilteredPeakSeen < SILENT_RECORDING_PEAK_THRESHOLD
            Log.i(TAG, "FILTERED PATH RESULT — maxFilteredPeak=$maxFilteredPeakSeen silent=$silent")
            if (silent) {
                onInfoListener?.onSilentRecordingDetected(isFirstSinceConnect)
            }
        }

        audioCapture.onAudioData = { data, timestamp ->
            onInfoListener?.onRawProgressUpdate(data)

            val filtered = filterEngine.processBlock(data)

            var peak = 0f
            for (sample in filtered) {
                val abs = kotlin.math.abs(sample)
                if (abs > peak) peak = abs
            }
            if (peak > maxFilteredPeakSeen) maxFilteredPeakSeen = peak

            try {
                filteredFos?.let { fos ->
                    val bytes = floatArrayToBytes(filtered)
                    fos.write(bytes)
                    filteredBytesWritten += bytes.size
                }
            } catch (_: Exception) {}

            onInfoListener?.onProgressUpdate(
                TaalAudioCapture.SAMPLE_RATE,
                filtered.size,
                timestamp,
                filtered
            )

            onLiveStreamListener?.onNewStream(floatArrayToBytes(filtered))
        }
    }

    /** Required. Output path for the raw (unfiltered) WAV. Must end in `.wav`. */
    fun setRawAudioFilePath(path: String) {
        checkNotRecording("setRawAudioFilePath")
        require(path.endsWith(".wav", ignoreCase = true)) { "File path must end with .wav — got: $path" }
        rawAudioFilePath = path
    }

    /** Recommended. Output path for the filtered WAV, written in real time. Must end in `.wav`. */
    fun setFilteredAudioFilePath(path: String) {
        checkNotRecording("setFilteredAudioFilePath")
        require(path.endsWith(".wav", ignoreCase = true)) { "File path must end with .wav — got: $path" }
        filteredAudioFilePath = path
    }

    /**
     * Auto-stop time in seconds. Default [DEFAULT_RECORDING_TIME_SECONDS] (15);
     * clamped to 1..[MAX_RECORDING_TIME_SECONDS].
     */
    fun setRecordingTime(seconds: Int) {
        checkNotRecording("setRecordingTime")
        recordingTime = seconds.coerceIn(1, MAX_RECORDING_TIME_SECONDS)
    }

    /** Selects the Lite or Hard heart filter. Mutually exclusive with [setCustomBandpass] — last call wins. */
    fun setPreFilter(filter: PreFilter) {
        checkNotRecording("setPreFilter")
        filterDescription = filter.name
        filterEngine.setPresetFilter(filter.toDspFilter())
    }

    /**
     * Custom band-pass in Hz. lowCut is clamped to >= 1 Hz, highCut to <= 24 000 Hz; if
     * highCut <= lowCut after clamping the call is ignored and the previous filter stays active.
     * Mutually exclusive with [setPreFilter] — last call wins.
     */
    fun setCustomBandpass(lowCut: Double, highCut: Double) {
        checkNotRecording("setCustomBandpass")
        filterDescription = "CUSTOM(${lowCut}-${highCut}Hz)"
        filterEngine.setCustomBandpass(lowCut, highCut)
    }

    /** Pre-amplification in dB, 0–30. Can be changed during recording. */
    fun setPreAmplification(db: Int) {
        preAmplificationDb = db.coerceIn(0, 30)
        filterEngine.setPreAmplification(preAmplificationDb.toFloat())
    }

    private fun checkNotRecording(method: String) {
        check(currentState != RecorderState.RECORDING) { "Cannot call $method() while recording is in progress" }
    }

    /**
     * Starts recording. Throws [TaalDisconnectedException] if no TAAL device is connected,
     * [IllegalStateException] if already recording or no raw path was set.
     */
    fun start() {
        check(currentState != RecorderState.RECORDING) { "Recording is already in progress — call stop() first" }
        val filePath = checkNotNull(rawAudioFilePath) { "Raw audio file path not set — call setRawAudioFilePath() first" }

        if (!audioCapture.checkUsbConnection()) {
            throw TaalDisconnectedException()
        }

        maxFilteredPeakSeen = 0f
        Log.i(TAG, "DSP CONFIG — filter=$filterDescription preAmpDb=$preAmplificationDb " +
            "recordingTimeSec=$recordingTime")

        filteredAudioFilePath?.let { path ->
            try {
                val fos = FileOutputStream(File(path))
                writeWavHeader(fos)
                filteredFos = fos
                filteredBytesWritten = 0
            } catch (_: Exception) {}
        }

        audioCapture.startRecording(File(filePath), recordingTime)
    }

    /** Stops recording and finalizes both files. No-op if not recording. */
    fun stop() {
        if (currentState != RecorderState.RECORDING) return
        audioCapture.stopRecording()
        finalizeFilteredFile()
    }

    /** Back to INITIAL: clears paths and restores defaults (LITE, 15 s, 0 dB). */
    fun reset() {
        audioCapture.stopRecording()
        finalizeFilteredFile()
        currentState = RecorderState.INITIAL
        rawAudioFilePath = null
        filteredAudioFilePath = null
        recordingTime = DEFAULT_RECORDING_TIME_SECONDS
        filterDescription = PreFilter.LITE.name
        filterEngine.setPresetFilter(PreFilter.LITE.toDspFilter())
        preAmplificationDb = 0
        filterEngine.setPreAmplification(0f)
    }

    fun getState(): RecorderState = currentState

    private fun finalizeFilteredFile() {
        val fos = filteredFos ?: return
        filteredFos = null
        try { fos.flush() } catch (_: Exception) {}

        val bytesWritten = filteredBytesWritten
        filteredBytesWritten = 0
        val path = filteredAudioFilePath ?: return

        // Close + patch the WAV header off the calling thread; the file is already playable.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                fos.close()
                RandomAccessFile(File(path), "rw").use { raf -> updateWavHeader(raf, bytesWritten) }
            } catch (_: Exception) {}
        }
    }

    private fun writeWavHeader(fos: FileOutputStream) {
        val sampleRate = TaalAudioCapture.SAMPLE_RATE
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16
        header[20] = 1
        header[22] = channels.toByte()
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = blockAlign.toByte()
        header[34] = bitsPerSample.toByte()
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        fos.write(header)
    }

    private fun updateWavHeader(raf: RandomAccessFile, dataSize: Int) {
        val fileSize = dataSize + 36
        raf.seek(4)
        raf.write(fileSize and 0xff); raf.write(fileSize shr 8 and 0xff)
        raf.write(fileSize shr 16 and 0xff); raf.write(fileSize shr 24 and 0xff)
        raf.seek(40)
        raf.write(dataSize and 0xff); raf.write(dataSize shr 8 and 0xff)
        raf.write(dataSize shr 16 and 0xff); raf.write(dataSize shr 24 and 0xff)
    }

    private fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val bytes = ByteArray(floats.size * 2)
        for (i in floats.indices) {
            val sample = (floats[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
            bytes[i * 2] = (sample.toInt() and 0xff).toByte()
            bytes[i * 2 + 1] = (sample.toInt() shr 8 and 0xff).toByte()
        }
        return bytes
    }

    interface OnInfoListener {
        fun onStateChange(state: RecorderState)
        /** Filtered, pre-amplified samples (-1..1), on the capture IO thread. */
        fun onProgressUpdate(sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray)
        /** Raw (pre-filter, pre-amp) samples, on the capture IO thread. Default no-op. */
        fun onRawProgressUpdate(data: FloatArray) {}
        /** Main thread. The device was unplugged mid-recording; files are already finalized. */
        fun onDeviceDisconnected() {}
        /** Main thread. Recording finished but the signal never rose above the noise floor. */
        fun onSilentRecordingDetected(isFirstSinceConnect: Boolean) {}
    }

    interface OnLiveStreamListener {
        /** 16-bit little-endian PCM of the filtered signal, on the capture IO thread. */
        fun onNewStream(stream: ByteArray)
    }
}

/** Heart filters available in the stemz SDK. */
enum class PreFilter {
    /** 20–250 Hz — standard heart-sound band. Default. */
    LITE,
    /** 20–200 Hz — narrower, cuts more high-frequency noise. */
    HARD;

    fun toDspFilter(): AudioFilterEngine.PresetFilter = when (this) {
        LITE -> AudioFilterEngine.PresetFilter.LITE
        HARD -> AudioFilterEngine.PresetFilter.HARD
    }
}

class TaalDisconnectedException : Exception("TAAL device not connected")
class TaalNotAvailableForUseException : Exception("TAAL device is in use by another application")
