package com.lteduenv.rxcheck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import com.lteduenv.rxcheck.Marker
import com.lteduenv.rxcheck.core.analysis.Analysis
import com.lteduenv.rxcheck.core.analysis.Peak
import com.lteduenv.rxcheck.core.sweep.Trace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ChartColors {
    val background = Color(0xFF0B0D12)
    val grid = Color(0xFF262B36)
    val axisText = Color(0xFF9AA3B2)
    val live = Color(0xFFF5C542)
    val stale = Color(0xFF6B6F7A)
    val hold = Color(0xFF4C8DFF)
    val baseline = Color(0xFF9AA3B2)
    val channel = Color(0xFF39A982)
    val peak = Color(0xFFFF8A3D)
    val marker = Color(0xFFFF5D5D)
    val markerOther = Color(0xFF4C8DFF)
}

private const val LEFT = 56f
private const val TOP = 8f
private const val BOTTOM = 30f
private const val RIGHT = 8f

/**
 * Spectrum display, Y from refLevel down 10 divisions; drawn levels are dBFS + offset.
 * Gestures: tap places the selected marker, one-finger vertical drag moves Ref,
 * two-finger pinch changes Span (applied on release, centre fixed).
 */
@Composable
fun SpectrumChart(
    live: Trace?,
    livePoints: Int,
    hold: Trace?,
    baseline: Trace?,
    offsetDb: Double,
    refLevelDb: Double,
    dbPerDiv: Double,
    startHz: Double,
    stopHz: Double,
    channelHz: Pair<Double, Double>?,
    peaks: List<Peak>,
    markers: List<Marker>,
    selectedMarker: Int,
    onTap: (Double) -> Unit,
    onRefDrag: (Double) -> Unit,
    onRefDragEnd: () -> Unit,
    onPinch: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan = live?.plan ?: hold?.plan
    val f0 = plan?.startHz ?: startHz
    val f1 = plan?.stopHz ?: stopHz
    val tap by rememberUpdatedState(onTap)
    val drag by rememberUpdatedState(onRefDrag)
    val dragEnd by rememberUpdatedState(onRefDragEnd)
    val pinch by rememberUpdatedState(onPinch)
    val range by rememberUpdatedState(f0 to f1)
    val divDb by rememberUpdatedState(dbPerDiv)

    Canvas(modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragged = false; var pinched = false
            var zoom = 1f; var dy = 0f
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2) {
                    pinched = true
                    val z = event.calculateZoom()
                    if (z.isFinite() && z > 0f) zoom = (zoom * z).coerceIn(0.01f, 100f)
                    event.changes.forEach { it.consume() }
                } else if (!pinched && fingers == 1) {
                    val d = event.changes.first { it.pressed }.positionChange().y
                    dy += d
                    val h = (size.height - TOP - BOTTOM).coerceAtLeast(1f)
                    if (!dragged && abs(dy) > viewConfiguration.touchSlop) {
                        dragged = true
                        drag(dy / h * 10 * divDb)
                    } else if (dragged) drag(d / h * 10 * divDb)
                    if (dragged) event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            when {
                pinched -> if (abs(zoom - 1f) > 0.05f) pinch(zoom)
                dragged -> dragEnd()
                else -> {
                    val w = (size.width - LEFT - RIGHT).coerceAtLeast(1f)
                    val frac = ((down.position.x - LEFT) / w).coerceIn(0f, 1f)
                    val (a, b) = range
                    if (b > a) tap(a + frac * (b - a))
                }
            }
        }
    }) {
        drawRect(ChartColors.background)
        val w = size.width - LEFT - RIGHT
        val h = size.height - TOP - BOTTOM
        val minDb = refLevelDb - 10 * dbPerDiv
        fun y(db: Double) = (TOP + ((refLevelDb - db) / (10 * dbPerDiv)).coerceIn(0.0, 1.0) * h).toFloat()
        fun x(f: Double) = (LEFT + (f - f0) / (f1 - f0) * w).toFloat()
        val text = android.graphics.Paint().apply {
            color = ChartColors.axisText.toArgb(); textSize = 22f; isAntiAlias = true
        }
        val nc = drawContext.canvas.nativeCanvas
        if (f1 > f0) channelHz?.let { (lo, hi) ->
            val a = max(lo, f0); val b = min(hi, f1)
            if (b > a) drawRect(ChartColors.channel.copy(alpha = 0.13f), Offset(x(a), TOP), Size(x(b) - x(a), h))
            for (edge in listOf(lo, hi)) if (edge in f0..f1)
                drawLine(ChartColors.channel.copy(alpha = 0.7f), Offset(x(edge), TOP), Offset(x(edge), TOP + h), 1.5f)
        }
        for (i in 0..10) {
            val yy = TOP + h * i / 10
            drawLine(ChartColors.grid, Offset(LEFT, yy), Offset(LEFT + w, yy))
            val xx = LEFT + w * i / 10
            drawLine(ChartColors.grid, Offset(xx, TOP), Offset(xx, TOP + h))
            if (i % 2 == 0) nc.drawText("%.0f".format(refLevelDb - i * dbPerDiv), 2f, yy + 8f, text)
        }
        if (f1 <= f0) return@Canvas
        for (i in listOf(0, 5, 10)) {
            val label = "%.3f".format((f0 + (f1 - f0) * i / 10) / 1e6)
            val tw = text.measureText(label)
            nc.drawText(label, (LEFT + w * i / 10 - tw / 2).coerceIn(0f, size.width - tw), size.height - 6f, text)
        }
        baseline?.takeIf { it.plan == plan }?.let { drawTrace(it, 0, it.points, offsetDb, ::x, ::y, ChartColors.baseline, minDb, 1.2f) }
        hold?.takeIf { it.plan == plan }?.let { drawTrace(it, 0, it.points, offsetDb, ::x, ::y, ChartColors.hold.copy(alpha = 0.8f), minDb, 1.5f) }
        live?.let {
            val upto = livePoints.coerceIn(0, it.points)
            if (upto < it.points) drawTrace(it, upto, it.points, offsetDb, ::x, ::y, ChartColors.stale, minDb, 1.6f)
            drawTrace(it, 0, upto, offsetDb, ::x, ::y, ChartColors.live, minDb, 1.8f)
        }
        for (p in peaks) {
            val px = x(p.freqHz); val py = y(p.levelDb)
            drawPath(Path().apply { moveTo(px, py - 4f); lineTo(px - 7f, py - 16f); lineTo(px + 7f, py - 16f); close() }, ChartColors.peak)
        }
        val shown = hold?.takeIf { it.plan == plan } ?: live
        for (m in markers) {
            val f = m.freqHz ?: continue
            if (f !in f0..f1) continue
            val color = if (m.index == selectedMarker) ChartColors.marker else ChartColors.markerOther
            val xx = x(f)
            val level = shown?.let { Analysis.levelAt(it, f, offsetDb) }
            drawLine(color, Offset(xx, TOP), Offset(xx, TOP + h), 1f)
            if (level != null) drawCircle(color, 5f, Offset(xx, y(level)))
            text.color = color.toArgb()
            nc.drawText("M${m.index}", xx + 6f, (if (level != null) y(level) else TOP + 20f) - 8f, text)
            text.color = ChartColors.axisText.toArgb()
        }
    }
}

private fun DrawScope.drawTrace(
    t: Trace, from: Int, to: Int, offsetDb: Double,
    x: (Double) -> Float, y: (Double) -> Float, color: Color, minDb: Double, width: Float,
) {
    if (to - from < 2) return
    val path = Path()
    var started = false
    // About one vertex per pixel column, keeping each column's maximum (peak detector).
    val cols = max(1, size.width.toInt())
    val step = max(1, (to - from) / cols)
    var i = from
    while (i < to) {
        var m = Float.NEGATIVE_INFINITY
        val end = min(to, i + step)
        for (k in i until end) if (t.levelsDb[k].isFinite() && t.levelsDb[k] > m) m = t.levelsDb[k]
        if (m.isFinite()) {
            val px = x(t.freqAt(i))
            val py = y(max(minDb, m + offsetDb))
            if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
        } else started = false
        i = end
    }
    drawPath(path, color, style = Stroke(width = width))
}
