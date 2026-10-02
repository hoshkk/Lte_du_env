package com.lteduenv.rxcheck.core.view

import com.lteduenv.rxcheck.core.sweep.Trace
import kotlin.math.max
import kotlin.math.min

/**
 * Time/frequency history of completed sweeps for a waterfall display. Each row
 * keeps the highest level per column (peak detector), so a narrow burst never
 * disappears when the trace has more points than the display has columns.
 * Unmeasured points stay NaN and are drawn in their own colour.
 *
 * Written by the measurement thread, read by the UI thread: all access is synchronized.
 */
class Waterfall(val columns: Int = 480, val rows: Int = 200) {
    private val data = Array(rows) { FloatArray(columns) { Float.NaN } }
    private var head = 0
    private var count = 0
    private var gridKey: Triple<Double, Double, Int>? = null
    private val times = LongArray(rows)

    /** Rows currently held. */
    @get:Synchronized val size get() = count

    /** Seconds covered by the held rows (oldest to newest), 0 if fewer than 2. */
    @get:Synchronized val spanSeconds: Double get() =
        if (count < 2) 0.0 else (times[(head - 1 + rows) % rows] - times[(head - count + rows) % rows]) / 1000.0

    @Synchronized fun clear() { count = 0; head = 0; gridKey = null }

    /** Adds a completed sweep (levels dBFS, no offset). A new frequency grid starts over. */
    @Synchronized fun add(t: Trace) {
        val key = Triple(t.plan.startHz, t.plan.binHz, t.points)
        if (key != gridKey) { count = 0; head = 0; gridKey = key }
        val row = data[head]
        val p = t.points
        for (c in 0 until columns) {
            val a = (c.toLong() * p / columns).toInt()
            val b = max(a + 1, ((c + 1).toLong() * p / columns).toInt())
            var m = Float.NaN
            for (i in a until min(b, p)) {
                val v = t.levelsDb[i]
                if (v.isFinite() && (m.isNaN() || v > m)) m = v
            }
            row[c] = m
        }
        times[head] = t.timestampMs
        head = (head + 1) % rows
        if (count < rows) count++
    }

    /** Row [age] (0 = newest) as a copy, or null. */
    @Synchronized fun row(age: Int): FloatArray? =
        if (age !in 0 until count) null else data[(head - 1 - age + 2 * rows) % rows].copyOf()

    /**
     * Fills [pixels] (columns x rows, newest row on top) with ARGB colours.
     * Levels map linearly from [minDb]..[maxDb] onto [palette]; empty rows are
     * [empty], unmeasured points [unmeasured].
     */
    @Synchronized fun render(pixels: IntArray, minDb: Double, maxDb: Double, palette: IntArray, empty: Int, unmeasured: Int) {
        require(pixels.size >= columns * rows)
        val scale = (palette.size - 1) / max(1e-6, maxDb - minDb)
        for (r in 0 until rows) {
            val base = r * columns
            if (r >= count) { java.util.Arrays.fill(pixels, base, base + columns, empty); continue }
            val row = data[(head - 1 - r + 2 * rows) % rows]
            for (c in 0 until columns) {
                val v = row[c]
                pixels[base + c] = if (v.isNaN()) unmeasured
                    else palette[((v - minDb) * scale).toInt().coerceIn(0, palette.size - 1)]
            }
        }
    }

    companion object {
        /** Dark blue -> cyan -> yellow -> red -> white, 256 entries. */
        fun palette(): IntArray {
            val stops = intArrayOf(0x000814, 0x0b2a6f, 0x0a76c4, 0x22c1c3, 0x9ee34a, 0xf5c542, 0xf26b1d, 0xd61f1f, 0xffffff)
            return IntArray(256) { i ->
                val x = i / 255.0 * (stops.size - 1)
                val k = min(stops.size - 2, x.toInt()); val f = x - k
                fun ch(c: Int, sh: Int) = (c shr sh) and 0xff
                fun mix(sh: Int) = (ch(stops[k], sh) + (ch(stops[k + 1], sh) - ch(stops[k], sh)) * f).toInt()
                (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
        }
    }
}
