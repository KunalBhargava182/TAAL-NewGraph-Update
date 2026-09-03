# PcgScale Graph — stemz-app Handoff

**Audience:** written for a fresh AI session (or developer) with zero prior context on
this specific thread of work — verify claims against live source before acting on them,
same as every other doc in this repo.

**Branch:** `StemzAppBranch` (forked from `audio-diagnostics-2026-09`, itself built on
top of `pcgscale-rev3`). Pushed to `github.com/KunalBhargava182/TAAL-NewGraph-Update`.
As of writing, `StemzAppBranch` is 2 commits ahead of what's on GitHub (icon + app-name
renames) — push before relying on the remote copy matching this doc.

**Scope of this doc:** everything touching the PcgScale waveform/graph screens — the
time-true-grid, RMS-auto-scale recorder/player/review family. A couple of adjacent
stemz-app changes from the same session are noted briefly at the end for context, but
aren't the focus (they're covered in `AUDIO_DIAGNOSTICS_BRANCH_CHANGELOG.md`).

---

## 1. What PcgScale is

A screen family — **Recorder → Player (new recording) → Review (saved recording)** —
built as a fork of the "Calibrated" ECG-paper screens, with two deliberate differences:

1. **Time-true grid**: 1 large grid box = exactly 1 second, 1 small box = exactly 0.2s,
   always. No mm/DPI calibration, no paper speed, no zoom — the window is range-locked
   so horizontal scale can never distort.
2. **RMS auto-scaled Y-axis**: instead of a fixed axis (Calibrated screens) or the old
   adaptive warmup/peak lock (production screens), the axis is sized per-recording so
   the signal's typical peak fills a target fraction of the graph height.

Lives at:
- `ecg/pcgscale/` — pure-logic support classes (no Android UI): `PcgAmplitudeScale`,
  `PcgTimeScale`, `PcgScaleEcgPaperView`, `PcgScaleWaveformView`, `PcgSpectralGate`
  (app only — removed from stemz-app, see §3).
- `ui/pcgscale/` — the three fragments: `PcgScaleRecordingFragment`,
  `PcgScalePlayerFragment`, `PcgScaleReviewFragment`, `PcgScaleRecordingViewModel`.

Originally **`app`-only**. Ported into **`stemz-app`** this session (§2) — that's most
of what this doc covers.

---

## 2. The Y-axis auto-scale — how it works and what changed

### The formula (unchanged since `pcgscale-rev3`, in both `app` and `stemz-app`)

```
fullScale = medianWindowPeak / TARGET_FILL_FRACTION,  clamped to [MIN_FULL_SCALE, MAX_FULL_SCALE]
```

Where `medianWindowPeak` is: per 5-second window, take the hop (50ms slice) that's
**3rd-largest by RMS energy** (not the loudest — this rejects up to 2 transient-elevated
hops per window, e.g. contact thuds), use *that hop's peak amplitude*, then take the
**median** of these values across all closed windows (not the mean — rejects a whole
poisoned window like a cough or repositioning bump outright).

This lives in `ecg/pcgscale/PcgAmplitudeScale.kt`, in both `app` and `stemz-app`.

### Every constant, and what changed since `pcgscale-rev3`

| Constant | `pcgscale-rev3` | `app` (current) | `stemz-app` (current) | What it does |
|---|---|---|---|---|
| `TARGET_FILL_FRACTION` | `0.60f` | `0.60f` | **`0.50f`** | Typical peak fills this fraction of the graph's half-height. Lower = smaller trace, more headroom. |
| `MIN_FULL_SCALE` | `0.02f` | `0.005f` | `0.005f` | Floor on the axis half-range — see explanation below. |
| `MAX_FULL_SCALE` | `1.0f` | `1.0f` | `1.0f` | Ceiling on the axis half-range. |
| `RMS_HOP_SECONDS` | `0.05f` | `0.05f` | `0.05f` | Length of each measurement hop (50ms). |
| `PEAK_WINDOW_SECONDS` | `5f` | `5f` | `5f` | Window size (5s) the calibrating hop is picked from. |
| `OUTLIER_REJECTION_K` | `3` | `3` | `3` | Uses the 3rd-loudest hop per window as the calibrator. |
| `SMOOTHING_PER_UPDATE` | `0.15f` | `0.15f` | `0.15f` | Live-recorder easing rate toward a new axis target. |
| `DEFAULT_INITIAL_FULL_SCALE` | `0.10f` | `0.10f` | `0.10f` | Neutral starting axis before the first 5s window closes. |

### `MIN_FULL_SCALE`: why 0.02 → 0.005

`MIN_FULL_SCALE` is a safety floor — it stops the axis from shrinking toward zero on
pure silence/noise (which would otherwise blow tiny noise up to fill the whole graph).

The problem with `0.02f`: on a quiet-capture device (measured on a study Samsung unit),
a real peak of `0.008` naturally wants an axis of `0.008 / 0.6 ≈ 0.0133`. But
`0.0133 < 0.02`, so the floor forced the axis up to `0.02` anyway — **the floor itself**,
not the measurement, was setting the axis, so the trace only filled `0.008/0.02 = 40%`
instead of the intended 60%. Lowering the floor to `0.005` lets the axis land at its
correctly-computed target for quiet devices instead of being artificially inflated.

The on-screen `(MIN-CLAMPED)` caption note (when present) means *the floor, not the
signal, is currently setting the axis* — useful for diagnosing this exact class of bug
on a new device.

### Note on `stemz-app`'s current caption

As of this session, **the on-screen caption no longer shows any of this** — see §4.6.
It always reads `"1 large box = 1 s · 1 small box = 0.2 s"` now, on all three screens,
in every state. The diagnostic values above are still logged (`TAAL_AUDIO_DEBUG` tag),
just not displayed.

---

## 3. Porting PcgScale into stemz-app

Commit `d47c236` — **"Port PcgScale screens into stemz-app, keep current recorder as
default"**.

`stemz-app` had zero PcgScale files before this. Ported from `app`'s current state
(i.e. rev3 + the `MIN_FULL_SCALE` fix + the denoise feature + diagnostic logging — all
already present in `app` by the time of the port):

**Copied verbatim** (no `stemz-app`-specific behavior needed):
`PcgTimeScale.kt`, `PcgScaleEcgPaperView.kt`, `PcgScaleWaveformView.kt`,
`PcgScaleRecordingViewModel.kt`, `PcgScalePlayerFragment.kt` (later modified, see §5),
`fragment_pcgscale_player.xml`, `fragment_pcgscale_review.xml`.

**Adapted, not verbatim:**
- `PcgAmplitudeScale.kt` — copied, then `TARGET_FILL_FRACTION` changed 0.60→0.50 in a
  later commit (`904c1d6`).
- `PcgScaleRecordingFragment.kt` — copied, then its filter UI was swapped from `app`'s
  original 5-preset row (Heart/Lungs/Bowel/Pregnancy/Full-body) to `stemz-app`'s own
  **Basic/Hard heart filter toggle** (Basic = `PreFilter.HEART`, Hard = a local
  `"HEART_HARD"` name via `setCustomBandpass(20.0, 200.0)`, not a real taal-core
  preset) — matching `stemz-app`'s production `RecordingFragment.kt`. Also carries that
  same file's **14-second hard auto-stop** (`autoStopJob`, routed through the real
  `stopRecording()` so the filtered WAV header finalizes correctly — not
  `TaalRecorder.setRecordingTime`, which bypasses that).
- `fragment_pcgscale_recording.xml` — same filter-row swap applied to the layout.

**Not ported at all** (removed later, see next section):
- `PcgSpectralGate.kt` (the denoise engine) — ported initially, then deleted.

### Wiring

- **Nav graph** (`stemz-app/src/main/res/navigation/nav_graph.xml`): added
  `pcgScaleRecordingFragment`, `pcgScalePlayerFragment`, `pcgScaleReviewFragment` as new
  destinations. `startDestination` initially **stayed** `recordingFragment` (first
  request), then changed to `pcgScaleRecordingFragment` in a follow-up commit (`66269aa`
  — **"Make PcgScale stemz-app's default screen"**) — matches `app`'s current default.
- **Deep link**: `stemzapp://pcgscale` added to `AndroidManifest.xml`, mirroring the
  existing `stemzapp://calibrated` pattern (same intent-filter, two `<data>` tags).
- **Saved Recordings → tap a file**: `SavedRecordingsFragment.kt`'s `onPlay` now routes
  through `action_savedRecordings_to_pcgScaleReview` → `PcgScaleReviewFragment`, instead
  of the old `action_savedRecordings_to_player` → plain `PlayerFragment`.

---

## 4. Every stemz-app-specific PcgScale change made this session

In commit order. All verified with `./gradlew :stemz-app:assembleDebug` (BUILD
SUCCESSFUL) before committing, none pushed further than noted in §"Branch" above at
time of writing.

### 4.1 — Remove Hum filter and Denoise (`9ea66a7`)

Both were optional extras added to `app`'s PcgScale screens *after* `pcgscale-rev3`
(§2's constants table doesn't cover these — they're feature additions, not scale
tuning). Removed from `stemz-app` only; `app` keeps both.

- **Hum filter** ("Feature A", opt-in 50/100/150Hz hum + 25Hz rumble biquad, default
  off): dropped the `humFilterSwitch` UI from `fragment_pcgscale_recording.xml` and the
  `setHumRumbleFilterEnabled()` call from `PcgScaleRecordingFragment.kt`. `taal-core`
  defaults this off when never called, so recording behavior is unchanged — same as
  every other consumer (`lungs-app`, `visualizertaal-app`) that never touches the flag.
- **Denoise** ("Feature B", post-processing spectral-gate toggle on the review screen,
  display-only — playback audio untouched either way): dropped the `denoiseSwitch`/
  `denoiseRow` UI and `waveformLoadingIndicator` from `fragment_pcgscale_review.xml`,
  and `onDenoiseToggled()` + the cached-sample fields it needed from
  `PcgScaleReviewFragment.kt`. **Deleted** `stemz-app`'s copy of `PcgSpectralGate.kt`
  entirely — nothing references it anymore.

### 4.2 — Rename screen titles (`08be43b`)

Visible on-screen titles only — no file/class renames.
- `PcgScaleRecordingFragment`: `"PCG Scale Recorder"` → **`"TAAL Recorder"`**
- `PcgScalePlayerFragment` and `PcgScaleReviewFragment`: `"PCG Scale Review"` →
  **`"Review Recording"`**

Matches `stemz-app`'s existing production Recorder/Player screen titles.

### 4.3 — Remove Equalizer button everywhere (`070f116`)

Dropped the `eqButton` (top-bar icon) and its click listener from **every** screen that
had one in `stemz-app`: `PlayerFragment`, `CalibratedPlayerFragment`,
`FullTimeOnPlayerFragment`, `PcgScalePlayerFragment`, `PcgScaleReviewFragment`. Not just
PcgScale — a full sweep. The `equalizerFragment` nav destination and
`EqualizerFragment.kt` itself are left in place, just unreferenced, in case wanted back.

### 4.4 — Lower `TARGET_FILL_FRACTION` 60% → 50% (`904c1d6`)

Covered in §2's table. Applied to `stemz-app`'s `PcgAmplitudeScale.kt` only — `app`
stays at 60%. Updated the three on-screen scale captions to say "50% fill" at the time
(later removed entirely, see §4.6).

### 4.5 — Wire up "Analyze Heart Sounds" segmentation button (`7779545`)

Since PcgScale is now the default flow, the segmentation feature (which only existed on
the plain `PlayerFragment`) was unreachable. Added the same button + gating to both
PcgScale review-type screens:

- **`PcgScalePlayerFragment`**: uses its own `rawFilePath` nav argument (passed
  directly from the recorder), gated on the file being inside `filesDir/saved/` — so a
  brand-new, not-yet-saved recording keeps the button hidden (matches production; in
  practice this screen is always a fresh recording, so the button never actually shows
  here yet — see the note in §7).
- **`PcgScaleReviewFragment`**: always reached from Saved Recordings, so computes the
  saved `_raw.wav` companion path via `filePath.replace("_filtered.wav", "_raw.wav")`,
  same as production `PlayerFragment` does.

Both navigate to the existing `segmentationReportFragment` via two new nav actions
(`action_pcgScalePlayer_to_segmentationReport`,
`action_pcgScaleReview_to_segmentationReport`). Reuses the `fragment_player.xml` icon
drawable and `SegmentationFeature.ENABLED` gate.

**Confirmed (separately, by tracing the code): segmentation always analyzes the RAW
file, never the filtered one.** `SegmentationReportFragment` reads `rawFilePath` and
passes it straight into `TaalCardiacSegmentation.segmentRawWav(rawFile, ...)` —
intentional, since the algorithm expects pre-filter audio.

The segmentation report screen's back button (on-screen and hardware/gesture) already
always lands on Saved Recordings — that logic (`goToSavedRecordings()` in
`SegmentationReportFragment.kt`) predates this port and needed no changes; it's shared
by every entry point regardless of which screen navigated in.

### 4.6 — Fix stale back stack after saving (`aae428e`)

**Real bug**, surfaced by making PcgScale the default screen (§3's `66269aa`).

`SaveRecordingFragment.navigateAfterSave()` hardcoded
`setPopUpTo(R.id.recordingFragment, false)`. Once `pcgScaleRecordingFragment` became
the actual `startDestination`, `recordingFragment` was never on the back stack for that
flow, so the pop-up-to silently found nothing to remove — Saved Recordings got pushed
**on top of** the entire stale stack (`pcgScaleRecordingFragment → pcgScalePlayerFragment
→ saveRecordingFragment`) instead of replacing it. Symptom: pressing back a second time
from Saved Recordings landed on the "name your recording" screen instead of the
recorder.

Fix: `PcgScalePlayerFragment` was already sending the correct target
(`R.id.pcgScaleRecordingFragment`) as a `popUpToDestination` bundle extra when
navigating to Save — it was just never read. Added the same `popUpToDestinationId`
mechanism `app` already has to `SaveRecordingFragment.kt`: read the argument, default
`recordingFragment` (so every other caller — production `PlayerFragment`, Calibrated,
FullTimeOn — is unaffected), use it in the `popUpTo` call.

### 4.7 — Pre-amp default 5dB → 10dB (`dede9f5`)

`stemz-app`'s PcgScale recorder only. Changed everywhere the default lived:
`PcgScaleRecordingViewModel`'s initial `LiveData` value, every `?: 5` fallback read in
`PcgScaleRecordingFragment` (slider init, `TaalRecorder.setPreAmplification`, display
compensation, bundle hand-off to the player), the `onResume()` reset-to-default block
(previously snapped back to 5dB every time you returned to the screen), and the
layout's design-time slider/label defaults. Slider range (0–30dB) and clamp unchanged —
only the default/starting value moved.

### 4.8 — Saved-recording name in Review screen title (`fce4005`)

`PcgScaleReviewFragment`'s title was hardcoded to "Review Recording" for every file.
Now shows the name the user actually typed when saving (filter prefix / `_filtered`
suffix stripped) — same convention and `HEART_HARD`-aware logic already used by
`SavedRecordingAdapter` and the equivalent feature on production `PlayerFragment`. No
`isInSavedDir` gate needed — this screen is only ever reached from Saved Recordings, so
`filePath` is always an already-saved file.

### 4.9 — Simplify the grid caption (`930f944`)

All three screens' `scaleCaption` — both idle and while actively recording — now
**always** reads exactly:

```
1 large box = 1 s · 1 small box = 0.2 s
```

Dropped: the Y-axis fill/scale info ("Y: auto (50% fill, RMS)"), "scroll to browse",
and (on the recorder specifically) the live diagnostic string that used to show while
recording (`sr=%d Hz · peak=%.4f · Y=±%.3f%s` with the MIN-CLAMPED note). Applied to the
Kotlin caption assignments in all three fragments *and* the layouts' design-time
defaults. **Diagnostic logging (`TAAL_AUDIO_DEBUG`) is untouched** — only what's
displayed on screen changed; the same numbers are still in logcat.

---

## 5. Open investigation — not yet fixed, no action taken

**Two large (near-full-height) spikes observed in a 14-second PcgScale review recording**,
at roughly the 12s and 13s marks, with no correspondingly loud audible sound reported by
the user. Investigated (code-review only, no device access) — **not a rendering bug**
as far as could be determined:

- `docs/notes/PCGSCALE_LOCAL_FIXES_LEDGER.md` documents this exact phenomenon by name —
  a **"stethoscope thud"**: a short transient from the sensor making/losing contact.
  The axis-scale outlier-rejection (§2's `OUTLIER_REJECTION_K`/median design) is
  specifically built to stay stable against exactly this — and it did (other heart-sound
  spikes on the same trace are still clearly readable, not squashed). The **trace**
  still faithfully draws the real transient, though — that's deliberate, not a bug.
- Timing is suggestive: both spikes fall in the last ~2 seconds of a 14-second recording
  — i.e. right around when the 14s auto-stop (§3, carried from `RecordingFragment.kt`)
  would fire, consistent with someone starting to lift/reposition the device as it
  approaches.
- Checked `PcgScaleReviewFragment.loadFullWaveform`'s decode/downsample path directly
  (this screen just decodes+plots the saved file, no live-recording ring-buffer or
  resize logic involved) — nothing indicating an indexing/rendering bug that would
  fabricate a spike from normal data.
- **Not confirmed:** whether Basic or Hard filter was used (Hard's narrow 20-200Hz
  custom bandpass can *ring* on a sharp transient, amplifying a real bump further), or
  whether playback around 12–13s reveals an audible thud/handling sound.

**Next step, if picked up:** have the user play back that section, or check which
filter was active, before deciding whether any code change is warranted at all — current
best read is this is genuine captured data, not a bug.

---

## 6. Quick reference — file locations (`stemz-app`)

```
stemz-app/src/main/java/com/musediagnostics/taal/app/
  ecg/pcgscale/
    PcgAmplitudeScale.kt        — Y-axis scale math (§2)
    PcgTimeScale.kt             — X-axis grid math (unmodified since rev3)
    PcgScaleEcgPaperView.kt     — grid drawing (unmodified since rev3)
    PcgScaleWaveformView.kt     — chart+grid stack, gesture sync (unmodified since rev3)
    (PcgSpectralGate.kt deleted — was denoise engine, §4.1)
  ui/pcgscale/
    PcgScaleRecordingFragment.kt   — recorder (Basic/Hard toggle, 14s auto-stop, §3)
    PcgScaleRecordingViewModel.kt  — recorder state (unmodified since rev3)
    PcgScalePlayerFragment.kt      — post-recording review/save screen
    PcgScaleReviewFragment.kt      — saved-recording review screen (from Saved Recordings)
  ui/segmentation/
    SegmentationReportFragment.kt — shared by all entry points (§4.5)

stemz-app/src/main/res/layout/
  fragment_pcgscale_recording.xml
  fragment_pcgscale_player.xml
  fragment_pcgscale_review.xml

stemz-app/src/main/res/navigation/nav_graph.xml   — all pcgScale* destinations/actions
stemz-app/src/main/AndroidManifest.xml            — stemzapp://pcgscale deep link
```

---

## 7. Known loose ends (as of this doc)

- `PcgScalePlayerFragment`'s Analyze button is wired but, per its current gating, only
  ever shows for an already-saved file — and in the live flow this screen is always
  reached with a brand-new, not-yet-saved recording. In practice, right now, only
  `PcgScaleReviewFragment`'s Analyze button is ever actually visible to a user. Not a
  bug, just worth knowing before assuming both buttons get exercised.
- §5's spike investigation is unresolved — genuinely open, not a "should probably be
  fine" close.
- Sharing a `.wav` to WhatsApp still transcodes to AAC and drops the filename even after
  the MIME-type fix applied earlier this branch (see
  `AUDIO_DIAGNOSTICS_BRANCH_CHANGELOG.md`) — unrelated to the graph, noted here only so
  it isn't mistaken for graph-related if seen in the same testing session.
- Icon (T→S) and app-name ("StemzApp") changes are on `StemzAppBranch` locally but not
  yet pushed to GitHub as of this doc being written.
