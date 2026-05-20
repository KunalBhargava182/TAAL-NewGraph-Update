package com.musediagnostics.taal.lungs.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.musediagnostics.taal.lungs.data.db.entity.LungSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LungSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: LungSessionEntity): Long

    @Query("SELECT * FROM lung_sessions WHERE patientId = :patientId ORDER BY sessionNumber ASC")
    fun getSessionsForPatient(patientId: Long): Flow<List<LungSessionEntity>>

    @Query("SELECT * FROM lung_sessions WHERE id = :id")
    suspend fun getById(id: Long): LungSessionEntity?

    @Query("SELECT COUNT(*) FROM lung_sessions WHERE patientId = :patientId")
    suspend fun getSessionCountForPatient(patientId: Long): Int
}
