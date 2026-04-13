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
        )
    ],
    indices = [Index("patientId")]
)
data class LungRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val pointCode: String,             // e.g., "aar", "pslr", "pill"
    val filePath: String,              // absolute path: filesDir/lungs/{seqNum}/{seqNum}_{pointCode}.wav
    val durationSeconds: Int,
    val createdAt: Long = System.currentTimeMillis()
)
