# API Reference

Two packages. `com.purnacardio.signal.pcg` is pure Kotlin with no Android dependency;
`com.purnacardio.signal.pcg.android` is the ONNX Runtime host.

---

## `TcnSegmenterRunner` — the entry point

```kotlin
class TcnSegmenterRunner(
    context: Context,
    modelAsset: String = DEFAULT_MODEL_ASSET,
    segmenter: CardiacSegmenter = CardiacSegmenter()
) : Closeable
```

Constructing it opens an `OrtSession` (~100 ms, reads a 407 KB asset). **Can throw** — missing
asset, unsupported ABI, corrupt model. Wrap it.

| Member | Notes |
|---|---|
| `segment(pcg: FloatArray, sampleRate: Int): SegmentationResult?` | Mono audio, any rate, any amplitude scale. |
| `segment(pcm16: ShortArray, sampleRate: Int): SegmentationResult?` | Convenience for `AudioRecord`. **Mono only** — it cannot detect interleaving. |
| `close()` | Closes the session. The process-wide `OrtEnvironment` is deliberately left open. |
| `DEFAULT_MODEL_ASSET` | `"tcn_c200_cardiac_seg.onnx"`, packaged in the AAR. |
| `MIN_DURATION_SEC` | `1.0`. |

### What `null` means

`null` is returned when the recording is too short, too quiet, or inference fails. It is a
**clinical outcome — "no heart sounds"** — and not an error to surface raw. Callers must not retry
the same buffer; nothing about it will change.

A non-null result is **not** a quality guarantee. The decoder will impose a legal cycle structure on
whatever it is given, so check `numCycles` against `durationSec` before trusting it.

---

## `SegmentationResult` — what you get back

```kotlin
data class SegmentationResult(
    val stateLabels: IntArray,          // per-frame labels at 200 Hz
    val featFs: Int,                    // 200
    val s1OnsetFrames: List<Int>,       // frame indices
    val s2OnsetFrames: List<Int>,
    val s1OnsetSamples2k: List<Int>,    // sample indices at 2 kHz
    val s2OnsetSamples2k: List<Int>,
    val s1PeakSamples2k: List<Int>,
    val s2PeakSamples2k: List<Int>,
    val numCycles: Int,
    val durationSec: Double,
    val confidencePerCycle: List<Float> = emptyList()
)
```

**`stateLabels`** — one label per 5 ms frame: `0` S1, `1` systole, `2` S2, `3` diastole. Constants
live on `CardiacSegmenter` (`STATE_S1`, `STATE_SYSTOLE`, `STATE_S2`, `STATE_DIASTOLE`).

**Onsets vs peaks.** The *onset* is where the state begins — the decoder's boundary, quantised to
5 ms. The *peak* is the maximum of the relevant band envelope inside that state (channel 0, the
20–50 Hz band, for S1; channel 1, 50–100 Hz, for S2) at 0.5 ms resolution. For interval measurements
against an ECG, our bench work found the DSP-refined **peak** materially more precise than the frame
boundary. Use peaks for timing; use onsets for display and windowing.

**`numCycles`** is `min(s1Onsets.size, s2Onsets.size)` — a count, not a confidence.

**`confidencePerCycle`** is **always empty**. The field exists in the data class but nothing
populates it; the runner does not compute softmax confidences. Do not build a quality gate on it.

**A state present at frame 0 produces no onset.** Onsets are detected as transitions, so if the
recording begins mid-S1 that first sound is not reported. Irrelevant at 20 s, but it is why you
cannot assume `s1OnsetFrames.size` equals the number of beats in the file.

**`equals`/`hashCode` are partial** — they compare `stateLabels`, `featFs` and `numCycles` only.
Two results with different onsets can compare equal. Do not use them for deduplication.

### `toS1Results(pcgSampleRate: Double = 44100.0): List<S1Result>`

Converts the 2 kHz indices back to the recording's own rate, so callers measuring EMAT (Q→S1) or
QS2 (Q→S2) against an ECG do not have to. Pass the rate you actually recorded at.

`S1Result.peakAmplitude` is always `1.0` — a placeholder. The segmenter does not measure amplitude.

---

## `CardiacSegmenter` — the algorithm, without a runtime

Use this directly only if you are hosting inference yourself (server-side, or another runtime).

| Member | Notes |
|---|---|
| `extractFeatures(pcgAudio, pcgSampleRate = 44100): Array<FloatArray>?` | 12 channels × N frames at 200 Hz. `null` if too short or silent. |
| `segmentWithLogits(logits, features, durationSec): SegmentationResult` | `logits` is `[class][frame]`, 4 classes. |
| `lastAudio2k: FloatArray?` | The resampled 2 kHz audio from the last `extractFeatures`, so downstream DSP need not resample again. |
| `constrainedDecode(...)` | `internal` — visible to the module's own tests, not to consumers. |

The model expects a `[1, 12, frames]` float tensor and emits `[1, 4, frames]`.

### The decoder's two rules

The network scores frames independently and has no idea a cardiac cycle has an order. Two rules fix
that, and both are pinned by `ConstrainedDecodeTest`:

1. **Legal transitions.** Only `S1 → systole → S2 → diastole → S1`. Anything else holds the previous
   state. A recording that argmaxes to `S1 → S2` yields *no S2 onsets at all* — the illegal state is
   never entered.
2. **Minimum durations** — S1 10 frames, systole 16, S2 8, diastole 20 (50/80/40/100 ms). A shorter
   segment is absorbed into the state before it. **Exception:** a too-short segment at frame 0 is
   kept, because a recording may legitimately start mid-sound.

One consequence worth knowing: rule 2 runs after rule 1, so absorbing a short segment can leave an
illegal adjacency behind it. That is existing upstream behaviour, reproduced here unchanged.

---

## `PcgFeatureExtractor` — the 12-channel contract

The channel count, **order**, and frame rate are the model's input signature, not free choices. A
reordered channel produces confident nonsense with no error raised.

| Ch | Content |
|---|---|
| 0 | 20–50 Hz Hilbert envelope (S1 band) |
| 1 | 50–100 Hz Hilbert envelope (S2 band) |
| 2 | 100–200 Hz Hilbert envelope |
| 3 | Spectral ratio ch0/(ch1+ε) |
| 4 | d/dt ch0 — S1 onset sharpness |
| 5 | d/dt ch1 — S2 onset sharpness |
| 6 | HR estimate from envelope autocorrelation |
| 7–10 | 4 coarse mel bands, 100–500 Hz |
| 11 | Mean delta across the mel bands |

Constants: `TARGET_SR` 2000, `FEAT_FS` 200, `SPF` 10, `NUM_CHANNELS` 12.

Channel 6 needs a 4-second window before it updates; below that it holds a constant 0.5.

### Level normalisation

`extract` divides by the peak **capped at 4× the 99.5th percentile**, then clips to `[-1, 1]`.
Not the plain peak: on a phone held to a chest the largest sample is very often a knock, and
dividing by it pushes real content toward silence. Not a plain percentile either: S1 and S2 *are*
the peaks of a good recording. On a well-formed recording the cap never binds and behaviour is
bit-for-bit what it was before the guard existed — which matters, because the model was trained on
peak-normalised audio. Measured: `max/p99.5` is 2.0 when the peaks are heart sounds and 15.8 when
one transient dominates. `PcgNormalisationTest` pins all of this.

---

## `Pcm` — the `AudioRecord` boundary

| Member | Notes |
|---|---|
| `toFloat(ShortArray)` | Widen, preserving scale. |
| `toFloatNormalised(ShortArray)` | Widen to `[-1, 1)`. Equivalent as far as the model is concerned. |
| `deinterleaveMono(FloatArray, channelCount, channel = 0)` | Split interleaved multi-channel PCM. Throws on an out-of-range channel. A trailing partial frame is dropped. |
