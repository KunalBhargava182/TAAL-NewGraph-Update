package com.musediagnostics.taal

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.musediagnostics.taal.dsp.AudioFilterEngine
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream

class TaalPlayer(private val context: Context) {

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var filterEngine = AudioFilterEngine()

    private var audioFile: File? = null
    @Volatile private var isPlaying = false
    private var isLooping = false

    /**
     * Sample rate read from the WAV header in setDataSource().
     * Defaults to 44100 Hz so clinical recordings work without any header read.
     * For 8kHz AI testing files this becomes 8000 Hz, ensuring AudioTrack plays
     * the file at the correct speed instead of 5.5x too fast.
     */
    private var wavSampleRate = 44100

    var onPlaybackProgress: ((Double, FloatArray) -> Unit)? = null

    fun setDataSource(filePath: String) {
        audioFile = File(filePath)
        if (!audioFile!!.exists() || audioFile!!.extension != "wav") {
            throw InvalidFileNameException()
        }
        // Read the actual sample rate from the WAV header (bytes 24–27, little-endian).
        // This allows TaalPlayer to play both 44100 Hz clinical recordings and
        // 8000 Hz AI testing files at the correct speed without any caller changes.
        wavSampleRate = readWavSampleRate(audioFile!!)
        // Recreate the filter engine so its biquad coefficients are calculated for
        // the correct sample rate. A 44100 Hz filter applied to 8000 Hz audio would
        // have a completely wrong frequency response.
        filterEngine = AudioFilterEngine(wavSampleRate)
    }

    /**
     * Read the sample rate field from a WAV file header.
     *
     * WAV format: bytes 24–27 hold the sample rate as a 32-bit little-endian integer.
     * Falls back to 44100 Hz on any read failure so clinical recordings are unaffected.
     */
    private fun readWavSampleRate(file: File): Int {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(28)
                if (fis.read(header) < 28) return 44100
                val rate = (header[24].toInt() and 0xff) or
                           ((header[25].toInt() and 0xff) shl 8) or
                           ((header[26].toInt() and 0xff) shl 16) or
                           ((header[27].toInt() and 0xff) shl 24)
                if (rate > 0) rate else 44100
            }
        } catch (_: Exception) { 44100 }
    }

    fun setLooping(loop: Boolean) {
        isLooping = loop
    }

    fun setPreFilter(filter: PreFilter) {
        filterEngine.setPresetFilter(filter.toDspFilter())
    }

    fun setCustomBandpass(lowCut: Double, highCut: Double) {
        filterEngine.setCustomBandpass(lowCut, highCut)
    }

    fun setGraphicEQ(eqState: AudioFilterEngine.GraphicEQState) {
        filterEngine.setGraphicEQ(eqState)
    }

    fun setPreAmplification(db: Float) {
        filterEngine.setPreAmplification(db)
    }

    fun prepare() {
        val bufferSize = AudioTrack.getMinBufferSize(
            wavSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(wavSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun start() {
        if (audioFile == null) throw IllegalStateException("Data source not set")

        audioTrack?.play()
        isPlaying = true

        // Use SupervisorJob so that an uncaught exception in playAudioFile() does NOT
        // crash the entire app. The try-catch inside playAudioFile() handles errors
        // gracefully, but this is a safety net for any unexpected runtime exceptions.
        val exceptionHandler = CoroutineExceptionHandler { _, _ ->
            isPlaying = false
        }
        playbackJob = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler).launch {
            playAudioFile()
        }
    }

    private suspend fun playAudioFile() {
        val file = audioFile ?: return

        try {
            do {
                FileInputStream(file).use { fis ->
                    // Skip WAV header (44 bytes)
                    fis.skip(44)

                    val buffer = ByteArray(4096)
                    val floatBuffer = FloatArray(2048)
                    val startTime = System.currentTimeMillis()

                    while (isPlaying) {
                        val bytesRead = fis.read(buffer)
                        if (bytesRead <= 0) break

                        // Need at least 2 bytes (1 PCM sample) to process
                        val sampleCount = bytesRead / 2
                        if (sampleCount == 0) break

                        // Convert to float for filtering
                        convertBytesToFloat(buffer, floatBuffer, bytesRead)

                        // Apply real-time filtering
                        val filtered = filterEngine.processBlock(
                            floatBuffer.copyOf(sampleCount)
                        )

                        // Convert back to bytes for playback
                        val filteredBytes = convertFloatToBytes(filtered)

                        // Play audio — guard against AudioTrack released on another thread
                        try {
                            audioTrack?.write(filteredBytes, 0, filteredBytes.size)
                        } catch (_: IllegalStateException) {
                            break
                        }

                        // Throttle UI callbacks to ~30 Hz max so the main thread is not
                        // flooded with runOnUiThread posts faster than the display refresh.
                        // Without this, fis.read() + audioTrack.write() complete in
                        // microseconds and fire hundreds of callbacks per second, causing
                        // the waveform to flicker because the main thread can't keep up.
                        val audioFrameDurationMs = (sampleCount * 1000L) / wavSampleRate
                        val minCallbackIntervalMs = 33L // ~30 Hz ceiling
                        if (audioFrameDurationMs < minCallbackIntervalMs) {
                            delay(minCallbackIntervalMs - audioFrameDurationMs)
                        }

                        // Callback with progress
                        val timestamp = (System.currentTimeMillis() - startTime) / 1000.0
                        onPlaybackProgress?.invoke(timestamp, filtered)
                    }
                }
            } while (isLooping && isPlaying)
        } catch (_: Exception) {
            // Any unexpected I/O or DSP exception — stop gracefully, don't crash
        }

        // Playback finished naturally
        isPlaying = false
        try {
            audioTrack?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped
        }

        // Notify UI on main thread that playback ended
        withContext(Dispatchers.Main) {
            onPlaybackComplete?.invoke()
        }
    }

    var onPlaybackComplete: (() -> Unit)? = null

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        try {
            audioTrack?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped
        }
    }

    fun release() {
        isPlaying = false
        playbackJob?.cancel()
        try {
            audioTrack?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
            // Already released
        }
        audioTrack = null
    }

    fun reset() {
        release()
        audioFile = null
        isLooping = false
    }

    private fun convertBytesToFloat(bytes: ByteArray, floats: FloatArray, bytesRead: Int) {
        for (i in 0 until bytesRead / 2) {
            val sample = ((bytes[i * 2 + 1].toInt() shl 8) or
                    (bytes[i * 2].toInt() and 0xff)).toShort()
            floats[i] = sample / 32768f
        }
    }

    private fun convertFloatToBytes(floats: FloatArray): ByteArray {
        val bytes = ByteArray(floats.size * 2)
        for (i in floats.indices) {
            val sample = (floats[i] * 32767).toInt()
                .coerceIn(-32768, 32767).toShort()
            bytes[i * 2] = (sample.toInt() and 0xff).toByte()
            bytes[i * 2 + 1] = (sample.toInt() shr 8 and 0xff).toByte()
        }
        return bytes
    }
}

class InvalidFileNameException : Exception("Invalid file name - only .wav files supported")
