package com.musediagnostics.taal.app.data.repository

import com.musediagnostics.taal.app.data.db.dao.RecordingDao
import com.musediagnostics.taal.app.data.db.entity.RecordingEntity
import com.musediagnostics.taal.app.data.db.entity.RecordingWithPatient
import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val recordingDao: RecordingDao) {

    fun getAllRecordings(): Flow<List<RecordingEntity>> = recordingDao.getAllRecordings()

    fun getRecordingsForPatient(patientId: Long): Flow<List<RecordingEntity>> =
        recordingDao.getRecordingsForPatient(patientId)

    fun getEmergencyRecordings(): Flow<List<RecordingEntity>> = recordingDao.getEmergencyRecordings()

    suspend fun getRecordingById(id: Long): RecordingEntity? = recordingDao.getRecordingById(id)

    suspend fun getMostRecentRecording(): RecordingEntity? = recordingDao.getMostRecentRecording()

    suspend fun insertRecording(recording: RecordingEntity): Long = recordingDao.insert(recording)

    suspend fun updateRecording(recording: RecordingEntity) = recordingDao.update(recording)

    suspend fun deleteRecording(recording: RecordingEntity) = recordingDao.delete(recording)

    suspend fun renameRecording(id: Long, newName: String) = recordingDao.renameRecording(id, newName)

    fun getRecordingsWithPatients(): Flow<List<RecordingWithPatient>> =
        recordingDao.getRecordingsWithPatients()

    fun searchRecordingsWithPatients(query: String): Flow<List<RecordingWithPatient>> =
        recordingDao.searchRecordingsWithPatients(query)
}
