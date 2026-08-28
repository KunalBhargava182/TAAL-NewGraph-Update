package com.musediagnostics.taal.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recordings",
    foreignKeys = [
        ForeignKey(
            entity = PatientEntity::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("patientId")]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val patientId: Long? = null,
    val filePath: String,
    val fileName: String = "",
    val filterType: String = "HEART",
    val durationSeconds: Int = 0,
    val bpm: Int = 0,
    val preAmplification: Float = 0f,
    val isEmergency: Boolean = false,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
