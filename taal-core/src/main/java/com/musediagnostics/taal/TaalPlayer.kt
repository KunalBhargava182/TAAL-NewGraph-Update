package com.musediagnostics.taal

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.musediagnostics.taal.dsp.AudioFilterEngine
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream

class TaalPlayer(private val context: Context) {

    companion object {
        // FIX 2026-09-02: same tag as TaalAudioCapture/TaalRecorder so ONE
        // `adb logcat -s TAAL_AUDIO_DEBUG` follows a recording all the way from capture
        // through save to playback of the saved file.
        private const val TAG = "TAAL_AUDIO_DEBUG"
    }

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
        // FIX 2026-09-02: playback-side provenance — which file, how big, what the WAV
        // header claims, and the duration those two imply (16-bit mono assumed, matching
        // everything this SDK writes). Lets a played-back file be tied to the capture
        // session that produced it.
        val f = audioFile!!
        val pcmBytes = (f.length() - 44).coerceAtLeast(0)
        val durationSec = pcmBytes / 2.0 / wavSampleRate
        Log.i(TAG, "════════ PLAYBACK setDataSource ════════ file=${f.name} " +
            "bytes=${f.length()} pcmBytes=$pcmBytes headerSampleRate=${wavSampleRate}Hz " +
            "impliedDurationSec=${"%.2f".format(durationSec)} path=${f.parent}")
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
        // FIX 2026-09-02: confirm the output track really initialised at the rate we asked
        // for — a mismatch here plays the recording at the wrong speed.
        Log.d(TAG, "PLAYBACK prepare() — requestedRate=${wavSampleRate}Hz " +
            "trackSampleRate=${audioTrack?.sampleRate}Hz minBufferBytes=$bufferSize " +
            "state=${audioTrack?.state}")
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

                    // FIX 2026-09-02: mirror the capture-side window/session statistics on
                    // the playback path, measured on the POST-filter samples actually sent
                    // to AudioTrack. Directly comparable to the capture summary, so a level
                    // change between recording and playback is visible instead of inferred.
                    var lastLogTime = startTime
                    var windowPeak = 0f
                    var windowSumSquares = 0.0
                    var windowSampleCount = 0L
                    var sessionPeak = 0f
                    var sessionSumSquares = 0.0
                    var sessionSampleCount = 0L

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

                        // FIX 2026-09-02: accumulate post-filter playback levels.
                        for (s in filtered) {
                            val abs = kotlin.math.abs(s)
                            if (abs > windowPeak) windowPeak = abs
                            windowSumSquares += s.toDouble() * s.toDouble()
                        }
                        windowSampleCount += filtered.size
                        val nowMs = System.currentTimeMillis()
                        val windowElapsedMs = nowMs - lastLogTime
                        if (windowElapsedMs >= 1000) {
                            lastLogTime = nowMs
                            val windowRms = if (windowSampleCount > 0) {
                                kotlin.math.sqrt(windowSumSquares / windowSampleCount)
                            } else 0.0
                            Log.d(TAG, "PLAYBACK — t=${nowMs - startTime}ms " +
                                "windowPeak=$windowPeak windowRms=$windowRms " +
                                "windowSamples=$windowSampleCount windowMs=$windowElapsedMs")
                            if (windowPeak > sessionPeak) sessionPeak = windowPeak
                            sessionSumSquares += windowSumSquares
                            sessionSampleCount += windowSampleCount
                            windowPeak = 0f
                            windowSumSquares = 0.0
                            windowSampleCount = 0L
                        }

                        // Callback with progress
                        val timestamp = (System.currentTimeMillis() - startTime) / 1000.0
                        onPlaybackProgress?.invoke(timestamp, filtered)
                    }

                    // FIX 2026-09-02: fold the open window in and state playback totals.
                    if (windowPeak > sessionPeak) sessionPeak = windowPeak
                    sessionSumSquares += windowSumSquares
                    sessionSampleCount += windowSampleCount
                    val playedMs = System.currentTimeMillis() - startTime
                    val sessionRms = if (sessionSampleCount > 0) {
                        kotlin.math.sqrt(sessionSumSquares / sessionSampleCount)
                    } else 0.0
                    Log.i(TAG, "════════ PLAYBACK PASS SUMMARY ════════ file=${file.name} " +
                        "playedMs=$playedMs samples=$sessionSampleCount " +
                        "playbackPeak=$sessionPeak playbackRms=$sessionRms " +
                        "(post-filter, post-preamp — compare against the CAPTURE SESSION SUMMARY)")
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
        // FIX 2026-09-02: distinguish a user-initiated stop from playback ending naturally.
        Log.d(TAG, "PLAYBACK stop() — requested by caller (wasPlaying=$isPlaying)")
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
