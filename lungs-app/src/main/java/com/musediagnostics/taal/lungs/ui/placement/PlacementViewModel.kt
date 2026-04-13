package com.musediagnostics.taal.lungs.ui.placement

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musediagnostics.taal.lungs.data.db.entity.LungRecordingEntity
import com.musediagnostics.taal.lungs.data.repository.LungRecordingRepository
import com.musediagnostics.taal.lungs.domain.LungRegion
import com.musediagnostics.taal.lungs.domain.LungPoints
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlacementViewModel : ViewModel() {

    private val _recordings = MutableLiveData<Map<String, Boolean>>(emptyMap())
    /** Maps pointCode → isDone for the current patient. */
    val recordings: LiveData<Map<String, Boolean>> = _recordings

    private val _currentRegionIndex = MutableLiveData(0)
    val currentRegionIndex: LiveData<Int> = _currentRegionIndex

    private var repository: LungRecordingRepository? = null
    private var patientId: Long = -1L

    fun init(patientId: Long, repo: LungRecordingRepository) {
        if (this.patientId == patientId) return   // already initialised
        this.patientId = patientId
        this.repository = repo

        viewModelScope.launch {
            repo.getRecordingsForPatient(patientId).collectLatest { recordings ->
                val map = recordings.associate { it.pointCode to true }
                _recordings.value = map
                autoAdvanceRegionIfNeeded(map)
            }
        }
    }

    private fun autoAdvanceRegionIfNeeded(map: Map<String, Boolean>) {
        val currentIdx = _currentRegionIndex.value ?: 0
        val currentRegion = LungRegion.entries[currentIdx]
        val pointsInRegion = LungPoints.byRegion(currentRegion)
        val allDone = pointsInRegion.all { map[it.code] == true }

        if (allDone && currentIdx < LungRegion.entries.size - 1) {
            // Silently advance to next region that still has un-recorded points
            val nextIdx = (currentIdx + 1 until LungRegion.entries.size).firstOrNull { idx ->
                val region = LungRegion.entries[idx]
                LungPoints.byRegion(region).any { map[it.code] != true }
            } ?: (currentIdx + 1)   // fall back to sequential
            if (nextIdx != currentIdx) _currentRegionIndex.value = nextIdx
        }
    }

    fun setRegion(index: Int) {
        _currentRegionIndex.value = index.coerceIn(0, LungRegion.entries.size - 1)
    }

    fun isRegionComplete(region: LungRegion): Boolean {
        val map = _recordings.value ?: return false
        return LungPoints.byRegion(region).all { map[it.code] == true }
    }

    fun allRegionsComplete(): Boolean =
        LungRegion.entries.all { isRegionComplete(it) }
}
