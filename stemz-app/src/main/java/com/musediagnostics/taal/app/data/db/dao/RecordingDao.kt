package com.musediagnostics.taal.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.musediagnostics.taal.app.data.db.entity.RecordingEntity
import com.musediagnostics.taal.app.data.db.entity.RecordingWithPatient
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Delete
    suspend fun delete(recording: RecordingEntity)

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun getAllRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE patientId = :patientId ORDER BY createdAt DESC")
    fun getRecordingsForPatient(patientId: Long): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC LIMIT 1")
    suspend fun getMostRecentRecording(): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE isEmergency = 1 ORDER BY createdAt DESC")
    fun getEmergencyRecordings(): Flow<List<RecordingEntity>>

    @Query("UPDATE recordings SET fileName = :newName WHERE id = :id")
    suspend fun renameRecording(id: Long, newName: String)

    @Query("""
        SELECT r.*, p.fullName AS patientName, p.patientId AS patientIdentifier
        FROM recordings r
        LEFT JOIN patients p ON r.patientId = p.id
        ORDER BY r.createdAt DESC
    """)
    fun getRecordingsWithPatients(): Flow<List<RecordingWithPatient>>

    @Query("""
        SELECT r.*, p.fullName AS patientName, p.patientId AS patientIdentifier
        FROM recordings r
        LEFT JOIN patients p ON r.patientId = p.id
        WHERE p.fullName LIKE '%' || :query || '%'
           OR p.patientId LIKE '%' || :query || '%'
           OR r.fileName LIKE '%' || :query || '%'
        ORDER BY r.createdAt DESC
    """)
    fun searchRecordingsWithPatients(query: String): Flow<List<RecordingWithPatient>>
}
