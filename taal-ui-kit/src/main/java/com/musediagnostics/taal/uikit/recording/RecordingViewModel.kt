package com.musediagnostics.taal.uikit.recording

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class RecordingViewModel : ViewModel() {

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
        val clamped = seconds.coerceAtLeast(0)
        val hours = clamped / 3600
        val minutes = (clamped % 3600) / 60
        val secs = clamped % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
}
