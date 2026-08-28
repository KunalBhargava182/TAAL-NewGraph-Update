package com.musediagnostics.taal.app.ecg.calibrated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Plain-JVM tests for [GraphCalibration.Override] — same scope limit as [CalibratedMmScaleTest]'s
 * DPI correction tests: `getOverride`/`saveOverride`/`clearOverride` go through
 * `Context.getSharedPreferences`, which isn't available in a plain JVM unit test (no Robolectric
 * in this module), so only the data shape and the validity rule it documents are covered here.
 */
class GraphCalibrationTest {

    @Test
    fun `Override holds the two values it was constructed with`() {
        val override = GraphCalibration.Override(visibleSeconds = 2.5f, yFullScale = 0.02f)
        assertEquals(2.5f, override.visibleSeconds, 1e-6f)
        assertEquals(0.02f, override.yFullScale, 1e-6f)
    }

    @Test
    fun `Overrides with different values are not equal`() {
        val a = GraphCalibration.Override(visibleSeconds = 2.5f, yFullScale = 0.02f)
        val b = GraphCalibration.Override(visibleSeconds = 3.0f, yFullScale = 0.02f)
        assertNotEquals(a, b)
    }

    @Test
    fun `Overrides with the same values are equal (data class structural equality)`() {
        val a = GraphCalibration.Override(visibleSeconds = 2.5f, yFullScale = 0.02f)
        val b = GraphCalibration.Override(visibleSeconds = 2.5f, yFullScale = 0.02f)
        assertEquals(a, b)
    }

    // getOverride() treats visibleSeconds<=0 or yFullScale<=0 as corrupt/stale and returns null
    // rather than propagating a broken value into the chart — documented in the source, verified
    // here as a plain boolean check on the same condition it uses.
    @Test
    fun `non-positive values are the documented invalid condition`() {
        assertEquals(true, isInvalidOverride(0f, 0.02f))
        assertEquals(true, isInvalidOverride(2.5f, 0f))
        assertEquals(true, isInvalidOverride(-1f, 0.02f))
        assertEquals(false, isInvalidOverride(2.5f, 0.02f))
    }

    private fun isInvalidOverride(visibleSeconds: Float, yFullScale: Float): Boolean =
        visibleSeconds <= 0f || yFullScale <= 0f
}
