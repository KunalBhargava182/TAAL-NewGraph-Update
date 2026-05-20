package com.musediagnostics.taal.lungs.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lung_recordings",
    foreignKeys = [
        ForeignKey(
            entity = LungPatientEntity::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = LungSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("patientId"), Index("sessionId")]
)
data class LungRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val sessionId: Long,
    val pointCode: String,
    val filePath: String,
    val durationSeconds: Int,
    val createdAt: Long = System.currentTimeMillis()
)
