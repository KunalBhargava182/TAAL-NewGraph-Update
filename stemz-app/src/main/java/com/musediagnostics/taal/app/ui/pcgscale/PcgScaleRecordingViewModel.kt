package com.musediagnostics.taal.app.ui.pcgscale

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.musediagnostics.taal.app.TaalApplication
import com.musediagnostics.taal.app.data.repository.RecordingRepository

/**
 * Copy of [com.musediagnostics.taal.app.ui.calibrated.CalibratedRecordingViewModel] (itself a
 * fork of the protected RecordingViewModel), forked so PcgScaleRecordingFragment can evolve
 * independently. State shape is identical — same reasoning as the original fork: it keeps a
 * future "swap the fragment class in nav_graph.xml" promotion a one-line change, and keeps
 * this package fully self-contained (deleting ui/calibrated or ui/pcgscale never breaks the
 * other).
 */
enum class PcgScaleRecordingUiState {
    IDLE,       // Pre-recording
    RECORDING,  // Actively recording
    STOPPED     // Recording finished, showing save options
}

class PcgScaleRecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as TaalApplication).database
    val recordingRepository = RecordingRepository(db.recordingDao())

    private val _uiState = MutableLiveData(PcgScaleRecordingUiState.IDLE)
    val uiState: LiveData<PcgScaleRecordingUiState> = _uiState

    private val _timerSeconds = MutableLiveData(0)
    val timerSeconds: LiveData<Int> = _timerSeconds

    private val _currentFilter = MutableLiveData("HEART")
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
}
