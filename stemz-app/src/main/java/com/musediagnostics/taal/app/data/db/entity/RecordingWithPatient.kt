package com.musediagnostics.taal.app.data.db.entity

import androidx.room.Embedded

data class RecordingWithPatient(
    @Embedded val recording: RecordingEntity,
    val patientName: String?,
    val patientIdentifier: String?
)
