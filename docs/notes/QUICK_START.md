# TAAL SDK - Quick Start Guide

## 🚀 Getting Started in 5 Minutes

### Step 1: Open Project in Android Studio

1. Launch **Android Studio**
2. Select **File → Open**
3. Navigate to: `C:\Users\vetar\OneDrive\Desktop\taal-sdk-project`
4. Click **OK**
5. Wait for Gradle sync to complete (may take 2-5 minutes)

### Step 2: Resolve Dependencies

Android Studio will automatically download:
- AndroidX libraries
- Kotlin coroutines
- MPAndroidChart (from JitPack)
- Material Components

**If sync fails**:
- Check internet connection
- Verify JitPack repository in `settings.gradle.kts`
- Try **File → Invalidate Caches / Restart**

### Step 3: Build the SDK

```bash
# In Terminal (Android Studio)
./gradlew :taal-sdk:build
```

**Expected output**:
```
BUILD SUCCESSFUL in 30s
```

### Step 4: Build the Demo App

```bash
./gradlew :demo-app:assembleDebug
```

**Output location**:
`demo-app/build/outputs/apk/debug/demo-app-debug.apk`

### Step 5: Run on Device

1. **Enable USB Debugging** on Android device:
   - Settings → About Phone → Tap "Build Number" 7 times
   - Settings → Developer Options → Enable "USB Debugging"

2. **Connect device** via USB

3. **Click Run** (green play button) in Android Studio
   - Select your device
   - Wait for installation

4. **Grant Permissions**:
   - Allow RECORD_AUDIO when prompted

### Step 6: Test Basic Recording

1. **Plug in TAAL USB stethoscope** to Android device (via OTG adapter if needed)

2. **Open app** → Tap "Start Recording"

3. **Verify**:
   - Timer starts (00:00, 00:01, 00:02...)
   - Waveform shows audio (if device is capturing)
   - Recording stops at 30 seconds

4. **Tap "Play Last Recording"** to hear playback

---

## 🎯 Quick API Examples

### Recording in Your App

```kotlin
// In your Activity
class MyActivity : AppCompatActivity() {

    private lateinit var recorder: TaalRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize recorder
        recorder = TaalRecorder(this)
        recorder.setRawAudioFilePath(filesDir.path + "/recording.wav")
        recorder.setRecordingTime(30)
        recorder.setPreFilter(PreFilter.HEART)

        // Listen to state changes
        recorder.onInfoListener = object : TaalRecorder.OnInfoListener {
            override fun onStateChange(state: RecorderState) {
                when (state) {
                    RecorderState.RECORDING -> Log.d("TAAL", "Recording started")
                    RecorderState.STOPPED -> Log.d("TAAL", "Recording stopped")
                    else -> {}
                }
            }

            override fun onProgressUpdate(
                sampleRate: Int,
                bufferSize: Int,
                timeStamp: Double,
                data: FloatArray
            ) {
                // Update UI with waveform data
                updateWaveformChart(data)
            }
        }

        // Start recording
        findViewById<Button>(R.id.recordButton).setOnClickListener {
            recorder.start()
        }
    }
}
```

### Playing Audio

```kotlin
val player = TaalPlayer(this)
player.setDataSource("/path/to/recording.wav")
player.setGraphicEQ(AudioFilterEngine.GraphicEQState(
    band20Hz = 0f,
    band100Hz = 3f,  // +3dB boost at 100Hz
    band600Hz = -2f  // -2dB cut at 600Hz
))
player.prepare()
player.start()
```

### Using Pre-built Activities

```kotlin
// Recording
val intent = RecorderActivity.getIntent(
    this,
    rawAudioFilePath = filesDir.path + "/recording.wav",
    recordingTime = 30,
    preFilter = PreFilter.LUNGS
)
startActivity(intent)

// Playback
val intent = PlayerActivity.getIntent(this, "/path/to/recording.wav")
startActivity(intent)
```

---

## 🔧 Common Issues & Solutions

### Issue: "AudioRecord initialization failed"

**Cause**: USB device not detected

**Solution**:
1. Check USB connection (use OTG cable if needed)
2. Grant USB permissions in Android settings
3. Verify device supports USB Audio Class
4. Try different USB cable

### Issue: "No recordings found"

**Cause**: Files not created yet

**Solution**:
- Record something first
- Check `context.filesDir` location
- Verify write permissions

### Issue: Waveform not displaying

**Cause**: MPAndroidChart dependency issue

**Solution**:
1. Check `build.gradle.kts` includes JitPack repository
2. Sync Gradle again
3. Clean and rebuild: `./gradlew clean build`

### Issue: Build fails with "R cannot be resolved"

**Cause**: Resources not generated yet

**Solution**:
1. Build → Clean Project
2. Build → Rebuild Project
3. Restart Android Studio

---

## 📱 Testing Checklist

### Before First Use
- [ ] Android Studio installed (Arctic Fox or later)
- [ ] Android device with USB OTG support
- [ ] USB cable / OTG adapter
- [ ] TAAL stethoscope device

### Basic Functionality
- [ ] App installs without errors
- [ ] Permissions granted (RECORD_AUDIO)
- [ ] USB device detected
- [ ] Recording creates WAV file
- [ ] Playback works
- [ ] Filters apply correctly

### Advanced Testing
- [ ] Real-time waveform updates
- [ ] Timer displays correctly
- [ ] EQ sliders change audio
- [ ] Loop mode works
- [ ] Multiple recordings saved

---

## 📚 Next Steps

### For Developers
1. Read `README.md` for full documentation
2. Study `TaalRecorder.kt` API
3. Explore DSP code in `AudioFilterEngine.kt`
4. Customize UI layouts in `res/layout/`

### For Integration
1. Add `taal-sdk` as dependency to your project
2. Copy demo code as starting point
3. Customize filters and UI
4. Test with real patient audio

### For Production
1. Add error reporting (Crashlytics, Sentry)
2. Implement cloud backup for recordings
3. Add HIPAA compliance features
4. Optimize battery usage

---

## 🆘 Getting Help

### Documentation
- `README.md` - Full SDK documentation
- `IMPLEMENTATION_SUMMARY.md` - Technical details
- KDoc comments in source code

### Support
- Email: support@musediagnostics.com
- GitHub Issues: [your-repo-url]

### Community
- Stack Overflow tag: `taal-sdk`
- Discord: [your-discord-link]

---

**Built with ❤️ for better healthcare**

Version 1.0.0 | February 2024
