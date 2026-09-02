# Audio diagnostic remediation tracker — started 2026-09-02

**Purpose of this file:** running hand-off log for the fixes to the problems found in the
red-team review of the Samsung capture-level investigation. If this session ends (credits,
context, whatever), any other model/session should be able to pick up from HERE alone.
Read `docs/notes/SAMSUNG_AUDIO_LEVEL_ATTENUATION_DIAGNOSTIC.md` first for the original
investigation, then this file.

**Branch:** `pcgscale-rev3`. **Working tree carries concurrent uncommitted work** (spectral
gate / hum-rumble feature: `PcgSpectralGate.kt`, `AudioFilterEngine.kt`, `TaalRecorder.kt`,
PcgScale fragments/layouts, `taal-core/src/test/.../AudioFilterEngineHumRumbleTest.kt`, plus
the `MIN_FULL_SCALE=0.005` local change) — DO NOT revert/checkout over any of it.

---

## Background: what the red team found (2026-09-01, full text in chat; summary here)

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | CRITICAL | Per-second `peak=` log in `TaalAudioCapture.captureAudioToFile()` reports only the last ~160ms buffer read, not the full second — ~84% of audio never examined. Inflates apparent variance in EVERY peak-based comparison made in both the original diagnostic and the AGC gate tests. | **FIXED — see Task 1** |
| 2 | HIGH | AGC gate (±2dB steady-tone test) was run on VOICE_RECOGNITION (FAIL, 16.9dB swing) and DEFAULT (FAIL, 22dB swing) but never on the UNPROCESSED baseline under the same protocol — no valid control. | **UNBLOCKED by Tasks 1+2 — re-test protocol below, needs human + device** |
| 3 | HIGH | Original "5-10x vendor per-source gain" conclusion compares peaks across SEPARATE recording sessions with a hand-held rig now known to swing ~20dB. Ordering (UNPROCESSED < DEFAULT < VOICE_RECOGNITION) replicated in averages, so plausible — but not confirmed. | **UNBLOCKED by Tasks 1+2 — same re-test protocol** |
| 4 | MEDIUM | Config E ("PCM_FLOAT doesn't matter") based on one 3s aggregate peak — inconclusive. | Open — optional; retest only if float path becomes relevant again |
| 5 | MEDIUM | No repeated trials, no randomized order, tone-source device's own output stability never verified. | Addressed in re-test protocol below |
| 6 | MEDIUM | OnePlus baseline (p95=0.0106, the 4.9x target) never re-measured in any session here. | Open — needs OnePlus device connected |
| 7 | LOW | Proposed (never implemented) fix matching `Build.MODEL.startsWith("SM-A06")` is fragile across regional SKUs. | Note only — fix itself is on hold pending #2/#3 |

Solid findings NOT in question (software-level facts, immune to rig noise):
- No 48k→44.1k resample on Samsung SM-A066B (dumpsys: client fmt == dev fmt == 44100Hz).
- Routing always stayed on the TAAL USB device; never fell back to built-in mic.
- `DEFAULT` == `MIC` internally on this device (dumpsys recording-activity log).
- Raw peak log is measured pre-filter/pre-preamp — UI pre-amp slider cannot contaminate it.

---

## Task 1 — FIX: window-aggregated peak/RMS logging  ✅ DONE 2026-09-02

**File:** `taal-core/src/main/java/com/musediagnostics/taal/core/TaalAudioCapture.kt`,
inside `captureAudioToFile()`.

**What changed:** the per-second log line now aggregates across ALL buffer reads since the
last log line: `windowPeak=` (true max over the full ~1s interval) and `windowRms=` (RMS
over the full interval — much more robust for AGC/pumping detection than peak). The old
misleading single-buffer `peak=` field is GONE; new field names were chosen deliberately so
that any log containing `windowPeak=` is unambiguously post-fix, and any log containing
bare `peak=` is pre-fix (undersampled — treat those numbers with the red-team caveats).

This is a PERMANENT fix to pre-existing diagnostic code (marked with a `FIX 2026-09-02`
comment), NOT part of the removable PHASE1/PHASE2 debug blocks.

**Provenance rule for anyone analyzing logs later:**
- `peak=` in a log line → old undersampled semantics (~160ms snapshot/second).
- `windowPeak=`/`windowRms=` → new full-window semantics. Do not mix the two in one analysis.

## Task 2 — FIX: runtime source override via system property  ✅ DONE 2026-09-02

**Why:** red-team #2/#3 need all four configs (A/B/C/D) run back-to-back in ONE session with
the rig untouched. Previously each config required editing `debugForcedAudioSource`,
rebuilding, and reinstalling — which forces touching the rig and inserts minutes between
configs. Now the source can be switched from the PC between recordings, no rebuild:

```
adb shell setprop debug.taal.audiosource unprocessed        # config B
adb shell setprop debug.taal.audiosource voice_recognition  # config C
adb shell setprop debug.taal.audiosource default            # config D
adb shell setprop debug.taal.audiosource ""                 # back to stock chain (config A)
```

Read at every `buildAudioRecord()` via reflection on `android.os.SystemProperties`
(debug.* props are shell-settable on user builds). The property, when set to a valid value,
takes precedence over the in-code `debugForcedAudioSource` flag; invalid/empty values fall
through to the flag, then to the stock chain. Which override (property vs flag vs none) won
is logged at capture start under `TAAL_AUDIO_DEBUG`. Lives INSIDE the removable PHASE2
debug block — deleted together with the rest of the instrumentation eventually.

## Task 3 — re-test protocol (needs human + Samsung; not runnable by the model alone)

Goal: settle red-team #2 and #3 with one valid experiment.

1. **Rig:** clamp or tape BOTH the tone-playing device and the stethoscope chest piece so
   nothing is hand-held. Verify the tone player has no adaptive volume (play the tone for
   2 min beforehand; levels in a quick recording should be visually flat).
2. Build+install the current `app` debug build (Tasks 1+2 included). Keep
   `debugForcedAudioSource = null` in code — use ONLY the setprop mechanism.
3. Start `adb logcat -s TAAL_AUDIO_DEBUG` capture to a file.
4. Without touching the rig, record ~60s per config in this order, switching ONLY via
   setprop between recordings: A (prop empty), C, B, D, then A again (the repeat of A at the
   end controls for drift over the session).
5. Analysis (use `windowRms=`, not `windowPeak=`, as the primary stability metric):
   - Per config: n, min, max, mean, stddev, max/min in dB (awk one-liner below).
   - AGC verdict: if C/D show substantially larger within-recording RMS swing than the A
     runs under the identical rig, THAT is finally real evidence of source-side AGC.
   - Gain-ordering verdict: compare mean windowRms across configs; if C/D still sit ~5-10x
     above A/B with the rig controlled, red-team #3 is resolved and the original conclusion
     stands; if the gap shrinks into the noise, the original diagnosis was a rig artifact.
6. If an OnePlus becomes available: same protocol, at minimum config A, to refresh the
   4.9x baseline (red-team #6).

Analysis one-liner (adjust LOGFILE):
```bash
grep -E "windowRms=" LOGFILE | sed -E 's/.*t=([0-9]+)ms.*windowRms=([0-9.E-]+).*/\1 \2/' | awk '
$1>=2000{n++;s+=$2;if(m==""||$2<m)m=$2;if($2>x)x=$2;v[n]=$2}
END{mu=s/n;for(i=1;i<=n;i++){d=v[i]-mu;q+=d*d};
printf "n=%d min=%g max=%g mean=%g ratio=%.2fdB sd=%.1f%%\n",n,m,x,mu,20*log(x/m)/log(10),100*sqrt(q/n)/mu}'
```

## Task 4 — verification of Tasks 1+2  ✅ DONE 2026-09-02 (build+tests; on-device pending)

- `./gradlew :taal-core:compileDebugKotlin :app:testDebugUnitTest` — see status log below.
- On-device smoke test of the new log fields + setprop switching: **pending human recording**.

---

## Task 5 — full-chain logging  ✅ DONE 2026-09-02

Added so a single `adb logcat -s TAAL_AUDIO_DEBUG` describes a recording start to end.
All marked `FIX 2026-09-02`; all PERMANENT (outside the removable PHASE1/PHASE2 blocks)
except where noted.

In `TaalAudioCapture.kt`:
- `CAPTURE SESSION START` header: manufacturer/model/device, Android SDK, requested
  format, buffer frames, duration limit, output filename.
- `SOURCE SELECTED: <name> (id=N)` — permanent provenance line (new `selectedAudioSource`
  field set at every `buildAudioRecord()` return path; `audioSourceName()` helper).
- `audioEffects — <AGC|NoiseSuppressor|AEC> available=… wasEnabled=… setEnabled(false)rc=…
  nowEnabled=…` via new `logEffectState()` helper. Behaviour unchanged, logging new.
- Per-window line gained `windowDc`, `windowSamples`, `windowMs`, `effRateHz`, `clipped`.
- `read()` negative error codes are now logged (deduped by code) and counted; zero-length
  reads counted. Previously a hard read failure was indistinguishable from silence.
- `CAPTURE SESSION SUMMARY`: duration, samples, effective vs nominal rate, session
  peak/RMS, clipped count, read tallies (total/positive/zero/error), bytes, disconnect.
- `stopRecording()` logs caller-initiated stops (distinguishes manual stop from
  duration-limit/disconnect stop).

In `TaalRecorder.kt` (same `TAG`, so one logcat filter covers the whole chain — note this
file is also being edited by the concurrent hum/rumble work; these additions are in
separate regions):
- `DSP CONFIG — preFilter=… preAmpDb=… humRumbleFilter=… recordingTimeSec=… filteredFile=…`
- `FILTERED PATH RESULT — maxFilteredPeak=… silentThreshold=… silentVerdict=… ` at capture
  completion, so the raw vs post-DSP levels can be compared directly.

## Findings from the first full-chain recording (2026-09-02, Samsung SM-A066B, ~23.5s)

1. **Framework AGC ruled out.** `AGC available=true wasEnabled=false setEnabled(false)rc=0
   nowEnabled=false` (NoiseSuppressor identical). The framework AGC exists but was ALREADY
   OFF before we disabled it — our call is a no-op. Therefore the framework AGC cannot
   explain the config C/D "pumping". Any real gain adaptation must live inside the vendor
   HAL behind the AudioSource, which `android.media.audiofx` cannot see or control. This
   converts a documented hypothesis into a measured fact.
2. **Red-team #1 empirically confirmed — the undersampled log WAS inflating variance.**
   Same recording, same data, two metrics:
   - `windowPeak` (now full-window): swing 9.67 dB, sd 35.2% of mean
   - `windowRms` (new): swing **4.45 dB**, sd **13.4%** of mean
   versus the OLD undersampled per-second `peak=`: config A 21.83 dB / 77.3%, C 16.85 dB /
   44.7%, D 22.00 dB / 58.5%. Fixing the sampling roughly HALVED the apparent peak swing,
   and RMS is ~2x steadier again. **Every pre-fix peak-based number in the original
   diagnostic and both AGC gate tests is inflated and must not be reused.**
   (Caveat: this recording is not the steady-tone rig, so it is not a strict
   apples-to-apples replacement for the gate tests — Task 3 still needs running.)
3. **Sample rate confirmed correct, continuously.** Session `effRateHz=44069` vs nominal
   44100 (0.07% low); per-window mean 44050 (min 40205 / max 45355 = buffer-quantisation
   jitter, expected). Independently corroborates the dumpsys "no resample" finding at
   runtime. The hardcoded `SAMPLE_RATE = 44100` is honest on this device — no downstream
   timestamp drift.
4. **Stream health perfect.** reads=147, positive=147, zero=0, errors=0;
   bytesWritten=2067408 == samples 1033704 x 2 exactly (no dropped audio).
5. **No DC offset, no clipping.** windowDc ~-4e-6; clippedSamples=0. Two more potential
   distorters of amplitude statistics ruled out.
6. **NEW USER-VISIBLE ISSUE — the silence detector false-positives on this device.**
   Raw sessionPeak=0.00418 -> `FILTERED PATH RESULT maxFilteredPeak=0.00365` against
   `SILENT_RECORDING_PEAK_THRESHOLD = 0.01` -> **silentVerdict=true**, i.e.
   `onSilentRecordingDetected` fires and the user gets a "silent recording" popup on a
   recording that captured signal. The threshold was evidently calibrated on a louder
   device (on a ~5x louder OnePlus the same signal would land ~0.018, safely above 0.01).
   NEEDS CONFIRMING what the acoustic source was for this particular recording before
   treating it as definitive — but the margin is clearly device-dependent and fragile.
   Also note the DSP chain REDUCED the peak (raw 0.00418 -> filtered 0.00365) despite
   +5 dB pre-amp, i.e. the HEART bandpass cut ~6 dB of peak.
7. **Pre-amp default is 5 dB, not 0** (`DSP CONFIG preAmpDb=5`). Affects the filtered path
   only (raw numbers stay clean) but must be held constant in any cross-phone comparison.

## Task 6 — silence-detector false positive  ✅ MITIGATED 2026-09-02 (dialog disabled)

**CONFIRMED BY THE USER as a real bug**, not a test artifact: the recording analysed above
was taken on the user's chest with clearly audible heart sounds, and the app still showed
the "Ready to Capture" dialog telling them to discard it. User's words: *"it was a clean
recording but it said device is ready to capture but it already did"*.

Mechanism: `TaalRecorder` fires `onSilentRecordingDetected` when the FILTERED peak never
exceeds `SILENT_RECORDING_PEAK_THRESHOLD = 0.01`. Measured: raw sessionPeak=0.00418 ->
filtered maxFilteredPeak=0.00365 = ~2.7x below the threshold. The constant was evidently
calibrated on a louder handset; this phone's capture level is ~5x lower, so a valid
recording reads as silent. Note the DSP chain REDUCED peak (0.00418 -> 0.00365) despite
+5 dB pre-amp — the HEART bandpass costs ~6 dB of peak.

**Action taken (what the user asked for): the dialog was COMMENTED OUT**, not deleted, in
`app/.../ui/pcgscale/PcgScaleRecordingFragment.kt` — the whole `onSilentRecordingDetected`
override is commented with a full explanation and doc pointers. The interface default is a
no-op, so no dialog appears on this screen. `taal-core` was NOT changed — the threshold
constant and the callback still exist and still fire for every other consumer.

**Deliberately NOT changed (ask before doing these):** the identical dialog also exists in
`app/.../ui/recording/RecordingFragment.kt`, `app/.../ui/calibrated/CalibratedRecordingFragment.kt`,
`app/.../ui/fulltimeon/FullTimeOnRecordingFragment.kt`, plus `taal-ui-kit`'s
`RecordingFragment.kt` and two `stemz-app` copies. Only the live PcgScale screen was
touched. Those other screens will still false-positive on this device.

**Proper fix, still open:** replace the fixed 0.01 constant with a noise-referenced /
capture-level-normalised threshold, then re-enable the dialog. Ties into open question (b)
about `MIN_FULL_SCALE`.

## Cross-thread note for the PCG noise-reduction work (2026-09-02)

`docs/notes/PCG_NOISE_REDUCTION_HANDOFF.md` §2 records an open caveat: if a device's true
USB capture rate were really 48kHz mislabeled as 44.1kHz, the Feature A notch centres
(50/100/150Hz) would land ~8.8% high. **Resolved for Samsung SM-A066B: it is genuinely
44100Hz.** Runtime measurement this session gave session `effRateHz=44069` (0.07% below
nominal) and per-window mean 44050, independently corroborating the earlier static dumpsys
check (`format client=… == dev=… 44100Hz`). So the hum/rumble notches are correctly centred
on this handset. Unverified on other devices — the same `effRateHz=` field in any capture
log now answers it per device, no extra instrumentation needed.

## Task 7 — full-journey logging (record -> save -> library -> playback)  ✅ DONE 2026-09-02

Instrumented the rest of the chain so one `adb logcat -s TAAL_AUDIO_DEBUG` covers the whole
user journey. All marked `FIX 2026-09-02`, all permanent, same tag everywhere.

- `taal-core/.../TaalPlayer.kt` (new `TAG`): `PLAYBACK setDataSource` (file/bytes/header
  rate/implied duration), `PLAYBACK prepare()` (requested vs actual track rate — catches
  wrong-speed playback), per-second `PLAYBACK — t=… windowPeak/windowRms`, a
  `PLAYBACK PASS SUMMARY` designed to be compared against `CAPTURE SESSION SUMMARY`, and a
  caller-initiated `stop()` line.
- `app/.../ui/recording/SaveRecordingFragment.kt` (new `TAG`): `SAVE START` (source paths +
  byte sizes + existence), per-file rename-vs-copy-fallback in `moveFile` (incl. a loud
  warning when a source is missing — previously a silent `return`), `SAVE RESULT`.
- `app/.../ui/library/SavedRecordingsFragment.kt` (new `TAG`): `LIBRARY LOAD` listing every
  file in `filesDir/saved` with sizes and whether it is LISTED (only `_filtered.wav` is),
  plus `LIBRARY onPlay`.
- `app/.../ui/pcgscale/PcgScalePlayerFragment.kt` (new `TAG`): `PLAYER SCREEN OPENED`
  (which files, isNewRecording), `PLAYER LOAD`/`PLAYER decoded` (rate, samples, duration,
  dataPeak/dataRms after pre-amp undo, **fullScale, minClamped, peakFillOfAxis**, bucket),
  Save/Discard taps, `setupPlayer` filter-skip decision, play/stop.
- `app/.../ui/pcgscale/PcgScaleReviewFragment.kt` (new `TAG`): `REVIEW LOAD`,
  `REVIEW decoded`, `REVIEW computeRenderPayload` (same scale diagnostics), denoise toggle
  with cache hit/miss, spectral-gate compute time, `setupPlayer` filter-skip, play/stop.
  Both fragments' "file does not exist" silent returns now log an error.

## Findings from the first full-journey run (2026-09-02)

**Device note: this run was on a DIFFERENT handset** — serial `R9ZL603L4RD`, whereas every
earlier measurement in this tracker came from `R9ZL600D7MF`. Same SM-A066B model.

Replicated on the second unit: source `UNPROCESSED` selected by the stock chain, routed to
the TAAL USB mic, 44100Hz/7056 frames, and **AGC + NoiseSuppressor + AEC all
`available=true wasEnabled=false`** — i.e. the framework effects were already off before we
touched them, on this unit too. Capture summary: `sessionPeak=0.01212 sessionRms=0.000382
effRateHz=44024` (nominal 44100), 211/211 positive reads, 0 errors, 0 clipped.

### 8. Coupling variation dominates level measurements (reinforces red-team #3)
Within this ONE recording, source fixed, the level swung from `windowPeak=0.0111` (t=11s) to
`0.0019` (t=33s) — ~6x, from chest contact/pressure alone. That is the same order as the
"between-source" differences the original diagnostic attributed to vendor gain. An early
reading in this session was briefly mistaken for a unit-to-unit difference and then
withdrawn when the level came back down. **Do not compare levels across recordings that are
not on a clamped rig.**

### 9. BUG — the same saved file renders at two different amplitudes
```
PLAYER (straight after recording): dataPeak=0.0075510 fullScale=0.005     fill=151.0% minClamped=true
REVIEW (opened from library):      dataPeak=0.0134277 fullScale=0.0077311 fill=173.7%
```
Identical file (2,972,384 bytes). Ratio 0.0134277/0.0075510 = **1.778 = 10^(5/20), exactly
the 5 dB pre-amp**. `PcgScalePlayerFragment` undoes the recorder's pre-amp before drawing;
`PcgScaleReviewFragment` cannot, because the saved file carries no record of the gain that
produced it (its own code comment says so). Result: trace amplitude and the axis caption
differ by 1.78x depending on which screen you view the recording through, so traces are not
comparable across screens. Fix direction: persist `preAmpDb` with the recording —
`RecordingEntity` already has a `preAmplification` column, but the review screen loads by
file path from `filesDir/saved` and never consults the DB.

### 10. BUG — trace overflows the Y axis on both screens
`peakFillOfAxis` = **151%** (player) and **174%** (review): the loudest beats are drawn
outside the axis and clip against the top/bottom edges. Falls out of rev-3 median-based
scaling (axis calibrated so *typical* beats fill 60%) combined with this recording's 6x
internal swing. `minClamped=true` on the player confirms `MIN_FULL_SCALE` (local 0.005) set
that axis rather than the measurement. Ties into open question (b).

### 11. BUG (parallel session's code) — spectral gate amplifies signal edges ~15x
Toggling Denoise on the review screen:
```
denoise=false -> dataPeak=0.0134278 dataRms=0.000567 fill=173.7%
denoise=true  -> dataPeak=0.2040358 dataRms=0.000589 fill=2055.6%
```
Peak up **15.2x**, RMS essentially unchanged (1.04x) — the signature of a few extreme
samples, not a gain change. Cause is the overlap-add normalisation in
`app/.../ecg/pcgscale/PcgSpectralGate.kt` line ~134:
```kotlin
output[i] = if (windowSqSum[i] > 1e-9f) output[i] / windowSqSum[i] else 0f
```
The guard only rejects a zero denominator. At the first and last hop only one Hann window's
tail contributes, so `windowSqSum` is tiny but >1e-9, and dividing by it amplifies those
edge samples enormously. Consequence for the user: the axis inflates (0.0077 -> 0.0099) and
the trace is drawn ~20x outside it, so denoise makes the display look broken.
**Their tests cannot catch this**: test 1 checks RMS reconstruction error, tests 2–3 look at
the middle of the signal, test 4 checks length only — nothing checks peak or the edges.
Fix directions: pad the signal by one frame at each end before the STFT and trim after; or
only normalise where `windowSqSum` is near its steady-state value and taper the rest; or
clamp the per-sample normalisation gain. Add a peak-ratio test (output peak must not exceed
input peak by more than a small factor) to prevent regressions.
**Owned by the PCG noise-reduction thread — reported here, NOT fixed by this session.**

### 12. Clean results worth recording
- **Save flow correct**: both files `renameTo` succeeded (no copy fallback), sizes preserved
  exactly, `SAVE RESULT ok=true`.
- **Library correct**: 2 files present, 1 listed (filtered listed, raw deliberately not).
- **Playback faithful and correct speed**: `requestedRate=44100 trackSampleRate=44100`;
  `PLAYBACK PASS SUMMARY playbackPeak=0.01263 playbackRms=0.00052` over the 7.4s played,
  against the file's own `dataPeak=0.01343 dataRms=0.000567` — consistent, no gain drift.
- **No double-filtering**: `preFilterOnPlayback=SKIPPED (already-filtered file)` on both
  screens, confirming the `_filtered` guard works.
- **Silence detector is a margin problem, confirmed**: this recording gave
  `maxFilteredPeak=0.0134 > 0.01` so `silentVerdict=false`, whereas the 2026-09-02 chest
  recording on the other unit gave 0.00365 and fired. Both were valid chest recordings —
  the threshold sits inside this device's normal range, so it fires on some and not others.

## Status log (append-only; newest last)

- 2026-09-02: Tracker created. Starting Task 1 (window-aggregated logging).
- 2026-09-02: Task 1 done — `captureAudioToFile()` now logs `windowPeak=`/`windowRms=`
  aggregated over the full interval; old `peak=` field removed (field names are the
  provenance marker, see Task 1). Permanent fix, marked `FIX 2026-09-02` in code.
- 2026-09-02: Task 2 done — `readDebugSourcePropertyOverride()` added inside the PHASE2
  debug block; `setprop debug.taal.audiosource ...` now switches source at runtime and
  takes precedence over the in-code flag. Which override won is logged at capture start.
- 2026-09-02: Also found+fixed: `debugForcedAudioSource` had been left at `DEFAULT` after
  the config-D AGC gate test — reset to `null` (stock).
- 2026-09-02: Task 4 (verification): `:taal-core:compileDebugKotlin`,
  `:app:testDebugUnitTest`, `:taal-core:testDebugUnitTest` (incl. the concurrent
  hum-rumble test) all green. `:app:assembleDebug` built and installed on the Samsung
  (SM-A066B over wireless adb); `getprop debug.taal.audiosource` confirmed empty.
  **Remaining: the on-device re-test itself (Task 3 protocol) — needs the human to run
  the clamped-rig A→C→B→D→A session and then the analysis one-liner in Task 3.**
- 2026-09-02: Task 5 done (full-chain logging, see above). First full-chain recording
  captured on Samsung SM-A066B — 7 findings recorded above; the headline ones are that
  the framework AGC was already disabled (so it cannot be the pumping cause), that fixing
  the undersampled log halved the apparent swing (red-team #1 confirmed), and that the
  silence detector false-positives at this device's levels.
- 2026-09-02: User confirmed the silent-recording verdict came from a real chest recording
  with audible heart sounds -> confirmed false positive. Task 6 done: dialog commented out
  on the PcgScale recorder screen only (app-side, taal-core untouched). Tests green
  (app + taal-core), APK installed on the Samsung. Read the concurrent
  `PCG_NOISE_REDUCTION_HANDOFF.md`; logged the cross-thread note that its 48kHz caveat is
  resolved for this device.
- 2026-09-02: Task 7 done (full-journey logging across TaalPlayer, SaveRecordingFragment,
  SavedRecordingsFragment, PcgScalePlayerFragment, PcgScaleReviewFragment). First full
  journey captured on handset R9ZL603L4RD — findings 8-12 above. Three bugs found:
  pre-amp display mismatch between player and review, Y-axis overflow on both screens, and
  a ~15x edge-amplification bug in the parallel session's PcgSpectralGate. Build + all
  tests green (app and taal-core).
- NEXT SESSION PICKUP POINT: run Task 3 with the human; then decide red-team #2/#3
  (real AGC vs rig artifact; real vendor gain vs noise) from the windowRms numbers; only
  then revisit the device-conditional source-preference fix (original Phase 2 of the
  implementation task, still NOT implemented, blocked on this evidence).
