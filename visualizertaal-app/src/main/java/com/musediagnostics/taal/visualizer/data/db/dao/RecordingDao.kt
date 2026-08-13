package com.musediagnostics.taal.visualizer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.musediagnostics.taal.visualizer.data.db.entity.RecordingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    /** REPLACE handles re-record overwrite via the unique (sessionId, pointCode) index. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity)

    @Query("SELECT * FROM recordings WHERE sessionId = :sessionId")
    fun getRecordingsForSession(sessionId: Long): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE sessionId = :sessionId")
    suspend fun getRecordingsForSessionOnce(sessionId: Long): List<RecordingEntity>

    @Query("SELECT COUNT(*) FROM recordings WHERE sessionId = :sessionId")
    suspend fun getRecordingCountForSession(sessionId: Long): Int

    @Query("UPDATE recordings SET fileName = :fileName, uriOrPath = :uriOrPath WHERE id = :id")
    suspend fun updateFileInfo(id: Long, fileName: String, uriOrPath: String)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)
}
