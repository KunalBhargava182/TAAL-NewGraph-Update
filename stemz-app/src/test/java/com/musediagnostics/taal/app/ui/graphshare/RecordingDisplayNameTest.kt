package com.musediagnostics.taal.app.ui.graphshare

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards [RecordingDisplayName] against the exact prefix-match bug this codebase has already
 * hit and annotated in three other places (SavedRecordingsFragment.extractFilterName,
 * SavedRecordingAdapter.extractFilter): HEART_HARD must be matched before the shorter HEART
 * prefix, or a "HEART_HARD_..." filename gets only "HEART_" stripped, leaving "HARD_" stuck in
 * the display name.
 */
class RecordingDisplayNameTest {

    @Test
    fun `HEART_HARD is matched before the shorter HEART prefix`() {
        val name = RecordingDisplayName.displayName("HEART_HARD_MyRecording_filtered")
        assertEquals("MyRecording", name)
    }

    @Test
    fun `plain HEART prefix is stripped correctly on its own`() {
        val name = RecordingDisplayName.displayName("HEART_MyRecording_filtered")
        assertEquals("MyRecording", name)
    }

    @Test
    fun `every known filter prefix strips cleanly`() {
        val cases = mapOf(
            "FULL_BODY_Session1_filtered" to "Session1",
            "PREGNANCY_Session2_filtered" to "Session2",
            "CUSTOM_Session3_filtered" to "Session3",
            "LUNGS_Session4_filtered" to "Session4",
            "BOWEL_Session5_filtered" to "Session5"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, RecordingDisplayName.displayName(input))
        }
    }

    @Test
    fun `an unrecognized prefix falls back to treating it as HEART`() {
        // extractFilterName's own documented fallback behavior — mirrored here exactly. The
        // fallback "HEART" prefix doesn't actually match this filename, so only the
        // "_filtered" suffix gets stripped.
        assertEquals("HEART", RecordingDisplayName.extractFilterName("SomeOddlyNamedFile_filtered"))
        assertEquals("SomeOddlyNamedFile", RecordingDisplayName.displayName("SomeOddlyNamedFile_filtered"))
    }

    @Test
    fun `a user-typed name that itself contains an underscore survives intact`() {
        val name = RecordingDisplayName.displayName("HEART_John_Doe_Visit_2_filtered")
        assertEquals("John_Doe_Visit_2", name)
    }
}
