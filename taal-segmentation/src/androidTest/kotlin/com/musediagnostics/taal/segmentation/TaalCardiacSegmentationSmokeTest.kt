package com.musediagnostics.taal.segmentation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a real connected device/emulator (`connectedAndroidTest`), not the JVM — this is the
 * only way to prove ONNX Runtime's native `.so` libraries actually load and run inference on
 * real hardware, which no unit test can check.
 *
 * The fixture at `src/androidTest/assets/synthetic_pcg_raw.wav` is a synthetic 20s/44.1kHz/mono
 * signal with paired low-frequency bursts at 75bpm (not a real heartbeat recording) — this test
 * only proves the pipeline runs end to end without crashing; it does not assert a specific
 * clinical outcome, since a synthetic signal may or may not resemble what the model was trained
 * to recognise.
 */
@RunWith(AndroidJUnit4::class)
class TaalCardiacSegmentationSmokeTest {

    @Test
    fun segmentsASyntheticRawRecordingOnRealHardware(): Unit = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        val rawFile = File(targetContext.cacheDir, "synthetic_pcg_raw.wav")
        testContext.assets.open("synthetic_pcg_raw.wav").use { input ->
            rawFile.outputStream().use { output -> input.copyTo(output) }
        }

        val segmenter = TaalCardiacSegmentation(targetContext)
        try {
            val outcome = segmenter.segmentRawWav(rawFile, verboseLogging = true)
            Log.i(TAG, "outcome = $outcome")

            assertTrue(
                "segmentation session should initialise on real hardware (ONNX Runtime must load) " +
                    "— got Unavailable, check logcat for the TcnSegmenterRunner init failure",
                outcome !is SegmentationOutcome.Unavailable
            )

            when (outcome) {
                is SegmentationOutcome.Ok -> Log.i(
                    TAG,
                    "Ok: numCycles=${outcome.result.numCycles} durationSec=${outcome.result.durationSec} " +
                        "heartRateBpm=${outcome.result.heartRateBpm}"
                )
                is SegmentationOutcome.TooWeak -> Log.i(
                    TAG,
                    "TooWeak: numCycles=${outcome.result.numCycles} durationSec=${outcome.result.durationSec}"
                )
                SegmentationOutcome.NoHeartSounds -> Log.i(
                    TAG,
                    "NoHeartSounds — synthetic input was not recognised as a valid cardiac cycle"
                )
                SegmentationOutcome.Unavailable -> Unit // already asserted against above
            }
        } finally {
            segmenter.close()
            rawFile.delete()
        }
    }

    companion object {
        private const val TAG = "SegmentationSmokeTest"
    }
}
