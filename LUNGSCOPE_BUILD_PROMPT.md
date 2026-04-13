# LungScope — Complete Build Prompt

**App Name:** LungScope  
**Package:** `com.musediagnostics.lungscope`  
**Minimum SDK:** 24 | **Target SDK:** 34  
**Language:** Kotlin + XML | **Architecture:** MVVM + Jetpack Navigation + Room

---

## Pre-Flight Checklist (Do This Before Pasting the Prompt)

> Items marked **[YOU]** must be done manually. Items marked **[CLAUDE]** are handled by the execution prompt.

### [YOU] Step 1 — Build the AARs from TaalDemoApp
Open a terminal in the `TaalDemoApp` project and run:
```bash
./gradlew :taal-core:assembleRelease
./gradlew :taal-ui-kit:assembleRelease
```
Outputs will be at:
- `taal-core/build/outputs/aar/taal-core-release.aar`
- `taal-ui-kit/build/outputs/aar/taal-ui-kit-release.aar`

### [YOU] Step 2 — Create a New Android Studio Project
- **Template:** Empty Views Activity
- **Name:** `LungScope`
- **Package:** `com.musediagnostics.lungscope`
- **Language:** Kotlin
- **Min SDK:** API 24
- **Build System:** Gradle (Kotlin DSL)

### [YOU] Step 3 — Copy AARs into the New Project
Inside the new project, create the folder `app/libs/` if it doesn't exist.  
Copy both AARs there:
```
app/
└── libs/
    ├── taal-core-release.aar
    └── taal-ui-kit-release.aar
```

### [YOU] Step 4 — Prepare Anatomy Images
You need 2 placeholder PNG/SVG images for the placement screen overlays:
- `ic_anatomy_front.png` — front view of human chest/torso outline
- `ic_anatomy_back.png`  — back view of human torso outline

Place them in `app/src/main/res/drawable/`.  
> If you don't have final assets yet, use any placeholder image — the button positions can be calibrated later.

### [YOU] Step 5 — Find Your TAAL Device USB Vendor ID
Open `TaalDemoApp` and check:
`taal-core/src/main/java/com/musediagnostics/taal/utils/SurrUtils.kt`  
Look for the USB vendor ID (e.g., `0x0D8C` or similar).  
You'll need this for the USB device filter XML in the new project's manifest.

---

## Execution Prompt

> Copy everything from the triple-backtick block below and paste it into Claude Code (or any AI coding assistant) inside your new `LungScope` Android Studio project.

```
Act as a Senior Android Developer. Build a complete standalone Android app called "LungScope"
for clinical lung auscultation studies using the TAAL digital stethoscope.

=== PROJECT IDENTITY ===

App Name:    LungScope
Package:     com.musediagnostics.lungscope
Min SDK:     24
Target SDK:  34
Language:    Kotlin + XML layouts
ViewBinding: enabled
Build:       Gradle Kotlin DSL

=== TAAL CORE AAR — APIs to Use ===

The file app/libs/taal-core-release.aar is already present. Reference these APIs directly:

TaalRecorder:
  fun start(rawFilePath: String, filteredFilePath: String)   // throws TaalDisconnectedException
  fun stop()
  var onAudioData: ((FloatArray) -> Unit)?                   // real-time PCM callback for waveform
  var onProgressUpdate: ((Long) -> Unit)?                    // timer in milliseconds
  fun setPreAmpDb(db: Int)                                   // 0–30 dB
  fun setPreFilter(filter: PreFilter)                        // always use PreFilter.LUNGS (LOCKED)

TaalPlayer:
  fun loadFile(path: String)
  fun play()
  fun pause()
  fun stop()
  fun release()
  var onPlaybackProgress: ((Long) -> Unit)?                  // current position in ms
  var onPlaybackComplete: (() -> Unit)?

Exceptions:
  TaalDisconnectedException — thrown by TaalRecorder.start() if no USB audio device connected

PreFilter enum: HEART, LUNGS, BOWEL, PREGNANCY, FULL_BODY
  → Always and only use PreFilter.LUNGS. Never show a filter selector in the UI.

TaalPlayerActivity (from taal-ui-kit-release.aar):
  TaalPlayerActivity.getIntent(context, filePath: String) — use for read-only playback in library

=== LIBRARIES TO ADD ===

In app/build.gradle.kts:

android {
    compileSdk = 34
    defaultConfig {
        applicationId = "com.musediagnostics.lungscope"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
}

In settings.gradle.kts, add jitpack repo:
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

In build.gradle.kts (root), add KSP plugin:
plugins {
    id("com.google.devtools.ksp") version "1.9.0-1.0.13" apply false
}

In app/build.gradle.kts plugins block add:
    id("com.google.devtools.ksp")

=== ANDROIDMANIFEST.XML ===

<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />

    <uses-feature android:name="android.hardware.usb.host" android:required="false" />

    <application
        android:name=".LungScopeApp"
        android:allowBackup="true"
        android:label="LungScope"
        android:theme="@style/Theme.LungScope"
        android:supportsRtl="true">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ui.recording.LungRecordingActivity"
            android:exported="false"
            android:launchMode="singleTop"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/usb_device_filter" />
        </activity>

    </application>
</manifest>

Create res/xml/usb_device_filter.xml:
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device vendor-id="3468" />
</resources>
(Replace vendor-id "3468" with the actual TAAL device USB vendor ID in decimal — check the
TaalDemoApp's SurrUtils.kt for the correct value.)

=== APP THEME ===

Use Material3 theme. Primary color: #008DB9 (TAAL teal).
Create a simple Theme.LungScope in res/values/themes.xml extending Theme.Material3.DayNight.NoActionBar.
Define:
  colorPrimary = #008DB9
  colorPointUnrecorded = #4D008DB9  (semi-transparent teal, for overlay buttons not yet recorded)
  colorPointRecorded   = #CC008DB9  (solid teal, for overlay buttons already recorded)

=== PROJECT STRUCTURE TO CREATE ===

com.musediagnostics.lungscope/
├── LungScopeApp.kt                          ← Application class (Room singleton init)
├── data/
│   └── db/
│       ├── LungDatabase.kt                  ← Room DB, version 1
│       ├── entity/
│       │   ├── Patient.kt
│       │   └── Recording.kt
│       └── dao/
│           ├── PatientDao.kt
│           └── RecordingDao.kt
├── data/repository/
│   ├── PatientRepository.kt
│   └── RecordingRepository.kt
├── model/
│   └── AuscultationPoint.kt                 ← enum of 16 points
└── ui/
    ├── MainActivity.kt
    ├── home/
    │   └── HomeFragment.kt
    ├── patient/
    │   ├── AddPatientFragment.kt
    │   └── AddPatientViewModel.kt
    ├── placement/
    │   ├── PlacementFragment.kt
    │   └── PlacementViewModel.kt
    ├── recording/
    │   ├── LungRecordingActivity.kt
    │   ├── LungRecordingFragment.kt
    │   ├── LungRecordingViewModel.kt
    │   ├── LungPlayerFragment.kt
    │   └── LungPlayerViewModel.kt
    └── library/
        ├── SavedPatientsFragment.kt
        ├── PatientRecordingsFragment.kt
        ├── PatientAdapter.kt
        └── RecordingAdapter.kt

res/layout/
├── activity_main.xml
├── activity_lung_recording.xml
├── fragment_home.xml
├── fragment_add_patient.xml
├── fragment_placement.xml
├── layout_placement_front.xml               ← front anatomy + 8 overlay buttons
├── layout_placement_back.xml                ← back anatomy + 8 overlay buttons
├── fragment_lung_recording.xml
├── fragment_lung_player.xml
├── fragment_saved_patients.xml
├── fragment_patient_recordings.xml
├── item_patient.xml
└── item_recording.xml

res/navigation/
├── nav_main.xml                             ← MainActivity's NavHost graph
└── nav_lung_recording.xml                  ← LungRecordingActivity's internal graph

=== DATA LAYER ===

--- AuscultationPoint.kt ---

enum class AuscultationPoint(
    val code: String,
    val displayName: String,
    val isFront: Boolean,
    val hBias: Float,
    val vBias: Float
) {
    AAR ("aar",  "Anterior Apex Right",            true,  0.62f, 0.15f),
    ASLR("aslr", "Anterior Superior Lobe Right",   true,  0.65f, 0.30f),
    AMLR("amlr", "Anterior Middle Lobe Right",     true,  0.67f, 0.48f),
    AILR("ailr", "Anterior Inferior Lobe Right",   true,  0.64f, 0.65f),
    AAL ("aal",  "Anterior Apex Left",             true,  0.38f, 0.15f),
    ASLL("asll", "Anterior Superior Lobe Left",    true,  0.35f, 0.30f),
    AMLL("amll", "Anterior Middle Lobe Left",      true,  0.33f, 0.48f),
    AILL("aill", "Anterior Inferior Lobe Left",    true,  0.36f, 0.65f),
    PAR ("par",  "Posterior Apex Right",           false, 0.62f, 0.14f),
    PSLR("pslr", "Posterior Superior Lobe Right",  false, 0.64f, 0.30f),
    PMLR("pmlr", "Posterior Middle Lobe Right",    false, 0.64f, 0.50f),
    PILR("pilr", "Posterior Inferior Lobe Right",  false, 0.62f, 0.68f),
    PAL ("pal",  "Posterior Apex Left",            false, 0.38f, 0.14f),
    PSLL("psll", "Posterior Superior Lobe Left",   false, 0.36f, 0.30f),
    PMLL("pmll", "Posterior Middle Lobe Left",     false, 0.36f, 0.50f),
    PILL("pill", "Posterior Inferior Lobe Left",   false, 0.38f, 0.68f);

    companion object {
        fun fromCode(code: String) = values().first { it.code == code }
        fun frontPoints() = values().filter { it.isFront }
        fun backPoints() = values().filter { !it.isFront }
    }
}

--- Patient.kt (Room Entity) ---

@Entity(tableName = "patients")
data class Patient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientCode: String,           // "01", "02", ...
    val sex: String,                   // "Male" | "Female" | "Other"
    val age: Int,
    val chestCircumferenceCm: Float,
    val heightCm: Float,
    val weightKg: Float,
    val bmi: Float,                    // auto-calculated, stored for display
    val folderPath: String,            // absolute path: filesDir/patients/{patientCode}
    val createdAt: Long = System.currentTimeMillis()
)

--- Recording.kt (Room Entity) ---

@Entity(
    tableName = "recordings",
    foreignKeys = [ForeignKey(
        entity = Patient::class,
        parentColumns = ["id"],
        childColumns = ["patientId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("patientId")]
)
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val patientCode: String,           // denormalized for fast display
    val auscultationPoint: String,     // "aar", "aslr", etc.
    val filteredFilePath: String,
    val rawFilePath: String,
    val durationSeconds: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

--- PatientDao.kt ---

@Dao interface PatientDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(p: Patient): Long
    @Update suspend fun update(p: Patient)
    @Delete suspend fun delete(p: Patient)
    @Query("SELECT * FROM patients ORDER BY createdAt DESC") fun getAll(): Flow<List<Patient>>
    @Query("SELECT * FROM patients WHERE id = :id") suspend fun getById(id: Long): Patient?
    @Query("SELECT COUNT(*) FROM patients") suspend fun getCount(): Int
}

--- RecordingDao.kt ---

@Dao interface RecordingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(r: Recording): Long
    @Delete suspend fun delete(r: Recording)
    @Query("SELECT * FROM recordings WHERE patientId = :pid") fun getForPatient(pid: Long): Flow<List<Recording>>
    @Query("SELECT * FROM recordings WHERE patientId = :pid AND auscultationPoint = :point LIMIT 1")
    suspend fun getForPoint(pid: Long, point: String): Recording?
    @Query("SELECT COUNT(*) FROM recordings WHERE patientId = :pid") suspend fun countForPatient(pid: Long): Int
}

--- LungDatabase.kt ---

@Database(entities = [Patient::class, Recording::class], version = 1, exportSchema = false)
abstract class LungDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun recordingDao(): RecordingDao
    companion object {
        @Volatile private var INSTANCE: LungDatabase? = null
        fun getInstance(context: Context): LungDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, LungDatabase::class.java, "lung_db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}

--- PatientRepository.kt ---

class PatientRepository(private val dao: PatientDao) {
    fun getAll() = dao.getAll()
    suspend fun getById(id: Long) = dao.getById(id)
    suspend fun getCount() = dao.getCount()
    suspend fun insert(patient: Patient) = dao.insert(patient)
    suspend fun delete(patient: Patient) = dao.delete(patient)
}

--- RecordingRepository.kt ---

class RecordingRepository(private val dao: RecordingDao) {
    fun getForPatient(patientId: Long) = dao.getForPatient(patientId)
    suspend fun getForPoint(patientId: Long, point: String) = dao.getForPoint(patientId, point)
    suspend fun countForPatient(patientId: Long) = dao.countForPatient(patientId)
    suspend fun insert(recording: Recording) = dao.insert(recording)
    suspend fun delete(recording: Recording) = dao.delete(recording)
}

=== NAVIGATION GRAPHS ===

--- nav_main.xml ---

<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_main"
    app:startDestination="@id/homeFragment">

    <fragment android:id="@+id/homeFragment"
        android:name="com.musediagnostics.lungscope.ui.home.HomeFragment"
        android:label="Home">
        <action android:id="@+id/action_home_to_addPatient"
            app:destination="@id/addPatientFragment" />
        <action android:id="@+id/action_home_to_savedPatients"
            app:destination="@id/savedPatientsFragment" />
    </fragment>

    <fragment android:id="@+id/addPatientFragment"
        android:name="com.musediagnostics.lungscope.ui.patient.AddPatientFragment"
        android:label="New Patient">
        <action android:id="@+id/action_addPatient_to_placement"
            app:destination="@id/placementFragment" />
        <argument android:name="patientCode" app:argType="string" android:defaultValue="" />
        <argument android:name="patientId"   app:argType="long"   android:defaultValue="-1" />
    </fragment>

    <fragment android:id="@+id/placementFragment"
        android:name="com.musediagnostics.lungscope.ui.placement.PlacementFragment"
        android:label="Placement">
        <argument android:name="patientCode" app:argType="string" />
        <argument android:name="patientId"   app:argType="long" />
    </fragment>

    <fragment android:id="@+id/savedPatientsFragment"
        android:name="com.musediagnostics.lungscope.ui.library.SavedPatientsFragment"
        android:label="Saved Patients">
        <action android:id="@+id/action_savedPatients_to_recordings"
            app:destination="@id/patientRecordingsFragment" />
    </fragment>

    <fragment android:id="@+id/patientRecordingsFragment"
        android:name="com.musediagnostics.lungscope.ui.library.PatientRecordingsFragment"
        android:label="Recordings">
        <argument android:name="patientId"   app:argType="long" />
        <argument android:name="patientCode" app:argType="string" />
    </fragment>

</navigation>

--- nav_lung_recording.xml (used by LungRecordingActivity) ---

<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_lung_recording"
    app:startDestination="@id/lungRecordingFragment">

    <fragment android:id="@+id/lungRecordingFragment"
        android:name="com.musediagnostics.lungscope.ui.recording.LungRecordingFragment"
        android:label="Recording">
        <action android:id="@+id/action_recording_to_player"
            app:destination="@id/lungPlayerFragment" />
    </fragment>

    <fragment android:id="@+id/lungPlayerFragment"
        android:name="com.musediagnostics.lungscope.ui.recording.LungPlayerFragment"
        android:label="Player">
        <argument android:name="filteredFilePath" app:argType="string" />
        <argument android:name="rawFilePath"      app:argType="string" />
        <argument android:name="durationSeconds"  app:argType="integer" android:defaultValue="0" />
    </fragment>

</navigation>

=== SCREEN IMPLEMENTATIONS ===

--- HomeFragment ---

Layout: Two MaterialCardView cards stacked vertically, each with an icon and title.
  Card 1: ic_person_add icon, "Add New Patient", subtitle "Create patient profile + record 16 points"
    → onClick: findNavController().navigate(R.id.action_home_to_addPatient)
  Card 2: ic_folder_open icon, "View Recordings", subtitle "Browse saved patient folders"
    → onClick: findNavController().navigate(R.id.action_home_to_savedPatients)
Toolbar with title "LungScope", no back button, no menu.

--- AddPatientFragment ---

ViewModel: AddPatientViewModel
  - On init: launch coroutine to fetch nextPatientCode = String.format("%02d", repo.getCount() + 1)
  - Expose: patientCodePreview: LiveData<String>, uiState: LiveData<UiState>
  - var isChestInInches = false
  - fun toggleChestUnit(currentValue: String): String
      converts between cm and in (multiply or divide by 2.54), updates display only
      always stores result in cm internally
  - fun calculateBmi(weightStr: String, heightStr: String): String
      returns String.format("%.1f", weightKg / (heightM * heightM)) or "" if invalid
  - fun savePatient(context, sex, age, chestStr, heightStr, weightStr):
      1. val patientCode = nextPatientCode value
      2. val chestCm = if (isChestInInches) value * 2.54f else value
      3. val bmi = weightKg / (heightM * heightM)
      4. val folderPath = context.filesDir.absolutePath + "/patients/" + patientCode
      5. File(folderPath).mkdirs()
      6. insert Patient entity → returns id
      7. emit Success(patientCode, id)

Layout (fragment_add_patient.xml):
  - Toolbar with back button, title "New Patient"
  - Non-editable chip/banner: "Patient ID: 01" (auto-assigned)
  - Sex: TextInputLayout + MaterialAutoCompleteTextView: ["Male", "Female", "Other"]
  - Age: TextInputLayout + EditText (inputType="number")
  - Chest Circumference row:
      TextInputLayout + EditText (inputType="numberDecimal") + suffix = "cm"
      ImageButton beside it showing "in" or "cm" toggle
      On toggle click: convert displayed value, flip unit label
  - Height: TextInputLayout + EditText (numberDecimal) + suffix "cm"
  - Weight: TextInputLayout + EditText (numberDecimal) + suffix "kg"
  - BMI: TextInputLayout + EditText (numberDecimal) + android:enabled="false" + suffix "kg/m²"
      Add TextWatcher to height and weight fields → recalculate BMI on every change
  - Buttons row: "Cancel" (outlined) + "Save Patient" (filled)

On "Save Patient":
  Validate all fields → show TextInputLayout errors if invalid
  On success → navigate to PlacementFragment(patientCode, patientId)

--- PlacementFragment ---

Nav args: patientCode: String, patientId: Long
ViewModel: PlacementViewModel
  - loadRecordings(patientId): Flow<List<Recording>> → map to Map<String, Boolean>
  - fun saveRecording(patientId, patientCode, pointCode, filePath, durationSecs):
      insert Recording entity into DB

Layout (fragment_placement.xml):
  - Toolbar: "Patient {patientCode}" + back button
  - Subtitle: "Tap a point to record"
  - MaterialButtonToggleGroup: buttons [Front] [Back], single selection
  - FrameLayout containing:
      include layout="@layout/layout_placement_front" (id: frontLayout)
      include layout="@layout/layout_placement_back"  (id: backLayout)
      Only one visible at a time based on toggle selection (default: Front)
  - "Done" button at bottom → findNavController().navigate to savedPatientsFragment or navigateUp()

For layout_placement_front.xml and layout_placement_back.xml:
  Each is a ConstraintLayout with:
  - ImageView id="ivAnatomy": fills parent, scaleType="fitCenter", aspectRatio "H,3:4"
    front: app:srcCompat="@drawable/ic_anatomy_front"
    back:  app:srcCompat="@drawable/ic_anatomy_back"
  - 8 MaterialButton circle buttons per layout, each 44dp×44dp, cornerRadius=22dp, text=""
    positioned via constraintHorizontal_bias + constraintVertical_bias on the ivAnatomy bounds.

FRONT layout buttons with their IDs, codes, and bias values:
  id=btnAar  code=aar  hBias=0.62 vBias=0.15
  id=btnAslr code=aslr hBias=0.65 vBias=0.30
  id=btnAmlr code=amlr hBias=0.67 vBias=0.48
  id=btnAilr code=ailr hBias=0.64 vBias=0.65
  id=btnAal  code=aal  hBias=0.38 vBias=0.15
  id=btnAsll code=asll hBias=0.35 vBias=0.30
  id=btnAmll code=amll hBias=0.33 vBias=0.48
  id=btnAill code=aill hBias=0.36 vBias=0.65

BACK layout buttons:
  id=btnPar  code=par  hBias=0.62 vBias=0.14
  id=btnPslr code=pslr hBias=0.64 vBias=0.30
  id=btnPmlr code=pmlr hBias=0.64 vBias=0.50
  id=btnPilr code=pilr hBias=0.62 vBias=0.68
  id=btnPal  code=pal  hBias=0.38 vBias=0.14
  id=btnPsll code=psll hBias=0.36 vBias=0.30
  id=btnPmll code=pmll hBias=0.36 vBias=0.50
  id=btnPill code=pill hBias=0.38 vBias=0.68

Button state tinting:
  Unrecorded: backgroundTint = #4D008DB9
  Recorded:   backgroundTint = #CC008DB9
  Observe PlacementViewModel.recordedPoints: StateFlow<Map<String, Boolean>> and update tints.

On each button click:
  val pointCode = button's associated code (bind via tag or companion map in Fragment)
  val existingRecording = viewModel.getExistingRecording(patientId, pointCode)
  if (existingRecording != null) {
      // Show AlertDialog: "Re-record this point? The previous recording will be deleted."
      // On confirm: delete old files, delete from DB, then launch activity
  }
  val intent = Intent(requireContext(), LungRecordingActivity::class.java).apply {
      putExtra(LungRecordingActivity.EXTRA_PATIENT_CODE, patientCode)
      putExtra(LungRecordingActivity.EXTRA_PATIENT_ID, patientId)
      putExtra(LungRecordingActivity.EXTRA_POINT_CODE, pointCode)
      putExtra(LungRecordingActivity.EXTRA_TARGET_FOLDER,
          requireContext().filesDir.absolutePath + "/patients/" + patientCode)
  }
  startActivityForResult(intent, REQUEST_RECORDING)

In onActivityResult(REQUEST_RECORDING, RESULT_OK):
  val filePath = data?.getStringExtra(LungRecordingActivity.RESULT_FILE_PATH) ?: return
  val duration = data?.getIntExtra(LungRecordingActivity.RESULT_DURATION_SECS, 0) ?: 0
  val pointCode = data?.getStringExtra(LungRecordingActivity.RESULT_POINT_CODE) ?: return
  viewModel.saveRecording(patientId, patientCode, pointCode, filePath, duration)

--- LungRecordingActivity ---

class LungRecordingActivity : AppCompatActivity() {
    lateinit var patientCode: String
    var patientId: Long = -1L
    lateinit var pointCode: String
    lateinit var targetFolderPath: String

    companion object {
        const val EXTRA_PATIENT_CODE   = "extra_patient_code"
        const val EXTRA_PATIENT_ID     = "extra_patient_id"
        const val EXTRA_POINT_CODE     = "extra_point_code"
        const val EXTRA_TARGET_FOLDER  = "extra_target_folder"
        const val RESULT_FILE_PATH     = "result_file_path"
        const val RESULT_DURATION_SECS = "result_duration_secs"
        const val RESULT_POINT_CODE    = "result_point_code"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lung_recording)
        patientCode      = intent.getStringExtra(EXTRA_PATIENT_CODE)!!
        patientId        = intent.getLongExtra(EXTRA_PATIENT_ID, -1L)
        pointCode        = intent.getStringExtra(EXTRA_POINT_CODE)!!
        targetFolderPath = intent.getStringExtra(EXTRA_TARGET_FOLDER)!!
        // NavHostFragment is set up in activity_lung_recording.xml with nav_lung_recording graph
    }

    fun finishWithResult(filteredPath: String, durationSecs: Int) {
        val data = Intent().apply {
            putExtra(RESULT_FILE_PATH, filteredPath)
            putExtra(RESULT_DURATION_SECS, durationSecs)
            putExtra(RESULT_POINT_CODE, pointCode)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    fun discardAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }
}

activity_lung_recording.xml: simple layout with a FragmentContainerView as NavHostFragment,
  navGraph="@navigation/nav_lung_recording", defaultNavHost="true", fills entire screen.

--- LungRecordingViewModel ---

class LungRecordingViewModel : ViewModel() {
    val taalRecorder = TaalRecorder()

    private val _uiState = MutableLiveData(RecordingUiState.IDLE)
    val uiState: LiveData<RecordingUiState> = _uiState

    private val _timerMs = MutableLiveData(0L)
    val timerMs: LiveData<Long> = _timerMs

    private val _waveformData = MutableLiveData<FloatArray>()
    val waveformData: LiveData<FloatArray> = _waveformData

    var rawFile: File? = null
    var filteredFile: File? = null
    var patientCode = ""
    var pointCode   = ""
    var targetFolder = ""

    enum class RecordingUiState { IDLE, RECORDING, STOPPED }

    fun startRecording() {
        val fileName = "${patientCode}_${pointCode}"
        val folder = File(targetFolder).also { it.mkdirs() }
        rawFile      = File(folder, "${fileName}_raw.wav")
        filteredFile = File(folder, "${fileName}_filtered.wav")
        taalRecorder.setPreFilter(PreFilter.LUNGS)
        taalRecorder.setPreAmpDb(5)
        taalRecorder.onAudioData     = { samples -> _waveformData.postValue(samples) }
        taalRecorder.onProgressUpdate = { ms -> _timerMs.postValue(ms) }
        taalRecorder.start(rawFile!!.absolutePath, filteredFile!!.absolutePath)
        _uiState.postValue(RecordingUiState.RECORDING)
    }

    fun stopRecording() {
        taalRecorder.stop()
        _uiState.postValue(RecordingUiState.STOPPED)
    }

    fun discardFiles() {
        rawFile?.delete()
        filteredFile?.delete()
        rawFile = null
        filteredFile = null
    }

    override fun onCleared() {
        super.onCleared()
        try { taalRecorder.stop() } catch (_: Exception) {}
    }
}

--- LungRecordingFragment (fragment_lung_recording.xml) ---

Layout:
  - Toolbar at top: back (chevron_left) icon + title "{pointCode.uppercase()} — {displayName}"
  - LineChart (MPAndroidChart): fills most of the screen (real-time waveform)
  - tvTimer: "00:00" centered below chart
  - Bottom section:
      row 1: tvPreAmpLabel "Pre-Amp: 5 dB" + Slider (id=sliderPreAmp, from=0 to=30 value=5)
      row 2: Large circular record button (id=btnRecord, visible in IDLE)
              Large circular stop button  (id=btnStop,   visible in RECORDING, initially GONE)
  - tvHint: "Press record to start" (IDLE) / "Recording..." (RECORDING)

In onViewCreated:
  val activity = requireActivity() as LungRecordingActivity
  viewModel.patientCode  = activity.patientCode
  viewModel.pointCode    = activity.pointCode
  viewModel.targetFolder = activity.targetFolderPath

  // Set title using AuscultationPoint.fromCode(pointCode)
  val point = AuscultationPoint.fromCode(activity.pointCode)
  binding.toolbar.title = "${point.code.uppercase()} — ${point.displayName}"

  binding.toolbar.setNavigationOnClickListener {
      if (viewModel.uiState.value == RecordingUiState.IDLE) {
          activity.discardAndFinish()
      }
  }

  binding.btnRecord.setOnClickListener {
      try {
          viewModel.startRecording()
      } catch (e: TaalDisconnectedException) {
          Snackbar.make(binding.root, "Connect your TAAL device first", Snackbar.LENGTH_LONG).show()
      }
  }

  binding.btnStop.setOnClickListener {
      viewModel.stopRecording()
      val dur = ((viewModel.timerMs.value ?: 0L) / 1000).toInt()
      findNavController().navigate(
          LungRecordingFragmentDirections.actionRecordingToPlayer(
              filteredFilePath = viewModel.filteredFile!!.absolutePath,
              rawFilePath      = viewModel.rawFile!!.absolutePath,
              durationSeconds  = dur
          )
      )
  }

  sliderPreAmp.addOnChangeListener { _, value, _ ->
      viewModel.taalRecorder.setPreAmpDb(value.toInt())
      binding.tvPreAmpLabel.text = "Pre-Amp: ${value.toInt()} dB"
  }

Waveform rendering:
  Implement a 10-second scrolling ECG-style waveform using MPAndroidChart LineChart.
  - DOWNSAMPLE_STEP = 44  (44100Hz / 44 ≈ 1002 points per second)
  - Keep a persistent LineDataSet — never replace chart.data, only add entries + notifyDataSetChanged
  - Track current page (10s = ~10020 pts). When page fills, start a new page.
  - Keep only current page + previous page in memory (remove older entries)
  - Call chart.moveViewToX(lastEntry.x) to scroll to latest data
  - X-axis: time in seconds (entryIndex / 1002.0f)
  - Y-axis: amplitude (±1.0 normalized)
  - No legend, no description, no grid lines, no dot circles
  - Line color: #008DB9, lineWidth: 1.5dp
  - Observe viewModel.waveformData, downsample by DOWNSAMPLE_STEP, add to dataset

Observe uiState:
  IDLE:       btnRecord visible, btnStop gone, tvHint = "Press record to start"
  RECORDING:  btnRecord gone, btnStop visible, tvHint = "Recording..."
  STOPPED:    both hidden (nav to player happens in btnStop click)

--- LungPlayerFragment (fragment_lung_player.xml) ---

Nav args: filteredFilePath, rawFilePath, durationSeconds
ViewModel: LungPlayerViewModel (TaalPlayer)

Layout:
  - Toolbar: title "{pointCode.uppercase()} — {displayName}", back button (calls discardAndFinish)
  - LineChart: shows static full waveform loaded from filteredFilePath
  - tvTimer: "0:00 / 0:{durationSeconds}"
  - Large play/pause button (btnPlayPause)
  - Bottom bar with two equal buttons:
      btnRecordAgain: "Record Again" (outlined style)
      btnSave:        "Save"         (filled style, teal)

In onViewCreated:
  Load waveform from filteredFilePath (read WAV PCM bytes, downsample at DOWNSAMPLE_STEP=44,
  add all entries to LineDataSet, call chart.notifyDataSetChanged + invalidate)

  val activity = requireActivity() as LungRecordingActivity
  val point = AuscultationPoint.fromCode(activity.pointCode)
  binding.toolbar.title = "${point.code.uppercase()} — ${point.displayName}"

  binding.toolbar.setNavigationOnClickListener {
      // same as Record Again (don't save, go back to recording)
      recordAgain()
  }

  binding.btnPlayPause.setOnClickListener { viewModel.togglePlayPause(navArgs.filteredFilePath) }

  binding.btnRecordAgain.setOnClickListener { recordAgain() }

  binding.btnSave.setOnClickListener {
      viewModel.taalPlayer.stop()
      viewModel.taalPlayer.release()
      activity.finishWithResult(navArgs.filteredFilePath, navArgs.durationSeconds)
  }

private fun recordAgain() {
    viewModel.taalPlayer.stop()
    viewModel.taalPlayer.release()
    viewModel.deleteFiles(navArgs.filteredFilePath, navArgs.rawFilePath)
    findNavController().popBackStack()
}

LungPlayerViewModel:
  val taalPlayer = TaalPlayer()
  private val _isPlaying = MutableLiveData(false)
  val isPlaying: LiveData<Boolean> = _isPlaying
  private val _progressMs = MutableLiveData(0L)
  val progressMs: LiveData<Long> = _progressMs

  fun togglePlayPause(path: String) {
      if (_isPlaying.value == true) {
          taalPlayer.pause(); _isPlaying.postValue(false)
      } else {
          if (_progressMs.value == 0L) taalPlayer.loadFile(path)
          taalPlayer.onPlaybackProgress = { ms -> _progressMs.postValue(ms) }
          taalPlayer.onPlaybackComplete = { _isPlaying.postValue(false); _progressMs.postValue(0L) }
          taalPlayer.play(); _isPlaying.postValue(true)
      }
  }

  fun deleteFiles(filteredPath: String, rawPath: String) {
      File(filteredPath).delete()
      File(rawPath).delete()
  }

  override fun onCleared() {
      super.onCleared()
      try { taalPlayer.stop(); taalPlayer.release() } catch (_: Exception) {}
  }

--- SavedPatientsFragment ---

RecyclerView, items from PatientRepository.getAll() (Flow observed via collectLatest).
Each item_patient.xml shows:
  - Large text: "Patient {patientCode}"
  - Row: sex, age, BMI
  - Row: "{recordingCount}/16 points recorded"
  - Teal progress bar (0–16)
  - Timestamp: "Added {date}"

On item click → findNavController().navigate to patientRecordingsFragment(patientId, patientCode)

Adapter: PatientAdapter (ListAdapter<Patient, PatientViewHolder>)

--- PatientRecordingsFragment ---

Nav args: patientId: Long, patientCode: String
RecyclerView from RecordingRepository.getForPatient(patientId).

Each item_recording.xml shows:
  - Point code uppercase + display name (e.g. "AAR — Anterior Apex Right")
  - Duration in seconds
  - Date recorded

On item click → startActivity(TaalPlayerActivity.getIntent(requireContext(), recording.filteredFilePath))

Future scope button (can add later, show as outlined button at bottom):
  "Upload to Google Drive" → TODO stub

=== TIMER FORMATTING UTILITY ===

Create a simple extension function:
fun Long.toTimerFormat(): String {
    val totalSecs = this / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format("%02d:%02d", mins, secs)
}

=== IMPLEMENTATION BUILD ORDER ===

Build strictly in this order so the app is always runnable at each step:

STEP 1: Project scaffolding
  - app/build.gradle.kts (full dependencies)
  - settings.gradle.kts (jitpack repo)
  - root build.gradle.kts (KSP plugin)
  - AndroidManifest.xml
  - res/xml/usb_device_filter.xml
  - LungScopeApp.kt (Application class, initialize DB)
  - res/values/themes.xml, colors.xml, strings.xml

STEP 2: Data layer
  - AuscultationPoint.kt
  - Patient.kt, Recording.kt
  - PatientDao.kt, RecordingDao.kt
  - LungDatabase.kt
  - PatientRepository.kt, RecordingRepository.kt

STEP 3: HomeFragment
  - MainActivity.kt + activity_main.xml (NavHostFragment)
  - fragment_home.xml + HomeFragment.kt
  - nav_main.xml (with all destinations, even if fragments are stubs)
  - Verify: app builds and shows Home screen

STEP 4: Patient creation
  - fragment_add_patient.xml + AddPatientFragment.kt + AddPatientViewModel.kt
  - Verify: can fill form, BMI auto-calculates, patient saved to DB

STEP 5: Placement screen
  - layout_placement_front.xml (all 8 buttons)
  - layout_placement_back.xml  (all 8 buttons)
  - fragment_placement.xml + PlacementFragment.kt + PlacementViewModel.kt
  - Verify: buttons show, toggle works, tint updates after recording

STEP 6: LungRecordingActivity + Recording screen
  - activity_lung_recording.xml
  - LungRecordingActivity.kt
  - nav_lung_recording.xml
  - LungRecordingViewModel.kt
  - fragment_lung_recording.xml + LungRecordingFragment.kt
  - Verify: activity launches, waveform shows, timer runs

STEP 7: Player screen
  - fragment_lung_player.xml + LungPlayerFragment.kt + LungPlayerViewModel.kt
  - Verify: waveform loads, play/pause works, Save finishes activity with result

STEP 8: Wire result back to Placement
  - Implement onActivityResult in PlacementFragment
  - Verify: button turns solid after save

STEP 9: Library screens
  - SavedPatientsFragment + PatientAdapter + item_patient.xml
  - PatientRecordingsFragment + RecordingAdapter + item_recording.xml
  - Verify: patients list, recordings list, tap plays file

=== CRITICAL CONSTRAINTS — DO NOT DEVIATE ===

1. PreFilter.LUNGS is ALWAYS locked. Zero UI for filter selection anywhere in this app.
2. No authentication, no login. HomeFragment is startDestination.
3. Post-recording choices are exactly two: "Record Again" and "Save". No "Discard" button.
4. File naming is ALWAYS automatic: {patientCode}_{pointCode}_filtered.wav / _raw.wav
   No name dialog shown to user at any point.
5. All recordings for a patient go into: filesDir/patients/{patientCode}/
6. "Record Again" in LungPlayerFragment MUST delete the temp files before popBackStack().
7. Button tint: #4D008DB9 (unrecorded) and #CC008DB9 (recorded). These exact colors.
8. The waveform LineDataSet must be PERSISTENT — never call chart.data = LineData(...) after init.
   Always append entries to the existing dataset and call notifyDataSetChanged() + invalidate().
```

---

## What YOU Need to Do After Claude Builds the Code

### Before Running the Execution Prompt

- [ ] **Build taal-core AAR** — Run `./gradlew :taal-core:assembleRelease` in TaalDemoApp
- [ ] **Build taal-ui-kit AAR** — Run `./gradlew :taal-ui-kit:assembleRelease` in TaalDemoApp
- [ ] **Create new Android Studio project** — Empty Views Activity, package `com.musediagnostics.lungscope`, Kotlin, Min SDK 24, Gradle Kotlin DSL
- [ ] **Copy AARs** — Place both `.aar` files into `app/libs/` of the new project
- [ ] **Get the USB Vendor ID** — Open `TaalDemoApp/taal-core/src/main/java/com/musediagnostics/taal/utils/SurrUtils.kt` and note the USB vendor ID integer. Convert to decimal and update `res/xml/usb_device_filter.xml` in the new project

### After Claude Builds the Code

- [ ] **Add anatomy images** — Place `ic_anatomy_front.png` and `ic_anatomy_back.png` into `app/src/main/res/drawable/`. These must be chest torso outline images (front and back view). Use any placeholder initially.
- [ ] **Calibrate button positions** — Run the app on a device, open the Placement screen, and visually adjust the `hBias`/`vBias` values inside `AuscultationPoint.kt` for each of the 16 buttons to match your actual anatomical images. No code logic changes needed — just float value tweaks.
- [ ] **Test on a physical device** — The TAAL device is USB audio. The waveform and recording won't work on an emulator. Test recording/playback only on a real Android device.
- [ ] **Verify file output** — After saving a recording, use Android Studio's Device File Explorer (`View → Tool Windows → Device File Explorer`) to confirm files exist at: `data/data/com.musediagnostics.lungscope/files/patients/01/01_aar_filtered.wav`
- [ ] **Sync recording count display** — In `SavedPatientsFragment`, the progress bar shows `recordingCount/16`. Make sure `RecordingRepository.countForPatient()` is wired to the adapter correctly.

### For the Future Google Drive Upload Feature

- [ ] Create a project in [Google Cloud Console](https://console.cloud.google.com)
- [ ] Enable the **Google Drive API**
- [ ] Enable **Google Sign-In**
- [ ] Download `google-services.json` and place it in `app/`
- [ ] Add `com.google.gms:google-services` plugin and `com.google.android.gms:play-services-auth` dependency
- [ ] Implement the "Upload to Drive" button in `PatientRecordingsFragment`

---

## Quick Reference

| Item | Value |
|------|-------|
| App Name | LungScope |
| Package | `com.musediagnostics.lungscope` |
| Min SDK | 24 |
| Target SDK | 34 |
| Entry Point | `HomeFragment` (no auth) |
| Filter | `PreFilter.LUNGS` (locked, no UI) |
| File Path | `filesDir/patients/{01}/{01_aar}_filtered.wav` |
| Point Count | 16 (8 anterior + 8 posterior) |
| Player Actions | "Record Again" + "Save" only (no Discard) |
| Primary Color | `#008DB9` |

| Point Side | Codes |
|------------|-------|
| Anterior Right | `aar` `aslr` `amlr` `ailr` |
| Anterior Left  | `aal` `asll` `amll` `aill` |
| Posterior Right | `par` `pslr` `pmlr` `pilr` |
| Posterior Left  | `pal` `psll` `pmll` `pill` |
