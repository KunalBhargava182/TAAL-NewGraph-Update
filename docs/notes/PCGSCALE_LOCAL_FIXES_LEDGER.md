# PcgScale local fixes — lost in the `update1` overwrite (2026-08-28)

## Why this file exists

The `PcgScale_Handoff` bundle (2026-08-25) was integrated, then this session made four
additional fixes directly on top of the bundle's files, tested and approved on-device. On
2026-08-28 a follow-up bundle (` PcgScale_Handoff_update1`) was placed by **overwriting** the
same files with an externally-authored "Rev 2" (sample-rate adoption + on-screen diagnostics
caption) that was written blind against the original 2026-08-25 snapshot — it has no knowledge
of the four fixes below, so overwriting silently reverted all of them.

**Decision made 2026-08-28:** keep the `update1` bundle files exactly as shipped (don't
re-apply these fixes now). This file documents each fix in enough detail to re-apply it later
without re-deriving it — code, exact location, and the reasoning/bug it addresses.

One compile-breaking knock-on effect was fixed as a minimum viable repair (see **Fix 4**,
which also explains the one change made to `PcgScaleReviewFragment.kt` — a file `update1`
never touched but which broke anyway because it depended on something `update1` reverted).

**Current repo state after this note was written:** `./gradlew :app:assembleDebug` succeeds.
`./gradlew :app:testDebugUnitTest` has exactly **one known, expected failure**:
`PcgAmplitudeScaleTest > one single-sample spike cannot peg the scale - RMS dilutes it` — this
is Fix 1 below being absent, not a new bug.

---

## Fix 1 — RMS outlier rejection (thud in the first ~10s can still skew the axis)

**File:** `app/src/main/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgAmplitudeScale.kt`
**Status:** reverted by `update1`. The shipped file uses the original raw-max-per-window logic.

### The bug

`PcgAmplitudeScale`'s Y-axis scale is `mean of each closed 5s window's peak hop-RMS`. The
original algorithm took a window's peak as the **outright maximum** hop RMS within that window.
For a single-hop transient (a stethoscope thud) landing in the recording's **first** window,
there's no prior window to average it down against — with only 1–2 windows closed, the
"average across windows" dilution the class's own doc comment promises doesn't actually apply
yet. Measured: a 0.05-amplitude quiet signal with one 1.0-amplitude sample spike produced
`spiked=0.1509` vs `clean=0.0589` — a 2.56× overshoot, failing the bundle's own test
(`PcgAmplitudeScaleTest.kt:72`, `spiked < clean * 2f`), which is still present unmodified in
`update1` and fails again now for the same reason.

### The fix

Change a window's peak from "the max hop RMS" to "the **3rd-largest** hop RMS." A real
heart-sound window contains many loud hops (S1/S2 recur every beat, ~5–8 times per 5s window),
so the 3rd-largest hop is still a genuine beat maximum. A one-off transient only elevates 1–2
hops, so it can never reach 3rd place and cannot set the window's peak — even on the very
first, history-free window, which is exactly the case cross-window averaging can't reach.

### Code

Add a constant:
```kotlin
// A window's peak is its Kth-largest hop RMS, not its max — see class doc stage 2.
const val OUTLIER_REJECTION_K = 3
```

Replace the single running-max field with a small top-K tracker:
```kotlin
// BEFORE
private var windowPeakRms = 0f
private var windowSampleCount = 0

// AFTER
// Current 5s window state: the K largest hop RMS values seen so far this window (unsorted;
// its min is the running Kth-largest, which is all closeWindow() needs).
private val topHopRms = ArrayList<Float>(OUTLIER_REJECTION_K + 1)
private var windowSampleCount = 0
```

Replace `closeHop()` / `closeWindow()`:
```kotlin
// BEFORE
private fun closeHop() {
    val rms = sqrt(hopSumSquares / hopCount).toFloat()
    if (rms > windowPeakRms) windowPeakRms = rms
    hopSumSquares = 0.0
    hopCount = 0
}

private fun closeWindow() {
    peakRmsSum += windowPeakRms.toDouble()
    closedWindowCount++
    windowPeakRms = 0f
    windowSampleCount = 0
}

// AFTER
private fun closeHop() {
    val rms = sqrt(hopSumSquares / hopCount).toFloat()
    topHopRms.add(rms)
    if (topHopRms.size > OUTLIER_REJECTION_K) {
        topHopRms.removeAt(topHopRms.indices.minByOrNull { topHopRms[it] }!!)
    }
    hopSumSquares = 0.0
    hopCount = 0
}

private fun closeWindow() {
    peakRmsSum += (topHopRms.minOrNull() ?: 0f).toDouble()
    closedWindowCount++
    topHopRms.clear()
    windowSampleCount = 0
}
```

Update `reset()`:
```kotlin
// BEFORE
hopSumSquares = 0.0
hopCount = 0
windowPeakRms = 0f

// AFTER
hopSumSquares = 0.0
hopCount = 0
topHopRms.clear()
```

**Compatibility note for re-applying against the `update1` version:** the shipped file also
adds `meanPeakRms()` and `isClampedAtMin()` (for the Rev 2 diagnostics caption) — neither reads
`windowPeakRms` directly, both go through `peakRmsSum`/`closedWindowCount`, so this fix applies
cleanly on top without touching those two methods.

---

## Fix 2 — PcgScale player's Save button must go through SaveRecordingFragment

**File:** `app/src/main/java/com/musediagnostics/taal/app/ui/pcgscale/PcgScalePlayerFragment.kt`
**Status:** reverted by `update1`. The shipped file always routes Save → Add Patient.
**Dependency (still intact, not reverted):** `nav_graph.xml` already has
`action_pcgScalePlayer_to_saveRecording → saveRecordingFragment` — only the Kotlin call site
needs restoring.

### The bug

The bundle's Save button unconditionally navigates to `AddPatientFragment`. Only
`SaveRecordingFragment` (`app/.../ui/recording/SaveRecordingFragment.kt`) actually renames the
recorder's temp `_filtered.wav`/`_raw.wav` into `filesDir/saved/` — the directory
`SavedRecordingsFragment` (opened via the recorder's folder icon) lists. Without this fix,
recordings made through the PcgScale recorder never show up in Saved Recordings.

### The fix

Mirror production `PlayerFragment.kt`'s exact branch: a new recording saves through
`SaveRecordingFragment` first; an existing (already-saved) recording goes straight to the
save/discard dialog → Add Patient (this second branch is currently unreachable in practice,
kept only for parity since the review screen, Fix in the "still intact" section below, is now
the only path that opens an already-saved file).

### Code

```kotlin
// BEFORE
// Production meaning restored (the Calibrated fork had repurposed this slot for graph
// calibration, which this screen doesn't have): Save → Add Patient.
binding.saveButton.setOnClickListener {
    val bundle = Bundle().apply { putString("recordingFilePath", filePath) }
    findNavController().navigate(R.id.action_pcgScalePlayer_to_addPatient, bundle)
}

// AFTER
// Production meaning restored (the Calibrated fork had repurposed this slot for graph
// calibration, which this screen doesn't have): same branch as PlayerFragment's
// saveButton — a new recording goes through SaveRecordingFragment first (the step that
// actually renames the temp WAVs into filesDir/saved/, which is what SavedRecordingsFragment
// lists), an existing recording goes straight to the save/discard dialog → Add Patient.
binding.saveButton.setOnClickListener {
    if (isNewRecording) {
        val rawFilePath = arguments?.getString("rawFilePath") ?: ""
        val bundle = Bundle().apply {
            putString("filePath", filePath)
            putString("rawFilePath", rawFilePath)
            putString("filterName", filterName)
            putInt("popUpToDestination", R.id.pcgScaleRecordingFragment)
        }
        findNavController().navigate(R.id.action_pcgScalePlayer_to_saveRecording, bundle)
    } else {
        showSaveDiscardDialog(filePath)
    }
}
```

### Related, still intact (not reverted — not in `update1`'s file list)

- `nav_graph.xml`: `saveRecordingFragment` gained a `popUpToDestination` reference argument
  (default `@id/recordingFragment`) so this shared screen can pop back to whichever recorder
  family invoked it, instead of always popping to production's `recordingFragment` (which
  isn't on the PcgScale back stack, so the pop was silently a no-op there).
- `app/.../ui/recording/SaveRecordingFragment.kt`: reads that argument and uses it in the
  `popUpTo(...)` call instead of the hardcoded `R.id.recordingFragment`.
- `nav_graph.xml`: `action_pcgScalePlayer_to_saveRecording → saveRecordingFragment` action
  itself — already present, ready for the Kotlin call site above to use again.

---

## Fix 3 — Endless background-grid scroll past the recording's end

**Files:**
`app/src/main/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgScaleWaveformView.kt` (source
of the fix, reverted by `update1`) and its two call sites in `PcgScalePlayerFragment.kt`
(reverted by `update1`) and `PcgScaleReviewFragment.kt` (not touched by `update1`, but broke
anyway — see note at the end).

### The bug

`PcgScaleEcgPaperView.scrollOffsetSeconds` only floors at 0 (`coerceAtLeast(0f)`) — nothing
caps it at the recording's actual end. Once the chart's trace correctly stopped scrolling
(bounded by its own data), the grid background kept mirroring `chart.lowestVisibleX` with no
independent notion of "end of recording," so the grid could keep being dragged (or visibly
animate during a fling) past where the trace had already stopped. User-confirmed bug in both
the Player and Review screens; user-confirmed fixed after this change.

### The fix

Give `PcgScaleWaveformView` the file's real duration once it's known, and clamp the mirrored
offset in `syncGridToChart()` to `totalDurationSeconds - visibleWindowSeconds`.

### Code

In `PcgScaleWaveformView.kt`, add a property:
```kotlin
/**
 * Total recording duration in seconds, set by the player/review fragment once the file's
 * real length is known. -1f (the default) means "unknown, don't clamp" — used while the
 * chart still holds placeholder/dummy data before a file loads.
 */
var totalDurationSeconds: Float = -1f
```

Replace `syncGridToChart()`:
```kotlin
// BEFORE
/** Mirror the chart's current left edge onto the grid so lines/labels stay time-pinned. */
fun syncGridToChart() {
    paperView.scrollOffsetSeconds = chart.lowestVisibleX
}

// AFTER
/**
 * Mirror the chart's current left edge onto the grid so lines/labels stay time-pinned —
 * clamped to the last valid scroll offset (recording end minus one window) whenever the
 * total duration is known, so the grid can never scroll past where the trace actually ends.
 */
fun syncGridToChart() {
    val raw = chart.lowestVisibleX
    val maxOffset = if (totalDurationSeconds > 0f) {
        (totalDurationSeconds - currentVisibleSeconds()).coerceAtLeast(0f)
    } else {
        Float.MAX_VALUE
    }
    paperView.scrollOffsetSeconds = raw.coerceAtMost(maxOffset)
}
```

At both call sites (`PcgScalePlayerFragment.renderWaveformEntries` and
`PcgScaleReviewFragment.renderWaveformEntries`), set the duration right where it's computed:
```kotlin
private fun renderWaveformEntries(entries: ArrayList<Entry>, durationSecs: Int) {
    binding.timerText.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
    binding.pcgScaleWaveformView.totalDurationSeconds = durationSecs.toFloat()   // <- add this line
    val dataSet = LineDataSet(entries, "Waveform").apply {
        ...
```

### Note on `PcgScaleReviewFragment.kt`

This file was never part of any handoff bundle (built directly in this session, after the
original bundle) and `update1` never touched it — but it broke anyway: it called
`binding.pcgScaleWaveformView.totalDurationSeconds = ...`, and once `update1` overwrote
`PcgScaleWaveformView.kt` back to the version without that property, the app stopped
compiling. To restore a green build while keeping `update1`'s files as shipped, the
`totalDurationSeconds` line was **removed** from `PcgScaleReviewFragment.renderWaveformEntries`
(2026-08-28). Re-applying this fix means adding the property back to `PcgScaleWaveformView.kt`
*and* restoring that line in `PcgScaleReviewFragment.kt`.

---

## Fix 4 — Trace line width halved

**Files:**
`app/src/main/java/com/musediagnostics/taal/app/ui/pcgscale/PcgScaleRecordingFragment.kt` and
`app/src/main/java/com/musediagnostics/taal/app/ui/pcgscale/PcgScalePlayerFragment.kt`.
**Status:** reverted by `update1` in both files.

### The fix

Explicit user request: "reduce the thickness of the graph line to 50% of what is currently
being used." Applied as a straight halving of each screen's own constant (the two screens had
different values to begin with).

### Code

```kotlin
// PcgScaleRecordingFragment.kt
// BEFORE: private const val TRACE_LINE_WIDTH_DP = 2.0f
// AFTER:  private const val TRACE_LINE_WIDTH_DP = 1.0f

// PcgScalePlayerFragment.kt
// BEFORE: private const val TRACE_LINE_WIDTH_DP = 3.0f
// AFTER:  private const val TRACE_LINE_WIDTH_DP = 1.5f
```

`PcgScaleReviewFragment.kt` (never touched by `update1`) still has its own copy at `1.5f` —
consistent with the player's halved value, since it was written after this fix was already
in place.

---

## Not lost — for context only

These files are **not** part of the `update1` bundle and were **not** reverted; listed here
only because they're part of the same body of work and referenced above.

- `app/.../ui/library/SavedRecordingsFragment.kt` — `onPlay` routes to the new
  `pcgScaleReviewFragment` destination instead of production's `action_savedRecordings_to_player`,
  so opening a saved recording shows the PcgScale-style review screen (same grid/RMS-scale
  look as the recorder) rather than the older production player. Still in place, working.
- `app/.../ui/pcgscale/PcgScaleReviewFragment.kt` +
  `app/src/main/res/layout/fragment_pcgscale_review.xml` — the whole new "review an
  already-saved recording" screen (fork of the player, minus Save/Discard). Still in place,
  compiling (after the Fix 3 knock-on repair above), but currently missing the Fix 3 clamp
  itself since its dependency was reverted.
- `nav_graph.xml` — `pcgScaleReviewFragment` destination + `action_savedRecordings_to_pcgScaleReview`
  action, plus the `popUpToDestination` argument/action mentioned under Fix 2. `nav_graph.xml`
  was never in `update1`'s file list, so none of this was touched.
