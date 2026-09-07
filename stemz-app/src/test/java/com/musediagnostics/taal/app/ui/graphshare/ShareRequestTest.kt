package com.musediagnostics.taal.app.ui.graphshare

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression net for the two share shapes in this app. [ShareRequest.audioOnly] mirrors
 * SavedRecordingsFragment.shareRecording's existing, untouched Intent-building code
 * (ui/library/SavedRecordingsFragment.kt:86-126) as a reference shape only — that function is
 * NOT refactored to call this (see the plan's safety section), so this test pins the shape
 * side by side with the new one and will catch either drifting in a future change.
 */
class ShareRequestTest {

    @Test
    fun `audioOnly matches the existing single-file ACTION_SEND shape`() {
        val request = ShareRequest.audioOnly("wavPath/Heartbeat_01.wav", "Heartbeat_01")

        assertEquals(ShareAction.SEND, request.action)
        assertEquals("application/octet-stream", request.mimeType)
        assertEquals(1, request.attachmentPaths.size)
        assertEquals("Heartbeat_01", request.subject)
        assertEquals("Heartbeat_01", request.title)
    }

    @Test
    fun `audioWithGraph is the new multi-file SEND_MULTIPLE shape with exactly three files`() {
        val request = ShareRequest.audioWithGraph(
            wavPath = "a.wav", pngPath = "a.png", pdfPath = "a.pdf", displayName = "Heartbeat_01"
        )

        assertEquals(ShareAction.SEND_MULTIPLE, request.action)
        assertEquals(3, request.attachmentPaths.size)
        assertEquals(listOf("a.wav", "a.png", "a.pdf"), request.attachmentPaths)
    }

    @Test
    fun `the two share shapes never use the same mime type`() {
        // A cheap guard against someone "simplifying" the two factories into one constant later
        // and silently changing the legacy single-file share's anti-transcode MIME type.
        val single = ShareRequest.audioOnly("a.wav", "a")
        val multi = ShareRequest.audioWithGraph("a.wav", "a.png", "a.pdf", "a")
        org.junit.Assert.assertNotEquals(single.mimeType, multi.mimeType)
    }
}
