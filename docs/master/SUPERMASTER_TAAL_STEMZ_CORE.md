# SUPERMASTER_TAAL_STEMZ_CORE.md — `taal-stemz-core` Module Reference

**Read this before touching any code in `taal-stemz-core`.** Written 2026-09-25 by the session
that created the module; verified against source and against the built release AAR.

---

## 1. Overview

Stemz-only audio-engine SDK (no screens). Ships to the stemz team as `taal-stemz-core.aar`,
alongside `taal-stemz-ui-kit.aar` (see `SUPERMASTER_TAAL_STEMZ_UI_KIT.md`). **Standalone**: it does
NOT depend on `taal-core` — it carries its own copy of the engine, so changes to the regular
client SDK can never break stemz. Built on **StemzAppBranch only** (see memory
`project_branch_separation` / `project_stemz_sdk_decisions`).

| | |
|---|---|
| Gradle module | `:taal-stemz-core` (`settings.gradle.kts`) |
| Namespace / root package | `com.musediagnostics.taal.stemz` |
| minSdk / compileSdk | 26 / 34 (26 because ONNX Runtime needs it) |
| Java/Kotlin target | 17 (segmentation sources were written for 17) |
| Version | 1.0.0 (`BuildConfig.SDK_VERSION`) |
| Release AAR | `taal-stemz-core/build/outputs/aar/taal-stemz-core-release.aar` (~478 KB) — `./gradlew :taal-stemz-core:assembleRelease` |

## 2. Where every file came from

| Package | Files | Copied from | Changes vs source |
|---|---|---|---|
| `…stemz` | `TaalRecorder.kt`, `TaalPlayer.kt` | `taal-core` | Recorder rewritten for stemz — see §3. Player: package only |
| `…stemz.core` | `TaalAudioCapture.kt` (+ `RecorderState` enum at its end) | `taal-core` | package only |
| `…stemz.dsp` | `AudioFilterEngine.kt` | `taal-core` | presets → `LITE`(20–250)/`HARD`(20–200)/`NONE`; hum/rumble stage **removed** |
| `…stemz.dsp` | `PcgDisplayFilter.kt` (+`PcgLiveDisplayFilter`, `PcgClickRemover`), `PcgSpectralGate.kt` | `stemz-app/ecg/pcgscale` | package only — "Clean Graph" DSP |
| `…stemz.dsp` | `HeartBpmCalculator.kt` | `stemz-app/dsp` | package only |
| `…stemz.graph` | `PcgTimeScale.kt`, `PcgAmplitudeScale.kt` | `stemz-app/ecg/pcgscale` | package only — PcgScale maths (pure Kotlin) |
| `…stemz.util` | `PcgWavDecoder.kt` (+`DecodedWav`) | `stemz-app/ui/graphshare` | package only |
| `…stemz.utils` | `SurrUtils.kt`, `TaalConnectionBroadcastReceiver.kt` | `taal-core` | package only |
| `…stemz.segmentation` | `TaalCardiacSegmentation.kt` (+`SegmentationOutcome`, extension props) | `taal-segmentation` | package only |
| `com.purnacardio.signal.pcg[.viz/.android]` (in `src/main/kotlin`) | segmentation algorithm + `TcnSegmenterRunner` | `taal-segmentation-core` + `taal-segmentation` | **none** — original packages kept |
| `assets/` | `tcn_c200_cardiac_seg.onnx` (407,085 B) | `taal-segmentation` | none; `noCompress += "onnx"` |

**Deliberately left out:** `AudioDownsampler`, `HeartResampler` (only used by screens stemz
doesn't ship), the hum/rumble filter (decision: "leave out"), `TaalRecorder.setPlayback` (dead).
`PcgSegmentationView` (a View) lives in the UI kit, not here, to keep core screen-free.

## 3. `TaalRecorder` (stemz edition) — what differs from taal-core

`src/main/java/com/musediagnostics/taal/stemz/TaalRecorder.kt`
- `PreFilter` enum is **`LITE`, `HARD`** only (bottom of the file). `setCustomBandpass()` still exists (Custom).
- Engine is set to `LITE` in `init` — fixes taal-core's mismatch where the field said HEART but the DSP ran NONE until `setPreFilter()` was called.
- `DEFAULT_RECORDING_TIME_SECONDS = 15`, `MAX_RECORDING_TIME_SECONDS = 300`; `setRecordingTime` clamps to 1..300.
- **Bug fix:** `onStateChange(STOPPED)` now calls `finalizeFilteredFile()` — taal-core never finalized the filtered WAV header when the *time limit* (not `stop()`) ended a recording. Idempotent with the explicit call in `stop()`.
- `reset()` restores LITE / 15 s / 0 dB in the engine too (taal-core only reset fields).
- Removed: `setHumRumbleFilterEnabled`, `setPlayback`.

## 4. Public API (kept by name in the obfuscated AAR)

`TaalRecorder` (+`OnInfoListener`, `OnLiveStreamListener`), `PreFilter`, `TaalPlayer`,
`RecorderState`, exceptions (`TaalDisconnectedException`, `TaalNotAvailableForUseException`,
`InvalidFileNameException`), `AudioFilterEngine` (+`GraphicEQState`), `PcgDisplayFilter`,
`PcgLiveDisplayFilter`, `HeartBpmCalculator`, `PcgTimeScale`, `PcgAmplitudeScale`,
`PcgWavDecoder`/`DecodedWav`, `SurrUtils`, `TaalConnectionBroadcastReceiver`,
`TaalCardiacSegmentation`, `SegmentationOutcome`, `heartRateBpm`/`systolicIntervalsMs`/… ,
`com.purnacardio.signal.pcg.SegmentationResult`, `S1Result`, `viz.*`.

**Obfuscated:** `TaalAudioCapture`, `PcgSpectralGate`, the feature extractor / decoder /
ONNX runner (`com.purnacardio…` except the kept types). Verified by listing the release
`classes.jar` (2026-09-25).

## 5. Build / obfuscation config

- `proguard-rules.pro` (library's own R8): keep-list above via a negated wildcard; `-repackageclasses 'com.musediagnostics.o.core'` (every obfuscated class goes into this SDK's own package — without it BOTH SDKs emitted top-level `a.a`, `b.c`… and an app using both AARs failed with **"Duplicate class a.a"**; found 2026-09-25 by building a separate consumer project), `-dontusemixedcaseclassnames` (R8 otherwise emits `a`/`A` names that collide on Windows); `-dontwarn java.lang.invoke.StringConcatFactory` (Java 17 string concat); strips `Log.v/d/i` (the `TAAL_AUDIO_DEBUG` capture diagnostics) from the shipped AAR.
- `consumer-rules.pro` (applied to integrators' apps): keeps ONNX Runtime, the public API, Kotlin metadata. **Gotcha:** its package wildcard must exclude `…stemz.uikit.**` (`!com.musediagnostics.taal.stemz.uikit.**,…`) — without that it also matched the UI kit and silently kept every UI-kit class name un-obfuscated.
- ONNX Runtime 1.29.0 is an `api` Maven dependency. **An `.aar` file carries no dependency metadata**, so integrators using the file must add `onnxruntime-android:1.29.0` (and coroutines) themselves — the integration guide must say so. ABI choice (decision: arm64-v8a + armeabi-v7a only) is therefore applied in the integrator's app via `ndk { abiFilters }`, not in this AAR.

## 6. Tests (plain JVM, 90 tests, all passing 2026-09-25)

`./gradlew :taal-stemz-core:testDebugUnitTest`
- `PreFilterTest` (new): Lite = 20–250, Hard = 20–200, only two presets, 15 s / 300 s constants, no NaN output.
- `graph/PcgTimeScaleTest`, `graph/PcgAmplitudeScaleTest` — from stemz-app.
- `dsp/PcgDisplayFilterTest`, `dsp/PcgSpectralGateTest` — from `app` (sources byte-identical to stemz's).
- `util/PcgWavDecoderTest` — from stemz-app.
- `src/test/kotlin/com/purnacardio/…` — the 6 segmentation-core suites (run on JUnit4 via `kotlin("test-junit")`).

## 7. Known issues / gotchas

- Segmentation/ONNX not exercised by a unit test (native lib); only on-device use proves it. Not yet device-tested inside this SDK (2026-09-25).
- Many KDoc comments copied from stemz-app still reference stemz-app/app class names (`[com.musediagnostics.taal.app…]`); harmless (not shipped), clean up opportunistically.
- `TaalAudioCapture.SAMPLE_RATE` is still the hardcoded 44100 (see `SUPERMASTER_TAAL_CORE.md` known issues) — inherited.

## 8. Changelog

| Date | Change | Notes |
|---|---|---|
| 2026-09-25 | Module created (v1.0.0) | Standalone copy of taal-core + PcgScale maths + Clean Graph DSP + BPM + WAV decoder + segmentation; Lite/Hard filters; 15 s auto-stop default; time-limit finalize fix; obfuscated release AAR. Decisions: memory `project_stemz_sdk_decisions`. |
| 2026-09-25 | Obfuscated classes repackaged into `com.musediagnostics.o.core` | Fixes "Duplicate class a.a" when an app uses both AARs. Verified: a separate consumer project (only the 2 AAR files + the test guide's code) builds debug AND minified release. |
| 2026-09-29 | First device test: UI kit OK; test guide updated (no SDK change) | Tester asked for hear-while-recording + analysis UI in the core-only screen. Core has **no built-in live monitor** — apps feed `onProgressUpdate` samples to their own `AudioTrack` (the UI kit does the same). The report screen is UI-kit only; core exposes the data (`SegmentationResult` S1/S2 sample indices @2000 Hz, `heartRateBpm`, `systolicIntervalsMs`). Guide §8 FAQ + checklist 7.2 #11–12. Candidate for a future core API: optional monitor switch on `TaalRecorder`. |
| 2026-09-29 | Test guide: Button 3 = core-only copy of the UI kit Recorder + Player (no SDK change) | Proves the full UI-kit recorder/review flow can be built from `taal-stemz-core` alone: `CoreRecorderActivity` / `CorePlayerActivity` + own layouts/icons in the test app (guide §5.8–5.12, checklist 7.3, FAQ). Deliberate gaps vs UI kit: text placement info, dialogs for save-name/saved list, no Share, analysis = dialog + S1/S2 markers. Guide verified by rebuilding the app from its code blocks only (36 files, 0 diffs, debug build OK); minified release also OK. Device-tested OK 2026-09-29. |
| 2026-09-29 | Test guide: live monitor now uses blocking `AudioTrack.write` (no SDK change) | Tester heard noise/crackle only while recording on the core-only screens (files and playback fine; UI kit fine). Cause: demo code used `WRITE_NON_BLOCKING`, which drops the rest of a buffer when the speaker buffer is full. Switched to the default blocking write, same as the UI kit recorder (`PcgScaleRecordingFragment` `track.write(pcm, 0, pcm.size)`); guide FAQ warns clients. Device-verified 2026-09-29: noise gone. |
