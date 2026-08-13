package com.musediagnostics.taal.visualizer.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recordings",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"], childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId"), Index("sessionId", "pointCode", unique = true)]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val pointCode: String,
    val fileName: String,
    /** content:// MediaStore URI (API 29+) or absolute file path (API < 29). */
    val uriOrPath: String,
    val createdAt: Long = System.currentTimeMillis()
)
