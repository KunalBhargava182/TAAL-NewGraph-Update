package com.musediagnostics.taal.app.ui.library

import com.musediagnostics.taal.app.data.db.entity.PatientEntity
import com.musediagnostics.taal.app.data.db.entity.RecordingWithPatient

sealed class PatientLibraryItem {
    data class PatientHeader(
        val patient: PatientEntity,
        val recordingCount: Int,
        var isExpanded: Boolean = false
    ) : PatientLibraryItem()

    data class RecordingItem(
        val recording: RecordingWithPatient
    ) : PatientLibraryItem()
}
