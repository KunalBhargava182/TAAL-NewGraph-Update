# Waveform Graph & Grid — Master Reference

> **Purpose of this file**: single source of truth for every piece of code that draws
> a waveform, a chart grid, or the ECG-paper background in this repo's `app` module.
> Everything below was read directly from source and cross-checked line-by-line — not
> summarized from memory or from older docs. If you paste this into a fresh chat to
> make changes, read the **"Read this first"** and **"If you change X, also check Y"**
> sections before touching any file, because this system is **four separate,
> independently-maintained implementations**, not one shared component.
>
> Verified against source on **2026-08-18**. If any file path/line below doesn't match
> what you see in the repo, the code has moved on since this was written — trust the
> code, not this doc, and update this file to match.

---

## 0. Read this first — the four implementations

There is **no shared waveform/chart module**. Four separate places each have their
own copy-pasted-and-diverged version of "downsample audio → plot on MPAndroidChart":

| # | Screen | Status | File | Grid style |
|---|---|---|---|---|
| A | Recording (production) | **Live, reachable** — this is what users see | `app/…/ui/recording/RecordingFragment.kt` | MPAndroidChart's own light-gray gridlines |
| B | Player (production) | **Live, reachable** | `app/…/ui/player/PlayerFragment.kt` | MPAndroidChart's own light-gray gridlines |
| C | Test Recording | **Dormant** — not reachable from any UI button/menu | `app/…/ui/recording/TestRecordingFragment.kt` | `EcgPaperView` (mm-accurate ECG paper) behind a transparent chart |
| D | Test Player | **Dormant** — only reachable from C | `app/…/ui/player/TestPlayerFragment.kt` | MPAndroidChart's own light-gray gridlines (no `EcgPaperView`) |

**Critical**: A/B and C/D look similar (same audio pipeline, same MPAndroidChart
library) but are **not refactors of each other** — they have different constants,
different axis ranges, different downsampling behavior, and C/D are missing entire
features A/B have (see §4 divergence table). Fixing something in A does **not** fix
it in C. If a request says "fix the waveform graph" without saying which screen,
**ask which of A/B/C/D**, or assume A+B (production) since those are the only ones
users can reach today.

There is also a **fifth, unrelated codebase** with its own `RecordingFragment`/
`PlayerFragment`: `taal-ui-kit/` (a separate Gradle module, used by
`visualizertaal-app`) and `lungs-app/` have their own independent waveform code.
`app` does not depend on `taal-ui-kit` — nothing in this doc applies to those
modules. Do not "fix" a bug across modules unless explicitly asked to.

---

## 1. Shared audio facts (true for all four)

| Property | Value |
|---|---|
| Source | TAAL USB digital stethoscope, via `TaalRecorder`/`TaalPlayer` (`taal-core`) |
| Native sample rate | 44,100 Hz |
| Bit depth | 16-bit PCM |
| Channels | Mono |
| File format | WAV, 44-byte header |
| Live buffer values | Normalized float `-1.0..+1.0` |
| Saved-file sample decode | `sampleInt = (bytes[hi] shl 8) or (bytes[lo] and 0xFF)`, then `/ 32768f` |
| WAV header sample-rate field | bytes 24–27, little-endian `Int` |

---

## 2. Screen A — Recording (production, live)

**File**: `app/src/main/java/com/musediagnostics/taal/app/ui/recording/RecordingFragment.kt`
**Layout**: `app/src/main/res/layout/fragment_recording.xml` (`@id/waveformChart` only — no `EcgPaperView`)

### 2.1 Constants (companion object, ~lines 79–92)
```kotlin
private const val WINDOW_SECONDS = 10f       // fixed 10s visible window
private const val INPUT_SAMPLE_RATE = 44100f
private const val DOWNSAMPLE_STEP = 44       // 1 in 44 samples plotted (~1002 pts/sec)
private const val WARMUP_MS = 2000           // observe signal for 2s before locking Y-axis
private const val HEADROOM = 1.5f            // Y-axis = peak * 1.5
private const val MIN_PEAK = 0.02f           // Y-axis half-range floor (near-silence guard)
```

### 2.2 Chart setup — `setupWaveformChart()` (~line 147)
```kotlin
chart.setTouchEnabled(false)             // no pan/zoom while recording
chart.setDrawGridBackground(true)
chart.setGridBackgroundColor(Color.WHITE)
xAxis: gridColor #F0F0F0, gridLineWidth 1f, granularity 1f, no labels, no axis line
axisLeft: gridColor #F0F0F0, gridLineWidth 1f, axisMinimum -1f, axisMaximum 1f (before warmup locks it)
axisRight: disabled
chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
chart.setVisibleXRangeMinimum(WINDOW_SECONDS)   // ← window is locked, not just capped
```
A transparent 2-point dummy dataset (`Entry(0,0)`, `Entry(WINDOW_SECONDS,0)`) is set
before recording starts, purely so the grid renders on an empty chart. Re-applied on
reset-to-idle.

### 2.3 Data flow, in order
```
TaalRecorder.onProgressUpdate(sampleRate, bufferSize, timeStamp, data: FloatArray)
  data[] = filtered (bandpass) + pre-amplified buffer
       │
       ▼  (~lines 697-700, inside onProgressUpdate)
val preAmpDb = viewModel.preAmpDb.value ?: 5
val preAmpGain = 10.0.pow(preAmpDb / 20.0)
val displayData = if (preAmpGain > 1.001f)
                     FloatArray(data.size) { i -> data[i] / preAmpGain }
                   else data
       │
       ▼
updateWaveform(timeStamp, displayData)   // Main thread
```
**This division is display-only.** The saved WAV file keeps the amplified signal;
only what's drawn on screen is de-amplified back to true acoustic level. If you ever
need to change pre-amp behavior, remember this compensation exists — turning it off
will make the on-screen trace look artificially loud relative to what was actually
picked up.

`preAmpDb` itself: `RecordingViewModel.kt` — `MutableLiveData(5)` default,
`setPreAmpDb()` coerces to `0..30`.

### 2.4 `updateWaveform()` — the V7 algorithm (~line 819)

**X-axis**: a monotonic sample counter, not the callback's `timeStamp` (which jitters):
```kotlin
val step = DOWNSAMPLE_STEP
for (i in 0 until data.size step step) {
    val currentX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
    waveformEntries.add(Entry(currentX, data[i]))     // raw sample, no min/max/RMS reduction
    totalSamplesProcessed += step
}
```

**Camera — page-snap, not smooth scroll**:
```kotlin
val currentPage = (latestX / WINDOW_SECONDS).toInt()
val currentViewX = currentPage * WINDOW_SECONDS
// later: chart.moveViewToX(currentViewX)
```
The waveform scrolls left→right *within* the current 10s page; at each 10s boundary
the camera **jumps** instantly to the next page. This is intentional (smooth
continuous scroll at ~1002 pts/sec was judged too costly on the Main thread) — don't
"fix" the jump without knowing that.

**Y-axis — two-phase, locks after warmup and never re-expands**:
```kotlin
// bufferPeak = max(abs(sample)) over this buffer, computed at top of updateWaveform()
if (!warmupDone) {
    if (bufferPeak > warmupPeak) warmupPeak = bufferPeak
    if (now - lastPeakUpdateTime >= WARMUP_MS) {
        warmupDone = true
        peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
        chart.axisLeft.axisMinimum = -peakAmplitude
        chart.axisLeft.axisMaximum = peakAmplitude
    }
}
// the `else` branch that used to keep re-expanding the axis has been deliberately removed
```
Peak here is a **true per-buffer max absolute sample**, not RMS. If the signal gets
louder after the 2s warmup window, the trace clips visually — the axis is frozen by
design (comment in source explicitly says this eliminates the flicker that continuous
re-expansion used to cause).

**Memory cleanup** — keeps current page + previous page only (~20s max):
```kotlin
val minXToKeep = (currentPage - 1) * WINDOW_SECONDS
// entries with x < minXToKeep are trimmed from the front of waveformEntries
```

**Dataset reuse** (avoids MPAndroidChart's one-frame blank on `chart.data =` replace):
```kotlin
if (ds == null || chart.data == null) {
    waveformDataSet = LineDataSet(snapshot, "Waveform").apply {
        color = R.color.waveform_blue   // #2D7DD2
        lineWidth = 1.5f
        mode = LineDataSet.Mode.LINEAR
        setDrawCircles(false); setDrawValues(false); setDrawHighlightIndicators(false)
    }
    chart.data = LineData(waveformDataSet)
} else {
    ds.values = snapshot
    chart.data?.notifyDataChanged()
}
```
`chart.data` is only ever *replaced* on reset-to-idle; every other update mutates the
existing `LineDataSet.values` in place.

### 2.5 Recording → Player handoff
`stopRecording()` navigates via `action_recording_to_player` (nav_graph.xml, ~line
186) passing `filePath` (filtered WAV), `rawFilePath`, `filterName`. See
`[[project_dual_file_filtered_recording]]`-style flow in memory — two files are
written per recording (`_raw` + `_filtered`); the graph only ever reads/plots the
filtered one during recording (via the live `data[]` buffer, not from disk).

---

## 3. Screen B — Player (production, live)

**File**: `app/src/main/java/com/musediagnostics/taal/app/ui/player/PlayerFragment.kt`
**Layout**: `app/src/main/res/layout/fragment_player.xml` (`@id/waveformChart` only — no `EcgPaperView`)

### 3.1 Constants (~lines 34–35)
```kotlin
private const val INPUT_SAMPLE_RATE = 44100f
private const val HEADROOM = 1.5f   // declared but NOT referenced anywhere else in this file — dead constant, leftover
```

### 3.2 Chart setup — `setupWaveformChart()` (~line 114)
```kotlin
setDrawGridBackground(true); setGridBackgroundColor(WHITE)
xAxis: gridColor #F0F0F0, gridLineWidth 1f, no labels, no axis line
axisLeft: gridColor #F0F0F0, gridLineWidth 1f, axisMinimum -0.5f, axisMaximum 0.5f  // fixed, no warmup — narrower than Recording's ±1.0 because the WAV is already filtered/normalized
axisRight: disabled
// touch IS enabled here (opposite of Recording screen)
```
Transparent dummy dataset `Entry(0,0)`/`Entry(4,0)` shown before the file loads.

### 3.3 Loading — one-shot from file, IO thread (~`loadFullWaveform()`, line 153)
```kotlin
val bytes = file.readBytes()
// sample rate read from WAV header bytes 24-27 little-endian — NOT hardcoded.
// Comment in source: an 8kHz AI-testing file would show wrong duration if hardcoded to 44100.
val fileSampleRate = (bytes[24]..bytes[27] little-endian Int).toFloat()  // falls back to 44100f if bytes.size < 28 or rate <= 0

val totalSamples = (bytes.size - 44) / 2
val durationSecs = (totalSamples / fileSampleRate).toInt()
val maxPoints = 3000
val step = maxOf(1, totalSamples / maxPoints)

// decode loop, every `step`-th sample:
val sample = (((bytes[bytePos+1].toInt() shl 8) or (bytes[bytePos].toInt() and 0xFF)).toShort()) / 32768f
entries.add(Entry(i.toFloat() / fileSampleRate, sample))   // X uses the FILE's actual rate
```
For a typical 10s/44100Hz file: `step ≈ 147`.

### 3.4 Render (~`renderWaveformEntries()`, line 198)
```kotlin
color = "#2D7DD2" (literal, same value as waveform_blue but not the resource)
lineWidth = 2.5f          // thicker than Recording's 1.5f
mode = LINEAR
chart.setVisibleXRangeMaximum(4f)                       // 4s window, unlike Recording's 10s
chart.centerViewTo(2f, 0f, AxisDependency.LEFT)          // start centered on 0-4s
setTouchEnabled(true); isDragEnabled = true; setScaleEnabled(true)   // user CAN pan/zoom, unlike Recording
```

### 3.5 Playback head tracking (~`setupPlayer()` → `onPlaybackProgress`, line 231)
```kotlin
val halfRange = chart.visibleXRange / 2f
val centerX = if (timestamp < halfRange) halfRange else timestamp.toFloat()
chart.centerViewTo(centerX, 0f, AxisDependency.LEFT)
```
Continuous centered-follow (not page-snap like Recording). Left edge is pinned at 0
for the first `halfRange` seconds so it never shows negative time.

On stop / on complete / on play-start, the view snaps back:
```kotlin
chart.centerViewTo(chart.visibleXRange / 2f, 0f, AxisDependency.LEFT)
```
Three separate call sites do this (`togglePlayback()` stop branch, `onPlaybackComplete`,
`togglePlayback()` play branch before `player.start()`) — if you change the snap-back
behavior, all three need updating, not just one.

### 3.6 Filter re-application guard (~line 222)
```kotlin
val fileName = File(filePath).name
if (!fileName.contains("_filtered") && !fileName.contains("_8k_downsampling")) {
    setPreFilter(preFilter)   // only applied to files NOT already filtered
}
```
Prevents double-filtering already-processed files. Has nothing to do with the graph
directly but lives right next to the chart setup in the same function block — easy to
accidentally touch when editing chart code nearby.

---

## 4. Screens C+D — Test Recording / Test Player (dormant, ECG paper grid)

**Not reachable from the app UI.** `nav_graph.xml` `startDestination` is
`recordingFragment` (confirmed current as of this doc). To reach C, you must
temporarily flip `startDestination` to `testRecordingFragment`, add a debug entry
point, or use an explicit nav deep link.

### 4.1 `EcgPaperView` + `MmScale` — the paper/grid layer itself

**Files**:
- `app/src/main/java/com/musediagnostics/taal/app/ecg/MmScale.kt` — pure Kotlin, no Android deps
- `app/src/main/java/com/musediagnostics/taal/app/ecg/EcgPaperView.kt` — the custom `View`
- `app/src/main/res/values/attrs_ecg_paper.xml` — XML attrs
- `app/src/test/java/com/musediagnostics/taal/app/ecg/MmScaleTest.kt` — 9 unit tests

It draws **grid only** — never a trace/waveform. A chart sits on top of it.

**`MmScale`** — the core invariant: **grid square physical size (px) depends only on
`pxPerMmX`/`pxPerMmY`; `paperSpeed`/`gain` change only what a square *means*, never
its size on screen.**
```kotlin
enum class PaperSpeed(val mmPerSecond: Float) { SPEED_12_5(12.5f), SPEED_25(25f), SPEED_50(50f) }   // default SPEED_25
enum class Gain(val mmPerMv: Float) { GAIN_5(5f), GAIN_10(10f), GAIN_20(20f) }                      // default GAIN_10

secondsToPx(s) = s * paperSpeed.mmPerSecond * pxPerMmX
mvToPx(mv)     = mv * gain.mmPerMv * pxPerMmY
smallSquarePxX() = 1mm * pxPerMmX     // grid geometry — NEVER touches speed/gain
largeSquarePxX() = 5mm * pxPerMmX     // (5 small squares per large square)
```
At defaults: 1 small square = 0.04s / 0.1mV; 1 large square = 0.2s / 0.5mV; 1mV = 10mm; 1s = 25mm.

**`EcgPaperView`**:
- `pxPerMmX` from `TypedValue.applyDimension(COMPLEX_UNIT_MM, 1f, displayMetrics)` (xdpi-based).
- `pxPerMmY` from `displayMetrics.ydpi / 25.4f` directly — **cannot** reuse `applyDimension` for this because it always keys off `xdpi` internally even for "vertical" units. This is a real Android quirk, not a bug in this code — don't "simplify" it into one shared call.
- `setPixelsPerMm(x, y)` public override, since OEM-reported DPI is often wrong.
- Whole grid (bg, minor lines, major lines, time ticks, calibration pulse) rendered once into a cached `Bitmap`, rebuilt only in `onSizeChanged()` or when a public setter (theme/speed/gain/color/DPI) changes it; `onDraw()` just blits the bitmap. **No allocations in `onDraw()`.**
- Minor grid every 1mm; major grid on top every 5th line (`MmScale.SMALL_SQUARES_PER_LARGE_SQUARE = 5`), heavier stroke. Overlap is intentional (cheaper than filtering coincident indices).
- Time ticks: every 15 large squares = fixed 75mm physical interval (reads as "3 seconds" specifically at default 25mm/s speed; the *rule* itself doesn't depend on speed).
- Calibration pulse: left edge, flat baseline → step up `mvToPx(1f)` tall (gain-aware) → flat top for 5 small squares (200ms at default speed) → step down.
- Themes: `PAPER` (`#FFF8F5` bg / `#F2B8B5` minor / `#E0837F` major) and `MONITOR` (`#05100A` bg / `#123D22` minor / `#1F7A45` major). Any color individually overridable via XML attrs regardless of theme.
- Edit-mode safe: falls back to `density * 160` DPI approximation if Layout Editor reports `xdpi`/`ydpi` as 0.

**`attrs_ecg_paper.xml`** enum values (confirmed matching `EcgPaperView.readAttrs()`'s `when` mapping exactly):
`ecgPaperSpeed`: 0=`speed_12_5`, 1=`speed_25`(default), 2=`speed_50`
`ecgGain`: 0=`gain_5`, 1=`gain_10`(default), 2=`gain_20`
`ecgTheme`: 0=`paper`(default), 1=`monitor`
plus booleans `ecgShowCalibrationPulse`/`ecgShowTimeTicks` and optional color overrides
`ecgPaperColor`/`ecgMinorGridColor`/`ecgMajorGridColor`.

### 4.2 How it's wired into the layout — `fragment_test_recording.xml`

```xml
<EcgPaperView android:id="@+id/ecgPaperBackground"
    android:layout_marginHorizontal="16dp" android:layout_marginVertical="16dp"
    app:layout_constraintTop_toTopOf="@id/waveformChart"
    app:layout_constraintBottom_toBottomOf="@id/waveformChart"
    app:layout_constraintStart_toStartOf="@id/waveformChart"
    app:layout_constraintEnd_toEndOf="@id/waveformChart" />

<LineChart android:id="@+id/waveformChart"
    android:layout_marginHorizontal="16dp" android:layout_marginVertical="16dp"
    app:layout_constraintTop_toBottomOf="@id/filterRow"
    app:layout_constraintBottom_toTopOf="@id/gainContainer" />
```
**Fragile point**: the two views' margins (`16dp`/`16dp`) are declared **separately**
on each view, not inherited — `EcgPaperView` is constrained to `waveformChart`'s edges,
but its own margins must be kept in sync by hand. Change one view's margin without the
other and the paper grid will no longer line up exactly behind the chart's plot area.

`TestRecordingFragment.setupWaveformChart()` (~line 96) turns off the chart's own
grid so only `EcgPaperView` is visible underneath:
```kotlin
chart.setDrawGridBackground(false)
chart.xAxis.setDrawGridLines(false)
chart.axisLeft.setDrawGridLines(false)
```
**If you ever add `EcgPaperView` behind the production Recording/Player charts (A/B),
you must copy this "turn off the chart's own grid" step too** — otherwise you'll get
two overlapping, misaligned grids (MPAndroidChart's `#F0F0F0` lines *and* the mm-paper
grid at the same time).

### 4.3 `TestRecordingFragment.kt` waveform logic — simpler than production A, on purpose

Marked in its own source: `// THIS IS NOT IN USE (ONLY TESTING BY KUNAL)`.

Diverges from Screen A in several ways — do not assume parity:
- **Single recording file** (`recording_{ts}.wav`) — no raw+filtered split like production.
- **No pre-amp display compensation** — plots `data[i]` directly, no `/preAmpGain`.
- **No warmup/peak logic at all** — Y-axis is a fixed `axisMinimum=-1f`/`axisMaximum=1f`, never adapts.
- **Continuous follow, not page-snap** — `chart.moveViewToX(currentX - 10f)` every callback, vs Screen A's `(latestX / WINDOW_SECONDS).toInt() * WINDOW_SECONDS` page jump.
- **No dummy 2-point dataset before recording** — `chart.data = LineData()` (empty).
- Recording start wraps `taalRecorder?.start()` in try/catch (added specifically to stop `TaalDisconnectedException` from crashing the app when no USB device is attached — this was a real bug found and fixed while building the ECG paper view, unrelated to the grid work itself).

Downsampling constant is the same (`DOWNSAMPLE_STEP = 44`), and the raw-sample
plotting technique (no RMS/min-max window reduction, just picking every Nth sample)
is the same as Screen A.

### 4.4 `TestPlayerFragment.kt` — a *third* independent player waveform implementation

Also unused (`// THIS IS NOT IN USE (ONLY TESTING BY KUNAL)`). Diverges from
production Screen B in ways worth knowing if this is ever revived:
- **No `EcgPaperView`** — `fragment_test_player.xml` has no such view; this screen still uses MPAndroidChart's own `#F0F0F0` grid (`setDrawGridBackground(true)`), same as production.
- **Hardcodes `INPUT_SAMPLE_RATE = 44100f`** for X-axis positions — does **not** read the WAV header's actual sample rate the way production `PlayerFragment` does. This means an 8kHz test file would display at the wrong duration/speed here, exactly the bug production's header-parsing was written to avoid.
- **Y-axis fixed `-1f`/`1f`** (vs production's `-0.5f`/`0.5f`).
- **No visible-range lock** — `setVisibleXRangeMaximum(Float.MAX_VALUE)`, i.e. effectively unbounded; no initial `centerViewTo()` framing.
- **Trace color `#128CB2`** (teal, matches this screen's other UI accents) and `lineWidth = 1.0f` — vs production's `#2D7DD2` / `2.5f`.
- **No `_filtered`/`_8k_downsampling` guard** before applying a filter — `setupPlayer()` here doesn't call `setPreFilter` at all.

### 4.5 Filter chip UI (present in both C and A, cosmetic — not graph logic)

The `gainSlider` in `fragment_test_recording.xml` is `valueFrom="0.0"` /
`valueTo="10.0"` — capped at 10dB in this dormant test screen even though
`TaalRecorder`/`AudioFilterEngine`'s actual pre-amp range is 0–30dB. This mirrors a
known pattern already tracked separately in project memory (`EditRecordingFragment`
has the same 0–10dB UI cap vs the SDK's real 0–30dB clamp) — worth fixing alongside
that one if this screen is ever revived, not a graph-rendering issue itself.

---

## 5. Cross-cutting "if you change X, also check Y" table

| You want to change... | Also check | Why |
|---|---|---|
| Visible window seconds (Recording) | `WINDOW_SECONDS` const, dummy dataset endpoints, `setVisibleXRangeMaximum/Minimum`, page-calc (`currentPage`), memory-cleanup (`minXToKeep`) | All derive from the same constant inside `RecordingFragment.kt`; none of it is shared with Player or the Test screens |
| Downsample density (`DOWNSAMPLE_STEP`) | It's declared **separately** in `RecordingFragment.kt`, `TestRecordingFragment.kt` — changing one does not change the other. Also unrelated to `HeartBpmCalculator`'s own internal `downsampleFactor = 44` (different subsystem, computes BPM not the trace — coincidentally the same number, not a shared constant) |
| Y-axis warmup behavior | `WARMUP_MS`/`HEADROOM`/`MIN_PEAK` only exist in production `RecordingFragment.kt`. `TestRecordingFragment` has no equivalent — its axis is hardcoded ±1.0 forever |
| Pre-amp display compensation | Only in `RecordingFragment.kt` (`onProgressUpdate`, `data[i] / preAmpGain`). `TestRecordingFragment` and both Player screens do **not** apply this — Player screens play back a file that's already had the gain baked in at record time, so there's nothing to compensate |
| WAV header / sample-rate parsing | Only production `PlayerFragment.kt` reads the real header rate. `TestPlayerFragment.kt` hardcodes 44100 — a known, undocumented-until-now divergence |
| Adding the ECG paper grid to production A/B | Must also: (1) set `chart.setDrawGridBackground(false)` + both axes' `setDrawGridLines(false)`, matching §4.2; (2) add `EcgPaperView` behind the chart with **matching** constraints AND margins; (3) decide whether `EcgPaperView`'s fixed-mm calibration-pulse/time-tick rules make sense at Player's 4s window vs Recording's 10s window — they're currently only tuned/tested behind Recording-shaped screens |
| Grid square physical size vs. what it means (`MmScale`) | These are deliberately decoupled — `pxPerMmX/Y` (screen physicality) vs `paperSpeed`/`gain` (clinical meaning). Don't "simplify" by making speed/gain resize the grid — that's the exact bug `MmScaleTest.kt` guards against |
| Line/trace color | Recording uses `R.color.waveform_blue` (`#2D7DD2`, `colors.xml:38`); Player uses the same hex as a **literal**, not the resource; Test screens use a different teal `#128CB2` literal (matches those screens' other accent colors) — there is no single "the waveform color" |
| `nav_graph.xml` `startDestination` | Currently `recordingFragment`. If you flip it to `testRecordingFragment` for testing C/D, **revert it before shipping** — this has been a recurring gotcha noted in `ECG_Paper_View.md` |

---

## 6. File index

| File | Role |
|---|---|
| `app/…/ui/recording/RecordingFragment.kt` | Screen A logic |
| `app/src/main/res/layout/fragment_recording.xml` | Screen A layout |
| `app/…/ui/recording/RecordingViewModel.kt` | `preAmpDb` LiveData (0–30 coerced, default 5) used by Screen A's compensation formula |
| `app/…/ui/player/PlayerFragment.kt` | Screen B logic |
| `app/src/main/res/layout/fragment_player.xml` | Screen B layout |
| `app/…/ui/recording/TestRecordingFragment.kt` | Screen C logic (dormant) |
| `app/src/main/res/layout/fragment_test_recording.xml` | Screen C layout — only place `EcgPaperView` is wired in |
| `app/…/ui/player/TestPlayerFragment.kt` | Screen D logic (dormant) |
| `app/src/main/res/layout/fragment_test_player.xml` | Screen D layout — no `EcgPaperView` |
| `app/…/ecg/MmScale.kt` | Pure-Kotlin mm/px/time/voltage unit math |
| `app/…/ecg/EcgPaperView.kt` | The paper/grid custom `View` |
| `app/src/main/res/values/attrs_ecg_paper.xml` | XML attrs for `EcgPaperView` |
| `app/src/test/java/…/ecg/MmScaleTest.kt` | 9 unit tests for `MmScale` |
| `app/src/main/res/navigation/nav_graph.xml` | `startDestination`, all fragment/action wiring (Recording ~line 181, TestRecording ~line 373) |
| `app/…/dsp/HeartBpmCalculator.kt` | **Not graph code** — BPM-only, mean-abs-value envelope + autocorrelation. Mentioned here only because it's easy to confuse with the trace-rendering pipeline; it never touches the chart |
| `app/src/main/res/values/colors.xml:38` | `waveform_blue = #2D7DD2` |

---

## 7. Related docs already in this repo

- `docs/notes/ECG_Paper_View.md` — narrative write-up of the `EcgPaperView` build (superseded in detail by §4 above, but has the "verification performed" / device-testing notes this file doesn't repeat).
- `docs/notes/MuseD App Graph.md` — earlier version of §2–3 above, written for an external web team; this file supersedes it with the C/D screens and the cross-cutting table added.
