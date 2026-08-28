package com.musediagnostics.taal.app.ecg.calibrated

import android.content.Context

/**
 * Persisted per-device override for the calibrated screens' default zoom/scale — "Peak Size"
 * and "Time Zoom", set from the Player's calibration panel and shared by both the Player and
 * the Recorder (so live recording and review keep matching sizes on that device).
 *
 * Same `SharedPreferences`-backed object pattern as [DpiCalibration], but a distinct, unrelated
 * concern — this is a *visual preference* (how big things look on this specific screen), not a
 * physical-accuracy correction like px-per-mm.
 *
 * We deliberately persist [Override.visibleSeconds] and [Override.yFullScale] — physical,
 * portable quantities read back directly from the chart — rather than MPAndroidChart's raw
 * pinch-zoom scale factors (`viewPortHandler.scaleX`/`scaleY`). Those scale factors are
 * relative to whatever file happens to be loaded at the time (confirmed via a live-tuning
 * session: the same `scaleX` value meant a different number of visible seconds depending on
 * file length), so persisting them and replaying them against a different recording later
 * would not reproduce the same visual result. `visibleSeconds`/`yFullScale` have no such
 * dependency — they mean the same thing regardless of which file is open.
 */
object GraphCalibration {
    private const val PREFS_NAME = "calibrated_graph_prefs"
    private const val KEY_HAS_OVERRIDE = "has_override"
    private const val KEY_VISIBLE_SECONDS = "visible_seconds"
    private const val KEY_Y_FULL_SCALE = "y_full_scale"

    data class Override(val visibleSeconds: Float, val yFullScale: Float)

    /** Null when no calibration has been applied on this device — callers fall back to their own built-in default. */
    fun getOverride(context: Context): Override? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_OVERRIDE, false)) return null
        val visibleSeconds = prefs.getFloat(KEY_VISIBLE_SECONDS, 0f)
        val yFullScale = prefs.getFloat(KEY_Y_FULL_SCALE, 0f)
        if (visibleSeconds <= 0f || yFullScale <= 0f) return null // corrupt/stale — ignore rather than crash
        return Override(visibleSeconds, yFullScale)
    }

    fun saveOverride(context: Context, visibleSeconds: Float, yFullScale: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_VISIBLE_SECONDS, visibleSeconds)
            .putFloat(KEY_Y_FULL_SCALE, yFullScale)
            .putBoolean(KEY_HAS_OVERRIDE, true)
            .apply()
    }

    fun clearOverride(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_VISIBLE_SECONDS)
            .remove(KEY_Y_FULL_SCALE)
            .putBoolean(KEY_HAS_OVERRIDE, false)
            .apply()
    }
}
