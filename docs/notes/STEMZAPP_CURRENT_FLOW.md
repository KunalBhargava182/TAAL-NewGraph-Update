# stemz-app — Current Screen Flow

Snapshot of `stemz-app`'s actual navigation as configured right now on `StemzAppBranch`
(`stemz-app/src/main/res/navigation/nav_graph.xml`). Verified against the live
`app:startDestination` attribute and every `<action>` element — not assumed.

---

## 1. What's actually live (the default flow)

```
App launch
  → PcgScaleRecordingFragment   ("TAAL Recorder" — app:startDestination)
      • Basic/Hard heart filter toggle + Custom (top bar)
      • Hum filter switch (opt-in, default off)
      • Record → 14s hard auto-stop (or manual Stop)
      → PcgScalePlayerFragment   ("Review Recording", Save/Discard bar shown)
          • Analyze Heart Sounds → SegmentationReportFragment
            (gated: only shows once the file is inside filesDir/saved/ — in
            practice this screen is always a fresh, not-yet-saved recording,
            so this button is wired but never actually visible right now)
          • Save  → SaveRecordingFragment (name it) → SavedRecordingsFragment
                     (pops cleanly back to PcgScaleRecordingFragment underneath)
          • Discard → back to PcgScaleRecordingFragment
      • Folder icon → SavedRecordingsFragment (skip recording entirely)

SavedRecordingsFragment
  • Tap a recording → PcgScaleReviewFragment   ("Review Recording", no Save/Discard)
      • Shows the actual filename you typed when saving, as the title
      • Denoise switch (opt-in, display-only — playback/file untouched either way)
      • Play/scroll through the waveform
      • Analyze Heart Sounds (only if a saved raw file exists — always true here)
          → SegmentationReportFragment
              • Runs TaalCardiacSegmentation on the RAW file (never the filtered one)
              • Chart shows a PcgDisplayFilter-conditioned copy of that same audio
                (click/USB-glitch removal, zero-phase 20–500Hz band) — segmentation
                itself is unaffected, since the filter is zero-phase
              • Back (on-screen button or device/gesture back) → always returns to
                SavedRecordingsFragment specifically, not just "up"
  • Share icon → share sheet (clean filename, generic file type — avoids some apps,
    e.g. WhatsApp, recompressing it — though WhatsApp itself still does regardless)
  • Delete icon → confirm → removed
```

**Grid legend on all three PcgScale screens:** always reads exactly
`"1 large box = 1 s · 1 small box = 0.2 s"` — no Y-axis/scale info shown on screen
(still logged under the `TAAL_AUDIO_DEBUG` tag, just not displayed).

---

## 2. What exists in the app but is NOT reachable from the live UI

These screens are real, built, and wired with their own internal navigation — but
**nothing currently live links to them**. They're either deep-link-only or fully
orphaned (not even a deep link).

| Screen family | How to reach it | Notes |
|---|---|---|
| **Calibrated screens** (`calibratedRecordingFragment` → `calibratedPlayerFragment` → `dpiCalibrationFragment`) | Deep link only: `adb shell am start -a android.intent.action.VIEW -d "stemzapp://calibrated" <applicationId>` | mm-accurate ECG paper grid, DPI calibration, pinch-zoom — an earlier design, superseded by PcgScale for the live flow |
| **PcgScale screens (redundant 2nd entry point)** | Deep link: `adb shell am start -a android.intent.action.VIEW -d "stemzapp://pcgscale" <applicationId>` | Same screens as §1 — this deep link is now redundant since PcgScale is already the default, kept from when it wasn't |
| **FullTimeOn screens** (`fullTimeOnRecordingFragment` → `fullTimeOnPlayerFragment`) | **Nothing** — zero inbound actions, no deep link either | A clone of the Calibrated screens under a different name (2026-08-20). Completely orphaned right now; would need a deep link or nav-graph action added to reach it at all |
| **Production `recordingFragment`/`playerFragment`** (the original, pre-PcgScale/pre-Calibrated screens) | **Nothing live** — only reachable from the auth chain below, which is itself unreachable | Still fully functional code, just not wired to anything the user can currently tap |
| **Auth chain** (`splashFragment` → `signInFragment`/`loginFragment`/`otpFragment`/`setPinFragment`/`pinConfirmedFragment`/`fingerprintSetupFragment`/`fingerprintConfirmFragment`/`pinLoginFragment`) | **Nothing** — `splashFragment` isn't the start destination and nothing points to it | Fully built sign-in/PIN/fingerprint flow, entirely bypassed since the app launches straight into the recorder |
| **`recordingLibraryFragment`, `editRecordingFragment`, `testRecordingFragment`/`testPlayerFragment`, `sharedRecordingsFragment`** | Only reachable from `recordingFragment`'s menu (itself unreachable) or nowhere at all | Patient-tied recording library, in-progress test screens |
| **`profileFragment`, `changePinFragment`, `faqFragment`, `privacyPolicyFragment`, `subscriptionFragment`, `userManualFragment`, `aboutUsFragment`** | Same — hang off `recordingFragment`'s drawer menu, which is unreachable | Info/settings screens |

**In short: the live app is just §1.** Everything in this section is real, compiled
code sitting in the nav graph, reachable only by adb deep link (Calibrated, redundant
PcgScale) or not reachable at all right now (FullTimeOn, the whole auth chain, and
everything that hangs off the production recorder's drawer menu).

---

## 3. Shared destinations (reached from more than one recorder family)

| Destination | Reached from |
|---|---|
| `saveRecordingFragment` ("Save Recording", name-it screen) | `playerFragment`, `calibratedPlayerFragment`, `fullTimeOnPlayerFragment`, `pcgScalePlayerFragment` |
| `savedRecordingsFragment` | `recordingFragment`, `calibratedRecordingFragment`, `fullTimeOnRecordingFragment`, `pcgScaleRecordingFragment`, and after any save via `saveRecordingFragment` |
| `segmentationReportFragment` | `playerFragment`, `pcgScalePlayerFragment`, `pcgScaleReviewFragment` |
| `equalizerFragment` | Still a nav destination for `calibratedPlayerFragment` and `fullTimeOnPlayerFragment` — **removed** from `playerFragment`/`pcgScalePlayerFragment`/`pcgScaleReviewFragment`'s UI (button deleted; the nav action to it was left in `nav_graph.xml`, just unused from those three) |
| `addPatientFragment` | `playerFragment`, `calibratedPlayerFragment`, `fullTimeOnPlayerFragment`, `pcgScalePlayerFragment`, `testPlayerFragment` |

`saveRecordingFragment` pops back to whichever recorder screen actually sent it there
(`popUpToDestination` bundle arg, default `recordingFragment`) — this was a real bug
fixed this branch: it used to be hardcoded to `recordingFragment` regardless of caller,
which broke the back stack for the PcgScale flow once that became the default screen.
