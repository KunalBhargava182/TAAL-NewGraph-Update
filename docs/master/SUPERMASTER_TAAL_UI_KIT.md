# SUPERMASTER_TAAL_UI_KIT.md — `taal-ui-kit` Module Reference

**Read this before touching any code in `taal-ui-kit`.** Verified directly against source as of 2026-09-08 (all file:line citations below were read from source on this date; the module's own `./gradlew :taal-ui-kit:testDebugUnitTest` was actually executed to confirm the test-suite claims in §10).

---

## 1. Module Overview

`taal-ui-kit` (Gradle module path `:taal-ui-kit`, public package `com.musediagnostics.taal.uikit`) is the pre-built **UI layer SDK** for the TAAL digital stethoscope, built on top of `taal-core` (the pure audio engine, package `com.musediagnostics.taal`). It ships two `Activity` entry points — `TaalRecorderActivity` and `TaalPlayerActivity` — that a consuming app launches to get a complete record → review → save → browse flow without writing any UI itself.

- **Depends on**: `:taal-core` only (`taal-ui-kit/build.gradle.kts:48`).
- **Real consumers in this monorepo**: `visualizertaal-app` is the only app module that actually declares a Gradle dependency on `:taal-ui-kit` (`visualizertaal-app/build.gradle.kts:65`), and it is also the only app that depends on both `:taal-core` and `:taal-ui-kit` together (`visualizertaal-app/build.gradle.kts:63,65`). **Correction to a prior memory note**: `lungs-app` does *not* depend on `:taal-ui-kit` — a repo-wide grep initially flagged `lungs-app/src/main/java/com/musediagnostics/taal/lungs/ui/recording/LungsRecordingFragment.kt:42` as a hit, but that line is only a comment ("Waveform rendering state (same V7 pattern as taal-ui-kit)"); `lungs-app/build.gradle.kts` declares `implementation(project(":taal-core"))` only, no `:taal-ui-kit` line. Verified directly, 2026-09-08.
- **No EQ screen.** There is no `EqualizerFragment` anywhere under `taal-ui-kit/src/main/java` (confirmed by directory listing — see §11). EQ exists only in `app` (`app/src/main/java/com/musediagnostics/taal/app/ui/player/EqualizerFragment.kt`).
- **No custom dB panel.** Both `RecordingFragment` and `PlayerFragment` expose pre-amp purely via a single Material `Slider` (0–30 dB) plus a text label — no secondary numeric-entry dB dialog/panel exists in either screen (see §4, §5). There *is* a separate, unrelated "Custom Frequency Range" panel for the `CUSTOM` filter type (low/high cut Hz inputs) — do not confuse the two; see §4.

## 2. Module Config

Source: `taal-ui-kit/build.gradle.kts:1-88`.

- `namespace = "com.musediagnostics.taal.uikit"`, `compileSdk = 34` (`build.gradle.kts:7-8`)
- `minSdk = 24`, `targetSdk = 34` (`build.gradle.kts:11-12`)
- `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` (`build.gradle.kts:14`)
- `viewBinding = true` (`build.gradle.kts:38`) — every fragment/activity uses generated `FragmentXBinding` / view binding, `_binding`/`binding` nullable pattern (see §12).
- Java/Kotlin target: 1.8 (`build.gradle.kts:29-34`)
- `testOptions { unitTests.isIncludeAndroidResources = true }` (`build.gradle.kts:41-43`) — required for Robolectric to resolve this module's own resources (layouts, strings) in tests.
- Release build type: `isMinifyEnabled = false` (`build.gradle.kts:20`), default + `proguard-rules.pro` (currently **empty**, see §9) plus `consumer-rules.pro` (also **empty**, `taal-ui-kit/consumer-rules.pro`).
- **AAR output path**: `taal-ui-kit/build/outputs/aar/taal-ui-kit-release.aar` (per repo convention; build via `./gradlew :taal-ui-kit:assembleRelease`).

### Runtime dependencies (`build.gradle.kts:46-88`)
| Dependency | Version | Purpose |
|---|---|---|
| `project(":taal-core")` | — | audio engine (recorder/player/DSP) |
| `androidx.core:core-ktx` | 1.12.0 | |
| `androidx.appcompat:appcompat` | 1.6.1 | |
| `com.google.android.material:material` | 1.11.0 | Slider, RangeSlider, MaterialCardView, MaterialAlertDialogBuilder |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | |
| `androidx.recyclerview:recyclerview` | 1.3.2 | saved-recordings list |
| `androidx.navigation:navigation-fragment-ktx` / `navigation-ui-ktx` | 2.7.6 | in-module `nav_uikit.xml` graph |
| `com.google.android.gms:play-services-base` | 18.3.0 | comment says "for animation and common UI resources" |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` / `livedata-ktx` / `runtime-ktx` | 2.7.0 | `RecordingViewModel` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` / `core` | 1.7.3 | BPM background computation, waveform load, save I/O |
| `com.github.PhilJay:MPAndroidChart` | v3.1.0 | waveform `LineChart` in both Recording and Player screens |

### Test dependencies (`build.gradle.kts:76-87`) — see §10 for full detail
`junit:4.13.2`, `mockito-core:5.5.0`, `mockito-inline:5.2.0`, `mockito-kotlin:5.1.0`, `robolectric:4.11.1`, `androidx.test:core:1.5.0`, `androidx.test.ext:junit:1.1.5`, `androidx.arch.core:core-testing:2.2.0` (for `InstantTaskExecutorRule`), `kotlinx-coroutines-test:1.7.3`; `androidTestImplementation` has `androidx.test.ext:junit:1.1.5` + `espresso-core:3.5.1` (unused — there is no `src/androidTest` directory in this module).

### Manifest (`taal-ui-kit/src/main/AndroidManifest.xml`)
- Permissions: `RECORD_AUDIO`, `USB_PERMISSION`; `<uses-feature android:name="android.hardware.usb.host" android:required="false"/>` (lines 4-9).
- A `FileProvider` at authority `${applicationId}.taaluikit.fileprovider`, paths resource `@xml/taal_file_paths` (lines 13-21), which exposes only `files-path name="taal_saved_recordings" path="saved/"` (`res/xml/taal_file_paths.xml:3`). Used by `SavedRecordingsFragment.shareRecording()` (§6).
- `TaalRecorderActivity` and `TaalPlayerActivity` both declared `exported="true"`, `screenOrientation="portrait"`, themed `@style/Theme.TaalUiKit` (lines 23-34). Only `TaalRecorderActivity` sets `windowSoftInputMode="adjustResize"`.

## 3. Entry Points

### `TaalRecorderActivity` (`taal-ui-kit/src/main/java/com/musediagnostics/taal/uikit/TaalRecorderActivity.kt`)
Public factory, lines 39-50:
```kotlin
fun getIntent(
    context: Context,
    preFilter: String = "HEART",
    preAmplification: Int = 5,
    recordingTimeSeconds: Int = 300
): Intent
```
Extras: `EXTRA_PRE_FILTER` ("preFilter"), `EXTRA_PRE_AMPLIFICATION` ("preAmplification"), `EXTRA_RECORDING_TIME_SECONDS` ("recordingTimeSeconds") — all three are string-constant-verified in `TaalRecorderActivityTest.kt:73-90`. Note `recordingTimeSeconds` is accepted as an extra but **`RecordingFragment.startRecording()` hard-codes `setRecordingTime(300)`** regardless of this extra (`RecordingFragment.kt:506`) — the extra is read into the `TaalRecorderActivity` intent but never consumed inside `RecordingFragment`; see §12 Known Issues.

`onCreate` (lines 53-64) hosts `nav_uikit.xml` in `R.id.navHostRecorder`, default start destination `recordingFragment`.

Result contract:
- `RESULT_FILE_PATH` = "filePath" (constant, verified `TaalRecorderActivityTest.kt:88-90`).
- `storeResult(filePath)` (lines 71-74): sets `RESULT_OK` with the path **without finishing** — called by `SaveRecordingFragment.triggerSave()` (`SaveRecordingFragment.kt:139`) right after a successful save, so the user can keep browsing (e.g. into `SavedRecordingsFragment`) while the eventual `onActivityResult` in the host app will still see `RESULT_OK` + the saved path once the user backs out.
- `finishWithResult(filePath)` (lines 80-83) and `discardAndFinish()` (lines 89-92) are declared but **`finishWithResult` has zero call sites** in the module (grep confirmed — only `storeResult` and `discardAndFinish` are actually invoked, from `SaveRecordingFragment.kt:139` and `PlayerFragment.kt:282` respectively). `finishWithResult` appears to be dead/unused public API left for host-app convenience.

### `TaalPlayerActivity` (`taal-ui-kit/src/main/java/com/musediagnostics/taal/uikit/TaalPlayerActivity.kt`)
Public factory, lines 25-31:
```kotlin
fun getIntent(context: Context, filePath: String): Intent
```
This **validates** with `require(filePath.isNotBlank())` (line 26) — it throws `IllegalArgumentException` for a blank path. This is a real behavioral change from what a comment in the module's own test suite claims (see §10 — `TaalPlayerActivityTest.kt:41-46` is now a **failing, stale test**, confirmed by actually running it).

`onCreate` (lines 33-58) does **not** use the static `nav_uikit.xml` start destination. Instead it creates a bare `NavHostFragment()`, inflates `nav_uikit.xml` manually, calls `graph.setStartDestination(R.id.playerFragment)`, and sets the graph with a `Bundle` containing `filePath` + `isNewRecording=false`. This means `TaalPlayerActivity` always opens directly on `PlayerFragment` in review-only mode (no Save/Discard bar — see §5), skipping `RecordingFragment` entirely. `EXTRA_FILE_PATH` = "filePath".

## 4. Recording Screen

Files: `recording/RecordingFragment.kt` (785 lines), `RecordingViewModel.kt` (56 lines), `RecordingUiState.kt` (7 lines), `FilterPlacementDialog.kt` (149 lines), layout `res/layout/fragment_recording.xml`.

### State machine
`RecordingUiState` = `IDLE | RECORDING | STOPPED` (`RecordingUiState.kt:3-7`). `RecordingViewModel` (`RecordingViewModel.kt`) exposes `uiState`, `timerSeconds`, `currentFilter` (default `"HEART"`), `bpm`, `preAmpDb` (default `5`) all as `LiveData`, plus plain vars `currentRecordingPath`, `currentFilteredPath`, `customLowCut`/`customHighCut` (nullable `Float`, for the CUSTOM filter). `setPreAmp(db)` clamps `db.coerceIn(0, 30)` (line 46) — **this is the 0-30 dB range, not 0-20** (see §10 stale test).

`observeState()` (`RecordingFragment.kt:407-446`) only branches on `IDLE`/`RECORDING`; there is no explicit `STOPPED` UI branch in the fragment's observer (an `else -> {}` catches it) because on `STOPPED` the fragment immediately navigates away to `PlayerFragment` via `stopRecording()` (line 665-683) rather than rendering a stopped state in place.

### Filters
Preset filter chips: HEART, LUNGS, BOWEL, PREGNANCY, FULL_BODY (mapped to `PreFilter` enum names in `taal-core`), plus a sixth **CUSTOM** chip (`RecordingFragment.kt:186-222`, `filterCustom` button, `fragment_recording.xml:198-207`). Selecting CUSTOM reveals a `customRangePanel` (`fragment_recording.xml:211-321`): a Material `RangeSlider` (0–24000 Hz) plus two `TextInputEditText` fields for Low Cut / High Cut Hz, two-way bound in `setupCustomRangePanel()` (`RecordingFragment.kt:224-282`). Validation before starting a CUSTOM recording (`RecordingFragment.kt:464-494`) rejects blank/zero/inverted ranges with a `MaterialAlertDialogBuilder` error. On start, `TaalRecorder.setCustomBandpass(low, high)` is called instead of `setPreFilter(...)` (`RecordingFragment.kt:509-516`). **This CUSTOM/custom-bandpass feature is not mentioned in any prior memory note** — it is a real, fully-wired feature in this module, worth flagging as new/undocumented territory.

`FilterPlacementDialog` (info "ⓘ" button, `RecordingFragment.kt:347-351`) shows a `ViewPager2` of placement images resolved at runtime via `resources.getIdentifier("taal_placement_{filter}_{index}", "drawable", packageName)` (`FilterPlacementDialog.kt:89-98`), scanning indices 1..12 (not required to be contiguous — comment notes lungs images start at `_2`).

### Pre-amp slider
Single Material `Slider`, `valueFrom=0`, `valueTo=30`, `stepSize=1`, default `5` (`fragment_recording.xml:362-379`, `ampSlider`). No secondary dB entry panel exists — confirmed by full layout read (`fragment_recording.xml`) and fragment code (`setupPreAmpSlider()`, `RecordingFragment.kt:174-184`). `onResume()` **force-resets pre-amp to 5 dB every time the fragment resumes** (`RecordingFragment.kt:768-770`), matching the documented convention.

### Waveform rendering — confirms V7 sample-accurate approach, same pattern as `app`
`updateWaveform()` (`RecordingFragment.kt:685-759`) implements the same "V7" scheme documented for `app`:
- `WINDOW_SECONDS = 10f`, `INPUT_SAMPLE_RATE = 44100f`, `DOWNSAMPLE_STEP = 44` (~1002 pts/sec) (`RecordingFragment.kt:66-68`).
- Warmup phase: `WARMUP_MS = 2000L` (2s, not the app's 1500ms per the memory note — **numeric difference from the documented `app` value**), accumulates `warmupPeak`, then locks Y-axis to `peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)` with `HEADROOM = 1.5f`, `MIN_PEAK = 0.02f` (`RecordingFragment.kt:69-71, 720-731`).
- Page-based memory cleanup: keeps current + previous 10s page only (`RecordingFragment.kt:704-716`).
- Persistent `LineDataSet` reuse — `ds.values = snapshot; chart.data?.notifyDataChanged()` instead of replacing `chart.data` (`RecordingFragment.kt:733-749`) — avoids blank frames.
- Camera snap via `chart.moveViewToX(currentPage * WINDOW_SECONDS)` (`RecordingFragment.kt:756`).
- **Pre-amp is undone before drawing**: `preAmpGain = 10^(preAmpDb/20)`; the displayed waveform divides samples by this gain so the graph always shows acoustic level, not the amplified capture (`RecordingFragment.kt:601-609`). This specific "undo pre-amp for display" behavior should be checked against `app`'s copy if exact parity matters — not verified against `app`'s `RecordingFragment.kt` line-by-line in this audit (time-boxed; see §11).

### BPM display
Uses `dsp/HeartBpmCalculator` (§7) via a dedicated `bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())` (`RecordingFragment.kt:63, 587-599`); `addSamples()` runs on the audio callback thread (fast), `computeBpm()` is dispatched onto the scope, result posted back via `Dispatchers.Main`.

### USB device icon
`deviceIcon` `ImageButton` uses `icon_taal.xml` and is tinted directly via `setColorFilter` (teal `#128CB2` connected / dark grey `#333333` disconnected) in both `TaalConnectionBroadcastReceiver` callbacks (`RecordingFragment.kt:311-327`) and an active poll `checkDeviceConnectionStatus()` on `onResume()` (`RecordingFragment.kt:329-341, 764`). No custom `filter_icon_selector`-style approach here — direct `setColorFilter` calls (contrast with `res/color/filter_icon_selector.xml` used for the filter chip icons, `fragment_recording.xml:127` etc., which is a separate mechanism).

### Cold-start silent-recording dialog
`onSilentRecordingDetected(isFirstSinceConnect)` (`RecordingFragment.kt:553-575`) shows an `AlertDialog` anchored to the **Activity window** (not the fragment view) only for the first recording since a physical USB connection began, since by the time it fires the user may already be on `PlayerFragment`. Explicit code comment states the "not first" variant was tried and dropped 2026-08-14 as unreliable/false-positive.

### Device-disconnect handling
`onDeviceDisconnected()` (`RecordingFragment.kt:529-551`) deletes both partial temp files (raw + filtered) and calls `resetToIdle()`.

## 5. Player + Save Screen

Files: `player/PlayerFragment.kt` (299 lines), `player/SaveRecordingFragment.kt` (231 lines), layouts `fragment_player.xml`, `fragment_save_recording.xml`.

### PlayerFragment
- Nav args: `filePath`, `rawFilePath`, `isNewRecording` (bool), `filterName` (declared in `nav_uikit.xml:31-49`).
- **Save/Discard bar** (`binding.saveDiscardBar`) visibility is gated purely on `isNewRecording` (`PlayerFragment.kt:53`) — `true` only when arriving from a fresh recording (`RecordingFragment.stopRecording()`); `false` when arriving from `SavedRecordingsFragment` or `TaalPlayerActivity` (both pass `isNewRecording=false`).
- **Full waveform load**: `loadFullWaveform()` (`PlayerFragment.kt:128-183`) reads the whole WAV file's bytes on `Dispatchers.IO`, manually parses 16-bit little-endian PCM samples starting at byte 44 (skips the WAV header manually rather than using `WavCropper`), downsamples to ≤3000 points (`sampleStep = maxOf(1, totalSamples / 3000)`), then renders a static `LineDataSet` with `setVisibleXRangeMaximum(4f)` and drag/scale enabled (pinch/scroll through the recording).
- **Double-filter avoidance**: `setupPlayer()` (`PlayerFragment.kt:185-235`) checks `File(filePath).name.contains("_filtered")` — if the file is already a `_filtered.wav` (the normal case from the dual-file recording flow), it **skips** `player.setPreFilter(...)` entirely, since DSP was already baked in during recording (`PlayerFragment.kt:190-195`).
- **Pre-amp slider on the Player screen too** (`fragment_player.xml:58-129`, `binding.ampSlider`/`ampLabel`), same 0-30dB single-slider pattern, wired to `player?.setPreAmplification(db.toFloat())` (`PlayerFragment.kt:91-96`) for live-adjustable playback gain — this is a `TaalPlayer` API call and is independent from the Recording screen's pre-amp slider/ViewModel state (no shared value; both default to 5dB in their respective XML `android:value="5"`).
- **Playback progress**: `onPlaybackProgress` centers the chart to the current playhead once past half the visible range (`PlayerFragment.kt:196-213`); `onPlaybackComplete` resets both the button icon and view centering (`PlayerFragment.kt:214-228`).
- **Discard**: `confirmDiscard()` (`PlayerFragment.kt:273-287`) deletes both the filtered and raw temp files, then calls `(requireActivity() as? TaalRecorderActivity)?.discardAndFinish()` — falling back to `findNavController().navigateUp()` if the host activity isn't a `TaalRecorderActivity` (i.e. when reached via `TaalPlayerActivity` or `SavedRecordingsFragment`, discard is just a nav-up, since those paths never show the Save/Discard bar and thus never call `confirmDiscard` in practice — the fallback exists but the button that triggers it is gone by the isNewRecording gate).

### SaveRecordingFragment — exact save mechanism
Nav args: `filePath` (filtered temp path), `rawFilePath`, `filterName` (`nav_uikit.xml:66-79`).

1. UI pre-fills the filename input with `yyyyMMdd_HHmmss` (`SaveRecordingFragment.kt:84-86`); `binding.filterChip` is explicitly hidden (`line 82`) — the layout has a filter-chip TextView (`fragment_save_recording.xml:104-118`) that is never shown in current code, effectively dead UI within a live screen (see §9).
2. `triggerSave(name)` (`SaveRecordingFragment.kt:102-145`): sanitizes the name with `Regex("[/\\\\:*?\"<>|]")` → `_`, builds `fullSaveName = "${filterName}_${safeName}"` (so saved files are always prefixed by filter, e.g. `HEART_20260908_143000`).
3. `saveInternally()` (`lines 147-157`) — **always runs first, on `Dispatchers.IO`**: `moveFile()` (rename, falling back to copy+delete) both the filtered and raw temp files into `filesDir/saved/{fullSaveName}_filtered.wav` and `..._raw.wav` (`lines 159-165`). This is the "AND" — internal save to app-private storage always happens regardless of Music-folder permission outcome.
4. **API branching for the public Music-folder copy** (`copyOneFile()`, `lines 180-213`):
   - **API 29+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`)**: `MediaStore.Audio.Media` insert with `RELATIVE_PATH = "Music/Taal Saved Audios"`, `MIME_TYPE = "audio/wav"`, `IS_PENDING=1` → write via `resolver.openOutputStream(uri)` → `IS_PENDING=0`. No runtime permission needed (scoped storage).
   - **API 24-28**: writes directly to `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)/Taal Saved Audios/`, requires `WRITE_EXTERNAL_STORAGE` — checked via `ContextCompat.checkSelfPermission` in `triggerSave()` (`lines 120-126`); if not granted, `storagePermissionLauncher.launch(...)` is fired and the actual `copyAllToDeviceStorage()` call is deferred into the permission-result callback (`lines 42-66`).
   - Both branches wrap failures in `catch (_: Exception) {}` — the comment states this is intentionally non-fatal because the internal `filesDir/saved/` copy is already complete by the time this runs (`lines 210-212`).
5. On success (or after permission handling), `(requireActivity() as? TaalRecorderActivity)?.storeResult(finalPath)` (line 139) then `navigateAfterSave()` — navigates to `savedRecordingsFragment` with `popUpTo(recordingFragment, inclusive=false)` (`lines 215-224`), i.e. pops `PlayerFragment` and `SaveRecordingFragment` off the back stack but keeps `RecordingFragment` as the new-bottom.

## 6. Saved Recordings List

Files: `player/SavedRecordingsFragment.kt` (111 lines), `player/SavedRecordingAdapter.kt` (82 lines), layouts `fragment_saved_recordings.xml`, `item_saved_recording.xml`.

- `loadRecordings()` (`SavedRecordingsFragment.kt:41-68`) lists only `*_filtered.wav` files directly under `filesDir/saved/` (raw files are hidden from the list, matched implicitly by filename convention), sorted by `lastModified()` descending. Called both in `onViewCreated` and `onResume` (so the list refreshes after returning from Player/Save).
- Filter icon + filename parsing both rely on filename convention `"{FILTER}_{userInput}_filtered.wav"`, matched against a hard-coded known-filter list `["FULL_BODY","PREGNANCY","CUSTOM","LUNGS","BOWEL","HEART"]` in **two separate places** — `SavedRecordingsFragment.extractFilterName()` (`lines 100-104`) and `SavedRecordingAdapter.extractFilter()` (`lines 67-70`) — duplicated logic, not shared via a common util (see §12).
- **Sharing**: `shareRecording()` (`SavedRecordingsFragment.kt:70-84`) uses the module's own `FileProvider` (`${packageName}.taaluikit.fileprovider`) to build a content `Uri` for the filtered WAV, then `Intent.ACTION_SEND` with `type="audio/wav"` via a chooser. Only the filtered file is shared, not the raw file.
- **Delete**: `confirmDelete()` (`lines 86-98`) deletes the filtered file and, if it exists, the sibling `_raw.wav` (derived via string replace), then reloads the list.
- `SavedRecordingAdapter.getWavDuration()` (`lines 72-80`) computes duration inline (`(file.length()-44)/2/44100`) rather than delegating to `WavCropper.getDurationSeconds()` — another small duplication (see §12).

## 7. BPM Calculation

`dsp/HeartBpmCalculator.kt` (163 lines) — autocorrelation-based heart-rate estimator, confirmed against source line-by-line:

- Constructor `HeartBpmCalculator(sampleRate: Int = 44100)` (line 13).
- `analysisRate = 1000` (Hz), `downsampleFactor = sampleRate / analysisRate` = 44 for 44100Hz input (integer division, lines 15-16) — **confirms the "downsamples to 1000Hz" memory note**, though the actual factor for 44100Hz truncates to 44 (not exactly 44.1).
- `bufferDurationSec = 4.0`, `bufferSize = analysisRate * bufferDurationSec = 4000` samples — **confirms the "4s buffer" memory note** (lines 18-19). Implemented as a `FloatArray(bufferSize)` ring buffer (`writePos` wraps via modulo).
- `addSamples(data)` (lines 41-58) is designed to run on the fast/UI-adjacent audio thread: it accumulates `abs(sample)` into `dsAccum`, and every `downsampleFactor` input samples writes one averaged-envelope value into the ring buffer. It returns `true` (signal "time to compute") only when **all** of: not already computing, `now - lastUpdateTime >= 5000L` ms, and `samplesCollected >= analysisRate * 3` (≥3s of data collected) — **confirms the "computes every 5s" memory note**, gated additionally by a 3-second minimum data requirement.
- `computeBpm()` (lines 64-87) — meant for a background thread — extracts up to `bufferSize` samples in correct temporal order from the ring buffer, then:
  - `calculateBpm(envelope)` (lines 89-150): normalizes by max value (bails if `maxVal < 0.0001f`), applies a 30-sample moving-average smoothing filter, then autocorrelates over lag range `[0.4s, 1.5s]` of the 1000Hz-equivalent signal (i.e. 40–150 BPM lag window) using a sliding-window sum-of-products, finds the lag with peak correlation, normalizes that peak against total signal energy (`autoZero`), and **rejects** the result if `normalizedCorr < 0.3f` or `bestLag == 0`.
  - Final result clamped to `bpm in 40..200` else returns `0` (line 149).
- `reset()` (lines 152-161) zeroes the buffer and all counters/flags — called by `RecordingFragment` both in `resetToIdle()` and at the start of every new recording.

## 8. WAV Utilities

`util/WavCropper.kt` (140 lines) — this module's own copy (do not assume it's byte-identical to any other module's `WavCropper`; only this copy was audited):

- Constants: `WAV_HEADER_SIZE=44`, `SAMPLE_RATE=44100`, `BITS_PER_SAMPLE=16`, `CHANNELS=1`, `BYTES_PER_SAMPLE=2` (lines 15-19) — hard-coded, not read from the actual WAV header.
- `getDurationSeconds(filePath): Float` (lines 21-27) — `(file.length() - 44) / 2 / 44100`. Returns `0f` for a missing file.
- `getWaveformData(filePath, maxPoints=1000): FloatArray` (lines 29-68) — streams the file via `FileInputStream`, skips the header, then reads sample-by-sample with a computed `step`, normalizing each 16-bit sample to `[-1,1]` via `/32768f`. Uses `fis.skip()` between kept samples to avoid loading the whole file into memory (unlike `PlayerFragment.loadFullWaveform()`, which *does* read the whole file into a `ByteArray` — an inconsistency between the two waveform-loading code paths in this module, see §12).
- `cropWav(inputPath, outputPath, startSeconds, endSeconds): Boolean` (lines 70-112) — computes byte offsets from the requested time range, copies raw PCM data in 8KB chunks, writes a fresh 44-byte header via `writeWavHeader()` (lines 114-138). Returns `false` for a non-existent input, an inverted range, or a zero/negative-length range (verified — `WavCropperTest.kt:112-137` covers all three and all pass).
- **No `computeWaveformForDisplay` / `writeFilteredWav` methods** — matches the memory note that these were removed 2026-03-21 elsewhere; this module's `WavCropper` only ever had the three methods above (confirmed — no other methods exist in the file).

## 9. Dead Code Audit

### `player/TaalSaveDialog.kt` — CONFIRMED STILL DEAD CODE
Repo-wide grep for `TaalSaveDialog` (`E:\AndroidProjects\TaalDemoApp`, all file types) returns exactly 5 hits:
- `taal-ui-kit/src/main/java/com/musediagnostics/taal/uikit/player/TaalSaveDialog.kt` (the class's own file/definition)
- `docs/notes/MASTER_HANDOFF.md`
- `docs/notes/CODEBASE_MAP.md`
- `docs/notes/RecorderandPLayerScreenwithallimp.md`
- `docs/notes/CONTEXT_FOR_PLANNING.md`

All four non-definition hits are **documentation files**, not code. There is **zero** live reference to `TaalSaveDialog` from any `.kt`, `.xml`, or Gradle file anywhere in the repo, including inside `taal-ui-kit` itself (`SaveRecordingFragment` is the live replacement and does not instantiate `TaalSaveDialog`). **Definitively confirmed dead code**, matching and reaffirming the 2026-08-14 memory note.

Its layout, `res/layout/dialog_taal_save.xml`, is correspondingly dead too — the only class that inflates it is `TaalSaveDialog.kt:33-34` itself.

### Other dead/unused code found during this audit
- `TaalRecorderActivity.finishWithResult(filePath)` (`TaalRecorderActivity.kt:80-83`) — zero call sites in the module; only `storeResult()` and `discardAndFinish()` are actually called. Likely intended as public API for host apps that want an immediate-finish variant, but nothing in this module or `visualizertaal-app` currently uses it.
- `SaveRecordingFragment`'s `binding.filterChip` (`fragment_save_recording.xml:104-118`) — the layout defines a filter-name chip TextView, but `SaveRecordingFragment.kt:82` explicitly sets `binding.filterChip.visibility = View.GONE` and it is never shown. Live layout, dead UI element.
- Unused `strings.xml` "Equalizer" block (`res/values/strings.xml:20-27`: `heart_sound`, `lungs_sound`, `noise`, `custom_1/2/3`, `edit_equalizer`) — grepped, these string keys are referenced **only** from `strings.xml`/`colors.xml` themselves (the matching `colors.xml:32-35` `eq_*` colors are in the same boat), with zero `R.string.`/`R.color.` usages anywhere in `taal-ui-kit`'s Kotlin/XML source. Leftover from a prior EQ screen that was removed from this module (per the memory note); the resources were never cleaned up.
- `consumer-rules.pro` and `proguard-rules.pro` are both **empty files** (0 bytes) — not necessarily "dead code" but worth flagging since a future session might expect ProGuard/R8 keep rules here and find none.
- Compiler warnings surfaced during a real build (`./gradlew :taal-ui-kit:compileDebugKotlin`, run 2026-09-08): `PlayerFragment.kt:237` parameter `filePath` never used; `RecordingFragment.kt:685` parameter `timestamp` never used (in `updateWaveform(timestamp, data)` — only `data` and the class-level `totalSamplesProcessed` counter are used for X-axis positioning, the passed timestamp is ignored in favor of the internally tracked sample count).

## 10. Test Suite (the Robolectric reference setup)

`taal-ui-kit` is confirmed to be **the only module in the repo using Robolectric** (based on the grep performed for this audit finding `robolectric.properties` only under `taal-ui-kit/src/test/resources/`). This is the reference pattern for adding Android-runtime JVM tests elsewhere in the monorepo.

### Files
| File | Framework | Purpose |
|---|---|---|
| `src/test/java/.../TaalRecorderActivityTest.kt` (91 lines, 10 tests) | Robolectric (`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk=[34])`) | Verifies `TaalRecorderActivity.getIntent()` extras and constant string values |
| `src/test/java/.../TaalPlayerActivityTest.kt` (52 lines, 5 tests) | Robolectric (same annotations) | Verifies `TaalPlayerActivity.getIntent()` — **1 of 5 tests currently fails**, see below |
| `src/test/java/.../TestWavHelper.kt` (73 lines) | plain Kotlin object, no test framework | Shared helper — synthesizes valid 16-bit PCM mono WAV files (sine tone, silence, header-only, or arbitrary `ShortArray`) for other tests to consume |
| `src/test/java/.../dsp/HeartBpmCalculatorTest.kt` (142 lines, 12 tests) | Plain JUnit4 (no Robolectric — pure Kotlin/math, no Android APIs touched) | Construction, `addSamples`/`computeBpm` behavior, silence handling, synthetic ~72 BPM signal, `reset()`, BPM range validation |
| `src/test/java/.../recording/RecordingUiStateTest.kt` (35 lines, 5 tests) | Plain JUnit4 | Enum values/`valueOf` sanity checks |
| `src/test/java/.../recording/RecordingViewModelTest.kt` (134 lines, 19 tests) | Plain JUnit4 + `InstantTaskExecutorRule` (`androidx.arch.core.executor.testing`) for synchronous LiveData | Defaults, setters, `formatTimer()` — **1 of 19 tests currently fails**, see below |
| `src/test/java/.../util/WavCropperTest.kt` (139 lines, 14 tests) | Plain JUnit4 + `TemporaryFolder` rule | `getDurationSeconds`, `getWaveformData`, `cropWav` — happy path + edge cases (missing file, zero/inverted range) |

**Total: 65 tests across 6 test classes.** Verified by actually running `./gradlew :taal-ui-kit:testDebugUnitTest` on 2026-09-08 (see below for the two failures found).

### VERIFIED CURRENTLY-FAILING TESTS (ran the full suite, not just read the source)
Running `./gradlew :taal-ui-kit:testDebugUnitTest` on 2026-09-08 produces **"65 tests completed, 2 failed"**:

1. **`RecordingViewModelTest.test_setPreAmp_aboveTwenty_coerced`** (`RecordingViewModelTest.kt:91-95`) — asserts `setPreAmp(25)` coerces to `20`, but `RecordingViewModel.setPreAmp()` (`RecordingViewModel.kt:45-47`) actually does `db.coerceIn(0, 30)`, so `25` stays `25`. **`java.lang.AssertionError` at `RecordingViewModelTest.kt:94`.** This is the exact "stale failing-by-spec test" the memory note refers to (the note was about `EditRecordingFragment` in `app` being hardcoded to a 10dB cap vs the SDK's 0-30dB clamp — this test in `taal-ui-kit` is a second, independent instance of the same class of drift: a test written against an old 0-20dB assumption that was never updated when the range became 0-30dB).
2. **`TaalPlayerActivityTest.test_getIntent_withEmptyString`** (`TaalPlayerActivityTest.kt:41-46`) — the test's own comment says `// BUG: should validate and throw, but currently accepts empty string`, and asserts the intent silently carries `""`. But `TaalPlayerActivity.getIntent()` (`TaalPlayerActivity.kt:26`) now has `require(filePath.isNotBlank()) { "filePath must not be blank" }`, which throws `IllegalArgumentException` before the intent is even built. The test doesn't catch this exception, so it fails with **`java.lang.IllegalArgumentException` at `TaalPlayerActivityTest.kt:44`**. In other words, the "bug" the test comment describes **has already been fixed in source**, but the test was never updated to expect success (or at least to `assertThrows`) — the fix flipped a previously-passing "document the bug" test into a hard failure.

Both failures are test/source drift, not necessarily production bugs — but any session touching `RecordingViewModel.setPreAmp` or `TaalPlayerActivity.getIntent` should be aware these two tests will already be red on a clean checkout, independent of their changes.

### Reusable Robolectric setup (copy this pattern for any other module)

**`build.gradle.kts` test deps** (`taal-ui-kit/build.gradle.kts:76-87`):
```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("org.mockito:mockito-core:5.5.0")
testImplementation("org.mockito:mockito-inline:5.2.0")
testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
testImplementation("org.robolectric:robolectric:4.11.1")
testImplementation("androidx.test:core:1.5.0")
testImplementation("androidx.test.ext:junit:1.1.5")
testImplementation("androidx.arch.core:core-testing:2.2.0")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
androidTestImplementation("androidx.test.ext:junit:1.1.5")
androidTestImplementation("androidx.espresso:espresso-core:3.5.1")
```
(Note: Mockito deps and `androidTestImplementation` entries are currently declared but **unused** — no test in the module uses Mockito mocking, and there is no `src/androidTest` directory at all.)

**`android { testOptions { ... } }` block** (`build.gradle.kts:41-43`):
```kotlin
testOptions {
    unitTests.isIncludeAndroidResources = true
}
```
This is the critical flag — without it Robolectric tests that touch resources (via `RuntimeEnvironment.getApplication()` and anything that resolves `R.*`) will fail to find them.

**`src/test/resources/robolectric.properties`** (exact, full content):
```
sdk=34
```

**Test class annotation pattern** (from `TaalRecorderActivityTest.kt:12-13` / `TaalPlayerActivityTest.kt:12-13`):
```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SomeActivityTest {
    private lateinit var context: Context
    @Before fun setup() { context = RuntimeEnvironment.getApplication() }
    ...
}
```
Only the two `Activity`-adjacent test classes actually use Robolectric (`TaalRecorderActivityTest`, `TaalPlayerActivityTest`) — both only test static `getIntent()` factories, not full activity lifecycle (`Robolectric.buildActivity(...)` is not used anywhere in this module). The other four test classes (`HeartBpmCalculatorTest`, `RecordingUiStateTest`, `RecordingViewModelTest`, `WavCropperTest`) are plain JUnit4 with no Android dependency at all — `RecordingViewModelTest` uses `androidx.arch.core.executor.testing.InstantTaskExecutorRule` for LiveData, which does not itself require Robolectric.

## 11. Parity vs `app`

Compared directly against `app`'s equivalent files (line counts + targeted greps performed 2026-09-08; this is not an exhaustive line-by-line diff of every feature — flagged explicitly where a full diff was not performed).

| Aspect | `app` | `taal-ui-kit` | Verdict |
|---|---|---|---|
| Recording screen size | `app/.../ui/recording/RecordingFragment.kt` = 997 lines | `taal-ui-kit/.../recording/RecordingFragment.kt` = 785 lines | `app`'s is noticeably larger — likely patient-linking, DB writes, and other app-only integration not needed in the standalone SDK |
| CUSTOM filter + Hz range panel | Present (`app` `RecordingFragment.kt` has `customRangePanel`, `CUSTOM` filter references at lines 219, 226, 234-235, 463-465, 546, 591) | Present (`RecordingFragment.kt:186-282, 464-516`) | **At parity** — this feature exists in both, contradicting any assumption that CUSTOM/custom-bandpass is `app`-only |
| Player screen size | `app/.../ui/player/PlayerFragment.kt` = 409 lines | `taal-ui-kit/.../player/PlayerFragment.kt` = 299 lines | `app`'s is larger |
| Equalizer (EQ) screen | **Present** — `app/.../ui/player/EqualizerFragment.kt` exists; `app`'s `PlayerFragment.kt:101` navigates to it via `R.id.action_player_to_equalizer` | **Absent** — no `EqualizerFragment.kt` anywhere in `taal-ui-kit`; `nav_uikit.xml` has no equalizer destination at all | `app`-only feature, confirmed |
| PCG Segmentation feature (report screen, full-screen chart, PDF export) | **Present**, feature-flagged — `app`'s `PlayerFragment.kt:21,72` imports and gates on `com.musediagnostics.taal.app.ui.segmentation.SegmentationFeature.ENABLED` | **Absent** — no segmentation package/imports found anywhere in `taal-ui-kit` | `app`-only feature, confirmed |
| Patient/Recording Room DB integration | Present (`PatientEntity`/`RecordingEntity`, repositories) | **Absent** — `taal-ui-kit` has no Room dependency in `build.gradle.kts` and no DB code; recordings are plain files under `filesDir/saved/` with filename-encoded metadata (filter name prefix) instead of DB rows | `app`-only; `taal-ui-kit` is deliberately DB-free/standalone |
| Save flow | `app` has its own `SaveRecordingFragment.kt` (separate file, app package) | `taal-ui-kit` has its own `SaveRecordingFragment.kt` (`player/SaveRecordingFragment.kt`) with the MediaStore/WRITE_EXTERNAL_STORAGE dual-save logic documented in §5 | Both exist independently; **not verified whether the exact save logic (MediaStore branch, filename convention) is identical between the two copies** — out of scope for this audit which owns only `taal-ui-kit`; a future cross-module audit should diff them directly |
| Auth / navigation drawer / other app chrome | Present (Splash, SignIn, Otp, drawer nav, etc.) | Absent — `taal-ui-kit` is UI-kit-only, no auth, no drawer; its own `nav_uikit.xml` graph starts directly at `recordingFragment` | Expected structural difference, not a gap |
| Robolectric/JVM test coverage | Not verified in this audit (out of scope — `app` is a separate module for another session to document) | 65 tests, 6 classes, 2 known-failing (see §10) | N/A — `app`'s test posture is unknown from this audit |

**Overall parity verdict**: the 2026-08 memory note that "`taal-ui-kit` reached near-parity with `app`" is **directionally still accurate** for the core record→save→browse loop and even extends to the CUSTOM/custom-bandpass filter (present in both) — but `app` retains three whole features `taal-ui-kit` deliberately lacks: **Equalizer**, **PCG Segmentation**, and **Room DB / patient linking**. These are structural, intentional differences (per the "build optional features behind one flag" and DB-free-SDK design), not accidental drift. A full line-by-line diff of the shared Recording/Player/Save code paths between the two modules was **not** performed in this audit (would require a dedicated session comparing both files in full) — treat the "at parity" claims above as scoped to the specific features actually greped/read, not an exhaustive statement.

## 12. Known Issues / Gotchas

- **Two currently-failing unit tests** on a clean checkout — see §10. Do not assume `testDebugUnitTest` is green; it currently reports `65 tests completed, 2 failed`.
- **`RecordingFragment.startRecording()` ignores `EXTRA_RECORDING_TIME_SECONDS`** — the activity accepts a custom `recordingTimeSeconds` in its `getIntent()` factory, but `RecordingFragment.kt:506` hard-codes `setRecordingTime(300)`. A host app passing a non-default value gets no effect. (`TaalRecorderActivity.kt:16-21` doc comment even shows `recordingTimeSeconds = 300` as an example, which may be why this was never caught — the default happens to match the hard-coded value.)
- **Filter-name parsing duplicated in two places** with a hard-coded list `["FULL_BODY","PREGNANCY","CUSTOM","LUNGS","BOWEL","HEART"]` — `SavedRecordingsFragment.extractFilterName()` (lines 100-104) and `SavedRecordingAdapter.extractFilter()` (lines 67-70). Adding a new `PreFilter` value requires updating both lists or filenames will silently fall back to `"HEART"`.
- **Two independent WAV-parsing code paths** for waveform display: `PlayerFragment.loadFullWaveform()` reads the entire file into memory (`file.readBytes()`), while `util/WavCropper.getWaveformData()` streams via `FileInputStream` with `skip()`. They are not unified; `PlayerFragment` does not call into `WavCropper` at all for its main waveform view.
- **Duration computed three different ways** across the module: `WavCropper.getDurationSeconds()` (`WavCropper.kt:21-27`), `SavedRecordingAdapter.getWavDuration()` (`SavedRecordingAdapter.kt:72-80`), and `PlayerFragment.loadFullWaveform()`'s inline `totalDurationSeconds` calc (`PlayerFragment.kt:135-137`) — all three do the same `(length-44)/2/44100` arithmetic independently rather than sharing one function.
- **`AudioTrack` deprecated constructor** — `RecordingFragment.kt:649` uses the deprecated 6-arg `AudioTrack(...)` constructor (flagged as a compiler warning during this audit's build run); newer code should use `AudioTrack.Builder`.
- **`finishWithResult()` and the `filterChip` view in `SaveRecordingFragment`** are both live-but-unused — see §9.
- **Leftover Equalizer string/color resources** (`strings.xml:20-27`, `colors.xml:32-35`) reference a screen that doesn't exist in this module — harmless but potentially confusing to a session searching for "where is EQ configured in taal-ui-kit."
- **`preAmpDb` state is NOT shared between Recording and Player screens** — each screen's slider defaults independently to 5dB and neither reads the other's value; adjusting pre-amp during recording has no bearing on the Player screen's playback pre-amp slider, and vice versa. This is likely intentional (recording pre-amp vs. playback pre-amp are conceptually different knobs) but worth knowing before "fixing" what might look like a shared-state bug.
- **`ampSlider`/`ampLabel` view IDs are reused identically across `fragment_recording.xml` and `fragment_player.xml`** — safe within view binding (each fragment gets its own generated binding class) but be careful with any future shared-style/theme refactor that might assume a single global slider.
- Empty `consumer-rules.pro` / `proguard-rules.pro` — no keep rules currently protect any of this module's reflection-sensitive code (if any exists) under R8/ProGuard minification in a consuming app that enables `isMinifyEnabled = true`.

## 13. Changelog

| Date | Change | Notes |
|---|---|---|
| 2026-09-08 | Initial SUPERMASTER_TAAL_UI_KIT.md created | Full source audit of all 13 `.kt` files under `src/main`, all 7 test files under `src/test`, `build.gradle.kts`, manifest, nav graph, and key layouts. Confirmed `TaalSaveDialog` still dead (repo-wide grep). Actually ran `./gradlew :taal-ui-kit:testDebugUnitTest` and found 2 of 65 tests failing (`RecordingViewModelTest.test_setPreAmp_aboveTwenty_coerced`, `TaalPlayerActivityTest.test_getIntent_withEmptyString`) — both are stale tests versus current source, not necessarily production bugs. Corrected a prior memory note: `lungs-app` does not depend on `:taal-ui-kit` (only a code comment mentioned the name; the real Gradle dependency is `:taal-core` only). Documented the previously-unrecorded CUSTOM/custom-bandpass filter feature in the Recording screen. |
