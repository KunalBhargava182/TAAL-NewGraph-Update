package com.musediagnostics.taal.lungs.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LungPatientDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(patient: LungPatientEntity): Long

    @Query("SELECT * FROM lung_patients ORDER BY sequenceNumber ASC")
    fun getAllPatients(): Flow<List<LungPatientEntity>>

    @Query("SELECT * FROM lung_patients WHERE id = :id")
    suspend fun getById(id: Long): LungPatientEntity?

    @Query("SELECT COUNT(*) FROM lung_patients")
    suspend fun getCount(): Int

    @Query("DELETE FROM lung_patients WHERE id = :id")
    suspend fun deleteById(id: Long)
}
