package com.musediagnostics.taal.lungs.data.repository

import com.musediagnostics.taal.lungs.data.db.dao.LungPatientDao
import com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity
import kotlinx.coroutines.flow.Flow

class LungPatientRepository(private val dao: LungPatientDao) {

    fun getAllPatients(): Flow<List<LungPatientEntity>> = dao.getAllPatients()

    suspend fun insert(patient: LungPatientEntity): Long = dao.insert(patient)

    suspend fun getById(id: Long): LungPatientEntity? = dao.getById(id)

    suspend fun getNextSequenceNumber(): Int = dao.getCount() + 1

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
