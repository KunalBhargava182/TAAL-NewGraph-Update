package com.musediagnostics.taal.stemz.uikit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.musediagnostics.taal.stemz.uikit.share.RecordingDisplayName
import java.io.File

/**
 * Opens one saved recording on the review screen: whole recording on the PcgScale grid, Clean
 * Graph toggle, playback, and "Analyze Heart Sounds".
 *
 * ```kotlin
 * startActivity(TaalPlayerActivity.getIntent(this, filePath = savedFilteredWavPath))
 * ```
 *
 * Pass a `_filtered.wav` saved by the UI kit (e.g. [TaalRecorderActivity.RESULT_FILE_PATH]).
 * Heart-sound analysis needs its `_raw.wav` companion next to it (the UI kit always saves both).
 */
class TaalPlayerActivity : StemzHostActivity() {

    companion object {
        private const val EXTRA_FILE_PATH = "com.musediagnostics.taal.stemz.uikit.FILE_PATH"

        /**
         * @param filePath absolute path to a `_filtered.wav`.
         * @param heartSoundAnalysis show "Analyze Heart Sounds" (default true).
         */
        @JvmStatic
        @JvmOverloads
        fun getIntent(context: Context, filePath: String, heartSoundAnalysis: Boolean = true): Intent {
            val config = StemzUiConfig.DEFAULT.copy(heartSoundAnalysisEnabled = heartSoundAnalysis)
            return StemzUiConfig.putInto(Intent(context, TaalPlayerActivity::class.java), config)
                .putExtra(EXTRA_FILE_PATH, filePath)
        }
    }

    override val startDestinationId: Int get() = R.id.pcgScaleReviewFragment

    override fun startDestinationArgs(): Bundle {
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        return Bundle().apply {
            putString("filePath", filePath)
            putString("filterName", RecordingDisplayName.extractFilterName(File(filePath).nameWithoutExtension))
        }
    }
}
