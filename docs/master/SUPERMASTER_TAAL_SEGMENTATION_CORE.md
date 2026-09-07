# SUPERMASTER_TAAL_SEGMENTATION_CORE.md — `taal-segmentation-core` Module Reference

**Read this before touching any code in `taal-segmentation-core`.** Verified directly against
source as of 2026-09-08.

Path: `E:\AndroidProjects\TaalDemoApp\taal-segmentation-core`

---

## 1. Module Overview

`taal-segmentation-core` is a **pure Kotlin/JVM library** (`kotlin("jvm")` plugin — no Android
Gradle plugin, no Android dependency anywhere) implementing the actual heart-sound (PCG:
phonocardiogram) segmentation algorithm. Package: `com.purnacardio.signal.pcg` (plus a `viz`
sub-package). It is a ported/frozen third-party SDK originally from PurnaCardio (see §5 —
`docs/pcg-segmentation/PROVENANCE.md`), and is treated as intentionally unmodified even in this
repo — do not "clean up" it as a drive-by refactor.

What it does, precisely: given raw PCG (heart-sound) audio and the output logits of an external
TCN (temporal convolutional network) classifier, it (a) extracts a fixed 12-channel, 200 Hz feature
representation from the audio (`PcgFeatureExtractor`), and (b) turns per-frame class scores into a
physiologically legal 4-state cycle — S1, systole, S2, diastole — with sample-accurate onset/peak
indices (`CardiacSegmenter`). It does **not** run the neural network itself — no ONNX Runtime
dependency exists in this module. Running the model is the Android module's job
(`taal-segmentation` — see `SUPERMASTER_TAAL_SEGMENTATION.md`). This module also owns the
renderer-agnostic chart geometry (`viz/PcgDisplayModel.kt`, `viz/SegmentationPalette.kt`) that both
`PcgSegmentationView` (Android) and any other renderer (Compose, SVG, PDF) draw from, specifically
so those renderers cannot visually drift from each other — see §3.

It is not a medical device and makes no diagnosis; see MODEL_CARD.md validation caveats in §5.

## 2. Module Config

`taal-segmentation-core/build.gradle.kts` (full file, 10 lines):

```kotlin
plugins { kotlin("jvm") }

dependencies { testImplementation(kotlin("test")) }

kotlin { jvmToolchain(17) }

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
```

- Plugin: `kotlin("jvm")` only — confirms pure-JVM, no `com.android.library`.
- JVM toolchain: **17**.
- The only dependency, even in tests, is `kotlin("test")` (JUnit 5 / JUnit Platform via
  `tasks.test { useJUnitPlatform() }`) — no other library, no ONNX Runtime, no Android artifacts.
- Root repo Kotlin plugin version (from `E:\AndroidProjects\TaalDemoApp\build.gradle.kts:5,7`) is
  **1.9.20**. `docs/pcg-segmentation/PROVENANCE.md:96` claims the original handoff was verified on
  "JDK 17 / Gradle 8.9 / Kotlin 1.9.24" — that is the *donor* repo's version at extraction time, not
  necessarily this monorepo's; a minor Kotlin-version mismatch (1.9.20 vs 1.9.24) exists between the
  provenance doc and this repo's root `build.gradle.kts`, and is not clinically/behaviourally
  significant but is worth knowing if you ever diff against upstream.
- `settings.gradle.kts:27` — `include(":taal-segmentation-core")`. No `projectDir` override; module
  lives at the conventional `taal-segmentation-core/` path.
- Nothing in this module is Android-namespaced (no `namespace`, no `minSdk`, no manifest).

## 3. The Segmentation Algorithm

### File map (`src/main/kotlin/com/purnacardio/signal/pcg/`)

| File | Role |
|---|---|
| `PcgFeatureExtractor.kt` | Raw audio → 12-channel, 200 Hz feature tensor. Pure DSP. |
| `CardiacSegmenter.kt` | Orchestrates feature extraction; decodes model logits into a legal 4-state sequence; finds onsets/peaks. |
| `Pcm.kt` | `AudioRecord`-boundary helpers: widen 16-bit PCM to float, deinterleave stereo. |
| `S1Result.kt` | Flat per-S1-event DTO (onset/peak sample index + time, at the *original* recording's sample rate). |
| `SegmentationResult.kt` | The main output data class; `toS1Results()` converts internal 2 kHz indices to the caller's rate. |
| `viz/PcgDisplayModel.kt` | `PcgDisplay.build(...)` — turns audio + a `SegmentationResult` into renderer-agnostic 0..1 chart geometry (`PcgDisplayModel`, `StateBand`). |
| `viz/SegmentationPalette.kt` | The four state colours (light/dark ARGB variants), legend generation. |

### Entry points

There is **no single top-level "segment(audio) → result" function in this module** — that
convenience lives one layer up, in `taal-segmentation`'s `TcnSegmenterRunner` (which owns the ONNX
session) and `TaalCardiacSegmentation` (the app-facing bridge). Within `taal-segmentation-core`
itself, the two entry points a caller hosting their own inference would use are, on
`CardiacSegmenter` (`CardiacSegmenter.kt:53-109`):

```kotlin
fun extractFeatures(pcgAudio: FloatArray, pcgSampleRate: Int = 44100): Array<FloatArray>?
fun segmentWithLogits(logits: Array<FloatArray>, features: Array<FloatArray>, durationSec: Double): SegmentationResult
```

Typical two-step external-inference flow (documented in `CardiacSegmenter.kt:59-67` KDoc):

```kotlin
val features = segmenter.extractFeatures(pcgAudio)
val logits = onnxSession.run(features)  // shape: (4, T) — run externally, not by this module
val result = segmenter.segmentWithLogits(logits, features, pcgAudio.size / 44100.0)
```

### What the algorithm actually computes

**Step 1 — 12-channel feature extraction (`PcgFeatureExtractor.extract`, `PcgFeatureExtractor.kt:126-182`):**

1. Resample input audio to `TARGET_SR = 2000` Hz (linear interpolation, `resample()`,
   `PcgFeatureExtractor.kt:395-405`) — done by the caller (`CardiacSegmenter.extractFeatures`,
   `CardiacSegmenter.kt:54`) before calling `extract`.
2. Reject if `audio.size < targetSr` (under 1 second) or `maxAbs < 1e-10f` (digital silence) →
   returns `null` (`PcgFeatureExtractor.kt:127,130`).
3. **Level normalisation** (`normaliser()`, `PcgFeatureExtractor.kt:117-124`): divide by the peak,
   *capped* at `OUTLIER_HEADROOM (4.0×)` the 99.5th percentile (`NORMALISER_PERCENTILE = 0.995f`),
   then clip to `[-1, 1]`. This is deliberately not a plain peak (one loud knock would crush
   everything else) and not a plain percentile (S1/S2 *are* the peaks of a good recording). On a
   well-formed recording the cap never binds and behaviour is identical to plain peak-normalisation
   — this matters because the model was trained on peak-normalised audio. Pinned by
   `PcgNormalisationTest` (§4).
4. **Channels 0-2** — Hilbert envelope (63-tap FIR approximation, `hilbertEnvelope()`,
   `PcgFeatureExtractor.kt:210-233`) of three Butterworth 4th-order bandpass filters (precomputed
   SOS coefficients, `sosFilter()` Direct-Form-II-Transposed, `PcgFeatureExtractor.kt:189-207`):
   20-50 Hz (S1 band), 50-100 Hz (S2 band), 100-200 Hz. Each envelope is max-pool downsampled
   (`maxPoolDownsample()`) from 2000 Hz to `FEAT_FS = 200` Hz, i.e. every 10 samples → 1 frame
   (`SPF = 10`).
5. **Channel 3** — spectral ratio, `envelopes[0] / (envelopes[1] + 1e-8f)`.
6. **Channels 4-5** — central-difference derivative (`gradient()`) of channels 0 and 1
   ("onset sharpness" for S1 and S2 respectively).
7. **Channel 6** — HR (heart-rate) estimate via autocorrelation of channel 0 over a **4-second
   sliding window** (`computeHrChannel()`, `PcgFeatureExtractor.kt:316-348`), updated every 1 second
   (`updateInterval = featFs`), lag range 0.33-1.5 s (i.e. 40-182 bpm), normalised to `[0,1]` via
   `(hrBpm - 40) / 140`. Before 4 seconds of audio have accumulated, this channel holds a constant
   `0.5`.
8. **Channels 7-10** — 4 coarse mel-style bands, 100-500 Hz, computed via a hand-rolled 128-point
   radix-2 Cooley-Tukey FFT (`fft()`, `PcgFeatureExtractor.kt:284-314`) with a Hann window, band
   energies in dB (`10*log10(energy + 1e-10)`).
9. **Channel 11** — mean delta (central difference /8) across the four mel bands.
10. **Post-processing normalisation**: channels 0-2 and 7-11 get `percentileNormalize` (2nd/98th
    percentile → `[0,1]` linear rescale); channel 3 gets `clipAndScale` (clip to 98th percentile,
    divide by max); channels 4-5 get `groupNormalize` (joint 2nd/98th percentile across both
    channels together, so their *relative* scale is preserved).

Output: `Array(12) { FloatArray(nFrames) }`, `nFrames = audio.size / 10`. Channel order, count, and
frame rate are **the model's fixed input signature** — not a stylistic choice
(`PcgFeatureExtractor.kt` class KDoc, `API.md:120-121`). A reordered channel produces confident
wrong output with no exception raised.

**Step 2 — decoding model logits into a legal cycle (`CardiacSegmenter.constrainedDecode`,
`CardiacSegmenter.kt:116-152`, `internal` — visible to this module's own tests only):**

1. **Argmax** over the model's 4 output classes per frame (`STATE_S1=0, STATE_SYSTOLE=1, STATE_S2=2,
   STATE_DIASTOLE=3` — `CardiacSegmenter.kt:27-30`).
2. **Legal-transition enforcement**: `LEGAL_NEXT = {0→1, 1→2, 2→3, 3→0}`
   (`CardiacSegmenter.kt:35`). At each frame, the raw argmax is accepted only if it equals the
   previous decoded state or is that state's one legal successor; otherwise the previous decoded
   state is held. A raw sequence that jumps `S1 → S2` (skipping systole) therefore never enters S2
   at all — it stays S1 throughout, and **no S2 onset is ever emitted** from that stretch
   (`ConstrainedDecodeTest.kt:44-51`).
3. **Minimum-duration enforcement**: `MIN_FRAMES = {S1: 10, systole: 16, S2: 8, diastole: 20}`
   frames at 200 Hz = 50/80/40/100 ms (`CardiacSegmenter.kt:36`). A run shorter than its state's
   minimum is rewritten to the state that preceded it — **except** a short run starting at frame 0,
   which is kept as-is, since a recording may legitimately begin mid-sound
   (`ConstrainedDecodeTest.kt:66-74`, guarded by `segStart > 0` at `CardiacSegmenter.kt:144`).
   Rule 3 runs *after* rule 2, so absorbing a short segment can leave an illegal adjacency behind it
   — this is documented as existing upstream behaviour, reproduced unchanged
   (`CardiacSegmenter.kt` / `API.md:113-114`).
4. **Onset/peak extraction**: onsets are frame indices where the decoded state *transitions into*
   S1 or S2 (`findStateOnsets`, `CardiacSegmenter.kt:154-160`) — a state present at frame 0 produces
   no onset. Peaks are the maximum of the relevant band envelope (channel 0 for S1, channel 1 for
   S2) inside that state's own frame range (`findPeakInRange`, `CardiacSegmenter.kt:167-173`), then
   scaled to 2 kHz sample indices via `× SPF (10)`.
5. `numCycles = min(s1Onsets.size, s2Onsets.size)` — a count, not a confidence
   (`CardiacSegmenter.kt:106`). `confidencePerCycle` on `SegmentationResult` is declared but **never
   populated** by this code path — always `emptyList()` (`SegmentationResult.kt:37`,
   `API.md:69-70`).

### Output shape — `SegmentationResult` (`SegmentationResult.kt:26-38`)

```kotlin
data class SegmentationResult(
    val stateLabels: IntArray,          // per-200Hz-frame: 0=S1 1=systole 2=S2 3=diastole
    val featFs: Int,                    // 200
    val s1OnsetFrames: List<Int>,
    val s2OnsetFrames: List<Int>,
    val s1OnsetSamples2k: List<Int>,    // onset frame * 10
    val s2OnsetSamples2k: List<Int>,
    val s1PeakSamples2k: List<Int>,
    val s2PeakSamples2k: List<Int>,
    val numCycles: Int,
    val durationSec: Double,
    val confidencePerCycle: List<Float> = emptyList()   // ALWAYS empty in practice
)
```

`equals`/`hashCode` (`SegmentationResult.kt:76-85`) compare only `stateLabels`, `featFs`, and
`numCycles` — **two results with different onset/peak lists can compare equal**. Do not use this
class for deduplication or as a cache key beyond that partial identity.

`toS1Results(pcgSampleRate = 44100.0)` (`SegmentationResult.kt:61-74`) converts the 2 kHz-domain
onset/peak indices back to the caller's actual recording rate, returning `List<S1Result>`.
`S1Result.peakAmplitude` (`S1Result.kt:23`) is **hard-coded to `1.0`** — the segmenter never
measures amplitude; do not read it as a signal-strength figure.

### Chart geometry (`viz/` — pure Kotlin, JVM-testable)

`PcgDisplay.build(audio, sampleRate, result, columns, windowStartSec, windowEndSec)` →
`PcgDisplayModel` (`PcgDisplayModel.kt:98-157`). Three specific problems it solves, each verified by
a dedicated test (§4, `PcgDisplayModelTest`):

1. **Min/max-per-column decimation** so short transients (S1/S2 peaks) survive when a ~880,000-
   sample recording is squeezed into ~1,000 pixel columns — sampling every Nth sample would step
   over the peaks entirely.
2. **Display-only amplitude scaling** to the 99th percentile of `|sample|` (`displayCeiling()`,
   `PcgDisplayModel.kt:166-184`, strided/subsampled rather than a full sort, capped at
   `PERCENTILE_SAMPLE_CAP = 20_000` samples inspected), reporting `clippedFraction` — the share of
   samples that exceeded the ceiling and were flattened. This is a *display* choice, independent of
   the model's own input normalisation in `PcgFeatureExtractor`.
3. **Rate conversion** across three timebases meeting in one chart: the audio's own sample rate, the
   200 Hz state-label frame rate, and the 2 kHz peak-marker sample indices.

`SegmentationPalette` (`SegmentationPalette.kt`) defines `light`/`dark` `Variant`s: `bandFill`
(per-state translucent wash), `accent` (opaque per-state colour for markers/legend/accent rules),
plus `waveform`, `baseline`, `grid`, `surface`, `onSurface`, `onSurfaceMuted`. S1/S2 (the "sounds")
get a stronger wash than systole/diastole (the "intervals") and an extra 2 dp accent rule (drawn by
the Android view, not this module) so they remain distinguishable in greyscale / for colour-vision
deficiency. A 2026-08-24 "readability pass" raised S1/S2 wash alpha 24%→35% (light) / 28%→40%
(dark) and lowered waveform alpha 82%→76% — documented in the KDoc at
`SegmentationPalette.kt:87-93,115-118`.

## 4. Test Suite

JUnit 5 (`kotlin("test")` on JUnit Platform, `useJUnitPlatform()`). Run with:

```bash
./gradlew :taal-segmentation-core:test
```

**38 tests total**, all under `src/test/kotlin/com/purnacardio/signal/pcg/`, across 6 files. Count
verified by reading every `@Test` in every file (2026-09-08):

| File | Tests | What it pins |
|---|---|---|
| `ConstrainedDecodeTest.kt` | 5 | The decoder's two rules and their two deliberate non-firing cases: a legal min-duration sequence passes through untouched; an illegal `S1→S2` transition is held at S1 forever; a too-short mid-recording segment is absorbed into the prior state; a too-short *leading* segment (frame 0) is kept, not absorbed; ties in argmax resolve to the highest-scoring class, not the first-encountered one. |
| `PcgFeatureExtractorTest.kt` | 7 | 12 channels at 200 Hz (1 frame / 10 samples); all output values finite (NaN would silently corrupt ONNX inference downstream); exactly 1.0s accepted, under it rejected (`null`); digital silence → `null`; resampler produces the exact expected output length and returns identity by reference when rates match; the hand-rolled FFT peaks at the correct bin for a known tone; the Hilbert envelope tracks a constant-amplitude tone's amplitude rather than oscillating with it. |
| `PcgNormalisationTest.kt` | 5 | The level-normalisation guard (§3 step 3), built directly from two real field captures (20260807-102243 loud-but-no-heartbeat vs 20260807-102815 quiet-but-real-heartbeat, cited by sample name in the test's own KDoc): a well-formed recording is normalised bit-identically to plain peak-normalisation (cap never binds); a single simulated 20× "knock" transient does not get to set the scale for the whole recording; the normalised output is still bounded to `[-1,1]`; near-silence is still rejected, not amplified; scaling a recording down does not change what the normaliser recovers (normalisation is scale-invariant). |
| `PcmTest.kt` | 6 | The `AudioRecord` boundary: raw 16-bit PCM and normalised `[-1,1]` floats produce numerically identical features; interleaved stereo is *split*, never averaged; mono input passes through by reference, untouched; a trailing partial stereo frame is dropped rather than read out of bounds; an out-of-range channel index or a channel count of 0 throws `IllegalArgumentException`; widening 16-bit PCM to float preserves exact values including `Short.MIN_VALUE`/`MAX_VALUE`. |
| `SegmentWithLogitsTest.kt` | 3 | End-to-end frame→2kHz-sample→source-rate arithmetic with the network replaced by known one-hot logits: onsets/peaks land on the exact expected frame and sample-index values (including that S1 peaks are read from channel 0 and S2 peaks from channel 1, not swapped); `toS1Results` converts 2 kHz indices back to an arbitrary source rate correctly (worked example: frame 20 → 200 samples@2kHz → 4410 samples@44.1kHz → 0.1s); a recording with no complete cycle reports `numCycles = 0` and empty onset lists rather than throwing. |
| `viz/PcgDisplayModelTest.kt` | 12 | Chart geometry: state bands land at the correct normalised x-positions in cycle order; bands tile the window with no gaps/overlaps; 2 kHz peak-marker indices map to the correct on-screen x; a windowed (zoomed) view rescales correctly and clips markers/bands outside the window; a single-sample transient survives min/max decimation at column counts from 8 to 1000; one large simulated "knock" clips instead of flattening the real content to a hairline; digital silence draws a flat baseline (not `NaN`); y is measured downward (positive sample → smaller y, i.e. above centre); degenerate inputs (0 columns, empty audio, 0 sample rate, empty labels, an out-of-range window) all return an empty model rather than throwing; every column resolves to a real sample even when zoomed in past 1 sample/column; the palette legend exposes exactly one entry per state in cycle order with visible-but-not-fully-transparent swatches and fully opaque accents; both palette variants (`light`/`dark`) define all 4 states and tolerate an out-of-range state index without crashing. |

This count (38) matches `docs/pcg-segmentation/README.md:72` and `PROVENANCE.md:96` exactly —
**verified current, not stale**, as of this audit.

## 5. Provenance / Model Notes

Full detail lives in `docs/pcg-segmentation/PROVENANCE.md` and `docs/pcg-segmentation/MODEL_CARD.md`
— read those for the complete story. Condensed and cross-checked against live source:

- **Source**: PurnaCardio internal repo `cardiac-signal`, commit `ce287c3391c...`, extracted
  2026-08-12. This module's algorithm files (`PcgFeatureExtractor.kt` byte-identical,
  `CardiacSegmenter.kt`/`SegmentationResult.kt` KDoc-only changes) are a **frozen port** — do not
  refactor them casually; if you must change them, the model likely needs re-validation (the
  12-channel layout is the model's input signature, not a convention —
  `PROVENANCE.md:113-114`).
- **New in this package, not upstream**: `Pcm.kt` (AudioRecord boundary helpers), the entire `viz/`
  package (`PcgDisplayModel.kt`, `SegmentationPalette.kt` — upstream's own app draws its waveform
  through a different, app-entangled path that wasn't portable), and all 5 of this module's own new
  test files (`ConstrainedDecodeTest`, `SegmentWithLogitsTest`, `PcgFeatureExtractorTest`, `PcmTest`,
  `PcgDisplayModelTest`). `PcgNormalisationTest.kt` is carried over from upstream.
- **The ONNX model itself does not live in this module** — it is bundled as an Android asset inside
  `taal-segmentation` (see `SUPERMASTER_TAAL_SEGMENTATION.md` §4). This module has no ONNX Runtime
  dependency at all and cannot run the model; it only supplies the feature extraction and decoding
  that surround the model's forward pass.
- **Model validation summary** (full detail in `MODEL_CARD.md`, not independently re-verified
  during this doc pass beyond confirming the file the numbers were run against, §5 of the Android
  module doc): 91.5% frame accuracy, 97.3% S1 recall, 13.5ms mean S1 onset error against 8 CirCor
  DigiScope recordings (337 cycles) — explicitly **not** a validation study (small, hand-picked
  sample; training-set overlap not ruled out; digital-stethoscope domain, not phone-microphone).
  **Training data/notebook is not held in any repository** — `MODEL_CARD.md:42-51`.
- **Known domain-gap risk** (from `MODEL_CARD.md`/`README.md`): the model was trained on stethoscope
  audio; phone-microphone captures show a real, unresolved mismatch in field testing (one recording
  with a clearly-audible 86 bpm rhythm returned no heart sounds). Roughly a quarter of the shipping
  app's own captures fail quality control and need a retake. This is a property of the *model*, not
  a bug in this module's code.

## 6. Known Issues / Gotchas

- **This module cannot run inference by itself.** There is no "just call `segment(audio)`" function
  here — `extractFeatures` + `segmentWithLogits` are two separate calls, and something else (ONNX
  Runtime, in `taal-segmentation`, or your own runtime if hosting elsewhere) must run the model
  between them. Do not add an ONNX dependency to this module to "simplify" that — the JVM-testability
  of the algorithm depends on this module staying runtime-free.
- **The 12-channel order is load-bearing and unchecked.** Swapping, adding, or reordering channels
  in `PcgFeatureExtractor.extract` produces confident, plausible-looking, wrong output with no
  exception — there is no shape/order assertion at the model boundary (that boundary is in the
  Android module, not here).
- **`confidencePerCycle` is dead weight in the data class.** It exists in `SegmentationResult` but
  nothing in this codebase populates it. Do not build new logic that assumes it will ever be
  non-empty without also writing the code that fills it.
- **`equals`/`hashCode` are intentionally partial** (`stateLabels`, `featFs`, `numCycles` only) —
  see §3. A caching layer or a "did the result change" check built on default data-class equality
  will silently treat different onset/peak data as equal.
- **Minimum-duration absorption (rule 2 in §3) can leave illegal adjacencies** after it fires,
  because it runs after legal-transition enforcement rather than jointly with it. This is documented
  upstream behaviour, deliberately reproduced, not a defect to "fix" without understanding why it
  was left this way.
- **Do not treat a non-null `SegmentationResult` as a quality guarantee.** The decoder imposes a
  legal cycle structure on whatever it is handed; a low `numCycles` relative to `durationSec` can
  mean "found a plausible-looking structure in noise," not "detected genuinely weak heart sounds."
  (The Android bridge's `TaalCardiacSegmentation` builds exactly this heuristic on top — see the
  other SUPERMASTER doc §3.)
- **Root-repo Kotlin version (1.9.20) vs. provenance doc's claimed verification version (1.9.24)** —
  a minor mismatch, noted in §2. Re-run the full test suite after any Kotlin version bump in the
  root `build.gradle.kts`, since the provenance doc's "tests all passing" claim was made against a
  slightly different Kotlin patch version.

## 7. Changelog

| Date | Change | Notes |
|---|---|---|
| 2026-09-08 | Initial SUPERMASTER_TAAL_SEGMENTATION_CORE.md created | Full source audit (all `src/main` and `src/test` files read directly), synthesized from `docs/pcg-segmentation/*`. Confirmed: 38 tests (matches docs), model SHA-256 confirmed against `taal-segmentation/src/main/assets/tcn_c200_cardiac_seg.onnx` (documented in the sibling module's doc), `PcgDisplayModel`/`SegmentationPalette`/`PcgDisplay` confirmed to live in this module (not per-app), `PcgDisplayFilter` confirmed to live in `app`/`stemz-app` instead (a different, app-layer display-conditioning class — not part of this module, do not confuse the two). |
