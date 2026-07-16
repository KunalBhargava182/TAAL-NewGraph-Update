## **TAAL Digital Stethoscope SDK** 

**Integration Guide for Android** _taal-core + taal-ui-kit_ 

**Version: 2.0.0** Date: March 2026 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

## **Version History** 

||||
|---|---|---|
|**Version**|**Date**|**Description**|
||||
|**2.0.0**|March 2026<br>(Updated July<br>2026)|Major release. New taal-core.aar and taal-ui-kit.aar. Package<br>rename from in.museinc.android.surr_core to<br>com.musediagnostics.taal. Pre-amp extended to 0-30 dB.<br>Dual file output (raw + filtered). BPM calculation.<br>setPreFilter added to TaalPlayer. Max recording time 300<br>seconds. Portrait-only orientation enforced.<br>**Update:** Added custom bandpass filter (setCustomBandpass).<br>Added on-screen acoustic placement diagrams. Saved<br>recordings now also copied to device storage, with<br>Play/Share/Delete actions on the saved recordings list.<br>Improved recording reliability on tablet devices.|
||||
|1.0.2|Oct 29, 2021|UI-Kit TAAL Player loop fix. UI changes. Background<br>operation stopped on screen exit.|
||||
|1.0.1|Oct 04, 2021|Added Custom pre filter. Added detailed error messages.<br>Bug/issue fixing.|
||||
|1.0.0|Sep 08, 2021|First edition (surr-core + surr-ui-kit).|



## **1.  Introduction** 

The MUSE Diagnostics TAAL SDK for Android provides complete functionality to record, process, and play back audio from the TAAL digital stethoscope device. It is designed for Android developers integrating stethoscope functionality into clinical applications. 

The SDK ships as two .aar files: 

- taal-core.aar — Pure audio engine with no UI dependency 

- taal-ui-kit.aar — Pre-built recording and playback UI (depends on taal-core) 

## **2.  SDK Types** 

## **2.1 taal-core** 

Provides core recording and playback functionality with no pre-built UI. Use this when you want full control over your own interface. 

**Package:** com.musediagnostics.taal 

## **2.2 taal-ui-kit** 

Provides pre-built Activities and Fragments for recording and playback with a complete waveform UI. Depends on taal-core. 

**Package:** com.musediagnostics.taal.uikit 

_Confidential — Property of MUSE Diagnostics_ 

Page 2 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

## **3.  Migration from surr-core / surr-ui-kit** 

**IMPORTANT:** This section is required reading if you previously used surr-core.aar or surr-ui.aar. 

## **3.1  AAR File Rename** 

|||
|---|---|
|**Old File**|**New File**|
|||
|surr-core.aar|taal-core.aar|
|||
|surr-UI-Kit.aar|taal-ui-kit.aar|



## **3.2  Package Name Changes** 

All imports must be updated. Replace every old import with the new one: 

|||
|---|---|
|**Old Package (surr-core)**|**New Package (taal-core)**|
|||
|in.museinc.android.surr_core.recorder.TaalRecord<br>er|com.musediagnostics.taal.TaalRecorder|
|||
|in.museinc.android.surr_core.recorder.PreFilter|com.musediagnostics.taal.PreFilter|
|||
|in.museinc.android.surr_core.recorder.TaalRecord<br>erState|com.musediagnostics.taal.core.RecorderState|
|||
|in.museinc.android.surr_core.player.TaalPlayer|com.musediagnostics.taal.TaalPlayer|
|||
|in.museinc.android.surr_core.utils.SurrUtils|com.musediagnostics.taal.utils.SurrUtils|
|||
|in.museinc.android.surr_core.utils.TaalConnection<br>BroadcastReceiver|com.musediagnostics.taal.utils.TaalConnectionBroa<br>dcastReceiver|
|||
|in.museinc.android.surr_core.exceptions.*|com.musediagnostics.taal.TaalDisconnectedExcepti<br>on etc.|



## **3.3  API Changes** 

||||
|---|---|---|
|**Area**|**Old (surr-core)**|**New (taal-core)**|
||||
|Pre-amp range|0–10 dB|0–30 dB|
||||
|Filtered file method|setPreFilteredAudioFilePath()|setFilteredAudioFilePath()|
||||
|Player filter|No setPreFilter() on TaalPlayer|setPreFilter(PreFilter) added|
||||
|File output|Single raw .wav|Two files: _raw.wav + _filtered.wav|
||||
|Screen orientation|Not locked|Portrait only (cannot be overridden)|
||||
|RecorderState enum|TaalRecorderState|RecorderState|
||||
|Max recording time|Not specified|300 seconds (5 minutes)|



_Confidential — Property of MUSE Diagnostics_ 

Page 3 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

## **3.4  Manifest Changes** 

Remove old activity declarations and add the new ones: 

<!-- REMOVE these old declarations --> 

<activity android:name="in.museinc.android.surr_ui.RecorderActivity" ... /> <activity android:name="in.museinc.android.surr_ui.PlayerActivity" ... /> 

<!-- ADD these new declarations --> <activity android:name="com.musediagnostics.taal.uikit.TaalRecorderActivity" ... /> <activity android:name="com.musediagnostics.taal.uikit.TaalPlayerActivity" ... /> 

## **4.  Features** 

- Record audio from the TAAL USB stethoscope device 

- Real-time audio filtering (Heart, Lungs, Bowel, Pregnancy, Full Body) 

- Pre-amplification control (0–30 dB) 

- Real-time waveform visualization during recording 

- BPM calculation (Heart filter) 

- Dual file output: raw .wav + filtered .wav written simultaneously in real-time 

- Pre-built UI for recording and playback (taal-ui-kit) 

- Saved recordings library 

- USB device connection/disconnection detection 

- Portrait-only orientation enforced on all SDK Activities 

- Custom frequency range filter — user-defined low/high cutoff in Hz 

- On-screen acoustic placement diagrams — info ("i") button on the recording screen shows anatomical stethoscope-placement guidance per filter 

- Saved recordings automatically copied to device storage (Music/Taal Saved Audios) in addition to app-private storage 

- Saved recordings list with Play, Share, and Delete actions per recording 

- Improved recording reliability on tablet devices across all brands 

## **5.  Specifications** 

|||
|---|---|
|**Item**|**Description**|
|||
|**Development Environment**|Android Studio Hedgehog or later|
|||
|**Minimum SDK API**|API 24 (Android 7.0)|
|||
|**Target SDK**|API 34|
|||
|**Development Language**|Kotlin|
|||
|**Supported Interfaces**|USB (OTG-Compatible Android Devices)|
|||
|**Audio Format**|WAV, 16-bit PCM, Mono, 44,100 Hz|
|||
|**Pre-amp Range**|0–30 dB|
|||
|**Custom Filter Range**|1 Hz – 24,000 Hz (user-defined low/high cutoff)|
|||
|**Max Recording Time**|300 seconds (5 minutes) per session|



_Confidential — Property of MUSE Diagnostics_ 

Page 4 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

## **6.  Folder Structure** 

TAAL SDK ├── Library │   ├── taal-core.aar │   └── taal-ui-kit.aar └── Documents └── TAAL_SDK_Integration_Guide.pdf 

## **7.  Setup & Installation** 

The TAAL SDK is built and designed to be used with Android Studio. The following instructions will help you to integrate the TAAL SDK into your application. 

## 

## 

1. Create the app/libs/ directory inside your app module 

2. Copy both AAR files into app/libs/: 

   - taal-core.aar 

   - taal-ui-kit.aar 

## **Step 2: Configure Repositories (settings.gradle.kts)** 

The UI-Kit uses MPAndroidChart for waveform rendering, hosted on JitPack. 

dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) repositories { google() mavenCentral() maven { url = uri("https://jitpack.io") }  // Required for MPAndroidChart } } 

## **Step 3: Add Dependencies (app/build.gradle.kts)** 

android { defaultConfig { minSdk = 24  // Must be 24 or higher } } dependencies { // TAAL SDK AAR files implementation(files("libs/taal-core.aar")) implementation(files("libs/taal-ui-kit.aar")) 

_Confidential — Property of MUSE Diagnostics_ 

Page 5 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

// taal-core requirements 

implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3") implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3") 

// taal-ui-kit requirements 

implementation("androidx.recyclerview:recyclerview:1.3.2") implementation("androidx.navigation:navigation-fragment-ktx:2.7.6") implementation("androidx.navigation:navigation-ui-ktx:2.7.6") implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0") implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0") implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0") implementation("com.google.android.material:material:1.11.0") implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")  // Waveform chart } 

## **Step 4: AndroidManifest.xml** 

Add the required permissions and declare both SDK activities. Use tools:replace to resolve manifest merger conflicts. 

<?xml version="1.0" encoding="utf-8"?> <manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"> 

<!-- Required permissions --> <uses-permission android:name="android.permission.RECORD_AUDIO" /> <uses-permission android:name="android.permission.USB_PERMISSION" /> <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" /> <uses-feature 

android:name="android.hardware.usb.host" 

android:required="false" /> 

<application android:allowBackup="true" android:icon="@mipmap/ic_launcher" android:label="@string/app_name" android:theme="@style/Theme.YourApp" tools:replace="android:allowBackup,android:label,android:theme,android:icon"> 

<!-- Your main activity --> <activity android:name=".MainActivity" android:exported="true"> <intent-filter> <action android:name="android.intent.action.MAIN" /> <category android:name="android.intent.category.LAUNCHER" /> </intent-filter> </activity> 

<!-- SDK Activities — set exported=false for security --> <activity android:name="com.musediagnostics.taal.uikit.TaalRecorderActivity" android:exported="false" tools:replace="android:exported" /> 

_Confidential — Property of MUSE Diagnostics_ 

Page 6 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

<activity android:name="com.musediagnostics.taal.uikit.TaalPlayerActivity" android:exported="false" tools:replace="android:exported" /> </application> </manifest> 

**Note:** Both SDK activities are locked to portrait orientation. This cannot be overridden. 

**Note:** The WRITE_EXTERNAL_STORAGE permission (maxSdkVersion 28) is only required if you want saved recordings copied to the device's Music folder on Android 7–9 (API 24–28). It is not required on Android 10+ (API 29+). The UI-Kit requests this permission automatically at runtime the first time the user saves a recording — if denied, the recording is still saved to app-private storage. 

## **8.  SDK Reference — TaalRecorder** 

TaalRecorder is the core class for recording audio from the TAAL device. 

## **Import:** 

import com.musediagnostics.taal.TaalRecorder import com.musediagnostics.taal.PreFilter import com.musediagnostics.taal.core.RecorderState 

**Note:** Audio capture has been hardened for tablet devices (audio source fallback chain, system Automatic Gain Control / Noise Suppression / Echo Cancellation disabled, USB device explicitly locked on API 28+). This is fully internal — no public API changed, and it applies automatically on all supported Android versions and both phones and tablets. 

## **8.1 State Diagram** 

Recording from the TAAL device is managed as a state machine. The following shows the lifecycle and states of a TaalRecorder object: 

INITIAL ──► start() ──► RECORDING ──► stop() ──► STOPPED ▲                                                  │ └──────────────────── reset() ────────────────────┘ 

Configuration methods valid in INITIAL state: setRawAudioFilePath(filePath) setFilteredAudioFilePath(filePath) setPlayback(playbackFlag) setRecordingTime(timeInSeconds) setPreFilter(preFilter) setCustomBandpass(lowCut, highCut) setPreAmplification(preAmpInDB) 

**Note:** setPreFilter() and setCustomBandpass() both configure the same internal bandpass filter — call only one of them per recording session. Whichever is called last takes effect. 

## **8.2  Common Usage** 

val taalRecorder = TaalRecorder(context) 

// Required: set raw audio output path taalRecorder.setRawAudioFilePath("/path/to/recording_raw.wav") 

// Recommended: set filtered audio output path (written in real-time) taalRecorder.setFilteredAudioFilePath("/path/to/recording_filtered.wav") 

taalRecorder.setRecordingTime(300)   // Max duration in seconds (default 30) 

_Confidential — Property of MUSE Diagnostics_ 

Page 7 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

taalRecorder.setPlayback(false)       // Earpiece monitoring (reserved for future use) taalRecorder.setPreFilter(PreFilter.HEART) taalRecorder.setPreAmplification(5)  // 0–30 dB 

// Alternative to setPreFilter(): a user-defined frequency range. // Do not call both — the last one called wins. // taalRecorder.setCustomBandpass(20.0, 1000.0)  // lowCut, highCut in Hz 

taalRecorder.start()  // Throws TaalDisconnectedException if no device connected 

// ... recording in progress ... 

taalRecorder.stop() taalRecorder.reset() 

## **8.3  Public Methods** 

||||
|---|---|---|
|**Method Name**|**Valid States**|**Description**|
||||
|setRawAudioFilePath(path:<br>String)|INITIAL|Sets output path for the raw (unfiltered) WAV<br>file. Must end in .wav.|
||||
|setFilteredAudioFilePath(path:<br>String)|INITIAL|Sets output path for the filtered WAV file.<br>Written in real-time during recording. Must<br>end in .wav.|
||||
|setRecordingTime(seconds: Int)|INITIAL|Maximum recording duration. Minimum 1<br>second. Default 30.|
||||
|setPlayback(enabled: Boolean)|INITIAL|Reserved for future earpiece monitoring.<br>Currently accepted but has no effect.|
||||
|setPreFilter(filter: PreFilter)|INITIAL|Sets the acoustic filter preset. Options:<br>HEART, LUNGS, BOWEL, PREGNANCY,<br>FULL_BODY.|
||||
|setCustomBandpass(lowCut: Double,<br>highCut: Double)|INITIAL|Sets a custom bandpass filter range in Hz,<br>overriding the preset filter. lowCut is clamped to<br>a minimum of 1 Hz, highCut to a maximum of<br>24,000 Hz. If highCut ends up ≤ lowCut, the call<br>is silently ignored and the previous filter<br>configuration remains active. Mutually exclusive<br>with setPreFilter() — the last call wins.|
||||
|setPreAmplification(db: Int)|Any|Sets pre-amplification gain in dB. Range: 0–30<br>dB. Can be updated during recording.|
||||
|getState()|Any|Returns current RecorderState: INITIAL,<br>RECORDING, or STOPPED.|
||||
|setOnInfoListener()|Any|Sets the OnInfoListener for state and progress<br>callbacks.|
||||
|start()|INITIAL|Starts recording. Throws<br>TaalDisconnectedException if TAAL device is<br>not connected.|
||||
|stop()|RECORDING|Stops recording and finalizes both output files.|
||||
|reset()|INITIAL,<br>STOPPED|Resets recorder back to INITIAL state. Clears<br>all paths and settings.|



_Confidential — Property of MUSE Diagnostics_ 

Page 8 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

## **8.4  OnInfoListener — State & Progress Updates** 

Register to receive state changes and live audio data during recording: 

taalRecorder.onInfoListener = object : TaalRecorder.OnInfoListener { override fun onStateChange(state: RecorderState) { when (state) { RecorderState.RECORDING -> { /* recording started */ } RecorderState.STOPPED   -> { /* recording finished */ } else -> {} } } override fun onProgressUpdate( sampleRate: Int, bufferSize: Int, timeStamp: Double,   // Elapsed recording time in seconds data: FloatArray     // Filtered float samples, range -1.0 to +1.0 ) { // Called on IO thread — switch to Main thread for any UI updates } } 

## **8.5  OnLiveStreamListener — Raw Byte Stream** 

Receive a raw byte stream of filtered audio (e.g. for network streaming or custom processing): 

taalRecorder.onLiveStreamListener = object : TaalRecorder.OnLiveStreamListener { override fun onNewStream(stream: ByteArray) { 

// 16-bit PCM bytes of the filtered audio — called on IO thread 

} } 

## **8.6  USB Connection Detection** 

Use TaalConnectionBroadcastReceiver to receive live plug/unplug callbacks. Register in onStart/onResume and always unregister in onStop/onDestroy to prevent memory leaks. 

import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver 

val receiver = TaalConnectionBroadcastReceiver( object : TaalConnectionBroadcastReceiver.TaalConnectionListener { override fun onTaalConnect() { // TAAL device plugged in } override fun onTaalDisconnect() { // TAAL device unplugged } } ) 

_Confidential — Property of MUSE Diagnostics_ 

Page 9 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

// In onStart / onResume receiver.register(context) 

// In onStop / onDestroy — always unregister to prevent memory leaks receiver.unregister(context) 

Check connection status on launch (the broadcast receiver misses events that happened before registration): 

import com.musediagnostics.taal.utils.SurrUtils 

val status = SurrUtils.isTaalDeviceConnected(context) // Returns: CONNECTED, NOT_CONNECTED, //          DEVICE_DOES_NOT_SUPPORT_OTG, INVALID_TAAL_CONNECTED 

## **9.  SDK Reference — TaalPlayer** 

TaalPlayer plays back WAV recordings from the TAAL device with real-time DSP filtering. 

## **Import:** 

import com.musediagnostics.taal.TaalPlayer import com.musediagnostics.taal.PreFilter import com.musediagnostics.taal.InvalidFileNameException import com.musediagnostics.taal.dsp.AudioFilterEngine  // Only needed if using setGraphicEQ() 

## **9.1  Common Usage** 

val taalPlayer = TaalPlayer(context) taalPlayer.setDataSource("/path/to/recording.wav") // Throws InvalidFileNameException if not .wav 

// Optional: apply filter for raw files only. // Do NOT call setPreFilter() on _filtered.wav — it is already filtered. if (!filePath.contains("_filtered")) { taalPlayer.setPreFilter(PreFilter.HEART) } taalPlayer.setPreAmplification(0f)  // 0–30 dB, default 0 taalPlayer.setLooping(false) taalPlayer.onPlaybackProgress = { timestamp, data -> // timestamp = elapsed seconds, data = filtered float samples } taalPlayer.onPlaybackComplete = { // Playback finished naturally } taalPlayer.prepare() taalPlayer.start() // ... 

_Confidential — Property of MUSE Diagnostics_ 

Page 10 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

taalPlayer.stop() taalPlayer.release()  // Always call when done to free AudioTrack resources 

## **9.2  Public Methods** 

|||
|---|---|
|**Method**|**Description**|
|||
|setDataSource(filePath: String)|Sets the WAV file path. Throws<br>InvalidFileNameException if the file does not exist or is<br>not .wav.|
|||
|setPreFilter(filter: PreFilter)|Applies an acoustic preset filter during playback. Only call<br>on raw (unfiltered) files.|
|||
|setPreAmplification(db: Float)|Sets playback amplification in dB. Range: 0–30.|
|||
|setGraphicEQ(eqState:<br>AudioFilterEngine.GraphicEQState)|Applies a 5-band graphic EQ during playback.|
|||
|setLooping(loop: Boolean)|Enables continuous looped playback. Default false.|
|||
|prepare()|Initialises the AudioTrack. Must be called before start().|
|||
|start()|Starts playback.|
|||
|stop()|Stops playback.|
|||
|release()|Releases AudioTrack and all resources. Always call when<br>done.|
|||
|reset()|Calls release() and clears the data source.|



## **10.  SDK Reference — TaalRecorderActivity (UI-Kit)** 

TaalRecorderActivity provides a complete pre-built recording screen with waveform, filter chips, pre-amp slider, BPM display, and save/discard flow. 

## **Import:** 

import com.musediagnostics.taal.uikit.TaalRecorderActivity 

## **10.1  Launch the Recorder** 

private val recorderLauncher = registerForActivityResult( ActivityResultContracts.StartActivityForResult() 

) { result -> 

if (result.resultCode == RESULT_OK) { 

val filePath = result.data?.getStringExtra(TaalRecorderActivity.RESULT_FILE_PATH) 

// filePath = absolute path to the saved _filtered.wav file 

- } 

} 

_Confidential — Property of MUSE Diagnostics_ 

Page 11 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

// Launch the recorder recorderLauncher.launch( TaalRecorderActivity.getIntent( context             = this, 

preFilter           = "HEART",  // HEART | LUNGS | BOWEL | PREGNANCY | FULL_BODY preAmplification    = 5,          // 0–30 dB, default 5 recordingTimeSeconds = 300        // Max duration, default 300 ) ) 

**Note:** The preFilter parameter only accepts HEART, LUNGS, BOWEL, PREGNANCY, or FULL_BODY. The Custom filter (user-defined Hz range) is a manual, in-screen-only selection — it cannot be pre-selected at launch. See Section 10.3. 

## **10.2  Activity Result** 

||||
|---|---|---|
|**Result Code**|**Meaning**|**Extra**|
||||
|RESULT_OK|User recorded and saved|RESULT_FILE_PATH → absolute<br>path to _filtered.wav|
||||
|RESULT_CANCELED|User discarded or pressed back|No extra|



## **10.3  Custom Filter (In-Screen)** 

Alongside the five preset filter chips (Heart, Lungs, Bowel, Pregnancy, Full Body), the recording screen includes a sixth "Custom" chip. Selecting it reveals a range slider (0–24,000 Hz) plus two manual Hz entry fields (Low Cut / High Cut) kept in sync with the slider. 

Recording is blocked with an alert dialog if the fields are left blank, set to 0 Hz, or if Low Cut is greater than or equal to High Cut. Like the preset filter chips, the Custom chip and its range panel are automatically disabled while a recording is in progress. 

## **10.4  Acoustic Placement Diagrams** 

The recording screen includes an info ("i") icon in the top bar. Tapping it opens a dialog with a swipeable image carousel (with page indicator dots) showing anatomical stethoscope-placement diagrams for whichever filter is currently selected. 

The images are bundled inside taal-ui-kit.aar as drawable resources — no additional assets or setup are required from the integrator. If no images exist for a given filter, the dialog shows a "No placement images found" fallback message. 

## **10.5  Saved Recordings Screen** 

After saving a recording, the user lands on a Saved Recordings list. Each item has three actions: 

- **Play** — opens the recording in the playback screen 

- **Share** — shares the .wav file via the system share sheet, using a FileProvider bundled inside taal-ui-kit (authority: ${applicationId}.taaluikit.fileprovider). No manifest configuration is required from the integrator. 

- **Delete** — permanently deletes both the _filtered.wav and _raw.wav files for that recording, after a confirmation dialog 

Saved recordings are stored in two places simultaneously — see Section 12 for the file naming format and device storage copy behavior in detail. 



## **11.  SDK Reference — TaalPlayerActivity (UI-Kit)** 

TaalPlayerActivity provides a pre-built playback screen with waveform visualisation, play/stop controls, and amplitude adjustment. 

## **Import:** 

import com.musediagnostics.taal.uikit.TaalPlayerActivity 

## **11.1  Launch the Player** 

startActivity( TaalPlayerActivity.getIntent( context  = this, filePath = "/path/to/recording_filtered.wav" ) ) 

// Pass the _filtered.wav path returned by RESULT_FILE_PATH from TaalRecorderActivity. // The player detects _filtered in the filename and skips preset filters // to avoid double-filtering. 

_Confidential — Property of MUSE Diagnostics_ 

Page 12 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

## **12.  Dual File Output System** 

Every recording produces two files simultaneously: 

|||||
|---|---|---|---|
|**File**|**Suffix**|**Content**|**Use**|
|||||
|**Raw file**|_raw.wav|Unfiltered audio from the<br>TAAL device|Archive / re-<br>processing|
|||||
|**Filtered file**|_filtered.wav|Filter + pre-amp applied in<br>real-time|Playback and<br>waveform display|



## **Why Two Files?** 

The filtered file is written to disk during recording with zero extra CPU cost — the filter computation already happens for the live waveform. This means the player screen is always instant, with no post-recording processing required. 

## **File Paths (Core SDK)** 

val ts = System.currentTimeMillis() val rawPath      = "${context.filesDir}/recording_${ts}_raw.wav" val filteredPath = "${context.filesDir}/recording_${ts}_filtered.wav" 

taalRecorder.setRawAudioFilePath(rawPath) taalRecorder.setFilteredAudioFilePath(filteredPath) 

## **File Paths (UI-Kit)** 

Handled automatically. RESULT_FILE_PATH always returns the _filtered.wav path. Both _raw.wav and _filtered.wav are saved together to filesDir/saved/. 

## **Saved Recording Filename Format** 

Saved recordings embed the filter as a filename prefix: 

{FILTER}_{user-entered name}_filtered.wav {FILTER}_{user-entered name}_raw.wav 

e.g. HEART_20260703_143210_filtered.wav 

No separate metadata (.meta) file is written — the filter is derived entirely from the filename prefix, both for icon display in the Saved Recordings list and for skipping re-filtering during playback. 

## **Device Storage Copy** 

In addition to the app-private copy above, saved recordings are copied to the device's shared storage at Music/Taal Saved Audios/, making them visible in the phone's file manager, other media apps, and when connected to a PC. 

|||
|---|---|
|**Android Version**|**Behavior**|
|||
|API 29+ (Android 10+)|Uses MediaStore. No extra permission required.|
|||
|API 24–28 (Android 7–9)|Requires WRITE_EXTERNAL_STORAGE. The SDK<br>requests this automatically at runtime the first<br>time the user saves a recording.|

This copy is best-effort: if it fails (permission denied, low storage), the recording remains safely saved in app-private storage — no data is lost, and the user sees a toast message if the device copy was skipped. 

## **13.  Exceptions** 

|||
|---|---|
|**Exception**|**When Thrown**|
|||
|TaalDisconnectedException|TaalRecorder.start() called with no TAAL device<br>connected via USB.|
|||
|TaalNotAvailableForUseException|TAAL device is in use by another application.|
|||
|InvalidFileNameException|TaalPlayer.setDataSource() called with a non-.wav path<br>or a file that does not exist.|
|||
|IllegalArgumentException|setRawAudioFilePath() or setFilteredAudioFilePath()<br>called with a path that does not end in .wav.|



_Confidential — Property of MUSE Diagnostics_ 

Page 13 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

|||
|---|---|
|DeviceDoesNotSupportOTGConnection|Thrown when the Android device does not support OTG<br>connection.|
|||
|InvalidTaalConnectedException|Connected TAAL device information does not match<br>MUSE Diagnostics records.|
|||
|IllegalStateException|Calling setup methods (e.g. setPreFilter()) while<br>recording is in progress, or calling start() without setting<br>rawAudioFilePath first.|



## **14.  Miscellaneous Utilities** 

## **14.1  SurrUtils** 

**Import:** import com.musediagnostics.taal.utils.SurrUtils 

||||
|---|---|---|
|**Method**|**Returns**|**Description**|
||||
|SurrUtils.isTaalDeviceConnected(context)|ConnectionStatus|Checks USB connection. Values:<br>CONNECTED, NOT_CONNECTED,<br>DEVICE_DOES_NOT_SUPPORT_OTG,<br>INVALID_TAAL_CONNECTED|
||||
|SurrUtils.readSampleRate(filePath)|Int|Returns the sample rate from a WAV file<br>header.|
||||
|SurrUtils.getFloatBuffer(filePath)|List<Float>|Returns all PCM samples normalised to<br>range -1.0 to +1.0.|



## **14.2  WavCropper (taal-ui-kit)** 

**Import:** import com.musediagnostics.taal.uikit.util.WavCropper 

||||
|---|---|---|
|**Method**|**Returns**|**Description**|
||||
|WavCropper.getDurationSeconds(filePath)|Float|Duration of a WAV file in seconds.|
||||
|WavCropper.getWaveformData(filePath,<br>maxPoints)|FloatArray|Downsampled waveform data for<br>display.|
||||
|WavCropper.cropWav(inputPath,<br>outputPath, startSeconds, endSeconds)|Boolean|Writes a trimmed WAV file from the<br>given time range.|



_Confidential — Property of MUSE Diagnostics_ 

Page 14 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

## **15.  UI-Kit Styling** 

You can override the following color attributes in your color.xml to customize the Player and Recording Activity UI: 

<color name="suk_status_bar">#your_color_code</color> <color name="suk_action_bar">#your_color_code</color> <color name="suk_action_bar_action_text_icon_active">#your_color_code</color> <color name="suk_action_bar_action_text_icon_inactive">#your_color_code</color> <color name="suk_window_background">#your_color_code</color> <color name="suk_primary_text">#your_color_code</color> 

<color name="suk_secondary_text">#your_color_code</color> 

<color name="suk_pre_filter_btn_background">#your_color_code</color> <color name="suk_pre_filter_btn_active">#your_color_code</color> 

<color name="suk_pre_filter_btn_inactive">#your_color_code</color> <color name="suk_pre_amplification_controller_track">#your_color_code</color> <color name="suk_pre_amplification_controller_thumb">#your_color_code</color> <color name="suk_graph_grid">#your_color_code</color> 

<color name="suk_graph_line">#your_color_code</color> 

<color name="suk_player_seek_bar">#your_color_code</color> <color name="suk_play_pause_start_btn">#your_color_code</color> <color name="suk_dialog_btn">#your_color_code</color> 

## **16.  Technical Limitations** 

1. AUX headphones conflict: Android gives higher priority to wired headphone microphones (AUX) than USB. If wired headphones with a built-in microphone are connected, recording will capture from the headphone mic instead of the TAAL device. Use Bluetooth headphones for monitoring — they do not affect USB recording priority. 

2. OTG required: The TAAL device connects via USB. OTG must be enabled on the Android device. Some devices have OTG always on; others require enabling it in Settings. 

3. Portrait only: Both TaalRecorderActivity and TaalPlayerActivity are locked to portrait orientation and cannot be changed. 

4. WAV format only: The SDK only supports .wav files (16-bit PCM, Mono, 44,100 Hz). Passing any other format will throw InvalidFileNameException. 

5. Filtered file WAV header: The _filtered.wav file's WAV header size field is updated asynchronously after recording stops. The file is fully playable immediately, but tools that strictly validate header size may read an incorrect duration until the header update completes (typically within 1–2 seconds). 

6. Custom filter range validation: setCustomBandpass() does not throw an exception for an invalid range (highCut ≤ lowCut after clamping) — the call is silently ignored and the previous filter configuration remains active. Validate lowCut < highCut in your own UI before calling it. 

## **17.  Best Practices to Connect TAAL to Android** 

1. Before using TAAL please make sure you have charged the device properly. Low battery can cause unstable USB connections. 

2. TAAL device has a USB-A port for connectivity. For Android devices with USB-C ports, use a USB-C to USB-A OTG adapter. For Android devices with USB-B ports, use an OTG converter with a USB-A(male) to USB-C(male) cable. 

_Confidential — Property of MUSE Diagnostics_ 

Page 15 

**MUSE Diagnostics | TAAL SDK Integration Guide** 

Version 2.0.0 

3. TAAL device connection requires OTG. Please make sure OTG is enabled on your Android device. On some devices OTG is always enabled; on others you must enable it from the Android Settings screen. 

4. Do not use wired headphones with an inbuilt microphone while recording. Android OS gives AUX audio input higher priority than USB, which causes the SDK to record from the headphone mic instead of TAAL. Use Bluetooth headphones instead — they do not take priority over the TAAL device. 

5. Always call release() on TaalPlayer when your Fragment/Activity is destroyed to free the AudioTrack. 

6. Always unregister TaalConnectionBroadcastReceiver in onDestroy to prevent memory leaks. 

7. Check connection status on launch using SurrUtils.isTaalDeviceConnected(context) in onCreate/onResume — TaalConnectionBroadcastReceiver only fires on plug/unplug events and misses devices already connected before registration. 

8. Do not call setPreFilter() on _filtered.wav files — the filter is already baked into the file. Applying it again produces incorrect audio. 

9. Keep pre-amplification moderate (5–15 dB) for most clinical environments. Values above 20 dB may saturate the signal in noisy settings. 

10. Wrap taalRecorder.start() in a try-catch to handle TaalDisconnectedException and show users a clear message to connect the TAAL device. 

_**This document and the information contained herein are confidential to and the property of MUSE Diagnostics. Unauthorized access, copying and replication are prohibited. This document must not be copied in whole or part by any means, without the written authorization of MUSE Diagnostics.**_ 

_Confidential — Property of MUSE Diagnostics_ 

Page 16 

