package com.musediagnostics.taal.lungs.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.musediagnostics.taal.lungs.data.db.entity.LungRecordingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LungRecordingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: LungRecordingEntity): Long

    @Query("SELECT * FROM lung_recordings WHERE patientId = :patientId")
    fun getRecordingsForPatient(patientId: Long): Flow<List<LungRecordingEntity>>

    @Query("SELECT * FROM lung_recordings WHERE sessionId = :sessionId")
    fun getRecordingsForSession(sessionId: Long): Flow<List<LungRecordingEntity>>

    @Query("SELECT COUNT(*) FROM lung_recordings WHERE patientId = :patientId")
    suspend fun getRecordingCountForPatient(patientId: Long): Int

    @Query("SELECT COUNT(*) FROM lung_recordings WHERE sessionId = :sessionId")
    suspend fun getRecordingCountForSession(sessionId: Long): Int

    @Query("SELECT * FROM lung_recordings WHERE sessionId = :sessionId AND pointCode = :pointCode LIMIT 1")
    suspend fun getRecordingForPointInSession(sessionId: Long, pointCode: String): LungRecordingEntity?

    @Query("DELETE FROM lung_recordings WHERE id = :id")
    suspend fun deleteById(id: Long)
}
