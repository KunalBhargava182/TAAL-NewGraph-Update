package com.musediagnostics.taal.visualizer.util

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Saves/overwrites/deletes/reads back filtered recordings in the public Downloads
 * folder, under Audios/{Heart|Lungs}/Session {N}/, using MediaStore on API 29+ and
 * direct file I/O (WRITE_EXTERNAL_STORAGE) on API < 29 — same branching as lungs-app's
 * DenoiserFragment.downloadDenoised(), but used here as the primary save path
 * (not a secondary export) and with overwrite support for re-record.
 */
object DownloadsStorage {

    private const val RELATIVE_ROOT = "Audios"

    /**
     * True when a legacy-storage write would fail without first requesting
     * WRITE_EXTERNAL_STORAGE. API 29+ always uses MediaStore (no permission needed).
     * Callers must request the permission (needs an Activity/Fragment launcher) before
     * calling [save] when this returns true.
     */
    fun needsLegacyWritePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * content:// URI string (API 29+) or absolute file path (API < 29).
     * [subFolders] e.g. listOf("Heart", "Session 1") -> Download/Audios/Heart/Session 1/
     */
    fun save(context: Context, sourceFile: File, fileName: String, subFolders: List<String>): String {
        val relativePath = buildRelativePath(subFolders)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            deleteExistingByName(context, fileName, relativePath) // overwrite semantics for re-record

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out -> sourceFile.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            var dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            (listOf(RELATIVE_ROOT) + subFolders).forEach { dir = File(dir, it) }
            dir.mkdirs()
            val dest = File(dir, fileName)
            sourceFile.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }

    private fun buildRelativePath(subFolders: List<String>): String =
        (listOf(Environment.DIRECTORY_DOWNLOADS, RELATIVE_ROOT) + subFolders).joinToString("/")

    /** Deletes any prior MediaStore row with this exact name in this subfolder, ignoring failures. */
    private fun deleteExistingByName(context: Context, fileName: String, relativePath: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val args = arrayOf(fileName, "$relativePath/")
        try {
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Renames the saved file in place (same folder). Returns the (possibly new) uriOrPath —
     * unchanged for MediaStore content URIs (renaming only updates DISPLAY_NAME, the URI
     * itself is a stable row id), or the new absolute path for legacy file-based storage.
     */
    fun rename(context: Context, uriOrPath: String, newFileName: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uriOrPath.startsWith("content://")) {
            try {
                val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, newFileName) }
                context.contentResolver.update(Uri.parse(uriOrPath), values, null, null)
            } catch (_: Exception) {}
            uriOrPath
        } else {
            val oldFile = File(uriOrPath)
            val newFile = File(oldFile.parentFile, newFileName)
            try {
                if (oldFile.exists()) oldFile.renameTo(newFile)
            } catch (_: Exception) {}
            newFile.absolutePath
        }
    }

    fun delete(context: Context, uriOrPath: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uriOrPath.startsWith("content://")) {
            try { context.contentResolver.delete(Uri.parse(uriOrPath), null, null) } catch (_: Exception) {}
        } else {
            try { File(uriOrPath).delete() } catch (_: Exception) {}
        }
    }

    /**
     * Returns a real filesystem path TaalPlayer can open. On API < 29, [uriOrPath] is
     * already a plain file path. On API 29+, [uriOrPath] is a content:// MediaStore URI —
     * copy its bytes into a cache file first, since TaalPlayer reads via java.io.File.
     */
    fun resolvePlayablePath(context: Context, uriOrPath: String): String {
        if (!uriOrPath.startsWith("content://")) return uriOrPath
        val cacheFile = File(context.cacheDir, "playback_temp.wav")
        context.contentResolver.openInputStream(Uri.parse(uriOrPath))?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        return cacheFile.absolutePath
    }

    /** Directly shareable for MediaStore content URIs; wraps a legacy file path via FileProvider otherwise. */
    fun shareUri(context: Context, uriOrPath: String): Uri {
        if (uriOrPath.startsWith("content://")) return Uri.parse(uriOrPath)
        return androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(uriOrPath)
        )
    }

    fun sanitizeFileNamePart(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(Regex("\\s+"), "_")
        return cleaned.ifBlank { "Recording" }
    }
}
