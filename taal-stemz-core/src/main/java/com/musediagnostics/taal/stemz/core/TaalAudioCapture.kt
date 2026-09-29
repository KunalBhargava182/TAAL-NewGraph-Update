package com.musediagnostics.taal.stemz.core

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
import com.musediagnostics.taal.stemz.TaalNotAvailableForUseException
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

        // ── PHASE2 DEBUG INSTRUMENTATION START (temporary — delete this block to revert) ──
        // Cross-phone source-attenuation experiment matrix. Edit ONE of these two flags,
        // rebuild+install this module (and whatever app consumes it), run a normal
        // recording, then harvest `adb logcat -s TAAL_AUDIO_DEBUG`. Both default to
        // "off" (stock behavior) — must be reverted to these defaults (or this whole
        // block deleted) before this instrumentation is considered removed.
        //
        //   Config A (stock)              — leave both at their defaults below.
        //   Config B (UNPROCESSED only)    — debugForcedAudioSource = MediaRecorder.AudioSource.UNPROCESSED
        //   Config C (VOICE_RECOGNITION)   — debugForcedAudioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
        //   Config D (DEFAULT only)        — debugForcedAudioSource = MediaRecorder.AudioSource.DEFAULT
        //   Config E (PCM_FLOAT probe)     — debugProbeFloatBeforeRecording = true (independent of the
        //                                     three above; runs a short standalone ENCODING_PCM_FLOAT
        //                                     capture and logs its peak before the normal recording
        //                                     starts — see debugProbeFloatEncodingPeak() below)
        //
        // null = stock fallback chain (UNPROCESSED -> VOICE_RECOGNITION -> DEFAULT), i.e. config A.
        // A non-null value skips the fallback chain entirely and uses exactly that source,
        // falling back to the stock chain only if the forced source itself fails to init
        // (logged loudly either way — see buildAudioRecord()).
        //
        // PREFERRED since 2026-09-02: don't edit this flag — use the runtime property
        // instead (no rebuild needed between configs, rig stays untouched):
        //   adb shell setprop debug.taal.audiosource unprocessed|voice_recognition|default
        // The property, when set to a valid value, takes precedence over this flag.
        // See readDebugSourcePropertyOverride() and the remediation tracker doc.
        @Volatile var debugForcedAudioSource: Int? = null

        // Config E toggle — see debugProbeFloatEncodingPeak(). Runs synchronously on
        // whatever thread calls startRecording() (blocks it for the probe's duration,
        // default 3s) and opens a SECOND, independent AudioRecord alongside the main
        // one being built — on some USB-audio HALs a second concurrent open may itself
        // perturb levels or fail to init, so treat config E results with that caveat
        // and prefer running it as a standalone call (not combined with a real
        // recording) if that's feasible for the test setup.
        @Volatile var debugProbeFloatBeforeRecording: Boolean = false
        // ── PHASE2 DEBUG INSTRUMENTATION END ──
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile private var isRecording = false
    @Volatile private var isStopped = false

    // FIX 2026-09-02: which MediaRecorder.AudioSource buildAudioRecord() actually settled
    // on, so the permanent provenance line in startRecording() can name it. -1 until built.
    private var selectedAudioSource: Int = -1

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
        // FIX 2026-09-02: consolidated session header, so every capture in a log file is
        // self-describing. MODEL/MANUFACTURER are here because the capture-level behaviour
        // under investigation is device-specific and any future fix is device-conditional.
        Log.i(TAG, "════════ CAPTURE SESSION START ════════")
        Log.i(TAG, "device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}" +
            " (${android.os.Build.DEVICE}) androidSdk=${android.os.Build.VERSION.SDK_INT}" +
            " requested=${SAMPLE_RATE}Hz/mono/16bit bufferFrames=${bufferSize / 2}" +
            " durationLimitSec=$durationSeconds file=${outputFile.name}")
        Log.d(TAG, "startRecording() begin — usbConnected=${checkUsbConnection()}")
        // ── PHASE1 DEBUG INSTRUMENTATION START (temporary — delete this line to revert) ──
        logAllInputDevices()
        // ── PHASE1 DEBUG INSTRUMENTATION END ──
        // ── PHASE2 DEBUG INSTRUMENTATION START (temporary — delete this block to revert) ──
        if (debugProbeFloatBeforeRecording) {
            debugProbeFloatEncodingPeak()
        }
        // ── PHASE2 DEBUG INSTRUMENTATION END ──

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
        // ── PHASE1 DEBUG INSTRUMENTATION START (temporary — delete this line to revert) ──
        Log.d(TAG, "AudioRecord built — getSampleRate()=${record.sampleRate} bufferSizeFrames=${bufferSize / 2}")
        // ── PHASE1 DEBUG INSTRUMENTATION END ──
        // FIX 2026-09-02: PERMANENT provenance line — deliberately OUTSIDE the removable
        // PHASE1/PHASE2 blocks. Recordings captured from different sources differ in level
        // by up to ~10x on some devices, so every recording must record which one made it.
        Log.i(TAG, "SOURCE SELECTED: ${audioSourceName(selectedAudioSource)} (id=$selectedAudioSource)" +
            " sessionId=${record.audioSessionId} hwSampleRate=${record.sampleRate}Hz")

        // ── TABLET FIX: Lock routing to USB audio device (API 28+), CONDITIONAL ──
        // Only forces setPreferredDevice() when the record ISN'T already routed to
        // the USB device — see lockToUsbAudioDevice() for the full history of why
        // this must be conditional and not unconditional.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            lockToUsbAudioDevice(record)
            // ── PHASE1 DEBUG INSTRUMENTATION START (temporary — delete this line to revert) ──
            Log.d(TAG, "post-lockToUsbAudioDevice() — routedDevice=${describeDevice(record.routedDevice)}")
            // ── PHASE1 DEBUG INSTRUMENTATION END ──
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

    // FIX 2026-09-02: readable names for the two integer codes that show up in these logs.
    /** Human-readable MediaRecorder.AudioSource name. */
    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        -1 -> "NOT_YET_BUILT"
        else -> "OTHER"
    }

    /** Human-readable AudioRecord.read() negative error code. */
    private fun readErrorName(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        AudioRecord.ERROR -> "ERROR"
        else -> "UNKNOWN_ERROR"
    }

    /** Compact string for logging an AudioDeviceInfo — type + product name, or "null". */
    private fun describeDevice(device: AudioDeviceInfo?): String {
        if (device == null) return "null"
        // ── PHASE1 DEBUG INSTRUMENTATION START (temporary — delete this block to revert) ──
        val rates = runCatching { device.sampleRates.joinToString(",") }.getOrNull() ?: "?"
        val masks = runCatching { device.channelMasks.joinToString(",") }.getOrNull() ?: "?"
        return "${device.type}/${device.productName}/sampleRates=[$rates]/channelMasks=[$masks]"
        // ── PHASE1 DEBUG INSTRUMENTATION END ──
    }

    // ── PHASE1 DEBUG INSTRUMENTATION START (temporary — delete this function to revert) ──
    /** Logs every input device the framework currently offers, per PHASE1 item 5. */
    private fun logAllInputDevices() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            Log.d(TAG, "logAllInputDevices() — AudioManager unavailable")
            return
        }
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        Log.d(TAG, "logAllInputDevices() — ${inputs.size} input device(s):")
        inputs.forEach { d ->
            Log.d(TAG, "  input: id=${d.id} ${describeDevice(d)}")
        }
    }
    // ── PHASE1 DEBUG INSTRUMENTATION END ──

    // ── PHASE2 DEBUG INSTRUMENTATION START (temporary — delete this function to revert) ──
    /**
     * Config E probe (see debugProbeFloatBeforeRecording doc above). Opens a SECOND,
     * independent AudioRecord using ENCODING_PCM_FLOAT instead of PCM_16BIT, reads for
     * [durationMs], and logs the raw peak seen. Entirely separate from the main
     * recording/file-writing pipeline — does not touch audioRecord/isRecording state
     * and does not write a file, so it's safe to call standalone (recommended) or via
     * debugProbeFloatBeforeRecording (blocks the calling thread for [durationMs]).
     * Uses debugForcedAudioSource if set, else UNPROCESSED, matching whatever config
     * the main recording for this run is also using so the two are comparable.
     */
    fun debugProbeFloatEncodingPeak(durationMs: Long = 3000L) {
        val floatBufferSizeBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AudioFormat.ENCODING_PCM_FLOAT
        )
        if (floatBufferSizeBytes <= 0) {
            Log.w(TAG, "debugProbeFloatEncodingPeak() — ENCODING_PCM_FLOAT unsupported for this config (getMinBufferSize=$floatBufferSizeBytes)")
            return
        }

        val source = debugForcedAudioSource ?: MediaRecorder.AudioSource.UNPROCESSED
        val probe = AudioRecord(
            source, SAMPLE_RATE, CHANNEL_CONFIG, AudioFormat.ENCODING_PCM_FLOAT, floatBufferSizeBytes * 4
        )
        if (probe.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "debugProbeFloatEncodingPeak() — AudioRecord init failed, source=$source encoding=PCM_FLOAT")
            probe.release()
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            findUsbAudioInput()?.let { probe.preferredDevice = it }
        }

        probe.startRecording()
        Log.d(TAG, "debugProbeFloatEncodingPeak() — started, source=$source routedDevice=${describeDevice(probe.routedDevice)}")

        val floatBuf = FloatArray(floatBufferSizeBytes / 4)
        var overallPeak = 0f
        val endAt = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < endAt) {
            val framesRead = probe.read(floatBuf, 0, floatBuf.size, AudioRecord.READ_BLOCKING)
            if (framesRead > 0) {
                for (i in 0 until framesRead) {
                    val abs = kotlin.math.abs(floatBuf[i])
                    if (abs > overallPeak) overallPeak = abs
                }
            }
        }

        probe.stop()
        probe.release()
        Log.d(TAG, "debugProbeFloatEncodingPeak() — done, source=$source overallPeak=$overallPeak (PCM_FLOAT path, ${durationMs}ms)")
    }
    // ── PHASE2 DEBUG INSTRUMENTATION END ──

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
    // ── PHASE2 DEBUG INSTRUMENTATION START (temporary — delete this function to revert) ──
    /**
     * Runtime source override so a full A/B/C/D matrix can be run in ONE session without
     * rebuilding (rig stays untouched between configs — see the remediation tracker doc):
     *   adb shell setprop debug.taal.audiosource unprocessed|voice_recognition|default
     *   adb shell setprop debug.taal.audiosource ""    (clear -> fall through to flag/stock)
     * debug.* properties are shell-settable on user builds. Read via reflection on
     * android.os.SystemProperties (hidden API — acceptable for removable debug code only).
     */
    private fun readDebugSourcePropertyOverride(): Int? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        when ((get.invoke(null, "debug.taal.audiosource") as? String)?.trim()?.lowercase()) {
            "unprocessed", "9" -> MediaRecorder.AudioSource.UNPROCESSED
            "voice_recognition", "6" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            "default", "mic", "0" -> MediaRecorder.AudioSource.DEFAULT
            else -> null
        }
    }.getOrNull()
    // ── PHASE2 DEBUG INSTRUMENTATION END ──

    private fun buildAudioRecord(): AudioRecord {
        // ── PHASE2 DEBUG INSTRUMENTATION START (temporary — delete this block to revert) ──
        val propertySource = readDebugSourcePropertyOverride()
        val forcedSource = propertySource ?: debugForcedAudioSource
        if (forcedSource != null) {
            Log.d(TAG, "buildAudioRecord() — PHASE2 override active: source=$forcedSource " +
                "(from ${if (propertySource != null) "setprop debug.taal.audiosource" else "debugForcedAudioSource flag"})")
        }
        if (forcedSource != null) {
            val r = AudioRecord(forcedSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "buildAudioRecord() — PHASE2 forced source=$forcedSource succeeded")
                selectedAudioSource = forcedSource
                return r
            }
            Log.w(TAG, "buildAudioRecord() — PHASE2 forced source=$forcedSource FAILED to init, falling back to stock chain")
            r.release()
        }
        // ── PHASE2 DEBUG INSTRUMENTATION END ──

        runCatching {
            val r = AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "buildAudioRecord() — UNPROCESSED succeeded")
                selectedAudioSource = MediaRecorder.AudioSource.UNPROCESSED
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
                selectedAudioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
                return r
            }
            r.release()
        }

        Log.d(TAG, "buildAudioRecord() — falling back to DEFAULT")
        selectedAudioSource = MediaRecorder.AudioSource.DEFAULT
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
        // FIX 2026-09-02: this used to be entirely silent. Whether a framework-level AGC
        // exists on this device — and whether we actually managed to turn it off — is
        // central to the capture-level / gain-pumping investigation, so each effect now
        // reports availability, prior state, the setEnabled() return code, and the state
        // afterwards. NOTE: this only covers android.media.audiofx session effects; it
        // CANNOT see or disable processing baked into the vendor HAL behind a given
        // AudioSource, which remains the leading hypothesis for per-source level changes.
        logEffectState("AGC", AutomaticGainControl.isAvailable(), sessionId) {
            AutomaticGainControl.create(sessionId)
        }
        logEffectState("NoiseSuppressor", NoiseSuppressor.isAvailable(), sessionId) {
            NoiseSuppressor.create(sessionId)
        }
        logEffectState("AEC", AcousticEchoCanceler.isAvailable(), sessionId) {
            AcousticEchoCanceler.create(sessionId)
        }
    }

    /**
     * FIX 2026-09-02: shared helper for [disableSystemAudioEffects] — creates the effect,
     * logs its availability/prior-enabled/disable-result, then releases it. Behaviour is
     * unchanged from before (effect ends up disabled and released); only logging is new.
     */
    private fun logEffectState(
        name: String,
        available: Boolean,
        sessionId: Int,
        create: () -> android.media.audiofx.AudioEffect?
    ) {
        if (!available) {
            Log.d(TAG, "audioEffects — $name available=false (no framework effect on this device)")
            return
        }
        val effect = runCatching { create() }.getOrNull()
        if (effect == null) {
            Log.w(TAG, "audioEffects — $name available=true but create(session=$sessionId) returned null/threw")
            return
        }
        val wasEnabled = runCatching { effect.enabled }.getOrNull()
        val rc = runCatching { effect.setEnabled(false) }.getOrNull()
        val nowEnabled = runCatching { effect.enabled }.getOrNull()
        Log.d(TAG, "audioEffects — $name available=true wasEnabled=$wasEnabled " +
            "setEnabled(false)rc=$rc nowEnabled=$nowEnabled")
        runCatching { effect.release() }
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

        // FIX 2026-09-02: the per-second log used to report the peak of only the single
        // ~160ms buffer read that happened to cross the 1s boundary — ~84% of each second's
        // audio was never examined, inflating apparent second-to-second variance in every
        // diagnostic built on this log (see docs/notes/AUDIO_DIAGNOSTIC_REMEDIATION_TRACKER.md).
        // These accumulate across ALL reads since the last log line; field names were
        // deliberately changed (peak= -> windowPeak=/windowRms=) so post-fix log lines are
        // unambiguous vs old undersampled ones.
        var windowPeak = 0f
        var windowSumSquares = 0.0
        var windowSampleCount = 0L
        var windowSum = 0.0          // for DC offset
        var windowClipped = 0L       // samples at/near full scale

        // Whole-session totals, so the summary line states ground truth without post-processing.
        var sessionPeak = 0f
        var sessionSumSquares = 0.0
        var sessionSampleCount = 0L
        var sessionClipped = 0L
        var zeroReadCount = 0
        var readErrorCount = 0
        var lastReadErrorCode = 0

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

                    // FIX 2026-09-02: aggregate peak AND sum-of-squares over every read in
                    // the window, not just the read that crosses the log boundary.
                    for (i in 0 until bytesRead / 2) {
                        val sample = floatBuffer[i]
                        val abs = kotlin.math.abs(sample)
                        if (abs > windowPeak) windowPeak = abs
                        windowSumSquares += sample.toDouble() * sample.toDouble()
                        windowSum += sample.toDouble()
                        if (abs >= 0.999f) windowClipped++
                    }
                    windowSampleCount += bytesRead / 2

                    val now = System.currentTimeMillis()
                    val windowElapsedMs = now - lastLogTime
                    if (windowElapsedMs >= 1000) {
                        lastLogTime = now
                        val windowRms = if (windowSampleCount > 0) {
                            kotlin.math.sqrt(windowSumSquares / windowSampleCount)
                        } else 0.0
                        val windowDc = if (windowSampleCount > 0) windowSum / windowSampleCount else 0.0
                        // Effective rate = samples actually delivered per second of wall clock.
                        // Continuously validates the hardcoded SAMPLE_RATE against reality and
                        // exposes dropped/starved audio that a level-only view would hide.
                        val effectiveRateHz = if (windowElapsedMs > 0) {
                            windowSampleCount * 1000.0 / windowElapsedMs
                        } else 0.0
                        Log.d(TAG, "captureAudioToFile() — t=${now - startTime}ms reads=$totalReads " +
                            "windowPeak=$windowPeak windowRms=$windowRms windowDc=$windowDc " +
                            "windowSamples=$windowSampleCount windowMs=$windowElapsedMs " +
                            "effRateHz=${effectiveRateHz.toInt()} clipped=$windowClipped " +
                            "routedDevice=${describeDevice(audioRecord?.routedDevice)}")

                        // Roll the window into the session totals, then reset the window.
                        if (windowPeak > sessionPeak) sessionPeak = windowPeak
                        sessionSumSquares += windowSumSquares
                        sessionSampleCount += windowSampleCount
                        sessionClipped += windowClipped
                        windowPeak = 0f
                        windowSumSquares = 0.0
                        windowSampleCount = 0L
                        windowSum = 0.0
                        windowClipped = 0L
                    }

                    // Stream to listeners
                    val timestamp = (System.currentTimeMillis() - startTime) / 1000.0
                    onAudioData?.invoke(floatBuffer.copyOf(bytesRead / 2), timestamp)

                    // Write raw data to file
                    fos.write(buffer, 0, bytesRead)
                    totalBytesWritten += bytesRead
                } else if (bytesRead < 0) {
                    // FIX 2026-09-02: a negative return is an AudioRecord error code. It used
                    // to be silently swallowed, which made a hard read failure indistinguishable
                    // from a very quiet recording. Deduped by code so a persistent error can't
                    // flood the log; the total count lands in the session summary.
                    readErrorCount++
                    if (bytesRead != lastReadErrorCode) {
                        lastReadErrorCode = bytesRead
                        Log.e(TAG, "captureAudioToFile() — read() returned ${readErrorName(bytesRead)}" +
                            " ($bytesRead) at t=${System.currentTimeMillis() - startTime}ms")
                    }
                } else {
                    zeroReadCount++
                }
            }

            Log.d(TAG, "captureAudioToFile() — loop ended. totalReads=$totalReads positiveReads=$positiveReads " +
                "firstPositiveReadElapsedMs=$firstPositiveReadElapsedMs " +
                "totalBytesWritten=$totalBytesWritten disconnectedMidRecording=$disconnectedMidRecording")

            // FIX 2026-09-02: fold in whatever partial window was open when the loop ended,
            // then state the whole-session ground truth in one parseable line.
            if (windowPeak > sessionPeak) sessionPeak = windowPeak
            sessionSumSquares += windowSumSquares
            sessionSampleCount += windowSampleCount
            sessionClipped += windowClipped
            val sessionDurationMs = System.currentTimeMillis() - startTime
            val sessionRms = if (sessionSampleCount > 0) {
                kotlin.math.sqrt(sessionSumSquares / sessionSampleCount)
            } else 0.0
            val sessionEffRateHz = if (sessionDurationMs > 0) {
                sessionSampleCount * 1000.0 / sessionDurationMs
            } else 0.0
            Log.i(TAG, "════════ CAPTURE SESSION SUMMARY ════════ " +
                "source=${audioSourceName(selectedAudioSource)} " +
                "durationMs=$sessionDurationMs samples=$sessionSampleCount " +
                "effRateHz=${sessionEffRateHz.toInt()} (nominal=$SAMPLE_RATE) " +
                "sessionPeak=$sessionPeak sessionRms=$sessionRms clippedSamples=$sessionClipped " +
                "reads=$totalReads positive=$positiveReads zero=$zeroReadCount errors=$readErrorCount " +
                "bytesWritten=$totalBytesWritten disconnected=$disconnectedMidRecording")
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
        // FIX 2026-09-02: mark the caller-initiated stop, so a manual stop is
        // distinguishable in the log from a duration-limit or disconnect stop.
        Log.d(TAG, "stopRecording() — requested by caller (isRecording=$isRecording isStopped=$isStopped)")
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
