# audio-diagnostics-2026-09 — Branch Changelog

Chronological list of everything done on this branch, with date/time (IST, +0530).
Kept up to date as work continues — add new entries at the bottom rather than editing
old ones, so this stays an accurate record of what happened when.

---

## 2026-08-28

**14:58 — `d5ed39e`** — PcgScale rev 3: peak-calibrated median Y-scale; re-apply all
four ledger fixes (Save routing, grid end-clamp, halved trace widths — see
`docs/notes/PCGSCALE_LOCAL_FIXES_LEDGER.md`).

## 2026-09-02

**15:45 — `8044aa5`** — PcgScale: lower `MIN_FULL_SCALE` from 0.02 to 0.005.

**15:45 — `0d44eac`** — PCG noise reduction: hum/rumble filter + spectral-gate
denoise added to `AudioFilterEngine` (both default OFF — zero behavioral change for
existing consumers until opted in).

**15:45 — `2e5c11e`** — Audio capture/playback diagnostic logging added, tag
`TAAL_AUDIO_DEBUG` (see `docs/notes/AUDIO_DIAGNOSTIC_REMEDIATION_TRACKER.md`).

**~17:30–17:58 — Heart Sound Segmentation screen changes** (`app` + `stemz-app`, kept
in sync):
- Hid the "Trustworthy segmentation" success-state status row, the "Download Report"
  button, and the "Research/test output only — not a medical device." disclaimer
  (report screen + full-screen chart) — hidden via `visibility="gone"`/code, not
  deleted, so they can come back easily.
- Added a back button (new top bar) to the segmentation report screen; wired both the
  on-screen button and the hardware/gesture back action to always return to Saved
  Recordings specifically, not just "up" (which would land on Player instead, since
  the real chain is Saved Recordings → Player → Segmentation Report).
- `PlayerFragment`'s "Review Recording" title now shows the actual saved recording's
  name (stripped of the internal `{FILTER}_..._filtered` naming) when reviewing an
  already-saved file; stays "Review Recording" for a brand-new, not-yet-saved take.
- Swapped the "Analyze Heart Sounds" button's icon from `ic_heart` to a new
  `fragment_player.xml` vector (segmented-bars glyph) and enlarged it 40dp → 52dp.

## 2026-09-03

**~00:43 — `RecordingLibraryFragment` share fix** (`app` + `stemz-app`): fixed the
share button using the wrong FileProvider authority (`.provider` instead of the
manifest's actual `.fileprovider`), which was silently crashing every tap there
("Unable to share recording"). Also changed the share Intent's MIME type from
`audio/*` to `application/octet-stream` to stop apps that recompress recognized audio
shares (WhatsApp among them) from doing so.

**~01:06 — `SavedRecordingsFragment` share fix** (`app` + `stemz-app`): same MIME-type
change as above, plus the share now copies the file to a cleanly-named temp file
first, so the recipient sees the actual name the user typed when saving (e.g.
`JohnDoe.wav`) instead of the internal on-disk name (`HEART_JohnDoe_filtered.wav`).
Also fixed a `HEART_HARD`/`HEART` filename-prefix collision bug in `stemz-app`'s
`extractFilterName` (same class of bug already fixed in `SavedRecordingAdapter.kt`)
that this new code path would otherwise have inherited.

**~01:19 — Public save folder renamed** (`app` + `stemz-app`): the on-device public
copy every saved recording is auto-copied to (already existed, runs by default with
no extra toggle — MediaStore on API 29+, direct write + runtime permission on API
24–28) moved from `Music/Taal Saved Audios` to **`Music/Taal Saved Recordings`**.

**Open issue, not yet resolved:** WhatsApp still appears to transcode shared `.wav`
files to AAC and drop the filename even with the `application/octet-stream` MIME type
fix above — it looks like WhatsApp is sniffing the file's content/extension itself
rather than trusting the declared Intent type. The two known reliable workarounds
(strip the `.wav` extension before sharing, or zip the file before sharing) both trade
away "tap-to-play directly inside WhatsApp" for guaranteed original bytes/filename.
Deferred pending a decision on which trade-off is acceptable.
