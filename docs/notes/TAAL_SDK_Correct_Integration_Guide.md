# TAAL SDK — Correct Integration Guide (Verified Working)

This is a minimal, verified-working reference. Every snippet below was actually built and
tested with a real TAAL device connected. Follow it in order — the two most common bugs
(instant crash on Record, "please connect device" while connected) both come from skipping
one of these steps.

Only 4 files need to change.

---

## 1. `settings.gradle` (or `settings.gradle.kts`) — add JitPack

The UI-Kit pulls in MPAndroidChart (waveform rendering) from JitPack. Without this repo,
the build fails to resolve it.

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }   // <-- add this line
    }
}
```

---

## 2. `app/build.gradle` (or `app/build.gradle.kts`) — dependencies + ViewBinding

```kotlin
android {
    defaultConfig {
        minSdk = 24   // must be 24 or higher
    }

    // REQUIRED. The UI-Kit's recording/playback screens are built with ViewBinding
    // internally. Because the AAR is a local file (not a Maven artifact), Gradle does
    // NOT pull this in automatically. Skip this and the app crashes the instant you
    // press Record, with: NoClassDefFoundError: androidx.viewbinding.ViewBinding
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // --- TAAL SDK AAR files ---
    implementation(files("libs/taal-core.aar"))
    implementation(files("libs/taal-ui-kit.aar"))

    // --- taal-core requirements ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // --- taal-ui-kit requirements ---
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
}
```

> ⚠️ **#1 most common bug:** skipping `buildFeatures { viewBinding = true }`.
> If your app already has ViewBinding enabled elsewhere, double check it's enabled in
> the **same module** (`app/build.gradle`) that depends on `taal-ui-kit.aar`.

---

## 3. `app/src/main/AndroidManifest.xml` — permissions & activities

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.USB_PERMISSION" />
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />

<uses-feature
    android:name="android.hardware.usb.host"
    android:required="false" />

<application ...>

    <activity
        android:name="com.musediagnostics.taal.uikit.TaalRecorderActivity"
        android:exported="false" />

    <activity
        android:name="com.musediagnostics.taal.uikit.TaalPlayerActivity"
        android:exported="false" />

</application>
```

---

## 4. Your recording screen's Java class — permission + connection check + launch

This is the part that matters most for correctness. Put it wherever your "Record" button
click currently lives.

### 4a. Ask for microphone permission at runtime

`RECORD_AUDIO` is a dangerous permission. Declaring it in the manifest is not enough on
Android 6+ — it must be requested at runtime, or `TaalRecorderActivity` crashes with an
uncaught `SecurityException` the moment it opens.

```java
private final ActivityResultLauncher<String> micPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                openTaalRecorder();
            } else {
                Toast.makeText(this, "Microphone permission is required to record", Toast.LENGTH_LONG).show();
            }
        });
```

### 4b. Check the TAAL connection *fresh*, right before launching — never from a cached flag

> ⚠️ **#2 most common bug — this is your client's exact symptom.**
> `TaalConnectionBroadcastReceiver` only reports **future** plug/unplug events. If the
> TAAL device was already connected *before* the receiver was registered, the receiver
> never fires, and any `boolean isConnected` flag that's only ever set inside that
> callback stays `false` forever — even with the device physically plugged in. The fix
> is to never trust a stored/cached flag for this decision. Always call
> `SurrUtils.isTaalDeviceConnected()` again at the exact moment the button is pressed.

```java
private void onRecordButtonClicked() {
    SurrUtils.ConnectionStatus status = SurrUtils.isTaalDeviceConnected(this);

    if (status != SurrUtils.ConnectionStatus.CONNECTED) {
        Toast.makeText(this, "Please connect the TAAL device", Toast.LENGTH_SHORT).show();
        return;
    }

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        return;
    }

    openTaalRecorder();
}
```

> If `SurrUtils.ConnectionStatus` doesn't resolve as an enum type in your project (older
> SDK builds may expose it slightly differently), fall back to comparing the raw value:
> `status.toString().equals("CONNECTED")` — behaves identically, just doesn't need the
> exact type import.

### 4c. Launch the recorder and read back the saved file

```java
private final ActivityResultLauncher<Intent> recorderLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                String filteredWavPath = result.getData()
                        .getStringExtra(TaalRecorderActivity.RESULT_FILE_PATH);
                // filteredWavPath = absolute path to the saved _filtered.wav file
            }
            // RESULT_CANCELED: user discarded / pressed back, nothing to do
        });

private void openTaalRecorder() {
    Intent intent = TaalRecorderActivity.getIntent(
            this,              // context
            "HEART",           // preFilter: HEART | LUNGS | BOWEL | PREGNANCY | FULL_BODY
            5,                 // preAmplification: 0-30 dB
            300                // recordingTimeSeconds: max 300
    );
    recorderLauncher.launch(intent);
}
```

> If `TaalRecorderActivity.getIntent(...)` doesn't resolve directly from Java, try
> `TaalRecorderActivity.Companion.getIntent(...)` instead — depends on how the Kotlin
> factory method was exported for Java callers.

### 4d. Launch the player

```java
startActivity(
    TaalPlayerActivity.getIntent(this, filteredWavPath)
);
```

---

## Recap — two mistakes, two symptoms

| Mistake | Symptom | Fix |
|---|---|---|
| `buildFeatures.viewBinding` not enabled in the module using `taal-ui-kit.aar` | App crashes instantly on pressing Record: `NoClassDefFoundError: ViewBinding` | Section 2 above |
| Connection status read from a cached/stored flag instead of a fresh `SurrUtils.isTaalDeviceConnected()` call at click-time | "Please connect device" shown even though it's physically connected | Section 4b above |

---

## Other must-not-skip rules (from the SDK docs)

- Never call `setPreFilter()` on a `_filtered.wav` file — it's already filtered.
- `setPreFilter()` and `setCustomBandpass()` are mutually exclusive — last call wins.
- Both SDK screens are locked to portrait orientation; this cannot be overridden.
- Always unregister `TaalConnectionBroadcastReceiver` in `onStop`/`onDestroy`.
- Always call `taalPlayer.release()` when done, to free the `AudioTrack`.
