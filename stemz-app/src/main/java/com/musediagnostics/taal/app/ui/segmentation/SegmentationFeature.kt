package com.musediagnostics.taal.app.ui.segmentation

/**
 * One switch for the whole "Analyze Heart Sounds" feature (PlayerFragment's button through the
 * SegmentationReportFragment screen and its chart). Flip [ENABLED] to `false` to hide it entirely
 * without touching or reverting any other file — [com.musediagnostics.taal.app.ui.player.PlayerFragment]
 * checks this before ever showing the button, so everything else behaves exactly as it did before
 * this feature existed.
 */
object SegmentationFeature {
    const val ENABLED = true
}
