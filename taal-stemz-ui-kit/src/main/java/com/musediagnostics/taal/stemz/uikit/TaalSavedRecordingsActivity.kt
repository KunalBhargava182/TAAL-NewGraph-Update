package com.musediagnostics.taal.stemz.uikit

import android.content.Context
import android.content.Intent

/**
 * Opens the Saved Recordings list directly (play / share wav+pdf / delete; play opens the review
 * screen with "Analyze Heart Sounds").
 *
 * ```kotlin
 * startActivity(TaalSavedRecordingsActivity.getIntent(this))
 * ```
 */
class TaalSavedRecordingsActivity : StemzHostActivity() {

    companion object {
        /** @param heartSoundAnalysis show "Analyze Heart Sounds" on the review screen (default true). */
        @JvmStatic
        @JvmOverloads
        fun getIntent(context: Context, heartSoundAnalysis: Boolean = true): Intent =
            StemzUiConfig.putInto(
                Intent(context, TaalSavedRecordingsActivity::class.java),
                StemzUiConfig.DEFAULT.copy(heartSoundAnalysisEnabled = heartSoundAnalysis)
            )
    }

    override val startDestinationId: Int get() = R.id.savedRecordingsFragment
}
