package com.musediagnostics.taal.core

import android.content.Context
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.musediagnostics.taal.TaalNotAvailableForUseException
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TaalAudioCapture(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_FREQUENCY_HZ = 2000 // Hard limit
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile private var isRecording = false
    @Volatile private var isStopped = false

    // ── TABLET FIX: Buffer size ────────────────────────────────────────────────
    // Previously: getMinBufferSize() * 2
    // Now:        getMinBufferSize() * 4, with an absolute floor of 8192 bytes.
    //
    // WHY: Tablets use mid-range SoCs (Snapdragon 6xx/7xx series across all brands —
    // Samsung, Xiaomi, Lenovo, Huawei, etc.) with different USB audio HAL scheduling
    // compared to flagship phone SoCs. The smaller *2 buffer caused frame drops on
    // tablets — visible as click artifacts or gaps in the waveform during heavy UI
    // rendering. *4 gives the USB audio thread enough headroom on all tablet hardware.
    // This is safe on phones — a larger buffer only means slightly less frequent
    // onAudioData callbacks, which all downstream code already handles correctly.
    private val bufferSize = maxOf(
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 4,
        8192
    )

    // Callbacks
    var onAudioData: ((FloatArray, Double) -> Unit)? = null
    var onStateChange: ((RecorderState) -> Unit)? = null

    fun startRecording(outputFile: File, durationSeconds: Int) {
        if (isRecording) return

        isStopped = false

        // ── TABLET FIX: AudioRecord creation ──────────────────────────────────
        // Previously: AudioRecord(AudioSource.DEFAULT, ...) — single call, no fallback.
        //
        // WHY DEFAULT WAS WRONG FOR TABLETS:
        // AudioSource.DEFAULT lets the OS decide which microphone to use. On phones,
        // Android typically routes DEFAULT to the USB audio device when one is connected.
        // On tablets (all brands — Samsung, Xiaomi, Lenovo, Huawei, OnePlus, etc.),
        // DEFAULT often routes to the built-in microphone array instead, because tablets
        // are designed with multi-mic arrays for voice capture and their audio policy
        // prefers those over USB audio. Android 16 tightened this further — DEFAULT
        // now strictly means "system default input", which on tablets is the internal mics.
        // The result: the TAAL device is ignored and the tablet's own mic is recorded.
        //
        // FIX: Use a priority fallback chain:
        //   1. UNPROCESSED — raw hardware signal, zero OS processing. Best for medical
        //      audio. Available on all Android devices since API 24 (our minSdk).
        //   2. VOICE_RECOGNITION — minimal processing, no beam-forming. Works on devices
        //      that do not expose UNPROCESSED for USB audio class sources.
        //   3. DEFAULT — original behaviour as last resort. Preserves current behaviour
        //      on any device where both options above fail.
        val record = buildAudioRecord()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            // If a USB audio device is present but AudioRecord still failed to init,
            // the device is already claimed by another application.
            if (checkUsbConnection()) {
                throw TaalNotAvailableForUseException()
            }
            throw IllegalStateException("AudioRecord initialization failed - check USB connection")
        }

        audioRecord = record
        record.startRecording()

        // ── TABLET FIX: Disable system audio effects ───────────────────────────
        // WHY: Android can silently attach AGC (Auto Gain Control), Noise Suppressor,
        // and Acoustic Echo Canceller to an AudioRecord session. On tablets across all
        // brands, these effects are tuned for voice calls — their passband is roughly
        // 300–3400 Hz. Heart sounds live in the 20–250 Hz range. These effects treat
        // that range as "low-frequency noise" and attenuate it, producing muffled or
        // inaudible heart sounds even when the correct input device is selected.
        // Each call is guarded by isAvailable() — if the effect does not exist on the
        // device, the call is a safe no-op. This is harmless on phones where these
        // effects are typically not attached to USB audio sources in the first place.
        disableSystemAudioEffects(record.audioSessionId)

        // ── TABLET FIX: Lock routing to USB audio device ───────────────────────
        // WHY: On Android 9+ (API 28), setPreferredDevice() tells the OS "always route
        // this AudioRecord session to this specific input device". Without this, the OS
        // may silently re-route to a different input mid-recording on tablets — for
        // example if a notification triggers a momentary routing switch to the internal
        // mic array. This targets any connected USB audio device (TYPE_USB_DEVICE or
        // TYPE_USB_HEADSET) so it works regardless of tablet brand or Android version.
        // On API < 28 this block is skipped entirely — no change to existing behaviour.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            lockToUsbAudioDevice(record)
        }

        isRecording = true
        onStateChange?.invoke(RecorderState.RECORDING)

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            captureAudioToFile(outputFile, durationSeconds)
        }
    }

    // ── TABLET FIX: Helper functions ───────────────────────────────────────────

    /**
     * Builds an AudioRecord using a priority fallback chain designed to work correctly
     * on all Android tablets regardless of brand (Samsung, Xiaomi, Lenovo, Huawei, etc.).
     *
     * Priority order:
     *  1. UNPROCESSED  — raw signal directly from the USB hardware transducer.
     *                    No OS processing of any kind. Correct for medical audio because
     *                    the TAAL SDK applies its own filters via AudioFilterEngine.
     *  2. VOICE_RECOGNITION — low-processing source, no beam-forming or AGC.
     *                         Fallback for devices that do not expose UNPROCESSED on USB.
     *  3. DEFAULT      — original behaviour. Last resort to ensure the recorder
     *                    always initialises, even on unusual device configurations.
     */
    private fun buildAudioRecord(): AudioRecord {
        // Attempt 1: UNPROCESSED — best for medical audio, available since API 24
        runCatching {
            val r = AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) return r
            r.release()
        }

        // Attempt 2: VOICE_RECOGNITION — minimal processing, no beam-forming
        runCatching {
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) return r
            r.release()
        }

        // Attempt 3: DEFAULT — original behaviour, preserves compatibility on all devices
        return AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )
    }

    /**
     * Explicitly disables system-level audio effects that Android may attach to a
     * recording session automatically. On tablets across all brands, these effects are
     * tuned for voice calls and attenuate the 20–250 Hz range that contains heart sounds.
     *
     * - AutomaticGainControl (AGC): normalises signal amplitude, destroying the
     *   rhythmic pattern of heartbeats by treating quiet beats as "too quiet".
     * - NoiseSuppressor (NS): treats sub-300 Hz content as background noise and
     *   removes it — exactly the range of heart and bowel sounds.
     * - AcousticEchoCanceller (AEC): can corrupt the signal entirely by treating
     *   the stethoscope output as acoustic echo from a speaker.
     *
     * Each call is guarded by isAvailable() so this is a safe no-op on devices
     * where these effects are not present.
     */
    private fun disableSystemAudioEffects(sessionId: Int) {
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)?.apply {
                enabled = false
                release()
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.apply {
                enabled = false
                release()
            }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.apply {
                enabled = false
                release()
            }
        }
    }

    /**
     * Locks the AudioRecord session to the connected USB audio device (API 28+).
     * Checks for both TYPE_USB_DEVICE and TYPE_USB_HEADSET since different tablet
     * brands and Android versions report the TAAL device under either type.
     *
     * Without this, the OS can silently re-route the recording session to the
     * built-in microphone array mid-recording on tablets — for example when a
     * notification fires or another app briefly claims the audio focus.
     *
     * If no USB audio device is found (e.g. TAAL not connected), this is a no-op
     * and the existing TaalDisconnectedException path in start() handles it.
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.P)
    private fun lockToUsbAudioDevice(record: AudioRecord) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val usbDevice = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        if (usbDevice != null) {
            record.preferredDevice = usbDevice
        }
    }

    private suspend fun captureAudioToFile(outputFile: File, durationSeconds: Int) {
        val buffer = ByteArray(bufferSize)
        val floatBuffer = FloatArray(bufferSize / 2) // 16-bit = 2 bytes per sample
        var totalBytesWritten = 0

        FileOutputStream(outputFile).use { fos ->
            // Write WAV header (placeholder, we'll update after recording)
            writeWavHeader(fos, 0, SAMPLE_RATE, 1, 16)

            val startTime = System.currentTimeMillis()
            val endTime = startTime + (durationSeconds * 1000)

            while (isRecording && System.currentTimeMillis() < endTime) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (bytesRead > 0) {
                    // Convert to float for callbacks
                    convertBytesToFloat(buffer, floatBuffer, bytesRead)

                    // Stream to listeners
                    val timestamp = (System.currentTimeMillis() - startTime) / 1000.0
                    onAudioData?.invoke(floatBuffer.copyOf(bytesRead / 2), timestamp)

                    // Write raw data to file
                    fos.write(buffer, 0, bytesRead)
                    totalBytesWritten += bytesRead
                }
            }
        }

        // Update WAV header with actual size using RandomAccessFile
        try {
            RandomAccessFile(outputFile, "rw").use { raf ->
                updateWavHeader(raf, totalBytesWritten)
            }
        } catch (_: Exception) {
            // File may already be closed if Activity was destroyed
        }

        // Release audio resources from the IO thread
        releaseAudioRecord()

        // Notify state change on main thread
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (!isStopped) {
                isStopped = true
                onStateChange?.invoke(RecorderState.STOPPED)
            }
        }
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
            // Already stopped
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
            // Already released
        }
        audioRecord = null
    }

    fun stopRecording() {
        if (!isRecording && isStopped) return
        isRecording = false
        captureJob?.cancel()
        releaseAudioRecord()
        if (!isStopped) {
            isStopped = true
            onStateChange?.invoke(RecorderState.STOPPED)
        }
    }

    private fun convertBytesToFloat(bytes: ByteArray, floats: FloatArray, bytesRead: Int) {
        val buffer = ByteBuffer.wrap(bytes, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until bytesRead / 2) {
            floats[i] = buffer.short.toFloat() / 32768f // Normalize to [-1, 1]
        }
    }

    private fun writeWavHeader(
        fos: FileOutputStream,
        dataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * bitsPerSample / 8

        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        // File size - 8
        val fileSize = dataSize + 36
        header[4] = (fileSize and 0xff).toByte()
        header[5] = (fileSize shr 8 and 0xff).toByte()
        header[6] = (fileSize shr 16 and 0xff).toByte()
        header[7] = (fileSize shr 24 and 0xff).toByte()

        // WAVE header
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // fmt subchunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        // Subchunk1 size (16 for PCM)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        // Audio format (1 = PCM)
        header[20] = 1
        header[21] = 0

        // Number of channels
        header[22] = channels.toByte()
        header[23] = 0

        // Sample rate
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()

        // Byte rate
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()

        // Block align
        val blockAlign = channels * bitsPerSample / 8
        header[32] = blockAlign.toByte()
        header[33] = 0

        // Bits per sample
        header[34] = bitsPerSample.toByte()
        header[35] = 0

        // data subchunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        // Data size
        header[40] = (dataSize and 0xff).toByte()
        header[41] = (dataSize shr 8 and 0xff).toByte()
        header[42] = (dataSize shr 16 and 0xff).toByte()
        header[43] = (dataSize shr 24 and 0xff).toByte()

        fos.write(header)
    }

    private fun updateWavHeader(raf: RandomAccessFile, dataSize: Int) {
        val fileSize = dataSize + 36
        // Update RIFF chunk size at byte 4
        raf.seek(4)
        raf.write(fileSize and 0xff)
        raf.write(fileSize shr 8 and 0xff)
        raf.write(fileSize shr 16 and 0xff)
        raf.write(fileSize shr 24 and 0xff)
        // Update data subchunk size at byte 40
        raf.seek(40)
        raf.write(dataSize and 0xff)
        raf.write(dataSize shr 8 and 0xff)
        raf.write(dataSize shr 16 and 0xff)
        raf.write(dataSize shr 24 and 0xff)
    }

    fun checkUsbConnection(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return false
        val deviceList = usbManager.deviceList

        // Check for USB audio class devices at both device and interface level.
        // Most USB audio devices (including composite devices like TAAL) report
        // class 0 at the device level and USB_CLASS_AUDIO only on their interfaces.
        return deviceList.values.any { device ->
            if (device.deviceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) {
                return@any true
            }
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) {
                    return@any true
                }
            }
            false
        }
    }
}

enum class RecorderState {
    INITIAL, RECORDING, STOPPED
}
