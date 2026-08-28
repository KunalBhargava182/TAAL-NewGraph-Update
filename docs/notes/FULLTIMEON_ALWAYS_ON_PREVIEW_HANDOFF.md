# FullTimeOn Screens — Always-On Preview, Speaker Mute & Production Y-Axis Handoff Doc

> **Purpose of this file**: a self-contained snapshot of the **FullTimeOn Recorder / FullTimeOn
> Player** screens — what they are, how they diverged from the Calibrated screens they were
> cloned from, the always-on live-preview + speaker-mute feature (including the approach that
> was tried and dropped), the production-matching Y-axis/window-size work, the bugs found along
> the way and how each was fixed, the exact current code, and how it's wired into the app.
>
> Companion to `docs/notes/CALIBRATED_SCREENS_HANDOFF.md`, which covers the **Calibrated**
> screens (the pair FullTimeOn was cloned from) and the shared `GraphCalibration`/pinch/Apply
> mechanism in general. Read that one first if you need the base mm-accurate-grid architecture;
> this one picks up from "and then a second, independent screen pair was cloned from it."
>
> Written 2026-08-25. If code and this doc disagree, trust the code — but update this file to
> match before moving on, since its whole value is being accurate.

---

## 1. What this feature is

Two screens — **FullTimeOn Recorder** and **FullTimeOn Player** — created as an exact functional
clone of the Calibrated Recorder/Player (own package `ui.fulltimeon`, own layouts, own nav
destinations), then evolved independently and now diverged substantially:

- The graph runs **live from the moment the screen opens** (not just once you press Record) —
  audio streams in the background, the speaker can be unmuted to listen for placement, and BPM
  updates — before you've pressed anything.
- The **Y-axis and default time window now match the original production `RecordingFragment`/
  `PlayerFragment` exactly** (adaptive warmup/lock on the Recorder, fixed ±0.5 on the Player,
  10s/4s default windows) — not the Calibrated screens' own fixed-constant approach.
- A saved **Peak Size / Time Zoom calibration** (pinch on the Player, then Apply) still overrides
  those production defaults and applies to **both** screens, exactly like the Calibrated
  screens' own `GraphCalibration` mechanism — this part is shared infrastructure, unmodified.
- The mm-accurate ECG-paper grid/background is untouched throughout all of this — same
  `CalibratedWaveformView`/`CalibratedEcgPaperView` the Calibrated screens use.

### Protected / never touched by any of this work
`RecordingFragment.kt`, `RecordingViewModel.kt`, `PlayerFragment.kt`, the dormant test screens,
`MmScale.kt`, `EcgPaperView.kt`, `attrs_ecg_paper.xml`, `HeartBpmCalculator.kt`, and their layout
XMLs — read for reference (to port their exact behavior), never modified. Every Calibrated
screen file, and `DpiCalibration.kt`/`GraphCalibration.kt`, are also untouched — FullTimeOn reads
from `GraphCalibration` (shared, additive read/write it was already designed for) but the file
itself was never edited for this feature. `taal-core`/`taal-ui-kit`/`lungs-app` — untouched; the
whole "no listen-only recording mode" finding below was arrived at by reading, not changing, the
SDK.

**One shared-layer, purely-additive exception**: `CalibratedWaveformView.kt` gained one new pure
function, `ringTrimMinX()` (§5), used only by FullTimeOnRecordingFragment. Nothing existing in
that file was modified — the Calibrated screens keep their own unchanged inline copy of the same
formula and are unaffected.

---

## 2. Current build/run status

- `./gradlew :app:assembleDebug` — **passes**, zero errors.
- `./gradlew :app:testDebugUnitTest` — **passes**, 53/53 tests green (38 pre-existing +
  4 new `ringTrimMinX` tests + 11 new `FullTimeOnRecordingTransitionsTest` tests).
- **`nav_graph.xml` `startDestination` is currently `fullTimeOnRecordingFragment`** — this has
  flipped back and forth several times during dev/testing (once even by unrelated concurrent
  work testing a different feature). Check the comment next to `app:startDestination` at the top
  of that file for the current, authoritative state before assuming either screen is live.
- Tested on real hardware (Samsung SM-A066B) throughout development — every fix in §6 below was
  verified or corrected based on actual on-device behavior, not just code review.

---

## 3. File map

```
app/src/main/java/com/musediagnostics/taal/app/ui/fulltimeon/
    FullTimeOnRecordingFragment.kt    — 1214 lines
    FullTimeOnRecordingViewModel.kt   — 109 lines (also declares FullTimeOnRecordingUiState enum
                                         and FullTimeOnRecordingTransitions, the pure state-graph lookup)
    FullTimeOnPlayerFragment.kt       — 646 lines

app/src/main/res/layout/
    fragment_fulltimeon_recording.xml — 536 lines
    fragment_fulltimeon_player.xml    — 279 lines

app/src/main/res/drawable/
    ic_speaker_muted.xml              — new, muted-speaker icon (unmuted state reuses the
                                         existing ic_volume_up.xml)

app/src/main/res/values/strings.xml
    speaker_muted_description / speaker_unmuted_description — new content-description strings

app/src/test/java/com/musediagnostics/taal/app/ui/fulltimeon/
    FullTimeOnRecordingTransitionsTest.kt   — 11 tests, the state-machine graph

app/src/test/java/com/musediagnostics/taal/app/ecg/calibrated/
    CalibratedWaveformViewTest.kt      — gained 4 new tests for ringTrimMinX() (shared file,
                                         additive-only — see §1's exception note)

Modified (additive only, verified via git diff --stat):
    app/src/main/res/navigation/nav_graph.xml — 2 new destinations + actions, startDestination
    app/src/main/java/.../ecg/calibrated/CalibratedWaveformView.kt — +1 new pure function
```

Reused, unmodified, from the Calibrated/shared layer: `CalibratedWaveformView` (minus the one
additive function above), `CalibratedEcgPaperView`, `CalibratedMmScale`, `DpiCalibration`,
`GraphCalibration`.

---

## 4. Wiring — `nav_graph.xml`

```xml
<!-- FullTimeOn screens — clone of the Calibrated screens above under a new name (user
     request, 2026-08-20), currently identical behavior, own destinations so it can evolve
     independently. Temporarily the app's startDestination — see the comment on
     app:startDestination at the top of this file. -->
<fragment
    android:id="@+id/fullTimeOnRecordingFragment"
    android:name="com.musediagnostics.taal.app.ui.fulltimeon.FullTimeOnRecordingFragment"
    android:label="FullTimeOn Recorder"
    tools:layout="@layout/fragment_fulltimeon_recording">
    <action
        android:id="@+id/action_fullTimeOnRecording_to_fullTimeOnPlayer"
        app:destination="@id/fullTimeOnPlayerFragment" />
    <action
        android:id="@+id/action_fullTimeOnRecording_to_savedRecordings"
        app:destination="@id/savedRecordingsFragment" />
    <action
        android:id="@+id/action_fullTimeOnRecording_to_dpiCalibration"
        app:destination="@id/dpiCalibrationFragment" />
</fragment>

<fragment
    android:id="@+id/fullTimeOnPlayerFragment"
    android:name="com.musediagnostics.taal.app.ui.fulltimeon.FullTimeOnPlayerFragment"
    android:label="FullTimeOn Review"
    tools:layout="@layout/fragment_fulltimeon_player">
    <argument android:name="filePath" android:defaultValue="" app:argType="string" />
    <argument android:name="isNewRecording" android:defaultValue="false" app:argType="boolean" />
    <argument android:name="filterName" android:defaultValue="HEART" app:argType="string" />
    <argument android:name="preAmpDb" android:defaultValue="0" app:argType="integer" />
    <action android:id="@+id/action_fullTimeOnPlayer_to_equalizer" app:destination="@id/equalizerFragment" />
    <action android:id="@+id/action_fullTimeOnPlayer_to_addPatient" app:destination="@id/addPatientFragment" />
    <action android:id="@+id/action_fullTimeOnPlayer_to_saveRecording" app:destination="@id/saveRecordingFragment" />
</fragment>
```

Both share `savedRecordingsFragment`, `dpiCalibrationFragment`, `equalizerFragment`,
`addPatientFragment`, `saveRecordingFragment` with the rest of the app — no duplicates created
for those.

---

## 5. The current end-to-end flow (plain language)

1. **App opens** → straight into the FullTimeOn Recorder (when it's the startDestination).
2. **The instant the screen appears** (`onResume`) → a live audio stream auto-starts in the
   background against a throwaway temp file (§6.2) — nothing durable is written. The grid shows
   a centered message instead of a graph: *"Listen for correct placement, then press Record to
   see the graph."* BPM already updates in the background; the speaker toggle (muted by default,
   never persisted) lets you listen to judge placement by ear.
3. **Press Record** → the preview stream stops, a real `TaalRecorder` session starts. The
   message disappears; the real graph takes over, sized either by production's own two-phase
   warmup/lock (default) or by a saved Peak Size calibration if one exists (§6.6/§6.7). Audio is
   now **always** audible regardless of the mute toggle, and the toggle itself is hidden — it
   has no job during a real recording (§6.8).
4. **Press Stop** → the real recording finalizes (`_raw`/`_filtered` WAV pair), and you're
   navigated straight to the FullTimeOn Player with that file.
5. **Player screen** → opens with production's own defaults (4s window, fixed ±0.5) unless a
   calibration override is saved, in which case that wins instead.
6. **Pinch to zoom** (time and/or peak size), then **Apply** → saves the *effective* view
   (whatever you landed on, by any mix of pinch and prior calibration) as this device's new
   default, for **both** screens. If you never touched the graph, Apply is a no-op (§6.9)
   besides navigating back. **Reset** clears the saved calibration and returns both screens to
   production's own defaults.
7. **Back at the Recorder** (`onResume` again) → picks up whatever was just Applied/Reset and
   reflects it immediately, both in the live preview and in the next real recording.

---

## 6. Chronological log of decisions (what was tried, what broke, how it was actually fixed)

This is the part most worth reading before changing any of this again — several of the values
and mechanisms below were already tuned or fixed once based on real feedback or a real,
diagnosed bug; re-deriving from scratch risks re-discovering the same problem.

### 6.1 Screen creation

FullTimeOn Recorder/Player were created as an exact clone of the Calibrated Recorder/Player
(own package `ui.fulltimeon`, own layouts named `fragment_fulltimeon_*.xml`, own nav
destinations/actions) — at creation, behavior was 100% identical to Calibrated. Everything below
is how they diverged afterward. Reused rather than forked: `CalibratedWaveformView`,
`CalibratedEcgPaperView`, `CalibratedMmScale`, `DpiCalibration`, `GraphCalibration` — these are
generic paper/mm-scale/calibration plumbing, not specific to either screen pair's identity, so a
calibration Applied from either pair's Player currently affects both pairs' screens (they share
the same `SharedPreferences`-backed `GraphCalibration` store).

### 6.2 Always-on live preview + speaker mute — the SDK investigation (task spec, §2)

Before writing any code, `taal-core`'s actual API surface was checked directly (not assumed):

- **(a) A listen-only/no-file mode** — does not exist. `TaalRecorder.start()` requires a
  mandatory `.wav` raw-file path (`setRawAudioFilePath`), and `TaalAudioCapture` unconditionally
  opens a `FileOutputStream` on that path and writes every buffer to it for the life of the
  session. There is no parameter or mode that skips this.
- **(b) A null/temp destination param** — does not exist either. `setRawAudioFilePath` throws if
  the path doesn't end in `.wav`; there's no "don't write" flag.
- **(c) Record to a temp file and delete it** — the only option, and what was implemented: a
  second, independent `TaalRecorder` instance (`previewRecorder`) pointed at a **fixed-name**
  temp file in `requireContext().cacheDir` (`fto_live_preview.wav`), overwritten on every preview
  start, deleted on every preview stop/pause/destroy, never referenced by any save/discard path
  or the recordings library. One real saving: `setFilteredAudioFilePath()` is deliberately never
  called for preview — `TaalRecorder.start()` only opens/writes that second file if a path was
  set, so preview only ever produces the *one* throwaway raw file, not two.
- **Live audio monitoring already existed** — `startAudioMonitor()`/`AudioTrack`, writing every
  filtered buffer to the speaker unconditionally, predates this whole feature. No new audio path
  was built; the mute toggle just gates the existing `AudioTrack.write()` call (see §6.8 for a
  bug found later in exactly this gate).

### 6.3 The PREVIEW state machine

Added `PREVIEW` to `FullTimeOnRecordingUiState` (alongside pre-existing `IDLE`/`RECORDING`/
`STOPPED`). The real, literal state graph (not a simplified version of it) — see
`FullTimeOnRecordingTransitions` in the ViewModel file and its 11 unit tests:

```
IDLE -> PREVIEW        (every onResume: resetToIdle() then startPreview())
PREVIEW -> IDLE        (internal/transient — startPreview() itself starts with resetToIdle())
PREVIEW -> RECORDING   (pressing Record)
RECORDING -> STOPPED   (stopRecording()'s synchronous TaalRecorder callback)
STOPPED -> IDLE        (the next resetToIdle() — next onResume, or inline on mid-recording disconnect)
```

The spec's shorthand "IDLE→PREVIEW→RECORDING→PREVIEW" is the *externally observable* cycle;
concretely it's `IDLE -> PREVIEW -> RECORDING -> STOPPED -> IDLE -> PREVIEW` — the STOPPED hop is
real but momentary and its own UI branch is never rendered (`stopRecording()` navigates to the
Player immediately after).

Reconnect handling: `setupConnectionReceiver()`'s `onTaalConnect()` retries `startPreview()`
silently (no toast) only if no stream is currently running — event-driven, never a timer/polling
loop, so a missing device produces exactly one failure message (on the original `onResume`
attempt), never a retry storm.

### 6.4 What was tried for "always on" and then dropped: a live graph during PREVIEW

The **original** design (per the task spec) rendered the waveform live during PREVIEW too —
`updateWaveform(displayData)` was called from the preview `TaalRecorder`'s `onProgressUpdate`,
same as during real recording, on the theory that "BPM appearing and stabilizing, plus seeing
the trace, is the clearest signal placement is good."

**User feedback (2026-08-21): drop this.** The request became: no live graph until Record is
pressed — the grid should just show a small message in its center — and rely on **audio only**
(the speaker/mute toggle) to judge placement, saving the visual graph for after Record is
pressed (where it should then look and behave like production's own Recording screen).

What changed to implement this:
- `previewMessage` — a new centered `TextView` overlay on `fragment_fulltimeon_recording.xml`,
  declared after `calibratedWaveformView` (so it draws on top), shown in PREVIEW/IDLE, hidden in
  RECORDING.
- The preview `TaalRecorder.OnInfoListener.onProgressUpdate` had its `updateWaveform(...)` call
  (and the now-dead `preAmpDb`/`preAmpGain`/`displayData` computation that only fed it) removed
  entirely — it still calls `feedSpeaker(data)` and still runs BPM computation/`viewModel.setBpm`,
  so the mute toggle and the BPM readout both keep working exactly as before. The timer stays at
  its default the whole time (was already true, unrelated to this change).
- The real-recording listener was **not** touched by this — it still calls `updateWaveform(...)`
  every buffer, so the graph works identically to before the moment RECORDING starts.

Net effect: PREVIEW is now audio/BPM-only, by design; RECORDING is where the graph lives.

### 6.5 Paper speed / "too fast" — three attempts, the real cause found on the third

1. **First report**: "the graph moving screen is too fast, slow it down, for both screens."
   Changed `CalibratedPaperSpeed.SPEED_50` → `SPEED_25` on both fragments (must always match
   between them, or a recording looks different live vs. in review).
2. **Second report**: "now it's an optical illusion — it looks like it's moving in the opposite
   direction (a wagon-wheel/stroboscopic effect), and you didn't touch the framerate, right?"
   Correct — only paper speed was touched. Reverted to `SPEED_50` (the last non-illusory state)
   as a safe baseline. Asked where the illusion showed up: **"both screens."** That ruled out
   the Player's continuous camera-pan as the sole cause (the Recorder's camera is page-snap, not
   continuous) and pointed at something both screens share — the grid rendering itself, and
   specifically a resonance between the trace's per-update pixel step and the grid's line
   spacing.
3. Tried `SPEED_12_5` (a full standard step further away, not the nearest neighbor) on the
   theory that a bigger jump was more likely to clear the same resonance than an adjacent value.
4. **Third report**: still "moving very fast." At this point the *actual* root cause was found
   by re-examining the math instead of guessing another speed: the visible window
   (`CalibratedWaveformView.recomputeVisibleSeconds()`'s grid-derived `visibleSeconds(plotWidthPx)`)
   works out to roughly **2 seconds** on a typical phone screen, *regardless of paper speed* —
   meaning the page was flipping roughly 5x more often than production's own Recording screen,
   which shows a flat, hardcoded **10 seconds** per page (`WINDOW_SECONDS = 10f` in
   `RecordingFragment.kt`), and production's Player shows a flat **4 seconds**
   (`setVisibleXRangeMaximum(4f)` in `PlayerFragment.kt`, confirmed by direct read). Paper speed
   was never the real lever for "how often does the page turn" once the window itself is a fixed
   constant — only the window size is.
5. **The actual fix**: added `DEFAULT_WINDOW_SECONDS` — `10f` on the Recorder, `4f` on the
   Player, copied verbatim from production — and changed the fallback in each fragment's
   `onVisibleSecondsChanged` from the grid-derived `seconds` parameter to this constant (a saved
   Time Zoom override still takes priority over both, unchanged). Paper speed was reverted back
   to `SPEED_50` on both screens, since it was never the actual lever. Also fixed a
   pre-existing inconsistency while in there: the `else` branch of each `onVisibleSecondsChanged`
   callback was reading the raw `seconds` parameter directly instead of the already-resolved
   `currentWindowSeconds` field — fixed on both screens for consistency.

**Lesson recorded for next time**: when a scroll/paper-speed complaint comes in, check the
*window size* (`visibleSeconds`/`WINDOW_SECONDS`) before touching the paper-speed enum — the
enum only changes what a grid square *means*, not how often the camera resets.

### 6.6 Squeeze/zoom-out on the Player

Separately reported: pinch-zoom on the Player only ever let you zoom *in* (narrower window,
bigger-looking trace) — the ceiling (`setVisibleXRangeMaximum`) was capped at the default
window, so you could never "squeeze" back out further than that, e.g. to see an entire short
recording at once. Fixed by tracking `loadedDurationSecs` (the file's own length, set in
`renderWaveformEntries`) and using `maxOf(currentWindowSeconds, loadedDurationSecs.toFloat())`
as the ceiling everywhere it's set (`onVisibleSecondsChanged`'s else-branch,
`renderWaveformEntries`, and — a second bug found in the same pass — `applyBuiltInDefaultScale()`
via `forceVisibleSeconds()`'s own `setVisibleXRangeMaximum` call, which had been silently
re-narrowing the ceiling back down as a side effect of Reset, undoing the fix the moment Reset
was pressed).

### 6.7 Porting production's actual Y-axis behavior (user request: match `RecordingFragment`/`PlayerFragment` exactly)

Investigated both production screens directly rather than assuming:

- **`RecordingFragment.kt`**: adaptive, two-phase. `WARMUP_MS = 2000`, `HEADROOM = 1.5f`,
  `MIN_PEAK = 0.02f`. Axis starts at ±1.0; for the first `WARMUP_MS` of a session it tracks the
  true peak (`warmupPeak`) without touching the axis; once the warmup window elapses, it locks
  permanently to `(warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)` and is never rescaled again
  for the rest of that session.
- **`PlayerFragment.kt`**: flat, fixed ±0.5 — set once at setup, no per-file peak scan, never
  touched again. (A `HEADROOM` constant exists in that file too but is dead/unused — confirmed
  directly, not applied anywhere.)

Ported verbatim into FullTimeOn:
- `FullTimeOnRecordingFragment`: added `peakAmplitude`/`warmupPeak`/`warmupDone`/
  `lastPeakUpdateTime` fields and `WARMUP_MS`/`HEADROOM`/`MIN_PEAK` constants (identical values),
  the same bufferPeak-scan-then-lock block inserted into `updateWaveform()`, and reset call
  sites matching production's own (`resetToIdle()`, `startRecording()`'s pre-flight block).
  Removed the old `FIXED_FULL_SCALE` constant entirely (see §6.8 for what this broke and how it
  was re-fixed).
- `FullTimeOnPlayerFragment`: replaced `FIXED_FULL_SCALE = 0.10f` with `FIXED_Y_FULL_SCALE = 0.5f`,
  used identically to production — flat, set once, never adaptive.
- Also matched production's window defaults in the same pass — see §6.5 (`DEFAULT_WINDOW_SECONDS`).
- The mm-accurate paper grid/background was explicitly **kept** throughout this — only the
  *sizing rule*, not the grid rendering, was ported from production.

### 6.8 Bug: calibration override silently stopped applying to Y (and appeared to stop applying to X too)

**User report**: "whatever we apply after changing it should be applied for both screens — it's
not like when in the Player screen we have to set it again." Diagnosed by re-reading the exact
code (not by guessing): §6.7's port had a real, confirmed gap — `setupWaveformChart()` on
**both** fragments was changed to set the Y-axis unconditionally (±1.0-then-warmup on the
Recorder, flat `FIXED_Y_FULL_SCALE` on the Player) and **never read `calibrationOverride?.yFullScale`
at all anymore**. The Apply button still computed and saved a `yFullScale` correctly (via
`currentEffectiveYFullScale()`); it just had no reader left on either screen. So Peak Size
pinch+Apply appeared to do nothing, even though it was, silently, still being persisted.

**Fix — a saved override wins over the production default, on both screens, without reverting
the production-matching work**:
- **Player**: restored `val effectiveYFullScale = calibrationOverride?.yFullScale ?: FIXED_Y_FULL_SCALE`
  at setup, same one-line pattern as before §6.7 — a saved override overrides the flat default.
- **Recorder** (more involved, since production's own behavior here is a *process*, not a
  constant): added `resetYAxisForNewSession()` — if a saved override exists, skip the warmup
  entirely for that session and lock straight to the override's value (`warmupDone = true` from
  the start); with no override, run production's real adaptive warmup/lock unmodified. Wired
  into every session-start site: `setupWaveformChart()`, `resetToIdle()`, `startRecording()`'s
  pre-flight reset — so a saved Peak Size applies to **preview and real recording alike**, not
  just the Player.
- **A second, subtler bug found in the same pass**: `FullTimeOnRecordingFragment.onResume()` was
  re-reading `calibrationOverride` *after* already calling `resetToIdle()`/`startPreview()` (both
  of which read the field via `resetYAxisForNewSession()`) — meaning a just-Applied override
  wouldn't be picked up until the *next* resume. Fixed by moving the
  `calibrationOverride = GraphCalibration.getOverride(...)` line to run before those calls.

Time Zoom (X) itself was never actually broken by §6.7 (that read path was untouched) — but the
user's report of "both" not sticking is consistent with the Y-axis break above making Apply look
completely non-functional at a glance, and the `onResume()` ordering bug affected X too (the
override was correct but read at the wrong time), so both are now confirmed fixed together.

### 6.9 Apply as a no-op when nothing changed

**User request**: there's no separate "Done" button — Apply is the only action — so pressing it
without ever having touched the graph must do nothing (not silently re-save the same default as
if something had changed). Implemented `hasUserAdjustedView` (Player only): set `true` inside
`onChartGestureEnd` (any real pinch/pan), checked by the Apply handler (skips the save entirely,
but still navigates back), and reset to `false` after a real Apply and after Reset — so a bare
Apply immediately following either of those also correctly no-ops.

### 6.10 Bug: the mute toggle was muting real recordings too

**User request**: "once Recording is pressed, audio should auto-be-on — the speaker button is
only needed to listen when we're not recording." The mute gate (`isSpeakerMuted`, added for
PREVIEW) had been applied inside the shared `feedSpeaker()` helper, which both the preview *and*
the real-recording listener called — so muting during preview also silently muted a real
recording's live monitor, which was never the intent (recording audio was always unconditional
before the mute feature existed). Fixed by giving `feedSpeaker()` a `forceUnmuted: Boolean = false`
parameter; the real-recording listener now calls `feedSpeaker(data, forceUnmuted = true)` (always
audible, ignores the toggle entirely), while the preview listener's call is unchanged (still
respects the toggle). Also hid `speakerToggle` during `RECORDING` (`View.GONE`) since it has no
effect there — it reappears in `PREVIEW`/`IDLE`.

---
## 7. Current constants — quick reference

| Constant | Recorder | Player | Notes |
|---|---|---|---|
| `PaperSpeed` | `SPEED_50` | `SPEED_50` | Reverted twice (§6.5) after 25 and 12.5 were tried and didn't fix the real "too fast" cause. Purely cosmetic now (grid-square meaning), not a scroll-speed lever. |
| `DEFAULT_WINDOW_SECONDS` | `10f` | `4f` | Copied verbatim from production's `WINDOW_SECONDS`/`setVisibleXRangeMaximum(4f)`. Only used when no calibration override is saved. |
| Y-axis default (no override) | Adaptive: starts ±1.0, locks to `(warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)` after `WARMUP_MS` | Flat `FIXED_Y_FULL_SCALE = 0.5f` | Ported verbatim from production (§6.7) — the two screens are *supposed* to differ here, mirroring production's own Recording/Player split. |
| Y-axis with a saved override | Skips warmup entirely, locks straight to `calibrationOverride.yFullScale` for the whole session | `calibrationOverride?.yFullScale ?: FIXED_Y_FULL_SCALE` | A saved override always wins over the production default (§6.8). |
| `WARMUP_MS` / `HEADROOM` / `MIN_PEAK` | `2000` / `1.5f` / `0.02f` | n/a | Identical to production `RecordingFragment`. |
| Zoom-out ceiling (Player) | n/a | `maxOf(currentWindowSeconds, loadedDurationSecs)` | Lets pinch-out reach the whole file, not just the default window (§6.6). |
| `PREVIEW_SESSION_SECONDS` | `600` | n/a | Internal `TaalRecorder` duration cap for the temp-file preview stream; auto-restarts silently on expiry. Bounds the throwaway cache file to ~10 minutes at a time. |
| `isSpeakerMuted` default | `true`, reset every `onResume` | n/a (no Player toggle — see below) | Never persisted (user request: an app that starts making noise on its own is worse than one extra tap). |
| Mute toggle scope | PREVIEW only — `RECORDING` is always audible, toggle hidden then | — | Fixed in §6.10; was previously (briefly, incorrectly) muting real recordings too. |
| Graph during PREVIEW | None — `previewMessage` shown instead | n/a | Dropped per §6.4; audio/BPM still run. |
| `TRACE_LINE_WIDTH_DP` | `2.0f` | `3.0f` | Unchanged from the Calibrated screens' own already-tuned values — not revisited in this feature. |

---

## 8. Known gaps / open items

1. **No Player-side speaker/mute control.** Investigated directly: `TaalPlayer`'s internal
   `AudioTrack` is a private field with no exposed volume/mute method, and the only external
   lever (system stream volume) is global and would leak to other apps. Per the original task
   spec's own escape valve ("if this turns out to be more than a small change, skip it and
   report why"), this was skipped rather than reworking `TaalPlayer`'s audio path or touching
   the SDK. `FullTimeOnPlayerFragment` has no mute toggle at all.
2. **Preview writes a real (if temp) file continuously.** Unavoidable per the SDK investigation
   in §6.2 — `TaalRecorder` always writes its raw output somewhere. Bounded to ~10 minutes per
   session (`PREVIEW_SESSION_SECONDS`) and deleted aggressively (stop/pause/destroy), but a
   screen left open indefinitely will keep restarting this cycle in the background.
3. **BPM still computes during PREVIEW even though the graph doesn't render.** Not requested to
   be removed when the live-preview-graph idea was dropped (§6.4) — left running since it's a
   reasonable auxiliary signal and doesn't conflict with the "message in the grid" requirement.
   Flag it for removal if the user later wants PREVIEW to be audio-only with no numeric feedback
   either.
4. **`resetToIdle()` runs twice in a row inside `onResume()`/`startPreview()`** (once directly,
   once again via `startPreview()`'s own call to it) — harmless/idempotent, a pre-existing
   redundancy noticed while fixing §6.8, not worth optimizing given it costs nothing observable.
5. **Amplitude mismatch between live recording and review at non-default pre-amp** — same,
   already-known, already-accepted limitation as the Calibrated screens (see that handoff doc's
   own §8 item 6): the Player has no persisted per-file pre-amp metadata for recordings reopened
   later from the library.
6. **`startDestination` churn.** This has been flipped between `recordingFragment`,
   `calibratedRecordingFragment`, and `fullTimeOnRecordingFragment` several times during dev —
   including once by unrelated concurrent work testing the PCG segmentation feature. Always
   check the live comment in `nav_graph.xml` rather than assuming from this doc or memory.

---

## 9. Full current source

### 9.1 `ui/fulltimeon/FullTimeOnRecordingViewModel.kt`

```kotlin
package com.musediagnostics.taal.app.ui.fulltimeon

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.musediagnostics.taal.app.TaalApplication
import com.musediagnostics.taal.app.data.repository.RecordingRepository

/**
 * Clone of [com.musediagnostics.taal.app.ui.calibrated.CalibratedRecordingViewModel] under a
 * new screen name (user request) — same state shape, own package so it can evolve
 * independently of the Calibrated screens it was copied from.
 */
enum class FullTimeOnRecordingUiState {
    IDLE,       // Transient — construction-time only, immediately replaced by PREVIEW on first onResume
    PREVIEW,    // Live graph + BPM streaming from a temp file that is never surfaced; nothing saved
    RECORDING,  // Actively recording to the real output files
    STOPPED     // Recording finished, showing save options
}

/**
 * The FullTimeOn Recorder's actual state graph, as a pure, unit-testable lookup — mirrors
 * exactly what [com.musediagnostics.taal.app.ui.fulltimeon.FullTimeOnRecordingFragment] does,
 * not a simplified version of it:
 *  - `IDLE -> PREVIEW`: every `onResume()` (first screen entry, or returning from the Player)
 *    calls `resetToIdle()` (sets IDLE) immediately followed by `startPreview()` (sets PREVIEW).
 *  - `PREVIEW -> IDLE`: internal/transient — `startPreview()` itself starts with `resetToIdle()`,
 *    so every preview (re)start (including the natural session-timeout auto-restart and the
 *    reconnect retry) passes through IDLE for a single synchronous step before landing back on
 *    PREVIEW.
 *  - `PREVIEW -> RECORDING`: pressing Record (`startRecording()`); `TaalRecorder`'s
 *    `onStateChange(RecorderState.RECORDING)` sets it.
 *  - `RECORDING -> STOPPED`: `stopRecording()` calls `taalRecorder.stop()`, whose synchronous
 *    `onStateChange(RecorderState.STOPPED)` callback sets it — this is the literal, if
 *    momentary, hop the real code takes; the "STOPPED-state UI" itself is dead/never rendered
 *    (stopRecording() navigates to the Player immediately after).
 *  - `STOPPED -> IDLE`: the next `resetToIdle()` — either the following `onResume()` (normal
 *    return-from-Player flow) or immediately inline, in the mid-recording-disconnect handler.
 *
 * The spec's shorthand "IDLE→PREVIEW→RECORDING→PREVIEW" describes the externally observable
 * cycle; concretely it is `IDLE -> PREVIEW -> RECORDING -> STOPPED -> IDLE -> PREVIEW` — see
 * [FullTimeOnRecordingTransitionsTest] for that full chain asserted step by step.
 */
object FullTimeOnRecordingTransitions {
    private val legalEdges: Set<Pair<FullTimeOnRecordingUiState, FullTimeOnRecordingUiState>> = setOf(
        FullTimeOnRecordingUiState.IDLE to FullTimeOnRecordingUiState.PREVIEW,
        FullTimeOnRecordingUiState.PREVIEW to FullTimeOnRecordingUiState.IDLE,
        FullTimeOnRecordingUiState.PREVIEW to FullTimeOnRecordingUiState.RECORDING,
        FullTimeOnRecordingUiState.RECORDING to FullTimeOnRecordingUiState.STOPPED,
        FullTimeOnRecordingUiState.STOPPED to FullTimeOnRecordingUiState.IDLE,
    )

    fun isLegal(from: FullTimeOnRecordingUiState, to: FullTimeOnRecordingUiState): Boolean =
        from != to && (from to to) in legalEdges
}

class FullTimeOnRecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as TaalApplication).database
    val recordingRepository = RecordingRepository(db.recordingDao())

    private val _uiState = MutableLiveData(FullTimeOnRecordingUiState.IDLE)
    val uiState: LiveData<FullTimeOnRecordingUiState> = _uiState

    private val _timerSeconds = MutableLiveData(0)
    val timerSeconds: LiveData<Int> = _timerSeconds

    private val _currentFilter = MutableLiveData("HEART")
    val currentFilter: LiveData<String> = _currentFilter

    private val _bpm = MutableLiveData(0)
    val bpm: LiveData<Int> = _bpm

    private val _preAmpDb = MutableLiveData(5)
    val preAmpDb: LiveData<Int> = _preAmpDb

    var currentRecordingPath: String = ""
    var currentFilteredPath: String = ""
    var customLowCut: Float? = null
    var customHighCut: Float? = null

    fun setUiState(state: FullTimeOnRecordingUiState) {
        _uiState.value = state
    }

    fun updateTimer(seconds: Int) {
        _timerSeconds.value = seconds
    }

    fun setFilter(filter: String) {
        _currentFilter.value = filter
    }

    fun setBpm(bpm: Int) {
        _bpm.value = bpm
    }

    fun setPreAmp(db: Int) {
        _preAmpDb.value = db.coerceIn(0, 30)
    }

    fun formatTimer(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
}
```

### 9.2 `ui/fulltimeon/FullTimeOnRecordingFragment.kt`

```kotlin
package com.musediagnostics.taal.app.ui.fulltimeon

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalRecorder
import com.musediagnostics.taal.core.RecorderState
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentFulltimeonRecordingBinding
import com.musediagnostics.taal.app.dsp.HeartBpmCalculator
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedPaperSpeed
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration
import com.musediagnostics.taal.app.ecg.calibrated.GraphCalibration
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Clone of [com.musediagnostics.taal.app.ui.calibrated.CalibratedRecordingFragment] under a new
 * screen name (user request, 2026-08-20) — behavior is currently identical to that fragment,
 * just in its own package/nav destinations so it can be changed independently without touching
 * the Calibrated screens. See docs/notes/CALIBRATED_SCREENS_HANDOFF.md for the full history of
 * everything this was copied from.
 *
 * Reuses the same shared calibrated-graph infrastructure (`CalibratedWaveformView`,
 * `DpiCalibration`, `GraphCalibration`) rather than forking those too — they're generic
 * paper/mm-scale/calibration plumbing, not specific to the "Calibrated" screen identity, so a
 * calibration Applied from either screen pair currently affects both.
 *
 * Added 2026-08-21 — always-on live preview + speaker mute toggle. The graph (and BPM) run
 * continuously from the moment this screen is visible ([PREVIEW][FullTimeOnRecordingUiState]),
 * writing nothing durable to disk, so the clinician can confirm placement before pressing
 * Record. [taal-core]'s `TaalRecorder` has no listen-only/no-file mode (checked directly —
 * `start()` requires a mandatory `.wav` raw-file path that `TaalAudioCapture` unconditionally
 * writes to), so preview uses a second `TaalRecorder` instance ([previewRecorder]) pointed at a
 * fixed temp file in [android.content.Context.getCacheDir] — the *filtered* WAV is skipped
 * entirely (never calling `setFilteredAudioFilePath`), since `TaalRecorder.start()` only opens
 * that file if a path was set. The temp file is overwritten on every preview start and deleted
 * on every preview stop/pause/destroy; it is never referenced by any save/discard path or the
 * recordings library. Pressing Record stops preview and starts a real, unmodified recording
 * session — the real-recording code path below this comment is otherwise untouched by this
 * change, so the produced WAV files are byte-identical to before.
 *
 * The speaker toggle gates a monitor path that already existed here before this change
 * ([startAudioMonitor]/[feedSpeaker]) — muted by default, never persisted across screen visits.
 */
class FullTimeOnRecordingFragment : Fragment() {

    private var _binding: FragmentFulltimeonRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FullTimeOnRecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    private var audioTrack: AudioTrack? = null
    private val waveformEntries = ArrayList<Entry>()

    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    private var waveformDataSet: LineDataSet? = null
    private var totalSamplesProcessed = 0L  // Sample-accurate X position counter
    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // The visible window is derived from the grid's physical width, not a fixed constant; this
    // holds the most recently derived value (updated on layout/rotation).
    private var currentWindowSeconds = 4f

    // Derived so ~one min/max bucket lands per horizontal pixel at the current paper speed,
    // instead of a fixed constant. Recomputed only while idle (see onVisibleSecondsChanged
    // below) and held frozen for the whole recording session: the recorder mutates
    // LineDataSet.values in place against a monotonic sample-counter X axis, so changing the
    // bucket size mid-recording would space already-plotted points inconsistently with new ones.
    private var sessionBucketSize = DOWNSAMPLE_BUCKET_FALLBACK

    // Per-device calibration override (Time Zoom, set from the Player) — null means "use the
    // grid-derived default window." Re-read in onResume() so returning from the Player after
    // Apply/Reset reflects immediately. X (visibleSeconds) only now — Y is no longer read from
    // this; see peakAmplitude below (user request: match production RecordingFragment's actual
    // sizing behavior instead of a fixed/overridable constant).
    private var calibrationOverride: GraphCalibration.Override? = null

    // Ported verbatim from production RecordingFragment (user request, 2026-08-21: "the graph
    // of RecordingFragment like the size, and how do we set after the peaks") — two-phase
    // warmup/lock Y-axis, replacing FullTimeOn's previous fixed-axis approach. Phase 1 (first
    // WARMUP_MS of a session): axis stays ±1.0 while the true peak is observed. Phase 2: axis
    // locks to peakAmplitude and is never touched again until the next session's reset. Reset
    // points mirror production's exactly: resetToIdle() (idle/disconnect/preview-restart) and
    // startRecording()'s own pre-flight reset — a fresh warmup starts each time, and the grid
    // background/paper is completely unaffected (this is purely a trace-scale computation).
    private var peakAmplitude = 1.0f
    private var warmupPeak = 0f
    private var warmupDone = false
    private var lastPeakUpdateTime = 0L

    // Live preview — a second, independent TaalRecorder pointed at a throwaway cache file (see
    // class doc for why this is the only option the SDK supports). Never active at the same
    // time as [taalRecorder] — startRecording() always stops this first.
    private var previewRecorder: TaalRecorder? = null
    private lateinit var previewTempFile: File
    // Distinguishes an intentional stopPreview() from the recorder's own internal
    // PREVIEW_SESSION_SECONDS timeout — see the preview OnInfoListener's onStateChange.
    private var previewStoppedIntentionally = false

    // Speaker toggle — muted by default, reset every screen entry (never persisted, see class
    // doc). Read by feedSpeaker(), which both the preview and real-recording listeners call.
    private var isSpeakerMuted = true

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f

        // Pre-layout fallback only (pxPerMmX not yet known) — see deriveBucketSize/sessionBucketSize.
        private const val DOWNSAMPLE_BUCKET_FALLBACK = 88

        // Y-axis warmup/lock constants, copied verbatim from production RecordingFragment's
        // values (WARMUP_MS/HEADROOM/MIN_PEAK) — see peakAmplitude's doc above.
        private const val WARMUP_MS = 2000
        private const val HEADROOM = 1.5f
        private const val MIN_PEAK = 0.02f

        // Default visible time window in seconds — copied verbatim from production
        // RecordingFragment's own WINDOW_SECONDS (user request, 2026-08-21: match production's
        // speed/shape/working exactly). Used only when no calibration override is saved; a
        // saved Time Zoom override (pinch + Apply on the Player) still takes priority over
        // this, applies live to the Recorder too, and Reset returns here — see
        // onVisibleSecondsChanged below.
        private const val DEFAULT_WINDOW_SECONDS = 10f

        // Pre-amp slider should only change loudness, never the live graph's size — the trace
        // reflects true acoustic level, not the amplified WAV level.
        private const val COMPENSATE_PREAMP_IN_DISPLAY = true

        // Trace stroke width in dp.
        private const val TRACE_LINE_WIDTH_DP = 2.0f

        // How long a single preview capture session runs before TaalRecorder's own internal
        // duration cap silently stops it (see TaalAudioCapture's `endTime` check) and it's
        // seamlessly restarted (see the preview OnInfoListener's onStateChange). Bounds how
        // large the throwaway cache file can grow if a screen is left open indefinitely —
        // 10 minutes ≈ 53MB of raw PCM, not something worth carrying for hours.
        private const val PREVIEW_SESSION_SECONDS = 600
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(requireContext(), "Audio permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFulltimeonRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewTempFile = File(requireContext().cacheDir, "fto_live_preview.wav")

        setupWaveformChart()
        setupFilterButtons()
        setupPreAmpSlider()
        setupButtons()
        setupSpeakerToggle()
        observeState()
        setupConnectionReceiver()
        updateCalibrationCaption()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.uiState.value == FullTimeOnRecordingUiState.RECORDING) {
                        Toast.makeText(
                            requireContext(),
                            "Stop the recording before going back",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun setupWaveformChart() {
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart
        chart.setTouchEnabled(false) // no pan/zoom while recording — same as production

        calibrationOverride = GraphCalibration.getOverride(requireContext())
        resetYAxisForNewSession()

        // 50mm/s. Reverted from a couple of failed speed guesses (25, then 12.5) once the real
        // cause of "feels too fast" turned out to be something else entirely: with the window
        // width grid-derived (see onVisibleSecondsChanged below, before this fix), the page
        // flips as often as every ~2 seconds on a typical phone at any paper speed — paper
        // speed alone barely moves that. DEFAULT_WINDOW_SECONDS below is the actual fix; paper
        // speed only governs what a grid square *means* now, not how often the page turns.
        // Must match FullTimeOnPlayerFragment exactly, so a recording looks the same live as it
        // does in review.
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_50)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        waveformView.onVisibleSecondsChanged = { _ ->
            // The actual "too fast" fix (user request, 2026-08-21): default to production
            // RecordingFragment's own fixed DEFAULT_WINDOW_SECONDS (10s) instead of the grid's
            // physically-derived `seconds` (which on a typical phone is only ~2s wide — the
            // page was flipping 5x more often than production's). A saved Time Zoom override
            // still wins over both, same as before — see FullTimeOnPlayerFragment's identical
            // substitution. `seconds` itself is intentionally unused as a fallback now; it's
            // still the parameter this callback fires with (grid/layout derivation still runs,
            // e.g. on rotation), just no longer what picks the *default*.
            currentWindowSeconds = calibrationOverride?.visibleSeconds ?: DEFAULT_WINDOW_SECONDS
            // Don't clobber an in-progress recording's dataset on rotation/resize — only
            // reset to the dummy grid-holder dataset when there's no real data yet.
            // updateWaveform() re-enforces the range every callback while recording is live.
            if (waveformDataSet == null) {
                // Only re-derive the bucket size while idle; a session in progress must keep
                // using whatever was frozen when it started (see sessionBucketSize doc).
                //
                // Derived from the *effective* window (currentWindowSeconds, which already
                // folds in any calibration override above), not from paper speed alone —
                // deriveBucketSize() assumes the grid's native, un-overridden pixel density,
                // so a saved override that narrows the window (more zoomed in) would leave the
                // bucket sized for the wider native view: each min/max pair stretched across
                // several pixels instead of one, reading as noisy/jagged.
                val plotWidthPx = waveformView.chart.width.toFloat()
                val visibleSampleCount = currentWindowSeconds * INPUT_SAMPLE_RATE
                val derived = if (plotWidthPx > 0f) {
                    CalibratedWaveformView.deriveBucketSizeForVisibleRange(visibleSampleCount, plotWidthPx)
                } else {
                    CalibratedWaveformView.deriveBucketSize(
                        INPUT_SAMPLE_RATE,
                        waveformView.paperView.currentScale().paperSpeed.mmPerSecond,
                        waveformView.paperView.currentScale().pxPerMmX
                    )
                }
                if (derived > 0) sessionBucketSize = derived
                resetChartToDummyData()
            } else {
                // currentWindowSeconds (already resolved above), not raw `seconds` — a
                // layout/rotation re-derivation must not silently override the fixed default
                // or an active calibration override mid-session.
                chart.setVisibleXRangeMaximum(currentWindowSeconds)
                chart.setVisibleXRangeMinimum(currentWindowSeconds)
                chart.invalidate()
            }
        }
        // If layout already happened (e.g. returning to this fragment), derive immediately.
        waveformView.recomputeVisibleSeconds()
        if (chart.data == null) resetChartToDummyData()
    }

    /**
     * Y-axis reset for the start of a new PREVIEW or RECORDING session — call sites:
     * setupWaveformChart() (fragment creation), resetToIdle() (idle/disconnect/preview
     * restart), startRecording()'s pre-flight reset, and onResume() (returning from the
     * Player). A saved calibration override wins outright: skip the warmup entirely and lock
     * straight to it, exactly like a saved value did before production's adaptive behavior was
     * ported in (user request, 2026-08-21 — Apply/Reset must actually take effect on this
     * screen, not just get silently ignored). With no override, production's own two-phase
     * warmup/lock behavior runs unmodified — see peakAmplitude's field doc.
     */
    private fun resetYAxisForNewSession() {
        if (_binding == null) return
        val chart = binding.calibratedWaveformView.chart
        val savedYFullScale = calibrationOverride?.yFullScale
        warmupPeak = 0f
        lastPeakUpdateTime = 0L
        if (savedYFullScale != null && savedYFullScale > 0f) {
            peakAmplitude = savedYFullScale
            warmupDone = true // override wins — no warmup, no auto re-lock this session
        } else {
            peakAmplitude = 1.0f
            warmupDone = false
        }
        chart.axisLeft.axisMinimum = -peakAmplitude
        chart.axisLeft.axisMaximum = peakAmplitude
    }

    private fun resetChartToDummyData() {
        if (_binding == null) return
        val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(currentWindowSeconds, 0f)), "").apply {
            color = Color.TRANSPARENT
            setDrawCircles(false)
            setDrawValues(false)
        }
        binding.calibratedWaveformView.chart.data = LineData(dummyDataSet)
        binding.calibratedWaveformView.chart.invalidate()
    }

    private fun updateCalibrationCaption() {
        if (_binding == null) return
        val correction = DpiCalibration.getCorrection(requireContext())
        val status = if (correction.isCalibrated) "calibrated (${correction.source})" else "UNCALIBRATED"
        val speed = binding.calibratedWaveformView.paperView.currentScale().paperSpeed.mmPerSecond
        // "auto" not "fixed" — Y-axis now warms up and locks to the signal each session,
        // matching production RecordingFragment, instead of a constant/overridable scale.
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude (auto) · DPI: $status"
    }

    private fun setupPreAmpSlider() {
        binding.ampSlider.value = (viewModel.preAmpDb.value ?: 5).toFloat()
        binding.ampLabel.text = "${viewModel.preAmpDb.value ?: 5} dB"

        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            viewModel.setPreAmp(db)
            binding.ampLabel.text = "$db dB"
            // Also push to previewRecorder (not just taalRecorder) — while previewing with the
            // speaker unmuted, dragging this slider should change what's actually heard live,
            // so the clinician can dial in the right dB by ear before pressing Record (user
            // request). Both are never non-null at the same time, so at most one call is real.
            taalRecorder?.setPreAmplification(db)
            previewRecorder?.setPreAmplification(db)
        }
    }

    /** Muted by default (see [isSpeakerMuted] doc) — click toggles, icon/contentDescription reflect state. */
    private fun setupSpeakerToggle() {
        updateSpeakerToggleIcon()
        binding.speakerToggle.setOnClickListener {
            isSpeakerMuted = !isSpeakerMuted
            updateSpeakerToggleIcon()
        }
    }

    private fun updateSpeakerToggleIcon() {
        if (_binding == null) return
        if (isSpeakerMuted) {
            binding.speakerToggle.setImageResource(R.drawable.ic_speaker_muted)
            binding.speakerToggle.contentDescription = getString(R.string.speaker_muted_description)
        } else {
            binding.speakerToggle.setImageResource(R.drawable.ic_volume_up)
            binding.speakerToggle.contentDescription = getString(R.string.speaker_unmuted_description)
        }
    }

    /**
     * Output-only — never affects what's written to disk or what the graph draws, just whether
     * the pre-existing speaker monitor ([startAudioMonitor]) actually plays this buffer. Shared
     * by both the preview and real-recording [TaalRecorder.OnInfoListener]s.
     */
    /**
     * [forceUnmuted] — the speaker toggle only means anything during PREVIEW (user request,
     * 2026-08-22): once RECORDING actually starts, audio plays unconditionally, same as it
     * always did before the mute toggle existed. The real-recording listener passes true here;
     * the preview listener leaves it false and respects [isSpeakerMuted] as before.
     */
    private fun feedSpeaker(data: FloatArray, forceUnmuted: Boolean = false) {
        if (isSpeakerMuted && !forceUnmuted) return
        audioTrack?.let { track ->
            val pcm = ShortArray(data.size) { i ->
                (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            track.write(pcm, 0, pcm.size)
        }
    }

    private fun setupFilterButtons() {
        val presetFilters = mapOf(
            binding.filterHeart to "HEART",
            binding.filterLungs to "LUNGS",
            binding.filterBowel to "BOWEL",
            binding.filterPregnancy to "PREGNANCY",
            binding.filterInfo to "FULL_BODY"
        )
        val allButtons = presetFilters.keys + binding.filterCustom

        binding.filterHeart.isSelected = true
        viewModel.setFilter("HEART")
        binding.customRangePanel.visibility = View.GONE

        presetFilters.forEach { (button, name) ->
            button.setOnClickListener {
                allButtons.forEach { it.isSelected = false }
                button.isSelected = true
                viewModel.setFilter(name)
                binding.customRangePanel.visibility = View.GONE
                dismissKeyboard()
            }
        }

        binding.filterCustom.setOnClickListener {
            allButtons.forEach { it.isSelected = false }
            binding.filterCustom.isSelected = true
            viewModel.setFilter("CUSTOM")
            binding.customRangePanel.visibility = View.VISIBLE
        }

        setupCustomRangePanel()
    }

    private fun dismissKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)
        requireView().clearFocus()
    }

    private fun setupCustomRangePanel() {
        val initLow = viewModel.customLowCut ?: 20f
        val initHigh = viewModel.customHighCut ?: 10000f

        viewModel.customLowCut = initLow
        viewModel.customHighCut = initHigh

        binding.customRangeSlider.values = listOf(
            initLow.coerceIn(0f, 24000f),
            initHigh.coerceIn(0f, 24000f)
        )
        binding.customLowCutInput.setText(initLow.toInt().toString())
        binding.customHighCutInput.setText(initHigh.toInt().toString())

        var isUpdating = false

        binding.customRangeSlider.addOnChangeListener { _, _, _ ->
            if (isUpdating) return@addOnChangeListener
            isUpdating = true
            val vals = binding.customRangeSlider.values
            val low = vals[0].toInt()
            val high = vals[1].toInt()
            binding.customLowCutInput.setText(low.toString())
            binding.customHighCutInput.setText(high.toString())
            viewModel.customLowCut = vals[0]
            viewModel.customHighCut = vals[1]
            isUpdating = false
        }

        binding.customLowCutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                viewModel.customLowCut = v.coerceIn(1f, 24000f)
                val currentHigh = binding.customRangeSlider.values[1]
                if (v in 1f..24000f && v < currentHigh) {
                    isUpdating = true
                    binding.customRangeSlider.values = listOf(v, currentHigh)
                    isUpdating = false
                }
            }
        })

        binding.customHighCutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                viewModel.customHighCut = v.coerceIn(1f, 24000f)
                val currentLow = binding.customRangeSlider.values[0]
                if (v in 1f..24000f && v > currentLow) {
                    isUpdating = true
                    binding.customRangeSlider.values = listOf(currentLow, v)
                    isUpdating = false
                }
            }
        })
    }

    private fun setupConnectionReceiver() {
        connectionReceiver = TaalConnectionBroadcastReceiver(object :
            TaalConnectionBroadcastReceiver.TaalConnectionListener {
            override fun onTaalConnect() {
                activity?.runOnUiThread {
                    binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))
                    // Event-driven retry only (never a timer/loop) — if preview failed to
                    // start earlier because no device was attached, the device physically
                    // reappearing is exactly the signal to try again. Silent: the one clear
                    // failure message was already shown when this first failed; retrying here
                    // must not add a second one on every reconnect.
                    if (isAdded && _binding != null &&
                        viewModel.uiState.value == FullTimeOnRecordingUiState.PREVIEW &&
                        previewRecorder == null && taalRecorder == null
                    ) {
                        startPreview(showErrorOnFailure = false)
                    }
                }
            }

            override fun onTaalDisconnect() {
                activity?.runOnUiThread {
                    binding.deviceIcon.setColorFilter(Color.parseColor("#333333"))
                }
            }
        })
        connectionReceiver?.register(requireContext())
    }

    private fun checkDeviceConnectionStatus() {
        try {
            val usbManager =
                requireContext().getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            if (usbManager.deviceList.isNotEmpty()) {
                binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))
            } else {
                binding.deviceIcon.setColorFilter(Color.parseColor("#333333"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupButtons() {
        binding.infoButton.setOnClickListener {
            val filterName = viewModel.currentFilter.value ?: "HEART"
            com.musediagnostics.taal.app.ui.recording.FilterPlacementDialog.newInstance(filterName)
                .show(parentFragmentManager, "filter_placement")
        }

        binding.recordButton.setOnClickListener {
            when (viewModel.uiState.value) {
                FullTimeOnRecordingUiState.IDLE, FullTimeOnRecordingUiState.PREVIEW -> checkPermissionAndRecord()
                FullTimeOnRecordingUiState.RECORDING -> stopRecording()
                else -> resetToIdle()
            }
        }

        // Dead in production too (STOPPED-state UI is never entered — stopRecording()
        // navigates directly to the player) — kept only so the layout/ID surface stays a
        // faithful replica.
        binding.playPauseButton.setOnClickListener {
            val filteredPath = viewModel.currentFilteredPath
            val rawPath = viewModel.currentRecordingPath
            val filterName = viewModel.currentFilter.value ?: "HEART"
            if (filteredPath.isNotEmpty()) {
                val bundle = Bundle().apply {
                    putString("filePath", filteredPath)
                    putString("rawFilePath", rawPath)
                    putString("filterName", filterName)
                    putInt("preAmpDb", viewModel.preAmpDb.value ?: 5)
                }
                findNavController().navigate(R.id.action_fullTimeOnRecording_to_fullTimeOnPlayer, bundle)
            }
        }

        binding.folderButton.setOnClickListener {
            findNavController().navigate(R.id.action_fullTimeOnRecording_to_savedRecordings)
        }

        binding.settingsButton.setOnClickListener {
            findNavController().navigate(R.id.action_fullTimeOnRecording_to_dpiCalibration)
        }
    }

    private fun resetToIdle() {
        viewModel.setUiState(FullTimeOnRecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        viewModel.currentFilteredPath = ""
        waveformEntries.clear()
        waveformDataSet = null
        totalSamplesProcessed = 0L
        // Fresh Y-axis for the next session — override-aware (see resetYAxisForNewSession doc).
        resetYAxisForNewSession()

        resetChartToDummyData()
        binding.calibratedWaveformView.chart.moveViewToX(0f)

        binding.bpmText.text = "-- BPM"
        updateCalibrationCaption()
    }

    private fun setFilterButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        listOf(
            binding.filterHeart,
            binding.filterLungs,
            binding.filterBowel,
            binding.filterPregnancy,
            binding.filterInfo,
            binding.filterCustom
        ).forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
        if (!enabled) {
            binding.customRangePanel.visibility = View.GONE
        } else if (viewModel.currentFilter.value == "CUSTOM") {
            binding.customRangePanel.visibility = View.VISIBLE
        }
    }

    private fun observeState() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                // PREVIEW looks identical to IDLE — Record is still the primary action, filters
                // and pre-amp are still editable, timer stays at its default (never runs in
                // preview). IDLE itself is transient (see enum doc) — real devices land in
                // PREVIEW within the same onResume call that first sets it.
                FullTimeOnRecordingUiState.IDLE, FullTimeOnRecordingUiState.PREVIEW -> {
                    binding.actionText.text = getString(R.string.start_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.VISIBLE
                    binding.preRecordingButtons.visibility = View.VISIBLE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordButton.setImageResource(R.drawable.ic_recording_start1)
                    binding.timerText.text = getString(R.string.timer_default)
                    binding.ampSlider.isEnabled = true
                    binding.ampSliderContainer.alpha = 1f
                    setFilterButtonsEnabled(true)
                    // No live graph in PREVIEW anymore (user request) — audio/speaker/BPM keep
                    // working, but the grid just shows this message until Record is pressed.
                    binding.previewMessage.visibility = View.VISIBLE
                    // The mute toggle only does anything during PREVIEW (user request) —
                    // recording is always audible regardless of it, see feedSpeaker's doc.
                    binding.speakerToggle.visibility = View.VISIBLE
                }

                FullTimeOnRecordingUiState.RECORDING -> {
                    binding.actionText.text = getString(R.string.stop_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.GONE
                    binding.preRecordingButtons.visibility = View.GONE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordButton.setImageResource(R.drawable.ic_recording_stop)
                    binding.ampSlider.isEnabled = false
                    binding.ampSliderContainer.alpha = 0.55f
                    setFilterButtonsEnabled(false)
                    binding.previewMessage.visibility = View.GONE
                    // Hidden while recording — it wouldn't do anything, audio is always on.
                    binding.speakerToggle.visibility = View.GONE
                }

                else -> {}
            }
        }

        viewModel.timerSeconds.observe(viewLifecycleOwner) { seconds ->
            binding.timerText.text = viewModel.formatTimer(seconds)
        }

        viewModel.bpm.observe(viewLifecycleOwner) { bpm ->
            binding.bpmText.text = if (bpm > 0) getString(R.string.bpm_format, bpm) else "-- BPM"
        }
    }

    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        dismissKeyboard()

        // PREVIEW → RECORDING (§4.2): stop the preview stream before claiming the device for
        // real. TaalAudioCapture.stopRecording() releases the AudioRecord synchronously (not
        // deferred), so this brief gap is safe — the real TaalRecorder.start() below reliably
        // gets a clean claim on the USB device, and nothing about the real-recording path past
        // this point is changed by this feature.
        stopPreview()

        val filterName = viewModel.currentFilter.value ?: "HEART"

        if (filterName == "CUSTOM") {
            val low = viewModel.customLowCut
            val high = viewModel.customHighCut
            val lowText = binding.customLowCutInput.text?.toString()?.trim()
            val highText = binding.customHighCutInput.text?.toString()?.trim()

            val message = when {
                lowText.isNullOrEmpty() && highText.isNullOrEmpty() ->
                    "Low Cut and High Cut cannot be blank.\nPlease enter valid frequency values (e.g. Low Cut: 20 Hz, High Cut: 1000 Hz)."
                lowText.isNullOrEmpty() ->
                    "Low Cut cannot be blank.\nPlease enter a frequency greater than 0 Hz."
                highText.isNullOrEmpty() ->
                    "High Cut cannot be blank.\nPlease enter a frequency greater than 0 Hz."
                low == null || low <= 0f ->
                    "Low Cut cannot be 0 Hz.\nA value of 0 Hz disables the filter entirely. Please enter a frequency greater than 0 Hz (e.g. 20 Hz)."
                high == null || high <= 0f ->
                    "High Cut cannot be 0 Hz.\nA value of 0 Hz disables the filter entirely. Please enter a frequency greater than 0 Hz (e.g. 1000 Hz)."
                low >= high ->
                    "Low Cut (${low.toInt()} Hz) must be less than High Cut (${high.toInt()} Hz).\nPlease adjust the values so the passband is valid."
                else -> null
            }

            if (message != null) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Custom Filter")
                    .setMessage(message)
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .show()
                return
            }
        }

        try {
            val ts = System.currentTimeMillis()
            val rawFilePath = "${requireContext().filesDir}/fto_recording_${ts}_raw.wav"
            val filteredFilePath = "${requireContext().filesDir}/fto_recording_${ts}_filtered.wav"
            viewModel.currentRecordingPath = rawFilePath
            viewModel.currentFilteredPath = filteredFilePath

            taalRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(rawFilePath)
                setFilteredAudioFilePath(filteredFilePath)
                setRecordingTime(300)
                setPlayback(false)
                setPreAmplification(viewModel.preAmpDb.value ?: 5)
                if (filterName == "CUSTOM") {
                    setCustomBandpass(
                        viewModel.customLowCut!!.toDouble(),
                        viewModel.customHighCut!!.toDouble()
                    )
                } else {
                    setPreFilter(PreFilter.valueOf(filterName))
                }

                onInfoListener = object : TaalRecorder.OnInfoListener {
                    override fun onStateChange(state: RecorderState) {
                        activity?.runOnUiThread {
                            when (state) {
                                RecorderState.RECORDING -> viewModel.setUiState(FullTimeOnRecordingUiState.RECORDING)
                                RecorderState.STOPPED -> viewModel.setUiState(FullTimeOnRecordingUiState.STOPPED)
                                else -> {}
                            }
                        }
                    }

                    override fun onRawProgressUpdate(data: FloatArray) {}

                    override fun onDeviceDisconnected() {
                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                stopAudioMonitor()
                                taalRecorder = null

                                if (viewModel.currentRecordingPath.isNotEmpty()) {
                                    try { File(viewModel.currentRecordingPath).delete() } catch (_: Exception) {}
                                }
                                if (viewModel.currentFilteredPath.isNotEmpty()) {
                                    try { File(viewModel.currentFilteredPath).delete() } catch (_: Exception) {}
                                }

                                Toast.makeText(
                                    requireContext(),
                                    "Device disconnected. Please connect the device.",
                                    Toast.LENGTH_LONG
                                ).show()
                                resetToIdle()
                                // Attempt to resume the always-on preview right away rather
                                // than waiting for the next onResume (the fragment stays
                                // visible through this whole flow) — fails silently since the
                                // device just disconnected; setupConnectionReceiver's
                                // onTaalConnect() retries once it's replugged. showErrorOnFailure
                                // is false so this doesn't add a second toast on top of the one
                                // just shown above.
                                startPreview(showErrorOnFailure = false)
                            }
                        }
                    }

                    override fun onSilentRecordingDetected(isFirstSinceConnect: Boolean) {
                        if (!isFirstSinceConnect) return
                        activity?.runOnUiThread {
                            val act = activity ?: return@runOnUiThread
                            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                            android.app.AlertDialog.Builder(act)
                                .setTitle("Ready to Capture")
                                .setMessage("Your TAAL device has been detected and is now ready. Please discard this recording and start a new one.")
                                .setPositiveButton("OK", null)
                                .setCancelable(false)
                                .show()
                        }
                    }

                    override fun onProgressUpdate(
                        sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
                    ) {
                        // Always audible while actually recording (user request) — the mute
                        // toggle only applies during PREVIEW, see feedSpeaker's doc.
                        feedSpeaker(data, forceUnmuted = true)

                        val shouldCompute = bpmCalculator.addSamples(data)
                        if (shouldCompute) {
                            bpmScope.launch {
                                val bpm = bpmCalculator.computeBpm()
                                if (bpm > 0) {
                                    withContext(Dispatchers.Main) {
                                        if (isAdded && _binding != null) {
                                            viewModel.setBpm(bpm)
                                        }
                                    }
                                }
                            }
                        }

                        // Undo pre-amp gain before drawing (COMPENSATE_PREAMP_IN_DISPLAY, on by
                        // default) — the trace reflects true acoustic level, not the amplified
                        // WAV level, so the pre-amp slider only changes loudness, never the
                        // live graph's size.
                        val preAmpDb = viewModel.preAmpDb.value ?: 5
                        val preAmpGain = Math.pow(10.0, preAmpDb / 20.0).toFloat()
                        val displayData = if (COMPENSATE_PREAMP_IN_DISPLAY && preAmpGain > 1.001f) {
                            FloatArray(data.size) { i -> data[i] / preAmpGain }
                        } else {
                            data
                        }

                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                updateWaveform(displayData)
                                val elapsed = timeStamp.toInt()
                                viewModel.updateTimer(elapsed)
                            }
                        }
                    }
                }
            }

            waveformEntries.clear()
            waveformDataSet = null
            totalSamplesProcessed = 0L
            bpmCalculator.reset()
            // Fresh Y-axis for this recording — override-aware (see resetYAxisForNewSession
            // doc): a saved Peak Size calibration wins here too, not just in preview, so a
            // recording actually comes out the size the user calibrated for.
            resetYAxisForNewSession()

            resetChartToDummyData()
            binding.calibratedWaveformView.chart.moveViewToX(0f)
            startAudioMonitor()
            taalRecorder?.start()

        } catch (e: Exception) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording Error").setMessage(e.message)
                .setPositiveButton("OKAY") { dialog, _ -> dialog.dismiss() }.show()
            viewModel.setUiState(FullTimeOnRecordingUiState.IDLE)
        }
    }

    private fun startAudioMonitor() {
        val minBuf = AudioTrack.getMinBufferSize(
            44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            44100,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
            AudioTrack.MODE_STREAM
        ).apply { play() }
    }

    private fun stopAudioMonitor() {
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private fun stopRecording() {
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
        taalRecorder = null

        val filteredPath = viewModel.currentFilteredPath
        val rawPath = viewModel.currentRecordingPath
        val filterName = viewModel.currentFilter.value ?: "HEART"

        if (filteredPath.isNotEmpty()) {
            val bundle = Bundle().apply {
                putString("filePath", filteredPath)
                putString("rawFilePath", rawPath)
                putBoolean("isNewRecording", true)
                putString("filterName", filterName)
                // So the Player can undo this recording's actual pre-amp gain and show the
                // same true-acoustic-level trace the recorder showed live.
                putInt("preAmpDb", viewModel.preAmpDb.value ?: 5)
            }
            findNavController().navigate(R.id.action_fullTimeOnRecording_to_fullTimeOnPlayer, bundle)
        }
        // Deliberately NOT restarting preview here — this fragment is about to navigate away
        // to the Player. Preview restarts on its own the next time onResume() runs, which
        // happens the moment the user comes back (§4.5's "returning from the Player must land
        // back in PREVIEW cleanly").
    }

    /**
     * Starts (or restarts) the live preview stream — see the class doc for why this uses a
     * second [TaalRecorder] pointed at a throwaway cache file rather than any listen-only SDK
     * mode (checked directly against taal-core's API surface; none exists). No-ops safely if
     * a real recording is in progress or preview is already running.
     *
     * [showErrorOnFailure] is false for the silent, event-driven retries this triggers on its
     * own (device reconnect via [setupConnectionReceiver], or the natural
     * [PREVIEW_SESSION_SECONDS] restart below) — true only for the original user-visible
     * attempt from [onResume], so a missing device produces exactly one message, never a retry
     * storm (§4.1 of the spec this was built against).
     */
    private fun startPreview(showErrorOnFailure: Boolean = true) {
        if (_binding == null || !isAdded) return
        if (taalRecorder != null) return
        if (previewRecorder != null) return

        resetToIdle()
        bpmCalculator.reset()
        viewModel.setUiState(FullTimeOnRecordingUiState.PREVIEW)

        val filterName = viewModel.currentFilter.value ?: "HEART"

        try {
            previewRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(previewTempFile.absolutePath)
                // Deliberately NOT calling setFilteredAudioFilePath() — TaalRecorder.start()
                // only opens/writes that second file if a path was set (verified directly in
                // taal-core), so preview only ever produces the one throwaway raw file.
                setRecordingTime(PREVIEW_SESSION_SECONDS)
                setPlayback(false)
                setPreAmplification(viewModel.preAmpDb.value ?: 5)

                val low = viewModel.customLowCut
                val high = viewModel.customHighCut
                if (filterName == "CUSTOM" && low != null && high != null && low > 0f && high > low) {
                    setCustomBandpass(low.toDouble(), high.toDouble())
                } else {
                    // Falls back to HEART for an unrecognized or not-yet-valid custom filter
                    // rather than popping the real recording flow's validation dialog — that
                    // dialog is for a user-initiated Record press, not an automatic screen-open
                    // side effect (§4.1: preview must never surprise the user with a popup).
                    setPreFilter(try { PreFilter.valueOf(filterName) } catch (_: Exception) { PreFilter.HEART })
                }

                onInfoListener = buildPreviewInfoListener()
            }

            startAudioMonitor()
            previewRecorder?.start()
        } catch (e: Exception) {
            previewRecorder = null
            if (showErrorOnFailure) {
                Toast.makeText(requireContext(), e.message ?: "TAAL device not connected", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Stops the preview stream (if any) and deletes its temp file — never surfaced anywhere else. */
    private fun stopPreview() {
        val recorder = previewRecorder ?: return
        previewStoppedIntentionally = true
        try { recorder.stop() } catch (_: Exception) {}
        previewRecorder = null
        stopAudioMonitor()
        try { previewTempFile.delete() } catch (_: Exception) {}
    }

    /**
     * Separate from the real recording's listener so a preview session can never accidentally
     * flip [viewModel]'s state to RECORDING or trigger real-recording-only side effects (file
     * cleanup on disconnect, the "silent recording" popup, timer updates) — see each override.
     */
    private fun buildPreviewInfoListener(): TaalRecorder.OnInfoListener {
        return object : TaalRecorder.OnInfoListener {
            override fun onStateChange(state: RecorderState) {
                activity?.runOnUiThread {
                    if (state != RecorderState.STOPPED) return@runOnUiThread // ignore RECORDING/INITIAL — not UI-visible for preview
                    if (previewStoppedIntentionally) {
                        previewStoppedIntentionally = false
                        return@runOnUiThread
                    }
                    // Reached PREVIEW_SESSION_SECONDS on its own — device is presumably still
                    // connected, so restart silently. Not a failure, nothing to show.
                    previewRecorder = null
                    if (isAdded && _binding != null && viewModel.uiState.value == FullTimeOnRecordingUiState.PREVIEW) {
                        startPreview(showErrorOnFailure = false)
                    }
                }
            }

            override fun onRawProgressUpdate(data: FloatArray) {}

            override fun onDeviceDisconnected() {
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        previewRecorder = null
                        stopAudioMonitor()
                        try { previewTempFile.delete() } catch (_: Exception) {}
                        Toast.makeText(
                            requireContext(),
                            "Device disconnected. Please connect the device.",
                            Toast.LENGTH_LONG
                        ).show()
                        // State stays PREVIEW — setupConnectionReceiver's onTaalConnect() retries
                        // once the device is replugged; no timer-based retry loop.
                    }
                }
            }

            // No onSilentRecordingDetected override — preview restarts silently every
            // PREVIEW_SESSION_SECONDS by design (see onStateChange above), and the base
            // interface's default no-op is exactly right here: popping "Ready to Capture /
            // please discard and start a new one" every ~10 minutes during ordinary preview
            // would be nonsensical (that dialog is about a just-completed real recording).

            override fun onProgressUpdate(
                sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
            ) {
                // No graph during PREVIEW anymore (user request, 2026-08-21) — audio keeps
                // streaming (feedSpeaker + BPM) so the mute toggle and BPM readout still work,
                // but nothing is drawn; previewMessage stays up until RECORDING actually
                // starts. Timer also intentionally not updated — stays 00:00:00 in PREVIEW.
                feedSpeaker(data)

                val shouldCompute = bpmCalculator.addSamples(data)
                if (shouldCompute) {
                    bpmScope.launch {
                        val bpm = bpmCalculator.computeBpm()
                        if (bpm > 0) {
                            withContext(Dispatchers.Main) {
                                if (isAdded && _binding != null) {
                                    viewModel.setBpm(bpm)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Clone of CalibratedRecordingFragment's V7-style updateWaveform(): X window derived from
     * the grid (or an active calibration override), min/max bucket downsampling, fixed Y-axis
     * (no warmup, no peak lock) — set once in setupWaveformChart() and never touched here.
     */
    private fun updateWaveform(data: FloatArray) {
        if (_binding == null || !isAdded) return
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart

        var bufferPeak = 0f
        for (sample in data) {
            val abs = Math.abs(sample)
            if (abs > bufferPeak) bufferPeak = abs
        }

        val bufferStartSample = totalSamplesProcessed
        val newEntries = CalibratedWaveformView.downsampleMinMax(data, sessionBucketSize) { j ->
            (bufferStartSample + j).toFloat() / INPUT_SAMPLE_RATE
        }
        waveformEntries.addAll(newEntries)
        totalSamplesProcessed += data.size

        val latestX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
        val windowSeconds = currentWindowSeconds

        val currentPage = (latestX / windowSeconds).toInt()
        val currentViewX = currentPage * windowSeconds

        // Bounds memory to ~2 windows' worth of points indefinitely — this is what keeps an
        // open-ended live preview session from accumulating points forever (§4.5's "ring
        // buffer sized to the visible window"); it already applied equally to recording, which
        // is naturally bounded by its own duration cap. Formula factored out to
        // CalibratedWaveformView.ringTrimMinX() (additive-only there) so it's unit-testable.
        val minXToKeep = CalibratedWaveformView.ringTrimMinX(latestX, windowSeconds)
        if (minXToKeep > 0) {
            val iterator = waveformEntries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().x < minXToKeep) iterator.remove() else break
            }
        }

        // Ported verbatim from production RecordingFragment (user request — see peakAmplitude's
        // field doc): two-phase warmup/lock. Phase 1 (first WARMUP_MS of this session):
        // accumulate the true peak, axis stays ±1.0. Phase 2: lock once and never touch the
        // axis again until the next session's reset (resetToIdle() / startRecording()). Applies
        // identically whether this buffer came from PREVIEW or RECORDING — both share this same
        // updateWaveform() pipeline and both get their own fresh warmup at session start.
        val now = System.currentTimeMillis()
        if (!warmupDone) {
            if (bufferPeak > warmupPeak) warmupPeak = bufferPeak
            if (lastPeakUpdateTime == 0L) lastPeakUpdateTime = now

            if (now - lastPeakUpdateTime >= WARMUP_MS) {
                warmupDone = true
                peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
                chart.axisLeft.axisMinimum = -peakAmplitude
                chart.axisLeft.axisMaximum = peakAmplitude
            }
        }

        val snapshot = ArrayList(waveformEntries)
        val ds = waveformDataSet

        if (ds == null || chart.data == null) {
            waveformDataSet = LineDataSet(snapshot, "Waveform").apply {
                color = ContextCompat.getColor(requireContext(), R.color.waveform_blue)
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = TRACE_LINE_WIDTH_DP
                mode = LineDataSet.Mode.LINEAR
                setDrawHighlightIndicators(false)
            }
            chart.data = LineData(waveformDataSet)
        } else {
            ds.values = snapshot
            chart.data?.notifyDataChanged()
        }

        chart.notifyDataSetChanged()

        chart.setVisibleXRangeMaximum(windowSeconds)
        chart.setVisibleXRangeMinimum(windowSeconds)
        chart.moveViewToX(currentViewX)
        chart.invalidate()
    }

    override fun onResume() {
        super.onResume()

        checkDeviceConnectionStatus()
        // Muted by default on every screen entry — deliberately never persisted (§4.3): an app
        // that starts making noise on its own is a worse failure than one extra tap.
        isSpeakerMuted = true
        updateSpeakerToggleIcon()

        // Re-read the calibration override in case the Player's Apply/Reset changed it since
        // this fragment was created — MUST happen before resetToIdle()/startPreview() below,
        // since resetYAxisForNewSession() (called from both) reads calibrationOverride to
        // decide the Y-axis for the session that's about to start. Getting this order wrong is
        // exactly how a just-Applied Peak Size silently failed to show up back here.
        calibrationOverride = GraphCalibration.getOverride(requireContext())

        if (taalRecorder == null) {
            resetToIdle()
            // Always-on preview (§4.1) — entered automatically the moment this screen becomes
            // visible, including the very first time and every return trip from the Player.
            // No-ops safely if already running; shows one message and waits for a reconnect
            // event if no device is attached (see startPreview's doc).
            startPreview()
        }
        viewModel.setPreAmp(5)
        binding.ampSlider.value = 5f
        binding.ampLabel.text = "5 dB"

        // Re-fires onVisibleSecondsChanged, which reads the refreshed override for X.
        binding.calibratedWaveformView.recomputeVisibleSeconds()
        binding.calibratedWaveformView.chart.invalidate()

        updateCalibrationCaption()
    }

    override fun onPause() {
        super.onPause()
        // Never leave the preview USB stream or speaker AudioTrack running in the background
        // (§4.5) — battery, device heat, not holding the stethoscope handle open against other
        // apps. Safe no-op if a real recording is in progress (stopPreview only touches
        // previewRecorder, never taalRecorder) or preview wasn't running.
        stopPreview()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        connectionReceiver?.unregister(requireContext())
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        bpmScope.cancel()
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
        // Defensive — onPause() already stops preview in the ordinary lifecycle, but cover the
        // case of this fragment being destroyed without an intervening onPause.
        try { previewRecorder?.stop() } catch (_: Exception) {}
        previewRecorder = null
        if (::previewTempFile.isInitialized) {
            try { previewTempFile.delete() } catch (_: Exception) {}
        }
    }
}
```

### 9.3 `ui/fulltimeon/FullTimeOnPlayerFragment.kt`

```kotlin
package com.musediagnostics.taal.app.ui.fulltimeon

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentFulltimeonPlayerBinding
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedPaperSpeed
import com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
import com.musediagnostics.taal.app.ecg.calibrated.DpiCalibration
import com.musediagnostics.taal.app.ecg.calibrated.GraphCalibration
import com.musediagnostics.taal.app.ui.player.PlayerSaveDiscardDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Clone of [com.musediagnostics.taal.app.ui.calibrated.CalibratedPlayerFragment] under a new
 * screen name (user request, 2026-08-20) — behavior is currently identical to that fragment,
 * just in its own package/nav destinations so it can be changed independently without touching
 * the Calibrated screens. See docs/notes/CALIBRATED_SCREENS_HANDOFF.md for the full history of
 * everything this was copied from (fixed-scale axis, Fix D zoom re-bucketing, pinch/Apply/Reset
 * graph calibration).
 */
class FullTimeOnPlayerFragment : Fragment() {

    private var _binding: FragmentFulltimeonPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    private var currentWindowSeconds = 4f

    // Smoothed camera-follow position during playback — see onPlaybackProgress. Reset to 0
    // wherever the chart snaps back to its start-centered framing.
    private var displayedPlaybackTime = 0f

    // Kept in memory for zoom-driven re-bucketing (rebucketForCurrentZoom). Never re-read from
    // disk after the initial load.
    private var decodedSamples: FloatArray? = null
    private var decodedSampleRate: Float = INPUT_SAMPLE_RATE
    private var waveformDataSet: LineDataSet? = null // persistent ref, mutated in place on re-bucket
    private var lastAppliedBucketSize = -1
    private var rebucketJob: Job? = null

    // Full length of the loaded file — the max-zoom-out cap (below) uses this so pinching all
    // the way out can "squeeze" to see the entire recording at once, not just the default
    // window (user request: zoom was one-directional — could only go bigger/narrower, never
    // smaller/wider than the default).
    private var loadedDurationSecs = 0

    // Per-device calibration override (set by pinching the graph, then pressing applyButton) —
    // null means "use the grid-derived default window." Read once at setup, kept in sync by the
    // Apply/Reset handlers. X (visibleSeconds) only now — Y is a fixed constant matching
    // production PlayerFragment exactly (see FIXED_Y_FULL_SCALE), not read from here anymore
    // (user request: match production's actual sizing behavior).
    private var calibrationOverride: GraphCalibration.Override? = null

    // True once the user has actually pinched/panned since this screen loaded (or since the
    // last Apply/Reset). There's no separate "Done" button — Apply is the only action — so
    // pressing it without having touched the graph at all must be a no-op (user request):
    // nothing to save, nothing changed. Set in onChartGestureEnd, cleared after a real Apply
    // and after Reset.
    private var hasUserAdjustedView = false

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val TARGET_POINT_BUDGET = 3000 // same total-point budget as production's maxPoints
        // Ported verbatim from production PlayerFragment (user request, 2026-08-21) — a plain
        // fixed constant, never adaptive, no per-file peak scan (confirmed directly against
        // PlayerFragment.kt: it hardcodes ±0.5 at setup and never touches axisMinimum/Maximum
        // again). Must stay identical to FullTimeOnRecordingFragment's own locked value once its
        // warmup completes only coincidentally — the two screens use different mechanisms now
        // (Recorder: warmup/lock; Player: this constant), exactly mirroring production's own
        // Recording/Player split.
        private const val FIXED_Y_FULL_SCALE = 0.5f

        // Default visible time window in seconds — copied verbatim from production
        // PlayerFragment's own hardcoded setVisibleXRangeMaximum(4f) (user request,
        // 2026-08-21: match production's speed/shape/working exactly). Used only when no
        // calibration override is saved; a saved Time Zoom override (pinch + Apply) still
        // takes priority, applies live to the Recorder too, and Reset returns here.
        private const val DEFAULT_WINDOW_SECONDS = 4f

        // Camera-follow smoothing. Fraction of the remaining gap to the real playback position
        // closed per progress callback — lower = gentler/slower-feeling follow, 1.0 = instant
        // snap.
        private const val FOLLOW_SMOOTHING = 0.15f
        // Trace stroke width in dp.
        private const val TRACE_LINE_WIDTH_DP = 3.0f

        // Floor used by forceVisibleSeconds() to relax the max-zoom-in bound back to
        // effectively unlimited after forcing an exact Time Zoom width — see that function.
        private const val MIN_VISIBLE_SECONDS = 0.3f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFulltimeonPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val isNewRecording = arguments?.getBoolean("isNewRecording", false) ?: false
        val filterName = arguments?.getString("filterName") ?: "HEART"
        // The dB the recorder actually used for this file, if known (0 = not passed / unknown,
        // meaning no compensation is applied) — see loadFullWaveform's doc for why this exists.
        val recordedPreAmpDb = arguments?.getInt("preAmpDb", 0) ?: 0

        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()
        setupAmpSlider()
        setupGraphCalibrationPanel()
        updateCalibrationCaption()

        if (filePath.isNotEmpty()) {
            // Capture the scale on the main thread before loadFullWaveform's IO coroutine
            // reads it — setupWaveformChart() above already applied paper speed + DPI
            // correction synchronously, so this snapshot is final for the rest of this load.
            val scale = binding.calibratedWaveformView.paperView.currentScale()
            loadFullWaveform(filePath, filterName, scale.paperSpeed.mmPerSecond, scale.pxPerMmX, recordedPreAmpDb)
            setupPlayer(filePath, filterName)
        }

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.eqButton.setOnClickListener {
            val bundle = Bundle().apply { putString("filePath", filePath) }
            findNavController().navigate(R.id.action_fullTimeOnPlayer_to_equalizer, bundle)
        }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }

        // Apply saves whatever pinch-zoom state the graph is currently showing as this device's
        // default and goes straight back to the Recorder to see it applied live. There's no
        // separate "Done" button, so if the user never touched the graph at all, Apply must not
        // silently (re-)save the same default as if something had changed (user request) —
        // it still navigates back, just skips the save.
        binding.applyButton.setOnClickListener {
            if (hasUserAdjustedView) {
                val chart = binding.calibratedWaveformView.chart
                val effectiveVisibleSeconds = chart.highestVisibleX - chart.lowestVisibleX
                val effectiveYFullScale = currentEffectiveYFullScale()
                if (effectiveVisibleSeconds > 0f && effectiveYFullScale > 0f) {
                    GraphCalibration.saveOverride(requireContext(), effectiveVisibleSeconds, effectiveYFullScale)
                    calibrationOverride = GraphCalibration.Override(effectiveVisibleSeconds, effectiveYFullScale)
                }
                hasUserAdjustedView = false
            }
            findNavController().navigateUp()
        }

        binding.discardButton.setOnClickListener {
            if (isNewRecording) {
                showDiscardConfirmation(filePath)
            } else {
                showSaveDiscardDialog(filePath)
            }
        }
    }

    private fun setupAmpSlider() {
        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            binding.ampLabel.text = "$db dB"
            player?.setPreAmplification(db.toFloat())
        }
    }

    private fun setupWaveformChart() {
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart

        calibrationOverride = GraphCalibration.getOverride(requireContext())

        // Fixed ±0.5 by default, exactly like production PlayerFragment's own setup — never
        // adaptive, no per-file peak scan. A saved calibration (pinch + Apply) overrides that
        // default and applies here and on the Recorder both (user request, 2026-08-21) — this
        // read was accidentally dropped when FIXED_Y_FULL_SCALE was ported in; restored.
        val effectiveYFullScale = calibrationOverride?.yFullScale ?: FIXED_Y_FULL_SCALE
        chart.axisLeft.axisMinimum = -effectiveYFullScale
        chart.axisLeft.axisMaximum = effectiveYFullScale

        // 50mm/s — see FullTimeOnRecordingFragment's copy of this comment for the full
        // reasoning (reverted after two failed speed guesses; DEFAULT_WINDOW_SECONDS below is
        // the actual fix for "too fast"). Must match FullTimeOnRecordingFragment exactly, so a
        // recording looks the same live as it does in review.
        waveformView.paperView.setPaperSpeed(CalibratedPaperSpeed.SPEED_50)

        DpiCalibration.applyTo(waveformView.paperView, requireContext())

        // User can pan/zoom to inspect the trace with two fingers, in both directions:
        // horizontal pinch for time (Time Zoom), vertical pinch for peak height (Peak Size).
        // MPAndroidChart scales Y via its own touch-matrix (viewPortHandler.scaleY), a separate
        // mechanism from the fixed axis bounds — currentEffectiveYFullScale() folds the two
        // together (divide by scaleY) so Apply always reads the true effective state regardless
        // of how the user got there.
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleXEnabled(true)
        chart.setScaleYEnabled(true)

        // Re-bucket for the new zoom/pan level once the gesture settles. Debounced by
        // construction: onChartGestureEnd fires once per discrete gesture, not per frame, so
        // this never runs mid-pinch and can't cause scroll stutter.
        chart.setOnChartGestureListener(object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {
                hasUserAdjustedView = true
                rebucketForCurrentZoom()
            }
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) {}
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {}
        })

        waveformView.onVisibleSecondsChanged = { _ ->
            // The actual "too fast" fix (user request, 2026-08-21): default to production
            // PlayerFragment's own fixed DEFAULT_WINDOW_SECONDS (4s) instead of the grid's
            // physically-derived `seconds`. A saved Time Zoom override still wins over both —
            // physical derivation still runs every time (e.g. on rotation), it's just
            // superseded either way. `seconds` is intentionally unused as a fallback now.
            currentWindowSeconds = calibrationOverride?.visibleSeconds ?: DEFAULT_WINDOW_SECONDS
            if (chart.data == null) {
                resetToDummyData()
            } else {
                // Real waveform already loaded (e.g. window changed on rotation) — reapply
                // the corrected max-zoom-out cap and re-center rather than silently drifting
                // stale. Deliberately NOT setVisibleXRangeMinimum — that would lock the range
                // to exactly `currentWindowSeconds` and disable pinch-zoom entirely. The ceiling
                // itself is never smaller than the whole file (see renderWaveformEntries) so
                // squeezing all the way out to see the full recording always stays possible.
                chart.setVisibleXRangeMaximum(maxOf(currentWindowSeconds, loadedDurationSecs.toFloat()))
                chart.centerViewTo(currentWindowSeconds / 2f, 0f, YAxis.AxisDependency.LEFT)
                chart.invalidate()
            }
        }
        waveformView.recomputeVisibleSeconds()
        if (chart.data == null) resetToDummyData()
    }

    private fun resetToDummyData() {
        if (_binding == null) return
        val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(currentWindowSeconds, 0f)), "").apply {
            color = Color.TRANSPARENT
            setDrawCircles(false)
            setDrawValues(false)
        }
        binding.calibratedWaveformView.chart.data = LineData(dummyDataSet)
        binding.calibratedWaveformView.chart.invalidate()
    }

    private fun updateCalibrationCaption() {
        if (_binding == null) return
        val correction = DpiCalibration.getCorrection(requireContext())
        val status = if (correction.isCalibrated) "calibrated (${correction.source})" else "UNCALIBRATED"
        val speed = binding.calibratedWaveformView.paperView.currentScale().paperSpeed.mmPerSecond
        binding.calibrationCaption.text = "$speed mm/s · Y: relative amplitude (fixed) · DPI: $status"
    }

    /**
     * Graph calibration is pinch-only — this just wires the status readout and Reset. Applying
     * a calibration happens via [R.id.applyButton] in the bottom bar (see onViewCreated), which
     * reads whatever the pinch-tuned view currently shows.
     */
    private fun setupGraphCalibrationPanel() {
        updateCalibrationStatusText()
        binding.resetCalibrationButton.setOnClickListener {
            GraphCalibration.clearOverride(requireContext())
            calibrationOverride = null
            applyBuiltInDefaultScale()
            // Back to the untouched default — a bare Apply right after this must still no-op.
            hasUserAdjustedView = false
            updateCalibrationStatusText()
            Toast.makeText(requireContext(), "Reset to default", Toast.LENGTH_SHORT).show()
        }
    }

    /** Re-applies FIXED_Y_FULL_SCALE and the grid-derived default window, bypassing any override — used by Reset. */
    private fun applyBuiltInDefaultScale() {
        val waveformView = binding.calibratedWaveformView
        val chart = waveformView.chart
        // Clears any pinch-driven X/Y viewport zoom (scaleX/scaleY) before reapplying the
        // built-in defaults below — otherwise a prior vertical pinch would still be layered on
        // top of the reset axis bounds and Reset wouldn't actually look reset.
        chart.fitScreen()
        chart.axisLeft.axisMinimum = -FIXED_Y_FULL_SCALE
        chart.axisLeft.axisMaximum = FIXED_Y_FULL_SCALE
        // Axis bounds alone don't move the already-plotted trace without this.
        chart.notifyDataSetChanged()
        // Re-fires onVisibleSecondsChanged with calibrationOverride already cleared above, so
        // currentWindowSeconds lands back on the physical grid-derived value.
        waveformView.recomputeVisibleSeconds()
        // onVisibleSecondsChanged only reapplies setVisibleXRangeMaximum (a zoom-OUT cap, see
        // forceVisibleSeconds) — if the user had pinched/slid to a *wider* view than the
        // default before hitting Reset, that alone wouldn't visually snap back. Force it.
        forceVisibleSeconds(currentWindowSeconds)
        // forceVisibleSeconds's own setVisibleXRangeMaximum(seconds) call just narrowed the
        // zoom-out ceiling back down to the default window as a side effect of forcing the
        // view there — widen it back to the full file so squeezing out to see the whole
        // recording is still possible after Reset, not just before it.
        chart.setVisibleXRangeMaximum(maxOf(currentWindowSeconds, loadedDurationSecs.toFloat()))
        rebucketForCurrentZoom()
        chart.invalidate()
    }

    /**
     * The Y full-scale actually being shown right now, folding together the fixed axis bounds
     * (chart.axisLeft.axisMaximum) and any pinch-driven vertical zoom on top of them
     * (chart.viewPortHandler.scaleY) — mirrors how X already reads its true state via
     * chart.highestVisibleX/lowestVisibleX rather than raw axis bounds. Relies on the Y axis
     * always being centered at 0 (every centerViewTo(...) call in this fragment passes 0f for y).
     */
    private fun currentEffectiveYFullScale(): Float {
        val chart = binding.calibratedWaveformView.chart
        val scaleY = chart.viewPortHandler.scaleY.coerceAtLeast(0.01f)
        return chart.axisLeft.axisMaximum / scaleY
    }

    /**
     * Forces the chart to display exactly [seconds] of width right now, regardless of whether
     * that's narrower or wider than the current view. `setVisibleXRangeMaximum` alone only sets
     * a zoom-out ceiling — it can't widen an already-narrower view. Both bounds are pinned to
     * [seconds] momentarily (forcing scaleX to exactly the target in either direction), then the
     * lower bound is relaxed back to effectively unlimited so pinch-zoom-in still works after.
     */
    private fun forceVisibleSeconds(seconds: Float) {
        val chart = binding.calibratedWaveformView.chart
        chart.setVisibleXRangeMinimum(seconds)
        chart.setVisibleXRangeMaximum(seconds)
        chart.centerViewTo(seconds / 2f, 0f, YAxis.AxisDependency.LEFT)
        chart.setVisibleXRangeMinimum(MIN_VISIBLE_SECONDS * 0.1f)
        chart.invalidate()
    }

    private fun updateCalibrationStatusText() {
        if (_binding == null) return
        binding.graphCalibrationStatus.text = if (calibrationOverride != null) {
            "Calibrated on this device"
        } else {
            "Not calibrated on this device — using default"
        }
    }

    /**
     * Clone of CalibratedPlayerFragment.loadFullWaveform(). Same WAV-header sample-rate parsing
     * (bytes 24-27, little-endian, fallback 44100), min/max-bucketed downsampling.
     *
     * [recordedPreAmpDb] undoes the same gain the recorder applied when this file was made, so a
     * recording looks the same size in the Player as it did live. 0 means unknown/not passed —
     * no compensation applied. Only works for files that arrived with that bundle arg (i.e.
     * reviewing a just-recorded file) — a recording reopened later from the library still won't
     * self-correct.
     */
    private fun loadFullWaveform(
        filePath: String, filterName: String, paperSpeedMmPerSecond: Float, pxPerMmX: Float, recordedPreAmpDb: Int
    ) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) return@launch
            val bytes = file.readBytes()

            val fileSampleRate: Float = if (bytes.size >= 28) {
                val rate = ((bytes[24].toInt() and 0xff) or
                        ((bytes[25].toInt() and 0xff) shl 8) or
                        ((bytes[26].toInt() and 0xff) shl 16) or
                        ((bytes[27].toInt() and 0xff) shl 24))
                if (rate > 0) rate.toFloat() else INPUT_SAMPLE_RATE
            } else INPUT_SAMPLE_RATE

            val dataSize = bytes.size - 44
            val totalSamples = dataSize / 2
            val durationSecs = (totalSamples / fileSampleRate).toInt()

            // Decode all samples first (need them in a FloatArray for bucketed min/max).
            val samples = FloatArray(totalSamples)
            var i = 0
            while (i < totalSamples) {
                val bytePos = 44 + i * 2
                if (bytePos + 1 >= bytes.size) break
                val low = bytes[bytePos].toInt() and 0xFF
                val high = bytes[bytePos + 1].toInt() shl 8
                samples[i] = (high or low).toShort().toFloat() / 32768f
                i++
            }

            // Undo the recorder's pre-amp gain, same formula FullTimeOnRecordingFragment uses
            // live — makes this file render at the same size it showed on the recording screen,
            // regardless of what dB was used.
            if (recordedPreAmpDb > 0) {
                val preAmpGain = Math.pow(10.0, recordedPreAmpDb / 20.0).toFloat()
                if (preAmpGain > 1.001f) {
                    for (j in samples.indices) samples[j] = samples[j] / preAmpGain
                }
            }

            // Derive so ~one min/max pair lands per horizontal pixel at the current paper
            // speed. Apply TARGET_POINT_BUDGET as a ceiling only: enlarge the bucket if the
            // derived value would produce more than the budget on a long file, but never
            // shrink below it.
            val derivedBucket = CalibratedWaveformView.deriveBucketSize(fileSampleRate, paperSpeedMmPerSecond, pxPerMmX)
            val budgetCeilingBucket = maxOf(1, totalSamples / (TARGET_POINT_BUDGET / 2))
            val bucketSize = if (derivedBucket > 0) maxOf(derivedBucket, budgetCeilingBucket) else budgetCeilingBucket
            val entries = CalibratedWaveformView.downsampleMinMax(samples, bucketSize) { idx ->
                idx.toFloat() / fileSampleRate
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                // Keep the decoded file in memory for zoom-driven re-bucketing, and remember
                // the bucket size just used so the first gesture-end after load doesn't
                // redundantly re-derive an unchanged value.
                decodedSamples = samples
                decodedSampleRate = fileSampleRate
                lastAppliedBucketSize = bucketSize
                renderWaveformEntries(ArrayList(entries), durationSecs)
            }
        }
    }

    private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int) {
        binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
        loadedDurationSecs = durationSecs
        val dataSet = LineDataSet(entries, "Waveform").apply {
            color = Color.parseColor("#2D7DD2")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = TRACE_LINE_WIDTH_DP
            mode = LineDataSet.Mode.LINEAR
        }
        waveformDataSet = dataSet // persistent ref, mutated in place on re-bucket
        // currentWindowSeconds is kept in sync by onVisibleSecondsChanged (set up in
        // setupWaveformChart, called before this) — including the case where layout hadn't
        // happened yet when this loaded; that callback will re-apply the range once it does.
        // Y-axis is fixed — already set once in setupWaveformChart(), not touched here.
        val chart = binding.calibratedWaveformView.chart
        chart.data = LineData(dataSet)
        // Cap max zoom-out only — no Minimum lock, so pinch-zoom works (see setupWaveformChart).
        // The ceiling is never smaller than the whole file (user request: pinching out used to
        // stop at the default window — "large only" — with no way to squeeze further; now it
        // can go all the way out to the full recording).
        val maxVisibleSeconds = maxOf(currentWindowSeconds, loadedDurationSecs.toFloat())
        chart.setVisibleXRangeMaximum(maxVisibleSeconds)
        chart.centerViewTo(currentWindowSeconds / 2f, 0f, YAxis.AxisDependency.LEFT)
        chart.invalidate()
    }

    /**
     * Recomputes the min/max bucket from whatever X range is currently visible (post-zoom/pan)
     * and re-buckets the whole decoded file at that density, targeting ~one min/max pair per
     * horizontal pixel. Re-buckets the whole file (not just the visible slice) so panning
     * within an unchanged zoom level doesn't need to re-run this — only an actual zoom change
     * does, since [lastAppliedBucketSize] short-circuits a no-op. Runs off the main thread since
     * re-bucketing a long file is real work; only the dataset swap happens on Main.
     */
    private fun rebucketForCurrentZoom() {
        val samples = decodedSamples ?: return
        val ds = waveformDataSet ?: return
        val chart = binding.calibratedWaveformView.chart
        val plotWidthPx = chart.width.toFloat()
        if (plotWidthPx <= 0f) return

        val visibleSeconds = (chart.highestVisibleX - chart.lowestVisibleX).coerceAtLeast(0f)
        if (visibleSeconds <= 0f) return
        val visibleSampleCount = visibleSeconds * decodedSampleRate

        val derivedBucket = CalibratedWaveformView.deriveBucketSizeForVisibleRange(visibleSampleCount, plotWidthPx)
        if (derivedBucket <= 0) return
        // Same budget-ceiling pattern as the load-time bucket — enlarge to stay within
        // TARGET_POINT_BUDGET on a long file, never shrink below the derived value.
        val ceilingBucket = maxOf(1, samples.size / (TARGET_POINT_BUDGET / 2))
        val finalBucket = maxOf(derivedBucket, ceilingBucket)
        if (finalBucket == lastAppliedBucketSize) return
        lastAppliedBucketSize = finalBucket

        val sampleRate = decodedSampleRate
        rebucketJob?.cancel()
        rebucketJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val entries = CalibratedWaveformView.downsampleMinMax(samples, finalBucket) { idx ->
                idx.toFloat() / sampleRate
            }
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                // Mutate in place (same pattern as the recorder) rather than replacing
                // chart.data — a replace would reset the viewport and undo the zoom/pan the
                // user just performed.
                ds.values = ArrayList(entries)
                binding.calibratedWaveformView.chart.data?.notifyDataChanged()
                binding.calibratedWaveformView.chart.notifyDataSetChanged()
                binding.calibratedWaveformView.chart.invalidate()
            }
        }
    }

    private fun setupPlayer(filePath: String, filterName: String) {
        try {
            player = TaalPlayer(requireContext()).apply {
                setDataSource(filePath)
                val fileName = File(filePath).name
                if (!fileName.contains("_filtered") && !fileName.contains("_8k_downsampling")) {
                    val preFilter = try { PreFilter.valueOf(filterName) } catch (_: Exception) { PreFilter.HEART }
                    setPreFilter(preFilter)
                }
                onPlaybackProgress = { timestamp, _ ->
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            // Audio timer stays exact — only the camera follow is smoothed.
                            val totalSecs = timestamp.toInt()
                            binding.timerText.text = String.format("%02d:%02d", totalSecs / 60, totalSecs % 60)

                            // Ease the camera toward the real playback position instead of
                            // snapping to it every callback.
                            displayedPlaybackTime += (timestamp.toFloat() - displayedPlaybackTime) * FOLLOW_SMOOTHING

                            val chart = binding.calibratedWaveformView.chart
                            val halfRange = chart.visibleXRange / 2f
                            val centerX = if (displayedPlaybackTime < halfRange) halfRange else displayedPlaybackTime
                            chart.centerViewTo(centerX, 0f, YAxis.AxisDependency.LEFT)
                        }
                    }
                }
                onPlaybackComplete = {
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            isPlaying = false
                            binding.actionText.text = getString(R.string.play_recording)
                            binding.playButton.setImageResource(R.drawable.ic_play_circle)

                            displayedPlaybackTime = 0f
                            val chart = binding.calibratedWaveformView.chart
                            chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
                        }
                    }
                }
            }
        } catch (e: InvalidFileNameException) {
            Toast.makeText(requireContext(), "Cannot open recording", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayback(filePath: String) {
        if (isPlaying) {
            player?.stop()
            isPlaying = false
            binding.actionText.text = getString(R.string.play_recording)
            binding.playButton.setImageResource(R.drawable.ic_play_circle)

            displayedPlaybackTime = 0f
            val chart = binding.calibratedWaveformView.chart
            chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
        } else {
            try {
                displayedPlaybackTime = 0f
                val chart = binding.calibratedWaveformView.chart
                chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)

                player?.prepare()
                player?.start()
                isPlaying = true
                binding.actionText.text = getString(R.string.stop_recording)
                binding.playButton.setImageResource(R.drawable.ic_recording_stop)
            } catch (e: Exception) {
                isPlaying = false
                Toast.makeText(requireContext(), "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDiscardConfirmation(filePath: String) {
        val rawFilePath = arguments?.getString("rawFilePath") ?: ""
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard Recording")
            .setMessage("Are you sure you want to discard this recording? It will be permanently deleted.")
            .setPositiveButton("Discard") { _, _ ->
                try { File(filePath).delete() } catch (_: Exception) {}
                if (rawFilePath.isNotEmpty()) {
                    try { File(rawFilePath).delete() } catch (_: Exception) {}
                }
                findNavController().navigateUp()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSaveDiscardDialog(filePath: String) {
        PlayerSaveDiscardDialog { action ->
            when (action) {
                PlayerSaveDiscardDialog.Action.SAVE -> {
                    val bundle = Bundle().apply { putString("recordingFilePath", filePath) }
                    findNavController().navigate(R.id.action_fullTimeOnPlayer_to_addPatient, bundle)
                }

                PlayerSaveDiscardDialog.Action.DISCARD -> {
                    try { File(filePath).delete() } catch (_: Exception) {}
                    findNavController().navigateUp()
                }
            }
        }.show(parentFragmentManager, "save_discard")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            player?.onPlaybackProgress = null
            player?.onPlaybackComplete = null
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        _binding = null
    }
}
```

### 9.4 `res/layout/fragment_fulltimeon_recording.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#F8F9FA">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/topBar"
        android:layout_width="match_parent"
        android:layout_height="40dp"
        android:background="@color/white"
        android:elevation="2dp"
        android:paddingStart="@dimen/spacing_md"
        android:paddingEnd="@dimen/spacing_md"
        app:layout_constraintTop_toTopOf="parent">

        <ImageButton
            android:id="@+id/infoButton"
            android:layout_width="26dp"
            android:layout_height="26dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Placement info"
            android:scaleType="centerInside"
            android:src="@drawable/ic_info"
            app:tint="#128CB2"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <TextView
            android:id="@+id/screenTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="FullTimeOn Recorder"
            android:textColor="@color/text_primary"
            android:textSize="14sp"
            android:textStyle="bold"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <ImageButton
            android:id="@+id/deviceIcon"
            android:layout_width="28dp"
            android:layout_height="28dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:src="@drawable/icon_taal"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent"
            app:tint="#333333" />

    </androidx.constraintlayout.widget.ConstraintLayout>

    <TextView
        android:id="@+id/timerText"
        style="@style/TaalText.Timer"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:text="@string/timer_default"
        android:textSize="15sp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/topBar" />

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/filterContainer"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        app:cardBackgroundColor="@color/white"
        app:cardCornerRadius="24dp"
        app:cardElevation="4dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/timerText"
        app:strokeColor="#E0E0E0"
        app:strokeWidth="1dp">

        <LinearLayout
            android:id="@+id/filterRow"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:gravity="center"
            android:orientation="horizontal"
            android:padding="5dp">

            <ImageButton
                android:id="@+id/filterHeart"
                android:layout_width="34dp"
                android:layout_height="34dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/filter_heart"
                android:padding="7dp"
                android:scaleType="centerInside"
                android:src="@drawable/ic_heart"
                app:tint="@color/filter_icon_selector" />

            <View
                android:layout_width="1dp"
                android:layout_height="18dp"
                android:background="#EEEEEE" />

            <ImageButton
                android:id="@+id/filterLungs"
                android:layout_width="34dp"
                android:layout_height="34dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/filter_lungs"
                android:padding="7dp"
                android:scaleType="centerInside"
                android:src="@drawable/ic_lungs"
                app:tint="@color/filter_icon_selector" />

            <View
                android:layout_width="1dp"
                android:layout_height="18dp"
                android:background="#EEEEEE" />

            <ImageButton
                android:id="@+id/filterBowel"
                android:layout_width="34dp"
                android:layout_height="34dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/filter_bowel"
                android:padding="7dp"
                android:scaleType="centerInside"
                android:src="@drawable/ic_bowel"
                app:tint="@color/filter_icon_selector" />

            <View
                android:layout_width="1dp"
                android:layout_height="18dp"
                android:background="#EEEEEE" />

            <ImageButton
                android:id="@+id/filterPregnancy"
                android:layout_width="34dp"
                android:layout_height="34dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/filter_pregnancy"
                android:padding="7dp"
                android:scaleType="centerInside"
                android:src="@drawable/ic_pregnancy"
                app:tint="@color/filter_icon_selector" />

            <View
                android:layout_width="1dp"
                android:layout_height="18dp"
                android:background="#EEEEEE" />

            <ImageButton
                android:id="@+id/filterInfo"
                android:layout_width="34dp"
                android:layout_height="34dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/filter_full_body"
                android:padding="7dp"
                android:scaleType="centerInside"
                android:src="@drawable/ic_accessibility"
                app:tint="@color/filter_icon_selector" />

            <View
                android:layout_width="1dp"
                android:layout_height="18dp"
                android:background="#EEEEEE" />

            <ImageButton
                android:id="@+id/filterCustom"
                android:layout_width="34dp"
                android:layout_height="34dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="Custom frequency range"
                android:padding="7dp"
                android:scaleType="centerInside"
                android:src="@drawable/ic_custom_filter"
                app:tint="@color/filter_icon_selector" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <!-- Custom frequency range panel — shown only when custom filter is selected -->
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/customRangePanel"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="6dp"
        android:layout_marginEnd="16dp"
        android:visibility="gone"
        app:cardBackgroundColor="@color/white"
        app:cardCornerRadius="16dp"
        app:cardElevation="4dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/filterContainer"
        app:strokeColor="#E0E0E0"
        app:strokeWidth="1dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingStart="16dp"
            android:paddingTop="10dp"
            android:paddingEnd="16dp"
            android:paddingBottom="10dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginBottom="4dp"
                android:text="Custom Frequency Range"
                android:textColor="#128CB2"
                android:textSize="12sp"
                android:textStyle="bold" />

            <com.google.android.material.slider.RangeSlider
                android:id="@+id/customRangeSlider"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:valueFrom="0"
                android:valueTo="24000"
                app:haloColor="#1A128CB2"
                app:labelBehavior="gone"
                app:thumbColor="#128CB2"
                app:thumbRadius="8dp"
                app:trackColorActive="#128CB2"
                app:trackColorInactive="#C8E6F5"
                app:trackHeight="4dp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="0 Hz"
                    android:textColor="#999999"
                    android:textSize="10sp" />
                <View android:layout_width="0dp" android:layout_height="0dp" android:layout_weight="1" />
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="24000 Hz"
                    android:textColor="#999999"
                    android:textSize="10sp" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:orientation="horizontal">

                <com.google.android.material.textfield.TextInputLayout
                    android:id="@+id/lowCutInputLayout"
                    style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox.Dense"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginEnd="6dp"
                    android:layout_weight="1"
                    android:hint="Low Cut (Hz)">

                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/customLowCutInput"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:inputType="number"
                        android:maxLength="5" />
                </com.google.android.material.textfield.TextInputLayout>

                <com.google.android.material.textfield.TextInputLayout
                    android:id="@+id/highCutInputLayout"
                    style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox.Dense"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="6dp"
                    android:layout_weight="1"
                    android:hint="High Cut (Hz)">

                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/customHighCutInput"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:inputType="number"
                        android:maxLength="5" />
                </com.google.android.material.textfield.TextInputLayout>

            </LinearLayout>
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/ampSliderContainer"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="4dp"
        android:layout_marginEnd="16dp"
        app:cardBackgroundColor="@color/white"
        app:cardCornerRadius="12dp"
        app:cardElevation="4dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/customRangePanel"
        app:strokeColor="#E0E0E0"
        app:strokeWidth="1dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:paddingStart="10dp"
                android:paddingTop="0dp"
                android:paddingEnd="10dp"
                android:paddingBottom="0dp">

                <ImageView
                    android:layout_width="14dp"
                    android:layout_height="14dp"
                    android:contentDescription="Amplification"
                    android:src="@drawable/ic_volume_up"
                    app:tint="#128CB2" />

                <com.google.android.material.slider.Slider
                    android:id="@+id/ampSlider"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="6dp"
                    android:layout_marginEnd="6dp"
                    android:layout_weight="1"
                    android:stepSize="1"
                    android:value="5"
                    android:valueFrom="0"
                    android:valueTo="30"
                    app:haloColor="#1A128CB2"
                    app:labelBehavior="gone"
                    app:thumbColor="#128CB2"
                    app:thumbRadius="5dp"
                    app:trackColorActive="#128CB2"
                    app:trackColorInactive="#C8E6F5"
                    app:trackHeight="2dp" />

                <TextView
                    android:id="@+id/ampLabel"
                    android:layout_width="44dp"
                    android:layout_height="wrap_content"
                    android:gravity="end"
                    android:text="5 dB"
                    android:textColor="#128CB2"
                    android:textSize="12sp"
                    android:textStyle="bold" />

            </LinearLayout>
        </LinearLayout>

    </com.google.android.material.card.MaterialCardView>

    <TextView
        android:id="@+id/calibrationCaption"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="2dp"
        android:layout_marginEnd="16dp"
        android:gravity="center"
        android:text="25 mm/s · Y: relative amplitude · DPI: uncalibrated"
        android:textColor="#999999"
        android:textSize="9sp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/ampSliderContainer" />

    <com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
        android:id="@+id/calibratedWaveformView"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="2dp"
        android:layout_marginBottom="2dp"
        app:layout_constraintBottom_toTopOf="@id/bpmText"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/calibrationCaption" />

    <!-- Overlaid on the graph's own corner (declared after calibratedWaveformView so it draws
         on top) — does not participate in that view's own width/height constraints, so it
         cannot shrink the plot area. Muted by default (see Fragment's onResume). -->
    <ImageButton
        android:id="@+id/speakerToggle"
        android:layout_width="30dp"
        android:layout_height="30dp"
        android:layout_marginTop="4dp"
        android:layout_marginEnd="4dp"
        android:background="#CCFFFFFF"
        android:contentDescription="@string/speaker_muted_description"
        android:padding="4dp"
        android:src="@drawable/ic_speaker_muted"
        app:layout_constraintEnd_toEndOf="@id/calibratedWaveformView"
        app:layout_constraintTop_toTopOf="@id/calibratedWaveformView"
        app:tint="#128CB2" />

    <!-- Shown instead of the live trace while PREVIEW is running (audio/speaker still work —
         see FullTimeOnRecordingFragment's preview OnInfoListener); hidden once RECORDING
         starts, when the real graph takes over. Centered over the grid, same overlay pattern
         as speakerToggle above — doesn't touch the waveform view's own constraints. -->
    <TextView
        android:id="@+id/previewMessage"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="24dp"
        android:layout_marginEnd="24dp"
        android:background="#CCFFFFFF"
        android:padding="12dp"
        android:text="Listen for correct placement, then press Record to see the graph"
        android:textAlignment="center"
        android:textColor="#128CB2"
        android:textSize="13sp"
        android:textStyle="bold"
        app:layout_constraintBottom_toBottomOf="@id/calibratedWaveformView"
        app:layout_constraintEnd_toEndOf="@id/calibratedWaveformView"
        app:layout_constraintStart_toStartOf="@id/calibratedWaveformView"
        app:layout_constraintTop_toTopOf="@id/calibratedWaveformView" />

    <TextView
        android:id="@+id/bpmText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="2dp"
        android:text="-- BPM"
        android:textColor="@color/text_secondary"
        android:textSize="12sp"
        app:layout_constraintBottom_toTopOf="@id/actionText"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <TextView
        android:id="@+id/actionText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="2dp"
        android:text="@string/start_recording"
        android:textColor="@color/text_primary"
        android:textSize="12sp"
        app:layout_constraintBottom_toTopOf="@id/recordButton"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <ImageButton
        android:id="@+id/recordButton"
        android:layout_width="52dp"
        android:layout_height="52dp"
        android:layout_marginBottom="8dp"
        android:background="@drawable/bg_record_button"
        android:contentDescription="Record"
        android:elevation="8dp"
        android:padding="0dp"
        android:scaleType="fitCenter"
        android:src="@drawable/ic_recording_start1"
        app:layout_constraintBottom_toTopOf="@id/bottomBar"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:tint="@color/white" />

    <LinearLayout
        android:id="@+id/bottomBar"
        android:layout_width="match_parent"
        android:layout_height="36dp"
        android:gravity="center"
        android:orientation="horizontal"
        android:paddingStart="@dimen/spacing_lg"
        android:paddingEnd="@dimen/spacing_lg"
        android:paddingBottom="2dp"
        app:layout_constraintBottom_toBottomOf="parent">

        <LinearLayout
            android:id="@+id/preRecordingButtons"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:orientation="horizontal">

            <ImageButton
                android:id="@+id/folderButton"
                android:layout_width="0dp"
                android:layout_height="30dp"
                android:layout_marginEnd="@dimen/spacing_sm"
                android:layout_weight="1"
                android:background="@drawable/bg_bottom_action_button"
                android:contentDescription="Library"
                android:scaleType="centerInside"
                android:src="@drawable/ic_gallery"
                app:tint="@color/white" />

            <ImageButton
                android:id="@+id/settingsButton"
                android:layout_width="0dp"
                android:layout_height="30dp"
                android:layout_marginStart="@dimen/spacing_sm"
                android:layout_weight="1"
                android:background="@drawable/bg_bottom_action_button"
                android:contentDescription="Calibrate grid"
                android:scaleType="centerInside"
                android:src="@drawable/ic_settings"
                app:tint="@color/white" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/recordingButtons"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:visibility="gone">

            <ImageButton
                android:id="@+id/playPauseButton"
                android:layout_width="56dp"
                android:layout_height="56dp"
                android:background="@drawable/bg_record_button"
                android:contentDescription="Play"
                android:scaleType="centerInside"
                android:src="@drawable/ic_play" />
        </LinearLayout>
    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

### 9.5 `res/layout/fragment_fulltimeon_player.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#F8F9FA">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/topBar"
        android:layout_width="match_parent"
        android:layout_height="40dp"
        android:background="@color/white"
        android:elevation="2dp"
        android:paddingStart="@dimen/spacing_md"
        android:paddingEnd="@dimen/spacing_md"
        app:layout_constraintTop_toTopOf="parent">

        <ImageButton
            android:id="@+id/backButton"
            android:layout_width="26dp"
            android:layout_height="26dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Back"
            android:scaleType="centerInside"
            android:src="@drawable/ic_arrow_back"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <TextView
            android:id="@+id/screenTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="FullTimeOn Review"
            android:textColor="@color/text_primary"
            android:textSize="14sp"
            android:textStyle="bold"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <ImageButton
            android:id="@+id/eqButton"
            android:layout_width="28dp"
            android:layout_height="28dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Equalizer"
            android:src="@drawable/ic_equalizer"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

    </androidx.constraintlayout.widget.ConstraintLayout>

    <TextView
        android:id="@+id/timerText"
        style="@style/TaalText.Timer"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:text="@string/timer_default"
        android:textSize="15sp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/topBar" />

    <!-- Compact Amp Slider Card -->
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/ampSliderContainer"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="4dp"
        android:layout_marginEnd="16dp"
        app:cardBackgroundColor="@color/white"
        app:cardCornerRadius="10dp"
        app:cardElevation="2dp"
        app:strokeColor="#E0E0E0"
        app:strokeWidth="1dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/timerText">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:paddingStart="10dp"
                android:paddingTop="0dp"
                android:paddingEnd="10dp"
                android:paddingBottom="0dp">

                <ImageView
                    android:layout_width="14dp"
                    android:layout_height="14dp"
                    android:contentDescription="Amplification"
                    android:src="@drawable/ic_volume_up"
                    app:tint="#128CB2" />

                <com.google.android.material.slider.Slider
                    android:id="@+id/ampSlider"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="4dp"
                    android:layout_marginEnd="4dp"
                    android:layout_weight="1"
                    android:stepSize="1"
                    android:value="5"
                    android:valueFrom="0"
                    android:valueTo="30"
                    app:haloColor="#1A128CB2"
                    app:labelBehavior="gone"
                    app:thumbColor="#128CB2"
                    app:thumbRadius="5dp"
                    app:trackColorActive="#128CB2"
                    app:trackColorInactive="#C8E6F5"
                    app:trackHeight="2dp" />

                <TextView
                    android:id="@+id/ampLabel"
                    android:layout_width="40dp"
                    android:layout_height="wrap_content"
                    android:gravity="end"
                    android:text="5 dB"
                    android:textColor="#128CB2"
                    android:textSize="11sp"
                    android:textStyle="bold" />

            </LinearLayout>
        </LinearLayout>

    </com.google.android.material.card.MaterialCardView>

    <TextView
        android:id="@+id/calibrationCaption"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="2dp"
        android:layout_marginEnd="16dp"
        android:gravity="center"
        android:text="25 mm/s · Y: relative amplitude · DPI: uncalibrated"
        android:textColor="#999999"
        android:textSize="9sp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/ampSliderContainer" />

    <!-- Per-device graph calibration status — pinch the graph below to adjust; this row is
         just the current-state readout + a way back to the built-in default. -->
    <LinearLayout
        android:id="@+id/graphCalibrationRow"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="4dp"
        android:layout_marginEnd="16dp"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/calibrationCaption">

        <TextView
            android:id="@+id/graphCalibrationStatus"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Not calibrated on this device — using default"
            android:textColor="#999999"
            android:textSize="9sp" />

        <Button
            android:id="@+id/resetCalibrationButton"
            android:layout_width="wrap_content"
            android:layout_height="24dp"
            android:minWidth="0dp"
            android:minHeight="0dp"
            android:background="@drawable/bg_button_outlined"
            android:paddingHorizontal="10dp"
            android:paddingVertical="0dp"
            android:text="Reset"
            android:textAllCaps="false"
            android:textSize="10sp" />
    </LinearLayout>

    <com.musediagnostics.taal.app.ecg.calibrated.CalibratedWaveformView
        android:id="@+id/calibratedWaveformView"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="2dp"
        android:layout_marginBottom="2dp"
        app:layout_constraintBottom_toTopOf="@id/actionText"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/graphCalibrationRow" />

    <ProgressBar
        android:id="@+id/waveformLoadingIndicator"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:indeterminateTint="#128CB2"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="@id/calibratedWaveformView"
        app:layout_constraintEnd_toEndOf="@id/calibratedWaveformView"
        app:layout_constraintStart_toStartOf="@id/calibratedWaveformView"
        app:layout_constraintTop_toTopOf="@id/calibratedWaveformView" />

    <TextView
        android:id="@+id/actionText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="4dp"
        android:text="@string/play_recording"
        android:textColor="@color/text_primary"
        android:textSize="12sp"
        app:layout_constraintBottom_toTopOf="@id/playButton"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <ImageButton
        android:id="@+id/playButton"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:layout_marginBottom="10dp"
        android:background="@drawable/bg_record_button"
        android:contentDescription="Play"
        android:elevation="8dp"
        android:padding="0dp"
        android:scaleType="fitCenter"
        android:src="@drawable/ic_play_circle"
        app:layout_constraintBottom_toTopOf="@id/saveDiscardBar"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:tint="@color/white" />

    <LinearLayout
        android:id="@+id/saveDiscardBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginHorizontal="16dp"
        android:layout_marginBottom="12dp"
        android:orientation="horizontal"
        app:layout_constraintBottom_toBottomOf="parent">

        <Button
            android:id="@+id/discardButton"
            android:layout_width="0dp"
            android:layout_height="38dp"
            android:layout_marginEnd="8dp"
            android:layout_weight="1"
            android:background="@drawable/bg_button_outlined"
            android:text="Discard"
            android:textAllCaps="false"
            android:textSize="13sp"
            android:textStyle="bold" />

        <Button
            android:id="@+id/applyButton"
            android:layout_width="0dp"
            android:layout_height="38dp"
            android:layout_marginStart="8dp"
            android:layout_weight="1"
            android:background="@drawable/bg_button_teal"
            android:text="Apply"
            android:textAllCaps="false"
            android:textColor="@color/white"
            android:textSize="13sp"
            android:textStyle="bold" />
    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

### 9.6 New test file: `FullTimeOnRecordingTransitionsTest.kt`

```kotlin
package com.musediagnostics.taal.app.ui.fulltimeon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for [FullTimeOnRecordingTransitions] — the FullTimeOn Recorder's always-on
 * preview state machine (added alongside the live-preview + speaker-mute feature). No Android
 * runtime needed: [FullTimeOnRecordingUiState] is a plain enum and the transition table is a
 * pure lookup, mirroring what FullTimeOnRecordingFragment actually does (see that object's doc).
 */
class FullTimeOnRecordingTransitionsTest {

    @Test
    fun `IDLE to PREVIEW is legal - every onResume`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.IDLE, FullTimeOnRecordingUiState.PREVIEW))
    }

    @Test
    fun `PREVIEW to RECORDING is legal - pressing Record`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.PREVIEW, FullTimeOnRecordingUiState.RECORDING))
    }

    @Test
    fun `RECORDING to STOPPED is legal - stopRecording's synchronous TaalRecorder callback`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.RECORDING, FullTimeOnRecordingUiState.STOPPED))
    }

    @Test
    fun `STOPPED to IDLE is legal - the next resetToIdle()`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.STOPPED, FullTimeOnRecordingUiState.IDLE))
    }

    @Test
    fun `PREVIEW to IDLE is legal - startPreview's own internal resetToIdle() step`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.PREVIEW, FullTimeOnRecordingUiState.IDLE))
    }

    @Test
    fun `the required conceptual cycle IDLE to PREVIEW to RECORDING to PREVIEW is reachable via legal edges only`() {
        // The spec's shorthand cycle, expanded to the real hop through STOPPED — every step
        // below must individually be a legal edge; chaining them reproduces the observable
        // "record, stop, land back in a live preview" cycle end to end.
        val chain = listOf(
            FullTimeOnRecordingUiState.IDLE,
            FullTimeOnRecordingUiState.PREVIEW,
            FullTimeOnRecordingUiState.RECORDING,
            FullTimeOnRecordingUiState.STOPPED,
            FullTimeOnRecordingUiState.IDLE,
            FullTimeOnRecordingUiState.PREVIEW,
        )
        for (i in 0 until chain.size - 1) {
            assertTrue(
                "expected ${chain[i]} -> ${chain[i + 1]} to be legal",
                FullTimeOnRecordingTransitions.isLegal(chain[i], chain[i + 1])
            )
        }
    }

    @Test
    fun `cannot skip preview - IDLE straight to RECORDING is illegal`() {
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.IDLE, FullTimeOnRecordingUiState.RECORDING))
    }

    @Test
    fun `cannot skip the stop hop - RECORDING straight to IDLE is illegal`() {
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.RECORDING, FullTimeOnRecordingUiState.IDLE))
    }

    @Test
    fun `cannot skip the stop hop - RECORDING straight to PREVIEW is illegal`() {
        // The real code always passes through STOPPED first (stopRecording()'s synchronous
        // TaalRecorder callback) even though that UI is never rendered — see the class doc.
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.RECORDING, FullTimeOnRecordingUiState.PREVIEW))
    }

    @Test
    fun `STOPPED cannot go straight back to RECORDING or PREVIEW`() {
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.STOPPED, FullTimeOnRecordingUiState.RECORDING))
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.STOPPED, FullTimeOnRecordingUiState.PREVIEW))
    }

    @Test
    fun `a state never legally transitions to itself`() {
        for (state in FullTimeOnRecordingUiState.entries) {
            assertFalse("$state -> $state should not be a legal transition", FullTimeOnRecordingTransitions.isLegal(state, state))
        }
    }
}
```

### 9.7 `ringTrimMinX()` — the one additive function in the shared `CalibratedWaveformView.kt`


```kotlin
        /**
         * Pure, additive helper — new symbol, not called by any existing code path (Calibrated
         * screens keep their own unchanged inline copy of this formula; only
         * FullTimeOnRecordingFragment's live-preview pipeline calls this). Added so the
         * page-based ring-trim bound used by a scrolling live trace (preview or recording) is
         * unit-testable without an Android runtime.
         *
         * Returns the minimum X (seconds) an entry must have to survive the trim — entries
         * older than one full window before the current page are dropped, bounding memory to
         * ~2 windows' worth of points indefinitely, however long the live session runs.
         */
        fun ringTrimMinX(latestX: Float, windowSeconds: Float): Float {
            if (windowSeconds <= 0f) return 0f
            val currentPage = (latestX / windowSeconds).toInt()
            val minXToKeep = (currentPage - 1) * windowSeconds
            return if (minXToKeep > 0f) minXToKeep else 0f
        }
```

---
