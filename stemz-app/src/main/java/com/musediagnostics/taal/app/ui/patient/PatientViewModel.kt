package com.musediagnostics.taal.app.ui.patient

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.musediagnostics.taal.app.TaalApplication
import com.musediagnostics.taal.app.data.db.entity.PatientEntity
import com.musediagnostics.taal.app.data.db.entity.RecordingEntity
import com.musediagnostics.taal.app.data.repository.PatientRepository
import com.musediagnostics.taal.app.data.repository.RecordingRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PatientViewModel : ViewModel() {

    private val db = TaalApplication.instance.database
    private val patientRepository = PatientRepository(db.patientDao())
    private val recordingRepository = RecordingRepository(db.recordingDao())

    private val searchQuery = MutableStateFlow("")

    val searchResults: LiveData<List<PatientEntity>> = searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                patientRepository.getAllPatients()
            } else {
                patientRepository.searchPatients(query)
            }
        }
        .asLiveData()

    private val _saveResult = MutableLiveData<SaveResult>()
    val saveResult: LiveData<SaveResult> = _saveResult

    fun search(query: String) {
        searchQuery.value = query
    }

    fun saveNewPatientWithRecording(
        fullName: String,
        patientId: String,
        phone: String,
        email: String,
        dateOfBirth: String,
        biologicalSex: String,
        recordingFilePath: String
    ) {
        if (fullName.isBlank()) {
            _saveResult.value = SaveResult.Error("Full name is required")
            return
        }

        viewModelScope.launch {
            try {
                // Create patient
                val patient = PatientEntity(
                    fullName = fullName,
                    patientId = patientId,
                    phone = phone,
                    email = email,
                    dateOfBirth = dateOfBirth,
                    biologicalSex = biologicalSex
                )
                val newPatientId = patientRepository.insertPatient(patient)

                // Attach recording to patient
                if (recordingFilePath.isNotBlank()) {
                    val recording = RecordingEntity(
                        patientId = newPatientId,
                        filePath = recordingFilePath,
                        fileName = recordingFilePath.substringAfterLast("/")
                    )
                    recordingRepository.insertRecording(recording)
                }

                _saveResult.postValue(SaveResult.Success(newPatientId))
            } catch (e: Exception) {
                _saveResult.postValue(SaveResult.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun attachRecordingToExistingPatient(patientId: Long, recordingFilePath: String) {
        viewModelScope.launch {
            try {
                val recording = RecordingEntity(
                    patientId = patientId,
                    filePath = recordingFilePath,
                    fileName = recordingFilePath.substringAfterLast("/")
                )
                recordingRepository.insertRecording(recording)
                _saveResult.postValue(SaveResult.Success(patientId))
            } catch (e: Exception) {
                _saveResult.postValue(SaveResult.Error(e.message ?: "Unknown error"))
            }
        }
    }

    sealed class SaveResult {
        data class Success(val patientId: Long) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }
}
