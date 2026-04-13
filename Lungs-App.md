# Lungs Auscultation App — Complete Reference

> **Purpose of this file:** Complete technical reference for the `:lungs-app` Android module.
> Share this file with any AI assistant to give it full context before making changes.
> Last updated: 2026-04-13 (session screen, tabs, bug fixes, icon, Drive stub)

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
| DB version | 1 |

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
│       │   │   ├── LungsDatabase.kt
│       │   │   ├── entity/
│       │   │   │   ├── LungPatientEntity.kt
│       │   │   │   └── LungRecordingEntity.kt
│       │   │   └── dao/
│       │   │       ├── LungPatientDao.kt
│       │   │       └── LungRecordingDao.kt
│       │   └── repository/
│       │       ├── LungPatientRepository.kt
│       │       └── LungRecordingRepository.kt
│       ├── domain/
│       │   └── LungPoint.kt           (LungRegion enum + LungPoint data class + LungPoints object)
│       ├── drive/
│       │   └── DriveUploadHelper.kt   (Google Drive service account upload — stubbed, button hidden)
│       └── ui/
│           ├── MainActivity.kt
│           ├── home/
│           │   ├── HomeFragment.kt
│           │   └── SavedPatientsFragment.kt
│           ├── patient/
│           │   ├── PatientFormFragment.kt
│           │   └── PatientFormViewModel.kt
│           ├── placement/
│           │   ├── PlacementFragment.kt
│           │   └── PlacementViewModel.kt
│           ├── recording/
│           │   ├── LungsRecordingFragment.kt
│           │   └── LungsRecordingViewModel.kt
│           ├── player/
│           │   └── LungsPlayerFragment.kt
│           └── session/
│               └── PatientSessionFragment.kt   (+ SessionAdapter + SessionListItem sealed class)
└── src/main/res/
    ├── navigation/lungs_nav_graph.xml
    ├── xml/
    │   └── file_provider_paths.xml             (FileProvider paths for WAV sharing)
    ├── layout/
    │   ├── activity_main.xml
    │   ├── fragment_home.xml
    │   ├── fragment_patient_form.xml
    │   ├── fragment_placement.xml              (now includes TabLayout for region navigation)
    │   ├── fragment_lungs_recording.xml
    │   ├── fragment_lungs_player.xml           (ids: playerRoot, screenTitle, saveDiscardBar)
    │   ├── fragment_saved_patients.xml
    │   ├── fragment_patient_session.xml        (session review screen)
    │   ├── item_patient.xml
    │   ├── item_session_header.xml             (region header row in session RecyclerView)
    │   └── item_recording.xml                 (recording row with play/share/delete buttons)
    ├── drawable/
    │   ├── ic_lungs.png/xml                   ← App icon + home screen logo
    │   ├── ic_share.xml                       ← Share icon
    │   ├── ic_delete.xml                      ← Delete/trash icon
    │   ├── ic_cloud_upload.xml                ← Drive upload icon
    │   ├── ic_arrow_back.xml
    │   ├── bg_status_dot.xml                  ← Oval dot for recording status
    │   ├── bg_button_teal.xml
    │   ├── bg_point_button_done.xml
    │   ├── bg_point_button_pending.xml
    │   ├── placeholder_anterior_right.png     ← Region overview anatomy images (4 total)
    │   ├── placeholder_anterior_left.png
    │   ├── placeholder_posterior_right.png
    │   ├── placeholder_posterior_left.png
    │   ├── point_aal.png                      ← Per-point stethoscope placement guides
    │   ├── point_asll.png                     ← (anterior left — 4 images present)
    │   ├── point_amll.png
    │   └── point_aill.png
    ├── mipmap-*/                              (default launcher icons — overridden by ic_lungs in manifest)
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

PatientFormFragment
  └── [Next] (validates + inserts patient into Room) → PlacementFragment(patientId, patientSeqNum)

PlacementFragment
  └── [tap any pending point] → LungsRecordingFragment(patientId, patientSeqNum, pointCode)
      NOTE: drag-to-calibrate is disabled; tap-to-record is active

LungsRecordingFragment
  └── [Stop Recording] → LungsPlayerFragment(filePath, rawFilePath, patientId, patientSeqNum, pointCode)

LungsPlayerFragment (new recording)
  ├── [Save]    → auto-saves WAV → inserts Room record → popBackStack to placementFragment
  └── [Discard] → deletes both temp files → navigateUp()

SavedPatientsFragment
  └── [tap patient card] → PatientSessionFragment(patientId, patientSeqNum)

PatientSessionFragment
  ├── [tap point row — recorded]    → LungsPlayerFragment(isReviewMode=true) — review only, no Save/Discard bar
  ├── [share icon on row]           → system share sheet for individual .wav file
  ├── [delete icon on row]          → MaterialAlertDialog confirm → delete Room record + file
  ├── [Share Report button]         → share plain-text patient summary via Intent.ACTION_SEND
  ├── [Upload to Drive button]      → HIDDEN (visibility=gone) — Drive upload stubbed, fails with storageQuotaExceeded
  └── [Continue Recording button]  → PlacementFragment — only shown when < 16 recordings done
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
| `action_saved_to_session` | savedPatientsFragment → patientSessionFragment |
| `action_session_to_placement` | patientSessionFragment → placementFragment |
| `action_session_to_player` | patientSessionFragment → lungsPlayerFragment |

### Nav Args

| Fragment | Args |
|---|---|
| placementFragment | `patientId: long`, `patientSeqNum: int` |
| lungsRecordingFragment | `patientId: long`, `patientSeqNum: int`, `pointCode: string` |
| lungsPlayerFragment | `filePath: string`, `rawFilePath: string`, `patientId: long`, `patientSeqNum: int`, `pointCode: string`, `isReviewMode: boolean (default=false)` |
| patientSessionFragment | `patientId: long`, `patientSeqNum: int` |

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

### LungRecordingEntity
```kotlin
@Entity(
    tableName = "lung_recordings",
    foreignKeys = [ForeignKey(
        entity = LungPatientEntity::class,
        parentColumns = ["id"], childColumns = ["patientId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("patientId")]
)
data class LungRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val pointCode: String,            // e.g. "aar", "pslr"
    val filePath: String,             // absolute path to saved WAV
    val durationSeconds: Int,
    val createdAt: Long = System.currentTimeMillis()
)
```

### DAOs

**LungPatientDao**: `insert`, `getAllPatients(): Flow`, `getById(id)`, `getCount(): Int`, `deleteById(id)`

**LungRecordingDao**: `insert`, `getRecordingsForPatient(patientId): Flow`, `getRecordingCountForPatient(patientId): Int`, `getRecordingForPoint(patientId, pointCode)`, `deleteById(id)`

### LungsDatabase
Room singleton via `getInstance(context)` (double-checked locking). DB file: `lungs_database`, version 1, exportSchema=false.

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

### All 16 LungPoints (current coordinates — updated by calibration)

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
e.g. `point_aal.png`, `point_pslr.png` — placed in `lungs-app/src/main/res/drawable/`  
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
- **Patient ID**: read-only, pre-populated with `getCount() + 1`
- On "Next": validates → inserts `LungPatientEntity` → navigate with patientId + patientSeqNum

### PlacementFragment + PlacementViewModel

#### Region navigation tabs (TabLayout)
- 4 fixed-width tabs: **Ant. R | Ant. L | Post. R | Post. L**
- Tapping any tab jumps directly to that region via `viewModel.setRegion(index)`
- `isSyncingTab` flag prevents observer → tab → observer loop
- Tab sits between the top bar and the region title text

#### Anatomy overlay
- Region image: loaded via `Resources.getIdentifier(region.drawableResName, "drawable", packageName)`
- Buttons added programmatically in `anatomyContainer` (FrameLayout) inside a `post { }` callback
- **Double-render fix**: `pendingOverlayRunnable` is stored; `removeCallbacks()` cancels any queued post before scheduling a new one. `removeViews` is also called inside the post lambda itself.
- Button text: `point.label.take(4)` when pending; `"✓"` when done
- Done buttons: alpha 0.8, not interactive

#### Drag calibration (CURRENTLY DISABLED for testing)
- `ACTION_MOVE` block is commented out
- `hasDragged` toast in `ACTION_UP` is commented out
- To re-enable: uncomment both blocks in `addPointButton()` (clearly marked)
- **Tap to record: ACTIVE** — `ACTION_UP` with `!hasDragged && !isDone` navigates to recording screen

#### PlacementViewModel
- `init(patientId, repo)` — idempotent, guards with `if (this.patientId == patientId) return`
- `_recordings: Map<String, Boolean>` (pointCode → isDone) — updated by Room Flow
- `autoAdvanceRegionIfNeeded()` — auto-advances to next incomplete region when current region is all done
- `setRegion(index)` — called by tab listener and "Next Region" button

### LungsRecordingFragment + LungsRecordingViewModel
- Receives: `patientId`, `patientSeqNum`, `pointCode`
- **Placement guide image**: loads `point_{pointCode}` drawable; visible if exists, GONE if not
- **Filter**: always `PreFilter.LUNGS` — hardcoded, no filter chips
- **Pre-amp**: 0–30 dB slider, reset to 5 dB on `onResume()`
- **Two temp files**: `recording_{ts}_raw.wav` + `recording_{ts}_filtered.wav` in `filesDir`
- **AudioTrack race condition fix**: `@Volatile private var audioTrack` + `try-catch(IllegalStateException)` around `track.write()` in `onProgressUpdate`
- On stop → navigate to `lungsPlayerFragment` with all 5 args

#### AudioTrack (monitor playback during recording)
```kotlin
audioTrack = AudioTrack.Builder()
    .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(USAGE_MEDIA).setContentType(CONTENT_TYPE_MUSIC).build())
    .setAudioFormat(AudioFormat.Builder()
        .setSampleRate(44100).setEncoding(ENCODING_PCM_16BIT)
        .setChannelMask(CHANNEL_OUT_MONO).build())
    .setBufferSizeInBytes(minBuf * 2)
    .setTransferMode(AudioTrack.MODE_STREAM)
    .build().apply { play() }
```

### LungsPlayerFragment
- Receives: `filePath`, `rawFilePath`, `patientId`, `patientSeqNum`, `pointCode`, `isReviewMode`
- **Dynamic title**:
  - `isReviewMode=true` + valid pointCode → `"Review: {label}"` e.g. `"Review: Apex Right"`
  - `isReviewMode=false` + valid pointCode → `"{label}"` e.g. `"Apex Right"`
  - No pointCode → `getString(R.string.player_title)` = `"Review Recording"` fallback
- **Review mode** (`isReviewMode=true`): Save/Discard bar hidden; play button re-anchored to screen bottom via `ConstraintSet`
- **Double-filter protection**: if filename contains `_filtered`, `setPreFilter` is skipped
- **Save flow**: renames filtered WAV to `lungs/{seqStr}/{seqStr}_{pointCode}.wav`, deletes raw, inserts Room record, `popBackStack(R.id.placementFragment, false)`
- **Discard**: deletes both temp files, `navigateUp()`

### PatientSessionFragment (+ SessionAdapter)
- Loaded from `SavedPatientsFragment` when a patient card is tapped
- **Patient info card**: Sex / Age / BMI / Chest / Height / Weight + date
- **RecyclerView**: 20 items = 4 region `Header` rows + 16 `PointRow` rows
  - `SessionListItem.Header(region, doneCount)` → `item_session_header.xml`
  - `SessionListItem.PointRow(point, recording?)` → `item_recording.xml`
- **Recorded row**: green status dot + filename + duration + Play / Share / Delete buttons
- **Unrecorded row**: grey dot + "Not recorded" label, no action buttons
- **Play** → `action_session_to_player` with `isReviewMode=true`
- **Share** → `FileProvider.getUriForFile()` + `Intent.ACTION_SEND` type `audio/wav`
- **Delete** → `MaterialAlertDialogBuilder` → `deleteById(id)` + `File.delete()` on IO thread
- **Share Report** → builds plain-text report with patient info + all 16 statuses → `Intent.ACTION_SEND` type `text/plain`
- **Upload to Drive** → button is `visibility=gone` (see Drive section below)
- **Continue Recording** → visible only when < 16 recordings; navigates to `placementFragment`
- **Recording count**: `tvRecordingCount` shows "X / 16 points recorded" or "All 16 points recorded"

### SavedPatientsFragment
- Lists all patients from Room as `item_patient.xml` cards
- Tap → `action_saved_to_session` → `PatientSessionFragment`

---

## 11. Google Drive Upload (STUBBED — Not Active)

`DriveUploadHelper.kt` in `drive/` package — fully implemented but **button is hidden** (`android:visibility="gone"` on `btnUploadDrive` in `fragment_patient_session.xml`).

**Why hidden**: Service accounts don't have Google Drive storage quota. Uploading files as a service account hits `storageQuotaExceeded` (403). The correct fix is to switch to OAuth / Google Sign-In so files are owned by a real Google account.

**When to fix**: Switch `DriveUploadHelper` to use `GoogleSignIn` + OAuth instead of `ServiceAccountCredentials`. One-time sign-in with the target Gmail, then all uploads are silent. Set button visibility back to `visible` once implemented.

**Current DriveUploadHelper behaviour** (if re-enabled):
- Reads `assets/service_account.json`
- Creates `TaalLungs Auscultation/Patient_01_YYYY-MM-DD/` folder in service account Drive
- Uploads `report.txt` + all recorded WAVs using `ByteArrayContent` (multipart, not resumable)
- `SHARE_WITH_EMAIL = "cloudbotz2024@gmail.com"` — auto-shares root folder on first creation
- Returns webViewLink of session folder on success

---

## 12. File Naming and Storage Convention

```
{context.filesDir}/
├── recording_{timestamp}_raw.wav         ← TEMP: raw capture
├── recording_{timestamp}_filtered.wav    ← TEMP: filtered capture
└── lungs/
    └── 01/                               ← patient sequenceNumber as %02d
        ├── 01_aar.wav                    ← {seqStr}_{pointCode}.wav
        ├── 01_aslr.wav
        ├── ...
        └── 01_pill.wav
```

- Temp files in `filesDir` root with timestamp
- On Save: filtered temp renamed to `lungs/{seqStr}/{seqStr}_{pointCode}.wav`
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

Used in `PatientSessionFragment.shareRecording()` to produce a URI for `Intent.ACTION_SEND`.

---

## 14. Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
```

- `RECORD_AUDIO`: Requested at runtime in `LungsRecordingFragment`
- `INTERNET`: Added for Drive upload (kept even while Drive is stubbed)
- USB host: Required for TAAL stethoscope

---

## 15. Key Patterns and Conventions

1. **ViewBinding**: `_binding`/`binding` pair; `_binding = null` in `onDestroyView()`
2. **Fragment lifecycle safety**: `isAdded && _binding != null` before UI updates from callbacks
3. **UI thread dispatch**: Recorder/player callbacks are on background threads → `activity?.runOnUiThread { }`
4. **Room on IO thread**: All DB operations in `Dispatchers.IO` coroutines
5. **Pre-amp reset**: Always reset to 5 dB in `onResume()`
6. **TaalRecorder error handling**: `start()` throws `TaalDisconnectedException` if no USB device
7. **PlacementViewModel idempotent init**: `if (this.patientId == patientId) return`
8. **AudioTrack cleanup**: `stop()` + `release()` in `stopAudioMonitor()`, set to null
9. **Player cleanup**: null callbacks → `stop()` → `release()` in `onDestroyView()`
10. **Overlay rebuild**: `pendingOverlayRunnable` + `removeCallbacks()` prevents double-render when both LiveData observers fire in sequence; `removeViews` also inside the post lambda

---

## 16. Colors and Theme

```xml
teal_primary:    #2ABFBF
teal_dark:       #1A9999
teal_device:     #008DB9   (USB icon when connected)
recording_red:   #E85555
success_green:   #4CAF50   (done point buttons, recorded status dot)
waveform_blue:   #2D7DD2
divider:         (light grey — used for unrecorded status dot)

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

### Button position calibration (in progress)
- Drag-to-calibrate is **temporarily disabled** in `PlacementFragment.addPointButton()`
- To re-enable: uncomment `ACTION_MOVE` block + `hasDragged` branch in `ACTION_UP`
- Once positions are finalised, update `xFraction`/`yFraction` values in `LungPoints.kt`

### Point placement images needed
- Anterior Left: **done** (`point_aal`, `point_asll`, `point_amll`, `point_aill`)
- Anterior Right: **missing** (`point_aar`, `point_aslr`, `point_amlr`, `point_ailr`)
- Posterior Right: **missing** (`point_par`, `point_pslr`, `point_pmlr`, `point_pilr`)
- Posterior Left: **missing** (`point_pal`, `point_psll`, `point_pmll`, `point_pill`)

### Region overview images needed
- `placeholder_anterior_right.png`, `placeholder_anterior_left.png`
- `placeholder_posterior_right.png`, `placeholder_posterior_left.png`
- Place in `lungs-app/src/main/res/drawable/`

### Google Drive upload (needs OAuth fix)
- Current impl uses service account → fails with `storageQuotaExceeded`
- Fix: switch to Google Sign-In OAuth so files are owned by `cloudbotz2024@gmail.com`
- Button is `visibility=gone` until fixed
- `DriveUploadHelper.SHARE_WITH_EMAIL = "cloudbotz2024@gmail.com"` already set

### Future features
- [ ] Re-record a point (overwrite existing recording)
- [ ] Session completion summary screen after all 16 points recorded
- [ ] ZIP export of all 16 WAVs + patient metadata

---

## 19. Known Limitations

- `SavedPatientsFragment` adapter uses a `CoroutineScope` per ViewHolder for async count loading — not cancelled on recycle (acceptable for read-only use)
- Drive upload stubbed — button hidden, requires OAuth rewrite
- Waveform rendering in `LungsPlayerFragment` reads full WAV into memory on IO thread — fine for recordings up to a few minutes
