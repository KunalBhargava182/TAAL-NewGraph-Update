# Integration Guide

For an engineer adding cardiac segmentation to an existing Android Kotlin application.

Target: **minSdk 26**, **JDK 17**, Kotlin 1.9.24, AGP 8.5.2. Newer Kotlin/AGP are expected to work
— the sources use no version-specific features — but re-run the core tests after bumping.

---

## 1. Add the modules

### Option A — source drop (recommended while the algorithm is still changing)

Copy `sdk/core` and `sdk/android` into the client's repository and include them:

```kotlin
// settings.gradle.kts
include(":pcg-segmentation-core")
project(":pcg-segmentation-core").projectDir = file("pcg-segmentation/core")

include(":pcg-segmentation-android")
project(":pcg-segmentation-android").projectDir = file("pcg-segmentation/android")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":pcg-segmentation-android"))
}
```

The core module is a plain `kotlin("jvm")` module, so it runs in the client's own JVM test suite —
worth having, because that is where any port or tuning gets checked.

### Option B — prebuilt AAR

```bash
cd sdk && ./gradlew :pcg-segmentation-android:assembleRelease
```

Hand over `sdk/android/build/outputs/aar/pcg-segmentation-android-release.aar`. It embeds the model
and the consumer R8 rules, but **not** the `core` classes — AARs do not bundle project dependencies.
Ship `core`'s JAR alongside it (`./gradlew :pcg-segmentation-core:jar`), or publish both to the
client's Maven repository, or use Option A. Option A avoids this entirely.

### ONNX Runtime

Declared `api("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")`, so the client's app resolves
it transitively with nothing to add.

**Do not downgrade below 1.18.** The model is exported at ONNX IR version 10; ONNX Runtime 1.17.x
supports at most IR 9 and throws `ORT_INVALID_ARGUMENT` when opening it. The consequence is not an
obvious crash — `TcnSegmenterRunner` construction fails, the recommended
`runCatching { … }.getOrNull()` pattern yields a null segmenter, and **every capture reports "no
heart sounds"**. It looks precisely like a capture-quality problem. This exact mistake cost us weeks
upstream. To check a model's IR version:

```bash
od -An -tu1 -N4 tcn_c200_cardiac_seg.onnx
```

To pin a different version, declare it in the app module (it wins by conflict resolution) and keep
it at 1.18 or above.

It carries native libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` and adds roughly 15 MB
to the APK across all ABIs. If that matters, use ABI splits or an App Bundle rather than excluding
ABIs by hand — dropping `x86_64` will break the client's emulator testing.

## 2. Own the session

```kotlin
class HeartSoundAnalyser(context: Context) : Closeable {

    // Lazy, and null-tolerant. Construction reads a 407 KB asset and builds an OrtSession —
    // about 100 ms — and it can fail on a device with an unsupported ABI. A capture flow that
    // crashes because segmentation is unavailable is worse than one that degrades.
    private val segmenter: TcnSegmenterRunner? by lazy {
        runCatching { TcnSegmenterRunner(context.applicationContext) }
            .onFailure { Log.e(TAG, "segmenter unavailable", it) }
            .getOrNull()
    }

    suspend fun analyse(pcg: FloatArray, sampleRate: Int): SegmentationResult? =
        withContext(Dispatchers.Default) { segmenter?.segment(pcg, sampleRate) }

    override fun close() { segmenter?.close() }
}
```

Three rules:

- **Build one, keep it.** Per-capture construction pays the ~100 ms session cost every time.
- **Not thread-safe.** One `segment` call in flight at a time.
- **Not on the main thread.** A 20 s recording takes hundreds of milliseconds.

## 3. Feed it correctly

| | |
|---|---|
| Channels | **Mono.** Interleaved stereo is not detected and silently halves the effective rate. Split with `Pcm.deinterleaveMono` first. |
| Sample rate | Any — resampled to 2 kHz internally. Pass the *true* rate; a wrong value scales every returned index. |
| Amplitude | Irrelevant. Raw 16-bit `AudioRecord` shorts and `[-1,1]` floats give bit-identical features (pinned by `PcmTest`). |
| Minimum length | 1 second. Practically, use **15–30 s** — our own captures are 20 s, and the HR channel needs 4 s before it does anything at all. |
| Microphone | `AudioSource.UNPROCESSED`, not `MIC`. This one matters — see [AUDIO_CAPTURE.md](AUDIO_CAPTURE.md). |

## 4. Handle the result honestly

```kotlin
val result = analyser.analyse(pcg, 44_100)

when {
    result == null -> retake(reason = "No heart sounds detected")

    // A result is not a quality guarantee. Expect roughly one S1 per 0.6-1.0 s of recording;
    // far fewer means the decoder found a handful of cycles in noise.
    result.numCycles < result.durationSec / 2 -> retake(reason = "Signal too weak")

    else -> use(result)
}
```

`null` is a **clinical outcome**, not an exception. Do not surface it as an error dialog and do not
retry the same buffer — the answer will not change. Ask for a new recording.

## 4b. Show it

`PcgSegmentationView` draws the recording with its segmentation — state bands, S1/S2 markers and a
legend — and needs no dependency beyond what you already have:

```kotlin
chart.setRecording(audio, sampleRate = 44_100, result = result)
```

Pass the *same* audio array you passed to `segment`; the overlay is aligned by time, so a resampled
or trimmed copy misplaces the bands while still looking plausible. Full guide, including Compose and
the palette rationale, in [VISUALISATION.md](VISUALISATION.md).

## 5. R8 / ProGuard

Nothing to do. `consumer-rules.pro` ships inside the AAR and keeps `ai.onnxruntime.**`.

Worth knowing why it is there: ONNX Runtime resolves part of its Java surface from native code, so
R8 cannot see those references and strips them. The failure is an `UnsatisfiedLinkError` or
`NoSuchMethodError` at `OrtEnvironment.getEnvironment()` **in release builds only**. If the client
uses `-dontusemixedcaseclassnames` or an aggressive custom config, keep that rule.

## 6. Verify the integration

```bash
cd sdk && ./gradlew :pcg-segmentation-core:test
```

38 tests. Run them in the client's CI too — they are the definition of "the same algorithm", and
they catch a bad port or an accidental edit to the filter coefficients immediately.

There is no instrumented test for the ONNX path here, because it needs a device or emulator and a
real recording. **Add one on the client side**: record 20 s from a live chest, assert
`numCycles` is plausible for the duration. That test is the one that would have caught the domain
gap described in [MODEL_CARD.md](MODEL_CARD.md), and it is worth having before a pilot.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `segment` always returns `null` | Silence or under 1 s. Check the buffer is non-zero and that `RECORD_AUDIO` was granted — a denied permission yields zeros, not an exception. |
| Works in debug, `UnsatisfiedLinkError` in release | R8 stripped ONNX Runtime. Section 5. |
| `FileNotFoundException: tcn_c200_cardiac_seg.onnx` | The AAR was consumed without its assets, or a custom `modelAsset` name was passed. AAR assets merge into the app automatically; check for an `aaptOptions`/`androidResources` exclusion. |
| Onsets land at implausible times | The `sampleRate` argument does not match the recording. Every index scales by that ratio. |
| Segmentation quality is poor but the audio sounds fine | Check for interleaved stereo, and check `UNPROCESSED` was actually granted rather than falling back to `MIC` with AGC on. |
| `OutOfMemoryError` on long recordings | The extractor holds several float arrays at the source rate. A 5-minute 44.1 kHz capture is ~13 M samples; segment in windows instead. |
