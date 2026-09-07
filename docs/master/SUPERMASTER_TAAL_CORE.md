# SUPERMASTER_TAAL_CORE.md — `taal-core` Module Reference

**Read this before touching any code in `taal-core`.** Verified directly against source as of 2026-09-08 (`git log -1` for this module at time of writing: `2e5c11e` "Audio capture/playback diagnostic logging (tag TAAL_AUDIO_DEBUG)"). Working tree for `taal-core` is clean (no uncommitted changes) at time of writing.

All file paths below are relative to `E:\AndroidProjects\TaalDemoApp\taal-core\` unless given in full.

---

## 1. Module Overview

`taal-core` is the pure audio-engine SDK for TAAL, MUSE Diagnostics' digital stethoscope. It is an Android library module (`com.android.library`) with **no UI dependencies whatsoever** — no MPAndroidChart, no Fragments/Activities, no layout resources. Its entire job is: talk to the TAAL USB audio device, capture PCM audio, apply DSP (bandpass filtering, graphic EQ, pre-amp), write/read WAV files, and play WAV files back.

**Public Kotlin package:** `com.musediagnostics.taal` (declared as the module `namespace` in `build.gradle.kts:7`; also the package of the two top-level public API files `TaalRecorder.kt` / `TaalPlayer.kt`). Internal code lives in sub-packages `com.musediagnostics.taal.core`, `.dsp`, `.utils`.

**Consumers (verified via grep across the repo):**
- `taal-ui-kit` — the only other SDK module; wraps `TaalRecorder`/`TaalPlayer` in Fragments/Activities. It depends on `taal-core` (not verified in this pass beyond the app-level SUPERMASTER.md's module map — trust that doc's dependency graph, this doc owns `taal-core` internals only).
- App modules `app`, `lungs-app`, `stemz-app`, `visualizertaal-app` all import `com.musediagnostics.taal.InvalidFileNameException` directly (see §6), meaning they call `TaalPlayer.setDataSource()` directly rather than exclusively through `taal-ui-kit`.
- `TaalConnectionBroadcastReceiver` (§7) is used directly by recording fragments in `app`, `lungs-app`, `stemz-app`, `visualizertaal-app`, and `taal-ui-kit`.

**Published as:** not a Maven-published artifact — no `maven-publish` plugin in `build.gradle.kts`. Instead it's built to a `.aar` and consumed as a local file. A pre-built copy, **`taal-core.aar`, lives at the repo root** (`E:\AndroidProjects\TaalDemoApp\taal-core.aar`, confirmed present, timestamped **2026-05-28** — see §9, this is stale relative to current source which has 2026-09-02 changes baked in and not yet rebuilt into that root AAR).

**Old prior module:** `taal-sdk` is mentioned in stale docs (`docs/notes/QUICK_START.md`) as a different, older project path (`taal-sdk-project`, unrelated to this repo's history) — confirmed **not** present in `settings.gradle.kts` (only `:taal-core`, `:taal-ui-kit`, `:app`, `:lungs-app`, `:visualizertaal-app`, `:taal-segmentation-core`, `:taal-segmentation`, `:stemz-app` are included) and no `taal-sdk/` directory exists in the working tree. Confirmed genuinely excluded.

---

## 2. Module Config

Full contents of `build.gradle.kts` (39 lines) verified read directly:

- `namespace = "com.musediagnostics.taal"` (line 7)
- `compileSdk = 34` (line 8)
- `minSdk = 24`, `targetSdk = 34` (lines 11–12)
- `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` (line 14)
- `consumerProguardFiles("consumer-rules.pro")` (line 15) — **the file `consumer-rules.pro` is empty (0 bytes)**, confirmed by direct read. No consumer ProGuard rules are actually applied.
- Release build type: `isMinifyEnabled = false` (line 20); `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")` (lines 21–24) — **`proguard-rules.pro` is also empty (0 bytes)**. Since minify is off, this has no practical effect currently, but note for later if minify is ever turned on: no custom rules exist yet.
- Java/Kotlin target: `JavaVersion.VERSION_1_8` / `jvmTarget = "1.8"` (lines 29–35).
- Dependencies (lines 38–50): `androidx.core:core-ktx:1.12.0`, `kotlinx-coroutines-android:1.7.3` + `kotlinx-coroutines-core:1.7.3` (used for the IO-thread capture/playback coroutines), test-only `junit:junit:4.13.2`, `androidx.test.ext:junit:1.1.5`, `espresso-core:3.5.1` (the latter two are unused — the only test file present is a plain JUnit4 test with no Android/Espresso dependency, see §8).
- No MPAndroidChart, no Room, no other UI/data dependency anywhere in this module — confirmed.

**AAR output path (per-module build):** `taal-core/build/outputs/aar/taal-core-release.aar` (does not currently exist on disk — `taal-core/build/` has no `aar` subfolder as of this pass, meaning `assembleRelease` has not been run since the last clean).

**Build command:** `./gradlew :taal-core:assembleRelease` (root SUPERMASTER.md documents the combined command `./gradlew :taal-core:assembleRelease :taal-ui-kit:assembleRelease` for rebuilding both AARs at once).

**Unit test command:** `./gradlew :taal-core:testDebugUnitTest` — ran this during this audit; **BUILD SUCCESSFUL**, all 3 tests in `AudioFilterEngineHumRumbleTest` passed (see §8).

---

## 3. Public API

### 3.1 `TaalRecorder` (`src/main/java/com/musediagnostics/taal/TaalRecorder.kt`, 340 lines)

Constructor: `TaalRecorder(private val context: Context)` (line 15). Internally owns one `TaalAudioCapture` instance and one `AudioFilterEngine` instance (lines 32–33) — these live for the lifetime of the `TaalRecorder` object, not per-recording.

**State machine:** `currentState: RecorderState` (INITIAL / RECORDING / STOPPED, defined in `core/TaalAudioCapture.kt:858-860`), mirrored from `audioCapture.onStateChange` (line 52-55).

**Configuration setters** (all throw `IllegalStateException` if called while `currentState == RecorderState.RECORDING`, via the shared `checkNotRecording()` guard at line 161-165 — exception message names the offending method):
- `setRawAudioFilePath(path: String)` (line 111) — must end in `.wav` (case-insensitive), else throws `IllegalArgumentException`. Sets the path for the **raw** (unfiltered) capture file.
- `setFilteredAudioFilePath(path: String)` (line 119) — same `.wav` validation. Sets the path for the **filtered + pre-amplified** file that TaalRecorder itself writes in real time (see §4/§5 pipeline). This is optional — if never set, no filtered file is produced (see `start()`, line 190: `filteredAudioFilePath?.let { ... }`).
- `setRecordingTime(seconds: Int)` (line 127) — coerced to `>= 1` via `.coerceAtLeast(1)`. Default `30`.
- `setPlayback(enabled: Boolean)` (line 132) — **dead field**: `playbackEnabled` (line 38) is written here and reset in `reset()` (line 216) but is never read anywhere else in this class or grepped anywhere in the repo. Calling this method currently has zero observable effect. Flagged as a gotcha in §9.
- `setPreFilter(filter: PreFilter)` (line 137) — sets the bandpass preset on the shared `filterEngine` via `filter.toDspFilter()` (line 140; the `PreFilter` → `AudioFilterEngine.PresetFilter` mapping is 1:1, see line 327-335). Default preset (before any call) is `PreFilter.HEART` (line 39 initializer) but note the underlying `AudioFilterEngine` itself defaults its `currentPreset` to `PresetFilter.NONE` until `setPresetFilter`/`updateFilters()` runs (see §5) — in practice `TaalRecorder` never calls `setPresetFilter` unless the caller explicitly calls `setPreFilter()`, so **a `TaalRecorder` used without ever calling `setPreFilter()` will record using `PresetFilter.NONE` (20–2000 Hz), not `HEART`**, despite the `preFilter` field defaulting to `HEART`. This is a latent mismatch between the Kotlin-level default and the DSP-engine-level default — flagged in §9.
- `setCustomBandpass(lowCut: Double, highCut: Double)` (line 143) — forwards to `filterEngine.setCustomBandpass()` (see §5 for clamping behavior).
- `setHumRumbleFilterEnabled(enabled: Boolean)` (line 150) — opt-in hum/rumble stage, default `false` (see §5). Added 2026-09-02.
- `setPreAmplification(db: Int)` (line 156) — clamped to `[0, 30]` via `.coerceIn(0, 30)`, forwarded to `filterEngine.setPreAmplification(preAmplificationDb.toFloat())`. Default `0` dB (field initializer line 40 — **not 5 dB**; the 5 dB default mentioned in `MEMORY.md` is an app-level/UI-level convention set by consumer fragments on `onResume`, not a `taal-core` default).

**Lifecycle methods:**
- `start()` (line 167) — Throws `IllegalStateException("Recording is already in progress — call stop() first")` if already recording (line 168-170). Throws `IllegalStateException("Raw audio file path not set...")` if `setRawAudioFilePath()` was never called (line 172-174). **Throws `TaalDisconnectedException`** if `audioCapture.checkUsbConnection()` returns `false` (line 175-177) — this is checked *before* attempting to open the audio device. If a filtered-file path is set, opens that `FileOutputStream` and writes a placeholder 44-byte WAV header immediately (lines 190-197), silently swallowing any `IOException` (`catch (_: Exception) {}` — a failure to open the filtered file is NOT surfaced to the caller). Then calls `audioCapture.startRecording(outputFile, recordingTime)` (line 200), which can itself throw `TaalNotAvailableForUseException` or `IllegalStateException` (see §4).
- `stop()` (line 203) — no-op if not currently recording. Otherwise stops `audioCapture` and finalizes the filtered file (flush + close + rewrite WAV header size fields, done asynchronously on `Dispatchers.IO`, see `finalizeFilteredFile()` line 225-244).
- `reset()` (line 209) — stops recording if active, finalizes the filtered file, and resets **all** configuration fields to their constructor-time defaults (`recordingTime = 30`, `preFilter = PreFilter.HEART`, `preAmplificationDb = 0`, `humRumbleFilterEnabled = false`, etc. — lines 213-220). Note this does **not** reset `filterEngine.currentPreset` back to `PresetFilter.NONE` explicitly except via the hum/rumble flag; the bandpass preset field `preFilter` is reset to `HEART` in the Kotlin object but `filterEngine.setPresetFilter()` is NOT re-invoked inside `reset()`, so the DSP engine keeps whatever bandpass it last had configured until `setPreFilter()` is called again. This is the same HEART-default mismatch noted above, now also present after `reset()`.
- `getState(): RecorderState` (line 223).

**Callbacks (interfaces, both public, set via public vars):**
- `onInfoListener: OnInfoListener?` (line 48) — interface at line 302-317:
  - `onStateChange(state: RecorderState)` — required.
  - `onProgressUpdate(sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray)` — required; `data` here is the **filtered + pre-amplified** signal (post `filterEngine.processBlock()`, line 81 & 99-104).
  - `onRawProgressUpdate(data: FloatArray)` — default no-op; fires with the **pre-filter, pre-amp raw** signal (line 79), same IO thread as the above. Documented as the tap point for e.g. AI-model input pipelines (`AudioDownsampler`/`HeartResampler`, see §5.4).
  - `onDeviceDisconnected()` — default no-op; fires on the **main thread** when the USB device disappears mid-recording. By the time this fires the recording has already been stopped/finalized (comment at line 307-309, corroborated by `TaalAudioCapture.captureAudioToFile()` logic, §4).
  - `onSilentRecordingDetected(isFirstSinceConnect: Boolean)` — default no-op; fires on the **main thread** after a recording completes with the filtered/pre-amplified peak never exceeding `SILENT_RECORDING_PEAK_THRESHOLD = 0.01f` (line 24, companion constant). Deliberately measured on the **filtered+pre-amped** signal, not raw — see the code comment at lines 18-23 for the false-positive rationale (a quiet raw signal boosted by pre-amp can look/sound normal to the user but still read "silent" on the raw scale). `isFirstSinceConnect` distinguishes a genuine "forgot to place the stethoscope" case from a known cold-start quirk on some phones' first read after a fresh USB connection (see `TaalAudioCapture` companion `lastKnownUsbDeviceId`, §4).
- `onLiveStreamListener: OnLiveStreamListener?` (line 49) — single method `onNewStream(stream: ByteArray)` (line 319-321), fired with the filtered+amplified signal re-encoded to 16-bit PCM bytes (line 106-107), same IO thread.

**Internal WAV writer** (`writeWavHeader`/`updateWavHeader`, lines 246-290) — writes a standard 44-byte canonical PCM WAV header (RIFF/WAVE/fmt /data chunks), 16-bit mono, sample rate taken from `TaalAudioCapture.SAMPLE_RATE` (44100). This is used only for the **filtered** file that `TaalRecorder` itself owns; the **raw** file's header is written by `TaalAudioCapture` (§4) using its own near-identical implementation (code duplicated between the two files, not shared).

`PreFilter` enum (top-level in this file, lines 324-336): `HEART, LUNGS, BOWEL, PREGNANCY, FULL_BODY` — **no `NONE` variant** at this public-API level (unlike the underlying `AudioFilterEngine.PresetFilter`, which has a 6th `NONE` value). `toDspFilter()` maps 1:1 to the corresponding `AudioFilterEngine.PresetFilter` value.

### 3.2 `TaalPlayer` (`src/main/java/com/musediagnostics/taal/TaalPlayer.kt`, 334 lines)

Constructor: `TaalPlayer(private val context: Context)` (line 13). Owns its own `AudioFilterEngine` (line 24), **recreated** inside `setDataSource()` (line 52) at the sample rate read from the WAV header — important, since a fresh `AudioFilterEngine(wavSampleRate)` means biquad coefficients are (re)designed for whatever rate that file actually claims, not hardcoded to 44100.

- `setDataSource(filePath: String)` (line 40) — **Throws `InvalidFileNameException`** if the file doesn't exist, or if `audioFile.extension != "wav"` (line 42). **This extension check is case-SENSITIVE** (`File.extension` preserves case; compared with `!=` against the literal lowercase `"wav"`) — a file named `recording.WAV` would fail this check and throw, **unlike** `TaalRecorder.setRawAudioFilePath()`/`setFilteredAudioFilePath()`, which explicitly use `endsWith(".wav", ignoreCase = true)`. This asymmetry is a real, verified inconsistency — flagged in §9.
  - Reads the sample rate from WAV header bytes 24-27 little-endian (`readWavSampleRate()`, lines 71-83); falls back to 44100 on any read failure or non-positive value.
- `setLooping(loop: Boolean)` (line 85).
- `setPreFilter(filter: PreFilter)` (line 89) — same `PreFilter` enum as `TaalRecorder` (shared top-level type).
- `setCustomBandpass(lowCut: Double, highCut: Double)` (line 93).
- `setGraphicEQ(eqState: AudioFilterEngine.GraphicEQState)` (line 97) — **only exposed on `TaalPlayer`, not on `TaalRecorder`** (confirmed: `TaalRecorder.kt` has no `setGraphicEQ` method at all — recordings are never graphic-EQ'd at capture time by this SDK; EQ is a playback-only feature at the `taal-core` level, consistent with `app`'s own `EqualizerFragment` operating post-recording).
- `setPreAmplification(db: Float)` (line 101) — **note the type is `Float` here vs `Int` on `TaalRecorder.setPreAmplification`**. No local clamp in `TaalPlayer`; forwards straight to `filterEngine.setPreAmplification(db)`, which clamps to `[0f, 30f]` internally (§5).
- `prepare()` (line 105) — builds the `AudioTrack` (`MODE_STREAM`, `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`, mono 16-bit PCM at `wavSampleRate`), buffer size from `AudioTrack.getMinBufferSize()`.
- `start()` (line 136) — **Throws `IllegalStateException("Data source not set")`** if `setDataSource()` was never called (line 137). Plays the AudioTrack and launches a `CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)` running `playAudioFile()` (lines 148-150); the `CoroutineExceptionHandler` catches any uncaught exception in the coroutine and just sets `isPlaying = false` — it will **not** propagate to the caller or crash the app, meaning a DSP/IO bug mid-playback fails silently from the caller's perspective (only visible via Logcat `TAAL_AUDIO_DEBUG` tag).
- `playAudioFile()` (private, line 153) — skips the 44-byte WAV header, reads in 4096-byte chunks, converts to float, runs through `filterEngine.processBlock()` (same DSP chain as recording, §5), converts back to 16-bit PCM, writes to `AudioTrack`. Throttles `onPlaybackProgress` callbacks to ~30 Hz max via `delay()` when a chunk plays back faster than that (comment lines 204-213 explains this exists to stop flooding the main thread / UI waveform). Loops the whole file via `do { ... } while (isLooping && isPlaying)` if `setLooping(true)` was called.
- `stop()` (line 279) — sets `isPlaying = false`, cancels the playback job, stops the `AudioTrack` (swallows `IllegalStateException` if already stopped).
- `release()` (line 291) — stop + `audioTrack.release()` + null it out.
- `reset()` (line 307) — `release()` + clears `audioFile` + `isLooping = false`.
- `onPlaybackProgress: ((Double, FloatArray) -> Unit)?` (line 38) — timestamp (seconds since playback start) + filtered/amplified `FloatArray` chunk.
- `onPlaybackComplete: (() -> Unit)?` (line 277) — invoked on the **main thread** (`withContext(Dispatchers.Main)`, line 272-274) when playback finishes naturally (end of file, non-looping) — **not** invoked on an explicit `stop()` call.

Both `TaalRecorder` and `TaalPlayer` log extensively under the shared Logcat tag `"TAAL_AUDIO_DEBUG"` (defined independently as a `private const val TAG` in each of `TaalRecorder.kt:29`, `TaalPlayer.kt:19`, and `TaalAudioCapture.kt:31` — same string literal, not a shared constant, so if one is ever edited without editing the others the "one logcat filter for the whole chain" guarantee documented in the code comments silently breaks).

---

## 4. Audio Capture Internals

`core/TaalAudioCapture.kt` (861 lines) — this file is currently **heavily instrumented with temporary and semi-permanent diagnostic logging** from an active investigation (see §9 and `docs/notes/AUDIO_DIAGNOSTIC_REMEDIATION_TRACKER.md`, not part of this module but essential context). Read the removable-block markers (`── PHASE1/PHASE2 DEBUG INSTRUMENTATION START/END ──`) carefully before assuming any given block is permanent behavior.

**Constants (companion object, lines 24-79):**
- `SAMPLE_RATE = 44100` (line 25)
- `CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO` (line 26)
- `AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT` (line 27)
- `MAX_FREQUENCY_HZ = 2000` (line 28) — commented "Hard limit" but **verified NOT enforced anywhere in this module**: it is never referenced by `AudioFilterEngine.setCustomBandpass()` (which actually clamps its upper bound to `24000.0`, see §5) nor anywhere else in `taal-core`. The only place a 2000 Hz ceiling is actually realized is `AudioFilterEngine.PresetFilter.NONE`'s `highCut = 2000.0` (§5) — a preset value, not an enforced hard limit on custom bandpasses. This is a genuine dead/misleading constant — flagged in §9.
- `USB_CHECK_INTERVAL_MS = 500L` (line 34) — how often the capture loop re-polls USB presence mid-recording.
- `lastKnownUsbDeviceId: Int?` (line 40) — **companion-level (class-level, not instance-level) `@Volatile` state**, deliberately shared across all `TaalAudioCapture` instances in the process so "is this the first recording since physical USB connect" survives the common `new TaalRecorder()` per-recording pattern used by callers.
- `debugForcedAudioSource: Int?` and `debugProbeFloatBeforeRecording: Boolean` (lines 68, 77) — PHASE2 debug toggles for the Samsung-attenuation investigation; both default to their "off"/stock values. A runtime property `adb shell setprop debug.taal.audiosource unprocessed|voice_recognition|default` (read via reflection on `android.os.SystemProperties`, `readDebugSourcePropertyOverride()` line 351-360) takes precedence over the in-code flag and needs no rebuild.

**USB device detection (`checkUsbConnection()`, line 836-855):** iterates `UsbManager.deviceList`, returns `true` if any device (checked at both the whole-device level `device.deviceClass == USB_CLASS_AUDIO` **and** at the per-interface level `device.getInterface(i).interfaceClass == USB_CLASS_AUDIO`) is a USB Audio Class device. Comment notes most USB audio devices including TAAL report class 0 at the device level and only expose `USB_CLASS_AUDIO` on an interface — hence the two-level check. This same logic is duplicated independently in `utils/SurrUtils.isTaalDeviceConnected()` (§7) with an extra "empty device list" early-return.

**Building the `AudioRecord` (`buildAudioRecord()`, lines 363-415):** priority fallback chain: `MediaRecorder.AudioSource.UNPROCESSED` → `VOICE_RECOGNITION` → `DEFAULT`, using `AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)` and checking `.state == AudioRecord.STATE_INITIALIZED`. The PHASE2 debug override (source property/flag) is checked first and, if set and successful, skips this chain entirely; if the forced source fails to init, it falls through to the stock chain anyway (log-only difference).

**Buffer size (`bufferSize`, lines 94-97):** `max(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 4, 8192)` — the `*4` (rather than the more common `*2`) is called out in a comment as the "TABLET FIX", giving tablet USB-audio HAL threads more headroom to reduce frame drops/click artifacts; confirmed by comment as unrelated to (not implicated in) a separate "flat first recording" bug.

**USB routing lock (`lockToUsbAudioDevice()`, lines 480-495, API 28+/`RequiresApi(P)`):** calls `record.preferredDevice = usbDevice` **only when the record is not already routed there** (`record.routedDevice?.id == usbDevice.id` check, line 488). History comment (lines 472-479) states an unconditional call was tried and rejected because it forces a native `restoreRecord_l` teardown/rebuild every time with unpredictable recovery latency (sometimes ~200ms, sometimes recording stayed dead silent for its whole duration) — making it conditional was "100% reliable across every on-device test."

**Disabling system audio effects (`disableSystemAudioEffects()`, lines 421-438, called unconditionally after `record.startRecording()`... actually before, at line 182-183 right before `record.startRecording()`):** creates-then-releases `AutomaticGainControl`, `NoiseSuppressor`, `AcousticEchoCanceler` on the record's session, calling `setEnabled(false)` on each if available. Comment explicitly notes this **cannot** see or disable processing baked into the vendor HAL behind a given `AudioSource` — described as "the leading hypothesis for per-source level changes" in the ongoing Samsung investigation (out of scope for this doc, but relevant context if debugging capture levels).

**Capture loop (`captureAudioToFile()`, private suspend fun, lines 497-687), runs on `Dispatchers.IO`:**
1. Opens `FileOutputStream(outputFile)`, writes a 44-byte placeholder WAV header via the module-local `writeWavHeader()` (lines 735-818, byte-for-byte the same canonical PCM header layout as `TaalRecorder`'s own copy).
2. Loop condition: `isRecording && System.currentTimeMillis() < endTime` where `endTime = startTime + durationSeconds * 1000` — i.e. **the recording auto-stops after the configured duration even if `stop()` is never called**, purely via wall-clock comparison inside the loop (not a scheduled callback/timer).
3. Every `USB_CHECK_INTERVAL_MS` (500ms) inside the loop, re-checks `checkUsbConnection()`; if it now returns `false`, sets `disconnectedMidRecording = true`, `isRecording = false`, and breaks — this is how `onDeviceDisconnected` gets triggered.
4. Each iteration: `audioRecord?.read(buffer, 0, buffer.size)`. Positive reads are converted to `FloatArray` (`convertBytesToFloat()`, little-endian 16-bit → `[-1,1]` normalized float, lines 728-733), fed to `onAudioData?.invoke(floatBuffer.copyOf(bytesRead/2), timestamp)` (the raw-audio callback consumed by `TaalRecorder`'s `init` block), and the **raw untouched bytes** are written straight to `fos` (line 616) — i.e. **the raw file written by `TaalAudioCapture` contains zero DSP processing**; all filtering happens downstream in `TaalRecorder`/`TaalPlayer`, never in the capture layer itself.
5. Negative reads are logged as AudioRecord error codes (deduped so a persistent error doesn't flood logcat); zero reads are silently counted.
6. On loop exit: rewrites the WAV header via `RandomAccessFile` with the real data size (`updateWavHeader()`, lines 820-834), releases the `AudioRecord` (`releaseAudioRecord()`), then on `Dispatchers.Main` fires `onStateChange(STOPPED)` (once, guarded by `isStopped`), `onDeviceDisconnected()` if applicable, and `onCaptureCompleted(isFirstSinceConnect)` if the capture completed normally (`!disconnectedMidRecording && positiveReads > 0`).

**Threading model summary:** `startRecording()` itself runs synchronously on the caller's thread (building the `AudioRecord`, locking routing, disabling effects, calling `record.startRecording()`) — only the actual read/write loop (`captureAudioToFile`) is dispatched to a coroutine on `Dispatchers.IO` (`CoroutineScope(Dispatchers.IO).launch { ... }`, line 201-203, **not** cancellable via `captureJob.cancel()` by design — see the extensive comment at lines 710-720 explaining that `stop()` deliberately does NOT cancel the coroutine job, because doing so would abort the loop's trailing `withContext(Dispatchers.Main) { ... }` callback block at its suspension point, silently breaking `onStateChange`/`onDeviceDisconnected`/`onCaptureCompleted` on every manual stop — confirmed on-device 2026-08-14 per the comment).

`stopRecording()` (lines 703-726) is a no-op if `!isRecording && isStopped`. Otherwise sets `isRecording = false` (which the loop notices on its next iteration and exits naturally), releases the `AudioRecord` immediately from the calling thread, and fires `onStateChange(STOPPED)` if not already fired.

`RecorderState` enum: `INITIAL, RECORDING, STOPPED` (line 858-860, bottom of file, top-level not nested).

---

## 5. DSP / Filtering

`dsp/AudioFilterEngine.kt` (272 lines) is the single DSP engine class, instantiated separately (and independently — no shared state) by `TaalRecorder`, `TaalPlayer`, `AudioDownsampler`, and `HeartResampler`. Constructor: `AudioFilterEngine(private val sampleRate: Int = 44100)` (line 5) — the sample rate is fixed at construction and all biquad coefficient math is derived from it; there is no way to change it after construction (confirmed by `TaalPlayer` discarding and recreating the whole engine in `setDataSource()` rather than mutating an existing one).

### 5.1 `PresetFilter` enum — verified exact Hz ranges (lines 8-12)

```kotlin
enum class PresetFilter(val lowCut: Double, val highCut: Double) {
    HEART(20.0, 250.0), LUNGS(100.0, 600.0), BOWEL(100.0, 600.0), // Same as lungs clinically
    PREGNANCY(20.0, 250.0), // Same as heart
    FULL_BODY(20.0, 600.0), NONE(20.0, 2000.0) // Full range but respect hard limit
}
```

| Preset | Low cut | High cut | Note |
|---|---|---|---|
| HEART | 20 Hz | 250 Hz | |
| LUNGS | 100 Hz | 600 Hz | |
| BOWEL | 100 Hz | 600 Hz | Literally identical values to LUNGS — in-line comment says "Same as lungs clinically", not a separate design |
| PREGNANCY | 20 Hz | 250 Hz | Literally identical values to HEART — in-line comment says "Same as heart" |
| FULL_BODY | 20 Hz | 600 Hz | |
| NONE | 20 Hz | 2000 Hz | This is the engine's default (`currentPreset` field initializer, line 23) before any `setPresetFilter()` call. **Not** exposed via the public `PreFilter` enum in `TaalRecorder.kt`/`TaalPlayer.kt` — only reachable if a caller uses `AudioFilterEngine.PresetFilter.NONE` directly (e.g. as the unit test in §8 does), or implicitly, by never calling `setPreFilter()` at all (see §3.1's HEART-default mismatch note). |

This **confirms and refines** the root `SUPERMASTER.md`'s brief mention of "PreFilter enum (HEART/LUNGS/BOWEL/PREGNANCY/FULL_BODY)" — that doc did not state the actual Hz ranges; they are recorded above from direct source, and the BOWEL≡LUNGS / PREGNANCY≡HEART duplication is worth knowing since it means there are really only **3 distinct frequency bands** across 5 named presets.

### 5.2 Biquad bandpass math (`designBandpass()`, lines 131-151)

This is a **2nd-order Constant-Skirt-Gain (peak gain = Q) RBJ-cookbook bandpass biquad**, derived as follows (verified line-by-line):

```
centerFreq = sqrt(lowCut * highCut)      // geometric mean of the two cutoffs
bandwidth  = highCut - lowCut
Q          = centerFreq / bandwidth
omega      = 2π * centerFreq / fs
cosOmega, sinOmega = cos(omega), sin(omega)
alpha      = sinOmega / (2*Q)
a0         = 1 + alpha

b0 =  alpha / a0
b1 =  0
b2 = -alpha / a0
a1 = -2*cosOmega / a0
a2 = (1 - alpha) / a0
```

For **HEART** (20–250 Hz) at 44100 Hz: centerFreq ≈ 70.71 Hz, bandwidth = 230 Hz, Q ≈ 0.3075. This is a fairly wide/low-Q bandpass, consistent with "20–250 Hz" being a broad clinical band rather than a narrow tone filter.

The in-code comment on line 134 calls this "Butterworth bandpass (2nd order)" — **this label should be treated with mild caution**: the coefficient derivation shown is the standard RBJ audio-EQ-cookbook constant-skirt-gain bandpass formula (this is a very common and reasonable choice for this purpose, and behaves like a 2nd-order bandpass with a Butterworth-like flat-ish passband near center), but it is not verified against the original Butterworth transfer-function derivation in this pass — take the code as authoritative over the comment's exact terminology if it ever matters (e.g. for a regulatory/clinical-accuracy review).

`setCustomBandpass(lowCut, highCut)` (lines 100-105): clamps `lowCut` to `>= 1.0` and `highCut` to `<= 24000.0` (**not** `MAX_FREQUENCY_HZ`'s 2000 — see the §4 discrepancy note), and **silently no-ops** (returns without changing coefficients) if `highCut <= lowCut` after clamping — comment explains this guards against a zero-bandwidth → infinite-Q → NaN-coefficient failure mode.

### 5.3 Hum/rumble filter stage — opt-in, default OFF (added 2026-09-02, lines 44-59, 88-98, 153-190, 238-245)

A separate, **fixed-frequency** (not user/preset-adjustable) cascade, designed once at construction time in `designHumRumbleFilters()` (called from `init {}`, line 79) rather than redesigned on every preset/gain change:
1. Two cascaded RBJ-cookbook high-pass sections at **25 Hz**, with fixed Q values `0.5412` and `1.3066` (lines 155-156) — this two-section cascade is a standard way to build a **4th-order Butterworth high-pass response** from two 2nd-order sections (the specific Q pair matches the classic Butterworth-cascade Q values for a 4-pole design).
2. Three RBJ-cookbook notch biquads at **50 Hz, 100 Hz, 150 Hz**, each with **Q = 30** (lines 157-159) — targeting mains hum and its first two harmonics.

Enabled via `AudioFilterEngine.setHumRumbleFilterEnabled(enabled: Boolean)` (line 96-98) / `TaalRecorder.setHumRumbleFilterEnabled()` (§3.1). When enabled, applied in `processBlock()` **after** the bandpass and graphic EQ stages, **before** the final `tanh()` soft-clip (lines 238-245). Code comment (lines 90-95) flags an **open issue**: if the device's true capture rate is actually 48 kHz mislabeled as 44.1 kHz (referencing the ongoing `TaalAudioCapture.SAMPLE_RATE` investigation), the notch centers would land ~8.8% high — accepted as fine at Q=30 for now, revisit after that root-cause task resolves. This stage is unit-tested (§8) but as of this pass **not wired into any UI toggle inside `taal-core` itself** (it's surfaced in the `app` module's PcgScale recorder screen per the commit message in §9/changelog — outside this module's scope).

### 5.4 5-band graphic EQ (`GraphicEQState`, lines 15-21; `updatePeakingFilter()`, lines 192-219)

```kotlin
data class GraphicEQState(
    val band20Hz: Float = 0f,   // -12dB to +12dB
    val band50Hz: Float = 0f,
    val band100Hz: Float = 0f,
    val band200Hz: Float = 0f,
    val band600Hz: Float = 0f
)
```

Five fixed center frequencies: **20, 50, 100, 200, 600 Hz**. Each band is an independent RBJ-cookbook peaking-EQ biquad with fixed `Q = 1.0` (line 209) and gain in dB. **The `-12dB to +12dB` range in the doc comment is not actually enforced anywhere in `AudioFilterEngine`** — `updatePeakingFilter()` and `setGraphicEQ()` apply whatever `Float` gain value is passed in with no `coerceIn()` call; the ±12 dB figure is purely a UI-level convention presumably enforced by slider bounds in consumer apps, not by this engine. Treat any claim that the engine itself limits EQ gain as false unless a caller-side clamp is separately verified.

Peaking filter math (standard RBJ cookbook, verified):
```
A     = 10^(gainDb/40)          // amplitude from dB
omega = 2π*centerFreq/sampleRate
alpha = sin(omega) / (2*Q)      // Q = 1.0 fixed
a0    = 1 + alpha/A

b0 = (1 + alpha*A) / a0
b1 = -2*cosOmega / a0
b2 = (1 - alpha*A) / a0
a1 = -2*cosOmega / a0
a2 = (1 - alpha/A) / a0
```
Bypass shortcut: if `abs(gainDb) < 0.01f`, coefficients are set to pure unity-gain passthrough (`b0=1, b1=b2=a1=a2=0`, lines 196-202) rather than running the general formula at gainDb≈0 — an optimization, not a behavior difference.

All 5 bands are applied in series (cascade) inside `processBlock()`'s `for ((freq, state) in eqBands)` loop (lines 234-236) — order of iteration follows `Map` insertion order (20, 50, 100, 200, 600 as declared, lines 61-67), though since these are independent linear peaking filters at well-separated center frequencies, cascade order has negligible practical effect on output.

**`setGraphicEQ()` is only called from `TaalPlayer`** (§3.2) — `TaalRecorder` never exposes or calls it, meaning captured/saved WAV files are never graphic-EQ'd at recording time by this SDK.

### 5.5 Pre-amplification — where exactly it sits in the chain

`setPreAmplification(gainDb: Float)` (lines 112-115): `preAmpGainDb = gainDb.coerceIn(0f, 30f)` — **the actual, enforced clamp is 0–30 dB**. Note the field's own doc-comment at line 25 (`private var preAmpGainDb: Float = 0f // 0-10dB`) is **stale/wrong** — it says 0-10dB but the real clamp three lines of logic away is 0-30dB. This matches the root `SUPERMASTER.md`'s claim of a 0–30 dB range (that claim is confirmed correct); it's only this one in-code comment that's out of date.

Applied in `processBlock()` (lines 221-255) as the **very first step**, before the bandpass filter:
```kotlin
val preAmpGain = 10.0.pow(preAmpGainDb / 20.0).toFloat()   // dB -> linear amplitude
for (i in input.indices) {
    var sample = input[i].toDouble() * preAmpGain           // 1. pre-amp
    sample = processBiquad(sample, bandpassCoeffs, ...)     // 2. bandpass
    for ((freq, state) in eqBands) { ... }                  // 3. 5-band EQ cascade
    if (humRumbleFilterEnabled) { ... }                     // 4. opt-in hum/rumble (if enabled)
    output[i] = tanh(sample).toFloat()                      // 5. soft-clip via tanh
}
```
**Full, verified signal chain order: pre-amp gain → bandpass → 5-band graphic EQ → (optional) hum/rumble stage → `tanh()` soft clipper.** The `tanh()` step (line 250) is a soft limiter that replaces an earlier hard `.coerceIn(-1f, 1f)` (visible as a commented-out line 249, `// output[i] = sample.toFloat().coerceIn(-1f, 1f)`), meaning **all output samples are always within (-1, 1)** by construction (tanh's range), not just clamped at the extremes — this smooths rather than clips any pre-amp-induced overdrive.

### 5.6 `AudioDownsampler` and `HeartResampler` (`dsp/AudioDownsampler.kt`, `dsp/HeartResampler.kt`)

Both are **used exclusively by consumer `app`/`stemz-app` code** (`RecordingFragment.kt`, `PlayerFragment.kt` in those modules — confirmed via grep, §9), not by `TaalRecorder`/`TaalPlayer` themselves. They exist in `taal-core` as reusable utility classes for an AI-model-training data pipeline (producing extra downsampled WAV files alongside the normal clinical recording), and are explicitly documented as "STRICTLY ISOLATED" from the main recording/playback pipeline (`HeartResampler.kt` doc comment, lines 6-9).

- `HeartResampler` — fixed 44100→8000 Hz, HEART-bandpass (20-250Hz) anti-alias filter, linear-interpolation resample (ratio 5.5125). Superseded by `AudioDownsampler` per its own doc comment (lines 11-13) but still present and still referenced by consumer code — **not dead code**, both are actively used.
- `AudioDownsampler(outputSampleRate: Int, filterPreset: AudioFilterEngine.PresetFilter)` — generalizes `HeartResampler` to any output rate (documented supported values: 500/1000/2000/3000/4000/8000 Hz) and any preset (HEART or LUNGS anti-alias band). Both use the same linear-interpolation-with-carried-fractional-position algorithm; not thread-safe (single-threaded audio-callback use only, per their doc comments).
- Neither class is unit-tested (see §8 — the only test file targets `AudioFilterEngineHumRumbleTest`).

---

## 6. Exceptions

All three custom exception classes are plain `Exception` subclasses with a fixed message, no custom fields:

| Exception | Defined at | Thrown from | Condition |
|---|---|---|---|
| `TaalDisconnectedException` | `TaalRecorder.kt:338` (message: `"TAAL device not connected"`) | `TaalRecorder.start()` (`TaalRecorder.kt:176`) | `audioCapture.checkUsbConnection()` returns `false` at the moment `start()` is called — i.e. no USB Audio Class device is present at all. |
| `TaalNotAvailableForUseException` | `TaalRecorder.kt:339` (message: `"TAAL device is in use by another application"`) | `TaalAudioCapture.startRecording()` (`core/TaalAudioCapture.kt:150`) | A USB audio device **is** physically present (`checkUsbConnection()` returns `true`), but `AudioRecord` still failed to reach `STATE_INITIALIZED` — inferred to mean another app already has the device claimed exclusively. |
| — (plain `IllegalStateException`) | n/a | `TaalAudioCapture.startRecording()` (`core/TaalAudioCapture.kt:152`), message `"AudioRecord initialization failed - check USB connection"` | Same `AudioRecord` init failure as above, but `checkUsbConnection()` now returns `false` — a race where the device was unplugged in the brief window between `TaalRecorder.start()`'s own check and `AudioRecord` construction. Not a custom exception type — just a generic `IllegalStateException`, worth knowing so callers don't assume every "device unavailable" case is one of the two named exceptions above. |
| `InvalidFileNameException` | `TaalPlayer.kt:333` (message: `"Invalid file name - only .wav files supported"`) | `TaalPlayer.setDataSource()` (`TaalPlayer.kt:43`) | The file at the given path either doesn't exist, or its extension (case-sensitive) isn't exactly `"wav"` (see §3.2's case-sensitivity note). |

**Other exceptions a caller can hit, not custom types:**
- `IllegalStateException` — `TaalRecorder.start()` if already recording (`TaalRecorder.kt:169`) or if no raw file path was set (`TaalRecorder.kt:173`); any `TaalRecorder` setter called mid-recording (`checkNotRecording()`, `TaalRecorder.kt:163`); `TaalPlayer.start()` if `setDataSource()` was never called (`TaalPlayer.kt:137`).
- `IllegalArgumentException` — `TaalRecorder.setRawAudioFilePath()`/`setFilteredAudioFilePath()` if the path doesn't end in `.wav` (`TaalRecorder.kt:114`, `:122`).

**Verified consumer usage:** grepping the whole repo, only `InvalidFileNameException` is ever imported/caught outside `taal-core` (by `app`, `stemz-app`, `lungs-app`, `visualizertaal-app`, `taal-ui-kit`'s various Player/Equalizer fragments — all `catch (e: InvalidFileNameException)`). **`TaalDisconnectedException` and `TaalNotAvailableForUseException` are never imported or caught by name anywhere outside `taal-core` itself** — every consumer call site that invokes `.start()` on a recorder wraps it in a generic `catch (e: Exception)` instead (confirmed for `taal-ui-kit`'s `RecordingFragment.kt:635-638`). This means the specific exception types exist and are thrown correctly, but no consumer currently branches on them by type — worth knowing if you're asked to add differentiated error UI (e.g. "device in use" vs "device unplugged" messaging) since today it would require adding new catch clauses, not modifying existing ones.

---

## 7. Utilities

### 7.1 `utils/SurrUtils.kt` (86 lines) — top-level `object SurrUtils`

- `isTaalDeviceConnected(context: Context): ConnectionStatus` (line 19) — returns one of 4 enum values: `CONNECTED`, `NOT_CONNECTED`, `DEVICE_DOES_NOT_SUPPORT_OTG`, `INVALID_TAAL_CONNECTED`. Logic: if `UsbManager` service is unavailable → `DEVICE_DOES_NOT_SUPPORT_OTG`; if the device list is empty → `NOT_CONNECTED`; else checks for a USB Audio Class device (same device-or-interface-level check as `TaalAudioCapture.checkUsbConnection()`, duplicated independently) → `CONNECTED` if found, else `INVALID_TAAL_CONNECTED` (something is plugged in via USB but it isn't a USB-audio device). **This is a richer, 4-state version of `TaalAudioCapture.checkUsbConnection()`'s boolean** — the two are not unified into one shared implementation despite doing almost the same USB scan; confirmed via grep that `TaalAudioCapture`/`TaalRecorder` use their own boolean `checkUsbConnection()`, not this enum function, internally. `SurrUtils` itself is **only self-referenced** within its own file per grep (§9) — no other file in the entire repo currently calls `SurrUtils.isTaalDeviceConnected()`, `readSampleRate()`, or `getFloatBuffer()`. Effectively dead/unused utility code at present, at least in this repo snapshot.
- `readSampleRate(filePath: String): Int` (line 51) — reads WAV header bytes 24-27 as little-endian int. No fallback on read failure (unlike `TaalPlayer`'s own private equivalent, which defaults to 44100) — will throw on a malformed/too-short file.
- `getFloatBuffer(filePath: String): List<Float>` (line 62) — reads an entire WAV file's PCM data (skipping the 44-byte header) into a `List<Float>` normalized to `[-1,1]`. Loads the whole file into memory as a boxed `Float` list — not suitable for very large files or hot paths.

### 7.2 `utils/TaalConnectionBroadcastReceiver.kt` (41 lines)

`class TaalConnectionBroadcastReceiver(private val listener: TaalConnectionListener) : BroadcastReceiver()`. Listens for exactly two system broadcasts: `UsbManager.ACTION_USB_DEVICE_ATTACHED` → `listener.onTaalConnect()`, `UsbManager.ACTION_USB_DEVICE_DETACHED` → `listener.onTaalDisconnect()` (lines 14-21). **Does not itself inspect whether the attached/detached device is actually a TAAL/USB-audio device** — it fires the callback for *any* USB device attach/detach event system-wide; the name is aspirational, not literally accurate (a consumer plugging in an unrelated USB device, e.g. a flash drive, would also trigger `onTaalConnect()`). Callers are responsible for re-checking `checkUsbConnection()`/`isTaalDeviceConnected()` themselves if they need to confirm it was actually the TAAL device. Provides `register(context)`/`unregister(context)` convenience wrappers around `Context.registerReceiver`/`unregisterReceiver` (lines 24-34) using a plain `IntentFilter` (no manual export-flag handling for Android 13+ `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` — relies on whatever the OS/AndroidManifest defaults resolve to; not verified further in this pass whether this causes issues on API 33+, since that would depend on caller context, not this class alone).

Confirmed by grep: this receiver **is** actively used — by recording fragments in `app`, `lungs-app`, `stemz-app`, `visualizertaal-app`, and `taal-ui-kit` (§1), unlike `SurrUtils` above.

---

## 8. Tests

**Only one test file exists in the entire module:** `src/test/java/com/musediagnostics/taal/dsp/AudioFilterEngineHumRumbleTest.kt` (91 lines) — a plain-JVM JUnit4 test (no Robolectric/Android dependency; the doc comment explicitly notes `AudioFilterEngine` "has no Android dependency", enabling this).

**3 test cases, all covering the hum/rumble stage (§5.3) only:**
1. `` `disabled stage produces bit-identical output to today's processBlock` `` — verifies that explicitly calling `setHumRumbleFilterEnabled(false)` produces byte-identical output to an engine that never had the method called at all (regression guard: the opt-in feature must be truly inert by default).
2. `` `enabled stage attenuates 50Hz mains hum relative to 80Hz passband tone` `` — generates a pure 50 Hz sine and a pure 80 Hz sine, both through the enabled hum/rumble stage, and asserts the 50 Hz tone's RMS (after a 0.5s settle period to let the biquad cascade ring in) is less than 10% of the 80 Hz tone's RMS.
3. `` `enabled stage attenuates sub-25Hz rumble relative to 80Hz passband tone` `` — same structure, 10 Hz vs 80 Hz.

**Run during this audit:** `./gradlew :taal-core:testDebugUnitTest` → **BUILD SUCCESSFUL**, all 3 tests passing as of the current source state.

**No test coverage exists for:** `TaalRecorder`, `TaalPlayer`, `TaalAudioCapture` (any of it — capture loop, USB detection, WAV I/O, exception paths), the bandpass filter design itself (`designBandpass()` — only the hum/rumble notches/highpass are tested), the 5-band graphic EQ, pre-amplification, `SurrUtils`, `TaalConnectionBroadcastReceiver`, `AudioDownsampler`, or `HeartResampler`. Any future work touching those areas has **zero regression safety net** from this module's own test suite.

No `src/androidTest/` directory exists despite `build.gradle.kts` declaring `androidTestImplementation` deps for `androidx.test.ext:junit` and `espresso-core` (lines 48-49) — those dependencies are currently unused dead weight in the Gradle config.

---

## 9. Known Issues / Gotchas

Verified directly against source in this pass — not inherited assumptions:

1. **Root `SUPERMASTER.md`'s brief `taal-core` description is accurate at the class/file level** (all named classes, the exception names, the 0-30dB pre-amp range, the biquad-bandpass-plus-5-band-EQ description all check out) but **omits two source files that exist and are part of the public surface**: `dsp/AudioDownsampler.kt` and `dsp/HeartResampler.kt`. Neither is mentioned in the root doc's `taal-core` bullet list. They are real, actively-used-by-consumers files (§5.6).
2. **`MAX_FREQUENCY_HZ = 2000`** (`TaalAudioCapture.kt:28`) is declared "Hard limit" but is **not enforced** by `AudioFilterEngine.setCustomBandpass()` (which allows up to 24000 Hz) or anywhere else. The only real 2000 Hz ceiling is the `PresetFilter.NONE` preset's own `highCut` value — a preset choice, not a hard-coded safety limit. Do not assume calling `setCustomBandpass()` is bounded by this constant.
3. **`PreFilter` default mismatch:** `TaalRecorder`'s Kotlin field `preFilter` defaults to `PreFilter.HEART`, but the underlying `AudioFilterEngine.currentPreset` defaults to `PresetFilter.NONE` (20-2000Hz) and is only changed by an explicit `setPreFilter()` call. A `TaalRecorder` that's never had `setPreFilter()` called will actually record/play using the **NONE** band, not HEART, despite what the field's default value implies. Same applies after `reset()` — the Kotlin field is reset to `HEART` but `filterEngine.setPresetFilter()` is not re-invoked.
4. **Stale doc-comment on `preAmpGainDb`** (`AudioFilterEngine.kt:25`, `// 0-10dB`) contradicts the actual enforced clamp of `0-30dB` three lines away in `setPreAmplification()` (`AudioFilterEngine.kt:113`). The 30dB figure is the one that's actually true and matches root `SUPERMASTER.md`.
5. **`GraphicEQState`'s documented `-12dB to +12dB` range is not enforced** by the engine (`updatePeakingFilter()`/`setGraphicEQ()` apply whatever gain is passed with no clamp). If any caller ever passes an out-of-range gain, the engine will happily apply it.
6. **`TaalRecorder.setPlayback(enabled: Boolean)` is dead code** — the `playbackEnabled` field it sets is never read anywhere in the codebase.
7. **Case-sensitivity asymmetry:** `TaalRecorder`'s file-path setters accept `.WAV`/`.Wav`/etc. (case-insensitive `endsWith` check), but `TaalPlayer.setDataSource()`'s validation (`audioFile.extension != "wav"`) is case-sensitive and will throw `InvalidFileNameException` on an uppercase-extension file that `TaalRecorder` itself would have happily accepted as an output path.
8. **`SurrUtils` appears to be dead/unused code** in the current repo snapshot — grep found zero call sites outside its own file. Its 4-state `ConnectionStatus` enum is more expressive than `TaalAudioCapture.checkUsbConnection()`'s boolean, but nothing currently uses it.
9. **`TaalConnectionBroadcastReceiver` fires for *any* USB device attach/detach**, not specifically TAAL — its name is aspirational. Callers must re-verify with `checkUsbConnection()` or `SurrUtils.isTaalDeviceConnected()` themselves.
10. **The TAG constant `"TAAL_AUDIO_DEBUG"` is duplicated as three separate `private const val` declarations** (`TaalRecorder.kt`, `TaalPlayer.kt`, `TaalAudioCapture.kt`) rather than one shared constant — the "one logcat filter follows the whole chain" property these files' comments rely on is coincidental string-matching, not enforced by the type system.
11. **Root-level pre-built `taal-core.aar`** (`E:\AndroidProjects\TaalDemoApp\taal-core.aar`) is dated **2026-05-28**, while the module's actual source has commits through **2026-09-02/09-07** (hum/rumble filter, disconnect/silence detection, extensive diagnostic logging — none of that is in the shipped AAR). Anyone consuming that root AAR directly (rather than building `:taal-core` fresh) is on stale code missing several months of fixes. Confirm which one a given build actually links before debugging a discrepancy between "what the docs say" and "what the app does."
12. **`TaalAudioCapture.kt` currently carries heavy, partly-temporary debug instrumentation** (PHASE1/PHASE2 blocks, `debugForcedAudioSource`, `debugProbeFloatBeforeRecording`, the `debug.taal.audiosource` system property) from an active Samsung audio-level investigation (see `docs/notes/AUDIO_DIAGNOSTIC_REMEDIATION_TRACKER.md` and `docs/notes/SAMSUNG_AUDIO_LEVEL_ATTENUATION_DIAGNOSTIC.md`, outside this module's own docs). These blocks are explicitly marked as removable but are live in the file as of this pass — expect them to be edited/removed in a future commit; don't assume their current defaults are permanent API surface.
13. **`consumer-rules.pro` and `proguard-rules.pro` are both empty files (0 bytes).** If minification is ever turned on for a consumer app (or for this module itself), nothing in this module currently protects any of its classes/methods from being stripped or renamed — worth a proactive check before shipping a minified release build that depends on this module.
14. **No test coverage exists for anything except the 2026-09-02 hum/rumble stage** (§8) — the bandpass filter design, pre-amp, graphic EQ, WAV I/O, USB detection, and the entire `TaalRecorder`/`TaalPlayer`/`TaalAudioCapture` public API are all completely untested at the unit level.
15. The in-code comment labeling the bandpass as "Butterworth" (§5.2) is a naming claim not independently re-derived/verified against a textbook Butterworth transfer function in this pass — the actual coefficient formula (RBJ constant-skirt-gain bandpass) is documented verbatim above so a future session can verify precisely, rather than trusting the comment's label.

---

## 10. Changelog

| Date | Change | Notes |
|---|---|---|
| 2026-09-08 | Initial SUPERMASTER_TAAL_CORE.md created | Full source audit of all 8 source files (`TaalRecorder.kt`, `TaalPlayer.kt`, `core/TaalAudioCapture.kt`, `dsp/AudioFilterEngine.kt`, `dsp/AudioDownsampler.kt`, `dsp/HeartResampler.kt`, `utils/SurrUtils.kt`, `utils/TaalConnectionBroadcastReceiver.kt`) plus the one test file, `build.gradle.kts`, both `.pro` files, `settings.gradle.kts`, and the root `SUPERMASTER.md`'s existing brief description. Ran `:taal-core:testDebugUnitTest` (passing). Verified `taal-sdk` module genuinely absent. Found and documented 15 concrete discrepancies/gotchas not previously written down anywhere (§9). |
