package com.musediagnostics.taal

import android.content.Context
import android.util.Log
import com.musediagnostics.taal.core.TaalAudioCapture
import com.musediagnostics.taal.core.RecorderState
import com.musediagnostics.taal.dsp.AudioFilterEngine
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaalRecorder(private val context: Context) {

    companion object {
        // A recording whose peak never exceeds this, measured on the *filtered and
        // pre-amplified* signal (i.e. exactly what the waveform/playback show the
        // user), is treated as silent. Deliberately NOT measured on the raw capture
        // signal — a quiet-but-valid raw signal boosted by pre-amp gain can look and
        // sound completely normal to the user while still reading as "silent" on the
        // raw scale, which caused false-positive popups. Confirmed 2026-08-14.
        private const val SILENT_RECORDING_PEAK_THRESHOLD = 0.01f

        // FIX 2026-09-02: same tag as TaalAudioCapture so one `adb logcat -s
        // TAAL_AUDIO_DEBUG` gives the whole chain — raw capture AND the filtered/
        // pre-amplified path the user actually sees and hears.
        private const val TAG = "TAAL_AUDIO_DEBUG"
    }

    private val audioCapture = TaalAudioCapture(context)
    private val filterEngine = AudioFilterEngine()

    private var rawAudioFilePath: String? = null
    private var filteredAudioFilePath: String? = null
    private var recordingTime: Int = 30
    private var playbackEnabled: Boolean = false
    private var preFilter: PreFilter = PreFilter.HEART
    private var preAmplificationDb: Int = 0
    private var humRumbleFilterEnabled: Boolean = false
    private var currentState: RecorderState = RecorderState.INITIAL

    @Volatile private var filteredFos: FileOutputStream? = null
    @Volatile private var filteredBytesWritten = 0
    @Volatile private var maxFilteredPeakSeen = 0f

    var onInfoListener: OnInfoListener? = null
    var onLiveStreamListener: OnLiveStreamListener? = null

    init {
        audioCapture.onStateChange = { state ->
            currentState = state
            onInfoListener?.onStateChange(state)
        }

        audioCapture.onDeviceDisconnected = {
            onInfoListener?.onDeviceDisconnected()
        }

        audioCapture.onCaptureCompleted = { isFirstSinceConnect ->
            // FIX 2026-09-02: report the filtered/pre-amplified peak — the level the user
            // actually sees on the waveform — alongside the silence verdict. Comparing this
            // against the raw sessionPeak/sessionRms in the capture summary shows exactly
            // how much the DSP chain contributed.
            val silent = maxFilteredPeakSeen < SILENT_RECORDING_PEAK_THRESHOLD
            Log.i(TAG, "FILTERED PATH RESULT — maxFilteredPeak=$maxFilteredPeakSeen " +
                "silentThreshold=$SILENT_RECORDING_PEAK_THRESHOLD silentVerdict=$silent " +
                "isFirstSinceConnect=$isFirstSinceConnect")
            if (silent) {
                onInfoListener?.onSilentRecordingDetected(isFirstSinceConnect)
            }
        }

        audioCapture.onAudioData = { data, timestamp ->
            // Notify listeners with raw (pre-filter) audio before any DSP is applied.
            // Callers can override onRawProgressUpdate to tap the unprocessed signal
            // (e.g. for AI model input). The default implementation is a no-op.
            onInfoListener?.onRawProgressUpdate(data)

            val filtered = filterEngine.processBlock(data)

            var peak = 0f
            for (sample in filtered) {
                val abs = kotlin.math.abs(sample)
                if (abs > peak) peak = abs
            }
            if (peak > maxFilteredPeakSeen) maxFilteredPeakSeen = peak

            // Write filtered bytes to the filtered file in real-time (same IO thread as recording)
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

            val bytes = floatArrayToBytes(filtered)
            onLiveStreamListener?.onNewStream(bytes)
        }
    }

    fun setRawAudioFilePath(path: String) {
        checkNotRecording("setRawAudioFilePath")
        if (!path.endsWith(".wav", ignoreCase = true)) {
            throw IllegalArgumentException("File path must end with .wav — got: $path")
        }
        rawAudioFilePath = path
    }

    fun setFilteredAudioFilePath(path: String) {
        checkNotRecording("setFilteredAudioFilePath")
        if (!path.endsWith(".wav", ignoreCase = true)) {
            throw IllegalArgumentException("File path must end with .wav — got: $path")
        }
        filteredAudioFilePath = path
    }

    fun setRecordingTime(seconds: Int) {
        checkNotRecording("setRecordingTime")
        recordingTime = seconds.coerceAtLeast(1)
    }

    fun setPlayback(enabled: Boolean) {
        checkNotRecording("setPlayback")
        playbackEnabled = enabled
    }

    fun setPreFilter(filter: PreFilter) {
        checkNotRecording("setPreFilter")
        preFilter = filter
        filterEngine.setPresetFilter(filter.toDspFilter())
    }

    fun setCustomBandpass(lowCut: Double, highCut: Double) {
        checkNotRecording("setCustomBandpass")
        filterEngine.setCustomBandpass(lowCut, highCut)
    }

    /** Opt-in 50/100/150Hz hum + 25Hz rumble filter stage — see AudioFilterEngine doc.
     *  Default false. Fixed per session, like [setPreFilter]/[setCustomBandpass]. */
    fun setHumRumbleFilterEnabled(enabled: Boolean) {
        checkNotRecording("setHumRumbleFilterEnabled")
        humRumbleFilterEnabled = enabled
        filterEngine.setHumRumbleFilterEnabled(enabled)
    }

    fun setPreAmplification(db: Int) {
        preAmplificationDb = db.coerceIn(0, 30)
        filterEngine.setPreAmplification(preAmplificationDb.toFloat())
    }

    private fun checkNotRecording(method: String) {
        if (currentState == RecorderState.RECORDING) {
            throw IllegalStateException("Cannot call $method() while recording is in progress")
        }
    }

    fun start() {
        if (currentState == RecorderState.RECORDING) {
            throw IllegalStateException("Recording is already in progress — call stop() first")
        }

        val filePath = rawAudioFilePath
            ?: throw IllegalStateException("Raw audio file path not set — call setRawAudioFilePath() first")

        if (!audioCapture.checkUsbConnection()) {
            throw TaalDisconnectedException()
        }

        maxFilteredPeakSeen = 0f

        // FIX 2026-09-02: DSP configuration provenance. The raw capture log is pre-DSP, so
        // these settings explain any difference between the raw session numbers and what
        // the user saw/heard — and pre-amp in particular must be known when comparing
        // levels across phones or across recordings.
        Log.i(TAG, "DSP CONFIG — preFilter=$preFilter preAmpDb=$preAmplificationDb " +
            "humRumbleFilter=$humRumbleFilterEnabled recordingTimeSec=$recordingTime " +
            "filteredFile=${filteredAudioFilePath?.substringAfterLast('/') ?: "none"}")

        // Open filtered output file if path is set
        filteredAudioFilePath?.let { path ->
            try {
                val fos = FileOutputStream(File(path))
                writeWavHeader(fos)
                filteredFos = fos
                filteredBytesWritten = 0
            } catch (_: Exception) {}
        }

        val outputFile = File(filePath)
        audioCapture.startRecording(outputFile, recordingTime)
    }

    fun stop() {
        if (currentState != RecorderState.RECORDING) return
        audioCapture.stopRecording()
        finalizeFilteredFile()
    }

    fun reset() {
        audioCapture.stopRecording()
        finalizeFilteredFile()
        currentState = RecorderState.INITIAL
        rawAudioFilePath = null
        filteredAudioFilePath = null
        recordingTime = 30
        playbackEnabled = false
        preFilter = PreFilter.HEART
        preAmplificationDb = 0
        humRumbleFilterEnabled = false
        filterEngine.setHumRumbleFilterEnabled(false)
    }

    fun getState(): RecorderState = currentState

    private fun finalizeFilteredFile() {
        val fos = filteredFos ?: return
        filteredFos = null
        // Flush synchronously so all buffered audio bytes hit disk before we navigate
        try { fos.flush() } catch (_: Exception) {}

        val bytesWritten = filteredBytesWritten
        filteredBytesWritten = 0
        val path = filteredAudioFilePath ?: return

        // Close + update WAV header on IO thread (not critical for playback timing)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                fos.close()
                RandomAccessFile(File(path), "rw").use { raf ->
                    updateWavHeader(raf, bytesWritten)
                }
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
        // File size — placeholder, updated after recording
        header[4] = 0; header[5] = 0; header[6] = 0; header[7] = 0
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = blockAlign.toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        // Data size — placeholder
        header[40] = 0; header[41] = 0; header[42] = 0; header[43] = 0
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
        fun onProgressUpdate(sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray)
        /** Called with raw (pre-filter, pre-amp) audio on the same IO thread as onProgressUpdate. Default is a no-op. */
        fun onRawProgressUpdate(data: FloatArray) {}
        /** Called on the main thread when the USB device disappears mid-recording.
         *  The recording has already been stopped and finalized by the time this fires.
         *  Default is a no-op so existing implementers keep compiling unchanged. */
        fun onDeviceDisconnected() {}
        /** Called on the main thread after a recording completes with no audible signal
         *  captured (peak never rose above the noise floor), even though nothing errored.
         *  [isFirstSinceConnect] is true if this was the first recording attempt since
         *  the TAAL device's current physical USB connection began. Default is a no-op
         *  so existing implementers keep compiling unchanged. */
        fun onSilentRecordingDetected(isFirstSinceConnect: Boolean) {}
    }

    interface OnLiveStreamListener {
        fun onNewStream(stream: ByteArray)
    }
}

enum class PreFilter {
    HEART, LUNGS, BOWEL, PREGNANCY, FULL_BODY;

    fun toDspFilter(): AudioFilterEngine.PresetFilter {
        return when (this) {
            HEART -> AudioFilterEngine.PresetFilter.HEART
            LUNGS -> AudioFilterEngine.PresetFilter.LUNGS
            BOWEL -> AudioFilterEngine.PresetFilter.BOWEL
            PREGNANCY -> AudioFilterEngine.PresetFilter.PREGNANCY
            FULL_BODY -> AudioFilterEngine.PresetFilter.FULL_BODY
        }
    }
}

class TaalDisconnectedException : Exception("TAAL device not connected")
class TaalNotAvailableForUseException : Exception("TAAL device is in use by another application")
