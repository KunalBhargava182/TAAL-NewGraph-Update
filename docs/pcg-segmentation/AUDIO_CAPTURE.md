# Audio Capture Requirements

The segmenter's output is only as good as what it is fed, and the defaults an Android app would
naturally reach for are the wrong ones. This page is short because there are only a few decisions,
but each of them changes results.

---

## Use `UNPROCESSED`, not `MIC`

`MediaRecorder.AudioSource.MIC` applies automatic gain control and noise suppression. Both are tuned
for speech, and heart sounds are not speech — they are quiet, low-frequency, and repetitive, which
is exactly the profile a noise suppressor is built to remove. AGC additionally rides the gain up
during diastole, which distorts the very amplitude relationship the S1/S2 envelope channels measure.

```kotlin
val record = AudioRecord(
    MediaRecorder.AudioSource.UNPROCESSED,   // fall back to MIC only if this fails
    44_100,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    bufferBytes
)
```

`UNPROCESSED` is **not available on every device**. The fallback to `MIC` is real and necessary —
but record which one was used with each capture. When a recording segments badly, that flag is the
first thing worth knowing, and it cannot be recovered afterwards.

Do not attach `AcousticEchoCanceler`, `NoiseSuppressor`, or `AutomaticGainControl`.

## Format

| | |
|---|---|
| Sample rate | 44,100 Hz. Any rate works (resampled to 2 kHz internally), but 44.1 kHz is universally supported and is what our captures and bench calibration used. |
| Channels | Mono. If the device gives stereo, split with `Pcm.deinterleaveMono` — do **not** average the channels; two chest positions averaged is not either recording. |
| Encoding | `ENCODING_PCM_16BIT`. |
| Duration | **15–30 s.** The hard floor is 1 s, but the HR feature channel needs 4 s before it contributes at all, and short captures give the decoder too few cycles to be worth trusting. Our app records 20 s. |

Amplitude scale does not matter — the extractor normalises internally, and raw 16-bit values give
bit-identical features to `[-1, 1]` floats.

## Physical capture

These come from our own bench and field work, and they affect the recording more than any code
setting does:

- **Microphone against bare skin**, at a standard auscultation site. Record *which* site — S1/S2
  relative amplitude differs between them, and a comparison across sites is not like-for-like.
- **Phone unplugged.** With the microphone edge against the chest there is no room for a USB cable,
  and a plugged phone changes both the physical contact and, on some devices, the audio routing.
- **Still.** A single knock is the dominant failure mode. The normaliser now caps its influence
  (see [API.md](API.md)), but that is damage limitation, not a fix — the knock still masks whatever
  it lands on.
- **Quiet room.** The model is blind: it cannot distinguish a rhythmic environmental sound from a
  heartbeat.

## Permissions

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

A denied permission yields a buffer of zeros rather than an exception, and the segmenter then
returns `null` — indistinguishable from a genuinely silent recording. Check the grant explicitly
before recording rather than inferring it from the result.

## What to log with each capture

Enough to diagnose a bad recording after the fact, since you will not get the patient back:

- Which audio source was actually granted (`UNPROCESSED` or the `MIC` fallback)
- Sample rate, channel count, duration
- Peak and RMS level in dBFS
- `numCycles` and `durationSec` from the result, or that it was `null`
- Auscultation site
- Device model — this is a device-dependent audio path, and a fault will cluster by handset
