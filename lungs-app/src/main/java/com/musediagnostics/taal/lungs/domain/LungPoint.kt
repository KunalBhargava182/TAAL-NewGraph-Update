package com.musediagnostics.taal.lungs.domain

/**
 * The 4 sequential anatomical regions shown one at a time in PlacementFragment.
 * Each region has a placeholder drawable that will be replaced with real anatomy images.
 * xFraction and yFraction (0.0–1.0) are relative to the anatomy ImageView bounds.
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
 * All 16 lung auscultation points.
 *
 * Coordinates calibrated against the standardised 1792x2400 placeholder images
 * (Anterior R/L and Posterior R/L) generated for the app.
 *
 * Convention used in the images:
 *   - R label is on the viewer's LEFT, L label is on the viewer's RIGHT (radiological convention).
 *   - "Anterior Right" highlights the patient's right lung (viewer's left side of image).
 *   - "Anterior Left"  highlights the patient's left lung  (viewer's right side of image).
 *   - "Posterior Right" highlights the right side of the back (viewer's left of spine).
 *   - "Posterior Left"  highlights the left side of the back  (viewer's right of spine).
 *
 * Anterior dots follow the mid-clavicular line at standard ICS levels.
 * Posterior dots follow the paravertebral (paraspinal) line on the highlighted side.
 */
object LungPoints {

    val all: List<LungPoint> = listOf(
        // Region 1: Anterior Right (patient's right lung — appears on viewer's LEFT)
        LungPoint("aar",  "Apex Right",     LungRegion.ANTERIOR_RIGHT, 0.40f, 0.32f),
        LungPoint("aslr", "Superior Right", LungRegion.ANTERIOR_RIGHT, 0.36f, 0.40f),
        LungPoint("amlr", "Middle Right",   LungRegion.ANTERIOR_RIGHT, 0.36f, 0.48f),
        LungPoint("ailr", "Inferior Right", LungRegion.ANTERIOR_RIGHT, 0.36f, 0.57f),

        // Region 2: Anterior Left (patient's left lung — appears on viewer's RIGHT)
        LungPoint("aal",  "Apex Left",     LungRegion.ANTERIOR_LEFT, 0.61f, 0.31f),
        LungPoint("asll", "Superior Left", LungRegion.ANTERIOR_LEFT, 0.65f, 0.38f),
        LungPoint("amll", "Middle Left",   LungRegion.ANTERIOR_LEFT, 0.66f, 0.48f),
        LungPoint("aill", "Inferior Left", LungRegion.ANTERIOR_LEFT, 0.66f, 0.58f),

        // Region 3: Posterior Right (paraspinal, viewer's LEFT of spine)
        LungPoint("par",  "Post. Apex Right",     LungRegion.POSTERIOR_RIGHT, 0.42f, 0.35f),
        LungPoint("pslr", "Post. Superior Right", LungRegion.POSTERIOR_RIGHT, 0.42f, 0.43f),
        LungPoint("pmlr", "Post. Middle Right",   LungRegion.POSTERIOR_RIGHT, 0.42f, 0.50f),
        LungPoint("pilr", "Post. Inferior Right", LungRegion.POSTERIOR_RIGHT, 0.42f, 0.58f),

        // Region 4: Posterior Left (paraspinal, viewer's RIGHT of spine)
        LungPoint("pal",  "Post. Apex Left",     LungRegion.POSTERIOR_LEFT, 0.59f, 0.34f),
        LungPoint("psll", "Post. Superior Left", LungRegion.POSTERIOR_LEFT, 0.59f, 0.40f),
        LungPoint("pmll", "Post. Middle Left",   LungRegion.POSTERIOR_LEFT, 0.59f, 0.48f),
        LungPoint("pill", "Post. Inferior Left", LungRegion.POSTERIOR_LEFT, 0.59f, 0.58f)
    )

    fun byRegion(region: LungRegion): List<LungPoint> = all.filter { it.region == region }

    fun byCode(code: String): LungPoint? = all.find { it.code == code }
}