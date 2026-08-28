# Provenance

What this package was cut from, and exactly what differs from it. Keeping this accurate is what
makes it possible to re-cut the handoff later, or to pull an upstream fix into the client's copy
without guesswork.

## Source

| | |
|---|---|
| Repository | `cardiac-signal` (PurnaCardio internal) |
| Commit | `ce287c3391c631006e691e72d706671084e151e7` |
| Commit date | 2026-08-12T13:11:31+05:30 |
| Working tree | Clean — no uncommitted changes at the time of extraction |
| Extracted | 2026-08-12 |

## File mapping

| This package | Upstream path | Change |
|---|---|---|
| `sdk/core/…/pcg/PcgFeatureExtractor.kt` | `core/src/main/kotlin/com/purnacardio/signal/pcg/PcgFeatureExtractor.kt` | **Byte-identical** |
| `sdk/core/…/pcg/CardiacSegmenter.kt` | `core/src/main/kotlin/com/purnacardio/signal/pcg/CardiacSegmenter.kt` | KDoc only — see below |
| `sdk/core/…/pcg/SegmentationResult.kt` | `core/src/main/kotlin/com/purnacardio/signal/pcg/SegmentationResult.kt` | KDoc only — see below |
| `sdk/core/…/pcg/S1Result.kt` | extracted from `core/…/pcg/S1Detector.kt` | Data class lifted out verbatim |
| `sdk/core/…/pcg/Pcm.kt` | — | **New** in this package |
| `sdk/android/…/pcg/android/TcnSegmenterRunner.kt` | `android/src/main/kotlin/com/cardiac/android/TcnSegmenterRunner.kt` | Package moved; one overload added |
| `sdk/android/src/main/assets/tcn_c200_cardiac_seg.onnx` | `app/src/main/assets/tcn_c200_cardiac_seg.onnx` | **Byte-identical** |
| `sdk/core/src/test/…/PcgNormalisationTest.kt` | `core/src/test/kotlin/com/cardiac/core/PcgNormalisationTest.kt` | Package changed; two doc references reworded |

## Every change, and why

**No algorithm behaviour was modified.** Every difference is packaging, documentation, or additive.

1. **`CardiacSegmenter` — KDoc only.** The class doc referenced `[S1Detector]` (the Shannon-Energy
   detector this replaced) and described module layout in terms of the internal app's structure.
   Both are meaningless outside our repository, so they were rewritten to point at
   `TcnSegmenterRunner` instead. No code changed.

2. **`SegmentationResult` — KDoc only.** Same reason: references to `[S1Detector]` and
   `[EmatCalculator.compute]`, neither of which ships here. The `toS1Results` doc now describes
   what the function does rather than which internal caller it was written for. No code changed.

3. **`S1Result` lifted out of `S1Detector.kt`.** `SegmentationResult.toS1Results` returns it, so it
   has to ship. Upstream it lives in the file for the Shannon-Energy detector, which is *not* part
   of this handoff — that detector was superseded by the TCN and shipping it would invite the
   client to use it. The data class is byte-identical; only its KDoc is new.

4. **`TcnSegmenterRunner` moved from `com.cardiac.android` to `com.purnacardio.signal.pcg.android`.**
   Upstream it sits in a module that also hosts the watch BLE SDK and the murmur classifier, and its
   package name reflects that. Here the library is only segmentation. The `segment(FloatArray, Int)`
   body is unchanged line-for-line; the class gained lifecycle and threading documentation, plus:

5. **`segment(ShortArray, Int)` overload — new.** Delegates to the existing method via
   `Pcm.toFloat`. Purely additive.

6. **`Pcm` — new.** Conversion helpers for the `AudioRecord` boundary. Not upstream because our own
   app already had its own capture layer. Added here because the two failure modes it prevents —
   guessing at amplitude scaling, and feeding interleaved stereo — are both silent, and a client
   integrating from scratch has no way to know about either.

7. **Murmur classification removed.** Upstream, `MurmurModelRunner` consumes a `SegmentationResult`.
   It is a separate model with its own calibration and threshold files and is **not** part of this
   handoff. `SegmentationResult` itself carries no dependency on it.

8. **Build files are new.** Upstream, `core` and `android` are modules of a 3-module app project
   with a vendored watch SDK, ONNX Runtime declared `compileOnly`, and an `app` module supplying the
   assets. Here they form a standalone 2-module library: ONNX Runtime is `api` so it resolves
   transitively, the model ships inside the AAR, and `consumer-rules.pro` carries the R8 rules so
   the client's app needs no ProGuard edits.

9. **ONNX Runtime is pinned to 1.19.2, matching the upstream `app` module — not the `1.17.1` that
   appears in upstream's `android/build.gradle.kts`.** That upstream 1.17.1 is `compileOnly`: it is
   a compile-time stub that is never resolved at runtime, and the app supplies 1.19.2. Copying it
   into a runtime `api` dependency here would have reproduced a bug upstream had already paid for —
   the model is ONNX IR version 10, 1.17.x refuses to load it, and every capture silently reports
   "no heart sounds". Caught by running the model during this handoff; see
   [docs/MODEL_CARD.md](docs/MODEL_CARD.md).

10. **Chart rendering is new.** `viz/PcgDisplayModel.kt` and `viz/SegmentationPalette.kt` in `core`,
   `PcgSegmentationView.kt` in the android module, and `sample/PcgChartCompose.kt`. None of this
   exists upstream — our own app draws its waveform through a different, app-specific path that is
   entangled with its capture UI and its ECG lane, and lifting that would have handed the client a
   component wired to screens they do not have. Written for this package instead: geometry in
   `core` so it is JVM-testable and shared by every renderer, and a plain `View` so the SDK adds no
   Compose dependency to apps that do not use it.

## Tests

`ConstrainedDecodeTest`, `SegmentWithLogitsTest`, `PcgFeatureExtractorTest`, `PcmTest` and
`PcgDisplayModelTest` are **new, written for this handoff**. They pin behaviour that was previously only exercised indirectly through
the internal app's analyzer tests, which do not ship here. They document existing behaviour; none of
them prompted a code change.

`PcgNormalisationTest` is upstream's, carried over.

38 tests, all passing on JDK 17 / Gradle 8.9 / Kotlin 1.9.24.

## Not included, deliberately

| | Why |
|---|---|
| Murmur classifier (`classifier.onnx` + calibration/threshold/normalisation JSON) | Separate model, separate handoff decision |
| Shannon-Energy `S1Detector`, `S1PeakDetector` | Superseded by the TCN |
| ECG delineation, HRV, rhythm, EMAT/QS2 calculators | Different subsystem, not segmentation |
| Watch BLE SDK, vendor AARs | Not segmentation, and separately licensed |
| Training notebook and dataset | **Not held in any repository** — see [docs/MODEL_CARD.md](docs/MODEL_CARD.md) |

## Re-cutting this package

The two files that will drift are `PcgFeatureExtractor.kt` (byte-identical, so a straight copy) and
`CardiacSegmenter.kt` (KDoc-only divergence, so a copy plus re-applying change 1). Diff against
upstream `ce287c3` first to see what actually moved. If the feature extractor changes, the model
must be re-validated against it — the 12-channel layout is the model's input signature, not a
convention.
