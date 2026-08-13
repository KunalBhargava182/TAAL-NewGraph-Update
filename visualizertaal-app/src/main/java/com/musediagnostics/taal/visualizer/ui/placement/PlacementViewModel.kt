package com.musediagnostics.taal.visualizer.ui.placement

import androidx.lifecycle.ViewModel

/** Fragment-scoped, survives rotation. No async state — just remembers tab selection. */
class PlacementViewModel : ViewModel() {
    var currentRegionIndex: Int = 0
    var currentPointCode: String? = null
}
