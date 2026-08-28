# Heart-Sound (PCG) Segmentation Feature — Porting Guide

**How to use this file:** this is a self-contained implementation spec, not a history log. Hand it
to a fresh Claude Code session together with a pointer to *this* repo (`TaalDemoApp`) as the
reference implementation, in some other Android project, and say "read this and add this feature
to the current recording/playback flow of this app." Everything needed to understand what to copy,
what to build fresh, and every non-obvious gotcha that cost real debugging time the first time is
below. Do not try to regenerate the ML pieces from this description — they must be copied, see §1.

---

## 0. What this feature is, in one paragraph

A user records heart sounds through a stethoscope/mic into a WAV file. On a screen reviewing an
already-saved recording, one button ("Analyze Heart Sounds") runs the **raw, unfiltered** audio
through an on-device ONNX neural network (a TCN — temporal convolutional network) that finds each
cardiac cycle's S1 and S2 sound and the systole/diastole intervals between them. The result is
shown as a colour-banded waveform chart with Full/10s/5s zoom, four stat tiles (heart rate, cycles
detected, duration, average systolic interval), a trustworthiness verdict, and a one-tap PDF
export. Everything is gated behind a single boolean flag so it can be hidden completely with one
line changed.

---

## 1. Non-negotiable: copy these two Gradle modules verbatim, do not rewrite them

The segmentation algorithm is a **third-party ported SDK** (originally from PurnaCardio) plus a
**trained ONNX model file**. Neither can be regenerated from a text description — an LLM cannot
recreate a trained neural network's weights, and the algorithm code is intentionally treated as
frozen even in the source repo (never edited there either). This is the one step in this guide
that is a file copy, not a from-scratch build.

Copy these two directories, byte-for-byte, from the `TaalDemoApp` repo into the target project's
root (as sibling Gradle modules to its `app` module):

- **`taal-segmentation-core/`** — pure `kotlin("jvm")` module, package `com.purnacardio.signal.pcg`.
  No Android dependency at all. Contains `CardiacSegmenter.kt`, `PcgFeatureExtractor.kt`, `Pcm.kt`,
  `S1Result.kt`, `SegmentationResult.kt`, plus `viz/PcgDisplayModel.kt` and
  `viz/SegmentationPalette.kt` (the chart's data-shaping and colour-palette layer). Has its own
  unit test suite (`src/test/kotlin/...`) — after copying, run its tests standalone
  (`./gradlew :taal-segmentation-core:test`) before doing anything else, to prove the copy is
  intact before any project-specific wiring is added on top.
- **`taal-segmentation/`** — Android library module (`minSdk = 26`), package
  `com.purnacardio.signal.pcg.android` for the low-level runner/view, and
  `com.musediagnostics.taal.segmentation` for the bridge class you'll actually call. Contains
  `TcnSegmenterRunner.kt`, `PcgSegmentationView.kt` (the chart custom `View`), and
  `TaalCardiacSegmentation.kt` (see §4 — this is the one file whose *usage* you need to understand
  deeply, even though its contents are also copied as-is). Critically, also contains
  **`src/main/assets/tcn_c200_cardiac_seg.onnx`** — the trained model, shipped as a module asset so
  any consumer that depends on this module gets it automatically via the AAR; no extra asset-copy
  step is needed in the target project.

Do **not** rename the `com.purnacardio.signal.pcg*` packages, and do not try to "clean up" or
re-architect these two modules as part of a port — they are intentionally frozen even in the
source repo.

### Gradle wiring in the target project

`settings.gradle.kts`:
```kotlin
include(":taal-segmentation-core")
include(":taal-segmentation")
```

The consuming app module's `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":taal-segmentation"))
}
```
That's the only dependency line needed — `taal-segmentation`'s own `build.gradle.kts` declares
`api(project(":taal-segmentation-core"))` and `api("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")`
as `api` (not `implementation`), specifically so the consuming app resolves both transitively
without declaring them itself.

### Hard requirements to check before wiring

- **`minSdk` ≥ 26** for any module (including the consuming app) that will call into
  `taal-segmentation` — its own `minSdk` floor is 26. If the target app's `minSdk` is lower, that's
  a real decision (raise the app's floor, or accept the feature is unavailable below 26) — surface
  it to the user rather than silently bumping `minSdk` project-wide.
- **ONNX Runtime version is a floor, not a preference: `1.19.2`, never lower.** The model is
  exported at ONNX IR version 10; `onnxruntime-android` 1.17.x supports at most IR 9. Below 1.19.2,
  `OrtSession` construction throws `ORT_INVALID_ARGUMENT` on every single recording,
  `TcnSegmenterRunner` fails to construct, and — because the bridge follows the recommended
  `runCatching { ... }.getOrNull()` pattern — the failure is silently swallowed and every capture
  reports "Segmentation unavailable" with no further explanation in the UI. This looks exactly like
  a capture-quality problem and is easy to chase down the wrong path. If you ever need a different
  pin, verify the model's IR version first (`od -An -tu1 -N4 tcn_c200_cardiac_seg.onnx`) and require
  `>= 1.18`.
- Repositories: only `google()` and `mavenCentral()` are needed for these two modules specifically
  (no special repo required for ONNX Runtime).
- JVM target 17 for both modules.

---

## 2. The critical rule that governs everything downstream: feed it RAW audio, never filtered

If the target app's recording pipeline applies any DSP before saving (bandpass filter, graphic EQ,
pre-amp gain baked into the samples, noise reduction, etc.), **the segmenter must be fed the
pre-DSP audio**, not the version the user hears on playback or sees on the waveform chart. The
segmenter's own feature extractor already band-passes ~20-200 Hz internally as part of its
training assumptions; running it on already-filtered audio shifts the input distribution away from
what the model was trained on and degrades results in a way that is not obviously wrong (it will
still produce *a* result, just a worse one).

**Practical implication for the port:** find out how the target project's recording pipeline
stores audio. If it only ever produces and keeps one (already-processed) file, that is a real gap
to flag to the user before proceeding — either the pipeline needs to start persisting an
unfiltered copy alongside the processed one (as this repo does — see the `_raw.wav` /
`_filtered.wav` pair in `TaalDemoApp`'s own recording flow), or the segmenter will be running on
suboptimal input. Do not silently wire the button to whatever file happens to be at hand.

The bridge class itself only defends against the *filename* signalling the mistake — if a file
whose name contains `_filtered` is passed to `segmentRawWav`, it logs a warning but still processes
it (it cannot know your project's naming convention, so this is a courtesy check, not a guarantee):

```kotlin
private fun warnIfFilteredFile(file: File) {
    if (file.name.contains("_filtered", ignoreCase = true)) {
        Log.w(TAG, "... looks like a FILTERED recording. Feed the RAW file instead ...")
    }
}
```

---

## 3. Step-by-step porting procedure

1. Copy `taal-segmentation-core/` and `taal-segmentation/` into the target project (§1). Wire
   `settings.gradle.kts` and the app module's `build.gradle.kts`. Build and run both modules' own
   test suites standalone — do not proceed to app-layer work until they pass unmodified.
2. Confirm (or establish) that the target project's recording pipeline persists an unfiltered raw
   audio file that survives at least as long as the processed/playback file does (§2). This is a
   prerequisite, not an app-UI detail — do this before writing any UI code.
3. Add a one-flag feature toggle in the target project's own package, e.g.:
   ```kotlin
   object SegmentationFeature {
       const val ENABLED = true
   }
   ```
   Every other piece of app-layer code checks this once, at the earliest point the feature would
   become visible (the entry-point button, §5) — not scattered across multiple call sites. This
   lets the whole feature be hidden with a one-line change and zero file reverts.
4. Build the bridge usage pattern (§4) into whichever screen reviews a saved recording.
5. Add the entry-point button (§5) — placement matters, read that section before guessing at a
   layout position.
6. Build the report screen (§6) and, optionally, the full-screen landscape variant (§7).
7. Wire PDF export (§8) if the target project wants a downloadable report.
8. Work through the gotchas in §9 as you go — each one was found live, on-device, not by
   inspection, and each cost real debugging time the first time.
9. Verify per the checklist in §10.

---

## 4. The bridge: `TaalCardiacSegmentation`

One class, `com.musediagnostics.taal.segmentation.TaalCardiacSegmentation(context: Context)`,
copied as-is inside `taal-segmentation`. It is `Closeable`; construct one per screen visit, call
`close()` in `onDestroyView`. It owns a `TcnSegmenterRunner` (built lazily, ~100ms cost, reused,
not thread-safe — the bridge serialises calls internally via a `Mutex`, so callers never need their
own dispatcher hop or locking).

Two entry points — use the file-based one unless you already have decoded PCM in hand:

```kotlin
suspend fun segmentRawWav(rawWavFile: File, verboseLogging: Boolean = false): SegmentationOutcome
suspend fun segment(pcm16: ShortArray, sampleRate: Int, channelCount: Int = 1, verboseLogging: Boolean = false): SegmentationOutcome
```

Both are suspend functions that internally dispatch onto `Dispatchers.Default` — call them from
any coroutine scope (e.g. `viewLifecycleOwner.lifecycleScope.launch { ... }`) without your own
`withContext`.

### `SegmentationOutcome` — four states, all must be handled

```kotlin
sealed interface SegmentationOutcome {
    data class Ok(val result: SegmentationResult) : SegmentationOutcome        // trustworthy
    data class TooWeak(val result: SegmentationResult) : SegmentationOutcome   // decoded, but low confidence
    data object NoHeartSounds : SegmentationOutcome                            // no legal cycle found — genuine result, not an error
    data object Unavailable : SegmentationOutcome                              // engine failed to init this session (e.g. ONNX floor not met)
}
```

`Ok` vs `TooWeak` is decided by a density heuristic, not a model-reported confidence score (the
model never populates one):

```kotlin
result.numCycles < result.durationSec / 2.0 -> TooWeak   // fewer than ~1 cycle per 2s of audio
else -> Ok
```

Why a heuristic instead of trusting any non-null result at face value: the decoder imposes a legal
S1→systole→S2→diastole cycle structure on *whatever* it's given, so a low-but-nonzero cycle count
is not proof the recording contains real heartbeats — it can be the decoder finding "a legal
structure" in noise. Cycle density relative to duration is the honest proxy used instead.

`SegmentationResult` (from `taal-segmentation-core`, not `Parcelable` — see §7 for why that
matters) exposes `numCycles`, `durationSec`, and peak-sample arrays at an internal 2000 Hz
timebase. Two derived extension properties do the useful math for you — use these rather than
re-deriving from the raw peak arrays:

```kotlin
val SegmentationResult.heartRateBpm: Double?          // null if fewer than 2 beats
val SegmentationResult.systolicIntervalsMs: List<Double>  // S1-peak-to-S2-peak per cycle, ms
```

---

## 5. The entry-point button: placement matters

**Lesson learned the hard way, worth repeating:** the first version of this button was a
full-width `<Button>` pinned to the bottom of the review screen with
`app:layout_constraintBottom_toBottomOf="parent"` — the same anchor a "Save/Discard" bar used, on
the (false) assumption that since the two are never visible at the same time, they'd never
conflict. In practice, the screen's large circular play button was constrained *relative to the
Save/Discard bar*, not the new button, so when the Save/Discard bar was hidden (exactly the
situation whenever this button is shown), the play button had no defined spatial relationship to
this button at all — they visually overlapped despite each one's own constraints being internally
consistent.

**Recommended pattern instead:** add the entry point as a small icon-only `ImageButton` in the
screen's existing top app bar / toolbar, alongside any other icon actions already there (this repo
places it where an existing-but-unused equalizer icon sits). This guarantees no overlap *by
construction* — it occupies a completely different region of the screen than the waveform chart,
play button, and any save/discard controls — rather than by a margin calculation that can silently
break again if a sibling view's size or visibility ever changes. It also reads well: a toolbar icon
is the conventional home for a secondary/optional action on a detail screen, while the primary
playback control keeps the visual centre of the screen.

```xml
<ImageButton
    android:id="@+id/analyzeButton"
    android:layout_width="40dp"
    android:layout_height="40dp"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:contentDescription="Analyze Heart Sounds"
    android:padding="6dp"
    android:src="@drawable/ic_heart"
    android:tint="#128CB2"
    android:visibility="gone"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintEnd_toEndOf="parent"
    app:layout_constraintTop_toTopOf="parent" />
```

If the target screen has no top bar at all, pick any region that is structurally isolated from the
screen's primary controls (e.g. a small chip below existing metadata text) — the general principle
is: don't share an anchor with a view whose own visibility toggles independently of this button's.

### Gating logic

Show the button only when **both** conditions hold — checked by directory, not just an
"is this a fresh recording" flag, since some navigation paths reach a review screen without
reliably setting such a flag:

```kotlin
if (SegmentationFeature.ENABLED) {
    val savedDir = File(requireContext().filesDir, "saved")
    val isInSavedDir = File(filePath).parentFile?.absolutePath == savedDir.absolutePath
    if (isInSavedDir /* && the raw companion actually resolves for this filePath */) {
        val rawPath = /* however the target project maps a playback file to its raw counterpart */
        if (File(rawPath).exists()) {
            binding.analyzeButton.visibility = View.VISIBLE
            binding.analyzeButton.setOnClickListener {
                val bundle = Bundle().apply { putString("rawFilePath", rawPath) }
                findNavController().navigate(/* action to the report screen */, bundle)
            }
        }
    }
}
```

Never show it for a recording that is still a temp/in-progress file — the raw companion is only
guaranteed to exist once the recording has actually been persisted to permanent storage.

---

## 6. The report screen

Reached from the entry-point button with one nav argument: the raw file's path. On
`onViewCreated`, decode that file to a `FloatArray` for the chart (any simple 16-bit-PCM WAV
reader; sample rate must be read from the WAV header, not assumed), and in parallel call
`segmentRawWav` on it:

```kotlin
segmenter = TaalCardiacSegmentation(requireContext().applicationContext)
viewLifecycleOwner.lifecycleScope.launch {
    val (audio, sampleRate) = withContext(Dispatchers.Default) { readWavAsFloatArray(rawFile) }
    val outcome = segmenter?.segmentRawWav(rawFile, verboseLogging = true)
    if (_binding == null || outcome == null) return@launch
    showResult(outcome, audio, sampleRate)
}
```

Pass the *same* `audio` array to both the segmenter and the chart — the chart overlay is aligned
by sample index/time, so decoding the file twice with any difference in framing would visibly
misplace the coloured bands while still looking plausible (a bug that's easy to miss without
side-by-side comparison against known-good output).

### Layout structure that works well

- **Status row**: icon + headline + optional subtext, one variant per `SegmentationOutcome` case.
  Exact copy used in the reference implementation (adapt tone to the target project, keep the
  distinctions):
  - `Ok` → green check icon, headline **"Trustworthy segmentation"**, no subtext.
  - `TooWeak` → orange info icon, headline **"Low confidence"**, subtext *"Few complete cycles
    relative to duration — consider retaking with better placement."*
  - `NoHeartSounds` → muted info icon, headline **"No heart sounds detected"**, subtext *"Please
    retake — check stethoscope placement and pre-amp gain."*
  - `Unavailable` → muted info icon, headline **"Segmentation unavailable"**, subtext *"The
    segmentation engine could not initialise on this device this session."*
- **Chart card**: `PcgSegmentationView` (from `taal-segmentation`) wrapped in a card. Public API:
  ```kotlin
  var palette: SegmentationPalette.Variant   // e.g. SegmentationPalette.light for print/PDF contexts
  fun setRecording(audio: FloatArray, sampleRate: Int, result: SegmentationResult?)
  fun setWindowSeconds(startSec: Double = 0.0, endSec: Double = Double.MAX_VALUE)
  ```
  Only render this card, and the stat tiles/download button below, when `result != null` (i.e. for
  `Ok` and `TooWeak` — `NoHeartSounds`/`Unavailable` have nothing to plot).
- **Full/10s/5s zoom control** — three buttons, each calling a `setZoom(sizeSec, button)` that
  **re-centres on whatever is currently in view rather than jumping back to the start**:
  ```kotlin
  private fun setZoom(sizeSec: Double, selectedButton: Button) {
      if (resultDurationSec <= 0) return
      val clampedSize = sizeSec.coerceIn(1.0, resultDurationSec)
      val center = currentWindowStart + currentWindowSizeSec / 2.0
      val maxStart = (resultDurationSec - clampedSize).coerceAtLeast(0.0)
      val newStart = (center - clampedSize / 2.0).coerceIn(0.0, maxStart)
      currentWindowSizeSec = clampedSize
      currentWindowStart = newStart
      binding.pcgChart.setWindowSeconds(newStart, newStart + clampedSize)
      markZoomSelected(selectedButton)
  }
  ```
  Pair with a visible selected-state indicator (filled pill + white text on the active button,
  transparent + muted text on the others) so the user can always see which zoom level is active —
  default to "Full" selected on load.
- **Stat tiles** (2×2 grid works well): Heart Rate (`result.heartRateBpm`, "—" if null), Cycles
  Detected (`result.numCycles`), Duration (`result.durationSec`), Avg Systolic Interval
  (`result.systolicIntervalsMs.average()`, "—" if empty).
- **Download button** — see §8. Visible only when `result != null`.
- A brief disclaimer footer ("research/test output only — not a medical device," or equivalent
  wording appropriate to the target project) is strongly recommended given this is not a certified
  diagnostic tool.

---

## 7. Optional: full-screen landscape variant

Only build this if the target project wants a dedicated immersive/landscape chart view reached
from the report screen. The one real complication: **`SegmentationResult` is not `Parcelable`**
(it lives in the pure-JVM `-core` module, which has no reason to add an Android dependency for
that), so it cannot be passed as a navigation argument across the Activity recreation that a
manifest-locked-orientation app undergoes when forcing landscape. Use an **activity-scoped
ViewModel** instead — it survives configuration-change recreation as long as the Activity isn't
finished:

```kotlin
class SegmentationViewModel : ViewModel() {
    var audio: FloatArray? = null
    var sampleRate: Int = 0
    var outcome: SegmentationOutcome? = null
    var originalOrientationForFullScreen: Int? = null
}
```

Both the report fragment and the full-screen fragment obtain it via `by activityViewModels()`; the
report fragment populates `audio`/`sampleRate`/`outcome` right before navigating, and the
full-screen fragment reads them back instead of re-running inference.

Orientation lock/restore pattern, with two non-obvious details:

```kotlin
override fun onResume() {
    super.onResume()
    // Captured only once — the ViewModel survives the very Activity recreation this call can
    // trigger, so on the second+ onResume the value is already set and must not be overwritten
    // with the now-current LANDSCAPE value.
    if (viewModel.originalOrientationForFullScreen == null) {
        viewModel.originalOrientationForFullScreen = requireActivity().requestedOrientation
    }
    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    hideSystemBars()
}

private fun restoreOrientationAndChrome() {
    val original = viewModel.originalOrientationForFullScreen
    if (original != null) {
        // Restore the captured value AS-IS (normally SCREEN_ORIENTATION_PORTRAIT). Do NOT
        // translate a captured PORTRAIT to SCREEN_ORIENTATION_UNSPECIFIED here — see the
        // 2026-08-24 gotcha in §9, this looked like the "more correct" choice and is wrong.
        requireActivity().requestedOrientation = original
        viewModel.originalOrientationForFullScreen = null
    }
    // ... show system bars again
}

override fun onDestroyView() {
    super.onDestroyView()
    // isChangingConfigurations is true when this teardown IS the Activity recreating for the
    // orientation change just requested above — restoring here would immediately fight that lock
    // and loop. Only restore when actually leaving the screen for good.
    if (!requireActivity().isChangingConfigurations) restoreOrientationAndChrome()
    _binding = null
}
```

Immersive chrome: `WindowCompat.setDecorFitsSystemWindows(window, false)` +
`WindowInsetsControllerCompat(window, decorView).hide(WindowInsetsCompat.Type.systemBars())`.

Drag-to-pan (only meaningful when zoomed in, no-op at Full): track `ACTION_DOWN` start position,
on `ACTION_MOVE` convert horizontal drag pixels to seconds via
`currentWindowSizeSec / view.width.toDouble()`, clamp the new window start to
`[0, durationSec - windowSizeSec]`.

---

## 8. PDF export

Uses only the platform `android.graphics.pdf.PdfDocument` — no third-party PDF library. The chart
is rendered by handing `PcgSegmentationView`'s own `draw()` the PDF page's `Canvas` directly (not a
bitmap screenshot), so it's vector-quality and, since it's the exact same view class the app screen
uses, cannot visually drift from what's on screen.

The one non-obvious trick: build an unattached, off-screen instance of the chart view with the
`Context`'s density forced to `DENSITY_DEFAULT` (160dpi / 1.0x), so the view's internal dp-based
sizing maps 1:1 onto PDF points. Without this, a real device's display density (commonly 2.5-3x)
blows up all the chart's internal padding/legend/axis sizing several times past what fits on the
page:

```kotlin
private fun buildOffscreenChart(context: Context, width: Int, height: Int): PcgSegmentationView {
    val config = Configuration(context.resources.configuration)
    config.densityDpi = DisplayMetrics.DENSITY_DEFAULT
    config.fontScale = 1f
    val pdfContext = context.createConfigurationContext(config)

    val chart = PcgSegmentationView(pdfContext)
    chart.palette = SegmentationPalette.light  // print-appropriate variant, not the on-screen dark one

    val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
    val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
    chart.measure(widthSpec, heightSpec)
    chart.layout(0, 0, width, height)
    return chart
}

// ... inside the page draw:
canvas.save()
canvas.translate(marginX, y)
chart.draw(canvas)
canvas.restore()
```

Run the whole PDF build off the main thread (`withContext(Dispatchers.Default)`) — drawing a chart
for a long recording is not instant. For saving: API 29+ use the `MediaStore.Downloads` collection
(no runtime permission needed); API 24-28 write directly into
`Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`, which requires
`WRITE_EXTERNAL_STORAGE` to already be declared/granted elsewhere in the target app for that API
range.

---

## 9. Gotchas — read before you hit them yourself

- **`Theme.MaterialComponents` silently drops `android:background` and `app:drawableStartCompat`
  on plain `<Button>` tags.** Under a MaterialComponents theme, the view-inflater auto-promotes
  plain `<Button>` to `MaterialButton`, which manages its own background via an internal helper and
  does not reliably respect a raw `android:background="@drawable/..."` or
  `app:drawableStartCompat="@drawable/..."` — it silently falls back to the theme's `colorPrimary`
  fill with no icon. This is easy to miss because it can look correct by coincidence (a button
  styled to be solid teal renders as solid teal anyway, since that's also the fallback colour) —
  it only becomes obviously wrong when the intended look differs from `colorPrimary`, e.g. an
  outlined/white button rendering solid-filled instead, or an icon that never appears. **Fix:** use
  an explicit `<com.google.android.material.button.MaterialButton>` tag with `app:backgroundTint`
  for fill colour, `style="@style/Widget.MaterialComponents.Button.OutlinedButton"` +
  `app:strokeColor` for an outlined look, and `app:icon`/`app:iconTint`/`app:iconGravity` (not
  `app:drawableStartCompat`) for a leading icon. Confirmed via pixel-sampling a screenshot, not
  just by inspection — worth doing the same if a button's colour ever looks "suspiciously default."
  If any target-project `<Button>` you touch as part of this port has a custom `android:background`
  that has never been visually verified, treat it as unverified/possibly-coincidental until
  checked.
- **The ONNX Runtime version floor (§1)** — below `1.19.2`, every segmentation attempt silently
  reports "Unavailable" with no error visible in the UI, and it looks exactly like a
  capture-quality problem rather than a dependency-version problem. Check this first if the feature
  never produces a result on a fresh port.
- **Feed the segmenter raw audio, never filtered/processed audio (§2).** The single most
  consequential wiring decision in the whole port, and the easiest one to get subtly wrong if the
  target project's recording pipeline only keeps one processed file.
- **Don't pin the entry-point button to the same anchor as another view whose visibility toggles
  independently (§5).** A shared bottom anchor with a conditionally-hidden sibling looks correct in
  the one state you happen to test and breaks in the other.
- **`SegmentationResult` isn't `Parcelable` — don't try to pass it through nav args or a `Bundle`
  across a configuration change; use an activity-scoped `ViewModel` (§7).**
- **[2026-08-24] `requestedOrientation = SCREEN_ORIENTATION_UNSPECIFIED` does NOT restore the
  manifest's `screenOrientation` lock — it means "no preference," which leaves the whole app free
  to sit in whatever orientation the device is physically in.** An earlier version of this guide's
  §7 sample restored a captured `PORTRAIT` value by translating it to `UNSPECIFIED`, on the theory
  that this would "let the manifest value govern again." In practice, on a real device, that theory
  is wrong: after backing out of the landscape-locked full-screen chart, the whole app was left
  stuck in landscape (the physical orientation the device happened to be in at that moment) instead
  of snapping back to portrait — confirmed live on-device (`stemz-app` port, Samsung SM-A066B,
  Android 16), not by inspection. **Fix:** restore the exact captured `requestedOrientation` value
  as-is (`requireActivity().requestedOrientation = original`) — do not special-case `PORTRAIT` into
  `UNSPECIFIED`. §7's code sample above has been corrected to match; if you ported this feature
  before this date using the old sample, apply the same one-line fix.
- **Guard orientation-restore logic with `isChangingConfigurations` in `onDestroyView` (§7)** —
  without it, the teardown triggered by your own landscape lock immediately fights itself.

---

## 10. Verification checklist after porting

- [ ] `taal-segmentation-core` unit tests pass unmodified in the new project.
- [ ] `taal-segmentation`'s `androidTest` smoke test (proves ONNX Runtime actually loads and runs
      inference on real hardware — a JVM-only test cannot prove this, since the module ships native
      `.so` libraries) passes on a real connected device or emulator, API 26+.
- [ ] With `SegmentationFeature.ENABLED = false`, rebuild and confirm the entry-point button and
      everything downstream of it is completely absent, with zero other behavioural change to the
      app.
- [ ] With it `true`: record (or supply) audio with real heart sounds saved to permanent storage,
      confirm the raw companion file exists, tap the entry point, confirm a result renders with the
      correct trustworthiness verdict, confirm Full/10s/5s zoom works and re-centres correctly, and
      confirm PDF export produces a readable file with the chart embedded.
  - [ ] Also test with a silent/near-empty recording to confirm `NoHeartSounds` renders correctly,
        and (if reachable) a too-short/sparse one to confirm `TooWeak` renders correctly.
- [ ] Confirm no unrelated screen, module, or existing button in the target project changed as a
      side effect of this port.
