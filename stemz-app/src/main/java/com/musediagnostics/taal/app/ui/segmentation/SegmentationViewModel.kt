package com.musediagnostics.taal.app.ui.segmentation

import androidx.lifecycle.ViewModel
import com.musediagnostics.taal.segmentation.SegmentationOutcome

/**
 * Shared (activity-scoped) between [SegmentationReportFragment] and
 * [SegmentationFullScreenFragment] so the full-screen chart can show the already-computed
 * segmentation across an orientation-driven Activity recreation without re-running inference.
 *
 * `SegmentationResult` isn't `Parcelable` — it lives in `taal-segmentation-core`, a pure-JVM
 * module with no Android dependency to add that with, and it's frozen (not to be edited). A
 * ViewModel is the only way to carry it across an Activity recreation without a Bundle; ViewModels
 * survive recreation-for-configuration-change by design as long as the Activity isn't finished.
 */
class SegmentationViewModel : ViewModel() {
    var audio: FloatArray? = null
    var sampleRate: Int = 0
    var outcome: SegmentationOutcome? = null

    /**
     * The activity's `requestedOrientation` as it was before [SegmentationFullScreenFragment]
     * first locked it to landscape — captured once, restored on exit. Held here (not as a
     * Fragment field) because the very act of locking to landscape can recreate the Activity,
     * which would otherwise wipe out the captured value before it's used.
     */
    var originalOrientationForFullScreen: Int? = null
}
