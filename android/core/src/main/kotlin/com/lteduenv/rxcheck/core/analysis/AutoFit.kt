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

/**
 * "자동 맞춤" gain search: every gain step is measured on the real input, then
 *  - only steps with no ADC clipping and at least [MIN_HEADROOM_DB] of headroom
 *    below full scale are allowed (linearity first);
 *  - among those, the lowest step whose noise floor is [FLOOR_RISE_DB] above the
 *    floor at the lowest gain is taken: from there the front end's noise, not the
 *    ADC's, sets the floor, so more gain adds little sensitivity and only costs
 *    headroom. If no step gets there, the highest allowed step is taken.
 */
object GainLadder {
    const val MIN_HEADROOM_DB = 6.0
    const val FLOOR_RISE_DB = 10.0
    const val MAX_CLIP = 1e-5

    data class Step(val gain: Int, val clippedFraction: Double, val peakAdc: Double, val floorDb: Double) {
        val headroomDb get() = -20 * kotlin.math.log10(peakAdc.coerceIn(1e-6, 1.0))
        val allowed get() = clippedFraction <= MAX_CLIP && headroomDb >= MIN_HEADROOM_DB
    }

    data class Choice(val step: Step, val floorRiseDb: Double, val reason: String)

    /**
     * Coarse-to-fine search over 0..[max]: measures the [coarse] values, picks
     * with [choose], then measures the untried values within [radius] of the
     * pick and picks again from everything. [measure] returns null to abort.
     */
    fun search(coarse: List<Int>, radius: Int, max: Int, measure: (Int) -> Step?): Pair<Choice?, List<Step>>? {
        val steps = ArrayList<Step>()
        for (g in coarse) steps += measure(g) ?: return null
        val first = choose(steps) ?: return null to steps
        for (g in (first.step.gain - radius)..(first.step.gain + radius)) {
            if (g < 0 || g > max || steps.any { it.gain == g }) continue
            steps += measure(g) ?: return null
        }
        return choose(steps) to steps
    }

    fun choose(steps: List<Step>): Choice? {
        if (steps.isEmpty()) return null
        val base = steps.minBy { it.gain }.floorDb
        val ok = steps.filter { it.allowed }.sortedBy { it.gain }
        if (ok.isEmpty()) return null
        val pick = ok.firstOrNull { it.floorDb - base >= FLOOR_RISE_DB }
        val s = pick ?: ok.last()
        val rise = s.floorDb - base
        val reason = if (pick != null) "잡음 바닥 +%.0f dB에서 충분 · 입력 여유 %.0f dB".format(java.util.Locale.US, rise, s.headroomDb)
            else "여유를 지키는 최고 Gain · 잡음 바닥 +%.0f dB · 입력 여유 %.0f dB".format(java.util.Locale.US, rise, s.headroomDb)
        return Choice(s, rise, reason)
    }
}

