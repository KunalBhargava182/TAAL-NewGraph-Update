package com.musediagnostics.taal.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.musediagnostics.taal.app.data.db.entity.PatientEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(patient: PatientEntity): Long

    @Update
    suspend fun update(patient: PatientEntity)

    @Delete
    suspend fun delete(patient: PatientEntity)

    @Query("SELECT * FROM patients ORDER BY createdAt DESC")
    fun getAllPatients(): Flow<List<PatientEntity>>

    @Query("SELECT * FROM patients WHERE id = :id")
    suspend fun getPatientById(id: Long): PatientEntity?

    @Query("SELECT * FROM patients WHERE fullName LIKE '%' || :query || '%' OR patientId LIKE '%' || :query || '%' ORDER BY fullName ASC")
    fun searchPatients(query: String): Flow<List<PatientEntity>>

    @Query("SELECT COUNT(*) FROM patients")
    suspend fun getPatientCount(): Int

    @Query("""
        SELECT DISTINCT p.* FROM patients p
        INNER JOIN recordings r ON r.patientId = p.id
        ORDER BY p.fullName ASC
    """)
    fun getPatientsWithRecordings(): Flow<List<PatientEntity>>
}
