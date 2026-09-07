# SUPERMASTER_VISUALIZERTAAL_APP.md — `visualizertaal-app` Module Reference

**Read this before touching any code in `visualizertaal-app`.** Verified directly against source as of 2026-09-08. Every claim below was checked against the live file at the cited path/line — this is not a copy of the older `docs/notes/VisualizerTaal-App.md` (dated 2026-08-10), though that document turned out to be accurate on every point checked; it is retained separately and can be treated as consistent with this one. The module's only git commit is `7481a0b` ("Add visualizertaal-app module wired to taal-core/taal-ui-kit SDKs"), 2026-08-14 — there has been no further work on this module since.

---

## 1. Module Overview

`visualizertaal-app` is a **standalone Android application** (its own APK, its own `MainActivity`, its own launcher icon) that offers two ways to use the TAAL USB digital stethoscope from one app:

1. **Basic TAAL** — the plain filtered-recording experience, launched by directly starting `taal-ui-kit`'s own `TaalRecorderActivity`/`TaalPlayerActivity` (no code of its own beyond the launch/relaunch glue).
2. **VisualizerTaal** — this module's actual purpose: a guided, point-by-point auscultation recorder for **Heart** (4 valves) and **Lungs** (20 points), organized into numbered, resumable **Sessions** with their own Room-backed persistence, file-naming convention, and review/share/delete UI.

It is included in the root `settings.gradle.kts` at line 26 (`include(":visualizertaal-app")`) and lives at `E:\AndroidProjects\TaalDemoApp\visualizertaal-app`. It is **the only app module in the monorepo that depends on both `taal-core` and `taal-ui-kit`** — confirmed directly in its `build.gradle.kts` (see §3). `app`, `lungs-app`, and `stemz-app` each depend on `taal-core` only (with their own UI); `visualizertaal-app` reuses `taal-ui-kit`'s prebuilt recorder/player Activities for the "Basic TAAL" path and builds everything else directly on `taal-core`.

---

## 2. Module Config

Source: `visualizertaal-app/build.gradle.kts:1-99`, `visualizertaal-app/src/main/AndroidManifest.xml:1-46`.

| Field | Value | Citation |
|---|---|---|
| `namespace` / `applicationId` | `com.musediagnostics.taal.visualizer` | build.gradle.kts:8,12 |
| `compileSdk` | 34 | build.gradle.kts:9 |
| `minSdk` | 24 | build.gradle.kts:13 |
| `targetSdk` | 34 | build.gradle.kts:14 |
| `versionCode` / `versionName` | 1 / "1.0" | build.gradle.kts:15-16 |
| `viewBinding` | enabled | build.gradle.kts:41 |
| `isMinifyEnabled` (release) | `false` | build.gradle.kts:23 |
| Java/Kotlin target | 1.8 | build.gradle.kts:32-38 |
| App name | `VisualizerTaal` | strings.xml:3 |
| App icon | `@drawable/ic_app_icon` (flat vector, no adaptive-icon layers) | AndroidManifest.xml:16,18 |
| Application class | `.VisualizerApplication` (empty subclass, no overrides) | AndroidManifest.xml:14; VisualizerApplication.kt:1-5 |
| Entry Activity | `.ui.MainActivity` (single activity, `exported="true"`, `LAUNCHER`) | AndroidManifest.xml:23-32 |
| `allowBackup` | `false` | AndroidManifest.xml:15 |
| `screenOrientation` | `portrait` (app-level and re-declared on the Activity) | AndroidManifest.xml:21,26 |
| Permissions | `RECORD_AUDIO`, `INTERNET`, `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`) | AndroidManifest.xml:4-7 |
| `uses-feature` | `android.hardware.usb.host`, `required="false"` | AndroidManifest.xml:9-11 |
| Manifest provider | `.util.VisualizerFileProvider` — see §7 | AndroidManifest.xml:34-42 |
| DB name | `"visualizer_database"` | VisualizerDatabase.kt:26 |
| DB version | **2** | VisualizerDatabase.kt:12 |
| DB migration strategy | `fallbackToDestructiveMigration()` — **no real `Migration` exists**; any future schema bump wipes `sessions`/`recordings` rows on next launch (WAV files on disk are untouched) | VisualizerDatabase.kt:27 |

---

## 3. Dependency on taal-core + taal-ui-kit (how each is used)

`build.gradle.kts:61-99` declares, among others:

```kotlin
implementation(project(":taal-core"))
implementation(project(":taal-ui-kit"))
```
(build.gradle.kts:63,65 — comments literally label these "TAAL Core SDK (audio engine...)" and "TAAL UI Kit (reusable 'Basic TAAL' recorder/player screens with filters)")

Other notable deps: `androidx.navigation:navigation-fragment-ktx/-ui-ktx:2.7.6` (build.gradle.kts:75-76), `lifecycle-viewmodel/-livedata/-runtime-ktx:2.7.0` (78-81), `kotlinx-coroutines-android/-core:1.7.3` (84-85), `androidx.room:room-runtime/-ktx:2.6.1` + `ksp room-compiler:2.6.1` (88-90, requires the `com.google.devtools.ksp` plugin declared at build.gradle.kts:4), and `com.github.PhilJay:MPAndroidChart:v3.1.0` (build.gradle.kts:93) for the waveform charts. No Room `Patient` entity, no TFLite, no Google Drive integration — this module is deliberately lighter than `lungs-app`.

**How `taal-core` is used (directly, everywhere the guided flow needs raw audio):**
- `RecordingFragment.kt:23-24` imports `com.musediagnostics.taal.TaalRecorder` and `com.musediagnostics.taal.core.RecorderState` directly and drives the whole capture lifecycle itself (see §5) — it does **not** use `taal-ui-kit`'s `RecordingFragment`.
- `PlayerFragment.kt:20-21` imports `com.musediagnostics.taal.InvalidFileNameException` and `com.musediagnostics.taal.TaalPlayer` directly for playback — again, its own fragment, not `taal-ui-kit`'s `PlayerFragment`.
- `RecordingFragment.kt:25` also uses `com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver` (from `taal-core`) to color a device-connection icon.
- `domain/RecordingPoint.kt:3,21-22` imports `com.musediagnostics.taal.PreFilter` (from `taal-core`) — `PointSetType.HEART`/`LUNGS` each carry a `PreFilter.HEART`/`PreFilter.LUNGS` value that's passed straight into `TaalRecorder.setPreFilter()` (RecordingFragment.kt:261).

**How `taal-ui-kit` is used (only for the "Basic TAAL" shortcut on the Home screen):**
- `HomeFragment.kt:11-12` imports `com.musediagnostics.taal.uikit.TaalPlayerActivity` and `com.musediagnostics.taal.uikit.TaalRecorderActivity`.
- `HomeFragment.kt:42-51`: tapping **Basic TAAL** launches `TaalRecorderActivity.getIntent(context, preFilter = "HEART", preAmplification = 5, recordingTimeSeconds = 300)` via an `ActivityResultContracts.StartActivityForResult` launcher.
- `HomeFragment.kt:21-30`: on `RESULT_OK`, it reads `TaalRecorderActivity.RESULT_FILE_PATH` from the result Intent and, if non-empty, starts `TaalPlayerActivity.getIntent(requireContext(), filePath)`.
- This is the **only** place `taal-ui-kit` is touched anywhere in the module's own source; the Basic TAAL flow never enters `visualizer_nav_graph.xml` — it's a plain Activity launch/relaunch pair, entirely separate from the guided-session nav graph.
- **Known gap** (confirmed, not a bug per se): the Basic TAAL `preFilter` is **hardcoded to `"HEART"`** at HomeFragment.kt:46 — there is no filter picker before launching `TaalRecorderActivity`.

---

## 4. Navigation & Screen Map

Entry point: `MainActivity` (`ui/MainActivity.kt:7-18`) inflates `activity_main.xml`, which contains only a `FragmentContainerView` with `app:navGraph="@navigation/visualizer_nav_graph"` and `app:defaultNavHost="true"` (`res/layout/activity_main.xml:8-18`). All navigation logic lives in the nav graph and fragments — `MainActivity` itself has no other code.

**`visualizer_nav_graph.xml`** (`res/navigation/visualizer_nav_graph.xml:1-103`), `startDestination = homeFragment`:

| Fragment (id) | Class | Nav args | Outgoing actions |
|---|---|---|---|
| `homeFragment` | `ui.home.HomeFragment` | none | `action_home_to_choose` → chooseRecordingTypeFragment |
| `chooseRecordingTypeFragment` | `ui.choose.ChooseRecordingTypeFragment` | none | `action_choose_to_pointSetHome` → pointSetHomeFragment |
| `pointSetHomeFragment` | `ui.pointsethome.PointSetHomeFragment` | `pointSet: string` | `action_pointSetHome_to_placement` → placementFragment; `action_pointSetHome_to_review` → reviewFragment |
| `placementFragment` | `ui.placement.PlacementFragment` | `pointSet: string`, `sessionId: long`, `sessionNumber: int` | `action_placement_to_recording` → recordingFragment; `action_placement_to_review` → reviewFragment |
| `recordingFragment` | `ui.recording.RecordingFragment` | `pointSet, pointCode: string`, `sessionId: long`, `sessionNumber: int`, `returnTo: string` (default `"placement"`) | `action_recording_to_player` → playerFragment |
| `playerFragment` | `ui.player.PlayerFragment` | `filePath, rawFilePath: string` (default `""`), `pointSet: string`, `pointCode: string` (default `""`), `sessionId: long`, `sessionNumber: int`, `isReviewMode: boolean` (default `false`), `returnTo: string` (default `"placement"`) | none (leaf) |
| `reviewFragment` | `ui.review.ReviewFragment` | `pointSet: string`, `sessionId: long`, `sessionNumber: int` | `action_review_to_player` → playerFragment; `action_review_to_recording` → recordingFragment |

`Basic TAAL` on the Home screen does **not** go through this nav graph at all (see §3) — it's a direct Intent-based Activity launch outside the NavHostFragment.

**Screen flow diagram:**
```
HomeFragment
  ├── [Basic TAAL]      → TaalRecorderActivity (taal-ui-kit) → on RESULT_OK → TaalPlayerActivity (taal-ui-kit)
  └── [VisualizerTaal]  → ChooseRecordingTypeFragment
        ├── [Record Heart] → PointSetHomeFragment(pointSet=HEART)
        └── [Record Lungs] → PointSetHomeFragment(pointSet=LUNGS)
              ├── [Start/Continue Session N] → PlacementFragment
              ├── [tap past session card]    → ReviewFragment
              └── [pencil icon on card]      → rename dialog (SessionRenameCoordinator)

PlacementFragment (tab-per-point picker)
  ├── [Record/Re-record] → RecordingFragment(returnTo="placement")
  └── [Review, top bar]  → ReviewFragment

RecordingFragment → [Stop] → PlayerFragment(filePath=filtered temp, rawFilePath=raw temp, returnTo carried through)

PlayerFragment (new recording: Save/Discard bar visible; review mode: hidden)
  ├── [Save]    → saves to Downloads/Audios/{PointSet}/Session {N}/, inserts RecordingEntity, then:
  │                 session complete → pop to PointSetHomeFragment
  │                 else returnTo=="review" → pop to ReviewFragment
  │                 else → pop to PlacementFragment (auto-selects next unrecorded point)
  └── [Discard] → deletes both temp files → navigateUp() (back to RecordingFragment)

ReviewFragment (session detail, reused mid-session and for past sessions)
  ├── recorded point row    → Play (PlayerFragment isReviewMode=true) / Re-record / Share / Delete
  ├── not-recorded point row → "Record" button → RecordingFragment(returnTo="review")
  └── [pencil icon]          → rename dialog
```

---

## 5. Guided Session Flow (point-by-point Heart/Lungs)

### What a "Session" is
A **Session** (`data/db/entity/SessionEntity.kt:6-13`) is a Room row: `id`, `pointSet` (`"HEART"`/`"LUNGS"`, i.e. `PointSetType.name`), `sessionNumber` (1, 2, 3... **scoped per pointSet**, not global), an optional `customName`, and `createdAt`. `SessionEntity.displayName()` (SessionEntity.kt:16) returns `customName` if non-blank, else `"Session $sessionNumber"`.

A session is "complete" when it has one `RecordingEntity` row per point in `PointSetType.points` for that pointSet (4 for Heart, 20 for Lungs). Completeness is checked by count, e.g. `RecordingRepository.getRecordingCountForSession(sessionId) >= pointSetType.points.size` (used at PlayerFragment.kt:332, PointSetHomeFragment.kt:93, SessionRepository.kt:24).

**Getting an active session** — `SessionRepository.getOrCreateActiveSession()` (SessionRepository.kt:22-30): fetches the latest session for the pointSet; if it exists and its recording count is still below `points.size`, **reuse it** (this is how a session is "continued" across app restarts/multiple sittings); otherwise create a new session numbered `latest.sessionNumber + 1` (or `1` if none exist yet).

### The point sets
Defined in `domain/RecordingPoint.kt:20-35`: `PointSetType` enum has `HEART` (displayName "Heart", `PreFilter.HEART`) and `LUNGS` (displayName "Lungs", `PreFilter.LUNGS`). `PointSetType.points` resolves to `HeartPoints.all` or `LungPoints.all`; `PointSetType.regions` is `points.mapNotNull { it.region }.distinct()` — **empty for Heart** (flat tabs), **non-empty for Lungs** (nested region → point-sub-tab UI). This `region == null` branch (`RecordingPoint.kt:16`) is the single shared code path driving both Placement/Recording/Player/Review UIs for both point sets — there is no separate Heart-only or Lungs-only fragment tree.

- **HeartPoints** (`domain/HeartPoints.kt:10-15`) — 4 points, no regions: `aortic` "Aortic", `mitral` "Mitral", `pulmonary` "Pulmonary", `tricuspid` "Tricuspid".
- **LungPoints** (`domain/LungPoints.kt:17-45`) — 20 points across 4 regions (`regionOrder` at LungPoints.kt:15: Anterior, Posterior, Lateral Right, Lateral Left):
  - **Anterior (8)**: `aar` Apex Right, `aslr` Superior Right, `amlr` Middle Right, `ailr` Inferior Right, `aal` Apex Left, `asll` Superior Left, `amll` Middle Left, `aill` Inferior Left.
  - **Posterior (8)**: `par`/`pslr`/`pmlr`/`pilr` (Right), `pal`/`psll`/`pmll`/`pill` (Left) — labels follow "Post. {Position} {Side}".
  - **Lateral Right (2)**: `lrs` "Lateral Right Superior", `lri` "Lateral Right Inferior".
  - **Lateral Left (2)**: `lls` "Lateral Left Superior", `lli` "Lateral Left Inferior".
  - A class-doc comment (LungPoints.kt:5-6) states Anterior/Posterior codes/labels are reused from `lungs-app`'s existing 16-point layout — **not independently re-verified against `lungs-app` source in this pass**; the prior `docs/notes/VisualizerTaal-App.md` (§12) additionally flags the 4 Lateral codes/labels as placeholders pending real clinical names — that claim could not be independently confirmed or refuted here since it concerns `lungs-app` content, out of this module's scope; treat it as carried-forward, unverified context.
  - All 24 codes have a matching `point_{code}.png` drawable — **verified present on disk**: `visualizertaal-app/src/main/res/drawable/point_{aal,aar,aill,ailr,amll,amlr,aortic,asll,aslr,lli,lls,lri,lrs,mitral,pal,par,pill,pilr,pmll,pmlr,psll,pslr,pulmonary,tricuspid}.png` (24 files, one per point code).

### The guided UI: PlacementFragment
`ui/placement/PlacementFragment.kt:22-212` is the "tab-per-point picker" that guides the user through recording each point:
- If `pointSetType.regions` is empty (Heart), it hides `regionTabs` and calls `setupPointTabs(pointSetType.points)` directly (PlacementFragment.kt:72-74) — one flat `TabLayout` of 4 tabs.
- If regions exist (Lungs), it shows `regionTabs` (one tab per region) and, on region selection, rebuilds `pointTabs` to the points in that region (PlacementFragment.kt:118-136) — a nested region → point tab-of-tabs UI.
- Each point tab shows: a placement guide image looked up at runtime via `resources.getIdentifier("point_${point.code}", "drawable", ...)` (PlacementFragment.kt:157-163; same pattern in RecordingFragment.kt:99-105) + an instruction string (`R.string.placement_instruction` = "Place the stethoscope in the position and then press Record", strings.xml:20) + a Record/Re-record button.
- Tab labels get a `"✓ "` prefix once that point's code is present in `recordedCodes`, a `Set<String>` refreshed live by collecting `RecordingRepository.getRecordingsForSession(sessionId)` as a `Flow` (PlacementFragment.kt:104-116, 168-176).
- **Auto-advance**: after a save produces a *newly* recorded code (diffed against `previousRecordedCodes` so mere re-collection doesn't re-trigger it), `advanceToNextUndonePoint()` (PlacementFragment.kt:179-201) selects the next unrecorded point tab in the current region, or — if the whole region is done — the next region (wrapping) that still has an unrecorded point.
- **Listener registration discipline**: the `pointTabs` listener is registered exactly once in `onViewCreated` (PlacementFragment.kt:95-102) and reads a live `currentPoints` field rather than closing over a stale list — a comment there (lines 93-94) explicitly documents this as a fix for a real prior bug (stale-listener accumulation across region switches causing wrong images to show).
- `PlacementViewModel` (`ui/placement/PlacementViewModel.kt:6-9`) is trivially fragment-scoped: just `currentRegionIndex: Int` and `currentPointCode: String?`, no async state — it survives rotation/back-forward nav because the Fragment instance itself persists across navigation within the single `NavHostFragment` (views are destroyed/recreated, but the Fragment object and its `viewModels()` survive).

### The capture screen: RecordingFragment
`ui/recording/RecordingFragment.kt:30-475` builds directly on `taal-core`'s `TaalRecorder` (no `taal-ui-kit` involvement):
- Resolves the correct filter automatically via `pointSetType.preFilter` (RecordingFragment.kt:261) — always `PreFilter.HEART` for a Heart point, `PreFilter.LUNGS` for a Lungs point; no manual filter picker on this screen.
- **`MAX_RECORDING_SECONDS = 20`** (RecordingFragment.kt:63) — auto-stop trigger checked every progress callback (`if (timeStamp >= MAX_RECORDING_SECONDS && !autoStopTriggered)`, lines 295-298) even though `TaalRecorder.setRecordingTime(300)` (line 258) sets a much longer internal cap — the 20s cutoff is enforced by this fragment's own callback, not by the recorder.
- Pre-amp: a slider bound to `RecordingViewModel.preAmpDb` (default 5, clamped 0–30 via `setPreAmp` at RecordingViewModel.kt:27), **reset to 5 dB every `onResume()`** (RecordingFragment.kt:446-453).
- Writes **two temp files per take** into `context.filesDir`: `recording_{ts}_raw.wav` and `recording_{ts}_filtered.wav` (RecordingFragment.kt:250-251), passed to `TaalRecorder.setRawAudioFilePath`/`setFilteredAudioFilePath` (lines 256-257) — filtering happens in real time during capture, not as a post-process.
- Live waveform: MPAndroidChart `LineDataSet`, 10-second scrolling window (`WINDOW_SECONDS = 10f`, line 57), downsampled by `DOWNSAMPLE_STEP = 44` (line 59), with a 2-second warmup phase (`WARMUP_MS = 2000L`, line 60) that sets the Y-axis peak to `warmupPeak * 1.5` (`HEADROOM`, line 61) clamped to `[MIN_PEAK=0.02f, 1.0f]` (RecordingFragment.kt:384-393) — architecturally similar in spirit to the "V7 sample-accurate" waveform pattern documented for the main `app` module, though this is an independent, simpler implementation (no page-based memory cleanup beyond the single `minXToKeep` trim at lines 377-381).
- Also plays live audio monitoring through a raw `AudioTrack` fed from the same `onProgressUpdate` callback (RecordingFragment.kt:277-284, `startAudioMonitor()` at 416-438) — this is local sidetone/monitoring, separate from the recorded WAV files.
- **Back-press safety**: intercepts both the in-fragment back arrow and system back via `OnBackPressedCallback` (RecordingFragment.kt:89-91, `handleBackPress()` at 176-189); if a recording is in progress, shows a confirm dialog ("Recording is still in progress. Going back will discard it.") before navigating up.
- **Orphan cleanup**: `onDestroy()` calls `cleanupOrphanedTempFiles()` (RecordingFragment.kt:461-474) to delete both temp WAVs **unless** the fragment is mid-navigation to PlayerFragment (`navigatingToPlayer` flag, set at line 332 right before the `findNavController().navigate(...)` call at RecordingFragment.kt:333-345) — so a recording abandoned any other way (back press, process death via onDestroy, etc.) doesn't leak temp files in `filesDir`.
- On Stop (`stopRecording()`, RecordingFragment.kt:323-346): navigates to `playerFragment` passing `filePath` = filtered temp path, `rawFilePath` = raw temp path, plus the pointSet/pointCode/sessionId/sessionNumber/returnTo that were passed in.

### Save/Discard and file naming: PlayerFragment
See §7 for the full save mechanics (`DownloadsStorage`). Key session/point-specific behavior in `ui/player/PlayerFragment.kt`:
- **No filename prompt** — the filename is auto-built as `"{sanitized session displayName}_{sanitized point label}.wav"` (PlayerFragment.kt:314-316), so renaming a session (or the point label never changing) keeps saves deterministic and overwrite-safe for re-records.
- `isReviewMode` (default `false`) hides the Save/Discard bar and re-anchors the Play button to the screen bottom via a `ConstraintSet` edit (`applyReviewMode()`, PlayerFragment.kt:150-157) — same Fragment class serves both "just recorded, decide to keep it" and "replaying an already-saved file" without a separate screen.
- Review-mode playback resolves a `content://` MediaStore URI to a local cache file first via `DownloadsStorage.resolvePlayablePath()` (PlayerFragment.kt:116, definition in util/DownloadsStorage.kt:129-136), since `TaalPlayer` reads via `java.io.File` and cannot open a content URI directly.
- After a successful save, navigation target depends on session completeness and `returnTo` (PlayerFragment.kt:336-343): session now complete → pop to `pointSetHomeFragment`; else `returnTo == "review"` → pop to `reviewFragment`; else → pop to `placementFragment` (which then auto-advances to the next unrecorded point per §5's PlacementFragment description).

### Session detail: ReviewFragment
`ui/review/ReviewFragment.kt:45-211` is the session-detail screen, reused both mid-session (jumped to from Placement's "Review" button) and for browsing a fully/partially completed past session (tapped from `PointSetHomeFragment`'s session list):
- Builds a flat list of `ReviewListItem` — `Header(region, doneCount, total)` rows only for Lungs (one per region, doneCount/total computed from which points in that region have a matching `RecordingEntity`), and one `PointRow(point, recording?)` per point, `recording` null if not yet recorded (`buildReviewList()`, ReviewFragment.kt:134-149).
- Per recorded point: status dot (green `success_green`), filename subtitle, and Play/Re-record/Share/Delete buttons (`PointVH.bind()`, ReviewFragment.kt:268-298). Per not-yet-recorded point: grey (`divider`-colored) status dot and a single "Record" button.
- Delete (`confirmDelete()`, ReviewFragment.kt:195-205) removes **both** the on-disk/MediaStore file (`DownloadsStorage.delete()`) and the `RecordingEntity` row (`recordingRepo.deleteById()`).
- Share (`shareRecording()`, ReviewFragment.kt:180-193) builds an `ACTION_SEND` intent with `type = "audio/wav"` around the URI from `DownloadsStorage.shareUri()` — see §7.

---

## 6. Data Layer

Room database `VisualizerDatabase` (`data/db/VisualizerDatabase.kt:12-30`), version 2, singleton via `getInstance(context)` (double-checked locking), `fallbackToDestructiveMigration()` (no real `Migration` — see §2 caveat).

### Entities
- **`SessionEntity`** (`data/db/entity/SessionEntity.kt:6-13`, table `sessions`): `id` (autogen PK), `pointSet: String` (`PointSetType.name`), `sessionNumber: Int` (per-pointSet sequence), `customName: String?`, `createdAt: Long`. Extension `displayName()` at line 16.
- **`RecordingEntity`** (`data/db/entity/RecordingEntity.kt:8-25`, table `recordings`): `id` (autogen PK), `sessionId: Long` (FK → `sessions.id`, `onDelete = CASCADE`), `pointCode: String`, `fileName: String`, `uriOrPath: String` (`content://` MediaStore URI on API 29+, or absolute file path on API < 29), `createdAt: Long`. Indices: `sessionId`, and a **unique composite index** on `(sessionId, pointCode)` (RecordingEntity.kt:15) — this is what makes re-recording a point a clean upsert.

### DAOs
- **`SessionDao`** (`data/db/dao/SessionDao.kt:10-26`): `insert` (suspend, returns new id), `getSessionsForPointSet(pointSet): Flow<List<SessionEntity>>` (ordered `sessionNumber DESC`), `getLatestSession(pointSet): SessionEntity?` (suspend, `LIMIT 1`), `getById(id)`, `updateName(id, name)`.
- **`RecordingDao`** (`data/db/dao/RecordingDao.kt:11-31`): `insert` uses `@Insert(onConflict = OnConflictStrategy.REPLACE)` (line 14) — relies on the unique `(sessionId, pointCode)` index to implement re-record-overwrites-same-row semantics at the DB layer. Also `getRecordingsForSession` (Flow and suspend-once variants), `getRecordingCountForSession`, `updateFileInfo(id, fileName, uriOrPath)` (used by rename sync, see below), `deleteById`.

### Repositories
- **`SessionRepository`** (`data/repository/SessionRepository.kt:8-31`) — thin wrapper plus the one piece of real logic, `getOrCreateActiveSession()` (lines 22-30), described in §5.
- **`RecordingRepository`** (`data/repository/RecordingRepository.kt:7-24`) — pure passthrough wrapper around `RecordingDao`.
- **`SessionRenameCoordinator`** (`data/repository/SessionRenameCoordinator.kt:13-33`) — orchestrates a rename: updates `SessionEntity.customName` via `SessionRepository.rename()`, then for every `RecordingEntity` in that session, recomputes the expected filename from the new session display name + that recording's point label, and if it differs from the stored `fileName`, calls `DownloadsStorage.rename()` (moves/relabels the on-disk file or MediaStore row) and `RecordingRepository.updateFileInfo()` to keep the DB in sync. This is what keeps saved filenames matching the session name after a user renames a session with recordings already saved in it. Invoked from both `PointSetHomeFragment.showRenameDialog()` and `ReviewFragment.showRenameDialog()`.

No `Patient` entity anywhere in this module — a deliberate product decision (numbered sessions only, no patient identity tracking), unlike `app`/`lungs-app`.

---

## 7. Sharing / Export

All file I/O for saved recordings goes through `util/DownloadsStorage.kt:22-150`, an `object` with no instance state:

- **Save** (`save()`, lines 41-70): On API 29+ (`Build.VERSION.SDK_INT >= Q`), inserts into `MediaStore.Downloads` under `RELATIVE_PATH = "Download/Audios/{subFolders...}"` (e.g. `Download/Audios/Heart/Session 1/`), using `IS_PENDING` bracketing around the actual byte copy (lines 47-59); first calls `deleteExistingByName()` (lines 76-91) to remove any prior MediaStore row with the exact same display name + relative path, giving overwrite semantics for re-records. On API < 29, writes directly to `Environment.DIRECTORY_DOWNLOADS/Audios/{subFolders...}/{fileName}` via `File.copyTo(overwrite = true)` (lines 61-69), gated by `needsLegacyWritePermission()` (lines 32-35) which the callers (PlayerFragment) check before saving and request `WRITE_EXTERNAL_STORAGE` if needed.
- **Resulting folder convention**: `{Public Downloads}/Audios/{Heart|Lungs}/Session {N}/{sessionLabel}_{pointLabel}.wav` — folder path is always the numeric `Session {N}` (stable even after rename), only the **filename** picks up a custom session name if one is set (see §5/§6 rename sync).
- **Rename** (`rename()`, lines 99-114): For a MediaStore URI, updates only `DISPLAY_NAME` via `ContentValues` — the `content://` URI string itself is unchanged (it's a stable row id), so `RecordingEntity.uriOrPath` doesn't need to change in that branch even though `fileName` does. For a legacy path, does a real `File.renameTo()` and returns the new absolute path.
- **Delete** (`delete()`, lines 116-122): `contentResolver.delete()` for MediaStore URIs, `File.delete()` for legacy paths.
- **`resolvePlayablePath()`** (lines 129-136): copies a `content://` URI's bytes to `context.cacheDir/playback_temp.wav` and returns that path, since `TaalPlayer` (from `taal-core`) only reads via `java.io.File`. Used by PlayerFragment in review mode.
- **`shareUri()`** (lines 139-144) — **confirmed exactly as described in project memory**: returns the MediaStore `content://` URI directly (already shareable) when `uriOrPath` starts with `"content://"`; otherwise wraps a legacy absolute file path via `androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(uriOrPath))`. Used from `ReviewFragment.shareRecording()` to build an `ACTION_SEND` (`audio/wav`) share intent.
- **`sanitizeFileNamePart()`** (lines 146-149): strips to `[A-Za-z0-9 _-]`, collapses whitespace runs to a single underscore, falls back to `"Recording"` if the result is blank.

**FileProvider collision avoidance**: `util/VisualizerFileProvider.kt:13` — `class VisualizerFileProvider : FileProvider()`, a subclass rather than declaring `androidx.core.content.FileProvider` directly in the manifest. The doc comment (lines 5-12) explains why: `taal-ui-kit` already declares its own `<provider android:name="androidx.core.content.FileProvider">`, and the Android manifest merger treats same-named `<provider>` nodes across modules/dependencies as one logical node — this caused an authorities/paths collision. Subclassing gives this module's provider a distinct `android:name` (`.util.VisualizerFileProvider`) so both providers coexist independently. Declared in the manifest at `AndroidManifest.xml:34-42` with `android:authorities="${applicationId}.fileprovider"`, `exported="false"`, paths from `res/xml/file_provider_paths.xml:1-7` (`files-path "."`, `cache-path "."`, `external-path "Download/Audios/"`).

---

## 8. UI Screen Inventory

| Fragment/Activity | File | One-line purpose |
|---|---|---|
| `MainActivity` | `ui/MainActivity.kt` | Single-activity host; inflates `activity_main.xml` (NavHostFragment only), no other logic. |
| `HomeFragment` | `ui/home/HomeFragment.kt` | Landing screen: "Basic TAAL" (launches taal-ui-kit's recorder/player Activities directly) vs "VisualizerTaal" (enters the guided nav graph). |
| `ChooseRecordingTypeFragment` | `ui/choose/ChooseRecordingTypeFragment.kt` | "Record Heart" vs "Record Lungs" chooser; sets `PointSetType` and forwards to PointSetHomeFragment. |
| `PointSetHomeFragment` | `ui/pointsethome/PointSetHomeFragment.kt` | Per-pointSet "folder": Start/Continue-session button (label driven by `updateStartButton()`), RecyclerView of past `SessionEntity` cards (tap → Review, pencil icon → rename). |
| `PlacementFragment` | `ui/placement/PlacementFragment.kt` | Tab-per-point picker (flat for Heart, region→point nested tabs for Lungs); shows the placement guide image + instructions + Record/Re-record button; auto-advances to the next unrecorded point after a save. |
| `PlacementViewModel` | `ui/placement/PlacementViewModel.kt` | Fragment-scoped tab-selection memory (`currentRegionIndex`, `currentPointCode`); no async/business logic. |
| `RecordingFragment` | `ui/recording/RecordingFragment.kt` | Actual capture screen: `TaalRecorder`-driven live waveform, pre-amp slider, 20s auto-stop, back-press-confirm + orphan temp-file cleanup, hands off to PlayerFragment on Stop. |
| `RecordingViewModel` | `ui/recording/RecordingViewModel.kt` | Holds `uiState` (IDLE/RECORDING/STOPPED), `timerSeconds`, `preAmpDb` (clamped 0-30, default 5), and the two temp file paths for the in-flight take. |
| `PlayerFragment` | `ui/player/PlayerFragment.kt` | Dual-purpose: (a) new-recording Save/Discard screen with full waveform + playback, auto-named save with no dialog; (b) review-mode playback of an already-saved recording (Save/Discard hidden). |
| `ReviewFragment` | `ui/review/ReviewFragment.kt` | Session detail list: per-point status (recorded/not), Play/Re-record/Share/Delete actions, session rename entry point, region-grouped headers for Lungs. |
| `VisualizerApplication` | `VisualizerApplication.kt` | Empty `Application` subclass — no custom initialization. |
| `VisualizerFileProvider` | `util/VisualizerFileProvider.kt` | Distinctly-named `FileProvider` subclass to avoid a manifest-merger collision with `taal-ui-kit`'s own FileProvider declaration. |
| `DownloadsStorage` | `util/DownloadsStorage.kt` | Save/rename/delete/resolve-playable-path/share-uri/sanitize helper object; MediaStore (API 29+) vs legacy file I/O (API <29) branching. |

Layouts (all under `res/layout/`): `activity_main.xml`, `fragment_home.xml`, `fragment_choose_recording_type.xml`, `fragment_point_set_home.xml`, `fragment_placement.xml`, `fragment_vt_recording.xml` (note `vt_` prefix), `fragment_vt_player.xml` (note `vt_` prefix), `fragment_review.xml`, `item_session_card.xml`, `item_review_header.xml`, `item_review_row.xml`, `dialog_rename_session.xml`. The `vt_` prefix on the recording/player layouts (and on most custom drawables — `vt_ic_*.xml`, `vt_bg_*.xml`) is a deliberate collision-avoidance convention against `taal-ui-kit`'s own resources of similar generic names (same manifest-merger resource-shadowing risk described for the FileProvider in §7) — carried forward from the prior module doc, and consistent with what's on disk.

---

## 9. Known Issues / Gotchas

- **No real Room migration path.** `VisualizerDatabase.kt:27` uses `fallbackToDestructiveMigration()`. Any future entity/schema change wipes the `sessions`/`recordings` tables on next app launch (metadata only — saved WAV files on disk survive, but the app loses track of them). Needs a real `Migration` before this ships with real user data.
- **Basic TAAL has no filter picker.** `HomeFragment.kt:46` hardcodes `preFilter = "HEART"` when launching `taal-ui-kit`'s `TaalRecorderActivity` — there is no UI to choose a different filter for the Basic TAAL path.
- **No adaptive icon.** `ic_app_icon` (referenced at AndroidManifest.xml:16,18) is a single flat vector drawable, not a `mipmap-anydpi-v26` foreground/background pair — OEM launchers apply their own default masking.
- **Legacy (API < 29) storage paths are comparatively less exercised.** `DownloadsStorage`'s `else` branches (save/rename/delete for `API < 29`) exist and look correct, but this is carried-forward context from the prior module doc noting testing has concentrated on modern (API 29+) devices — no independent evidence either way was gathered in this pass.
- **Lateral lung point codes/labels possibly placeholders.** Per a doc comment in `LungPoints.kt:5-7` the Anterior/Posterior codes are reused from `lungs-app`; the prior module note additionally flagged the 4 Lateral codes (`lrs`/`lri`/`lls`/`lli`) as placeholder clinical names pending real ones — **not independently verified in this pass** (would require comparing against `lungs-app` source or clinical reference material, out of this module's scope). All 4 do have matching `point_{code}.png` images on disk.
- **`RecordingFragment`'s `TaalRecorder.setRecordingTime(300)`** (RecordingFragment.kt:258) is set far above the fragment's own effective 20s cutoff (`MAX_RECORDING_SECONDS`, line 63) — the 300s value is inert in normal operation since the fragment's own `onProgressUpdate` callback calls `stopRecording()` well before that; worth knowing if `TaalRecorder`'s internal timer is ever relied upon directly elsewhere.
- **`AudioTrack` monitoring in `RecordingFragment.startAudioMonitor()`** (lines 416-438) plays back the incoming audio live through the device speaker/output during capture — separate from and in addition to the two WAV files being written; this is a deliberate sidetone feature, not a bug, but worth knowing if debugging unexpected audio output during recording.
- **Resource-name collisions with `taal-ui-kit` are a real, previously-hit failure mode** in this module (per the `vt_` prefix convention documented in §8) — the manifest-merger/resource-merger silently lets app-level resources override library ones of the same name; any new drawable/layout added to this module should be checked against `taal-ui-kit/src/main/res/` for name collisions before being added without a `vt_` prefix.

---

## 10. Changelog

| Date | Change | Notes |
|---|---|---|
| 2026-09-08 | Initial SUPERMASTER_VISUALIZERTAAL_APP.md created | Full source audit of `visualizertaal-app`: build.gradle.kts, AndroidManifest.xml, all 19 Kotlin source files, nav graph, and key resource files read and cross-checked line-by-line against the prior `docs/notes/VisualizerTaal-App.md` (2026-08-10) and `SUPERMASTER.md`'s summary entries. All prior claims confirmed accurate against current source; module has had no commits since its single introducing commit `7481a0b` (2026-08-14). |
