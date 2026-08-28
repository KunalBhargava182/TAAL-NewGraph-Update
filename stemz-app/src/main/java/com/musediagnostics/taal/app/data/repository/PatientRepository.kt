package com.musediagnostics.taal.app.data.repository

import com.musediagnostics.taal.app.data.db.dao.PatientDao
import com.musediagnostics.taal.app.data.db.entity.PatientEntity
import kotlinx.coroutines.flow.Flow

class PatientRepository(private val patientDao: PatientDao) {

    fun getAllPatients(): Flow<List<PatientEntity>> = patientDao.getAllPatients()

    fun searchPatients(query: String): Flow<List<PatientEntity>> = patientDao.searchPatients(query)

    suspend fun getPatientById(id: Long): PatientEntity? = patientDao.getPatientById(id)

    suspend fun insertPatient(patient: PatientEntity): Long = patientDao.insert(patient)

    suspend fun updatePatient(patient: PatientEntity) = patientDao.update(patient)

    suspend fun deletePatient(patient: PatientEntity) = patientDao.delete(patient)

    suspend fun getPatientCount(): Int = patientDao.getPatientCount()

    fun getPatientsWithRecordings(): Flow<List<PatientEntity>> = patientDao.getPatientsWithRecordings()
}
