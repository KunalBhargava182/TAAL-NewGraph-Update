# Recording Reliability Fixes — August 2026

This document covers a multi-part investigation and fix session for TAAL recording
reliability issues in `taal-core` / `taal-ui-kit` / `app`. Read this before touching
`TaalAudioCapture.kt`, `TaalRecorder.kt`, or the recording-screen `onDeviceDisconnected`
/ `onSilentRecordingDetected` handlers — several approaches below were tried and
rejected on-device, and re-attempting them will reintroduce known bugs.

## Background

A prior "tablet fix" update (July 2026) changed `TaalAudioCapture` to make recording
work correctly on tablets, which route audio differently than phones. That update
introduced a regression: **the first recording after connecting the TAAL device (or
after the app cold-starts with the device already plugged in) came out completely
flat — no waveform, no sound.** A second recording, without touching the cable,
worked fine. This had never happened before the tablet-fix update, on any device,
including in front of clients.

## Issue 1 — First recording flat when device already connected (FIXED)

**Root cause:** the tablet fix called `AudioRecord.preferredDevice = usbDevice`
*unconditionally* on every recording start, even when the record was already
correctly routed to the USB device. Forcing this call triggers Android's native
`restoreRecord_l` (an internal track teardown/rebuild), and how long that takes to
recover is unpredictable — sometimes ~200ms, sometimes the entire recording stayed
silent. This reproduced 100% of the time when the device was already connected and
correctly routed before the recording started.

**Fix** (`TaalAudioCapture.lockToUsbAudioDevice()`): only set `preferredDevice` when
`record.routedDevice?.id != usbDevice.id`. If already routed correctly, skip the call
entirely. This preserves the original tablet-routing correction (tablets that
genuinely route to the internal mic still get fixed) while eliminating the
unnecessary/harmful re-lock on devices that were already routed correctly.

**Explicitly rejected alternatives** (do not reintroduce):
- Always calling `setPreferredDevice()` unconditionally — the original bug.
- Any warm-up/delay before recording starts, to "let routing settle." Rejected by
  product decision — recording must start instantly on tapping Record, and delays
  were also empirically unreliable (observed recordings still flat after a 5s warm-up,
  once for 12.8s, once for 50+ seconds continuously).

Other tablet-fix pieces (buffer size `*4`/floor 8192, `AudioSource` fallback chain
`UNPROCESSED → VOICE_RECOGNITION → DEFAULT`, `disableSystemAudioEffects()`) were
confirmed **not** implicated in this bug and were left functionally unchanged — only
comments were trimmed and diagnostic logging added.

## Issue 2 — Intermittent flat recording on fresh USB replug (budget-hardware limitation, NOT a code bug)

Even after the Issue 1 fix, unplugging and replugging the TAAL device and then
immediately recording could still produce a flat/silent recording — but **only on
the Samsung Galaxy A06** (budget chipset), never on a OnePlus 7T (flagship) with the
identical app build and identical steps. This is a known class of Android
fragmentation: budget SoC USB-audio HALs are slower/less robust to cold-start after a
fresh USB audio-class enumeration than flagship SoCs. There is no public Android API
that reliably predicts this in advance, and no code-level fix was found (multiple
warm-up/timeout strategies were tried and were unreliable — see above).

**Resolution:** rather than trying to prevent this hardware quirk, detect it after
the fact and tell the user — see "Silent-recording detection" below. This also
serves as a way to identify which client devices are affected ("low chip" devices).

## Issue 3 — Mid-recording USB disconnect (NEW FEATURE, working)

If the TAAL device is physically unplugged while a recording is in progress, the app
now stops the recording immediately, discards the partial files, and tells the user
to reconnect — instead of silently continuing to "record" from whatever the OS falls
back to.

**Implementation:**
- `TaalAudioCapture.captureAudioToFile()` polls `checkUsbConnection()` every 500ms
  (`USB_CHECK_INTERVAL_MS`) inside the capture loop. On disconnect: sets
  `disconnectedMidRecording = true`, `isRecording = false`, breaks the loop.
- New callback `TaalAudioCapture.onDeviceDisconnected: (() -> Unit)?`.
- `TaalRecorder` relays this to `OnInfoListener.onDeviceDisconnected()` (new
  interface method, default no-op — non-breaking for existing implementers like
  lungs-app).
- `RecordingFragment` (both `app` and `taal-ui-kit`) overrides it: stops the audio
  monitor, deletes both partial temp files (raw + filtered), shows a Toast
  ("Device disconnected. Please connect the device."), resets to idle.

## Issue 4 — Silent-recording detection popup (NEW FEATURE, working)

After a recording completes with no captured audio at all (used to address Issue 2),
a dialog tells the user rather than silently handing them a dead recording.

**Evolution of the detection logic (important — do not regress to v1):**

- **v1 (wrong):** measured peak amplitude on the *raw* mic signal inside
  `TaalAudioCapture`, before any pre-amp gain or filtering. This caused false
  positives: a quiet-but-valid raw signal that gets boosted by the user's pre-amp
  setting can look/sound completely normal on the waveform and in playback, yet still
  read as "silent" on the raw scale.
- **v2 (current, correct):** the amplitude check was moved to `TaalRecorder`, which
  tracks `maxFilteredPeakSeen` — the peak of the **filtered and pre-amplified**
  signal, i.e. exactly what the waveform draws and what plays back. A recording is
  only flagged silent if the peak of that signal never exceeds
  `SILENT_RECORDING_PEAK_THRESHOLD = 0.01f`. `TaalAudioCapture` no longer makes the
  silence decision at all — it only reports whether a recording completed normally
  (`onCaptureCompleted(isFirstSinceConnect: Boolean)`, fired whenever the device
  stayed connected and at least one real read happened, regardless of signal level).

**First-since-connect gating:** `TaalAudioCapture` tracks whether a given recording
is the *first* one since the TAAL device's current physical USB connection began,
using `UsbDevice.deviceId` (kernel/host-controller id — stable per physical
connection, changes only on a real unplug/replug), compared against a
companion/class-level `lastKnownUsbDeviceId` (persists across the
"new TaalRecorder/TaalAudioCapture per recording" pattern used by callers).

**Current UX (final, per product decision):** the dialog is shown **only** when
`isFirstSinceConnect == true`. A second variant ("check the device is connected and
touching the skin") was tried for the non-first case but proved unreliable/produced
unwanted popups on perfectly good recordings in testing, and was intentionally
removed — do not re-add without a specific new request.

- Title: **"Ready to Capture"**
- Message: **"Your TAAL device has been detected and is now ready. Please discard
  this recording and start a new one."**
- `AlertDialog` (not Toast) with a single OK button, `setCancelable(false)`, anchored
  to the Activity window (`act.isFinishing`/`isDestroyed` guarded) rather than the
  fragment's view — this matters because by the time the callback fires, the user has
  usually already navigated to `PlayerFragment`, and an Activity-level dialog still
  displays correctly on top of whatever screen is current.
- Copy went through several rounds of options before landing on the above — framed
  as a routine "device just got ready" step, not an error, so the user doesn't read
  it as "your app/device is broken."

## Issue 5 — Popup silently not appearing despite correct backend detection (FIXED)

Even with detection working correctly (confirmed via logs), the dialog never actually
appeared on screen when the user tapped Stop manually (the overwhelmingly common way
a recording ends).

**Root cause:** `TaalAudioCapture.stopRecording()` called `captureJob?.cancel()`.
The capture loop already exits cleanly on its own via the `isRecording` flag — the
`cancel()` call added nothing for *stopping* the loop, but it silently aborted the
coroutine at its `withContext(Dispatchers.Main) {}` tail block in
`captureAudioToFile()`, which is where `onStateChange` / `onDeviceDisconnected` /
`onCaptureCompleted` actually fire. Kotlin coroutine cancellation takes effect at the
*next suspension point* — and `withContext(Main)` is exactly that suspension point —
so the entire tail block silently never ran after a manual stop.

**Fix:** removed `captureJob?.cancel()` from `stopRecording()` entirely (with an
explanatory comment in place, since this is a non-obvious trap — do not re-add it).

## Current public API surface (`TaalRecorder.OnInfoListener`)

```kotlin
interface OnInfoListener {
    fun onStateChange(state: RecorderState)
    fun onProgressUpdate(sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray)
    fun onRawProgressUpdate(data: FloatArray) {}          // default no-op
    fun onDeviceDisconnected() {}                          // default no-op — Issue 3
    fun onSilentRecordingDetected(isFirstSinceConnect: Boolean) {}  // default no-op — Issue 4
}
```

Both new methods default to no-op, so any existing implementer (lungs-app,
dev-only fragments, etc.) keeps compiling unchanged and simply doesn't get the new
behavior unless it opts in.

## Tablet-fix status

All four original tablet-fix pieces are intact:
- Buffer size (`*4`, floor 8192) — unchanged, unconditional.
- `AudioSource` fallback chain (`UNPROCESSED → VOICE_RECOGNITION → DEFAULT`) —
  unchanged, unconditional.
- `disableSystemAudioEffects()` — unchanged, unconditional.
- `lockToUsbAudioDevice()` — changed from unconditional to conditional (see Issue 1),
  but this is equivalent-or-better for tablets: it still corrects mis-routing
  whenever the record isn't already on the USB device (the actual tablet failure
  mode), it just skips the redundant call when routing is already correct (the phone
  bug's cause).

**Caveat:** none of this session's testing was on physical tablet hardware — all
verification was on a Samsung Galaxy A06 and a OnePlus 7T (both phones). Re-verify on
a real tablet before considering this fully confirmed post-changes.

## Files changed this session

| File | What changed |
|---|---|
| `taal-core/.../core/TaalAudioCapture.kt` | Conditional device lock (Issue 1); mid-recording disconnect polling + `onDeviceDisconnected` (Issue 3); `isFirstSinceConnect` tracking; `onCaptureCompleted` callback (replaces old raw-signal `onSilentRecordingDetected`, Issue 4 v1→v2); removed `captureJob?.cancel()` (Issue 5); diagnostic `Log.d`/`Log.w` throughout; dead raw peak-tracking (`minPeakSeen`/`maxPeakSeen`) removed as cleanup. |
| `taal-core/.../TaalRecorder.kt` | `maxFilteredPeakSeen` tracking on the filtered/amplified signal + `SILENT_RECORDING_PEAK_THRESHOLD` (Issue 4 v2, the correct detection point); wires `onDeviceDisconnected`/`onCaptureCompleted` from `TaalAudioCapture` to `OnInfoListener`; two new `OnInfoListener` default-no-op methods. |
| `app/.../ui/recording/RecordingFragment.kt` | `onDeviceDisconnected()` override (Issue 3 UI); `onSilentRecordingDetected()` override — gated to `isFirstSinceConnect`, `AlertDialog` "Ready to Capture" (Issue 4 UI, final copy). |
| `taal-ui-kit/.../recording/RecordingFragment.kt` | Same two overrides, mirrored for the SDK-facing UI kit. |

## What's intentionally still there (do not remove without reason)

- All state-transition/diagnostic `Log.d`/`Log.w` lines in `TaalAudioCapture.kt`
  (`startRecording() begin`, `buildAudioRecord()` outcome, `AudioRecord built`,
  `lockToUsbAudioDevice()` decision, per-second peak/routing, disconnect warning) —
  kept deliberately for future field debugging of device-specific quirks like Issue 2.
  Safe to strip for a quieter release build if ever needed, but not required.
- The "History" comments explaining rejected approaches (unconditional lock,
  warm-up delay, the `cancel()` trap) — kept so nobody re-introduces the same bugs.
