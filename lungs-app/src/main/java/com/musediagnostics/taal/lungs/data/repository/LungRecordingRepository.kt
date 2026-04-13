package com.musediagnostics.taal.lungs.data.repository

import com.musediagnostics.taal.lungs.data.db.dao.LungRecordingDao
import com.musediagnostics.taal.lungs.data.db.entity.LungRecordingEntity
import kotlinx.coroutines.flow.Flow

class LungRecordingRepository(private val dao: LungRecordingDao) {

    fun getRecordingsForPatient(patientId: Long): Flow<List<LungRecordingEntity>> =
        dao.getRecordingsForPatient(patientId)

    suspend fun insert(recording: LungRecordingEntity): Long = dao.insert(recording)

    suspend fun getRecordingCountForPatient(patientId: Long): Int =
        dao.getRecordingCountForPatient(patientId)

    suspend fun getRecordingForPoint(patientId: Long, pointCode: String): LungRecordingEntity? =
        dao.getRecordingForPoint(patientId, pointCode)

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
