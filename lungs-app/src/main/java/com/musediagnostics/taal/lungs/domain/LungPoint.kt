package com.musediagnostics.taal.lungs.domain

/**
 * The 4 sequential anatomical regions shown one at a time in PlacementFragment.
 * Each region has a placeholder drawable that will be replaced with real anatomy images.
 * xFraction and yFraction (0.0–1.0) are relative to the anatomy ImageView bounds and
 * will be adjusted once real images are finalized.
 */
enum class LungRegion(
    val label: String,
    val drawableResName: String   // resolved at runtime via Resources.getIdentifier()
) {
    ANTERIOR_RIGHT("Anterior Right (Front)", "placeholder_anterior_right"),
    ANTERIOR_LEFT("Anterior Left (Front)", "placeholder_anterior_left"),
    POSTERIOR_RIGHT("Posterior Right (Back)", "placeholder_posterior_right"),
    POSTERIOR_LEFT("Posterior Left (Back)", "placeholder_posterior_left");

    val index: Int get() = ordinal
}

/**
 * One of the 16 lung auscultation points.
 *
 * @param code        Unique file-naming code (e.g., "aar", "pslr").
 * @param label       Human-readable label shown on the placement button.
 * @param region      Which of the 4 anatomical regions this point belongs to.
 * @param xFraction   Horizontal position on the anatomy image (0.0 = left edge, 1.0 = right edge).
 * @param yFraction   Vertical position on the anatomy image (0.0 = top edge, 1.0 = bottom edge).
 */
data class LungPoint(
    val code: String,
    val label: String,
    val region: LungRegion,
    val xFraction: Float,
    val yFraction: Float
)

/**
 * All 16 lung auscultation points grouped by region.
 * Coordinates are mapped to align with the 1, 2, 3, 4 numbered markers on the base images.
 */
object LungPoints {

    val all: List<LungPoint> = listOf(
        // Region 1: Anterior Right (Front Right)
        LungPoint("aar",  "Apex Right",     LungRegion.ANTERIOR_RIGHT, 0.61f, 0.32f),
        LungPoint("aslr", "Superior Right", LungRegion.ANTERIOR_RIGHT, 0.66f, 0.41f),
        LungPoint("amlr", "Middle Right",   LungRegion.ANTERIOR_RIGHT, 0.71f, 0.52f),
        LungPoint("ailr", "Inferior Right", LungRegion.ANTERIOR_RIGHT, 0.72f, 0.64f),

        // Region 2: Anterior Left (Front Left)
        LungPoint("aal",  "Apex Left",            LungRegion.ANTERIOR_LEFT,  0.50f, 0.28f),
        LungPoint("asll", "Superior Left",        LungRegion.ANTERIOR_LEFT,  0.32f, 0.40f),
        LungPoint("amll", "Middle Left",          LungRegion.ANTERIOR_LEFT,  0.30f, 0.51f),
        LungPoint("aill", "Inferior Left",        LungRegion.ANTERIOR_LEFT,  0.27f, 0.63f),

        // Region 3: Posterior Right (Back Right)
        LungPoint("par",  "Post. Apex Right",     LungRegion.POSTERIOR_RIGHT, 0.63f, 0.33f),
        LungPoint("pslr", "Post. Superior Right", LungRegion.POSTERIOR_RIGHT, 0.59f, 0.44f),
        LungPoint("pmlr", "Post. Middle Right",   LungRegion.POSTERIOR_RIGHT, 0.59f, 0.55f),
        LungPoint("pilr", "Post. Inferior Right", LungRegion.POSTERIOR_RIGHT, 0.60f, 0.65f),

        // Region 4: Posterior Left (Back Left)
        LungPoint("pal",  "Post. Apex Left",      LungRegion.POSTERIOR_LEFT,  0.41f, 0.34f),
        LungPoint("psll", "Post. Superior Left",  LungRegion.POSTERIOR_LEFT,  0.41f, 0.44f),
        LungPoint("pmll", "Post. Middle Left",    LungRegion.POSTERIOR_LEFT,  0.41f, 0.56f),
        LungPoint("pill", "Post. Inferior Left",  LungRegion.POSTERIOR_LEFT,  0.41f, 0.67f)
    )

    fun byRegion(region: LungRegion): List<LungPoint> = all.filter { it.region == region }

    fun byCode(code: String): LungPoint? = all.find { it.code == code }
}