# SUPERMASTER_APP.md — `app` Module Reference

**Read this before touching any code in `app`.** Originally written 2026-09-08 on
`AppBranch`, substantially rewritten 2026-09-22 after two major structural changes landed:
(1) the `AddPatientFragment`/patient-tagging flow was **deleted entirely** (§4.8, §5, §9),
and (2) the module was split into **two fully independent, parallel screen flows** —
**Production** and **PcgScale** — each with its own recorder/player/save/list/review screens
that never share a destination past nothing at all (§3, §4.7). Nothing is shared between
them except the physical `filesDir/saved/` storage pool, `EqualizerFragment`, and
`SegmentationReportFragment`. **Before touching anything, grep `nav_graph.xml:6` yourself**
to see which flow is the live `startDestination` right now — it has changed direction
multiple times across sessions (full history in §11) and this doc's prose will lag whichever
value is actually live at the moment you read it.

Earlier history: this file was originally carried over from a `SUPERMASTER_APP.md` written
the same day (2026-09-08) on `StemzAppBranch`, then had a full "port everything from
`stemz-app`" pass applied (Clean Graph, segmentation wiring, share-with-graph, sharing bug
fixes, rev3 Y-axis algorithm — see §0 for exactly what was and wasn't ported). §0 is now
mostly historical background for *why* the module looks the way it does; §3–§9 describe the
module's *current* structure and take priority wherever the two disagree.

---

## 0. Branch provenance — read this first

`AppBranch` was created 2026-09-08 directly off `main` (`git checkout -b AppBranch main`),
specifically so that all future `app`-module feature work and testing has a dedicated home
(per repo convention, mirroring `StemzAppBranch` for `stemz-app`). `main` itself did not
include a run of `app`-touching commits that had, until this session, only ever landed on
`StemzAppBranch` (`d5ed39e` through `cff9f3b` — run `git log --oneline main..StemzAppBranch --
app/` to see the original list). **As of 2026-09-08 (same day, later session), the practical
gap described in this section has been closed**: the user asked to port everything from
`stemz-app` to `app` (excluding the 15s auto-stop, the Basic/Hard filter replacement, and any
app-identity change — see §8.4), and that work is now done and live-device-tested. What
follows is what's still explicitly true and what changed:

- **`PcgAmplitudeScale` "rev 3" — NOW PORTED.** `app`'s copy is the median-of-per-5s-window
  calibrating-hop-peak scale with `MIN_FULL_SCALE=0.005f`, `OUTLIER_REJECTION_K=3`,
  `typicalPeakAmplitude()` (verified `PcgAmplitudeScale.kt` current source; the matching
  `meanPeakRms()`-based old API is gone, and `PcgScaleRecordingFragment.kt`'s live caption was
  updated to call `typicalPeakAmplitude()`). The rev3 test file was ported too.
- **Sharing fixes — NOW PORTED.** `RecordingLibraryFragment.shareRecording()`'s FileProvider
  authority bug (`.provider` instead of the manifest's actual `.fileprovider`) is fixed, and
  both `RecordingLibraryFragment` and `SavedRecordingsFragment` now use
  `"application/octet-stream"` (not `"audio/*"`/`"audio/wav"`) plus a cleanly-named temp copy
  for `SavedRecordingsFragment`'s share, matching `stemz-app`'s fix. Verified live on-device
  via the actual Android share sheet (§8.3).
- **`PlayerFragment.kt` / `SegmentationReportFragment.kt` polish — NOW PORTED**: saved-name
  screen titles, the segmentation report's own top bar + back button (always lands on Saved
  Recordings, not Player), the `resultStatusRow`-hidden-on-success tweak, and running the
  segmentation chart's display copy through `PcgDisplayFilter`. `SaveRecordingFragment.kt`'s
  ledger-fix changes (routing a new PcgScale recording's Save button through
  `SaveRecordingFragment` rather than straight to Add Patient) were **NOT** ported — out of
  scope for what was asked, and `PcgScalePlayerFragment`'s save flow is unchanged from before
  this session.
- **`PcgDisplayFilter.kt` / `PcgSpectralGate.kt` — ported in §8.2** (Clean Graph), unchanged
  by this later session.
- **"Share with graph" — NOW PORTED**, see new §8.3. Full `ui/graphshare/` package, live
  UI wiring, live-device-tested (produced a real WAV+PNG+PDF bundle and launched the Android
  share sheet, inspected on-device — see that section).
- **`stemz-app`'s 15s recording auto-stop, Basic/Hard filter toggle replacement, and app
  name/branding — DELIBERATELY NOT PORTED**, per explicit instruction. `app`'s PcgScale
  recorder keeps its original 5-preset filter row and no auto-stop (only the pre-existing 300s
  hard ceiling); the app's name/label/launcher icon are untouched.
- **`stemz-app`'s recorder-side Hum/rumble filter toggle — NOT ported**, out of caution: it
  wasn't clear from available context whether that toggle affects only display or also the
  recorder's actual capture-time filtering, and the instruction to leave filter behavior alone
  argued for skipping it rather than guessing. Revisit explicitly if wanted.
- **`stemz-app`'s real release `signingConfig`** — **NOT ported**: it depends on stemz-app's
  own keystore secrets (`stemz-app/keystore.properties`, gitignored), which are not something
  to silently reuse or fabricate for `app`'s own release identity. `app`'s release build
  remains unsigned (§2, §10) — a deliberate decision needing the user's own keystore material,
  not a gap this session closed.

If you're about to trust a claim in this file about exact algorithm constants, RMS windowing,
or a specific commit hash, **verify against `AppBranch`'s own source first** — this doc's
structure is a shared template across branches, not proof the referenced code is present here.

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
- `buildTypes.release`: `isMinifyEnabled = false`, default + `proguard-rules.pro` (`:21-29`). **No `signingConfig` block at all** — unlike `stemz-app`, which wires a real release `signingConfig`; `app` has none, so a release build here is unsigned.
- `compileOptions`/`kotlinOptions`: Java 1.8 / jvmTarget 1.8 (`:31-38`)
- `buildFeatures.viewBinding = true` (`:40-42`) — every fragment in this module uses generated `Fragment*Binding` classes, `_binding`/`binding` nullable pattern, cleared in `onDestroyView`.
- Dependencies (`:45-87`): `project(":taal-core")`, `project(":taal-segmentation")` (note: **not** `taal-ui-kit` — `app` does not depend on the SDK's prebuilt UI layer, only the pure audio engine + the segmentation wrapper), AndroidX core/appcompat/material/constraintlayout, Navigation 2.7.6, Lifecycle/ViewModel/LiveData 2.7.0, Room 2.6.1 (+ KSP compiler), Coroutines 1.7.3, MPAndroidChart `v3.1.0` (JitPack), ViewPager2 1.0.0, Biometric 1.1.0.
- `proguard-rules.pro`: keeps `com.musediagnostics.taal.app.data.db.entity.**`, `com.musediagnostics.taal.**`, and `com.github.mikephil.charting.**` wholesale.

### `AndroidManifest.xml` (`app/src/main/AndroidManifest.xml`, full contents — 62 lines)
- Permissions: `RECORD_AUDIO`, `USB_PERMISSION`, `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"` only — API 29+ uses MediaStore, no runtime permission). `uses-feature android.hardware.usb.host` (`required="false"`).
- `application android:name=".TaalApplication"` — holds the lazily-created `TaalDatabase` singleton and a static `instance` (`TaalApplication.kt:6-21`).
- A `FileProvider` at authority `${applicationId}.fileprovider`, `exported="false"`, `grantUriPermissions="true"`, paths from `@xml/file_paths` (manifest `:27-35`). `res/xml/file_paths.xml` grants exactly one directory: `<files-path name="saved_recordings" path="saved/" />` — i.e. only `filesDir/saved/` is ever exposed via content:// URI, nothing else.
- One `Activity`: `.ui.MainActivity`, `exported="true"`, `screenOrientation="portrait"`, `LAUNCHER`.
- A **second intent-filter on the same `MainActivity`** for `ACTION_VIEW` with two deep-link hosts: `taalapp://calibrated` and `taalapp://pcgscale` (manifest `:47-57`). Production has **no deep link of its own**. The in-manifest comment only mentions the calibrated screens ("Debug entry point for the calibrated ECG-paper screens... not reachable from any always-visible production button") — stale/incomplete since `taalapp://pcgscale` was added later; whether that host is "redundant" depends entirely on the current `startDestination` (§3) — it's the only way to reach PcgScale at all whenever Production is live, and redundant only on the rare occasion PcgScale itself is the `startDestination`.
- No other manifest-declared providers, receivers, or deep links.

---

## 3. Navigation & Screen Map

**Live navigation graph**: `app/src/main/res/navigation/nav_graph.xml`.

**Confirmed live `startDestination` right now (2026-09-22)**: `@id/recordingFragment` — i.e.
**Production**. This value has flipped direction repeatedly across sessions (full history in
§11); **grep `nav_graph.xml:6` yourself before trusting this** — it names the *class*
(`ProductionRecordingFragment`) that destination ID now points at, not literally
"production," so don't rely on the ID string alone either. Production has **no deep link**
of its own; PcgScale is reachable via `taalapp://pcgscale` regardless of which one is
`startDestination`. Switching between them is a one-line edit + rebuild, nothing else needs
to change — see the note at the top of this file.

### 3.0 The module is now two independent flows

As of 2026-09-16, the module was deliberately split so **Production** and **PcgScale** each
own their entire recorder → player → save → saved-list → review chain, with no shared
destination in between (they used to converge on the same `SavedRecordingsFragment` /
`PcgScaleReviewFragment` regardless of which recorder made the file — that convergence is
gone). See §4.7 for the full rationale and file-by-file breakdown of the fork. The two flows
still share the same physical `filesDir/saved/` folder (a recording made in either one shows
up in *both* saved-lists, just reviewed through that list's own review screen), plus two
genuinely generic utility screens: `EqualizerFragment` and `SegmentationReportFragment`.

```
Production                              PcgScale
───────────                              ────────
recordingFragment (Production​Recording​Fragment)     pcgScaleRecordingFragment (PcgScaleRecordingFragment)
 │                                        │
 ▼                                        ▼
playerFragment (Production​Player​Fragment)          pcgScalePlayerFragment (PcgScalePlayerFragment)
 │  Save                                  │  Save
 ▼                                        ▼
productionSaveRecordingFragment          saveRecordingFragment  (original, shared — see below)
 │                                        │
 ▼                                        ▼
productionSavedRecordingsFragment        savedRecordingsFragment  (original, shared)
 │  tap item                              │  tap item
 ▼                                        ▼
productionReviewFragment                 pcgScaleReviewFragment
 │  Analyze                               │  Analyze
 └──────────────┬─────────────────────────┘
                 ▼
        segmentationReportFragment  (shared utility; returnDestination arg
                                      tells it which saved-list to pop back to, §8)
```

`saveRecordingFragment`/`savedRecordingsFragment` are the **original** files — untouched by
the split, still exactly what PcgScale (and the still-orphaned Calibrated/FullTimeOn
families) use. Production got its own **fork** of each (`ProductionSaveRecordingFragment`,
`ProductionSavedRecordingsFragment`) rather than a rename, specifically so nothing about
PcgScale's copies needed to change.

### 3.1 What a user can actually reach from a cold launch

This was determined by (a) reading every `<action>` edge in `nav_graph.xml` and (b) grepping
every `findNavController().navigate(...)` / `R.id.<destination>` call and every
`openDrawer()` call in `app/src/main/java` to see which graph edges are ever actually
exercised vs. declared-but-dead.

```
recordingFragment (START — production V7 recorder, MPAndroidChart, warmup/peak Y-axis)
 ├─ [folder icon] → productionSavedRecordingsFragment
 │    └─ [tap a saved item] → productionReviewFragment
 │         ├─ [EQ button] → equalizerFragment
 │         ├─ [share icon] → Android share sheet (audio only, §7)
 │         └─ [Analyze Heart Sounds, if a saved _raw.wav exists] → segmentationReportFragment
 │              (returnDestination=productionSavedRecordingsFragment, §8)
 └─ [record → stop] → playerFragment  (always a fresh temp file, no isNewRecording branch anymore)
      ├─ [EQ button] → equalizerFragment
      ├─ [Save button] → productionSaveRecordingFragment (popUpTo=recordingFragment)
      │    └─ (auto, after save) → productionSavedRecordingsFragment
      └─ [Discard button] → confirm dialog → delete temp files → navigateUp()
```

`pcgScaleRecordingFragment`/`pcgScalePlayerFragment`/`pcgScaleReviewFragment` and
`savedRecordingsFragment` are **not reachable from a cold launch** on this `startDestination`
— only via the `taalapp://pcgscale` deep link (which lands on `pcgScaleRecordingFragment`,
from which the rest of that flow — folder icon, save, review — is reachable exactly as
described in §3.0's right-hand column). **If `nav_graph.xml:6` is flipped to
`pcgScaleRecordingFragment` instead, the reachability mirrors this exactly with the columns
swapped** — Production becomes deep-link-**less** (it has no `taalapp://` host at all, so it
becomes fully unreachable, not just deep-link-only) while PcgScale becomes the live cold-launch
flow.

**Every other fragment in this module is unreachable from a cold launch on either
`startDestination` value** — the entire auth chain, `RecordingLibraryFragment`,
`ProfileFragment`, all Info screens, `ChangePinFragment`, `EditRecordingFragment`,
`CropRecordingFragment`, `NewRecordingFragment`, `TestRecordingFragment`/`TestPlayerFragment`,
`SharedRecordingsFragment`, `LoadingFragment`, the Calibrated screens, the FullTimeOn screens.
This is now **permanent**, not just a live-`startDestination` artifact, because of §4.8:

**Why the drawer (and everything behind it) is now permanently unreachable:**
- `MainActivity`'s navigation drawer (`ui/MainActivity.kt:122-163`) still calls
  `navController.navigate()` on `R.id.profileFragment`, `R.id.recordingLibraryFragment`,
  `R.id.changePinFragment`, `R.id.aboutUsFragment`, `R.id.faqFragment`,
  `R.id.privacyPolicyFragment`, `R.id.subscriptionFragment`, `R.id.userManualFragment`, plus a
  sign-out footer button to `R.id.loginFragment` — none of that code was touched.
- But `MainActivity.openDrawer()`'s **only caller in the entire module was
  `AddPatientFragment.kt:125`**, and `AddPatientFragment` was **deleted outright** on
  2026-09-09 (§4.8) — not just made unreachable, the class/layout/nav destination/all six
  inbound `<action>` edges to it no longer exist at all. There is now **no code path in the
  module that can call `openDrawer()`, period** — not "currently dead," but structurally
  impossible until someone adds a new call site. The drawer XML, `MainActivity`'s handling
  code, `ProfileFragment`, `RecordingLibraryFragment`, and every Info screen are all still
  present and functional if you reached them another way (e.g. `navController.navigate()`
  from a debugger) — there is simply no in-app button left that opens the drawer.
- Consequence: the entire auth chain (`SplashFragment` → ...) remains unreachable for the
  same reason it always was (nothing navigates to `splashFragment`, it's not the
  `startDestination`), and the drawer's sign-out button is unreachable transitively.
- Segmentation is unaffected by any of this (§8) — both flows' Analyze buttons are direct nav
  actions off their own review screen, no drawer/auth/AddPatientFragment involvement ever.
- `RecordingLibraryFragment`/`EditRecordingFragment` are drawer-gated, so unreachable.
  **`CropRecordingFragment` has no live caller at all** — no button in either Player fragment
  navigates to it (grepped, zero hits); treat it as a pure orphan.

**Confirmed pure orphans (declared in `nav_graph.xml`, zero inbound `<action>` AND zero direct `navigate()` call anywhere in the module):**
- `testRecordingFragment` / `testPlayerFragment` — `TestRecordingFragment.kt:3` literally says `//THIS IS NOT IN USE (ONLY TESTING BY KUNAL)`.
- `sharedRecordingsFragment` (`SharedRecordingsFragment.kt`) — grep for the class name anywhere outside its own file returns nothing.
- `loadingFragment` (`LoadingFragment.kt`) — same, zero references.
- `cropRecordingFragment` — no live nav action call site found anywhere.
- `calibratedRecordingFragment` and `fullTimeOnRecordingFragment` — no inbound `<action>` from any reachable screen and not the `startDestination`. `calibratedRecordingFragment` **is** reachable via the `taalapp://calibrated` ADB deep link (manifest-registered, see §2); `fullTimeOnRecordingFragment` has no deep link at all and is reachable **only** via IDE/manual navigation-graph tooling.
- `newRecordingFragment` — its only inbound edge (`action_recording_to_newRecording`,
  `ProductionRecordingFragment.kt`) is live exactly when Production is the `startDestination`
  (currently: yes — see §10, this is still an open issue, a real settings-gear button opens
  an explicitly "NOT IN USE / experimental" screen).
- `pcgScaleRecordingFragment`/`pcgScalePlayerFragment`/`pcgScaleReviewFragment`/`savedRecordingsFragment` — deep-link-only (or fully unreachable if `startDestination` is Production and you never use the deep link) whenever Production is the live `startDestination`; swap to fully live if PcgScale is instead (§3.0).
- `addPatientFragment`, `PatientSearchAdapter`, `PatientViewModel` — **no longer exist**, not just orphaned (§4.8).

**Caveat on all of the above**: this is a static analysis of every `navigate()`/action edge/`openDrawer()` call site as of this snapshot — it is not a runtime-tested claim. If you're about to fix a bug in any of the "unreachable" screens, first re-run these greps yourself.

### 3.2 Full destination list
See §9 for the full table with one-line purpose + reachability. Argument types/defaults for every destination are documented inline in `nav_graph.xml` itself. `popUpToDestination` and `returnDestination` are the two recurring bundle keys (not declared `<argument>` elements — read via `arguments?.getInt(key, default)` in Kotlin) that let a shared screen (`SaveRecordingFragment`/`ProductionSaveRecordingFragment`, `SegmentationReportFragment`) know which caller-specific destination to land back on.

---

## 4. Waveform/Graph Screen Families

Six independently-evolved implementations exist. None share code beyond copy-paste-and-fork (each fork's own doc comments say so explicitly).

### 4.1 Production Recording/Player — `ui/recording/ProductionRecordingFragment.kt` + `ui/player/ProductionPlayerFragment.kt`
- **Renamed 2026-09-16** from `RecordingFragment.kt`/`PlayerFragment.kt` as part of the
  Production/PcgScale split (§4.7) — same classes, same core V7 rendering, new names so every
  file in the Production flow is greppable by name. Nav destination IDs (`recordingFragment`,
  `playerFragment`) were deliberately **not** renamed to minimize blast radius — only the
  `android:name` class references changed.
- **Live?** Depends entirely on `nav_graph.xml:6` (§3) — **currently yes** (Production is the
  `startDestination` as of 2026-09-22). No deep link exists for this family; if PcgScale is
  the `startDestination` instead, Production is **fully unreachable**, not deep-link-only.
- MPAndroidChart `LineChart`. Fixed 10-second scrolling window (`WINDOW_SECONDS = 10f`). Downsample step 44 (~1002 pts/sec).
- Y-axis: **adaptive warmup/peak** scheme — accumulate the signal's true peak over the first `WARMUP_MS = 2000`ms, then lock the axis to `peakAmplitude × HEADROOM(1.5)` so the waveform fills ~65% of height; `MIN_PEAK = 0.02` floor prevents over-zoom on near-silence. Once locked, the axis does not move again during that recording — this is the scheme the Calibrated fork's Fix A explicitly reversed for being "unstable."
- This is the family memory/root-doc calls "V7 sample-accurate downsampling."
- `ProductionRecordingFragment`'s "Ready to Capture" silence-detection dialog was **removed** — matches `PcgScaleRecordingFragment`. Confirmed absent in source (`onSilentRecordingDetected` override no longer present) and live on-device.
- `ProductionRecordingFragment`'s settings-gear button opens `NewRecordingFragment` (explicitly "NOT IN USE / experimental" per its own header comment) — **live whenever Production is the `startDestination`** (§10, still an open/unresolved issue, not something this session's work addressed).
- **`ProductionPlayerFragment` was simplified 2026-09-16** (§4.7): it used to also handle
  reviewing an already-saved recording (`isNewRecording=false` branch — EQ, Analyze Heart
  Sounds, save/discard dialog routing to the now-deleted `AddPatientFragment`). That entire
  branch is gone from this file; it now does exactly one thing — review a **brand-new**
  recording, Save or Discard, nothing else. No `isNewRecording` argument read anymore, no
  Analyze button in its layout at all (a fresh temp file's raw companion is never inside
  `filesDir/saved/`, so the gate could never pass anyway). Save routes straight to
  `productionSaveRecordingFragment` (§3.0), always, no branching.
- Reviewing an **already-saved** Production recording is now `ProductionReviewFragment`'s job
  exclusively — see §4.7.

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

### 4.6 PcgScale screens — `ecg/pcgscale/*`, `ui/pcgscale/*`
- **Live?** Depends on `nav_graph.xml:6` (§3) — **currently no** (Production is the
  `startDestination` as of 2026-09-22); reachable only via the `taalapp://pcgscale` deep link,
  which lands on `pcgScaleRecordingFragment` and from there the whole family (player,
  `savedRecordingsFragment`, `pcgScaleReviewFragment`) is reachable exactly as before. **Since
  the 2026-09-16 split (§4.7), `SavedRecordingsFragment` no longer serves Production
  recordings too** — it and `PcgScaleReviewFragment` are PcgScale-exclusive now; Production
  has its own separate list/review pair. Both flows still read/write the same physical
  `filesDir/saved/` folder, so a file is visible in both lists — it's just reviewed through
  whichever list you opened it from.
- Explicitly documented as a fork of the Calibrated screens (`PcgScaleRecordingFragment.kt:46-64`), with these deliberate differences:
  1. **Time grid, not mm grid**: `PcgTimeScale` (`ecg/pcgscale/PcgTimeScale.kt`) fixes 1 large box = 1 second, 1 small box = 0.2s (`SECONDS_PER_LARGE_SQUARE=1f`, `SMALL_SQUARES_PER_LARGE_SQUARE=5`, `:32-34`), default visible window 4 seconds (`DEFAULT_VISIBLE_SECONDS=4f`, `:40`). `pixelsPerSecond` is derived once from plot width and frozen for the session — **zoom is impossible by construction**: `PcgScaleWaveformView` locks both `setVisibleXRangeMaximum` and `Minimum` to the same value (`PcgScaleWaveformView.kt:146-148`), unlike the Calibrated player which deliberately leaves pinch-zoom alive.
  2. **Y-axis**: RMS-derived 60%-fill auto-scale via `PcgAmplitudeScale` (`ecg/pcgscale/PcgAmplitudeScale.kt`), replacing both the Calibrated fixed-scale scheme and the production warmup/peak lock. **On `AppBranch` this is the ORIGINAL (pre-"rev 3") algorithm — see §0** — mean of per-5s-window peak 50ms-RMS values, divided by `TARGET_FILL_FRACTION=0.60f`, clamped to `[MIN_FULL_SCALE=0.02f, MAX_FULL_SCALE=1.0f]` (`PcgAmplitudeScale.kt:52,58`). `DEFAULT_INITIAL_FULL_SCALE = 0.10f` is the neutral pre-first-window value. Player/Review use `computeFullScaleForFile()` — same algorithm run once over the whole decoded file.
  3. **Clean Graph display-only conditioning chain** (added this session, see §8.2): `PcgDisplayFilter`/`PcgSpectralGate` (click/USB-glitch removal + zero-phase 20–500 Hz band-pass + 50/100/150 Hz mains-hum notches + a transient-protected spectral gate), toggleable per-screen on `PcgScalePlayerFragment`/`PcgScaleReviewFragment` via a `denoiseOnBadge` tap-pill, default ON. **Never** applied on the live recorder screen in this module (unlike `stemz-app`, which also runs a causal `PcgLiveDisplayFilter` on the live trace) and **never** applied to the recorded WAV bytes themselves — display only.
  4. **Sample-rate self-correction**: the fragment assumes 44100 Hz until the recorder's first callback reports the real rate (`onSampleRateReported`, `PcgScaleRecordingFragment.kt:~750-763`) — added because Samsung USB-audio stacks commonly report 48000 Hz, and a hardcoded 44100 assumption there makes "1 second" boxes span ~1.09s of real signal (documented regression).
- `PcgScaleRecordingViewModel`/`PcgScaleRecordingUiState` are a byte-for-byte state-shape copy of `CalibratedRecordingViewModel` "so a future 'swap the fragment class in nav_graph.xml' promotion is a one-line change" (`PcgScaleRecordingViewModel.kt:10-17`).
- `PcgScaleReviewFragment` (reached from `SavedRecordingsFragment`) is a read-only variant with **no** save/discard/`isNewRecording` branch at all, unlike `PcgScalePlayerFragment`. It now (this session) has its own segmentation "Analyze Heart Sounds" entry — see §8.
- **`PcgScalePlayerFragment` still carries dead `isNewRecording == false` branch code** (`showSaveDiscardDialog`, `showDiscardConfirmation`'s "else" twin) — per the reachability analysis in §3.1, this screen is only ever opened live with `isNewRecording=true`, so that branch has no live caller. Its SAVE case was updated 2026-09-09 (same fix applied to every Player family, §4.8) to toast "Recording already saved" and back out, rather than navigate to the now-deleted `AddPatientFragment`. Its own segmentation Analyze-button gate (§8) has the same dead shape: only shows for a file already inside `filesDir/saved/`, which a brand-new recording never is on this screen — so in practice, **only `PcgScaleReviewFragment`'s Analyze button is ever visible to a user today**.
- **`PcgScalePlayerFragment`'s Save button was fixed 2026-09-09** — it used to navigate straight to the (now-deleted) `AddPatientFragment` with only the filtered temp path, silently dropping `rawFilePath` and never actually moving the file into `filesDir/saved/` (a fresh recording would vanish from the UI forever the moment you tapped Save — see §4.8 for the full bug writeup). It now correctly routes through `action_pcgScalePlayer_to_saveRecording` → `SaveRecordingFragment`, passing `filePath`/`rawFilePath`/`filterName` and an explicit `popUpToDestination=pcgScaleRecordingFragment` (required — `SaveRecordingFragment` defaults to popping up to Production's `recordingFragment`, which isn't on this back stack).

### 4.7 The Production/PcgScale split (2026-09-16)

**Why**: before this, every recorder family funneled into the same `SaveRecordingFragment` →
`SavedRecordingsFragment` → `PcgScaleReviewFragment` tail regardless of which recorder made
the file — Production recordings were reviewed through PcgScale's time-grid/Clean-Graph
screen, not through anything resembling the original production look. Explicit request: keep
the existing PcgScale screens completely untouched, and build Production its own parallel
"whole flow" — recorder, player, save, saved-list, review — so switching `startDestination`
switches between two genuinely independent experiences, not just two recorder screens feeding
the same tail.

**New files** (all `ui/` package, all 2026-09-16):

| File | Forked from | Nav destination |
|---|---|---|
| `ProductionRecordingFragment.kt` | `RecordingFragment.kt` (renamed, not forked) | `recordingFragment` |
| `ProductionPlayerFragment.kt` | `PlayerFragment.kt` (renamed + simplified, not forked) | `playerFragment` |
| `recording/ProductionSaveRecordingFragment.kt` | `SaveRecordingFragment.kt` (new fork) | `productionSaveRecordingFragment` |
| `library/ProductionSavedRecordingsFragment.kt` | `SavedRecordingsFragment.kt` (new fork) | `productionSavedRecordingsFragment` |
| `library/ProductionSavedRecordingAdapter.kt` | `SavedRecordingAdapter.kt` (new fork, minus the share-with-graph button/param — that feature stays PcgScale-only) | (adapter, no destination) |
| `player/ProductionReviewFragment.kt` | Extracted from `PlayerFragment.kt`'s old `isNewRecording=false` branch | `productionReviewFragment` |

Plus matching new layouts: `fragment_production_recording.xml` (renamed), `fragment_production_player.xml` (renamed, `analyzeButton`/dead-branch views removed), `fragment_production_save_recording.xml`, `fragment_production_saved_recordings.xml`, `item_production_saved_recording.xml`, `fragment_production_review.xml`.

**What stayed exactly as it was**: `SaveRecordingFragment.kt`, `SavedRecordingsFragment.kt`,
`SavedRecordingAdapter.kt`, `PcgScaleRecordingFragment.kt`, `PcgScalePlayerFragment.kt`,
`PcgScaleReviewFragment.kt`, and their layouts — all untouched by the split itself (though
`PcgScalePlayerFragment`'s Save button got its own independent bug fix the same week, §4.8).

**The one shared screen that needed a real code change**: `SegmentationReportFragment`'s
`goToSavedRecordings()` used to hardcode `R.id.savedRecordingsFragment` as its "back" target.
With two separate saved-lists now existing, that would send a Production-flow user back to
PcgScale's list on "back" from a segmentation report. Fixed by adding an optional
`returnDestination` bundle int (defaulting to `R.id.savedRecordingsFragment`, so PcgScale's
behavior is byte-for-byte unchanged), which `ProductionReviewFragment` sets to
`R.id.productionSavedRecordingsFragment` when it navigates there. This is the **only**
PcgScale-touching file this split modified, and only additively.

**Live-device verified (2026-09-16)**: cold-launched on Production `startDestination`,
tapped through folder → an existing saved recording → `ProductionReviewFragment` (correct
saved name in title, V7 spiky-trace rendering — visually distinct from PcgScale's grid — EQ
button, Analyze button active/blue since a raw companion existed) → Analyze → segmentation
report rendered correctly → back button returned to `ProductionSavedRecordingsFragment` (not
PcgScale's list). No crash anywhere in the pass.

### 4.8 AddPatientFragment — deleted entirely (2026-09-09)

**The bug that triggered this**: `PcgScalePlayerFragment`'s Save button (for a fresh
recording) navigated straight to `AddPatientFragment` with only `recordingFilePath` set to
the **temp** filtered path — never through `SaveRecordingFragment`, so the file was **never
renamed into `filesDir/saved/`**, `rawFilePath` was silently dropped entirely, and
`AddPatientFragment` → `PatientViewModel` just inserted a `RecordingEntity` DB row pointing
at a temp file that was never moved anywhere. Net effect: saving a fresh PcgScale recording
made it **permanently invisible in the UI** — not in `SavedRecordingsFragment` (which only
lists `filesDir/saved/`), and the only screen that could show the DB row
(`RecordingLibraryFragment`) was itself drawer-gated/unreachable. Production's own
`PlayerFragment` had the equivalent bug already fixed for its `isNewRecording=true` case (it
correctly went through `SaveRecordingFragment`) — only the `isNewRecording=false` "reopen an
already-saved recording" case still routed through `AddPatientFragment`, same as every other
Player family's save/discard dialog SAVE branch.

**The decision, given the choice between "just reroute Save to `SaveRecordingFragment`" or
"delete `AddPatientFragment` outright"**: explicitly the latter, chosen and confirmed by the
user after being told the consequence (below).

**Deleted**: `ui/patient/AddPatientFragment.kt`, `PatientSearchAdapter.kt`,
`PatientViewModel.kt`, `res/layout/fragment_add_patient.xml`,
`res/layout/item_patient_search_result.xml`, the `addPatientFragment` nav destination, and
all six `action_*_to_addPatient` edges (`recordingFragment`, `playerFragment`,
`testPlayerFragment`, `calibratedPlayerFragment`, `fullTimeOnPlayerFragment`,
`pcgScalePlayerFragment`). `PatientEntity`/`RecordingEntity`/their DAOs/repositories were
**not** deleted — see §5, they're now simply unused by any live code path but the schema
still exists.

**Every Player family's save/discard dialog SAVE branch was updated** (production, Calibrated,
FullTimeOn, Test, PcgScale) — since that branch only ever runs for an *already-saved*
recording (nothing left to actually save), it now just toasts "Recording already saved" and
calls `navigateUp()`, instead of navigating to the deleted screen.

**Known, accepted consequence**: `AddPatientFragment.kt:125` was the module's **only**
`openDrawer()` call site. Deleting it means the navigation drawer (Profile, Recording
Library, Change PIN, FAQ, Privacy Policy, Subscription, User Manual, About, sign-out) has
**no remaining entry point anywhere in the app** — not "currently unreachable" as it was
before, but structurally impossible to open without adding a brand-new call site somewhere.
`MainActivity`'s drawer code and every one of those destination screens are untouched and
would work fine if reached — there's just no button left that reaches them. See §3.1 for the
updated reachability writeup and §10 for this as a tracked, deliberately-accepted issue.

**Live-device verified (2026-09-09)**: full compile + unit test pass, then installed and
exercised the fixed save flow on-device with Production as the `startDestination` — a fresh
recording's Save button now correctly lands in `filesDir/saved/` and shows up in the saved
list, confirmed via a subsequent app-storage inspection.

---

## 5. Data Layer

**As of 2026-09-09, this entire layer is unused by any live or reachable code path.**
`AddPatientFragment`/`PatientViewModel` (§4.8) were the **only** code in the module that ever
called `PatientRepository.insertPatient()` or `RecordingRepository.insertRecording()` — both
deleted. Nothing else in `app` writes to either table. `RecordingLibraryFragment` (the only
reader of `RecordingWithPatient`/`getRecordingsWithPatients()`) was already unreachable before
this and remains so. The schema, DAOs, and repositories below are left completely intact
(deleting a live Room schema is a different, more destructive class of change than deleting
an unreachable UI flow) — just be aware that `patients`/`recordings` will now sit permanently
empty on every install unless something new is built to write to them.

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

Described for the PcgScale family; Production/Calibrated/FullTimeOn follow the same
dual-file shape with different file-path prefixes and different downstream screens.
**Production's save step now differs in destination, not mechanism** (§4.7/§4.8): steps 1–4
below apply identically, but step 5 lands in `ProductionSaveRecordingFragment` →
`ProductionSavedRecordingsFragment` instead of the shared `SaveRecordingFragment` →
`SavedRecordingsFragment` — same rename/copy-to-device-storage code, just a separate fork
(§4.7) so the two flows' post-save destinations never overlap.

0. **RECORD_AUDIO permission is now requested as soon as this (startDestination) screen opens** (`requestAudioPermissionIfNeeded()`, called from `onViewCreated`, ported this session) — not deferred to the first tap of Start Recording. Does not auto-start a recording on grant (`startRecordingAfterPermission` guards that); a no-op if already granted.
1. **Start**: `PcgScaleRecordingFragment.startRecording()` builds two temp file paths directly under `filesDir` (not `filesDir/saved/` yet): `pcg_recording_{ts}_raw.wav` and `pcg_recording_{ts}_filtered.wav`. A `TaalRecorder` (from `taal-core`) is configured with both paths (`setRawAudioFilePath`/`setFilteredAudioFilePath`), a 300s hard cap (`setRecordingTime(300)`), the selected `PreFilter` or a custom bandpass, current pre-amp dB (0–30, **default 10, ported from `stemz-app` this session** — was 5), and no additional hum/rumble toggle (deliberately not ported, see §0 — that concept exists in `stemz-app`'s PcgScale recorder but not here). **`stemz-app`'s 15s auto-stop was deliberately NOT ported** — this recorder still has only the pre-existing 300s hard ceiling, per explicit instruction.
2. **Live callback** (`onProgressUpdate`): feeds a local `AudioTrack` monitor (so the clinician hears live audio), feeds `HeartBpmCalculator` for BPM, and — after undoing the pre-amp gain so display always reflects true acoustic level (`COMPENSATE_PREAMP_IN_DISPLAY`) — updates the RMS-based Y-axis (`PcgAmplitudeScale`, now the rev3 algorithm — see §0), downsamples via `PcgScaleWaveformView.downsampleMinMax`, and redraws the chart, snapping the camera to the current 1-of-N "page" (window) via `chart.moveViewToX`.
3. **Stop** (`stopRecording()`): stops the recorder and the monitor, then navigates **immediately** to `pcgScalePlayerFragment` with `filePath` = the filtered temp file, `rawFilePath` = the raw temp file, `isNewRecording=true`, `filterName`, and `preAmpDb`. No toast, no precompute — same "instant graph" pattern as the production flow.
4. **Player** (`PcgScalePlayerFragment`): loads the full waveform from the filtered file, offers EQ, the new Clean Graph toggle (§8.2), and shows a Save/Discard bar only when `isNewRecording`.
5. **Save** (`SaveRecordingFragment.kt`, shared by every recorder family via `popUpToDestination` argument): user types a name; `saveInternally()` renames both temp files into `filesDir/saved/{FILTER}_{userInput}_filtered.wav` and `_raw.wav`, then best-effort copies both to the device's shared Music folder.
6. **Discard**: temp files are deleted directly (`File(...).delete()`), no DB/repository interaction.
7. A device-disconnect during recording (`onDeviceDisconnected`) deletes both temp files and resets to IDLE with a toast.
8. **"Ready to Capture" dialog — REMOVED, confirmed gone from BOTH recorder screens as of 2026-09-08.** `onSilentRecordingDetected` was previously overridden on both `PcgScaleRecordingFragment` and production `RecordingFragment` to show a blocking `AlertDialog` ("Ready to Capture" / "Your TAAL device has been detected and is now ready. Please discard this recording and start a new one.") the first time a silent recording was detected since USB connect. **Correction to this doc's prior claim**: an earlier version of this file said this callback was "commented out entirely" — that was never accurate; the dialog was live in both `app` files (and in `stemz-app`'s production `RecordingFragment`) until this and an earlier session's changes. `PcgScaleRecordingFragment.kt`'s override was removed first (matching `stemz-app`'s PcgScale recorder, which never implemented it); production `RecordingFragment.kt`'s override was removed in this later session specifically because that screen became the live `startDestination` again (§3.1) and the dialog would otherwise be user-visible on every cold-start silent recording. Both now leave the callback at its default no-op in `TaalRecorder.OnInfoListener`. Verified absent live on-device across a full navigation pass this session (no dialog appeared at any point). `stemz-app`'s own `CalibratedRecordingFragment`/`FullTimeOnRecordingFragment` equivalents still have it — out of scope (those screens are deep-link/orphan only, §3.1).

---

## 7. Sharing

Two independent audio-only share code paths, both reachable (one from `SavedRecordingsFragment`, one from `RecordingLibraryFragment` — the latter itself unreachable per §3.1, but the code is identical in intent), plus a third, separate "share with graph" action added this session — see §8.3.

- `SavedRecordingsFragment.shareRecording()` (`ui/library/SavedRecordingsFragment.kt`): copies the on-disk `{FILTER}_{userInput}_filtered.wav` into a scratch `.share_tmp/` dir under a **cleaned display name** (stripping the filter prefix and `_filtered` suffix) so the recipient sees the name the user actually typed, not internal naming cruft. Wraps it in a `FileProvider` content URI (authority `${packageName}.fileprovider`) and fires `ACTION_SEND`.
- `RecordingLibraryFragment.shareRecording()` (`ui/library/RecordingLibraryFragment.kt`): shares the recording's DB `filePath` directly via the same `FileProvider`, no rename step.
- **Both explicitly set `type = "application/octet-stream"`, not `"audio/wav"` or `"audio/*"`** — several apps (WhatsApp named explicitly) treat an `audio/*` share as a voice-note/media attachment and **transcode it** (e.g. to AAC), corrupting the diagnostic recording. The generic MIME type routes the intent through each app's "send as document/file" path instead.
- **Sharing bugs — FIXED this session (2026-09-08), ported from `stemz-app`.** Prior to this fix, `RecordingLibraryFragment.shareRecording()` had two real bugs (found while auditing for this port, not previously documented): it built its `FileProvider` URI with authority `"${packageName}.provider"`, which does **not** match the manifest's actual `"${applicationId}.fileprovider"` — this would throw `IllegalArgumentException` (no FileProvider found for that authority) the moment this function was ever called, i.e. it was **completely broken**, latent only because this fragment is currently unreachable (§3.1). It also used `type = "audio/*"` (the exact transcoding problem the comment above warns about), not the safe generic type. Both are now fixed to match `SavedRecordingsFragment`'s pattern. Separately, `SavedRecordingsFragment.shareRecording()` used `type = "audio/wav"` (same transcoding risk) and shared the raw on-disk file directly (filter-prefix/`_filtered`-suffix cruft in the shared filename) — now fixed to `application/octet-stream` plus a cleanly-named temp copy, matching `RecordingLibraryFragment`. **Verified live on-device this session**: tapping the plain share button on a real saved recording opened the Android share sheet showing exactly `20260908_162505.wav` (the clean, correct name) as the one attachment.

---

## 8. PCG Segmentation Feature

- **Gate**: `ui/segmentation/SegmentationFeature.ENABLED` — a single `const val Boolean`, currently `true` (`SegmentationFeature.kt:11`). Its own doc comment states the intended contract: flip to `false` and the whole feature (button through screen through chart) disappears with no other file needing changes.
- **Entry points, current reality (updated 2026-09-16):**
  1. `ProductionReviewFragment`'s "Analyze Heart Sounds" button — **live**, reached only for an already-saved Production recording (§4.7). Sets `returnDestination=productionSavedRecordingsFragment` so the report's back button returns to Production's own list, not PcgScale's.
  2. `ProductionPlayerFragment` — **no longer has an Analyze button at all** (§4.7's simplification removed the dead gate entirely, since a fresh recording's raw companion is never inside `filesDir/saved/`).
  3. `PcgScaleReviewFragment`'s "Analyze Heart Sounds" button — **live**, reached only for an already-saved PcgScale-list recording. Gated on `SegmentationFeature.ENABLED` plus `filePath` containing `_filtered.wav` with a same-named `_raw.wav` companion existing on disk. Uses the default `returnDestination` (`savedRecordingsFragment`) — unchanged by the split.
  4. `PcgScalePlayerFragment`'s "Analyze Heart Sounds" button — wired but never actually visible: gated on the file already living inside `filesDir/saved/`, but this screen is only ever reached with a brand-new, not-yet-saved recording, so the gate never passes.
  5. Production `PlayerFragment`'s old Analyze wiring — **gone**, along with the rest of the `isNewRecording=false` branch removed in §4.7.
- **`SegmentationReportFragment`** (`ui/segmentation/SegmentationReportFragment.kt`): instantiates `TaalCardiacSegmentation` (from the `taal-segmentation` module, package `com.musediagnostics.taal.segmentation`), calls `segmentRawWav(rawFile, verboseLogging=true)` off the main thread, producing a `SegmentationOutcome` (`Ok` / `TooWeak` / `NoHeartSounds` / `Unavailable`). The chart (`pcgChart`, a `PcgSegmentationView`) is now (ported this session) drawn from a `PcgDisplayFilter.processOffline()`-conditioned copy of the raw audio — segmentation itself still runs on the untouched raw file, unaffected since the filter is zero-phase. Shows heart-rate, cycle count, duration, mean systolic interval; zoom presets (Full/10s/5s) re-center on the current view rather than resetting to 0. **Now has its own top bar + back button** (`res/layout/fragment_segmentation_report.xml` restructured from a plain `ScrollView` to a `ConstraintLayout` with a `topBar`) — the back button (and the device/gesture back action, via an `OnBackPressedCallback`) always lands on a caller-specified saved-list, not a plain `navigateUp()` (which would land back on whichever review screen was actually the caller). **Since the 2026-09-16 split (§4.7), this target is a `returnDestination` bundle int**, defaulting to `R.id.savedRecordingsFragment` (PcgScale's list, unchanged behavior) but overridden to `R.id.productionSavedRecordingsFragment` by `ProductionReviewFragment`. Verified live on-device for both paths. The `resultStatusRow` ("Trustworthy segmentation" banner) is now hidden specifically for the `Ok` outcome (ported this session) — the Low confidence/No heart sounds/Unavailable rows below are unaffected and still show.
- Result is cached in an `activityViewModels()`-scoped `SegmentationViewModel` so `SegmentationFullScreenFragment` (a landscape-locked full-screen chart, reached via `action_segmentationReport_to_segmentationFullScreen`) doesn't need to re-run inference after the orientation-triggered Activity recreation.
- **PDF export**: `savePdfToDownloads()` via `SegmentationPdfExporter` → MediaStore `Downloads` collection (API 29+) or direct `DIRECTORY_DOWNLOADS` write (API 24–28), MIME `application/pdf`. `downloadButton` is `visibility="gone"` in the XML but `showResult()` unconditionally sets it `VISIBLE` in code (pre-existing, untouched this session) — so it IS shown in practice, confirmed in the on-device screenshot from this session's test pass.
- Full pipeline internals live in `taal-segmentation-core` (pure Kotlin/JVM, unit-tested) + `taal-segmentation` (Android wrapper) — out of scope for this doc.
- **Live-device verification (2026-09-08)**: ran the full segmentation flow on the connected Samsung SM-A066B against a real saved recording — result: 68 bpm, 21 cycles detected, 19.0s duration, 307ms avg systolic interval, chart rendered correctly with S1/Systole/S2/Diastole legend. No crash.

### 8.1 Segmentation wiring — new files this session

None — no new Kotlin files were needed for segmentation wiring; it reuses the existing `SegmentationFeature`/`SegmentationReportFragment` machinery. New **resources**: `res/drawable/fragment_player.xml` (the analyze-icon vector, ported byte-identical from `stemz-app`'s copy of the same file) and an `analyzeButton` `ImageButton` added to `res/layout/fragment_pcgscale_player.xml` and `res/layout/fragment_pcgscale_review.xml`'s `topBar`.

### 8.2 "Clean Graph" toggle — ported from `stemz-app` this session (2026-09-08)

Display-only denoise toggle on `PcgScalePlayerFragment` and `PcgScaleReviewFragment` (not the recorder — the live recorder in this module applies no display filtering at all, unlike `stemz-app`'s causal `PcgLiveDisplayFilter`).

- **New source files**: `ecg/pcgscale/PcgDisplayFilter.kt` and `ecg/pcgscale/PcgSpectralGate.kt` — ported byte-identical from `stemz-app`'s copies (pure Kotlin, no Android dependency, plain-JVM testable; no unit tests were ported alongside them this session — `stemz-app`'s `PcgDisplayFilterTest.kt`/`PcgSpectralGateTest.kt` exist upstream on `StemzAppBranch` and would be a reasonable follow-up to bring over). Chain: `PcgClickRemover` (USB-glitch/click removal) → zero-phase 20–500 Hz band-pass + 50/100/150 Hz notches → `PcgSpectralGate` with the `DISPLAY` preset (transient-protected, mid-diastole noise profile). Never touches the saved file or playback audio.
- **UI**: a `denoiseRow` (`LinearLayout`) with a `denoiseOnBadge` (`TextView`, single rectangular tap-toggle — itself both the control and its own "ON"/"OFF" indicator) added between the amp-slider card and the scale caption in both `fragment_pcgscale_player.xml` and `fragment_pcgscale_review.xml`. New drawables `res/drawable/bg_status_pill_on.xml` (solid `#128CB2`) / `bg_status_pill_off.xml` (`#E8E8E8` with `#C8C8C8` stroke), both ported byte-identical from `stemz-app`.
- **Behavior**: default **ON** — both fragments call `onDenoiseToggled(true)` immediately after decode completes, matching `stemz-app`. First toggle-ON pays the `PcgDisplayFilter.processOffline()` cost off the main thread (`waveformLoadingIndicator` shown, already present in both layouts before this change); every subsequent toggle in either direction is instant (both sample arrays cached: `originalSamples`/`denoisedSamples` in the Player, `originalSamples`/`gatedSamples` in the Review — same field names `stemz-app` uses). The Y-axis full-scale is recomputed for whichever array is shown via `computeRenderPayload`/`applyRenderPayload` (new private helpers in both fragments, refactored out of the existing whole-file-scale logic so the toggle can re-run it on demand).
- **Not ported (deliberately)**: `stemz-app`'s recorder-side hum-filter switch and its causal live-display filtering — those apply to the recording screen; see §0/§8.4 for why this was skipped even in the later "port everything" pass.
- **Live-device verification (2026-09-08)**: opened a real saved recording via `PcgScaleReviewFragment` on the connected Samsung SM-A066B — screen title showed the real recording name, "Clean Graph" badge showed "ON" by default, grid+trace rendered correctly with the rev3 `PcgAmplitudeScale`. No crash.

### 8.3 "Share With Graph" — ported from `stemz-app` this session (2026-09-08)

A **second, separate** share action on `SavedRecordingsFragment`'s list rows — sends the `.wav` **plus a PNG and a PDF** of the whole recording rendered as a time-true, stacked-row graph strip in the Clean-Graph-ON state, alongside the completely untouched original audio-only share (§7). Full feature, ported essentially byte-for-byte from `stemz-app`'s copy (same package structure, same class names) since the namespace is identical between the two apps.

**New files** (`ui/graphshare/`, all new to `app` this session):

| File | Role |
|---|---|
| `GraphShareFeature.kt` | `object { const val ENABLED = true }` — rollback flag, same convention as `SegmentationFeature` |
| `PcgStripLayout.kt` | Pure Kotlin. Geometry: splits the recording into fixed-duration rows (default 5s/row), computes each row's absolute time range + pixel bounds, PDF page grouping (`paginate`) |
| `PcgWavDecoder.kt` | Pure Kotlin. Same 44-byte-header/16-bit-LE-PCM decode the fragments do inline — a 4th, isolated copy rather than a refactor of the two working screens |
| `RecordingDisplayName.kt` | Pure Kotlin. A 3rd copy of the `{FILTER}_{name}_filtered` → `{name}` stripping logic (`HEART_HARD` checked before `HEART` — not currently relevant to `app` since the Basic/Hard filter toggle wasn't ported, but harmless to keep for parity with `stemz-app`'s source) |
| `ShareRequest.kt` | Pure Kotlin descriptor (`ShareAction.SEND`/`SEND_MULTIPLE`, mime type, attachment paths) — plain-JVM testable, no `Intent`/`Uri` dependency |
| `PcgGraphStripRenderer.kt` | Android. Draws the full strip onto ANY `Canvas` (Bitmap for PNG, PDF page for PDF) — same renderer for both formats. Reuses `PcgScaleEcgPaperView`/`PcgTimeScale` unmodified for the grid; hand-draws the trace polyline (on-screen that's MPAndroidChart's `LineChart`, which only renders its 4s window) |
| `GraphShareExporter.kt` | Android. Two thin adapters over the one renderer: PNG (single tall `Bitmap`) and paginated PDF (`PdfDocument`, A4 landscape) |
| `GraphShareBundler.kt` | Android. Orchestrates: read file → `PcgWavDecoder.decode` → `PcgDisplayFilter.processOffline` (Clean-Graph-ON state) → `PcgAmplitudeScale.computeFullScaleForFile` → render → write `{name}.wav`/`.png`/`.pdf` → return a `ShareRequest` |

**`PcgScaleEcgPaperView.kt` gained two export-only methods** this session (`setGridAlpha`, `setGridStrokeWidthPx`) — never called by any on-screen fragment, exist solely for `PcgGraphStripRenderer` to render a darker/thicker grid than the live in-app trace (a low-alpha 0.7px on-screen hairline reads as invisible on a static PNG/PDF).

**UI wiring** (surgical, additive only, matching `stemz-app`'s pattern exactly):
- `item_saved_recording.xml` — new `shareWithGraphButton` (`ic_share_graph.xml`, new drawable), `visibility="gone"` by default.
- `SavedRecordingAdapter.kt` — new defaulted `onShareWithGraph: (File) -> Unit = {}` constructor param; button only ever set `VISIBLE` and its listener only ever registered inside `if (GraphShareFeature.ENABLED)`.
- `SavedRecordingsFragment.kt` — new `shareRecordingWithGraph(file)` + `launchShareRequest(request)`. **The original `shareRecording()` (§7) is untouched** beyond this session's separate sharing-bug fix.
- `fragment_saved_recordings.xml` — new `shareGraphProgressOverlay` (indeterminate spinner), gone by default, shown while `GraphShareBundler` runs.
- Writes to `filesDir/saved/.share_bundle_tmp/` — a different temp directory from `.share_tmp/` (§7), so the two share paths can never race or delete each other's in-flight files.

**Live-device verification (2026-09-08), full end-to-end, on the connected Samsung SM-A066B**:
- Tapped the new share-with-graph button on a real saved recording (18s, heart filter) — no crash, Android share chooser opened (confirmed via logcat: `ChooserActivityLauncher` intent fired).
- Confirmed on-device that `filesDir/saved/.share_bundle_tmp/` contained a correctly-named `.wav` (1.67MB), `.png` (183KB), and `.pdf` (194KB) — all three files actually written, not just claimed.
- Pulled and visually inspected the generated PNG: title `20260908_162505`, subtitle `19s · Clean Graph ON`, 4 stacked 5-second rows each with a correctly-rendered time grid (1 large box = 1s) and the heart-sound trace with clearly visible S1/S2 bursts, footer caption present. **No sign of the historical "only last row renders" `Canvas.drawColor()`-clip bug or the header/row-0 text-overlap bug** that `stemz-app`'s own SUPERMASTER doc records finding and fixing during its original build — both fixes (the per-row `clipRect()` and the proportional header text positioning) were carried over as part of the ported `PcgGraphStripRenderer.kt` and are confirmed working here too.

### 8.4 What was explicitly excluded from this port (2026-09-08 instruction)

Per explicit instruction, the following `stemz-app`-only things were **not** ported, even though "port everything" was the general ask:

1. **15-second recording auto-stop** — `app`'s PcgScale recorder keeps only its pre-existing 300s hard ceiling; no new auto-stop timer was added.
2. **Filter behavior** — the 5-preset filter row (Heart/Lungs/Bowel/Pregnancy/Full Body/Custom) and its underlying `taal-core` `PreFilter`/custom-bandpass wiring are completely unchanged; `stemz-app`'s Basic/Hard 2-button toggle replacement was not ported.
3. **App identity** — package name (`com.musediagnostics.taal`), display label ("TAAL Recorder" per the on-screen title, confirmed unchanged in this session's screenshots), and launcher icon are untouched. `stemz-app`'s "StemzApp"/teal-S branding was never relevant here and wasn't touched.
4. **Recorder-side Hum/rumble filter toggle** — see §0's reasoning: it wasn't clear whether this affects only display or also actual capture-time filtering, and given the explicit caution around not changing filter behavior, it was left out rather than guessed at.
5. **Real release `signingConfig`** — depends on the user's own keystore material, not something to fabricate; `app`'s release build remains unsigned (§2, §10).
6. **`stemz-app`-specific screens/duplication** — nothing was copied as a parallel "stemz" fork; every ported feature was applied directly onto `app`'s own existing `PcgScaleRecordingFragment`/`PcgScalePlayerFragment`/`PcgScaleReviewFragment`/etc. files, per instruction to keep the same screens and flow rather than create new ones.

---

## 9. UI Screen Inventory

Reachability column reflects §3.1 with `recordingFragment` (**Production**) as the current
`startDestination` (as of 2026-09-22 — see §11 for the full flip history and **re-check
`nav_graph.xml:6` yourself**, this has moved direction many times). If PcgScale is the
`startDestination` instead, swap "Yes"/"Deep-link"/"No" between the two flows' screens per
§3.0/§3.1 — everything else in this table (auth chain, drawer-gated, orphans) is unaffected
by which flow is live. "Deep-link" = reachable via `adb`/`taalapp://` only. "Orphan" = no
inbound edge and no code caller at all. "Auth-chain" = wired to other auth screens but the
chain's entry point is unreachable. "Drawer-gated" = reachable only through the drawer, which
now has **zero entry points anywhere in the app** (§4.8) — permanently unreachable, not just
currently.

| Fragment (package) | One-line purpose | Reachable? |
|---|---|---|
| `ui.SplashFragment` | 2s branding delay, then routes to recording/pinLogin/signIn based on `SharedPreferences` (`is_logged_in`, `pin_set`) | No (auth-chain; not the `startDestination`) |
| `ui.auth.SignInFragment` | Sign-in landing screen | No (auth-chain) |
| `ui.auth.LoginFragment` | Enter mobile number | No (auth-chain; also the drawer sign-out target) |
| `ui.auth.OtpFragment` | OTP verification | No (auth-chain) |
| `ui.auth.SignUpFragment` | New account sign-up | No (auth-chain) |
| `ui.auth.SetPinFragment` | Set app PIN | No (auth-chain) |
| `ui.auth.PinConfirmedFragment` | Confirm PIN | No (auth-chain) |
| `ui.auth.PinLoginFragment` | PIN-based login | No (auth-chain) |
| `ui.auth.FingerprintSetupFragment` | Enroll biometric unlock | No (auth-chain) |
| `ui.auth.FingerprintConfirmFragment` | Confirm biometric unlock | No (auth-chain) |
| `ui.recording.ProductionRecordingFragment` | **Production** recorder (renamed from `RecordingFragment`, §4.7): MPAndroidChart, 10s window, warmup/peak Y-axis, "Ready to Capture" dialog removed (§4.1) | **Yes — entry point** (Production is the `startDestination`) |
| `ui.recording.NewRecordingFragment` | "Experimental/NOT IN USE" recording UI, per its own header comment | **Yes, via the settings-gear button** — still-open issue, §10 |
| `ui.recording.TestRecordingFragment` | Dormant recorder test screen, explicitly marked not-in-use in-file | No (orphan) |
| `ui.player.TestPlayerFragment` | Dormant player counterpart | No (orphan) |
| `ui.player.ProductionPlayerFragment` | **Production** player for a fresh recording only (renamed + simplified from `PlayerFragment`, §4.7) — EQ, Save/Discard. No Analyze button, no `isNewRecording=false` handling anymore | Yes |
| `ui.player.ProductionReviewFragment` | **NEW (§4.7)** — Production's read-only review of an already-saved recording: EQ, Analyze Heart Sounds, real saved-name title. Same V7 rendering as the player | Yes |
| `ui.recording.ProductionSaveRecordingFragment` | **NEW (§4.7)** — Production's own fork of the Save screen; identical rename/copy-to-device-storage logic, lands on Production's own saved-list | Yes |
| `ui.library.ProductionSavedRecordingsFragment` | **NEW (§4.7)** — Production's own fork of the saved-recordings list; same `filesDir/saved/` listing, no share-with-graph button, opens `ProductionReviewFragment` | Yes |
| `ui.library.ProductionSavedRecordingAdapter` | Adapter backing the row above (§4.7) | N/A (adapter, no destination) |
| `ui.player.EqualizerFragment` | 6-band parametric EQ over a saved/temp WAV, shared by every player family in both flows | Yes (via whichever flow is live) |
| `ui.recording.CropRecordingFragment` | Trim a recording's start/end | No (orphan — no live call site found anywhere, see §3.1) |
| `ui.recording.EditRecordingFragment` | Edit a saved recording's metadata + re-amplify (0–10dB slider — see §10) | No (only from RecordingLibrary, drawer-gated) |
| `ui.library.RecordingLibraryFragment` | Patient-scoped recording library + share/rename/delete | No (drawer-gated, and the drawer has no entry point at all now — §4.8) |
| `ui.profile.ProfileFragment` | Doctor profile (name/photo, feeds drawer header) | No (drawer-gated) |
| `ui.settings.ChangePinFragment` | Reset app PIN | No (drawer-gated) |
| `ui.info.FaqFragment` | Static FAQ | No (drawer-gated) |
| `ui.info.PrivacyPolicyFragment` | Terms/privacy | No (drawer-gated) |
| `ui.info.SubscriptionFragment` | Subscription info | No (drawer-gated) |
| `ui.info.UserManualFragment` | User manual | No (drawer-gated) |
| `ui.info.AboutUsFragment` | About screen | No (drawer-gated) |
| `ui.LoadingFragment` | Generic loading spinner destination | No (orphan) |
| `ui.library.SharedRecordingsFragment` | (Declared, unimplemented usage) | No (orphan) |
| ~~`ui.patient.AddPatientFragment`~~ | **DELETED 2026-09-09** (§4.8) — used to search/create a patient to attach to a recording; was the module's only `openDrawer()` call site | N/A — class no longer exists |
| ~~`ui.patient.PatientSearchAdapter`~~ | **DELETED 2026-09-09** — adapter for the above | N/A |
| ~~`ui.patient.PatientViewModel`~~ | **DELETED 2026-09-09** — the only code that ever wrote `PatientEntity`/`RecordingEntity` rows (§5) | N/A |
| `ui.recording.SaveRecordingFragment` | **PcgScale/Calibrated/FullTimeOn's** Save screen (renames temp → `filesDir/saved/`, MediaStore copy). Production has its own fork now (§4.7), this one is unchanged | Yes, when PcgScale is the `startDestination` (deep-link-reachable otherwise) |
| `ui.library.SavedRecordingsFragment` | **PcgScale-only since the split (§4.7)** — lists `filesDir/saved/` WAVs, share/delete, opens `PcgScaleReviewFragment`. Has the "share with graph" button (§8.3) | Yes, when PcgScale is the `startDestination` |
| `ui.graphshare.*` | Share-with-graph feature (§8.3) — PcgScale-only, no UI of its own, backs `ui.library.SavedRecordingsFragment`'s button | N/A (logic package) |
| `ui.calibrated.CalibratedRecordingFragment` | Calibrated-fork recorder, mm-accurate paper, fixed Y-axis | Deep-link only (`taalapp://calibrated`) |
| `ui.calibrated.CalibratedPlayerFragment` | Calibrated-fork player | Deep-link only |
| `ui.calibrated.DpiCalibrationFragment` | Physical-ruler DPI calibration | Deep-link only |
| `ui.fulltimeon.FullTimeOnRecordingFragment` | Fork of Calibrated, now diverged (preview state, warmup Y-axis) | No (orphan, no deep link) |
| `ui.fulltimeon.FullTimeOnPlayerFragment` | FullTimeOn-fork player | No (orphan) |
| `ui.pcgscale.PcgScaleRecordingFragment` | Time-grid + 60%-fill RMS auto-scale recorder. "Ready to Capture" dialog removed. Pre-amp default 10dB, RECORD_AUDIO requested on open (§0) | Deep-link only (`taalapp://pcgscale`) while Production is the `startDestination` |
| `ui.pcgscale.PcgScalePlayerFragment` | PcgScale-fork player for a brand-new recording. Has Clean Graph toggle (§8.2). Save button fixed 2026-09-09 to actually route through `SaveRecordingFragment` (§4.6/§4.8) | Deep-link only (reached only via the recorder above) |
| `ui.pcgscale.PcgScaleReviewFragment` | Read-only PcgScale review for an already-saved recording. Has Clean Graph toggle (§8.2) and a live segmentation Analyze button (§8) | Deep-link only |
| `ui.segmentation.SegmentationReportFragment` | Heart-sound segmentation report + chart. `returnDestination` arg (§4.7/§8) picks which flow's saved-list "back" returns to | Yes, from either flow's review screen |
| `ui.segmentation.SegmentationFullScreenFragment` | Landscape full-screen segmentation chart | Yes, transitively (from the above) |
| `ui.MainActivity` | Single Activity, NavHost + Drawer host (drawer now has no entry point in-app, §4.8) | N/A (always live) |

---

## 10. Known Issues / Gotchas

1. **Pre-amp dB range mismatch — confirmed still live.** SDK-level clamp (`TaalRecorder`/`AudioFilterEngine` in `taal-core`) and every recorder screen's slider in this module allow **0–30 dB**. `EditRecordingFragment`'s amplify slider is hardcoded to **0–10 dB** (`res/layout/fragment_edit_recording.xml:207-208`), labeled explicitly in dB and written straight into `RecordingEntity.preAmplification`. Currently latent rather than user-visible since `EditRecordingFragment` is itself unreachable (§3.1).
2. **Drawer / auth-chain / `RecordingLibraryFragment` are now PERMANENTLY unreachable, not just currently** — `AddPatientFragment` (the module's only `openDrawer()` call site) was deleted outright 2026-09-09 (§4.8), not just left orphaned. There is no code path anywhere in the module that can open the drawer until someone adds a new call site. Segmentation is unaffected (§8) — both flows' Analyze buttons are direct nav actions off their own review screen.
3. **`fallbackToDestructiveMigration()` on `TaalDatabase`** (`TaalDatabase.kt:33`) — any future entity/schema change without a real `Migration` silently drops all existing patients/recordings on the next app upgrade.
4. **`FullTimeOnRecordingFragment`'s own header comment is stale** — claims behavior identical to Calibrated; it has since diverged (§4.5).
5. **`PcgScalePlayerFragment` carries dead branches**, including its own segmentation Analyze-button gate, which can never pass given how this screen is reached (§4.6, §8) — now reachable only via deep link anyway (§3.1).
6. **Manifest deep-link comment is stale/incomplete** — only describes `taalapp://calibrated`, not the sibling `taalapp://pcgscale` host.
7. **`RecordingFragment.kt` (production)'s "Ready to Capture" dialog — REMOVED, resolved 2026-09-08 (later session).** Was flagged as still-live in an earlier revision of this doc; now fixed to match `PcgScaleRecordingFragment.kt` — see §6.8 for the full history and live-device verification.
8. **`app`'s release build has no `signingConfig`** (§2) — unlike `stemz-app`. Deliberately left unsigned this session (§0/§8.4) — porting `stemz-app`'s config would require its keystore secrets, which aren't something to reuse or fabricate for `app`'s own release identity. Needs the user's own keystore material to resolve.
9. **`app` does not depend on `taal-ui-kit`** — only `taal-core` (audio engine) + `taal-segmentation`. All UI in this module is bespoke, not SDK-provided.
10. **Sharing intentionally avoids `audio/*` MIME types** (§7) to prevent WhatsApp and similar apps from transcoding the WAV to AAC. Confirmed correctly applied everywhere as of this session's sharing-bug fix (§7).
11. **`AppBranch`'s `PcgAmplitudeScale` gap — CLOSED 2026-09-08.** Was on the older mean-of-peaks algorithm; now has the rev3 median/Kth-largest-RMS algorithm, matching `stemz-app`. See §0.
12. **Previous version of this doc's claim that `onSilentRecordingDetected` was "commented out entirely" in `PcgScaleRecordingFragment.kt` was inaccurate** — corrected in §6.8. The dialog was live in both `app` and `stemz-app` until sessions on 2026-09-08 removed it from both of `app`'s recorder screens.
13. **`ProductionRecordingFragment`'s settings-gear button opens `NewRecordingFragment`, a screen explicitly marked "NOT IN USE (ONLY TESTING BY KUNAL)" / "experimental" in its own header comment** (`NewRecordingFragment.kt:3,36-37`) — **currently LIVE** (Production is the `startDestination` as of 2026-09-22, §3.1). **Still unresolved** — if this button is meant to reach a real settings screen in production, it currently does not; verify with the team before shipping a build with `recordingFragment` as the `startDestination`.
17. **Patient/Recording DB tables are now permanently write-orphaned** (§5) — deleting `AddPatientFragment`/`PatientViewModel` (§4.8) removed the only code that ever inserted into `patients`/`recordings`. The schema, DAOs, and `RecordingLibraryFragment` (the only reader) are all still present and would work if something new wrote to the tables, but nothing does today.
18. **Two nearly-identical Save/SavedRecordings implementations now exist** (`SaveRecordingFragment`/`SavedRecordingsFragment` for PcgScale, `ProductionSaveRecordingFragment`/`ProductionSavedRecordingsFragment` for Production, §4.7) — a bug fix to the file-rename/MediaStore-copy logic found in one will very likely apply to the other too; check both before considering such a fix complete.
14. **`CropRecordingFragment` reachability claim corrected** — an earlier version of this doc stated it was "only reachable from production player," but no live call site exists anywhere in `PlayerFragment.kt` or elsewhere (grepped 2026-09-08, zero hits for "crop"/"Crop"). Treat it as a pure orphan (§3.1) until a real caller is found.
15. **Recorder-side Hum/rumble filter and the causal live-display filter were intentionally NOT ported** from `stemz-app` (§0/§8.4) — the live recorder in `app` still applies zero display conditioning to the live trace; only the Player/Review screens' Clean Graph toggle (offline, §8.2) exists.
16. **`stemz-app`'s 15s auto-stop and Basic/Hard filter toggle were intentionally NOT ported** (§0/§8.4) — `app`'s PcgScale recorder still has the original 5-preset filter row and only the pre-existing 300s ceiling, per explicit instruction.
19. **16KB page-size support — FIXED 2026-09-23.** `taal-segmentation` pinned ONNX Runtime 1.19.2, whose JNI `.so` isn't 16KB-aligned, so segmentation could fail to load on 16KB-page Android 15/16 devices. Now 1.29.0 (`taal-segmentation/build.gradle.kts:48`). Related compatibility notes (not fixed, just known): the app is `targetSdk = 34` (runs on Android 14 and 16; Android 16's large-screen/edge-to-edge enforcement only applies to apps targeting 35/36, but Google Play requires a newer target for store uploads), `MainActivity` is locked to portrait (`AndroidManifest.xml:40`), and there are no tablet-specific (`sw600dp`) layouts — tablets get the phone layout.

---

## 11. Changelog

| Date | Change | Notes |
|---|---|---|
| 2026-09-08 | `SUPERMASTER_APP.md` (re)created on `AppBranch` | `AppBranch` cut from `main` this same day and had no copy of this file (it previously existed only on `StemzAppBranch`, describing a divergent `app` state — see §0). Rebuilt from that version's structure with corrections for `AppBranch`'s actual source: `PcgAmplitudeScale` algorithm version, the "Ready to Capture" dialog's real (live, not disabled) prior state, and the three features landed today (next row). |
| 2026-09-08 | Ported 3 features from `stemz-app` into `app`'s PcgScale family | (1) Removed the "Ready to Capture" silence-detection dialog from `PcgScaleRecordingFragment.kt` (§6.8) — matches `stemz-app`, which never had it. (2) Added a "Clean Graph" denoise toggle to `PcgScalePlayerFragment`/`PcgScaleReviewFragment` (§8.2), pulling in `PcgDisplayFilter.kt`/`PcgSpectralGate.kt` as new files (byte-identical ports from `stemz-app`). (3) Wired the segmentation "Analyze Heart Sounds" entry point onto both PcgScale Player and Review fragments (§8), adding two new nav actions and an `analyzeButton` to both layouts — segmentation is now reachable in the live app for the first time, via `PcgScaleReviewFragment`. Verified via `:app:compileDebugKotlin` (BUILD SUCCESSFUL). Branch: `AppBranch`. |
| 2026-09-08 | `startDestination` reverted from `pcgScaleRecordingFragment` back to production `recordingFragment` | Explicit request ("bring back the production screen to the app for now"), `nav_graph.xml:6`. Ripple effects fully re-audited: production `RecordingFragment`/`PlayerFragment` (for a fresh recording) are live again (§4.1); the PcgScale recorder/player pair is now deep-link-only (§4.6) but `PcgScaleReviewFragment` remains fully live and is now the module's de facto main review screen, since `SavedRecordingsFragment` always opens it regardless of recorder family (§3.1/§4.6) — so the segmentation entry point added earlier today is unaffected. Drawer/auth-chain/`AddPatientFragment`/`RecordingLibraryFragment` remain unreachable, just via a different closed loop (§3.1). New issue surfaced: `RecordingFragment`'s settings-gear button now live-opens the explicitly-experimental `NewRecordingFragment` (§10 #13). Also corrected a stale claim about `CropRecordingFragment`'s reachability found during this re-audit (§10 #14). Verified via `:app:compileDebugKotlin` + `:app:processDebugResources` (BUILD SUCCESSFUL). Branch: `AppBranch`. |
| 2026-09-08 | Full "port everything from `stemz-app`" pass, with explicit exclusions, + live on-device testing | Per explicit instruction: ported the `PcgAmplitudeScale` rev3 algorithm + its test file (§0), the sharing-bug fixes in `RecordingLibraryFragment`/`SavedRecordingsFragment` (§7 — found and fixed a real, previously-undocumented `FileProvider` authority bug), `PlayerFragment`/`SegmentationReportFragment` polish (saved-name titles, report screen top bar + back-to-Saved-Recordings behavior, §8), the entire "Share With Graph" feature as a new `ui/graphshare/` package (§8.3), PcgScale recorder pre-amp default 5→10dB, and RECORD_AUDIO requested on screen-open instead of first-tap. Also removed the "Ready to Capture" dialog from production `RecordingFragment.kt` (§6.8, §10 #7) — necessary because the earlier same-day `startDestination` revert made that screen live again. **Deliberately excluded** (§0, §8.4): the 15s auto-stop, the Basic/Hard filter toggle replacement, any app name/branding change, the recorder-side Hum filter, and `stemz-app`'s real release `signingConfig`. Verified via `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (all passing), and `:app:assembleDebug`, then **installed and live-tested on the connected Samsung SM-A066B over the existing ADB connection**: launched the app, confirmed no "Ready to Capture" dialog anywhere, confirmed filters unchanged, exercised Saved Recordings → both share buttons (audio-only share sheet showed the correct clean filename; share-with-graph produced and inspected a real wav+png+pdf bundle — the PNG was pulled off-device and visually confirmed to render a correct 4-row time-true grid with S1/S2 trace, no clip or header-overlap bugs), opened `PcgScaleReviewFragment` (real recording name in title, Clean Graph ON badge), ran a live segmentation (68 bpm / 21 cycles / 19.0s / 307ms avg systolic — chart rendered correctly), and confirmed the segmentation report's back button correctly returns to Saved Recordings. Could not live-test an actual new TAAL recording (no physical stethoscope hardware attached to the test phone — recorder correctly showed "TAAL device not connected" rather than crashing), so the no-15s-limit behavior is verified by code inspection only, not a live recording. Branch: `AppBranch`, nothing committed. |
| 2026-09-08 | `startDestination` flipped BACK to `pcgScaleRecordingFragment` (3rd flip today) | Explicit request ("make [the PcgScale screens] the start" to test/work with them), `nav_graph.xml:6`. Full flip history today: `pcgScaleRecordingFragment` (pre-existing TEMP value) → `recordingFragment` (session 2, "bring back the production screen") → `pcgScaleRecordingFragment` (this change). Re-audited §3.1/§4.1/§4.6/§9/§10 accordingly: the whole PcgScale family (recorder, player, review) is live again; production `recordingFragment`/`playerFragment` and the settings-gear→`NewRecordingFragment` edge (§10 #13) are dormant again, reachable only by hand-editing this attribute (no deep link exists for production, unlike PcgScale's `taalapp://pcgscale`). Drawer/auth-chain reachability conclusion is unchanged (still a closed loop, just via `PcgScalePlayerFragment`'s `isNewRecording==false` branch instead of production's). Rebuilt (`:app:assembleDebug`, BUILD SUCCESSFUL), reinstalled on the connected Samsung SM-A066B, and confirmed live via screenshot: "PCG Scale Recorder" title, 10dB pre-amp default, time-true grid, all filters intact, no dialog. Branch: `AppBranch`, nothing committed. |
| 2026-09-09 | Fixed `PcgScalePlayerFragment`'s broken Save button; deleted the entire `AddPatientFragment` flow | User asked to verify the save flow from the PcgScale Player screen — found it was completely broken (§4.8): Save navigated straight to `AddPatientFragment` with only the temp filtered path, `rawFilePath` silently dropped, no file ever moved into `filesDir/saved/`, so a fresh recording became permanently invisible in the UI the moment you tapped Save. Fixed to route through `action_pcgScalePlayer_to_saveRecording` → `SaveRecordingFragment` with explicit `popUpToDestination=pcgScaleRecordingFragment`. Then, per explicit instruction (given the choice between "just reroute" and "delete outright"), **deleted `AddPatientFragment`/`PatientSearchAdapter`/`PatientViewModel`** and all six `action_*_to_addPatient` nav edges entirely (§4.8) — every Player family's save/discard-dialog SAVE branch (production, Calibrated, FullTimeOn, Test, PcgScale) now just toasts "Recording already saved" and backs out instead. Known accepted consequence: the drawer's only `openDrawer()` call site is gone, so the drawer is now permanently unreachable from anywhere in the app (§3.1, §10 #2). `PatientEntity`/`RecordingEntity` tables left intact but now write-orphaned (§5, §10 #17). Verified via `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (all passing), `:app:assembleDebug`, and a live-device pass confirming a fresh recording's Save button now correctly lands in `filesDir/saved/`. `startDestination` was `pcgScaleRecordingFragment` throughout this entry. Branch: `AppBranch`, nothing committed. |
| 2026-09-16 (date on the phone at time of this change) | `startDestination` flipped to `recordingFragment` (Production) for a one-off test, then the whole module was split into two independent flows | First, explicit request to test the production recorder/player/save flow once — flipped `nav_graph.xml:6` to `recordingFragment`, rebuilt, live-verified ("TAAL Recorder" screen, filters/pre-amp/no dialog all correct). Then, explicit request: keep every existing PcgScale screen completely untouched, but give Production its own full parallel flow — recorder through review — under consistently "Production"-named files, so switching `startDestination` switches between two genuinely separate experiences instead of two recorders feeding the same shared tail. Executed as §4.7: renamed `RecordingFragment.kt`→`ProductionRecordingFragment.kt` and `PlayerFragment.kt`→`ProductionPlayerFragment.kt` (+ their layouts), simplified the latter to drop its now-obsolete `isNewRecording=false` branch, and built 4 brand-new files (`ProductionSaveRecordingFragment`, `ProductionSavedRecordingsFragment`, `ProductionSavedRecordingAdapter`, `ProductionReviewFragment`) plus their layouts as fresh forks of the PcgScale-side originals (which remain byte-for-byte unmodified other than the pre-existing 2026-09-09 save fix, §4.6). The only PcgScale-touching change anywhere in this pass was an additive `returnDestination` bundle arg on the shared `SegmentationReportFragment`, defaulting to the exact previous hardcoded behavior. Verified via `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (all passing), `:app:assembleDebug`, and a full live-device walkthrough on the connected Samsung SM-A066B (folder → saved recording → `ProductionReviewFragment` with correct name/V7 rendering/active Analyze button → segmentation report → back correctly returned to Production's own list, not PcgScale's). `startDestination` remains `recordingFragment` (Production) as of this entry — re-check `nav_graph.xml:6` before trusting that. Branch: `AppBranch`, nothing committed until this session's commit (see next entry if present). |
| 2026-09-23 | ONNX Runtime bumped 1.19.2 → 1.29.0 in `taal-segmentation/build.gradle.kts` (16KB page-size fix) | 1.19.2's `libonnxruntime4j_jni.so` wasn't 16KB-page aligned, so on 16KB-page devices (some Android 15/16 hardware) the native lib could fail to load and "Analyze Heart Sounds" would become unavailable (§10 #19). Same bump `StemzAppBranch` already had since 2026-09-04 (`01ddf76`) — ported the one-line change only, nothing else from that commit. Shared module: affects every consumer of `taal-segmentation` built from this branch (`app`, and `AppBranch`'s own copy of `stemz-app`). No `app/` source changed. Verified: `:app:assembleDebug` + `:app:testDebugUnitTest` + `:taal-segmentation-core:test` green; `zipalign -c -P 16` on the debug APK reports every `lib/*/libonnxruntime*.so` OK for all 4 ABIs. Not yet re-tested on-device (segmentation run) after the bump. Branch: `AppBranch`. |
