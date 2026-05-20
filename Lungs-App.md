# Lungs Auscultation App — Complete Reference

> **Purpose of this file:** Complete technical reference for the `:lungs-app` Android module.
> Share this file with any AI assistant to give it full context before making changes.
> Last updated: 2026-05-20

---

## Changelog

### 2026-05-20
- **Placement dots first-load fix** (`PlacementFragment`): Added `binding.anatomyImage.doOnLayout { }` inside the overlay runnable. Previously `anatomyContainer.post { }` could fire before the first layout pass completed, giving `anatomyImage.width = 0` and putting all dots at (0, 0). `doOnLayout` fires immediately if the view is already laid out, or waits for the first layout pass otherwise. Fix: `import androidx.core.view.doOnLayout` added; button-building code moved inside `anatomyImage.doOnLayout { }` within the existing runnable (double `removeViews` guard included inside the `doOnLayout` lambda).
- **Duplicate patient on back-press fix** (`PatientFormFragment`): After `validateAndProceed()` inserts a new patient and navigates to `PlacementFragment`, the form stayed on the back stack. Pressing Back → patient form → pressing Next again → second patient created. Fixed by passing `NavOptions.Builder().setPopUpTo(R.id.homeFragment, false).build()` to `findNavController().navigate(...)` so the form is popped immediately on navigate. Edit mode (`saveEdit`) is unaffected.
- **Auto-backup disabled** (`AndroidManifest.xml`): Changed `android:allowBackup="true"` → `android:allowBackup="false"` to prevent Android Auto Backup restoring the Room DB from Google Drive on reinstall — which caused phantom patients to reappear after uninstalling and reinstalling the app.
- **Recording count denominator fix** (`SavedPatientsFragment`): Was hardcoded to `/ 16` regardless of session count. Now queries `sessionRepo.getSessionCountForPatient(patientId)` and shows `"$count / ${sessionCount * 16} recorded"` with `.coerceAtLeast(1)` guard.

### 2026-05-19
- **Multi-session support** — Full sessions feature added. Same patient can be recorded across multiple visits. See Section 8 for DB schema, Section 10 for new screens, Section 12 for updated file naming.
  - New Room entity: `LungSessionEntity` (table `lung_sessions`)
  - DB version bumped 1 → 2 with full migration (table recreation required for SQLite FK constraints)
  - New screen: `PatientSessionsListFragment` — sits between `SavedPatientsFragment` and `PatientSessionFragment`
  - "Record Again" button creates a new `LungSessionEntity` (sessionNumber = count + 1) and navigates to `PlacementFragment`
  - `PlacementViewModel.init()` is now session-scoped (`init(sessionId, repo)` — not patientId)
  - `sessionId` + `sessionNumber` threaded through full nav chain: PatientForm → Placement → Recording → Player
  - Versioned file naming: `lungs/{seqStr}/{sessionNumber}/{seqStr}.{sessionNumber}_{pointCode}.wav`
  - ZIP export named `Patient_01.2.zip` (includes sessionNumber); session title shown as `Patient 01 — Session 2`
  - Finish button in `PlacementFragment` pops to `patientSessionsListFragment` if in back stack, else `homeFragment`

### 2026-05-08
- **Export ZIP** (`PatientSessionFragment`): Added `btnExportZip` ImageButton (`ic_zip`) in the top bar. Zips all 16 saved WAV files via `java.util.zip.ZipOutputStream` and shares via `Intent.ACTION_SEND`. If < 16 recordings exist, shows a Toast with count instead of exporting.
- **Top bar layout fix** (`fragment_patient_session.xml`): `screenTitle` changed to `0dp` constrained between `backButton` and `btnExportZip` to prevent overlap.
- **Placement dot screen-size fix** (`PlacementFragment`): `getImageDisplayRect()` computes actual displayed image bounds accounting for `fitCenter` letterboxing. Dots positioned relative to real image area, not full FrameLayout.
- **Patient edit mode** (`PatientFormFragment`): Full edit-mode support. `patientId = -1L` = new patient; any other = edit. Pre-fills all fields, "Save Changes" button, calls `repo.update()`.
- **Drag-to-calibrate toggle**: `ACTION_MOVE` block in `PlacementFragment.addPointButton()` is currently commented out. Tap-to-record is active.
- **Crash hardening**: `requireContext()` captured before IO coroutines in `LungsPlayerFragment`, `PatientSessionFragment`. `isAdded && _binding != null` guards added to all fragment navigation calls.

---

## 1. What This Is

A standalone Android application (`com.musediagnostics.taal.lungs`) for recording **16 lung auscultation points** across 4 anatomical regions using the MUSE Diagnostics TAAL USB stethoscope.

It lives as a module (`:lungs-app`) inside `E:\AndroidProjects\TaalDemoApp` but produces its **own separate APK** — completely independent from the main `:app` module.

---

## 2. Project Structure

```
E:\AndroidProjects\TaalDemoApp\
├── app/                    ← Main TaalDemoApp — DO NOT TOUCH
├── taal-core/              ← USB audio engine — DO NOT TOUCH
├── taal-ui-kit/            ← UI kit for main app — NOT USED by lungs-app
├── lungs-app/              ← THIS APP
└── settings.gradle.kts     ← includes(":lungs-app") added here
```

### Dependency Graph
```
:taal-core   (USB audio capture, WAV writing, AudioFilterEngine, TaalRecorder, TaalPlayer)
     ↑
:lungs-app   (own UI, own Room DB, own nav graph — depends ONLY on :taal-core)

:app         (untouched, unaffected)
:taal-ui-kit (not used by lungs-app)
```

**Key rule:** lungs-app depends only on `:taal-core`, NOT on `:taal-ui-kit`.

---

## 3. App Identity

| Field | Value |
|---|---|
| applicationId | `com.musediagnostics.taal.lungs` |
| namespace | `com.musediagnostics.taal.lungs` |
| minSdk | 24 |
| targetSdk | 34 |
| versionCode | 1 |
| versionName | 1.0 |
| App name | Lungs Auscultation |
| App icon | `@drawable/ic_lungs` (set in AndroidManifest for both icon + roundIcon) |
| Entry Activity | `MainActivity` (single activity, NavHostFragment) |
| DB name | `lungs_database` |
| DB version | **2** (bumped from 1 — sessions feature added 2026-05-19) |
| allowBackup | `false` (prevents DB restore on reinstall) |

---

## 4. Module File Layout

```
lungs-app/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   └── service_account.json       ← Google service account key (Drive upload stub — not active)
│   └── java/com/musediagnostics/taal/lungs/
│       ├── LungsApplication.kt
│       ├── data/
│       │   ├── db/
│       │   │   ├── LungsDatabase.kt            ← DB v2; has MIGRATION_1_2
│       │   │   ├── entity/
│       │   │   │   ├── LungPatientEntity.kt
│       │   │   │   ├── LungRecordingEntity.kt  ← now has sessionId FK
│       │   │   │   └── LungSessionEntity.kt    ← NEW (2026-05-19)
│       │   │   └── dao/
│       │   │       ├── LungPatientDao.kt
│       │   │       ├── LungRecordingDao.kt     ← new session-scoped queries
│       │   │       └── LungSessionDao.kt       ← NEW (2026-05-19)
│       │   └── repository/
│       │       ├── LungPatientRepository.kt
│       │       ├── LungRecordingRepository.kt  ← new session-scoped methods
│       │       └── LungSessionRepository.kt    ← NEW (2026-05-19)
│       ├── denoiser/
│       │   ├── CrnDenoiser.kt
│       │   ├── LungsDenoiser.kt
│       │   ├── StftEngine.kt
│       │   └── WienerDenoiser.kt
│       ├── domain/
│       │   └── LungPoint.kt           (LungRegion enum + LungPoint data class + LungPoints object)
│       ├── drive/
│       │   └── DriveUploadHelper.kt   (Google Drive service account upload — stubbed, button hidden)
│       └── ui/
│           ├── MainActivity.kt
│           ├── home/
│           │   ├── HomeFragment.kt
│           │   └── SavedPatientsFragment.kt    ← shows sessionCount * 16 denominator
│           ├── patient/
│           │   ├── PatientFormFragment.kt      ← pops itself on navigate (no back-press dupe)
│           │   └── PatientFormViewModel.kt
│           ├── placement/
│           │   ├── PlacementFragment.kt        ← doOnLayout fix; session-scoped
│           │   └── PlacementViewModel.kt       ← init(sessionId, repo)
│           ├── recording/
│           │   ├── LungsRecordingFragment.kt
│           │   └── LungsRecordingViewModel.kt
│           ├── player/
│           │   └── LungsPlayerFragment.kt      ← versioned file path incl. sessionNumber
│           ├── denoiser/
│           │   └── DenoiserFragment.kt         ← per-patient denoiser (patient-scoped, not session-scoped)
│           └── session/
│               ├── PatientSessionFragment.kt   ← session-scoped; title "Patient 01.2"
│               └── PatientSessionsListFragment.kt ← NEW (2026-05-19): sessions list per patient
└── src/main/res/
    ├── navigation/lungs_nav_graph.xml
    ├── xml/
    │   └── file_provider_paths.xml
    ├── layout/
    │   ├── activity_main.xml
    │   ├── fragment_home.xml
    │   ├── fragment_patient_form.xml
    │   ├── fragment_placement.xml
    │   ├── fragment_lungs_recording.xml
    │   ├── fragment_lungs_player.xml
    │   ├── fragment_saved_patients.xml
    │   ├── fragment_patient_sessions_list.xml  ← NEW (2026-05-19)
    │   ├── fragment_patient_session.xml
    │   ├── fragment_denoiser.xml
    │   ├── item_patient.xml
    │   ├── item_session.xml                    ← NEW (2026-05-19): session card row
    │   ├── item_session_header.xml
    │   ├── item_recording.xml
    │   └── item_denoiser_row.xml
    ├── drawable/
    │   ├── ic_lungs.png/xml
    │   ├── ic_share.xml
    │   ├── ic_delete.xml
    │   ├── ic_cloud_upload.xml
    │   ├── ic_arrow_back.xml
    │   ├── ic_zip.xml
    │   ├── bg_status_dot.xml
    │   ├── bg_button_teal.xml
    │   ├── bg_point_button_done.xml
    │   ├── bg_point_button_pending.xml
    │   ├── placeholder_anterior_right.png
    │   ├── placeholder_anterior_left.png
    │   ├── placeholder_posterior_right.png
    │   ├── placeholder_posterior_left.png
    │   ├── point_aal.png                      ← Per-point placement guides (Ant. Left done)
    │   ├── point_asll.png
    │   ├── point_amll.png
    │   └── point_aill.png
    ├── mipmap-*/
    └── values/
        ├── strings.xml
        ├── colors.xml
        ├── dimens.xml
        └── themes.xml
```

---

## 5. Dependencies (build.gradle.kts)

```kotlin
implementation(project(":taal-core"))

// Core Android
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
implementation("androidx.constraintlayout:constraintlayout:2.1.4")
implementation("androidx.recyclerview:recyclerview:1.3.2")

// Navigation
implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

// Lifecycle + ViewModel
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

// Room
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

// Waveform chart
implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

// Google Drive (service account upload — stubbed, button currently hidden)
implementation("com.google.api-client:google-api-client-android:2.2.0") {
    exclude(group = "org.apache.httpcomponents")
}
implementation("com.google.apis:google-api-services-drive:v3-rev20230822-2.0.0") {
    exclude(group = "org.apache.httpcomponents")
}
implementation("com.google.http-client:google-http-client-gson:1.43.3") {
    exclude(group = "org.apache.httpcomponents")
}
implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")
```

### Packaging options (required for Google libs)
```kotlin
packaging {
    resources {
        excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
            "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/*.kotlin_module",
            "META-INF/INDEX.LIST", "META-INF/AL2.0", "META-INF/LGPL2.1"
        )
    }
}
```

---

## 6. User Flow (Screen by Screen)

```
HomeFragment
  ├── [Add New Patient] → PatientFormFragment
  └── [View Saved]      → SavedPatientsFragment

PatientFormFragment (new patient mode)
  └── [Next] validates → inserts LungPatientEntity + LungSessionEntity(sessionNumber=1)
           → PlacementFragment(patientId, patientSeqNum, sessionId, sessionNumber=1)
           NOTE: form is POPPED from back stack on navigate; Back from Placement → Home

PlacementFragment
  └── [tap pending point] → LungsRecordingFragment(patientId, patientSeqNum, sessionId, sessionNumber, pointCode)
      [Finish / all done] → patientSessionsListFragment if in back stack, else homeFragment
      NOTE: drag-to-calibrate is disabled; tap-to-record is active

LungsRecordingFragment
  └── [Stop Recording] → LungsPlayerFragment(filePath, rawFilePath, patientId, patientSeqNum,
                                             sessionId, sessionNumber, pointCode)

LungsPlayerFragment (new recording)
  ├── [Save]    → saves WAV to versioned path → inserts Room record → popBackStack to placementFragment
  └── [Discard] → deletes both temp files → navigateUp()

SavedPatientsFragment
  └── [tap patient card] → PatientSessionsListFragment(patientId, patientSeqNum)

PatientSessionsListFragment                          ← NEW (2026-05-19)
  ├── [tap session card] → PatientSessionFragment(patientId, patientSeqNum, sessionId, sessionNumber)
  ├── [Record Again]     → creates new LungSessionEntity → PlacementFragment(new sessionId, new sessionNumber)
  └── [Edit Patient]     → PatientFormFragment(patientId) in edit mode

PatientSessionFragment (single session review)
  ├── [tap recorded row]      → LungsPlayerFragment(isReviewMode=true)
  ├── [share icon on row]     → system share sheet for individual .wav
  ├── [delete icon on row]    → confirm dialog → deleteById + File.delete()
  ├── [Share Report]          → plain-text summary via Intent.ACTION_SEND
  ├── [Export ZIP]            → zips all WAVs for this session → Intent.ACTION_SEND
  ├── [Denoiser]              → DenoiserFragment(patientId, patientSeqNum)
  ├── [Edit Patient]          → PatientFormFragment in edit mode
  ├── [Continue Recording]    → PlacementFragment (only shown when < 16 done)
  └── [Upload to Drive]       → HIDDEN (visibility=gone — Drive upload stubbed)

DenoiserFragment
  ├── [Denoise] → runs LungsDenoiser.denoiseWav() on IO thread → saves denoised WAV
  └── [Play]    → LungsPlayerFragment(isReviewMode=true) for denoised file
  NOTE: patient-scoped (queries all recordings for patient, not per-session)
```

---

## 7. Navigation Graph (`lungs_nav_graph.xml`)

- **startDestination**: `homeFragment`

| Action | From → To |
|---|---|
| `action_home_to_patient_form` | homeFragment → patientFormFragment |
| `action_home_to_saved_patients` | homeFragment → savedPatientsFragment |
| `action_patient_form_to_placement` | patientFormFragment → placementFragment |
| `action_placement_to_recording` | placementFragment → lungsRecordingFragment |
| `action_lungs_recording_to_player` | lungsRecordingFragment → lungsPlayerFragment |
| `action_saved_to_sessions_list` | savedPatientsFragment → patientSessionsListFragment |
| `action_sessions_list_to_session` | patientSessionsListFragment → patientSessionFragment |
| `action_sessions_list_to_placement` | patientSessionsListFragment → placementFragment |
| `action_sessions_list_to_edit_patient` | patientSessionsListFragment → patientFormFragment |
| `action_session_to_placement` | patientSessionFragment → placementFragment |
| `action_session_to_player` | patientSessionFragment → lungsPlayerFragment |
| `action_session_to_edit_patient` | patientSessionFragment → patientFormFragment (edit mode) |
| `action_session_to_denoiser` | patientSessionFragment → denoiserFragment |
| `action_denoiser_to_player` | denoiserFragment → lungsPlayerFragment |

### Nav Args

| Fragment | Args |
|---|---|
| `patientFormFragment` | `patientId: long (default=-1L)` — `-1L` = new, any other = edit mode |
| `placementFragment` | `patientId: long`, `patientSeqNum: int`, `sessionId: long`, `sessionNumber: int` |
| `lungsRecordingFragment` | `patientId: long`, `patientSeqNum: int`, `sessionId: long`, `sessionNumber: int`, `pointCode: string` |
| `lungsPlayerFragment` | `filePath: string`, `rawFilePath: string`, `patientId: long`, `patientSeqNum: int`, `sessionId: long`, `sessionNumber: int`, `pointCode: string`, `isReviewMode: boolean (default=false)` |
| `savedPatientsFragment` | _(none)_ |
| `patientSessionsListFragment` | `patientId: long`, `patientSeqNum: int` |
| `patientSessionFragment` | `patientId: long`, `patientSeqNum: int`, `sessionId: long`, `sessionNumber: int` |
| `denoiserFragment` | `patientId: long`, `patientSeqNum: int` |

> **Critical nav arg rule**: `long` type default values MUST use the `L` suffix in XML (`android:defaultValue="-1L"`), otherwise Navigation throws `XmlPullParserException: Type is long but found integer` at startup.

---

## 8. Data Layer

### LungPatientEntity
```kotlin
@Entity(tableName = "lung_patients")
data class LungPatientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sequenceNumber: Int,          // formatted as 01, 02, 03...
    val sex: String,                  // "Male" | "Female" | "Other"
    val age: Int,
    val chestCircumferenceCm: Float,
    val heightCm: Float,
    val weightKg: Float,
    val bmi: Float,                   // auto-computed: weightKg / (heightCm/100)^2
    val createdAt: Long = System.currentTimeMillis()
)
```

### LungSessionEntity _(added DB v2)_
```kotlin
@Entity(
    tableName = "lung_sessions",
    foreignKeys = [ForeignKey(
        entity = LungPatientEntity::class,
        parentColumns = ["id"], childColumns = ["patientId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("patientId")]
)
data class LungSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val sessionNumber: Int,           // 1, 2, 3...
    val createdAt: Long = System.currentTimeMillis()
)
```

### LungRecordingEntity _(updated DB v2: added sessionId)_
```kotlin
@Entity(
    tableName = "lung_recordings",
    foreignKeys = [
        ForeignKey(entity = LungPatientEntity::class,
            parentColumns = ["id"], childColumns = ["patientId"],
            onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LungSessionEntity::class,
            parentColumns = ["id"], childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("patientId"), Index("sessionId")]
)
data class LungRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val sessionId: Long,              // FK → lung_sessions
    val pointCode: String,            // e.g. "aar", "pslr"
    val filePath: String,             // absolute path to saved WAV
    val durationSeconds: Int,
    val createdAt: Long = System.currentTimeMillis()
)
```

### DAOs

**LungPatientDao**: `insert`, `getAllPatients(): Flow`, `getById(id)`, `getCount(): Int`, `update(patient)`, `deleteById(id)`, `getNextSequenceNumber()` (= `getCount() + 1`)

**LungSessionDao** _(new)_: `insert(session): Long`, `getSessionsForPatient(patientId): Flow<List>`, `getById(id): LungSessionEntity?`, `getSessionCountForPatient(patientId): Int`

**LungRecordingDao**: `insert`, `getRecordingsForPatient(patientId): Flow`, `getRecordingsForSession(sessionId): Flow`, `getRecordingCountForPatient(patientId): Int`, `getRecordingCountForSession(sessionId): Int`, `getRecordingForPointInSession(sessionId, pointCode): LungRecordingEntity?`, `deleteById(id)`

### LungsDatabase — Migration 1 → 2

Full table recreation (SQLite `ALTER TABLE ADD COLUMN` cannot add FK constraints):

1. Create `lung_sessions` table with FK CASCADE to `lung_patients`
2. Insert `session 1` for every existing patient: `INSERT INTO lung_sessions SELECT id, 1, createdAt FROM lung_patients`
3. Create `lung_recordings_new` with both FKs (`patientId` + `sessionId`)
4. Copy existing recordings with JOIN to resolve `sessionId` from the just-inserted session 1
5. Drop old `lung_recordings`, rename `lung_recordings_new`, recreate indices

---

## 9. Domain Model (`LungPoint.kt`)

### LungRegion enum
```kotlin
enum class LungRegion(val label: String, val drawableResName: String) {
    ANTERIOR_RIGHT("Anterior Right (Front)", "placeholder_anterior_right"),
    ANTERIOR_LEFT("Anterior Left (Front)",   "placeholder_anterior_left"),
    POSTERIOR_RIGHT("Posterior Right (Back)","placeholder_posterior_right"),
    POSTERIOR_LEFT("Posterior Left (Back)",  "placeholder_posterior_left");
    val index: Int get() = ordinal
}
```

### All 16 LungPoints (current coordinates)

| Code | Label | Region | xFrac | yFrac |
|---|---|---|---|---|
| aar | Apex Right | ANTERIOR_RIGHT | 0.62 | 0.31 |
| aslr | Superior Right | ANTERIOR_RIGHT | 0.65 | 0.41 |
| amlr | Middle Right | ANTERIOR_RIGHT | 0.71 | 0.52 |
| ailr | Inferior Right | ANTERIOR_RIGHT | 0.71 | 0.63 |
| aal | Apex Left | ANTERIOR_LEFT | 0.50 | 0.28 |
| asll | Superior Left | ANTERIOR_LEFT | 0.32 | 0.40 |
| amll | Middle Left | ANTERIOR_LEFT | 0.30 | 0.51 |
| aill | Inferior Left | ANTERIOR_LEFT | 0.27 | 0.63 |
| par | Post. Apex Right | POSTERIOR_RIGHT | 0.63 | 0.33 |
| pslr | Post. Superior Right | POSTERIOR_RIGHT | 0.59 | 0.44 |
| pmlr | Post. Middle Right | POSTERIOR_RIGHT | 0.59 | 0.54 |
| pilr | Post. Inferior Right | POSTERIOR_RIGHT | 0.60 | 0.63 |
| pal | Post. Apex Left | POSTERIOR_LEFT | 0.41 | 0.34 |
| psll | Post. Superior Left | POSTERIOR_LEFT | 0.41 | 0.44 |
| pmll | Post. Middle Left | POSTERIOR_LEFT | 0.41 | 0.56 |
| pill | Post. Inferior Left | POSTERIOR_LEFT | 0.41 | 0.67 |

### Point image naming convention
Per-point stethoscope placement guide images: `point_{pointCode}.png`  
e.g. `point_aal.png` — placed in `lungs-app/src/main/res/drawable/`  
Anterior Left images are present. All others still needed.

---

## 10. Screen Details

### HomeFragment
- Two buttons: "Add New Patient" and "View Saved Recordings"
- App logo: `@drawable/ic_lungs` ImageView at top

### PatientFormFragment + PatientFormViewModel
- Fields: sex (ChipGroup: Male/Female/Other), age, chest circumference (cm), height (cm), weight (kg)
- **BMI**: Auto-computed via `MediatorLiveData` watching height + weight — live display, `--` until both valid
- **Inch→cm converter**: toggle row with `btnInchConverter`; auto-fills chest field
- **Patient ID**: read-only, pre-populated via `repo.getNextSequenceNumber()`
- **New patient mode** (`patientId = -1L`):
  - Validates → inserts `LungPatientEntity`
  - Immediately inserts `LungSessionEntity(patientId = newId, sessionNumber = 1)`
  - Navigates to `PlacementFragment` with `sessionId + sessionNumber = 1`
  - Uses `NavOptions.setPopUpTo(R.id.homeFragment, false)` → form is removed from back stack instantly; pressing Back from Placement goes to Home, not the form
- **Edit mode** (`patientId ≠ -1L`): pre-fills all fields, "Save Changes" button, calls `repo.update()` → `navigateUp()`
- Both flows guarded with `if (!isAdded || _binding == null) return@launch` after every IO suspend

### PatientSessionsListFragment _(new 2026-05-19)_
- Entry: from `SavedPatientsFragment` when tapping a patient card
- **Patient info card**: shows `Patient 01` title, sex / age / BMI
- **Sessions RecyclerView** (`item_session.xml`): badge (`S1`, `S2`), title, date, recording count (`X / 16`)
- **Record Again button**: queries session count → creates `LungSessionEntity(sessionNumber = count + 1)` on IO → navigates to `PlacementFragment` with new `sessionId`/`sessionNumber`
- **Edit Patient button**: navigates to `PatientFormFragment` in edit mode

### PlacementFragment + PlacementViewModel

#### Region navigation tabs (TabLayout)
- 4 fixed-width tabs: **Ant. R | Ant. L | Post. R | Post. L**
- Tapping any tab calls `viewModel.setRegion(index)`
- `isSyncingTab` flag prevents observer → tab → observer feedback loop

#### Anatomy overlay (dot positioning)
- Buttons built inside `anatomyContainer.post { anatomyImage.doOnLayout { ... } }`
- `doOnLayout` guarantees image has non-zero dimensions before positioning; fires immediately on revisit
- `pendingOverlayRunnable` + `removeCallbacks()` cancels stale posts before scheduling a new one
- `getImageDisplayRect()` computes actual displayed image area (fitCenter letterboxing) → dots stay on anatomy regardless of screen size
- Done buttons: `"✓"` text, alpha 0.8, not interactive

#### Drag calibration (CURRENTLY DISABLED)
- `ACTION_MOVE` block is commented out — dots are fixed
- To re-enable: uncomment `ACTION_MOVE` block in `addPointButton()` (clearly marked)
- **Tap to record: ACTIVE** — `ACTION_UP` with `!hasDragged && !isDone && isAdded && _binding != null`

#### Finish button behaviour
- Calls `findNavController().popBackStack(R.id.patientSessionsListFragment, false)`
- If sessions list not in back stack (e.g. first recording flow), falls through to `popBackStack(R.id.homeFragment, false)`

#### PlacementViewModel
- `init(sessionId, repo)` — idempotent, guards with `if (this.sessionId == sessionId) return`
- Queries `repo.getRecordingsForSession(sessionId)` → each session starts with all 16 pending, independent of other sessions
- `_recordings: Map<String, Boolean>` (pointCode → isDone)
- `autoAdvanceRegionIfNeeded()` — auto-advances to next incomplete region when current is all done

### LungsRecordingFragment + LungsRecordingViewModel
- Receives: `patientId`, `patientSeqNum`, `sessionId`, `sessionNumber`, `pointCode`
- **Filter**: always `PreFilter.LUNGS` — hardcoded, no filter chips
- **Pre-amp**: 0–30 dB slider, reset to 5 dB on `onResume()`
- **Two temp files**: `recording_{ts}_raw.wav` + `recording_{ts}_filtered.wav` in `filesDir`
- On stop → navigates to `lungsPlayerFragment` with all args including `sessionId`/`sessionNumber`

### LungsPlayerFragment
- Receives: `filePath`, `rawFilePath`, `patientId`, `patientSeqNum`, `sessionId`, `sessionNumber`, `pointCode`, `isReviewMode`
- **Review mode** (`isReviewMode=true`): Save/Discard bar hidden; no DB insert
- **Double-filter protection**: if filename contains `_filtered`, `setPreFilter` is skipped on `TaalPlayer`
- **Save flow**:
  - Versioned path: `lungs/{seqStr}/{sessionNumber}/{seqStr}.{sessionNumber}_{pointCode}.wav`
  - e.g. Patient 01, Session 2, point aar → `lungs/01/2/01.2_aar.wav`
  - Renames filtered temp WAV to versioned path, deletes raw temp
  - Inserts `LungRecordingEntity` with `sessionId`
  - `popBackStack(R.id.placementFragment, false)`
- **Discard**: deletes both temp files → `navigateUp()`

### SavedPatientsFragment
- Lists all patients from Room as `item_patient.xml` cards
- Recording count shown as: `"$count / ${sessionCount * 16} recorded"` (denominator = sessions × 16, guarded with `.coerceAtLeast(1)`)
- Tap → `action_saved_to_sessions_list` → `PatientSessionsListFragment`

### PatientSessionFragment
- Session-scoped (takes `sessionId` + `sessionNumber`)
- **Title**: `"Patient %02d.%d".format(sequenceNumber, sessionNumber)` e.g. `Patient 01.2`
- Queries `getRecordingsForSession(sessionId)` — shows only recordings for this session
- RecyclerView: 4 `Header` rows + 16 `PointRow` rows (region + point breakdown)
- **Export ZIP** (`btnExportZip`): ZIP named `Patient_01.2.zip`, shares via FileProvider
- **Continue Recording**: navigates to `PlacementFragment` passing `sessionId`/`sessionNumber`
- **Edit Patient** (`btnEditPatient`): → `PatientFormFragment` in edit mode
- **Denoiser** (`btnDenoiser`): → `DenoiserFragment` passing `patientId`/`patientSeqNum`
- **Upload to Drive**: button `visibility=gone` (stubbed — see Section 11)

### DenoiserFragment
- Entry: from `PatientSessionFragment` via `action_session_to_denoiser`
- Args: `patientId`, `patientSeqNum`
- **Note**: currently **patient-scoped**, not session-scoped — queries `getRecordingsForPatient(patientId)` across all sessions
- **Denoised file path**: `lungs/{seqStr}/denoised/{seqStr}_{pointCode}.wav` (uses old non-versioned naming — known limitation)
- Each row shows: point label, region, status dot (green = denoised, grey = pending/no recording)
- Row states: `Denoise` button (active if original exists), `Processing...` (disabled during denoising), `▶ Play` button (if denoised file exists), disabled (no original recording)
- `setProcessing(pointCode, true/false)` updates UI via `notifyItemChanged` while coroutine runs
- `LungsDenoiser.denoiseWav(inputPath, outputPath)` — runs on `Dispatchers.IO`; on success rebuilds the list to refresh done states

---

## 11. Google Drive Upload (STUBBED — Not Active)

`DriveUploadHelper.kt` in `drive/` package — fully implemented but **button is hidden** (`android:visibility="gone"` on `btnUploadDrive`).

**Why hidden**: Service accounts have no Drive storage quota → `storageQuotaExceeded` (403).

**When to fix**: Switch to `GoogleSignIn` + OAuth so files are owned by a real Google account (`cloudbotz2024@gmail.com`). Set button visibility back to `visible` once implemented.

**Current DriveUploadHelper behaviour** (if re-enabled):
- Reads `assets/service_account.json`
- Creates `TaalLungs Auscultation/Patient_01_YYYY-MM-DD/` folder in service account Drive
- Uploads `report.txt` + all recorded WAVs
- `SHARE_WITH_EMAIL = "cloudbotz2024@gmail.com"` — auto-shares root folder on first creation

---

## 12. File Naming and Storage Convention

```
{context.filesDir}/
├── recording_{timestamp}_raw.wav         ← TEMP: raw capture (deleted on save or discard)
├── recording_{timestamp}_filtered.wav    ← TEMP: filtered capture (renamed on save, deleted on discard)
└── lungs/
    └── 01/                               ← patient sequenceNumber as %02d
        ├── 1/                            ← sessionNumber (1, 2, 3...)
        │   ├── 01.1_aar.wav             ← {seqStr}.{sessionNumber}_{pointCode}.wav
        │   ├── 01.1_aslr.wav
        │   └── ...
        ├── 2/
        │   ├── 01.2_aar.wav
        │   └── ...
        └── denoised/
            ├── 01_aar.wav               ← denoised: {seqStr}_{pointCode}.wav (patient-scoped, no session)
            └── ...
```

- On Save: filtered temp renamed to `lungs/{seqStr}/{sessionNumber}/{seqStr}.{sessionNumber}_{pointCode}.wav`
- On Save: raw temp deleted
- On Discard: both temp files deleted

---

## 13. FileProvider (WAV Sharing)

```xml
<!-- AndroidManifest.xml -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_provider_paths" />
</provider>
```

```xml
<!-- res/xml/file_provider_paths.xml -->
<paths>
    <files-path name="lungs_files" path="." />
</paths>
```

Used in `PatientSessionFragment.shareRecording()` and ZIP export.

---

## 14. Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
```

`android:allowBackup="false"` — prevents Android Auto Backup from restoring the Room DB from Google Drive on reinstall (fixes phantom patients reappearing).

---

## 15. Key Patterns and Conventions

1. **ViewBinding**: `_binding`/`binding` pair; `_binding = null` in `onDestroyView()`
2. **Fragment lifecycle safety**: `isAdded && _binding != null` before any UI update from async callbacks
3. **UI thread dispatch**: Recorder/player callbacks on background threads → `activity?.runOnUiThread { }`
4. **Room on IO thread**: All DB operations in `Dispatchers.IO` coroutines; `ctx` captured before `launch`
5. **Pre-amp reset**: Always reset to 5 dB in `onResume()`
6. **TaalRecorder error handling**: `start()` throws `TaalDisconnectedException` if no USB device
7. **PlacementViewModel idempotent init**: `if (this.sessionId == sessionId) return` (session-scoped since v2)
8. **AudioTrack cleanup**: `stop()` + `release()` in `stopAudioMonitor()`, set to null
9. **Player cleanup**: null callbacks → `stop()` → `release()` in `onDestroyView()`
10. **Overlay rebuild deduplication**: `pendingOverlayRunnable` + `removeCallbacks()` before each `post { }`; `doOnLayout` inside the runnable ensures image is laid out before computing dot positions
11. **Form → Placement nav**: Uses `NavOptions.setPopUpTo(homeFragment, false)` so the patient form is removed from the back stack immediately on navigate — prevents duplicate patient creation if user presses Back and re-submits
12. **Long nav arg defaults**: Must use `"-1L"` syntax in XML (not `"-1"`) for `app:argType="long"` defaults

---

## 16. Colors and Theme

```xml
teal_primary:    #2ABFBF
teal_dark:       #1A9999
teal_device:     #008DB9   (USB icon when connected)
recording_red:   #E85555
success_green:   #4CAF50   (done point buttons, recorded status dot, denoiser done dot)
waveform_blue:   #2D7DD2
divider:         (light grey — used for unrecorded/pending status dots)

Theme: Theme.LungsApp → MaterialComponents.Light.NoActionBar
```

---

## 17. Build Commands

```bash
# Build debug APK
./gradlew :lungs-app:assembleDebug

# APK output
lungs-app/build/outputs/apk/debug/lungs-app-debug.apk

# Install directly to connected device
./gradlew :lungs-app:installDebug

# Clean build
./gradlew :lungs-app:clean :lungs-app:assembleDebug
```

---

## 18. Pending / In-Progress Work

### Button position calibration
- Dots are screen-size-independent (image-rect-relative, `doOnLayout` for first-load safety)
- Drag-to-calibrate **currently disabled** — `ACTION_MOVE` block commented out in `PlacementFragment.addPointButton()`
- Tap-to-record **active**
- To re-enable drag: uncomment `ACTION_MOVE` block (clearly marked in code)
- Once positions are finalised, update `xFraction`/`yFraction` values in `LungPoints.kt`

### Point placement images needed
- Anterior Left: **done** (`point_aal`, `point_asll`, `point_amll`, `point_aill`)
- Anterior Right: **missing** (`point_aar`, `point_aslr`, `point_amlr`, `point_ailr`)
- Posterior Right: **missing** (`point_par`, `point_pslr`, `point_pmlr`, `point_pilr`)
- Posterior Left: **missing** (`point_pal`, `point_psll`, `point_pmll`, `point_pill`)

### Google Drive upload (needs OAuth fix)
- Current impl uses service account → fails with `storageQuotaExceeded`
- Fix: switch to Google Sign-In OAuth so files are owned by `cloudbotz2024@gmail.com`
- Button is `visibility=gone` until fixed

### Denoiser — session-scope gap
- `DenoiserFragment` currently queries by patient (`getRecordingsForPatient`) — shows recordings across all sessions
- Denoised file path is non-versioned (`lungs/{seqStr}/denoised/{seqStr}_{pointCode}.wav`) — no sessionNumber in path
- Future fix: make it session-scoped (pass `sessionId`; query `getRecordingsForSession`; update denoised path to include sessionNumber)

### Future features
- [ ] Re-record a point (overwrite existing recording for a session point)
- [ ] Session completion summary screen after all 16 points recorded
- [ ] Drive upload via OAuth (replace service account)

---

## 19. Known Limitations

- `SavedPatientsFragment` and `PatientSessionsListFragment` adapters use a `CoroutineScope` per ViewHolder for async count loading — not cancelled on recycle (acceptable for read-only, small datasets)
- Drive upload stubbed — button hidden, requires OAuth rewrite
- Waveform rendering in `LungsPlayerFragment` reads full WAV into memory — fine for recordings up to a few minutes
- Denoiser is patient-scoped, not session-scoped (denoises across all sessions for a patient)
