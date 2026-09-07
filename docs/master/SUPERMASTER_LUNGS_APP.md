# SUPERMASTER_LUNGS_APP.md — `lungs-app` Module Reference

**Read this before touching any code in `lungs-app`.** Verified directly against source as of **2026-09-08**. Every claim below was checked against live files in `E:\AndroidProjects\TaalDemoApp\lungs-app` — not against the older `docs/notes/Lungs-App.md` or root `SUPERMASTER.md`, which were used only as a starting hypothesis. Discrepancies found between those docs and the actual source are called out explicitly in §11.

---

## 1. Module Overview

`lungs-app` is a standalone Android application for recording **16 lung auscultation points** across 4 anatomical regions using the MUSE Diagnostics TAAL USB digital stethoscope. It produces its own APK, completely independent of `:app`.

- **Package / namespace**: `com.musediagnostics.taal.lungs`
- **applicationId**: `com.musediagnostics.taal.lungs`
- App display name: "Lungs Auscultation" (`lungs-app/src/main/res/values/strings.xml:3`)
- Entry point: single `MainActivity` (`ui/MainActivity.kt`) hosting one `NavHostFragment` — no drawer, no toolbar, no auth. Home screen loads immediately.
- Own **Room database** (`lungs_database`, schema v2), separate from `app`'s `TaalDatabase`.
- Depends only on `:taal-core` (the pure audio engine) — **not** `:taal-ui-kit**. Confirmed in `lungs-app/build.gradle.kts:67`.
- Multi-session support: the same patient can be recorded across multiple visits, each a numbered "Session."
- Has a WAV noise-suppression ("denoiser") pipeline using a classical Wiener-filter DSP chain; a TFLite CRN neural model is bundled but not wired into the active pipeline.
- Google Drive upload is fully implemented in code but its UI entry point is hidden (`visibility="gone"`) — see §11.

## 2. Module Config

Source: `lungs-app/build.gradle.kts` (full file read).

| Field | Value |
|---|---|
| namespace | `com.musediagnostics.taal.lungs` |
| applicationId | `com.musediagnostics.taal.lungs` |
| compileSdk / targetSdk | 34 |
| minSdk | 24 |
| versionCode / versionName | 1 / "1.0" |
| viewBinding | enabled (`buildFeatures { viewBinding = true }`, line 41) |
| Java/Kotlin target | 1.8 |
| release build type | `isMinifyEnabled = false`; standard proguard files + `proguard-rules.pro` |
| signingConfig | none declared — release build is unsigned (uses default debug behavior unless configured at build time) |
| `aaptOptions { noCompress += "tflite" }` | line 44-46 — required so the bundled `.tflite` assets stay uncompressed for TFLite's mmap loader |
| `packaging.resources.excludes` | standard META-INF exclusions for the Google API client jars (lines 48-62) |

**Dependencies** (`build.gradle.kts:65-117`):
- `implementation(project(":taal-core"))` — the ONLY intra-repo module dependency. No `:taal-ui-kit`.
- AndroidX: core-ktx 1.12.0, appcompat 1.6.1, material 1.11.0, constraintlayout 2.1.4, recyclerview 1.3.2
- Navigation: navigation-fragment-ktx / navigation-ui-ktx 2.7.6
- Lifecycle/ViewModel: 2.7.0 (viewmodel-ktx, livedata-ktx, runtime-ktx)
- Room: room-runtime/room-ktx 2.6.1 + ksp room-compiler 2.6.1
- Coroutines: 1.7.3 (android + core)
- **MPAndroidChart** `com.github.PhilJay:MPAndroidChart:v3.1.0` — waveform charts (recording + player screens)
- Google Drive stack: `google-api-client-android:2.2.0`, `google-api-services-drive:v3-rev20230822-2.0.0`, `google-http-client-gson:1.43.3`, `google-auth-library-oauth2-http:1.23.0` (all exclude `org.apache.httpcomponents`)
- **TensorFlow Lite** `2.14.0` — for the (currently unused) CRN denoiser model
- **Apache Commons Math3** `3.6.1` — FFT backend for the STFT engine
- Test: junit 4.13.2, androidx.test.ext:junit 1.1.5, espresso-core 3.5.1

**Module registration**: `settings.gradle.kts:25` — `include(":lungs-app")`.

### AndroidManifest.xml (full file, `lungs-app/src/main/AndroidManifest.xml`)

- Permissions: `RECORD_AUDIO`, `INTERNET`, `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`)
- `<uses-feature android:name="android.hardware.usb.host" android:required="false" />`
- `<application android:name=".LungsApplication" android:allowBackup="false" ...>` — `LungsApplication.kt` (`lungs-app/src/main/java/com/musediagnostics/taal/lungs/LungsApplication.kt`) is a bare empty `Application` subclass (no custom init logic).
- `allowBackup="false"` — deliberately prevents Android Auto Backup from restoring the Room DB from Google Drive on reinstall (historical bug: phantom patients reappearing after uninstall/reinstall).
- Single activity: `.ui.MainActivity`, `exported="true"`, portrait-locked, `windowSoftInputMode="adjustResize"`.
- `FileProvider` at `${applicationId}.fileprovider`, `exported="false"`, `grantUriPermissions="true"`, paths resource `@xml/file_provider_paths` → `<files-path name="lungs_files" path="." />` (i.e. all of `filesDir` is shareable).
- App icon/roundIcon: `android:icon="@drawable/ic_lungs"` — a **vector drawable**, not the `mipmap/ic_launcher` adaptive icon. Note: `res/mipmap-anydpi-v26/ic_launcher.xml` (adaptive icon: teal background + `ic_launcher_foreground`) exists in the resource tree but is **not referenced by the manifest at all** — it is dead/unused since the manifest points directly at `@drawable/ic_lungs`.

## 3. Navigation & Screen Map

Nav graph file: `lungs-app/src/main/res/navigation/lungs_nav_graph.xml` (full file read, 141 lines).

**`app:startDestination="@id/homeFragment"`** (line 5) — confirmed live, not contested/volatile like some `app`-module nav graphs.

Every fragment declared in the graph is reachable — there are no orphaned/dead nav-graph destinations in `lungs-app` (unlike `app`'s six-parallel-waveform-screen situation).

```
homeFragment (START)
 ├─ action_home_to_patient_form ──────────► patientFormFragment (patientId default -1L = new)
 └─ action_home_to_saved_patients ───────► savedPatientsFragment

patientFormFragment
 └─ action_patient_form_to_placement ────► placementFragment

placementFragment
 └─ action_placement_to_recording ───────► lungsRecordingFragment

lungsRecordingFragment
 └─ action_lungs_recording_to_player ────► lungsPlayerFragment

savedPatientsFragment
 └─ action_saved_to_sessions_list ───────► patientSessionsListFragment

patientSessionsListFragment
 ├─ action_sessions_list_to_session ─────► patientSessionFragment
 ├─ action_sessions_list_to_placement ───► placementFragment   (Record Again → new session)
 └─ action_sessions_list_to_edit_patient ► patientFormFragment (edit mode)

patientSessionFragment
 ├─ action_session_to_placement ─────────► placementFragment   (Continue Recording)
 ├─ action_session_to_player ─────────────► lungsPlayerFragment (review mode)
 ├─ action_session_to_edit_patient ───────► patientFormFragment (edit mode)
 └─ action_session_to_denoiser ───────────► denoiserFragment

denoiserFragment
 └─ action_denoiser_to_player ───────────► lungsPlayerFragment (review mode)
```

### Nav argument types (exact, from XML)

| Fragment | Args |
|---|---|
| `patientFormFragment` | `patientId: long` default `-1L` |
| `placementFragment` | `patientId: long`, `patientSeqNum: int`, `sessionId: long`, `sessionNumber: int` (no defaults — must always be supplied) |
| `lungsRecordingFragment` | `patientId: long`, `patientSeqNum: int`, `sessionId: long`, `sessionNumber: int`, `pointCode: string` |
| `lungsPlayerFragment` | `filePath: string` default `""`, `rawFilePath: string` default `""`, `patientId: long`, `patientSeqNum: int`, `sessionId: long` default `-1L`, `sessionNumber: int` default `0`, `pointCode: string` default `""`, `isReviewMode: boolean` default `false` |
| `savedPatientsFragment` | none |
| `patientSessionsListFragment` | `patientId: long`, `patientSeqNum: int` |
| `patientSessionFragment` | `patientId: long`, `patientSeqNum: int`, `sessionId: long`, `sessionNumber: int` |
| `denoiserFragment` | `patientId: long`, `patientSeqNum: int` |

**Nav-arg gotcha (still applicable)**: `long`-typed default values must use the `L` suffix in the XML (`android:defaultValue="-1L"`) or Navigation throws at startup. All `long` defaults in this graph correctly use `L` (lines 28, 72).

## 4. Data Layer (Room DB, migrations)

DB class: `lungs-app/src/main/java/com/musediagnostics/taal/lungs/data/db/LungsDatabase.kt` (full file read, 99 lines).

```kotlin
@Database(
    entities = [LungPatientEntity::class, LungSessionEntity::class, LungRecordingEntity::class],
    version = 2,
    exportSchema = false
)
```

- DB file name: `"lungs_database"` (`LungsDatabase.kt:90`)
- Singleton accessor: `LungsDatabase.getInstance(context)` — double-checked-locking `INSTANCE`.
- `exportSchema = false` — no Room schema JSON is checked in; migrations are hand-written and unverifiable against exported schemas.

### Entities

**`LungPatientEntity`** (`data/db/entity/LungPatientEntity.kt`), table `lung_patients`:
```kotlin
data class LungPatientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sequenceNumber: Int,           // display: 01, 02, 03…
    val sex: String,                   // "Male" | "Female" | "Other" (free string, no enum/CHECK constraint)
    val age: Int,
    val chestCircumferenceCm: Float,
    val heightCm: Float,
    val weightKg: Float,
    val bmi: Float,                    // computed client-side = weightKg / (heightCm/100)^2
    val createdAt: Long = System.currentTimeMillis()
)
```

**`LungSessionEntity`** (`data/db/entity/LungSessionEntity.kt`), table `lung_sessions`, added in DB v2:
```kotlin
@Entity(
    tableName = "lung_sessions",
    foreignKeys = [ForeignKey(entity = LungPatientEntity::class, parentColumns = ["id"],
        childColumns = ["patientId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("patientId")]
)
data class LungSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val sessionNumber: Int,            // 1, 2, 3…
    val createdAt: Long = System.currentTimeMillis()
)
```

**`LungRecordingEntity`** (`data/db/entity/LungRecordingEntity.kt`), table `lung_recordings`, updated in v2 to add `sessionId`:
```kotlin
@Entity(
    tableName = "lung_recordings",
    foreignKeys = [
        ForeignKey(entity = LungPatientEntity::class, parentColumns = ["id"], childColumns = ["patientId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LungSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("patientId"), Index("sessionId")]
)
data class LungRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val sessionId: Long,
    val pointCode: String,             // e.g. "aar", "pslr"
    val filePath: String,              // absolute path to saved WAV
    val durationSeconds: Int,
    val createdAt: Long = System.currentTimeMillis()
)
```

### DAOs (`data/db/dao/`)

- **`LungPatientDao`**: `insert` (REPLACE conflict), `getAllPatients(): Flow<List>` (ordered by `sequenceNumber ASC`), `getById(id): LungPatientEntity?`, `getCount(): Int`, `update`, `deleteById`.
- **`LungSessionDao`**: `insert` (REPLACE conflict) `-> Long`, `getSessionsForPatient(patientId): Flow<List>` (ordered by `sessionNumber ASC`), `getById(id): LungSessionEntity?`, `getSessionCountForPatient(patientId): Int`.
- **`LungRecordingDao`**: `insert` (REPLACE conflict), `getRecordingsForPatient(patientId): Flow<List>`, `getRecordingsForSession(sessionId): Flow<List>`, `getRecordingCountForPatient`, `getRecordingCountForSession`, `getRecordingForPointInSession(sessionId, pointCode): LungRecordingEntity?` (unused by any caller currently — grep shows no call sites outside the DAO itself), `deleteById`.

### Repositories (`data/repository/`)

Thin 1:1 wrappers with no extra logic beyond `LungPatientRepository.getNextSequenceNumber() = dao.getCount() + 1` (`LungPatientRepository.kt:15`). Note: sequence numbers are derived from a live `COUNT(*)`, not a persisted counter — deleting a patient will cause the next new patient's `sequenceNumber` to collide with (or duplicate) an existing one, since `getCount()` decreases after a delete. There is currently no `deleteById` call site in the UI for patients (no "delete patient" feature exists), so this is a latent risk, not an active bug.

### Migration 1 → 2 (`LungsDatabase.kt:31-83`, `MIGRATION_1_2`)

Full table recreation is required because `ALTER TABLE ADD COLUMN` cannot add a foreign-key constraint in SQLite. Exact steps as coded:

1. `CREATE TABLE lung_sessions` with FK CASCADE to `lung_patients`, plus index on `patientId`.
2. `INSERT INTO lung_sessions (patientId, sessionNumber, createdAt) SELECT id, 1, createdAt FROM lung_patients` — every pre-existing patient gets a synthetic "Session 1" whose `createdAt` is copied from the patient row (not from any actual recording timestamp).
3. `CREATE TABLE lung_recordings_new` with **both** FKs (`patientId` → `lung_patients`, `sessionId` → `lung_sessions`), `sessionId` column declared `NOT NULL DEFAULT 0` in the CREATE statement (though the following INSERT always supplies a real value via COALESCE).
4. Copy old `lung_recordings` rows into `lung_recordings_new` via `LEFT JOIN lung_sessions s ON s.patientId = r.patientId AND s.sessionNumber = 1`, using `COALESCE(s.id, 0)` for `sessionId` — i.e. any recording whose patient somehow has no session-1 row would get `sessionId = 0`, which does not correspond to a real session (edge case, should not occur given step 2 runs first for every patient).
5. `DROP TABLE lung_recordings`; `ALTER TABLE lung_recordings_new RENAME TO lung_recordings`; recreate both indices (`patientId`, `sessionId`).

This migration is registered via `.addMigrations(MIGRATION_1_2)` on the `Room.databaseBuilder` (`LungsDatabase.kt:92`) — there is no fallback `.fallbackToDestructiveMigration()`, so an unmigrated v1 DB will migrate correctly, but any future schema change without a matching `Migration` object will crash at DB-open time.

## 5. Sessions Feature

A **session** represents one recording visit for a patient — a patient can be re-recorded (e.g. follow-up visits) without losing prior recordings.

- **Creation on new patient**: `PatientFormFragment.validateAndProceed()` (`ui/patient/PatientFormFragment.kt:215-249`) inserts the new `LungPatientEntity`, then immediately inserts `LungSessionEntity(patientId = newId, sessionNumber = 1)`, then navigates to `placementFragment` with both `sessionId` and `sessionNumber=1`. Uses `NavOptions.Builder().setPopUpTo(R.id.homeFragment, false).build()` so the form is removed from the back stack immediately (prevents duplicate-patient creation on Back + resubmit).
- **Creation via "Record Again"**: `PatientSessionsListFragment.kt:73-93` — queries `sessionRepo.getSessionCountForPatient(patientId)`, computes `newNumber = count + 1`, inserts the new `LungSessionEntity`, navigates straight to `placementFragment` (skipping the patient form — demographics are not re-entered per session).
- **Numbering**: sequential per patient, `count + 1`, computed live (not gap-safe if sessions were ever deleted — there is no session-delete feature, so this is theoretical only).
- **Session scoping**: `PlacementViewModel.init(sessionId, repo)` (`ui/placement/PlacementViewModel.kt:23-34`) is idempotent (`if (this.sessionId == sessionId) return`) and queries `repo.getRecordingsForSession(sessionId)` — each session independently starts all 16 points as "pending," regardless of what other sessions for the same patient have recorded.
- **Versioned file naming**: `lungs/{seqStr}/{sessionNumber}/{seqStr}.{sessionNumber}_{pointCode}.wav` where `seqStr = "%02d".format(patientSeqNum)`. Example: Patient 01, Session 2, point `aar` → `lungs/01/2/01.2_aar.wav`. Implemented in `LungsPlayerFragment.saveRecording()` (`ui/player/LungsPlayerFragment.kt:263-269`).
- **ZIP export naming**: `Patient_{seqStr}.{sessionNumber}.zip`, e.g. `Patient_01.2.zip` (`PatientSessionFragment.exportZip()`, line 354).
- **Session title display**: `"Patient %02d.%d".format(sequenceNumber, sessionNumber)` for the screen title, `"Patient %02d — Session %d"` for the card title (`PatientSessionFragment.displayPatient()`, lines 157-158).
- **Session list screen** (`PatientSessionsListFragment`) sits between `SavedPatientsFragment` and `PatientSessionFragment`; shows patient demographics card + a `RecyclerView` of session cards (`item_session.xml`: badge `S{n}`, title `Session {n}`, date, `{count} / 16` recorded — count loaded asynchronously per row via `sessionRepo`/`recordingRepo`).
- **Recording count denominator** on `SavedPatientsFragment`'s patient cards: `"$count / ${sessionCount * 16} recorded"` with `sessionCount.coerceAtLeast(1)` (`ui/home/SavedPatientsFragment.kt:113-114`) — i.e. the denominator scales with how many sessions exist for that patient (a patient with 2 sessions shows `x / 32`).

## 6. Recording Flow

Screen: `LungsRecordingFragment` + `LungsRecordingViewModel` (`ui/recording/`).

- **Filter**: hardcoded to `PreFilter.LUNGS` always — no filter chips/selection UI (`LungsRecordingFragment.kt:243`, `setPreFilter(PreFilter.LUNGS)`). `PreFilter.LUNGS` resolves in `taal-core` (`TaalRecorder.kt:325,330`) to `AudioFilterEngine.PresetFilter.LUNGS`, a **100–600 Hz bandpass** (`taal-core/.../dsp/AudioFilterEngine.kt:9`).
- **Two temp files per recording** (dual-file convention, same pattern as `app`): `recording_{timestamp}_raw.wav` and `recording_{timestamp}_filtered.wav`, both written under `context.filesDir` (`LungsRecordingFragment.kt:231-233`). `TaalRecorder` is configured with `setRawAudioFilePath`, `setFilteredAudioFilePath`, `setRecordingTime(300)` (5-minute hard SDK-level cap), `setPreAmplification`, `setPreFilter(PreFilter.LUNGS)`.
- **Auto-stop**: `MAX_RECORDING_SECONDS = 20` (`LungsRecordingFragment.kt:65`) — the fragment itself calls `stopRecording()` once `timeStamp >= 20` inside `onProgressUpdate`, independent of the SDK's 300s cap. This is a **client-side 20-second auto-stop**, not documented in the prior `docs/notes/Lungs-App.md`. (For comparison, `app`/`stemz-app` use a 15s auto-stop per recent commit history — `lungs-app`'s is 20s, confirmed distinct.)
- **Pre-amp**: 0–30 dB `Slider` (`fragment_lungs_recording.xml` — `valueFrom="0" valueTo="30"`), reset to 5 dB every `onResume()` (`LungsRecordingFragment.kt:440-442`), clamped in `LungsRecordingViewModel.setPreAmp()` via `.coerceIn(0, 30)`.
- **Waveform rendering during recording**: MPAndroidChart `LineChart`, 10-second scrolling window (`WINDOW_SECONDS = 10f`), `DOWNSAMPLE_STEP = 44` (~1002 pts/sec at 44100Hz input), page-based memory trimming (keeps current + previous page). Y-axis: **adaptive warmup/peak scaling** — for the first `WARMUP_MS = 2000` ms, tracks `warmupPeak`, then sets `peakAmplitude = (warmupPeak * HEADROOM=1.5).coerceIn(MIN_PEAK=0.02f, 1.0f)` and fixes `axisLeft.axisMinimum/axisMaximum` to `±peakAmplitude` for the rest of the recording (`updateWaveform()`, lines 355-404). This is the same "V7-style" warmup/peak pattern used elsewhere in the repo (`app`/`taal-ui-kit`'s original recording screens), not a simple fixed axis.
- **Audio monitor**: a separate `AudioTrack` (`MODE_STREAM`, mono 16-bit 44100Hz) plays back live audio for real-time monitoring while recording, fed PCM converted from the filter callback's `FloatArray` (`startAudioMonitor()`/`onProgressUpdate`, lines 262-271, 406-428).
- **USB connection indicator**: `deviceIcon` tinted via `TaalConnectionBroadcastReceiver` (teal `#128CB2` connected / dark grey `#333333` disconnected) plus an immediate `checkDeviceConnectionStatus()` on view creation and `onResume()`.
- **Stop → navigate**: immediately navigates to `lungsPlayerFragment` passing the filtered file as `filePath` and the raw file as `rawFilePath`, plus all patient/session/point args (`stopRecording()`, lines 313-335).
- **Placement guide image**: attempts to load `point_{pointCode}.png` from drawables via `resources.getIdentifier` (line 97) — **all 16 `point_*.png` files exist** in `res/drawable/` (verified — see §11, this corrects the prior doc which said only 4 of 16 existed).

### Player / Save / Discard (`LungsPlayerFragment`, `ui/player/`)

- **Full-file waveform load**: reads the entire WAV into memory (`file.readBytes()`), decimates to ≤3000 points (`sampleStep = maxOf(1, totalSamples / 3000)`), builds a single `LineDataSet` (`loadFullWaveform()`, lines 141-184). Fine for recordings up to a few minutes; not appropriate for very long files (though the 20s auto-stop and 300s SDK cap keep files short in practice).
- **Y-axis in the player is FIXED, not adaptive**: `chart.axisLeft.axisMinimum = -0.5f; axisMaximum = 0.5f` set once in `setupWaveformChart()` (lines 130-137) and never rescaled afterward, even after `loadFullWaveform()` populates real data. This is a real, verified difference from the recorder screen's adaptive warmup/peak scaling — **the prior docs' generic "simple peak-track" description does not apply uniformly**: recorder = adaptive warmup/peak; player = static ±0.5 range regardless of actual signal amplitude.
- **Double-filter protection**: `if (!File(filePath).name.contains("_filtered")) setPreFilter(PreFilter.LUNGS)` on the `TaalPlayer` (line 191-192) — skips re-filtering an already-filtered file.
- **Review mode** (`isReviewMode=true`, used when opening from `PatientSessionFragment`/`DenoiserFragment`): hides the Save/Discard bar and re-anchors the Play button to the screen bottom via a `ConstraintSet` mutation (`applyReviewMode()`, lines 100-107); no DB write occurs.
- **Save**: builds `lungs/{seqStr}/{sessionNumber}/` dir, renames (falls back to copy+delete on cross-filesystem rename failure) the filtered temp file to `{seqStr}.{sessionNumber}_{pointCode}.wav`, deletes the raw temp file, computes `durationSeconds` from file size (`(size-44)/2/44100`), inserts a `LungRecordingEntity`, then `popBackStack(R.id.placementFragment, false)` (`saveRecording()`, lines 248-314).
- **Discard**: confirmation dialog (`MaterialAlertDialogBuilder`) → deletes both temp files → `navigateUp()` (`confirmDiscard()`, lines 316-327). Pressing the top-bar **back button** also silently discards (comment at line 81-82: "Navigating back = discard (same as pressing discard without confirming)") — note this bypasses the confirmation dialog entirely; a stray tap on the back arrow deletes the recording with no confirmation.

## 7. Denoiser Feature

Package: `denoiser/` (4 files, all read in full). Entry point: `DenoiserFragment` (`ui/denoiser/DenoiserFragment.kt`), reached via `patientSessionFragment` → `action_session_to_denoiser` → `denoiserFragment`.

**Scope**: patient-scoped, not session-scoped — `DenoiserFragment` queries `recordingRepo.getRecordingsForPatient(patientId)` (line 97) across ALL sessions for that patient, then keys by `pointCode` via `associateBy { it.pointCode }` inside `buildDenoiserList()` (implicit via `recordingMap = recordings.associateBy { it.pointCode }`, line 113) — if the same point code was recorded in multiple sessions, only the last-inserted one is shown/denoised (Room `Flow` emission order).

**Pipeline** (`LungsDenoiser.denoiseWav()`, `denoiser/LungsDenoiser.kt`): **display/output-file effect — modifies the output WAV file on disk, not the original recording.** The original recording is never overwritten; a brand-new denoised file is written alongside it.

```
readWav(inputPath)              → FloatArray normalized -1..1, sourceSampleRate  (16-bit PCM parser, handles mono/stereo, keeps left channel only)
    ↓
resample to 8000 Hz             → integer-ratio: decimate(factor) via sourceSr % 8000 == 0
                                   else: resampleLinear() (linear interpolation)
    ↓
StftEngine.computeStft()        → real[129][nFrames], imag[129][nFrames]
    ↓
WienerDenoiser.denoise()        → enhanced real[129][nFrames], imag[129][nFrames]
    ↓
StftEngine.computeIstft()       → FloatArray time-domain, cropped to original length
    ↓
writeWav(outputPath, 8000 Hz)   → 16-bit PCM mono WAV
```

**Important fidelity note**: the denoised output WAV is written **at 8000 Hz**, downsampled from the original 44100 Hz — a real, permanent quality/fidelity reduction, though lung sounds are clinically <2000 Hz so this is by design.

### `StftEngine` constants (`denoiser/StftEngine.kt`)
| Constant | Value |
|---|---|
| `N_FFT` | 256 |
| `WIN_SIZE` | 200 (25ms @ 8kHz) |
| `HOP` | 80 (10ms @ 8kHz) |
| `N_BINS` | 129 (`N_FFT/2 + 1`) |

Hann window; FFT backend `org.apache.commons.math3.transform.FastFourierTransformer(DftNormalization.STANDARD)`; ISTFT uses overlap-add with sum-of-squared-window normalization, cropped back to `originalLength`.

### `WienerDenoiser` constants (`denoiser/WienerDenoiser.kt`)
| Constant | Value | Meaning |
|---|---|---|
| `ALPHA_S` | 0.9 | power smoothing (IIR) |
| `ALPHA_D` | 0.85 | noise update rate, signal absent |
| `L` | 125 | sliding-min window frames (~1.25s) |
| `DELTA` | 5.0 | signal-presence SNR threshold |
| `BREATH_CORRECTION` | 0.20 | scales IMCRA noise estimate down (prevents over-suppressing breath sounds) |
| `BETA` | 0.08 | global spectral gain floor |
| `WARMUP` | 20 | first 20 frames pass through with gain=1.0 |
| `RELEASE` | 0.2 | fast-release smoothing factor; attack is 0.9 below 600Hz, 0.7 above |

Per-band params `(upperHz, overEstimation, floor)`: `(300, 1.00, 0.20)`, `(600, 1.50, 0.12)`, `(1200, 2.00, 0.06)`, `(2000, 2.50, 0.00)`; below 100Hz or above 2000Hz → pass-through (gain untouched). Noise floor tracked via IMCRA (Improved Minima Controlled Recursive Averaging) using a monotonic deque per frequency bin for O(1) sliding-minimum (`imcra()`, lines 70-113). Gain: `SNR = max(noisyPow - effNoise, 0)/(effNoise+ε)`; `gain = SNR/(SNR+1)`, floored by `max(gain, BETA, bandFloor)`, then asymmetrically smoothed (slow attack, fast release) frame-to-frame.

### `CrnDenoiser` — bundled, NOT wired into the active pipeline (confirmed)

`denoiser/CrnDenoiser.kt` loads `assets/crn_float32.tflite` via a raw TFLite `Interpreter`, probing input chunk sizes from `{33,64,100,128,160,200,256,312,313,320,400,512}` until one succeeds (`resizeInput` → `allocateTensors` → dummy run). **Grep confirms `CrnDenoiser(` appears only at its own class declaration — it is never instantiated anywhere else in the module.** `LungsDenoiser.denoiseWav()` calls only `WienerDenoiser().denoise(real, imag)` (line 34) — the CRN path is fully dead code at runtime, despite both `crn_float16.tflite` and `crn_float32.tflite` being bundled in `src/main/assets/`.

**Denoised file path**: `lungs/{seqStr}/denoised/{seqStr}.{sessionNumber}_{pointCode}_denoised.wav`, where `sessionNumber` is **inferred from the original recording's parent directory name** (`File(recording.filePath).parentFile?.name?.toIntOrNull() ?: 1`, `DenoiserFragment.kt:117`) — not read from the DB (the `LungRecordingEntity` doesn't store `sessionNumber` directly, only `sessionId`).

**Row states** (`item_denoiser_row.xml` + `DenoiserAdapter.onBindViewHolder`):
- `isDone` (denoised file exists on disk): green status dot; Share + Download buttons visible; action button reads "▶ Play".
- `isProcessing` (denoise in flight): grey dot; Share/Download hidden; action button "Processing…", disabled.
- has original but not denoised: grey dot; action button "Denoise", enabled.
- no original recording for that point: grey dot; action button "Denoise", **disabled**.

**Share**: `FileProvider` URI, `Intent.ACTION_SEND`, MIME `audio/wav`.
**Download**: API 29+ via `MediaStore.Downloads` (`ContentResolver`, `IS_PENDING` flag pattern, no permission needed); API <29 via `Environment.DIRECTORY_DOWNLOADS` + runtime-requested `WRITE_EXTERNAL_STORAGE` (`ActivityResultContracts.RequestPermission()`).

## 8. Sharing / Export

Three distinct, independently-triggered export paths, all in `PatientSessionFragment.kt` unless noted:

1. **Per-recording share** (`shareRecording()`, lines 206-228): individual WAV → `FileProvider` URI → `Intent.ACTION_SEND`, `type = "audio/wav"`. Triggered from the share icon on each row in the session's recording list.
2. **ZIP export** (`exportZip()`, lines 331-395): requires all 16 points recorded for the session (`if (recordings.size < 16)` → Toast + abort, no partial ZIP). Builds `Patient_{seqStr}.{sessionNumber}.zip` under `context.filesDir` via `java.util.zip.ZipOutputStream`, one entry per WAV (flat, no subfolders inside the zip), then shares via `FileProvider` + `Intent.ACTION_SEND`, `type = "application/zip"`. Triggered by the `btnExportZip` top-bar icon (`ic_zip.xml`).
3. **Share Report** (`shareReport()`, lines 397-438): builds a **plain-text** summary (patient demographics + per-region recorded/not-recorded checklist with durations) and sends via `Intent.ACTION_SEND`, `type = "text/plain"` (no file attachment). **This button (`btnShareReport`) is wired in code (`btnShareReport.setOnClickListener { shareReport() }`, line 89) but is `android:visibility="gone"` in `fragment_patient_session.xml:99` and is never set visible anywhere in the codebase (confirmed by grep — no other reference to `btnShareReport`).** This is a real discrepancy vs. the prior `docs/notes/Lungs-App.md`, which listed "Share Report" as an active, reachable button in the user flow — it is currently **dead UI**, unreachable by any user gesture.
4. **Denoiser share/download** — see §7 above (separate FileProvider share + Downloads-folder save, scoped to denoised files only).
5. **Google Drive upload** (`startDriveUpload()`, lines 250-329, and `drive/DriveUploadHelper.kt`, full file read) — fully implemented (service-account auth from `assets/service_account.json`, creates `TaalLungs Auscultation/Patient_{seq}_{date}/` folder tree, uploads `report.txt` + all WAVs, optional auto-share to `SHARE_WITH_EMAIL = "cloudbotz2024@gmail.com"`), wired to `btnUploadDrive.setOnClickListener`, but **`btnUploadDrive` is `android:visibility="gone"` in the layout** (`fragment_patient_session.xml:87`) and never unhidden — also dead UI. (Known reason, per code comments: service accounts have no Drive storage quota of their own → uploads would fail with `storageQuotaExceeded`.)

**FileProvider config**: `<files-path name="lungs_files" path="." />` in `res/xml/file_provider_paths.xml` — grants sharing access to the entirety of `filesDir`, not a restricted subfolder.

## 9. Waveform/Graph Rendering

`lungs-app` does **not** reuse any waveform/graph code from `taal-core` or `taal-ui-kit` — both are pure DSP/UI-kit libraries with their own (different) chart implementations; `lungs-app` has its own independent MPAndroidChart-based rendering written directly inside `LungsRecordingFragment` and `LungsPlayerFragment`. (Consistent with `lungs-app` not depending on `:taal-ui-kit` at all — see §2.)

- **Library**: `com.github.mikephil.charting.charts.LineChart` (MPAndroidChart v3.1.0), same library version used elsewhere in the repo.
- **Recorder screen** (`LungsRecordingFragment.updateWaveform()`): live, sample-accurate, 10-second scrolling window; downsamples by taking every 44th sample (`DOWNSAMPLE_STEP`); adaptive Y-axis — 2-second warmup phase tracks peak amplitude, then fixes `axisLeft` to `±(warmupPeak × 1.5)` clamped to `[0.02, 1.0]` for the remainder of the recording. This is the same general pattern documented elsewhere in the repo as "V7 sample-accurate warmup/peak scaling" (see `app`'s original Recording/Player screens), independently reimplemented here rather than shared via a library.
- **Player screen** (`LungsPlayerFragment.loadFullWaveform()`): reads the whole WAV file once, decimates to ≤3000 points, renders as a single static `LineDataSet`; **Y-axis is a hardcoded, never-adjusted `±0.5` range** — this does NOT adapt to the actual recorded signal's peak amplitude. A quiet recording will look nearly flat; a recording that clips near ±1.0 will visually clip against the fixed axis. This is a real, verified discrepancy against the "simple peak-track" description in the task brief/prior docs — there is no peak-tracking at all in the player; it's a fixed range.
- Playback position is tracked by re-centering the visible X range (`centerViewTo`) as `onPlaybackProgress` fires, not by redrawing data.

## 10. UI Screen Inventory

All fragments live under `lungs-app/src/main/java/com/musediagnostics/taal/lungs/ui/`.

| Fragment | File | Purpose |
|---|---|---|
| `MainActivity` | `ui/MainActivity.kt` | Single activity; hosts `NavHostFragment` bound to `lungs_nav_graph.xml`; no other logic. |
| `HomeFragment` | `ui/home/HomeFragment.kt` | Landing screen: "Add New Patient" / "View Saved Recordings" buttons. |
| `SavedPatientsFragment` | `ui/home/SavedPatientsFragment.kt` | Lists all patients (Room `Flow`), shows recording count vs. `sessionCount × 16`; tap → sessions list. |
| `PatientFormFragment` | `ui/patient/PatientFormFragment.kt` | New-patient intake (sex/age/chest/height/weight, live BMI, inch→cm helper) or edit-existing-patient mode, keyed by `patientId == -1L`. |
| `PatientFormViewModel` | `ui/patient/PatientFormViewModel.kt` | Holds height/weight `MutableLiveData`, derives BMI via `MediatorLiveData`. |
| `PlacementFragment` | `ui/placement/PlacementFragment.kt` | 4-region (Ant.R/Ant.L/Post.R/Post.L) anatomy map with tap-to-record point overlay buttons; drag-to-calibrate code present but commented out. |
| `PlacementViewModel` | `ui/placement/PlacementViewModel.kt` | Session-scoped recorded/pending state; auto-advances region when current region is complete. |
| `LungsRecordingFragment` | `ui/recording/LungsRecordingFragment.kt` | Records one point; always `PreFilter.LUNGS`; live waveform + BPM-free (no HR calc, unlike heart apps); 20s auto-stop. |
| `LungsRecordingViewModel` | `ui/recording/LungsRecordingViewModel.kt` | UI state (IDLE/RECORDING/STOPPED), timer, pre-amp (0-30dB clamp), temp file paths. |
| `LungsPlayerFragment` | `ui/player/LungsPlayerFragment.kt` | Review/playback of one recording; Save (versioned rename + DB insert) or Discard; also used in "review mode" (no save bar) from session/denoiser screens. |
| `PatientSessionsListFragment` | `ui/session/PatientSessionsListFragment.kt` | Per-patient list of sessions + "Record Again" (new session) + "Edit Patient". |
| `PatientSessionFragment` | `ui/session/PatientSessionFragment.kt` | Single-session detail: 4 region headers + 16 point rows, Share/Delete per row, Share Report (dead UI), Export ZIP, Denoiser entry, Drive upload (dead UI), Continue Recording. |
| `DenoiserFragment` | `ui/denoiser/DenoiserFragment.kt` | Patient-scoped (not session-scoped) list of all 16 points; per-point Denoise/Play/Share/Download. |

## 11. Known Issues / Gotchas

Confirmed directly against source (this session, 2026-09-08):

1. **"Share Report" button is dead UI.** `btnShareReport` in `fragment_patient_session.xml:92-102` is `android:visibility="gone"` and nothing in the codebase ever sets it visible (grep-confirmed, only two references total: the layout declaration and the click-listener wiring). The prior `docs/notes/Lungs-App.md` (§6, §10) presented "Share Report" as a live, reachable feature — it is not currently reachable by any user gesture.
2. **"Upload to Drive" button is dead UI** (this one WAS correctly documented as hidden by the prior doc). `btnUploadDrive` is `visibility="gone"` in `fragment_patient_session.xml:80-90`; `DriveUploadHelper` is fully implemented and wired to the click listener but unreachable. Root cause per in-code comments: the bundled `assets/service_account.json` service account has no Drive storage quota of its own, so uploads would fail with `storageQuotaExceeded` (403) until switched to OAuth/Google Sign-In under a real account.
3. **Player waveform Y-axis is a fixed `±0.5` range, never adaptive** (`LungsPlayerFragment.kt:130-137`) — unlike the recorder screen's adaptive warmup/peak scaling. A recording that never approaches ±0.5 amplitude will render as a near-flat line with no rescaling.
4. **Denoiser is patient-scoped, not session-scoped.** `DenoiserFragment` queries `getRecordingsForPatient(patientId)` and keys by `pointCode` — if the same point was recorded in 2+ sessions, only the most-recently-emitted one from the `Flow` is shown/denoised; there is no session selector in the Denoiser screen.
5. **CRN neural denoiser is bundled but 100% dead code.** `crn_float16.tflite` + `crn_float32.tflite` ship in `assets/` and `CrnDenoiser.kt` is a complete, working TFLite wrapper, but it is never instantiated — `LungsDenoiser.denoiseWav()` hardcodes `WienerDenoiser()` only.
6. **Denoised output is 8kHz, a permanent downsample from the original 44.1kHz recording** — by design for lung-sound bandwidth (<2kHz), but a real fidelity loss if anyone expects the denoised file to match the original sample rate.
7. **Back button on the Player screen silently discards with no confirmation.** `binding.backButton.setOnClickListener { findNavController().navigateUp() }` (`LungsPlayerFragment.kt:80-83`) — the code comment explicitly says "Navigating back = discard (same as pressing discard without confirming)". Only the dedicated Discard button shows a confirmation dialog; the back arrow does not.
8. **Drag-to-calibrate point-placement code exists but is fully commented out** (`PlacementFragment.addPointButton()`, `ACTION_MOVE` block, lines 239-253) — tap-to-record is the only active interaction. To re-enable, uncomment that block plus the `hasDragged` handling in `ACTION_UP`.
9. **`mipmap-anydpi-v26/ic_launcher.xml` (adaptive icon) is orphaned/unused.** The manifest points `android:icon`/`android:roundIcon` directly at `@drawable/ic_lungs` (a vector), not `@mipmap/ic_launcher` — the adaptive-icon XML and its `ic_launcher_foreground.xml` exist in the resource tree but are never referenced.
10. **`getRecordingForPointInSession()` DAO method is unused** — declared in `LungRecordingDao` and wrapped in `LungRecordingRepository`, but no call site exists anywhere in `ui/`.
11. **Patient `sequenceNumber` is derived from a live `COUNT(*)`**, not a persisted auto-increment counter (`LungPatientRepository.getNextSequenceNumber() = dao.getCount() + 1`). There is no "delete patient" feature in the current UI, so this can't currently produce a collision, but it is a latent footgun if patient deletion is ever added.
12. **Point coordinate data has changed from what the prior doc recorded.** The prior `docs/notes/Lungs-App.md` (§9) listed a specific `xFraction`/`yFraction` table for all 16 `LungPoint`s that **no longer matches** `domain/LungPoint.kt` (e.g. `aar` was `0.62/0.31`, is now `0.40/0.32`; `aal` was `0.50/0.28`, is now `0.61/0.31`). The current file also documents a "radiological convention" (R label on viewer's left, L on viewer's right) directly in its KDoc — this convention was not previously documented. Always read `domain/LungPoint.kt` directly rather than trusting a cached coordinate table.
13. **All 16 per-point placement guide images and all 4 region placeholder images now exist** (`point_aar.png` … `point_pill.png`, `placeholder_anterior_right.png` etc., all in `res/drawable/`) — this corrects the prior doc's "Anterior Left done, others still needed" status, which is now stale/resolved.
14. **`teal_dark` color value drifted**: prior doc recorded `#1A9999`; live `res/values/colors.xml:5` has `teal_dark = #1FA3A3`.
15. **No signingConfig block** in `build.gradle.kts` — release builds are not configured with a real signing config in this module (contrast with `stemz-app`, which recently had a real release signingConfig wired — see repo-root git log). Not necessarily a bug for an internal/dev app, but worth knowing before attempting a release build.
16. **20-second client-side recording auto-stop** (`LungsRecordingFragment.kt:65`, `MAX_RECORDING_SECONDS = 20`) was not mentioned at all in the prior `docs/notes/Lungs-App.md`. This is distinct from the SDK-level `setRecordingTime(300)` (5-minute hard cap) — the fragment stops recording itself well before the SDK would.
17. **Auto-generated adapter coroutine scopes are not lifecycle-bound**: `SavedPatientsFragment.PatientAdapter.VH` and `PatientSessionsListFragment.SessionListAdapter.VH` each spin up their own `CoroutineScope(Dispatchers.Main + SupervisorJob())` per ViewHolder for async count-loading, never cancelled on view recycle. Acceptable for small/read-only datasets per existing code comments, but is a real (minor) leak pattern if patient/session lists ever grow large.

## 12. Changelog

| Date | Change | Notes |
|---|---|---|
| 2026-09-08 | Initial SUPERMASTER_LUNGS_APP.md created | Full source audit of every `.kt`/`.xml` file in `lungs-app`; cross-checked against `docs/notes/Lungs-App.md` (2026-05-28) and root `SUPERMASTER.md` (2026-08-28). Found and documented 17 discrepancies/gotchas not previously captured accurately, most notably: "Share Report" button is dead UI (not just "Upload to Drive"), player waveform Y-axis is fixed not adaptive, a previously-undocumented 20s client-side recording auto-stop, all 16 point placement images now present (previously only 4/16), and `LungPoint` coordinate data has changed from the last recorded snapshot. |
