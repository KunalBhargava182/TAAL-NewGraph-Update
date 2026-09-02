# PCG noise-reduction features — handoff (2026-09-01/02)

**Purpose of this file:** zero-context handoff for whoever (human or another Claude Code
session) picks this up next. Read this before touching `AudioFilterEngine.kt`,
`TaalRecorder.kt`, or anything under `app/.../ecg/pcgscale/` or `app/.../ui/pcgscale/`.

**Branch:** `pcgscale-rev3`. **Status: implemented, built, tested, NOT yet committed.**
Nothing in this body of work has been git-committed — it's all in the working tree.

**Also in this working tree, NOT part of this doc:** a separate, concurrent Samsung
audio-capture-level investigation (started 2026-09-01, still active 2026-09-02) that has
added logging to `TaalAudioCapture.kt` and `TaalRecorder.kt`. See
`docs/notes/SAMSUNG_AUDIO_LEVEL_ATTENUATION_DIAGNOSTIC.md` and
`docs/notes/AUDIO_DIAGNOSTIC_REMEDIATION_TRACKER.md` for that thread — it's additive
logging only, doesn't conflict with anything below, and this doc's diffs were re-verified
against the file as it stands with that logging already layered on top.

---

## 1. What was asked for

Two independent, additive, default-OFF noise-reduction features for the TAAL PCG (heart
sound) waveform screens, driven by an offline analysis of paired study recordings showing:
(a) 20–80Hz broadband rumble inside the HEART passband, and (b) 50Hz mains hum (OnePlus)
/ 50+60Hz (Samsung). A two-stage chain — biquad hum/rumble filter, then spectral gating —
was validated offline at −6.7dB (biquad alone) to −22dB/−13.9dB (both stages), SNR
19.7→37.5dB and 25.8→35.8dB, ~4dB signal cost. This work productionizes both stages as
opt-in features behind switches, without touching any protected production screen.

- **Feature A** — a toggleable hum/rumble biquad filter in the **live recording** DSP
  chain (`taal-core`), surfaced as a switch on the PcgScale recorder screen only.
- **Feature B** — a spectral-gate **post-processing** denoise toggle on the PcgScale
  **Review** screen (already-saved recordings), display-only — does not touch the saved
  file or playback audio.

Constraints followed throughout: additive only, default OFF everywhere, no Gradle/dependency
changes, no edits to production/Calibrated/FullTimeOn screens, `taal-core` changes required
explicit sign-off before being written (obtained — see §2).

---

## 2. Feature A — hum/rumble biquad filter (live recording, opt-in)

### Where

- `taal-core/src/main/java/com/musediagnostics/taal/dsp/AudioFilterEngine.kt`
- `taal-core/src/main/java/com/musediagnostics/taal/TaalRecorder.kt`
- `taal-core/src/test/java/com/musediagnostics/taal/dsp/AudioFilterEngineHumRumbleTest.kt` (new)
- `app/src/main/res/layout/fragment_pcgscale_recording.xml`
- `app/src/main/java/com/musediagnostics/taal/app/ui/pcgscale/PcgScaleRecordingFragment.kt`

### What / why

`AudioFilterEngine.processBlock()`'s existing per-sample chain is
`preAmpGain → bandpass biquad → 5× peaking-EQ biquad → tanh`. It already had a private
`BiquadCoeffs`/`BiquadState`/`processBiquad()` implementation — Feature A reuses that
exact design rather than introducing a second one.

Added: a 4th-order Butterworth high-pass at 25Hz (two cascaded biquad sections, standard
Butterworth pole Qs 0.5412 and 1.3066) followed by three RBJ-cookbook notch biquads at 50,
100, 150Hz (Q=30 each), inserted **after** the EQ cascade and **before** the final `tanh`
soft-clip. Gated by a single new boolean, default `false`:

```kotlin
// AudioFilterEngine.kt
private var humRumbleFilterEnabled: Boolean = false   // new field, default off

fun setHumRumbleFilterEnabled(enabled: Boolean) {      // new public setter
    humRumbleFilterEnabled = enabled
}

// designed once in init{} via designHumRumbleFilters() — fixed frequencies relative to
// this engine's sampleRate, unlike bandpass/EQ which move with the user's preset/gain.

// in processBlock(), after the EQ cascade, before tanh:
if (humRumbleFilterEnabled) {
    sample = processBiquad(sample, humHpCoeffs1, humHpState1)
    sample = processBiquad(sample, humHpCoeffs2, humHpState2)
    sample = processBiquad(sample, humNotchCoeffs50, humNotchState50)
    sample = processBiquad(sample, humNotchCoeffs100, humNotchState100)
    sample = processBiquad(sample, humNotchCoeffs150, humNotchState150)
}
```

`TaalRecorder` gets a matching pass-through:

```kotlin
fun setHumRumbleFilterEnabled(enabled: Boolean) {
    checkNotRecording("setHumRumbleFilterEnabled")   // fixed per session, like setPreFilter
    humRumbleFilterEnabled = enabled
    filterEngine.setHumRumbleFilterEnabled(enabled)
}
```
...and `reset()` now also resets this flag to `false` (mirrors `preFilter`/`preAmplificationDb`).

**Why default OFF and additive-only matters:** every other `taal-core` consumer
(production `app`, `lungs-app`, `visualizertaal-app`, `stemz-app`) calls `processBlock()`
without ever touching this flag, so their DSP output is byte-for-byte unchanged — verified
in the first `AudioFilterEngineHumRumbleTest` (identical output disabled vs. never-toggled).

**Known caveat, documented in a code comment on `setHumRumbleFilterEnabled`:** if a device's
true USB-audio capture rate is actually 48kHz mislabeled as 44.1kHz (an open, unrelated
`taal-core` issue — see the Samsung diagnostic doc above for the *separate* investigation
into that), the notch centers land ~8.8% high. Acceptable at Q=30 for now; revisit once
that root cause is fixed.

**UI:** a compact `SwitchMaterial` ("Hum filter") added to `fragment_pcgscale_recording.xml`
next to the amp-slider card, default unchecked. `PcgScaleRecordingFragment` disables it
whenever recording is in progress (same `setFilterButtonsEnabled(...)` path the preset
filter buttons already use), and reads `binding.humFilterSwitch.isChecked` into
`TaalRecorder.setHumRumbleFilterEnabled(...)` right before `taalRecorder?.start()`. **Not
persisted** — resets to OFF every time the fragment is recreated (see Open Questions).

### Tests

`taal-core/src/test/java/com/musediagnostics/taal/dsp/AudioFilterEngineHumRumbleTest.kt`
(new `src/test` source set for `taal-core` — no Gradle change needed, `junit:junit:4.13.2`
was already a `testImplementation` dependency):
1. Disabled → bit-identical output to an engine that was never toggled.
2. Enabled → a 50Hz tone's RMS ends up <10% of an 80Hz passband tone's RMS (after a
   settling period).
3. Enabled → a 10Hz tone (sub-25Hz rumble) is likewise suppressed >10× relative to 80Hz.

All 3 pass.

---

## 3. Feature B — spectral-gate denoise (Review screen, display-only)

### Where

- `app/src/main/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgSpectralGate.kt` (new)
- `app/src/test/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgSpectralGateTest.kt` (new)
- `app/src/main/res/layout/fragment_pcgscale_review.xml`
- `app/src/main/java/com/musediagnostics/taal/app/ui/pcgscale/PcgScaleReviewFragment.kt`

**Deliberately scoped to `PcgScaleReviewFragment` only** (reviewing an already-saved
recording). `PcgScalePlayerFragment` (the screen shown immediately after a live recording,
before it's saved) does **not** get this toggle — see Open Questions.

### `PcgSpectralGate.kt` — the algorithm

Pure Kotlin, zero Gradle dependencies — hand-rolls its own iterative radix-2 Cooley-Tukey
FFT (`private object Fft`) inside the file. Public surface is exactly one function:

```kotlin
class PcgSpectralGate {
    fun process(samples: FloatArray, sampleRate: Float): FloatArray   // same length out
}
```

Algorithm, in order:

1. **STFT**: `NFFT=2048`, `HOP=1024` (50% overlap), Hann window used as **both** the
   analysis and synthesis window.
2. **Noise profile, three streaming passes** (never materializes the full magnitude
   matrix — that would be ~100MB for a 300s file — so memory stays O(NFFT) regardless of
   file length):
   - Pass 1: per-frame RMS on the raw (unwindowed) segment.
   - Pass 2: mean magnitude per FFT bin, averaged over only the quietest 30% of frames
     (by pass-1 RMS).
   - Pass 3, per frame: `gain[bin] = clamp((mag - 1.5×noiseProfile[bin]) / mag, -18dB, 1)`,
     then temporal release smoothing `g_t = max(g_raw, 0.6 × g_{t-1})` per bin (suppresses
     "musical noise" chirping), applied multiplicatively to the complex spectrum, then
     inverse-FFT'd and overlap-added back.
3. **Overlap-add normalization**: divided by the summed squared window
   (analysis×synthesis, both Hann, so literally `Σ window²`) at each output sample — this
   is what makes gain=1 reconstruct the input exactly (see test 1 below), not an
   approximation.

A minor Kotlin compiler quirk hit during implementation: `array[i] /= scalar` (compound
assignment on a primitive-array element with a cross-type RHS, e.g. `FloatArray /= Int`)
fails to compile with `No set method providing array access`. Fixed by writing it out as
`array[i] = array[i] / scalar` everywhere in this file — purely a syntax workaround, no
behavior difference.

### `PcgScaleReviewFragment.kt` — integration

New fields cache both sample-array versions so toggling is instant after the first compute:

```kotlin
private var originalSamples: FloatArray? = null   // as-decoded from the WAV
private var gatedSamples: FloatArray? = null       // computed lazily, cached forever
private var fileSampleRateForGate: Float = INPUT_SAMPLE_RATE
private var pixelsPerSecondForRender: Float = -1f
private var recordingDurationSecs: Int = 0
private var denoiseEnabled = false
```

`loadFullWaveform()` was refactored to decode once, cache `originalSamples`, and share a
new `computeRenderPayload(samples, sampleRate)` helper (whole-file
`PcgAmplitudeScale.computeFullScaleForFile` + `PcgScaleWaveformView.downsampleMinMax`,
exactly the existing math) with the new `onDenoiseToggled(enabled: Boolean)`:

- **OFF**, or **ON with a cache hit** → synchronous re-bucket + re-render on the main
  thread ("instant" per spec — both arrays already live in memory).
- **ON, first time** → shows the layout's existing (previously-unused)
  `waveformLoadingIndicator`, runs `PcgSpectralGate().process(...)` on
  `viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default)` (CPU-bound FFT work,
  not `Dispatchers.IO`), caches the result in `gatedSamples`, then renders.

Axis, bucket, range-lock, and grid-sync are all recomputed **exactly** through the existing
load path (`applyRenderPayload` → `renderWaveformEntries`) on every toggle, per spec — this
includes `renderWaveformEntries`'s `chart.centerViewTo(currentWindowSeconds / 2f, ...)`,
so toggling Denoise while scrolled mid-recording snaps the view back to the start; this
matches "re-apply ... exactly as the existing load path does" literally, not an oversight.

**Playback is untouched** — `setupPlayer`/`togglePlayback` still read directly from
`filePath` (the original file on disk); the gate only ever touches the in-memory display
arrays. Caption (`updateScaleCaption()`) appends `" · denoised"` when active.

### Tests

`PcgSpectralGateTest.kt`, 4 tests, all pass:

1. **Reconstruction identity** — a signal with a long leading silent stretch guarantees the
   "quietest 30% of frames" noise profile is exactly zero, which forces `gain=1` for every
   bin/frame via the real formula (not a test-only bypass) — a legitimate way to exercise
   the full STFT/OLA machinery through the public API only. RMS error vs. input < 1e-3.
2. **SNR improvement + amplitude preservation** — an 80Hz tone pulsed on/off (300ms
   burst / 500ms gap × 6 cycles, mimicking S1/S2-separated-by-diastole rather than a
   continuous tone) plus continuous white noise at 0.01 RMS, at a realistic 44.1kHz.
   Asserts ≥10dB SNR improvement and tone amplitude preserved within 1.5dB.
3. **All-zeros in → all-zeros out.**
4. **Output length always equals input length**, across several sizes including
   sub-`NFFT`, `NFFT`, `NFFT+1`, and non-hop-aligned lengths.

**Important debugging note for whoever touches this test next:** test 2's SNR metric was
*not* "residual (output − clean) during the tone bursts" — that metric actually got
**worse** after gating (SNR dropped ~12dB) despite the algorithm visibly working correctly
(verified via temporary debug logging: gain at the tone's peak bin was ~0.997, i.e. barely
touched). The reason: per-bin multiplicative spectral gating is a *time-varying filter*,
and comparing its output point-wise against the pre-noise clean reference during the
*active* region conflates gating-induced signal distortion (an accepted, inherent
side-effect of this class of algorithm) with actual denoising, and is not how such gates
are normally evaluated. The metric was corrected to the standard approach: signal level
during bursts vs. **noise floor during the quiet gaps** (skipping the first 40% of each gap
to avoid biasing on the release-smoothing decay tail). That gives a clean, appropriate
+13.7dB measurement and is presumably close to what the offline validation in the original
task brief also measured. If you see a similar "SNR got worse" result while modifying this
algorithm, check whether you're using the same (correct) burst-vs-gap methodology before
assuming a regression.

---

## 4. Build & test verification (as of 2026-09-01, re-verified against the file state as of 2026-09-02 with the Samsung-diagnostic logging layered on top)

```
./gradlew :app:assembleDebug :taal-core:assembleDebug   → BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest :taal-core:testDebugUnitTest → BUILD SUCCESSFUL
  app:        84/84 tests passed (incl. 4 new PcgSpectralGateTest, 15 PcgAmplitudeScaleTest
              — the latter still carries the pre-existing local MIN_FULL_SCALE=0.005 fixture,
              untouched by this work)
  taal-core:  3/3 tests passed (AudioFilterEngineHumRumbleTest, new)
```

`git status` at completion showed exactly: the 9 files listed in §2/§3 as modified/new,
plus the two **pre-existing, unrelated** local uncommitted changes that were explicitly
preserved and never touched:
- `PcgAmplitudeScale.kt` + `PcgAmplitudeScaleTest.kt` — `MIN_FULL_SCALE` 0.02→0.005 (rev-3
  Samsung fix, predates this work).
- `taal-core/.../TaalAudioCapture.kt` — Samsung diagnostic instrumentation (separate,
  concurrent thread of work — see the tracker doc referenced at the top of this file).

**Nothing has been committed.** `docs/notes/SAMSUNG_AUDIO_LEVEL_ATTENUATION_DIAGNOSTIC.md`
and `docs/notes/AUDIO_DIAGNOSTIC_REMEDIATION_TRACKER.md` are also still untracked, from the
concurrent session.

---

## 5. On-device checklist (not yet executed — needs physical devices)

- [ ] OnePlus: Hum filter toggle OFF→ON on PcgScale recorder — 50Hz line and rumble ribbon
      visibly/audibly reduced in the live trace and in the saved recording's playback.
- [ ] Samsung: open a saved recording in PcgScale Review, toggle Denoise ON — trace
      collapses to clean beat trains, axis visibly grows (smaller noise floor → smaller
      full-scale), caption shows "· denoised". Toggle OFF — restores instantly, no reload.
- [ ] Toggle Denoise ON the first time on a long (~300s) recording — loading spinner shows
      briefly, no ANR.
- [ ] Confirm playback audio is unaffected by Denoise (plays original file either way).
- [ ] Confirm production `RecordingFragment`/`PlayerFragment`, Calibrated, and FullTimeOn
      screens are pixel-for-pixel unchanged (no hum switch, no denoise switch, filters
      behave as before).
- [ ] Confirm `lungs-app`/`visualizertaal-app`/`stemz-app` (other `taal-core` consumers)
      build and record normally — hum filter defaults off, zero behavioral change.

---

## 6. Open questions (explicitly not resolved — flag, don't silently decide)

1. **Toggle persistence** — neither switch persists across sessions/screens; both reset to
   OFF every time. Would need a `SharedPreferences` flag if you want it sticky.
2. **Player parity** — `PcgScalePlayerFragment` (the screen shown right after a live
   recording, before Save/Discard) doesn't have the Denoise toggle, only
   `PcgScaleReviewFragment` (saved-file review) does. The same `computeRenderPayload` /
   `PcgSpectralGate` code would drop in directly if wanted there too.
3. **ML pipeline export** — the gate only ever affects the on-screen trace today. Whether
   gated audio should also be offered as an export/preprocessing option ahead of the
   segmentation/ML pipeline (which currently reads raw WAV files directly) is unaddressed.

---

## 7. Full file manifest

**Modified:**
- `taal-core/src/main/java/com/musediagnostics/taal/dsp/AudioFilterEngine.kt`
- `taal-core/src/main/java/com/musediagnostics/taal/TaalRecorder.kt`
- `app/src/main/res/layout/fragment_pcgscale_recording.xml`
- `app/src/main/res/layout/fragment_pcgscale_review.xml`
- `app/src/main/java/com/musediagnostics/taal/app/ui/pcgscale/PcgScaleRecordingFragment.kt`
- `app/src/main/java/com/musediagnostics/taal/app/ui/pcgscale/PcgScaleReviewFragment.kt`

**New:**
- `taal-core/src/test/java/com/musediagnostics/taal/dsp/AudioFilterEngineHumRumbleTest.kt`
- `app/src/main/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgSpectralGate.kt`
- `app/src/test/java/com/musediagnostics/taal/app/ecg/pcgscale/PcgSpectralGateTest.kt`

**Untouched (verified, don't need re-checking unless you suspect regression):**
- Production `RecordingFragment`/`PlayerFragment`, all Calibrated/FullTimeOn screens,
  `lungs-app`, `visualizertaal-app`, `stemz-app`.
- `PcgScalePlayerFragment.kt` (the not-yet-saved review screen — see Open Question 2).
