package com.lteduenv.spectrum.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lteduenv.spectrum.data.Marker
import com.lteduenv.spectrum.data.SpectrumFrame

private const val GRID_ROWS = 8
private const val GRID_COLS = 10
private val AXIS_LABEL_WIDTH = 56.dp

@Composable
private fun GraphFrame(
    yLabels: List<String>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    graph: @Composable (Modifier) -> Unit,
) {
    Column(modifier) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.width(AXIS_LABEL_WIDTH).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                yLabels.forEach { label ->
                    androidx.compose.material3.Text(
                        label,
                        color = AnalyzerColors.TextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                }
            }
            graph(Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().padding(start = AXIS_LABEL_WIDTH, top = 2.dp)) {
            xLabels.forEachIndexed { i, label ->
                androidx.compose.material3.Text(
                    label,
                    color = AnalyzerColors.TextSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        xLabels.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                )
            }
        }
    }
}

private fun gridPath(sizeWidth: Float, sizeHeight: Float): Pair<List<Float>, List<Float>> {
    val xs = (0..GRID_COLS).map { it / GRID_COLS.toFloat() * sizeWidth }
    val ys = (0..GRID_ROWS).map { it / GRID_ROWS.toFloat() * sizeHeight }
    return xs to ys
}

@Composable
fun SpectrumTraceCanvas(
    frame: SpectrumFrame?,
    refLevelDbm: Double,
    markers: List<Marker>,
    selectedMarker: Int,
    modifier: Modifier = Modifier,
    dbSpan: Double = 100.0,
    onTapFrequency: (Double) -> Unit = {},
) {
    val yLabels = remember(refLevelDbm, dbSpan) {
        (0..GRID_ROWS).map { row -> "%.1f".format(refLevelDbm - row * (dbSpan / GRID_ROWS)) }
    }
    val xLabels = remember(frame?.startMhz, frame?.stopMhz) {
        val start = frame?.startMhz ?: 0.0
        val stop = frame?.stopMhz ?: 0.0
        (0..4).map { i -> "%.1f".format(start + i * (stop - start) / 4) }
    }

    GraphFrame(yLabels, xLabels, modifier) { graphModifier ->
        Canvas(
            graphModifier.pointerInput(frame?.startMhz, frame?.stopMhz) {
                detectTapGestures { offset ->
                    val f = frame ?: return@detectTapGestures
                    val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                    onTapFrequency(f.startMhz + ratio * (f.stopMhz - f.startMhz))
                }
            },
        ) {
            val (xs, ys) = gridPath(size.width, size.height)
            xs.forEach { x -> drawLine(AnalyzerColors.GridLine, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1f) }
            ys.forEach { y -> drawLine(AnalyzerColors.GridLine, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f) }

            val f = frame
            if (f != null && f.pointCount > 1) {
                val path = Path()
                val n = f.pointCount
                for (i in 0 until n) {
                    val x = i / (n - 1).toFloat() * size.width
                    val level = f.levelsDbm[i].toDouble()
                    val y = ((refLevelDbm - level) / dbSpan).toFloat().coerceIn(0f, 1f) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, AnalyzerColors.Trace, style = Stroke(width = 2.5f))
            }

            if (f != null) {
                val paint = Paint().apply {
                    textSize = 26f
                    isAntiAlias = true
                }
                markers.filter { it.enabled }.forEach { m ->
                    val ratio = (((m.freqMhz - f.startMhz) / (f.stopMhz - f.startMhz)).coerceIn(0.0, 1.0)).toFloat()
                    val x = ratio * size.width
                    val y = (((refLevelDbm - m.levelDbm) / dbSpan).coerceIn(0.0, 1.0)).toFloat() * size.height
                    val markerColor = if (m.index == selectedMarker) AnalyzerColors.Bad else AnalyzerColors.AccentBlue
                    drawCircle(markerColor, radius = 5f, center = androidx.compose.ui.geometry.Offset(x, y))
                    drawLine(markerColor, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                    paint.color = markerColor.toArgb()
                    drawContext.canvas.nativeCanvas.drawText("M${m.index}", x + 8f, (y - 8f).coerceAtLeast(16f), paint)
                }
            }
        }
    }
}
