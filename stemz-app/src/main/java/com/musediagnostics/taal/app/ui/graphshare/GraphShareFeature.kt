package com.musediagnostics.taal.app.ui.graphshare

/**
 * One switch for the whole "share with graph" feature (the extra share button on
 * SavedRecordingsFragment's list rows, through GraphShareBundler and the PNG/PDF export it
 * produces). Flip [ENABLED] to `false` to hide it entirely without touching or reverting any
 * other file — [com.musediagnostics.taal.app.ui.library.SavedRecordingsFragment] checks this
 * before ever showing the button, so the existing audio-only share action (and everything else)
 * behaves exactly as it did before this feature existed. Same convention as
 * [com.musediagnostics.taal.app.ui.segmentation.SegmentationFeature].
 */
object GraphShareFeature {
    const val ENABLED = true
}
