## TAAL Custom UI Build Guide

**Everything needed to build your own recording/playback screens against `taal-core` only — no `taal-ui-kit.aar` dependency, no forced navigation graph, drop straight into your own app flow.**

---

### 0. Why this document exists

Today we ship clients two AARs: `taal-core.aar` (audio engine) + `taal-ui-kit.aar` (our pre-built screens, with our own Activities and Navigation graph). Some clients don't want `taal-ui-kit` at all — they have their own app architecture, their own navigation, their own screens, and just want the **exact same recording/playback UI and behavior** wired into their own Fragments.

This document is the full recipe: every layout XML, every drawable, every line of Kotlin, copy-pasteable, so a client can rebuild the `taal-ui-kit` experience inside their own screens using only `taal-core.aar`.

Two integration points are called out everywhere in the code below, because they are the only places the original `taal-ui-kit` code assumes **our** Activity/Navigation structure:

- **① Initial values** — the original reads `preFilter`/`preAmplification` from `TaalRecorderActivity`'s intent extras. Replace with however your screen receives its initial config (fragment arguments, a ViewModel injected by your DI, a constructor param — your choice).
- **② Screen transitions** — the original calls `findNavController().navigate(...)`. Replace every one of these with your own navigation call (Jetpack Navigation with your own graph, plain FragmentTransaction, startActivity, whatever your app already uses). Each call site below is marked `// ② YOUR NAVIGATION HERE`.

Everything else — the `taal-core` wiring, the waveform math, the BPM calculation, the filter chip logic — has zero dependency on our Activities and can be copied verbatim.

---

### 1. Dependencies

```kotlin
// build.gradle.kts (your app module or a UI module of your own)
dependencies {
    implementation(project(":taal-core"))              // or files("libs/taal-core.aar")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")          // only if you build the saved-recordings list
    implementation("androidx.viewpager2:viewpager2:1.0.0")              // only if you build the placement-image dialog

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")         // waveform rendering
}
```

```kotlin
// settings.gradle.kts — required for MPAndroidChart
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

If you'd rather not pull in MPAndroidChart, every chart call below is isolated to `setupWaveformChart()` / `updateWaveform()` / `loadFullWaveform()` — swap those three functions for your own charting/canvas code and leave everything else untouched.

---

### 2. Assets to copy

#### 2.1 Filter icons (drawable, vector XML — copy as-is)

| Drawable | Used for |
|---|---|
| `ic_heart.xml` | Heart filter chip + saved-list icon |
| `ic_lungs.xml` | Lungs filter chip + saved-list icon |
| `ic_bowel.xml` | Bowel filter chip + saved-list icon |
| `ic_pregnancy.xml` | Pregnancy filter chip + saved-list icon |
| `ic_accessibility.xml` | Full Body filter chip + saved-list icon |
| `ic_custom_filter.xml` | Custom (user-defined Hz range) filter chip |

Selected/unselected tint is driven by a color-state-list, not per-icon assets — copy this file as `res/color/filter_icon_selector.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:color="#FF0000" android:state_selected="true" />
    <item android:color="#757575" />
</selector>
```

Apply it to every filter `ImageButton` with `app:tint="@color/filter_icon_selector"`, and toggle `button.isSelected = true/false` in code (see §3.2) — you get the red-when-selected / gray-when-not behavior for free, no drawable swapping needed.

#### 2.2 Action icons (all standard Material-style vector drawables — recreate or request from us)

`ic_recording_start1`, `ic_recording_stop`, `ic_play`, `ic_play_circle`, `ic_volume_up`, `ic_info`, `icon_taal` (TAAL device/USB icon), `ic_sync_arrows`, `ic_gallery`, `ic_settings`, `ic_trash`, `ic_check_save`, `ic_arrow_back`, `ic_close`, `ic_share`, `ic_save`, `dot_active` / `dot_inactive` (small circle shape drawables, used for the placement-image carousel page indicator).

#### 2.3 Placement diagrams — anatomical stethoscope-placement images

These are the images behind the "i" info button. **They are bundled PNGs, not code** — ask us for the raw image files if you need them; they cannot be copy-pasted as text. Naming convention (required — the dialog code in §4 resolves images by this exact pattern):

```
taal_placement_{filter_lowercase}_{index}.png
```

Currently shipped: `taal_placement_heart_1/2`, `taal_placement_lungs_2/3/4` (no `_1` — indices don't need to be contiguous), `taal_placement_bowel_1`, `taal_placement_pregnancy_1`, `taal_placement_full_body_1`, `taal_placement_custom_1`. Drop them straight into `res/drawable/` — the resolver in §4 scans indices 1–12 per filter and just skips any that don't exist.

#### 2.4 Colors, dimens, strings

```xml
<!-- res/values/colors.xml — only the ones referenced below -->
<color name="teal_primary">#2ABFBF</color>
<color name="red_primary">#E85555</color>
<color name="white">#FFFFFF</color>
<color name="text_primary">#333333</color>
<color name="text_secondary">#999999</color>
<color name="waveform_blue">#2D7DD2</color>
```

```xml
<!-- res/values/dimens.xml -->
<dimen name="spacing_sm">8dp</dimen>
<dimen name="spacing_md">16dp</dimen>
<dimen name="spacing_lg">24dp</dimen>
<dimen name="record_button_size">80dp</dimen>
<dimen name="bottom_bar_height">56dp</dimen>
<dimen name="text_bpm">14sp</dimen>
```

```xml
<!-- res/values/strings.xml -->
<string name="start_recording">Start Recording</string>
<string name="stop_recording">Stop Recording</string>
<string name="play_recording">Play Recording</string>
<string name="bpm_format">%d BPM</string>
<string name="timer_default">00:00:00</string>
<string name="filter_heart">Heart</string>
<string name="filter_lungs">Lung</string>
<string name="filter_bowel">Bowel</string>
<string name="filter_pregnancy">Pregnancy</string>
<string name="filter_full_body">Full Body</string>
```

```xml
<!-- res/drawable/bg_record_button.xml -->
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/red_primary" />
    <size android:width="80dp" android:height="80dp" />
</shape>
```

---

### 3. Recording screen

#### 3.1 Layout — `fragment_recording.xml`

Copy this whole layout in (or merge the pieces you need into your existing screen — every view referenced by the Kotlin below is here):

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#F8F9FA">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/topBar"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:background="@color/white"
        android:elevation="2dp"
        android:paddingStart="@dimen/spacing_md"
        android:paddingEnd="@dimen/spacing_md"
        app:layout_constraintTop_toTopOf="parent">

        <ImageButton
            android:id="@+id/infoButton"
            android:layout_width="32dp" android:layout_height="32dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Placement info"
            android:scaleType="centerInside"
            android:src="@drawable/ic_info"
            app:tint="#128CB2"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <TextView
            android:id="@+id/screenTitle"
            android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:text="TAAL Recorder"
            android:textColor="@color/text_primary" android:textSize="18sp" android:textStyle="bold"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <ImageButton
            android:id="@+id/deviceIcon"
            android:layout_width="40dp" android:layout_height="40dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:src="@drawable/icon_taal"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent" />
    </androidx.constraintlayout.widget.ConstraintLayout>

    <TextView
        android:id="@+id/timerText"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_marginTop="15dp"
        android:text="@string/timer_default"
        android:textSize="20sp" android:textStyle="bold" android:textColor="@color/text_primary"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/topBar" />

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/filterContainer"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_marginTop="10dp"
        app:cardBackgroundColor="@color/white" app:cardCornerRadius="24dp" app:cardElevation="4dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/timerText"
        app:strokeColor="#E0E0E0" app:strokeWidth="1dp">

        <LinearLayout
            android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:gravity="center" android:orientation="horizontal" android:padding="5dp">

            <ImageButton android:id="@+id/filterHeart" android:layout_width="48dp" android:layout_height="48dp"
                android:background="?attr/selectableItemBackgroundBorderless" android:padding="12dp"
                android:scaleType="centerInside" android:src="@drawable/ic_heart"
                app:tint="@color/filter_icon_selector" />
            <View android:layout_width="1dp" android:layout_height="24dp" android:background="#EEEEEE" />

            <ImageButton android:id="@+id/filterLungs" android:layout_width="48dp" android:layout_height="48dp"
                android:background="?attr/selectableItemBackgroundBorderless" android:padding="12dp"
                android:scaleType="centerInside" android:src="@drawable/ic_lungs"
                app:tint="@color/filter_icon_selector" />
            <View android:layout_width="1dp" android:layout_height="24dp" android:background="#EEEEEE" />

            <ImageButton android:id="@+id/filterBowel" android:layout_width="48dp" android:layout_height="48dp"
                android:background="?attr/selectableItemBackgroundBorderless" android:padding="12dp"
                android:scaleType="centerInside" android:src="@drawable/ic_bowel"
                app:tint="@color/filter_icon_selector" />
            <View android:layout_width="1dp" android:layout_height="24dp" android:background="#EEEEEE" />

            <ImageButton android:id="@+id/filterPregnancy" android:layout_width="48dp" android:layout_height="48dp"
                android:background="?attr/selectableItemBackgroundBorderless" android:padding="12dp"
                android:scaleType="centerInside" android:src="@drawable/ic_pregnancy"
                app:tint="@color/filter_icon_selector" />
            <View android:layout_width="1dp" android:layout_height="24dp" android:background="#EEEEEE" />

            <ImageButton android:id="@+id/filterInfo" android:layout_width="48dp" android:layout_height="48dp"
                android:background="?attr/selectableItemBackgroundBorderless" android:padding="12dp"
                android:scaleType="centerInside" android:src="@drawable/ic_accessibility"
                app:tint="@color/filter_icon_selector" />
            <View android:layout_width="1dp" android:layout_height="24dp" android:background="#EEEEEE" />

            <ImageButton android:id="@+id/filterCustom" android:layout_width="48dp" android:layout_height="48dp"
                android:background="?attr/selectableItemBackgroundBorderless" android:padding="12dp"
                android:scaleType="centerInside" android:src="@drawable/ic_custom_filter"
                app:tint="@color/filter_icon_selector" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/customRangePanel"
        android:layout_width="0dp" android:layout_height="wrap_content"
        android:layout_marginStart="16dp" android:layout_marginTop="6dp" android:layout_marginEnd="16dp"
        android:visibility="gone"
        app:cardBackgroundColor="@color/white" app:cardCornerRadius="16dp" app:cardElevation="4dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/filterContainer"
        app:strokeColor="#E0E0E0" app:strokeWidth="1dp">

        <LinearLayout
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingStart="16dp" android:paddingTop="10dp" android:paddingEnd="16dp" android:paddingBottom="10dp">

            <TextView
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:layout_marginBottom="4dp"
                android:text="Custom Frequency Range" android:textColor="#128CB2" android:textSize="12sp" android:textStyle="bold" />

            <com.google.android.material.slider.RangeSlider
                android:id="@+id/customRangeSlider"
                android:layout_width="match_parent" android:layout_height="wrap_content"
                android:valueFrom="0" android:valueTo="24000"
                app:haloColor="#1A128CB2" app:labelBehavior="gone"
                app:thumbColor="#128CB2" app:thumbRadius="8dp"
                app:trackColorActive="#128CB2" app:trackColorInactive="#C8E6F5" app:trackHeight="4dp" />

            <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="0 Hz" android:textColor="#999999" android:textSize="10sp" />
                <View android:layout_width="0dp" android:layout_height="0dp" android:layout_weight="1" />
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="24000 Hz" android:textColor="#999999" android:textSize="10sp" />
            </LinearLayout>

            <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp" android:orientation="horizontal">
                <com.google.android.material.textfield.TextInputLayout
                    style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox.Dense"
                    android:layout_width="0dp" android:layout_height="wrap_content" android:layout_marginEnd="6dp" android:layout_weight="1"
                    android:hint="Low Cut (Hz)">
                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/customLowCutInput" android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:inputType="number" android:maxLength="5" />
                </com.google.android.material.textfield.TextInputLayout>

                <com.google.android.material.textfield.TextInputLayout
                    style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox.Dense"
                    android:layout_width="0dp" android:layout_height="wrap_content" android:layout_marginStart="6dp" android:layout_weight="1"
                    android:hint="High Cut (Hz)">
                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/customHighCutInput" android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:inputType="number" android:maxLength="5" />
                </com.google.android.material.textfield.TextInputLayout>
            </LinearLayout>
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/ampSliderContainer"
        android:layout_width="0dp" android:layout_height="wrap_content"
        android:layout_marginStart="16dp" android:layout_marginTop="8dp" android:layout_marginEnd="16dp"
        app:cardBackgroundColor="@color/white" app:cardCornerRadius="16dp" app:cardElevation="4dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/customRangePanel"
        app:strokeColor="#E0E0E0" app:strokeWidth="1dp">

        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center_vertical" android:orientation="horizontal"
            android:paddingStart="14dp" android:paddingTop="6dp" android:paddingEnd="14dp" android:paddingBottom="2dp">

            <ImageView android:layout_width="20dp" android:layout_height="20dp" android:src="@drawable/ic_volume_up" app:tint="#128CB2" />

            <com.google.android.material.slider.Slider
                android:id="@+id/ampSlider"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_marginStart="6dp" android:layout_marginEnd="6dp" android:layout_weight="1"
                android:stepSize="1" android:value="5" android:valueFrom="0" android:valueTo="30"
                app:haloColor="#1A128CB2" app:labelBehavior="gone"
                app:thumbColor="#128CB2" app:thumbRadius="8dp"
                app:trackColorActive="#128CB2" app:trackColorInactive="#C8E6F5" app:trackHeight="4dp" />

            <TextView android:id="@+id/ampLabel" android:layout_width="44dp" android:layout_height="wrap_content"
                android:gravity="end" android:text="5 dB" android:textColor="#128CB2" android:textSize="12sp" android:textStyle="bold" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <com.github.mikephil.charting.charts.LineChart
        android:id="@+id/waveformChart"
        android:layout_width="0dp" android:layout_height="0dp"
        android:layout_marginTop="8dp" android:layout_marginBottom="8dp"
        app:layout_constraintBottom_toTopOf="@id/bpmText"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/ampSliderContainer" />

    <TextView android:id="@+id/bpmText"
        android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginBottom="8dp"
        android:text="-- BPM" android:textColor="@color/text_secondary" android:textSize="@dimen/text_bpm"
        app:layout_constraintBottom_toTopOf="@id/actionText"
        app:layout_constraintEnd_toEndOf="parent" app:layout_constraintStart_toStartOf="parent" />

    <TextView android:id="@+id/actionText"
        android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginBottom="8dp"
        android:text="@string/start_recording" android:textColor="@color/text_primary" android:textSize="16sp"
        app:layout_constraintBottom_toTopOf="@id/recordButton"
        app:layout_constraintEnd_toEndOf="parent" app:layout_constraintStart_toStartOf="parent" />

    <ImageButton android:id="@+id/recordButton"
        android:layout_width="@dimen/record_button_size" android:layout_height="@dimen/record_button_size"
        android:layout_marginBottom="32dp" android:background="@drawable/bg_record_button"
        android:elevation="8dp" android:padding="0dp" android:scaleType="fitCenter"
        android:src="@drawable/ic_recording_start1"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent" app:layout_constraintStart_toStartOf="parent"
        app:tint="@color/white" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

> The original also has a `bottomBar` (folder/settings buttons that navigate to a saved-recordings screen) — omitted here since that's pure app-flow navigation, entirely up to you. Wire your own "go to my recordings list" button anywhere you like; it has no bearing on `taal-core`.

#### 3.2 ViewModel + UI state (copy verbatim, zero dependencies beyond AndroidX)

```kotlin
// RecordingUiState.kt
enum class RecordingUiState { IDLE, RECORDING, STOPPED }
```

```kotlin
// RecordingViewModel.kt
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class RecordingViewModel : ViewModel() {
    private val _uiState = MutableLiveData(RecordingUiState.IDLE)
    val uiState: LiveData<RecordingUiState> = _uiState

    private val _timerSeconds = MutableLiveData(0)
    val timerSeconds: LiveData<Int> = _timerSeconds

    private val _currentFilter = MutableLiveData("HEART")
    val currentFilter: LiveData<String> = _currentFilter

    private val _bpm = MutableLiveData(0)
    val bpm: LiveData<Int> = _bpm

    private val _preAmpDb = MutableLiveData(5)
    val preAmpDb: LiveData<Int> = _preAmpDb

    var currentRecordingPath: String = ""
    var currentFilteredPath: String = ""
    var customLowCut: Float? = null
    var customHighCut: Float? = null

    fun setUiState(state: RecordingUiState) { _uiState.value = state }
    fun updateTimer(seconds: Int) { _timerSeconds.value = seconds }
    fun setFilter(filter: String) { _currentFilter.value = filter }
    fun setBpm(bpm: Int) { _bpm.value = bpm }
    fun setPreAmp(db: Int) { _preAmpDb.value = db.coerceIn(0, 30) }

    fun formatTimer(seconds: Int): String {
        val clamped = seconds.coerceAtLeast(0)
        val hours = clamped / 3600
        val minutes = (clamped % 3600) / 60
        val secs = clamped % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
}
```

#### 3.3 The Fragment — full `taal-core` wiring

This is the complete recording screen logic. `①`/`②` mark the two spots you adapt to your own app's config-passing and navigation.

```kotlin
package your.app.package.recording

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalRecorder
import com.musediagnostics.taal.core.RecorderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingFragment : Fragment() {

    // Replace with your own ViewBinding class for the layout in §3.1
    private var _binding: FragmentRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    private var audioTrack: AudioTrack? = null
    private val waveformEntries = ArrayList<Entry>()

    private var waveformDataSet: LineDataSet? = null
    private var peakAmplitude = 1.0f
    private var lastPeakUpdateTime = 0L
    private var warmupPeak = 0f
    private var warmupDone = false
    private var chartInitialized = false
    private var totalSamplesProcessed = 0L
    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ② Callback for "recording finished, go to player" — wire this to your own navigation.
    var onGoToPlayer: ((filteredPath: String, rawPath: String, filterName: String, isNewRecording: Boolean) -> Unit)? = null
    // ② Callback for "go to my saved recordings" — wire this to your own navigation.
    var onGoToSavedRecordings: (() -> Unit)? = null

    companion object {
        private const val WINDOW_SECONDS = 10f
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val DOWNSAMPLE_STEP = 44
        private const val WARMUP_MS = 2000L
        private const val HEADROOM = 1.5f
        private const val MIN_PEAK = 0.02f
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(requireContext(), "Audio permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ① INITIAL VALUES — original reads these from TaalRecorderActivity's intent extras.
        // Replace with your own source, e.g. fragment arguments:
        val initFilter = arguments?.getString("preFilter") ?: "HEART"
        val initPreAmp = arguments?.getInt("preAmplification", 5) ?: 5
        viewModel.setFilter(initFilter)
        viewModel.setPreAmp(initPreAmp)

        setupWaveformChart()
        setupFilterButtons()
        setupCustomRangePanel()
        setupPreAmpSlider()
        setupButtons()
        observeState()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.uiState.value == RecordingUiState.RECORDING) {
                        Toast.makeText(requireContext(), "Stop the recording before going back", Toast.LENGTH_SHORT).show()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun setupWaveformChart() {
        val chart = binding.waveformChart
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setDrawGridBackground(true)
        chart.setGridBackgroundColor(Color.WHITE)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            gridLineWidth = 1f
            granularity = 1f
            axisMinimum = 0f
            setLabelCount(10, false)
            setDrawAxisLine(false)
            setDrawLabels(false)
        }
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            gridLineWidth = 1f
            axisMinimum = -1f
            axisMaximum = 1f
            setLabelCount(5, true)
            setDrawLabels(false)
            setDrawAxisLine(false)
        }
        chart.axisRight.isEnabled = false
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)

        val dummy = LineDataSet(listOf(Entry(0f, 0f), Entry(WINDOW_SECONDS, 0f)), "").apply {
            color = Color.TRANSPARENT; setDrawCircles(false); setDrawValues(false)
        }
        chart.data = LineData(dummy)
        chart.invalidate()
    }

    private fun setupPreAmpSlider() {
        binding.ampSlider.value = (viewModel.preAmpDb.value ?: 5).toFloat()
        binding.ampLabel.text = "${viewModel.preAmpDb.value ?: 5} dB"
        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            viewModel.setPreAmp(db)
            binding.ampLabel.text = "$db dB"
            taalRecorder?.setPreAmplification(db)   // live update while recording
        }
    }

    private fun setupFilterButtons() {
        val presetFilters = mapOf(
            binding.filterHeart to "HEART",
            binding.filterLungs to "LUNGS",
            binding.filterBowel to "BOWEL",
            binding.filterPregnancy to "PREGNANCY",
            binding.filterInfo to "FULL_BODY"
        )
        val allButtons = presetFilters.keys + binding.filterCustom

        val currentFilter = viewModel.currentFilter.value ?: "HEART"
        if (currentFilter == "CUSTOM") {
            binding.filterCustom.isSelected = true
            binding.customRangePanel.visibility = View.VISIBLE
        } else {
            (presetFilters.entries.find { it.value == currentFilter }?.key ?: binding.filterHeart).isSelected = true
            binding.customRangePanel.visibility = View.GONE
        }

        presetFilters.forEach { (button, name) ->
            button.setOnClickListener {
                allButtons.forEach { it.isSelected = false }
                button.isSelected = true
                viewModel.setFilter(name)
                binding.customRangePanel.visibility = View.GONE
                dismissKeyboard()
            }
        }
        binding.filterCustom.setOnClickListener {
            allButtons.forEach { it.isSelected = false }
            binding.filterCustom.isSelected = true
            viewModel.setFilter("CUSTOM")
            binding.customRangePanel.visibility = View.VISIBLE
        }
    }

    private fun setupCustomRangePanel() {
        val initLow = viewModel.customLowCut ?: 20f
        val initHigh = viewModel.customHighCut ?: 10000f
        viewModel.customLowCut = initLow
        viewModel.customHighCut = initHigh

        binding.customRangeSlider.values = listOf(initLow.coerceIn(0f, 24000f), initHigh.coerceIn(0f, 24000f))
        binding.customLowCutInput.setText(initLow.toInt().toString())
        binding.customHighCutInput.setText(initHigh.toInt().toString())

        var isUpdating = false
        binding.customRangeSlider.addOnChangeListener { _, _, _ ->
            if (isUpdating) return@addOnChangeListener
            isUpdating = true
            val vals = binding.customRangeSlider.values
            binding.customLowCutInput.setText(vals[0].toInt().toString())
            binding.customHighCutInput.setText(vals[1].toInt().toString())
            viewModel.customLowCut = vals[0]
            viewModel.customHighCut = vals[1]
            isUpdating = false
        }

        binding.customLowCutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                viewModel.customLowCut = v.coerceIn(1f, 24000f)
                val currentHigh = binding.customRangeSlider.values[1]
                if (v in 1f..24000f && v < currentHigh) {
                    isUpdating = true
                    binding.customRangeSlider.values = listOf(v, currentHigh)
                    isUpdating = false
                }
            }
        })
        binding.customHighCutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                viewModel.customHighCut = v.coerceIn(1f, 24000f)
                val currentLow = binding.customRangeSlider.values[0]
                if (v in 1f..24000f && v > currentLow) {
                    isUpdating = true
                    binding.customRangeSlider.values = listOf(currentLow, v)
                    isUpdating = false
                }
            }
        })
    }

    private fun dismissKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)
        requireView().clearFocus()
    }

    private fun setFilterButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        listOf(binding.filterHeart, binding.filterLungs, binding.filterBowel,
               binding.filterPregnancy, binding.filterInfo, binding.filterCustom).forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
        if (!enabled) binding.customRangePanel.visibility = View.GONE
        else if (viewModel.currentFilter.value == "CUSTOM") binding.customRangePanel.visibility = View.VISIBLE
    }

    private fun setupButtons() {
        binding.infoButton.setOnClickListener {
            val filterName = viewModel.currentFilter.value ?: "HEART"
            FilterPlacementDialog.newInstance(filterName).show(parentFragmentManager, "filter_placement")
        }

        binding.recordButton.setOnClickListener {
            when (viewModel.uiState.value) {
                RecordingUiState.IDLE -> checkPermissionAndRecord()
                RecordingUiState.RECORDING -> stopRecording()
                else -> resetToIdle()
            }
        }
    }

    private fun resetToIdle() {
        viewModel.setUiState(RecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        viewModel.currentFilteredPath = ""
        waveformEntries.clear()
        waveformDataSet = null
        chartInitialized = false
        peakAmplitude = 1.0f
        warmupPeak = 0f
        warmupDone = false
        lastPeakUpdateTime = 0L
        totalSamplesProcessed = 0L
        bpmCalculator.reset()

        val dummy = LineDataSet(listOf(Entry(0f, 0f), Entry(10f, 0f)), "").apply {
            color = Color.TRANSPARENT; setDrawCircles(false); setDrawValues(false)
        }
        binding.waveformChart.data = LineData(dummy)
        binding.waveformChart.moveViewToX(0f)
        binding.waveformChart.invalidate()
        binding.bpmText.text = "-- BPM"
    }

    private fun observeState() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                RecordingUiState.IDLE -> {
                    binding.actionText.text = getString(R.string.start_recording)
                    binding.recordButton.setImageResource(R.drawable.ic_recording_start1)
                    binding.timerText.text = getString(R.string.timer_default)
                    binding.ampSlider.isEnabled = true
                    binding.ampSliderContainer.alpha = 1f
                    setFilterButtonsEnabled(true)
                }
                RecordingUiState.RECORDING -> {
                    binding.actionText.text = getString(R.string.stop_recording)
                    binding.recordButton.setImageResource(R.drawable.ic_recording_stop)
                    binding.ampSlider.isEnabled = false
                    binding.ampSliderContainer.alpha = 0.55f
                    setFilterButtonsEnabled(false)
                }
                else -> {}
            }
        }
        viewModel.timerSeconds.observe(viewLifecycleOwner) { seconds ->
            binding.timerText.text = viewModel.formatTimer(seconds)
        }
        viewModel.bpm.observe(viewLifecycleOwner) { bpm ->
            binding.bpmText.text = if (bpm > 0) getString(R.string.bpm_format, bpm) else "-- BPM"
        }
    }

    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) startRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        dismissKeyboard()
        val filterName = viewModel.currentFilter.value ?: "HEART"

        if (filterName == "CUSTOM") {
            val low = viewModel.customLowCut
            val high = viewModel.customHighCut
            val lowText = binding.customLowCutInput.text?.toString()?.trim()
            val highText = binding.customHighCutInput.text?.toString()?.trim()
            val message = when {
                lowText.isNullOrEmpty() && highText.isNullOrEmpty() -> "Low Cut and High Cut cannot be blank."
                lowText.isNullOrEmpty() -> "Low Cut cannot be blank."
                highText.isNullOrEmpty() -> "High Cut cannot be blank."
                low == null || low <= 0f -> "Low Cut cannot be 0 Hz."
                high == null || high <= 0f -> "High Cut cannot be 0 Hz."
                low >= high -> "Low Cut (${low.toInt()} Hz) must be less than High Cut (${high.toInt()} Hz)."
                else -> null
            }
            if (message != null) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Custom Filter").setMessage(message)
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }.show()
                return
            }
        }

        try {
            val ts = System.currentTimeMillis()
            val rawFilePath = "${requireContext().filesDir}/recording_${ts}_raw.wav"
            val filteredFilePath = "${requireContext().filesDir}/recording_${ts}_filtered.wav"
            viewModel.currentRecordingPath = rawFilePath
            viewModel.currentFilteredPath = filteredFilePath

            taalRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(rawFilePath)
                setFilteredAudioFilePath(filteredFilePath)
                setRecordingTime(300)
                setPlayback(false)
                setPreAmplification(viewModel.preAmpDb.value ?: 5)
                if (filterName == "CUSTOM") {
                    setCustomBandpass(viewModel.customLowCut!!.toDouble(), viewModel.customHighCut!!.toDouble())
                } else {
                    setPreFilter(PreFilter.valueOf(filterName))
                }

                onInfoListener = object : TaalRecorder.OnInfoListener {
                    override fun onStateChange(state: RecorderState) {
                        activity?.runOnUiThread {
                            when (state) {
                                RecorderState.RECORDING -> viewModel.setUiState(RecordingUiState.RECORDING)
                                RecorderState.STOPPED -> viewModel.setUiState(RecordingUiState.STOPPED)
                                else -> {}
                            }
                        }
                    }

                    override fun onProgressUpdate(sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray) {
                        // optional: monitor the raw signal through the earpiece/speaker
                        audioTrack?.let { track ->
                            val pcm = ShortArray(data.size) { i -> (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                            track.write(pcm, 0, pcm.size)
                        }

                        val shouldCompute = bpmCalculator.addSamples(data)
                        if (shouldCompute) {
                            bpmScope.launch {
                                val bpm = bpmCalculator.computeBpm()
                                if (bpm > 0) withContext(Dispatchers.Main) {
                                    if (isAdded && _binding != null) viewModel.setBpm(bpm)
                                }
                            }
                        }

                        // Undo pre-amp gain before drawing — waveform always reflects
                        // acoustic signal level, not the amplified version.
                        val preAmpDb = viewModel.preAmpDb.value ?: 5
                        val preAmpGain = Math.pow(10.0, preAmpDb / 20.0).toFloat()
                        val displayData = if (preAmpGain > 1.001f) FloatArray(data.size) { i -> data[i] / preAmpGain } else data

                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                updateWaveform(timeStamp, displayData)
                                viewModel.updateTimer(timeStamp.toInt())
                            }
                        }
                    }
                }
            }

            waveformEntries.clear()
            waveformDataSet = null
            chartInitialized = false
            peakAmplitude = 1.0f
            warmupPeak = 0f
            warmupDone = false
            lastPeakUpdateTime = 0L
            totalSamplesProcessed = 0L
            bpmCalculator.reset()
            binding.waveformChart.data = LineData()
            binding.waveformChart.moveViewToX(0f)
            startAudioMonitor()
            taalRecorder?.start()   // throws TaalDisconnectedException if TAAL not connected

        } catch (e: Exception) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording Error").setMessage(e.message)
                .setPositiveButton("OKAY") { dialog, _ -> dialog.dismiss() }.show()
            viewModel.setUiState(RecordingUiState.IDLE)
        }
    }

    private fun startAudioMonitor() {
        val minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC, 44100, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 2, AudioTrack.MODE_STREAM
        ).apply { play() }
    }

    private fun stopAudioMonitor() {
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private fun stopRecording() {
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
        taalRecorder = null

        val filteredPath = viewModel.currentFilteredPath
        val rawPath = viewModel.currentRecordingPath
        val filterName = viewModel.currentFilter.value ?: "HEART"

        if (filteredPath.isNotEmpty()) {
            // ② YOUR NAVIGATION HERE — go to your player screen with these 4 values.
            onGoToPlayer?.invoke(filteredPath, rawPath, filterName, true)
        }
    }

    // The core waveform algorithm — 10-second scrolling window, sample-accurate X axis,
    // auto-scaled Y axis locked after a 2-second warmup period. Copy verbatim.
    private fun updateWaveform(timestamp: Double, data: FloatArray) {
        if (_binding == null || !isAdded) return
        val chart = binding.waveformChart

        var bufferPeak = 0f
        for (sample in data) { val abs = Math.abs(sample); if (abs > bufferPeak) bufferPeak = abs }

        val step = DOWNSAMPLE_STEP
        for (i in 0 until data.size step step) {
            val currentX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
            waveformEntries.add(Entry(currentX, data[i]))
            totalSamplesProcessed += step
        }

        val latestX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
        val currentPage = (latestX / WINDOW_SECONDS).toInt()
        val minXToKeep = (currentPage - 1) * WINDOW_SECONDS
        if (minXToKeep > 0) {
            val iterator = waveformEntries.iterator()
            while (iterator.hasNext()) { if (iterator.next().x < minXToKeep) iterator.remove() else break }
        }

        val now = System.currentTimeMillis()
        if (!warmupDone) {
            if (bufferPeak > warmupPeak) warmupPeak = bufferPeak
            if (lastPeakUpdateTime == 0L) lastPeakUpdateTime = now
            if (now - lastPeakUpdateTime >= WARMUP_MS) {
                warmupDone = true
                peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
                chart.axisLeft.axisMinimum = -peakAmplitude
                chart.axisLeft.axisMaximum = peakAmplitude
            }
        }

        val snapshot = ArrayList(waveformEntries.toList())
        val ds = waveformDataSet
        if (ds == null || chart.data == null) {
            waveformDataSet = LineDataSet(snapshot, "Waveform").apply {
                color = ContextCompat.getColor(requireContext(), R.color.waveform_blue)
                setDrawCircles(false); setDrawValues(false)
                lineWidth = 1.5f
                mode = LineDataSet.Mode.LINEAR
                setDrawHighlightIndicators(false)
            }
            chart.data = LineData(waveformDataSet)
        } else {
            ds.values = snapshot
            chart.data?.notifyDataChanged()
        }
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)
        chart.moveViewToX(currentPage * WINDOW_SECONDS)
        chart.invalidate()
    }

    override fun onResume() {
        super.onResume()
        if (taalRecorder == null) resetToIdle()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        bpmScope.cancel()
        stopAudioMonitor()
        try { taalRecorder?.stop() } catch (_: Exception) {}
    }
}
```

**USB connection indicator (optional, drop into `onResume`/lifecycle of your own choosing):**

```kotlin
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import com.musediagnostics.taal.utils.SurrUtils

// Check once when the screen appears — catches a device plugged in before this screen existed.
val status = SurrUtils.isTaalDeviceConnected(requireContext())
binding.deviceIcon.setColorFilter(
    if (status == SurrUtils.ConnectionStatus.CONNECTED) Color.parseColor("#128CB2")
    else Color.parseColor("#333333")
)

// Register for live plug/unplug events.
val connectionReceiver = TaalConnectionBroadcastReceiver(object : TaalConnectionBroadcastReceiver.TaalConnectionListener {
    override fun onTaalConnect() { activity?.runOnUiThread { binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2")) } }
    override fun onTaalDisconnect() { activity?.runOnUiThread { binding.deviceIcon.setColorFilter(Color.parseColor("#333333")) } }
})
connectionReceiver.register(requireContext())
// ... and connectionReceiver.unregister(requireContext()) in onDestroyView, always.
```

---

### 4. Filter placement diagrams ("i" info button)

Full dialog, unchanged, portable — it depends only on Android APIs plus your own bundled images (§2.3).

```xml
<!-- res/layout/dialog_filter_placement.xml -->
<?xml version="1.0" encoding="utf-8"?>
<androidx.cardview.widget.CardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent" android:layout_height="wrap_content"
    app:cardCornerRadius="16dp" app:cardElevation="8dp" app:cardBackgroundColor="@color/white">

    <LinearLayout
        android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical"
        android:paddingStart="16dp" android:paddingEnd="16dp" android:paddingTop="16dp" android:paddingBottom="20dp">

        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content"
            android:orientation="horizontal" android:gravity="center_vertical" android:layout_marginBottom="12dp">
            <TextView android:id="@+id/placementTitle" android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:textSize="17sp" android:textStyle="bold" android:textColor="#1A1A2E" />
            <ImageButton android:id="@+id/closeButton" android:layout_width="32dp" android:layout_height="32dp"
                android:background="?attr/selectableItemBackgroundBorderless" android:src="@drawable/ic_close"
                android:scaleType="centerInside" android:padding="4dp" app:tint="#666666" />
        </LinearLayout>

        <androidx.viewpager2.widget.ViewPager2
            android:id="@+id/imagePager" android:layout_width="match_parent" android:layout_height="300dp" />

        <LinearLayout android:id="@+id/dotsContainer" android:layout_width="match_parent" android:layout_height="wrap_content"
            android:gravity="center" android:orientation="horizontal" android:layout_marginTop="14dp" />

        <TextView android:id="@+id/noImagesText" android:layout_width="match_parent" android:layout_height="300dp"
            android:gravity="center" android:text="No placement images found." android:textColor="#999999"
            android:textSize="14sp" android:visibility="gone" />
    </LinearLayout>
</androidx.cardview.widget.CardView>
```

```xml
<!-- res/layout/item_placement_image.xml -->
<?xml version="1.0" encoding="utf-8"?>
<ImageView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/placementImage"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:scaleType="fitCenter" android:adjustViewBounds="true" />
```

```kotlin
package your.app.package.recording

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class FilterPlacementDialog : DialogFragment() {

    companion object {
        private const val MAX_PLACEMENT_IMAGES = 12
        fun newInstance(filterName: String) = FilterPlacementDialog().apply {
            arguments = Bundle().apply { putString("filterName", filterName) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also {
            it.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_filter_placement, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val filterName = arguments?.getString("filterName") ?: "HEART"
        val images = resolveImages(filterName)

        val title = filterName.split("_").joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } } + " Placement"
        view.findViewById<TextView>(R.id.placementTitle).text = title
        view.findViewById<ImageButton>(R.id.closeButton).setOnClickListener { dismiss() }

        val pager = view.findViewById<ViewPager2>(R.id.imagePager)
        val dotsContainer = view.findViewById<LinearLayout>(R.id.dotsContainer)
        val noImagesText = view.findViewById<TextView>(R.id.noImagesText)

        if (images.isEmpty()) {
            pager.visibility = View.GONE
            dotsContainer.visibility = View.GONE
            noImagesText.visibility = View.VISIBLE
            return
        }

        pager.adapter = PlacementImageAdapter(images)
        setupDots(dotsContainer, images.size)
        if (images.size > 1) {
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) { updateDots(dotsContainer, position, images.size) }
            })
        } else {
            dotsContainer.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout((resources.displayMetrics.widthPixels * 0.88).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    // Images bundled as taal_placement_{filter}_{index}.png. Indices need not be
    // contiguous — scan the full range instead of stopping at the first gap.
    private fun resolveImages(filterName: String): List<Int> {
        val prefix = "taal_placement_${filterName.lowercase()}_"
        val result = mutableListOf<Int>()
        for (index in 1..MAX_PLACEMENT_IMAGES) {
            val resId = resources.getIdentifier("$prefix$index", "drawable", requireContext().packageName)
            if (resId != 0) result.add(resId)
        }
        return result
    }

    private fun setupDots(container: LinearLayout, count: Int) {
        container.removeAllViews()
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        repeat(count) { i ->
            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp8, dp8).apply { marginStart = dp4; marginEnd = dp4 }
                background = ContextCompat.getDrawable(requireContext(), if (i == 0) R.drawable.dot_active else R.drawable.dot_inactive)
            }
            container.addView(dot)
        }
    }

    private fun updateDots(container: LinearLayout, selected: Int, count: Int) {
        for (i in 0 until count) {
            container.getChildAt(i)?.background = ContextCompat.getDrawable(
                requireContext(), if (i == selected) R.drawable.dot_active else R.drawable.dot_inactive
            )
        }
    }

    private class PlacementImageAdapter(private val images: List<Int>) : RecyclerView.Adapter<PlacementImageAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.placementImage)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_placement_image, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) { holder.imageView.setImageResource(images[position]) }
        override fun getItemCount() = images.size
    }
}
```

---

### 5. Player screen

#### 5.1 Layout — `fragment_player.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent" android:layout_height="match_parent" android:background="#F8F9FA">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/topBar" android:layout_width="match_parent" android:layout_height="56dp"
        android:background="@color/white" android:elevation="2dp"
        android:paddingStart="@dimen/spacing_md" android:paddingEnd="@dimen/spacing_md"
        app:layout_constraintTop_toTopOf="parent">

        <ImageButton android:id="@+id/backButton" android:layout_width="32dp" android:layout_height="32dp"
            android:background="?attr/selectableItemBackgroundBorderless" android:scaleType="centerInside"
            android:src="@drawable/ic_arrow_back"
            app:layout_constraintBottom_toBottomOf="parent" app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toTopOf="parent" />

        <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Review Recording"
            android:textColor="@color/text_primary" android:textSize="18sp" android:textStyle="bold"
            app:layout_constraintBottom_toBottomOf="parent" app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toTopOf="parent" />
    </androidx.constraintlayout.widget.ConstraintLayout>

    <TextView android:id="@+id/timerText" android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_marginTop="15dp" android:text="@string/timer_default" android:textSize="20sp" android:textStyle="bold"
        app:layout_constraintEnd_toEndOf="parent" app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toBottomOf="@id/topBar" />

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/ampSliderContainer" android:layout_width="0dp" android:layout_height="wrap_content"
        android:layout_marginStart="16dp" android:layout_marginTop="6dp" android:layout_marginEnd="16dp"
        app:cardBackgroundColor="@color/white" app:cardCornerRadius="12dp" app:cardElevation="2dp"
        app:strokeColor="#E0E0E0" app:strokeWidth="1dp"
        app:layout_constraintEnd_toEndOf="parent" app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toBottomOf="@id/timerText">

        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center_vertical"
            android:orientation="horizontal" android:paddingStart="12dp" android:paddingTop="4dp" android:paddingEnd="12dp">
            <ImageView android:layout_width="18dp" android:layout_height="18dp" android:src="@drawable/ic_volume_up" app:tint="#128CB2" />
            <com.google.android.material.slider.Slider
                android:id="@+id/ampSlider" android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_marginStart="4dp" android:layout_marginEnd="4dp" android:layout_weight="1"
                android:stepSize="1" android:value="5" android:valueFrom="0" android:valueTo="30"
                app:haloColor="#1A128CB2" app:labelBehavior="gone" app:thumbColor="#128CB2" app:thumbRadius="7dp"
                app:trackColorActive="#128CB2" app:trackColorInactive="#C8E6F5" app:trackHeight="3dp" />
            <TextView android:id="@+id/ampLabel" android:layout_width="40dp" android:layout_height="wrap_content"
                android:gravity="end" android:text="5 dB" android:textColor="#128CB2" android:textSize="11sp" android:textStyle="bold" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <com.github.mikephil.charting.charts.LineChart
        android:id="@+id/waveformChart" android:layout_width="0dp" android:layout_height="0dp"
        android:layout_marginTop="8dp" android:layout_marginBottom="8dp"
        app:layout_constraintBottom_toTopOf="@id/actionText" app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toBottomOf="@id/ampSliderContainer" />

    <TextView android:id="@+id/actionText" android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_marginBottom="8dp" android:text="@string/play_recording" android:textColor="@color/text_primary" android:textSize="16sp"
        app:layout_constraintBottom_toTopOf="@id/playButton" app:layout_constraintEnd_toEndOf="parent" app:layout_constraintStart_toStartOf="parent" />

    <ImageButton android:id="@+id/playButton"
        android:layout_width="@dimen/record_button_size" android:layout_height="@dimen/record_button_size"
        android:layout_marginBottom="24dp" android:background="@drawable/bg_record_button"
        android:elevation="8dp" android:padding="0dp" android:scaleType="fitCenter" android:src="@drawable/ic_play_circle"
        app:layout_constraintBottom_toTopOf="@id/saveDiscardBar" app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" app:tint="@color/white" />

    <LinearLayout android:id="@+id/saveDiscardBar" android:layout_width="match_parent" android:layout_height="wrap_content"
        android:layout_marginHorizontal="24dp" android:layout_marginBottom="24dp" android:orientation="horizontal"
        app:layout_constraintBottom_toBottomOf="parent">

        <Button android:id="@+id/discardButton" android:layout_width="0dp" android:layout_height="48dp"
            android:layout_marginEnd="8dp" android:layout_weight="1" android:background="@drawable/bg_button_outlined"
            android:text="Discard" android:textAllCaps="false" android:textSize="15sp" android:textStyle="bold" />

        <Button android:id="@+id/saveButton" android:layout_width="0dp" android:layout_height="48dp"
            android:layout_marginStart="8dp" android:layout_weight="1" android:background="@drawable/bg_button_teal"
            android:text="Save" android:textAllCaps="false" android:textColor="@color/white" android:textSize="15sp" android:textStyle="bold" />
    </LinearLayout>
</androidx.constraintlayout.widget.ConstraintLayout>
```

#### 5.2 The Fragment — full `taal-core` wiring

```kotlin
package your.app.package.player

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    companion object { private const val INPUT_SAMPLE_RATE = 44100f }

    // ① Provide these however your screen receives them — nav args, constructor, etc.
    var filePath: String = ""
    var rawFilePath: String = ""
    var filterName: String = "HEART"
    var isNewRecording: Boolean = false

    // ② Wire to your own navigation.
    var onBack: (() -> Unit)? = null
    var onGoToSave: ((filePath: String, rawFilePath: String, filterName: String) -> Unit)? = null
    var onDiscardedOrBack: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()
        setupAmpSlider()

        if (filePath.isNotEmpty()) {
            loadFullWaveform(filePath)
            setupPlayer(filePath)
        }

        binding.backButton.setOnClickListener { onBack?.invoke() }   // ② YOUR NAVIGATION HERE

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }

        binding.saveButton.setOnClickListener {
            onGoToSave?.invoke(filePath, rawFilePath, filterName)   // ② YOUR NAVIGATION HERE
        }

        binding.discardButton.setOnClickListener { confirmDiscard(filePath, rawFilePath) }
    }

    private fun setupAmpSlider() {
        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            binding.ampLabel.text = "$db dB"
            player?.setPreAmplification(db.toFloat())
        }
    }

    private fun setupWaveformChart() {
        val chart = binding.waveformChart
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawGridBackground(true)
        chart.setGridBackgroundColor(Color.WHITE)
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true); gridColor = Color.parseColor("#F0F0F0"); gridLineWidth = 1f
            setDrawAxisLine(false); setDrawLabels(false)
        }
        chart.axisLeft.apply {
            setDrawGridLines(true); gridColor = Color.parseColor("#F0F0F0"); gridLineWidth = 1f
            axisMinimum = -0.5f; axisMaximum = 0.5f
            setDrawLabels(false); setDrawAxisLine(false)
        }
        chart.axisRight.isEnabled = false
    }

    // Loads the ENTIRE recording at once (unlike the live scrolling recorder view) —
    // downsamples to ~3000 points regardless of file length, so a 5-minute recording
    // renders just as fast as a 10-second one.
    private fun loadFullWaveform(filePath: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@launch
                val bytes = file.readBytes()
                val dataSize = bytes.size - 44
                val totalSamples = dataSize / 2
                val totalDurationSeconds = (totalSamples / INPUT_SAMPLE_RATE).toInt()

                val sampleStep = maxOf(1, totalSamples / 3000)
                val entries = ArrayList<Entry>()
                var sampleIndex = 0
                for (i in 44 until bytes.size - 1 step sampleStep * 2) {
                    val low = bytes[i].toInt() and 0xFF
                    val high = bytes[i + 1].toInt() shl 8
                    val sample = (high or low).toShort().toFloat() / 32768f
                    entries.add(Entry((sampleIndex.toFloat() / INPUT_SAMPLE_RATE), sample))
                    sampleIndex += sampleStep
                }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.timerText.text = String.format("%02d:%02d", totalDurationSeconds / 60, totalDurationSeconds % 60)
                    val dataSet = LineDataSet(entries, "Waveform").apply {
                        color = Color.parseColor("#2D7DD2")
                        setDrawCircles(false); setDrawValues(false)
                        lineWidth = 2.5f; mode = LineDataSet.Mode.LINEAR
                    }
                    binding.waveformChart.apply {
                        data = LineData(dataSet)
                        setVisibleXRangeMaximum(4f)
                        centerViewTo(2f, 0f, YAxis.AxisDependency.LEFT)
                        setTouchEnabled(true); isDragEnabled = true; setScaleEnabled(true)
                        invalidate()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun setupPlayer(filePath: String) {
        try {
            player = TaalPlayer(requireContext()).apply {
                setDataSource(filePath)
                // _filtered.wav already has DSP applied during recording — filtering
                // it again is the single most common integration bug. Always gate on filename.
                if (!File(filePath).name.contains("_filtered")) {
                    val preFilter = try { PreFilter.valueOf(filterName) } catch (_: Exception) { PreFilter.HEART }
                    setPreFilter(preFilter)
                }
                onPlaybackProgress = { timestamp, _ ->
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            val totalSecs = timestamp.toInt()
                            binding.timerText.text = String.format("%02d:%02d", totalSecs / 60, totalSecs % 60)
                            val chart = binding.waveformChart
                            val halfRange = chart.visibleXRange / 2f
                            val centerX = if (timestamp.toFloat() < halfRange) halfRange else timestamp.toFloat()
                            chart.centerViewTo(centerX, 0f, YAxis.AxisDependency.LEFT)
                        }
                    }
                }
                onPlaybackComplete = {
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            isPlaying = false
                            binding.actionText.text = getString(R.string.play_recording)
                            binding.playButton.setImageResource(R.drawable.ic_play_circle)
                            val chart = binding.waveformChart
                            chart.centerViewTo(chart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
                        }
                    }
                }
            }
        } catch (e: InvalidFileNameException) {
            Toast.makeText(requireContext(), "Cannot open recording", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayback(filePath: String) {
        if (isPlaying) {
            player?.stop()
            isPlaying = false
            binding.actionText.text = getString(R.string.play_recording)
            binding.playButton.setImageResource(R.drawable.ic_play_circle)
            binding.waveformChart.centerViewTo(binding.waveformChart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
        } else {
            try {
                binding.waveformChart.centerViewTo(binding.waveformChart.visibleXRange / 2f, 0f, YAxis.AxisDependency.LEFT)
                player?.prepare()
                player?.start()
                isPlaying = true
                binding.actionText.text = getString(R.string.stop_recording)
                binding.playButton.setImageResource(R.drawable.ic_recording_stop)
            } catch (e: Exception) {
                isPlaying = false
                Toast.makeText(requireContext(), "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDiscard(filePath: String, rawFilePath: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard Recording?")
            .setMessage("This recording will be permanently deleted.")
            .setPositiveButton("Discard") { _, _ ->
                try { File(filePath).delete() } catch (_: Exception) {}
                if (rawFilePath.isNotEmpty()) try { File(rawFilePath).delete() } catch (_: Exception) {}
                onDiscardedOrBack?.invoke()   // ② YOUR NAVIGATION HERE
            }
            .setNegativeButton("Keep") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            player?.onPlaybackProgress = null
            player?.onPlaybackComplete = null
            player?.stop()
            player?.release()   // always release — AudioTrack is not reused across plays
        } catch (_: Exception) {}
        _binding = null
    }
}
```

---

### 6. Save flow (optional — you likely already have your own file/patient management)

If you want the exact same "name it, then it lands in `filesDir/saved/` plus a best-effort copy to the device's `Music/` folder" behavior:

```xml
<!-- res/xml/taal_file_paths.xml — required if you also want to Share saved files -->
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path name="taal_saved_recordings" path="saved/" />
</paths>
```

```xml
<!-- AndroidManifest.xml — add inside <application>, only if you want Share via FileProvider -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.yourapp.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/taal_file_paths" />
</provider>
```

```kotlin
// Core save logic — rename temp files into permanent storage, then best-effort
// copy to Music/ so the files show up in the device's file manager / other apps.
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

fun saveRecording(context: android.content.Context, filteredTempPath: String, rawTempPath: String, filterName: String, userGivenName: String): String? {
    val safeName = userGivenName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    val fullSaveName = "${filterName}_${safeName}"   // filename prefix encodes the filter — no separate metadata file needed
    val savedDir = File(context.filesDir, "saved").also { it.mkdirs() }

    fun moveFile(src: File, dst: File) {
        if (!src.exists()) return
        if (!src.renameTo(dst)) { src.copyTo(dst, overwrite = true); src.delete() }
    }

    val filteredDst = File(savedDir, "${fullSaveName}_filtered.wav")
    moveFile(File(filteredTempPath), filteredDst)
    moveFile(File(rawTempPath), File(savedDir, "${fullSaveName}_raw.wav"))
    if (!filteredDst.exists()) return null

    // Best-effort copy to Music/ — never blocks the save, failures are silent.
    try {
        for (source in listOf(filteredDst, File(savedDir, "${fullSaveName}_raw.wav"))) {
            if (!source.exists()) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Your App Saved Audios")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: continue
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                // API 24–28 needs WRITE_EXTERNAL_STORAGE, requested at runtime — see original
                // SaveRecordingFragment.kt for the full permission-launcher pattern.
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Your App Saved Audios")
                folder.mkdirs()
                source.copyTo(File(folder, source.name), overwrite = true)
            }
        }
    } catch (_: Exception) { /* non-fatal — internal save already succeeded */ }

    return filteredDst.absolutePath
}
```

**Filename convention — load-bearing, keep it if you reuse any of the list/player code above:** `{FILTER}_{name}_filtered.wav` / `{FILTER}_{name}_raw.wav`. The filter is read back out of this prefix everywhere (list icon, double-filter guard) — there's no separate metadata sidecar file.

---

### 7. Saved recordings list (optional reference — RecyclerView + Play/Share/Delete)

```xml
<!-- res/layout/item_saved_recording.xml -->
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent" android:layout_height="wrap_content"
    android:layout_marginStart="16dp" android:layout_marginEnd="16dp"
    android:layout_marginTop="6dp" android:layout_marginBottom="6dp"
    app:cardBackgroundColor="@color/white" app:cardCornerRadius="12dp" app:cardElevation="2dp">

    <androidx.constraintlayout.widget.ConstraintLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:padding="16dp">
        <androidx.cardview.widget.CardView android:id="@+id/filterIconBg" android:layout_width="44dp" android:layout_height="44dp"
            app:cardBackgroundColor="#EAF6F6" app:cardCornerRadius="22dp" app:cardElevation="0dp"
            app:layout_constraintBottom_toBottomOf="parent" app:layout_constraintStart_toStartOf="parent" app:layout_constraintTop_toTopOf="parent">
            <ImageView android:id="@+id/filterIcon" android:layout_width="24dp" android:layout_height="24dp"
                android:layout_gravity="center" android:src="@drawable/ic_heart" app:tint="@color/teal_primary" />
        </androidx.cardview.widget.CardView>

        <TextView android:id="@+id/fileName" android:layout_width="0dp" android:layout_height="wrap_content"
            android:layout_marginStart="12dp" android:layout_marginEnd="8dp" android:ellipsize="end" android:maxLines="1"
            android:textColor="@color/text_primary" android:textSize="14sp" android:textStyle="bold"
            app:layout_constraintEnd_toStartOf="@id/playButton" app:layout_constraintStart_toEndOf="@id/filterIconBg" app:layout_constraintTop_toTopOf="@id/filterIconBg" />

        <TextView android:id="@+id/fileMeta" android:layout_width="0dp" android:layout_height="wrap_content"
            android:layout_marginStart="12dp" android:layout_marginEnd="8dp" android:layout_marginTop="4dp"
            android:textColor="@color/text_secondary" android:textSize="12sp"
            app:layout_constraintEnd_toStartOf="@id/playButton" app:layout_constraintStart_toEndOf="@id/filterIconBg" app:layout_constraintTop_toBottomOf="@id/fileName" />

        <ImageButton android:id="@+id/playButton" android:layout_width="36dp" android:layout_height="36dp"
            android:background="?attr/selectableItemBackgroundBorderless" android:padding="4dp" android:scaleType="centerInside" android:src="@drawable/ic_play_circle"
            app:layout_constraintBottom_toBottomOf="parent" app:layout_constraintEnd_toStartOf="@id/shareButton" app:layout_constraintTop_toTopOf="parent" app:tint="@color/teal_primary" />

        <ImageButton android:id="@+id/shareButton" android:layout_width="36dp" android:layout_height="36dp" android:layout_marginStart="2dp"
            android:background="?attr/selectableItemBackgroundBorderless" android:padding="6dp" android:scaleType="centerInside" android:src="@drawable/ic_share"
            app:layout_constraintBottom_toBottomOf="parent" app:layout_constraintEnd_toStartOf="@id/deleteButton" app:layout_constraintTop_toTopOf="parent" app:tint="#128CB2" />

        <ImageButton android:id="@+id/deleteButton" android:layout_width="36dp" android:layout_height="36dp" android:layout_marginStart="2dp"
            android:background="?attr/selectableItemBackgroundBorderless" android:padding="6dp" android:scaleType="centerInside" android:src="@drawable/ic_trash"
            app:layout_constraintBottom_toBottomOf="parent" app:layout_constraintEnd_toEndOf="parent" app:layout_constraintTop_toTopOf="parent" app:tint="#E53935" />
    </androidx.constraintlayout.widget.ConstraintLayout>
</com.google.android.material.card.MaterialCardView>
```

```kotlin
package your.app.package.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedRecordingAdapter(
    private val files: List<File>,
    private val onPlay: (File) -> Unit,
    private val onShare: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<SavedRecordingAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemSavedRecordingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemSavedRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        val b = holder.binding

        // Filename format: "{FILTER}_{userInput}_filtered.wav"
        val baseName = file.nameWithoutExtension
        val filterName = extractFilter(baseName)
        b.fileName.text = baseName.removePrefix("${filterName}_").removeSuffix("_filtered")

        val durationSecs = getWavDuration(file)
        b.fileMeta.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60) +
            "  •  " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(file.lastModified()))

        b.filterIcon.setImageResource(when (filterName) {
            "LUNGS" -> R.drawable.ic_lungs
            "BOWEL" -> R.drawable.ic_bowel
            "PREGNANCY" -> R.drawable.ic_pregnancy
            "FULL_BODY" -> R.drawable.ic_accessibility
            "CUSTOM" -> R.drawable.ic_custom_filter
            else -> R.drawable.ic_heart
        })

        b.root.setOnClickListener { onPlay(file) }
        b.playButton.setOnClickListener { onPlay(file) }
        b.shareButton.setOnClickListener { onShare(file) }
        b.deleteButton.setOnClickListener { onDelete(file) }
    }

    override fun getItemCount() = files.size

    private fun extractFilter(baseName: String): String {
        val known = listOf("FULL_BODY", "PREGNANCY", "CUSTOM", "LUNGS", "BOWEL", "HEART")
        return known.firstOrNull { baseName.startsWith("${it}_") } ?: "HEART"
    }

    private fun getWavDuration(file: File): Int {
        return try {
            val dataSize = file.length() - 44
            if (dataSize <= 0) 0 else (dataSize / 2 / 44100).toInt()
        } catch (_: Exception) { 0 }
    }
}
```

**Sharing a saved file** (needs the FileProvider from §6):

```kotlin
val uri = androidx.core.content.FileProvider.getUriForFile(
    requireContext(), "${requireContext().packageName}.yourapp.fileprovider", file
)
val intent = Intent(Intent.ACTION_SEND).apply {
    type = "audio/wav"
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
startActivity(Intent.createChooser(intent, file.nameWithoutExtension))
```

---

### 8. Portable utilities — copy the whole file, zero adaptation needed

These two have no dependency on `taal-core` or our Activities at all — copy the files as-is into any package.

**`HeartBpmCalculator.kt`** — autocorrelation-based BPM estimate, already wired into §3.3's `onProgressUpdate`:

```kotlin
package your.app.package.dsp

import kotlin.math.*

class HeartBpmCalculator(private val sampleRate: Int = 44100) {
    private val analysisRate = 1000
    private val downsampleFactor = sampleRate / analysisRate
    private val bufferDurationSec = 4.0
    private val bufferSize = (analysisRate * bufferDurationSec).toInt()
    private val audioBuffer = FloatArray(bufferSize)
    private var writePos = 0
    private var samplesCollected = 0
    private var dsAccum = 0f
    private var dsCount = 0

    @Volatile var lastBpm = 0
        private set
    @Volatile private var lastUpdateTime = 0L
    @Volatile private var computing = false

    fun addSamples(data: FloatArray): Boolean {
        for (sample in data) {
            dsAccum += abs(sample)
            dsCount++
            if (dsCount >= downsampleFactor) {
                audioBuffer[writePos] = dsAccum / dsCount
                writePos = (writePos + 1) % bufferSize
                samplesCollected++
                dsAccum = 0f; dsCount = 0
            }
        }
        val now = System.currentTimeMillis()
        return !computing && now - lastUpdateTime >= 5000L && samplesCollected >= analysisRate * 3
    }

    fun computeBpm(): Int {
        if (computing) return lastBpm
        computing = true
        lastUpdateTime = System.currentTimeMillis()
        try {
            val length = minOf(samplesCollected, bufferSize)
            val analysis = FloatArray(length)
            val startPos = (writePos - length + bufferSize) % bufferSize
            for (i in 0 until length) analysis[i] = audioBuffer[(startPos + i) % bufferSize]
            val bpm = calculateBpm(analysis)
            if (bpm > 0) lastBpm = bpm
            return lastBpm
        } catch (_: Exception) { return lastBpm }
        finally { computing = false }
    }

    private fun calculateBpm(envelope: FloatArray): Int {
        if (envelope.size < analysisRate * 2) return 0
        val maxVal = envelope.max()
        if (maxVal < 0.0001f) return 0
        val normalized = FloatArray(envelope.size) { envelope[it] / maxVal }

        val windowSize = 30
        val smoothed = FloatArray(normalized.size)
        var runningSum = 0f
        for (i in 0 until minOf(windowSize, normalized.size)) runningSum += normalized[i]
        for (i in normalized.indices) {
            val wEnd = i + windowSize
            if (wEnd < normalized.size) runningSum += normalized[wEnd]
            if (i > 0 && i - 1 < normalized.size) runningSum -= normalized[i - 1]
            val cnt = minOf(windowSize, normalized.size - i)
            smoothed[i] = if (cnt > 0) runningSum / cnt else 0f
        }

        val minLag = (0.4 * analysisRate).toInt()
        val maxLag = (1.5 * analysisRate).toInt()
        val n = smoothed.size
        if (maxLag >= n) return 0

        var maxCorr = -1f
        var bestLag = 0
        for (lag in minLag..minOf(maxLag, n - 1)) {
            var sum = 0f
            val limit = n - lag
            for (t in 0 until limit) sum += smoothed[t] * smoothed[t + lag]
            val corr = sum / limit
            if (corr > maxCorr) { maxCorr = corr; bestLag = lag }
        }

        var autoZero = 0f
        for (t in smoothed.indices) autoZero += smoothed[t] * smoothed[t]
        autoZero /= n
        val normalizedCorr = if (autoZero > 0) maxCorr / autoZero else 0f
        if (normalizedCorr < 0.3f) return 0
        if (bestLag == 0) return 0

        val period = bestLag.toFloat() / analysisRate
        val bpm = (60f / period).roundToInt()
        return if (bpm in 40..200) bpm else 0
    }

    fun reset() {
        audioBuffer.fill(0f)
        writePos = 0; samplesCollected = 0; lastBpm = 0
        lastUpdateTime = 0L; dsAccum = 0f; dsCount = 0; computing = false
    }
}
```

**`WavCropper.kt`** — duration/waveform-preview/trim utility for saved files (useful if you add a "trim before save" step):

```kotlin
package your.app.package.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavCropper {
    private const val WAV_HEADER_SIZE = 44
    private const val SAMPLE_RATE = 44100
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    fun getDurationSeconds(filePath: String): Float {
        val file = File(filePath)
        if (!file.exists()) return 0f
        val dataSize = file.length() - WAV_HEADER_SIZE
        return (dataSize / BYTES_PER_SAMPLE).toFloat() / SAMPLE_RATE
    }

    fun getWaveformData(filePath: String, maxPoints: Int = 1000): FloatArray {
        val file = File(filePath)
        if (!file.exists()) return FloatArray(0)
        val dataSize = (file.length() - WAV_HEADER_SIZE).toInt()
        val totalSamples = dataSize / BYTES_PER_SAMPLE
        if (totalSamples <= 0) return FloatArray(0)

        val step = maxOf(1, totalSamples / maxPoints)
        val result = FloatArray(totalSamples / step)

        FileInputStream(file).use { fis ->
            fis.skip(WAV_HEADER_SIZE.toLong())
            val buffer = ByteArray(BYTES_PER_SAMPLE)
            var sampleIndex = 0
            var outIndex = 0
            while (outIndex < result.size) {
                val read = fis.read(buffer)
                if (read < BYTES_PER_SAMPLE) break
                if (sampleIndex % step == 0 && outIndex < result.size) {
                    val sample = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).short
                    result[outIndex] = sample.toFloat() / 32768f
                    outIndex++
                }
                sampleIndex++
                if (step > 1 && sampleIndex % step != 0) {
                    val skip = ((step - (sampleIndex % step)) * BYTES_PER_SAMPLE).toLong()
                    fis.skip(skip)
                    sampleIndex += (skip / BYTES_PER_SAMPLE).toInt()
                }
            }
        }
        return result
    }

    fun cropWav(inputPath: String, outputPath: String, startSeconds: Float, endSeconds: Float): Boolean {
        try {
            val inputFile = File(inputPath)
            if (!inputFile.exists()) return false
            val startSample = (startSeconds * SAMPLE_RATE).toInt()
            val endSample = (endSeconds * SAMPLE_RATE).toInt()
            val numSamples = endSample - startSample
            if (numSamples <= 0) return false

            val dataSize = numSamples * BYTES_PER_SAMPLE
            val startByte = WAV_HEADER_SIZE + (startSample * BYTES_PER_SAMPLE)

            FileInputStream(inputFile).use { fis ->
                FileOutputStream(outputPath).use { fos ->
                    writeWavHeader(fos, dataSize)
                    fis.skip(startByte.toLong())
                    val buffer = ByteArray(8192)
                    var remaining = dataSize
                    while (remaining > 0) {
                        val read = fis.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            return true
        } catch (e: Exception) { e.printStackTrace(); return false }
    }

    private fun writeWavHeader(fos: FileOutputStream, dataSize: Int) {
        val totalSize = dataSize + 36
        val byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
        val blockAlign = CHANNELS * BYTES_PER_SAMPLE
        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(totalSize); header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()); header.putInt(16); header.putShort(1)
        header.putShort(CHANNELS.toShort()); header.putInt(SAMPLE_RATE); header.putInt(byteRate)
        header.putShort(blockAlign.toShort()); header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray()); header.putInt(dataSize)
        fos.write(header.array())
    }
}
```

---

### 9. Full file checklist

| Piece | Section | Depends on taal-core? |
|---|---|---|
| Recording screen layout + Fragment | §3 | Yes — `TaalRecorder` |
| RecordingViewModel / RecordingUiState | §3.2 | No |
| Filter placement dialog | §4 | No (just your bundled images) |
| Player screen layout + Fragment | §5 | Yes — `TaalPlayer` |
| Save flow | §6 | No |
| Saved recordings list | §7 | No |
| HeartBpmCalculator | §8 | No |
| WavCropper | §8 | No |
| USB connection indicator | §3.3 end | Yes — `TaalConnectionBroadcastReceiver`, `SurrUtils` |

Every other visual detail (colors, spacing, card corner radii, slider colors) is inline in the XML above exactly as shipped — change any of it freely, none of it is load-bearing for `taal-core` to function correctly.
