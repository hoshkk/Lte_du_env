package com.lteduenv.rxcheck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.lteduenv.rxcheck.core.analysis.Peak
import com.lteduenv.rxcheck.core.sweep.Trace
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
    val channel = Color(0x2233C47A)
    val marker = Color(0xFFFF5D5D)
}

/**
 * Spectrum display: Y from refLevel down 10 divisions. Levels drawn are
 * dBFS + offset. Tapping places a marker read out through [onTap].
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
    channelHz: Pair<Double, Double>?,
    peaks: List<Peak>,
    markerHz: Double?,
    onTap: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan = live?.plan ?: hold?.plan ?: baseline?.plan
    Canvas(modifier.pointerInput(plan) {
        detectTapGestures { pos ->
            if (plan != null) {
                val left = 52f; val w = size.width - left - 8f
                val frac = ((pos.x - left) / w).coerceIn(0f, 1f)
                onTap(plan.startHz + frac * (plan.stopHz - plan.startHz))
            }
        }
    }) {
        drawRect(ChartColors.background)
        val left = 52f; val top = 8f; val bottom = 30f; val right = 8f
        val w = size.width - left - right
        val h = size.height - top - bottom
        val minDb = refLevelDb - 10 * dbPerDiv
        fun y(db: Double) = (top + (refLevelDb - db) / (10 * dbPerDiv) * h).toFloat()
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(0x9a, 0xa3, 0xb2); textSize = 22f; isAntiAlias = true
        }
        for (i in 0..10) {
            val yy = top + h * i / 10
            drawLine(ChartColors.grid, Offset(left, yy), Offset(left + w, yy))
            val xx = left + w * i / 10
            drawLine(ChartColors.grid, Offset(xx, top), Offset(xx, top + h))
            if (i % 2 == 0) drawContext.canvas.nativeCanvas.drawText(
                "%.0f".format(refLevelDb - i * dbPerDiv), 2f, yy + 8f, textPaint)
        }
        if (plan == null) return@Canvas
        val f0 = plan.startHz; val f1 = plan.stopHz
        fun x(f: Double) = (left + (f - f0) / (f1 - f0) * w).toFloat()
        channelHz?.let { (lo, hi) ->
            val a = x(max(lo, f0)); val b = x(min(hi, f1))
            if (b > a) drawRect(ChartColors.channel, Offset(a, top), androidx.compose.ui.geometry.Size(b - a, h))
        }
        val nc = drawContext.canvas.nativeCanvas
        listOf(0, 5, 10).forEach { i ->
            val f = f0 + (f1 - f0) * i / 10
            val label = "%.3f".format(f / 1e6)
            val tw = textPaint.measureText(label)
            val xx = left + w * i / 10
            nc.drawText(label, (xx - tw / 2).coerceIn(0f, size.width - tw), size.height - 6f, textPaint)
        }
        baseline?.takeIf { it.plan == plan }?.let { drawTrace(it, 0, it.points, offsetDb, ::x, ::y, ChartColors.baseline, minDb) }
        hold?.takeIf { it.plan == plan }?.let { drawTrace(it, 0, it.points, offsetDb, ::x, ::y, ChartColors.hold, minDb) }
        live?.let {
            val upto = livePoints.coerceIn(0, it.points)
            if (upto < it.points) drawTrace(it, upto, it.points, offsetDb, ::x, ::y, ChartColors.stale, minDb)
            drawTrace(it, 0, upto, offsetDb, ::x, ::y, ChartColors.live, minDb)
        }
        for (p in peaks) {
            val px = x(p.freqHz); val py = y(p.levelDb)
            val path = Path().apply {
                moveTo(px, py - 4f); lineTo(px - 8f, py - 18f); lineTo(px + 8f, py - 18f); close()
            }
            drawPath(path, ChartColors.marker)
        }
        markerHz?.let { mf ->
            if (mf in f0..f1) {
                val xx = x(mf)
                drawLine(ChartColors.marker, Offset(xx, top), Offset(xx, top + h), strokeWidth = 1.5f)
            }
        }
    }
}

private fun DrawScope.drawTrace(
    t: Trace, from: Int, to: Int, offsetDb: Double,
    x: (Double) -> Float, y: (Double) -> Float, color: Color, minDb: Double,
) {
    if (to - from < 2) return
    val path = Path()
    var started = false
    // Decimate to about one vertex per pixel column, keeping the max (peak detector).
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
    drawPath(path, color, style = Stroke(width = 1.6f))
}
