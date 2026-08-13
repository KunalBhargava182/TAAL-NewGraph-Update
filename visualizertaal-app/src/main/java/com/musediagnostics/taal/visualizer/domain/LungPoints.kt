package com.musediagnostics.taal.visualizer.domain

/**
 * The 20 lung auscultation points across 4 regions (Anterior 8, Posterior 8,
 * Lateral Right 2, Lateral Left 2). Anterior/Posterior codes and labels are
 * reused from the existing lungs-app 16-point layout. Every code below has a
 * matching "point_{code}" drawable guide image.
 */
object LungPoints {
    const val REGION_ANTERIOR = "Anterior"
    const val REGION_POSTERIOR = "Posterior"
    const val REGION_LATERAL_RIGHT = "Lateral Right"
    const val REGION_LATERAL_LEFT = "Lateral Left"

    val regionOrder = listOf(REGION_ANTERIOR, REGION_POSTERIOR, REGION_LATERAL_RIGHT, REGION_LATERAL_LEFT)

    val all: List<RecordingPoint> = listOf(
        // Anterior (8)
        RecordingPoint("aar", "Apex Right", REGION_ANTERIOR),
        RecordingPoint("aslr", "Superior Right", REGION_ANTERIOR),
        RecordingPoint("amlr", "Middle Right", REGION_ANTERIOR),
        RecordingPoint("ailr", "Inferior Right", REGION_ANTERIOR),
        RecordingPoint("aal", "Apex Left", REGION_ANTERIOR),
        RecordingPoint("asll", "Superior Left", REGION_ANTERIOR),
        RecordingPoint("amll", "Middle Left", REGION_ANTERIOR),
        RecordingPoint("aill", "Inferior Left", REGION_ANTERIOR),

        // Posterior (8)
        RecordingPoint("par", "Post. Apex Right", REGION_POSTERIOR),
        RecordingPoint("pslr", "Post. Superior Right", REGION_POSTERIOR),
        RecordingPoint("pmlr", "Post. Middle Right", REGION_POSTERIOR),
        RecordingPoint("pilr", "Post. Inferior Right", REGION_POSTERIOR),
        RecordingPoint("pal", "Post. Apex Left", REGION_POSTERIOR),
        RecordingPoint("psll", "Post. Superior Left", REGION_POSTERIOR),
        RecordingPoint("pmll", "Post. Middle Left", REGION_POSTERIOR),
        RecordingPoint("pill", "Post. Inferior Left", REGION_POSTERIOR),

        // Lateral Right (2)
        RecordingPoint("lrs", "Lateral Right Superior", REGION_LATERAL_RIGHT),
        RecordingPoint("lri", "Lateral Right Inferior", REGION_LATERAL_RIGHT),

        // Lateral Left (2)
        RecordingPoint("lls", "Lateral Left Superior", REGION_LATERAL_LEFT),
        RecordingPoint("lli", "Lateral Left Inferior", REGION_LATERAL_LEFT)
    )

    fun byRegion(region: String): List<RecordingPoint> = all.filter { it.region == region }

    fun byCode(code: String): RecordingPoint? = all.find { it.code == code }
}
