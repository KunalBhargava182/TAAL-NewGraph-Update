package com.musediagnostics.taal.stemz.uikit

import android.content.Context
import com.musediagnostics.taal.stemz.dsp.PcgDisplayFilter
import com.musediagnostics.taal.stemz.graph.PcgAmplitudeScale
import com.musediagnostics.taal.stemz.util.PcgWavDecoder
import com.musediagnostics.taal.stemz.uikit.share.GraphShareExporter
import com.musediagnostics.taal.stemz.uikit.share.RecordingDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Helpers for integrators who build their own screens on top of taal-stemz-ui-kit's pieces. */
object TaalStemzUiKit {

    /** Version of taal-stemz-ui-kit. */
    const val VERSION = "1.0.0"

    /**
     * FileProvider authority the UI kit uses for sharing (declared in the UI kit's own manifest,
     * scoped under `saved/` in the app's files dir). Separate from any `.fileprovider` the host
     * app declares, so the two never clash.
     */
    @JvmStatic
    fun fileProviderAuthority(context: Context): String = "${context.packageName}.taalstemz.fileprovider"

    /**
     * Writes a PDF of the whole recording drawn on the PcgScale grid (1 large box = 1 s,
     * 1 small box = 0.2 s, stacked 5-second rows, Clean Graph ON, auto-scaled Y axis) — the same
     * PDF the Saved Recordings share button attaches. Runs on background dispatchers; call from a
     * coroutine.
     *
     * @param wavFile any 16-bit mono PCM WAV (normally a `_filtered.wav`).
     * @param outputPdf destination file (created/overwritten).
     * @return true if the PDF was written.
     */
    @JvmStatic
    suspend fun exportGraphPdf(context: Context, wavFile: File, outputPdf: File): Boolean =
        withContext(Dispatchers.IO) {
            val decoded = try {
                PcgWavDecoder.decode(wavFile.readBytes())
            } catch (_: Exception) {
                null
            } ?: return@withContext false

            val cleaned = try {
                PcgDisplayFilter.processOffline(decoded.samples, decoded.sampleRate)
            } catch (_: Exception) {
                decoded.samples
            }
            val fullScale = PcgAmplitudeScale.computeFullScaleForFile(cleaned, decoded.sampleRate)
            val title = RecordingDisplayName.displayName(wavFile.nameWithoutExtension)
            val subtitle = "${decoded.durationSecs.toInt()}s · Clean Graph ON"
            try {
                outputPdf.parentFile?.mkdirs()
                outputPdf.outputStream().use { out ->
                    GraphShareExporter.writePdf(
                        context.applicationContext, cleaned, decoded.sampleRate, fullScale,
                        decoded.durationSecs, title, subtitle, out
                    )
                }
                true
            } catch (_: Exception) {
                false
            }
        }
}
