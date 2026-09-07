# SUPERMASTER_APP.md — `app` Module Reference

**Read this before touching any code in `app`.** Verified directly against source as of **2026-09-08**. Every claim below was checked against the live files in `E:\AndroidProjects\TaalDemoApp\app` (not against other docs) unless explicitly marked otherwise. Where this contradicts the root `SUPERMASTER.md` or auto-memory, this file is the more current one — but re-verify anything load-bearing yourself, especially `nav_graph.xml`'s `startDestination`, which has changed at least four times in this repo's history and could easily have moved again since this was written.

---

## 1. Module Overview

- **Namespace**: `com.musediagnostics.taal.app`
- **applicationId**: `com.musediagnostics.taal`
- **minSdk**: 26, **targetSdk**: 34, **compileSdk**: 34
- **versionCode/versionName**: 1 / "1.1"

(`app/build.gradle.kts:8-16`)

`app` is MUSE Diagnostics' main clinical TAAL digital-stethoscope app: single-`Activity` + Jetpack Navigation + MVVM + Room. It records heart/lung/bowel/pregnancy/full-body/custom-bandpass PCG audio over USB from the TAAL hardware, renders a live scrolling waveform, computes heart-rate (BPM) via autocorrelation, lets the clinician attach a patient record, saves recordings to internal storage (+ best-effort copy to device Music folder), shares the raw WAV losslessly, and optionally runs an offline heart-sound segmentation model on saved recordings to produce a cycle-by-cycle report. It shares almost all of its source tree with `stemz-app` (a rebrand fork — not covered by this doc; see that module's own reference).

The single most important fact about this module: **it currently contains six independently-evolved waveform/graph screen families**, only one of which is reachable from the app's actual live navigation entry point. See §3 and §4.

---

## 2. Module Config

### `app/build.gradle.kts` (full contents summarized, verified line-by-line)
- Plugins: `com.android.application`, `org.jetbrains.kotlin.android`, `com.google.devtools.ksp` (`build.gradle.kts:1-5`)
- `buildTypes.release`: `isMinifyEnabled = false`, default + `proguard-rules.pro` (`:21-29`). **No `signingConfig` block at all** — unlike `stemz-app`, which per recent commit history now wires a real release `signingConfig`; `app` has none, so a release build here is unsigned.
- `compileOptions`/`kotlinOptions`: Java 1.8 / jvmTarget 1.8 (`:31-38`)
- `buildFeatures.viewBinding = true` (`:40-42`) — every fragment in this module uses generated `Fragment*Binding` classes, `_binding`/`binding` nullable pattern, cleared in `onDestroyView`.
- Dependencies (`:45-87`): `project(":taal-core")`, `project(":taal-segmentation")` (note: **not** `taal-ui-kit` — `app` does not depend on the SDK's prebuilt UI layer, only the pure audio engine + the segmentation wrapper), AndroidX core/appcompat/material/constraintlayout, Navigation 2.7.6, Lifecycle/ViewModel/LiveData 2.7.0, Room 2.6.1 (+ KSP compiler), Coroutines 1.7.3, MPAndroidChart `v3.1.0` (JitPack), ViewPager2 1.0.0, Biometric 1.1.0.
- `proguard-rules.pro`: keeps `com.musediagnostics.taal.app.data.db.entity.**`, `com.musediagnostics.taal.**`, and `com.github.mikephil.charting.**` wholesale.

### `AndroidManifest.xml` (`app/src/main/AndroidManifest.xml`, full contents — 62 lines)
- Permissions: `RECORD_AUDIO`, `USB_PERMISSION`, `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"` only — API 29+ uses MediaStore, no runtime permission). `uses-feature android.hardware.usb.host` (`required="false"`).
- `application android:name=".TaalApplication"` — holds the lazily-created `TaalDatabase` singleton and a static `instance` (`TaalApplication.kt:6-21`).
- A `FileProvider` at authority `${applicationId}.fileprovider`, `exported="false"`, `grantUriPermissions="true"`, paths from `@xml/file_paths` (manifest `:27-35`). `res/xml/file_paths.xml` grants exactly one directory: `<files-path name="saved_recordings" path="saved/" />` — i.e. only `filesDir/saved/` is ever exposed via content:// URI, nothing else.
- One `Activity`: `.ui.MainActivity`, `exported="true"`, `screenOrientation="portrait"`, `LAUNCHER`.
- A **second intent-filter on the same `MainActivity`** for `ACTION_VIEW` with two deep-link hosts: `taalapp://calibrated` and `taalapp://pcgscale` (manifest `:47-57`). The in-manifest comment only mentions the calibrated screens ("Debug entry point for the calibrated ECG-paper screens... not reachable from any always-visible production button") — it is now stale/incomplete since `taalapp://pcgscale` was added later and, as of this snapshot, `pcgscale` is actually the **default launch destination anyway** (see §3), making that specific deep link redundant for normal launches (still useful for jumping straight past whatever the current `startDestination` happens to be).
- No other manifest-declared providers, receivers, or deep links.

---

## 3. Navigation & Screen Map

**Live navigation graph**: `app/src/main/res/navigation/nav_graph.xml`.

**Confirmed live `startDestination` right now**: `@id/pcgScaleRecordingFragment` (`nav_graph.xml:6`), with an inline comment: *"TEMP: flipped to pcgScaleRecordingFragment for on-device testing (2026-08-25). Revert to @id/recordingFragment before shipping."* — this comment has been sitting there for two weeks-plus as of this writing; do not assume it will be reverted by the time you read this, **grep line 6 yourself**.

### 3.1 What a user can actually reach from a cold launch

This was determined by (a) reading every `<action>` edge in `nav_graph.xml` and (b) grepping every `findNavController().navigate(...)` / `R.id.<destination>` call and every `openDrawer()` call in `app/src/main/java` to see which graph edges are ever actually exercised vs. declared-but-dead. Both checks agree on the following reachable set:

```
pcgScaleRecordingFragment (START)
 ├─ [folder icon] → savedRecordingsFragment
 │    └─ [tap a saved item] → pcgScaleReviewFragment
 │         └─ [EQ button] → equalizerFragment
 ├─ [record → stop] → pcgScalePlayerFragment  (always isNewRecording=true from this path)
 │    ├─ [EQ button] → equalizerFragment
 │    └─ [Save button] → saveRecordingFragment (popUpTo = pcgScaleRecordingFragment)
 │         └─ (auto, after save) → savedRecordingsFragment
```

That's it. **Every other fragment in this module — the entire auth chain, the production `RecordingFragment`/`PlayerFragment`, `RecordingLibraryFragment`, `AddPatientFragment`, `ProfileFragment`, all Info screens, `ChangePinFragment`, `EditRecordingFragment`, `CropRecordingFragment`, `NewRecordingFragment`, `TestRecordingFragment`/`TestPlayerFragment`, `SharedRecordingsFragment`, `LoadingFragment`, the Calibrated screens, the FullTimeOn screens, and — critically — the entire PCG segmentation feature — is unreachable from a cold launch by tapping through the UI**, given the current `startDestination` and current code. Details and the reasoning chain below; this is the single most surprising/load-bearing finding in this document, verify it yourself before relying on it.

**Why the drawer (and everything behind it) is unreachable:**
- `MainActivity`'s navigation drawer (`ui/MainActivity.kt:122-163`) is the only place in the whole module that calls `navController.navigate()` on `R.id.profileFragment`, `R.id.recordingLibraryFragment`, `R.id.changePinFragment`, `R.id.aboutUsFragment`, `R.id.faqFragment`, `R.id.privacyPolicyFragment`, `R.id.subscriptionFragment`, `R.id.userManualFragment`, plus a sign-out footer button that navigates to `R.id.loginFragment` (`:114-119`).
- The drawer is opened by `MainActivity.openDrawer()` (`:166-168`). A grep of the entire `app/src/main/java` tree for callers of `openDrawer()` finds **exactly one call site**: `AddPatientFragment.kt:125` (`binding.menuButton.setOnClickListener { (activity as? MainActivity)?.openDrawer() }`). No other fragment's layout or code exposes a hamburger/menu button. `PcgScaleRecordingFragment`, `PcgScalePlayerFragment`, `SavedRecordingsFragment`, and `PcgScaleReviewFragment` have no such button.
- So the drawer is reachable **only** by first reaching `AddPatientFragment`. `AddPatientFragment` has six inbound nav actions in the graph (`action_recording_to_addPatient`, `action_player_to_addPatient`, `action_testPlayer_to_addPatient`, `action_calibratedPlayer_to_addPatient`, `action_fullTimeOnPlayer_to_addPatient`, `action_pcgScalePlayer_to_addPatient`) — but five of those six source fragments are themselves unreachable (see below), and the sixth, `PcgScalePlayerFragment`, only calls `action_pcgScalePlayer_to_addPatient` from inside `showSaveDiscardDialog()`'s SAVE branch (`PcgScalePlayerFragment.kt:554-568`), which is only wired to the Save/Discard buttons' **`else` branch — the one taken when `isNewRecording == false`** (`:159-183`). The only nav-graph edge that ever opens `PcgScalePlayerFragment` is `PcgScaleRecordingFragment.stopRecording()`, which always passes `isNewRecording = true` (`PcgScaleRecordingFragment.kt:768`). No other code path opens `PcgScalePlayerFragment` with `isNewRecording = false`, so that `else` branch — and therefore `AddPatientFragment`, and therefore the drawer, and therefore everything gated behind it — currently has **no live trigger**, despite every individual edge in the chain being "wired" in the graph.
- Consequence: the entire auth chain (`SplashFragment` → `SignInFragment`/`PinLoginFragment` → `LoginFragment`/`OtpFragment`/`SignUpFragment`/`SetPinFragment`/`PinConfirmedFragment`/`FingerprintSetupFragment`/`FingerprintConfirmFragment`) is unreachable too — nothing navigates to `splashFragment` (it's not the `startDestination` and has no other inbound edge), and the only other entry point into that chain (the drawer's sign-out button → `loginFragment`) is itself gated behind the dead chain above.
- Production `RecordingFragment`/`PlayerFragment` are only reached from that same unreachable auth chain (every auth screen's "success" action targets `recordingFragment` with `popUpTo="@id/nav_graph"` — see `nav_graph.xml:108-179`) — so they, and the **PCG segmentation feature** (`SegmentationReportFragment`, only reachable from production `PlayerFragment`'s "Analyze Heart Sounds" button, `PlayerFragment.kt:72-79`) are also currently unreachable via any live UI path, despite `SegmentationFeature.ENABLED = true`.
- `RecordingLibraryFragment` (patient-scoped recording library, separate from `SavedRecordingsFragment`) is drawer-only (`nav_library` menu item), so it's unreachable for the same reason. `EditRecordingFragment` and `CropRecordingFragment` are only reachable from `RecordingLibraryFragment`/production `PlayerFragment` respectively, so also unreachable.

**Confirmed pure orphans (declared in `nav_graph.xml`, zero inbound `<action>` AND zero direct `navigate()` call anywhere in the module):**
- `testRecordingFragment` / `testPlayerFragment` — `TestRecordingFragment.kt:3` literally says `//THIS IS NOT IN USE (ONLY TESTING BY KUNAL)`.
- `sharedRecordingsFragment` (`SharedRecordingsFragment.kt`) — grep for the class name anywhere outside its own file returns nothing.
- `loadingFragment` (`LoadingFragment.kt`) — same, zero references.
- `newRecordingFragment` ("Experimental new recording UI — for testing only" per the nav_graph comment, `nav_graph.xml:215`) — only inbound edge is from the (currently unreachable) production `recordingFragment`.
- `calibratedRecordingFragment` and `fullTimeOnRecordingFragment` — no inbound `<action>` from any reachable screen and not the `startDestination`. `calibratedRecordingFragment` **is** reachable via the `taalapp://calibrated` ADB deep link (manifest-registered, see §2); `fullTimeOnRecordingFragment` has no deep link at all and is reachable **only** via IDE/manual navigation-graph tooling, not any real user or `adb` path.

**Caveat on all of the above**: this is a static analysis of every `navigate()`/action edge/`openDrawer()` call site as of this snapshot — it is not a runtime-tested claim. If you're about to fix a bug in any of the "unreachable" screens, first re-run these greps yourself (`openDrawer`, the destination's `R.id.` name, and its class name) in case something changed since.

### 3.2 Full destination list (all 40 fragments declared in `nav_graph.xml`)
See §9 for the full table with one-line purpose + reachability. Argument types/defaults for every destination are documented inline in `nav_graph.xml` itself (arguments use `defaultValue`/`argType` consistently — `filePath`, `rawFilePath`, `isNewRecording`, `filterName`, `preAmpDb`, `recordingId`, `popUpToDestination` are the recurring argument names shared across the four recorder/player families).

---

## 4. Waveform/Graph Screen Families

Six independently-evolved implementations exist. None share code beyond copy-paste-and-fork (each fork's own doc comments say so explicitly).

### 4.1 Production Recording/Player — `ui/recording/RecordingFragment.kt` + `ui/player/PlayerFragment.kt`
- **Live?** No (see §3.1) — reachable only via the dead auth chain.
- MPAndroidChart `LineChart`. Fixed 10-second scrolling window (`WINDOW_SECONDS = 10f`, `RecordingFragment.kt:79`). Downsample step 44 (~1002 pts/sec) (`:83`).
- Y-axis: **adaptive warmup/peak** scheme — accumulate the signal's true peak over the first `WARMUP_MS = 2000`ms (`:86`), then lock the axis to `peakAmplitude × HEADROOM(1.5)` (`:88-89`) so the waveform fills ~65% of height; `MIN_PEAK = 0.02` floor prevents over-zoom on near-silence (`:92`). Once locked, the axis does not move again during that recording — this is the scheme the Calibrated fork's Fix A explicitly reversed for being "unstable."
- This is the family memory/root-doc calls "V7 sample-accurate downsampling."
- `PlayerFragment` has the **only** live wiring to the PCG segmentation feature (`action_player_to_segmentationReport`, gated by `SegmentationFeature.ENABLED`) and the only wiring to `EditRecordingFragment`/`CropRecordingFragment`/`EqualizerFragment` from a saved-recording context.

### 4.2 Test Recording/Player — `ui/recording/TestRecordingFragment.kt` + `ui/player/TestPlayerFragment.kt`
- **Live?** No — pure orphan (§3.1), and the file itself is annotated `//THIS IS NOT IN USE (ONLY TESTING BY KUNAL)` (`TestRecordingFragment.kt:3`).
- Structurally a near-copy of production `RecordingFragment` (same imports, same `RecordingViewModel`, MPAndroidChart-based).

### 4.3 `EcgPaperView` / `MmScale` — `app/.../ecg/EcgPaperView.kt`, `ecg/MmScale.kt`
- **Live?** No — not instantiated from any reachable layout as far as the fragment graph goes; this is the first-built mm-accurate custom `View` (predates the Calibrated fork).
- `MmScale`: grid geometry (small/large square size in px) depends **only** on `pxPerMmX`/`pxPerMmY`; `paperSpeed` (`PaperSpeed` enum: 12.5/25/50 mm/s) and `gain` affect only the seconds↔px / mV↔px conversions, never grid size (`MmScale.kt:31-35`, `:49-66`). `EcgPaperView` renders the grid + calibration pulse from this scale (`EcgPaperView.kt:24`, `:280-296`).

### 4.4 Calibrated screens — `ecg/calibrated/*`, `ui/calibrated/*`
- **Live?** Only via ADB deep link `taalapp://calibrated` (manifest + `nav_graph.xml:482`) — no in-app button reaches it.
- `CalibratedRecordingFragment` is documented as a hand-ported, **protected, not modified** replica of production `RecordingFragment`, differing only in how the graph is drawn (`CalibratedRecordingFragment.kt:45-51`).
- `CalibratedMmScale`/`CalibratedEcgPaperView`/`CalibratedWaveformView`: same mm/paper-speed separation as `MmScale` (`CalibratedMmScale.kt:30-34`), but paper speed defaults to `SPEED_25` (25 mm/s, raised from 12.5 mm/s per the "Fix B" 2026-08-18 commit noted in the root SUPERMASTER changelog).
- Y-axis: **Fix A** deliberately reverses the adaptive warmup/peak scheme back to a **single fixed full-scale**, `FIXED_FULL_SCALE = 0.10f`, set once at chart setup and never touched again for the session (`CalibratedRecordingFragment.kt:127`, doc comment `:53-60` explicitly compares this to "Kardia's own ECG display... fixed 10mm/mV, never rescales"). Per-device calibration is available via pinch + Apply (`GraphCalibration`), not automatic.
- Downsample bucket derived from `sampleRate / (paperSpeedMmPerSecond × pxPerMmX × 2)` — roughly one min/max bucket pair per 2 horizontal pixels (`CalibratedWaveformView.kt:146-150`).
- `DpiCalibrationFragment` + `DpiCalibration.kt` handle physical-ruler DPI calibration, reachable from `CalibratedRecordingFragment`'s own button.

### 4.5 FullTimeOn screens — `ui/fulltimeon/*`
- **Live?** No — no inbound nav action, no deep link, not the `startDestination`.
- The in-file doc comment (`FullTimeOnRecordingFragment.kt:47-51`) says it was cloned from Calibrated "user request, 2026-08-20... behavior is currently identical to that fragment." **This is now stale**: the live code has since diverged — it has its own `FullTimeOnRecordingUiState` including an `IDLE`/`PREVIEW` split not present in Calibrated (`:623-628`), an always-on live preview, a warmup-peak-based Y-axis (`warmupPeak`/`warmupDone` fields, `:1110-1115` — i.e. it has drifted back toward the production adaptive scheme, not the Calibrated fixed one), and a speaker/monitor toggle (`:71-73`). Treat the "identical to Calibrated" claim in its own header comment as outdated; if you need FullTimeOn's exact current Y-axis algorithm, read `updateWaveform()` in `FullTimeOnRecordingFragment.kt` directly rather than trusting that comment or this summary.

### 4.6 PcgScale screens — `ecg/pcgscale/*`, `ui/pcgscale/*` — **THE LIVE FAMILY**
- **Live?** Yes — this is the `startDestination` (`pcgScaleRecordingFragment`) and the only fully-reachable recorder/player pair in the module.
- Explicitly documented as a fork of the Calibrated screens (`PcgScaleRecordingFragment.kt:46-64`), with two deliberate differences:
  1. **Time grid, not mm grid**: `PcgTimeScale` (`ecg/pcgscale/PcgTimeScale.kt`) fixes 1 large box = 1 second, 1 small box = 0.2s (`SECONDS_PER_LARGE_SQUARE=1f`, `SMALL_SQUARES_PER_LARGE_SQUARE=5`, `:32-34`), default visible window 4 seconds (`DEFAULT_VISIBLE_SECONDS=4f`, `:40`). `pixelsPerSecond` is derived once from plot width and frozen for the session — **zoom is impossible by construction**: `PcgScaleWaveformView` locks both `setVisibleXRangeMaximum` and `Minimum` to the same value (`PcgScaleWaveformView.kt:146-148`), unlike the Calibrated player which deliberately leaves pinch-zoom alive.
  2. **Y-axis**: RMS-derived 60%-fill auto-scale via `PcgAmplitudeScale` (`ecg/pcgscale/PcgAmplitudeScale.kt`), replacing both the Calibrated fixed-scale scheme and the production warmup/peak lock. Algorithm ("ENERGY PICKS, AMPLITUDE CALIBRATES, MEDIAN + Kth-LARGEST PROTECT", full doc at `PcgAmplitudeScale.kt:7-53`):
     - 50ms hops (`RMS_HOP_SECONDS`); each hop's RMS *and* peak `|sample|` are both tracked.
     - Per 5s window (`PEAK_WINDOW_SECONDS`), the hop with the **3rd-largest RMS** (`OUTLIER_REJECTION_K=3`) is the window's "calibrating hop" — its **peak amplitude** (not its RMS) becomes that window's value, so a single transient/thud can't dominate.
     - Across windows, the axis target is the **median** of these per-window peak values, divided by `TARGET_FILL_FRACTION = 0.60f`, clamped to `[MIN_FULL_SCALE=0.005f, MAX_FULL_SCALE=1.0f]`.
     - Live display eases toward the target at `SMOOTHING_PER_UPDATE = 0.15f` of the remaining gap per UI update, so the axis glides rather than snaps.
     - `DEFAULT_INITIAL_FULL_SCALE = 0.10f` is the neutral pre-first-window value (matches Calibrated's `FIXED_FULL_SCALE`).
     - Player/review use `computeFullScaleForFile()` — same algorithm run once over the whole decoded file.
  3. A **display-only** conditioning chain, `PcgLiveDisplayFilter`/`PcgDisplayFilter` (click/USB-glitch removal + causal/zero-phase 20–500 Hz band-pass + 50/100/150 Hz mains-hum notches + a transient-protected spectral gate), runs on the data fed to both the trace and the RMS scaler — **never** on the recorded WAV bytes themselves (`PcgScaleRecordingFragment.kt:97-102`; see `docs/notes/PCG_DISPLAY_FILTER_2026-09-03.md`).
  4. **Sample-rate self-correction**: the fragment assumes 44100 Hz until the recorder's first callback reports the real rate (`onSampleRateReported`, `:786-798`) — added because Samsung USB-audio stacks commonly report 48000 Hz, and a hardcoded 44100 assumption there makes "1 second" boxes span ~1.09s of real signal (documented regression, `:104-110`).
- `PcgScaleRecordingViewModel`/`PcgScaleRecordingUiState` are a byte-for-byte state-shape copy of `CalibratedRecordingViewModel` "so a future 'swap the fragment class in nav_graph.xml' promotion is a one-line change" (`PcgScaleRecordingViewModel.kt:10-17`).
- `PcgScaleReviewFragment` (reached from `SavedRecordingsFragment`) is a read-only variant with **no** save/discard/`isNewRecording` branch at all (confirmed by grep — the class has no such logic), unlike `PcgScalePlayerFragment`.
- **Important internal inconsistency**: `PcgScalePlayerFragment` (used only for brand-new recordings, see §3.1) contains a large amount of `isNewRecording == false` branch code (`showSaveDiscardDialog`, `showDiscardConfirmation`'s "else" twin, the route to `AddPatientFragment`) that, per the reachability analysis in §3.1, currently has no live caller — it looks like functioning code in review but is presently dead weight inside an otherwise-live file.

---

## 5. Data Layer

`TaalDatabase` — `app/src/main/java/com/musediagnostics/taal/app/data/db/TaalDatabase.kt`
- `@Database(entities = [PatientEntity::class, RecordingEntity::class], version = 1, exportSchema = false)` (`:12-16`)
- `.fallbackToDestructiveMigration()` (`:33`) — **any future schema change with no explicit migration silently wipes all patient/recording rows on upgrade.** Not a bug today (schema hasn't changed since v1), but a live landmine for the next entity edit.
- Singleton via `TaalApplication.database` (lazy) / `TaalDatabase.getInstance(context)`.

**`PatientEntity`** (`data/db/entity/PatientEntity.kt`) — table `patients`: `id` (PK, autoGen), `fullName`, `patientId=""`, `phone=""`, `email=""`, `dateOfBirth=""`, `biologicalSex=""`, `conditions=""`, `createdAt=now`.

**`RecordingEntity`** (`data/db/entity/RecordingEntity.kt`) — table `recordings`, FK `patientId → patients.id` `ON DELETE SET NULL`, indexed on `patientId`: `id` (PK, autoGen), `patientId: Long?`, `filePath`, `fileName=""`, `filterType="HEART"`, `durationSeconds=0`, `bpm=0`, `preAmplification: Float=0f`, `isEmergency=false`, `notes=""`, `createdAt=now`.

**`RecordingWithPatient`** (`data/db/entity/RecordingWithPatient.kt`) — join projection used by the two `*WithPatients` DAO queries (adds `patientName`, `patientIdentifier`).

**DAOs**: `PatientDao` (insert/update/delete, `getAllPatients` Flow, `getPatientById`, `searchPatients` LIKE-query Flow, `getPatientCount`, `getPatientsWithRecordings` inner-join Flow) — `data/db/dao/PatientDao.kt`. `RecordingDao` (insert/update/delete, `getAllRecordings` Flow, `getRecordingById`, `getRecordingsForPatient`, `getMostRecentRecording`, `getEmergencyRecordings` Flow, `renameRecording` raw UPDATE, `getRecordingsWithPatients`/`searchRecordingsWithPatients` LEFT-JOIN Flows) — `data/db/dao/RecordingDao.kt`.

**Repositories** (`data/repository/PatientRepository.kt`, `RecordingRepository.kt`) — thin pass-through wrappers over the DAOs, no extra logic.

Note: `lungs-app` has its own, separately-versioned (`v2`) database — not shared with `app`, per its own module docs (not verified in this pass — out of scope).

---

## 6. Recording Flow

Described for the **live** family (PcgScale); the production/Calibrated/FullTimeOn families follow the same dual-file shape with different file-path prefixes and different downstream screens.

1. **Start**: `PcgScaleRecordingFragment.startRecording()` (`:531-733`) builds two temp file paths directly under `filesDir` (not `filesDir/saved/` yet): `pcg_recording_{ts}_raw.wav` and `pcg_recording_{ts}_filtered.wav` (`:569-571`). A `TaalRecorder` (from `taal-core`) is configured with both paths (`setRawAudioFilePath`/`setFilteredAudioFilePath`), a 300s hard cap (`setRecordingTime(300)`), the selected `PreFilter` or a custom bandpass, current pre-amp dB (0–30, default 5, clamped in the ViewModel: `PcgScaleRecordingViewModel.kt:66`), and an optional hum/rumble filter toggle.
2. **Live callback** (`onProgressUpdate`, `:662-707`): feeds a local `AudioTrack` monitor (so the clinician hears live audio), feeds `HeartBpmCalculator` for BPM, and — after undoing the pre-amp gain so display always reflects true acoustic level (`COMPENSATE_PREAMP_IN_DISPLAY`, `:126-128`, `:686-697`) — runs the display-only `PcgLiveDisplayFilter`, updates the RMS-based Y-axis (`PcgAmplitudeScale`), downsamples via `PcgScaleWaveformView.downsampleMinMax`, and redraws the chart, snapping the camera to the current 1-of-N "page" (window) via `chart.moveViewToX`.
3. **Stop** (`stopRecording()`, `:755-776`): stops the recorder and the monitor, then navigates **immediately** to `pcgScalePlayerFragment` with `filePath` = the filtered temp file, `rawFilePath` = the raw temp file, `isNewRecording=true`, `filterName`, and `preAmpDb` (so the player can undo the same gain for its own display/RMS measurement). No toast, no precompute — same "instant graph" pattern as the production flow.
4. **Player** (`PcgScalePlayerFragment`): loads the full waveform from the filtered file, offers EQ, and shows a Save/Discard bar only when `isNewRecording` (`:120`).
5. **Save** (`SaveRecordingFragment.kt`, shared by every recorder family via `popUpToDestination` argument so each family's Save lands back on its own recorder, `:44-48`): user types a name; `saveInternally()` renames (falling back to copy+delete across mount points, `moveFile()`, `:213-229`) both temp files into `filesDir/saved/{FILTER}_{userInput}_filtered.wav` and `_raw.wav` — the **filter name is embedded as a filename prefix** specifically so the icon can be derived from the filename alone with no sidecar metadata file (`:139-140`, `:179-180`). Then, best-effort, both files are additionally copied to the device's shared Music folder (`copyOneFile()`, `:258-292`): MediaStore `Music/Taal Saved Recordings` on API 29+ (no permission needed, `IS_PENDING` dance), or a direct `Environment.DIRECTORY_MUSIC/Taal Saved Recordings` write gated behind a runtime `WRITE_EXTERNAL_STORAGE` request on API 24–28. Finally navigates to `savedRecordingsFragment` with `popUpTo` = whichever recorder family's destination ID was passed in.
6. **Discard**: temp files are deleted directly (`File(...).delete()`), no DB/repository interaction — matches the production flow's discard semantics.
7. A device-disconnect during recording (`onDeviceDisconnected`, `PcgScaleRecordingFragment.kt:606-627`) deletes both temp files and resets to IDLE with a toast.
8. **Known-disabled feature**: `onSilentRecordingDetected` is commented out entirely (`:629-660`) — a documented false-positive on a Samsung SM-A066B study device (a real, audible recording measured `filtered peak=0.00365`, ~2.7× below the SDK's fixed `0.01` silence threshold) means this dialog is intentionally dead code right now, not an oversight. See `docs/notes/SAMSUNG_AUDIO_LEVEL_ATTENUATION_DIAGNOSTIC.md` / `docs/notes/AUDIO_DIAGNOSTIC_REMEDIATION_TRACKER.md`.

---

## 7. Sharing

Two independent share code paths, both reachable (one from `SavedRecordingsFragment`, one from `RecordingLibraryFragment` — the latter itself unreachable per §3.1, but the code is identical in intent):

- `SavedRecordingsFragment.shareRecording()` (`ui/library/SavedRecordingsFragment.kt:95-135`): copies the on-disk `{FILTER}_{userInput}_filtered.wav` into a scratch `.share_tmp/` dir under a **cleaned display name** (stripping the filter prefix and `_filtered` suffix) so the recipient sees the name the user actually typed, not internal naming cruft. Wraps it in a `FileProvider` content URI (authority `${packageName}.fileprovider`) and fires `ACTION_SEND`.
- `RecordingLibraryFragment.shareRecording()` (`ui/library/RecordingLibraryFragment.kt:301-328`): shares the recording's DB `filePath` directly via the same `FileProvider`, no rename step.
- **Both explicitly set `type = "application/octet-stream"`, not `"audio/wav"` or `"audio/*"`** — with an inline comment in both files explaining why: several apps (WhatsApp named explicitly) treat an `audio/*` share as a voice-note/media attachment and **transcode it** (e.g. to AAC), corrupting the diagnostic recording. The generic MIME type routes the intent through each app's "send as document/file" path instead, which passes the original WAV bytes through untouched; the `.wav` extension on the (still-present) filename is what tells the receiving app what it actually is.

---

## 8. PCG Segmentation Feature

- **Gate**: `ui/segmentation/SegmentationFeature.ENABLED` — a single `const val Boolean`, currently `true` (`SegmentationFeature.kt:11`). Its own doc comment states the intended contract: flip to `false` and the whole feature (button through screen through chart) disappears with no other file needing changes.
- **Current reality**: per §3.1, the feature's only entry point — production `PlayerFragment`'s "Analyze Heart Sounds" button — is itself unreachable from the app's live `startDestination`. The flag being `true` does not currently mean a user can reach this feature through normal navigation; it means the code path exists and works if you do reach it (e.g. by manually navigating there, or once/if `startDestination` reverts to `recordingFragment`).
- **Entry**: `PlayerFragment.kt` checks the flag, shows the button only on an already-**saved** recording (never a fresh in-memory one), and navigates via `action_player_to_segmentationReport` with `rawFilePath` pointing at the saved `{name}_raw.wav`.
- **`SegmentationReportFragment`** (`ui/segmentation/SegmentationReportFragment.kt`): instantiates `TaalCardiacSegmentation` (from the `taal-segmentation` module, package `com.musediagnostics.taal.segmentation`), calls `segmentRawWav(rawFile, verboseLogging=true)` off the main thread, producing a `SegmentationOutcome` (`Ok` / `TooWeak` / `NoHeartSounds` / `Unavailable`). The chart (`pcgChart`, a `PcgSegmentationView`) is drawn from a **separately display-filtered** copy of the same raw audio (`PcgDisplayFilter.processOffline`, zero-phase so S1/S2 timestamps are unaffected) — segmentation itself always runs on the untouched raw file. Shows heart-rate, cycle count, duration, mean systolic interval; zoom presets (Full/10s/5s) re-center on the current view rather than resetting to 0.
- Result is cached in an `activityViewModels()`-scoped `SegmentationViewModel` so `SegmentationFullScreenFragment` (a landscape-locked full-screen chart, reached via `action_segmentationReport_to_segmentationFullScreen`) doesn't need to re-run inference after the orientation-triggered Activity recreation (`MainActivity` is portrait-locked with no `configChanges`, so landscape requires a real Activity recreate — `SegmentationFullScreenFragment.kt:1-5`).
- **PDF export**: `savePdfToDownloads()` (`SegmentationReportFragment.kt:243-285`) via `SegmentationPdfExporter` (not read in this pass) → MediaStore `Downloads` collection (API 29+) or direct `DIRECTORY_DOWNLOADS` write (API 24–28), MIME `application/pdf`. **The download button is currently hidden** (`binding.downloadButton.visibility = View.GONE`, `:153`) "per request (2026-09-02)" but the listener is still wired — i.e. deliberately dead UI, not a bug, kept so re-enabling is a one-line change.
- Full pipeline internals live in `taal-segmentation-core` (pure Kotlin/JVM, unit-tested) + `taal-segmentation` (Android wrapper) — out of scope for this doc; see `docs/pcg-segmentation/APP_INTEGRATION_STATUS.md` and `docs/pcg-segmentation/PORTING_GUIDE.md`.

---

## 9. UI Screen Inventory

Reachability column reflects the §3.1 analysis (as of this snapshot). "Live" = reachable by tapping through the UI from a cold launch. "Deep-link" = reachable via `adb`/`taalapp://` only. "Orphan" = no inbound edge and no code caller at all. "Auth-chain" = technically wired to other auth screens but the chain's entry point itself is unreachable. "Drawer-gated" = reachable only through the currently-dead drawer chain (§3.1).

| Fragment (package) | One-line purpose | Reachable? |
|---|---|---|
| `ui.SplashFragment` | 2s branding delay, then routes to recording/pinLogin/signIn based on `SharedPreferences` (`is_logged_in`, `pin_set`) | No (auth-chain) |
| `ui.auth.SignInFragment` | Sign-in landing screen | No (auth-chain) |
| `ui.auth.LoginFragment` | Enter mobile number | No (auth-chain; also the drawer sign-out target) |
| `ui.auth.OtpFragment` | OTP verification | No (auth-chain) |
| `ui.auth.SignUpFragment` | New account sign-up | No (auth-chain) |
| `ui.auth.SetPinFragment` | Set app PIN | No (auth-chain) |
| `ui.auth.PinConfirmedFragment` | Confirm PIN | No (auth-chain) |
| `ui.auth.PinLoginFragment` | PIN-based login | No (auth-chain) |
| `ui.auth.FingerprintSetupFragment` | Enroll biometric unlock | No (auth-chain) |
| `ui.auth.FingerprintConfirmFragment` | Confirm biometric unlock | No (auth-chain) |
| `ui.recording.RecordingFragment` | **Production** recorder: MPAndroidChart, 10s window, warmup/peak Y-axis | No (auth-chain) |
| `ui.recording.NewRecordingFragment` | "Experimental new recording UI — for testing only" | No (orphan-ish; only inbound edge is dead) |
| `ui.recording.TestRecordingFragment` | Dormant recorder test screen, explicitly marked not-in-use in-file | No (orphan) |
| `ui.player.TestPlayerFragment` | Dormant player counterpart | No (orphan) |
| `ui.player.PlayerFragment` | **Production** player: EQ, crop, save/discard, "Analyze Heart Sounds" (segmentation entry) | No (auth-chain) |
| `ui.player.EqualizerFragment` | 6-band parametric EQ over a saved/temp WAV, shared by every player family | Yes (via pcgScale/pcgScaleReview) |
| `ui.recording.CropRecordingFragment` | Trim a recording's start/end | No (only from production player) |
| `ui.recording.EditRecordingFragment` | Edit a saved recording's metadata + re-amplify (0–10dB slider — see §10) | No (only from RecordingLibrary) |
| `ui.library.RecordingLibraryFragment` | Patient-scoped recording library + share/rename/delete | No (drawer-gated) |
| `ui.patient.AddPatientFragment` | Search/create a patient to attach to a recording; also exposes the only `openDrawer()` call in the module | No (only from dead `PcgScalePlayerFragment` else-branch — see §3.1) |
| `ui.profile.ProfileFragment` | Doctor profile (name/photo, feeds drawer header) | No (drawer-gated) |
| `ui.settings.ChangePinFragment` | Reset app PIN | No (drawer-gated) |
| `ui.info.FaqFragment` | Static FAQ | No (drawer-gated) |
| `ui.info.PrivacyPolicyFragment` | Terms/privacy | No (drawer-gated) |
| `ui.info.SubscriptionFragment` | Subscription info | No (drawer-gated) |
| `ui.info.UserManualFragment` | User manual | No (drawer-gated) |
| `ui.info.AboutUsFragment` | About screen | No (drawer-gated) |
| `ui.LoadingFragment` | Generic loading spinner destination | No (orphan) |
| `ui.library.SharedRecordingsFragment` | (Declared, unimplemented usage) | No (orphan) |
| `ui.recording.SaveRecordingFragment` | Shared Save screen for every recorder family (renames temp → `filesDir/saved/`, MediaStore copy) | Yes |
| `ui.library.SavedRecordingsFragment` | Lists `filesDir/saved/` WAVs, share/delete, opens `PcgScaleReviewFragment` | Yes |
| `ui.calibrated.CalibratedRecordingFragment` | Calibrated-fork recorder, mm-accurate paper, fixed Y-axis | Deep-link only (`taalapp://calibrated`) |
| `ui.calibrated.CalibratedPlayerFragment` | Calibrated-fork player | Deep-link only |
| `ui.calibrated.DpiCalibrationFragment` | Physical-ruler DPI calibration | Deep-link only |
| `ui.fulltimeon.FullTimeOnRecordingFragment` | Fork of Calibrated, now diverged (preview state, warmup Y-axis) | No (orphan, no deep link) |
| `ui.fulltimeon.FullTimeOnPlayerFragment` | FullTimeOn-fork player | No (orphan) |
| `ui.pcgscale.PcgScaleRecordingFragment` | **Live startDestination.** Time-grid + 60%-fill RMS auto-scale recorder | **Yes — entry point** |
| `ui.pcgscale.PcgScalePlayerFragment` | PcgScale-fork player for a brand-new recording | Yes |
| `ui.pcgscale.PcgScaleReviewFragment` | Read-only PcgScale review for an already-saved recording (from library) | Yes |
| `ui.segmentation.SegmentationReportFragment` | Heart-sound segmentation report + chart | No (only from dead production player) |
| `ui.segmentation.SegmentationFullScreenFragment` | Landscape full-screen segmentation chart | No (same) |
| `ui.MainActivity` | Single Activity, NavHost + Drawer host | N/A (always live) |

---

## 10. Known Issues / Gotchas

1. **Pre-amp dB range mismatch — confirmed still live.** SDK-level clamp (`TaalRecorder`/`AudioFilterEngine` in `taal-core`) and every recorder screen's slider in this module (`PcgScaleRecordingViewModel.setPreAmp`, `:66`: `db.coerceIn(0, 30)`) allow **0–30 dB**. `EditRecordingFragment`'s amplify slider is hardcoded to **0–10 dB** (`res/layout/fragment_edit_recording.xml:207-208`, `android:valueTo="10"`), labeled explicitly in dB (`"Amplify (%ddB)"`, `EditRecordingFragment.kt:62`) and written straight into `RecordingEntity.preAmplification` (`:167`). A recording amplified above 10dB elsewhere cannot be represented, let alone edited, in this screen — though as of this snapshot `EditRecordingFragment` is itself unreachable from the live start destination (§3.1), so the mismatch is currently latent rather than user-visible. (Note: the *other* slider in that same layout, `noiseReductionSlider`, is also `0–10` but is an arbitrary intensity control, not a dB value — not part of this mismatch.)
2. **`AddPatientFragment` / drawer / auth-chain / production RecordingFragment-PlayerFragment / PCG segmentation are all currently unreachable from the app's live `startDestination`** — see the detailed reachability chain in §3.1. This is the most important thing to internalize before "fixing" anything in those files: a correct-looking code change there will not be observable by tapping through the running app.
3. **`fallbackToDestructiveMigration()` on `TaalDatabase`** (`TaalDatabase.kt:33`) — any future entity/schema change without a real `Migration` silently drops all existing patients/recordings on the next app upgrade. Not currently biting (schema is still v1) but a landmine for the next DB change.
4. **`FullTimeOnRecordingFragment`'s own header comment is stale.** It claims "behavior is currently identical to" the Calibrated fork it was cloned from (`:47-48`), but the live code has since added an `IDLE`/`PREVIEW` UI-state split, an always-on preview, a warmup-peak-based Y-axis, and a speaker-monitor toggle not present in Calibrated. Don't trust that comment for FullTimeOn's actual current behavior — read `updateWaveform()` directly.
5. **`PcgScalePlayerFragment` carries dead branches.** Its `isNewRecording == false` code paths (save/discard dialog → `AddPatientFragment`) have no live caller under the current wiring (§3.1/§4.6) — they read as functioning production code but currently cannot execute.
6. **Manifest deep-link comment is stale/incomplete.** The `MainActivity` intent-filter comment only describes the `taalapp://calibrated` host and calls it "not reachable from any always-visible production button" — true, but it doesn't mention the sibling `taalapp://pcgscale` host, which as of this snapshot is redundant with a plain cold launch anyway (pcgscale is the `startDestination`).
7. **Silence-detection dialog is intentionally disabled**, not missing — see §6 point 8. A real fix requires deriving the threshold from a measured per-device noise floor rather than the current fixed `0.01` constant; do not simply uncomment the code without addressing that.
8. **`app`'s release build has no `signingConfig`** (§2) — unlike `stemz-app`, which recently had one wired. A release build of `app` today is unsigned.
9. **`app` does not depend on `taal-ui-kit`** — only `taal-core` (audio engine) + `taal-segmentation`. All UI in this module is bespoke, not SDK-provided, despite `taal-ui-kit` having reached near feature-parity per project memory (`project_uikit_parity_update.md`, not verified in this pass).
10. **Sharing intentionally avoids `audio/*` MIME types** (§7) to prevent WhatsApp and similar apps from transcoding the WAV to AAC — this looks unusual in code review (`"application/octet-stream"` for an audio file) but is deliberate and commented as such in both call sites.

---

## 11. Changelog

| Date | Change | Notes |
|---|---|---|
| 2026-09-08 | Initial `SUPERMASTER_APP.md` created | Full source audit of the `app` module: build config, manifest, nav graph reachability (including the drawer/auth-chain/segmentation dead-path chain in §3.1 — the main new finding not previously documented this precisely), all six waveform families, Room schema, recording/save/share/segmentation flows, full UI inventory. |
