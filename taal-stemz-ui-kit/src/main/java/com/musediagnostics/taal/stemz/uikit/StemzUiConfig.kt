package com.musediagnostics.taal.stemz.uikit

import android.app.Activity
import android.content.Intent
import com.musediagnostics.taal.stemz.PreFilter
import com.musediagnostics.taal.stemz.TaalRecorder

/**
 * Settings the integrator passes through the entry activities' `getIntent(...)` factories.
 * Every screen in the UI kit reads them from its host activity's intent via [from], so a value
 * set once at launch applies to the whole flow (record → review → save → saved list → analyze).
 */
internal data class StemzUiConfig(
    val preFilter: PreFilter,
    val preAmplificationDb: Int,
    val autoStopSeconds: Int,
    val publicFolderName: String,
    val heartSoundAnalysisEnabled: Boolean
) {
    companion object {
        const val EXTRA_PRE_FILTER = "com.musediagnostics.taal.stemz.uikit.PRE_FILTER"
        const val EXTRA_PRE_AMPLIFICATION = "com.musediagnostics.taal.stemz.uikit.PRE_AMPLIFICATION"
        const val EXTRA_AUTO_STOP_SECONDS = "com.musediagnostics.taal.stemz.uikit.AUTO_STOP_SECONDS"
        const val EXTRA_PUBLIC_FOLDER_NAME = "com.musediagnostics.taal.stemz.uikit.PUBLIC_FOLDER_NAME"
        const val EXTRA_HEART_SOUND_ANALYSIS = "com.musediagnostics.taal.stemz.uikit.HEART_SOUND_ANALYSIS"

        const val DEFAULT_PRE_AMPLIFICATION_DB = 10
        const val DEFAULT_PUBLIC_FOLDER_NAME = "Stemz Recordings"

        val DEFAULT = StemzUiConfig(
            preFilter = PreFilter.LITE,
            preAmplificationDb = DEFAULT_PRE_AMPLIFICATION_DB,
            autoStopSeconds = TaalRecorder.DEFAULT_RECORDING_TIME_SECONDS,
            publicFolderName = DEFAULT_PUBLIC_FOLDER_NAME,
            heartSoundAnalysisEnabled = true
        )

        fun putInto(intent: Intent, config: StemzUiConfig): Intent = intent.apply {
            putExtra(EXTRA_PRE_FILTER, config.preFilter.name)
            putExtra(EXTRA_PRE_AMPLIFICATION, config.preAmplificationDb)
            putExtra(EXTRA_AUTO_STOP_SECONDS, config.autoStopSeconds)
            putExtra(EXTRA_PUBLIC_FOLDER_NAME, config.publicFolderName)
            putExtra(EXTRA_HEART_SOUND_ANALYSIS, config.heartSoundAnalysisEnabled)
        }

        fun from(activity: Activity?): StemzUiConfig {
            val intent = activity?.intent ?: return DEFAULT
            val filter = intent.getStringExtra(EXTRA_PRE_FILTER)
                ?.let { name -> PreFilter.entries.firstOrNull { it.name == name } }
                ?: DEFAULT.preFilter
            return StemzUiConfig(
                preFilter = filter,
                preAmplificationDb = intent.getIntExtra(EXTRA_PRE_AMPLIFICATION, DEFAULT.preAmplificationDb)
                    .coerceIn(0, 30),
                autoStopSeconds = intent.getIntExtra(EXTRA_AUTO_STOP_SECONDS, DEFAULT.autoStopSeconds)
                    .coerceIn(1, TaalRecorder.MAX_RECORDING_TIME_SECONDS),
                publicFolderName = intent.getStringExtra(EXTRA_PUBLIC_FOLDER_NAME)
                    ?.takeIf { it.isNotBlank() } ?: DEFAULT.publicFolderName,
                heartSoundAnalysisEnabled = intent.getBooleanExtra(
                    EXTRA_HEART_SOUND_ANALYSIS, DEFAULT.heartSoundAnalysisEnabled
                )
            )
        }
    }
}
