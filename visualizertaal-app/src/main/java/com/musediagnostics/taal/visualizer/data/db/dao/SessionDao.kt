package com.musediagnostics.taal.visualizer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.musediagnostics.taal.visualizer.data.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE pointSet = :pointSet ORDER BY sessionNumber DESC")
    fun getSessionsForPointSet(pointSet: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE pointSet = :pointSet ORDER BY sessionNumber DESC LIMIT 1")
    suspend fun getLatestSession(pointSet: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Query("UPDATE sessions SET customName = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String?)
}
