package com.musediagnostics.taal.app.ui.fulltimeon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for [FullTimeOnRecordingTransitions] — the FullTimeOn Recorder's always-on
 * preview state machine (added alongside the live-preview + speaker-mute feature). No Android
 * runtime needed: [FullTimeOnRecordingUiState] is a plain enum and the transition table is a
 * pure lookup, mirroring what FullTimeOnRecordingFragment actually does (see that object's doc).
 */
class FullTimeOnRecordingTransitionsTest {

    @Test
    fun `IDLE to PREVIEW is legal - every onResume`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.IDLE, FullTimeOnRecordingUiState.PREVIEW))
    }

    @Test
    fun `PREVIEW to RECORDING is legal - pressing Record`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.PREVIEW, FullTimeOnRecordingUiState.RECORDING))
    }

    @Test
    fun `RECORDING to STOPPED is legal - stopRecording's synchronous TaalRecorder callback`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.RECORDING, FullTimeOnRecordingUiState.STOPPED))
    }

    @Test
    fun `STOPPED to IDLE is legal - the next resetToIdle()`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.STOPPED, FullTimeOnRecordingUiState.IDLE))
    }

    @Test
    fun `PREVIEW to IDLE is legal - startPreview's own internal resetToIdle() step`() {
        assertTrue(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.PREVIEW, FullTimeOnRecordingUiState.IDLE))
    }

    @Test
    fun `the required conceptual cycle IDLE to PREVIEW to RECORDING to PREVIEW is reachable via legal edges only`() {
        // The spec's shorthand cycle, expanded to the real hop through STOPPED — every step
        // below must individually be a legal edge; chaining them reproduces the observable
        // "record, stop, land back in a live preview" cycle end to end.
        val chain = listOf(
            FullTimeOnRecordingUiState.IDLE,
            FullTimeOnRecordingUiState.PREVIEW,
            FullTimeOnRecordingUiState.RECORDING,
            FullTimeOnRecordingUiState.STOPPED,
            FullTimeOnRecordingUiState.IDLE,
            FullTimeOnRecordingUiState.PREVIEW,
        )
        for (i in 0 until chain.size - 1) {
            assertTrue(
                "expected ${chain[i]} -> ${chain[i + 1]} to be legal",
                FullTimeOnRecordingTransitions.isLegal(chain[i], chain[i + 1])
            )
        }
    }

    @Test
    fun `cannot skip preview - IDLE straight to RECORDING is illegal`() {
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.IDLE, FullTimeOnRecordingUiState.RECORDING))
    }

    @Test
    fun `cannot skip the stop hop - RECORDING straight to IDLE is illegal`() {
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.RECORDING, FullTimeOnRecordingUiState.IDLE))
    }

    @Test
    fun `cannot skip the stop hop - RECORDING straight to PREVIEW is illegal`() {
        // The real code always passes through STOPPED first (stopRecording()'s synchronous
        // TaalRecorder callback) even though that UI is never rendered — see the class doc.
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.RECORDING, FullTimeOnRecordingUiState.PREVIEW))
    }

    @Test
    fun `STOPPED cannot go straight back to RECORDING or PREVIEW`() {
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.STOPPED, FullTimeOnRecordingUiState.RECORDING))
        assertFalse(FullTimeOnRecordingTransitions.isLegal(FullTimeOnRecordingUiState.STOPPED, FullTimeOnRecordingUiState.PREVIEW))
    }

    @Test
    fun `a state never legally transitions to itself`() {
        for (state in FullTimeOnRecordingUiState.entries) {
            assertFalse("$state -> $state should not be a legal transition", FullTimeOnRecordingTransitions.isLegal(state, state))
        }
    }
}
