package com.musediagnostics.taal.visualizer.data.repository

import android.content.Context
import com.musediagnostics.taal.visualizer.data.db.entity.displayName
import com.musediagnostics.taal.visualizer.domain.PointSetType
import com.musediagnostics.taal.visualizer.util.DownloadsStorage

/**
 * Renames a session and keeps every already-saved recording's filename in sync
 * with the new name ({sessionDisplayName}_{pointLabel}.wav), so files on disk
 * never go stale relative to what the app shows.
 */
class SessionRenameCoordinator(
    private val context: Context,
    private val sessionRepo: SessionRepository,
    private val recordingRepo: RecordingRepository
) {
    suspend fun renameSession(sessionId: Long, newName: String?) {
        sessionRepo.rename(sessionId, newName)
        val session = sessionRepo.getById(sessionId) ?: return
        val pointSetType = PointSetType.valueOf(session.pointSet)
        val sessionLabel = DownloadsStorage.sanitizeFileNamePart(session.displayName())

        recordingRepo.getRecordingsForSessionOnce(sessionId).forEach { recording ->
            val pointLabel = pointSetType.pointByCode(recording.pointCode)?.label ?: recording.pointCode
            val newFileName = "${sessionLabel}_${DownloadsStorage.sanitizeFileNamePart(pointLabel)}.wav"
            if (newFileName != recording.fileName) {
                val newUriOrPath = DownloadsStorage.rename(context, recording.uriOrPath, newFileName)
                recordingRepo.updateFileInfo(recording.id, newFileName, newUriOrPath)
            }
        }
    }
}
