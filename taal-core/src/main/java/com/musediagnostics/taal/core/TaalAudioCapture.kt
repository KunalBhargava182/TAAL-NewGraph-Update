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
import android.util.Log
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

        // Read-only diagnostic tag. `adb logcat -s TAAL_AUDIO_DEBUG` to watch.
        private const val TAG = "TAAL_AUDIO_DEBUG"

        // How often the capture loop checks whether the USB device is still present.
        private const val USB_CHECK_INTERVAL_MS = 500L

        // Companion (class-level, not instance-level) so this survives across the
        // "new TaalRecorder/TaalAudioCapture per recording" pattern most callers use —
        // it needs to persist for the life of the process to correctly tell whether a
        // given recording is the first one since this physical USB connection began.
        @Volatile private var lastKnownUsbDeviceId: Int? = null
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile private var isRecording = false
    @Volatile private var isStopped = false

    // ── TABLET FIX: Buffer size ────────────────────────────────────────────────
    // getMinBufferSize() * 4, floor 8192. Gives tablet USB-audio HAL threads more
    // headroom than *2 — reduces frame drops / click artifacts. Confirmed 2026-08
    // this is NOT implicated in the "flat first recording" bug (isolated in testing).
    private val bufferSize = maxOf(
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 4,
        8192
    )

    // Callbacks
    var onAudioData: ((FloatArray, Double) -> Unit)? = null
    var onStateChange: ((RecorderState) -> Unit)? = null
    /** Fired when the USB device disappears mid-recording. The recording has already
     *  been stopped and cleaned up by the time this fires — see captureAudioToFile(). */
    var onDeviceDisconnected: (() -> Unit)? = null
    /** Fired after a recording completes normally (device stayed connected, at least
     *  one real read happened) — regardless of signal level. Silence detection based
     *  on amplitude belongs upstream in TaalRecorder, which has the filtered/amplified
     *  signal the user actually sees and hears; this raw capture stage only knows the
     *  pre-gain signal, which can legitimately be very quiet and still produce a
     *  perfectly normal recording once pre-amp gain is applied. The Boolean is true
     *  when this was the first recording attempt since this physical USB connection
     *  began (see lastKnownUsbDeviceId) — useful for telling a genuine "forgot to
     *  place the stethoscope" case apart from the known cold-start quirk some
     *  budget-chipset phones show on their very first read from a fresh USB
     *  audio connection. */
    var onCaptureCompleted: ((isFirstSinceConnect: Boolean) -> Unit)? = null

    fun startRecording(outputFile: File, durationSeconds: Int) {
        if (isRecording) return

        isStopped = false
        Log.d(TAG, "startRecording() begin — usbConnected=${checkUsbConnection()}")

        // ── TABLET FIX: AudioRecord creation, priority fallback chain ──────────
        // UNPROCESSED (best for medical audio) → VOICE_RECOGNITION → DEFAULT.
        // Confirmed 2026-08 NOT implicated in the "flat first recording" bug.
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
        Log.d(TAG, "AudioRecord built — state=${record.state} sessionId=${record.audioSessionId} " +
            "routedDevice=${describeDevice(record.routedDevice)}")

        // ── TABLET FIX: Lock routing to USB audio device (API 28+), CONDITIONAL ──
        // Only forces setPreferredDevice() when the record ISN'T already routed to
        // the USB device — see lockToUsbAudioDevice() for the full history of why
        // this must be conditional and not unconditional.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            lockToUsbAudioDevice(record)
        }

        // ── TABLET FIX: Disable system audio effects ───────────────────────────
        // AGC/NoiseSuppressor/AEC are tuned for voice calls and attenuate the
        // 20-250Hz range that contains heart sounds. Confirmed 2026-08 NOT
        // implicated in the "flat first recording" bug.
        disableSystemAudioEffects(record.audioSessionId)

        record.startRecording()
        Log.d(TAG, "record.startRecording() returned — recordingState=${record.recordingState} " +
            "routedDevice=${describeDevice(record.routedDevice)}")

        // Determine whether this is the first recording attempt since this physical
        // USB connection began — compared against the kernel-assigned UsbDevice id,
        // which only changes on a real unplug/replug (stable across multiple
        // recordings on the same connection, even across separate TaalAudioCapture
        // instances, since lastKnownUsbDeviceId is companion/class-level state).
        val currentUsbDeviceId = getConnectedUsbDeviceId()
        val isFirstSinceConnect = currentUsbDeviceId != null && currentUsbDeviceId != lastKnownUsbDeviceId
        lastKnownUsbDeviceId = currentUsbDeviceId
        Log.d(TAG, "startRecording() — currentUsbDeviceId=$currentUsbDeviceId isFirstSinceConnect=$isFirstSinceConnect")

        isRecording = true
        onStateChange?.invoke(RecorderState.RECORDING)

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            captureAudioToFile(outputFile, durationSeconds, isFirstSinceConnect)
        }
    }

    /** Compact string for logging an AudioDeviceInfo — type + product name, or "null". */
    private fun describeDevice(device: AudioDeviceInfo?): String {
        if (device == null) return "null"
        return "${device.type}/${device.productName}"
    }

    /** Finds the connected USB audio input device, if any. */
    private fun findUsbAudioInput(): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    /**
     * The kernel/host-controller-assigned id of the connected USB audio device, or
     * null if none is connected. Unlike AudioDeviceInfo.id (which is an Android
     * audio-framework id), UsbDevice.deviceId is tied to the actual physical USB
     * enumeration — stable while the cable stays connected, and guaranteed to change
     * on a real unplug/replug. That makes it the right signal for "is this the same
     * physical connection as last time" rather than "is this the same logical device".
     */
    private fun getConnectedUsbDeviceId(): Int? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return usbManager.deviceList.values.firstOrNull { device ->
            if (device.deviceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) return@firstOrNull true
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) return@firstOrNull true
            }
            false
        }?.deviceId
    }

    /**
     * Builds an AudioRecord using a priority fallback chain: UNPROCESSED (raw signal,
     * best for medical audio) → VOICE_RECOGNITION (minimal processing) → DEFAULT
     * (original behaviour, last resort).
     */
    private fun buildAudioRecord(): AudioRecord {
        runCatching {
            val r = AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "buildAudioRecord() — UNPROCESSED succeeded")
                return r
            }
            r.release()
        }

        runCatching {
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "buildAudioRecord() — VOICE_RECOGNITION succeeded")
                return r
            }
            r.release()
        }

        Log.d(TAG, "buildAudioRecord() — falling back to DEFAULT")
        return AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )
    }

    /**
     * Disables AGC / NoiseSuppressor / AcousticEchoCanceler on the recording session.
     * Each call is guarded by isAvailable() — safe no-op where the effect doesn't exist.
     */
    private fun disableSystemAudioEffects(sessionId: Int) {
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)?.apply { enabled = false; release() }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.apply { enabled = false; release() }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.apply { enabled = false; release() }
        }
    }

    /**
     * Locks the AudioRecord session to the connected USB audio device (API 28+), but
     * ONLY when it isn't already routed there.
     *
     * History (2026-08): tried always calling setPreferredDevice() unconditionally —
     * REJECTED. It forces a native restoreRecord_l (track teardown/rebuild) every
     * time, and how long that takes to recover was unpredictable on test hardware —
     * sometimes ~200ms, sometimes the entire recording stayed dead silent. Making it
     * conditional (only correct when actually wrong) proved 100% reliable across
     * every on-device test while still protecting tablets that genuinely need the
     * correction (where AudioSource routes to the built-in mic instead of USB).
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.P)
    private fun lockToUsbAudioDevice(record: AudioRecord) {
        val usbDevice = findUsbAudioInput()
        if (usbDevice == null) {
            Log.w(TAG, "lockToUsbAudioDevice() — no USB input device found; recording may fall back to the built-in mic")
            return
        }

        if (record.routedDevice?.id == usbDevice.id) {
            Log.d(TAG, "lockToUsbAudioDevice() — already routed to ${describeDevice(usbDevice)}, skipping setPreferredDevice()")
            return
        }

        record.preferredDevice = usbDevice
        Log.d(TAG, "lockToUsbAudioDevice() — set preferredDevice=${describeDevice(usbDevice)}")
    }

    private suspend fun captureAudioToFile(outputFile: File, durationSeconds: Int, isFirstSinceConnect: Boolean) {
        val buffer = ByteArray(bufferSize)
        val floatBuffer = FloatArray(bufferSize / 2) // 16-bit = 2 bytes per sample
        var totalBytesWritten = 0
        var disconnectedMidRecording = false

        // Read-only diagnostics — no effect on control flow or timing below.
        var totalReads = 0
        var positiveReads = 0
        var firstPositiveReadElapsedMs = -1L

        FileOutputStream(outputFile).use { fos ->
            // Write WAV header (placeholder, we'll update after recording)
            writeWavHeader(fos, 0, SAMPLE_RATE, 1, 16)

            val startTime = System.currentTimeMillis()
            val endTime = startTime + (durationSeconds * 1000)
            var lastLogTime = startTime
            var lastUsbCheckTime = startTime

            while (isRecording && System.currentTimeMillis() < endTime) {
                // Periodic USB-presence check — stop immediately if the device is
                // physically unplugged mid-recording, rather than silently continuing
                // to "record" from whatever the OS falls back to.
                val checkNow = System.currentTimeMillis()
                if (checkNow - lastUsbCheckTime >= USB_CHECK_INTERVAL_MS) {
                    lastUsbCheckTime = checkNow
                    if (!checkUsbConnection()) {
                        Log.w(TAG, "captureAudioToFile() — USB device disconnected mid-recording at t=${checkNow - startTime}ms, stopping")
                        disconnectedMidRecording = true
                        isRecording = false
                        break
                    }
                }

                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                totalReads++

                if (bytesRead > 0) {
                    positiveReads++
                    if (firstPositiveReadElapsedMs < 0) {
                        firstPositiveReadElapsedMs = System.currentTimeMillis() - startTime
                        Log.d(TAG, "captureAudioToFile() — first successful read after ${firstPositiveReadElapsedMs}ms")
                    }

                    // Convert to float for callbacks
                    convertBytesToFloat(buffer, floatBuffer, bytesRead)

                    var peak = 0f
                    for (i in 0 until bytesRead / 2) {
                        val abs = kotlin.math.abs(floatBuffer[i])
                        if (abs > peak) peak = abs
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastLogTime >= 1000) {
                        lastLogTime = now
                        Log.d(TAG, "captureAudioToFile() — t=${now - startTime}ms reads=$totalReads peak=$peak " +
                            "routedDevice=${describeDevice(audioRecord?.routedDevice)}")
                    }

                    // Stream to listeners
                    val timestamp = (System.currentTimeMillis() - startTime) / 1000.0
                    onAudioData?.invoke(floatBuffer.copyOf(bytesRead / 2), timestamp)

                    // Write raw data to file
                    fos.write(buffer, 0, bytesRead)
                    totalBytesWritten += bytesRead
                }
            }

            Log.d(TAG, "captureAudioToFile() — loop ended. totalReads=$totalReads positiveReads=$positiveReads " +
                "firstPositiveReadElapsedMs=$firstPositiveReadElapsedMs " +
                "totalBytesWritten=$totalBytesWritten disconnectedMidRecording=$disconnectedMidRecording")
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

        val completedNormally = !disconnectedMidRecording && positiveReads > 0

        // Notify state change on main thread
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (!isStopped) {
                isStopped = true
                onStateChange?.invoke(RecorderState.STOPPED)
            }
            if (disconnectedMidRecording) {
                onDeviceDisconnected?.invoke()
            }
            if (completedNormally) {
                onCaptureCompleted?.invoke(isFirstSinceConnect)
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

        // Deliberately NOT calling captureJob?.cancel() here. The capture loop already
        // exits cleanly on its own via the isRecording flag above — cancel() adds
        // nothing for stopping it. What it DOES do is corrupt the coroutine's own tail
        // logic: captureAudioToFile() finishes with a withContext(Dispatchers.Main) {}
        // block that fires onStateChange/onDeviceDisconnected/onCaptureCompleted.
        // That's a suspension point, and a cancelled job aborts at the next suspension
        // point — so calling cancel() here silently prevented that whole block (and
        // every callback in it) from ever running whenever the user manually tapped
        // Stop, which is how the vast majority of recordings actually end. Confirmed
        // on-device 2026-08-14: the silent-recording callback's trace logs never
        // appeared after a manual stop, only after this call was removed.
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
