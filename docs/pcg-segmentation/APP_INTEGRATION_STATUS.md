# PCG Cardiac Segmentation — Integration Status

**Date:** 2026-08-15, updated 2026-08-24 (visualization layer added and wired live into the app —
§12; chart readability + full-screen landscape + PDF export + disclaimer placement — §13; report
screen redesign + zoom selection indicator — §14; "Analyze Heart Sounds" button repositioned to
the top bar — §15)
**Purpose of this file:** a complete, standalone record of what has been built, tested, and
verified for the PCG (heart-sound) segmentation feature in the TAAL SDK repo — written to be
pasted into a fresh Claude conversation as full context. If you are reading this in a new chat:
this file plus the repo itself should be everything you need to understand what exists, what
works, what was tried and reverted, and how to redo it correctly.

---

## 1. TL;DR — current state as of this file being written

| Layer | Status |
|---|---|
| SDK modules (`taal-segmentation-core`, `taal-segmentation`) | **Built, tested, working.** 38/38 ported unit tests pass (26 algorithm + 12 visualization, see §10). AAR builds clean, 374,446 bytes. |
| Bridge (`TaalCardiacSegmentation.kt`) | **Built, tested on real hardware, working.** Two real bugs found via live testing, both fixed and reverified. |
| Visualization (`PcgSegmentationView`, `PcgDisplay`/`PcgDisplayModel`, `SegmentationPalette`) | **Built, tested (38/38), wired live, and made readable at real cycle density.** Time axis, hairline waveform, stronger S1/S2 colour, min band width — see §10 and §13.1. |
| App UI integration (Record → Player → Analyze → Report+Chart+Zoom → Full-screen → PDF Download) | **LIVE in the app right now**, gated by a single flag (`SegmentationFeature.ENABLED`). Report screen redesigned with status card, stat tiles, and a Full/10s/5s zoom control with a visible selection indicator (§14); a separate full-screen landscape chart (§13.2) with the same zoom levels plus drag-to-pan; Download now produces a real PDF with the chart embedded, not `.txt` (§13.3). |
| Original app flow (Record/Player/Save/SavedRecordings) | **Unaffected when the flag is off.** With the flag on, the only change to existing screens is one button that appears solely on already-saved recordings. |

If you're picking this up fresh: go straight to **§13**, **§14**, and **§15** for the current live
state — they supersede everything before them. §6/§7 are a superseded first attempt (text-only, was
fully reverted) kept only for history; §12 is the version right before the readability/full-screen/
PDF/redesign work — don't follow its §12.6 "how to try it" steps literally (the download is a PDF
now, not `.txt`), the underlying screens and flag still work the same way.

**Porting this feature into a different project?** Don't use this file for that — it's a dated
history log of this repo, not a step-by-step guide. Use
`docs/pcg-segmentation/PORTING_GUIDE.md` instead: it's written to be handed to a fresh Claude Code
session with zero context on some *other* Android project and say "read this and implement it
here."

---

## 2. What "PCG Cardiac Segmentation" is

A third-party (PurnaCardio) Kotlin SDK, handed off as a folder called `PCG Cardiac Segmentation/`
at the repo root, that takes a recorded heart-sound (PCG) audio buffer and finds each heart sound
in it: S1, systole, S2, diastole, repeating. It does **not** do murmur classification, ECG, or any
diagnosis — it's a 4-state segmenter only. Full provenance, model card, and API docs are at
`docs/pcg-segmentation/{PROVENANCE,MODEL_CARD,API,AUDIO_CAPTURE,INTEGRATION}.md`.

- Model: `tcn_c200_cardiac_seg.onnx`, TCN "C-200", ~87K params, ONNX IR version 10 (confirmed by
  byte inspection), requires ONNX Runtime **≥ 1.18** (pinned to 1.19.2 — 1.17.x silently makes
  every capture report "no heart sounds" with no error, per upstream's own postmortem).
- Internally: resamples 44,100→2,000 Hz, extracts a 12-channel feature tensor at 200 Hz, runs the
  TCN, decodes with legal-transition + minimum-duration constraints.
- Source repo commit: `ce287c3391c631006e691e72d706671084e151e7`, extracted 2026-08-12.

---

## 3. SDK integration — modules, build, tests

### 3.1 Module layout (unchanged by the revert, still present)

```
taal-segmentation-core/            — plain kotlin("jvm") module, no Android
  src/main/kotlin/com/purnacardio/signal/pcg/
    CardiacSegmenter.kt
    PcgFeatureExtractor.kt
    Pcm.kt
    S1Result.kt
    SegmentationResult.kt
  src/main/kotlin/com/purnacardio/signal/pcg/viz/
    PcgDisplayModel.kt              — chart geometry, normalised 0..1 coords (see §10)
    SegmentationPalette.kt          — the four state colours, light/dark (see §10)
  src/test/kotlin/com/purnacardio/signal/pcg/
    ConstrainedDecodeTest.kt        (5 tests)
    PcgFeatureExtractorTest.kt      (7 tests)
    PcgNormalisationTest.kt         (5 tests)
    PcmTest.kt                      (6 tests)
    SegmentWithLogitsTest.kt        (3 tests)
  src/test/kotlin/com/purnacardio/signal/pcg/viz/
    PcgDisplayModelTest.kt          (12 tests, see §10)

taal-segmentation/                 — Android library (minSdk 26)
  build.gradle.kts
  consumer-rules.pro
  src/main/kotlin/com/purnacardio/signal/pcg/android/
    TcnSegmenterRunner.kt           — ported from handoff, untouched
    PcgSegmentationView.kt          — chart View, see §10
  src/main/kotlin/com/musediagnostics/taal/segmentation/
    TaalCardiacSegmentation.kt      — THE NEW BRIDGE FILE (see §4)
  src/main/assets/
    tcn_c200_cardiac_seg.onnx       (407,085 bytes)
  src/androidTest/kotlin/com/musediagnostics/taal/segmentation/
    TaalCardiacSegmentationSmokeTest.kt   — real-device instrumented test (see §5.2)
  src/androidTest/assets/
    synthetic_pcg_raw.wav           — synthetic 75bpm test fixture, 20s/44100Hz/mono/16-bit

docs/pcg-segmentation/              — reference docs copied from the handoff, + this file
  (includes VISUALISATION.md and PcgChartCompose.kt as of the 2026-08-24 update, see §10)
```

### 3.2 Gradle wiring (unchanged by the revert, still present)

- `settings.gradle.kts`: `include(":taal-segmentation-core")`, `include(":taal-segmentation")`
- Root `build.gradle.kts`: added `id("org.jetbrains.kotlin.jvm") version "1.9.20" apply false`
  (wasn't present before; needed for the plain-JVM core module)
- `taal-segmentation-core/build.gradle.kts`: plain `kotlin("jvm")`, `jvmToolchain(17)`,
  JUnit 5 (`useJUnitPlatform()`). **No `repositories {}` block** — this repo's
  `settings.gradle.kts` uses `FAIL_ON_PROJECT_REPOS`, so per-module repo declarations are
  forbidden; it resolves through the root's centrally-declared `mavenCentral()`.
- `taal-segmentation/build.gradle.kts`: Android library, `minSdk = 26`,
  `compileOptions`/`kotlinOptions` target **Java 17** (this repo's other modules target Java 8 —
  a deliberate, documented mismatch, not an oversight — Android's D8 dexes both down fine).
  Dependencies: `api(project(":taal-segmentation-core"))`,
  `api("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")`,
  `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")` (needed by the
  bridge's `Dispatchers.Default` dispatch), plus `androidx.test.ext:junit:1.1.5` /
  `androidx.test:runner:1.5.2` as `androidTestImplementation` for the on-device smoke test.

### 3.3 Test results

```
./gradlew :taal-segmentation-core:test
```
**38/38 passing, 0 failures** (as of the 2026-08-24 visualization update — was 26/26 before).
Breakdown: ConstrainedDecodeTest 5, PcgFeatureExtractorTest 7, PcgNormalisationTest 5, PcmTest 6,
SegmentWithLogitsTest 3, **PcgDisplayModelTest 12** (new, see §10). Test names are English
sentences pinning specific behavior (e.g. *"a single knock does not get to scale the whole
recording"*, *"a heart sound survives being squeezed into few columns"*) — see the test source
files for the full list.

```
./gradlew :taal-segmentation:assembleRelease
```
AAR builds clean, **373,095 bytes** as of the 2026-08-24 update (was 334,799 bytes before —
the increase is `PcgSegmentationView`'s compiled class), contains `assets/tcn_c200_cardiac_seg.onnx`
and `proguard.txt`.

**taal-core and taal-ui-kit are untouched** — verified byte-identical AAR hashes against a
pre-integration baseline (saved outside the repo) after every phase of this work. Nothing in this
integration ever modified existing TAAL SDK source.

**One pre-existing, unrelated issue**: `./gradlew build` (the full-project strict check, not the
normal build) fails at `:taal-core:lintDebug` — 3 `MissingPermission` lint errors in
`TaalAudioCapture.kt` (missing explicit permission-check annotations around `AudioRecord(...)`
calls) plus 6 stale-dependency-version warnings. This is 100% pre-existing — confirmed via
byte-identical `taal-core` AAR hash — and simply never surfaced before because nobody had run
plain `./gradlew build` on this repo; only per-module `assembleRelease` was ever used. **Does not
block building, testing, or running the app** — only that one strict lint-everything command.

---

## 4. The bridge: `TaalCardiacSegmentation`

**File**: `taal-segmentation/src/main/kotlin/com/musediagnostics/taal/segmentation/TaalCardiacSegmentation.kt`
(package `com.musediagnostics.taal.segmentation`, matching this repo's `com.musediagnostics.taal.*`
convention — the ported PurnaCardio code keeps its own `com.purnacardio.signal.pcg` package,
deliberately unchanged from upstream).

This is the **only file in the repo that references both** the TAAL audio contract and the
PurnaCardio segmenter. It does **not** depend on `taal-core` — it takes primitive types
(`ShortArray`/`FloatArray`/`File`) so the caller (app code) is responsible for getting audio out of
`TaalRecorder` and into this class; `taal-segmentation`'s own `build.gradle.kts` only depends on
`taal-segmentation-core` and ONNX Runtime.

### 4.1 Public API

```kotlin
class TaalCardiacSegmentation(context: Context) : Closeable {
    suspend fun segmentRawWav(rawWavFile: File, verboseLogging: Boolean = false): SegmentationOutcome
    suspend fun segment(pcm16: ShortArray, sampleRate: Int, channelCount: Int = 1, verboseLogging: Boolean = false): SegmentationOutcome
    suspend fun segment(pcg: FloatArray, sampleRate: Int, channelCount: Int = 1, verboseLogging: Boolean = false): SegmentationOutcome
    override fun close()
}

sealed interface SegmentationOutcome {
    data class Ok(val result: SegmentationResult) : SegmentationOutcome
    data class TooWeak(val result: SegmentationResult) : SegmentationOutcome
    data object NoHeartSounds : SegmentationOutcome
    data object Unavailable : SegmentationOutcome
}

// Extension properties/functions on SegmentationResult:
val SegmentationResult.heartRateBpm: Double?
val SegmentationResult.systolicIntervalsMs: List<Double>
val SegmentationResult.beatTimesSec: List<Double>
fun SegmentationResult.stateAt(tSeconds: Double): Int?
```

Full source is in the file itself (357 lines) — read it directly rather than relying on a stale
copy here. Key design decisions, all deliberate:

1. **Feed it the raw `.wav`, never `_filtered.wav`.** The segmenter band-passes 20–200 Hz
   internally; TAAL's `_filtered.wav` has already been through `AudioFilterEngine` (bandpass + EQ +
   pre-amp). Double-filtering shifts the input away from the model's training distribution. If a
   caller passes a `_filtered` file to `segmentRawWav`, it logs a warning but still processes it —
   doesn't hard-fail.
2. **No resampling by this code.** `PcgFeatureExtractor.resample()` inside the ported library does
   44,100→2,000 Hz itself. This bridge reads the *true* sample rate from the WAV header (own
   RIFF-chunk scanner, not a hardcoded-44-byte-offset assumption) and passes audio at native rate.
3. **Clipping is checked, level is not.** The extractor normalizes level internally (raw 16-bit and
   `[-1,1]` floats produce identical features — pinned by the ported `PcmTest`). Only clipping
   (>0.1% of samples at full scale) gets a warning logged.
4. **`SegmentationOutcome` instead of nullable.** `null` from the underlying library is a genuine
   clinical result ("no heart sounds"), not an error — modeled as `NoHeartSounds`. `TooWeak` gates
   on `numCycles < durationSec / 2` (never on `confidencePerCycle`, which the library always
   leaves empty).
5. **One `TcnSegmenterRunner`, lazily built, wrapped in `runCatching{}.getOrNull()`.** ~100ms
   construction cost, never rebuilt per call. Not thread-safe internally, so every public method
   here serializes through a `Mutex` and dispatches onto `Dispatchers.Default` itself — callers
   never need their own dispatcher hop.

### 4.2 Bugs found via live testing (both fixed, in the current file)

**Bug 1 — `systolicIntervalsMs` could go negative.** Original implementation paired
`s1PeakSamples2k[i]` with `s2PeakSamples2k[i]` by index. If a recording starts mid-systole, the
first detected S2 belongs to a cycle whose S1 happened *before* recording started — pairing it with
this recording's first S1 produces a negative "interval." Caught live: a real 20s recording
produced **"Avg systolic interval: -454.0 ms"**. Fixed by dropping any S2 peak at or before the
first S1 peak before pairing index-wise. Reverified on the same class of input:
**"Avg systolic interval: 285.0 ms"** — correct.

**Bug 2 — this was in the app-layer gating code, not the bridge itself** — see §6.4.

---

## 5. On-device verification actually performed

### 5.1 Synthetic-signal instrumented test (`TaalCardiacSegmentationSmokeTest`)

Still present at `taal-segmentation/src/androidTest/kotlin/.../TaalCardiacSegmentationSmokeTest.kt`.
Copies `src/androidTest/assets/synthetic_pcg_raw.wav` (a synthetic 20s/44.1kHz/mono signal with
paired low-frequency bursts generated to mimic 75bpm — not a real heartbeat, built with a small
Python script since no real TAAL recording existed at the time) to a temp file, runs it through
`TaalCardiacSegmentation.segmentRawWav()`, asserts the outcome isn't `Unavailable` (i.e. ONNX
Runtime actually loaded on real hardware), and logs the rest.

Run with:
```
./gradlew :taal-segmentation:connectedDebugAndroidTest
```

**Actual result on a real device** (Samsung SM-A065F, API 36, arm64-v8a):
```
capture: sampleRate=44100 channels=1 durationSec=20.00 peakDbfs=-7.2 rmsDbfs=-26.5 clipped=0.000%
outcome=Ok numCycles=24 durationSec=20.00
Ok: numCycles=24 durationSec=20.0 heartRateBpm=74.99999999999997
```
The synthetic signal was built for exactly 75 bpm; the model recovered **74.9997 bpm** — the
whole chain (WAV parsing → resample → feature extraction → TCN inference → constrained decode →
cycle/HR calculation) is correct, not just "doesn't crash."

*(Note: one Kotlin gotcha hit and fixed along the way — the `@Test` method originally had an
expression body `= runBlocking { ... }`, which made its inferred return type non-`Unit`; JUnit4
requires `@Test` methods to return `void`. Fixed by declaring `(): Unit = runBlocking { ... }`.)*

### 5.2 Real hardware, real recording, through the actual app UI

This is the app-integration work described in full in §6. Recorded through the real TAAL Recorder
screen with a real connected TAAL stethoscope device (confirmed connected — earlier "TAAL device
not connected" tests correctly failed before it was plugged in, later tests correctly succeeded
once real hardware was attached and a real ~20s capture was taken).

**Real captured recording, saved as "Kunal test" in the app's Saved Recordings** (this recording
still exists on the test device in `filesDir/saved/HEART_Kunal test_{filtered,raw}.wav` — it was
**not** deleted, since it's real user-generated data, not something this session created):
```
Result: OK (trustworthy segmentation)
Duration: 20.6s
Cardiac cycles detected: 24
Heart rate: 67.3 bpm
```
Downloaded report confirmed present at `/sdcard/Download/taal_segmentation_report_1786752425004.txt`
(296 bytes) — pulled and read directly to confirm content, not just that a toast appeared.

A second synthetic verification (pushed a copy of the same `synthetic_pcg_raw.wav` fixture into
`filesDir/saved/` as `HEART_verify_test_{filtered,raw}.wav`, opened via the real Saved Recordings
UI, tapped the real "Analyze Heart Sounds" button) reproduced the expected 75.0 bpm / 24 cycles /
20.0s result and confirmed the systolic-interval bug fix (285.0 ms, not negative). This test
fixture pair was deleted after verification (it was synthetic, created solely for this test).

---

## 6. App UI integration — built, verified, then reverted

### 6.1 What was built

A full Record → Save → Analyze → Report → Download flow, reusing the app's **real** existing
screens end to end (not a bespoke test screen — an earlier bespoke-recorder version was tried
first and explicitly rejected in favor of this):

1. User records normally on the real **RecordingFragment** (full waveform, filter chips, pre-amp
   slider, BPM — completely unmodified).
2. Stop → real **PlayerFragment** (Review Recording) → Save → real **SaveRecordingFragment** →
   file lands in `filesDir/saved/{NAME}_filtered.wav` + `{NAME}_raw.wav`, exactly as it always has.
3. From **SavedRecordingsFragment** (the library), open that recording → back in PlayerFragment,
   now showing **one new button**, "Analyze Heart Sounds" — added only for recordings confirmed to
   live inside `filesDir/saved/` (see §6.4 for why "confirmed" mattered).
4. Tapping it opens a new **SegmentationReportFragment**, which immediately runs
   `TaalCardiacSegmentation.segmentRawWav()` on the saved `_raw.wav` companion file, shows a
   plain-text report, and has a "Download Report" button that saves a `.txt` file to the device's
   real Downloads folder (MediaStore Downloads collection on API 29+, direct file write on 24–28 —
   mirrors the exact pattern `SaveRecordingFragment` already uses for audio files).

### 6.2 Files this created (all new, no existing-file conflict)

```
app/src/main/java/com/musediagnostics/taal/app/ui/segmentation/SegmentationReportFragment.kt
app/src/main/res/layout/fragment_segmentation_report.xml
```

*(An earlier iteration also created `HeartSegmentationTestFragment.kt` +
`fragment_heart_segmentation_test.xml` — a bespoke recorder screen with its own Record button,
reached by temporarily hijacking the nav graph's `startDestination`. This was explicitly replaced
by the flow above per direct feedback — "I want that in that screen we have the full taal UI flow,
and the saved file... after report making" — and those two files were deleted. If you see any
reference to `HeartSegmentationTestFragment` anywhere, it no longer exists and should not be
recreated; use the pattern in this section instead.)*

### 6.3 Existing files this touched (and has now been reverted — see §7)

| File | Change |
|---|---|
| `app/src/main/res/layout/fragment_player.xml` | Added one `Button` (`analyzeButton`), default `visibility="gone"` |
| `app/src/main/java/.../player/PlayerFragment.kt` | Added ~15 lines in `onViewCreated()`: compute whether `filePath` is inside `filesDir/saved/`, and if a matching `_raw.wav` exists, show the button and wire its click to navigate to the report screen |
| `app/src/main/res/navigation/nav_graph.xml` | Added the `segmentationReportFragment` destination (with a `rawFilePath` string argument) and one new `<action>` from `playerFragment` |
| `app/build.gradle.kts` | `implementation(project(":taal-segmentation"))` — one line, opt-in |

Exact diffs are reproduced in §6.5 for reapplication.

### 6.4 Bugs found via live testing (app-layer, now moot since reverted, but documented for reapplication)

**Bug — button showed on an unsaved, temp recording.** First gating attempt used the app's
`isNewRecording` bundle flag (`if (!isNewRecording && ...)`). Live testing found this flag isn't
reliably set: `RecordingFragment`'s `playPauseButton` click handler navigates to `PlayerFragment`
**without** setting `isNewRecording` at all, so it silently defaults to `false` — making an
in-progress temp recording indistinguishable from a genuinely saved one by that flag alone. Fixed
by checking the file's actual parent directory equals `File(filesDir, "saved")`, not trusting the
flag:
```kotlin
val savedDir = File(requireContext().filesDir, "saved")
val isInSavedDir = File(filePath).parentFile?.absolutePath == savedDir.absolutePath
```
This is the authoritative, reapplication-safe check — **use this pattern, not the `isNewRecording`
flag**, if this feature is rebuilt.

### 6.5 Exact recipe to reapply this (tested working, then reverted)

**Step 1** — `app/build.gradle.kts`, in the `dependencies {}` block, right after
`implementation(project(":taal-core"))`:
```kotlin
implementation(project(":taal-segmentation"))
```

**Step 2** — `app/src/main/res/layout/fragment_player.xml`, immediately before the closing
`</androidx.constraintlayout.widget.ConstraintLayout>`, after the existing `saveDiscardBar`
`LinearLayout`:
```xml
<!-- Shown only for an already-saved recording, since that's when a saved _raw.wav
     companion is guaranteed to exist on disk to analyse. -->
<Button
    android:id="@+id/analyzeButton"
    android:layout_width="match_parent"
    android:layout_height="48dp"
    android:layout_marginHorizontal="24dp"
    android:layout_marginBottom="24dp"
    android:background="@drawable/bg_button_teal"
    android:text="Analyze Heart Sounds"
    android:textAllCaps="false"
    android:textColor="@color/white"
    android:textSize="15sp"
    android:textStyle="bold"
    android:visibility="gone"
    app:layout_constraintBottom_toBottomOf="parent" />
```

**Step 3** — `app/src/main/java/com/musediagnostics/taal/app/ui/player/PlayerFragment.kt`, in
`onViewCreated()`, right after the `binding.saveDiscardBar.visibility = ...` line:
```kotlin
// Only offered for a file actually inside filesDir/saved/ — checked by directory, not the
// isNewRecording flag: some existing callers (e.g. RecordingFragment's playPauseButton)
// navigate here without setting isNewRecording, which would otherwise make an in-progress
// temp recording look identical to a saved one.
val savedDir = File(requireContext().filesDir, "saved")
val isInSavedDir = File(filePath).parentFile?.absolutePath == savedDir.absolutePath
if (isInSavedDir && filePath.contains("_filtered.wav")) {
    val savedRawPath = filePath.replace("_filtered.wav", "_raw.wav")
    if (File(savedRawPath).exists()) {
        binding.analyzeButton.visibility = View.VISIBLE
        binding.analyzeButton.setOnClickListener {
            val bundle = Bundle().apply { putString("rawFilePath", savedRawPath) }
            findNavController().navigate(R.id.action_player_to_segmentationReport, bundle)
        }
    }
}
```
(`java.io.File` is already imported in this file.)

**Step 4** — `app/src/main/res/navigation/nav_graph.xml`: add one `<action>` inside the
`playerFragment` `<fragment>` block (anywhere among its existing actions):
```xml
<action
    android:id="@+id/action_player_to_segmentationReport"
    app:destination="@id/segmentationReportFragment" />
```
and add a new top-level `<fragment>` destination (placed near `saveRecordingFragment` /
`savedRecordingsFragment` for readability, order doesn't matter functionally):
```xml
<!-- Heart segmentation report — reached from PlayerFragment's "Analyze Heart Sounds" button
     on an already-saved recording; runs TaalCardiacSegmentation on the saved raw file and
     offers a downloadable report. -->
<fragment
    android:id="@+id/segmentationReportFragment"
    android:name="com.musediagnostics.taal.app.ui.segmentation.SegmentationReportFragment"
    android:label="Segmentation Report"
    tools:layout="@layout/fragment_segmentation_report">
    <argument
        android:name="rawFilePath"
        android:defaultValue=""
        app:argType="string" />
</fragment>
```

**Step 5** — recreate `app/src/main/res/layout/fragment_segmentation_report.xml` and
`app/src/main/java/com/musediagnostics/taal/app/ui/segmentation/SegmentationReportFragment.kt`.
Both are new files with no dependency on anything reverted — if they were deleted as part of the
revert in §7, their last-known-working full source should be recovered from this session's git
history / conversation log; the layout is a simple `ScrollView` with a status `TextView`,
`ProgressBar`, monospace `resultText` `TextView`, and a `downloadButton`. The fragment: reads
`rawFilePath` from `arguments`, constructs `TaalCardiacSegmentation(requireContext().applicationContext)`
in `onViewCreated`, immediately launches a coroutine calling `segmentRawWav(File(rawFilePath), verboseLogging = true)`,
builds a plain-text report from the `SegmentationOutcome` (branch on `Ok`/`TooWeak`/`NoHeartSounds`/`Unavailable`),
and on "Download Report" writes it via `MediaStore.Downloads` (API 29+) or a direct file write to
`Environment.DIRECTORY_DOWNLOADS` (API 24–28) — same pattern as `SaveRecordingFragment`'s
audio-file MediaStore code (`ContentValues` + `IS_PENDING` dance). Closes the segmenter in
`onDestroyView()`.

**After reapplying**: rebuild (`./gradlew :app:assembleDebug`), reinstall, and retest via the
Saved Recordings → open a recording → Analyze Heart Sounds path. Do not reintroduce the
`isNewRecording`-based gate (§6.4) — use the `filesDir/saved/` directory check.

---

## 7. What was reverted, and confirmation the original app is unaffected

At the end of this session, the app-layer integration (§6.2, §6.3) was fully removed per explicit
request, to bring the app back to its exact original behavior:

- Deleted: `SegmentationReportFragment.kt`, `fragment_segmentation_report.xml`
- Reverted: `fragment_player.xml`, `PlayerFragment.kt`, `nav_graph.xml`, `app/build.gradle.kts`
  back to their pre-§6 state (no `analyzeButton`, no segmentation-report destination, no
  `taal-segmentation` dependency in `:app`)

**Not reverted / not touched by this revert**: `taal-segmentation-core/`, `taal-segmentation/`
(including the bridge, the on-device smoke test, and the synthetic test fixture), and all of
`docs/pcg-segmentation/` including this file. Those remain exactly as described in §3–§5 — built,
tested, working, just not currently wired into the `:app` module.

**Verification performed after reverting**: `./gradlew :app:assembleDebug` builds clean; app
installed and launched on the same real device; confirmed the app opens directly to the real
Recording screen with no trace of the segmentation UI, and the normal Record → Player →
Save → Saved Recordings flow behaves identically to before this work began.

---

## 8. Known open items / things to be aware of

1. **`app`'s `minSdk` is now 26** (was 24) — this was a deliberate, explicitly-approved decision
   (Option A from three presented) to satisfy `taal-segmentation`'s `minSdk = 26` floor
   (ONNX Runtime / the ported library's own requirement). `taal-core`, `taal-ui-kit`, `lungs-app`,
   `visualizertaal-app` remain at `minSdk 24`, untouched. This line was **not** reverted in §7 —
   it stays at 26 regardless of whether the app wires in `taal-segmentation`, since lowering it
   back was never asked for and doing so silently would be an unrelated compatibility regression
   to make without being asked. Flag this explicitly if picking the work back up.
2. **`./gradlew build` fails on a pre-existing, unrelated `taal-core` lint issue** — see §3.3.
   Not something this work introduced or should fix without separate explicit sign-off (would
   require editing `TaalAudioCapture.kt`, an existing TAAL source file).
3. **`:app`'s release build has R8/`isMinifyEnabled` off** — pre-existing project convention, not
   changed by this work. If `taal-segmentation` is wired back in, an R8-enabled release build has
   not yet been verified (though `consumer-rules.pro` ships inside the AAR specifically to make
   that safe).
4. **Kotlin 1.9.20 (this repo) vs. the handoff's stated floor of 1.9.24** — repo is slightly older
   than what the handoff docs discuss (they only discuss going *newer*, not older). All 26 tests
   pass on 1.9.20 as things stand, so this is a "verified working, not verified why," not a known
   problem.
5. There is a real saved recording on the test device named **"Kunal test"** in Saved Recordings —
   genuine data from live testing during this session, not synthetic, not something to delete
   without asking.

---

## 10. Visualization layer — added 2026-08-24

A second update package (`newsegmentation_update/`, structured identically to the original
handoff) was applied on top of everything in §2–§9. **It changes nothing about the algorithm** —
every algorithm file (`CardiacSegmenter.kt`, `PcgFeatureExtractor.kt`, `Pcm.kt`, `S1Result.kt`,
`SegmentationResult.kt`), every existing test, the model file (sha256 `e0cab786...9cde9`,
unchanged), and `TcnSegmenterRunner.kt` were diffed byte-for-byte against what was already
integrated and are **identical**. Verified with `diff`, not assumed. Only new, additive files
were added, plus doc updates.

### 10.1 What was added

```
taal-segmentation-core/src/main/kotlin/com/purnacardio/signal/pcg/viz/
  PcgDisplayModel.kt      — PcgDisplay.build(audio, sampleRate, result, columns, ...) -> PcgDisplayModel
  SegmentationPalette.kt  — light/dark ARGB palettes, legend entries, per-state colours

taal-segmentation-core/src/test/kotlin/com/purnacardio/signal/pcg/viz/
  PcgDisplayModelTest.kt  — 12 tests (see below)

taal-segmentation/src/main/kotlin/com/purnacardio/signal/pcg/android/
  PcgSegmentationView.kt  — plain android.view.View that draws the chart

docs/pcg-segmentation/
  VISUALISATION.md        — new, full guide
  PcgChartCompose.kt      — new, Compose sample (reference only, not compiled — same treatment
                            as the existing SegmentationExample.kt)
  README.md, PROVENANCE.md, INTEGRATION.md, SHA256SUMS.txt — updated to describe the viz layer
```

No `build.gradle.kts` changes were needed anywhere — `PcgSegmentationView` uses only
`android.graphics`/`android.view`, no new dependency, and the Compose sample is reference-only
(not part of the compiled module, so it doesn't pull in a Compose dependency either).

### 10.2 The public API

```kotlin
// The whole integration, from a layout:
// <com.purnacardio.signal.pcg.android.PcgSegmentationView
//     android:id="@+id/pcgChart"
//     android:layout_width="match_parent"
//     android:layout_height="200dp" />

chart.setRecording(audio, sampleRate = 44_100, result = result)   // that's it
```

Also available: `setDisplayModel(model)` (hand over a `PcgDisplayModel` built off the main thread
— for long recordings), `setWindowSeconds(startSec, endSec)` (zoom/scrub, redraws from the
original audio so it reveals real detail rather than stretching pixels), `displayModel()` (read
back `clippedFraction` as a capture-quality hint), `palette` (defaults to system light/dark),
`showLegend` / `showSecondGrid`.

**Pass the same `audio` array you passed to `segment`.** The overlay is aligned by time; a
resampled or trimmed copy shifts the bands relative to the waveform while still looking plausible.

### 10.3 Design points worth knowing before touching this code

- **All geometry lives in `core` as normalised 0..1 coordinates**, reduced to fills in the View.
  This is why it's unit-testable on the JVM (`PcgDisplayModelTest`, 12 tests, no device needed)
  and why a future Compose canvas or SVG/PDF export can't visually drift from what the View draws
  — they'd all read the same `PcgDisplayModel`.
- **Min/max decimation per column** — a 20s/44.1kHz recording is ~880,000 samples against ~1,000
  pixels; naive every-Nth-sample downsampling throws away peaks, and in a PCG the peaks *are* S1
  and S2. Each column keeps the true min and max of the samples inside it.
- **99th-percentile amplitude scaling**, not peak scaling — one knock/bump would otherwise flatten
  the whole heartbeat to a hairline. `clippedFraction` on the resulting model reports how much got
  clipped by this choice, which doubles as a "recording was bumped" capture-quality hint.
- **Three timebases are reconciled in one place**: the audio's own sample rate, the segmenter's
  200 Hz state-label frames, and the 2 kHz sample indices its S1/S2 peak markers use. Mixing these
  up shifts the bands relative to the waveform by a constant factor — which reads to a reviewer as
  "the model is inaccurate," not as a display bug.
- **Colours are deliberately desaturated, no red** (red carries an alarm meaning that's wrong for
  a normal S1). S1 = deep blue, S2 = teal-green (stronger washes, since they're events); systole =
  warm ochre (weaker wash, but the warmest of the two intervals — it's where a murmur lives),
  diastole = near-neutral slate. S1/S2 bands also get a 2dp accent rule along their top edge so
  they stay distinguishable in greyscale, on a washed-out projector, or for a colour-deficient
  reader — colour is never the only cue. The legend is on by default; per `SegmentationPalette`'s
  own doc comment, "four washes with no key is a decorative background, not a reading."

### 10.4 Verification performed

```
./gradlew :taal-segmentation-core:test
```
**38/38 passing** (confirmed via the JUnit XML reports, not just console output) — the 26 from
before plus `PcgDisplayModelTest`'s 12, covering: band placement matches labels, bands tile the
window without gaps, S1/S2 markers convert from 2kHz sample indices to the right on-screen
position, windowing/zoom rescales correctly, a single-sample transient survives decimation down to
8 columns, one loud knock doesn't flatten the rest of the recording, silence draws a flat line
instead of NaN, y-axis direction is correct (positive sample = upper half), every column resolves
to a real sample even zoomed in past 1 sample/column, and both palette variants + the legend
expose all 4 states correctly.

```
./gradlew :taal-segmentation:assembleRelease
```
AAR builds clean, grew from 334,799 → **373,095 bytes** (the added `PcgSegmentationView` class;
still just the one `.onnx` asset).

`taal-core` / `taal-ui-kit` reconfirmed byte-identical to the original baseline after this update
(same hashes as in §3.3 and the original Phase 0 baseline) — this update touched neither.
`:app:assembleDebug` still builds clean (the app doesn't depend on `taal-segmentation` right now,
per the §7 revert, so this was expected, but checked anyway).

### 10.5 The chart is now wired into the app — see §12

As of 2026-08-24 (later the same day), the app-level integration described in §6 was re-applied
**and enhanced** to actually show `PcgSegmentationView`, not just text. §6/§7 below are kept as a
historical record of the first attempt (text-only, later reverted); §12 describes the current live
state, which supersedes them. `newsegmentation_update/` has since been deleted — everything in it
was fully copied out before removal.

---

## 11. Build & verify commands (copy-paste reference)

```bash
# SDK module tests (should always pass, independent of app wiring)
./gradlew :taal-segmentation-core:test

# SDK module AAR build
./gradlew :taal-segmentation:assembleRelease

# On-device smoke test (needs a connected device/emulator, API 26+)
./gradlew :taal-segmentation:connectedDebugAndroidTest

# Confirm taal-core / taal-ui-kit are untouched
./gradlew :taal-core:assembleRelease :taal-ui-kit:assembleRelease
# compare AAR sha256 against a pre-integration baseline

# App build (works whether or not taal-segmentation is wired in per §6.5)
./gradlew :app:assembleDebug
```

---

## 12. App UI integration v2 — live, with the chart, flag-gated (2026-08-24)

**This section describes the current state of the app.** §6/§7 describe an earlier attempt
(text-report only) that was built, verified, then fully reverted; this section describes what
replaced it — same idea, now showing `PcgSegmentationView`, and built to be hidden with one flag
flip instead of a multi-file revert.

### 12.1 Important discovery made while re-applying this

`nav_graph.xml` was found to already have **unrelated, in-progress work** sitting in it:

- `app:startDestination` was set to `@id/fullTimeOnRecordingFragment`, not the original
  `@id/recordingFragment`, marked `<!-- TEMP for dev testing — revert to @id/recordingFragment
  before shipping -->`.
- A parallel `FullTimeOnRecordingFragment` / `FullTimeOnPlayerFragment` pair (dated 2026-08-20,
  described in its own comment as "a clone of the Calibrated screens... currently identical
  behavior, own destinations so it can evolve independently").
- A separate `Calibrated*` screen set (`CalibratedRecordingFragment`, `CalibratedPlayerFragment`,
  `DpiCalibrationFragment`), referencing `docs/notes/WAVEFORM_GRAPH_AND_GRID_REFERENCE.md`.

None of this is part of the segmentation work — it's a different, concurrent effort (possibly a
different session or IDE). **This was not touched.** Explicitly asked, and told: wire the
segmentation feature into the original `PlayerFragment` only, and do not touch
`FullTimeOnPlayerFragment` or the `startDestination` flag.

**Practical consequence**: because `startDestination` currently points at
`fullTimeOnRecordingFragment`, opening the app fresh right now does **not** land you on the
screen with the segmentation feature. To reach it: navigate to `recordingFragment` some other way
(it's still a valid destination, just not the start one), or temporarily flip `startDestination`
yourself the same way the other work already does, test, then flip it back — do **not** leave it
pointed at `recordingFragment`, since that would silently revert someone else's in-progress setup.
Verification for this section was done exactly that way: flip → build → install → test → flip
back to `fullTimeOnRecordingFragment` with the exact original comment restored — confirmed via
`git diff` showing zero net change on that specific line afterward.

### 12.2 The "hide it" mechanism

One new file, one flag:

```kotlin
// app/src/main/java/com/musediagnostics/taal/app/ui/segmentation/SegmentationFeature.kt
object SegmentationFeature {
    const val ENABLED = true
}
```

`PlayerFragment` checks `SegmentationFeature.ENABLED` before ever computing whether to show the
"Analyze Heart Sounds" button. Flip it to `false` and the button never appears — no other file
needs to change or be reverted. This replaces the "revert 4 files" approach from §6.5; that recipe
still works if a full removal (not just hiding) is ever wanted, but for day-to-day toggling, use
this flag.

### 12.3 What's different from §6 — the chart

`SegmentationReportFragment` (recreated) now:
1. Reads `rawFilePath` from nav args, same as before.
2. **Independently reads the WAV file into a `FloatArray` + true sample rate** (a small private
   `readWavAsFloatArray()` in the same file — simple 44-byte-header read, mirrors the same pattern
   `PlayerFragment.loadFullWaveform()` already uses elsewhere in this app). This is separate from
   `TaalCardiacSegmentation`'s own internal WAV parsing (which stays private) — done this way
   specifically so the tested bridge module didn't need to change just to expose audio for display.
3. Runs `segmenter.segmentRawWav(rawFile, verboseLogging = true)` as before.
4. On `Ok` or `TooWeak` (i.e. whenever there's a `SegmentationResult` to show), calls
   `binding.pcgChart.setRecording(audio, sampleRate, result)` and makes the chart visible. On
   `NoHeartSounds` / `Unavailable`, the chart stays hidden — there's nothing to plot.
5. Text report and Download button behavior are otherwise unchanged from §6.

`fragment_segmentation_report.xml` gained one view:
```xml
<com.purnacardio.signal.pcg.android.PcgSegmentationView
    android:id="@+id/pcgChart"
    android:layout_width="match_parent"
    android:layout_height="220dp"
    android:layout_marginTop="16dp"
    android:visibility="gone" />
```
placed between the progress bar and the text report, `visibility="gone"` by default, set to
`VISIBLE` only when there's a result to draw.

### 12.4 Files touched (current, live state)

| File | Change |
|---|---|
| `app/build.gradle.kts` | `implementation(project(":taal-segmentation"))` — re-added |
| `app/src/main/res/layout/fragment_player.xml` | `analyzeButton`, same as §6.5 |
| `app/src/main/java/.../player/PlayerFragment.kt` | Same gating block as §6.4/§6.5, now also checking `SegmentationFeature.ENABLED` first |
| `app/src/main/res/navigation/nav_graph.xml` | `segmentationReportFragment` destination + `action_player_to_segmentationReport` — **`startDestination` line untouched**, confirmed via diff |
| `app/src/main/java/.../ui/segmentation/SegmentationFeature.kt` | **New** — the one-flag toggle |
| `app/src/main/java/.../ui/segmentation/SegmentationReportFragment.kt` | Recreated, now reads audio for the chart (§12.3) |
| `app/src/main/res/layout/fragment_segmentation_report.xml` | Recreated, now includes `pcgChart` |

### 12.5 Live device verification performed

Built, installed, and tested on a real device (different device from §5's — this one required
dismissing a debug-only "16 KB page-size alignment" compatibility notice for
`libonnxruntime.so`/`libonnxruntime4j_jni.so`, informational only on a 4KB-page device, not a
crash or a functional problem). Full path exercised:

Saved Recordings (synthetic `HEART_verify_test_{filtered,raw}.wav` fixture, pushed for this test
and deleted afterward — same fixture/approach as §5.2's second verification) → tap play → Player
screen shows **"Analyze Heart Sounds"** → tap it → Segmentation Report screen shows:
- The chart: real waveform trace, colored state bands, S1/S2 triangle markers, 4-item legend —
  confirmed rendering, not just "no crash"
- Text report: `Result: OK`, `Duration: 20.0s`, `Cardiac cycles detected: 24`,
  `Heart rate: 75.0 bpm`, `Avg systolic interval: 285.0 ms` — same correct numbers as §6's earlier
  verification (the negative-interval bug from §4.2 stayed fixed)
- Tapped **Download Report** → toast confirmed `"Report saved to Downloads"`

No crashes, no exceptions in logcat, chart genuinely draws the segmentation (not a placeholder).

### 12.6 To actually try it yourself

1. If you want it reachable by just opening the app: temporarily edit `nav_graph.xml`'s
   `app:startDestination` to `@id/recordingFragment`, build, install. **Remember to flip it back**
   to whatever the other in-progress work has it set to before you're done — check `git diff` on
   that one line before and after.
2. Record normally (real Recording screen — filter chips, pre-amp, waveform, BPM, all untouched).
3. Stop → Save (goes through the real Save screen, lands in `filesDir/saved/`).
4. Open it from Saved Recordings → Player screen now shows **Analyze Heart Sounds** at the bottom.
5. Tap it → chart + report → Download Report if you want the file (**now a PDF, not `.txt`** —
   see §13 Part 3, this changed after §12 was written).
6. To turn the feature off again without any of the above: set
   `SegmentationFeature.ENABLED = false` and rebuild. Everything reverts to exactly original
   Player-screen behavior with zero other changes needed.

---

## 13. Segmentation Report v3 — readable chart, landscape full-screen, PDF export (2026-08-24)

A follow-up task, run in four parts against an explicit scope-locked whitelist (only
`PcgSegmentationView.kt`, `SegmentationPalette.kt`, `SegmentationReportFragment.kt` + its layout,
new files under `ui/segmentation/`, one new layout, and additive-only `nav_graph.xml` changes —
algorithm, bridge, `taal-core`/`taal-ui-kit`/`lungs-app`/`visualizertaal-app`, and
`startDestination` all frozen). Recon (Phase 0) confirmed: bands already drew behind the waveform
(waveform alpha was 82%, not literally opaque); `SegmentationResult` is **not** `Parcelable`
(pure-JVM module, no Android dep to add it with) — this is what drove the ViewModel decision in
Part 2; `MainActivity` is `android:screenOrientation="portrait"` with **no** `configChanges`
declared, so a runtime orientation change fully recreates the Activity.

### 13.1 Part 1 — chart readability

`SegmentationPalette.kt`: S1/S2 band wash raised 24%→35% (light) / 28%→40% (dark), interval washes
unchanged (widens the events-vs-intervals contrast). Waveform alpha lowered 82%→76% so band colour
reads through it.

`PcgSegmentationView.kt`: waveform paint changed to `FILL_AND_STROKE` with `strokeWidth = 0f`
(hairline — exactly 1 physical pixel) so the trace stays crisp instead of smearing into a block at
high cycle density. Added a **time axis** — second ticks + labels below the lane, step width
(1/2/5/10/15/30/60/120/300s) picked adaptively so labels never collide regardless of zoom. Legend
swatches enlarged 10dp→14dp. Band rendering now clamps to a **minimum 1.5dp width** so a band
narrower than that still paints rather than disappearing (position never shifts — only the right
edge is pushed out enough to stay visible). The existing baseline (zero line) already satisfied the
"add a centre line" requirement — no change needed there.

Verified: 38/38 tests unaffected (Part 1 only touches the View/palette, not `PcgDisplay`'s
geometry, which is what the tests pin). AAR 373,095 → 374,446 bytes. On-device: confirmed the time
axis renders correctly with adaptive spacing (e.g. "0s 2s 4s...18s" on a 20s view).

### 13.2 Part 2 — full-screen landscape view

New `SegmentationFullScreenFragment` + `fragment_segmentation_fullscreen.xml`, reached via a new
"Full Segmentation" button on the report screen (visible only when there's a result to show). New
`SegmentationViewModel` (activity-scoped) carries `audio`/`sampleRate`/`outcome` across the
orientation-driven Activity recreation — chosen specifically because `SegmentationResult` can't
cross that recreation via a Bundle. Locks to `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE` on entry
(captured original value once, guarded so the second `onResume()` — which the orientation change
itself triggers — doesn't overwrite it with the now-current LANDSCAPE value), immersive system
bars via `WindowInsetsControllerCompat`. Restoration happens in `onDestroyView()`, guarded by
`!requireActivity().isChangingConfigurations` — without that guard, restoring on every
`onDestroyView()` would immediately fight the fragment's own landscape lock and loop, since
`onDestroyView()` also fires during the recreation the fragment itself causes. Restore target is
`SCREEN_ORIENTATION_UNSPECIFIED`, not a hardcoded copy of the captured `PORTRAIT` value — so the
manifest stays the actual source of truth.

Zoom: segmented control (Full/10s/5s — chosen over pinch-to-zoom as "the simpler one to implement
correctly"), re-centres on the current view rather than jumping to 0. Drag-to-pan only active when
zoomed in (disabled at Full, since panning the whole recording is meaningless), clamped to
`[0, duration]`, never narrower than 1s.

**Discovery made while doing this work**: `nav_graph.xml` already had unrelated in-progress work
in it (`fullTimeOnRecordingFragment` as a temp `startDestination`, `Calibrated*`/`FullTimeOn*`
screens — someone else's separate effort, dated 2026-08-20). Explicitly asked where to wire the
new button; told: `PlayerFragment` only, don't touch the other screens or the `startDestination`
flag. Honoured for the rest of Parts 1–4 — verification flips to `recordingFragment` were always
done and undone with `git diff` proof of zero net change on that line before continuing.

Verified live, both exit paths (close button and system back button): landscape renders correctly,
5s zoom makes individual S1/S2 fully distinct (the actual goal), no re-analysis on entry (verified
structurally — the fragment never references `TaalCardiacSegmentation` at all, not just "didn't
see it happen"), both exit paths correctly restore portrait + system bars and land back on the
report screen with unchanged cached numbers.

### 13.3 Part 3 — PDF export

New `SegmentationPdfExporter.kt` — `writeSegmentationPdf()`, platform `android.graphics.pdf.PdfDocument`
only, no new dependency. Single A4-landscape page (842×595pt @ 72dpi). **The chart is not a
screenshot** — a real (never-attached-to-a-window) `PcgSegmentationView` instance is measured,
laid out, and its own `draw()` called directly against the PDF page's `Canvas`, so it's
vector-quality and can't visually drift from the app's own rendering.

Key technical detail: the offscreen chart's `Context` has its density forced to 1.0 via
`createConfigurationContext` with `densityDpi = DisplayMetrics.DENSITY_DEFAULT` (160dpi) — without
this, the view's internal `dp()` conversions would use the *device's* real density (2.5–3x
typical) and blow the legend/axis sizing several times past what fits on the PDF page, since PDF
canvas units are points, not device pixels.

`SegmentationReportFragment`'s old `.txt` writer was **replaced**, not kept as a second button —
`savePdfToDownloads()` uses the same `MediaStore.Downloads`/`ContentValues`/`IS_PENDING` pattern,
only the MIME type (`application/pdf`) and extension changed. Download button now hidden by
default, shown only when there's a result to plot (matches the `NoHeartSounds`/`Unavailable`
requirement).

Verified by actually opening the file, not just checking the toast: pulled
`taal_segmentation_report_1787525002983.pdf` (**73,410 bytes** for a 20s recording) off the
device and read its content — title, generated timestamp, full metadata block, the real chart
(waveform + bands + time axis + legend), and the disclaimer footer all present and correctly laid
out.

### 13.4 Part 4 — disclaimer placement

Wording kept byte-identical everywhere: `Research/test output only — not a medical device.` Only
presentation moved. Report screen: from a subtitle directly under the title to a footer below the
Download button, smaller (12sp→10sp) and lower-contrast. Full-screen chart and PDF footer were
**already** correctly placed as part of Parts 2 and 3's own work — no change needed there when
Part 4 was reached.

### 13.5 Phase 5 — full verification pass

| Check | Result |
|---|---|
| `taal-segmentation-core:test` | **38/38** |
| `taal-segmentation:assembleRelease` | Clean, AAR 374,446 bytes (unchanged since Part 1 — Parts 2–4 are app-layer only) |
| `app:assembleDebug` | Clean |
| `taal-core`/`taal-ui-kit` `assembleRelease` | Byte-identical to the original Phase-0 baseline hashes |
| `git diff --stat` | Only whitelisted files — separately confirmed `PlayerFragment.kt`/`fragment_player.xml` had **zero** new changes from Parts 1–4 (that work predates this task) |
| `nav_graph.xml` `startDestination` | Confirmed against git HEAD: the tracked baseline value is `recordingFragment` (the `fullTimeOnRecordingFragment` state was itself uncommitted, from the other in-progress work) — current value matched HEAD exactly, zero net change on the destination itself |
| `SegmentationFeature.ENABLED = false` → rebuild → reinstall | Confirmed live on-device: "Analyze Heart Sounds" button completely gone, Player screen identical to pre-feature original. Flipped back to `true` afterward. |

---

## 14. UI/UX polish round (2026-08-24, same day)

A follow-up, user-requested pass on the report screen's visual design — "best UI... proper UI/UX",
add the Full/10s/5s zoom control to the report screen itself (not just full-screen), and better
colours/icons. Also flag-gated the same way as everything else in this feature (no new toggle
needed — reuses `SegmentationFeature.ENABLED`).

### 14.1 Report screen redesign

`fragment_segmentation_report.xml` rebuilt around the app's existing card-based design language
(same `MaterialCardView` style already used elsewhere in the app: `cardCornerRadius`, subtle
`strokeColor`/`strokeWidth`, `@color/divider`) instead of a flat scroll of text:

- **Status row**: icon + headline + optional subtext, replacing a plain "Analysis complete" line.
  `ic_check_circle` (green, `success_green`) for `Ok`; `ic_info` (orange `warning_orange` for
  `TooWeak`, muted `text_secondary` for `NoHeartSounds`/`Unavailable`) with an explanatory subtext
  for the non-`Ok` cases. Both icons already existed in the app's drawable set — nothing new added.
- **Chart card**: `PcgSegmentationView` wrapped in a `MaterialCardView`, with the **same
  Full/10s/5s zoom control now duplicated onto the report screen** (previously full-screen-only) —
  drag-to-pan deliberately **not** added here, since it would conflict with the surrounding
  `ScrollView`'s own vertical scroll gesture.
- **Stat tiles**: 2×2 grid (Heart Rate with `ic_heart` in `red_primary`, Cycles Detected, Duration,
  Avg Systolic Interval), each a small bordered card — label above value, matching a standard
  dashboard tile pattern. Replaces the old single monospace text dump entirely (removed
  `buildReport()` — the PDF's own metadata text is independent and was never affected).
- Buttons re-styled (see 14.2), disclaimer footer unchanged from §13.4.

### 14.2 MaterialButton fix

Found live, not by inspection: the redesigned "Full Segmentation View (Full Screen)" button (meant
to be white/outlined) rendered as solid `teal_primary` instead — confirmed by pixel-sampling a
screenshot at `(42, 191, 191)`, an exact match for `#2ABFBF`. Root cause, and the fix, are recorded
in full in memory as [[project_materialbutton_background_gotcha]] — short version: this app's
`Theme.MaterialComponents` theme auto-inflates plain `<Button>` to `MaterialButton`, which ignores
`android:background`/`app:drawableStartCompat` and falls back to `colorPrimary`. Fixed by using
explicit `<com.google.android.material.button.MaterialButton>` tags with `app:backgroundTint`,
`style="...OutlinedButton"` + `app:strokeColor`, and `app:icon`/`app:iconTint`/`app:iconGravity`
for the download button's save icon (which had also been silently dropped for the same reason).

### 14.3 Zoom selection indicator + button rename

Per explicit follow-up request: the currently-active Full/10s/5s level now shows a visible
selected state (teal `bg_chip_selected` pill + white text, matching the app's existing chip
selection pattern used elsewhere for filter chips; unselected buttons are transparent with muted
text) — implemented identically on both the report screen and the full-screen chart, defaulting to
"Full" selected on load. The "Full Segmentation" button was renamed to
**"Full Segmentation View (Full Screen)"**.

Verified live: selection indicator correctly follows taps on both screens (confirmed via a clean,
controlled re-test after an initial false alarm — a stray test-tap timing issue, not a real bug —
where full-screen briefly appeared to open on "10s" instead of "Full"; a fresh, careful re-test
confirmed "Full" is always correctly selected on entry). Zoomed 5s view on the report screen now
shows individual S1/systole/S2/diastole bands clearly distinct, matching the full-screen
experience without needing to leave the screen.

## 15. "Analyze Heart Sounds" button repositioned to the top bar (2026-08-24)

**Problem, exactly as reported:** on `PlayerFragment` ("Review Recording" screen), the circular
`playButton` visually overlapped the "Analyze Heart Sounds" button beneath it — visible in every
screenshot of that screen taken throughout this whole feature's development as the button's text
partially hidden behind the red play circle. User's ask was narrowly scoped: reposition/restyle
*only* `analyzeButton` so it no longer clashes with anything, and touch nothing else on the screen.

**Root cause:** `analyzeButton` (a full-width `<Button>` pinned with
`app:layout_constraintBottom_toBottomOf="parent"`, same anchor `saveDiscardBar` uses) and
`playButton` (`app:layout_constraintBottom_toTopOf="@id/saveDiscardBar"`) were never actually
constrained relative to each other. Since `analyzeButton` only shows for an already-saved
recording — precisely the case where `saveDiscardBar` is `GONE` — `playButton`'s real vertical
position depended entirely on where the invisible `saveDiscardBar` and its margins collapsed to,
with no defined relationship to `analyzeButton` at all. The two buttons were, in effect,
independently placed in the same screen region by coincidence.

**Fix — moved, not just re-margined:** rather than trying to hand-tune margins against a layout
relationship that doesn't structurally exist, `analyzeButton` was moved into `topBar`, as a 40dp
`ImageButton` (`ic_heart`, tinted `#128CB2` to match the amp-slider's accent colour,
`contentDescription="Analyze Heart Sounds"`) sitting where `eqButton` sits (`eqButton` itself is
`android:visibility="gone"` and unused in this screen currently, so there is no clash even though
both are `app:layout_constraintEnd_toEndOf="parent"`). This guarantees zero overlap by
construction — it's nowhere near the waveform chart or the play button — rather than by a margin
calculation that could drift again if any sibling view's size ever changes. It also matches the
screen's own existing convention: `backButton` and `eqButton` are already icon-only actions in
this exact top bar.

**What did *not* change:** `PlayerFragment.kt` needed zero edits — the only two things it does
with this view are `binding.analyzeButton.visibility = View.VISIBLE` and
`binding.analyzeButton.setOnClickListener { ... }`, both plain `View` members that work identically
on an `ImageButton` as they did on a `Button`. The gating logic (only shown for a file confirmed to
live in `filesDir/saved/` with a `_raw.wav` companion on disk), the click behaviour (navigate to
`segmentationReportFragment` with `rawFilePath`), the waveform chart, amp slider, timer, play
button, and save/discard bar are byte-identical to before this change.

Verified live: rebuilt (`:app:assembleDebug`), installed, opened an existing saved recording
(`HEART_kkuu`) from the library, confirmed via screenshot that the teal heart icon renders cleanly
in the top-right of the top bar with the circular play button fully clear beneath it — no overlap,
nothing else on the screen visibly changed. See [[project_materialbutton_background_gotcha]] in
memory — irrelevant here since an `ImageButton` (not a background-styled `Button`) was used, but
was checked against before deciding on `android:tint` for the icon.
