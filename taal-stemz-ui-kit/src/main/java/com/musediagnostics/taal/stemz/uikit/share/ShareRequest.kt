package com.musediagnostics.taal.stemz.uikit.share

/**
 * Pure Kotlin descriptor of a share action — no android.content.Intent / android.net.Uri
 * dependency, so its shape is plain-JVM unit-testable (ShareRequestTest) without a device or
 * Robolectric, which stemz-app has neither of today.
 *
 * [attachmentPaths] are local file paths; the Android-facing caller (GraphShareBundler /
 * SavedRecordingsFragment) turns each into a content:// Uri via FileProvider.getUriForFile and
 * builds the real Intent from this descriptor's [action]/[mimeType]/[subject]/[chooserTitle].
 *
 * [audioOnly] documents the EXISTING, untouched SavedRecordingsFragment.shareRecording() body
 * as a reference shape only — that function is not refactored to call this (see the plan's
 * safety section: the legacy path is never edited), so this factory exists purely so
 * ShareRequestTest can pin both the old and new share shapes side by side and catch either one
 * drifting in a future change.
 */
internal enum class ShareAction { SEND, SEND_MULTIPLE }

internal data class ShareRequest(
    val action: ShareAction,
    val mimeType: String,
    val attachmentPaths: List<String>,
    val subject: String?,
    val title: String?,
    val chooserTitle: String
) {
    companion object {
        // Matches SavedRecordingsFragment.shareRecording's Intent(Intent.ACTION_SEND).type
        // exactly — see that function's own comment for why it's generic, not "audio/wav".
        const val LEGACY_SINGLE_MIME_TYPE = "application/octet-stream"

        // A mixed wav+pdf bundle has no single correct MIME type; "*/*" routes receiving
        // apps down their generic "send as document" path, same anti-transcode reasoning as
        // the plain single-file share's application/octet-stream choice.
        const val BUNDLE_MIME_TYPE = "*/*"

        fun audioOnly(wavPath: String, displayName: String): ShareRequest = ShareRequest(
            action = ShareAction.SEND,
            mimeType = LEGACY_SINGLE_MIME_TYPE,
            attachmentPaths = listOf(wavPath),
            subject = displayName,
            title = displayName,
            chooserTitle = displayName
        )

        // No PNG — per explicit request, the graph share bundle is wav + pdf only.
        fun audioWithGraph(
            wavPath: String,
            pdfPath: String,
            displayName: String
        ): ShareRequest = ShareRequest(
            action = ShareAction.SEND_MULTIPLE,
            mimeType = BUNDLE_MIME_TYPE,
            attachmentPaths = listOf(wavPath, pdfPath),
            subject = displayName,
            title = displayName,
            chooserTitle = displayName
        )
    }
}
