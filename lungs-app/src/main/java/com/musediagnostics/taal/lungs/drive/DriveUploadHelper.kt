package com.musediagnostics.taal.lungs.drive

import android.content.Context
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.services.drive.model.Permission
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.ServiceAccountCredentials
import com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity
import com.musediagnostics.taal.lungs.data.db.entity.LungRecordingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles uploading a patient's recordings + report to a Google Drive folder
 * using a service account. The service account credentials are read from
 * assets/service_account.json.
 *
 * All uploaded files land in the service account's own Drive under:
 *   TaalLungs Auscultation/
 *   └── Patient_01_2026-04-13/
 *       ├── report.txt
 *       ├── 01_RUF.wav
 *       └── ...
 *
 * To access the files, share the "TaalLungs Auscultation" root folder with
 * your personal Google account once from drive.google.com (sign in with the
 * service account credentials via the Drive API explorer, or use the
 * SHARE_WITH_EMAIL constant below).
 *
 * To auto-share with a specific email on first folder creation, set:
 *   SHARE_WITH_EMAIL = "your.email@gmail.com"
 */
class DriveUploadHelper(private val context: Context) {

    companion object {
        private const val ROOT_FOLDER_NAME = "TaalLungs Auscultation"
        private const val APP_NAME = "TaalLungs"

        /**
         * Set this to your personal Gmail to auto-share the root Drive folder.
         * Leave empty ("") to skip sharing.
         */
        private const val SHARE_WITH_EMAIL = "cloudbotz2024@gmail.com"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API

    /**
     * Uploads all [recordings] + a report.txt for [patient] to Google Drive.
     * Progress is reported via [onProgress] on the Main thread.
     *
     * @return The webViewLink URL of the session folder in Drive.
     * @throws Exception on auth error, network failure, or IO error.
     */
    suspend fun uploadSession(
        patient: LungPatientEntity,
        recordings: List<LungRecordingEntity>,
        onProgress: suspend (uploaded: Int, total: Int) -> Unit
    ): String = withContext(Dispatchers.IO) {

        val drive = buildDriveService()

        // 1. Root folder (create once, reuse)
        val (rootFolderId, rootWasNew) = findOrCreateFolder(drive, ROOT_FOLDER_NAME, null)
        if (rootWasNew && SHARE_WITH_EMAIL.isNotBlank()) {
            shareFolder(drive, rootFolderId, SHARE_WITH_EMAIL)
        }

        // 2. Patient session folder: "Patient_01_2026-04-13"
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val folderName = "Patient_%02d_%s".format(
            patient.sequenceNumber, dateFmt.format(Date(patient.createdAt))
        )
        val (sessionFolderId, _) = findOrCreateFolder(drive, folderName, rootFolderId)

        val total = recordings.size + 1 // +1 for report.txt
        withContext(Dispatchers.Main) { onProgress(0, total) }

        // 3. Upload report.txt
        val reportText = buildReportText(patient, recordings)
        uploadOrReplaceText(drive, "report.txt", reportText, sessionFolderId)
        withContext(Dispatchers.Main) { onProgress(1, total) }

        // 4. Upload each WAV file
        recordings.forEachIndexed { index, rec ->
            val file = File(rec.filePath)
            if (file.exists()) {
                uploadOrReplaceWav(drive, file, sessionFolderId)
            }
            withContext(Dispatchers.Main) { onProgress(index + 2, total) }
        }

        // 5. Return the web link so the user can open the folder in a browser
        drive.files().get(sessionFolderId)
            .setFields("webViewLink")
            .execute()
            .webViewLink ?: "https://drive.google.com"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drive helpers

    private fun buildDriveService(): Drive {
        val credentials = context.assets.open("service_account.json").use { stream ->
            ServiceAccountCredentials.fromStream(stream)
                .createScoped(listOf(DriveScopes.DRIVE))
        }
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            HttpCredentialsAdapter(credentials)
        ).setApplicationName(APP_NAME).build()
    }

    /** Returns Pair(id, wasJustCreated). */
    private fun findOrCreateFolder(
        drive: Drive, name: String, parentId: String?
    ): Pair<String, Boolean> {
        val q = buildString {
            append("mimeType='application/vnd.google-apps.folder'")
            append(" and name='${name.replace("'", "\\'")}'")
            append(" and trashed=false")
            if (parentId != null) append(" and '$parentId' in parents")
        }
        drive.files().list()
            .setQ(q).setFields("files(id)").setPageSize(1).execute()
            .files?.firstOrNull()?.id?.let { return Pair(it, false) }

        val meta = DriveFile().apply {
            this.name = name
            mimeType = "application/vnd.google-apps.folder"
            if (parentId != null) parents = listOf(parentId)
        }
        val id = drive.files().create(meta).setFields("id").execute().id
        return Pair(id, true)
    }

    private fun shareFolder(drive: Drive, folderId: String, email: String) {
        try {
            drive.permissions().create(
                folderId,
                Permission().apply {
                    type = "user"
                    role = "writer"
                    emailAddress = email
                }
            ).setSendNotificationEmail(false).execute()
        } catch (_: Exception) {
            // Sharing failure is non-fatal — files are still uploaded
        }
    }

    private fun uploadOrReplaceText(
        drive: Drive, name: String, content: String, parentId: String
    ) {
        val media = ByteArrayContent("text/plain", content.toByteArray(Charsets.UTF_8))
        val existingId = findFileInFolder(drive, name, parentId)
        if (existingId != null) {
            drive.files().update(existingId, DriveFile(), media).execute()
        } else {
            val meta = DriveFile().apply { this.name = name; parents = listOf(parentId) }
            drive.files().create(meta, media).execute()
        }
    }

    private fun uploadOrReplaceWav(drive: Drive, file: File, parentId: String) {
        // ByteArrayContent uses multipart upload (POST), not resumable upload (PUT).
        // This avoids 403 issues that can occur with the resumable upload endpoint.
        val bytes = file.readBytes()
        val media = ByteArrayContent("audio/wav", bytes)
        val existingId = findFileInFolder(drive, file.name, parentId)
        if (existingId != null) {
            drive.files().update(existingId, DriveFile(), media).execute()
        } else {
            val meta = DriveFile().apply { name = file.name; parents = listOf(parentId) }
            drive.files().create(meta, media).execute()
        }
    }

    private fun findFileInFolder(drive: Drive, name: String, parentId: String): String? {
        val q = "name='${name.replace("'", "\\'")}' and '$parentId' in parents and trashed=false"
        return drive.files().list()
            .setQ(q).setFields("files(id)").setPageSize(1).execute()
            .files?.firstOrNull()?.id
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Report text

    private fun buildReportText(
        patient: LungPatientEntity,
        recordings: List<LungRecordingEntity>
    ): String {
        val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        return buildString {
            appendLine("=== LUNGS AUSCULTATION REPORT ===")
            appendLine()
            appendLine("Patient No : %02d".format(patient.sequenceNumber))
            appendLine("Sex        : ${patient.sex}")
            appendLine("Age        : ${patient.age} yrs")
            appendLine("BMI        : ${"%.1f".format(patient.bmi)}")
            appendLine("Chest      : ${"%.1f".format(patient.chestCircumferenceCm)} cm")
            appendLine("Height     : ${"%.1f".format(patient.heightCm)} cm")
            appendLine("Weight     : ${"%.1f".format(patient.weightKg)} kg")
            appendLine("Date       : ${fmt.format(Date(patient.createdAt))}")
            appendLine()
            appendLine("--- RECORDINGS (${recordings.size} / 16) ---")
            recordings.forEach { rec ->
                val dur = "%d:%02d".format(rec.durationSeconds / 60, rec.durationSeconds % 60)
                appendLine("  ${File(rec.filePath).name}  –  $dur")
            }
            appendLine()
            appendLine("Generated by TaalLungs Auscultation App")
        }
    }
}
