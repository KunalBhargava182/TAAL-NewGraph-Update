# ECG Paper View

> Status: **built and verified working, not yet wired into any production screen.**
> Lives entirely in the `app` module, behind the dev-only Test Recording screen.
> Last updated: 2026-08-14.

## What this is

A medically-proportioned ECG graph-paper background: a custom Android `View` that
draws a millimetre-accurate minor/major grid (1mm small squares, 5mm large squares),
with an optional 3-second tick row and a gain-aware calibration pulse — the same
visual convention as real printed ECG paper or a bedside monitor.

It does **not** draw any trace/waveform data itself. It's a paper/background layer
only; a chart or trace view is meant to sit on top of it and draw the signal.

## Where it lives

```
app/src/main/java/com/musediagnostics/taal/app/ecg/
    MmScale.kt          — plain-Kotlin unit math (no Android deps)
    EcgPaperView.kt      — the custom View

app/src/main/res/values/
    attrs_ecg_paper.xml  — XML attrs for EcgPaperView

app/src/test/java/com/musediagnostics/taal/app/ecg/
    MmScaleTest.kt       — 9 unit tests for MmScale
```

Package: `com.musediagnostics.taal.app.ecg`. It depends on nothing outside
`android.graphics`/`android.view`/`android.util` — no MPAndroidChart, no taal-core,
no taal-ui-kit.

**Note on placement**: this was first built inside `taal-ui-kit` (reasoning: it's
generic enough to be reused by a future PCG/trace renderer, and taal-ui-kit is the
reusable UI-SDK module). It was then moved into `app` instead, because `app` doesn't
depend on `taal-ui-kit` at all — everything in `app`'s recording/player screens is a
parallel, separately-maintained implementation on top of `taal-core` directly — and
because that's the module actually being iterated on and tested live on-device. If
this ever needs to be shared with `taal-ui-kit` or `visualizertaal-app`, it will need
to be either duplicated there (matching how `app` already duplicates
Recording/PlayerFragment instead of depending on taal-ui-kit) or promoted into a
module both sides depend on — that's an open decision, not yet made.

## Where it's wired in (test-only, right now)

`app/src/main/res/layout/fragment_test_recording.xml` — an `EcgPaperView`
(`@id/ecgPaperBackground`) sits directly behind `@id/waveformChart` (the
MPAndroidChart `LineChart`), constrained to the exact same bounds. Declared first in
the ConstraintLayout so it draws underneath.

`TestRecordingFragment.kt`'s `setupWaveformChart()` was changed so the chart itself
draws **no** background/grid of its own (`setDrawGridBackground(false)`, all
`setDrawGridLines(false)`) — it's fully transparent now, and only plots the live
waveform trace. The grid you see is 100% `EcgPaperView` underneath.

`TestRecordingFragment` is reached via the `testRecordingFragment` nav destination in
`nav_graph.xml` — it still exists in the graph, but **`startDestination` has been
reverted back to `recordingFragment`** (the app's normal entry point). It is not
currently reachable from anywhere in the UI (no button/menu points to it) — to open
it for testing, either temporarily flip `startDestination` again, or add a debug-only
entry point, or `adb shell am start` with an explicit nav deep link if one gets added
later. This mirrors how it already was before this work (`TestRecordingFragment.kt`
is marked `// THIS IS NOT IN USE (ONLY TESTING BY KUNAL)` in its own source).

**Nothing in the production `RecordingFragment`/`PlayerFragment`, `taal-core`, or
`taal-ui-kit` was touched or depends on any of this.**

## The code

### `MmScale.kt`

Pure Kotlin, no Android imports — deliberately so it's plain-JVM unit-testable and
reusable by a future trace/PCG renderer without pulling in View/Canvas.

- `PaperSpeed` enum: `SPEED_12_5` / `SPEED_25` / `SPEED_50` (mm/s). Default `SPEED_25`.
- `Gain` enum: `GAIN_5` / `GAIN_10` / `GAIN_20` (mm/mV). Default `GAIN_10`.
- `MmScale(pxPerMmX, pxPerMmY, paperSpeed, gain)`:
  - `secondsToPx(s)` / `pxToSeconds(px)` — time axis, uses `paperSpeed`
  - `mvToPx(mv)` / `pxToMv(px)` — voltage axis, uses `gain`
  - `smallSquarePxX/Y()`, `largeSquarePxX/Y()` — **grid geometry, depends only on
    pxPerMmX/Y**, never on speed or gain
  - `secondsPerSmallSquare()`, `secondsPerLargeSquare()`, `mvPerSmallSquare()`,
    `mvPerLargeSquare()` — what a square currently *means*, given the current
    speed/gain

The split matters: changing paper speed or gain changes what a square is worth, but
never resizes it on screen. That invariant is what `MmScaleTest.kt` checks directly
(e.g. `changing gain scales only the trace, never the grid`).

At the defaults, this gives exactly the standard ECG convention: 1 small square =
0.04s / 0.1mV, 1 large square (5 small squares) = 0.2s / 0.5mV, 1mV = 10mm, 1s = 25mm.

### `EcgPaperView.kt`

- **Millimetre accuracy**: `pxPerMmX` comes from
  `TypedValue.applyDimension(COMPLEX_UNIT_MM, 1f, displayMetrics)` (which is
  `xdpi`-based). `pxPerMmY` is computed separately from `displayMetrics.ydpi / 25.4f`,
  because `applyDimension` always uses `xdpi` internally even when you're asking for
  a "vertical" style unit — so it cannot be reused for the Y axis. `setPixelsPerMm(x,
  y)` is exposed publicly to override both, since OEM-reported DPI is frequently
  wrong.
- **No rounding drift**: every grid line's position is computed as
  `index * pxPerMm` in `Float` all the way through; nothing is rounded to `Int` until
  Canvas actually rasterizes it. Avoids the classic bug where 30+ accumulated
  per-line roundings drift the grid visibly out of alignment.
- **Rendering**: the whole grid (background fill, minor lines, major lines, time
  ticks, calibration pulse) is drawn once into a cached `Bitmap` — rebuilt only in
  `onSizeChanged()` or when a public setter changes theme/speed/gain/colors/DPI —
  and just blitted with `canvas.drawBitmap(...)` in `onDraw()`. No allocations happen
  in `onDraw()` itself. Minor and major gridlines are each batched into one
  `Canvas.drawLines(FloatArray, Paint)` call rather than one `drawLine()` call per
  line.
- **Two-tier grid**: minor lines drawn first at every 1mm; major lines drawn on top
  at every 5th line (`MmScale.SMALL_SQUARES_PER_LARGE_SQUARE`) with a heavier stroke
  — the overlap is intentional and cheaper than filtering out coincident indices.
- **Time ticks**: short marks along the top edge every 15 large squares (a fixed
  75mm physical interval — reads as "3 seconds" specifically at the default 25mm/s
  speed, per the real ECG paper convention; the rule itself is speed-independent).
- **Calibration pulse**: drawn at the left edge — flat baseline, step up
  `mmScale.mvToPx(1f)` tall (so it's gain-aware: 10mm tall at default gain, 20mm at
  double gain, matching real calibration-pulse behavior), flat top for 5 small
  squares wide (200ms at the default 25mm/s), step back down.
- **Themes**: `EcgTheme.PAPER` (`#FFF8F5` background, `#F2B8B5` minor grid,
  `#E0837F` major grid) and `EcgTheme.MONITOR` (near-black `#05100A` background, dim
  green `#123D22` minor / `#1F7A45` major). Any of paper/minor/major color can be
  overridden individually via XML attrs regardless of theme.
- **Edit-mode safe**: `isInEditMode` is checked explicitly — if the Layout Editor's
  preview device reports `xdpi`/`ydpi` as 0 (common), it falls back to an
  approximation from `density * 160` instead of producing a NaN/blank grid.

### `attrs_ecg_paper.xml`

`declare-styleable name="EcgPaperView"` with: `ecgPaperSpeed` (enum:
`speed_12_5`/`speed_25`/`speed_50`), `ecgGain` (enum: `gain_5`/`gain_10`/`gain_20`),
`ecgTheme` (enum: `paper`/`monitor`), `ecgShowCalibrationPulse` (boolean),
`ecgShowTimeTicks` (boolean), `ecgPaperColor` / `ecgMinorGridColor` /
`ecgMajorGridColor` (color, each optional — overrides the theme default when set).

## Verification performed

- `MmScaleTest.kt`, 9/9 passing: 1mV=10mm and 1s=25mm at defaults; grid-square px
  size is unaffected by changing speed or gain (only what the square *means*
  changes); non-square pixels (different pxPerMmX vs pxPerMmY) handled correctly and
  independently.
- Full `:app` unit test suite run clean after these changes (no new failures).
- `:app:assembleDebug` builds clean.
- Visually confirmed on a real connected device (Samsung Galaxy A06) in both PAPER
  and MONITOR themes, rendered live inside `TestRecordingFragment` behind the actual
  waveform chart — fine 1mm grid, bold 5mm grid, correct colors, calibration pulse
  all present and correctly proportioned.

## Bug found and fixed along the way (unrelated to the ECG work itself)

`TestRecordingFragment.startRecording()` called `taalRecorder?.start()` with no
try/catch. Tapping Record with no TAAL USB device connected throws
`TaalDisconnectedException`, which was uncaught and crashed the whole app. Wrapped it
in a try/catch that re-enables the gain slider and shows a `Toast` with the
exception's message instead — matching (in spirit, at test-screen weight) how the
real `RecordingFragment.kt` already handles the same exception with a
`MaterialAlertDialogBuilder`. This was pre-existing behavior, not something the ECG
work introduced — it just got surfaced while testing on a device with no stethoscope
attached.

## Known limitations / open questions for next time

- Not wired into the real `RecordingFragment`/`PlayerFragment` yet. `app` doesn't
  depend on `taal-ui-kit`, so this was always going to need a decision either way —
  see the "Note on placement" section above.
- Waveform trace color (`#128CB2` teal in `TestRecordingFragment`) was deliberately
  left unchanged — only the background/grid changed. Whether the trace color should
  change to fit an ECG-paper look (traditionally black/dark on paper, bright
  green on a monitor) hasn't been decided.
- `TestRecordingFragment` is currently unreachable from the app's UI (no menu/button
  points to it) — same as before this session. Reaching it for further testing needs
  a temporary `startDestination` flip, a debug entry point, or a nav deep link.
- `EcgPaperView`'s time-tick and calibration-pulse geometry are fixed-mm rules (not
  configurable via XML attrs yet) — only speed/gain/theme/colors/show-flags are.
