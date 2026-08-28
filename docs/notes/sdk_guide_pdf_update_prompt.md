# Prompt to paste into Claude web

Upload the old "TAAL SDK Integration Guide" PDF (Version 2.0.0) in the same
message as this prompt.

```
I've uploaded our TAAL SDK Integration Guide (Version 2.0.0) as a PDF. I need
you to produce an updated version of this exact same document with new
content added in the correct places — nothing else should change.

CRITICAL RULES — read carefully:
- This document already works very well. Do NOT rewrite, reword, reformat,
  or restructure anything that isn't listed below. Copy all existing text,
  tables, code blocks, headers, footers, and page structure exactly as-is.
- ONLY insert the new content given below, at the exact section each item
  specifies. Match the surrounding document's existing formatting style
  precisely for every insertion (same table styling, same code-block styling,
  same heading level/numbering style, same "Note:" callout style already
  used elsewhere in the document).
- Do NOT renumber any existing top-level section (do not touch sections 1–17
  as numbered). Where a new item is a new subsection, add it as the next
  available sub-number under its parent section (e.g. a new subsection under
  Section 10 becomes 10.3, 10.4, etc. — this does not affect Section 11, 12,
  etc.).
- Keep the version number as "2.0.0" everywhere in the document — do NOT bump
  it to 2.1.0. In the Version History table, keep the existing 2.0.0 row and
  append a new line inside its Description cell (do not add a new row),
  starting with "Update:" summarizing the six additions below.
- Give me back the complete document (same length as before, plus the new
  additions), as a downloadable file, preserving the original page layout,
  header/footer, and the confidentiality notice on the last page.

Here are the exact insertions, in document order:

──────────────────────────────────────────────────────────────
1) VERSION HISTORY TABLE — update the existing "2.0.0" row only
──────────────────────────────────────────────────────────────
Keep the existing Description text for that row exactly as it is, and append
a new paragraph inside the same cell:

    Update: Added custom bandpass filter (setCustomBandpass). Added
    on-screen acoustic placement diagrams. Saved recordings now also
    copied to device storage, with Play/Share/Delete actions on the
    saved recordings list. Improved recording reliability on tablet
    devices.

Do not change the Version ("2.0.0") or add a new row. You may optionally
add "(Updated July 2026)" next to the existing Date if there's room, but
this is not required.

──────────────────────────────────────────────────────────────
2) SECTION 4 "FEATURES" — add 5 new bullets at the end of the existing list
──────────────────────────────────────────────────────────────
After the existing last bullet ("Portrait-only orientation enforced on all
SDK Activities"), add:

    - Custom frequency range filter — user-defined low/high cutoff in Hz
    - On-screen acoustic placement diagrams — info ("i") button on the
      recording screen shows anatomical stethoscope-placement guidance
      per filter
    - Saved recordings automatically copied to device storage (Music/Taal
      Saved Audios) in addition to app-private storage
    - Saved recordings list with Play, Share, and Delete actions per
      recording
    - Improved recording reliability on tablet devices across all brands

──────────────────────────────────────────────────────────────
3) SECTION 5 "SPECIFICATIONS" TABLE — add one new row
──────────────────────────────────────────────────────────────
Insert a new row directly after the "Pre-amp Range" row (before "Max
Recording Time"):

    Item: Custom Filter Range
    Description: 1 Hz – 24,000 Hz (user-defined low/high cutoff)

──────────────────────────────────────────────────────────────
4) SECTION 7 "SETUP & INSTALLATION", Step 4 (AndroidManifest.xml)
──────────────────────────────────────────────────────────────
In the manifest code block, add this permission line right after the
existing USB_PERMISSION line and before the <uses-feature> block:

    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />

Then, right after the existing note at the end of Section 7 ("Note: Both SDK
activities are locked to portrait orientation. This cannot be overridden."),
add a second note in the same style:

    Note: The WRITE_EXTERNAL_STORAGE permission (maxSdkVersion 28) is only
    required if you want saved recordings copied to the device's Music
    folder on Android 7–9 (API 24–28). It is not required on Android 10+
    (API 29+). The UI-Kit requests this permission automatically at runtime
    the first time the user saves a recording — if denied, the recording is
    still saved to app-private storage.

──────────────────────────────────────────────────────────────
5) SECTION 8 "SDK REFERENCE — TaalRecorder"
──────────────────────────────────────────────────────────────
(a) Right after the "Import:" code block and before "8.1 State Diagram",
add a note in the same style as other notes in the document:

    Note: Audio capture has been hardened for tablet devices (audio source
    fallback chain, system Automatic Gain Control / Noise Suppression /
    Echo Cancellation disabled, USB device explicitly locked on API 28+).
    This is fully internal — no public API changed, and it applies
    automatically on all supported Android versions and both phones and
    tablets.

(b) In "8.1 State Diagram", in the line listing configuration methods valid
in INITIAL state, insert setCustomBandpass(lowCut, highCut) right after
setPreFilter(preFilter) and before setPreAmplification(preAmpInDB). Then add
a note right after that list:

    Note: setPreFilter() and setCustomBandpass() both configure the same
    internal bandpass filter — call only one of them per recording session.
    Whichever is called last takes effect.

(c) In "8.2 Common Usage", right after the line
`taalRecorder.setPreAmplification(5)  // 0–30 dB`, add these lines to the
code sample:

    // Alternative to setPreFilter(): a user-defined frequency range.
    // Do not call both — the last one called wins.
    // taalRecorder.setCustomBandpass(20.0, 1000.0)  // lowCut, highCut in Hz

(d) In "8.3 Public Methods" table, insert a new row directly after the
setPreFilter(filter: PreFilter) row:

    Method Name: setCustomBandpass(lowCut: Double, highCut: Double)
    Valid States: INITIAL
    Description: Sets a custom bandpass filter range in Hz, overriding the
    preset filter. lowCut is clamped to a minimum of 1 Hz, highCut to a
    maximum of 24,000 Hz. If highCut ends up ≤ lowCut, the call is silently
    ignored and the previous filter configuration remains active. Mutually
    exclusive with setPreFilter() — the last call wins.

──────────────────────────────────────────────────────────────
6) SECTION 10 "SDK REFERENCE — TaalRecorderActivity (UI-Kit)"
──────────────────────────────────────────────────────────────
(a) In "10.1 Launch the Recorder", right after the code sample (after the
preFilter/preAmplification/recordingTimeSeconds example), add a note:

    Note: The preFilter parameter only accepts HEART, LUNGS, BOWEL,
    PREGNANCY, or FULL_BODY. The Custom filter (user-defined Hz range) is a
    manual, in-screen-only selection — it cannot be pre-selected at launch.
    See Section 10.3.

(b) After "10.2 Activity Result" (and its result-code table), add three new
subsections — 10.3, 10.4, 10.5 — using the same heading style as 10.1/10.2:

    10.3 Custom Filter (In-Screen)

    Alongside the five preset filter chips (Heart, Lungs, Bowel, Pregnancy,
    Full Body), the recording screen includes a sixth "Custom" chip.
    Selecting it reveals a range slider (0–24,000 Hz) plus two manual Hz
    entry fields (Low Cut / High Cut) kept in sync with the slider.

    Recording is blocked with an alert dialog if the fields are left blank,
    set to 0 Hz, or if Low Cut is greater than or equal to High Cut. Like
    the preset filter chips, the Custom chip and its range panel are
    automatically disabled while a recording is in progress.

    10.4 Acoustic Placement Diagrams

    The recording screen includes an info ("i") icon in the top bar.
    Tapping it opens a dialog with a swipeable image carousel (with page
    indicator dots) showing anatomical stethoscope-placement diagrams for
    whichever filter is currently selected.

    The images are bundled inside taal-ui-kit.aar as drawable resources —
    no additional assets or setup are required from the integrator. If no
    images exist for a given filter, the dialog shows a "No placement
    images found" fallback message.

    10.5 Saved Recordings Screen

    After saving a recording, the user lands on a Saved Recordings list.
    Each item has three actions:

    - Play — opens the recording in the playback screen
    - Share — shares the .wav file via the system share sheet, using a
      FileProvider bundled inside taal-ui-kit (authority:
      ${applicationId}.taaluikit.fileprovider). No manifest configuration
      is required from the integrator.
    - Delete — permanently deletes both the _filtered.wav and _raw.wav
      files for that recording, after a confirmation dialog

    Saved recordings are stored in two places simultaneously — see Section
    12 for the file naming format and device storage copy behavior in
    detail.

──────────────────────────────────────────────────────────────
7) SECTION 12 "DUAL FILE OUTPUT SYSTEM"
──────────────────────────────────────────────────────────────
Right after the existing "File Paths (UI-Kit)" paragraph ("Handled
automatically. RESULT_FILE_PATH always returns the _filtered.wav path. Both
_raw.wav and _filtered.wav are saved together to filesDir/saved/."), add two
new subsections in the same plain-header style already used for "Why Two
Files?" and "File Paths (Core SDK)":

    Saved Recording Filename Format

    Saved recordings embed the filter as a filename prefix:

        {FILTER}_{user-entered name}_filtered.wav
        {FILTER}_{user-entered name}_raw.wav

        e.g. HEART_20260703_143210_filtered.wav

    No separate metadata (.meta) file is written — the filter is derived
    entirely from the filename prefix, both for icon display in the Saved
    Recordings list and for skipping re-filtering during playback.

    Device Storage Copy

    In addition to the app-private copy above, saved recordings are copied
    to the device's shared storage at Music/Taal Saved Audios/, making them
    visible in the phone's file manager, other media apps, and when
    connected to a PC.

    Add a small two-column table here:
    Android Version: API 29+ (Android 10+) | Behavior: Uses MediaStore. No
    extra permission required.
    Android Version: API 24–28 (Android 7–9) | Behavior: Requires
    WRITE_EXTERNAL_STORAGE. The SDK requests this automatically at runtime
    the first time the user saves a recording.

    Then this paragraph:

    This copy is best-effort: if it fails (permission denied, low storage),
    the recording remains safely saved in app-private storage — no data is
    lost, and the user sees a toast message if the device copy was skipped.

──────────────────────────────────────────────────────────────
8) SECTION 16 "TECHNICAL LIMITATIONS"
──────────────────────────────────────────────────────────────
Add a new numbered item 6 at the end of the existing list (items 1–5 stay
exactly as they are):

    6. Custom filter range validation: setCustomBandpass() does not throw an
    exception for an invalid range (highCut ≤ lowCut after clamping) — the
    call is silently ignored and the previous filter configuration remains
    active. Validate lowCut < highCut in your own UI before calling it.

──────────────────────────────────────────────────────────────

That is everything. Please double-check afterward that every other section
(1, 2, 3, 6, 9, 11, 13, 14, 15, 17, and the confidentiality notice) is
character-for-character identical to the uploaded original, and that the
version number reads "2.0.0" throughout.
```
