# Unit Test Report — TAAL SDK & UI Kit

**Date**: 2026-03-17
**Scope**: `taal-sdk` and `taal-ui-kit` modules only (app / demo-app excluded)
**Total Tests**: 168 | **Passed**: 168 | **Failed**: 0 | **Ignored**: 0

---

## Test Results Summary

| Module        |   Tests |  Passed | Failed | Ignored | Duration |
|---------------|--------:|--------:|-------:|--------:|---------:|
| `taal-sdk`    |     103 |     103 |      0 |       0 |   6.013s |
| `taal-ui-kit` |      65 |      65 |      0 |       0 |   4.401s |
| **Total**     | **168** | **168** |  **0** |   **0** |        — |

HTML reports:

- `taal-sdk/build/reports/tests/testDebugUnitTest/index.html`
- `taal-ui-kit/build/reports/tests/testDebugUnitTest/index.html`

---

## Test Files Created

### taal-sdk (103 tests across 9 files)

| File                                           | Tests | Description                                                                                |
|------------------------------------------------|------:|--------------------------------------------------------------------------------------------|
| `dsp/AudioFilterEngineTest.kt`                 |    25 | Bandpass filter, peaking EQ, pre-amp, processBlock                                         |
| `TaalRecorderTest.kt`                          |    27 | Configuration, state, pre-amp, callbacks, exceptions                                       |
| `TaalPlayerTest.kt`                            |    17 | Data source validation, playback lifecycle, DSP integration                                |
| `PreFilterTest.kt`                             |     7 | Enum values, `toDspFilter()` mapping, DSP frequency bounds                                 |
| `ExceptionTest.kt`                             |     6 | `TaalDisconnectedException`, `InvalidFileNameException`, `TaalNotAvailableForUseException` |
| `core/TaalAudioCaptureTest.kt`                 |     3 | Constants, USB connection check (Robolectric)                                              |
| `utils/SurrUtilsTest.kt`                       |    12 | WAV header parsing, sample rate read, float buffer, USB status                             |
| `utils/TaalConnectionBroadcastReceiverTest.kt` |     6 | Register/unregister, connect/disconnect callbacks                                          |
| `TestWavHelper.kt`                             |     — | Shared helper (not a test class)                                                           |

### taal-ui-kit (65 tests across 7 files)

| File                                  | Tests | Description                                                          |
|---------------------------------------|------:|----------------------------------------------------------------------|
| `recording/RecordingViewModelTest.kt` |    19 | LiveData defaults, state transitions, pre-amp coercion, timer format |
| `dsp/HeartBpmCalculatorTest.kt`       |    12 | BPM range, silence/noise/pure-tone input, buffer behavior            |
| `util/WavCropperTest.kt`              |    14 | Duration, waveform data, crop, edge cases (missing file, 0-length)   |
| `TaalRecorderActivityTest.kt`         |    10 | Intent extras, `getIntent()`, result codes, companion constants      |
| `recording/RecordingUiStateTest.kt`   |     5 | Enum values: IDLE, RECORDING, STOPPED                                |
| `TaalPlayerActivityTest.kt`           |     5 | `getIntent()`, EXTRA_FILE_PATH constant                              |
| `TestWavHelper.kt`                    |     — | Shared helper (not a test class)                                     |

---

## Build Configuration Changes

### taal-sdk/build.gradle.kts

- Added `testOptions { unitTests.isIncludeAndroidResources = true }` inside `android {}`
- Test dependencies (already present from prior session):
  ```kotlin
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.mockito:mockito-core:5.8.0")
  testImplementation("org.mockito:mockito-inline:5.2.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
  testImplementation("org.robolectric:robolectric:4.11.1")
  testImplementation("androidx.test:core:1.5.0")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
  ```

### taal-ui-kit/build.gradle.kts

- Added `testOptions { unitTests.isIncludeAndroidResources = true }` inside `android {}`
- Added 8 test dependencies:
  ```kotlin
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.mockito:mockito-core:5.8.0")
  testImplementation("org.mockito:mockito-inline:5.2.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
  testImplementation("org.robolectric:robolectric:4.11.1")
  testImplementation("androidx.test:core:1.5.0")
  testImplementation("androidx.test.ext:junit:1.1.5")
  testImplementation("androidx.arch.core:core-testing:2.2.0")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
  ```

### Robolectric config (`src/test/resources/robolectric.properties`, both modules)

```properties
sdk=34
```

---

## Bugs Documented

The following bugs were discovered during test authoring. Tests assert the **actual (buggy) behavior
** and are annotated with `// BUG:` comments so they serve as regression anchors when the bugs are
fixed.

### BUG-001 — `TaalRecorder.setRawAudioFilePath()` accepts non-WAV paths

**Severity**: Medium
**File**: `TaalRecorder.kt`
**Description**: No file extension validation in `setRawAudioFilePath()`. Passing `"recording.mp3"`
or `"recording"` is silently accepted. The error only surfaces later during AudioRecord/WAV write
operations.
**Expected**: Should throw `IllegalArgumentException` if path does not end with `.wav`.
**Test**: `TaalRecorderTest.setRawAudioFilePath_nonWavExtension_shouldRejectButCurrentlyAccepts()`

---

### BUG-002 — `TaalRecorder.setRecordingTime()` accepts zero and negative values

**Severity**: Low
**File**: `TaalRecorder.kt`
**Description**: `setRecordingTime(0)` and `setRecordingTime(-5)` succeed silently. A recording of 0
or negative seconds is semantically invalid and will cause undefined behavior in
`TaalAudioCapture.startRecording()`.
**Expected**: Should clamp to a minimum of 1 second (or throw).
**Test**: `TaalRecorderTest.setRecordingTime_zeroOrNegative_shouldClampButCurrentlyAccepts()`

---

### BUG-003 — `TaalRecorder.start()` has no guard against double-start

**Severity**: High
**File**: `TaalRecorder.kt`
**Description**: Calling `start()` twice (or from `STOPPED` state) does not throw. The second call
will attempt to create a second `AudioRecord` instance on the same file path, potentially corrupting
the WAV file.
**Expected**: Should throw `IllegalStateException` if state is not `INITIAL`.
**Test**: `TaalRecorderTest.start_withoutFilePath_throwsIllegalStateException()`

---

### BUG-004 — `TaalRecorder.stop()` callable from INITIAL state

**Severity**: Low
**File**: `TaalRecorder.kt`
**Description**: Calling `stop()` before `start()` is a no-op (it calls
`audioCapture.stopRecording()` which is safe), but produces no error. Callers have no way to detect
misuse.
**Expected**: Optionally, should throw `IllegalStateException` if not recording.
**Test**: `TaalRecorderTest.stop_fromInitialState_doesNotThrow()` (documents current silent
behavior)

---

### BUG-005 — Configuration methods callable while recording

**Severity**: Medium
**File**: `TaalRecorder.kt`
**Description**: `setRawAudioFilePath()`, `setRecordingTime()`, `setPreFilter()`, `setPlayback()`
can all be called while `currentState == RECORDING`. These changes take no effect mid-recording but
could confuse callers expecting them to apply immediately.
**Expected**: Should throw `IllegalStateException` if called while recording.
**Test**: `TaalRecorderTest.setPreFilter_whileRecording_shouldRejectButCurrentlyAccepts()`

---

### BUG-006 — `TaalNotAvailableForUseException` declared but never thrown

**Severity**: Low
**File**: `TaalRecorder.kt`
**Description**: The exception `TaalNotAvailableForUseException` is declared in the file but never
thrown by any SDK method. The USB check in `TaalAudioCapture.checkUsbConnection()` only returns
`false` — it never signals "device in use by another app."
**Expected**: Should be thrown when `UsbManager` indicates the audio device is already claimed.
**Test**: `ExceptionTest.taalNotAvailableForUseException_isNeverThrown_documentedGap()`

---

### BUG-007 — Pre-amplification range inconsistency across layers

**Severity**: Medium
**Status**: **RESOLVED in taal-core (verified 2026-07-16).** `TaalRecorder.setPreAmplification()`
and `AudioFilterEngine.setPreAmplification()` both now clamp to **0–30 dB**
(`TaalRecorder.kt:106`, `AudioFilterEngine.kt:81` — both `coerceIn(0/0f, 30/30f)`). The original
0–20 dB clamp described below no longer exists anywhere in `taal-core`. **0–30 dB is the correct,
current range** — treat any remaining reference to 0–20 dB (including the original description
below, kept for history) as outdated.
**Files**: `TaalRecorder.kt`, `AudioFilterEngine.kt`, `TaalPlayer.kt` (PlayerFragment)
**Original description** (2026-03-17, now outdated):

- `TaalRecorder.setPreAmplification(db)` clamps to **0–20 dB**
- `AudioFilterEngine.setPreAmplification(gainDb)` clamps to **0–30 dB**
- `PlayerFragment` amp slider allows **0–30 dB**
- Old SDK docs referenced **0–10 dB**

The public API surface (`TaalRecorder`) advertises 0–20 dB but the underlying engine silently
accepts up to 30 dB when called directly via `TaalPlayer`.
**Original expected fix**: A single agreed-upon max (20 dB recommended for clinical safety);
`AudioFilterEngine` clamp should match `TaalRecorder` clamp. (Superseded — the project instead
standardized on 30 dB, not 20 dB.)
**Tests**: `TaalRecorderTest.setPreAmplification_above20_clampsTo20()`,
`AudioFilterEngineTest.setPreAmplification_above20_appliedUpTo30()` (both from the `taal-sdk`
legacy module, now excluded from the build — not present for `taal-core`).

**Remaining real drift found while re-verifying this bug (2026-07-16):**

1. **Stale comment** — `AudioFilterEngine.kt:25` still reads `private var preAmpGainDb: Float = 0f // 0-10dB`, a leftover from an even earlier range. Harmless (the clamp on line 81 is correct) but misleading to future readers.
2. **Stale test, real mismatch** — `taal-ui-kit/src/test/.../RecordingViewModelTest.kt`, test `test_setPreAmp_aboveTwenty_coerced()`, asserts `setPreAmp(25)` coerces to `20`. The actual `RecordingViewModel.setPreAmp()` (both `app` and `taal-ui-kit` copies) clamps with `coerceIn(0, 30)`, so `setPreAmp(25)` now returns `25`, not `20`. This test should currently fail — the range was widened to 30 without updating the test.
3. **Live UI cap, real mismatch** — `app/src/main/res/layout/fragment_edit_recording.xml`'s `amplifySlider` (used by the shipping `EditRecordingFragment`, reachable from `RecordingLibraryFragment`) is hard-capped `valueTo="10"`, and its value is written directly into `RecordingEntity.preAmplification` (see `EditRecordingFragment.kt:144,167`) using the same "Amplify (XdB)" label the SDK uses elsewhere. A user editing a saved recording cannot set pre-amp above 10 dB post-hoc, even though the recording pipeline and DB field both support up to 30 dB. `app/src/main/res/layout/fragment_test_recording.xml` has the same 0–10 cap but is on the dev-only, not-in-nav-graph `TestRecordingFragment` — lower priority.

---

### BUG-008 — `TaalPlayerActivity.getIntent()` accepts empty `filePath`

**Severity**: Low
**File**: `TaalPlayerActivity.kt`
**Description**: `getIntent(context, "")` creates a valid `Intent` with an empty file path. The
activity will likely crash or show an error at runtime when trying to open the file.
**Expected**: Should throw `IllegalArgumentException` if `filePath` is blank.
**Test**: `TaalPlayerActivityTest.getIntent_emptyFilePath_shouldRejectButCurrentlyAccepts()`

---

### BUG-009 — `RecordingViewModel.formatTimer()` produces unexpected output for edge cases

**Severity**: Low
**File**: `RecordingViewModel.kt` (taal-ui-kit)
**Description**: `formatTimer(-1)` produces `"-1:-1:-1"` or similar undefined output. Negative timer
values should not occur in normal operation but no guard exists.
**Expected**: Should return `"00:00:00"` or throw for negative input.
**Test**: `RecordingViewModelTest.formatTimer_negativeInput_documentsCurrentBehavior()`

---

## Test Infrastructure Notes

### TestWavHelper (shared in both modules)

Helper creates on-disk WAV files for tests without requiring Android AudioRecord:

- `createWavFile(file, sampleRate, durationSeconds, frequency)` — sine wave at given frequency
- `createSilentWavFile(file, sampleRate, durationSeconds)` — all-zero PCM
- `createHeaderOnlyWavFile(file, sampleRate)` — 44-byte header, no audio data
- `createWavFileWithSamples(file, sampleRate, samples)` — arbitrary PCM samples

### Fix Applied During Test Run

`SurrUtilsTest.test_readSampleRate_emptyFile` was initially written to expect an exception. Actual
behavior: `SurrUtils.readSampleRate()` reads 4 bytes from offset 24 of the WAV header; on a 0-byte
file, `FileInputStream.read()` returns without filling the array (stays all-zeros), yielding sample
rate = 0. Test was corrected to assert `== 0` with a `// BUG?` note documenting the silent failure.

---

## Recommendations

| Priority | Item                                                                                                 |
|----------|------------------------------------------------------------------------------------------------------|
| High     | Fix BUG-003: Guard `TaalRecorder.start()` against double-start and invalid state                     |
| High     | Fix BUG-007: Align pre-amp range across `TaalRecorder`, `AudioFilterEngine`, and `TaalPlayer`/UI     |
| Medium   | Fix BUG-001: Validate `.wav` extension in `setRawAudioFilePath()`                                    |
| Medium   | Fix BUG-005: Throw `IllegalStateException` from config methods when recording is active              |
| Low      | Fix BUG-002: Clamp `setRecordingTime()` to minimum 1 second                                          |
| Low      | Implement BUG-006: Throw `TaalNotAvailableForUseException` when USB device is claimed by another app |
| Low      | Fix BUG-008: Validate non-blank `filePath` in `TaalPlayerActivity.getIntent()`                       |
| Low      | Fix BUG-009: Guard `formatTimer()` against negative input in `RecordingViewModel`                    |
| Low      | Add `TaalConnectionBroadcastReceiver` integration tests (USB attach/detach system broadcasts)        |
| Low      | Add `WavCropper` fuzz tests for out-of-range crop timestamps                                         |
