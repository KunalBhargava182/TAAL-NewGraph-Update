# Lungs App — Denoiser Feature Reference

## Changelog

### 2026-05-12 — CRN replaced with Wiener filter; play button navigates to player screen

**What changed:**

1. **`WienerDenoiser.kt` created** — classical IMCRA + spectral-subtraction Wiener filter replaces the TFLite CRN model. No model file, no TFLite dependency, no `Context` needed.
2. **`LungsDenoiser.kt` updated** — removed `CrnDenoiser`, removed `Context` constructor parameter, removed `close()`. The pipeline now routes the raw STFT complex output directly into `WienerDenoiser.denoise()`.
3. **`DenoiserFragment.kt` updated** — play button now navigates to `LungsPlayerFragment` (full waveform + TaalPlayer screen) instead of inline `MediaPlayer`. `isReviewMode=true` hides Save/Discard bar.
4. **`lungs_nav_graph.xml` updated** — added `action_denoiser_to_player` inside `denoiserFragment` destination.

**Reason for replacing CRN:** The model's internal RESHAPE node has a hardcoded size that conflicts with all possible input shapes. The RESHAPE constraint forces T=313 (129×313=40377 elements) but T=313 causes an un-broadcastable ADD skip-connection (`[1,129,79,16]` vs `[1,1,33,16]`). No T value satisfies both simultaneously — the model cannot run on Android CPU.

---

## Overview

A noise-reduction feature for the `:lungs-app` module. Each lung recording point can be denoised using a classical Wiener filter (IMCRA noise estimation + spectral subtraction). The pipeline is:

```
WAV (any SR) → resample to 8 kHz → STFT → WienerDenoiser → iSTFT → denoised WAV (8 kHz)
```

Entry point: `PatientSessionFragment` → `btnDenoiser` → `DenoiserFragment` (lists all 16 lung points, denoise one at a time). After denoising, the "▶ Play" button opens `LungsPlayerFragment` in review mode.

---

## Module: `:lungs-app`

**Namespace:** `com.musediagnostics.taal.lungs`
**Package for denoiser code:** `com.musediagnostics.taal.lungs.denoiser`
**Package for denoiser UI:** `com.musediagnostics.taal.lungs.ui.denoiser`

---

## Dependencies (`lungs-app/build.gradle.kts`)

```kotlin
// Apache Commons Math3 (FastFourierTransformer for STFT/iSTFT)
implementation("org.apache.commons:commons-math3:3.6.1")
```

> **Note:** `tensorflow-lite:2.14.0` was added during the CRN phase and is still in `build.gradle.kts` but is no longer actively used since `CrnDenoiser` is superseded. It can be removed in a cleanup pass once `CrnDenoiser.kt` is deleted.

Also inside `android {}`:

```kotlin
aaptOptions {
    noCompress += "tflite"   // safe to keep even if tflite is removed
}

packaging {
    resources {
        excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/*.kotlin_module",
            "META-INF/INDEX.LIST",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
    }
}
```

---

## Files Created

### 1. `StftEngine.kt`
**Path:** `lungs-app/src/main/java/com/musediagnostics/taal/lungs/denoiser/StftEngine.kt`

Short-time Fourier Transform engine using Apache Commons Math3.

**Constants:**

| Constant   | Value | Notes                                    |
|------------|-------|------------------------------------------|
| `N_FFT`    | 256   | FFT size                                 |
| `WIN_SIZE` | 200   | Window length — 25 ms at 8 kHz          |
| `HOP`      | 80    | Hop length — 10 ms at 8 kHz             |
| `N_BINS`   | 129   | Frequency bins = N_FFT/2 + 1            |

**Methods:**

| Method | Input → Output | Notes |
|---|---|---|
| `computeStft(audio)` | `FloatArray` → `Pair<Array<FloatArray>, Array<FloatArray>>` | Returns `(real[129][T], imag[129][T])`. Pads audio to multiple of HOP. Applies Hann window before FFT. |
| `computeIstft(real, imag, origLen)` | `[129][T], [129][T], Int` → `FloatArray` | Overlap-add reconstruction. Normalises by sum-of-squared Hann windows. Returns trimmed to `origLen`. |
| `magnitude(real, imag)` | `[129][T], [129][T]` → `[129][T]` | `sqrt(r² + i²)` per bin/frame — still present, no longer called from `LungsDenoiser` |
| `phase(real, imag)` | `[129][T], [129][T]` → `[129][T]` | `atan2(i, r)` per bin/frame — still present, no longer called from `LungsDenoiser` |
| `fromMagnitudeAndPhase(mag, phase)` | `[129][T], [129][T]` → `Pair` | Still present, no longer called from `LungsDenoiser` |

---

### 2. `WienerDenoiser.kt` *(added 2026-05-12)*
**Path:** `lungs-app/src/main/java/com/musediagnostics/taal/lungs/denoiser/WienerDenoiser.kt`

Classical IMCRA + spectral-subtraction Wiener filter. No model file, no Android context, no `close()`.

**Parameters (companion object):**

| Constant | Value | Role |
|---|---|---|
| `ALPHA_S` | 0.9 | Power spectrum smoothing factor |
| `ALPHA_D` | 0.85 | Noise tracker update rate (signal absent) |
| `L` | 125 | Sliding-min window width (~1.25 s at 10 ms/frame) |
| `DELTA` | 5.0 | Signal-presence threshold (ratio of smoothed power to sliding min) |
| `BREATH_CORRECTION` | 0.20 | Scales noise estimate down to avoid over-suppression of breath sounds |
| `BETA` | 0.08 | Global spectral floor (gain never drops below 8%) |
| `WARMUP` | 20 | First 20 frames pass through unfiltered (noise estimator not yet stable) |
| `RELEASE` | 0.2 | Asymmetric gain smoother — fast release coefficient |

**Frequency band parameters (`BAND_PARAMS`):**

| Upper Hz | Over-estimation | Floor gain |
|---|---|---|
| 300 | 1.00× | 0.20 |
| 600 | 1.50× | 0.12 |
| 1200 | 2.00× | 0.06 |
| 2000 | 2.50× | 0.00 |

Bins below 100 Hz or above 2000 Hz are passed through unmodified (gain = 1.0).

**Public API:**

```kotlin
fun denoise(
    real: Array<FloatArray>,   // [nBins][nFrames] — from StftEngine.computeStft
    imag: Array<FloatArray>,   // [nBins][nFrames]
    sampleRate: Int = 8000,
    nFft: Int = 256
): Pair<Array<FloatArray>, Array<FloatArray>>   // denoised (real, imag)
```

Internally computes magnitude and phase from the complex STFT, runs IMCRA noise estimation, applies the per-bin Wiener gain to the magnitude, then reconstructs the complex output by multiplying the denoised magnitude back by the original phase. The caller passes this directly to `StftEngine.computeIstft()`.

**IMCRA (`imcra()`):**
- Smooths power spectrum with `ALPHA_S` exponential moving average.
- Maintains a monotonic deque per bin for O(1) sliding minimum over `L` frames.
- If `smoothPwr / slidingMin > DELTA`, signal is considered present → noise estimate is frozen (`alpha ≈ 1.0`). Otherwise noise tracks the smoothed power with `ALPHA_D`.
- Noise is seeded from the minimum of the first 10 frames.

**Wiener filter (`wienerFilter()`):**
- Per bin: `snrEst = max(noisyPwr − effNoise, 0) / effNoise`
- Gain: `snrEst / (snrEst + 1)`, clamped to `max(BETA, bandFloor)`
- Asymmetric temporal smoothing: attack = 0.9 (f < 600 Hz) or 0.7 (f ≥ 600 Hz), release = 0.2

---

### 3. `CrnDenoiser.kt` *(superseded — 2026-05-12)*
**Path:** `lungs-app/src/main/java/com/musediagnostics/taal/lungs/denoiser/CrnDenoiser.kt`

**Status: No longer used.** Was the original TFLite-based denoiser. Kept on disk but not referenced by `LungsDenoiser`. Safe to delete in a future cleanup.

Wraps a TFLite `Interpreter` loading `crn_float32.tflite` from assets. Contained a runtime probe strategy scanning 12 candidate chunk sizes. Replaced because the model's RESHAPE and ADD skip-connection constraints are mutually incompatible — no T value passes both `allocateTensors()` and `run()`.

---

### 4. `LungsDenoiser.kt` *(updated 2026-05-12)*
**Path:** `lungs-app/src/main/java/com/musediagnostics/taal/lungs/denoiser/LungsDenoiser.kt`

Orchestrates the full denoise pipeline for one WAV file.

**Constructor:** `LungsDenoiser()` — no parameters, no `Context` required.

**`denoiseWav(inputPath, outputPath): Boolean`**

Steps:
1. `readWav(inputPath)` — reads 16-bit PCM WAV, handles mono/stereo (stereo: left channel only). Returns `Pair(FloatArray, sampleRate)`.
2. Resample to 8 kHz:
   - Source SR == 8000 → no-op
   - Source SR is integer multiple of 8000 → `decimate()` (keep every Nth sample)
   - Otherwise → `resampleLinear()` (linear interpolation)
3. `stft.computeStft(audio8k)` → `(real, imag)`
4. `WienerDenoiser().denoise(real, imag)` → `(enhReal, enhImag)` *(changed 2026-05-12)*
5. `stft.computeIstft(enhReal, enhImag, audio8k.size)` → `denoised`
6. Creates parent dirs for `outputPath`
7. `writeWav(outputPath, denoised, sampleRate=8000)` — writes 16-bit PCM mono WAV at 8 kHz

Returns `true` on success, `false` on any exception. No `close()` — `WienerDenoiser` holds no resources.

**Output WAV is written at 8 kHz** (the Wiener filter's operating rate). Intentional — the denoised file is a different sample rate than the original.

---

### 5. `DenoiserFragment.kt` *(updated 2026-05-12)*
**Path:** `lungs-app/src/main/java/com/musediagnostics/taal/lungs/ui/denoiser/DenoiserFragment.kt`

Shows all 16 lung points for a patient. Each row shows the point label, region, and a button that is either:
- **Disabled "Denoise"** — no recording exists for this point
- **Enabled "Denoise"** — recording exists, not yet denoised
- **"Processing..."** (disabled) — denoise in progress
- **"▶ Play"** (enabled, green dot) — denoised file exists; tapping navigates to `LungsPlayerFragment`

**Arguments received:**

| Arg | Type | Description |
|---|---|---|
| `patientId` | `Long` | Room DB patient ID |
| `patientSeqNum` | `Int` | 2-digit sequence number (used to build file paths) |

**Denoised file path pattern:**
```
{filesDir}/lungs/{seqStr}/denoised/{seqStr}_{pointCode}.wav
```
Example: `/data/data/.../files/lungs/01/denoised/01_aar.wav`

**Denoising flow:**
1. User taps "Denoise" on a row
2. `startDenoising(item)` sets the row to "Processing..." via `processingSet`
3. `LungsDenoiser().denoiseWav(recordingPath, denoisedPath)` runs on `Dispatchers.IO`
4. On success: toast "Done", list rebuilt and resubmitted
5. On failure: toast "Failed — original kept"

**Play flow** *(changed 2026-05-12 — previously used inline `MediaPlayer`)*:
```kotlin
private fun playDenoised(item: DenoiserItem) {
    val bundle = Bundle().apply {
        putString("filePath", item.denoisedFilePath)
        putString("rawFilePath", "")
        putLong("patientId", patientId)
        putInt("patientSeqNum", patientSeqNum)
        putString("pointCode", item.point.code)
        putBoolean("isReviewMode", true)   // hides Save/Discard bar
    }
    findNavController().navigate(R.id.action_denoiser_to_player, bundle)
}
```

`isReviewMode=true` is passed because the denoised file is already written to disk — there is nothing to save or discard from the player screen.

**`DenoiserAdapter`:**
- `ListAdapter<DenoiserItem, ViewHolder>`
- `processingSet: mutableSetOf<String>()` — point codes currently processing
- `setProcessing(pointCode, bool)` calls `notifyItemChanged(index)` for the specific row
- `itemAnimator = null` on RecyclerView to prevent flicker during state changes

**`DenoiserItem` data class:**
```kotlin
data class DenoiserItem(
    val point: LungPoint,
    val recordingFilePath: String?,
    val denoisedFilePath: String?,
    val isDone: Boolean
)
```

---

### 6. `fragment_denoiser.xml`
**Path:** `lungs-app/src/main/res/layout/fragment_denoiser.xml`

`ConstraintLayout` with:
- `topBar` (nested `ConstraintLayout`, 56dp): `backButton` (left) + `screenTitle` "Denoised Recordings" (center)
- `recyclerView` (`RecyclerView`): fills remaining height below top bar, `clipToPadding=false`, `paddingBottom=8dp`

---

### 7. `item_denoiser_row.xml`
**Path:** `lungs-app/src/main/res/layout/item_denoiser_row.xml`

`LinearLayout` (vertical, `match_parent` width, `wrap_content` height):
- Inner `LinearLayout` (horizontal, 64dp height, gravity `center_vertical`):
  - `statusDot` — `View` 10×10dp, background `@drawable/bg_status_dot`; tinted green (`success_green`) when done, grey (`divider`) otherwise
  - `tvPointLabel` — bold 14sp, `text_primary`
  - `tvRegionLabel` — 12sp, `text_secondary`
  - `btnAction` — 88×36dp, `bg_button_teal`, text switches between "Denoise" / "Processing..." / "▶ Play"
- Divider `View` (1dp, `divider` color, `marginStart=38dp` to align with content)

---

### 8. `ic_denoiser.xml`
**Path:** `lungs-app/src/main/res/drawable/ic_denoiser.xml`

Vector drawable — funnel/filter icon (3 horizontal bars narrowing downward). Used in `PatientSessionFragment`'s top bar as the denoiser navigation button.

---

## Files Modified

### `lungs_nav_graph.xml` *(updated 2026-05-12)*
**Path:** `lungs-app/src/main/res/navigation/lungs_nav_graph.xml`

**Original additions (denoiser feature):**

Added inside `patientSessionFragment`:
```xml
<action
    android:id="@+id/action_session_to_denoiser"
    app:destination="@id/denoiserFragment" />
```

Added new destination:
```xml
<fragment
    android:id="@+id/denoiserFragment"
    android:name="com.musediagnostics.taal.lungs.ui.denoiser.DenoiserFragment"
    android:label="Denoised Recordings">
    <argument android:name="patientId" app:argType="long" />
    <argument android:name="patientSeqNum" app:argType="integer" />
</fragment>
```

**New addition (2026-05-12) — play button navigation:**

Added inside `denoiserFragment` destination:
```xml
<action
    android:id="@+id/action_denoiser_to_player"
    app:destination="@id/lungsPlayerFragment" />
```

---

### `fragment_patient_session.xml`
**Path:** `lungs-app/src/main/res/layout/fragment_patient_session.xml`

Added `btnDenoiser` ImageButton in the top bar:
```xml
<ImageButton
    android:id="@+id/btnDenoiser"
    android:layout_width="40dp"
    android:layout_height="40dp"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:src="@drawable/ic_denoiser"
    android:contentDescription="Denoised Recordings"
    app:tint="@color/text_primary"
    app:layout_constraintEnd_toStartOf="@id/btnEditPatient"
    app:layout_constraintTop_toTopOf="parent"
    app:layout_constraintBottom_toBottomOf="parent" />
```

Top bar button order (left → right): `backButton`, `screenTitle`, `btnUploadDrive` (gone), `btnExportZip`, `btnDenoiser`, `btnEditPatient`, `btnShareReport`

---

### `PatientSessionFragment.kt`
**Path:** `lungs-app/src/main/java/com/musediagnostics/taal/lungs/ui/session/PatientSessionFragment.kt`

Added click listener for `btnDenoiser`:
```kotlin
binding.btnDenoiser.setOnClickListener {
    val bundle = Bundle().apply {
        putLong("patientId", patientId)
        putInt("patientSeqNum", patientSeqNum)
    }
    findNavController().navigate(R.id.action_session_to_denoiser, bundle)
}
```

---

## Errors Encountered and Fixes (CRN phase — historical)

### Error 1 — App crash: float16 unsupported (`conv.cc:359`)
**Root cause:** Initial code loaded `crn_float16.tflite`. Android CPU kernels do not support float16 activations.
**Fix:** Switched to `crn_float32.tflite`.

### Error 2 — Toast "Failed": RESHAPE mismatch `261870 != 40377`
**Root cause:** `enhance()` called with full recording STFT (T=2030 → 2030×129=261,870 elements). Model's internal RESHAPE expects exactly 40,377 = 129×313.
**Fix:** Implemented chunked inference with T=313.

### Error 3 — Toast "Failed": RESHAPE mismatch `129 != 40377`
**Root cause:** `chunkFrames` dynamically read from `interpreter.getInputTensor(0).shape().last()` which defaults to **1** (dynamic placeholder). Giving 1×129=129 elements.
**Fix:** Hardcoded `CHUNK_FRAMES=313`, explicit `resizeInput + allocateTensors` in `init`.

### Error 4 — Crash at init: ADD broadcasting `[1,129,79,16]` vs `[1,1,33,16]`
**Root cause:** T=313 satisfies the RESHAPE but breaks an internal skip-connection ADD. No T value satisfies both nodes.
**Resolution:** Runtime probe strategy implemented (12 candidate T values) as a workaround, but the fundamental model conflict is irresolvable → CRN replaced entirely with Wiener filter on 2026-05-12.

---

## File Tree Summary

```
lungs-app/
├── build.gradle.kts                        MODIFIED — added commons-math3 dep
│                                                       (tensorflow-lite still present, unused)
│
├── src/main/java/.../lungs/
│   ├── denoiser/
│   │   ├── StftEngine.kt                   CREATED — STFT / iSTFT (Commons Math3)
│   │   ├── WienerDenoiser.kt               CREATED 2026-05-12 — IMCRA + Wiener filter
│   │   ├── CrnDenoiser.kt                  SUPERSEDED 2026-05-12 — TFLite CRN (keep for reference)
│   │   └── LungsDenoiser.kt                UPDATED 2026-05-12 — uses WienerDenoiser, no Context
│   │
│   └── ui/denoiser/
│       └── DenoiserFragment.kt             UPDATED 2026-05-12 — play navigates to LungsPlayerFragment
│
└── src/main/res/
    ├── drawable/
    │   └── ic_denoiser.xml                 CREATED — funnel icon for top bar button
    ├── layout/
    │   ├── fragment_denoiser.xml           CREATED — top bar + RecyclerView
    │   ├── item_denoiser_row.xml           CREATED — status dot + labels + action button
    │   └── fragment_patient_session.xml    MODIFIED — added btnDenoiser, updated constraints
    └── navigation/
        └── lungs_nav_graph.xml             MODIFIED — denoiserFragment + action_session_to_denoiser
                                                        + action_denoiser_to_player (2026-05-12)
```

---

## Key Technical Notes

- **Phase preservation:** `WienerDenoiser` only modifies STFT magnitude. The original phase is computed inside `denoise()` and recombined before returning the enhanced complex STFT. This is the standard approach for magnitude-only enhancement.
- **Output sample rate:** Denoised WAV is written at 8 kHz (Wiener filter's operating rate), not the original recording's sample rate.
- **No resource cleanup needed:** `WienerDenoiser` holds no native resources. `LungsDenoiser` no longer has a `close()` method.
- **Thread safety:** `LungsDenoiser` is instantiated and used within a single `Dispatchers.IO` coroutine. Not shared between threads.
- **Room DB not updated:** The denoise step writes a new file but does NOT insert a new `LungRecordingEntity`. `DenoiserFragment` checks `File(denoisedPath).exists()` to determine done state.
- **Review mode in player:** `isReviewMode=true` is passed when navigating from `DenoiserFragment` so `LungsPlayerFragment` hides the Save/Discard bar. The file is already on disk; there is nothing to save.
- **Breath-sound preservation:** `BREATH_CORRECTION=0.20` scales the noise estimate down by 80% before applying the Wiener gain, which prevents over-suppression of low-amplitude breath sounds in the 100–300 Hz band.
