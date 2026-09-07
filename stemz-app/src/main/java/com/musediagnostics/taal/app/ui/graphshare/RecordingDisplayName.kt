package com.musediagnostics.taal.app.ui.graphshare

/**
 * Pure Kotlin re-implementation of the filter-prefix / "_filtered"-suffix stripping that turns
 * "{FILTER}_{userInput}_filtered" into the name the user actually typed when saving. The SAME
 * algorithm already exists, independently duplicated, in
 * SavedRecordingsFragment.extractFilterName (ui/library/SavedRecordingsFragment.kt:140-143) and
 * SavedRecordingAdapter.extractFilter (ui/library/SavedRecordingAdapter.kt:69-74) — both private
 * methods on Android classes, so neither is plain-JVM callable from a test or from
 * GraphShareBundler without instantiating a Fragment/Adapter.
 *
 * This is a THIRD implementation, not a refactor of the existing two (see the plan's safety
 * section: the working share path is never edited) — it exists so the new graph-share code path
 * has one pure, unit-tested (ShareNamingTest) source of this logic instead of adding a fourth ad
 * hoc inline copy. De-duplicating all three onto this one is an explicit follow-up, out of scope
 * here specifically because it would touch the two working files.
 */
object RecordingDisplayName {
    // HEART_HARD must be checked before HEART — otherwise "HEART_HARD_..." would match the
    // "HEART_" prefix first and leave "HARD_" stuck in the display name. Same ordering bug note
    // as both existing copies.
    private val KNOWN_FILTERS = listOf("FULL_BODY", "PREGNANCY", "CUSTOM", "LUNGS", "BOWEL", "HEART_HARD", "HEART")

    fun extractFilterName(fileNameWithoutExtension: String): String =
        KNOWN_FILTERS.firstOrNull { fileNameWithoutExtension.startsWith("${it}_") } ?: "HEART"

    /** "{FILTER}_{userInput}_filtered" -> "{userInput}" — the name the user actually typed. */
    fun displayName(fileNameWithoutExtension: String): String {
        val filterName = extractFilterName(fileNameWithoutExtension)
        return fileNameWithoutExtension
            .removePrefix("${filterName}_")
            .removeSuffix("_filtered")
    }
}
