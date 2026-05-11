# MuseD App – `waveformChart` Documentation

> **Purpose:** Complete reference for how the waveform graph works in both the
> Recording screen and the Player (Review) screen of the TAAL Android app.
> Written to help the web team reproduce the same graph behaviour accurately.

---

## 1. Library & Chart Type

| Property | Value |
|---|---|
| Library | **MPAndroidChart** (`com.github.PhilJay:MPAndroidChart:v3.1.0`) |
| Chart class | `com.github.mikephil.charting.charts.LineChart` |
| View ID | `@+id/waveformChart` |
| Fragment (Recording) | `RecordingFragment.kt` |
| Fragment (Player) | `PlayerFragment.kt` |

---

## 2. Audio Input Specs

These specs apply to the signal being graphed. The source is the **TAAL USB digital stethoscope**.

| Property | Value |
|---|---|
| Sample rate | 44,100 Hz |
| Bit depth | 16-bit PCM |
| Channels | Mono (1 channel) |
| File format | WAV |
| Signal range | Normalized float: `−1.0` to `+1.0` |
| Pre-amp range | 0–30 dB (applied by `TaalRecorder` before the callback) |
| Pre-set filter | Butterworth bandpass (HEART / LUNGS / BOWEL / PREGNANCY / FULL_BODY) |

---

## 3. Recording Screen Graph

### 3.1 Layout Positioning

The chart occupies all vertical space between the **amp slider card** (above) and the **BPM text** (below). Width is full-screen. When recording is active, the bottom bar hides so the chart expands further.

```
[Top Bar]
[Timer]
[Filter Chips]
[Amp Slider Card]
  ↕
[waveformChart]  ← fills available height
  ↕
[BPM text]
[Action text]
[Record button]
[Bottom bar]     ← hidden during recording
```

### 3.2 Chart Configuration

```kotlin
chart.description.isEnabled = false   // no description label
chart.legend.isEnabled = false         // no legend
chart.setTouchEnabled(false)           // touch/drag disabled while recording
chart.setDrawGridBackground(true)
chart.setGridBackgroundColor(Color.WHITE)
```

### 3.3 Axes

**X-Axis**

| Setting | Value |
|---|---|
| Position | BOTTOM |
| Grid lines | Enabled, color `#F0F0F0`, width 1f |
| Granularity | 1f (1 second per grid cell) |
| Label count | 10 |
| Labels visible | No |
| Axis line | No |
| Min / Max | Not set — X grows infinitely as recording progresses |

**Y-Axis (Left)**

| Setting | Value |
|---|---|
| Grid lines | Enabled, color `#F0F0F0`, width 1f |
| Initial range | `−1.0` to `+1.0` (set during warmup phase) |
| Labels visible | No |
| Axis line | No |

**Y-Axis (Right)**: disabled entirely.

### 3.4 Viewport (Window Lock)

```kotlin
chart.setVisibleXRangeMaximum(10f)   // always show exactly 10 seconds
chart.setVisibleXRangeMinimum(10f)   // no zoom in or out allowed
```

The user always sees a fixed **10-second window**. No pinch-zoom, no drag.

### 3.5 Initial Dummy Data (Before Recording)

A transparent 2-point line is placed at X=0 and X=10 so the grid is visible even before recording starts:

```kotlin
val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(10f, 0f)), "").apply {
    color = Color.TRANSPARENT
    setDrawCircles(false)
    setDrawValues(false)
}
chart.data = LineData(dummyDataSet)
```

On **reset to idle**, this same dummy data is re-applied so the grid stays visible.

---

### 3.6 How Data Flows from Device to Graph

```
TAAL USB Stethoscope
       │
       ▼
TaalRecorder (AudioRecord 44100Hz mono 16-bit)
  → applies PreFilter (bandpass)
  → applies Pre-Amp gain (0–30 dB)
       │
       ▼
onProgressUpdate(sampleRate, bufferSize, timeStamp, data: FloatArray)
  → data[] is the FILTERED + AMPLIFIED audio buffer
       │
       ▼
Pre-Amp Compensation (display only):
  preAmpGain = 10^(preAmpDb / 20)
  displayData[i] = data[i] / preAmpGain
  (Graph shows ACOUSTIC level, not the amplified level)
       │
       ▼
updateWaveform(timestamp, displayData)
  → called on the Main thread via runOnUiThread
```

### 3.7 Downsampling for Display

The audio buffer arrives at 44,100 Hz. Plotting every sample would be too dense and slow. Instead, every **44th sample** is taken:

```kotlin
const val DOWNSAMPLE_STEP = 44
// 44100 / 44 ≈ 1002 display points per second
```

**X position is sample-accurate** — it uses a monotonically incrementing counter, not the timestamp argument (which can have jitter):

```kotlin
val currentX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE  // seconds
waveformEntries.add(Entry(currentX, data[i]))
totalSamplesProcessed += DOWNSAMPLE_STEP
```

This gives jitter-free X positioning regardless of buffer callback timing irregularities.

### 3.8 Page-Based Camera (Scrolling)

X grows indefinitely. The camera doesn't smoothly scroll; instead it **snaps** to the start of each 10-second page:

```
Page 0: camera shows  0s – 10s
Page 1: camera shows 10s – 20s
Page 2: camera shows 20s – 30s
...
```

```kotlin
val latestX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
val currentPage = (latestX / 10f).toInt()
val currentViewX = currentPage * 10f
chart.moveViewToX(currentViewX)
```

The waveform **scrolls from left to right** within the current 10s page, then the camera jumps to the next page.

### 3.9 Y-Axis Auto-Scaling (Two-Phase)

The Y-axis adapts to the signal level using a warmup mechanism to avoid a jarring initial scale jump.

**Constants:**

| Constant | Value | Purpose |
|---|---|---|
| `WARMUP_MS` | 2000 ms | Duration to observe the signal before locking scale |
| `HEADROOM` | 1.5× | Y-axis is set to `peak × 1.5` so waveform uses ~65% of height |
| `MIN_PEAK` | 0.02 | Minimum half-range; prevents over-zoom on near-silence |

**Phase 1 — Warmup (first 2 seconds):**
- Y-axis stays at `±1.0`
- The app accumulates the highest absolute sample value seen: `warmupPeak = max(warmupPeak, bufferPeak)`

**Phase 2 — Locked (after 2 seconds):**
- `peakAmplitude = (warmupPeak × 1.5).coerceIn(0.02f, 1.0f)`
- `chart.axisLeft.axisMinimum = -peakAmplitude`
- `chart.axisLeft.axisMaximum = +peakAmplitude`
- The scale **never changes again** for the rest of the recording session, even if signal gets louder. The axis only expands during the warmup window.

**Visual result:** The graph auto-fits to the stethoscope's signal level in the first 2 seconds, then stays fixed — no flickering or unexpected re-scaling.

### 3.10 Dataset Management (No Blank Frame Bug)

MPAndroidChart resets internal state when `chart.data` is replaced, causing a blank frame. To avoid this, a **single persistent `LineDataSet`** is reused across all callbacks:

```kotlin
// First callback → create dataset and assign
waveformDataSet = LineDataSet(snapshot, "Waveform").apply { ... }
chart.data = LineData(waveformDataSet)

// Every subsequent callback → update values in place
waveformDataSet.values = snapshot
chart.data?.notifyDataChanged()
chart.notifyDataSetChanged()
```

`chart.data` is only replaced on reset/idle transitions.

### 3.11 Line Style

```kotlin
color = R.color.waveform_blue   // #2D7DD2 (blue)
lineWidth = 1.5f
mode = LineDataSet.Mode.LINEAR  // straight lines between points
setDrawCircles(false)           // no dots at data points
setDrawValues(false)            // no value text labels
setDrawHighlightIndicators(false)
```

### 3.12 Memory Cleanup

To prevent unbounded memory growth during long recordings, data outside the current and previous 10-second page is deleted:

```kotlin
val minXToKeep = (currentPage - 1) * 10f
// Remove all entries with X < minXToKeep
```

**At any point in time, at most ~20 seconds of data is held in memory.**

### 3.13 State Transitions

| UI State | Chart Behaviour |
|---|---|
| `IDLE` | Dummy transparent line; grid visible; touch disabled |
| `RECORDING` | Live data streaming; page-scroll camera; touch disabled |
| Reset to IDLE | All entries cleared; dummy data re-applied; camera reset to X=0 |

---

## 4. Player Screen Graph

### 4.1 Layout Positioning

Same full-width, height-fill-constraint layout. A loading `ProgressBar` sits as an overlay (initially `gone`).

```
[Top Bar]
[Timer]
[Amp Slider Card]
  ↕
[waveformChart]      ← fills available height
  (ProgressBar overlay, hidden until file loads)
  ↕
[Action text]
[Play button]
[Save / Discard bar]
```

### 4.2 Chart Configuration

```kotlin
chart.description.isEnabled = false
chart.legend.isEnabled = false
chart.setDrawGridBackground(true)
chart.setGridBackgroundColor(Color.WHITE)
// Touch is ENABLED — user can drag and zoom
```

### 4.3 Axes

**X-Axis**

| Setting | Value |
|---|---|
| Position | BOTTOM |
| Grid lines | `#F0F0F0`, width 1f |
| Labels | Hidden |
| Axis line | Hidden |

**Y-Axis (Left)**

| Setting | Value |
|---|---|
| Grid lines | `#F0F0F0`, width 1f |
| Initial range | `−0.5` to `+0.5` (narrower than recording; WAV file is already filtered/normalized) |
| Labels | Hidden |
| Axis line | Hidden |

**Y-Axis (Right)**: disabled.

### 4.4 Initial Viewport

```kotlin
chart.setVisibleXRangeMaximum(4f)              // shows 4 seconds at a time
chart.centerViewTo(2f, 0f, AxisDependency.LEFT) // initial view: 0s–4s
chart.setTouchEnabled(true)
chart.isDragEnabled = true
chart.setScaleEnabled(true)
```

The player shows **4 seconds** at a time (vs 10s for recording). The user can pan and zoom.

### 4.5 Waveform Loading (One-Shot from File)

The waveform is loaded **once** from the WAV file on the IO thread, then rendered on Main:

```kotlin
viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
    val bytes = File(filePath).readBytes()
    // ... decode → entries
    withContext(Dispatchers.Main) {
        renderWaveformEntries(entries, durationSecs)
    }
}
```

**WAV header parsing:**

The sample rate is read from the WAV header bytes 24–27 (little-endian), **not hardcoded**. This is critical because AI-testing files are at 8kHz — a hardcoded 44100 would show them with the wrong duration.

```kotlin
val fileSampleRate = ((bytes[24] and 0xff)
                    or (bytes[25] and 0xff shl 8)
                    or (bytes[26] and 0xff shl 16)
                    or (bytes[27] and 0xff shl 24)).toFloat()
```

**Sample decoding (16-bit PCM little-endian → float):**

```
bytePos = 44 + i * 2          // skip 44-byte WAV header
low  = bytes[bytePos]  & 0xFF
high = bytes[bytePos+1] << 8
sampleInt = (high | low) as Short
sampleFloat = sampleInt / 32768f   // normalize to [-1.0, 1.0]
```

**X value:** `i.toFloat() / fileSampleRate` (in seconds)

### 4.6 Downsampling for Display

The entire WAV is downsampled to at most **3,000 points** for display:

```kotlin
val maxPoints = 3000
val step = maxOf(1, totalSamples / maxPoints)
// Pick 1 sample every `step` samples
```

For a 10-second 44100Hz file: `step = 441000/3000 ≈ 147` — every 147th sample is taken.

### 4.7 Line Style

```kotlin
color = Color.parseColor("#2D7DD2")   // same blue as recording
lineWidth = 2.5f                       // slightly thicker than recording (1.5f)
mode = LineDataSet.Mode.LINEAR
setDrawCircles(false)
setDrawValues(false)
```

### 4.8 Playback Head Tracking

During playback, `onPlaybackProgress` fires on every audio buffer. The chart scrolls to follow the playhead:

```kotlin
onPlaybackProgress = { timestamp, _ ->
    val visibleRange = chart.visibleXRange     // 4f default
    val halfRange = visibleRange / 2f          // 2f

    // At the very start, anchor left edge at 0 (don't go negative)
    val centerX = if (timestamp < halfRange) halfRange else timestamp.toFloat()

    chart.centerViewTo(centerX, 0f, AxisDependency.LEFT)
}
```

**Effect:** The waveform scrolls so the current playback position is always **centred** in the chart. At the very beginning (0–2s), the left edge stays locked at 0 to avoid showing negative time on the left.

### 4.9 On Stop / Complete / Play-Start

All three events snap the view back to the beginning of the waveform:

```kotlin
chart.centerViewTo(chart.visibleXRange / 2f, 0f, AxisDependency.LEFT)
```

---

## 5. Side-by-Side Comparison

| Property | Recording Screen | Player Screen |
|---|---|---|
| Data source | Live audio (TAAL USB device) | WAV file read from storage |
| Update frequency | Per audio buffer callback (~50ms) | One-shot load, then playhead updates |
| Visible window | **10 seconds** fixed | **4 seconds** default (user can zoom) |
| Touch / drag | Disabled | Enabled (pan + scale) |
| Camera movement | Page-snap every 10s (`moveViewToX`) | Continuous follow (`centerViewTo`) |
| Y-axis range | Two-phase: ±1.0 warmup → auto-fit after 2s | Fixed ±0.5 |
| Display points | ~1002 / sec (DOWNSAMPLE_STEP = 44) | Up to 3000 total for entire file |
| Line width | 1.5f | 2.5f |
| Line color | `#2D7DD2` | `#2D7DD2` |
| Memory cleanup | Rolling 20s window | No cleanup (entire file in memory) |
| Dataset strategy | Persistent reuse (no `chart.data` replace) | Single assign on load |

---

## 6. Key Constants Summary

| Constant | Location | Value | Meaning |
|---|---|---|---|
| `WINDOW_SECONDS` | RecordingFragment | `10f` | Seconds visible in recording view |
| `INPUT_SAMPLE_RATE` | Both fragments | `44100f` | Audio sample rate |
| `DOWNSAMPLE_STEP` | RecordingFragment | `44` | Take 1 in every 44 samples for live display |
| `WARMUP_MS` | RecordingFragment | `2000` | Warmup window in milliseconds |
| `HEADROOM` | RecordingFragment | `1.5f` | Y-axis headroom multiplier post-warmup |
| `MIN_PEAK` | RecordingFragment | `0.02f` | Minimum Y-axis half-range |
| `maxPoints` | PlayerFragment | `3000` | Max display points for static waveform |
| Player visible range | PlayerFragment | `4f` | Seconds visible in player view |

---

## 7. Colors

| Color name | Hex | Used for |
|---|---|---|
| `waveform_blue` | `#2D7DD2` | Waveform line (recording) |
| (literal) | `#2D7DD2` | Waveform line (player) |
| (literal) | `#F0F0F0` | Grid line color (both) |
| (literal) | `#FFFFFF` | Chart background (both) |

---

## 8. Pre-Amp & Graph Relationship

The TaalRecorder applies a **pre-amplification gain** (0–30 dB) to the audio before writing the WAV file AND before the display callback fires.

**The graph always undoes this gain** so it shows the raw acoustic signal level, not the boosted level:

```
graphSample = audioSample / 10^(preAmpDb / 20)
```

This means:
- The **WAV file** stored on disk contains the amplified audio.
- The **graph on screen** during recording reflects true acoustic amplitude.
- The **player graph** is loaded directly from the WAV bytes (which already have amp applied) without any correction — the player graph shows the amplified signal as stored.

---

## 9. Notable Rules / Design Decisions

1. **Y-axis never expands after warmup.** Only the warmup window sets the scale. If the signal gets louder mid-recording, the waveform clips visually but the file is unaffected.

2. **X-axis uses sample counter, not timestamp.** Using the `timeStamp` argument from the audio callback introduces jitter. `totalSamplesProcessed / sampleRate` is perfectly uniform.

3. **Page-snap, not smooth scroll.** At 10-second boundaries the camera jumps instantly (`moveViewToX`). This is intentional — smooth scrolling at ~1002 pts/s would cause lag on the Main thread.

4. **Persistent dataset reuse.** Replacing `chart.data` every callback triggers MPAndroidChart to re-calculate internal offsets and causes a one-frame blank. The fix is `ds.values = snapshot` + `notifyDataChanged()`.

5. **Touch disabled during recording.** The user cannot pan or zoom while live data is streaming. After stopping and navigating to the Player screen, full touch is restored.

6. **Player reads sample rate from the WAV header.** Never hardcoded to 44100. AI-testing files may be at 8kHz, 4kHz, etc. — hardcoding would display wrong durations.

7. **Rolling 20s memory window.** Keeps at most current + previous 10s page. Prevents out-of-memory errors on recordings longer than a few minutes.

8. **Filter cannot be changed mid-recording.** Filter buttons are disabled (alpha dimmed to 0.4) once recording starts. The pre-set filter is set once at `startRecording()`.
