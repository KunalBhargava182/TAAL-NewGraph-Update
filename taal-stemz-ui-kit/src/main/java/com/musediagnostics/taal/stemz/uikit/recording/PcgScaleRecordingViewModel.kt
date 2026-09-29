package com.musediagnostics.taal.stemz.uikit.recording

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

internal enum class PcgScaleRecordingUiState {
    IDLE,       // Pre-recording
    RECORDING,  // Actively recording
    STOPPED     // Recording finished (navigates straight to the review screen)
}

/**
 * Recorder screen state. Filter names are the saved-file prefixes: "LITE", "HARD" or "CUSTOM"
 * (files are named "{FILTER}_{name}_filtered.wav" / "_raw.wav").
 */
internal class PcgScaleRecordingViewModel : ViewModel() {

    private val _uiState = MutableLiveData(PcgScaleRecordingUiState.IDLE)
    val uiState: LiveData<PcgScaleRecordingUiState> = _uiState

    private val _timerSeconds = MutableLiveData(0)
    val timerSeconds: LiveData<Int> = _timerSeconds

    private val _currentFilter = MutableLiveData(FILTER_LITE)
    val currentFilter: LiveData<String> = _currentFilter

    private val _bpm = MutableLiveData(0)
    val bpm: LiveData<Int> = _bpm

    private val _preAmpDb = MutableLiveData(10)
    val preAmpDb: LiveData<Int> = _preAmpDb

    var currentRecordingPath: String = ""
    var currentFilteredPath: String = ""
    var customLowCut: Float? = null
    var customHighCut: Float? = null

    fun setUiState(state: PcgScaleRecordingUiState) {
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

    companion object {
        const val FILTER_LITE = "LITE"
        const val FILTER_HARD = "HARD"
        const val FILTER_CUSTOM = "CUSTOM"
    }
}
