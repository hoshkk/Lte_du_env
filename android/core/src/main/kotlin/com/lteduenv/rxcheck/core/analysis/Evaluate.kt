package com.lteduenv.rxcheck.core.analysis

import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.sweep.Trace
import kotlin.math.abs
import kotlin.math.max

enum class Level { OK, WARN, ALERT, HOLD }

/** One-line field verdict with the reasons behind it. Rule based, not a fault diagnosis. */
data class Verdict(val level: Level, val title: String, val reasons: List<String>)

data class Results(
    val channel: ChannelPower?,
    val floorDbPerMhz: Double?,
    val blocks: List<Block>,
    /** External emissions first, then dongle-internal ones (shown greyed, not counted). */
    val peaks: List<Peak>,
    /** Channel power change versus the baseline, dB. */
    val riseDb: Double?,
    val verdict: Verdict,
) {
    val externalPeaks get() = peaks.filter { it.origin == Origin.EXTERNAL }
}

/**
 * Turns a completed sweep into the field result for each mode.
 *
 * Spurious: a peak counts only if it is not a dongle-internal spur (28.8 MHz
 * crystal harmonic, or present in the no-input recording) and it shows up in at
 * least 2 of the last 3 sweeps, so one-off bursts and noise maxima do not alarm.
 *
 * Reverse: internal spurs are masked before integrating; the verdict combines
 * the rise versus the saved baseline and blocks standing out from the others.
 */
object Evaluate {
    /** Sweeps a peak must appear in (out of the recent history) to be reported. */
    const val CONFIRM_SWEEPS = 2

    fun run(
        s: Settings,
        trace: Trace,
        hold: Trace?,
        baseline: Trace?,
        history: List<Trace> = listOf(trace),
        internal: Trace? = null,
    ): Results {
        val (lo, hi) = s.channelHz()
        val center = (lo + hi) / 2
        val bw = hi - lo
        val tol = max(3 * trace.plan.binHz, 20e3)
        val internalPeaks = internal?.takeIf { it.plan == trace.plan }?.let { Analysis.peaks(it, 6.0, maxCount = 200) } ?: emptyList()
        val knownInternal = Analysis.xtalHarmonicsIn(trace.plan.startHz, trace.plan.stopHz) + internalPeaks.map { it.freqHz }
        val floor = Analysis.noiseFloorDbPerMhz(trace, s.offsetDb)

        if (trace.clipped) {
            val v = Verdict(Level.HOLD, "입력 과다 · 판정 보류",
                listOf("ADC 클리핑 ${String.format("%.2f", trace.clippedFraction * 100)}%", "이득을 낮추거나 감쇠기를 사용하세요"))
            return Results(Analysis.channelPower(trace, center, bw, s.offsetDb), floor, emptyList(), emptyList(), null, v)
        }

        return when (s.mode) {
            Mode.REVERSE -> {
                val clean = Analysis.maskBins(trace, knownInternal, tol)
                val cleanBase = baseline?.let { Analysis.maskBins(it, knownInternal, tol) }
                val channel = Analysis.channelPower(clean, center, bw, s.offsetDb)
                val blocks = Analysis.blocks(clean, center, bw, 1e6, s.offsetDb, cleanBase)
                val baseCh = cleanBase?.let { Analysis.channelPower(it, center, bw, s.offsetDb) }
                val rise = if (channel != null && baseCh != null) channel.totalDb - baseCh.totalDb else null
                Results(channel, floor, blocks, emptyList(), rise, reverseVerdict(s, channel, blocks, rise, baseline != null))
            }
            Mode.SPURIOUS -> {
                val recent = history.filter { it.plan == trace.plan && !it.clipped }.takeLast(3)
                val recentPeaks = recent.map { Analysis.peaks(it, s.thresholdDb, maxCount = 200) }
                val display = if (s.maxHold && hold != null && hold.plan == trace.plan) hold else trace
                val need = minOf(CONFIRM_SWEEPS, recent.size)
                val found = Analysis.peaks(trace, s.thresholdDb, lo, hi, s.offsetDb, maxCount = 60).mapNotNull { p ->
                    val seen = recentPeaks.count { ps -> ps.any { abs(it.freqHz - p.freqHz) <= tol } }
                    if (seen < need) return@mapNotNull null
                    val origin = when {
                        Analysis.nearXtalHarmonic(p.freqHz, tol) -> Origin.DONGLE_XTAL
                        internalPeaks.any { ip -> abs(ip.freqHz - p.freqHz) <= tol && p.aboveFloorDb - ip.aboveFloorDb < 6 } ->
                            Origin.DONGLE_RECORDED
                        else -> Origin.EXTERNAL
                    }
                    val level = Analysis.levelAt(display, p.freqHz, s.offsetDb) ?: p.levelDb
                    p.copy(levelDb = max(level, p.levelDb), origin = origin, seenSweeps = seen)
                }
                val sorted = found.sortedWith(compareBy<Peak> { it.origin != Origin.EXTERNAL }.thenByDescending { it.levelDb }).take(30)
                Results(Analysis.channelPower(trace, center, bw, s.offsetDb), floor, emptyList(), sorted, null,
                    spuriousVerdict(sorted, recent.size, internal != null))
            }
        }
    }

    private fun reverseVerdict(s: Settings, ch: ChannelPower?, blocks: List<Block>, rise: Double?, hasBaseline: Boolean): Verdict {
        if (ch == null) return Verdict(Level.HOLD, "채널이 화면 밖 · 판정 불가", listOf("Span 안에 채널 전체가 들어오게 하세요"))
        val t = s.thresholdDb
        val reasons = ArrayList<String>()
        var level = Level.OK
        fun raise(l: Level) { if (l.ordinal > level.ordinal) level = l }
        rise?.let {
            when {
                it >= t -> { raise(Level.ALERT); reasons += "채널 전체가 기준보다 ${fmt(it)} dB 높음 (광대역 잡음 상승)" }
                it >= t / 2 -> { raise(Level.WARN); reasons += "채널 전체가 기준보다 ${fmt(it)} dB 높음" }
                else -> reasons += "기준 대비 ${fmt(it)} dB (정상 범위)"
            }
        }
        val hotRise = blocks.filter { (it.riseDb ?: 0.0) >= t }
        val hotLocal = blocks.filter { it.aboveMedianDb >= t }
        val warnLocal = blocks.filter { it.aboveMedianDb >= t / 2 && it.aboveMedianDb < t }
        if (hotRise.isNotEmpty()) { raise(Level.ALERT); reasons += "기준보다 ${fmt(t)} dB 이상 오른 구간: ${ranges(hotRise)}" }
        if (hotLocal.isNotEmpty()) { raise(Level.ALERT); reasons += "주변보다 튀는 구간(협대역 간섭 의심): ${ranges(hotLocal)}" }
        else if (warnLocal.isNotEmpty()) { raise(Level.WARN); reasons += "약간 높은 구간: ${ranges(warnLocal)}" }
        if (!hasBaseline) reasons += "기준 미저장 · 블록 균일도만 판정 (정상 시간대에 기준 저장 권장)"
        val title = when (level) {
            Level.OK -> "정상"
            Level.WARN -> "주의"
            Level.ALERT -> if (hotLocal.isNotEmpty() && (rise == null || rise < t)) "협대역 간섭 의심" else "잡음 상승 의심"
            Level.HOLD -> "판정 보류"
        }
        return Verdict(level, title, reasons)
    }

    private fun spuriousVerdict(peaks: List<Peak>, sweeps: Int, recorded: Boolean): Verdict {
        val ext = peaks.filter { it.origin == Origin.EXTERNAL }
        val dongle = peaks.size - ext.size
        val reasons = ArrayList<String>()
        if (dongle > 0) reasons += "동글 자체 신호 ${dongle}건 제외"
        if (!recorded) reasons += "무입력 기록 없음 · 메뉴 '동글 자체 신호 기록'으로 더 정확히 거를 수 있음"
        if (sweeps < CONFIRM_SWEEPS) return Verdict(Level.HOLD, "확인 중 (스윕 $sweeps/$CONFIRM_SWEEPS)", reasons)
        if (ext.isEmpty()) return Verdict(Level.OK, "불요파 없음", reasons)
        val inCh = ext.count { it.inChannel }
        reasons.add(0, ext.take(3).joinToString(", ") { String.format("%.3f MHz (+%.0f dB)", it.freqHz / 1e6, it.aboveFloorDb) })
        return Verdict(if (inCh > 0) Level.ALERT else Level.WARN,
            "불요파 ${ext.size}건" + if (inCh > 0) " · RX 대역 안 ${inCh}건" else "", reasons)
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%+.1f", v)

    private fun ranges(blocks: List<Block>) =
        blocks.joinToString(", ") { String.format(java.util.Locale.US, "%.1f–%.1f", it.startHz / 1e6, it.stopHz / 1e6) } + " MHz"
}
