package com.musediagnostics.taal.app.ui.recording

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.musediagnostics.taal.app.TaalApplication
import com.musediagnostics.taal.app.data.repository.RecordingRepository

enum class RecordingUiState {
    IDLE,       // Pre-recording
    RECORDING,  // Actively recording
    STOPPED     // Recording finished, showing save options
}

class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as TaalApplication).database
    val recordingRepository = RecordingRepository(db.recordingDao())

    private val _uiState = MutableLiveData(RecordingUiState.IDLE)
    val uiState: LiveData<RecordingUiState> = _uiState

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
    /** Path to the 8kHz HEART AI-testing WAV file (managed by save/discard flow). */
    var currentAiTestingPath: String = ""
    /**
     * Paths to every additional AI downsampling file created by DownsamplingStream.
     * HEART filter → [4kHz, 1kHz, 500Hz] HEART files.
     * LUNGS filter → [8kHz, 4kHz, 3kHz, 2kHz] LUNGS files.
     * Cleared on resetToIdle(). Passed to PlayerFragment for save/discard handling.
     */
    var extraAiFilePaths: List<String> = emptyList()

    fun setUiState(state: RecordingUiState) {
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
