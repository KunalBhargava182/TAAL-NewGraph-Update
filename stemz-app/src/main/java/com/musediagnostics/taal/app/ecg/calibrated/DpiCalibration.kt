package com.musediagnostics.taal.app.ecg.calibrated

import android.content.Context
import android.os.Build

/**
 * Persisted px-per-mm correction factors for [CalibratedEcgPaperView].
 *
 * `TypedValue.applyDimension(COMPLEX_UNIT_MM, ...)` / `displayMetrics.ydpi` depend on the
 * OEM-reported DPI, which is frequently wrong. Without correction the grid is precise but
 * not accurate — it looks authoritative and isn't. This class stores a multiplicative
 * correction (`correction = nominalMm / measuredMm`, from a physical-ruler measurement) that
 * [applyTo] multiplies onto the view's OEM-reported px-per-mm.
 *
 * Lookup order: (1) a hardcoded per-model table for known clinical tablets — faster in the
 * field and avoids re-running the ruler test on every unit of the same model; (2) a manual
 * SharedPreferences calibration done via [com.musediagnostics.taal.app.ui.calibrated.DpiCalibrationFragment];
 * (3) default 1.0 (uncorrected) when neither is present.
 */
object DpiCalibration {
    private const val PREFS_NAME = "calibrated_dpi_prefs"
    private const val KEY_CORRECTION_X = "correction_x"
    private const val KEY_CORRECTION_Y = "correction_y"
    private const val KEY_IS_CALIBRATED = "is_calibrated"

    /**
     * Per-model correction factors (correctionX, correctionY) for devices already measured
     * in the field with a physical ruler. Checked before SharedPreferences so a fleet of
     * identical clinical tablets doesn't need the manual calibration screen run on every
     * unit. Empty until a model has actually been ruler-tested — do not guess values here.
     */
    private val KNOWN_DEVICE_CORRECTIONS: Map<String, Pair<Float, Float>> = emptyMap()

    data class Correction(
        val x: Float,
        val y: Float,
        val isCalibrated: Boolean,
        val source: String // "device-table" | "manual" | "uncalibrated"
    )

    fun getCorrection(context: Context): Correction {
        KNOWN_DEVICE_CORRECTIONS[Build.MODEL]?.let { (x, y) ->
            return Correction(x, y, isCalibrated = true, source = "device-table")
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val calibrated = prefs.getBoolean(KEY_IS_CALIBRATED, false)
        val x = prefs.getFloat(KEY_CORRECTION_X, 1.0f)
        val y = prefs.getFloat(KEY_CORRECTION_Y, 1.0f)
        return Correction(x, y, calibrated, if (calibrated) "manual" else "uncalibrated")
    }

    fun saveManualCorrection(context: Context, correctionX: Float, correctionY: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_CORRECTION_X, correctionX)
            .putFloat(KEY_CORRECTION_Y, correctionY)
            .putBoolean(KEY_IS_CALIBRATED, true)
            .apply()
    }

    fun clearManualCorrection(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_CORRECTION_X)
            .remove(KEY_CORRECTION_Y)
            .putBoolean(KEY_IS_CALIBRATED, false)
            .apply()
    }

    /**
     * Multiplies the view's current (OEM-reported) px-per-mm by the stored correction and
     * applies it via [CalibratedEcgPaperView.setPixelsPerMm]. Call once, after the view has
     * been constructed (so `currentScale()` holds the reported baseline) and before any other
     * manual override — calling it twice would compound the correction.
     */
    fun applyTo(view: CalibratedEcgPaperView, context: Context) {
        val reported = view.currentScale()
        val correction = getCorrection(context)
        view.setPixelsPerMm(
            reported.pxPerMmX * correction.x,
            reported.pxPerMmY * correction.y
        )
    }
}
