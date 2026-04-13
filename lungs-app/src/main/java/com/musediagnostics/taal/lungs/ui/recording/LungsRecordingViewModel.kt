package com.musediagnostics.taal.lungs.ui.recording

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

enum class LungsRecordingUiState { IDLE, RECORDING, STOPPED }

class LungsRecordingViewModel : ViewModel() {

    private val _uiState = MutableLiveData(LungsRecordingUiState.IDLE)
    val uiState: LiveData<LungsRecordingUiState> = _uiState

    private val _timerSeconds = MutableLiveData(0)
    val timerSeconds: LiveData<Int> = _timerSeconds

    private val _preAmpDb = MutableLiveData(5)
    val preAmpDb: LiveData<Int> = _preAmpDb

    var currentRecordingPath: String = ""
    var currentFilteredPath: String = ""

    fun setUiState(state: LungsRecordingUiState) { _uiState.value = state }

    fun updateTimer(seconds: Int) { _timerSeconds.value = seconds }

    fun setPreAmp(db: Int) { _preAmpDb.value = db.coerceIn(0, 30) }

    fun formatTimer(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
