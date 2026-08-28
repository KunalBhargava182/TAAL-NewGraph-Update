# PcgScale screens — drop-in integration notes

New screen family implementing: **PCG maxima at ~60% of graph height (RMS-based, noise-robust)**
and a **time-true grid (1 large box = 1 s, 1 small box = 0.2 s) with labeled, scrollable seconds
that can never distort**. Built as a fork of the Calibrated screens, following the same
convention Calibrated→FullTimeOn used: new package, new layouts, additive nav entries, zero
edits to any existing file.

Authored against the 2026-08-25 `TAAL_Waveform_Handoff` bundle (MASTER_HANDOFF.md + source
snapshots). **Not compiled** — the bundle has no Gradle project — so expect the usual small
round of fix-ups on first build (imports, IDs), but the structure mirrors code that already
compiles in this repo.

## 1. Copy these files (paths are repo-relative)

| File | What it is |
|---|---|
| `app/src/main/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgTimeScale.kt` | Fixed pixels-per-second time mapping + tick/label math. Pure Kotlin. |
| `app/src/main/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgAmplitudeScale.kt` | 50ms-RMS → peak-per-5s-window → running mean → 60%-fill axis scale, clamped + eased. Pure Kotlin. |
| `app/src/main/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgScaleEcgPaperView.kt` | Grid renderer: scrolling 1s/0.2s boxes + second labels. Fork of CalibratedEcgPaperView. |
| `app/src/main/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgScaleWaveformView.kt` | Paper+chart stack; owns the range lock (both bounds → zoom impossible) and grid↔chart scroll sync. Fork of CalibratedWaveformView, incl. forked copies of its bucket/downsample statics. |
| `app/src/main/java/com/musediagnostics/taal/app/ui/pcgscale/PcgScaleRecordingFragment.kt` | Recorder fork (of CalibratedRecordingFragment). |
| `app/src/main/java/com/musediagnostics/taal/app/ui/pcgscale/PcgScaleRecordingViewModel.kt` | ViewModel fork (kept package-local so neither package depends on the other). |
| `app/src/main/java/com/musediagnostics/taal/app/ui/pcgscale/PcgScalePlayerFragment.kt` | Player fork (of CalibratedPlayerFragment). |
| `app/src/main/res/layout/fragment_pcgscale_recording.xml` | Layout fork. |
| `app/src/main/res/layout/fragment_pcgscale_player.xml` | Layout fork. |
| `app/src/test/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgTimeScaleTest.kt` | 12 plain-JVM tests. |
| `app/src/test/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgAmplitudeScaleTest.kt` | 12 plain-JVM tests. |

## 2. The ONE existing-file edit

Paste the two `<fragment>` blocks from `NAV_GRAPH_ADDITIONS.xml` into
`app/src/main/res/navigation/nav_graph.xml` (purely additive — do not touch
`startDestination` as part of this change; the live file currently has it temp-flipped to
`fullTimeOnRecordingFragment` with a revert-before-shipping comment).

No manifest change expected: the `taalapp://` scheme's `<intent-filter>` on MainActivity
(added for `taalapp://calibrated`) matches on scheme, so `taalapp://pcgscale` should resolve.
Verify once on device.

## 3. Deliberate design decisions (vs. the Calibrated screens)

- **No DPI/mm calibration, no paper speed, no GraphCalibration.** Boxes are defined in TIME
  (large = 1 s), so pixel-mm accuracy is irrelevant; the two SharedPreferences calibration
  systems are untouched and unread by this package.
- **Zoom is impossible by construction**, not convention: scale gestures are disabled AND the
  visible X range is locked on both bounds to the grid-derived window. This is what makes
  "the time scale must never distort" a hard guarantee — and it deletes the whole Fix-D
  re-bucketing bug class (the bucket's window can never change after layout).
- **The grid scrolls with the trace** (opposite of Calibrated's static grid): a grid line
  means a specific second of the recording, and its label says which. Sync points: the
  waveform view owns the chart's gesture listener (user drags/flings); fragments call
  `syncGridToChart()` after every programmatic camera move (recorder page-snap, player
  playback-follow). **Fragments must never call `chart.setOnChartGestureListener` themselves**
  — that would silently disconnect the grid.
- **Y-axis**: recorder glides onto the measured 60%-fill scale as 5s RMS windows close
  (eased, mirroring FOLLOW_SMOOTHING, so no visible pops — the instability that killed the
  old adaptive scheme was axis *jumps*, which the RMS mean + easing both suppress); player
  computes the same statistic across the whole file once at load. Both start from the same
  neutral 0.10 the Calibrated screens use, so the first seconds look familiar.
- **Pre-amp compensation** is applied before BOTH drawing and RMS measurement, live and in
  review (the recorder passes `preAmpDb` to the player) — the 60% fill is measured on the
  same data that's drawn, so the slider can't change the fill fraction.
- **Player point budget** raised from Calibrated's 3000 to 120,000 total points: with no
  re-bucketing, per-pixel quality must hold at every scroll position of the fixed window.
  MPAndroidChart renders only the visible X range, so draw cost stays window-bounded; memory
  is a few MB at the 300 s max recording length. If a low-end device struggles, lower
  `MAX_TOTAL_POINTS` — quality when scrolled degrades gracefully.
- **Player Save button restored**: the Calibrated player's Apply slot (graph calibration) has
  no meaning here, so the bottom bar is Discard / Save (→ Add Patient), like production.
- **No attrs file** (the plan listed one): the paper view is only ever constructed
  programmatically inside `PcgScaleWaveformView`, same as the Calibrated stack — an XML attrs
  surface would be dead code. One planned file dropped, nothing else changed.
- **Lifecycle**: `PcgAmplitudeScale` is pure state fed from the existing audio-callback UI
  hop — no thread, handler, or session, so there is nothing new to release in
  onPause/onDestroy. The recorder/player teardown contracts are byte-for-byte the Calibrated
  ones (including the known, pre-existing gap that players only release in onDestroyView, not
  onPause — unchanged here for parity).

## 3b. Device variance (rev 2 — after the study Samsung report)

First device round: OnePlus fine, study Samsung "graph scaling doesn't work". Two hardened
paths, one per way "scaling" can fail:

- **Time (X) — REAL FIX.** The recorder previously mapped samples→seconds with a hardcoded
  44100 (inherited from Calibrated, which has the same latent bug). Samsung USB-audio stacks
  commonly deliver **48000 Hz**; at 48k every live X lands ~8.8% late, so a "1 second" box
  spans ~1.09 s — exactly "scaling doesn't work", and only on those devices. The recorder now
  adopts the rate `onProgressUpdate` actually reports (`onSampleRateReported`, first buffer of
  each session) and rebuilds the RMS windows + bucket density from it. The player was never
  affected (it parses the WAV-header rate) — a symptom signature of this bug is therefore
  *live wrong, review correct*.
- **Height (Y) — DIAGNOSTIC.** If a device's input path runs very quiet, the measured
  60%-fill scale can fall below the `MIN_FULL_SCALE = 0.02` clamp floor (the anti-noise
  guard): the axis stops shrinking and the trace sits well under 60% forever. The caption now
  shows live diagnostics while recording — `sr=48000 Hz · peakRMS=0.0042 · Y=±0.020
  (MIN-CLAMPED: input very quiet)` — and the player shows the same for a loaded file. If the
  Samsung shows MIN-CLAMPED with real heart sounds on the chest piece, the fix is a policy
  decision, not code: either lower `MIN_FULL_SCALE` (trades noise-magnification risk in true
  silence) or raise that device's input gain — read the `peakRMS` number off the screen first.

On the Samsung, one recording now tells you everything: boxes still wrong + `sr=44100` on
screen → the rate is being misreported upstream in taal-core; boxes right but trace short +
`MIN-CLAMPED` → quiet-input case; boxes right, no clamp marker, ~60% fill → fixed.

## 4. Verify (Phase 3)

1. `./gradlew :app:assembleDebug` — clean build.
2. `./gradlew :app:testDebugUnitTest` — all pre-existing tests still green (bundle's last
   stated count: **53/53**) **plus 24 new** (12 PcgTimeScaleTest + 12 PcgAmplitudeScaleTest).
3. `git status` / `git diff --stat` — only the new files above plus a small additive
   `nav_graph.xml` diff; `startDestination` unchanged from whatever it was before you started.

On-device checklist:
- Old screens unregressed: Calibrated Recorder/Player (fixed scale, pinch-zoom, Apply/Reset)
  and FullTimeOn (preview, warmup scale) behave exactly as before.
- Launch `taalapp://pcgscale`. Record with a deliberate loud thud in the first seconds, then
  normal heart sounds: the thud must NOT peg the scale — within ~10 s the trace settles with
  peaks at roughly 60% of the graph height.
- Against the timer text (or a metronome), confirm one large box = 1 s at the start AND
  deep into the recording; small boxes = 0.2 s.
- Stop → review screen: drag through the whole recording; grid lines, second labels, and
  trace move in perfect lockstep; the labels' seconds match the audio timer when playing.
- Confirm nothing on this screen can zoom: pinch does nothing, double-tap does nothing, and
  after any gesture a large box still spans exactly 1 s.
- Rotation note: MainActivity is portrait-locked, so the resize path is effectively
  unexercised (same as Calibrated) — split-screen if you want to poke it.

## 5. Open items for the humans

1. **Recorder camera style**: kept Calibrated's page-snap (jumps a full window at a time).
   If a continuously-scrolling live view is wanted instead, change `updateWaveform`'s
   `moveViewToX(currentViewX)` to follow `latestX` — the grid sync already handles it.
2. **Persist the file's RMS scale?** The player recomputes from the decoded file at load
   (deterministic, no storage change). If live-vs-review must match to the last pixel even
   though live ends mid-window, persist the recorder's final value as file metadata later.
3. **`DEFAULT_VISIBLE_SECONDS = 4f`** (4 large boxes across the screen) is a taste decision —
   change the constant in `PcgTimeScale` if the strip should be denser/sparser. Time stays
   exact at any value.
4. Whether this family eventually replaces Calibrated/FullTimeOn is out of scope here — this
   drop-in is purely additive and fully removable (delete the two `pcgscale` packages, the two
   layouts, the two test files, and the nav block).
