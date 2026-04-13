package com.musediagnostics.taal

import android.content.Context
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

    private val audioCapture = TaalAudioCapture(context)
    private val filterEngine = AudioFilterEngine()

    private var rawAudioFilePath: String? = null
    private var filteredAudioFilePath: String? = null
    private var recordingTime: Int = 30
    private var playbackEnabled: Boolean = false
    private var preFilter: PreFilter = PreFilter.HEART
    private var preAmplificationDb: Int = 0
    private var currentState: RecorderState = RecorderState.INITIAL

    @Volatile private var filteredFos: FileOutputStream? = null
    @Volatile private var filteredBytesWritten = 0

    var onInfoListener: OnInfoListener? = null
    var onLiveStreamListener: OnLiveStreamListener? = null

    init {
        audioCapture.onStateChange = { state ->
            currentState = state
            onInfoListener?.onStateChange(state)
        }

        audioCapture.onAudioData = { data, timestamp ->
            // Notify listeners with raw (pre-filter) audio before any DSP is applied.
            // Callers can override onRawProgressUpdate to tap the unprocessed signal
            // (e.g. for AI model input). The default implementation is a no-op.
            onInfoListener?.onRawProgressUpdate(data)

            val filtered = filterEngine.processBlock(data)

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
