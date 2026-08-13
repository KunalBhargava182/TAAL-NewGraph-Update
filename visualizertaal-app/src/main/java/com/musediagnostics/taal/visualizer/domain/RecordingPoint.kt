package com.musediagnostics.taal.visualizer.domain

import com.musediagnostics.taal.PreFilter

/**
 * One auscultation point shown as a tab in PlacementFragment.
 *
 * @param code    Unique file-naming code (e.g., "aortic", "aar").
 * @param label   Human-readable label shown on the tab / placement screen.
 * @param region  Grouping used for nested region tabs (e.g. "Anterior"). Null = flat,
 *                single-level tabs (used by Heart, which has no regions).
 */
data class RecordingPoint(
    val code: String,
    val label: String,
    val region: String? = null
)

/** Which guided recording flow is active — drives the filter, point list, and file naming. */
enum class PointSetType(val displayName: String, val preFilter: PreFilter) {
    HEART("Heart", PreFilter.HEART),
    LUNGS("Lungs", PreFilter.LUNGS);

    val points: List<RecordingPoint>
        get() = when (this) {
            HEART -> HeartPoints.all
            LUNGS -> LungPoints.all
        }

    /** Distinct region names in declaration order; empty for a flat (regionless) point set. */
    val regions: List<String>
        get() = points.mapNotNull { it.region }.distinct()

    fun pointByCode(code: String): RecordingPoint? = points.find { it.code == code }
}
