package com.musediagnostics.taal.stemz.uikit.share

import org.junit.Assert.assertEquals
import org.junit.Test

/** Saved-file naming for the stemz UI kit: "{LITE|HARD|CUSTOM}_{userInput}_filtered". */
class RecordingDisplayNameTest {

    @Test
    fun `LITE prefix is stripped`() {
        assertEquals("MyRecording", RecordingDisplayName.displayName("LITE_MyRecording_filtered"))
        assertEquals("LITE", RecordingDisplayName.extractFilterName("LITE_MyRecording_filtered"))
    }

    @Test
    fun `HARD prefix is stripped`() {
        assertEquals("MyRecording", RecordingDisplayName.displayName("HARD_MyRecording_filtered"))
        assertEquals("HARD", RecordingDisplayName.extractFilterName("HARD_MyRecording_filtered"))
    }

    @Test
    fun `CUSTOM prefix is stripped`() {
        assertEquals("Session3", RecordingDisplayName.displayName("CUSTOM_Session3_filtered"))
        assertEquals("CUSTOM", RecordingDisplayName.extractFilterName("CUSTOM_Session3_filtered"))
    }

    @Test
    fun `an unrecognized prefix falls back to LITE and only the suffix is stripped`() {
        assertEquals("LITE", RecordingDisplayName.extractFilterName("SomeOddlyNamedFile_filtered"))
        assertEquals("SomeOddlyNamedFile", RecordingDisplayName.displayName("SomeOddlyNamedFile_filtered"))
    }

    @Test
    fun `a user-typed name that itself contains underscores survives intact`() {
        assertEquals("John_Doe_Visit_2", RecordingDisplayName.displayName("HARD_John_Doe_Visit_2_filtered"))
    }
}
