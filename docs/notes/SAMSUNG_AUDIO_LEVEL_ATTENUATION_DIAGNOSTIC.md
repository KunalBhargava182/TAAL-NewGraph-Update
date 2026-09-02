# Samsung low-level capture diagnostic — 2026-09-01

## Background

Paired recordings of the same physical source showed the Samsung study phone's raw
capture samples arriving **4.9x quieter** than the OnePlus's (p95 peak 0.0022 vs 0.0106,
normalized units), with the Samsung's SNR actually 6 dB *better* — a pure level problem,
not a noise problem. Since TAAL's ADC is in the hardware (USB audio class device), every
phone should receive bit-identical samples; something on the Samsung's Android audio path
was digitally attenuating the USB capture before it ever reaches `taal-core`.

This diagnostic isolates which stage. It was run entirely on a **Samsung SM-A066B**
("a06x"), connected over wireless ADB, using temporary debug instrumentation added to
`taal-core/src/main/java/com/musediagnostics/taal/core/TaalAudioCapture.kt` (see the
`PHASE1`/`PHASE2 DEBUG INSTRUMENTATION` comment blocks in that file — additive, tagged,
removable in one pass). No OnePlus data was collected in this session; the 4.9x/0.0106
figures above are the pre-existing baseline this diagnostic was scoped against, not
re-measured here.

**Physical setup:** same stethoscope placement/tone source held constant across all six
recordings below, each ~15-20s, using the existing per-second raw-peak logging
(`captureAudioToFile()`, tag `TAAL_AUDIO_DEBUG`) added in Phase 1.

## Result table

| Config | AudioSource forced | Encoding | Routed device | Sample rate | Peak range | Avg peak |
|---|---|---|---|---|---|---|
| A (stock) | *(auto-picked)* → `UNPROCESSED` | PCM_16BIT | USB-Audio - MUSE Microphone_TAAL | 44100 Hz | 0.00018 – 0.00226 | ~0.0011 |
| B | `UNPROCESSED` | PCM_16BIT | USB-Audio - MUSE Microphone_TAAL | 44100 Hz | 0.00021 – 0.00247 | ~0.0011 |
| C | `VOICE_RECOGNITION` | PCM_16BIT | USB-Audio - MUSE Microphone_TAAL | 44100 Hz | 0.0027 – 0.024 | ~0.012 |
| D | `DEFAULT` (= `MIC` internally, see below) | PCM_16BIT | USB-Audio - MUSE Microphone_TAAL | 44100 Hz | 0.0030 – 0.0105 | ~0.006 |
| E (probe) | `UNPROCESSED` | **PCM_FLOAT** | USB-Audio - MUSE Microphone_TAAL | 44100 Hz | 0.0038 (single 3s window) | 0.0038 |
| E (main, post-probe) | `UNPROCESSED` | PCM_16BIT | USB-Audio - MUSE Microphone_TAAL | 44100 Hz | 0.0003 – 0.0033 | ~0.0015 |

Every single configuration stayed routed to the TAAL USB device the entire time —
`describeDevice()`'s post-`lockToUsbAudioDevice()` re-check never showed a fallback to the
built-in mic, and `lockToUsbAudioDevice()` logged "already routed" (no `setPreferredDevice()`
call needed) on every run.

## What this rules out

- **Routing to the built-in mic** — ruled out. Every config, every recording, stayed on
  `USB-Audio - MUSE Microphone_TAAL` (id visible in `logAllInputDevices()`'s enumeration and
  in every `routedDevice=` log line). `lockToUsbAudioDevice()`'s "no USB input device found,
  proceeding anyway" branch (the one flagged as risky in the original source review) never
  fired.
- **A hidden 48kHz→44.1kHz resample** — ruled out. `adb shell dumpsys audio`, captured mid-recording,
  shows the active `AudioRecordClient` session as `format client=1ch 44100Hz ENCODING_PCM_16BIT,
  dev=1ch 44100Hz ENCODING_PCM_16BIT` — client and device/HAL format are identical. The
  hardcoded `SAMPLE_RATE = 44100` in `TaalAudioCapture.kt` is not silently lying about an
  underlying 48k HAL path on this phone. (This was flagged in the original task as a
  "knock-on" worth checking — it's clear on this device.)
- **PCM_16BIT vs PCM_FLOAT encoding** — not implicated. The standalone `ENCODING_PCM_FLOAT`
  probe (config E), same source (`UNPROCESSED`) as configs A/B, produced a peak (0.0038 over
  a 3s window) in the same order of magnitude as A/B's PCM_16BIT peaks, not the 5-10x jump
  seen with a different *source*. If there were a framework input-volume stage that only
  applies to the 16-bit path, encoding would have mattered here; it didn't.

## What this confirms

**Vendor per-source input gain**, source-selected, on the Samsung's audio HAL:

```
UNPROCESSED  (quietest)  ~0.0011 avg
DEFAULT/MIC  (~5x)       ~0.006  avg
VOICE_RECOGNITION (~10x) ~0.012  avg
```

`buildAudioRecord()`'s existing fallback chain (`UNPROCESSED → VOICE_RECOGNITION → DEFAULT`,
picking the *first* one that initializes) happens to land on `UNPROCESSED` on this phone
because it always succeeds — so the fallback never reaches the louder sources. `UNPROCESSED`
is the theoretically "best" choice (least vendor DSP applied) and is exactly the source the
code was written to prefer for that reason — but on this particular Samsung, "least
processing" apparently also means "lowest applied input gain," which is the opposite of what
the priority order assumed.

One more nuance worth carrying forward: `dumpsys audio`'s own recording-activity log labels
config D (`MediaRecorder.AudioSource.DEFAULT`, value `0`) as `src:MIC` — on this device,
`DEFAULT` and `MIC` resolve to the same HAL source, consistent with D's peak levels
(~0.006 avg) sitting between UNPROCESSED (~0.0011) and VOICE_RECOGNITION (~0.012).

## Sizing the effect against the original 4.9x gap

The original baseline: Samsung p95 peak 0.0022, OnePlus p95 peak 0.0106 (ratio 4.9x). This
session's config A/B average (~0.0011, peak up to 0.0023-0.0025) reproduces that Samsung
baseline almost exactly, confirming the measurement setup here is representative. Config C's
average (~0.012, peak up to 0.024) is *at or above* the OnePlus's reported p95 — i.e.
forcing `VOICE_RECOGNITION` on the Samsung alone would plausibly close or overshoot the gap,
**without needing to touch the OnePlus side at all**. (Caveat: this is inferred from the
pre-existing OnePlus baseline number, not a same-session paired measurement — no OnePlus was
connected during this diagnostic.)

## Recommended fix — NOT implemented, for review

Smallest change: reorder `buildAudioRecord()`'s fallback-chain priority so `VOICE_RECOGNITION`
is tried (or preferred) ahead of `UNPROCESSED` — or, more conservatively, make the choice
device-conditional rather than a blanket global swap.

**Caveat that should gate this before it ships:** this session only has Samsung data.
`UNPROCESSED` is `UNPROCESSED` precisely *because* it's supposed to carry the least vendor
DSP — reordering the global priority to prefer `VOICE_RECOGNITION` (which is allowed to carry
noise suppression / gain-control processing on other devices, even though this app disables
AGC/NS/AEC as session effects afterward — a source-level DSP stage baked into the vendor HAL
is not something `disableSystemAudioEffects()` can reach) risks *regressing* level or fidelity
on devices — including the OnePlus itself — where `UNPROCESSED` is already the louder/cleaner
path. A blind global swap trades one device-specific problem for a different one on a
different device.

Two candidate directions, in increasing order of engineering cost:
1. **Static per-model swap** — keep the existing priority chain as the default, add a small
   device-model allowlist (e.g. by `Build.MODEL`/`Build.MANUFACTURER`) that prefers
   `VOICE_RECOGNITION` only on confirmed-affected models. Cheapest, but doesn't scale past the
   models someone explicitly tests.
2. **Runtime auto-calibration** — at `startRecording()`, briefly open and read a few frames
   from each candidate source (or reuse the existing per-second peak-tracking logic over a
   short window), and pick whichever source yields the highest input level before committing
   to it for the real recording. Self-correcting across unknown devices, but a real change to
   capture startup latency and to `buildAudioRecord()`'s structure — needs its own design pass,
   not a logging-only diff.

No code change has been made for this fix — this diagnostic's scope was instrumentation and
root-causing only, per the task's explicit "report and stop" instruction.

## Current state of the temporary instrumentation

`TaalAudioCapture.kt` still has the Phase 2 debug flags in a **non-default** state from the
last test run:
- `debugForcedAudioSource = null` (back to stock — fine)
- `debugProbeFloatBeforeRecording = true` (**not default** — leave as `false` before any
  further non-diagnostic build; currently causes a ~3s freeze at the start of every recording)

All instrumentation (Phase 1 device/routing logging + Phase 2 debug flags) is additive and
bounded by `PHASE1`/`PHASE2 DEBUG INSTRUMENTATION START/END` comment markers in
`TaalAudioCapture.kt` — delete those blocks to fully revert once this investigation is closed
out and a real fix direction is chosen.
