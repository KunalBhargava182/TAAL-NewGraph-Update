package com.musediagnostics.taal.lungs.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lung_sessions",
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
data class LungSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val sessionNumber: Int,
    val createdAt: Long = System.currentTimeMillis()
)
