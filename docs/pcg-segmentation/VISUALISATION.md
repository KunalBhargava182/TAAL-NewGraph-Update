# Displaying the Segmentation

How to draw a PCG recording with its S1 / systole / S2 / diastole overlay. Written for the engineer
wiring it into the app.

---

## What ships

| | |
|---|---|
| `PcgDisplay` / `PcgDisplayModel` | Turns audio + a `SegmentationResult` into drawable geometry, in normalised 0..1 coordinates. Pure Kotlin, in `core`, no Android dependency. |
| `SegmentationPalette` | The four state colours, light and dark, as ARGB ints. Also in `core`. |
| `PcgSegmentationView` | A plain Android `View` that draws it, legend included. No new dependencies. |
| `sample/PcgChartCompose.kt` | Compose usage — a three-line wrapper, and a native Canvas version. |

The split matters: **all the geometry lives in `core`**, so the Android view, a Compose canvas, an
exported SVG and a print report cannot drift apart, and the arithmetic that actually breaks is
unit-tested on the JVM rather than eyeballed on a device. There are 12 tests covering it.

---

## The shortest path

```kotlin
val chart = PcgSegmentationView(context)
chart.setRecording(audio, sampleRate = 44_100, result = segmentationResult)
```

Or from a layout:

```xml
<com.purnacardio.signal.pcg.android.PcgSegmentationView
    android:id="@+id/pcgChart"
    android:layout_width="match_parent"
    android:layout_height="200dp" />
```

```kotlin
findViewById<PcgSegmentationView>(R.id.pcgChart)
    .setRecording(audio, 44_100, result)
```

That is the whole integration. It picks light or dark from the device configuration, draws the
legend, and handles resize and rotation.

## End to end, from a capture

```kotlin
val runner = TcnSegmenterRunner(context)          // build once, keep it
val audio  = Pcm.toFloat(pcm16)                   // mono; scale is irrelevant

when (val result = withContext(Dispatchers.Default) { runner.segment(audio, 44_100) }) {
    null -> showRetakePrompt("No heart sounds detected")
    else -> {
        chart.setRecording(audio, 44_100, result)

        // The chart can also tell you about capture quality: a large clipped fraction means one
        // transient dominates the recording — usually the phone or stethoscope knocking the chest.
        val clipped = chart.displayModel()?.clippedFraction ?: 0f
        if (clipped > 0.05f) showHint("Recording was bumped — consider a retake")
    }
}
```

**Pass the same `audio` you passed to `segment`.** The overlay is aligned by time, so a different
array — resampled, trimmed, or a different channel — puts the bands in the wrong place while still
looking plausible.

## Compose

```kotlin
AndroidView(
    modifier = Modifier.fillMaxWidth().height(200.dp),
    factory = { PcgSegmentationView(it) },
    update = { it.setRecording(audio, sampleRate, result) }
)
```

The SDK ships a `View` rather than a composable on purpose: it adds no Compose dependency for apps
that do not use Compose, and it works in XML layouts, Compose, and a `RecyclerView` row alike. If
you want the chart to participate in Compose gestures or animation, `sample/PcgChartCompose.kt`
has a native `Canvas` implementation that draws from the same display model.

---

## The colours, and why they are what they are

Four states, four colours, defined once in `SegmentationPalette` for both light and dark.

| State | Role | Treatment |
|---|---|---|
| **S1** | first heart sound | deep clinical blue, stronger wash, accent rule on top |
| **Systole** | interval | warm ochre, mid wash |
| **S2** | second heart sound | deep teal-green, stronger wash, accent rule on top |
| **Diastole** | interval | near-neutral slate, faintest wash |

**Desaturated, not primary.** Saturated red/green/blue reads as a toy, and red specifically carries
an alarm meaning in a clinical interface that is wrong here — S1 is not an error. These sit in the
register of printed medical illustration.

**The sounds outrank the intervals.** S1 and S2 are events and carry the stronger fills; systole and
diastole are the gaps between them and are washes. Four equal-weight bands produce a stripey
background that fights the trace instead of annotating it.

**Systole is the interval that gets the warmer colour**, because it is the one a reader is usually
looking into — it is where a murmur lives. Diastole recedes to near neutral.

**Colour is never the only cue.** The two heart sounds also carry a 2 dp accent rule along the top
of their bands. That keeps S1 and S2 apart in greyscale, on a washed-out projector, and for a reader
with a colour vision deficiency, where the blue and the teal-green can otherwise converge. The cycle
order S1 → systole → S2 → diastole is fixed, and the legend is drawn by default.

### Overriding them

```kotlin
chart.palette = SegmentationPalette.light          // pin, rather than follow the system
```

To match a house style, construct your own `SegmentationPalette.Variant`. Keep the two properties
above — sounds heavier than intervals, and a non-colour cue for S1 vs S2 — or the chart stops being
readable for a chunk of users.

---

## Windowing, zoom and scrubbing

```kotlin
chart.setWindowSeconds(startSec = 4.0, endSec = 9.0)
```

The chart redraws from the original audio every time, so zooming in reveals real detail instead of
stretching pixels. Wire this to a scrubber or a pinch gesture and it behaves as expected.

## Long recordings

`setRecording` walks the audio once to build the display model — a few milliseconds for a 20 s
capture, fine on the main thread. For a multi-minute recording, or if you rebuild on every frame of
a pinch gesture, build it off the main thread and hand it over:

```kotlin
val model = withContext(Dispatchers.Default) {
    PcgDisplay.build(audio, sampleRate, result, columns = chart.width,
                     windowStartSec = start, windowEndSec = end)
}
chart.setDisplayModel(model)
```

## Three things the display model handles that a naive chart does not

Each of these produces a chart that looks fine and is wrong, which is why they are in `core` behind
tests rather than left to the view:

1. **Decimation.** A 20 s capture at 44.1 kHz is ~880,000 samples against maybe 1,000 pixels.
   Drawing every Nth sample throws away peaks — and in a PCG the peaks *are* S1 and S2, so the
   heart sounds visibly shrink as the view narrows. Each column keeps the true min and max of its
   samples, so the envelope survives at any width.

2. **Amplitude scaling.** Scaling to the loudest sample hands the chart to one transient: a knock
   flattens the heartbeat to a hairline. The model scales to the 99th percentile and lets the rest
   clip, reporting how much clipped via `clippedFraction`.

3. **Rate conversion.** Three timebases meet in this chart — the audio's sample rate, the
   segmenter's 200 Hz state frames, and the 2 kHz indices its peak markers use. Confusing them
   offsets the bands from the waveform by a constant factor, which reads to a reviewer as "your
   model is inaccurate".

## Accessibility

The view supplies a `contentDescription` naming the duration and the number of cycles shown. If you
present the chart as a clinical finding, add your own summary alongside it — a screen reader user
cannot read band positions off a canvas, and the segmentation's value is in the numbers
(`numCycles`, onsets, intervals) as much as the picture.

---

## Reference

**`PcgDisplay.build(audio, sampleRate, result, columns, windowStartSec, windowEndSec)`**
→ `PcgDisplayModel`. Pass `columns = view width in pixels`.

**`PcgDisplayModel`** — `top` / `bottom` (waveform edges per column, y 0..1), `bands`
(`state`, `xStart`, `xEnd`), `s1MarkersX` / `s2MarkersX`, `startSec`, `endSec`, `clippedFraction`,
`isEmpty`.

**`PcgSegmentationView`** — `setRecording`, `setDisplayModel`, `setWindowSeconds`, `displayModel()`,
`palette`, `showLegend`, `showSecondGrid`.

**`SegmentationPalette`** — `light`, `dark`, `labels`, `longLabels`, `isSound`, `legend(variant)`.
