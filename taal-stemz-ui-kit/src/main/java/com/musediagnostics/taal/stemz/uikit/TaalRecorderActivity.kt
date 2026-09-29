package com.musediagnostics.taal.stemz.uikit

import android.content.Context
import android.content.Intent
import com.musediagnostics.taal.stemz.PreFilter
import com.musediagnostics.taal.stemz.TaalRecorder

/**
 * Pre-built stemz recording flow: Recorder (live PcgScale graph, Lite/Hard/Custom filter,
 * pre-amp, BPM, auto-stop) → Review (Clean Graph, playback, Save/Discard) → name & save →
 * Saved Recordings (play / share wav+pdf / delete) → saved review → Analyze Heart Sounds.
 *
 * ```kotlin
 * private val recorderLauncher = registerForActivityResult(
 *     ActivityResultContracts.StartActivityForResult()
 * ) { result ->
 *     if (result.resultCode == RESULT_OK) {
 *         val filteredWav = result.data?.getStringExtra(TaalRecorderActivity.RESULT_FILE_PATH)
 *     }
 * }
 *
 * recorderLauncher.launch(TaalRecorderActivity.getIntent(this))
 * ```
 *
 * Result: RESULT_OK with [RESULT_FILE_PATH] (absolute path of the last saved `_filtered.wav`)
 * if at least one recording was saved before the user left, otherwise RESULT_CANCELED.
 */
class TaalRecorderActivity : StemzHostActivity() {

    companion object {
        /** Result extra: absolute path of the saved `{FILTER}_{name}_filtered.wav`. */
        const val RESULT_FILE_PATH = "com.musediagnostics.taal.stemz.uikit.RESULT_FILE_PATH"

        /**
         * @param preFilter filter selected when the screen opens: [PreFilter.LITE] (default) or
         *   [PreFilter.HARD]. The user can still switch to the other one, or to Custom.
         * @param preAmplification pre-amp in dB when the screen opens, 0–30 (default 10).
         * @param autoStopSeconds recording stops automatically after this many seconds,
         *   1–300 (default 15).
         * @param publicFolderName saved recordings are also copied to the device's
         *   `Music/<publicFolderName>/` folder (default "Stemz Recordings").
         * @param heartSoundAnalysis show "Analyze Heart Sounds" on saved recordings (default true).
         */
        @JvmStatic
        @JvmOverloads
        fun getIntent(
            context: Context,
            preFilter: PreFilter = PreFilter.LITE,
            preAmplification: Int = StemzUiConfig.DEFAULT_PRE_AMPLIFICATION_DB,
            autoStopSeconds: Int = TaalRecorder.DEFAULT_RECORDING_TIME_SECONDS,
            publicFolderName: String = StemzUiConfig.DEFAULT_PUBLIC_FOLDER_NAME,
            heartSoundAnalysis: Boolean = true
        ): Intent = StemzUiConfig.putInto(
            Intent(context, TaalRecorderActivity::class.java),
            StemzUiConfig(preFilter, preAmplification, autoStopSeconds, publicFolderName, heartSoundAnalysis)
        )
    }

    override val startDestinationId: Int get() = R.id.pcgScaleRecordingFragment
}
