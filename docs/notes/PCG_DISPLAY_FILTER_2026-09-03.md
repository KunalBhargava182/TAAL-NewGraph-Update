# PCG display-only conditioning chain — 2026-09-03 (branch `pcgscale-rev3`)

Zero-context handoff for the change that makes the PcgScale graphs (and the segmentation
report's chart) readable on both study phones without touching any recorded audio.
Read `docs/notes/PCG_NOISE_REDUCTION_HANDOFF.md` first (the 2026-09-02 features this builds
on) and `docs/notes/SAMSUNG_AUDIO_LEVEL_ATTENUATION_DIAGNOSTIC.md` for the capture-level
thread, which is separate and unchanged here.

## The three reports this answers

1. **"The Denoise toggle shrinks S1/S2."** The 2026-09-02 spectral gate subtracts
   1.5× the noise profile from every bin of every frame. The 20–80 Hz rumble that
   dominates the noise profile is also where S1 carries most of its energy, so S1's bins
   were being shaved (~1 dB measured on the study recordings, more visible on device where
   the `MIN_FULL_SCALE` floor then bit harder on the Samsung). Fix: **transient
   protection** — any STFT frame whose broadband RMS is ≥ 8 dB above the measured noise
   floor is passed through with gain 1 in every bin. Beats and murmurs above the floor are
   never gated; the gate acts only between events. Measured (paired OnePlus/Samsung
   recordings, offline sweep): −10 to −11 dB hiss in the gaps, **0.0 dB** change on S1/S2
   peaks (vs ~1 dB before). Power-subtraction variants were also tried and rejected (too
   gentle: −2 to −5 dB).
2. **"Random maxed-out amplitudes on the Samsung with nothing audible."** USB frame-drop
   glitches — documented as real on this hardware in `TaalAudioCapture.kt`'s buffer-size
   comment. New `PcgClickRemover`: an isolated 2 ms hop ≥ 6× the median peak of the
   surrounding ±50 ms with both neighbours below half of it, or a full-scale run shorter
   than 10 ms, is interpolated out. A real S1/S2 (30–100 ms, surrounded by its own energy)
   or a genuinely clipped loud beat (a long full-scale run) can never match.
3. **"Dense block vs clear lines; raw audio makes the segmentation chart unreadable."**
   Both are the same 20–80 Hz rumble + 50 Hz hum + hiss floor, drawn honestly by the
   min/max renderer. New **zero-phase** band-pass 20–500 Hz (rumble out, murmur energy
   kept) plus zero-phase notches at 50/100/150 Hz, applied to the display copy only.
   Zero-phase matters: every feature stays at its true time, so the 1 s grid boxes and the
   segmentation overlay remain exact.

## What was built

| File | Role |
|---|---|
| `app/.../ecg/pcgscale/PcgDisplayFilter.kt` (new) | `processOffline()` = click removal → zero-phase band+notches (`filtfilt` on RBJ biquads) → gate with `Params.DISPLAY`. `PcgLiveDisplayFilter` = causal twin (per-buffer click removal + 4th-order Butterworth cascade + notches, state carried across buffers) for the live recorder. `PcgClickRemover`, `BiquadCoeffs`, `BiquadState`. |
| `app/.../ecg/pcgscale/PcgSpectralGate.kt` (modified) | Gains a `Params` data class. `Params.DEFAULT` is byte-identical to the 2026-09-02 behaviour (guarded by a test); `Params.DISPLAY` adds transient protection (8 dB) and mid-diastole-only noise frames (quiet frames adjacent to a loud frame are excluded from the profile). |
| `PcgScaleReviewFragment.kt` | Denoise toggle now runs the full display chain instead of the bare gate. |
| `PcgScalePlayerFragment.kt` + `fragment_pcgscale_player.xml` | Gains the same Denoise toggle (parity — this is the screen seen right after recording). |
| `PcgScaleRecordingFragment.kt` | Live trace goes through `PcgLiveDisplayFilter` (always on; display only — the recorded files and the audio monitor are untouched; the 60%-fill scaler measures the cleaned signal so the axis matches what is drawn). |
| `ui/segmentation/SegmentationReportFragment.kt` | Chart (report + full-screen, via the shared ViewModel) draws `PcgDisplayFilter.processOffline(rawAudio)`; segmentation still runs on the untouched raw file. `stemz-app`'s copy of this fragment is NOT updated (it has no `ecg/pcgscale` package to import from) — port when `stemz` next syncs. |
| `PcgDisplayFilterTest.kt` (new, 8 tests), `PcgSpectralGateTest.kt` (+2) | Zero-phase timing, band edges, notch, click removal (glitch removed / beat and clipped beat untouched), full-chain beat preservation within 1 dB with ≥ 12 dB gap reduction, degenerate lengths, live-filter passband; DISPLAY-preset beat preservation within 0.3 dB; DEFAULT-preset identity. |

Parameters (all in code, all display-only): offline zero-phase HP 20 Hz, LP 500 Hz,
notches 50/100/150 Hz Q=30, gate k=1.5 / floor −18 dB / release 0.6 / quietest 30 % frames
/ transient protection 8 dB. The 500 Hz upper edge is the user's "450–600 Hz"
murmur-retention choice. **Live path is gentler on purpose**: HP 10 Hz, 2nd order (see
next section).

## Rev 2 same day — "the denoise shrank S1/S2", measured stage by stage

Per-stage effect on the 95th-percentile 50 ms hop peak (the S1/S2 height), paired study
recordings, zero-phase unless stated:

| Stage | OnePlus peak | OnePlus noise | Samsung peak | Samsung noise |
|---|---|---|---|---|
| HP 20 Hz only | −1.8 dB | −6.7 dB | −0.1 dB | −0.2 dB |
| LP 500 Hz only | 0.0 | 0.0 | −0.1 | −0.1 |
| Notches only | 0.0 | 0.0 | **−0.8** | +0.2 |
| Full band (offline, current) | −2.2 | −6.3 | −1.0 | −0.1 |
| Live causal 4th-order HP 20 (old live) | −1.4 | −4.6 | **−2.0** | +0.5 |
| Live causal 2nd-order HP 10 (new live) | −0.2 | −1.2 | −1.0 | +0.5 |

Reading: the OnePlus "shrink" is the high-pass removing sub-25 Hz rumble that was riding
on top of the beats (it takes 6.7 dB off the noise for the same reason) — the beat's own
energy is untouched. The Samsung "shrink" is the notches removing 50/60 Hz hum that was
riding on the beats. Neither is S1 being eaten; both are correct. In the app the axis
re-derives from the cleaned peaks, so on screen the beats stay at the target fill; the
fixed-axis comparison figure exaggerated the effect. What WAS a genuine skew: the live
causal 4th-order 20 Hz corner's group-delay dispersion smeared S1 onsets (Samsung −2.0 dB
vs −1.0 zero-phase). Fixed by making the live high-pass 2nd order at 10 Hz
(`PcgDisplayFilter.Params.LIVE`) — taal-core's own 20 Hz HEART band-pass already shapes
the stream the recorder draws, so the live stage only needs to catch what leaks through
that filter's shallow skirt. Offline keeps the 20 Hz zero-phase spec.

Also this rev: the chain is ported into **`stemz-app`** (copied `PcgDisplayFilter.kt` +
`PcgSpectralGate.kt`; live filter in its recorder; cleaned background in its segmentation
report/full-screen chart — the study build's murmur view). stemz's Player/Review keep no
denoise toggle (Kunal removed it deliberately per `PCGSCALE_WORK_REFERENCE.md`) — apply
the offline chain there always-on if wanted; one-line change each.

## Not changed (deliberately)

- `taal-core`: nothing. Feature A's hum/rumble filter (affects the recorded file) is
  untouched and still opt-in; the new chain is app-side and display-only.
- `MIN_FULL_SCALE = 0.005`: unchanged. With hiss gated out, the Samsung's measured peak
  statistic rises relative to the floor, so the clamp should bite less — but the real fix
  for the Samsung's level remains the capture-source change in the diagnostic doc.
- The remaining Samsung-vs-OnePlus ~25 % peak difference is a capture-level gain
  difference (see diagnostic: UNPROCESSED vs VOICE_RECOGNITION), not a display issue; at
  the Samsung's current level it is also recording with ~7 effective bits, which no
  display filter can recover.

## On-device checklist

- Recorder (both phones): live trace shows clean beats on a thin baseline; no maxed-out
  spikes on the Samsung; caption/axis behave as before; recorded files identical in
  content to before this change (the filter is display-only).
- Player and Review: Denoise OFF = previous look; ON = beats the SAME height or larger
  (axis rescales to the cleaned peaks), gaps visibly quieter, "· denoised" in the caption;
  toggling back is instant; scrolling/labels unaffected; playback audio unchanged.
- Segmentation report: chart readable, S1/S2 bands still land on the beats (zero-phase —
  if they ever appear shifted, that is a bug in this change, not in segmentation).
