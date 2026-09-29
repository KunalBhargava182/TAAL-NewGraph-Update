package com.musediagnostics.taal.stemz.uikit.share

/**
 * Saved recordings are named "{FILTER}_{userInput}_filtered.wav" / "_raw.wav", where FILTER is
 * LITE, HARD or CUSTOM. This turns a file name back into the name the user typed when saving,
 * and tells which filter the recording used. Single source of this logic for every UI-kit
 * screen (saved list, review title, share file names).
 */
internal object RecordingDisplayName {
    // Checked in order; none of these is a prefix of another, so order doesn't matter today —
    // keep longer names first if a new filter is ever added that could be (e.g. "HARD_X" vs "HARD").
    private val KNOWN_FILTERS = listOf("CUSTOM", "LITE", "HARD")
    private const val DEFAULT_FILTER = "LITE"

    fun extractFilterName(fileNameWithoutExtension: String): String =
        KNOWN_FILTERS.firstOrNull { fileNameWithoutExtension.startsWith("${it}_") } ?: DEFAULT_FILTER

    /** "{FILTER}_{userInput}_filtered" -> "{userInput}" — the name the user actually typed. */
    fun displayName(fileNameWithoutExtension: String): String {
        val filterName = extractFilterName(fileNameWithoutExtension)
        return fileNameWithoutExtension
            .removePrefix("${filterName}_")
            .removeSuffix("_filtered")
    }
}
