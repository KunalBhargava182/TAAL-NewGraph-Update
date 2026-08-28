# TAAL App — Complete Codebase Map
**Package:** `com.musediagnostics.taal.app`  
**Date:** June 2026  
**Modules:** `app` · `taal-core` · `taal-ui-kit`

---

## Table of Contents

1. [App Flow](#1-app-flow)
   - 1.1 [Authentication](#11-authentication)
   - 1.2 [Recording Flow](#12-recording-flow)
   - 1.3 [Player Flow](#13-player-flow)
   - 1.4 [Save Recording Fragment](#14-save-recording-fragment)
   - 1.5 [Equalizer Fragment](#15-equalizer-fragment)
   - 1.6 [Crop Recording Fragment](#16-crop-recording-fragment)
   - 1.7 [Library & File Management](#17-library--file-management)
   - 1.8 [Patient Management](#18-patient-management)
   - 1.9 [Data Layer](#19-data-layer)
   - 1.10 [Dialogs](#110-dialogs)
   - 1.11 [Info Screens & Navigation](#111-info-screens--navigation)
   - 1.12 [Experimental / Dev-Only](#112-experimental--dev-only)
2. [SDK Flows](#2-sdk-flows)
   - 2.1 [taal-core](#21-taal-core)
   - 2.2 [taal-ui-kit](#22-taal-ui-kit)
3. [App vs SDK Comparison Table](#3-app-vs-sdk-comparison-table)
4. [Tablet Fix — What Changed & What Can Break](#4-tablet-fix--what-changed--what-can-break)

---

## 1. App Flow

### 1.1 Authentication

```
App Launch
   │
   └─► SplashFragment
            │
            ├─► [New User]
            │    SignInFragment → SignUpFragment → OtpFragment
            │                                          │
            │                                    SetPinFragment → PinConfirmedFragment
            │
            ├─► [Returning User]
            │    LoginFragment → OtpFragment
            │                        │
            │                   PinLoginFragment         (PIN entry screen)
            │                   FingerprintSetupFragment (biometric setup)
            │                   FingerprintConfirmFragment (biometric verify)
            │
            └─► LoadingFragment (transition/loading state)
```

**Note:** The entire auth system — OTP, PIN, fingerprint — is exclusive to the `app` module. Neither SDK has any authentication.

Settings path:
```
ChangePinFragment   (accessible from DrawerLayout settings)
```

---

### 1.2 Recording Flow

```
RecordingFragment  ─────────────────────────────────────── IDLE state
│
│  Filter row:
│    [HEART] [LUNGS] [BOWEL] [PREGNANCY] [FULL_BODY] [CUSTOM]
│     └── CUSTOM opens: RangeSlider + Low Hz input + High Hz input
│
│  Pre-amp slider: 0–30 dB (default 5 dB, reset to 5 on every onResume)
│  Info button (ⓘ) ──────────────────────────────► FilterPlacementDialog
│  Folder button ────────────────────────────────► SavedRecordingsFragment
│  Settings button ──────────────────────────────► NewRecordingFragment (dev only)
│  Back press LOCKED while recording (toast: "Stop the recording before going back")
│
▼  [Tap Record Button]
│
│  CUSTOM filter validation:
│    • Low or High field empty → error dialog
│    • Low or High = 0 Hz    → error dialog
│    • Low >= High            → error dialog
│    (passes → continues)
│
│  Filter buttons + custom panel disabled + dimmed (alpha 0.4) during recording
│
│  TaalRecorder setup:
│    setRawAudioFilePath("…/recording_{ts}_raw.wav")
│    setFilteredAudioFilePath("…/recording_{ts}_filtered.wav")
│    setRecordingTime(300)          ← 5 minutes max
│    setPlayback(false)             ← app manages AudioTrack itself
│    setPreAmplification(5)
│    if CUSTOM → setCustomBandpass(lowCut, highCut)
│    else      → setPreFilter(PreFilter.valueOf(filterName))
│
▼  TaalRecorder.start()
│
│  Two WAV files written simultaneously on IO thread:
│  ┌──────────────────────────────┐  ┌────────────────────────────────────┐
│  │  recording_{ts}_raw.wav      │  │  recording_{ts}_filtered.wav       │
│  │  (raw USB audio via          │  │  (DSP-filtered in real-time via    │
│  │   TaalAudioCapture)          │  │   AudioFilterEngine in TaalRecorder)│
│  └──────────────────────────────┘  └────────────────────────────────────┘
│
│  onProgressUpdate (filtered FloatArray, called ~40×/sec)
│    ├── Undo pre-amp gain before drawing:
│    │     displayData[i] = data[i] / 10^(preAmpDb/20)
│    │     (graph always shows acoustic level, not amplified level)
│    ├── updateWaveform(timestamp, displayData)
│    ├── HeartBpmCalculator.addSamples(data)
│    │     → if ready: bpmScope.launch { computeBpm() } → viewModel.setBpm()
│    └── AudioTrack.write(pcm) ← live speaker monitor (optional)
│
│  onRawProgressUpdate(data: FloatArray)
│    └── Currently no-op (AI downsampling pipeline disabled)
│
▼  [Tap Stop Button]
│
│  stopAudioMonitor() + taalRecorder.stop()
│  Navigate → PlayerFragment
│    args: filePath=_filtered.wav, rawFilePath=_raw.wav,
│          isNewRecording=true, filterName
```

**Waveform Rendering — V7 (Sample-Accurate ECG):**

| Property | Value |
|---|---|
| Window | 10 seconds |
| Downsample step | 44 (→ ~1002 display pts/sec at 44100 Hz) |
| Warmup duration | 2000 ms |
| After warmup | Y-axis locked at `warmupPeak × 1.5`, never re-expands |
| Scrolling style | ECG page-flip: `moveViewToX(currentPage × 10s)` |
| Memory cleanup | Keep current + previous 10s page only |
| Dataset strategy | Persistent `LineDataSet` — values updated in place, never `chart.data` replaced (avoids blank-frame flash from MPAndroidChart re-init) |
| Pre-amp removal | `displayData = data / gain` before `updateWaveform()` |

---

### 1.3 Player Flow

```
PlayerFragment
│
│  Receives: filePath, rawFilePath, isNewRecording, filterName
│
├── loadFullWaveform(filePath, filterName)  [Dispatchers.IO]
│    ├── Reads WAV bytes directly from file
│    ├── Reads sample rate from WAV header bytes 24–27 (little-endian)
│    │     (handles non-44100 Hz files — e.g. 8kHz AI testing files)
│    ├── Downsamples to max 3000 display points regardless of duration
│    └── Renders on Main thread → waveform is interactive (drag + pinch-zoom)
│
├── setupPlayer(filePath, filterName)  [TaalPlayer]
│    ├── setDataSource(filePath)
│    ├── Guard: if filename contains "_filtered" OR "_8k_downsampling"
│    │     → skip setPreFilter() (already DSP-processed, double-filter = wrong response)
│    │   else
│    │     → setPreFilter(PreFilter.valueOf(filterName))
│    ├── onPlaybackProgress → update timer + scroll waveform to follow playhead
│    └── onPlaybackComplete → reset play button + snap waveform to start
│
├── Pre-amp slider → TaalPlayer.setPreAmplification(db) live during playback
│
├── EQ button ──────────────────────────────────────────► EqualizerFragment
│    (passes: filePath)
│
├── [isNewRecording = true]  Save/Discard bar VISIBLE
│    ├── Save button ──────────────────────────────────► SaveRecordingFragment
│    │    (passes: filePath, rawFilePath, filterName)
│    └── Discard button → confirm dialog
│         → File(filePath).delete()
│         → File(rawFilePath).delete()
│         → navigateUp()
│
└── [isNewRecording = false]  Save/Discard bar HIDDEN
     Save or Discard → PlayerSaveDiscardDialog
          Save    → AddPatientFragment (save to patient DB)
          Discard → delete file → navigateUp()
```

**TaalPlayer lifecycle (must be followed exactly):**
```
TaalPlayer(context)
  .setDataSource(filePath)
  [.setPreFilter(filter)]     ← only for raw/non-filtered files
  [.setGraphicEQ(eqState)]    ← from EqualizerFragment
  .prepare()                  ← creates AudioTrack
  .start()                    ← starts coroutine
  .stop()                     ← when user stops or fragment pauses
  .release()                  ← in onDestroyView()
```

---

### 1.4 Save Recording Fragment

```
SaveRecordingFragment
│
│  Receives: filePath (_filtered temp), rawFilePath (_raw temp), filterName
│
│  UI: text input pre-filled with {yyyyMMdd_HHmmss}, user can edit
│
▼  [Tap Save]
│
│  safeName = userInput.replace("[/\\:*?\"<>|]", "_")
│  fullSaveName = "{FILTER}_{safeName}"
│    e.g.  "HEART_20260630_142335"
│    (filter embedded as prefix — icon always derivable from filename alone)
│
│  Step 1: Internal save (always first, must succeed)
│    moveFile(_filtered temp → filesDir/saved/{fullSaveName}_filtered.wav)
│    moveFile(_raw temp      → filesDir/saved/{fullSaveName}_raw.wav)
│    moveFile() uses renameTo(), falls back to copyTo()+delete() on cross-mount failure
│
│  Step 2: Copy to device storage
│    API 29+  → MediaStore (Music/Taal Saved Audios/)
│               IS_PENDING=1 → write bytes → IS_PENDING=0 (makes visible to other apps)
│    API < 29 → Check WRITE_EXTERNAL_STORAGE permission
│               Granted  → direct file copy to Environment.DIRECTORY_MUSIC/Taal Saved Audios/
│               Denied   → toast "saved in app storage only"
│
▼  Navigate → SavedRecordingsFragment
     NavOptions: popUpTo=RecordingFragment
     (user cannot go back to PlayerFragment)
```

---

### 1.5 Equalizer Fragment

> **App-only. Not in taal-ui-kit.**

```
EqualizerFragment
│
│  Receives: filePath (already processed WAV)
│
├── EqCurveView  (custom inner class View)
│    ├── 5 draggable control points: 20 | 50 | 100 | 250 | 1000 Hz
│    ├── Frequency axis: 10 Hz – 2000 Hz (logarithmic scale)
│    ├── Gain axis: -12 dB to +12 dB
│    ├── Rendered with cubic bezier curve + filled area
│    ├── Real-time spectrum overlay:
│    │     64-band pseudo-FFT from onPlaybackProgress FloatArray
│    │     rendered as wave INSIDE the EQ area, behind the curve
│    ├── Touch drag: neighbor-frequency collision avoidance (1.2× min separation)
│    └── requestDisallowInterceptTouchEvent while dragging
│
├── 3 custom preset slots (FloatArray[5] stored in memory per slot)
│    Load via PopupMenu → loadPreset(index) → all 5 gains applied + applyEqToPlayer()
│
├── Reset button → all gains = 0f → applyEqToPlayer()
│
├── applyEqToPlayer()
│    Maps UI 5-point control to AudioFilterEngine.GraphicEQState:
│      UI 20Hz  → band20Hz
│      UI 50Hz  → band50Hz
│      UI 100Hz → band100Hz
│      UI 250Hz → band200Hz  (closest SDK band)
│      UI 1000Hz→ band600Hz  (closest SDK band)
│    TaalPlayer.setGraphicEQ(eqState)
│
├── TaalPlayer recreated on each play (AudioTrack state safety)
│    EQ applied BEFORE prepare()+start()
│    EQ also applied live while dragging (real-time update during playback)
│
└── Playback: togglePlayback() → standard prepare/start/stop lifecycle
```

---

### 1.6 Crop Recording Fragment

> **App-only. Not in taal-ui-kit.**

```
CropRecordingFragment
│
│  Receives: filePath
│
├── WavCropper.getDurationSeconds(filePath)  → total duration
├── WavCropper.getWaveformData(filePath)     → FloatArray for display
│
├── CropWaveformView  (custom inner class View)
│    ├── Displays full waveform as filled area
│    ├── Two draggable handles: cropStart + cropEnd
│    ├── Shaded overlay on cropped-out regions
│    ├── Touch drag with bounds clamping (start < end always)
│    └── Returns cropStartSeconds + cropEndSeconds
│
├── TaalPlayer for preview playback of the selected region
│
└── [Confirm Crop]
     WavCropper.cropWav(filePath, startSec, endSec, outputPath)
     → writes trimmed WAV to new file
     → navigate to PlayerFragment with new file
```

---

### 1.7 Library & File Management

```
SavedRecordingsFragment  (app version)
│
├── Scans filesDir/saved/ for *_filtered.wav files
│    sorted by lastModified() descending
├── Empty state view if no files
├── SavedRecordingAdapter
│    ├── Filter icon (derived from filename prefix: HEART_, LUNGS_, etc.)
│    └── Duration (WavCropper.getDurationSeconds)
│
├── Tap item ─────────────────────────────────────► PlayerFragment
│    args: filePath, isNewRecording=false, filterName
│
├── Share button (per item)
│    FileProvider.getUriForFile(…, file)
│    Intent.ACTION_SEND (type: audio/wav)
│    + FLAG_GRANT_READ_URI_PERMISSION
│
└── Delete button (per item)
     MaterialAlertDialogBuilder confirm
     file.delete()                               ← _filtered.wav
     File(parent, name.replace("_filtered","_raw")).delete()  ← _raw.wav
     loadRecordings() to refresh list

RecordingLibraryFragment  (DB-driven)
├── Queries RecordingRepository (Room DB)
├── RecordingLibraryAdapter → recordings linked to patients
└── PatientLibraryAdapter / PatientLibraryItem

SharedRecordingsFragment
└── Separate view for shared/imported recordings
```

**Filter name extraction from filename:**
```kotlin
// Filename format: "{FILTER}_{userInput}_filtered.wav"
// Known prefixes checked in order (longest first to avoid BODY matching FULL_BODY):
listOf("FULL_BODY", "PREGNANCY", "CUSTOM", "LUNGS", "BOWEL", "HEART")
```

---

### 1.8 Patient Management

> **App-only. Neither SDK has any patient concept.**

```
AddPatientFragment
└── Form fields:
     fullName, patientId, phone, email,
     dateOfBirth, biologicalSex, conditions

PatientViewModel  (AndroidViewModel)
└── PatientRepository → PatientDao → Room DB patients table

PatientSearchAdapter
└── Live search/filter on patient list (used from RecordingLibraryFragment)
```

---

### 1.9 Data Layer

> **App-only. Neither SDK has a database.**

```
TaalDatabase  (Room v1)
│
├── patients  ──────────────────────────────── PatientEntity
│    id (PK)           BIGINT auto-gen
│    fullName          TEXT
│    patientId         TEXT
│    phone             TEXT
│    email             TEXT
│    dateOfBirth       TEXT
│    biologicalSex     TEXT
│    conditions        TEXT
│    createdAt         BIGINT (epoch ms)
│
└── recordings  ─────────────────────────────── RecordingEntity
     id (PK)           BIGINT auto-gen
     patientId (FK)    → patients.id  ON DELETE SET NULL
     filePath          TEXT
     fileName          TEXT
     filterType        TEXT
     durationSeconds   FLOAT
     bpm               INT
     preAmplification  INT
     isEmergency       BOOLEAN
     notes             TEXT
     createdAt         BIGINT (epoch ms)

RecordingWithPatient  ── relation join class

Repositories:
  PatientRepository   (thin wrapper over PatientDao)
  RecordingRepository (thin wrapper over RecordingDao)
```

---

### 1.10 Dialogs

> **All app-only.**

| Dialog | Trigger | Purpose |
|---|---|---|
| `FilterPlacementDialog` | Info ⓘ button in RecordingFragment | Shows stethoscope placement guide per filter |
| `PlayerSaveDiscardDialog` | Save/Discard on existing (non-new) recording in PlayerFragment | Choose: save to patient DB or discard |
| `RenameDialog` | From library/edit flow | Rename an existing saved recording |
| `EmergencySaveDialog` | *(currently commented out)* | Emergency save with custom filename prefix |
| `SaveAlertDialog` | *(currently commented out)* | Three-way: Save / Emergency / Discard choice |

---

### 1.11 Info Screens & Navigation

```
MainActivity
└── DrawerLayout
     └── NavigationView
          └── NavHostFragment  (nav_graph)
               │
               ├── SplashFragment           (start destination)
               ├── Auth fragments (see 1.1)
               ├── RecordingFragment
               ├── PlayerFragment
               ├── EqualizerFragment
               ├── SaveRecordingFragment
               ├── CropRecordingFragment
               ├── SavedRecordingsFragment
               ├── RecordingLibraryFragment
               ├── SharedRecordingsFragment
               ├── AddPatientFragment
               ├── EditRecordingFragment
               ├── ProfileFragment
               ├── ChangePinFragment
               ├── AboutUsFragment
               ├── FaqFragment
               ├── PrivacyPolicyFragment
               ├── SubscriptionFragment
               └── UserManualFragment
```

---

### 1.12 Experimental / Dev-Only

> These are **NOT wired into the nav graph** and not shipping to users.

| Fragment | Purpose |
|---|---|
| `NewRecordingFragment` | Kunal's alternate recording UI (comment at top: "NOT IN USE") |
| `TestRecordingFragment` | Dev testing / sandbox |
| `TestPlayerFragment` | Dev testing / sandbox |

---

---

## 2. SDK Flows

---

### 2.1 taal-core

> **Package:** `com.musediagnostics.taal`  
> **Output:** `taal-core/build/outputs/aar/taal-core-release.aar`  
> **Build:** `./gradlew :taal-core:assembleRelease`  
> **No UI. No MPAndroidChart. Pure audio engine.**

```
taal-core
│
├── PUBLIC API
│   │
│   ├── TaalRecorder(context)
│   │    ├── setRawAudioFilePath(path: String)        must end in .wav
│   │    ├── setFilteredAudioFilePath(path: String)   must end in .wav
│   │    ├── setRecordingTime(seconds: Int)            default: 30s
│   │    ├── setPlayback(enabled: Boolean)             built-in speaker monitor
│   │    ├── setPreAmplification(db: Int)              0–30 dB
│   │    ├── setPreFilter(PreFilter)                   preset bandpass
│   │    ├── setCustomBandpass(low: Double, high: Double)
│   │    ├── start()  ──────────────────────────────► throws TaalDisconnectedException
│   │    │                                              if no USB audio device found
│   │    ├── stop()
│   │    │
│   │    ├── onInfoListener: OnInfoListener?
│   │    │    ├── onStateChange(state: RecorderState)
│   │    │    ├── onProgressUpdate(sampleRate, bufferSize, timestamp, data: FloatArray)
│   │    │    │     data = DSP-filtered + pre-amplified audio
│   │    │    └── onRawProgressUpdate(data: FloatArray)
│   │    │          data = raw USB audio before any DSP
│   │    │
│   │    └── onLiveStreamListener: OnLiveStreamListener?
│   │         └── onNewStream(bytes: ByteArray)
│   │
│   ├── TaalPlayer(context)
│   │    ├── setDataSource(filePath: String)      reads WAV sample rate from header
│   │    ├── setPreFilter(filter: PreFilter)      bandpass preset
│   │    ├── setCustomBandpass(low: Double, high: Double)
│   │    ├── setGraphicEQ(eqState: GraphicEQState)  5-band parametric EQ
│   │    ├── setPreAmplification(db: Float)
│   │    ├── setLooping(loop: Boolean)
│   │    ├── prepare()                            creates AudioTrack
│   │    ├── start()                              starts coroutine
│   │    ├── stop()
│   │    ├── release()                            releases AudioTrack
│   │    ├── onPlaybackProgress: ((Double, FloatArray) -> Unit)?
│   │    └── onPlaybackComplete: (() -> Unit)?
│   │
│   ├── PreFilter (enum)
│   │    HEART      20–250 Hz   heart sounds (S1/S2)
│   │    LUNGS      100–600 Hz  lung auscultation
│   │    BOWEL      100–600 Hz  bowel sounds
│   │    PREGNANCY  20–250 Hz   fetal heart
│   │    FULL_BODY  20–600 Hz   general
│   │
│   └── Exceptions
│        TaalDisconnectedException      USB device not connected at start()
│        TaalNotAvailableForUseException USB device claimed by another app
│        InvalidFileNameException        non-.wav file passed to TaalPlayer
│
├── INTERNAL ENGINE
│   │
│   ├── TaalAudioCapture(context)
│   │    Audio: 44100 Hz · Mono · 16-bit PCM · WAV
│   │    Writes raw WAV: placeholder header → update on stop (RandomAccessFile)
│   │    checkUsbConnection(): checks USB_CLASS_AUDIO at device + interface level
│   │
│   │    ╔══════════════════════════════════════════════════════════╗
│   │    ║              TABLET FIX (4 changes, 1 file)              ║
│   │    ╠══════════════════════════════════════════════════════════╣
│   │    ║                                                          ║
│   │    ║  1. Buffer size                                          ║
│   │    ║     Old: getMinBufferSize() * 2                          ║
│   │    ║     New: max(getMinBufferSize() * 4, 8192)               ║
│   │    ║     Why: mid-range tablet SoCs need more headroom        ║
│   │    ║                                                          ║
│   │    ║  2. AudioSource fallback chain (buildAudioRecord())      ║
│   │    ║     Try 1: UNPROCESSED  ← raw signal, zero OS processing ║
│   │    ║     Try 2: VOICE_RECOGNITION ← no beam-forming          ║
│   │    ║     Try 3: DEFAULT ← original (last resort)             ║
│   │    ║     Why: tablets route DEFAULT to internal mic array     ║
│   │    ║           especially on Android 16                       ║
│   │    ║                                                          ║
│   │    ║  3. Disable system audio effects (disableSystemAudioEffects) ║
│   │    ║     AGC.isAvailable() → enabled=false                   ║
│   │    ║     NoiseSuppressor.isAvailable() → enabled=false        ║
│   │    ║     AcousticEchoCanceler.isAvailable() → enabled=false   ║
│   │    ║     Why: voice-call effects passband 300–3400 Hz         ║
│   │    ║           destroys heart sounds at 20–250 Hz             ║
│   │    ║                                                          ║
│   │    ║  4. Lock to USB device (lockToUsbAudioDevice, API 28+)   ║
│   │    ║     record.preferredDevice = USB_DEVICE or USB_HEADSET   ║
│   │    ║     Why: prevents OS re-routing to internal mic          ║
│   │    ║           mid-recording on tablets (e.g. notifications)  ║
│   │    ║                                                          ║
│   │    ╚══════════════════════════════════════════════════════════╝
│   │
│   ├── AudioFilterEngine
│   │    Biquad bandpass (2nd-order Butterworth) — preset filters
│   │    5-band peaking EQ — graphic equalizer
│   │    MAX_FREQUENCY_HZ = 2000 Hz (hard limit in filter design)
│   │    setPresetFilter(PresetFilter)
│   │    setCustomBandpass(low: Double, high: Double)
│   │    setGraphicEQ(GraphicEQState)
│   │    processBlock(data: FloatArray): FloatArray
│   │
│   │    GraphicEQState(
│   │      band20Hz, band50Hz, band100Hz, band200Hz, band600Hz
│   │    )
│   │
│   ├── RecorderState (enum): INITIAL · RECORDING · STOPPED
│   │
│   ├── AudioDownsampler    ← available, not active in app right now
│   │    Downsamples to any target sample rate
│   │
│   ├── HeartResampler      ← available, not active in app right now
│   │    HEART filter + downsample for AI model input
│   │    Produces files at: 8kHz, 4kHz, 1kHz, 500Hz
│   │
│   ├── SurrUtils
│   │    checkUsbConnection()
│   │    readWavHeader()
│   │    floatToByteBuffer()
│   │
│   └── TaalConnectionBroadcastReceiver
│        Listens: USB_DEVICE_ATTACHED / USB_DEVICE_DETACHED
│        Callbacks: onTaalConnect() / onTaalDisconnect()
│
└── AUDIO SPECS
     Sample rate:  44,100 Hz
     Bit depth:    16-bit PCM
     Channels:     Mono
     Format:       WAV
     Max filter:   2,000 Hz (hard-coded in AudioFilterEngine)
     Pre-amp:      0–30 dB
```

**Filter frequency bands:**

| PreFilter | Low Cut | High Cut | Clinical Use |
|---|---|---|---|
| HEART | 20 Hz | 250 Hz | Heart sounds (S1/S2) |
| LUNGS | 100 Hz | 600 Hz | Lung auscultation |
| BOWEL | 100 Hz | 600 Hz | Bowel sounds |
| PREGNANCY | 20 Hz | 250 Hz | Fetal heart |
| FULL_BODY | 20 Hz | 600 Hz | General |
| CUSTOM | user-set | user-set | Custom bandpass |

---

### 2.2 taal-ui-kit

> **Package:** `com.musediagnostics.taal.uikit`  
> **Output:** `taal-ui-kit/build/outputs/aar/taal-ui-kit-release.aar`  
> **Build:** `./gradlew :taal-ui-kit:assembleRelease`  
> **Depends on taal-core + MPAndroidChart**

```
taal-ui-kit
│
├── ENTRY POINTS
│   │
│   ├── TaalRecorderActivity
│   │    startActivity(Intent)
│   │    Result: RESULT_FILE_PATH → absolute path of the _filtered.wav
│   │    Hosts: RecordingFragment (SDK version)
│   │
│   └── TaalPlayerActivity
│        startActivity(Intent with filePath extra)
│        Hosts: PlayerFragment (SDK version)
│        Plays any saved WAV file
│
├── RECORDING SIDE
│   │
│   ├── RecordingFragment  (SDK version)
│   │    Filter chips: HEART | LUNGS | BOWEL | PREGNANCY | FULL_BODY
│   │    ❌ NO CUSTOM filter
│   │    ❌ NO FilterPlacementDialog
│   │    ❌ NO back-press lock
│   │    Pre-amp slider: 0–30 dB (default 5 dB, reset on every onResume)
│   │    Waveform: same V7 logic (warmup + page-scroll + persistent dataset)
│   │    BPM: HeartBpmCalculator (same algorithm)
│   │    USB icon: TaalConnectionBroadcastReceiver (icon_taal.xml, teal #008DB9)
│   │    Live monitor: AudioTrack during recording
│   │    Stop → navigate to PlayerFragment (isNewRecording=true)
│   │
│   └── RecordingViewModel  (SDK version)
│        Plain ViewModel  ← NOT AndroidViewModel (no Application context)
│        ❌ NO RecordingRepository
│        ❌ NO customLowCut / customHighCut
│        ❌ NO currentAiTestingPath / extraAiFilePaths
│        Fields: uiState, timerSeconds, currentFilter, bpm, preAmpDb,
│                currentRecordingPath (raw), currentFilteredPath (filtered)
│
├── PLAYER SIDE
│   │
│   ├── PlayerFragment  (SDK version)
│   │    loadFullWaveform():
│   │      Uses HARDCODED 44100f  ← does NOT read sample rate from WAV header
│   │      (differs from app's PlayerFragment which reads header bytes 24–27)
│   │    Waveform: interactive (drag + pinch-zoom)
│   │    ❌ NO EQ button
│   │    ❌ NO navigate to EqualizerFragment
│   │    ❌ NO navigate to SaveRecordingFragment
│   │    Save → TaalSaveDialog
│   │    Discard → delete _filtered + _raw → navigateUp()
│   │
│   ├── TaalSaveDialog
│   │    Default filename: {yyyyMMdd_HHmmss}   (auto-timestamp)
│   │    Saves to: filesDir/saved/{name}_filtered.wav
│   │    Also saves: filesDir/saved/{name}_raw.wav
│   │    Writes sidecar: {name}_filtered.meta + {name}_raw.meta (contains filterName)
│   │    ❌ NO copy to device Music/ folder
│   │    ❌ NO MediaStore
│   │    ❌ NO WRITE_EXTERNAL_STORAGE flow
│   │
│   └── SavedRecordingsFragment  (SDK version)
│        Lists filesDir/saved/ *_filtered.wav
│        ❌ NO Share
│        ❌ NO Delete
│        SavedRecordingAdapter → filter icon + duration
│        Tap → PlayerFragment (isNewRecording=false)
│
└── SUPPORTING CLASSES
     RecordingUiState (enum): IDLE · RECORDING · STOPPED
     HeartBpmCalculator: autocorrelation (4s buffer, 5s interval, 40–200 BPM range)
     WavCropper:
       getDurationSeconds(filePath)
       getWaveformData(filePath)
       ❌ NO cropWav()   ← only the app's WavCropper has this
```

---

---

## 3. App vs SDK Comparison Table

> ✅ = present and active · 🔇 = present but disabled/commented out · ❌ = not present

### Recording

| Feature | App | taal-core | taal-ui-kit |
|---|:---:|:---:|:---:|
| CUSTOM filter (RangeSlider + Hz inputs) | ✅ | ❌ | ❌ |
| CUSTOM filter validation (0Hz, empty, low≥high errors) | ✅ | ❌ | ❌ |
| FilterPlacementDialog (info ⓘ button) | ✅ | ❌ | ❌ |
| Back-press lock during recording | ✅ | ❌ | ❌ |
| Filter buttons disabled + dimmed during recording | ✅ | ❌ | ❌ |
| Custom range panel auto-hide during recording | ✅ | ❌ | ❌ |
| Preset filters (HEART / LUNGS / BOWEL / PREGNANCY / FULL_BODY) | ✅ | ✅ enum | ✅ |
| setCustomBandpass() | ✅ | ✅ API | ❌ |
| Pre-amp slider 0–30 dB | ✅ | ✅ API | ✅ |
| V7 Waveform (warmup + page-scroll + persistent dataset) | ✅ | ❌ | ✅ |
| Pre-amp removal before waveform draw | ✅ | ❌ | ✅ |
| BPM display via autocorrelation | ✅ | ❌ | ✅ |
| USB connection icon (TaalConnectionBroadcastReceiver) | ✅ | ✅ receiver | ✅ |
| Live audio monitor (AudioTrack during recording) | ✅ | ❌ | ✅ |
| Dual-file recording (raw + filtered) | ✅ | ✅ API | ✅ |
| onRawProgressUpdate callback | 🔇 no-op | ✅ | ❌ |
| AI downsampling (AudioDownsampler, HeartResampler) | 🔇 disabled | ✅ available | ❌ |

### Player

| Feature | App | taal-core | taal-ui-kit |
|---|:---:|:---:|:---:|
| Full waveform load from WAV bytes | ✅ | ❌ | ✅ |
| WAV sample rate read from header (non-44100 Hz support) | ✅ | ✅ TaalPlayer | ❌ hardcoded 44100 |
| Waveform scrolls in sync with playback | ✅ | ❌ | ✅ |
| Pre-amp slider during playback | ✅ | ✅ API | ✅ |
| EQ button → EqualizerFragment | ✅ | ❌ | ❌ |
| Save → SaveRecordingFragment (with device copy) | ✅ | ❌ | ❌ |
| Save → TaalSaveDialog (app storage only) | ❌ | ❌ | ✅ |
| Discard deletes both _filtered + _raw | ✅ | ❌ | ✅ |
| isNewRecording=false → PlayerSaveDiscardDialog | ✅ | ❌ | ❌ |
| Double-filter guard (`_filtered` filename check) | ✅ | ❌ | ✅ |

### Save Flow

| Feature | App | taal-core | taal-ui-kit |
|---|:---:|:---:|:---:|
| Internal save to filesDir/saved/ | ✅ | ❌ | ✅ |
| Copy to Music/Taal Saved Audios (MediaStore API 29+) | ✅ | ❌ | ❌ |
| IS_PENDING=1 → write → IS_PENDING=0 pattern | ✅ | ❌ | ❌ |
| Direct file write (API 24–28) + permission flow | ✅ | ❌ | ❌ |
| moveFile() with copy+delete fallback | ✅ | ❌ | ❌ |
| Filename: {FILTER}_{userInput} (filter prefix embedded) | ✅ | ❌ | ❌ |
| Filename: {FILTER}_{yyyyMMdd_HHmmss} (auto-timestamp) | ❌ | ❌ | ✅ |
| .meta sidecar file (stores filterName per WAV) | ❌ | ❌ | ✅ |
| Filter name extracted from filename prefix | ✅ | ❌ | ❌ |
| Filter name read from .meta file | ❌ | ❌ | ✅ |

### Saved Recordings List

| Feature | App | taal-core | taal-ui-kit |
|---|:---:|:---:|:---:|
| List _filtered.wav files from filesDir/saved/ | ✅ | ❌ | ✅ |
| Share via FileProvider + Intent.ACTION_SEND | ✅ | ❌ | ❌ |
| Delete (both _filtered + _raw) with confirm dialog | ✅ | ❌ | ❌ |
| Empty state view | ✅ | ❌ | ✅ |

### Equalizer (app-only)

| Feature | App | taal-core | taal-ui-kit |
|---|:---:|:---:|:---:|
| EqualizerFragment | ✅ | ❌ | ❌ |
| Draggable parametric EQ curve (EqCurveView) | ✅ | ❌ | ❌ |
| Real-time spectrum overlay inside EQ | ✅ | ❌ | ❌ |
| 3 custom EQ preset slots | ✅ | ❌ | ❌ |
| AudioFilterEngine.GraphicEQState used in UI | ✅ | ✅ API | ❌ |

### Crop Recording (app-only)

| Feature | App | taal-core | taal-ui-kit |
|---|:---:|:---:|:---:|
| CropRecordingFragment with drag handles | ✅ | ❌ | ❌ |
| WavCropper.cropWav() | ✅ | ❌ | ❌ |
| WavCropper.getDurationSeconds() | ✅ | ❌ | ✅ |
| WavCropper.getWaveformData() | ✅ | ❌ | ✅ |

### Auth, Data, Patient (all app-only)

| Feature | App | taal-core | taal-ui-kit |
|---|:---:|:---:|:---:|
| OTP / PIN / Fingerprint authentication | ✅ | ❌ | ❌ |
| ChangePinFragment | ✅ | ❌ | ❌ |
| Room DB (patients + recordings tables) | ✅ | ❌ | ❌ |
| PatientEntity / RecordingEntity | ✅ | ❌ | ❌ |
| PatientRepository / RecordingRepository | ✅ | ❌ | ❌ |
| RecordingViewModel extends AndroidViewModel | ✅ | ❌ | ❌ plain ViewModel |
| customLowCut / customHighCut in ViewModel | ✅ | ❌ | ❌ |
| AddPatientFragment | ✅ | ❌ | ❌ |
| PatientViewModel (CRUD) | ✅ | ❌ | ❌ |
| RecordingLibraryFragment (DB-driven) | ✅ | ❌ | ❌ |
| PatientSearchAdapter | ✅ | ❌ | ❌ |

### Navigation & Info (all app-only)

| Feature | App | taal-core | taal-ui-kit |
|---|:---:|:---:|:---:|
| DrawerLayout + NavigationView | ✅ | ❌ | ❌ |
| About / FAQ / Privacy / Subscription / UserManual | ✅ | ❌ | ❌ |
| ProfileFragment | ✅ | ❌ | ❌ |

---

---

## 4. Tablet Fix — What Changed & What Can Break

### The Problem (Summary)

On **Android 16 tablets** (tested on Samsung Galaxy Tab A9+), the TAAL app recorded from the **built-in microphone array** instead of the USB TAAL stethoscope. Android 14 tablets worked better by accident — they happened to route `AudioSource.DEFAULT` to the USB device, but Android 16 tightened this to strictly mean "system default input", which for tablets is the internal mic array with voice-call audio processing.

Three compounding problems existed in the original code:

| # | Problem | Location | Impact |
|---|---|---|---|
| 1 | `AudioSource.DEFAULT` used instead of `UNPROCESSED` | `TaalAudioCapture.kt` line 80 (old) | Audio routes to built-in mic; OS applies voice processing to heart sounds |
| 2 | No audio effects disabled | `TaalAudioCapture.kt` — missing | AGC/NoiseSuppressor silently destroy 20–250 Hz heart-sound range |
| 3 | Buffer size `×2` too small | `TaalAudioCapture.kt` line 47 (old) | Frame drops on mid-range tablet SoCs = click artifacts in waveform |

---

### What Was Changed (All in One File)

**File:** `taal-core/src/main/java/com/musediagnostics/taal/core/TaalAudioCapture.kt`

| Change | Before | After |
|---|---|---|
| Buffer size | `getMinBufferSize() * 2` | `max(getMinBufferSize() * 4, 8192)` |
| AudioRecord creation | `AudioRecord(AudioSource.DEFAULT, …)` single call | Fallback chain: `buildAudioRecord()` |
| Fallback chain #1 | — | Try `UNPROCESSED` → return if STATE_INITIALIZED |
| Fallback chain #2 | — | Try `VOICE_RECOGNITION` → return if STATE_INITIALIZED |
| Fallback chain #3 | — | Try `DEFAULT` (original behaviour — last resort) |
| Audio effects | Not disabled | `disableSystemAudioEffects(record.audioSessionId)` |
| AGC | Not touched | `AutomaticGainControl.create(sessionId)?.enabled = false` |
| NoiseSuppressor | Not touched | `NoiseSuppressor.create(sessionId)?.enabled = false` |
| AcousticEchoCanceler | Not touched | `AcousticEchoCanceler.create(sessionId)?.enabled = false` |
| USB device routing | OS chooses freely | `lockToUsbAudioDevice(record)` — API 28+ only |
| USB device routing detail | — | `record.preferredDevice = USB_DEVICE or USB_HEADSET type` |

---

### What CANNOT Break From These Fixes

Every area listed below is completely unaffected because the tablet fix is self-contained inside `TaalAudioCapture` — nothing else changed.

| Area | Why it is safe |
|---|---|
| All app fragments (RecordingFragment, PlayerFragment, etc.) | They call `TaalRecorder.start()` — internal TaalAudioCapture changes are invisible |
| taal-ui-kit (RecordingFragment, TaalRecorderActivity) | Also calls `TaalRecorder.start()` — benefits transparently with zero code changes |
| AudioFilterEngine / DSP chain | Completely untouched — issue was upstream (input routing), not downstream (processing) |
| WAV file format / WAV header writing | Unchanged — still 44100 Hz, Mono, 16-bit PCM |
| Waveform rendering (V7) | Receives the same FloatArray via `onProgressUpdate` — no change |
| BPM calculation (HeartBpmCalculator) | Same input data — no change |
| File save / rename / crop operations | No relation to AudioRecord setup |
| Playback (TaalPlayer) | Completely separate code path |
| Auth, patient DB, all UI screens | No relation |
| `SAMPLE_RATE = 44100` | Not changed — do not change this, it matches TAAL hardware |
| `AUDIO_FORMAT = PCM_16BIT` | Not changed |
| `CHANNEL_CONFIG = MONO` | Not changed |

---

### What COULD Theoretically Behave Differently

| Risk | Likelihood | Mitigation Already in Place |
|---|---|---|
| `UNPROCESSED` initializes but some OEM firmware still routes to built-in mic | Very low | `lockToUsbAudioDevice()` (fix 4) sets `preferredDevice` which prevents mid-session re-routing |
| `preferredDevice` targets a non-TAAL USB audio device (USB keyboard, hub with audio) | Edge case, rare | It's a **soft preference** not a hard lock — OS can override it. `TaalDisconnectedException` still guards against TAAL not being present at all |
| 4× buffer = slightly less frequent `onProgressUpdate` callbacks | Real, but minor | All downstream code (waveform, BPM) handles variable-size buffers. Only observable effect: callbacks arrive slightly less often — still many times per second |
| Disabling AGC removes a boost that was compensating for a weak TAAL signal on some specific device | Unlikely | TAAL has its own 0–30 dB pre-amp slider. User can increase pre-amp if needed |
| `UNPROCESSED` not available for USB audio on some device/firmware combo | Possible on unusual devices | Fallback chain catches this — drops to VOICE_RECOGNITION, then DEFAULT. Never crashes |

---

### Android 14 vs Android 16 — Why the Fix Was Needed Now

| Behaviour | Android 14 Tablet | Android 16 Tablet |
|---|---|---|
| `DEFAULT` routes to USB audio device | Often yes (worked by accident) | Often no — goes to built-in mic |
| AGC attached to recording session | Sometimes | More frequently yes, by default |
| NoiseSuppressor active | Partial | More frequently yes |
| USB audio SRC (OS resampling) | Rare | More common on mid-range SoCs |
| Audio scheduling latency | Lower | Higher |

---

### Impact on Ongoing Development

- **No app code changes required** — the fix is fully inside `taal-core`.
- **Re-build and re-export both AARs** after the fix: `./gradlew :taal-core:assembleRelease :taal-ui-kit:assembleRelease`
- **taal-ui-kit benefits automatically** since it depends on taal-core at source level.
- **Test on Android 14 tablet first** to confirm existing behavior is not regressed before testing on Android 16.
- **Logcat tag to verify fix in debug builds:** `TAAL_AUDIO` — look for `clientSource=9` (UNPROCESSED) and `device=11` (TYPE_USB_DEVICE) in active recording configurations.

---

*Document last updated: June 2026*
