package com.musediagnostics.taal.stemz.uikit.share

import android.content.Context
import com.musediagnostics.taal.stemz.graph.PcgAmplitudeScale
import com.musediagnostics.taal.stemz.dsp.PcgDisplayFilter
import com.musediagnostics.taal.stemz.util.PcgWavDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Outcome of [GraphShareBundler.buildShareRequest] — either a ready-to-launch [ShareRequest] or
 *  a reason the bundle couldn't be built, so the Fragment can toast something specific instead
 *  of failing silently. */
internal sealed class GraphShareResult {
    data class Success(val request: ShareRequest) : GraphShareResult()
    data class Failure(val reason: String) : GraphShareResult()
}

/**
 * Orchestrates the "share with graph" path end to end: read the saved `.wav` -> decode ->
 * condition with [PcgDisplayFilter] (the Clean-Graph-ON state, matching PcgScaleReviewFragment's
 * default-ON behavior) -> compute the same whole-file Y-axis scale the Review screen shows ->
 * render the multi-row strip -> write wav+pdf (no PNG — per explicit request) into a share-only
 * temp directory -> describe the resulting [ShareRequest].
 *
 * Writes into `{savedDir}/.share_bundle_tmp/`, a DIFFERENT directory from the existing
 * `.share_tmp/` SavedRecordingsFragment.shareRecording() uses and wipes on every call — the two
 * temp directories can never race or delete each other's in-flight files. Cleans its own
 * directory on every call, same lazy-cleanup posture as the existing one.
 *
 * Runs entirely on [Dispatchers.Default] / [Dispatchers.IO] via [withContext] — decode +
 * PcgDisplayFilter + render of a long recording is not instant. Callers must not invoke this
 * from the main thread without their own coroutine scope wrapping it (see
 * SavedRecordingsFragment.shareRecordingWithGraph).
 */
internal object GraphShareBundler {

    private const val TEMP_DIR_NAME = ".share_bundle_tmp"

    suspend fun buildShareRequest(context: Context, filteredWavFile: File): GraphShareResult =
        withContext(Dispatchers.IO) {
            if (!filteredWavFile.exists()) {
                return@withContext GraphShareResult.Failure("Recording not found")
            }

            val decoded = try {
                PcgWavDecoder.decode(filteredWavFile.readBytes())
            } catch (e: Exception) {
                null
            } ?: return@withContext GraphShareResult.Failure("Unable to read recording")

            val displayName = RecordingDisplayName.displayName(filteredWavFile.nameWithoutExtension)

            // Clean Graph ON is this screen family's default state (PcgScaleReviewFragment
            // calls onDenoiseToggled(true) immediately after decode) — the shared graph matches
            // what a user opening this recording sees first, not an arbitrary choice.
            val cleanedSamples = try {
                PcgDisplayFilter.processOffline(decoded.samples, decoded.sampleRate)
            } catch (e: Exception) {
                decoded.samples // Conditioning failure degrades to the raw trace, not a hard failure.
            }
            val fullScale = PcgAmplitudeScale.computeFullScaleForFile(cleanedSamples, decoded.sampleRate)

            val shareDir = File(filteredWavFile.parentFile, TEMP_DIR_NAME).also { it.mkdirs() }
            shareDir.listFiles()?.forEach { it.delete() }

            val wavOut = File(shareDir, "$displayName.wav")
            val pdfOut = File(shareDir, "$displayName.pdf")

            try {
                filteredWavFile.copyTo(wavOut, overwrite = true)

                val title = displayName
                val subtitle = "${decoded.durationSecs.toInt()}s · Clean Graph ON"

                pdfOut.outputStream().use { out ->
                    GraphShareExporter.writePdf(
                        context, cleanedSamples, decoded.sampleRate, fullScale,
                        decoded.durationSecs, title, subtitle, out
                    )
                }
            } catch (e: Exception) {
                return@withContext GraphShareResult.Failure("Unable to prepare files for sharing")
            }

            GraphShareResult.Success(
                ShareRequest.audioWithGraph(
                    wavPath = wavOut.absolutePath,
                    pdfPath = pdfOut.absolutePath,
                    displayName = displayName
                )
            )
        }
}
