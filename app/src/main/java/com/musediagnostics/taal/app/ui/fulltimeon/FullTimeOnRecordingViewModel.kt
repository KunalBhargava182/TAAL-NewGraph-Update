package com.musediagnostics.taal.app.ui.fulltimeon

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.musediagnostics.taal.app.TaalApplication
import com.musediagnostics.taal.app.data.repository.RecordingRepository

/**
 * Clone of [com.musediagnostics.taal.app.ui.calibrated.CalibratedRecordingViewModel] under a
 * new screen name (user request) — same state shape, own package so it can evolve
 * independently of the Calibrated screens it was copied from.
 */
enum class FullTimeOnRecordingUiState {
    IDLE,       // Transient — construction-time only, immediately replaced by PREVIEW on first onResume
    PREVIEW,    // Live graph + BPM streaming from a temp file that is never surfaced; nothing saved
    RECORDING,  // Actively recording to the real output files
    STOPPED     // Recording finished, showing save options
}

/**
 * The FullTimeOn Recorder's actual state graph, as a pure, unit-testable lookup — mirrors
 * exactly what [com.musediagnostics.taal.app.ui.fulltimeon.FullTimeOnRecordingFragment] does,
 * not a simplified version of it:
 *  - `IDLE -> PREVIEW`: every `onResume()` (first screen entry, or returning from the Player)
 *    calls `resetToIdle()` (sets IDLE) immediately followed by `startPreview()` (sets PREVIEW).
 *  - `PREVIEW -> IDLE`: internal/transient — `startPreview()` itself starts with `resetToIdle()`,
 *    so every preview (re)start (including the natural session-timeout auto-restart and the
 *    reconnect retry) passes through IDLE for a single synchronous step before landing back on
 *    PREVIEW.
 *  - `PREVIEW -> RECORDING`: pressing Record (`startRecording()`); `TaalRecorder`'s
 *    `onStateChange(RecorderState.RECORDING)` sets it.
 *  - `RECORDING -> STOPPED`: `stopRecording()` calls `taalRecorder.stop()`, whose synchronous
 *    `onStateChange(RecorderState.STOPPED)` callback sets it — this is the literal, if
 *    momentary, hop the real code takes; the "STOPPED-state UI" itself is dead/never rendered
 *    (stopRecording() navigates to the Player immediately after).
 *  - `STOPPED -> IDLE`: the next `resetToIdle()` — either the following `onResume()` (normal
 *    return-from-Player flow) or immediately inline, in the mid-recording-disconnect handler.
 *
 * The spec's shorthand "IDLE→PREVIEW→RECORDING→PREVIEW" describes the externally observable
 * cycle; concretely it is `IDLE -> PREVIEW -> RECORDING -> STOPPED -> IDLE -> PREVIEW` — see
 * [FullTimeOnRecordingTransitionsTest] for that full chain asserted step by step.
 */
object FullTimeOnRecordingTransitions {
    private val legalEdges: Set<Pair<FullTimeOnRecordingUiState, FullTimeOnRecordingUiState>> = setOf(
        FullTimeOnRecordingUiState.IDLE to FullTimeOnRecordingUiState.PREVIEW,
        FullTimeOnRecordingUiState.PREVIEW to FullTimeOnRecordingUiState.IDLE,
        FullTimeOnRecordingUiState.PREVIEW to FullTimeOnRecordingUiState.RECORDING,
        FullTimeOnRecordingUiState.RECORDING to FullTimeOnRecordingUiState.STOPPED,
        FullTimeOnRecordingUiState.STOPPED to FullTimeOnRecordingUiState.IDLE,
    )

    fun isLegal(from: FullTimeOnRecordingUiState, to: FullTimeOnRecordingUiState): Boolean =
        from != to && (from to to) in legalEdges
}

class FullTimeOnRecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as TaalApplication).database
    val recordingRepository = RecordingRepository(db.recordingDao())

    private val _uiState = MutableLiveData(FullTimeOnRecordingUiState.IDLE)
    val uiState: LiveData<FullTimeOnRecordingUiState> = _uiState

    private val _timerSeconds = MutableLiveData(0)
    val timerSeconds: LiveData<Int> = _timerSeconds

    private val _currentFilter = MutableLiveData("HEART")
    val currentFilter: LiveData<String> = _currentFilter

    private val _bpm = MutableLiveData(0)
    val bpm: LiveData<Int> = _bpm

    private val _preAmpDb = MutableLiveData(5)
    val preAmpDb: LiveData<Int> = _preAmpDb

    var currentRecordingPath: String = ""
    var currentFilteredPath: String = ""
    var customLowCut: Float? = null
    var customHighCut: Float? = null

    fun setUiState(state: FullTimeOnRecordingUiState) {
        _uiState.value = state
    }

    fun updateTimer(seconds: Int) {
        _timerSeconds.value = seconds
    }

    fun setFilter(filter: String) {
        _currentFilter.value = filter
    }

    fun setBpm(bpm: Int) {
        _bpm.value = bpm
    }

    fun setPreAmp(db: Int) {
        _preAmpDb.value = db.coerceIn(0, 30)
    }

    fun formatTimer(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
}
