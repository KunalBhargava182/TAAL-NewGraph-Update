package com.musediagnostics.taal.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "patients")
data class PatientEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fullName: String,
    val patientId: String = "",
    val phone: String = "",
    val email: String = "",
    val dateOfBirth: String = "",
    val biologicalSex: String = "",
    val conditions: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
