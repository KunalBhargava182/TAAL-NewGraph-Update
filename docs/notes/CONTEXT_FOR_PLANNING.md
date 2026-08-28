# TAAL Demo App — Full Project Context (Planning Reference)

> Use this file to brief Claude.ai for planning sessions. It reflects the full codebase as of May 2026.

---

## 1. What This Project Is

TAAL is an Android SDK + demo app for **MUSE Diagnostics' digital USB stethoscope**. It captures clinical audio (heart, lungs, bowel, pregnancy), applies real-time DSP filtering, displays live waveforms, and saves recordings linked to patients.

- **Package**: `com.musediagnostics.taal.app`
- **Language**: Kotlin
- **Build**: Gradle KTS
- **Min SDK**: 24 | **Target SDK**: 34
- **Architecture**: Single-Activity + Jetpack Navigation + MVVM + Room

---

## 2. Module Structure

```
TaalDemoApp/
├── taal-core/          # Pure audio engine — no UI
├── taal-ui-kit/        # Reusable recording/playback Activities
├── app/                # Full clinical demo app
└── lungs-app/          # Specialized lung diagnostics variant
```

### `taal-core` (`com.musediagnostics.taal`)
Pure audio library. No UI, no MPAndroidChart, no patient management.

| File | Purpose |
|------|---------|
| `TaalRecorder.kt` | Public API: wraps TaalAudioCapture + AudioFilterEngine; 0–30 dB pre-amp |
| `TaalPlayer.kt` | WAV playback via AudioTrack; real-time DSP; callbacks: onPlaybackProgress, onPlaybackComplete |
| `core/TaalAudioCapture.kt` | AudioRecord-based capture; 44100 Hz mono 16-bit; writes WAV; USB check |
| `dsp/AudioFilterEngine.kt` | Butterworth bandpass (preset filters) + 5-band graphic EQ (peaking) + pre-amp |
| `dsp/AudioDownsampler.kt` | Linear interpolation downsampler; 44100 Hz → any rate (8000, 4000, 3000, 2000, 1000, 500 Hz) |
| `dsp/HeartResampler.kt` | Legacy 44100→8000 Hz HEART-specific resampler (used in older paths) |
| `utils/SurrUtils.kt` | USB connection check, WAV header read, float buffer util |
| `utils/TaalConnectionBroadcastReceiver.kt` | USB attach/detach broadcast monitor |

**Public enums/exceptions:**
- `PreFilter`: HEART, LUNGS, BOWEL, PREGNANCY, FULL_BODY
- `RecorderState`: INITIAL, RECORDING, STOPPED
- `TaalDisconnectedException` — no USB audio device found
- `TaalNotAvailableForUseException` — AudioRecord failed to init despite USB present
- `InvalidFileNameException` — bad file name passed to recorder

---

### `taal-ui-kit` (`com.musediagnostics.taal.uikit`)
Reusable Activities wrapping taal-core for embedding in 3rd-party apps.

| File | Purpose |
|------|---------|
| `TaalRecorderActivity.kt` | Entry point; returns RESULT_FILE_PATH via onActivityResult |
| `TaalPlayerActivity.kt` | Entry point; plays existing WAV file |
| `recording/RecordingFragment.kt` | Waveform, filter chips, pre-amp slider (0–30 dB) |
| `recording/RecordingViewModel.kt` | uiState, timerSeconds, currentFilter, bpm, preAmpDb, currentRecordingPath, currentFilteredPath |
| `recording/RecordingUiState.kt` | Enum: IDLE, RECORDING, STOPPED |
| `player/PlayerFragment.kt` | Full waveform load, playback, Save/Discard bar |
| `player/TaalSaveDialog.kt` | Names file as {FILTER}_{yyyyMMdd_HHmmss}, saves to filesDir/saved/ |
| `player/SavedRecordingsFragment.kt` | Lists filesDir/saved/ WAV files |
| `player/SavedRecordingAdapter.kt` | RecyclerView adapter with filter icon + duration |
| `dsp/HeartBpmCalculator.kt` | Autocorrelation BPM; downsamples to 1000 Hz; 4s buffer; computes every 5s |
| `util/WavCropper.kt` | getDurationSeconds, getWaveformData, cropWav |

---

### `app` (`com.musediagnostics.taal.app`)
Full-featured demo with auth, patient management, library, and all screens.

**UI Layer:**

| Fragment | Purpose |
|----------|---------|
| `SplashFragment` | Entry point; routes to auth or main |
| `SignInFragment`, `LoginFragment`, `OtpFragment` | Auth flow start |
| `SignUpFragment` | New user registration |
| `SetPinFragment`, `PinConfirmedFragment` | PIN setup |
| `PinLoginFragment` | PIN-based re-login |
| `FingerprintSetupFragment`, `FingerprintConfirmFragment` | Biometric setup |
| `RecordingFragment` | **Hub screen**; recording, waveform, filters, BPM, AI streams |
| `RecordingViewModel` | uiState, filter, preAmpDb, BPM, raw/filtered/AI file paths |
| `SaveRecordingFragment` | Rename + save to internal + copy to Music/ folder |
| `NewRecordingFragment` | Experimental alternate recording UI |
| `CropRecordingFragment` | WAV trimming (WavCropper) |
| `EditRecordingFragment` | Edit recording metadata in DB |
| `PlayerFragment` | Playback with waveform, EQ button, Save/Discard for new recordings |
| `EqualizerFragment` | 5-band graphic EQ (app only, not in taal-ui-kit) |
| `RecordingLibraryFragment` | Browse all recordings (Room JOIN with patients) |
| `SavedRecordingsFragment` | Browse filesDir/saved/ |
| `SharedRecordingsFragment` | (planned / partial) |
| `AddPatientFragment` | Create or link patient to a recording |
| `ProfileFragment` | Edit doctor profile |
| `ChangePinFragment` | PIN change in settings |
| `AboutUsFragment`, `FaqFragment`, `PrivacyPolicyFragment`, `SubscriptionFragment`, `UserManualFragment` | Info screens |

**Data Layer:**

```
Room DB: TaalDatabase (v1)
├── patients table
│   PatientEntity: id, fullName, patientId, phone, email, dateOfBirth,
│                  biologicalSex, conditions, createdAt
└── recordings table
    RecordingEntity: id, patientId (FK→patients, onDelete=SET_NULL),
                     filePath, fileName, filterType, durationSeconds,
                     bpm, preAmplification, isEmergency, notes, createdAt
```

DAOs: `PatientDao`, `RecordingDao`
Repositories: `PatientRepository`, `RecordingRepository` (thin wrappers)

**App-specific DSP:**
- `dsp/HeartBpmCalculator.kt` — same as ui-kit version

**Utility:**
- `util/WavCropper.kt` — getDurationSeconds, getWaveformData, cropWav

---

### `lungs-app` (`com.musediagnostics.taal.lungs`)
Specialized variant for lung diagnostics.

| File | Purpose |
|------|---------|
| `ui/home/HomeFragment.kt` | Start: new patient or select existing |
| `ui/patient/PatientFormFragment.kt` | Patient data entry |
| `ui/placement/PlacementFragment.kt` | Select one of 6 lung points (TL, TR, ML, MR, BL, BR) |
| `ui/recording/LungsRecordingFragment.kt` | Record with **20-second auto-stop** |
| `ui/recording/LungsRecordingViewModel.kt` | State + auto-stop timer |
| `ui/player/LungsPlayerFragment.kt` | Playback |
| `ui/session/PatientSessionFragment.kt` | Save session to LungsDatabase |
| `ui/denoiser/DenoiserFragment.kt` | Denoising UI |
| `denoiser/LungsDenoiser.kt` | Denoiser orchestrator |
| `denoiser/StftEngine.kt` | STFT-based frequency domain processing |
| `denoiser/WienerDenoiser.kt` | Wiener adaptive noise suppression |
| `denoiser/CrnDenoiser.kt` | Conditional denoising |
| `domain/LungPoint.kt` | Enum: 6 lung points with codes/labels/images |
| `drive/DriveUploadHelper.kt` | Google Drive export |
| `data/db/LungsDatabase.kt` | Room DB for lungs |
| `data/entity/LungPatientEntity.kt`, `LungRecordingEntity.kt` | Parallel to app entities |

---

## 3. Complete App Flow

### Navigation Graph (app)
**Start Destination:** `recordingFragment`

```
SplashFragment
├── (new user)
│   SignInFragment → SignUpFragment → OtpFragment
│   → SetPinFragment → PinConfirmedFragment
│   → FingerprintSetupFragment → FingerprintConfirmFragment
│   → RecordingFragment
└── (returning user)
    PinLoginFragment → RecordingFragment

RecordingFragment  ← HUB
├── [record] → PlayerFragment
│   ├── [EQ] → EqualizerFragment
│   └── [save] → SaveRecordingFragment → SavedRecordingsFragment
│
├── [library] → RecordingLibraryFragment
│   └── [tap recording] → PlayerFragment (isNewRecording=false)
│       ├── [save] → AddPatientFragment
│       └── [discard] → delete + back
│
├── [new patient] → AddPatientFragment
├── [profile] → ProfileFragment
├── [settings] → ChangePinFragment
└── [info] → AboutUs / FAQ / Privacy / Subscription / UserManual
```

### Lungs App Navigation
```
HomeFragment
├── [new] → PatientFormFragment → PlacementFragment
└── [existing] → SavedPatientsFragment → PlacementFragment

PlacementFragment → LungsRecordingFragment (auto-stop 20s)
  → LungsPlayerFragment
    ├── [denoise] → DenoiserFragment
    └── [save] → PatientSessionFragment
```

---

## 4. Recording Flow (Detailed)

### Phase 1: Setup
```kotlin
// Two file paths created upfront
rawFilePath      = "${filesDir}/recording_{ts}_raw.wav"
filteredFilePath = "${filesDir}/recording_{ts}_filtered.wav"

TaalRecorder(context).apply {
    setRawAudioFilePath(rawFilePath)
    setFilteredAudioFilePath(filteredFilePath)
    setRecordingTime(300)       // 5 min max
    setPlayback(false)
    setPreAmplification(preAmpDb)
    setPreFilter(preFilter)
}
```

### Phase 2: AI Downsampling Streams (parallel, isolated)
Created alongside main recording — fed from `onRawProgressUpdate` (raw pre-filter audio):

| Filter | AI Files Created |
|--------|-----------------|
| HEART | 8kHz (→ aiTestingPath, saved in discard flow), 4kHz, 1kHz, 500Hz |
| LUNGS | 8kHz, 4kHz, 3kHz, 2kHz |
| BOWEL / PREGNANCY / FULL_BODY | None |

Each stream: `AudioDownsampler.process(rawSamples)` → writes to WAV FileOutputStream

### Phase 3: During Recording (callbacks)
```
onProgressUpdate(sampleRate, bufferSize, timestamp, filteredData)
  ├── Audio monitor: stream filtered audio to AudioTrack (live monitoring)
  ├── BPM: feed to HeartBpmCalculator (HEART filter only)
  └── Waveform: V7 rendering algorithm (see §6)

onRawProgressUpdate(rawData)
  └── AI streams: AudioDownsampler.process() per active stream
```

### Phase 4: Stop
```
taalRecorder.stop()
→ Finalize AI WAV files: flush, close FileOutputStream, patch WAV header (RandomAccessFile)
→ navigate to PlayerFragment:
    filePath = filteredPath
    rawFilePath = rawPath
    aiTestingFilePath = aiTestingPath   // 8kHz HEART only
    isNewRecording = true
    filterName = "HEART" | "LUNGS" | etc.
    extraAiFilePaths = [4kHz, 1kHz, 500Hz, ...]
```

---

## 5. Playback Flow (Detailed)

### PlayerFragment Setup
```
1. Read WAV header bytes 24–27 → actual sample rate
2. Build 3000-pt downsampled waveform for graph
3. Detect if already filtered:
   - filename contains "_filtered" OR "_8k_downsampling" → skip setPreFilter
   - otherwise apply filterName from args
4. TaalPlayer.setDataSource(filePath)
5. TaalPlayer.prepare() → AudioTrack at file's sample rate
6. TaalPlayer.start()
```

### During Playback
```
onPlaybackProgress(timestamp, filteredData)
  ├── Animate playhead position on chart
  └── Pan waveform (moveViewToX)

onPlaybackComplete()
  └── Snap playhead back to start
```

### Save New Recording
```
PlayerFragment → SaveRecordingFragment
  1. Internal save (filesDir/saved/):
     - {safeName}_filtered.wav
     - {safeName}_raw.wav
     - {safeName}_8k_downsampling.wav (HEART only)
     - {safeName}_{suffix}.wav for each extraAiFile
     - {safeName}_*.meta = filterName string (for library icons)
  2. External copy (Music/Taal Saved Audios/):
     - API 29+: MediaStore (IS_PENDING atomic commit)
     - API 24–28: Direct file write (needs WRITE_EXTERNAL_STORAGE)
  3. navigate → SavedRecordingsFragment
```

### Discard New Recording
```
PlayerFragment → delete:
  - filteredFilePath
  - rawFilePath
  - aiTestingFilePath
  - all extraAiFilePaths
→ navigate back to RecordingFragment
```

---

## 6. DSP Pipeline

### Audio Signal Chain (Recording)
```
USB Audio Device
  ↓ 44100 Hz, 16-bit PCM, Mono
TaalAudioCapture.captureAudioToFile()
  ↓ raw PCM bytes → onAudioData callback
TaalRecorder
  ├── → raw.wav (raw bytes written directly)
  └── AudioFilterEngine.process(floatSamples)
        ├── Preset bandpass (Butterworth 2nd-order biquad)
        ├── Pre-amp gain: 10^(dB/20)
        ├── [optional] 5-band graphic EQ (peaking biquads)
        └── soft-clip: tanh(sample)
            ↓
      → filtered.wav + onProgressUpdate(filteredData)

AI Pipeline (orthogonal, from onRawProgressUpdate):
rawSamples → AudioDownsampler(targetRate, preFilter) → {rate}_*.wav
```

### Preset Bandpass Frequencies
| Filter | Low Cut | High Cut | Clinical Target |
|--------|---------|---------|----------------|
| HEART | 20 Hz | 250 Hz | S1/S2 heart sounds |
| LUNGS | 100 Hz | 600 Hz | Breath, crackles, wheezes |
| BOWEL | 100 Hz | 600 Hz | Bowel sounds |
| PREGNANCY | 20 Hz | 250 Hz | Fetal heart sounds |
| FULL_BODY | 20 Hz | 600 Hz | Broad clinical |
| NONE | 20 Hz | 2000 Hz | Full range (hard limit) |

### AudioDownsampler Algorithm
```
ratio = 44100 / outputRate  (e.g., 5.5125 for 8kHz)
1. Apply preset bandpass (anti-alias)
2. Linear interpolation:
   P = 0.0 (carry fractional position across blocks)
   while P+1 < inputLen:
     i0 = floor(P), i1 = i0+1, frac = P - i0
     output = in[i0]*(1-frac) + in[i1]*frac
     P += ratio
```

### Waveform Rendering V7 (Real-Time)
```
X-axis: sampleCounter / 44100.0  → jitter-free, grows infinitely
Downsample: DOWNSAMPLE_STEP = 44  → ~1002 display points/sec
Pagination: 10-second windows; moveViewToX(currentPage * 10)
Memory: keep current page + previous page only

Y-axis (2-phase auto-scale):
  WARMUP (first 2000ms): accumulate peak, axis locked ±1.0
  LOCKED (after warmup): peakAmplitude = warmupPeak × 1.5
                          only expand upward if signal exceeds scale

Pre-amp compensation before display:
  displayData = rawData / 10^(preAmpDb/20)
  (so graph reflects acoustic level, not amplified level)

Chart updates: persistent LineDataSet reuse (no chart.data replacement → no blank frames)
```

### BPM (HeartBpmCalculator)
- Autocorrelation algorithm on HEART-filtered audio
- Downsamples to 1000 Hz internally
- 4-second rolling buffer
- Computes every 5 seconds
- Only active when PreFilter = HEART

---

## 7. File Naming Conventions

### Temp Files (during recording)
```
recording_{timestamp}_raw.wav
recording_{timestamp}_filtered.wav
recording_{timestamp}_8k_downsampling.wav         ← HEART AI
recording_{timestamp}_4k_heart_downsampling.wav
recording_{timestamp}_1k_heart_downsampling.wav
recording_{timestamp}_500hz_heart_downsampling.wav
recording_{timestamp}_8k_lungs_downsampling.wav   ← LUNGS AI
recording_{timestamp}_4k_lungs_downsampling.wav
recording_{timestamp}_3k_lungs_downsampling.wav
recording_{timestamp}_2k_lungs_downsampling.wav
```

### Saved Files (after user confirms save)
```
{safeName}_filtered.wav
{safeName}_raw.wav
{safeName}_8k_downsampling.wav
{safeName}_4k_heart_downsampling.wav
...
{safeName}_{variant}.meta    ← contains filterName string for library UI
```
`safeName` = user input with `/\:*?"<>|` replaced by `_`

---

## 8. Key Patterns & Conventions

### ViewBinding
```kotlin
private var _binding: FragmentXxxBinding? = null
private val binding get() = _binding!!

override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
}
```

### TaalPlayer usage pattern
Always release + recreate on each play (AudioTrack state safety):
```kotlin
taalPlayer?.stop()
taalPlayer?.release()
taalPlayer = TaalPlayer(context)
taalPlayer?.setDataSource(filePath)
taalPlayer?.prepare()
taalPlayer?.start()
```

### RecordingViewModel state
- `currentRecordingPath` is a plain `var` (not LiveData) — must be reset manually
- Pre-amp default: **5 dB**, reset to 5 dB on every `onResume`

### Coroutine scopes
- IO thread: file operations (TaalAudioCapture, SaveRecordingFragment)
- Main thread: UI updates, navigation, toasts, waveform
- Default thread: BPM computation
- `SupervisorJob` + exception handler in TaalPlayer and TaalAudioCapture

### Database access
- Flow<> for reactive list queries
- suspend functions for one-shot reads/writes
- Thin repository wrappers (no business logic in repos)

### WAV header management
- 44-byte header written upfront with placeholder sizes (0)
- After recording, `RandomAccessFile` patches:
  - bytes 4–7: total file size − 8
  - bytes 40–43: data chunk size

### Error handling
- `TaalDisconnectedException`: no USB audio → show "Connect stethoscope" alert
- `TaalNotAvailableForUseException`: AudioRecord failed → show device error
- AI stream failures: swallowed silently (try/catch), never interrupt clinical recording
- Permissions: RECORD_AUDIO (runtime), WRITE_EXTERNAL_STORAGE (API 24–28 only)

---

## 9. Build & Integration

### settings.gradle.kts (current includes)
```
:taal-core, :taal-ui-kit, :app, :lungs-app
```
(`:taal-sdk`, `:taal-recording-kit`, `:taal-player-kit` excluded — legacy, replaced)

### AAR Output Paths
```
taal-core/build/outputs/aar/taal-core-release.aar
taal-ui-kit/build/outputs/aar/taal-ui-kit-release.aar
```
Build command: `./gradlew :taal-core:assembleRelease :taal-ui-kit:assembleRelease`

### Key Dependencies
| Library | Version | Use |
|---------|---------|-----|
| Navigation | 2.7.6 | Single-activity nav |
| Room | 2.6.1 + KSP | Local database |
| Lifecycle/ViewModel | 2.7.0 | MVVM |
| Coroutines | 1.7.3 | Async |
| MPAndroidChart | v3.1.0 (JitPack) | Waveform chart |
| Biometric | 1.1.0 | Fingerprint login |
| ViewBinding | enabled | Layout binding |

### Test Project: taalsdkimplemented
- Package: `com.mused.taalsdkimplemented`
- Uses both AARs from `app/libs/`
- Requires JitPack repo for MPAndroidChart transitive dep
- Must manually declare `TaalRecorderActivity` + `TaalPlayerActivity` in AndroidManifest

---

## 10. Audio Specs Summary

| Parameter | Value |
|-----------|-------|
| Sample Rate | 44,100 Hz |
| Bit Depth | 16-bit PCM |
| Channels | Mono |
| Format | WAV |
| Max Frequency | 2000 Hz (hard-coded limit) |
| Pre-amp Range | 0–30 dB |
| Filter Type | Butterworth bandpass, 2nd order biquad |
| EQ | 5-band peaking, ±12 dB, Q=1.0 |
| EQ Centers | 20, 50, 100, 200, 600 Hz |
| BPM Sample Rate | 1000 Hz (downsampled) |
| BPM Buffer | 4 seconds |

---

## 11. What's NOT in the Codebase

- No cloud sync / backend API (recordings are local only, except lungs-app Drive upload)
- No EQ in `taal-ui-kit` (EqualizerFragment is app-only)
- No old modules: `taal-sdk`, `taal-recording-kit`, `taal-player-kit` (excluded from build)
- `computeWaveformForDisplay` and `writeFilteredWav` removed from WavCropper (2026-03-21)

---

*Generated from full codebase exploration — May 2026*
