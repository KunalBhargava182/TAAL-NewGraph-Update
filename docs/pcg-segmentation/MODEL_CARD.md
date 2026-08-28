# Model Card — `tcn_c200_cardiac_seg.onnx`

Send this section to whoever signs off on the integration. It is deliberately explicit about what
is *not* known.

| | |
|---|---|
| File | `tcn_c200_cardiac_seg.onnx` |
| Size | 407,085 bytes |
| SHA-256 | `e0cab7866d5051109aa6d263cfb77f5cf54e79f96cf892f5d6e4982843d9cde9` |
| Task | 4-state PCG segmentation: S1, systole, S2, diastole |
| Frame rate | 200 Hz (5 ms) |
| ONNX IR version | **10** — requires ONNX Runtime **≥ 1.18**; 1.17.x refuses to load it |
| Runtime | ONNX Runtime 1.19.2 (Android), verified on 1.19.2 desktop |

## Architecture

Temporal Convolutional Network, "C-200", ~87K parameters. The following is read directly from the
ONNX graph rather than from a training record:

- Exported from PyTorch **2.10.0+cpu**, from a module named `colab_segmentation.TCN`
- 6 residual blocks, each `Conv1d` → `BatchNorm1d`
- Input `x`, shape `[1, 12, frames]`; output `conv1d_13`, shape `[1, 4, frames]`
- Sequence length is a **dynamic axis** — any recording length works without re-export

Inference is only part of the pipeline. The 12-channel feature extraction and the constrained
decoder are hand-written Kotlin (see [API.md](API.md)) and carry a meaningful share of the
behaviour — in particular, the decoder is what makes the output a physiologically legal cycle rather
than per-frame argmax.

## Intended use

Research and product development on heart-sound recordings. Segmentation output feeds interval
measurements (EMAT, QS2, systolic time ratios) and windowing for downstream classifiers.

**Not a medical device.** Not cleared or approved by any regulator. Not for diagnosis, and not for
any decision affecting patient care. If the client's product makes clinical claims, this model's
validation status (below) is nowhere near sufficient to support them.

## Training data

**Not recorded in our repositories.** The model was trained in a Colab notebook that is not held in
either the application or the calibration repository, and no dataset manifest, split definition, or
training log accompanies the checkpoint.

The related — but **different** — 100 Hz model used in our bench calibration work is recorded as
`tcn_seg_100hz_2859recs.pt`, i.e. 2,859 records. Whether the 200 Hz model here shares that corpus is
**not established**, and it should not be assumed.

*Action for us:* recover the notebook and the dataset manifest before the client asks. They will
ask, and "we don't know" is a much worse answer the second time.

## Validation status

### Measured on CirCor DigiScope, 2026-08-12

A first independent check was run for this handoff against the **CirCor DigiScope** dataset
(PhysioNet 2022), whose recordings carry expert S1/systole/S2/diastole annotations. The shipped
Kotlin feature extractor and decoder were compiled from this package's own sources and run against
this exact model file.

**Sample: 8 recordings, 217 s, 337 cardiac cycles** — 4 murmur-absent, 4 murmur-present spanning
holosystolic, early-systolic and mid-systolic timings at gradings I/VI to III/VI.

| | Murmur absent | Murmur present | All |
|---|---|---|---|
| Frame accuracy vs expert labels | 92.4% | 90.6% | **91.5%** |
| S1 recall | 96.8% (149/154) | 97.8% (177/181) | 97.3% (326/335) |
| S1 precision | 95.5% | 97.3% | 96.4% |
| S1 onset MAE | 11.4 ms | 15.7 ms | **13.5 ms** |
| S1 onset bias | −3.4 ms | −13.1 ms | −8.2 ms |
| S2 recall | 97.4% (150/154) | 98.3% (178/181) | 97.9% (328/335) |
| S2 onset MAE | 7.9 ms | 10.2 ms | **9.0 ms** |

Matching tolerance ±60 ms; per-file frame accuracy ranged 88.0–95.0%. Inference took 90–178 ms per
recording on a desktop JVM.

**Murmur localisation.** Murmur-band (120–400 Hz) energy density, systole vs diastole, computed
using the model's own state labels:

| | systole / diastole |
|---|---|
| Murmur absent (4 files) | 1.06 (range 0.91–1.14) |
| Murmur present (4 files) | 3.89 (range 1.87–5.85) |

Separation is complete on this sample — murmur energy sits **in systole, between S1 and S2**, and
not inside the heart sounds themselves. The negative S1 bias on murmur recordings (−13.1 ms, versus
−3.4 ms without a murmur) is consistent with murmur energy pulling the S1 onset boundary slightly
early, and is worth watching if the client measures EMAT on murmur patients.

### What this does and does not establish

**It does not close the phone-microphone question.** CirCor is digital-stethoscope audio at 4 kHz —
the model's own training domain. These numbers say the algorithm is sound and correctly ported; they
say nothing about the domain gap described under "Known limitations", which remains the open risk.

**The sample is small** — 8 recordings chosen for near-complete annotation coverage, not sampled at
random. Treat the figures as a sanity check with real numbers attached, not as a validation study.
A full run over the 3,163-recording CirCor training set is a day's work and would supersede this.

**Training-set overlap is unknown.** Because the training corpus was never recorded (see above), we
cannot rule out that some of these 8 recordings were seen during training. That alone bars these
numbers from being quoted as held-out performance.

### Do not quote the bench-calibration figures for this model

Our calibration reports contain accuracy figures for TCN segmentation, and it would be easy — and
wrong — to attach them to this file:

| Reported figure | Which model produced it |
|---|---|
| EMAT 104.9 ± 15.6 ms, QS2 193.4 ± 44 ms (TCN frame boundary) | `matrix_100hz_100hz_focal_bnd.pt`, **100 Hz** |
| 73.5% beat match rate | Shannon-Energy S1 detector, not a TCN at all |
| QS2 390.5 ms matching Weissler to 0.1 ms | DSP Hilbert refinement, **not** the TCN frame boundary |

None of these are figures for `tcn_c200_cardiac_seg.onnx`. Do not quote them for it.

What *is* verified for the code in this handoff is the algorithm's behaviour, not its clinical
accuracy: 26 JVM tests covering the decoder rules, the 12-channel contract, the frame-to-sample
arithmetic, and level normalisation.

## Known limitations

**Domain gap — the significant one.** The model was trained on stethoscope audio. A phone
microphone against a chest is a different acoustic path, and in our field captures the mismatch
shows: recording `20260807-102815` peaked at −35.7 dBFS and contained a genuine 86 bpm heartbeat
that a plain envelope autocorrelation locates without difficulty — and the model returned no heart
sounds. The cause is not established. A level-normalisation defect was found and fixed while
investigating (that fix is in this drop), but it does **not** explain that capture, and the leading
hypothesis remains the training-domain mismatch.

**Failure rate.** Roughly a quarter of captures in our own app fail quality control and need a
retake. Body habitus matters, and users with a high BMI do not improve with practice — our app stops
after three attempts rather than looping.

**It is blind.** No ECG, no trigger, no prior. It segments from audio alone. Where an ECG R-peak is
available, it is a far stronger timing reference and should be preferred over the segmenter for
interval measurement.

**Silent degradation modes.** Interleaved stereo, a wrong `sampleRate` argument, or reordered
feature channels all produce plausible-looking output with no error raised.

**No confidence output.** `SegmentationResult.confidencePerCycle` is always empty. There is no
per-cycle quality signal to gate on; use `numCycles` against `durationSec` as a coarse proxy.

## Before a clinical pilot

1. Recover and archive the training provenance.
2. Establish held-out metrics for *this* model — per-state F1 and onset error against annotated
   recordings, on a public set (PhysioNet/CinC, CirCor) for comparability plus phone-captured data
   for the real domain.
3. Quantify the phone-vs-stethoscope gap on matched recordings. It is currently one observation and
   a hypothesis.
4. Decide whether to fine-tune on phone-mic audio. If the gap is confirmed, that is likely the
   highest-value work available, and it would change this model file.
