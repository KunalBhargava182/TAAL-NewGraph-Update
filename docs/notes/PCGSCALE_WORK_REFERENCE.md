# PCG Scaling — What Was Done

This is a technical record of the PCG-scaling (PcgScale graph) work itself — the
algorithm, the reasoning behind every change, and what differs between the apps in this
monorepo. It assumes the reader (or their Claude) can already read the branches and
source directly — it exists to point out *what was done and why*, not to explain how to
navigate git.

Relevant branches: `pcgscale-rev3` (the original baseline), `audio-diagnostics-2026-09`
(rev3 + follow-on fixes/features, `app`-only), `StemzAppBranch` (built on top of that,
adds the `stemz-app` port and everything below).

---

## 1. What PcgScale is

A waveform screen family — **Recorder → Player (new recording) → Review (saved
recording)** — built as a fork of the older "Calibrated" ECG-paper screens, with two
deliberate differences from that design:

1. **Time-true grid**: 1 large grid box = exactly 1 second, 1 small box = exactly 0.2s,
   always. No mm/DPI calibration, no paper speed, no zoom — the visible window is
   range-locked so horizontal scale can never distort, at any scroll position.
2. **RMS auto-scaled Y-axis**: the axis is sized per-recording so the signal's typical
   peak fills a target fraction of the graph height, instead of a fixed axis
   (Calibrated screens) or the old adaptive warmup/peak lock (original production
   screens).

Code: `ecg/pcgscale/` (pure logic, no Android UI — `PcgAmplitudeScale`, `PcgTimeScale`,
`PcgScaleEcgPaperView`, `PcgScaleWaveformView`, and `PcgSpectralGate` where present) and
`ui/pcgscale/` (`PcgScaleRecordingFragment`, `PcgScalePlayerFragment`,
`PcgScaleReviewFragment`, `PcgScaleRecordingViewModel`).

---

## 2. Which apps have it, and how each differs

Four app modules exist in this monorepo (`taal-core`/`taal-ui-kit`/`taal-segmentation*`
are libraries, not apps). Only two have PcgScale at all:

| | `app` | `stemz-app` | `lungs-app` | `visualizertaal-app` |
|---|---|---|---|---|
| **Has PcgScale?** | Yes — original | Yes — ported from `app` this branch | No | No |
| **Default screen** | `pcgScaleRecordingFragment` | `pcgScaleRecordingFragment` | Own `LungsRecordingFragment` | Own `RecordingFragment` |
| **Y-axis algorithm** | `PcgAmplitudeScale` — median of 3rd-largest-by-RMS hop per 5s window, outlier-robust (§3) | Same algorithm, same file, tuned differently (§3) | Simple peak-track: `axisMinimum = -peakAmplitude` per buffer, no outlier rejection, no windowing | Same simple peak-track as `lungs-app` |
| **`TARGET_FILL_FRACTION`** | `0.60f` | `0.50f` | n/a | n/a |
| **`MIN_FULL_SCALE`** | `0.005f` | `0.005f` (inherited) | n/a | n/a |
| **Heart filter UI** | 5-preset row (Heart/Lungs/Bowel/Pregnancy/Full-body) + Custom | 2-button **Basic/Hard** toggle (Basic=`HEART` preset, Hard=custom bandpass 20–200Hz) + Custom | Lungs-specific filter set | Guided point-by-point Heart/Lungs flow (via `taal-ui-kit`) |
| **Auto-stop** | None (300s ceiling only) | **14s hard auto-stop**, routed through real `stopRecording()` | Session-based, no fixed auto-stop | No fixed auto-stop |
| **Hum/rumble filter (opt-in)** | Yes — "Feature A" switch on recorder | Yes — same switch, same `setHumRumbleFilterEnabled()` (removed 2026-09-03, re-added 2026-09-04 for parity with `app`) | n/a | n/a |
| **Denoise toggle (opt-in)** | Yes — "Feature B" switch on review, uses `PcgDisplayFilter.processOffline()` | Yes — same switch/mechanism (removed 2026-09-03 as `PcgSpectralGate`-based, re-added 2026-09-04 using `app`'s current `PcgDisplayFilter`-based version) | n/a | n/a |
| **`PcgDisplayFilter`** (click/USB-glitch removal, zero-phase 20–500Hz band + hum notches — Arvind's addition on `pcgscale-rev3`) | Yes — powers the Denoise toggle and the segmentation report chart | Yes — same, pulled in via merging `pcgscale-rev3` | n/a | n/a |
| **Equalizer button** | Present on Player screens | **Removed** from every Player/Review screen (not just PcgScale) | Not applicable | Not applicable |
| **Segmentation ("Analyze Heart Sounds")** | Reachable from production `PlayerFragment` only | Now also reachable from **`PcgScalePlayerFragment`** and **`PcgScaleReviewFragment`** (§5) | Not present | Not present |
| **Default pre-amp** | 5dB | **10dB** (PcgScale recorder only) | 5dB (unaffected) | Unaffected |
| **Grid caption text** | Shows Y-axis fill %, "scroll to browse", live diagnostics while recording | **Simplified** to always read `"1 large box = 1 s · 1 small box = 0.2 s"`, nothing else | n/a | n/a |
| **Saved-recording title** | Static "Review Recording" always | Shows the **actual saved filename** the user typed (Review screen only) | Own naming scheme | Own naming scheme |
| **App icon / name** | "TAAL" branding | Icon letter changed T→S, app name → **"StemzApp"** | "Lungs Auscultation" branding | Own branding |

`lungs-app` and `visualizertaal-app` are included here only to make clear they are
**unaffected by any of this work** — neither has PcgScale, neither shares any of the
files touched.

---

## 3. The Y-axis auto-scale — the algorithm and every constant

### Formula (identical logic in `app` and `stemz-app`, values differ — see table below)

```
fullScale = medianWindowPeak / TARGET_FILL_FRACTION,  clamped to [MIN_FULL_SCALE, MAX_FULL_SCALE]
```

`medianWindowPeak`: per 5-second window, take the hop (50ms slice) that's **3rd-largest
by RMS energy** — not the loudest, so up to 2 transient-elevated hops per window (e.g. a
contact thud) can't dominate — use *that hop's peak amplitude*, then take the **median**
of these values across all closed windows (not the mean, so one whole poisoned window —
a cough, a repositioning bump — gets rejected outright rather than averaged in).

Lives in `ecg/pcgscale/PcgAmplitudeScale.kt`.

### Every constant, `pcgscale-rev3` vs current

| Constant | `pcgscale-rev3` | `app` (current) | `stemz-app` (current) | What it does |
|---|---|---|---|---|
| `TARGET_FILL_FRACTION` | `0.60f` | `0.60f` | **`0.50f`** | Typical peak fills this fraction of the graph's half-height. Lower = smaller trace, more headroom above/below. |
| `MIN_FULL_SCALE` | `0.02f` | `0.005f` | `0.005f` | Floor on the axis half-range — explained below. |
| `MAX_FULL_SCALE` | `1.0f` | `1.0f` | `1.0f` | Ceiling on the axis half-range. |
| `RMS_HOP_SECONDS` | `0.05f` | `0.05f` | `0.05f` | Length of each measurement hop (50ms). |
| `PEAK_WINDOW_SECONDS` | `5f` | `5f` | `5f` | Window size (5s) the calibrating hop is picked from. |
| `OUTLIER_REJECTION_K` | `3` | `3` | `3` | Uses the 3rd-loudest hop per window as the calibrator. |
| `SMOOTHING_PER_UPDATE` | `0.15f` | `0.15f` | `0.15f` | Live-recorder easing rate toward a new axis target per UI update. |
| `DEFAULT_INITIAL_FULL_SCALE` | `0.10f` | `0.10f` | `0.10f` | Neutral starting axis before the first 5s window closes. |

### `MIN_FULL_SCALE`: why `0.02f` → `0.005f`

`MIN_FULL_SCALE` is a safety floor — it stops the axis from shrinking toward zero on
pure silence/noise (which would otherwise blow tiny noise up to fill the whole graph).

The problem with `0.02f`: on a quiet-capture device (measured on a study Samsung unit),
a real peak of `0.008` naturally wants an axis of `0.008 / 0.6 ≈ 0.0133`. But
`0.0133 < 0.02`, so the floor forced the axis up to `0.02` anyway — **the floor itself,
not the measurement, was setting the axis**, so the trace only filled
`0.008 / 0.02 = 40%` of the height instead of the intended 60%. Lowering the floor to
`0.005` lets the axis land at the correctly-computed target for quiet devices instead of
being artificially inflated.

The `(MIN-CLAMPED)` caption note that used to appear (see §4.6 — since removed from the
visible caption in `stemz-app`, but still logged) meant *the floor, not the signal, is
currently setting the axis* — the diagnostic for exactly this class of bug on a new
device.

---

## 4. Everything changed for PcgScale, in order

### 4.1 — Port into `stemz-app`

`stemz-app` had zero PcgScale files before this. Ported from `app`'s state at the time
(rev3 + the `MIN_FULL_SCALE` fix + the denoise feature + diagnostic logging — all
already present in `app` by then):

**Copied verbatim:** `PcgTimeScale.kt`, `PcgScaleEcgPaperView.kt`,
`PcgScaleWaveformView.kt`, `PcgScaleRecordingViewModel.kt`, `PcgScalePlayerFragment.kt`
(modified afterward, see 4.5), `fragment_pcgscale_player.xml`,
`fragment_pcgscale_review.xml`.

**Adapted, not verbatim:**
- `PcgAmplitudeScale.kt` — copied, `TARGET_FILL_FRACTION` later changed 0.60→0.50 (4.4).
- `PcgScaleRecordingFragment.kt` — copied, then its filter UI swapped from `app`'s
  original 5-preset row to `stemz-app`'s own **Basic/Hard heart filter toggle** (Basic =
  `PreFilter.HEART`, Hard = a local `"HEART_HARD"` name via
  `setCustomBandpass(20.0, 200.0)`, not a real taal-core preset) — matching
  `stemz-app`'s production `RecordingFragment.kt`. Also carries that same file's
  **14-second hard auto-stop**, routed through the real `stopRecording()` (not
  `TaalRecorder.setRecordingTime`, which bypasses filtered-WAV header finalization).
- `fragment_pcgscale_recording.xml` — same filter-row swap applied to the layout.

**Not carried over:** `PcgSpectralGate.kt` (denoise engine) — ported initially, deleted
in 4.2.

**Wiring:** nav graph destinations added for all three screens; `startDestination`
changed to `pcgScaleRecordingFragment` (`app`'s current default too); new
`stemzapp://pcgscale` deep link (mirrors the existing `stemzapp://calibrated` pattern);
Saved Recordings' tap-to-open now routes through `PcgScaleReviewFragment` instead of the
old plain `PlayerFragment`.

### 4.2 — Remove Hum filter and Denoise (`stemz-app` only)

Both are optional extras `app` added to PcgScale *after* rev3 — feature additions, not
scale tuning, so not in §3's table.

- **Hum filter** ("Feature A": opt-in 50/100/150Hz hum + 25Hz rumble biquad, default
  off): UI switch and `setHumRumbleFilterEnabled()` call both removed from `stemz-app`.
  `taal-core` defaults this off when never called, so recording behavior is unchanged —
  same as every other consumer (`lungs-app`, `visualizertaal-app`) that never touches
  the flag.
- **Denoise** ("Feature B": post-processing spectral-gate toggle on the review screen,
  display-only — playback audio untouched either way): switch UI, toggle logic, and
  cached-sample state removed. `stemz-app`'s copy of `PcgSpectralGate.kt` deleted
  outright — nothing references it anymore.

`app` keeps both features unchanged.

### 4.3 — Rename screen titles (`stemz-app` only)

Visible titles only, no file/class renames: `"PCG Scale Recorder"` → `"TAAL Recorder"`;
`"PCG Scale Review"` (used by both Player and Review) → `"Review Recording"`. Matches
`stemz-app`'s existing production Recorder/Player titles.

### 4.4 — Remove Equalizer button (`stemz-app`, all Player/Review screens)

Not PcgScale-specific — swept across every screen with an `eqButton` in `stemz-app`:
production `PlayerFragment`, `CalibratedPlayerFragment`, `FullTimeOnPlayerFragment`,
`PcgScalePlayerFragment`, `PcgScaleReviewFragment`. The `equalizerFragment` nav
destination and `EqualizerFragment.kt` are left in place, just unreferenced.

### 4.5 — `TARGET_FILL_FRACTION` 60% → 50% (`stemz-app` only)

Covered in §3. `app` stays at 60%.

### 4.6 — Wire up "Analyze Heart Sounds" on the PcgScale screens (`stemz-app`)

Segmentation only existed on production `PlayerFragment` — unreachable once PcgScale
became the default flow. Added the same button + gating to:

- **`PcgScalePlayerFragment`**: uses its own `rawFilePath` nav argument, gated on the
  file being inside `filesDir/saved/`. In the live flow this screen is always a
  brand-new, not-yet-saved recording, so **this button never actually shows yet** — see
  §6.
- **`PcgScaleReviewFragment`**: always reached from Saved Recordings, computes the saved
  `_raw.wav` path via `filePath.replace("_filtered.wav", "_raw.wav")`, same as
  production `PlayerFragment`.

Both route to the existing `SegmentationReportFragment` via two new nav actions.

**Traced end-to-end and confirmed: segmentation always analyzes the raw file, never the
filtered one.** `SegmentationReportFragment` reads its `rawFilePath` argument and passes
it straight to `TaalCardiacSegmentation.segmentRawWav(rawFile, ...)`.

The report screen's back button (on-screen and hardware/gesture, both landing on Saved
Recordings) predates this and needed no changes — it's shared logic that doesn't care
which screen navigated in.

### 4.7 — Fix a stale back stack after saving (`stemz-app`)

Real bug, surfaced by 4.1's `startDestination` change.
`SaveRecordingFragment.navigateAfterSave()` hardcoded
`setPopUpTo(R.id.recordingFragment, false)`. Once `pcgScaleRecordingFragment` became the
actual start destination, `recordingFragment` was never on the back stack for that flow,
so the pop-up-to silently found nothing to remove — Saved Recordings got pushed **on top
of** the entire stale stack instead of replacing it. Symptom: pressing back a second
time from Saved Recordings landed on the "name your recording" screen instead of the
recorder.

`PcgScalePlayerFragment` was already sending the correct target
(`R.id.pcgScaleRecordingFragment`) as a `popUpToDestination` bundle extra when
navigating to Save — it was just never read. Added the same `popUpToDestinationId`
mechanism `app` already has: read the argument, default `recordingFragment` (so every
other caller — production `PlayerFragment`, Calibrated, FullTimeOn — is unaffected).

### 4.8 — Pre-amp default 5dB → 10dB (`stemz-app`, PcgScale recorder only)

Changed everywhere the default lived: the ViewModel's initial value, every fallback read
(slider init, `setPreAmplification`, display compensation, hand-off to the player), the
`onResume()` reset block (previously snapped back to 5dB every time you returned to the
screen), and the layout's design-time defaults. Slider range (0–30dB) unchanged.

### 4.9 — Saved-recording name in the Review title (`stemz-app`)

`PcgScaleReviewFragment`'s title was static "Review Recording" for every file. Now shows
the name the user actually typed when saving (filter prefix / `_filtered` suffix
stripped) — same convention and `HEART_HARD`-aware logic as `SavedRecordingAdapter` and
production `PlayerFragment`'s equivalent feature.

### 4.10 — Simplify the grid caption (`stemz-app`, all three screens)

`scaleCaption` — idle and while actively recording — now always reads exactly:

```
1 large box = 1 s · 1 small box = 0.2 s
```

Dropped: Y-axis fill %/scale info, "scroll to browse", and (recorder only) the live
diagnostic string that used to show while recording (`sr=%d Hz · peak=%.4f · Y=±%.3f%s`
with the MIN-CLAMPED note). Diagnostic logging (`TAAL_AUDIO_DEBUG` tag) is untouched —
only what's displayed on screen changed.

### 4.11 — Merge in `pcgscale-rev3`, restore Hum filter + Denoise for parity

Separately, on `pcgscale-rev3`, Arvind added **`PcgDisplayFilter.kt`** — a display-only
conditioning chain (click/USB-glitch removal, zero-phase 20–500 Hz band + hum notches,
transient-protected gate) — to both `app` and `stemz-app`, and wired it into
`SegmentationReportFragment.kt`: the segmentation *chart* now renders a
`PcgDisplayFilter.processOffline()`-conditioned copy of the audio, while segmentation
itself still runs on the untouched raw file (the filter is zero-phase, so beat timing —
and the overlay alignment — is unaffected). `app`'s existing Denoise toggle was also
updated to use this new filter instead of the old bare `PcgSpectralGate`.

`StemzAppBranch` merged `pcgscale-rev3` in (clean fast-forward, `StemzAppBranch`'s prior
HEAD was the merge-base — no conflicts). That brought the new `PcgDisplayFilter` in, but
initially landed it in `stemz-app` as **always-on** in `PcgScaleReviewFragment` (no user
toggle), respecting 4.2's earlier removal.

**Per explicit follow-up request, that removal was then reversed for full feature
parity with `app`:**
- **Denoise toggle** restored in `PcgScaleReviewFragment`/`fragment_pcgscale_review.xml`
  — same `denoiseSwitch` UI and toggle mechanism as before, just using `app`'s current
  `PcgDisplayFilter`-based implementation instead of the original `PcgSpectralGate`-based
  one from 4.2.
- **Hum filter** restored in `PcgScaleRecordingFragment`/`fragment_pcgscale_recording.xml`
  — `humFilterSwitch` UI, enabled/disabled alongside the other filter controls, and
  `setHumRumbleFilterEnabled(binding.humFilterSwitch.isChecked)` wired into
  `startRecording()` — identical to `app`'s mechanism.

`stemz-app` keeps everything else that made it different from `app` throughout this —
its own `TARGET_FILL_FRACTION` (0.50f vs 0.60f), Basic/Hard filter toggle, 14s auto-stop,
10dB pre-amp default, simplified caption, no EQ button, "TAAL Recorder"/"Review
Recording" titles, and "StemzApp" branding. Only Hum filter and Denoise came back, per
this specific request — §2's table reflects the current (post-4.11) state, not the
mid-branch state described in 4.2.

---

## 5. Open investigation — not fixed, no code changed

**Two large (near-full-height) spikes observed in a 14-second PcgScale review
recording**, at roughly the 12s and 13s marks, with no correspondingly loud audible
sound reported. Investigated by code review only (no device access) — current best read
is **not a rendering bug**:

- The project's own `PCGSCALE_LOCAL_FIXES_LEDGER.md` documents this exact phenomenon by
  name — a **"stethoscope thud"**: a short transient from the sensor making/losing
  contact. §3's outlier-rejection design is specifically built to keep the *axis scale*
  stable against exactly this, and it did (other heart-sound spikes on the same trace
  are still clearly readable, not squashed) — the **trace** still faithfully draws the
  real transient, which is deliberate, not a bug.
- Timing is suggestive: both spikes fall in the last ~2 seconds of a 14-second
  recording — right around when the 14s auto-stop (§4.1) fires, consistent with someone
  starting to lift/reposition the device as it approaches.
- Checked `PcgScaleReviewFragment.loadFullWaveform`'s decode/downsample path directly
  (this screen just decodes and plots the saved file — no live-recording ring-buffer or
  resize logic involved). Nothing found indicating an indexing/rendering bug that would
  fabricate a spike from normal data.
- **Not confirmed:** whether Basic or Hard filter was active (Hard's narrow 20–200Hz
  custom bandpass can *ring* on a sharp transient, amplifying a real bump further), or
  whether playback around 12–13s reveals an audible thud/handling sound.

Next step if picked up: play back that section, or check which filter was active,
before deciding any code change is warranted.

---

## 6. File reference (`stemz-app`)

```
stemz-app/src/main/java/com/musediagnostics/taal/app/
  ecg/pcgscale/
    PcgAmplitudeScale.kt        — Y-axis scale math (§3)
    PcgTimeScale.kt             — X-axis grid math (unmodified since rev3)
    PcgScaleEcgPaperView.kt     — grid drawing (unmodified since rev3)
    PcgScaleWaveformView.kt     — chart+grid stack, gesture sync (unmodified since rev3)
    (PcgSpectralGate.kt deleted — was denoise engine, §4.2)
  ui/pcgscale/
    PcgScaleRecordingFragment.kt   — recorder (Basic/Hard toggle, 14s auto-stop, §4.1)
    PcgScaleRecordingViewModel.kt  — recorder state (unmodified since rev3)
    PcgScalePlayerFragment.kt      — post-recording review/save screen
    PcgScaleReviewFragment.kt      — saved-recording review screen (from Saved Recordings)
  ui/segmentation/
    SegmentationReportFragment.kt — shared by all entry points (§4.6)

stemz-app/src/main/res/layout/
  fragment_pcgscale_recording.xml
  fragment_pcgscale_player.xml
  fragment_pcgscale_review.xml

stemz-app/src/main/res/navigation/nav_graph.xml   — all pcgScale* destinations/actions
stemz-app/src/main/AndroidManifest.xml            — stemzapp://pcgscale deep link
```

Same relative paths under `app/` for the unmodified/original versions.

---

## 7. Known loose ends

- `PcgScalePlayerFragment`'s Analyze button is wired but, per its current gating, only
  ever shows for an already-saved file — and in the live flow this screen is always
  reached with a brand-new, not-yet-saved recording. In practice, right now, only
  `PcgScaleReviewFragment`'s Analyze button is ever actually visible to a user.
- §5's spike investigation is genuinely open, not a "probably fine" close.
- Sharing a `.wav` to WhatsApp still transcodes to AAC and drops the filename even after
  a MIME-type fix applied earlier on this branch — unrelated to the graph, noted here
  only so it isn't mistaken for graph-related if seen in the same testing session.
