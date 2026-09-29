# SUPERMASTER.md — TAAL SDK Monorepo Master Reference

**Read this file first.** This is the single entry point for understanding this repo — what's in it, how to set it up, how to run each app, and where deeper docs live. Written for a developer or AI agent with zero prior context cloning this repo fresh.

Everything below was verified directly against source as of **2026-08-28**. Where an older doc or a prior AI's memory disagrees with what's actually in the code, this file states the live/current truth and calls out the discrepancy.

---

## 1. Project Overview

**TAAL** is a digital stethoscope product by **MUSE Diagnostics**. This repo is a Gradle multi-module Android monorepo containing:

- Two **SDK library modules** (`taal-core`, `taal-ui-kit`) that wrap the TAAL USB audio hardware into a reusable recording/playback/filtering API plus optional pre-built UI.
- Several **consumer apps** built on top of those SDKs, each a distinct product/branding experiment (main clinical app, a lungs-only app, a guided-auscultation app, a heart-sound-segmentation research app, and a rebrand fork).
- A **PCG heart-sound segmentation** feature/module pair, mostly pure-Kotlin signal processing plus an Android UI layer.

Root Kotlin package for the SDKs and most apps: `com.musediagnostics.taal`. Root Gradle project name: **"TAAL SDK"** (see `settings.gradle.kts`).

---

## 2. Repo Structure / Module Map

All modules below are declared in `settings.gradle.kts`. Every Android module targets `compileSdk = 34`, `targetSdk = 34` unless noted.

| Module | Type | Namespace | applicationId | minSdk | Purpose |
|---|---|---|---|---|---|
| `taal-core` | Android library | `com.musediagnostics.taal` | — | 24 | Pure audio engine: USB capture, DSP filters, WAV I/O. No UI. |
| `taal-ui-kit` | Android library | `com.musediagnostics.taal.uikit` | — | 24 | Pre-built recording/player/save/library screens on top of `taal-core`. |
| `app` | Android app | `com.musediagnostics.taal.app` | `com.musediagnostics.taal` | **26** | Main clinical app: patients, recordings, EQ, ECG-paper waveform screens, PCG segmentation. |
| `lungs-app` | Android app | `com.musediagnostics.taal.lungs` | `com.musediagnostics.taal.lungs` | 24 | Lungs-only auscultation app; multi-session recording, denoiser. |
| `visualizertaal-app` | Android app | `com.musediagnostics.taal.visualizer` | `com.musediagnostics.taal.visualizer` | 24 | Guided point-by-point Heart/Lungs auscultation with numbered Sessions. Only module depending on **both** `taal-core` and `taal-ui-kit`. |
| `taal-segmentation-core` | **Pure Kotlin/JVM** library (`kotlin("jvm")`, JVM toolchain 17) | — | — | — | Heart-sound segmentation algorithm core. No Android dependency; has its own JUnit5 test suite. |
| `taal-segmentation` | Android library | `com.purnacardio.signal.pcg.android` | — | 26 | Android wrapper around `taal-segmentation-core` for use inside apps. |
| `stemz-app` | Android app | `com.musediagnostics.taal.app` ⚠️ | `com.musediagnostics.taal.stemz` | 26 | **Confirmed real, not a stub.** A near-complete duplicate/fork of `app` — same package tree (`ui/auth`, `ui/library`, `ui/patient`, `ecg/calibrated`, etc.), same `TaalDatabase`, same dependency set (`taal-core` + `taal-segmentation`). Different `applicationId` so it installs alongside `app` without conflict, but it **reuses `app`'s Android namespace** (`com.musediagnostics.taal.app`) — almost certainly a copy-paste that was never renamed. Treat as a rebrand-in-progress, not a throwaway. |

**Discrepancy resolved:** auto-memory/CLAUDE.md notes flagged `stemz-app` as unverified ("appeared from unrelated concurrent work"). It is real, builds against the same modules as `app`, and has a full UI tree — not a stub.

---

## 3. Prebuilt AAR Files

Two pre-built release AARs live at the **repo root** (pushed alongside source, not gitignored despite the `*.aar` rule in `.gitignore` — they were force-added deliberately so a fresh clone is immediately usable without a Gradle build):

- `taal-core.aar` — compiled `taal-core` module.
- `taal-ui-kit.aar` — compiled `taal-ui-kit` module.

To rebuild them yourself: `./gradlew :taal-core:assembleRelease :taal-ui-kit:assembleRelease`, output lands in `taal-core/build/outputs/aar/taal-core-release.aar` and `taal-ui-kit/build/outputs/aar/taal-ui-kit-release.aar`.

### Using the AARs in a fresh consumer project

1. Copy both `.aar` files into the consumer project's `app/libs/` directory.
2. In the consumer app's `build.gradle.kts`:
   ```kotlin
   dependencies {
       implementation(files("libs/taal-core.aar"))
       implementation(files("libs/taal-ui-kit.aar"))
       implementation("com.github.PhilJay:MPAndroidChart:v3.1.0") // taal-ui-kit transitive dep
   }
   ```
3. In the consumer project's `settings.gradle.kts`, add the JitPack repo (required for the MPAndroidChart dependency — it's not on Maven Central):
   ```kotlin
   dependencyResolutionManagement {
       repositories {
           google()
           mavenCentral()
           maven { url = uri("https://jitpack.io") }
       }
   }
   ```
4. Manually declare any Activities you use from `taal-ui-kit` (e.g. `TaalRecorderActivity`, `TaalPlayerActivity`) in the consumer app's `AndroidManifest.xml` — they are not auto-merged from the AAR's own manifest in all consumer setups; verify by checking the merged manifest after first build.

---

## 4. Prerequisites & Setup

- **Android Studio**: a recent stable release compatible with AGP 8.8.1 (Android Studio Ladybug/Koala-era or newer).
- **JDK**: 17 (required by `taal-segmentation-core`'s Kotlin/JVM toolchain; also matches modern AGP requirements).
- **AGP**: 8.8.1, **Kotlin**: 1.9.20, **KSP**: 1.9.20-1.0.14 (see root `build.gradle.kts`).
- **compileSdk / targetSdk**: 34 across all modules.
- **minSdk discrepancy (confirmed, not fixed):** `taal-core`, `taal-ui-kit`, `lungs-app`, `visualizertaal-app` use `minSdk = 24`; `app`, `stemz-app`, `taal-segmentation` use `minSdk = 26`. This is real and current — the SDK modules support API 24+ but the flagship `app` module currently requires API 26+. Not a bug per se, just something to know before testing on older devices.
- **First-time setup**:
  1. Clone the repo.
  2. Open the root folder in Android Studio — it will detect `settings.gradle.kts` and sync all 8 modules.
  3. `local.properties` is **not** committed (gitignored). Android Studio will create/populate `sdk.dir` automatically on first sync, or create it manually with `sdk.dir=<path to your Android SDK>`.
  4. No API keys, signing configs, or other secrets are required anywhere in this repo — confirmed no `google-services.json`, no keystore files, no hardcoded credentials in any `build.gradle.kts`.

---

## 5. How to Run Each App

Each app module has its own Gradle run configuration (Android Studio auto-generates one per `com.android.application` module — pick it from the run-configuration dropdown, or `./gradlew :<module>:installDebug`).

All apps talk to TAAL hardware over **USB audio** (`android.permission.USB_PERMISSION`, `RECORD_AUDIO`). Without the physical TAAL stethoscope connected, `TaalRecorder.start()` throws `TaalDisconnectedException` — recording screens will not produce live audio, but you can still navigate the UI and play back previously saved `.wav` files.

| App | Run config | Launcher | Actual current start destination (verified live) |
|---|---|---|---|
| `app` | `app` | `MainActivity` (`.ui.MainActivity`, single-activity + Navigation) | `nav_graph.xml` → **`pcgScaleRecordingFragment`** ⚠️ — see below |
| `stemz-app` | `stemz-app` | its own `MainActivity` (duplicate of `app`'s) | own `nav_graph.xml` → `recordingFragment` (comment in file notes it was "flipped back from `fullTimeOnRecordingFragment` at explicit user request, to test the segmentation feature") |
| `lungs-app` | `lungs-app` | own `MainActivity` | `lungs_nav_graph.xml` (not audited in this pass — see module's own docs) |
| `visualizertaal-app` | `visualizertaal-app` | own `MainActivity` | `visualizer_nav_graph.xml` (not audited in this pass) |

**⚠️ Important discrepancy resolved (`app`'s start destination is volatile — verify before relying on any doc, including this one):**

- Auto-memory said this was contested between `testRecordingFragment` and the Calibrated screens.
- The in-repo hand-off doc `PcgScale_Handoff/INTEGRATION_NOTES.md` (2026-08-25) says the live start destination is `fullTimeOnRecordingFragment` with a "revert before shipping" comment.
- **What `app/src/main/res/navigation/nav_graph.xml` line 6 actually says right now is `app:startDestination="@id/pcgScaleRecordingFragment"`.**

So as of this push, launching `app` opens the **PcgScale** recording screen — a brand-new, uncommitted-until-this-push ECG/PCG waveform screen family (see §6). This has changed multiple times across recent sessions and will likely change again; **always grep `nav_graph.xml` for `startDestination` yourself** before trusting any doc's claim, this one included.

---

## 6. Key Architecture Notes

### Recording flow (dual-file)
1. `startRecording()` creates **two** WAV files: `recording_{ts}_raw.wav` and `recording_{ts}_filtered.wav`.
2. `TaalRecorder` writes raw audio via `TaalAudioCapture`, and writes the filtered stream in real time inside the `onAudioData` callback.
3. On stop, the app navigates immediately to the player with `filePath` = filtered file (instant graph, no re-processing) and `rawFilePath` = raw file for reference.
4. On save, both temp files are renamed into `filesDir/saved/`; on discard, both are deleted.

### Waveform rendering — **six parallel, independently-evolving implementations**
This is the single most important thing to understand before touching any waveform code. Over time, multiple ECG/PCG "paper" rendering systems were built side-by-side rather than one being refactored:

1. **Production Recording/Player** (`app`'s original `RecordingFragment`/`PlayerFragment`, MPAndroidChart-based, V7 sample-accurate downsampling, adaptive warmup/peak Y-axis).
2. **Test Recording/Player** (`TestRecordingFragment`, dormant/experimental).
3. **`EcgPaperView` / `MmScale`** (`app/.../ecg/`) — first mm-accurate custom-drawn ECG graph paper.
4. **Calibrated screens** (`app/.../ecg/calibrated/`, `ui/calibrated/`) — `CalibratedEcgPaperView`, `CalibratedWaveformView`, `DpiCalibration`, `GraphCalibration`, `DpiCalibrationFragment` — a second, separately-calibrated mm-accurate paper system with adjustable paper speed and grid transparency (see the `Fix A`–`Fix E` commits from 2026-08-18/19).
5. **FullTimeOn screens** (`ui/fulltimeon/`) — fork of Calibrated screens.
6. **PcgScale screens** (`ecg/pcgscale/`, `ui/pcgscale/`) — **newest, uncommitted-until-this-push fork of the Calibrated screens.** Per `PcgScale_Handoff/INTEGRATION_NOTES.md`: fixed time-based grid (1 large box = 1s, 1 small box = 0.2s) that "can never distort," zoom disabled by construction, RMS-based 60%-fill Y-axis autoscaling, grid scrolls with the trace (opposite of Calibrated's static grid), no DPI/mm calibration at all. This is currently `app`'s live start destination (§5).

None of these six share code beyond copy-paste-and-modify ("fork of Calibrated," "fork of FullTimeOn," etc. — the hand-off docs are explicit about this pattern). If you're asked to fix a waveform bug, **first confirm which of the six screen families is actually reachable from the current `startDestination`** — a fix in the wrong one will look correct in code review and do nothing at runtime.

### Room database
`TaalDatabase` (schema `version = 1` in `app`) — tables:
- `PatientEntity` — id, fullName, patientId, phone, email, dateOfBirth, biologicalSex, conditions, createdAt.
- `RecordingEntity` — id, patientId (FK → patients, `SET_NULL`), filePath, fileName, filterType, durationSeconds, bpm, preAmplification, isEmergency, notes, createdAt.

`lungs-app` has its own, separately-versioned DB (v2, sessions migration) — not the same database as `app`.

### Pre-amp dB range mismatch (confirmed, still present)
- SDK-level clamp (`TaalRecorder` / `AudioFilterEngine`) and `taal-ui-kit`'s own recording slider: **0–30 dB**.
- `app`'s `EditRecordingFragment` amplify slider (`fragment_edit_recording.xml`): hardcoded **`android:valueTo="10"` — capped at 0–10 dB.**
This is a real, currently-live inconsistency, not stale documentation — verified directly in the layout XML. A recording amplified above 10dB elsewhere cannot be edited back down/up correctly in this screen because the slider physically can't represent it.

### PCG heart-sound segmentation feature
Gated behind a single flag: `app/.../ui/segmentation/SegmentationFeature.ENABLED` (currently `true`). `PlayerFragment` checks this flag before showing the "Analyze Heart Sounds" entry point; when `false`, the entire feature (button → `SegmentationReportFragment` → chart) disappears with no other code changes needed. Full pipeline lives in `taal-segmentation-core` (pure Kotlin, unit-tested) + `taal-segmentation` (Android wrapper) + the `app`-side UI.

---

## 7. Where To Look (deeper docs)

**Per-module master references (added 2026-09-08):** every module now has (or is being given) a
dedicated `docs/master/SUPERMASTER_<MODULE>.md` file — read the relevant one before touching that
module's code, and update it (not just this file) after any change. See root `CLAUDE.md` for the
full rule. Files: `SUPERMASTER_APP.md`, `SUPERMASTER_STEMZ_APP.md`, `SUPERMASTER_LUNGS_APP.md`,
`SUPERMASTER_VISUALIZERTAAL_APP.md`, `SUPERMASTER_TAAL_CORE.md`, `SUPERMASTER_TAAL_UI_KIT.md`,
`SUPERMASTER_TAAL_SEGMENTATION.md`, `SUPERMASTER_TAAL_SEGMENTATION_CORE.md`,
`SUPERMASTER_TAAL_STEMZ_CORE.md`, `SUPERMASTER_TAAL_STEMZ_UI_KIT.md` (stemz-only client SDKs,
added 2026-09-25 on `StemzAppBranch`: `taal-stemz-core.aar` + `taal-stemz-ui-kit.aar`).

This file remains the whole-monorepo entry point/index; the deeper docs below (and the
per-module files above) are where module-specific detail actually lives — check their own
dates/headers for currency, as several are explicitly self-flagged as partially stale:

- `docs/notes/MASTER_HANDOFF.md` — huge (~7,200 line) zero-context handoff with **full embedded source** (not summaries) for all six waveform implementations, plus `taal-core`/`taal-ui-kit`/`app` architecture. Excludes `lungs-app`, `visualizertaal-app`, PCG segmentation, `stemz-app` by original scope decision.
- `docs/notes/CALIBRATED_SCREENS_HANDOFF.md` — Calibrated/FullTimeOn screen family detail.
- `PcgScale_Handoff/INTEGRATION_NOTES.md` + `PcgScale_Handoff/NAV_GRAPH_ADDITIONS.xml` — the newest PcgScale screen family, written against a 2026-08-25 snapshot; **not compiled at authoring time**, expect minor first-build fixups.
- `docs/pcg-segmentation/APP_INTEGRATION_STATUS.md` and `docs/pcg-segmentation/PORTING_GUIDE.md` — segmentation feature status and a portable how-to-add-this-to-another-project spec.
- `docs/notes/CODEBASE_MAP.md`, `docs/notes/CONTEXT_FOR_PLANNING.md` — older architecture snapshots; both self/externally flagged as presenting a Splash→auth navigation flow that is **not actually reachable** in the current app (auth chain exists in code but isn't wired into `nav_graph.xml`'s live path).
- `docs/notes/RECORDING_RELIABILITY_FIXES_2026-08.md` — flat-first-recording / disconnect-detection fix history.
- `docs/notes/PCGSCALE_LOCAL_FIXES_LEDGER.md` — local fix ledger for the PcgScale screens (new as of this push).

**Rule of thumb for any future agent:** these docs describe *intent at time of writing*. `nav_graph.xml`'s `startDestination` and which waveform-screen family is actually live have changed at least four times in the commit history below — always verify against current source before acting on a doc's claim, this file included.

---

## 8. Update / Changelog Table

| Date | Change | Notes |
|---|---|---|
| 2026-09-03 | PCG display-only conditioning chain (`pcgscale-rev3`, on top of the merged `audio-diagnostics-2026-09` work) | `PcgDisplayFilter`: click/USB-glitch removal + zero-phase 20–500 Hz band + 50/100/150 Hz notches + spectral gate with transient protection (beats never gated). Wired into the PcgScale recorder (live, causal twin — 2nd-order 10 Hz HP to avoid S1 onset dispersion), Player + Review (Denoise toggle, now peak-preserving), and the segmentation report chart; ported into `stemz-app` (recorder + segmentation chart) after merging `StemzAppBranch`. Recorded audio untouched. See `docs/notes/PCG_DISPLAY_FILTER_2026-09-03.md`. |
| 2026-08-28 | PcgScale rev 3: peak-calibrated median Y-scale + all four ledger fixes re-applied | Fixes the on-device "~30% bigger but not 60%, not uniform" report: the axis now calibrates on the DRAWN peak of each 5s window's 3rd-loudest-by-RMS hop, median across windows (`PcgAmplitudeScale.kt`). Ledger fixes 1–4 (`docs/notes/PCGSCALE_LOCAL_FIXES_LEDGER.md`) restored after the `update1` overwrite — Save routing, grid end-clamp, halved trace widths. Recorder caption now shows `peak=`, not `peakRMS=`. |
| 2026-08-28 | Repo republished as new GitHub repo with full history + AAR files + SUPERMASTER.md | Includes in-flight, previously-uncommitted work: PcgScale screens (`ecg/pcgscale`, `ui/pcgscale`, `PcgScale_Handoff/`), Calibrated/DpiCalibration additions, FullTimeOn screens — all committed together as part of this push. |
| 2026-08-19 | Fix E: update status caption — no longer auto-scaled, axis is fixed | Calibrated screens |
| 2026-08-19 | Fix D: re-bucket the player's trace on zoom instead of a load-time-fixed bucket | Calibrated screens |
| 2026-08-19 | Fix C: Kardia-style grid — aggressive minor/major split, pixel-snapped lines | Calibrated screens |
| 2026-08-19 | Fix B: make live and review render the same recording identically | Calibrated screens |
| 2026-08-19 | Fix A: fixed Y axis on the recorder, replacing adaptive warmup/peak scaling | Calibrated screens |
| 2026-08-18 | Fix C: derive the min/max downsample bucket instead of a fixed constant | Calibrated screens |
| 2026-08-18 | Fix B: paper speed 12.5 → 25 mm/s on both calibrated screens | Calibrated screens |
| 2026-08-18 | Fix A: add tunable grid line transparency to calibrated ECG paper | Calibrated screens |
| 2026-08-18 | docs: correct stale device-testing claims in calibrated screens handoff | |
| 2026-08-14 | Add visualizertaal-app module wired to taal-core/taal-ui-kit SDKs | New module |
| 2026-08-14 | Fix flat-first-recording regression, add disconnect/silence detection | |
| 2026-07-16 | Add .gitignore and untrack build artifacts; custom filter placement UI | |
| 2026-05-28 | Updated Lungs-App.md file and new flow | |
| 2026-05-28 | lungs-app: denoiser Share/Download + Denoiser button visible + doc update | |
| 2026-05-20 | lungs-app: multi-session support + bug fixes | |
| 2026-05-11 | lungs app : version 2 | |
| 2026-04-13 | Added the LUNGS-App to the ecosystem (App Name: Lungs Auscultation) | New module |
| 2026-04-09 | Initial commit for Information Feature migration | |
| 2026-03-30 | Update SDKs (21 March 2026) | |
| 2026-03-16 | Db max till 30db, Bug Fixes | SDK-level pre-amp clamp raised to 30dB — see §6 mismatch note |
| 2026-03-13 | Added taal-core-sdk and taal-ui-kit modules | SDK split from monolith |
| 2026-03-13 | Add Pre-Amp slider (0dB-20dB) Recorder Fragment | |
| 2026-03-10 | Refactor UI for Recorder/Player and implement device connection logic | |
| 2026-03-09 | Fix bugs, update UI flow, and resolve graph display issues | |
| 2026-02-21 | Initial commit - TAAL SDK | Repo origin |

*(Full history confirmed via `git log --pretty=format:"%ad|%s" --date=short --all` — 24 commits total as of this writing, table above is complete, not truncated.)*
