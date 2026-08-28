# VisualizerTaal-App — Complete Reference

> **Purpose of this file:** Complete technical reference for the `:visualizertaal-app` Android module.
> Share this file with any AI assistant (or read it yourself) to give full context before changing/adding/removing anything.
> Last updated: 2026-08-10

---

## 1. What This Is

A standalone Android application (`com.musediagnostics.taal.visualizer`) that offers two ways to use the TAAL USB digital stethoscope:

1. **Basic TAAL** — the plain filtered-recording experience, reused as-is from `taal-ui-kit`.
2. **VisualizerTaal** — a guided, point-by-point auscultation recorder for **Heart** (4 valves) and **Lungs** (20 points), organized into numbered **Sessions** you can revisit, rename, and finish over multiple sittings.

It lives as a module (`:visualizertaal-app`) inside `E:\AndroidProjects\TaalDemoApp` and produces its **own separate APK** — fully standalone from `:app` and `:lungs-app`, though it reuses the shared `:taal-core` and `:taal-ui-kit` library modules.

---

## 2. Project Structure

```
E:\AndroidProjects\TaalDemoApp\
├── app/                    ← Main TaalDemoApp — untouched
├── lungs-app/               ← Standalone lungs auscultation app — untouched
├── taal-core/               ← USB audio engine (shared) — untouched
├── taal-ui-kit/              ← Shared UI kit (Basic TAAL recorder/player screens) — untouched
├── visualizertaal-app/        ← THIS APP
└── settings.gradle.kts      ← includes(":visualizertaal-app") added here
```

### Dependency Graph
```
:taal-core     (USB audio capture, WAV writing, AudioFilterEngine, TaalRecorder, TaalPlayer)
     ↑
:taal-ui-kit   (Basic TAAL recorder/player screens, depends on :taal-core)
     ↑
:visualizertaal-app  (own UI, own Room DB, own nav graph — depends on :taal-core AND :taal-ui-kit)

:app           (untouched, unaffected)
:lungs-app     (untouched, unaffected)
```

**Key rule:** `visualizertaal-app` is the only module that depends on `:taal-ui-kit` (for the "Basic TAAL" button, which launches `taal-ui-kit`'s `TaalRecorderActivity`/`TaalPlayerActivity` directly). Everything else (Heart/Lungs guided recording) is built directly on `:taal-core`.

---

## 3. App Identity

| Field | Value |
|---|---|
| applicationId | `com.musediagnostics.taal.visualizer` |
| namespace | `com.musediagnostics.taal.visualizer` |
| minSdk | 24 |
| targetSdk | 34 |
| versionCode | 1 |
| versionName | 1.0 |
| App name | VisualizerTaal |
| App icon | `@drawable/ic_app_icon` (TAAL stethoscope mark, teal `#008DB9`, centered on white) |
| Entry Activity | `MainActivity` (single activity, NavHostFragment) |
| DB name | `visualizer_database` |
| DB version | **2** (bumped for session `customName` field) |
| allowBackup | `false` |

---

## 4. Module File Layout

```
visualizertaal-app/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   └── java/com/musediagnostics/taal/visualizer/
│       ├── VisualizerApplication.kt
│       ├── domain/
│       │   ├── RecordingPoint.kt       ← RecordingPoint data class + PointSetType enum
│       │   ├── HeartPoints.kt          ← 4 heart valve points (flat, no regions)
│       │   └── LungPoints.kt           ← 20 lung points across 4 regions
│       ├── data/
│       │   ├── db/
│       │   │   ├── VisualizerDatabase.kt
│       │   │   ├── entity/
│       │   │   │   ├── SessionEntity.kt     ← + displayName() extension
│       │   │   │   └── RecordingEntity.kt
│       │   │   └── dao/
│       │   │       ├── SessionDao.kt
│       │   │       └── RecordingDao.kt
│       │   └── repository/
│       │       ├── SessionRepository.kt
│       │       ├── RecordingRepository.kt
│       │       └── SessionRenameCoordinator.kt   ← keeps saved filenames in sync on rename
│       ├── util/
│       │   ├── DownloadsStorage.kt      ← MediaStore/legacy save-delete-rename-share
│       │   └── VisualizerFileProvider.kt  ← distinct FileProvider subclass (see §11)
│       └── ui/
│           ├── MainActivity.kt
│           ├── home/HomeFragment.kt                        ← Basic TAAL / VisualizerTaal
│           ├── choose/ChooseRecordingTypeFragment.kt        ← Record Heart / Record Lungs
│           ├── pointsethome/PointSetHomeFragment.kt         ← per-type "folder": sessions list
│           ├── placement/
│           │   ├── PlacementFragment.kt                     ← tab-per-point picker
│           │   └── PlacementViewModel.kt
│           ├── recording/
│           │   ├── RecordingFragment.kt                     ← actual capture screen
│           │   └── RecordingViewModel.kt
│           ├── player/PlayerFragment.kt                     ← Save/Discard or review playback
│           └── review/ReviewFragment.kt                     ← session detail list
└── src/main/res/
    ├── navigation/visualizer_nav_graph.xml
    ├── layout/ ...  (see §9 per-screen)
    ├── drawable/
    │   ├── point_{code}.png          ← 4 heart + 20 lungs placement guide images
    │   ├── vt_*.xml                   ← this module's own icons/backgrounds (see §11)
    │   └── ic_app_icon.xml            ← launcher icon
    ├── values/{colors,strings,dimens,themes}.xml
    └── xml/file_provider_paths.xml
```

---

## 5. Dependencies (build.gradle.kts)

```kotlin
implementation(project(":taal-core"))
implementation(project(":taal-ui-kit"))

// AndroidX core/appcompat/material/constraintlayout/recyclerview
// Navigation fragment-ktx + ui-ktx : 2.7.6
// Lifecycle viewmodel/livedata/runtime-ktx : 2.7.0
// Coroutines android + core : 1.7.3

// Room (session/recording metadata only — no patient details)
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")   // needs id("com.google.devtools.ksp") plugin

// MPAndroidChart v3.1.0 (waveform)
```

No Room `Patient` entity, no TFLite, no Google Drive — deliberately lighter than `lungs-app`.

---

## 6. User Flow (Screen by Screen)

```
HomeFragment
  ├── [Basic TAAL]      → launches taal-ui-kit's TaalRecorderActivity (ActivityResultContracts),
  │                        on RESULT_OK → launches TaalPlayerActivity with the returned file path
  └── [VisualizerTaal]  → ChooseRecordingTypeFragment

ChooseRecordingTypeFragment
  ├── [Record Heart] → PointSetHomeFragment(pointSet=HEART)
  └── [Record Lungs] → PointSetHomeFragment(pointSet=LUNGS)

PointSetHomeFragment (the per-type "folder")
  ├── [Start Session N] / [Continue Session N]
  │     → gets-or-creates the active session (continues the latest session if it still has
  │        unrecorded points, else creates the next-numbered one) → PlacementFragment
  ├── [tap a past session card] → ReviewFragment (session detail)
  └── [pencil icon on a session card] → rename dialog → SessionRenameCoordinator

PlacementFragment (tab-per-point picker)
  - Heart: flat tabs, 4 points (Aortic/Mitral/Pulmonary/Tricuspid)
  - Lungs: region tabs (Anterior/Posterior/Lateral Right/Lateral Left) → point sub-tabs (8/8/2/2)
  - Each tab shows: placement guide image (if present) + instructions + Record/Re-record button
  - Tab label gets a "✓" prefix once that point is recorded (read live from the DB)
  - [Record button] → RecordingFragment(pointSet, pointCode, sessionId, sessionNumber)
  - [Review button, top bar] → ReviewFragment (jump to session detail anytime)

RecordingFragment
  - Filter: PreFilter.HEART or PreFilter.LUNGS depending on pointSet — always correct,
    resolved via PointSetType.preFilter (verified 2026-08-10)
  - Pre-amp 0–30dB slider, reset to 5dB on resume; MAX_RECORDING_SECONDS = 20 (auto-stop)
  - Two temp files per take: recording_{ts}_raw.wav + recording_{ts}_filtered.wav in filesDir
  - Back mid-recording → confirmation dialog ("discard in-progress recording?"); if the
    screen is left without saving, onDestroy() deletes the orphaned temp files
  - [Stop] → PlayerFragment(filePath=filtered, rawFilePath, pointSet, pointCode, sessionId,
             sessionNumber, returnTo)

PlayerFragment (new recording: Save/Discard bar visible; review mode: hidden)
  - No name prompt — filename is auto-built as "{SessionDisplayName}_{PointLabel}.wav"
  - [Save] → saves filtered WAV to Downloads/Audios/{PointSet}/Session {N}/, deletes raw temp,
             inserts a RecordingEntity, then:
               • if the session is now fully recorded → pop to PointSetHomeFragment
               • else if returnTo == "review" → pop to ReviewFragment
               • else → pop to PlacementFragment, which auto-selects the next unrecorded point
  - [Discard] → confirm → deletes both temp files → navigateUp() (back to Recording screen)
  - Review-mode playback of a saved file resolves content:// URIs to a cache file first
    (TaalPlayer reads via java.io.File)

ReviewFragment (session detail — reused both mid-session and for past sessions)
  ├── Header rows (Lungs only) showing "{doneCount} / {total}" per region
  ├── Per-point row:
  │     recorded    → status dot (green) + filename + Play / Re-record / Share / Delete
  │     not recorded → status dot (grey) + "Record" button → RecordingFragment (returnTo="review")
  ├── [pencil icon, top bar] → rename dialog for this session
  └── Delete → removes the MediaStore/file entry AND the RecordingEntity row
```

---

## 7. Navigation Graph (`visualizer_nav_graph.xml`)

**startDestination**: `homeFragment`

| Action | From → To |
|---|---|
| `action_home_to_choose` | homeFragment → chooseRecordingTypeFragment |
| `action_choose_to_pointSetHome` | chooseRecordingTypeFragment → pointSetHomeFragment |
| `action_pointSetHome_to_placement` | pointSetHomeFragment → placementFragment |
| `action_pointSetHome_to_review` | pointSetHomeFragment → reviewFragment |
| `action_placement_to_recording` | placementFragment → recordingFragment |
| `action_placement_to_review` | placementFragment → reviewFragment |
| `action_recording_to_player` | recordingFragment → playerFragment |
| `action_review_to_player` | reviewFragment → playerFragment |
| `action_review_to_recording` | reviewFragment → recordingFragment |

### Nav Args

| Fragment | Args |
|---|---|
| `pointSetHomeFragment` | `pointSet: string` ("HEART" / "LUNGS") |
| `placementFragment` | `pointSet: string`, `sessionId: long`, `sessionNumber: int` |
| `recordingFragment` | `pointSet, pointCode: string`, `sessionId: long`, `sessionNumber: int`, `returnTo: string` (default `"placement"`, or `"review"`) |
| `playerFragment` | `filePath, rawFilePath: string` (default `""`), `pointSet, pointCode: string`, `sessionId: long`, `sessionNumber: int`, `isReviewMode: boolean` (default `false`), `returnTo: string` |
| `reviewFragment` | `pointSet: string`, `sessionId: long`, `sessionNumber: int` |

`Basic TAAL` on the home screen does **not** go through this nav graph — it launches `taal-ui-kit`'s Activities directly via Intent.

---

## 8. Data Layer (Room)

No `Patient` entity — deliberately excluded per product decision (no patient tracking, just numbered sessions).

### SessionEntity
```kotlin
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pointSet: String,          // "HEART" or "LUNGS" — PointSetType.name
    val sessionNumber: Int,        // 1, 2, 3... scoped per pointSet
    val customName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
fun SessionEntity.displayName(): String = customName?.takeIf { it.isNotBlank() } ?: "Session $sessionNumber"
```

### RecordingEntity
```kotlin
@Entity(
    tableName = "recordings",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("sessionId", "pointCode", unique = true)]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val pointCode: String,
    val fileName: String,
    val uriOrPath: String,   // content:// MediaStore URI (API 29+) or absolute path (API < 29)
    val createdAt: Long = System.currentTimeMillis()
)
```
Re-record overwrite is handled by `@Insert(onConflict = OnConflictStrategy.REPLACE)` against the unique `(sessionId, pointCode)` index.

### Repositories
- **SessionRepository** — `getSessionsForPointSet(pointSet): Flow<List<SessionEntity>>`, `getById`, `rename(sessionId, name)`, `getOrCreateActiveSession(pointSet, recordingCountForSession)` (continues the latest session if incomplete, else creates the next-numbered one).
- **RecordingRepository** — `getRecordingsForSession(sessionId): Flow<...>`, `getRecordingsForSessionOnce` (suspend, non-Flow — used by rename), `getRecordingCountForSession`, `insert`, `updateFileInfo`, `deleteById`.
- **SessionRenameCoordinator** — orchestrates a rename: updates `SessionEntity.customName`, then walks every recording in that session and renames its saved file (`DownloadsStorage.rename`) + updates `RecordingEntity.fileName`/`uriOrPath` to match the new session display name. This is what keeps on-disk filenames in sync when you rename a session after already recording some points.

`VisualizerDatabase.getInstance(context)` — singleton, `fallbackToDestructiveMigration()` (fine pre-release; **no real migration path exists yet** — see §12).

---

## 9. Domain Model

### RecordingPoint.kt
```kotlin
data class RecordingPoint(val code: String, val label: String, val region: String? = null)

enum class PointSetType(val displayName: String, val preFilter: PreFilter) {
    HEART(PreFilter.HEART, ...), LUNGS(PreFilter.LUNGS, ...)
    val points: List<RecordingPoint>   // HeartPoints.all or LungPoints.all
    val regions: List<String>          // distinct regions, empty for Heart (flat)
    fun pointByCode(code: String): RecordingPoint?
}
```
`region == null` → flat single-level tabs (Heart). Non-null regions → nested region-tab → point-sub-tab UI (Lungs). This is the one branch point shared by Placement/Recording/Player/Review across both point sets — there is no separate Heart-only or Lungs-only fragment tree.

### HeartPoints (4, flat)
`aortic` "Aortic", `mitral` "Mitral", `pulmonary` "Pulmonary", `tricuspid` "Tricuspid"

### LungPoints (20, 4 regions)
- **Anterior (8)**: `aar` Apex Right, `aslr` Superior Right, `amlr` Middle Right, `ailr` Inferior Right, `aal` Apex Left, `asll` Superior Left, `amll` Middle Left, `aill` Inferior Left
- **Posterior (8)**: `par`, `pslr`, `pmlr`, `pilr` (Right); `pal`, `psll`, `pmll`, `pill` (Left) — same "Post. {Position} {Side}" label pattern
- **Lateral Right (2)**: `lrs` Lateral Right Superior, `lri` Lateral Right Inferior
- **Lateral Left (2)**: `lls` Lateral Left Superior, `lli` Lateral Left Inferior

Anterior/Posterior codes are reused verbatim from `lungs-app`'s existing 16-point layout. The 4 Lateral codes/labels are **placeholders** — see §12.

---

## 10. Save / File Storage Convention

`util/DownloadsStorage.kt` — MediaStore on API 29+, direct file I/O (`WRITE_EXTERNAL_STORAGE`, runtime-requested when needed) on API < 29.

```
{Public Downloads}/
└── Audios/
    ├── Heart/
    │   ├── Session 1/
    │   │   ├── Session_1_Aortic.wav
    │   │   ├── Session_1_Mitral.wav
    │   │   └── ...
    │   └── Session 2/  (or "Session_2" / a custom name if renamed)
    └── Lungs/
        └── Session 1/
            ├── Session_1_Apex_Right.wav
            └── ...
```
- Filename = `{sanitize(session.displayName())}_{sanitize(pointLabel)}.wav`. Renaming a session renames every already-saved file in it (via `SessionRenameCoordinator`) — the **folder path stays numeric/stable** (`Session {N}`), only the individual filenames change.
- Only the **filtered** WAV is ever kept — the raw temp file is always deleted after save (and on discard).
- Re-recording a point overwrites the same file (same session + same point code = same filename), via `deleteExistingByName` (API 29+) or `overwrite=true` copy (API < 29).
- `DownloadsStorage.sanitizeFileNamePart()` strips to `[A-Za-z0-9 _-]`, collapses spaces to underscores, falls back to `"Recording"` if the result would be blank.

---

## 11. Known Patterns / Conventions

- **`vt_` drawable/layout prefix is mandatory for anything that might collide with `taal-ui-kit`.** Android merges all dependency resources into one namespace, and app-level resources silently *override* library ones with the same name — this caused a real crash once (`taal-ui-kit`'s own `RecordingFragment` inflated *this* module's `fragment_recording.xml` instead of its own, NPE on a missing view). Every layout/drawable that could plausibly share a name with something in `taal-ui-kit` (icons like `ic_arrow_back`, `ic_play_circle`, layout names like `fragment_recording.xml`/`fragment_player.xml`) is prefixed `vt_` in this module. Files that are clearly unique (`item_review_row.xml`, `dialog_rename_session.xml`, etc.) don't need the prefix, but check `taal-ui-kit/src/main/res/` before adding a new one with a generic name.
- **`VisualizerFileProvider`** is a subclass of `androidx.core.content.FileProvider`, not `FileProvider` directly — same collision reason: `taal-ui-kit` already declares its own `<provider android:name="androidx.core.content.FileProvider">`, and the manifest merger treats same-named `<provider>` nodes across modules as one logical node. Subclassing gives this module's provider a distinct `android:name` so both coexist.
- **ViewBinding pattern**: `_binding`/`binding` nullable pair, `_binding = null` in `onDestroyView()` — consistent with `lungs-app`/`app`.
- **TabLayout listeners**: register listeners that read points **once, outside** any rebuild function (e.g. via a `currentPoints` field), not re-registered on every tab-rebuild — a real bug here (stale-listener accumulation across region switches) caused wrong images to briefly/persistently show; fixed by registering `pointTabs`'s listener exactly once in `onViewCreated`.
- **Fragment instances persist across forward/back nav** in the single NavHostFragment (views are destroyed/recreated via detach/attach, but the Fragment object and its `viewModels()` survive) — `PlacementFragment`'s `previousRecordedCodes` field and `PlacementViewModel.currentPointCode` rely on this to implement auto-advance-to-next-point correctly.
- **Back-press safety**: `RecordingFragment` intercepts both the in-screen arrow and system back via `OnBackPressedCallback`, confirming before discarding an in-progress recording; `onDestroy()` cleans up orphaned temp files if the screen is left without ever reaching Save.

---

## 12. Known Limitations / Pending Work

- **Lateral lung point codes/labels are placeholders** (`lrs`/`lri`/`lls`/`lli`, "Lateral Right/Left Superior/Inferior") — swap in `LungPoints.kt` if real clinical names are provided. Images already exist and are correctly matched to these codes (verified by visual inspection against the source photos on 2026-08-10).
- **No real Room migration path** — `fallbackToDestructiveMigration()` means any future schema change wipes existing sessions/recordings on next launch (metadata only; saved WAV files on disk are untouched). Fine pre-release; needs a real `Migration` before this ships with real user data.
- **No adaptive icon** (`mipmap-anydpi-v26` foreground/background layers) — `ic_app_icon` is a single flat drawable referenced directly via `android:icon`. Works fine, just not using the modern adaptive-icon system; OEM launchers apply their own default masking.
- **Basic TAAL** on the home screen is hardcoded to `preFilter = "HEART"` in `HomeFragment.kt` — there's no filter picker before launching `TaalRecorderActivity` (matches `taal-ui-kit`'s own default). Revisit if Basic TAAL needs its own filter selection here.
- **Legacy (API < 29) share/rename paths are less battle-tested** than the MediaStore (API 29+) path, since testing so far has been on modern devices — worth a real pass on an API 24–28 device before shipping broadly.

---

## 13. Design System

- **Brand color**: TAAL teal — `teal_primary #2ABFBF`, `teal_dark #1FA3A3`, `teal_deep #0E5F60` (headline text), `teal_device #008DB9` (the stethoscope-mark brand color), `teal_soft #E3F5F5` (tinted icon badges/pills). Deliberately avoids introducing unrelated purple/green/blue accent colors.
- **Neutrals**: `surface #FFFFFF`, `background #F6F8F8`, `divider #E2E6E6`, `text_primary #333333`, `text_secondary #999999`, `text_hint #BBBBBB`.
- **Status**: `recording_red #E85555` / `recording_red_soft #FBEAEA` (Heart icon badge, delete actions), `success_green #4CAF50` (done indicators).
- **Cards over shadows**: `MaterialCardView` with a 1dp `divider`-colored stroke and `cardElevation="0dp"` used throughout instead of drop-shadow elevation — flatter, cleaner look.
- **Icons**: all custom vectors prefixed `vt_` (see §11), simple single-color Material-style glyphs. Notable ones: `vt_ic_redo` (re-record — intentionally distinct from `vt_ic_play_circle` to avoid the two looking like duplicate Play buttons), `vt_ic_heart`/`vt_ic_lungs` (Choose-screen icon badges), `vt_ic_placement_pin` (VisualizerTaal button on Home).
- **Logo**: `ic_app_icon.xml` — the TAAL stethoscope mark, teal `#008DB9`, centered on white. Same mark (icon-only, no baked background) is `vt_icon_taal.xml`, used inline on the Home screen's icon badge.

---

## 14. Build Commands

```bash
# Build debug APK
./gradlew :visualizertaal-app:assembleDebug

# Install on connected device(s)
./gradlew :visualizertaal-app:installDebug

# APK output
visualizertaal-app/build/outputs/apk/debug/visualizertaal-app-debug.apk

# Clean build
./gradlew :visualizertaal-app:clean :visualizertaal-app:assembleDebug
```

---

## 15. Changelog

### 2026-08-10
- Built the entire module from scratch: Home (Basic TAAL / VisualizerTaal) → Choose → PointSetHome (sessions "folder") → Placement → Recording → Player → Review.
- Added Room DB (`SessionEntity` + `RecordingEntity`, no patient tracking) for persistent, numbered, resumable sessions per Heart/Lungs.
- Added 4 heart + 20 lung placement guide images (`point_{code}.png`), matched and verified against source images.
- Added session rename (with automatic saved-filename sync via `SessionRenameCoordinator`), auto-advance to the next unrecorded point after Save, and a "Record" action for not-yet-recorded points directly from the session detail screen.
- Fixed two resource-collision bugs against `taal-ui-kit` (a layout-name crash, a `FileProvider` conflict) and one stale-TabLayout-listener bug causing wrong images to display.
- Full visual pass: replaced ad-hoc off-brand colors with a consistent TAAL-teal design system, new launcher icon/logo, icon cards on the Choose screen, framed placement images, flatter card styling throughout.
