package com.musediagnostics.taal.lungs.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lung_patients")
data class LungPatientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sequenceNumber: Int,           // formatted display: 01, 02, 03…
    val sex: String,                   // "Male" | "Female" | "Other"
    val age: Int,
    val chestCircumferenceCm: Float,
    val heightCm: Float,
    val weightKg: Float,
    val bmi: Float,                    // auto-computed = weightKg / (heightCm/100)^2
    val createdAt: Long = System.currentTimeMillis()
)
