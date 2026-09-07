# SUPERMASTER_TAAL_SEGMENTATION.md — `taal-segmentation` Module Reference

**Read this before touching any code in `taal-segmentation`.** Verified directly against source as
of 2026-09-08.

Path: `E:\AndroidProjects\TaalDemoApp\taal-segmentation`

---

## 1. Module Overview

`taal-segmentation` is the **Android wrapper library** around the pure-Kotlin algorithm in
`taal-segmentation-core` (see `SUPERMASTER_TAAL_SEGMENTATION_CORE.md` — read that first for the
actual DSP/decode algorithm; this doc covers the Android-specific host, the TAAL-specific bridge,
the bundled ONNX model, the chart view, and the porting/flag-gating pattern that consumer apps use).

It contains three logically distinct pieces, in two packages:

- `com.purnacardio.signal.pcg.android` — the low-level, TAAL-agnostic ONNX Runtime host
  (`TcnSegmenterRunner`) and the chart custom `View` (`PcgSegmentationView`). This package name is
  a holdover from the original PurnaCardio handoff and should not be renamed (see
  `PORTING_GUIDE.md:53`).
- `com.musediagnostics.taal.segmentation` — the **TAAL-specific bridge**, `TaalCardiacSegmentation`,
  which is the one place in the whole codebase that knows about both the TAAL audio pipeline and the
  PurnaCardio segmenter. This is what `app` and `stemz-app` actually call.
- `src/main/assets/tcn_c200_cardiac_seg.onnx` — the trained model, shipped as a module asset so it
  rides along automatically in any consumer's AAR resolution; no manual asset-copy step is needed
  by consuming apps.

Consumers: `app` (`app/build.gradle.kts:48`) and `stemz-app`
(`stemz-app/build.gradle.kts:75`) both declare `implementation(project(":taal-segmentation"))`.
Both gate the entire user-facing feature behind `SegmentationFeature.ENABLED` (§7) — this module
itself has no such flag; it always compiles in and is always callable, the flag lives at the
consuming-app layer.

## 2. Module Config

`taal-segmentation/build.gradle.kts` (full file, 57 lines, read in full 2026-09-08):

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.purnacardio.signal.pcg.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    api(project(":taal-segmentation-core"))
    api("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
```

Key facts:

- **`namespace = "com.purnacardio.signal.pcg.android"`**, `compileSdk = 34`, **`minSdk = 26`** —
  this is a hard floor for any consumer that calls into this module (Android 8.0 Oreo).
- **ONNX Runtime version: `1.29.0`** (`build.gradle.kts:47`). **This is a live, current
  discrepancy against the docs**: `docs/pcg-segmentation/README.md:116`,
  `docs/pcg-segmentation/INTEGRATION.md:48`, `docs/pcg-segmentation/PROVENANCE.md:71-77`,
  `docs/pcg-segmentation/MODEL_CARD.md:14`, and `docs/pcg-segmentation/PORTING_GUIDE.md:72,82-90` all
  state `1.19.2` as the pinned/required version. The build file's own inline comment
  (`build.gradle.kts:41-43`) explains the bump: *"Bumped 1.19.2 -> 1.29.0 (2026-09-04) to pick up the
  libonnxruntime4j_jni.so 16KB-page alignment fix (upstream PR #24947, merged after 1.19.2 shipped) —
  1.19.2's JNI .so wasn't 16KB-aligned, which triggered Android's debug-build 'app compatibility'
  warning dialog."* The **floor** described in the docs (`>= 1.18`, because the model is ONNX IR
  version 10 and Runtime `1.17.x` cannot load it) still applies and is still correctly enforced —
  1.29.0 comfortably clears it — but any future session citing "1.19.2" from the docs as the
  *current* pin is reading stale information; **trust `build.gradle.kts` line 47, not the docs, for
  the actual pinned version**, and update the docs under `docs/pcg-segmentation/` if you touch this
  again.
- `api(project(":taal-segmentation-core"))` and `api(onnxruntime-android)` are both declared `api`
  (not `implementation`) deliberately — `taal-segmentation-core`'s `SegmentationResult`/
  `CardiacSegmenter` are the public return/configuration types of this module's own API, and ONNX
  Runtime is meant to resolve transitively so a consuming app's `build.gradle.kts` needs only one
  dependency line (confirmed true in practice: `app` and `stemz-app` each declare only
  `implementation(project(":taal-segmentation"))`, nothing ONNX-related).
- `kotlinx-coroutines-android:1.7.3` is `implementation`-only, used by `TaalCardiacSegmentation`'s
  internal `Mutex` + `Dispatchers.Default` dispatch (§3).
- `consumerProguardFiles("consumer-rules.pro")` — see §9/consumer-rules.pro content below; ships R8
  rules so consuming apps need zero ProGuard edits of their own.
- Only test dependencies are `androidx.test.ext:junit` and `androidx.test:runner`, both
  `androidTestImplementation` — **there is no `testImplementation` (JVM unit test) dependency at
  all** in this module; all its own tests are instrumented (§6).

## 3. Public API

### `TaalCardiacSegmentation` — the class consumer apps actually call

`src/main/kotlin/com/musediagnostics/taal/segmentation/TaalCardiacSegmentation.kt` (357 lines, read
in full). Package `com.musediagnostics.taal.segmentation` — this is TAAL/MUSE-specific, distinct
from the `com.purnacardio.signal.pcg.android` package the low-level runner lives in.

```kotlin
class TaalCardiacSegmentation(context: Context) : Closeable {
    suspend fun segmentRawWav(rawWavFile: File, verboseLogging: Boolean = false): SegmentationOutcome
    suspend fun segment(pcm16: ShortArray, sampleRate: Int, channelCount: Int = 1, verboseLogging: Boolean = false): SegmentationOutcome
    suspend fun segment(pcg: FloatArray, sampleRate: Int, channelCount: Int = 1, verboseLogging: Boolean = false): SegmentationOutcome
    override fun close()
}
```

Behavioural contract (`TaalCardiacSegmentation.kt:21-38`, `52-133`):

- **Feed it the RAW TAAL recording, never `_filtered.wav`.** The segmenter band-passes 20-200 Hz
  internally as part of its own feature extraction (see the core module's §3 step 4); TAAL's own
  `setPreFilter()` output has already been through a bandpass + graphic EQ + pre-amp chain, and
  double-filtering shifts the input away from the model's training distribution. `segmentRawWav`
  logs a warning (does not refuse) if the filename contains `"_filtered"`
  (`warnIfFilteredFile`, `TaalCardiacSegmentation.kt:158-168`) — it is a courtesy check on the
  filename, not a content-based guarantee.
- **`segmentRawWav`** reads the WAV file's own RIFF chunks (`readWavPcm16`,
  `TaalCardiacSegmentation.kt:261-303`) rather than assuming a canonical 44-byte header or a fixed
  44.1kHz/mono format — it scans for `fmt ` and `data` chunks explicitly, so a file with extra
  metadata chunks before `data` still reads its true sample rate/channel count. It requires 16-bit
  PCM (`require(bitsPerSample == 16)`) and throws if the file isn't RIFF/WAVE or has no `data`
  chunk.
- **Not thread-safe by accident, thread-safe by design**: `TcnSegmenterRunner` (the underlying ONNX
  host) is not thread-safe and costs ~100ms to construct. `TaalCardiacSegmentation` builds it
  **lazily, once** (`ensureRunner()`, `TaalCardiacSegmentation.kt:125-133` — a `runCatching` around
  the constructor, retried never — `runnerInitAttempted` latches to `true` on the first call whether
  it succeeded or not) and serialises every public method through a `Mutex`
  (`mutex.withLock`, lines 68, 102) while dispatching onto `Dispatchers.Default` internally — so
  **callers never need their own dispatcher hop or their own locking**.
- **Clipping is checked and logged, not silently repaired.** `warnIfClipped`
  (`TaalCardiacSegmentation.kt:148-156`) fires a `Log.w` when more than `CLIPPING_WARN_THRESHOLD =
  0.001` (0.1%) of samples sit at full scale — suggesting pre-amp gain is too high for the
  environment. Amplitude *scale* (quiet vs loud, not clipped) is not a problem the segmenter cares
  about, since `PcgFeatureExtractor` normalises level internally.
- **Multi-channel input is split, never averaged** (`Pcm.deinterleaveMono(..., channel = 0)`,
  called at `TaalCardiacSegmentation.kt:85,104` when `channelCount > 1`) — averaging two chest
  positions is not the same recording as either one.
- `close()` closes the underlying `TcnSegmenterRunner` and nulls it (does **not** reset
  `runnerInitAttempted`, so a `TaalCardiacSegmentation` instance is not reusable after `close()` —
  construct a new instance per screen visit, as `PORTING_GUIDE.md:159` recommends).

### `SegmentationOutcome` — the four-state result the bridge always returns

```kotlin
sealed interface SegmentationOutcome {
    data class Ok(val result: SegmentationResult) : SegmentationOutcome
    data class TooWeak(val result: SegmentationResult) : SegmentationOutcome
    data object NoHeartSounds : SegmentationOutcome
    data object Unavailable : SegmentationOutcome
}
```

(`TaalCardiacSegmentation.kt:208-220`.) Decision logic, in `classify()`
(`TaalCardiacSegmentation.kt:135-146`):

```kotlin
val outcome = when {
    result == null -> SegmentationOutcome.NoHeartSounds
    result.numCycles < result.durationSec / 2.0 -> SegmentationOutcome.TooWeak(result)
    else -> SegmentationOutcome.Ok(result)
}
```

- `Ok` vs `TooWeak` is a **cycle-density heuristic** — fewer than roughly one cycle per 2 seconds of
  audio is `TooWeak` — deliberately *not* a model-reported confidence score, because
  `confidencePerCycle` on `SegmentationResult` is never populated (core module §3/§6). A low-but-
  nonzero cycle count can be the constrained decoder finding "a legal structure" in noise, not proof
  of a real heartbeat.
- `NoHeartSounds` = `TcnSegmenterRunner.segment(...)` returned `null` — too short, too quiet, or
  inference genuinely failed inside the try/catch in `TcnSegmenterRunner.segment` (§4). This is a
  **clinical outcome**, not an error — do not surface it as an error dialog, and do not retry the
  same buffer (the answer will not change).
- `Unavailable` = the ONNX session never opened this session at all (`ensureRunner()` returned
  `null`) — e.g. missing asset, unsupported ABI, or (historically) an ONNX Runtime version below the
  IR-10 floor. Distinct from `NoHeartSounds`: `Unavailable` means the engine itself couldn't start;
  `NoHeartSounds` means it ran and found nothing.

### Derived extension helpers (`TaalCardiacSegmentation.kt:222-253`)

```kotlin
val SegmentationResult.heartRateBpm: Double?              // mean instantaneous rate from S1-to-S1 peak intervals; null if <2 beats
val SegmentationResult.systolicIntervalsMs: List<Double>   // S1-peak-to-S2-peak per cycle, ms; drops any leading unpaired S2
val SegmentationResult.beatTimesSec: List<Double>          // S1 peak times, seconds from recording start
fun SegmentationResult.stateAt(tSeconds: Double): Int?     // decoded state (0-3) at a given time, or null past the end
```

`systolicIntervalsMs` specifically guards against a recording that starts mid-systole: it drops any
S2 peak at or before the first S1 peak before pairing index-wise, since pairing it with this
recording's first S1 would otherwise produce a negative interval belonging to a cycle that started
before the recording did.

## 4. ONNX Model Bundling

- **Asset path**: `taal-segmentation/src/main/assets/tcn_c200_cardiac_seg.onnx`.
- **Size**: 407,085 bytes (confirmed via `ls -la` on the live asset, 2026-09-08).
- **SHA-256**: `e0cab7866d5051109aa6d263cfb77f5cf54e79f96cf892f5d6e4982843d9cde9` — computed directly
  against the live asset file and **confirmed to match exactly** both
  `docs/pcg-segmentation/MODEL_CARD.md:10` and `docs/pcg-segmentation/SHA256SUMS.txt`. The model has
  not drifted from its documented provenance.
- **Loaded by** `TcnSegmenterRunner`'s constructor (`com.purnacardio.signal.pcg.android`,
  `TcnSegmenterRunner.kt:65-68`):
  ```kotlin
  private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
  private val session: OrtSession = context.assets.open(modelAsset).use { input ->
      env.createSession(input.readBytes(), OrtSession.SessionOptions())
  }
  ```
  `modelAsset` defaults to `TcnSegmenterRunner.DEFAULT_MODEL_ASSET = "tcn_c200_cardiac_seg.onnx"`
  (`TcnSegmenterRunner.kt:59`) and only `context.assets` is touched — no reference to the `Context`
  itself is retained (`TcnSegmenterRunner.kt:44` KDoc).
- **Cost**: opening the `OrtSession` costs roughly 100ms and reads the full 407KB asset — this is
  why both `TcnSegmenterRunner` and `TaalCardiacSegmentation` insist on "build one, keep it" rather
  than constructing per capture.
- **Architecture** (per `docs/pcg-segmentation/MODEL_CARD.md:16-29`, not independently
  re-verified against the ONNX graph itself during this pass — treat as doc-sourced): Temporal
  Convolutional Network "C-200", ~87K parameters, 6 residual `Conv1d`→`BatchNorm1d` blocks, exported
  from PyTorch 2.10.0+cpu. Input `x` shape `[1, 12, frames]`, output `conv1d_13` shape `[1, 4,
  frames]`; the frame-count axis is dynamic (any recording length works without re-export). ONNX IR
  version **10**, which is the reason for the ONNX Runtime `>= 1.18` floor (§2).
- **Inference call site**: `TcnSegmenterRunner.segment(pcg: FloatArray, sampleRate: Int)`
  (`TcnSegmenterRunner.kt:83-113`) — calls `CardiacSegmenter.extractFeatures`, rejects
  (`return null`) if there are fewer than 32 frames, flattens the `[channels][frames]` feature array
  to `[1, channels, frames]`, runs the ONNX session, and reads back `out[0].value as
  Array<Array<FloatArray>>` — asserting `logits.size == NUM_CLASSES (4)` — before calling
  `CardiacSegmenter.segmentWithLogits`. Any exception during the ONNX call is caught, logged
  (`Log.e`), and converted to a `null` return, **not** propagated — so a corrupt model or an
  unexpected output shape degrades to "no heart sounds" from the caller's perspective, same as a
  genuinely silent recording.
- **Model validation status**: covered in the core module's SUPERMASTER doc §5 and in full in
  `docs/pcg-segmentation/MODEL_CARD.md` — not re-validated as part of this pass; this module only
  hosts the model, it does not affect its accuracy.

## 5. `PcgSegmentationView`

**Lives in this module**, not per-app: `taal-segmentation/src/main/kotlin/com/purnacardio/signal/pcg/android/PcgSegmentationView.kt`
(351 lines, read in full). Package `com.purnacardio.signal.pcg.android`, same package as
`TcnSegmenterRunner`.

- A plain `android.view.View` (not a Compose composable) — deliberately, so it adds no Compose
  dependency to consumers that don't use Compose, and works identically in XML layouts, Compose (via
  `AndroidView`), and a `RecyclerView` row.
- All actual geometry comes from `PcgDisplay.build(...)` in `taal-segmentation-core`'s `viz` package
  (see the core module's SUPERMASTER doc §3) — this view is purely a renderer over that
  already-computed `PcgDisplayModel`. It draws, in `onDraw` (`PcgSegmentationView.kt:199-258`), in
  this order: state-band fills → accent rules on S1/S2 bands only → 1-second gridlines → the
  zero-amplitude baseline → the waveform trace (as one closed `Path`, built once per rebuild in
  `rebuildWavePath()`, not per frame) → S1/S2 peak markers in a gutter below the lane → an adaptive
  time axis → an optional legend.
- **Public API**:
  ```kotlin
  var palette: SegmentationPalette.Variant          // defaults to light/dark by device config
  var showLegend: Boolean                            // default true
  var showSecondGrid: Boolean                        // default true
  fun setRecording(audio: FloatArray, sampleRate: Int, result: SegmentationResult?)
  fun setDisplayModel(model: PcgDisplayModel?)        // for pre-built models off the main thread
  fun setWindowSeconds(startSec: Double = 0.0, endSec: Double = Double.MAX_VALUE)
  fun displayModel(): PcgDisplayModel?
  ```
- **Pass the same `audio` array to both the segmenter and the chart.** The overlay is aligned by
  time; a resampled, trimmed, or different-channel copy silently misplaces the bands while still
  looking plausible (documented repeatedly across `VISUALISATION.md`, `INTEGRATION.md`, and in this
  view's own KDoc).
- Time-axis tick spacing adapts automatically from a fixed candidate list (`TICK_STEP_CANDIDATES =
  [1, 2, 5, 10, 15, 30, 60, 120, 300]` seconds, `PcgSegmentationView.kt:72`) so labels never
  collide regardless of zoom level or recording length.
- A band narrower than `MIN_BAND_WIDTH_DP = 1.5dp` is width-clamped (never position-clamped) so it
  still paints instead of disappearing at high cycle density.
- `getContentDescription()` is overridden to report duration and cycle count for accessibility
  (`PcgSegmentationView.kt:345-350`) — per `VISUALISATION.md`'s note that a screen-reader user needs
  a textual summary alongside the chart, not just an accessible label on the canvas itself.

There is **no separate app-layer copy of this chart view** — `SegmentationReportFragment` in both
`app` and `stemz-app` uses this exact class from `taal-segmentation`. Do not confuse
`PcgSegmentationView` (this module, the segmentation-result chart) with `PcgDisplayFilter` (a
completely different class — see §9).

## 6. Instrumented Test

**The only instrumented test in the entire monorepo.** Confirmed by task scope and by this module
being the only one with a populated `src/androidTest/` directory among the modules examined.

- **File**: `taal-segmentation/src/androidTest/kotlin/com/musediagnostics/taal/segmentation/TaalCardiacSegmentationSmokeTest.kt`
  (72 lines, read in full).
- **Class**: `TaalCardiacSegmentationSmokeTest`, `@RunWith(AndroidJUnit4::class)`.
- **Test method**: `segmentsASyntheticRawRecordingOnRealHardware()`, `runBlocking`.
- **What it does**: copies a bundled asset WAV to the target app's cache dir, constructs a real
  `TaalCardiacSegmentation(targetContext)`, calls `segmentRawWav(rawFile, verboseLogging = true)`,
  logs the outcome, and asserts **only** that the outcome is **not** `SegmentationOutcome.Unavailable`
  — i.e. it proves the ONNX session actually opens and runs inference to completion on real device
  hardware (native `.so` libraries load correctly), which no JVM-only unit test can check, since
  `onnxruntime-android` ships native libraries per-ABI.
- **It does not assert a specific clinical outcome.** The test's own KDoc
  (`TaalCardiacSegmentationSmokeTest.kt:12-21`) is explicit: the fixture is a *synthetic* signal, "not
  a real heartbeat recording," and the test "does not assert a specific clinical outcome, since a
  synthetic signal may or may not resemble what the model was trained to recognise." Whether the
  outcome comes back `Ok`, `TooWeak`, or `NoHeartSounds`, the test passes (only `Unavailable` fails
  it) — each branch is merely logged (`Log.i`) for a human to inspect in logcat.
- **Asset used**: `taal-segmentation/src/androidTest/assets/synthetic_pcg_raw.wav` — per the test's
  KDoc, a synthetic 20-second, 44.1kHz, mono signal with paired low-frequency bursts at 75bpm.
- **Run with**: `./gradlew :taal-segmentation:connectedAndroidTest` (needs a connected device or
  emulator, API 26+ per this module's `minSdk`). This is distinct from
  `:taal-segmentation-core:test`, which is a pure-JVM `test` task needing no device.
- Test-only dependencies: `androidx.test.ext:junit:1.1.5` and `androidx.test:runner:1.5.2`
  (`build.gradle.kts:55-56`), both `androidTestImplementation`.

## 7. Feature-Flag Gating Pattern

`taal-segmentation` itself has no feature flag — it always compiles and is always callable. Gating
happens entirely in the **consuming app layer**, via a single object:

```kotlin
// app/src/main/java/com/musediagnostics/taal/app/ui/segmentation/SegmentationFeature.kt
// (identical file also exists in stemz-app at the same path)
object SegmentationFeature {
    const val ENABLED = true
}
```

Confirmed live usage, both apps (grep results, 2026-09-08):

- `app/src/main/java/com/musediagnostics/taal/app/ui/player/PlayerFragment.kt:72` —
  `if (SegmentationFeature.ENABLED) { ... }` gates showing the "Analyze Heart Sounds" entry-point
  button.
- `stemz-app` has the same gate in its own `PlayerFragment.kt:73`, plus two additional call sites
  not present in `app`: `PcgScalePlayerFragment.kt:128` and
  `PcgScaleReviewFragment.kt:108` (`if (SegmentationFeature.ENABLED && filePath.contains("_filtered.wav"))`)
  — `stemz-app` has a `pcgscale` screen family that `app` does not, and both of its entry points are
  gated the same way.
- `app/src/main/res/layout/fragment_player.xml:57` and `app/src/main/res/navigation/nav_graph.xml:261`
  reference the flag in comments (not live code) to document *why* the analyze button/nav action
  exists in the layout/graph despite being conditionally shown.
- One-flag-flip contract, per the class's own KDoc (`SegmentationFeature.kt:3-9`): flipping `ENABLED`
  to `false` hides the entire "Analyze Heart Sounds" feature — the button through the report screen
  and its chart — "without touching or reverting any other file."

This matches the pattern the user's global memory records as a confirmed preference ("Build optional
features behind one toggle flag" — `feedback_flag_gated_features.md`), and matches
`docs/pcg-segmentation/PORTING_GUIDE.md`'s §3 step 3 recommendation exactly (§8 below).

## 8. Porting Checklist

Condensed from `docs/pcg-segmentation/PORTING_GUIDE.md` (526 lines, read in full), cross-verified
against how `app`/`stemz-app` actually wire this module. The guide is written for a **different**
target project; cross-references below note where this repo's own reality either confirms or
diverges from what the guide describes.

1. **Copy both modules verbatim.** `taal-segmentation-core/` (pure JVM, package
   `com.purnacardio.signal.pcg`) and `taal-segmentation/` (Android, `minSdk 26`, packages
   `com.purnacardio.signal.pcg.android` + `com.musediagnostics.taal.segmentation`) — do not rename
   the `com.purnacardio.signal.pcg*` packages, do not "clean up" the algorithm files.
2. **Gradle wiring**: `settings.gradle.kts` — `include(":taal-segmentation-core")` and
   `include(":taal-segmentation")`. Consuming app module needs exactly one line:
   `implementation(project(":taal-segmentation"))` — confirmed this is genuinely the *only* line
   needed in both `app/build.gradle.kts:48` and `stemz-app/build.gradle.kts:75`; both `taal-segmentation-core`
   and ONNX Runtime resolve transitively via `api`.
3. **ONNX Runtime version**: the guide (`PORTING_GUIDE.md:82-90`) says pin `1.19.2`, "never lower."
   **This is now stale against this repo's own `build.gradle.kts`, which pins `1.29.0`** (§2) — the
   underlying rule the guide is protecting (never go below the ONNX-IR-10 floor, `>= 1.18`) is still
   correct and still the thing to check first if a fresh port produces `Unavailable` on every
   capture; just don't cite "1.19.2" as the number to pin going forward without checking
   `taal-segmentation/build.gradle.kts` first.
4. **`minSdk` floor**: 26 for any module that calls into `taal-segmentation`, including the
   consumer app itself. This is a real decision to surface, not something to silently work around.
5. **The non-negotiable wiring rule**: feed the segmenter the **raw, unfiltered** audio, never a
   DSP-processed/playback copy — see §3 above and the core module's algorithm description. If the
   target project's pipeline only persists one (already-processed) file, that's a real gap to flag,
   not something to silently paper over. (This repo's own recording flow keeps a `_raw.wav` /
   `_filtered.wav` pair specifically to satisfy this.)
6. **One feature flag** (§7), checked once at the entry point, not scattered across call sites —
   confirmed as the actual pattern in both `app` and `stemz-app`.
7. **Entry-point button placement**: the guide (§5, `PORTING_GUIDE.md:211-250`) documents a real
   lesson learned the hard way — don't anchor an optional button to the same constraint as a sibling
   whose visibility toggles independently (a full-width bottom button once overlapped the play
   button because both were positioned relative to a Save/Discard bar that hides in exactly the
   situation the new button shows). Recommended fix: a small icon-only `ImageButton` in the existing
   toolbar. This matches the user's own recorded preference in memory
   (`project_analyze_button_reposition.md` — "Analyze button moved to top-bar icon... general
   pattern: don't anchor an optional action to a sibling whose visibility toggles independently").
8. **`SegmentationResult` is not `Parcelable`** (lives in the pure-JVM core module, which has no
   reason to add an Android dependency for that) — if building a full-screen/landscape variant that
   survives an orientation-triggered Activity recreation, use an activity-scoped `ViewModel`, not a
   nav-arg `Bundle`.
9. **Verification checklist before considering a port complete**: `taal-segmentation-core` unit
   tests pass unmodified; the `androidTest` smoke test (§6) passes on a real device/emulator API 26+;
   with the flag `false`, the entire feature and its UI are completely absent; with it `true`, a real
   recording produces a rendered result with correct trustworthiness verdict, zoom works, and (if
   built) PDF export works; a silent/near-empty recording correctly renders `NoHeartSounds`.

**Not independently re-verified in this pass** (out of scope for these two modules specifically,
and the guide itself says it's written for porting the app-layer UI, which lives in `app`/
`stemz-app`, not in either module this doc covers): the report-screen layout details (§6-8 of the
porting guide), the PDF export mechanics, and the full-screen landscape orientation-restore gotcha
(`PORTING_GUIDE.md:497-508`) — those all live in consumer-app code (`SegmentationReportFragment` and
friends), not in `taal-segmentation` or `taal-segmentation-core`. See `APP_INTEGRATION_STATUS.md`
for that layer if you need it.

## 9. Known Issues / Gotchas

- **ONNX Runtime version in the docs is stale.** `docs/pcg-segmentation/{README,INTEGRATION,
  PROVENANCE,MODEL_CARD,PORTING_GUIDE}.md` all say `1.19.2`; the live `build.gradle.kts` pins
  `1.29.0` as of a 2026-09-04 change (16KB-page-alignment fix for `libonnxruntime4j_jni.so`). The
  *floor* logic (`>= 1.18`, ONNX IR version 10) is unaffected and still correctly enforced. Treat
  `build.gradle.kts:47` as ground truth over any of those docs for "what version is actually pinned
  right now."
- **`PcgDisplayFilter` is NOT part of this module.** It is a separate, app-layer class at
  `app/src/main/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgDisplayFilter.kt` (and an
  identical copy in `stemz-app` at the same relative path) that does click/USB-glitch removal and a
  zero-phase 20-500Hz bandpass + hum notches for **display conditioning** of a different, unrelated
  chart family (the `pcgscale`/ECG-paper views), not for segmentation. Confirmed by grep: it does
  not exist anywhere under `taal-segmentation/` or `taal-segmentation-core/`. Do not assume the two
  "Pcg-something-filter/display" names refer to the same code — `PcgSegmentationView` (§5, this
  module) is the segmentation chart; `PcgDisplayFilter` (app-layer only) is unrelated signal
  conditioning for a different screen.
- **`Unavailable` vs `NoHeartSounds` are easy to conflate in symptom, not in cause.** Both look like
  "the feature isn't producing a result" from a QA perspective, but `Unavailable` means the ONNX
  session never opened this session (check logcat for `TcnSegmenterRunner failed to initialise`) and
  `NoHeartSounds` means it opened fine and genuinely found nothing. If every capture on a fresh
  device/build comes back `Unavailable`, suspect the ONNX Runtime version floor or an unsupported
  ABI before suspecting the audio.
- **`ensureRunner()` only ever tries once per `TaalCardiacSegmentation` instance**
  (`runnerInitAttempted` latches permanently on the first call). A transient failure (e.g. a cold
  start race) will not be retried within the same instance's lifetime — construct a fresh instance
  (e.g. per screen visit, as recommended) rather than expecting a later call on the same instance to
  recover.
- **The bridge only warns on a `_filtered` filename; it does not inspect audio content.** A raw file
  renamed without `_filtered` in it, or a filtered file renamed to remove that substring, will be
  silently processed as if it were raw, with degraded (not obviously wrong) results.
- **No JVM unit tests in this module** — everything here that has a test is instrumented (§6). Do
  not expect `./gradlew :taal-segmentation:test` to exercise anything meaningful; the real algorithm
  coverage lives in `taal-segmentation-core`'s 38 JVM tests.
- **This module's own package name (`com.purnacardio.signal.pcg.android`) does not match its Gradle
  module name or its Maven-style artifact identity (`taal-segmentation`)** — this is intentional
  (§1) and should not be "fixed."

## 10. Changelog

| Date | Change | Notes |
|---|---|---|
| 2026-09-08 | Initial SUPERMASTER_TAAL_SEGMENTATION.md created | Full source audit (`build.gradle.kts`, `TaalCardiacSegmentation.kt`, `TcnSegmenterRunner.kt`, `PcgSegmentationView.kt`, `TaalCardiacSegmentationSmokeTest.kt`, `consumer-rules.pro` all read in full), synthesized from `docs/pcg-segmentation/*`. Confirmed live: ONNX Runtime pinned at 1.29.0 (docs across the board still say 1.19.2 — flagged as stale in §9); model asset SHA-256 matches documented checksum exactly; `SegmentationFeature.ENABLED` pattern confirmed live in both `app` and `stemz-app`, matching `PORTING_GUIDE.md`'s recommended pattern; `PcgSegmentationView`/`PcgDisplayModel`/`SegmentationPalette` confirmed to live in `taal-segmentation`/`taal-segmentation-core` respectively (not per-app copies); `PcgDisplayFilter` confirmed to be an unrelated, app-layer-only class, not part of either segmentation module. |
