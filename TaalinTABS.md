# TAAL App — Tablet Compatibility Guide

**Product:** TAAL Digital Stethoscope App  
**Affected Devices:** Samsung Galaxy Tab A9+ and other Android tablets  
**Android Versions Tested:** Android 14 (works), Android 16 (degraded audio)  
**Date:** June 2026  
**Author:** Engineering Reference  

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Why Tablets Behave Differently from Phones](#2-why-tablets-behave-differently-from-phones)
3. [Android 14 vs Android 16 — What Changed](#3-android-14-vs-android-16--what-changed)
4. [Root Cause Breakdown](#4-root-cause-breakdown)
5. [Part A — Code Changes (taal-core)](#part-a--code-changes-taal-core)
6. [Part B — Tablet Settings to Check](#part-b--tablet-settings-to-check)
7. [Part C — Diagnostic Procedure](#part-c--diagnostic-procedure)
8. [Part D — Testing Checklist](#part-d--testing-checklist)
9. [What Not to Change](#9-what-not-to-change)
10. [Quick Reference Card](#10-quick-reference-card)

---

## 1. Executive Summary

The TAAL app and SDK work correctly on Android phones but show degraded heart sound quality on Android tablets — specifically on Android 16. Android 14 tablets work acceptably.

**The issue is not with the TAAL hardware device.** The device is functioning correctly. The problem is in how Android routes and processes the USB audio input differently on tablets, and how Android 16 tightened audio source routing behavior compared to Android 14.

**Three compounding problems were found in the current code:**

| # | Problem | Where | Impact |
|---|---------|--------|--------|
| 1 | `AudioSource.DEFAULT` used instead of `UNPROCESSED` | `TaalAudioCapture.kt` line 46 | Audio routes to wrong device or OS applies voice processing to heart sounds |
| 2 | No audio effects disabled | `TaalAudioCapture.kt` — missing | AGC/Noise Suppressor silently degrades 20–250 Hz signal |
| 3 | Buffer size too small (`*2`) | `TaalAudioCapture.kt` line 30 | Frame drops on tablets with different USB audio scheduling |

All three fixes are backward-compatible with Android 14 and will not break phones.

---

## 2. Why Tablets Behave Differently from Phones

This section explains the structural differences between tablets and phones that cause the audio quality issue. Understanding this is important before making code changes.

### 2.1 AudioSource.DEFAULT Routing — Phones vs Tablets

On Android, `MediaRecorder.AudioSource.DEFAULT` does **not** guarantee which microphone is selected. The OS chooses based on its own audio policy.

**On phones:**
- Typically one USB-C port, used exclusively by the TAAL device
- Android's audio policy on phones maps DEFAULT to the USB audio class device when one is connected, because there is no ambiguity about which input to prefer
- This "worked by accident" — not by design

**On tablets:**
- Tablets have more complex audio hardware: 2–4 built-in microphones, often in a stereo/quad array
- The tablet's audio policy may prefer the built-in mic array even when a USB audio device is connected, because tablets are designed for voice capture via their internal mics
- Samsung Galaxy Tab A9+ has a dual-microphone array with beam-forming; Android's DEFAULT source on this device may activate beam-forming processing, which filters out sub-300 Hz content — destroying heart sounds
- On some Samsung tablets, DEFAULT explicitly routes to the main body microphone, not USB audio

**Conclusion:** Phones happen to route DEFAULT to USB audio. Tablets often do not. The code must explicitly request the USB audio device instead of relying on DEFAULT.

---

### 2.2 Samsung Audio Stack — Tablet vs Phone Behavior

Samsung tablets ship with additional audio processing that is active by default and behaves differently than the same software on Samsung phones.

| Feature | Samsung Phone Behavior | Samsung Tab A9+ Behavior |
|---------|----------------------|--------------------------|
| Dolby Atmos | Applied to speakers/headphones | Applied to speakers **and affects recording pipeline** on some firmware |
| Adapt Sound | Requires headphones connected to activate | Can activate on USB audio path |
| Noise reduction | Voice-optimized (300–3400 Hz passband) | More aggressive on tablets due to noisier form factor use cases |
| AGC (Auto Gain Control) | Often disabled on USB source | May remain enabled on tablet USB audio path |

Heart sounds are predominantly in the **20–250 Hz range** (HEART filter). Any noise reduction or AGC tuned for voice calls will aggressively attenuate this range because it treats it as low-frequency noise or inconsistent gain.

---

### 2.3 USB OTG Implementation Differences

USB OTG (On-The-Go) is used differently on tablets vs phones:

- **Phones** use USB OTG rarely — primarily for the TAAL device or USB drives. The OTG audio driver is simple and direct.
- **Tablets** use USB OTG more often — keyboards, mice, USB hubs, external storage. As a result, the USB audio class driver on tablets has more complex device detection and power management logic.
- Samsung Galaxy Tab A9+ uses a USB 2.0 controller with different power delivery characteristics than Samsung's phone line. Low power delivery to the TAAL device under tablet power management profiles can cause intermittent audio.

---

### 2.4 Multi-Microphone Array Processing

The Samsung Galaxy Tab A9+ has **two built-in microphones** in a dual-array configuration. Android may apply:

- **Beam-forming** — directional audio capture that physically attenuates off-axis frequencies
- **Microphone fusion** — combines the two mic channels, which changes the audio characteristics compared to a single USB mic source
- **Environmental noise cancellation** — designed to clean up tablet recordings in noisy meeting rooms, which attenuates the low-frequency range used by stethoscopes

None of these apply when `UNPROCESSED` is used with explicit USB device routing. They only apply when `DEFAULT` is used.

---

### 2.5 Buffer Size and CPU Scheduler Differences

Tablets typically use mid-range SoCs (Samsung Tab A9+ uses Snapdragon 695) compared to flagship phone SoCs.

- `AudioRecord.getMinBufferSize()` returns **different values** on different SoCs — the minimum safe buffer on the Tab A9+ may be larger than what a phone reports
- The current code uses `getMinBufferSize() * 2` which is safe on flagship phones but may be too small on tablet SoCs
- If the buffer is too small, the audio capture loop misses frames — this manifests as click artifacts or gaps in the waveform, especially during heavy UI rendering (the real-time chart is CPU-intensive)

---

## 3. Android 14 vs Android 16 — What Changed

This explains why Android 14 tablets work better than Android 16 tablets.

### 3.1 AudioSource.DEFAULT Routing Policy Change

Google changed the audio routing policy in Android 15/16:

- **Android 14:** When a USB audio class device is connected, `AudioSource.DEFAULT` on many devices did route to it as the "preferred external input"
- **Android 16:** Google tightened this — `DEFAULT` now more strictly means "system default input", which for tablets is the built-in microphone array. The USB device is no longer preferred automatically.

This is why Android 14 tablets produce better recordings — they happened to route DEFAULT to the TAAL device. Android 16 tablets do not.

### 3.2 Audio Effect Application

Android 16 introduced changes to how system audio effects are attached to recording sessions:

- **Android 14:** AGC and Noise Suppressor were often not attached to USB audio sources by default
- **Android 16:** These effects may now be attached to all recording sessions by default on newer devices, regardless of the input source. They must be explicitly disabled.

### 3.3 USB Audio Class Driver Changes

Android 15 and 16 updated the USB audio class (UAC) driver:

- Stricter compliance with USB Audio Class 1.0/2.0 specification
- Changed how the OS negotiates sample rate with USB audio devices
- On some devices, the OS now forces resampling through an SRC (sample rate converter) even when the device supports 44100 Hz natively. SRC can introduce artifacts.

### 3.4 Summary Table

| Behavior | Android 14 Tablet | Android 16 Tablet |
|----------|------------------|------------------|
| DEFAULT routes to USB audio | Often yes | Often no — goes to built-in mic |
| AGC attached by default | Sometimes | More frequently yes |
| Noise Suppressor active | Partial | More frequently yes |
| USB audio SRC (resampling) | Rare | More common |
| Audio scheduling latency | Lower | Higher on mid-range SoCs |

---

## 4. Root Cause Breakdown

### Primary Root Cause
`MediaRecorder.AudioSource.DEFAULT` in `TaalAudioCapture.kt` line 46 allows the OS to decide where audio comes from. On Android 16 tablets, this results in audio being captured from the built-in microphone array with voice-call processing applied, not from the TAAL USB device.

### Secondary Root Causes

**No explicit audio effect disabling:**  
Android can attach `AutomaticGainControl`, `NoiseSuppressor`, and `AcousticEchoCanceler` to the `AudioRecord` session. These are transparently applied after the data is captured, before `onAudioData` is called. The SDK has no code to disable these.

**Insufficient buffer size:**  
`getMinBufferSize() * 2` is the safe minimum for phones. Tablets with different USB audio class drivers and mid-range SoCs need `* 4` minimum to avoid frame drops.

**No explicit USB device targeting:**  
The `AudioRecord` API allows explicitly setting a preferred input device since API 28. The SDK does not use this, relying on OS routing instead.

---

## Part A — Code Changes (taal-core)

All changes are in one file: `taal-core/src/main/java/com/musediagnostics/taal/core/TaalAudioCapture.kt`

These changes are **backward-compatible** — they will work on Android 14 and Android 16, on phones and tablets.

---

### A1. Change AudioSource from DEFAULT to UNPROCESSED

**File:** `TaalAudioCapture.kt`  
**Line:** 30–51  

**Current code (lines 30–51):**
```kotlin
private val bufferSize = AudioRecord.getMinBufferSize(
    SAMPLE_RATE,
    CHANNEL_CONFIG,
    AUDIO_FORMAT
) * 2 // Double for safety

// ...

fun startRecording(outputFile: File, durationSeconds: Int) {
    if (isRecording) return
    isStopped = false

    val record = AudioRecord(
        MediaRecorder.AudioSource.DEFAULT,   // ← PROBLEM
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT,
        bufferSize
    )
```

**Replace with:**
```kotlin
private val bufferSize = maxOf(
    AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * 4,  // 4x minimum — tablets with mid-range SoCs need larger buffers
    8192    // absolute floor regardless of what getMinBufferSize returns
)

// ...

fun startRecording(outputFile: File, durationSeconds: Int) {
    if (isRecording) return
    isStopped = false

    // UNPROCESSED = raw hardware signal, no system audio effects applied.
    // This is correct for medical audio — DEFAULT allows the OS to apply
    // noise suppression / AGC / beam-forming which destroys heart sounds.
    // Fall back to VOICE_RECOGNITION if UNPROCESSED fails (some devices do not
    // expose it for USB audio class devices).
    val record = buildAudioRecord()

    // ...
}

private fun buildAudioRecord(): AudioRecord {
    // Try UNPROCESSED first — gives raw hardware signal with zero OS processing
    runCatching {
        val r = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        if (r.state == AudioRecord.STATE_INITIALIZED) return r
        r.release()
    }

    // Fallback: VOICE_RECOGNITION — less processing than DEFAULT, no beam-forming
    runCatching {
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        if (r.state == AudioRecord.STATE_INITIALIZED) return r
        r.release()
    }

    // Last resort: DEFAULT (original behavior — may have issues on tablets)
    return AudioRecord(
        MediaRecorder.AudioSource.DEFAULT,
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT,
        bufferSize
    )
}
```

> **Why UNPROCESSED:** API 24 (your minSdk). Returns raw PCM from the hardware transducer with nothing applied. The TAAL SDK already applies its own filters via `AudioFilterEngine` — the OS must not apply a second layer on top.

> **Why the fallback chain:** Not all USB audio class devices expose `UNPROCESSED` on all Android versions. The fallback ensures the recorder never silently fails.

---

### A2. Disable System Audio Effects After AudioRecord is Created

**File:** `TaalAudioCapture.kt`  
**Where:** Inside `startRecording()`, immediately after `record.startRecording()` is called  

**Add these imports at the top of the file:**
```kotlin
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
```

**Add this block immediately after `record.startRecording()` (current line 64):**
```kotlin
record.startRecording()

// Explicitly disable all system audio effects — these silently degrade
// heart sounds by treating the 20–250 Hz range as noise.
// Each call is guarded: if the effect is not available on this device, nothing happens.
disableAudioEffects(record.audioSessionId)
```

**Add this new private function to the class:**
```kotlin
private fun disableAudioEffects(sessionId: Int) {
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
```

> **Why this matters:** Even when `UNPROCESSED` is used, some device manufacturers (Samsung in particular) attach audio effects at the hardware layer that apply regardless of the source type. Explicitly calling `setEnabled(false)` on each effect forces them off at the session level.

---

### A3. Set Preferred USB Input Device (Android 28+ Only)

**File:** `TaalAudioCapture.kt`  
**Where:** Inside `startRecording()`, after `disableAudioEffects()` is called  

**Add this import:**
```kotlin
import android.media.AudioDeviceInfo
import android.media.AudioManager
```

**Add this block after `disableAudioEffects(record.audioSessionId)`:**
```kotlin
// On Android 9+ (API 28), explicitly set the USB audio device as the preferred
// input. This prevents the OS from silently switching to a different input
// (e.g. built-in mic array) mid-recording on tablets.
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
    preferUsbAudioDevice(record)
}
```

**Add this new private function:**
```kotlin
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.P)
private fun preferUsbAudioDevice(record: AudioRecord) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    val usbDevice = inputs.firstOrNull { device ->
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
    if (usbDevice != null) {
        record.preferredDevice = usbDevice
    }
}
```

> **Why:** `preferredDevice` tells Android "when routing, always prefer this device". Without it, the OS may re-route mid-recording on tablets if a second audio event happens (notification, other app).

---

### A4. Add USB Source Verification Logging (Diagnostic Build)

When investigating a tablet issue in the field, add this temporary log block inside `startRecording()` after `preferUsbAudioDevice()`. **Remove before release build.**

```kotlin
// DIAGNOSTIC ONLY — remove before release
val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
android.util.Log.d("TAAL_AUDIO", "=== Audio Source Debug ===")
android.util.Log.d("TAAL_AUDIO", "AudioRecord session: ${record.audioSessionId}")
android.util.Log.d("TAAL_AUDIO", "Preferred device: ${record.preferredDevice?.productName}")
am.activeRecordingConfigurations.forEach { config ->
    android.util.Log.d("TAAL_AUDIO", "Active recording: clientSource=${config.clientAudioSource}, " +
        "device=${config.audioDevice?.type}")
}
am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { device ->
    android.util.Log.d("TAAL_AUDIO", "Available input: type=${device.type} " +
        "name=${device.productName} id=${device.id}")
}
```

> USB audio device types are: `TYPE_USB_DEVICE = 11`, `TYPE_USB_HEADSET = 22`.  
> Built-in mic is: `TYPE_BUILTIN_MIC = 15`.  
> If you see `TYPE_BUILTIN_MIC` as the active recording device, the root cause is confirmed.

---

### Summary of All Code Changes in TaalAudioCapture.kt

| Change | What | Why |
|--------|------|-----|
| A1a | Buffer size `*4` with floor 8192 | Tablets need larger buffers — frame drops on mid-range SoCs |
| A1b | `DEFAULT` → `UNPROCESSED` with fallback chain | Capture raw signal without OS voice processing |
| A2 | Disable AGC, NoiseSuppressor, AEC after start | Samsung tablets attach these even with UNPROCESSED |
| A3 | `record.preferredDevice = usbDevice` (API 28+) | Locks routing to TAAL device, prevents OS re-routing |
| A4 | Debug logging (temporary) | Confirms which device is actually being recorded |

---

## Part B — Tablet Settings to Check

Check these settings on the tablet **before** installing the updated APK. Some of these are device-level settings that will affect recording quality regardless of app code.

### B1. Samsung Galaxy Tab A9+ — Audio Processing Settings

| Setting | Navigation Path | Action |
|---------|----------------|--------|
| Dolby Atmos | Settings → Sound → Sound quality and effects | Turn **OFF** |
| Adapt Sound | Settings → Sound → Sound quality and effects → Adapt sound | Turn **OFF** |
| Equalizer | Settings → Sound → Sound quality and effects → Equalizer | Set to **Off / Flat** |
| Volume steps | Settings → Sound → Volume | Keep media volume at 50–70% to avoid AGC activation |
| Hearing enhancements | Settings → Accessibility → Hearing enhancements | Turn off all toggles |

### B2. USB OTG Settings

| Setting | Navigation Path | Action |
|---------|----------------|--------|
| OTG connection | Settings → Connections → USB → OTG | Ensure **Enabled** |
| USB mode when connected | Pull-down notification when USB plugged in | Should show "USB audio" or be in "Charging only" mode — not "File transfer" |
| Battery saver USB | Settings → Battery and device care → Battery → Power saving | Turn **OFF** — power saving can throttle USB current and affect TAAL |

> **Important:** When the TAAL device is plugged into the tablet, you should see a headphone or audio icon in the top notification bar. If not visible, the OS has not recognized it as a USB audio device.

### B3. Developer Options (for testing only — do not enable in production)

Access via: Settings → About tablet → Software information → Tap "Build number" 7 times → Back → Developer options

| Setting | Action | Why |
|---------|--------|-----|
| Disable HW overlays | Turn OFF | Reduces GPU workload competing with audio thread |
| Background process limit | Standard limit | Avoid "No background processes" which can kill audio thread |
| USB audio routing | Default | Do not change unless testing |

### B4. Check for Conflicting Audio Peripherals

The TAAL SDK documentation (Section 16.1) notes: **AUX wired headphones with a built-in microphone take priority over USB audio on Android.** This is an OS-level rule.

On tablets:
- Check if any wired headphones are plugged into the 3.5mm port
- If yes: unplug them. The recording will capture from the headphone mic, not TAAL.
- Use Bluetooth headphones for audio monitoring during recording — they do not take priority over USB

### B5. Check TAAL Device Battery

A low-battery TAAL device can cause unstable USB enumeration. Tablets provide less USB bus power than phones due to different USB controller configurations. Ensure the TAAL device is fully charged before testing.

---

## Part C — Diagnostic Procedure

Follow this sequence in order. Stop when you find the problem.

### Step 1 — Confirm TAAL Device is Recognized

Connect the TAAL device to the tablet. Check:
- Notification bar shows audio/headphone icon → USB audio device recognized ✓
- No icon visible → tablet is not recognizing TAAL as audio device → check OTG settings (B2)

### Step 2 — Check Competing Peripherals

- Are wired headphones with mic connected? → Unplug them
- Is any other USB device connected via hub? → Remove and connect TAAL directly
- Is Bluetooth audio active? → Disconnect Bluetooth devices

### Step 3 — Disable Samsung Audio Enhancements

Apply all settings from B1. Then test a recording. If recording quality improves after disabling Dolby Atmos and Adapt Sound, the audio processing stack is the cause — and the code fix in A2 (disabling AGC/NS/AEC) will address this permanently in the APK.

### Step 4 — Install Debug APK and Check Logcat

Install a debug build with the A4 logging added. Connect via Android Studio or `adb logcat -s TAAL_AUDIO`. Record for 5 seconds and read the output.

**Good output (Android 14 or after the fix):**
```
TAAL_AUDIO: Active recording: clientSource=9, device=11
TAAL_AUDIO: Preferred device: TAAL Stethoscope
```
`clientSource=9` = `UNPROCESSED`, `device=11` = `TYPE_USB_DEVICE`

**Bad output (Android 16 before fix):**
```
TAAL_AUDIO: Active recording: clientSource=1, device=15
TAAL_AUDIO: Preferred device: null
```
`clientSource=1` = `DEFAULT`, `device=15` = `TYPE_BUILTIN_MIC` — recording from built-in mic, not TAAL

### Step 5 — Deploy Fixed APK (Changes from Part A)

Install the APK with all A1–A3 changes applied. Record 15 seconds. Compare:
- Is the waveform showing periodic peaks corresponding to heartbeats?
- Is the heart sound audible on playback?

### Step 6 — Compare WAV Files in Audacity (if issue persists)

Save a recording from the Android 14 tablet and the Android 16 tablet. Open both in Audacity:
- If Android 16 waveform is flat or near-zero amplitude → audio routed to wrong device (fix A1 + A3)
- If Android 16 waveform has signal but sounds muffled → audio effect processing active (fix A2)
- If Android 16 waveform has clicks/gaps at regular intervals → buffer too small (fix A1a)
- If Android 16 waveform sounds like a different room microphone → still routing to built-in mic (fix A3)

---

## Part D — Testing Checklist

Use this checklist after deploying the updated APK to verify each issue is resolved.

### Pre-Test Setup
- [ ] TAAL device fully charged
- [ ] No wired headphones with mic connected
- [ ] Dolby Atmos turned OFF in Samsung settings
- [ ] Adapt Sound turned OFF
- [ ] Battery saver turned OFF
- [ ] TAAL device connected and audio icon visible in notification bar

### Audio Quality Tests

| Test | Method | Pass Condition |
|------|--------|----------------|
| Heart sound capture | Record 20 seconds HEART filter | Periodic heartbeat peaks visible in waveform; lub-dub audible on playback |
| BPM accuracy | Record and compare BPM display vs manual count | ±5 BPM of manual count |
| No noise floor | Record 5 seconds with stethoscope in the air (not on body) | Waveform near flat baseline, no 50/60 Hz hum |
| Recording duration | Record for 30 seconds | File duration = 30 seconds (verify with WavCropper.getDurationSeconds) |
| No click artifacts | Record 60 seconds | No audible clicks or dropouts |
| Android 14 vs Android 16 comparison | Same patient, same placement, both tablets | Waveform shape should be similar; amplitude should be comparable |

### Regression Tests (ensure phone behavior unchanged)

| Test | Method | Pass Condition |
|------|--------|----------------|
| Phone recording works | Record on any Android phone | No change from current behavior |
| UNPROCESSED fallback | Test on device where UNPROCESSED may not be available | Falls back to VOICE_RECOGNITION without crash |
| Filter types | Test HEART, LUNGS, BOWEL, PREGNANCY, FULL_BODY | All filter types record correctly on tablet |

---

## 9. What Not to Change

These areas should **not** be modified as part of this fix:

| Area | Reason |
|------|--------|
| `AudioFilterEngine.kt` | The DSP filters are working correctly — the problem is upstream (input routing), not downstream (processing) |
| `TaalRecorder.kt` | The recorder wrapper is not involved in the input routing problem |
| `SAMPLE_RATE = 44100` | Do not change — the TAAL hardware outputs at 44100 Hz. Changing this introduces resampling artifacts |
| `AUDIO_FORMAT = PCM_16BIT` | Standard format; changing does not fix routing |
| `CHANNEL_CONFIG = MONO` | Correct for the TAAL stethoscope; do not change to stereo |
| Waveform rendering code | Not related to audio quality |
| WAV header writing | Correct and working |

---

## 10. Quick Reference Card

**The one-line summary of the problem:**
> Android 16 tablets do not automatically route `AudioSource.DEFAULT` to the USB TAAL device. It captures from the built-in microphone array instead, with voice-call audio processing applied that destroys heart sounds.

**The three-line fix:**
1. Change `AudioSource.DEFAULT` → `UNPROCESSED` (with fallback chain)
2. Disable AGC, NoiseSuppressor, AEC after `AudioRecord` starts
3. Set `record.preferredDevice` to the detected USB audio device

**File to change:** `taal-core/src/main/java/com/musediagnostics/taal/core/TaalAudioCapture.kt`

**Settings to check on tablet first:**
- Dolby Atmos OFF
- Adapt Sound OFF  
- No wired headphones with mic
- OTG enabled
- TAAL battery charged

**Android source constants for reference:**
```
AudioSource.DEFAULT = 1         ← bad for tablets
AudioSource.UNPROCESSED = 9     ← correct for medical audio
AudioSource.VOICE_RECOGNITION = 6  ← acceptable fallback
TYPE_USB_DEVICE = 11            ← what you want in logcat
TYPE_BUILTIN_MIC = 15           ← what you do NOT want in logcat
```

---

*This document covers both the immediate tablet issue and the longer-term Android 16 compatibility fix. The code changes in Part A should be applied to the taal-core module before the next APK build delivered to clients.*
