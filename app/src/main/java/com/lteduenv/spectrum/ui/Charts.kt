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
    refLevelDb: Double,
    markers: List<Marker>,
    selectedMarker: Int,
    modifier: Modifier = Modifier,
    dbSpan: Double = 100.0,
    staleBeforeMs: Long = 0,
    heldFrame: SpectrumFrame? = null,
    onTapFrequency: (Double) -> Unit = {},
    displayStartMhz: Double = 0.0,
    displayStopMhz: Double = 0.0,
    channelCenterMhz: Double = 0.0,
    channelBwMhz: Double = 0.0,
) {
    val yLabels = remember(refLevelDb, dbSpan) {
        (0..GRID_ROWS).map { row -> "%.1f".format(refLevelDb - row * (dbSpan / GRID_ROWS)) }
    }
    val xLabels = remember(frame?.startMhz, frame?.stopMhz, displayStartMhz, displayStopMhz) {
        val start = frame?.startMhz ?: displayStartMhz
        val stop = frame?.stopMhz ?: displayStopMhz
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

            val start = frame?.startMhz ?: displayStartMhz
            val stop = frame?.stopMhz ?: displayStopMhz
            if(stop > start && channelBwMhz > 0.0) {
                val lo = channelCenterMhz-channelBwMhz/2
                val hi = channelCenterMhz+channelBwMhz/2
                val a = maxOf(start,lo)
                val b = minOf(stop,hi)
                if(b > a) {
                    val x1 = ((a-start)/(stop-start)*size.width).toFloat()
                    val x2 = ((b-start)/(stop-start)*size.width).toFloat()
                    val shade = androidx.compose.ui.graphics.Color(0xFF39A982)
                    drawRect(shade.copy(alpha=0.13f),androidx.compose.ui.geometry.Offset(x1,0f),androidx.compose.ui.geometry.Size(x2-x1,size.height))
                    listOf(lo,hi).filter { it in start..stop }.forEach { edge ->
                        val x = ((edge-start)/(stop-start)*size.width).toFloat()
                        drawLine(shade.copy(alpha=0.6f),androidx.compose.ui.geometry.Offset(x,0f),androidx.compose.ui.geometry.Offset(x,size.height),1.5f)
                    }
                }
            }

            val f = frame
            val held=heldFrame
            if(held!=null && f!=null && held.pointCount>1 && held.startMhz==f.startMhz && held.stopMhz==f.stopMhz) {
                val hp=Path();var connected=false
                val columns=minOf(held.pointCount,size.width.toInt().coerceAtLeast(1))
                for(column in 0 until columns){
                    val first=column*held.pointCount/columns
                    val end=((column+1)*held.pointCount/columns).coerceAtLeast(first+1)
                    var peak=Float.NEGATIVE_INFINITY
                    for(i in first until end)if(held.levelsDb[i].isFinite())peak=maxOf(peak,held.levelsDb[i])
                    if(!peak.isFinite()){connected=false;continue}
                    val x=column/(columns-1).coerceAtLeast(1).toFloat()*size.width
                    val y=((refLevelDb-peak)/dbSpan).toFloat().coerceIn(0f,1f)*size.height
                    if(connected)hp.lineTo(x,y)else hp.moveTo(x,y)
                    connected=true
                }
                drawPath(hp,AnalyzerColors.AccentBlue.copy(alpha=0.7f),style=Stroke(width=1.5f))
            }
            if (f != null && f.pointCount > 1) {
                val path = Path()
                val previousPath=Path()
                val n = f.pointCount
                // Preserve narrow peaks when many FFT bins map to the same screen pixel.
                val columns=kotlin.math.min(n,size.width.toInt().coerceAtLeast(1))
                var connected=false
                var previousConnected=false
                for(column in 0 until columns) {
                    val first=column*n/columns
                    val end=((column+1)*n/columns).coerceAtLeast(first+1)
                    var peak=Float.NEGATIVE_INFINITY
                    for(i in first until end)if(f.levelsDb[i].isFinite())peak=kotlin.math.max(peak,f.levelsDb[i])
                    if(!peak.isFinite()){connected=false;previousConnected=false;continue}
                    val x=column/(columns-1).coerceAtLeast(1).toFloat()*size.width
                    val y=((refLevelDb-peak)/dbSpan).toFloat().coerceIn(0f,1f)*size.height
                    val stale=(first until end).any{f.observedAtMs[it]<staleBeforeMs}
                    if(stale){
                        if(!previousConnected)previousPath.moveTo(x,y)else previousPath.lineTo(x,y)
                        previousConnected=true;connected=false
                    }else{
                        if(!connected)path.moveTo(x,y)else path.lineTo(x,y)
                        connected=true;previousConnected=false
                    }
                }
                drawPath(previousPath, AnalyzerColors.TextSecondary.copy(alpha=0.5f), style = Stroke(width = 2f))
                drawPath(path, AnalyzerColors.Trace, style = Stroke(width = 2.5f))
            }

            if (f != null) {
                val paint = Paint().apply {
                    textSize = 26f
                    isAntiAlias = true
                }
                markers.filter { it.enabled && it.levelDb.isFinite() && it.freqMhz in f.startMhz..f.stopMhz }.forEach { m ->
                    val ratio = (((m.freqMhz - f.startMhz) / (f.stopMhz - f.startMhz)).coerceIn(0.0, 1.0)).toFloat()
                    val x = ratio * size.width
                    val y = (((refLevelDb - m.levelDb) / dbSpan).coerceIn(0.0, 1.0)).toFloat() * size.height
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
