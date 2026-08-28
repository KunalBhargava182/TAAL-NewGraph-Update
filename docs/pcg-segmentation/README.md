# PurnaCardio Cardiac Segmentation — Android SDK Handoff

Four-state phonocardiogram (PCG) segmentation — **S1, systole, S2, diastole** — at a 200 Hz frame
rate (5 ms resolution), packaged as a drop-in Android library for a Kotlin application.

Given a recording of heart sounds, it returns where each heart sound starts and peaks. That is all
it does. It does not classify murmurs, does not read an ECG, and does not produce a diagnosis.

---

## Read this before you integrate

Two facts materially affect whether this will work in the client's product. Neither is a reason not
to ship it, but both are reasons to plan a pilot rather than a launch.

1. **The model was trained on stethoscope audio, and the client's app will almost certainly feed it
   phone-microphone audio.** In our own field captures this domain gap is real and unresolved: a
   recording with a clearly detectable 86 bpm rhythm — one a plain envelope autocorrelation finds
   without difficulty — came back with no heart sounds detected. Roughly **a quarter of captures in
   our own app fail quality control** and need a retake. Design the client's UX for retake as a
   primary flow, not an error path.

2. **The shipped model now has measured numbers, but not a validation study.** Run against the
   CirCor DigiScope dataset for this handoff — 8 recordings, 337 cardiac cycles — it reached
   **91.5% frame accuracy** against expert annotation, **97.3% S1 recall** and **13.5 ms mean S1
   onset error**. Murmur energy landed in systole, between S1 and S2, with complete separation from
   the murmur-absent recordings. That is a real result and it is in
   [`docs/MODEL_CARD.md`](docs/MODEL_CARD.md).

   What it is not: CirCor is digital-stethoscope audio — the model's own training domain — the
   sample is small and hand-picked, and because the training corpus was never recorded we cannot
   rule out overlap. It does not close point 1. The published EMAT/QS2 bench figures in our
   calibration reports came from a *different, older* 100 Hz model and must not be quoted for this
   one.

Neither point is a defect in the code. Both are things a client integrating it is entitled to know
up front, and both are cheaper to say now than after a pilot.

---

## What is in this folder

```
README.md                     this file
PROVENANCE.md                 exact source commit, checksums, what changed from upstream
docs/
  INTEGRATION.md              step-by-step: Gradle, code, R8, threading, troubleshooting
  VISUALISATION.md            drawing the PCG with its segmentation, and the palette
  API.md                      public surface reference
  MODEL_CARD.md               architecture, provenance, validation status, intended use
  AUDIO_CAPTURE.md            microphone configuration — this changes results
sdk/                          the Gradle project you hand over
  core/                       pure-Kotlin DSP, decoding and chart geometry (38 tests)
  android/                    ONNX Runtime host, the packaged model, and the chart view
sample/
  SegmentationExample.kt      copy-pasteable end-to-end usage
  PcgChartCompose.kt          the chart in Compose
model/
  tcn_c200_cardiac_seg.onnx   standalone copy of the model (also inside the AAR)
  SHA256SUMS.txt
```

## Verified in this handoff

Both commands were run against this folder before it was written, on JDK 17 / Gradle 8.9 /
AGP 8.5.2:

```bash
cd sdk && ./gradlew :pcg-segmentation-core:test
```

38 tests, all passing — they pin the decoder rules, the 12-channel input contract, the frame-to-
sample arithmetic, the level normalisation, and the chart geometry.

```bash
cd sdk && ./gradlew :pcg-segmentation-android:assembleRelease
```

Produces a 334 KB AAR with the 407 KB model and the consumer R8 rules inside it. The client's app
needs no asset copying and no ProGuard edits.

The model itself was then run end-to-end over 8 annotated CirCor recordings using these exact
sources — see [`docs/MODEL_CARD.md`](docs/MODEL_CARD.md). That run is what caught the ONNX Runtime
version floor: **the runtime must be ≥ 1.18**, because the model is ONNX IR version 10 and 1.17.x
silently refuses to load it, turning every capture into "no heart sounds".

## The 60-second version

```kotlin
// Build once, keep it — opening the ONNX session costs ~100 ms.
private val segmenter = TcnSegmenterRunner(context)

// Off the main thread. `pcg` is mono; any sample rate; scale does not matter.
val result: SegmentationResult? = segmenter.segment(pcg, sampleRate = 44_100)

if (result == null || result.numCycles < 5) {
    // Too short, too quiet, or nothing cardiac in it. Ask for a retake.
} else {
    result.s1OnsetSamples2k   // S1 onsets, sample indices at 2 kHz
    result.s2OnsetSamples2k   // S2 onsets
    result.stateLabels        // per-frame 0=S1 1=systole 2=S2 3=diastole, at 200 Hz
}

// And to show it — waveform, state bands, S1/S2 markers, legend:
chart.setRecording(pcg, sampleRate = 44_100, result = result)
```

`null` means "no heart sounds", not "error" — see [`docs/API.md`](docs/API.md) for why that
distinction is load-bearing.

## Licensing and third-party code

The segmentation sources and the model are PurnaCardio's, and the terms under which the client may
use them are a commercial matter this folder does not decide — **agree them before handing this
over**. The one third-party dependency is Microsoft ONNX Runtime (`onnxruntime-android:1.19.2`,
MIT licence), pulled from Maven Central by the client's own build; it is not redistributed here.
