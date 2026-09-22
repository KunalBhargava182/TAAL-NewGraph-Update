# SUPERMASTER_STEMZ_APP.md — `stemz-app` Module Reference

**Read this before touching any code in `stemz-app`.** Verified directly against source as of **2026-09-08**, written by the same session that built the "share with graph" feature (§9) end to end — every claim below was hand-traced against the file at the line cited, not carried over from an older doc.

---

## 1. Module Overview

`stemz-app` (added ~2026-08-24) is a **near-complete rebrand fork of `app`** — same package tree, same `TaalDatabase`, same dependency set, but its own `applicationId` so it installs side-by-side with `app` on the same device. It is real, actively developed code, not a stub or throwaway experiment.

| | |
|---|---|
| Gradle module name | `stemz-app` (`settings.gradle.kts`) |
| Android namespace | `com.musediagnostics.taal.app` ⚠️ **never renamed** — identical to `app`'s namespace, a copy-paste artifact |
| `applicationId` | `com.musediagnostics.taal.stemz` — this is what actually prevents install conflicts with `app` |
| App label | "StemzApp" (icon letter S, teal) |
| `minSdk` | 26 |
| `compileSdk`/`targetSdk` | 34 |
| Dependencies | `taal-core` + `taal-segmentation` (NOT `taal-ui-kit`) |
| Java/Kotlin target | 1.8 |

Because the namespace collides with `app`'s, **never assume a stack trace's package name tells you which app it came from** — check the actual APK / applicationId.

---

## 2. Module Config

`stemz-app/build.gradle.kts` (verified current):
- `buildFeatures { viewBinding = true }` — no Compose, no dataBinding.
- Real release `signingConfigs.release` reads `stemz-app/keystore.properties` (gitignored) — falls back to unsigned if that file is absent, added in commit `92fdf62`.
- `isMinifyEnabled = false` in the release build type — ProGuard rules exist (`proguard-rules.pro`) but are not currently load-bearing.
- No flavors, no `applicationIdSuffix`, no `packagingOptions`, no `testOptions` block.
- Test deps: **`junit:junit:4.13.2` only.** `androidTestImplementation` declares Espresso + `androidx.test.ext:junit`, but there is **no `src/androidTest/` directory** — those are dead declarations. No Robolectric, no MockK, no Mockito, no Truth. Any new test must be plain-JVM (see `taal-ui-kit`'s SUPERMASTER for the Robolectric setup to copy if Android-runtime tests are ever needed).

`AndroidManifest.xml` (`stemz-app/src/main/AndroidManifest.xml`):
- Permissions: `RECORD_AUDIO`, `USB_PERMISSION`, `WRITE_EXTERNAL_STORAGE` (maxSdk 28 only — API 29+ uses MediaStore, no runtime permission needed).
- `uses-feature android.hardware.usb.host` (`required=false`).
- Single `FileProvider`, authority `${applicationId}.fileprovider` = **`com.musediagnostics.taal.stemz.fileprovider`** at runtime, backed by `res/xml/file_paths.xml` which exposes **only** `<files-path name="saved_recordings" path="saved/" />` — i.e. `filesDir/saved/` and everything under it (including temp share subdirectories). Nothing else (`cacheDir`, `filesDir` root, external storage) is exposed. Any new share/export feature must either use `filesDir/saved/...` or add a new `<paths>` entry.
- Single-Activity: `.ui.MainActivity` is the only declared Activity. Two deep-link hosts on the same intent-filter: `stemzapp://calibrated` and `stemzapp://pcgscale` — both are debug/direct-access entry points into screen families that otherwise have no production button pointing at them (see §4).

---

## 3. Navigation & Screen Map

`stemz-app/src/main/res/navigation/nav_graph.xml:6` — **live `app:startDestination = @id/pcgScaleRecordingFragment`** (set 2026-09-03, matching `app`'s current default; was `recordingFragment` before that — verify this yourself before trusting it, this value has changed multiple times in this repo's history).

### 3.1 What's actually reachable (the live flow)

```
App launch
  → PcgScaleRecordingFragment  ("TAAL Recorder")
      • Basic/Hard heart filter toggle + Custom (top bar)
      • Hum filter switch (opt-in, default off)
      • Record → 15s hard auto-stop (or manual Stop)
      → PcgScalePlayerFragment  ("Review Recording", Save/Discard bar)
          • Analyze Heart Sounds → SegmentationReportFragment (gated: only shows for an
            already-saved file — in practice NEVER visible here, since this screen is always a
            fresh, not-yet-saved recording; see §7's "known loose end")
          • Save → SaveRecordingFragment (name it) → SavedRecordingsFragment
          • Discard → back to PcgScaleRecordingFragment
      • Folder icon → SavedRecordingsFragment (skip recording entirely)

SavedRecordingsFragment
  • Tap a recording → PcgScaleReviewFragment ("Review Recording", no Save/Discard)
      • Shows the actual filename typed at save time
      • Clean Graph toggle (see §6), default ON
      • Analyze Heart Sounds → SegmentationReportFragment (always visible here — a saved
        file always has a companion _raw.wav)
  • Share icon → audio-only share (§8.1)
  • Share-with-graph icon → audio + PNG/PDF graph share (§9)
  • Delete icon → confirm → removed
```

### 3.2 What exists but is NOT reachable from the live UI

| Screen family | How to reach it | Notes |
|---|---|---|
| **Calibrated screens** (`calibratedRecordingFragment` → `calibratedPlayerFragment` → `dpiCalibrationFragment`) | Deep link only: `stemzapp://calibrated` | mm-accurate ECG paper, DPI calibration, pinch-zoom — superseded by PcgScale for the live flow |
| **PcgScale screens (2nd entry)** | Deep link: `stemzapp://pcgscale` | Same screens as §3.1 — redundant now that PcgScale is the default |
| **FullTimeOn screens** (`fullTimeOnRecordingFragment` → `fullTimeOnPlayerFragment`) | **Nothing** — zero inbound actions, no deep link | Orphaned clone of the Calibrated screens |
| **Production `recordingFragment`/`playerFragment`** (pre-PcgScale/pre-Calibrated) | **Nothing live** — only reachable from the unreachable auth chain | Still compiles, just unwired |
| **Auth chain** (`splashFragment` → sign-in/OTP/PIN/fingerprint) | **Nothing** — `splashFragment` isn't the start destination | Fully built, entirely bypassed |
| `recordingLibraryFragment`, `editRecordingFragment`, `testRecordingFragment`/`testPlayerFragment`, `sharedRecordingsFragment` | Only reachable from `recordingFragment`'s menu (itself unreachable) | Patient-tied library, in-progress test screens |
| `profileFragment`, `changePinFragment`, `faqFragment`, `privacyPolicyFragment`, `subscriptionFragment`, `userManualFragment`, `aboutUsFragment` | Same — hang off the unreachable drawer | Info/settings screens |

**The live app is effectively just §3.1.** Before "fixing" anything in a screen from §3.2, confirm the user can actually reach it in the shipped build.

### 3.3 Shared destinations

| Destination | Reached from |
|---|---|
| `saveRecordingFragment` | `playerFragment`, `calibratedPlayerFragment`, `fullTimeOnPlayerFragment`, `pcgScalePlayerFragment` — pops back to whichever recorder sent it there via a `popUpToDestination` bundle arg (default `recordingFragment`) |
| `savedRecordingsFragment` | `recordingFragment`, `calibratedRecordingFragment`, `fullTimeOnRecordingFragment`, `pcgScaleRecordingFragment`, and after any save |
| `segmentationReportFragment` | `playerFragment`, `pcgScalePlayerFragment`, `pcgScaleReviewFragment` |
| `equalizerFragment` | Still wired for `calibratedPlayerFragment`/`fullTimeOnPlayerFragment`; **removed from the UI** of `playerFragment`/`pcgScalePlayerFragment`/`pcgScaleReviewFragment` (button deleted, nav action left in place unused) |

---

## 4. Waveform/Graph Screen Families

Six independently-evolved implementations exist in this codebase (a monorepo-wide pattern, not stemz-app-specific — see the root `SUPERMASTER.md` §6). In `stemz-app` specifically:

| Family | Live? | Grid/Y-axis algorithm | Key files |
|---|---|---|---|
| **PcgScale** | **YES — the only live one** | Time-true grid (1 large box = 1s, 1 small box = 0.2s, `PcgTimeScale`); RMS-outlier-rejected median-peak auto-scaled Y-axis (`PcgAmplitudeScale`, `TARGET_FILL_FRACTION=0.60f`) | `ecg/pcgscale/*`, `ui/pcgscale/*` |
| Production Recording/Player | No (unreachable) | V7 sample-accurate adaptive warmup/peak | `ui/recording/RecordingFragment.kt`, `ui/player/PlayerFragment.kt` |
| Calibrated | Deep-link only | mm/DPI-calibrated, static grid, trace scrolls over it | `ecg/calibrated/*`, `ui/calibrated/*` |
| FullTimeOn | Orphaned | Fork of Calibrated | `ui/fulltimeon/*` |
| `EcgPaperView`/`MmScale` | Not used by any live screen | mm-accurate, bitmap-cached | `ecg/EcgPaperView.kt`, `ecg/MmScale.kt` |
| Test Recording/Player | Orphaned | — | `ui/recording/TestRecordingFragment.kt`, `ui/player/TestPlayerFragment.kt` |

### 4.1 PcgScale in detail (the one that matters)

- `ecg/pcgscale/PcgTimeScale.kt` — pure Kotlin, `pixelsPerSecond` derived once from plot width via `fromPlotWidth(plotWidthPx, visibleSeconds=4f default)`. Both bounds of the chart's visible range are locked to this (`setVisibleXRangeMaximum` AND `Minimum`) — **zoom is impossible by construction**, not convention. The on-screen window is a fixed 4 seconds; **the whole recording deliberately never fits on screen** — this is why the graph-share export (§9) exists.
- `ecg/pcgscale/PcgScaleEcgPaperView.kt` — custom `View`, grid-only, `onDraw` draws paper background + amplitude rows + time columns/labels. No bitmap cache (redrawn every scroll frame — deliberate, since the grid is only a few dozen lines). Pixel-identical grid on screen and in the graph-share export, because the export literally instantiates and draws this same class unattached (see §9.2). Gained two EXPORT-ONLY methods 2026-09-08: `setGridAlpha(minor, major)` and `setGridStrokeWidthPx(minor, major)` — never called by any on-screen fragment, exist solely for `PcgGraphStripRenderer`.
- `ecg/pcgscale/PcgScaleWaveformView.kt` — `FrameLayout` stacking `PcgScaleEcgPaperView` behind an MPAndroidChart `LineChart` (chart's own grid/axes disabled). Owns the chart's `OnChartGestureListener` — fragments must use `onGestureEnd`/`onGestureTranslate` hooks, never call `chart.setOnChartGestureListener` themselves. Static helpers `downsampleMinMax`/`deriveBucketSizeForVisibleRange` are reused verbatim by the graph-share renderer.
- `ecg/pcgscale/PcgAmplitudeScale.kt` — Y-axis full-scale = median-across-5s-windows of the 3rd-largest-by-RMS hop's peak amplitude, divided by `TARGET_FILL_FRACTION` (0.60f in `stemz-app`; `app` also currently 0.60f — check both live, this has changed at least twice), clamped to `[MIN_FULL_SCALE=0.005f, MAX_FULL_SCALE=1.0f]`. `computeFullScaleForFile(samples, sampleRate)` is the whole-file variant used by Player/Review/export.
- `ecg/pcgscale/PcgDisplayFilter.kt` — display-only conditioning: click/USB-glitch removal, zero-phase 20–500Hz band, 50/100/150Hz notches, transient-protected spectral gate. `processOffline(samples, sampleRate)` is what "Clean Graph ON" actually runs (§6). **Never touches the saved file or playback audio.**
- `ui/pcgscale/PcgScaleRecordingFragment.kt` — the live recorder. Basic/Hard filter toggle (§5), 15s auto-stop (§5), optional Hum filter switch.
- `ui/pcgscale/PcgScalePlayerFragment.kt` — post-recording review; Save routes through `SaveRecordingFragment` (which does the actual temp→`filesDir/saved/` rename), not directly to Add Patient.
- `ui/pcgscale/PcgScaleReviewFragment.kt` — fork of the Player screen for opening an already-saved file from `SavedRecordingsFragment`; no Save/Discard, no `rawFilePath` handling since the file's already on disk. This is the screen `GraphShareBundler` (§9) mirrors.

---

## 5. Recording Flow

1. `PcgScaleRecordingFragment` starts a recording via `TaalRecorder`, which writes **two files**: `{FILTER}_{ts}_raw.wav` and `{FILTER}_{ts}_filtered.wav` (filtered written in real time inside the audio callback).
2. **Filter toggle** — this is stemz-app's real differentiator from `app`: a 2-button **Basic/Hard** toggle instead of `app`'s 5-preset row.
   - **Basic** = 20–250Hz = `PreFilter.HEART` (a real `taal-core` preset).
   - **Hard** = 20–200Hz = `setCustomBandpass(20.0, 200.0)` — a local `"HEART_HARD"` name, **not** a real `taal-core` preset.
   - A separate top-bar icon still offers the original arbitrary custom-range filter.
3. **Auto-stop**: `AUTO_STOP_SECONDS = 15` (bumped from 14 in commit `1f069a4`, 2026-09-07 — fixed a duration-floor bug at the same time), calling the **real** `stopRecording()` — deliberately not `TaalRecorder.setRecordingTime()`, which bypasses filtered-WAV header finalization. `setRecordingTime(30)` is kept only as a ceiling safety net. Same constant duplicated in `ui/pcgscale/PcgScaleRecordingFragment.kt` and `ui/pcgscale/PcgScaleRecordingFragment.kt`'s ported copy inside `PcgScaleRecordingFragment.kt` — check both if changing this.
4. The "Ready to Capture" cold-start dialog is removed entirely (vs `app`, which still has it).
5. RECORD_AUDIO permission is requested as soon as the Recorder screen opens (commit `68ae1af`), not deferred to first tap.
6. On stop → immediately navigates to `PcgScalePlayerFragment` with `filePath`=filtered, `rawFilePath`=raw.
7. Save → `SaveRecordingFragment` renames both temp files into `filesDir/saved/{name}_filtered.wav` + `_raw.wav`. Discard → deletes both temp files.
8. Default pre-amp for the PcgScale recorder: **10dB** (differs from `app`'s 5dB default and from stemz-app's own production `RecordingFragment`, also 5dB).

---

## 6. "Clean Graph" (the Denoise toggle, renamed)

**This is not a chrome/visibility toggle.** Renamed from "Denoise" in commit `01ddf76` (2026-09-04) because the old name overclaimed — it only conditions the *displayed waveform*, never playback audio or the saved file.

- Lives on `PcgScalePlayerFragment` and `PcgScaleReviewFragment` only (not the recorder, which applies `PcgLiveDisplayFilter` unconditionally with no toggle).
- UI: a single rectangular tap-toggle button (`denoiseOnBadge` — Kotlin identifiers still say "denoise" everywhere; only the XML label text says "Clean Graph"), text/fill both read "ON"/"OFF".
- **Default ON** — both fragments call `onDenoiseToggled(true)` immediately after decode completes.
- ON: plots `PcgDisplayFilter.processOffline(originalSamples, rate)`. OFF: plots the raw decoded samples. The Y-axis full-scale is recomputed for whichever array is shown (so the cleaned trace, having a smaller full-scale, usually renders **larger**, not just "cleaner").
- Grid, trace colour (`#2D7DD2`), and line width (1.5dp) are **identical** in both states — nothing else changes.
- First toggle-ON pays the `PcgDisplayFilter.processOffline` cost (shows `waveformLoadingIndicator`); the result is cached (`gatedSamples`/`denoisedSamples`) so every subsequent toggle in either direction is instant.

---

## 7. PCG Segmentation Feature

Gated by `ui/segmentation/SegmentationFeature.kt` — `object SegmentationFeature { const val ENABLED = true }`. Checked at three entry points: `PlayerFragment.kt`, `PcgScalePlayerFragment.kt`, `PcgScaleReviewFragment.kt` — each shows/hides its own "Analyze Heart Sounds" button, layout default `visibility="gone"`, only ever flipped `VISIBLE` inside the `if (SegmentationFeature.ENABLED)` block. Flip to `false` to hide the entire feature with zero other file changes.

Runs `TaalCardiacSegmentation.segmentRawWav()` on the **raw** file, never the filtered one (see `taal-segmentation`'s own SUPERMASTER for the algorithm itself). The report chart (`SegmentationReportFragment`) renders a `PcgDisplayFilter.processOffline()`-conditioned copy of the audio for display — segmentation timing itself is unaffected since that filter is zero-phase.

**Known loose end:** `PcgScalePlayerFragment`'s Analyze button is gated on the file already being inside `filesDir/saved/` — but this screen is *always* reached with a brand-new, not-yet-saved recording in the live flow, so in practice **only `PcgScaleReviewFragment`'s Analyze button is ever visible to a user.**

`SegmentationPdfExporter.kt` (same file in `app`, byte-identical) draws the chart's PDF report by measuring/laying out an unattached `PcgSegmentationView` and calling its `draw(canvas)` directly onto the PDF page — **this exact technique is the basis for the graph-share PNG/PDF export in §9.** Its own "Download Report" button is currently hidden (`visibility="gone"` in both XML and code) but still functional/wired.

---

## 8. Sharing

### 8.1 Audio-only share (original, untouched)

`ui/library/SavedRecordingsFragment.kt:86-126` — `shareRecording(file: File)`. Copies the file to a clean-named temp copy in `filesDir/saved/.share_tmp/` (wiped on every call), shares via `FileProvider` with **`application/octet-stream`** (deliberately generic, not `audio/wav`, so WhatsApp and similar apps route it through their "send as document" path instead of transcoding it to AAC — this MIME-type trick is a recurring pattern, reused for the graph-share bundle too). `ACTION_SEND`, single `EXTRA_STREAM`.

A second, near-duplicate share function exists in `ui/library/RecordingLibraryFragment.kt:301-328` for the (unreachable, §3.2) patient-library screen — no clean-name handling, will throw for any DB row whose `filePath` sits outside `filesDir/saved/` (pre-existing bug, not fixed).

### 8.2 Known WhatsApp limitation

Even with the anti-transcode MIME trick, WhatsApp itself still re-transcodes shared `.wav` files to AAC as of this writing — noted in `docs/notes/STEMZAPP_CURRENT_FLOW.md`, unrelated to any graph-share work.

---

## 9. Graph Share Feature (added 2026-09-07/08, merged into the primary share button 2026-09-22)

**Current behavior (2026-09-22):** `SavedRecordingsFragment`'s single, primary share icon (`shareButton`) sends the `.wav` **plus a PDF** of the whole recording rendered as a time-true, stacked-row graph strip in the Clean-Graph-ON state. **No PNG** — that was dropped per explicit request; `GraphShareExporter.writePng()` was deleted outright since nothing called it anymore. There is no second, separate share icon any more — see §9.7.

*(Sections 9.1-9.6 below describe the feature's original 2026-09-07/08 design — a genuinely separate second button, wav+png+pdf — kept as history since the rendering/DSP internals it describes are still accurate. §9.7 is the current wiring; read that first if you only need "how does the share button work right now.")*

### 9.1 Why this design (constraints that shaped it)

1. The Review screen only shows a 4-second scrolling window of a (max 300s, typically 15s) recording — screenshotting it would capture an arbitrary slice, not the whole recording.
2. The share button lives on a list screen (`SavedRecordingsFragment`) with no graph view instantiated at all — there is nothing on-screen to screenshot.
3. → Rules out screenshot/`PixelCopy`/`drawToBitmap` entirely. The only viable approach is an **offscreen deterministic re-render**, using the exact on-screen drawing code so the export can never visually drift from the app — the same rationale `SegmentationPdfExporter.kt` already established for the segmentation report (§7).

### 9.2 New files (`ui/graphshare/`)

| File | Role |
|---|---|
| `GraphShareFeature.kt` | `object { const val ENABLED = true }` — rollback flag, same convention as `SegmentationFeature` |
| `PcgStripLayout.kt` | **Pure Kotlin**, no Android dep. Geometry: splits the recording into fixed-duration rows (default 5s/row), computes each row's absolute time range + pixel bounds, PDF page grouping (`paginate`). `firstRowIndex`/`rowCountOverride` params let one instance represent a single PDF page while keeping each row's `startSec` an absolute recording time (needed to clip the right sample slice) |
| `PcgWavDecoder.kt` | **Pure Kotlin.** Same 44-byte-header/16-bit-LE-PCM decode the fragments inline — lifted out here as a 4th (new, isolated) copy rather than refactoring the two working fragments |
| `RecordingDisplayName.kt` | **Pure Kotlin.** 3rd copy of the `{FILTER}_{name}_filtered` → `{name}` stripping logic (HEART_HARD before HEART — same prefix-match bug guarded in all 3 copies) |
| `ShareRequest.kt` | **Pure Kotlin** descriptor (`ShareAction.SEND`/`SEND_MULTIPLE`, mime type, attachment paths) — no `Intent`/`Uri` dependency, so its shape is plain-JVM testable. `audioWithGraph()` takes `(wavPath, pdfPath, displayName)` as of 2026-09-22 — the `pngPath` param was removed |
| `PcgGraphStripRenderer.kt` | **Android.** Draws the full strip onto ANY `Canvas` — was used for both a Bitmap (PNG) and PDF pages, now only ever called for PDF pages (§9.7), but the renderer itself is still format-agnostic. Reuses `PcgScaleEcgPaperView`/`PcgTimeScale` unmodified for the grid; hand-draws the trace polyline itself (on-screen that's MPAndroidChart's `LineChart`, which only renders its 4s window) |
| `GraphShareExporter.kt` | **Android.** As of 2026-09-22, PDF-only (`writePdf`, `PdfDocument`, A4 landscape, same density-forcing trick as `SegmentationPdfExporter`). `writePng()` and its `PNG_CANVAS_WIDTH_PX` constant were **deleted** — no other caller existed once `GraphShareBundler` stopped calling it |
| `GraphShareBundler.kt` | **Android.** Orchestrates: read file → `PcgWavDecoder.decode` → `PcgDisplayFilter.processOffline` (Clean-Graph-ON state, matching the Review screen's default) → `PcgAmplitudeScale.computeFullScaleForFile` → render → write `{name}.wav`/`.pdf` (no `.png` as of 2026-09-22) → return a `ShareRequest` |

### 9.3 UI wiring, as originally built 2026-09-07/08 (superseded — see §9.7 for current)

- `res/layout/item_saved_recording.xml` — new `shareWithGraphButton` (`ic_share_graph.xml`), `android:visibility="gone"` by default.
- `ui/library/SavedRecordingAdapter.kt` — new defaulted `onShareWithGraph: (File) -> Unit = {}` constructor param; button only ever set `VISIBLE` and its listener only ever registered inside `if (GraphShareFeature.ENABLED)`.
- `ui/library/SavedRecordingsFragment.kt` — new `shareRecordingWithGraph(file)` function + `launchShareRequest(request)` (turns the pure `ShareRequest` into a real `Intent`/`Uri`). **The original `shareRecording()` function (§8.1) is byte-for-byte untouched** — verified via `git diff` on every commit in this feature.
- `res/layout/fragment_saved_recordings.xml` — new `shareGraphProgressOverlay` (indeterminate spinner), gone by default, shown while `GraphShareBundler` runs.
- Writes to **`filesDir/saved/.share_bundle_tmp/`** — a different directory from `.share_tmp/` (§8.1), so the two share paths can never race or delete each other's in-flight files. No `file_paths.xml`/manifest changes needed — both temp dirs already fall under the existing `saved/` exposure.

### 9.4 Two real bugs found and fixed during on-device testing (both documented in `[[project_canvas_drawcolor_clip_gotcha]]` memory)

1. **Only the last row ever rendered.** `Canvas.drawColor()` fills the canvas's current *clip region*, ignoring `canvas.translate()` (it isn't geometry). Each row's `PcgScaleEcgPaperView.onDraw` opens with `canvas.drawColor(paperColor)` to paint its white background — without a per-row `clipRect`, each row's background wiped out every row (and the header) drawn before it. **Fix:** `canvas.clipRect(0f, 0f, width, height)` in `PcgGraphStripRenderer.drawRow` right after translating, before drawing that row's content.
2. **PDF header text overlapped row 0.** `drawHeader` originally used *fixed* pixel offsets (`marginPx+24`/`marginPx+46`) that happened to fit the PNG export's 64px header but not the PDF's shorter 40pt header — the subtitle landed inside row 0's area and got erased by fix #1's clip. **Fix:** title/subtitle Y positions are now proportional to the layout's actual `headerHeightPx` (45%/85%), and `PDF_HEADER_HEIGHT_PT` was bumped 40→46 for extra buffer.

### 9.5 Grid visibility tuning (export-only)

The 0.2s minor grid squares needed to be pushed well past their on-screen defaults to read clearly on a static PNG/PDF. Two new **export-only** methods added to `PcgScaleEcgPaperView.kt` — `setGridAlpha(minor, major)` and `setGridStrokeWidthPx(minor, major)` — never called by any on-screen fragment (verified via `git diff` on the three `ui/pcgscale/*Fragment.kt` files showing zero changes). Current export values: minor alpha 0.20→**0.85**, major alpha 0.50→**1.0**, minor stroke 0.7dp→**2.2px**, major stroke 1.2dp→**3.4px**.

### 9.6 Testing

`stemz-app` has only `junit:junit:4.13.2` (no Robolectric) — all new tests are plain-JVM, zero new Gradle dependencies:

| Test file | Covers |
|---|---|
| `PcgStripLayoutTest.kt` | Row tiling, partial final row, PDF pagination, `firstRowIndex` absolute-time correctness |
| `PcgWavDecoderTest.kt` | Synthetic WAV decode, sample-rate parsing, truncation safety |
| `ShareRequestTest.kt` | Locks the legacy single-file shape and the new multi-file shape side by side |
| `RecordingDisplayNameTest.kt` | HEART_HARD-before-HEART prefix-match guard |

Also **backfilled** `PcgTimeScaleTest.kt` and `PcgAmplitudeScaleTest.kt` from `app/` into `stemz-app/` (existed there but not here — byte-identical source files, so the copied tests apply cleanly).

Regression guard used at every step of this feature: `git diff HEAD` scoped to `ui/pcgscale/*` and the original `shareRecording()`/manifest/`file_paths.xml` — confirmed zero unintended changes at each commit.

### 9.7 Current wiring (2026-09-22 — read this one)

Per explicit request, the separate second button was folded into the primary share action and the PNG was dropped:

- **`ui/library/SavedRecordingsFragment.kt`'s `onShare` callback** (in `loadRecordings()`) now reads:
  ```kotlin
  onShare = { file ->
      if (GraphShareFeature.ENABLED) shareRecordingWithGraph(file) else shareRecording(file)
  },
  ```
  i.e. the single, primary `shareButton` triggers the graph-bundle flow when the flag is on (the
  default). **`GraphShareFeature.ENABLED` is kept specifically as the rollback switch** — flip it
  to `false` and the same button reverts to exactly the original single-file `shareRecording()`
  behavior (§8.1, still byte-for-byte unmodified), with zero other code changes, same convention
  as `SegmentationFeature` elsewhere in this app.
- **`ui/library/SavedRecordingAdapter.kt`** — the `onShareWithGraph` constructor param and the
  `if (GraphShareFeature.ENABLED) { shareWithGraphButton.visibility = VISIBLE; ... }` block are
  both **removed**. `shareWithGraphButton` is still declared in `item_saved_recording.xml`
  (`android:visibility="gone"`) — nothing in code ever sets it visible any more, so it stays
  permanently hidden. The XML element itself was deliberately left in place rather than deleted
  (lower-risk, reversible) — it is dead UI, not dead code with live effects.
- **`GraphShareBundler.buildShareRequest()`** no longer writes a `.png` file or calls (the now
  deleted) `GraphShareExporter.writePng()` — only `{name}.wav` and `{name}.pdf` are written into
  `.share_bundle_tmp/` and passed to `ShareRequest.audioWithGraph(wavPath, pdfPath, displayName)`.
- Everything else about the feature — the offscreen renderer, the grid-visibility tuning (§9.5),
  the two bug fixes (§9.4), the temp directory separation from the legacy share path, the test
  suite (§9.6, with `ShareRequestTest` updated for the 2-file shape) — is unchanged.
- Verified: `./gradlew :stemz-app:assembleDebug :stemz-app:testDebugUnitTest` green; `git diff`
  scoped to exactly 6 files (`GraphShareBundler.kt`, `GraphShareExporter.kt`, `ShareRequest.kt`,
  `SavedRecordingAdapter.kt`, `SavedRecordingsFragment.kt`, `ShareRequestTest.kt`) — no layout,
  manifest, or other-module changes; confirmed working on-device.

---

## 10. UI Screen Inventory

| Package | Purpose | Reachable? |
|---|---|---|
| `ui/pcgscale/` | Live Recorder/Player/Review (§3.1, §4.1) | **Yes** |
| `ui/library/` | Saved Recordings list + share/delete (§8, §9), Recording Library (patient-tied) | Saved Recordings: yes. Recording Library: no |
| `ui/segmentation/` | Analyze Heart Sounds report + PDF export | Yes (from Review only, §7) |
| `ui/graphshare/` | Share-with-graph feature (§9) — no UI of its own, backs `ui/library`'s new button | N/A (logic package) |
| `ui/recording/`, `ui/player/` | Original production Recorder/Player/EQ/crop/edit | No (unreachable, §3.2) |
| `ui/calibrated/` | mm-accurate ECG paper + DPI calibration | Deep-link only |
| `ui/fulltimeon/` | Always-on preview fork of Calibrated | No |
| `ui/auth/` | Sign-in/OTP/PIN/fingerprint chain | No |
| `ui/patient/` | Add/search patients | No (hangs off unreachable screens) |
| `ui/info/`, `ui/settings/`, `ui/profile/` | About/FAQ/privacy/subscription/manual/change-PIN/profile | No |

---

## 11. Known Issues / Gotchas

- `stemz-app` still has the **false-positive silence-detection bug** in its own `RecordingFragment` copies (the version documented as fixed elsewhere is not the one here) — see `docs/notes/AUDIO_DIAGNOSTIC_REMEDIATION_TRACKER.md`.
- Hum/denoise filters (the recorder's opt-in hum switch, unrelated to Clean Graph) default off with zero effect per `docs/notes/PCG_NOISE_REDUCTION_HANDOFF.md`.
- `SegmentationFullScreenFragment.kt` has a fixed orientation-restore bug in `stemz-app` (caught on a physical Samsung device) that has **not** been ported back to `app`'s copy — check before assuming parity between the two apps on this file.
- `RecordingLibraryFragment.shareRecording` (§8.1) silently fails for DB rows outside `filesDir/saved/` — pre-existing, unreachable in the live flow, not fixed.
- Two large near-full-height spikes observed ~12-13s into a 14s (pre-bump) PcgScale recording — investigated, most likely a genuine "stethoscope thud" transient rather than a rendering bug, but **not conclusively closed** (see `docs/notes/PCGSCALE_WORK_REFERENCE.md` §5).
- WhatsApp still transcodes shared `.wav` to AAC despite the anti-transcode MIME trick (§8.2) — a WhatsApp-side limitation, not a bug in this code.
- Sharing the wav+pdf bundle (§9) uses `*/*` as the MIME type since there's no single correct type for a heterogeneous bundle (untested trade-off, documented in the original plan). **PNG dropped 2026-09-22** — the bundle is wav+pdf only now, so this is less of a concern than when it was wav+png+pdf.

---

## 12. Changelog

| Date | Change | Notes |
|---|---|---|
| 2026-09-22 | Graph share merged into primary share button, PNG dropped (§9.7) | Per explicit request: single `shareButton` now does what the second `shareWithGraphButton` used to; that button retired (still in XML, never shown); bundle is wav+pdf only — `GraphShareExporter.writePng()` deleted, `ShareRequest.audioWithGraph()` signature shrunk to 2 files. `GraphShareFeature.ENABLED` kept as the rollback switch. Confirmed working on-device. |
| 2026-09-08 | Initial `SUPERMASTER_STEMZ_APP.md` created | Full audit, written from a session that had just built §9 end to end |
| 2026-09-07/08 | "Share with graph" feature added (§9) | New `ui/graphshare/` package, 2 rendering bugs found+fixed on device, grid visibility tuned twice per explicit feedback |
| 2026-09-07 | Auto-stop 14s→15s, duration-floor bug fix | `1f069a4` |
| 2026-09-07 | Release signingConfig wired to real keystore | `92fdf62` |
| 2026-09-04 | "Denoise" renamed to "Clean Graph" + single tap-toggle button | `01ddf76`, `29b35a6` |
| 2026-09-03 | PcgScale ported into `stemz-app`, became `startDestination` | See `docs/notes/PCGSCALE_WORK_REFERENCE.md` for full detail |
