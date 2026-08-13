package com.musediagnostics.taal.visualizer.domain

/**
 * The 4 heart valve auscultation points. Flat list — no regions/nested tabs.
 * Placement guide images are looked up at runtime as drawable "point_{code}"
 * (e.g. "point_aortic"); until real assets are supplied the guide image is
 * simply hidden (same fallback behaviour as lungs-app).
 */
object HeartPoints {
    val all: List<RecordingPoint> = listOf(
        RecordingPoint("aortic", "Aortic"),
        RecordingPoint("mitral", "Mitral"),
        RecordingPoint("pulmonary", "Pulmonary"),
        RecordingPoint("tricuspid", "Tricuspid")
    )

    fun byCode(code: String): RecordingPoint? = all.find { it.code == code }
}
