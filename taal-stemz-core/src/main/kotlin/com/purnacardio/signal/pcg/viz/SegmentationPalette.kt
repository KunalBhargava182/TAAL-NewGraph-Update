package com.purnacardio.signal.pcg.viz

/**
 * Colours for the four cardiac states, as plain ARGB ints.
 *
 * Kept in `core` rather than in Android resources so that every renderer — the Android View, a
 * Compose Canvas, an exported SVG, a PDF report — draws the identical chart. A clinician comparing
 * a screen against a printout should not have to wonder whether the colours mean the same thing.
 *
 * ## Why these colours
 *
 * **Desaturated, not primary.** Saturated red/green/blue reads as a toy and, worse, red carries an
 * alarm meaning in a clinical interface that is wrong here — S1 is not an error. These sit around
 * 35–45% saturation, in the register of printed medical illustration.
 *
 * **The sounds are stronger than the intervals.** S1 and S2 are events and carry the darker, more
 * present fills. Systole and diastole are the gaps between them and are washes — present enough to
 * locate, quiet enough that the waveform stays the thing you read. Giving all four equal weight
 * produces a striped background that fights the trace.
 *
 * **Blue for S1, teal-green for S2** — separated in lightness as well as hue, so the pair survives
 * deuteranopia and greyscale printing. Colour is never the only cue: the cycle order S1 → systole →
 * S2 → diastole is fixed, the legend is mandatory, and [labels] is available for direct annotation.
 *
 * **Systole ochre, diastole slate.** Systole is where a murmur lives and is the interval a reader
 * is usually looking for, so it gets the warmer, more visible wash; diastole recedes to near
 * neutral. This is what makes systolic filling legible at a glance.
 *
 * Both variants keep the waveform ink at roughly 11:1 contrast against their own background, and
 * every band fill is light enough to keep it there.
 */
object SegmentationPalette {

    /** Display names, indexed by state — 0=S1, 1=systole, 2=S2, 3=diastole. */
    val labels = arrayOf("S1", "Systole", "S2", "Diastole")

    /**
     * Which states are heart *sounds* rather than intervals.
     *
     * Renderers draw a short accent rule along the top of these two bands. That is a deliberate
     * redundancy: it separates S1 and S2 from the intervals by shape as well as hue, so the chart
     * still parses in greyscale, on a projector, or for a reader with a colour vision deficiency —
     * cases where the blue and the teal-green can otherwise converge.
     */
    val isSound = booleanArrayOf(true, false, true, false)

    /** Longer names for a legend with room, or for accessibility descriptions. */
    val longLabels = arrayOf(
        "S1 — first heart sound",
        "Systole",
        "S2 — second heart sound",
        "Diastole"
    )

    /**
     * One palette variant. All values are ARGB, alpha included.
     *
     * @property bandFill Per-state background wash, indexed by state.
     * @property accent Per-state solid colour: legend swatches, markers, labels.
     * @property waveform The PCG trace.
     * @property baseline The zero line.
     * @property grid Second gridlines and axis rules.
     * @property surface Chart background.
     * @property onSurface Body text on [surface].
     * @property onSurfaceMuted Secondary text — axis numbers, captions.
     */
    class Variant(
        val bandFill: IntArray,
        val accent: IntArray,
        val waveform: Int,
        val baseline: Int,
        val grid: Int,
        val surface: Int,
        val onSurface: Int,
        val onSurfaceMuted: Int
    ) {
        /** Band wash for a state label, tolerant of an out-of-range value. */
        fun fillFor(state: Int): Int = bandFill.getOrElse(state) { bandFill[3] }

        /** Solid accent for a state label, tolerant of an out-of-range value. */
        fun accentFor(state: Int): Int = accent.getOrElse(state) { accent[3] }
    }

    /**
     * For light backgrounds — also the variant to print.
     *
     * S1/S2 wash alpha was raised from the original 24% to 35% (2026-08-24, readability pass) —
     * at real cycle density (~20px/cycle on a phone) the original wash was too faint to read
     * through the waveform fill. Systole/diastole were left alone, which also widens the
     * events-vs-intervals contrast the design already called for. `waveform` alpha was lowered
     * from 82% to 76% for the same pass, so band colour reads through the trace instead of being
     * masked by it.
     */
    val light = Variant(
        bandFill = intArrayOf(
            0x592F5C86, // S1       deep clinical blue, 35%
            0x2E9A7B3C, // systole  ochre, 18% — the interval a reader looks into
            0x591F6F66, // S2       teal-green, 35%
            0x1A707B8C  // diastole near-neutral slate, 10%
        ),
        accent = intArrayOf(
            0xFF2F5C86.toInt(),
            0xFF8A6C2E.toInt(),
            0xFF1F6F66.toInt(),
            0xFF6B7688.toInt()
        ),
        waveform = 0xC21B2430.toInt(),
        baseline = 0x40707B8C,
        grid = 0xFFE3E9F0.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        onSurface = 0xFF16202B.toInt(),
        onSurfaceMuted = 0xFF5E6B7D.toInt()
    )

    /**
     * For dark backgrounds. Hues are held; lightness is lifted to keep contrast on a dark ground.
     * Same 2026-08-24 readability pass as [light]: S1/S2 wash 28%→40%, waveform alpha 82%→76%.
     */
    val dark = Variant(
        bandFill = intArrayOf(
            0x666D9BD1.toInt(), // S1, 40%
            0x38C79A44, // ochre, 22%
            0x663FA595.toInt(), // S2, 40%
            0x1E939EAF
        ),
        accent = intArrayOf(
            0xFF7FA9DA.toInt(),
            0xFFD3A550.toInt(),
            0xFF52B8A8.toInt(),
            0xFF98A3B4.toInt()
        ),
        waveform = 0xC2D8E0EA.toInt(),
        baseline = 0x40939EAF,
        grid = 0xFF27313E.toInt(),
        surface = 0xFF101720.toInt(),
        onSurface = 0xFFE4EAF2.toInt(),
        onSurfaceMuted = 0xFF8D9AAD.toInt()
    )

    /** One legend entry, in cycle order. */
    data class LegendEntry(val state: Int, val label: String, val swatch: Int, val accent: Int)

    /**
     * Legend entries in cardiac cycle order, which is also the order they appear on screen.
     *
     * A legend is not optional on this chart: four washes with no key is a decorative background,
     * not a reading of the recording.
     */
    fun legend(variant: Variant, long: Boolean = false): List<LegendEntry> =
        (0..3).map { s ->
            LegendEntry(
                state = s,
                label = if (long) longLabels[s] else labels[s],
                swatch = variant.fillFor(s),
                accent = variant.accentFor(s)
            )
        }
}
