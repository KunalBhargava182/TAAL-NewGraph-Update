package com.musediagnostics.taal.lungs.data.repository

import com.musediagnostics.taal.lungs.data.db.dao.LungSessionDao
import com.musediagnostics.taal.lungs.data.db.entity.LungSessionEntity
import kotlinx.coroutines.flow.Flow

class LungSessionRepository(private val dao: LungSessionDao) {

    suspend fun insert(session: LungSessionEntity): Long = dao.insert(session)

    fun getSessionsForPatient(patientId: Long): Flow<List<LungSessionEntity>> =
        dao.getSessionsForPatient(patientId)

    suspend fun getSessionCountForPatient(patientId: Long): Int =
        dao.getSessionCountForPatient(patientId)

    suspend fun getById(id: Long): LungSessionEntity? = dao.getById(id)
}
