package com.musediagnostics.taal.visualizer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pointSet: String,          // "HEART" or "LUNGS" — PointSetType.name
    val sessionNumber: Int,        // 1, 2, 3... scoped per pointSet
    val customName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** "Session N" unless the user has given it a custom name. */
fun SessionEntity.displayName(): String = customName?.takeIf { it.isNotBlank() } ?: "Session $sessionNumber"
