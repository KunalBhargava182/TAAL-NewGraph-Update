# TAAL Stemz SDK 1.0.0 — Test Integration Guide

**Purpose:** build a small Android test app that integrates the two stemz SDK files, using
**only this guide**, and check that every feature works on a real phone with the TAAL
stethoscope. Anything this guide gets wrong or leaves out will be fixed before the official SDK
and integration guide go to the stemz team — so how you follow it matters as much as the result.

| SDK file | What it is |
|---|---|
| `taal-stemz-core.aar` | Audio engine, no screens: recorder/player, Lite/Hard/Custom filters, auto-stop, PcgScale graph maths, Clean Graph filter, BPM, heart-sound analysis |
| `taal-stemz-ui-kit.aar` | Ready-made screens on top of the core: recorder, review, save, saved list (share .wav + PDF), heart-sound analysis report |

The test app has **three buttons**:
1. **Open UI Kit** — launches the ready-made screens from `taal-stemz-ui-kit`.
2. **Core only — our own screen** — a screen we build ourselves using only `taal-stemz-core`
   (own layout, own graph view drawn with the SDK's PcgScale maths, hear-while-recording, and our
   own heart-sound analysis display). No UI-kit screens.
3. **Core only — Recorder + Player, UI-kit look** — our own copy of the UI kit's two main
   screens (the TAAL Recorder and the Review/Player with Save and Analyze), built only on
   `taal-stemz-core` with our own layouts, icons and code. This shows a client can build the
   full UI-kit experience themselves from the core SDK.

---

## 0. Ground rules — read before you start

1. **Do not open, unzip, inspect or decompile the `.aar` files** — not with Android Studio's
   "Analyze APK/AAR", not with 7-Zip/WinRAR, not with jadx, JD-GUI, `javap`, apktool or any
   other tool, and not by reading the files Gradle extracts into its caches. The stemz team will
   not do that either; everything needed must come from this guide.
2. **Use only what this guide gives you.** Do not copy code from other projects, the internet
   or the SDK's source repository, and do not add dependencies, Gradle settings or code that
   the guide does not list.
3. **Do not change the versions** in the files below, even if Android Studio suggests
   "upgrade" (AGP, Kotlin, Gradle, libraries). Dismiss those prompts.
4. **If something is missing, unclear or fails — STOP and ask.** Do not work around it.
   Write down: the step number, what you did, and the **exact** error text (copy it, don't
   summarise), then ask the SDK owner. They will give you what's needed from the SDK project
   and the guide will be corrected. A workaround you invent yourself hides exactly the kind of
   problem this test exists to find.
5. Paste every file **exactly** as written — including comments. Where a step says "replace
   the whole file", delete everything in it first.

---

## 1. What you need

| Item | Requirement |
|---|---|
| Android Studio | Hedgehog (2023.1) or newer, with Android SDK Platform **34** installed (SDK Manager → SDK Platforms → Android 14.0 "UpsideDownCake") |
| JDK | **JDK 17 or 21** for Gradle (**not** 22 or newer — this guide's Gradle 8.10.2 cannot run on JDK 24/25). Newer Android Studio versions select JDK 25 by default; Step 4.7 shows how to switch |
| Internet | Needed on first build (Gradle downloads the libraries listed in Step 4) |
| Phone | Android **8.0 (API 26) or newer**, 64-bit or 32-bit ARM (any normal phone/tablet — emulators are **not** supported), with **USB OTG** |
| TAAL device | Charged TAAL stethoscope + OTG adapter (USB-A → USB-C/Micro-USB, as your phone needs) |
| SDK files | `taal-stemz-core.aar` and `taal-stemz-ui-kit.aar` (given to you with this guide) |

---

## 2. Create the project

1. Android Studio → **File → New → New Project… → Empty Views Activity** → Next.
   (Choose *Empty **Views** Activity*, not *Empty Activity* — the latter is Jetpack Compose.)
2. Fill in exactly:
   - **Name:** `StemzSdkTest`
   - **Package name:** `com.example.stemzsdktest`
   - **Language:** Kotlin
   - **Minimum SDK:** API 26 ("Oreo", Android 8.0)
   - **Build configuration language:** Kotlin DSL (build.gradle.kts)
3. Click **Finish** and wait for the first sync to end (errors in this first sync are fine —
   the next steps replace the build files).
4. Switch the Project panel (top-left dropdown) from **Android** to **Project** view, so you
   see the real folders.

---

## 3. Add the two SDK files

1. In the Project view, right-click the **`app`** folder → **New → Directory** → name it `libs`.
2. Copy both files into it so you have exactly:
   ```
   StemzSdkTest/app/libs/taal-stemz-core.aar
   StemzSdkTest/app/libs/taal-stemz-ui-kit.aar
   ```
   The file names must match exactly (rename them if they arrived as `…-release.aar`).
3. Do **not** open them (Ground rule 1).

---

## 4. Gradle files

Replace the **whole content** of each file below. If Android Studio created a
`gradle/libs.versions.toml` file, leave it alone — the files below don't use it.

**Delete `StemzSdkTest/gradle/gradle-daemon-jvm.properties` if it exists.** Newer Android Studio
templates create it and pin Gradle to JDK 25, which the Gradle version below (8.10.2) cannot run
on — the sync then fails with *"Incompatible Gradle JVM version … Gradle 8.10.2 supports Java
versions between 1.8 and 23"*. Do **not** use the "Apply compatible Daemon JVM criteria" button
that error offers.

### 4.1 `StemzSdkTest/settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Required: MPAndroidChart (used by taal-stemz-ui-kit) is only published on JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "StemzSdkTest"
include(":app")
```

### 4.2 `StemzSdkTest/build.gradle.kts` (the project-level one, next to `settings.gradle.kts`)

```kotlin
plugins {
    id("com.android.application") version "8.8.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
}
```

### 4.3 `StemzSdkTest/gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

### 4.4 `StemzSdkTest/gradle/wrapper/gradle-wrapper.properties`

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

### 4.5 `StemzSdkTest/app/build.gradle.kts`

Why each block is there:
- **`minSdk = 26`, `compileSdk = 34`** — the SDKs require them.
- **`abiFilters`** — heart-sound analysis uses native code built for ARM phones only.
- **Java 17** — the SDKs are compiled for Java 17.
- **`viewBinding = true`** — the UI-kit screens need the View Binding runtime.
- **Dependencies** — an `.aar` file cannot bring its own dependencies with it, so every library
  the two SDKs use is listed here explicitly.

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.stemzsdktest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.stemzsdktest"
        minSdk = 26          // Required: the SDKs need Android 8.0+ (API 26)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Heart-sound analysis ships native libraries. Only these two phone CPU types are
        // supported by the SDK; this also keeps the APK size down.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            // Optional. The SDKs ship their own R8 rules, so minification works with no extra
            // configuration — turn this on to check that before release.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // Required: the SDKs are compiled for Java 17.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // Required: taal-stemz-ui-kit's screens use View Binding at runtime.
        viewBinding = true
    }
}

dependencies {
    // ---- The two SDK files (copied into app/libs/) ----
    implementation(files("libs/taal-stemz-core.aar"))
    implementation(files("libs/taal-stemz-ui-kit.aar"))

    // ---- Required by taal-stemz-core ----
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0") // heart-sound analysis

    // ---- Required by taal-stemz-ui-kit ----
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0") // from JitPack
}
```

### 4.6 `StemzSdkTest/app/proguard-rules.pro`

```properties
# No SDK-specific rules are needed: taal-stemz-core.aar and taal-stemz-ui-kit.aar ship their own.
```

### 4.7 Choose the Gradle JDK, then sync

1. **Settings** (File → Settings, or Android Studio → Settings on macOS) → **Build, Execution,
   Deployment → Build Tools → Gradle** → **Gradle JDK**.
2. Pick a **17** or **21** JDK from the list (e.g. "jbr-21", "jbr-17", "corretto-17",
   "temurin-21"). If none is listed: choose **Download JDK…** → Version **17** → any vendor
   (e.g. JetBrains Runtime or Eclipse Temurin) → Download, then select it.
3. OK.

Then click **File → Sync Project with Gradle Files**. It must end with **BUILD SUCCESSFUL / sync
finished** and no red errors. If it fails → **STOP** (Ground rule 4).

---

## 5. App files

**First delete these template folders** (whichever exist):
- `app/src/test/` and `app/src/androidTest/` — the template's example tests need JUnit/Espresso
  libraries this test app doesn't use, and would fail the build.
- `app/src/main/res/values-night/` — it references the template's old theme.
- `app/src/main/keepRules/` — created by newer templates, not used by this app.

(If an AI coding tool is doing these steps, its permission settings may block deleting folders —
then delete them yourself in the file explorer or Android Studio.)

Then delete anything Android Studio generated that is **not** listed below under `app/src/main/`
(e.g. the template's `MainActivity.kt` and `res/values/colors.xml` get replaced;
`res/values/strings.xml`, `res/xml/`, `res/mipmap-*` and the template's own files in
`res/drawable/` may stay — they're unused but harmless).

### 5.1 `app/src/main/AndroidManifest.xml`

Note: you do **not** declare the SDK screens, permissions or FileProvider — they come in
automatically from the two `.aar` files.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- RECORD_AUDIO, the USB-host feature, the UI kit's screens and its FileProvider are
         merged in automatically from the two SDK files — nothing SDK-related to declare here. -->

    <!-- Button 3's player copies saved recordings to Music/Stemz Recordings. Only Android 8/9
         need this permission for that; Android 10+ need none. -->
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />

    <application
        android:allowBackup="true"
        android:label="Stemz SDK Test"
        android:supportsRtl="true"
        android:theme="@style/Theme.StemzSdkTest">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".CoreDemoActivity"
            android:exported="false"
            android:label="Core SDK — own screen"
            android:screenOrientation="portrait" />

        <!-- Button 3: our own Recorder + Player, UI-kit look, core only. -->
        <activity
            android:name=".CoreRecorderActivity"
            android:exported="false"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.StemzSdkTest.Taal" />

        <activity
            android:name=".CorePlayerActivity"
            android:exported="false"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.StemzSdkTest.Taal" />

    </application>

</manifest>
```

### 5.2 `app/src/main/res/values/themes.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.StemzSdkTest" parent="Theme.MaterialComponents.Light.DarkActionBar" />

    <!-- Button 3 screens (CoreRecorderActivity / CorePlayerActivity): our own copy of the
         UI kit's look — no action bar, white status bar, teal accents. -->
    <style name="Theme.StemzSdkTest.Taal" parent="Theme.MaterialComponents.Light.NoActionBar">
        <item name="colorPrimary">@color/taal_teal</item>
        <item name="colorPrimaryDark">@color/taal_teal_dark</item>
        <item name="colorAccent">@color/taal_blue</item>
        <item name="colorOnPrimary">@color/taal_white</item>
        <item name="android:windowBackground">@color/taal_white</item>
        <item name="android:statusBarColor">@color/taal_white</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:textColorPrimary">@color/taal_text_primary</item>
        <item name="android:textColorSecondary">@color/taal_text_secondary</item>
    </style>

    <!-- The teal monospace timer at the top of both screens. -->
    <style name="TaalTimerText" parent="android:Widget.TextView">
        <item name="android:textSize">15sp</item>
        <item name="android:textColor">@color/taal_teal</item>
        <item name="android:fontFamily">monospace</item>
    </style>
</resources>
```

### 5.3 `app/src/main/res/layout/activity_main.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="24dp">

    <Button
        android:id="@+id/openUiKitButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="1. Open UI Kit (taal-stemz-ui-kit)" />

    <Button
        android:id="@+id/openCoreDemoButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="2. Core only — our own screen (taal-stemz-core)" />

    <Button
        android:id="@+id/openCoreRecorderButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="3. Core only — Recorder + Player, UI-kit look (taal-stemz-core)" />

    <TextView
        android:id="@+id/resultText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:textIsSelectable="true"
        android:text="No recording saved yet." />

</LinearLayout>
```

### 5.4 `app/src/main/java/com/example/stemzsdktest/MainActivity.kt` — Button 1 (UI kit)

```kotlin
package com.example.stemzsdktest

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.stemzsdktest.databinding.ActivityMainBinding
import com.musediagnostics.taal.stemz.PreFilter
import com.musediagnostics.taal.stemz.uikit.TaalRecorderActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Button 1 result: RESULT_OK + the saved "_filtered.wav" path if the user saved anything.
    private val uiKitLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = result.data?.getStringExtra(TaalRecorderActivity.RESULT_FILE_PATH)
        binding.resultText.text = if (result.resultCode == RESULT_OK && path != null) {
            "UI kit saved:\n$path"
        } else {
            "UI kit closed without saving."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Button 1 — the ready-made screens from taal-stemz-ui-kit.
        binding.openUiKitButton.setOnClickListener {
            uiKitLauncher.launch(
                TaalRecorderActivity.getIntent(
                    context = this,
                    preFilter = PreFilter.LITE,          // LITE (default) or HARD
                    preAmplification = 10,               // dB, 0–30
                    autoStopSeconds = 15,                // 1–300
                    publicFolderName = "Stemz Recordings",
                    heartSoundAnalysis = true
                )
            )
        }

        // Button 2 — our own screen built only on taal-stemz-core.
        binding.openCoreDemoButton.setOnClickListener {
            startActivity(Intent(this, CoreDemoActivity::class.java))
        }

        // Button 3 — our own copy of the UI kit's Recorder + Player, built only on taal-stemz-core.
        binding.openCoreRecorderButton.setOnClickListener {
            startActivity(Intent(this, CoreRecorderActivity::class.java))
        }
    }
}
```

### 5.5 `app/src/main/java/com/example/stemzsdktest/PcgGraphView.kt` — our own PcgScale graph

This is how to draw the PcgScale graph yourself: the grid geometry comes from the core's
`PcgTimeScale` (1 large box = 1 s, 1 small box = 0.2 s at any screen width), the Y axis from
`PcgAmplitudeScale`. No UI-kit class is used.

```kotlin
package com.example.stemzsdktest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.musediagnostics.taal.stemz.graph.PcgAmplitudeScale
import com.musediagnostics.taal.stemz.graph.PcgTimeScale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Our OWN PcgScale graph, drawn on a plain Canvas using only taal-stemz-core maths:
 *  - [PcgTimeScale]      → time-true grid: 1 large box = 1 s, 1 small box = 0.2 s, always.
 *  - [PcgAmplitudeScale] → Y-axis half-range ("full scale") supplied by the caller.
 *
 * Two modes:
 *  - LIVE:   [clear] then [appendLive] per audio buffer; the view shows one 4-second page and
 *            jumps to the next page when the trace reaches the right edge.
 *  - REVIEW: [showRecording] with a whole file; the user drags left/right to scroll.
 *            There is no zoom on purpose, so box widths always mean the same time.
 *            [setHeartSoundMarkers] overlays S1/S2 positions from the heart-sound analysis.
 */
class PcgGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Seconds visible across the view's width (4 s = 4 large boxes). */
    private val visibleSeconds = PcgTimeScale.DEFAULT_VISIBLE_SECONDS

    private var timeScale: PcgTimeScale? = null

    // Samples currently shown. LIVE: only the current page. REVIEW: the whole recording.
    private var samples = FloatArray(0)
    private var sampleCount = 0
    private var sampleRate = 44100f

    /** Absolute recording time (s) of samples[0]. */
    private var bufferStartSec = 0f

    /** Absolute recording time (s) at the view's left edge. */
    private var scrollOffsetSec = 0f

    private var fullScale = PcgAmplitudeScale.DEFAULT_INITIAL_FULL_SCALE
    private var reviewMode = false
    private var reviewDurationSec = 0f
    private var lastTouchX = 0f

    // Heart-sound analysis markers, in seconds from the start of the recording.
    private var s1MarkersSec: List<Float> = emptyList()
    private var s2MarkersSec: List<Float> = emptyList()

    private val density = resources.displayMetrics.density
    private val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2B8B5"); strokeWidth = 0.7f * density
    }
    private val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0837F"); strokeWidth = 1.2f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999"); textSize = 10f * density; textAlign = Paint.Align.CENTER
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2D7DD2"); strokeWidth = 1.5f * density
    }
    private val s1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935"); strokeWidth = 2f * density; textSize = 11f * density
    }
    private val s2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#43A047"); strokeWidth = 2f * density; textSize = 11f * density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The ONE place pixels-per-second is chosen: from the width and the visible seconds.
        timeScale = PcgTimeScale.fromPlotWidth(w.toFloat(), visibleSeconds)
    }

    // ------------------------------------------------------------------ LIVE

    fun clear() {
        reviewMode = false
        s1MarkersSec = emptyList()
        s2MarkersSec = emptyList()
        samples = FloatArray(0)
        sampleCount = 0
        bufferStartSec = 0f
        scrollOffsetSec = 0f
        fullScale = PcgAmplitudeScale.DEFAULT_INITIAL_FULL_SCALE
        invalidate()
    }

    /** Append one buffer of (display-conditioned) samples. Call on the main thread. */
    fun appendLive(data: FloatArray, rate: Float, newFullScale: Float) {
        sampleRate = rate
        fullScale = newFullScale
        ensureCapacity(sampleCount + data.size)
        System.arraycopy(data, 0, samples, sampleCount, data.size)
        sampleCount += data.size

        // Page snap: when the trace passes the right edge, start a new page and forget old samples.
        val latestSec = bufferStartSec + sampleCount / sampleRate
        val pageStart = floor(latestSec / visibleSeconds) * visibleSeconds
        if (pageStart > scrollOffsetSec) {
            val keepFrom = ((pageStart - bufferStartSec) * sampleRate).toInt().coerceIn(0, sampleCount)
            System.arraycopy(samples, keepFrom, samples, 0, sampleCount - keepFrom)
            sampleCount -= keepFrom
            bufferStartSec = pageStart
            scrollOffsetSec = pageStart
        }
        invalidate()
    }

    // ------------------------------------------------------------------ REVIEW

    /** Show a whole recording, scrollable by dragging. [keepScroll] keeps the current position
     *  (used when only the Clean Graph version changes). */
    fun showRecording(all: FloatArray, rate: Float, newFullScale: Float, keepScroll: Boolean = false) {
        reviewMode = true
        samples = all
        sampleCount = all.size
        sampleRate = rate
        bufferStartSec = 0f
        if (!keepScroll) scrollOffsetSec = 0f
        fullScale = newFullScale
        reviewDurationSec = all.size / rate
        invalidate()
    }

    /** During playback: keep the playing position in the middle of the view (left-aligned at
     *  the start). Only moves the view; never changes how many seconds are visible. */
    fun followPlayback(timeSec: Float) {
        if (!reviewMode) return
        val maxOffset = (reviewDurationSec - visibleSeconds).coerceAtLeast(0f)
        scrollOffsetSec = (timeSec - visibleSeconds / 2f).coerceIn(0f, maxOffset)
        invalidate()
    }

    /** Back to the start of the recording. */
    fun scrollToStart() {
        scrollOffsetSec = 0f
        invalidate()
    }

    /** Mark S1 (red) and S2 (green) positions from the heart-sound analysis. */
    fun setHeartSoundMarkers(s1Sec: List<Float>, s2Sec: List<Float>) {
        s1MarkersSec = s1Sec
        s2MarkersSec = s2Sec
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scale = timeScale ?: return false
        if (!reviewMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dxSec = scale.pxToSeconds(lastTouchX - event.x)
                lastTouchX = event.x
                val maxOffset = (reviewDurationSec - visibleSeconds).coerceAtLeast(0f)
                scrollOffsetSec = (scrollOffsetSec + dxSec).coerceIn(0f, maxOffset)
                invalidate()
            }
        }
        return true
    }

    // ------------------------------------------------------------------ DRAW

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val scale = timeScale ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        drawGrid(canvas, scale, w, h)
        drawTrace(canvas, scale, w, h)
        drawMarkers(canvas, scale, w, h, s1MarkersSec, s1Paint, "S1")
        drawMarkers(canvas, scale, w, h, s2MarkersSec, s2Paint, "S2")
    }

    private fun drawMarkers(
        canvas: Canvas, scale: PcgTimeScale, w: Float, h: Float,
        timesSec: List<Float>, paint: Paint, label: String
    ) {
        for (t in timesSec) {
            val x = scale.secondsToPx(t - scrollOffsetSec)
            if (x < 0f || x > w) continue
            canvas.drawLine(x, 14f * density, x, h - 16f * density, paint)
            canvas.drawText(label, x + 2f * density, 12f * density, paint)
        }
    }

    private fun drawGrid(canvas: Canvas, scale: PcgTimeScale, w: Float, h: Float) {
        // Horizontal rows: same pitch as the time boxes so squares are square; centred on zero.
        val small = scale.smallSquarePx()
        val centerY = h / 2f
        var i = 0
        while (i * small <= centerY) {
            val paint = if (i % PcgTimeScale.SMALL_SQUARES_PER_LARGE_SQUARE == 0) majorPaint else minorPaint
            canvas.drawLine(0f, centerY - i * small, w, centerY - i * small, paint)
            if (i > 0) canvas.drawLine(0f, centerY + i * small, w, centerY + i * small, paint)
            i++
        }
        // Vertical ticks every 0.2 s (heavier + labelled every whole second), pinned to real time.
        var index = scale.firstTickIndexVisible(scrollOffsetSec)
        while (true) {
            val x = scale.tickPositionPx(index, scrollOffsetSec)
            if (x > w) break
            val major = scale.isMajorTick(index)
            canvas.drawLine(x, 0f, x, h, if (major) majorPaint else minorPaint)
            if (major) {
                val sec = scale.tickTimeSeconds(index).roundToInt()
                canvas.drawText("${sec}s", x, h - 4f * density, labelPaint)
            }
            index++
        }
    }

    private fun drawTrace(canvas: Canvas, scale: PcgTimeScale, w: Float, h: Float) {
        if (sampleCount == 0 || fullScale <= 0f) return
        val centerY = h / 2f
        // Min/max per pixel column, so short S1/S2 spikes are never skipped.
        val samplesPerPx = sampleRate / scale.pixelsPerSecond
        var x = 0
        while (x < w) {
            val tStart = scrollOffsetSec + scale.pxToSeconds(x.toFloat())
            val from = ((tStart - bufferStartSec) * sampleRate).toInt()
            val to = (from + samplesPerPx).toInt().coerceAtLeast(from + 1)
            if (from >= sampleCount) break
            if (to > 0) {
                var mn = Float.MAX_VALUE
                var mx = -Float.MAX_VALUE
                for (k in from.coerceAtLeast(0) until to.coerceAtMost(sampleCount)) {
                    val v = samples[k]
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                }
                if (mn <= mx) {
                    val yTop = centerY - (mx / fullScale) * centerY
                    val yBottom = centerY - (mn / fullScale) * centerY
                    canvas.drawLine(x.toFloat(), yTop, x.toFloat(), yBottom + 1f, tracePaint)
                }
            }
            x++
        }
    }

    private fun ensureCapacity(needed: Int) {
        if (samples.size >= needed) return
        samples = samples.copyOf(maxOf(needed, samples.size * 2, 44100))
    }
}
```

### 5.6 `app/src/main/res/layout/activity_core_demo.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:id="@+id/deviceStatusText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="TAAL device: checking…" />

        <!-- Filter: Lite / Hard / Custom -->
        <RadioGroup
            android:id="@+id/filterGroup"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:orientation="horizontal">

            <RadioButton
                android:id="@+id/filterLite"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:checked="true"
                android:text="Lite 20–250 Hz" />

            <RadioButton
                android:id="@+id/filterHard"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Hard 20–200 Hz" />

            <RadioButton
                android:id="@+id/filterCustom"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Custom" />
        </RadioGroup>

        <LinearLayout
            android:id="@+id/customRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:visibility="gone">

            <EditText
                android:id="@+id/customLowInput"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:hint="Low cut Hz"
                android:inputType="number"
                android:text="20" />

            <EditText
                android:id="@+id/customHighInput"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:hint="High cut Hz"
                android:inputType="number"
                android:text="400" />
        </LinearLayout>

        <TextView
            android:id="@+id/preAmpLabel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:text="Pre-amp: 10 dB" />

        <SeekBar
            android:id="@+id/preAmpSeekBar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:max="30"
            android:progress="10" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Auto-stop (s): " />

            <EditText
                android:id="@+id/autoStopInput"
                android:layout_width="80dp"
                android:layout_height="wrap_content"
                android:inputType="number"
                android:text="15" />
        </LinearLayout>

        <CheckBox
            android:id="@+id/monitorCheck"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:checked="true"
            android:text="Hear sound while recording (phone speaker / headphones)" />

        <Button
            android:id="@+id/recordButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="Start recording" />

        <TextView
            android:id="@+id/liveInfoText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="00:00 · -- BPM" />

        <!-- Our own PcgScale graph (plain Canvas + taal-stemz-core maths) -->
        <com.example.stemzsdktest.PcgGraphView
            android:id="@+id/graphView"
            android:layout_width="match_parent"
            android:layout_height="220dp"
            android:layout_marginTop="8dp" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center"
            android:text="1 large box = 1 s · 1 small box = 0.2 s"
            android:textSize="12sp" />

        <!-- Review controls (enabled after a recording) -->
        <CheckBox
            android:id="@+id/cleanGraphCheck"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:checked="true"
            android:enabled="false"
            android:text="Clean Graph (display only)" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">

            <Button
                android:id="@+id/playButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:enabled="false"
                android:text="Play" />

            <Button
                android:id="@+id/analyzeButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:layout_weight="1"
                android:enabled="false"
                android:text="Analyze heart sounds" />
        </LinearLayout>

        <TextView
            android:id="@+id/analysisText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:textIsSelectable="true" />

    </LinearLayout>
</ScrollView>
```

### 5.7 `app/src/main/java/com/example/stemzsdktest/CoreDemoActivity.kt` — Button 2 (core only)

```kotlin
package com.example.stemzsdktest

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.stemzsdktest.databinding.ActivityCoreDemoBinding
import com.musediagnostics.taal.stemz.PreFilter
import com.musediagnostics.taal.stemz.TaalDisconnectedException
import com.musediagnostics.taal.stemz.TaalPlayer
import com.musediagnostics.taal.stemz.TaalRecorder
import com.musediagnostics.taal.stemz.core.RecorderState
import com.musediagnostics.taal.stemz.dsp.HeartBpmCalculator
import com.musediagnostics.taal.stemz.dsp.PcgDisplayFilter
import com.musediagnostics.taal.stemz.dsp.PcgLiveDisplayFilter
import com.musediagnostics.taal.stemz.graph.PcgAmplitudeScale
import com.musediagnostics.taal.stemz.segmentation.SegmentationOutcome
import com.musediagnostics.taal.stemz.segmentation.TaalCardiacSegmentation
import com.musediagnostics.taal.stemz.segmentation.heartRateBpm
import com.musediagnostics.taal.stemz.segmentation.systolicIntervalsMs
import com.purnacardio.signal.pcg.SegmentationResult
import com.musediagnostics.taal.stemz.util.PcgWavDecoder
import com.musediagnostics.taal.stemz.utils.SurrUtils
import com.musediagnostics.taal.stemz.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

/**
 * Button 2: a recorder + review screen built ONLY on taal-stemz-core — no taal-stemz-ui-kit
 * classes. Shows how to use every core feature from your own UI:
 * TaalRecorder (Lite / Hard / Custom, pre-amp, auto-stop), live monitoring (hear the sound while
 * recording — Android AudioTrack fed from onProgressUpdate), live PcgScale graph (our PcgGraphView +
 * PcgTimeScale / PcgAmplitudeScale / PcgLiveDisplayFilter), live BPM (HeartBpmCalculator),
 * review with Clean Graph (PcgWavDecoder + PcgDisplayFilter), playback (TaalPlayer) and heart-sound
 * analysis (TaalCardiacSegmentation) drawn as S1/S2 markers on our own graph.
 */
class CoreDemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCoreDemoBinding

    private var recorder: TaalRecorder? = null
    private var player: TaalPlayer? = null
    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    // Live monitor: TaalRecorder has no built-in "listen" switch — you play the samples it hands
    // you in onProgressUpdate through a standard Android AudioTrack (the UI kit does the same).
    @Volatile private var monitorTrack: AudioTrack? = null
    @Volatile private var monitorEnabled = true

    // Live-graph helpers — new instances for every recording.
    private var liveRate = 44100f
    private var liveFilter = PcgLiveDisplayFilter(liveRate)
    private var liveScale = PcgAmplitudeScale(liveRate)
    private val bpmCalculator = HeartBpmCalculator()

    private var rawFile: File? = null
    private var filteredFile: File? = null
    private var preAmpDb = 10

    // Review data (filled after a recording): raw-decoded and Clean-Graph versions.
    private var reviewOriginal: FloatArray? = null
    private var reviewCleaned: FloatArray? = null
    private var reviewRate = 44100f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(this, "Microphone permission is required to record", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCoreDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.filterGroup.setOnCheckedChangeListener { _, checkedId ->
            binding.customRow.visibility = if (checkedId == binding.filterCustom.id) View.VISIBLE else View.GONE
        }
        binding.preAmpSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                preAmpDb = progress
                binding.preAmpLabel.text = "Pre-amp: $progress dB"
                recorder?.setPreAmplification(progress) // allowed while recording
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.recordButton.setOnClickListener {
            if (recorder?.getState() == RecorderState.RECORDING) {
                recorder?.stop()          // STOPPED arrives in onStateChange below
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startRecording()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.cleanGraphCheck.setOnCheckedChangeListener { _, _ -> showReviewGraph() }
        binding.playButton.setOnClickListener { togglePlayback() }
        binding.analyzeButton.setOnClickListener { analyzeHeartSounds() }
    }

    override fun onStart() {
        super.onStart()
        // Status right now (the receiver only reports plug/unplug events that happen later)…
        binding.deviceStatusText.text = "TAAL device: ${SurrUtils.isTaalDeviceConnected(this)}"
        // …and live plug/unplug updates.
        connectionReceiver = TaalConnectionBroadcastReceiver(object :
            TaalConnectionBroadcastReceiver.TaalConnectionListener {
            override fun onTaalConnect() { binding.deviceStatusText.text = "TAAL device: CONNECTED" }
            override fun onTaalDisconnect() { binding.deviceStatusText.text = "TAAL device: NOT_CONNECTED" }
        }).also { it.register(this) }
    }

    override fun onStop() {
        super.onStop()
        connectionReceiver?.unregister(this)
        connectionReceiver = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recorder?.stop() } catch (_: Exception) {}
        stopMonitor()
        stopPlayback()
    }

    // ================================================================== RECORD

    private fun startRecording() {
        stopPlayback()
        val dir = File(filesDir, "core_demo").apply { mkdirs() }
        val ts = System.currentTimeMillis()
        val raw = File(dir, "rec_${ts}_raw.wav")
        val filtered = File(dir, "rec_${ts}_filtered.wav")
        rawFile = raw
        filteredFile = filtered

        val autoStop = binding.autoStopInput.text.toString().toIntOrNull() ?: 15
        val newRecorder = TaalRecorder(this)
        try {
            newRecorder.setRawAudioFilePath(raw.absolutePath)
            newRecorder.setFilteredAudioFilePath(filtered.absolutePath)
            newRecorder.setRecordingTime(autoStop)          // auto-stop (1–300 s, default 15)
            newRecorder.setPreAmplification(preAmpDb)       // 0–30 dB
            when (binding.filterGroup.checkedRadioButtonId) {
                binding.filterHard.id -> newRecorder.setPreFilter(PreFilter.HARD)
                binding.filterCustom.id -> {
                    val low = binding.customLowInput.text.toString().toDoubleOrNull()
                    val high = binding.customHighInput.text.toString().toDoubleOrNull()
                    if (low == null || high == null || low <= 0.0 || low >= high) {
                        Toast.makeText(this, "Custom: enter Low < High, both above 0 Hz", Toast.LENGTH_LONG).show()
                        return
                    }
                    newRecorder.setCustomBandpass(low, high)
                }
                else -> newRecorder.setPreFilter(PreFilter.LITE)
            }
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        }

        newRecorder.onInfoListener = object : TaalRecorder.OnInfoListener {
            override fun onStateChange(state: RecorderState) {
                runOnUiThread {
                    when (state) {
                        RecorderState.RECORDING -> onRecordingStarted()
                        RecorderState.STOPPED -> onRecordingStopped()
                        else -> {}
                    }
                }
            }

            // Called on the SDK's audio thread with filtered, pre-amplified samples (-1..1).
            override fun onProgressUpdate(sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray) {
                // Live monitor: play exactly what is being recorded (filter + pre-amp applied).
                if (monitorEnabled) playToMonitor(data, sampleRate)

                // Some devices capture at 48 kHz, not 44.1 kHz: rebuild the rate-dependent helpers
                // with the real rate the SDK reports (happens on the first buffer only).
                if (sampleRate.toFloat() != liveRate) {
                    liveRate = sampleRate.toFloat()
                    liveFilter = PcgLiveDisplayFilter(liveRate)
                    liveScale = PcgAmplitudeScale(liveRate)
                }

                // BPM: feed every buffer; compute when the calculator says it has enough.
                val bpm = if (bpmCalculator.addSamples(data)) bpmCalculator.computeBpm() else 0

                // Graph: undo the pre-amp gain so the trace shows the true acoustic level
                // (the slider changes loudness, not the drawing), then apply the live
                // display filter and feed the Y-axis auto-scale.
                val gain = 10f.pow(preAmpDb / 20f)
                val display = liveFilter.process(FloatArray(data.size) { i -> data[i] / gain })
                liveScale.addSamples(display)
                val fullScale = liveScale.smoothedFullScale()

                runOnUiThread {
                    binding.graphView.appendLive(display, sampleRate.toFloat(), fullScale)
                    val secs = timeStamp.toInt()
                    val bpmText = if (bpm > 0) "$bpm BPM" else (binding.liveInfoText.tag as? String ?: "-- BPM")
                    binding.liveInfoText.tag = bpmText
                    binding.liveInfoText.text = String.format("%02d:%02d · %s", secs / 60, secs % 60, bpmText)
                }
            }

            override fun onDeviceDisconnected() {
                Toast.makeText(this@CoreDemoActivity, "TAAL device disconnected", Toast.LENGTH_LONG).show()
            }

            override fun onSilentRecordingDetected(isFirstSinceConnect: Boolean) {
                Toast.makeText(this@CoreDemoActivity, "No sound captured — check the stethoscope", Toast.LENGTH_LONG).show()
            }
        }

        // Fresh live helpers for this recording.
        liveRate = 44100f
        liveFilter = PcgLiveDisplayFilter(liveRate)
        liveScale = PcgAmplitudeScale(liveRate)
        bpmCalculator.reset()
        binding.liveInfoText.tag = null
        binding.graphView.clear()
        monitorEnabled = binding.monitorCheck.isChecked

        try {
            newRecorder.start()
            recorder = newRecorder
        } catch (e: TaalDisconnectedException) {
            Toast.makeText(this, "Connect the TAAL device first", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onRecordingStarted() {
        binding.recordButton.text = "Stop recording"
        binding.monitorCheck.isEnabled = false
        setReviewControlsEnabled(false)
        binding.analysisText.text = ""
    }

    /** Manual stop, auto-stop and unplug all end here. Both WAV files are finalized by the SDK. */
    private fun onRecordingStopped() {
        binding.recordButton.text = "Start recording"
        binding.monitorCheck.isEnabled = true
        recorder = null
        stopMonitor()
        val file = filteredFile ?: return
        lifecycleScope.launch {
            delay(300) // let the SDK finish flushing the files to disk
            loadReview(file)
        }
    }

    // ================================================================== LIVE MONITOR

    /** Called on the SDK's audio thread. Creates the AudioTrack on the first buffer, using the
     *  sample rate the SDK reports, then streams each buffer to the speaker/headphones. */
    private fun playToMonitor(data: FloatArray, sampleRate: Int) {
        val track = monitorTrack ?: createMonitorTrack(sampleRate).also { monitorTrack = it }
        val pcm = ShortArray(data.size) { i -> (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        // Blocking write (the default), same as the UI kit: every sample reaches the speaker.
        // Do NOT use WRITE_NON_BLOCKING — when the speaker buffer is full it drops the rest of the
        // buffer, and those gaps are heard as crackle/noise while recording (the file is fine).
        track.write(pcm, 0, pcm.size)
    }

    private fun createMonitorTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }

    private fun stopMonitor() {
        val track = monitorTrack ?: return
        monitorTrack = null
        try { track.stop() } catch (_: Exception) {}
        try { track.release() } catch (_: Exception) {}
    }

    // ================================================================== REVIEW

    /** Decode the filtered file once, compute the Clean Graph version once, then draw. */
    private suspend fun loadReview(file: File) {
        val decoded = withContext(Dispatchers.IO) { PcgWavDecoder.decode(file.readBytes()) }
        if (decoded == null) {
            Toast.makeText(this, "Recording too short to review", Toast.LENGTH_SHORT).show()
            return
        }
        reviewRate = decoded.sampleRate
        reviewOriginal = decoded.samples
        reviewCleaned = withContext(Dispatchers.Default) {
            PcgDisplayFilter.processOffline(decoded.samples, decoded.sampleRate)
        }
        showReviewGraph()
        setReviewControlsEnabled(true)
    }

    /** Clean Graph ON = PcgDisplayFilter output; OFF = the file as recorded. Display only. */
    private fun showReviewGraph() {
        val samples = (if (binding.cleanGraphCheck.isChecked) reviewCleaned else reviewOriginal) ?: return
        // Y axis computed once from the whole recording (typical peaks fill ~60% of half-height).
        val fullScale = PcgAmplitudeScale.computeFullScaleForFile(samples, reviewRate)
        binding.graphView.showRecording(samples, reviewRate, fullScale)
    }

    private fun setReviewControlsEnabled(enabled: Boolean) {
        binding.cleanGraphCheck.isEnabled = enabled
        binding.playButton.isEnabled = enabled
        binding.analyzeButton.isEnabled = enabled
    }

    // ================================================================== PLAYBACK

    private fun togglePlayback() {
        if (player != null) {
            stopPlayback()
            return
        }
        val file = filteredFile ?: return
        try {
            player = TaalPlayer(this).apply {
                setDataSource(file.absolutePath)
                // No setPreFilter(): a "_filtered.wav" is already filtered.
                onPlaybackComplete = { runOnUiThread { stopPlayback() } }
                prepare()
                start()
            }
            binding.playButton.text = "Stop"
        } catch (e: Exception) {
            Toast.makeText(this, "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        player?.let {
            it.onPlaybackComplete = null
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
        if (::binding.isInitialized) binding.playButton.text = "Play"
    }

    // ================================================================== ANALYSIS

    /** Heart-sound segmentation always runs on the RAW file. */
    private fun analyzeHeartSounds() {
        val raw = rawFile ?: return
        binding.analyzeButton.isEnabled = false
        binding.analysisText.text = "Analyzing…"
        lifecycleScope.launch {
            val outcome = TaalCardiacSegmentation(applicationContext).use { it.segmentRawWav(raw) }
            binding.analysisText.text = when (outcome) {
                is SegmentationOutcome.Ok -> describe(outcome.result, lowConfidence = false)
                is SegmentationOutcome.TooWeak -> describe(outcome.result, lowConfidence = true)
                SegmentationOutcome.NoHeartSounds -> "No heart sounds detected — please retake."
                SegmentationOutcome.Unavailable -> "Analysis unavailable on this device."
            }
            binding.analyzeButton.isEnabled = true
        }
    }

    /** Our own analysis "report": text + S1/S2 markers drawn on our graph. The analysis gives
     *  positions as sample indices at 2000 Hz, so seconds = index / 2000. */
    private fun describe(r: SegmentationResult, lowConfidence: Boolean): String {
        binding.graphView.setHeartSoundMarkers(
            s1Sec = r.s1PeakSamples2k.map { it / 2000f },
            s2Sec = r.s2PeakSamples2k.map { it / 2000f }
        )
        val systolic = r.systolicIntervalsMs
        return buildString {
            if (lowConfidence) append("LOW CONFIDENCE — few complete cycles, consider a retake.\n")
            append("Heart rate: ${r.heartRateBpm?.let { "%.0f bpm".format(it) } ?: "n/a (fewer than 2 beats)"}\n")
            append("Cardiac cycles: ${r.numCycles}\n")
            append("Duration: ${"%.1f".format(r.durationSec)} s\n")
            append("Avg S1 to S2 (systole): ${if (systolic.isEmpty()) "n/a" else "%.0f ms".format(systolic.average())}\n")
            append("S1 found: ${r.s1PeakSamples2k.size} · S2 found: ${r.s2PeakSamples2k.size}\n")
            append("Graph markers: red = S1, green = S2 (drag the graph to see them all).")
        }
    }
}
```

### 5.8 Button 3 resources — colors, color selectors and icons

Button 3 must not use anything from the UI kit — not its classes and not its resources — so it
brings its own colors and icons. Create each file below exactly as shown (all are plain Android
XML; the icons are vector drawables, no image files needed).

#### `app/src/main/res/values/colors.xml` (replaces the template's colors.xml)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="taal_teal">#2ABFBF</color>
    <color name="taal_teal_dark">#1FA3A3</color>
    <color name="taal_blue">#4A90D9</color>
    <color name="taal_accent">#128CB2</color>
    <color name="taal_white">#FFFFFF</color>
    <color name="taal_screen_bg">#F8F9FA</color>
    <color name="taal_card_stroke">#E0E0E0</color>
    <color name="taal_text_primary">#333333</color>
    <color name="taal_text_secondary">#999999</color>
</resources>
```

#### `app/src/main/res/color/filter_icon_selector.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:color="#FF0000" android:state_selected="true" />

    <item android:color="#757575" />
</selector>
```

#### `app/src/main/res/color/heart_filter_toggle_bg.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:color="#128CB2" android:state_checked="true" />
    <item android:color="@android:color/transparent" />
</selector>
```

#### `app/src/main/res/color/heart_filter_toggle_text.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:color="@android:color/white" android:state_checked="true" />
    <item android:color="#128CB2" />
</selector>
```

#### `app/src/main/res/drawable/bg_bottom_action_button.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#4A90D9" />
    <corners android:radius="8dp" />
</shape>
```

#### `app/src/main/res/drawable/bg_pill_off.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#E8E8E8" />
    <corners android:radius="6dp" />
    <stroke
        android:width="1dp"
        android:color="#C8C8C8" />
</shape>
```

#### `app/src/main/res/drawable/bg_pill_on.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#128CB2" />
    <corners android:radius="6dp" />
</shape>
```

#### `app/src/main/res/drawable/bg_record_button.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#E85555" />
    <size
        android:width="80dp"
        android:height="80dp" />
</shape>
```

#### `app/src/main/res/drawable/ic_analyze.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
  <path
      android:pathData="M121,680v-400q0,-33 23.5,-56.5T201,200h559q33,0 56.5,23.5T840,280v400q0,33 -23.5,56.5T760,760L201,760q-33,0 -56.5,-23.5T121,680ZM200,680h133v-400L200,280v400ZM413,680h133v-400L413,280v400ZM626,680h133v-400L626,280v400Z"
      android:fillColor="#FFFFFF"/>
</vector>
```

#### `app/src/main/res/drawable/ic_arrow_back.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#333333"
        android:pathData="M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"/>
</vector>
```

#### `app/src/main/res/drawable/ic_custom_filter.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="30dp"
    android:height="30dp"
    android:viewportWidth="30"
    android:viewportHeight="30">

    <!-- Top slider track -->
    <path
        android:fillColor="#B3B3B3"
        android:pathData="M2,9.5 L28,9.5 L28,11 L2,11 Z" />

    <!-- Top slider knob — left position (low cut) -->
    <path
        android:fillColor="#B3B3B3"
        android:pathData="M4,10.25 A4,4 0 1 0 12,10.25 A4,4 0 1 0 4,10.25 Z" />

    <!-- Bottom slider track -->
    <path
        android:fillColor="#B3B3B3"
        android:pathData="M2,19.5 L28,19.5 L28,21 L2,21 Z" />

    <!-- Bottom slider knob — right position (high cut) -->
    <path
        android:fillColor="#B3B3B3"
        android:pathData="M18,20.25 A4,4 0 1 0 26,20.25 A4,4 0 1 0 18,20.25 Z" />

</vector>
```

#### `app/src/main/res/drawable/ic_folder.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M10,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z" />
</vector>
```

#### `app/src/main/res/drawable/ic_heart.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="30dp"
    android:height="30dp"
    android:viewportWidth="30"
    android:viewportHeight="30">
    <path
        android:fillColor="#B3B3B3"
        android:pathData="M26.031,18.125c0,0 -3.688,4.49 -11.03,9.217c-7.342,-4.726 -11.03,-9.217 -11.03,-9.217c-0.612,-0.717 -1.144,-1.414 -1.613,-2.134c-0.292,-0.446 -0.544,-0.88 -0.764,-1.301c-3.036,-5.807 0.282,-9.23 0.282,-9.23c6.258,-6.817 13.125,0.948 13.125,0.948c0,0 6.867,-7.765 13.124,-0.948c-0.003,0 4.649,4.798 -2.094,12.665Z"/>
</vector>
```

#### `app/src/main/res/drawable/ic_info.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#2ABFBF"
        android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z" />
</vector>
```

#### `app/src/main/res/drawable/ic_play_circle.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#E85555"
        android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,16.5v-9l6,4.5 -6,4.5z" />
</vector>
```

#### `app/src/main/res/drawable/ic_record_start.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="40dp"
    android:height="40dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
  <path
      android:pathData="M382,653.33 L653.33,480 382,306.67v346.66ZM480,880q-82.33,0 -155.33,-31.5 -73,-31.5 -127.34,-85.83Q143,708.33 111.5,635.33T80,480q0,-83 31.5,-156t85.83,-127q54.34,-54 127.34,-85.5T480,80q83,0 156,31.5T763,197q54,54 85.5,127T880,480q0,82.33 -31.5,155.33 -31.5,73 -85.5,127.34Q709,817 636,848.5T480,880ZM480,813.33q139.33,0 236.33,-97.33t97,-236q0,-139.33 -97,-236.33t-236.33,-97q-138.67,0 -236,97 -97.33,97 -97.33,236.33 0,138.67 97.33,236 97.33,97.33 236,97.33ZM480,480Z"
      android:fillColor="#EA3323"/>
</vector>
```

#### `app/src/main/res/drawable/ic_record_stop.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
  <path
      android:pathData="M320,640h320v-320L320,320v320ZM480,880q-83,0 -156,-31.5T197,763q-54,-54 -85.5,-127T80,480q0,-83 31.5,-156T197,197q54,-54 127,-85.5T480,80q83,0 156,31.5T763,197q54,54 85.5,127T880,480q0,83 -31.5,156T763,763q-54,54 -127,85.5T480,880ZM480,800q134,0 227,-93t93,-227q0,-134 -93,-227t-227,-93q-134,0 -227,93t-93,227q0,134 93,227t227,93ZM480,480Z"
      android:fillColor="#EA3323"/>
</vector>
```

#### `app/src/main/res/drawable/ic_taal.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="35dp"
    android:height="24dp"
    android:viewportWidth="35"
    android:viewportHeight="24">
  <path
      android:pathData="M26.996,12.025C26.996,10.626 25.937,9.35 24.017,8.443C22.271,7.608 19.955,7.155 17.5,7.155C15.044,7.155 12.728,7.608 10.971,8.443C9.05,9.35 7.991,10.614 7.991,12.025V19.054C7.991,20.452 9.05,21.728 10.971,22.635C12.728,23.47 15.044,23.924 17.5,23.924C21.84,23.924 25.507,22.525 26.647,20.44C26.891,19.998 27.008,19.532 27.008,19.066L26.996,12.025ZM17.5,8.504C22.341,8.504 25.728,10.356 25.728,12.025C25.728,13.693 22.353,15.545 17.5,15.545C12.646,15.533 9.271,13.681 9.271,12.025C9.271,10.368 12.646,8.504 17.5,8.504ZM25.53,19.765C24.773,21.163 21.771,22.574 17.5,22.574C12.646,22.574 9.271,20.71 9.271,19.054V14.515C9.725,14.92 10.295,15.275 10.971,15.594C12.728,16.428 15.044,16.882 17.5,16.882C19.955,16.882 22.271,16.428 24.028,15.594C24.703,15.275 25.274,14.907 25.728,14.515V19.054C25.716,19.287 25.658,19.52 25.53,19.765Z"
      android:fillColor="#008DB9"/>
  <path
      android:pathData="M11.304,19.341C11.593,19.191 11.615,18.617 11.354,18.058C11.093,17.498 10.647,17.166 10.359,17.316C10.071,17.465 10.049,18.04 10.31,18.599C10.571,19.158 11.016,19.49 11.304,19.341Z"
      android:fillColor="#008DB9"/>
  <path
      android:pathData="M25.238,17.189C25.238,17.189 24.272,17.888 23.434,18.256C23.434,18.256 22.794,18.722 23.178,19.372C23.178,19.372 23.586,19.998 25.296,18.207C25.296,18.207 25.483,18.06 25.529,17.79C25.587,17.52 25.529,17.14 25.238,17.189Z"
      android:fillColor="#008DB9"/>
  <path
      android:pathData="M31,12.06L29.55,11.588C29.718,11.079 29.776,10.54 29.722,10.007C29.667,9.474 29.5,8.959 29.232,8.495C28.964,8.03 28.601,7.628 28.167,7.314C27.733,7 27.237,6.781 26.712,6.672L27.028,5.181C27.763,5.334 28.456,5.64 29.063,6.08C29.671,6.519 30.179,7.082 30.554,7.731C30.928,8.381 31.162,9.102 31.239,9.848C31.316,10.594 31.235,11.347 31,12.06Z"
      android:fillColor="#008DB9"/>
  <path
      android:pathData="M4.248,12.026L5.698,11.554C5.53,11.046 5.472,10.507 5.527,9.974C5.581,9.441 5.748,8.925 6.016,8.461C6.284,7.997 6.647,7.595 7.081,7.281C7.515,6.967 8.011,6.748 8.536,6.639L8.22,5.147C7.486,5.301 6.792,5.607 6.185,6.046C5.577,6.486 5.069,7.049 4.694,7.698C4.32,8.348 4.086,9.069 4.009,9.815C3.932,10.561 4.014,11.314 4.248,12.026Z"
      android:fillColor="#008DB9"/>
  <path
      android:pathData="M34.293,13.304L32.893,12.706C33.311,11.729 33.495,10.667 33.431,9.606C33.368,8.545 33.058,7.513 32.526,6.592C31.995,5.672 31.256,4.888 30.369,4.302C29.482,3.716 28.471,3.345 27.415,3.218L27.597,1.707C28.887,1.862 30.123,2.316 31.208,3.032C32.292,3.748 33.195,4.706 33.844,5.832C34.494,6.957 34.873,8.218 34.951,9.515C35.028,10.812 34.803,12.109 34.293,13.304Z"
      android:fillColor="#008DB9"/>
  <path
      android:pathData="M0.956,13.271L2.356,12.673C1.938,11.695 1.754,10.634 1.817,9.573C1.881,8.512 2.191,7.48 2.723,6.559C3.254,5.639 3.993,4.854 4.88,4.269C5.767,3.683 6.778,3.312 7.834,3.185L7.652,1.674C6.362,1.829 5.126,2.283 4.041,2.999C2.957,3.715 2.054,4.673 1.405,5.798C0.755,6.924 0.376,8.185 0.299,9.482C0.221,10.779 0.446,12.076 0.956,13.271Z"
      android:fillColor="#008DB9"/>
</vector>
```

#### `app/src/main/res/drawable/ic_volume_up.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#000000"
        android:pathData="M3,9v6h4l5,5V4L7,9H3zm13.5,3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-0.73,2.5-2.25,2.5-4.02zM14,3.23v2.06c2.89,0.86,5,3.54,5,6.71s-2.11,5.85-5,6.71v2.06c4.01-0.91,7-4.49,7-8.77s-2.99-7.86-7-8.77z"/>
</vector>
```

### 5.9 `app/src/main/res/layout/activity_core_recorder.xml` — Button 3, Recorder screen

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Button 3, screen 1: our own copy of the UI kit's "TAAL Recorder" screen, built on
     taal-stemz-core only. Top bar (placement info · title · Custom filter · TAAL device),
     timer, Heart Filter card (Lite / Hard), Custom range panel, pre-amp slider, scale caption,
     live PcgScale graph, BPM, round record button, Saved-recordings button. -->
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/taal_screen_bg">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/topBar"
        android:layout_width="match_parent"
        android:layout_height="40dp"
        android:background="@color/taal_white"
        android:elevation="2dp"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        app:layout_constraintTop_toTopOf="parent">

        <ImageButton
            android:id="@+id/infoButton"
            android:layout_width="26dp"
            android:layout_height="26dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Placement info"
            android:scaleType="centerInside"
            android:src="@drawable/ic_info"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent"
            app:tint="@color/taal_accent" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="TAAL Recorder"
            android:textColor="@color/taal_text_primary"
            android:textSize="14sp"
            android:textStyle="bold"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <ImageButton
            android:id="@+id/deviceIcon"
            android:layout_width="28dp"
            android:layout_height="28dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="TAAL device"
            android:src="@drawable/ic_taal"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent"
            app:tint="#333333" />

        <ImageButton
            android:id="@+id/filterCustomTop"
            android:layout_width="26dp"
            android:layout_height="26dp"
            android:layout_marginEnd="6dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Custom frequency filter"
            android:scaleType="centerInside"
            android:src="@drawable/ic_custom_filter"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toStartOf="@id/deviceIcon"
            app:layout_constraintTop_toTopOf="parent"
            app:tint="@color/filter_icon_selector" />
    </androidx.constraintlayout.widget.ConstraintLayout>

    <TextView
        android:id="@+id/timerText"
        style="@style/TaalTimerText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:text="00:00:00"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/topBar" />

    <!-- Heart Filter card: Lite (20–250 Hz) and Hard (20–200 Hz). -->
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/filterContainer"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="4dp"
        android:layout_marginEnd="16dp"
        app:cardBackgroundColor="@color/taal_white"
        app:cardCornerRadius="14dp"
        app:cardElevation="4dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/timerText"
        app:strokeColor="@color/taal_card_stroke"
        app:strokeWidth="1dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingStart="12dp"
            android:paddingTop="6dp"
            android:paddingEnd="12dp"
            android:paddingBottom="6dp">

            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginBottom="4dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <ImageView
                    android:layout_width="13dp"
                    android:layout_height="13dp"
                    android:layout_marginEnd="4dp"
                    android:contentDescription="@null"
                    android:src="@drawable/ic_heart"
                    app:tint="@color/taal_accent" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Heart Filter"
                    android:textColor="@color/taal_accent"
                    android:textSize="11sp"
                    android:textStyle="bold" />
            </LinearLayout>

            <com.google.android.material.button.MaterialButtonToggleGroup
                android:id="@+id/heartFilterToggle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                app:selectionRequired="true"
                app:singleSelection="true">

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/filterLite"
                    style="@style/Widget.MaterialComponents.Button.OutlinedButton"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:gravity="center"
                    android:insetTop="0dp"
                    android:insetBottom="0dp"
                    android:lineSpacingExtra="1dp"
                    android:minWidth="0dp"
                    android:minHeight="0dp"
                    android:paddingStart="14dp"
                    android:paddingTop="6dp"
                    android:paddingEnd="14dp"
                    android:paddingBottom="6dp"
                    android:text="Lite\n20–250 Hz"
                    android:textAllCaps="false"
                    android:textColor="@color/heart_filter_toggle_text"
                    android:textSize="10sp"
                    android:textStyle="bold"
                    app:backgroundTint="@color/heart_filter_toggle_bg"
                    app:cornerRadius="10dp"
                    app:strokeColor="@color/taal_accent"
                    app:strokeWidth="1.5dp" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/filterHard"
                    style="@style/Widget.MaterialComponents.Button.OutlinedButton"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:gravity="center"
                    android:insetTop="0dp"
                    android:insetBottom="0dp"
                    android:lineSpacingExtra="1dp"
                    android:minWidth="0dp"
                    android:minHeight="0dp"
                    android:paddingStart="14dp"
                    android:paddingTop="6dp"
                    android:paddingEnd="14dp"
                    android:paddingBottom="6dp"
                    android:text="Hard\n20–200 Hz"
                    android:textAllCaps="false"
                    android:textColor="@color/heart_filter_toggle_text"
                    android:textSize="10sp"
                    android:textStyle="bold"
                    app:backgroundTint="@color/heart_filter_toggle_bg"
                    app:cornerRadius="10dp"
                    app:strokeColor="@color/taal_accent"
                    app:strokeWidth="1.5dp" />
            </com.google.android.material.button.MaterialButtonToggleGroup>
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <!-- Custom frequency range — shown only when the Custom icon (top bar) is selected. -->
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/customRangePanel"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="6dp"
        android:layout_marginEnd="16dp"
        android:visibility="gone"
        app:cardBackgroundColor="@color/taal_white"
        app:cardCornerRadius="16dp"
        app:cardElevation="4dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/filterContainer"
        app:strokeColor="@color/taal_card_stroke"
        app:strokeWidth="1dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingStart="16dp"
            android:paddingTop="10dp"
            android:paddingEnd="16dp"
            android:paddingBottom="10dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginBottom="4dp"
                android:text="Custom Frequency Range"
                android:textColor="@color/taal_accent"
                android:textSize="12sp"
                android:textStyle="bold" />

            <com.google.android.material.slider.RangeSlider
                android:id="@+id/customRangeSlider"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:valueFrom="0"
                android:valueTo="24000"
                app:haloColor="#1A128CB2"
                app:labelBehavior="gone"
                app:thumbColor="@color/taal_accent"
                app:thumbRadius="8dp"
                app:trackColorActive="@color/taal_accent"
                app:trackColorInactive="#C8E6F5"
                app:trackHeight="4dp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="0 Hz"
                    android:textColor="@color/taal_text_secondary"
                    android:textSize="10sp" />

                <View
                    android:layout_width="0dp"
                    android:layout_height="0dp"
                    android:layout_weight="1" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="24000 Hz"
                    android:textColor="@color/taal_text_secondary"
                    android:textSize="10sp" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:orientation="horizontal">

                <com.google.android.material.textfield.TextInputLayout
                    style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox.Dense"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginEnd="6dp"
                    android:layout_weight="1"
                    android:hint="Low Cut (Hz)">

                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/customLowCutInput"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:inputType="number"
                        android:maxLength="5" />
                </com.google.android.material.textfield.TextInputLayout>

                <com.google.android.material.textfield.TextInputLayout
                    style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox.Dense"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="6dp"
                    android:layout_weight="1"
                    android:hint="High Cut (Hz)">

                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/customHighCutInput"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:inputType="number"
                        android:maxLength="5" />
                </com.google.android.material.textfield.TextInputLayout>
            </LinearLayout>
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <!-- Pre-amplification (0–30 dB). -->
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/ampSliderContainer"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="4dp"
        android:layout_marginEnd="16dp"
        app:cardBackgroundColor="@color/taal_white"
        app:cardCornerRadius="12dp"
        app:cardElevation="4dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/customRangePanel"
        app:strokeColor="@color/taal_card_stroke"
        app:strokeWidth="1dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingStart="10dp"
            android:paddingEnd="10dp">

            <ImageView
                android:layout_width="14dp"
                android:layout_height="14dp"
                android:contentDescription="Amplification"
                android:src="@drawable/ic_volume_up"
                app:tint="@color/taal_accent" />

            <com.google.android.material.slider.Slider
                android:id="@+id/ampSlider"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="6dp"
                android:layout_marginEnd="6dp"
                android:layout_weight="1"
                android:stepSize="1"
                android:value="10"
                android:valueFrom="0"
                android:valueTo="30"
                app:haloColor="#1A128CB2"
                app:labelBehavior="gone"
                app:thumbColor="@color/taal_accent"
                app:thumbRadius="5dp"
                app:trackColorActive="@color/taal_accent"
                app:trackColorInactive="#C8E6F5"
                app:trackHeight="2dp" />

            <TextView
                android:id="@+id/ampLabel"
                android:layout_width="44dp"
                android:layout_height="wrap_content"
                android:gravity="end"
                android:text="10 dB"
                android:textColor="@color/taal_accent"
                android:textSize="12sp"
                android:textStyle="bold" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <TextView
        android:id="@+id/scaleCaption"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="2dp"
        android:layout_marginEnd="16dp"
        android:gravity="center"
        android:text="1 large box = 1 s · 1 small box = 0.2 s"
        android:textColor="@color/taal_text_secondary"
        android:textSize="9sp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/ampSliderContainer" />

    <!-- Our own PcgScale graph (plain Canvas + taal-stemz-core maths). -->
    <com.example.stemzsdktest.PcgGraphView
        android:id="@+id/graphView"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="2dp"
        android:layout_marginBottom="2dp"
        app:layout_constraintBottom_toTopOf="@id/bpmText"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/scaleCaption" />

    <TextView
        android:id="@+id/bpmText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="2dp"
        android:text="-- BPM"
        android:textColor="@color/taal_text_secondary"
        android:textSize="12sp"
        app:layout_constraintBottom_toTopOf="@id/actionText"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <TextView
        android:id="@+id/actionText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="2dp"
        android:text="Start Recording"
        android:textColor="@color/taal_text_primary"
        android:textSize="12sp"
        app:layout_constraintBottom_toTopOf="@id/recordButton"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <ImageButton
        android:id="@+id/recordButton"
        android:layout_width="52dp"
        android:layout_height="52dp"
        android:layout_marginBottom="8dp"
        android:background="@drawable/bg_record_button"
        android:contentDescription="Record"
        android:elevation="8dp"
        android:padding="0dp"
        android:scaleType="fitCenter"
        android:src="@drawable/ic_record_start"
        app:layout_constraintBottom_toTopOf="@id/bottomBar"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:tint="@color/taal_white" />

    <LinearLayout
        android:id="@+id/bottomBar"
        android:layout_width="match_parent"
        android:layout_height="36dp"
        android:gravity="center"
        android:orientation="horizontal"
        android:paddingStart="24dp"
        android:paddingEnd="24dp"
        android:paddingBottom="2dp"
        app:layout_constraintBottom_toBottomOf="parent">

        <ImageButton
            android:id="@+id/folderButton"
            android:layout_width="0dp"
            android:layout_height="30dp"
            android:layout_weight="1"
            android:background="@drawable/bg_bottom_action_button"
            android:contentDescription="Saved recordings"
            android:scaleType="centerInside"
            android:src="@drawable/ic_folder"
            app:tint="@color/taal_white" />
    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

### 5.10 `app/src/main/java/com/example/stemzsdktest/CoreRecorderActivity.kt` — Button 3, Recorder

Same behaviour as the UI kit recorder: Lite/Hard + Custom range, pre-amp locked while recording,
live graph, live BPM, you hear the sound while recording, 15 s auto-stop, then it opens the
player. The folder button lists recordings saved from the player.

```kotlin
package com.example.stemzsdktest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.stemzsdktest.databinding.ActivityCoreRecorderBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.musediagnostics.taal.stemz.PreFilter
import com.musediagnostics.taal.stemz.TaalDisconnectedException
import com.musediagnostics.taal.stemz.TaalRecorder
import com.musediagnostics.taal.stemz.core.RecorderState
import com.musediagnostics.taal.stemz.dsp.HeartBpmCalculator
import com.musediagnostics.taal.stemz.dsp.PcgLiveDisplayFilter
import com.musediagnostics.taal.stemz.graph.PcgAmplitudeScale
import com.musediagnostics.taal.stemz.utils.SurrUtils
import com.musediagnostics.taal.stemz.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.pow

/**
 * Button 3, screen 1 — our own copy of the UI kit's "TAAL Recorder" screen, built ONLY on
 * taal-stemz-core (no taal-stemz-ui-kit classes or resources).
 *
 * Same behaviour as the UI kit recorder: Lite / Hard heart filter + Custom Hz range, pre-amp
 * slider (locked while recording), live PcgScale graph (1 large box = 1 s, 1 small box = 0.2 s,
 * auto-scaled Y axis, Clean-Graph live conditioning), live BPM, you hear the sound while
 * recording, auto-stop at [AUTO_STOP_SECONDS]; on stop it opens [CorePlayerActivity] with both
 * files. The folder button lists recordings saved from the player.
 */
class CoreRecorderActivity : AppCompatActivity() {

    companion object {
        /** Auto-stop, in seconds (1–300). Same default as the UI kit. */
        const val AUTO_STOP_SECONDS = 15

        /** Where the player saves recordings (inside the app). Separate from the UI kit's own
         *  folder so the two demos never mix files. */
        fun savedDir(context: Context): File = File(context.filesDir, "core_saved").apply { mkdirs() }

        const val FILTER_LITE = "LITE"
        const val FILTER_HARD = "HARD"
        const val FILTER_CUSTOM = "CUSTOM"

        private const val DEVICE_CONNECTED_TINT = "#128CB2"
        private const val DEVICE_DISCONNECTED_TINT = "#333333"
    }

    private lateinit var binding: ActivityCoreRecorderBinding

    private var recorder: TaalRecorder? = null
    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    // Selected filter and Custom range.
    private var filterName = FILTER_LITE
    private var customLowHz = 20f
    private var customHighHz = 1000f
    private var preAmpDb = 10

    // Current recording's files.
    private var rawFile: File? = null
    private var filteredFile: File? = null
    private var deviceWasUnplugged = false

    // Live monitor: TaalRecorder has no built-in "listen" switch — the samples it hands us in
    // onProgressUpdate are played through a standard Android AudioTrack.
    @Volatile private var monitorTrack: AudioTrack? = null

    // Live-graph helpers — new instances for every recording.
    private var liveRate = 44100f
    private var liveFilter = PcgLiveDisplayFilter(liveRate)
    private var liveScale = PcgAmplitudeScale(liveRate)
    private val bpmCalculator = HeartBpmCalculator()
    private var lastBpm = 0

    // Set only when the user tapped Record — the on-open permission request must not start one.
    private var startAfterPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && startAfterPermission) startRecording()
        else if (!granted && startAfterPermission) {
            Toast.makeText(this, "Audio permission required", Toast.LENGTH_SHORT).show()
        }
        startAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCoreRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFilterButtons()
        setupCustomRangePanel()
        setupPreAmpSlider()

        binding.infoButton.setOnClickListener { showPlacementInfo() }
        binding.folderButton.setOnClickListener { showSavedRecordings() }
        binding.recordButton.setOnClickListener {
            if (recorder?.getState() == RecorderState.RECORDING) {
                recorder?.stop()          // STOPPED arrives in onStateChange
            } else {
                checkPermissionAndRecord()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (recorder?.getState() == RecorderState.RECORDING) {
                    Toast.makeText(this@CoreRecorderActivity, "Stop the recording before going back", Toast.LENGTH_SHORT).show()
                } else {
                    finish()
                }
            }
        })

        // Ask for the microphone as soon as the screen opens (does not start a recording).
        if (!hasMicPermission()) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        showIdleState()
    }

    override fun onStart() {
        super.onStart()
        setDeviceIcon(SurrUtils.isTaalDeviceConnected(this) == SurrUtils.ConnectionStatus.CONNECTED)
        connectionReceiver = TaalConnectionBroadcastReceiver(object :
            TaalConnectionBroadcastReceiver.TaalConnectionListener {
            override fun onTaalConnect() { runOnUiThread { setDeviceIcon(true) } }
            override fun onTaalDisconnect() { runOnUiThread { setDeviceIcon(false) } }
        }).also { it.register(this) }
    }

    override fun onStop() {
        super.onStop()
        connectionReceiver?.unregister(this)
        connectionReceiver = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recorder?.stop() } catch (_: Exception) {}
        stopMonitor()
    }

    private fun setDeviceIcon(connected: Boolean) {
        binding.deviceIcon.setColorFilter(
            Color.parseColor(if (connected) DEVICE_CONNECTED_TINT else DEVICE_DISCONNECTED_TINT)
        )
    }

    // ================================================================== FILTERS / PRE-AMP

    private fun setupFilterButtons() {
        binding.filterLite.isChecked = true
        binding.heartFilterToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            filterName = if (checkedId == binding.filterHard.id) FILTER_HARD else FILTER_LITE
            binding.customRangePanel.visibility = View.GONE
            binding.filterCustomTop.isSelected = false
            dismissKeyboard()
        }
        // The Custom icon in the top bar opens/closes the Custom range panel.
        binding.filterCustomTop.setOnClickListener {
            if (binding.filterCustomTop.isSelected) {
                binding.filterCustomTop.isSelected = false
                binding.customRangePanel.visibility = View.GONE
                binding.filterLite.isChecked = true           // back to Lite
            } else {
                binding.filterCustomTop.isSelected = true
                binding.heartFilterToggle.isSelectionRequired = false
                binding.heartFilterToggle.clearChecked()
                binding.heartFilterToggle.isSelectionRequired = true
                filterName = FILTER_CUSTOM
                binding.customRangePanel.visibility = View.VISIBLE
            }
            dismissKeyboard()
        }
    }

    private fun setupCustomRangePanel() {
        binding.customRangeSlider.values = listOf(customLowHz, customHighHz)
        binding.customLowCutInput.setText(customLowHz.toInt().toString())
        binding.customHighCutInput.setText(customHighHz.toInt().toString())

        var updating = false
        binding.customRangeSlider.addOnChangeListener { slider, _, _ ->
            if (updating) return@addOnChangeListener
            updating = true
            customLowHz = slider.values[0]
            customHighHz = slider.values[1]
            binding.customLowCutInput.setText(customLowHz.toInt().toString())
            binding.customHighCutInput.setText(customHighHz.toInt().toString())
            updating = false
        }
        binding.customLowCutInput.addTextChangedListener(afterChanged { v ->
            if (updating) return@afterChanged
            customLowHz = v
            val high = binding.customRangeSlider.values[1]
            if (v in 1f..24000f && v < high) {
                updating = true; binding.customRangeSlider.values = listOf(v, high); updating = false
            }
        })
        binding.customHighCutInput.addTextChangedListener(afterChanged { v ->
            if (updating) return@afterChanged
            customHighHz = v
            val low = binding.customRangeSlider.values[0]
            if (v in 1f..24000f && v > low) {
                updating = true; binding.customRangeSlider.values = listOf(low, v); updating = false
            }
        })
    }

    private fun afterChanged(onNumber: (Float) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            s?.toString()?.toFloatOrNull()?.let(onNumber)
        }
    }

    private fun setupPreAmpSlider() {
        binding.ampSlider.value = preAmpDb.toFloat()
        binding.ampLabel.text = "$preAmpDb dB"
        binding.ampSlider.addOnChangeListener { _, value, _ ->
            preAmpDb = value.toInt()
            binding.ampLabel.text = "$preAmpDb dB"
        }
    }

    /** Filter buttons and the pre-amp slider are locked (dimmed) while recording. */
    private fun setControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        listOf(binding.filterLite, binding.filterHard, binding.filterCustomTop).forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
        binding.customRangePanel.visibility =
            if (enabled && filterName == FILTER_CUSTOM) View.VISIBLE else View.GONE
        binding.ampSlider.isEnabled = enabled
        binding.ampSliderContainer.alpha = if (enabled) 1f else 0.55f
    }

    /** Returns an error message if the Custom range is not usable, else null. */
    private fun customRangeError(): String? {
        val lowText = binding.customLowCutInput.text?.toString()?.trim()
        val highText = binding.customHighCutInput.text?.toString()?.trim()
        return when {
            lowText.isNullOrEmpty() || highText.isNullOrEmpty() -> "Low Cut and High Cut cannot be blank."
            customLowHz <= 0f -> "Low Cut must be greater than 0 Hz (e.g. 20 Hz)."
            customHighHz <= 0f -> "High Cut must be greater than 0 Hz (e.g. 1000 Hz)."
            customLowHz >= customHighHz ->
                "Low Cut (${customLowHz.toInt()} Hz) must be less than High Cut (${customHighHz.toInt()} Hz)."
            else -> null
        }
    }

    // ================================================================== RECORD

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissionAndRecord() {
        if (hasMicPermission()) startRecording()
        else {
            startAfterPermission = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        dismissKeyboard()
        if (filterName == FILTER_CUSTOM) {
            customRangeError()?.let { message ->
                MaterialAlertDialogBuilder(this).setTitle("Custom Filter").setMessage(message)
                    .setPositiveButton("OK", null).show()
                return
            }
        }

        // Temporary files; the player moves them into savedDir() when the user saves.
        val ts = System.currentTimeMillis()
        val raw = File(filesDir, "core_rec_${ts}_raw.wav")
        val filtered = File(filesDir, "core_rec_${ts}_filtered.wav")
        rawFile = raw
        filteredFile = filtered
        deviceWasUnplugged = false

        val newRecorder = TaalRecorder(this)
        try {
            newRecorder.setRawAudioFilePath(raw.absolutePath)
            newRecorder.setFilteredAudioFilePath(filtered.absolutePath)
            newRecorder.setRecordingTime(AUTO_STOP_SECONDS)     // auto-stop
            newRecorder.setPreAmplification(preAmpDb)           // 0–30 dB
            when (filterName) {
                FILTER_HARD -> newRecorder.setPreFilter(PreFilter.HARD)
                FILTER_CUSTOM -> newRecorder.setCustomBandpass(customLowHz.toDouble(), customHighHz.toDouble())
                else -> newRecorder.setPreFilter(PreFilter.LITE)
            }
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        }

        newRecorder.onInfoListener = object : TaalRecorder.OnInfoListener {
            override fun onStateChange(state: RecorderState) {
                runOnUiThread {
                    when (state) {
                        RecorderState.RECORDING -> showRecordingState()
                        RecorderState.STOPPED -> onRecordingStopped()
                        else -> {}
                    }
                }
            }

            // SDK audio thread: filtered, pre-amplified samples (-1..1).
            override fun onProgressUpdate(sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray) {
                // 1) Hear the sound while recording.
                playToMonitor(data, sampleRate)

                // 2) Use the real capture rate (some phones record at 48 kHz, not 44.1 kHz).
                if (sampleRate.toFloat() != liveRate) {
                    liveRate = sampleRate.toFloat()
                    liveFilter = PcgLiveDisplayFilter(liveRate)
                    liveScale = PcgAmplitudeScale(liveRate)
                }

                // 3) Live BPM.
                if (bpmCalculator.addSamples(data)) {
                    val bpm = bpmCalculator.computeBpm()
                    if (bpm > 0) lastBpm = bpm
                }

                // 4) Graph: undo the pre-amp gain (trace shows the true level; the slider changes
                //    loudness only), clean it for display, feed the auto Y-axis.
                val gain = 10f.pow(preAmpDb / 20f)
                val display = liveFilter.process(FloatArray(data.size) { i -> data[i] / gain })
                liveScale.addSamples(display)
                val fullScale = liveScale.smoothedFullScale()

                runOnUiThread {
                    binding.graphView.appendLive(display, sampleRate.toFloat(), fullScale)
                    binding.timerText.text = formatTimer(timeStamp.toInt())
                    binding.bpmText.text = if (lastBpm > 0) "$lastBpm BPM" else "-- BPM"
                }
            }

            override fun onDeviceDisconnected() {
                // STOPPED follows; don't open the player for a cut-off recording.
                deviceWasUnplugged = true
                Toast.makeText(this@CoreRecorderActivity,
                    "Device disconnected. Please connect the device.", Toast.LENGTH_LONG).show()
            }
        }

        // Fresh live state for this recording.
        liveRate = 44100f
        liveFilter = PcgLiveDisplayFilter(liveRate)
        liveScale = PcgAmplitudeScale(liveRate)
        bpmCalculator.reset()
        lastBpm = 0
        binding.graphView.clear()

        try {
            newRecorder.start()
            recorder = newRecorder
        } catch (e: TaalDisconnectedException) {
            Toast.makeText(this, "Connect the TAAL device first", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this).setTitle("Recording Error").setMessage(e.message)
                .setPositiveButton("OKAY", null).show()
        }
    }

    /** Manual stop, auto-stop and unplug all end here; the SDK has finalized both WAV files. */
    private fun onRecordingStopped() {
        recorder = null
        stopMonitor()
        val raw = rawFile
        val filtered = filteredFile
        if (deviceWasUnplugged || raw == null || filtered == null || isFinishing) {
            raw?.delete(); filtered?.delete()
            showIdleState()
            return
        }
        lifecycleScope.launch {
            delay(300) // let the SDK finish flushing the files to disk
            startActivity(CorePlayerActivity.newRecordingIntent(
                this@CoreRecorderActivity, filtered, raw, filterName, preAmpDb
            ))
            showIdleState()
        }
    }

    private fun showIdleState() {
        binding.actionText.text = "Start Recording"
        binding.recordButton.setImageResource(R.drawable.ic_record_start)
        binding.bottomBar.visibility = View.VISIBLE
        binding.timerText.text = formatTimer(0)
        binding.bpmText.text = "-- BPM"
        setControlsEnabled(true)
        binding.graphView.clear()
    }

    private fun showRecordingState() {
        binding.actionText.text = "Stop Recording"
        binding.recordButton.setImageResource(R.drawable.ic_record_stop)
        binding.bottomBar.visibility = View.GONE
        setControlsEnabled(false)
    }

    private fun formatTimer(seconds: Int) =
        String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)

    // ================================================================== LIVE MONITOR

    private fun playToMonitor(data: FloatArray, sampleRate: Int) {
        val track = monitorTrack ?: createMonitorTrack(sampleRate).also { monitorTrack = it }
        val pcm = ShortArray(data.size) { i -> (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        // Blocking write (the default), same as the UI kit: every sample reaches the speaker.
        // Do NOT use WRITE_NON_BLOCKING — when the speaker buffer is full it drops the rest of the
        // buffer, and those gaps are heard as crackle/noise while recording (the file is fine).
        track.write(pcm, 0, pcm.size)
    }

    private fun createMonitorTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }

    private fun stopMonitor() {
        val track = monitorTrack ?: return
        monitorTrack = null
        try { track.stop() } catch (_: Exception) {}
        try { track.release() } catch (_: Exception) {}
    }

    // ================================================================== INFO / SAVED LIST

    private fun showPlacementInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Heart — stethoscope placement")
            .setMessage(
                "Lite, Hard and Custom are all heart recordings: same stethoscope positions, " +
                    "only the filter differs.\n\n" +
                    "• Aortic: 2nd right intercostal space\n" +
                    "• Pulmonic: 2nd left intercostal space\n" +
                    "• Tricuspid: 4th left intercostal space, lower sternal border\n" +
                    "• Mitral: 5th intercostal space, mid-clavicular line (apex)"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /** Recordings saved from CorePlayerActivity, newest first; tap one to open it in the player. */
    private fun showSavedRecordings() {
        val files = savedDir(this).listFiles { f -> f.name.endsWith("_filtered.wav") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, "No saved recordings yet", Toast.LENGTH_SHORT).show()
            return
        }
        val names = files.map { CorePlayerActivity.displayName(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Saved Recordings")
            .setItems(names) { _, which ->
                startActivity(CorePlayerActivity.savedRecordingIntent(this, files[which]))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun dismissKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.root.clearFocus()
    }
}
```

### 5.11 `app/src/main/res/layout/activity_core_player.xml` — Button 3, Player screen

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Button 3, screen 2: our own copy of the UI kit's "Review Recording" player, built on
     taal-stemz-core only. Top bar (back · title · Analyze Heart Sounds once saved), duration,
     playback-volume slider, Clean Graph ON/OFF, scale caption, scrollable PcgScale graph,
     play button, Discard / Save. -->
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/taal_screen_bg">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/topBar"
        android:layout_width="match_parent"
        android:layout_height="40dp"
        android:background="@color/taal_white"
        android:elevation="2dp"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        app:layout_constraintTop_toTopOf="parent">

        <ImageButton
            android:id="@+id/backButton"
            android:layout_width="26dp"
            android:layout_height="26dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Back"
            android:scaleType="centerInside"
            android:src="@drawable/ic_arrow_back"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <TextView
            android:id="@+id/screenTitle"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="40dp"
            android:layout_marginEnd="40dp"
            android:ellipsize="end"
            android:gravity="center"
            android:maxLines="1"
            android:text="Review Recording"
            android:textColor="@color/taal_text_primary"
            android:textSize="14sp"
            android:textStyle="bold"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent" />

        <!-- Shown once the recording is saved (the analysis needs the saved _raw.wav). -->
        <ImageButton
            android:id="@+id/analyzeButton"
            android:layout_width="30dp"
            android:layout_height="30dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Analyze Heart Sounds"
            android:padding="5dp"
            android:scaleType="fitCenter"
            android:src="@drawable/ic_analyze"
            android:visibility="gone"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent"
            app:tint="@color/taal_accent" />
    </androidx.constraintlayout.widget.ConstraintLayout>

    <TextView
        android:id="@+id/timerText"
        style="@style/TaalTimerText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:text="00:00"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/topBar" />

    <!-- Playback volume (0–30 dB). Changes loudness only — never the file or the graph. -->
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/ampSliderContainer"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="4dp"
        android:layout_marginEnd="16dp"
        app:cardBackgroundColor="@color/taal_white"
        app:cardCornerRadius="10dp"
        app:cardElevation="2dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/timerText"
        app:strokeColor="@color/taal_card_stroke"
        app:strokeWidth="1dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingStart="10dp"
            android:paddingEnd="10dp">

            <ImageView
                android:layout_width="14dp"
                android:layout_height="14dp"
                android:contentDescription="Amplification"
                android:src="@drawable/ic_volume_up"
                app:tint="@color/taal_accent" />

            <com.google.android.material.slider.Slider
                android:id="@+id/ampSlider"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="4dp"
                android:layout_marginEnd="4dp"
                android:layout_weight="1"
                android:stepSize="1"
                android:value="0"
                android:valueFrom="0"
                android:valueTo="30"
                app:haloColor="#1A128CB2"
                app:labelBehavior="gone"
                app:thumbColor="@color/taal_accent"
                app:thumbRadius="5dp"
                app:trackColorActive="@color/taal_accent"
                app:trackColorInactive="#C8E6F5"
                app:trackHeight="2dp" />

            <TextView
                android:id="@+id/ampLabel"
                android:layout_width="40dp"
                android:layout_height="wrap_content"
                android:gravity="end"
                android:text="0 dB"
                android:textColor="@color/taal_accent"
                android:textSize="11sp"
                android:textStyle="bold" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <!-- Clean Graph: a single tap-toggle that is also its own ON/OFF indicator. Display only. -->
    <LinearLayout
        android:id="@+id/cleanGraphRow"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="4dp"
        android:layout_marginEnd="16dp"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/ampSliderContainer">

        <TextView
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Clean Graph"
            android:textColor="@color/taal_text_primary"
            android:textSize="12sp" />

        <TextView
            android:id="@+id/cleanGraphBadge"
            android:layout_width="60dp"
            android:layout_height="30dp"
            android:background="@drawable/bg_pill_on"
            android:clickable="true"
            android:focusable="true"
            android:gravity="center"
            android:text="ON"
            android:textColor="@color/taal_white"
            android:textSize="12sp"
            android:textStyle="bold" />
    </LinearLayout>

    <TextView
        android:id="@+id/scaleCaption"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginTop="2dp"
        android:layout_marginEnd="16dp"
        android:gravity="center"
        android:text="1 large box = 1 s · 1 small box = 0.2 s"
        android:textColor="@color/taal_text_secondary"
        android:textSize="9sp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/cleanGraphRow" />

    <com.example.stemzsdktest.PcgGraphView
        android:id="@+id/graphView"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="2dp"
        android:layout_marginBottom="2dp"
        app:layout_constraintBottom_toTopOf="@id/actionText"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/scaleCaption" />

    <ProgressBar
        android:id="@+id/loadingIndicator"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:indeterminateTint="@color/taal_accent"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="@id/graphView"
        app:layout_constraintEnd_toEndOf="@id/graphView"
        app:layout_constraintStart_toStartOf="@id/graphView"
        app:layout_constraintTop_toTopOf="@id/graphView" />

    <TextView
        android:id="@+id/actionText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="4dp"
        android:text="Play Recording"
        android:textColor="@color/taal_text_primary"
        android:textSize="12sp"
        app:layout_constraintBottom_toTopOf="@id/playButton"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <ImageButton
        android:id="@+id/playButton"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:layout_marginBottom="10dp"
        android:background="@drawable/bg_record_button"
        android:contentDescription="Play"
        android:elevation="8dp"
        android:padding="0dp"
        android:scaleType="fitCenter"
        android:src="@drawable/ic_play_circle"
        app:layout_constraintBottom_toTopOf="@id/saveDiscardBar"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_goneMarginBottom="24dp"
        app:tint="@color/taal_white" />

    <LinearLayout
        android:id="@+id/saveDiscardBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginHorizontal="16dp"
        android:layout_marginBottom="12dp"
        android:orientation="horizontal"
        app:layout_constraintBottom_toBottomOf="parent">

        <com.google.android.material.button.MaterialButton
            android:id="@+id/discardButton"
            style="@style/Widget.MaterialComponents.Button.OutlinedButton"
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_marginEnd="8dp"
            android:layout_weight="1"
            android:insetTop="0dp"
            android:insetBottom="0dp"
            android:text="Discard"
            android:textAllCaps="false"
            android:textColor="@color/taal_text_primary"
            android:textSize="13sp"
            android:textStyle="bold"
            app:backgroundTint="@color/taal_white"
            app:cornerRadius="22dp"
            app:strokeColor="@color/taal_card_stroke" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/saveButton"
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_marginStart="8dp"
            android:layout_weight="1"
            android:insetTop="0dp"
            android:insetBottom="0dp"
            android:text="Save"
            android:textAllCaps="false"
            android:textColor="@color/taal_white"
            android:textSize="13sp"
            android:textStyle="bold"
            app:backgroundTint="@color/taal_teal"
            app:cornerRadius="22dp" />
    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

### 5.12 `app/src/main/java/com/example/stemzsdktest/CorePlayerActivity.kt` — Button 3, Player

New recording: whole recording on the grid (drag to scroll), Clean Graph ON/OFF, Play (the graph
follows the sound), playback volume, Discard / Save. Save asks for a name, keeps the files in the
app and copies them to **Music/Stemz Recordings**, then the same screen switches to the saved
view: the recording's name as title and the **Analyze Heart Sounds** icon (top right).

```kotlin
package com.example.stemzsdktest

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.stemzsdktest.databinding.ActivityCorePlayerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.musediagnostics.taal.stemz.TaalPlayer
import com.musediagnostics.taal.stemz.dsp.PcgDisplayFilter
import com.musediagnostics.taal.stemz.graph.PcgAmplitudeScale
import com.musediagnostics.taal.stemz.segmentation.SegmentationOutcome
import com.musediagnostics.taal.stemz.segmentation.TaalCardiacSegmentation
import com.musediagnostics.taal.stemz.segmentation.heartRateBpm
import com.musediagnostics.taal.stemz.segmentation.systolicIntervalsMs
import com.musediagnostics.taal.stemz.util.PcgWavDecoder
import com.purnacardio.signal.pcg.SegmentationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Button 3, screen 2 — our own copy of the UI kit's "Review Recording" player, built ONLY on
 * taal-stemz-core (no taal-stemz-ui-kit classes or resources).
 *
 * Two modes, same screen:
 *  - NEW recording (opened by CoreRecorderActivity): whole recording on the PcgScale grid (drag
 *    to scroll, no zoom), Clean Graph ON/OFF (default ON, display only), playback with the view
 *    following the sound, playback volume, Discard / Save. Save asks for a name, moves both files
 *    into CoreRecorderActivity.savedDir() and copies them to the phone's Music/Stemz Recordings.
 *  - SAVED recording (after Save, or opened from the recorder's folder button): title = the
 *    saved name, no Discard/Save, and the Analyze Heart Sounds icon (top right).
 */
class CorePlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_FILTERED = "filtered"
        private const val EXTRA_RAW = "raw"
        private const val EXTRA_FILTER = "filter"
        private const val EXTRA_PRE_AMP = "preAmpDb"
        private const val EXTRA_SAVED = "saved"

        /** Public folder under Music/ that saved recordings are copied to. */
        const val PUBLIC_FOLDER = "Stemz Recordings"

        fun newRecordingIntent(context: Context, filtered: File, raw: File, filter: String, preAmpDb: Int) =
            Intent(context, CorePlayerActivity::class.java)
                .putExtra(EXTRA_FILTERED, filtered.absolutePath)
                .putExtra(EXTRA_RAW, raw.absolutePath)
                .putExtra(EXTRA_FILTER, filter)
                .putExtra(EXTRA_PRE_AMP, preAmpDb)
                .putExtra(EXTRA_SAVED, false)

        /** Saved files are named "{FILTER}_{name}_filtered.wav" + "{FILTER}_{name}_raw.wav". */
        fun savedRecordingIntent(context: Context, savedFiltered: File) =
            Intent(context, CorePlayerActivity::class.java)
                .putExtra(EXTRA_FILTERED, savedFiltered.absolutePath)
                .putExtra(EXTRA_RAW, savedFiltered.absolutePath.replace("_filtered.wav", "_raw.wav"))
                .putExtra(EXTRA_FILTER, savedFiltered.name.substringBefore("_"))
                .putExtra(EXTRA_PRE_AMP, 0)
                .putExtra(EXTRA_SAVED, true)

        /** "HARD_Patient 1_filtered.wav" -> "Patient 1 (Hard)". */
        fun displayName(savedFiltered: File): String {
            val filter = savedFiltered.name.substringBefore("_")
            val name = savedFiltered.name.substringAfter("_").removeSuffix("_filtered.wav")
            val label = when (filter) {
                CoreRecorderActivity.FILTER_HARD -> "Hard"
                CoreRecorderActivity.FILTER_CUSTOM -> "Custom"
                else -> "Lite"
            }
            return "$name ($label)"
        }
    }

    private lateinit var binding: ActivityCorePlayerBinding

    private lateinit var filteredFile: File
    private lateinit var rawFile: File
    private lateinit var filterName: String
    private var isSaved = false

    private var player: TaalPlayer? = null
    private var isPlaying = false
    private var displayedPlaybackTime = 0f      // smoothed follow position

    // Both versions of the samples are kept, so Clean Graph ON/OFF is instant after the first time.
    private var originalSamples: FloatArray? = null
    private var cleanedSamples: FloatArray? = null
    private var sampleRate = 44100f
    private var cleanGraphOn = true

    // Holds the chosen name while we ask for storage permission (Android 8/9 only).
    private var pendingPublicCopy: List<File>? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val files = pendingPublicCopy ?: return@registerForActivityResult
        pendingPublicCopy = null
        if (granted) lifecycleScope.launch(Dispatchers.IO) { files.forEach { copyToPublicMusic(it) } }
        else Toast.makeText(this, "Storage permission denied — saved in app storage only", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCorePlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filteredFile = File(intent.getStringExtra(EXTRA_FILTERED).orEmpty())
        rawFile = File(intent.getStringExtra(EXTRA_RAW).orEmpty())
        filterName = intent.getStringExtra(EXTRA_FILTER) ?: CoreRecorderActivity.FILTER_LITE
        isSaved = intent.getBooleanExtra(EXTRA_SAVED, false)
        val recordedPreAmpDb = intent.getIntExtra(EXTRA_PRE_AMP, 0)

        binding.backButton.setOnClickListener { finish() }
        binding.playButton.setOnClickListener { togglePlayback() }
        binding.saveButton.setOnClickListener { askNameAndSave() }
        binding.discardButton.setOnClickListener { confirmDiscard() }
        binding.analyzeButton.setOnClickListener { analyzeHeartSounds() }
        binding.cleanGraphBadge.setOnClickListener { setCleanGraph(!cleanGraphOn) }
        binding.ampSlider.addOnChangeListener { _, value, _ ->
            binding.ampLabel.text = "${value.toInt()} dB"
            player?.setPreAmplification(value)    // playback loudness only
        }

        showMode()
        setCleanGraphEnabled(false)
        loadWaveform(recordedPreAmpDb)
        createPlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    /** New recording: "Review Recording" + Discard/Save. Saved: its name + Analyze icon. */
    private fun showMode() {
        if (isSaved) {
            binding.screenTitle.text = displayName(filteredFile)
            binding.saveDiscardBar.visibility = View.GONE
            binding.analyzeButton.visibility = View.VISIBLE
        } else {
            binding.screenTitle.text = "Review Recording"
            binding.saveDiscardBar.visibility = View.VISIBLE
            binding.analyzeButton.visibility = View.GONE
        }
    }

    // ================================================================== GRAPH

    /** Decode the filtered file once, undo the recording's pre-amp (so the trace shows the true
     *  level, as it did live), then show it with Clean Graph ON. */
    private fun loadWaveform(recordedPreAmpDb: Int) {
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                if (filteredFile.exists()) PcgWavDecoder.decode(filteredFile.readBytes()) else null
            }
            if (decoded == null) {
                binding.loadingIndicator.visibility = View.GONE
                Toast.makeText(this@CorePlayerActivity, "Cannot open recording", Toast.LENGTH_LONG).show()
                return@launch
            }
            val samples = decoded.samples
            if (recordedPreAmpDb > 0) {
                val gain = 10f.pow(recordedPreAmpDb / 20f)
                for (i in samples.indices) samples[i] /= gain
            }
            sampleRate = decoded.sampleRate
            originalSamples = samples
            val secs = (samples.size / sampleRate).roundToInt()
            binding.timerText.text = String.format("%02d:%02d", secs / 60, secs % 60)
            setCleanGraph(true)
        }
    }

    /** Clean Graph ON = PcgDisplayFilter output; OFF = the file as recorded. Display only —
     *  playback always plays the file. The Y axis is recomputed for whichever version is shown. */
    private fun setCleanGraph(on: Boolean) {
        cleanGraphOn = on
        binding.cleanGraphBadge.text = if (on) "ON" else "OFF"
        binding.cleanGraphBadge.setBackgroundResource(if (on) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
        binding.cleanGraphBadge.setTextColor(if (on) Color.WHITE else Color.parseColor("#757575"))

        val original = originalSamples ?: return
        if (!on) {
            draw(original)
            return
        }
        cleanedSamples?.let { draw(it); return }

        // First time ON: compute the clean version off the main thread.
        binding.loadingIndicator.visibility = View.VISIBLE
        setCleanGraphEnabled(false)
        lifecycleScope.launch {
            val cleaned = withContext(Dispatchers.Default) {
                PcgDisplayFilter.processOffline(original, sampleRate)
            }
            cleanedSamples = cleaned
            setCleanGraphEnabled(true)
            if (cleanGraphOn) draw(cleaned)
        }
    }

    private fun draw(samples: FloatArray) {
        binding.loadingIndicator.visibility = View.GONE
        setCleanGraphEnabled(true)
        // Whole-file Y axis: typical heart-sound peaks fill ~60% of the half-height.
        val fullScale = PcgAmplitudeScale.computeFullScaleForFile(samples, sampleRate)
        binding.graphView.showRecording(samples, sampleRate, fullScale, keepScroll = true)
    }

    private fun setCleanGraphEnabled(enabled: Boolean) {
        binding.cleanGraphBadge.isEnabled = enabled
        binding.cleanGraphBadge.alpha = if (enabled) 1f else 0.4f
    }

    // ================================================================== PLAYBACK

    private fun createPlayer() {
        releasePlayer()
        try {
            player = TaalPlayer(this).apply {
                setDataSource(filteredFile.absolutePath)
                // No setPreFilter(): a "_filtered.wav" is already filtered.
                setPreAmplification(binding.ampSlider.value)
                onPlaybackProgress = { timestamp, _ ->
                    runOnUiThread {
                        val secs = timestamp.toInt()
                        binding.timerText.text = String.format("%02d:%02d", secs / 60, secs % 60)
                        // Eased follow, so the graph glides with the sound instead of jumping.
                        displayedPlaybackTime += (timestamp.toFloat() - displayedPlaybackTime) * 0.15f
                        binding.graphView.followPlayback(displayedPlaybackTime)
                    }
                }
                onPlaybackComplete = { runOnUiThread { showStopped() } }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open recording: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (isPlaying) {
            try { p.stop() } catch (_: Exception) {}
            showStopped()
            return
        }
        try {
            displayedPlaybackTime = 0f
            binding.graphView.scrollToStart()
            p.prepare()
            p.start()
            isPlaying = true
            binding.actionText.text = "Stop Recording"
            binding.playButton.setImageResource(R.drawable.ic_record_stop)
        } catch (e: Exception) {
            showStopped()
            Toast.makeText(this, "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showStopped() {
        isPlaying = false
        displayedPlaybackTime = 0f
        binding.actionText.text = "Play Recording"
        binding.playButton.setImageResource(R.drawable.ic_play_circle)
        binding.graphView.scrollToStart()
        val secs = ((originalSamples?.size ?: 0) / sampleRate).roundToInt()
        binding.timerText.text = String.format("%02d:%02d", secs / 60, secs % 60)
    }

    private fun releasePlayer() {
        player?.let {
            it.onPlaybackProgress = null
            it.onPlaybackComplete = null
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
        isPlaying = false
    }

    // ================================================================== DISCARD / SAVE

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Discard Recording")
            .setMessage("Are you sure you want to discard this recording? It will be permanently deleted.")
            .setPositiveButton("Discard") { _, _ ->
                releasePlayer()
                filteredFile.delete()
                rawFile.delete()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askNameAndSave() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Save Recording")
            .setMessage("Name your recording")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) Toast.makeText(this, "Please enter a file name", Toast.LENGTH_SHORT).show()
                else save(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Moves both files into savedDir() as "{FILTER}_{name}_filtered.wav" / "_raw.wav", copies
     *  them to Music/Stemz Recordings, then switches this screen to SAVED mode. */
    private fun save(name: String) {
        releasePlayer()
        val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val dir = CoreRecorderActivity.savedDir(this)
        val newFiltered = File(dir, "${filterName}_${safeName}_filtered.wav")
        val newRaw = File(dir, "${filterName}_${safeName}_raw.wav")
        if (newFiltered.exists()) {
            Toast.makeText(this, "A recording with this name already exists", Toast.LENGTH_LONG).show()
            createPlayer()
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                moveFile(filteredFile, newFiltered)
                moveFile(rawFile, newRaw)
                newFiltered.exists()
            }
            if (!ok) {
                Toast.makeText(this@CorePlayerActivity, "Failed to save recording", Toast.LENGTH_SHORT).show()
                createPlayer()
                return@launch
            }
            filteredFile = newFiltered
            rawFile = newRaw
            isSaved = true
            showMode()
            createPlayer()
            Toast.makeText(this@CorePlayerActivity, "Recording saved!", Toast.LENGTH_SHORT).show()

            // Public copy: Android 10+ needs no permission; Android 8/9 needs WRITE_EXTERNAL_STORAGE.
            val files = listOf(newFiltered, newRaw)
            val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this@CorePlayerActivity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                pendingPublicCopy = files
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                withContext(Dispatchers.IO) { files.forEach { copyToPublicMusic(it) } }
            }
        }
    }

    private fun moveFile(src: File, dst: File) {
        if (!src.exists()) return
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    /** Copies one file into the phone's Music/Stemz Recordings folder. Failure is not fatal —
     *  the app's own copy is already safe. */
    private fun copyToPublicMusic(source: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$PUBLIC_FOLDER")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return
                contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), PUBLIC_FOLDER)
                folder.mkdirs()
                source.copyTo(File(folder, source.name), overwrite = true)
            }
        } catch (_: Exception) {
        }
    }

    // ================================================================== HEART-SOUND ANALYSIS

    /** Runs on the saved RAW file; shows a report and marks S1 (red) / S2 (green) on the graph. */
    private fun analyzeHeartSounds() {
        if (!rawFile.exists()) {
            Toast.makeText(this, "Raw recording not found", Toast.LENGTH_SHORT).show()
            return
        }
        binding.analyzeButton.isEnabled = false
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val outcome = TaalCardiacSegmentation(applicationContext).use { it.segmentRawWav(rawFile) }
            binding.loadingIndicator.visibility = View.GONE
            binding.analyzeButton.isEnabled = true
            val report = when (outcome) {
                is SegmentationOutcome.Ok -> report(outcome.result, lowConfidence = false)
                is SegmentationOutcome.TooWeak -> report(outcome.result, lowConfidence = true)
                SegmentationOutcome.NoHeartSounds -> "No heart sounds detected — please retake the recording."
                SegmentationOutcome.Unavailable -> "Heart-sound analysis is unavailable on this device."
            }
            MaterialAlertDialogBuilder(this@CorePlayerActivity)
                .setTitle("Heart Sound Analysis")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** Positions come as sample indices at 2000 Hz, so seconds = index / 2000. */
    private fun report(r: SegmentationResult, lowConfidence: Boolean): String {
        binding.graphView.setHeartSoundMarkers(
            s1Sec = r.s1PeakSamples2k.map { it / 2000f },
            s2Sec = r.s2PeakSamples2k.map { it / 2000f }
        )
        val systolic = r.systolicIntervalsMs
        return buildString {
            if (lowConfidence) append("LOW CONFIDENCE — few complete cycles, consider a retake.\n\n")
            append("Heart rate: ${r.heartRateBpm?.let { "%.0f bpm".format(it) } ?: "n/a (fewer than 2 beats)"}\n")
            append("Cardiac cycles: ${r.numCycles}\n")
            append("Duration: ${"%.1f".format(r.durationSec)} s\n")
            append("Avg S1 to S2 (systole): ${if (systolic.isEmpty()) "n/a" else "%.0f ms".format(systolic.average())}\n")
            append("S1 found: ${r.s1PeakSamples2k.size} · S2 found: ${r.s2PeakSamples2k.size}\n\n")
            append("The graph now shows red S1 and green S2 markers — drag it to see them all.")
        }
    }
}
```

### 5.13 Final file list (check yours matches)

```
StemzSdkTest/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    ├── libs/
    │   ├── taal-stemz-core.aar
    │   └── taal-stemz-ui-kit.aar
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/stemzsdktest/
        │   ├── MainActivity.kt
        │   ├── CoreDemoActivity.kt
        │   ├── CoreRecorderActivity.kt
        │   ├── CorePlayerActivity.kt
        │   └── PcgGraphView.kt
        └── res/
            ├── layout/activity_main.xml
            ├── layout/activity_core_demo.xml
            ├── layout/activity_core_recorder.xml
            ├── layout/activity_core_player.xml
            ├── values/themes.xml
            ├── values/colors.xml
            ├── color/filter_icon_selector.xml
            ├── color/heart_filter_toggle_bg.xml
            ├── color/heart_filter_toggle_text.xml
            ├── drawable/bg_bottom_action_button.xml
            ├── drawable/bg_pill_off.xml
            ├── drawable/bg_pill_on.xml
            ├── drawable/bg_record_button.xml
            ├── drawable/ic_analyze.xml
            ├── drawable/ic_arrow_back.xml
            ├── drawable/ic_custom_filter.xml
            ├── drawable/ic_folder.xml
            ├── drawable/ic_heart.xml
            ├── drawable/ic_info.xml
            ├── drawable/ic_play_circle.xml
            ├── drawable/ic_record_start.xml
            ├── drawable/ic_record_stop.xml
            ├── drawable/ic_taal.xml
            └── drawable/ic_volume_up.xml
```

---

## 6. Build and install

1. **Build → Rebuild Project** — must end with **BUILD SUCCESSFUL**. If not → **STOP**.
2. On the phone: Settings → About phone → tap **Build number** 7 times → back →
   Developer options → turn on **USB debugging**.
3. Connect the phone to the computer with USB, accept the "Allow USB debugging" prompt.
4. Select the phone in the device dropdown and press **Run ▶**.
5. The app "Stemz SDK Test" opens with three buttons.
6. Unplug the phone from the computer and plug the **TAAL device** into the phone via OTG
   (the phone can only use one USB connection at a time). Start the app from the launcher.

---

## 7. Test checklist

Tick each item, and for anything that fails write down **what you did, what you expected, what
happened** (with a screenshot if possible).

### 7.1 Button 1 — Open UI Kit

| # | Check | Pass? |
|---|---|---|
| 1 | Tapping the button opens the **TAAL Recorder** screen; the microphone permission prompt appears the first time | |
| 2 | TAAL device icon (top bar) is **teal** with the TAAL plugged in, **dark grey** when unplugged | |
| 3 | Filter toggle shows **Lite 20–250 Hz** (selected) and **Hard 20–200 Hz**; the Custom icon in the top bar opens a Hz range panel | |
| 4 | Info (i) button shows the heart placement picture | |
| 5 | Pre-amp slider starts at **10 dB** and moves 0–30 | |
| 6 | Start Recording: live graph with **1 large box = 1 s** grid and second labels; timer runs; BPM appears after a few seconds | |
| 7 | Recording **stops by itself at 15 s** and opens **Review Recording** | |
| 8 | Review: whole recording scrollable by dragging (no pinch-zoom), **Clean Graph ON/OFF** changes the trace, Play plays the sound | |
| 9 | Discard → asks to confirm → back to the recorder | |
| 10 | Record again → Save → type a name → Save → lands on **Saved Recordings** with the new item | |
| 11 | Share on an item → share sheet offers **two files: `<name>.wav` and `<name>.pdf`**; the PDF shows the whole recording in 5-second rows on the grid | |
| 12 | Play on an item → review screen titled with the saved name → tap the **Analyze Heart Sounds icon** (top bar, right) → report with heart rate / cycles; **"Full Segmentation View (Full Screen)"** opens the landscape chart | |
| 13 | Delete on an item → confirm → it disappears | |
| 14 | Back out to the test app's main screen → it shows **"UI kit saved: …_filtered.wav"** | |
| 15 | Phone's file manager → **Music/Stemz Recordings/** contains the saved `_filtered.wav` and `_raw.wav` | |

### 7.2 Button 2 — Core only (our own screen)

(Tip: use headphones for check 11, otherwise the phone speaker can feed back into the stethoscope.)

| # | Check | Pass? |
|---|---|---|
| 1 | Screen opens; top line shows **TAAL device: CONNECTED** (or NOT_CONNECTED) and updates when you plug/unplug | |
| 2 | Start recording with **Lite**: live graph draws on our own grid, jumps to the next 4-second page when full | |
| 3 | Timer and **BPM** update while recording | |
| 4 | Recording **stops by itself** after the Auto-stop seconds (default 15); try another value, e.g. 8 | |
| 5 | Repeat with **Hard**, and with **Custom** (e.g. 20 / 400 Hz); Custom with Low ≥ High shows an error instead of recording | |
| 6 | Moving the pre-amp slider while recording makes the **sound louder** (check with Play afterwards) but does **not** change the graph size much | |
| 7 | After the stop, the graph shows the whole recording; **drag** scrolls it; the **Clean Graph** checkbox visibly changes the trace | |
| 8 | **Play** plays the recording; tapping again stops it | |
| 9 | **Analyze heart sounds** shows heart rate + cycles (or "retake" for a bad recording) | |
| 10 | Starting a recording with the TAAL unplugged shows "Connect the TAAL device first" | |
| 11 | With **"Hear sound while recording"** ticked you **hear the heart sounds live** while recording; unticked, you don't | |
| 12 | After **Analyze heart sounds**: the text shows heart rate, cycles, duration, average S1→S2 time and S1/S2 counts, and the graph shows **red S1** and **green S2** markers (drag to see all) | |

### 7.3 Button 3 — Core only: Recorder + Player (UI-kit look)

(Tip: use headphones — this recorder always plays the sound while recording, like the UI kit.)

| # | Check | Pass? |
|---|---|---|
| 1 | Opens a **TAAL Recorder** screen that looks like the UI kit's (compare with Button 1): info icon, title, Custom icon, TAAL icon, teal timer `00:00:00`, Heart Filter card, pre-amp card, graph, round red record button, blue folder bar | |
| 2 | TAAL icon is **teal** with the TAAL plugged in, **dark grey** when unplugged, and changes when you plug/unplug | |
| 3 | **Lite** is selected; tapping **Hard** selects it; the **Custom** icon turns red and opens the Hz range panel (slider and Low/High boxes stay in sync); tapping it again closes it and goes back to Lite | |
| 4 | Info (i) shows the heart placement text | |
| 5 | Start Recording: live graph on the 1 s / 0.2 s grid with second labels, jumps to a new page every 4 s; timer runs; **BPM** appears; you **hear the heart sounds**; filter buttons and pre-amp are dimmed and locked; folder bar hidden | |
| 6 | Recording **stops by itself at 15 s** and opens **Review Recording** (also when you tap stop early) | |
| 7 | Custom with Low ≥ High (e.g. 500 / 100) shows a "Custom Filter" error instead of recording | |
| 8 | Player: duration shown; whole recording on the grid, **drag** scrolls it (no zoom); **Clean Graph ON/OFF** changes the trace | |
| 9 | Play: sound plays, the button turns to stop, the graph **follows** the sound; tapping again stops; at the end it resets to the start | |
| 10 | The volume slider changes playback loudness | |
| 11 | **Discard** → confirm → back to the recorder | |
| 12 | Record again → **Save** → name dialog (date/time pre-filled) → Save → "Recording saved!"; the title becomes the name, Discard/Save disappear, the **Analyze** icon appears top right | |
| 13 | **Analyze** → "Heart Sound Analysis" report (heart rate, cycles, duration, S1→S2, S1/S2 counts, or "retake") and **red S1 / green S2** markers on the graph | |
| 14 | Back → recorder → **folder** bar → "Saved Recordings" lists the saved name (e.g. `20260929_101500 (Lite)`); tapping it opens the player in the saved view with Analyze | |
| 15 | Phone's file manager → **Music/Stemz Recordings/** contains the new `_filtered.wav` and `_raw.wav` | |
| 16 | Back during a recording shows "Stop the recording before going back" | |

---

## 8. FAQ (questions from the first test run)

**Can the UI kit's 15-second auto-stop be changed?** Yes — it is the `autoStopSeconds` value
(1–300) in the `TaalRecorderActivity.getIntent(...)` call in `MainActivity.kt`. Leave it at 15 for
this test so checklist 7.1 #7 stays valid; any value works in a real app. `preFilter`,
`preAmplification`, `publicFolderName` and `heartSoundAnalysis` are set the same way.

**How do I hear the sound while recording on my own (core-only) screen?** `TaalRecorder` has no
"listen" switch. Every buffer it records arrives in `onProgressUpdate(...)`; play those samples
through a standard Android `AudioTrack` — see `playToMonitor()` / `createMonitorTrack()` in
`CoreDemoActivity.kt`. (The UI kit's recorder does the same internally.) Use the normal
**blocking** `track.write(pcm, 0, pcm.size)`. Do **not** use `AudioTrack.WRITE_NON_BLOCKING`: when
the speaker buffer is full it silently drops the rest of the buffer, and you hear crackle/noise
while recording even though the saved file is perfect (found in the second test run).

**Is there a heart-sound analysis screen for core-only apps?** The full report screen (with the
"Full Segmentation View") is part of `taal-stemz-ui-kit`. From core alone you get all the
analysis data and draw it yourself: `TaalCardiacSegmentation.segmentRawWav(rawFile)` returns a
`SegmentationOutcome`; on `Ok`/`TooWeak` its `result` has `heartRateBpm`, `numCycles`,
`durationSec`, `systolicIntervalsMs`, and the S1/S2 positions `s1PeakSamples2k` /
`s2PeakSamples2k` (sample indices at 2000 Hz → seconds = index / 2000). `CoreDemoActivity.describe()`
shows the text report and `PcgGraphView.setHeartSoundMarkers()` draws the markers.

**Can we build the UI kit's screens ourselves with only the core?** Yes — Button 3 is exactly
that: `CoreRecorderActivity` + `CorePlayerActivity` reproduce the UI kit's Recorder and
Review/Player screens with their own layouts, icons and code, calling only `taal-stemz-core`
(`TaalRecorder`, `TaalPlayer`, `PcgTimeScale`, `PcgAmplitudeScale`, `PcgLiveDisplayFilter`,
`PcgDisplayFilter`, `HeartBpmCalculator`, `PcgWavDecoder`, `TaalCardiacSegmentation`,
`TaalConnectionBroadcastReceiver`, `SurrUtils`). Differences from the UI kit, on purpose: the
placement info is text (the UI kit shows a picture), the Save name and Saved Recordings list are
dialogs instead of full screens, there is no Share button, and the analysis report is a dialog +
graph markers instead of the UI kit's report screen with the full-screen segmentation chart.

---

## 9. What to send back

1. All three checklists (7.1, 7.2, 7.3) with pass/fail.
2. For every **STOP** you hit: step number, what you did, exact error text / screenshot.
3. Anything in this guide that was unclear, even if you worked it out.
4. Phone model(s) and Android version(s) tested.
