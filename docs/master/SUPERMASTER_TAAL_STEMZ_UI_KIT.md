# SUPERMASTER_TAAL_STEMZ_UI_KIT.md — `taal-stemz-ui-kit` Module Reference

**Read this before touching any code in `taal-stemz-ui-kit`.** Written 2026-09-25 by the session
that created the module; verified against source and against the built release AAR.

---

## 1. Overview

Stemz-only pre-built screens, shipped as `taal-stemz-ui-kit.aar` on top of
`taal-stemz-core.aar` (`api(project(":taal-stemz-core"))`). Every screen is the **stemz-app
PcgScale version** (time-true grid: 1 large box = 1 s, 1 small box = 0.2 s; zoom impossible;
60%-fill auto-scaled Y axis). Built on **StemzAppBranch only**. `stemz-app` itself was NOT
changed — the UI kit is built from copies of its code.

| | |
|---|---|
| Gradle module | `:taal-stemz-ui-kit` |
| Namespace / package | `com.musediagnostics.taal.stemz.uikit` |
| minSdk / compileSdk | 26 / 34, Java/Kotlin 17, viewBinding |
| Resource prefix | **`tsuk_`** on every resource (`resourcePrefix = "tsuk_"`) |
| Version | 1.0.0 (`TaalStemzUiKit.VERSION`) |
| Release AAR | `taal-stemz-ui-kit/build/outputs/aar/taal-stemz-ui-kit-release.aar` (~559 KB) |

## 2. Public API

| Class | Purpose |
|---|---|
| `TaalRecorderActivity.getIntent(context, preFilter = LITE, preAmplification = 10, autoStopSeconds = 15, publicFolderName = "Stemz Recordings", heartSoundAnalysis = true)` | Full flow starting at the recorder. Result: `RESULT_OK` + `RESULT_FILE_PATH` (last saved `_filtered.wav`) if anything was saved |
| `TaalPlayerActivity.getIntent(context, filePath, heartSoundAnalysis = true)` | Opens one saved `_filtered.wav` on the review screen |
| `TaalSavedRecordingsActivity.getIntent(context, heartSoundAnalysis = true)` | Opens the saved list |
| `TaalStemzUiKit` | `fileProviderAuthority(context)` = `${packageName}.taalstemz.fileprovider`; `suspend exportGraphPdf(context, wavFile, outputPdf)` — the same PDF the share button attaches |
| `graph.PcgScaleEcgPaperView`, `graph.PcgScaleWaveformView` | The PcgScale grid + waveform views, public so integrators can build their own screens |
| `segmentation.PcgSegmentationView` | Segmentation chart view |
| `StemzHostActivity` | Public abstract base of the 3 activities (internal constructor) — not for direct use |

Settings travel as intent extras (`StemzUiConfig`, internal) and every screen reads them from
its host activity's intent (`StemzUiConfig.from(activity)`), so one `getIntent(...)` configures
the whole flow.

## 3. Screens (one nav graph `res/navigation/tsuk_nav_taal_stemz.xml`)

`StemzHostActivity` inflates the graph and sets the start destination per entry activity.

| Destination | Class | From stemz-app | SDK changes |
|---|---|---|---|
| `pcgScaleRecordingFragment` | `recording/PcgScaleRecordingFragment` | `ui/pcgscale/` | Lite/Hard (was Basic/`HEART_HARD`) via core `PreFilter`; Custom kept; hum switch removed (code + layout); auto-stop & pre-amp from config; placement dialog always shows heart image |
| `pcgScalePlayerFragment` | `player/PcgScalePlayerFragment` | same | always a new recording: Save → name screen, Discard → confirm; removed Save/Discard dialog, Add Patient branch, dead Analyze gate |
| `saveRecordingFragment` | `player/SaveRecordingFragment` | `ui/recording/` | public folder from config (default "Stemz Recordings"); reports saved path to `StemzHostActivity.onRecordingSaved` (→ activity result); pops to recorder |
| `savedRecordingsFragment` | `player/SavedRecordingsFragment` | `ui/library/` | share = wav + graph PDF only (no audio-only path, no feature flag); own FileProvider authority; back finishes the activity when it's the first screen |
| `pcgScaleReviewFragment` | `player/PcgScaleReviewFragment` | `ui/pcgscale/` | Analyze gated by config instead of `SegmentationFeature` |
| `segmentationReportFragment`, `segmentationFullScreenFragment` | `segmentation/…` | `ui/segmentation/` | package + visibility only (hidden "Download report" PDF kept as in stemz-app) |

Also: `recording/FilterPlacementDialog` (explicit image list instead of `getIdentifier`, so it
survives the `tsuk_` prefix and integrators' resource shrinking), `player/SavedRecordingAdapter`,
`share/*` (graph-PDF bundle: `GraphShareBundler`, `GraphShareExporter` PDF-only,
`PcgGraphStripRenderer`, `PcgStripLayout`, `RecordingDisplayName`, `ShareRequest`).

**Saved-file naming:** `filesDir/saved/{LITE|HARD|CUSTOM}_{name}_filtered.wav` + `_raw.wav`
(stemz-app used `HEART_`/`HEART_HARD_`). `RecordingDisplayName` is the single parser.

## 4. Resources

Ported with `scratchpad/port_resources.py` (session tool, not in repo): closure of everything
the copied layouts/Kotlin reference in stemz-app, each renamed `tsuk_<name>`, references and
view-binding class names rewritten (`TsukFragment…Binding`). IDs are NOT prefixed. Hand edits
after the port: "Basic" → "Lite" label, hum-switch block removed, retired share-with-graph button
removed, placement PNG (6.7 MB) → `tsuk_placement_heart_1.webp` (322 KB, same 1984×2150,
visually checked). New: `tsuk_activity_taal_stemz_host.xml`, `tsuk_nav_taal_stemz.xml`,
`xml/tsuk_file_paths.xml` (exposes `files/saved/` only). Theme `tsuk_Theme.Taal` (teal, as
stemz-app). Colors in `values/tsuk_colors.xml` are the override points for restyling.

## 5. Manifest (merged into integrators' apps)

RECORD_AUDIO, WRITE_EXTERNAL_STORAGE (maxSdk 28), usb.host feature; FileProvider
`${applicationId}.taalstemz.fileprovider`; the 3 activities, `exported="false"`, portrait,
theme `tsuk_Theme.Taal`. The full-screen segmentation chart rotates itself to landscape
programmatically (as in stemz-app).

## 6. Build / obfuscation

`proguard-rules.pro`: keeps the public API above; keeps **names** (not bodies) of all
Fragments/ViewModels because the nav graph instantiates them by class name;
`-repackageclasses 'com.musediagnostics.o.uikit'` (every obfuscated class goes into this SDK's own package — without it BOTH SDKs emitted top-level `a.a`, `b.c`… and an app using both AARs failed with **"Duplicate class a.a"**; found 2026-09-25 by building a separate consumer project), `-dontusemixedcaseclassnames`; strips `Log.v/d/i`. `consumer-rules.pro` mirrors that for
integrators. MPAndroidChart is an `api` dependency (public `PcgScaleWaveformView.chart`) —
integrators using the `.aar` must add it + JitPack themselves.

## 7. Tests (plain JVM, 20, all passing 2026-09-25)

`share/PcgStripLayoutTest`, `share/ShareRequestTest` (from stemz-app), `share/RecordingDisplayNameTest` (rewritten for LITE/HARD/CUSTOM).

## 8. Known issues / next steps

- **Not yet run on a device.** Test plan (user's choice): a person builds a separate app from `docs/stemz-sdk/STEMZ_SDK_TEST_GUIDE.md` using only the 2 AARs (hand-off folder `dist/taal-stemz-sdk-1.0.0/`, AARs gitignored) — no decompiling allowed, stop-and-ask on any gap; the guide's exact code was built here against the release AARs (debug + minified release) on 2026-09-25.
- KDoc in copied files still mentions stemz-app/app classes and history (not shipped; tidy later).
- Integration guide for the stemz team not written yet (next step after SDKs).

## 9. Changelog

| Date | Change | Notes |
|---|---|---|
| 2026-09-25 | Module created (v1.0.0) | All 7 stemz-app PcgScale-flow screens + 3 entry activities + `TaalStemzUiKit`; `tsuk_` resource prefix; wav+pdf share; obfuscated release AAR. |
| 2026-09-25 | Obfuscated classes repackaged into `com.musediagnostics.o.uikit` | Fixes "Duplicate class a.a" when an app uses both AARs. Verified: a separate consumer project (only the 2 AAR files + the test guide's code) builds debug AND minified release. |
| 2026-09-25 | Test integration guide written | `docs/stemz-sdk/STEMZ_SDK_TEST_GUIDE.md`: 2-button test app (UI kit + own core-only screen); code verified by building it against only the 2 AARs. |
