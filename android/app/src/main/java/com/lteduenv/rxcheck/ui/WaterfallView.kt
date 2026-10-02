package com.lteduenv.rxcheck.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lteduenv.rxcheck.core.view.Waterfall
import java.util.Locale

/**
 * Waterfall under the spectrum: newest sweep on top, colour = level on the same
 * Ref / dB-per-div scale as the graph. Columns line up with the graph's
 * frequency axis. Unmeasured points are brown, never a low-level colour.
 */
@Composable
fun WaterfallView(wf: Waterfall, version: Int, minDb: Double, maxDb: Double, modifier: Modifier = Modifier) {
    val palette = remember { Waterfall.palette() }
    val bmp = remember(wf) { Bitmap.createBitmap(wf.columns, wf.rows, Bitmap.Config.ARGB_8888) }
    val px = remember(wf) { IntArray(wf.columns * wf.rows) }
    val image = remember(wf) { bmp.asImageBitmap() }
    val bg = ChartColors.background.toArgb()
    Canvas(modifier) {
        // Re-render on every new row (version) or scale change.
        if (version >= 0) {
            wf.render(px, minDb, maxDb, palette, bg, UNMEASURED)
            bmp.setPixels(px, 0, wf.columns, 0, 0, wf.columns, wf.rows)
        }
        drawRect(ChartColors.background)
        val w = (size.width - LEFT - RIGHT).toInt().coerceAtLeast(1)
        drawImage(image, IntOffset.Zero, IntSize(wf.columns, wf.rows), IntOffset(LEFT.toInt(), 0),
            IntSize(w, size.height.toInt().coerceAtLeast(1)), filterQuality = FilterQuality.None)
        val text = android.graphics.Paint().apply { color = ChartColors.axisText.toArgb(); textSize = 20f; isAntiAlias = true }
        val nc = drawContext.canvas.nativeCanvas
        nc.drawText("시간", 4f, 20f, text)
        nc.drawText("↓", 14f, 42f, text)
        val secs = wf.spanSeconds
        nc.drawText(if (wf.size == 0) "워터폴 대기" else String.format(Locale.US, "최근 %d회 · %.0f초", wf.size, secs),
            LEFT + 6f, size.height - 6f, text)
    }
}

private const val UNMEASURED = 0xFF4A3410.toInt()
