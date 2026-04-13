# AI Downsampling Pipeline

Reference document for the multi-rate downsampling pipeline that produces WAV files
for offline AI model training.  Read this before touching any AI pipeline code.

---

## Overview

During every recording, the app taps the **raw** 44.1 kHz audio stream (before
TaalRecorder applies any filter or pre-amp) and produces downsampled WAV files for
AI model training.  These files are **strictly isolated** from the clinical recording
pipeline — any failure in this path is silently swallowed and never affects the
`_raw.wav` or `_filtered.wav` files used by the app.

The set of files created depends on the **filter selected at recording time**:

| Selected filter | Files created |
|---|---|
| HEART | `_8k_downsampling.wav` + `_4k_heart_downsampling.wav` + `_1k_heart_downsampling.wav` + `_500hz_heart_downsampling.wav` |
| LUNGS | `_8k_lungs_downsampling.wav` + `_4k_lungs_downsampling.wav` + `_3k_lungs_downsampling.wav` + `_2k_lungs_downsampling.wav` |
| BOWEL / PREGNANCY / FULL_BODY | *(no AI files)* |

---

## File Naming

All files live in `context.filesDir` (app's internal storage, not accessible without ADB).

Pattern: `recording_{timestamp}_{suffix}.wav`

| Suffix | Filter | Sample rate | Notes |
|---|---|---|---|
| `_8k_downsampling` | HEART | 8000 Hz | Original stream — path in ViewModel for save/discard |
| `_4k_heart_downsampling` | HEART | 4000 Hz | |
| `_1k_heart_downsampling` | HEART | 1000 Hz | |
| `_500hz_heart_downsampling` | HEART | 500 Hz | Borderline Nyquist — see notes below |
| `_8k_lungs_downsampling` | LUNGS | 8000 Hz | |
| `_4k_lungs_downsampling` | LUNGS | 4000 Hz | |
| `_3k_lungs_downsampling` | LUNGS | 3000 Hz | |
| `_2k_lungs_downsampling` | LUNGS | 2000 Hz | |

---

## Processing Chain (per audio block)

```
AudioRecord (44100 Hz, 16-bit PCM, mono)
    │
    ▼  TaalAudioCapture.captureAudioToFile()
    ├── fos.write(buffer) ──────────────────────────► recording_{ts}_raw.wav  (unchanged)
    └── convertBytesToFloat()
            │
            ▼  TaalRecorder.onAudioData
            ├── filterEngine.processBlock() + pre-amp
            │       └──────────────────────────────► recording_{ts}_filtered.wav  (unchanged)
            │
            ├── onInfoListener.onRawProgressUpdate(rawData)   ← AI pipeline taps here
            │       │
            │       │  RecordingFragment.onRawProgressUpdate()
            │       │
            │       ├── heartResampler8k.process(rawData)
            │       │       └── HEART bandpass (anti-alias) → downsample ×1/5.5125
            │       │               └─────────────────────► _8k_downsampling.wav
            │       │
            │       └── activeDownsamplingStreams.forEach { it.process(rawData) }
            │               │
            │               │  HEART filter selected:
            │               ├── AudioDownsampler(4000, HEART) → ×1/11.025 ► _4k_heart_downsampling.wav
            │               ├── AudioDownsampler(1000, HEART) → ×1/44.1   ► _1k_heart_downsampling.wav
            │               └── AudioDownsampler( 500, HEART) → ×1/88.2   ► _500hz_heart_downsampling.wav
            │
            │               LUNGS filter selected:
            │               ├── AudioDownsampler(8000, LUNGS) → ×1/5.5125  ► _8k_lungs_downsampling.wav
            │               ├── AudioDownsampler(4000, LUNGS) → ×1/11.025  ► _4k_lungs_downsampling.wav
            │               ├── AudioDownsampler(3000, LUNGS) → ×1/14.7    ► _3k_lungs_downsampling.wav
            │               └── AudioDownsampler(2000, LUNGS) → ×1/22.05   ► _2k_lungs_downsampling.wav
            │
            └── onInfoListener.onProgressUpdate(filteredData)
                    └── waveform / BPM / AudioTrack  (clinical pipeline, unchanged)
```

---

## AudioDownsampler — How It Works

**File:** `taal-core/src/main/java/com/musediagnostics/taal/dsp/AudioDownsampler.kt`

Constructor: `AudioDownsampler(outputSampleRate: Int, filterPreset: AudioFilterEngine.PresetFilter)`

### Step 1 — Anti-alias filter

Runs the raw 44.1 kHz block through the specified preset bandpass **before** downsampling.
This removes all frequency content above the Nyquist limit of the target rate, preventing
aliasing (high-frequency content folding back into the passband and corrupting the signal).

- **HEART preset** → Butterworth bandpass 20–250 Hz
- **LUNGS preset** → Butterworth bandpass 100–600 Hz

Pre-amp is intentionally 0 dB — the AI model receives normalised float samples.

### Step 2 — Linear interpolation downsample

Ratio = 44100 / outputSampleRate (always non-integer → decimation cannot be used).

For each output sample at fractional position P:
```
i0   = floor(P)
i1   = i0 + 1
frac = P - i0              (in [0.0, 1.0))
out  = in[i0]*(1-frac) + in[i1]*frac
P   += ratio
```

The fractional position is **carried across block boundaries** via `fractionalPos`.
Without this, each call to `process()` would restart at 0.0, causing a phase
discontinuity (~audible click) at every audio driver buffer boundary.

### Reset between recordings

`reset()` must be called before each new recording:
- Clears `fractionalPos` to 0.0
- Recreates `AudioFilterEngine` to wipe biquad state registers (x1, x2, y1, y2)

Stale biquad state from a previous recording would produce a brief transient
artefact at the start of the new file as the filter ring-down decays.

---

## Nyquist Safety Analysis

| Rate | Ratio | Nyquist | Filter | Safe? |
|---|---|---|---|---|
| HEART 8000 Hz | 5.5125 | 4000 Hz | HEART (≤250 Hz) | ✓ |
| HEART 4000 Hz | 11.025 | 2000 Hz | HEART (≤250 Hz) | ✓ |
| HEART 1000 Hz | 44.1 | 500 Hz | HEART (≤250 Hz) | ✓ |
| HEART 500 Hz | 88.2 | 250 Hz | HEART (≤250 Hz) | Borderline* |
| LUNGS 8000 Hz | 5.5125 | 4000 Hz | LUNGS (≤600 Hz) | ✓ |
| LUNGS 4000 Hz | 11.025 | 2000 Hz | LUNGS (≤600 Hz) | ✓ |
| LUNGS 3000 Hz | 14.7 | 1500 Hz | LUNGS (≤600 Hz) | ✓ |
| LUNGS 2000 Hz | 22.05 | 1000 Hz | LUNGS (≤600 Hz) | ✓ |

**\* HEART 500 Hz borderline note:** The HEART filter's -3 dB point (250 Hz) coincides
exactly with the Nyquist limit.  The Butterworth filter attenuates above 250 Hz but does
not produce a hard wall — there is minor content above Nyquist that could alias.
In practice this is acceptable because S1/S2 heart sounds peak at 20–150 Hz and the
energy above 200 Hz is small.  The 500 Hz file is useful for ultra-lightweight models
where file size matters more than perfect spectral accuracy.

---

## WAV File Format

All AI files: **mono, 16-bit PCM, little-endian, standard 44-byte RIFF/WAVE header**.

The data-size fields (RIFF bytes 4–7 and data bytes 40–43) are written as 0 placeholders
and back-patched after recording stops using `RandomAccessFile.seek()`.

---

## DownsamplingStream Inner Class

**File:** `app/.../recording/RecordingFragment.kt` (private inner class)

Encapsulates one stream's state: `AudioDownsampler` + `@Volatile FileOutputStream` + `@Volatile bytesWritten`.

| Method | Thread | Called from | Purpose |
|---|---|---|---|
| `open()` | main | `startRecording()` | Open file, write WAV header |
| `process(data)` | audio IO | `onRawProgressUpdate()` | Downsample + append PCM |
| `finalize()` | main | `stopRecording()` | Flush, close, patch WAV header |
| `safeClose()` | main | `resetToIdle()`, `onDestroy()` | Emergency close, no header patch |

---

## Save / Discard Behaviour

| Stream | Participates in save/discard? |
|---|---|
| `_8k_downsampling.wav` (8kHz HEART) | **Yes** — path in `viewModel.currentAiTestingPath`, copied/deleted by `SaveRecordingFragment` / `PlayerFragment` |
| All other AI files | **No** — remain in `filesDir/` after recording; pull via ADB |

To pull AI files from a connected device:
```bash
adb shell "ls /data/data/com.musediagnostics.taal.app/files/"
adb pull /data/data/com.musediagnostics.taal.app/files/recording_<ts>_4k_heart_downsampling.wav
```

---

## Key Files

| File | Role |
|---|---|
| `taal-core/dsp/AudioDownsampler.kt` | Generic resampler (all 8 streams) |
| `taal-core/dsp/HeartResampler.kt` | Original hardcoded 8kHz HEART resampler (superseded, unused) |
| `taal-core/TaalRecorder.kt` | Fires `onRawProgressUpdate()` with pre-filter raw data |
| `app/recording/RecordingFragment.kt` | Opens streams, routes data, finalizes on stop |
| `app/recording/RecordingViewModel.kt` | Stores `currentAiTestingPath` (8kHz HEART only) |

---

## Adding a New Rate or Filter in Future

1. Add a new `DownsamplingStream(AudioDownsampler(rate, preset), ...)` to the relevant
   `when` branch in `RecordingFragment.startRecording()`.
2. Verify Nyquist: `preset filter upper cutoff < rate / 2`.
3. No changes needed to `AudioDownsampler`, `TaalRecorder`, or any other file.
