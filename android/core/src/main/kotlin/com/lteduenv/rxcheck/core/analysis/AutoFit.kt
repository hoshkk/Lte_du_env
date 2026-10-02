package com.lteduenv.rxcheck.core.analysis

import com.lteduenv.rxcheck.core.sweep.Trace
import kotlin.math.ceil

/**
 * "자동 맞춤": accuracy first. Gain is only ever lowered, and only when the ADC
 * actually clipped; a weak-looking trace never raises it (that would trade
 * linearity for looks). Afterwards Ref/scale are fitted so the floor sits about
 * two divisions above the bottom of a 10-division screen and the peak stays on it.
 */
object AutoFit {
    data class Decision(val gainStep: Int?, val refLevelDb: Double, val dbPerDiv: Double, val lowerGain: Boolean)

    fun decide(traces: List<Trace>, gainStep: Int?, offsetDb: Double): Decision? {
        val values = traces.flatMap { t -> t.levelsDb.filter { it.isFinite() } }.sorted()
        if (values.isEmpty()) return null
        val clipped = traces.any { it.clipped }
        if (clipped && gainStep != null && gainStep > 0)
            return Decision(gainStep - 1, 0.0, 0.0, lowerGain = true)
        val peak = values.last() + offsetDb
        val floor = values[values.size / 10] + offsetDb
        val div = if (peak - floor + 20 > 100) 15.0 else 10.0
        val ref = (ceil(maxOf(floor + 8 * div, peak + 5) / 5) * 5).coerceIn(-150.0, 50.0)
        return Decision(gainStep, ref, div, lowerGain = false)
    }

    const val MAX_GAIN_REDUCTIONS = 3
    const val SWEEPS_PER_PASS = 2
}
