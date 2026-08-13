package com.musediagnostics.taal.visualizer.data.repository

import com.musediagnostics.taal.visualizer.data.db.dao.SessionDao
import com.musediagnostics.taal.visualizer.data.db.entity.SessionEntity
import com.musediagnostics.taal.visualizer.domain.PointSetType
import kotlinx.coroutines.flow.Flow

class SessionRepository(private val dao: SessionDao) {

    fun getSessionsForPointSet(pointSet: PointSetType): Flow<List<SessionEntity>> =
        dao.getSessionsForPointSet(pointSet.name)

    suspend fun getById(id: Long): SessionEntity? = dao.getById(id)

    suspend fun rename(sessionId: Long, name: String?) =
        dao.updateName(sessionId, name?.trim()?.ifBlank { null })

    /**
     * Returns the session to record into: the latest session if it still has
     * unrecorded points, otherwise a freshly created next-numbered session.
     */
    suspend fun getOrCreateActiveSession(pointSet: PointSetType, recordingCountForSession: suspend (Long) -> Int): SessionEntity {
        val latest = dao.getLatestSession(pointSet.name)
        if (latest != null && recordingCountForSession(latest.id) < pointSet.points.size) {
            return latest
        }
        val nextNumber = (latest?.sessionNumber ?: 0) + 1
        val newId = dao.insert(SessionEntity(pointSet = pointSet.name, sessionNumber = nextNumber))
        return SessionEntity(id = newId, pointSet = pointSet.name, sessionNumber = nextNumber)
    }
}
