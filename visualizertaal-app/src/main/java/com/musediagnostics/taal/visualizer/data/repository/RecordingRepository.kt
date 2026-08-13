package com.musediagnostics.taal.visualizer.data.repository

import com.musediagnostics.taal.visualizer.data.db.dao.RecordingDao
import com.musediagnostics.taal.visualizer.data.db.entity.RecordingEntity
import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val dao: RecordingDao) {

    fun getRecordingsForSession(sessionId: Long): Flow<List<RecordingEntity>> =
        dao.getRecordingsForSession(sessionId)

    suspend fun getRecordingsForSessionOnce(sessionId: Long): List<RecordingEntity> =
        dao.getRecordingsForSessionOnce(sessionId)

    suspend fun getRecordingCountForSession(sessionId: Long): Int =
        dao.getRecordingCountForSession(sessionId)

    suspend fun insert(recording: RecordingEntity) = dao.insert(recording)

    suspend fun updateFileInfo(id: Long, fileName: String, uriOrPath: String) =
        dao.updateFileInfo(id, fileName, uriOrPath)

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
