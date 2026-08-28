package com.example.heartsounds

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import com.purnacardio.signal.pcg.SegmentationResult
import com.purnacardio.signal.pcg.android.PcgSegmentationView
import com.purnacardio.signal.pcg.viz.PcgDisplay
import com.purnacardio.signal.pcg.viz.PcgDisplayModel
import com.purnacardio.signal.pcg.viz.SegmentationPalette

/**
 * Two ways to put the chart in a Compose screen. Pick one.
 *
 * The SDK ships a plain Android `View` rather than a composable so it adds no Compose dependency to
 * apps that do not use Compose. Both options below render identically, because both take their
 * geometry and colours from the same `PcgDisplay` / `SegmentationPalette` in `core`.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Option A — wrap the shipped view. Three lines, and you inherit every fix we
// make to the view. This is the one to use unless you have a reason not to.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PcgChart(
    audio: FloatArray,
    sampleRate: Int,
    result: SegmentationResult,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxWidth().height(200.dp),
        factory = { ctx -> PcgSegmentationView(ctx) },
        // update runs on recomposition, so a new recording swaps in without rebuilding the view
        update = { it.setRecording(audio, sampleRate, result) }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Option B — draw it natively in Compose. Use this if you want the chart to take
// part in Compose animation or gesture handling. The display model does the work;
// this is only fills.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PcgChartNative(
    audio: FloatArray,
    sampleRate: Int,
    result: SegmentationResult,
    modifier: Modifier = Modifier,
    windowStartSec: Double = 0.0,
    windowEndSec: Double = Double.MAX_VALUE,
    dark: Boolean = false
) {
    val palette = if (dark) SegmentationPalette.dark else SegmentationPalette.light
    val density = LocalDensity.current
    // Columns are pixels, so the model is rebuilt when the width changes — not on every frame.
    val widthPx = with(density) { 1000.dp.toPx().toInt() }

    val model: PcgDisplayModel = remember(audio, sampleRate, result, windowStartSec, windowEndSec) {
        PcgDisplay.build(audio, sampleRate, result, widthPx, windowStartSec, windowEndSec)
    }

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val w = size.width
            val lane = size.height - with(density) { 10.dp.toPx() }

            drawRect(Color(palette.surface), size = Size(w, size.height))

            model.bands.forEach { b ->
                drawRect(
                    color = Color(palette.fillFor(b.state)),
                    topLeft = Offset(b.xStart * w, 0f),
                    size = Size((b.xEnd - b.xStart) * w, lane)
                )
            }
            // the two heart sounds also get a rule along the top — shape as well as hue
            model.bands.filter { SegmentationPalette.isSound.getOrElse(it.state) { false } }
                .forEach { b ->
                    drawRect(
                        color = Color(palette.accentFor(b.state)),
                        topLeft = Offset(b.xStart * w, 0f),
                        size = Size((b.xEnd - b.xStart) * w, with(density) { 2.dp.toPx() })
                    )
                }

            drawLine(
                Color(palette.baseline),
                Offset(0f, lane / 2f), Offset(w, lane / 2f),
                strokeWidth = 1f
            )

            if (model.columns > 0) {
                val path = Path().apply {
                    val sx = w / model.columns
                    moveTo(0f, model.top[0] * lane)
                    for (c in 1 until model.columns) lineTo(c * sx, model.top[c] * lane)
                    for (c in model.columns - 1 downTo 0) lineTo(c * sx, model.bottom[c] * lane)
                    close()
                }
                drawPath(path, Color(palette.waveform))
            }

            listOf(model.s1MarkersX to 0, model.s2MarkersX to 2).forEach { (xs, state) ->
                xs.forEach { x0 ->
                    val x = x0 * w
                    val h = with(density) { 7.dp.toPx() }
                    drawPath(
                        Path().apply {
                            moveTo(x, lane); lineTo(x - h * 0.45f, lane + h)
                            lineTo(x + h * 0.45f, lane + h); close()
                        },
                        Color(palette.accentFor(state))
                    )
                }
            }
        }
        PcgLegend(dark = dark)
    }
}

/**
 * The key. Not optional — four washes with no legend is a decorative background, not a reading.
 * Entries come back in cardiac cycle order, which is the order they appear on the chart.
 */
@Composable
fun PcgLegend(dark: Boolean = false, modifier: Modifier = Modifier) {
    val palette = if (dark) SegmentationPalette.dark else SegmentationPalette.light
    Row(modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SegmentationPalette.legend(palette).forEach { entry ->
            Canvas(Modifier.size(12.dp)) {
                drawRect(Color(palette.surface))
                drawRect(Color(entry.swatch))
                if (SegmentationPalette.isSound[entry.state]) {
                    drawRect(Color(entry.accent), size = Size(size.width, size.height * 0.18f))
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                entry.label,
                style = TextStyle(fontSize = 12.sp, color = Color(palette.onSurfaceMuted))
            )
            Spacer(Modifier.width(14.dp))
        }
    }
}
