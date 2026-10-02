package com.lteduenv.rxcheck.core.analysis

import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.sweep.Trace
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** How far the numbers of a sweep can be used. Not a pass/fail of the equipment. */
enum class Validity {
    /** Complete sweep, no clipping. */
    VALID,
    /** Usable, but some values are unmeasured or not computed (see notes). */
    PARTIAL,
    /** Numbers are not trustworthy (ADC clipping, incomplete sweep). */
    INVALID,
}

/**
 * Short measurement-validity line plus factual notes (levels, counts, what was
 * corrected). It deliberately says nothing like "normal", "faulty" or "no
 * spurious": the user judges from the numbers.
 */
data class Status(val validity: Validity, val title: String, val notes: List<String>)

data class Results(
    /** Raw channel power; null if the channel is not fully swept or has unmeasured bins. */
    val channel: ChannelPower?,
    /** Why [channel] is null (shown instead of a number). */
    val channelNote: String?,
    /** Only with the user's internal-spur correction on: channel power with those bins replaced. */
    val correctedChannel: ChannelPower?,
    val floorDbPerMhz: Double?,
    val blocks: List<Block>,
    /** Everything above the threshold, strongest first; flags are hints only. */
    val peaks: List<Peak>,
    /** Raw channel power change versus the baseline, dB (null if either side is incomplete). */
    val riseDb: Double?,
    /** Same with the correction applied to both traces. */
    val correctedRiseDb: Double?,
    val status: Status,
) {
    val confirmedPeaks get() = peaks.filter { it.seenSweeps >= Evaluate.CONFIRM_SWEEPS && it.source == PeakSource.CURRENT }
}

/**
 * Turns a completed sweep into numbers for each mode, without hiding anything:
 * internal-spur candidates (28.8 MHz harmonics, no-input recording, DC bins) are
 * flagged, not removed, and the raw spectrum is what the channel power is
 * computed from. A peak seen in 2 of the last 3 sweeps is marked as repeated;
 * one seen only now, only in Max Hold or only in the per-frame maximum is still
 * listed.
 */
object Evaluate {
    /** Sweeps a peak must appear in (out of the recent history) to count as repeated. */
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
        val base = baseline?.takeIf { it.plan.sameGrid(trace.plan) }
        val rec = internal?.takeIf { it.plan.sameGrid(trace.plan) }
        val recPeaks = rec?.let { Analysis.peaks(it, 6.0, maxCount = 200) } ?: emptyList()
        val floor = Analysis.noiseFloorDbPerMhz(trace, s.offsetDb)
        val notes = ArrayList<String>()

        val channel = Analysis.channelPower(trace, center, bw, s.offsetDb)
        val channelNote = when {
            channel != null -> null
            !Analysis.covers(trace, lo, hi) -> "채널 일부가 Span 밖 · 미계산"
            else -> "채널 안 미측정 ${Analysis.missingBins(trace, lo, hi)} bin · 보류"
        }
        val baseCh = base?.let { Analysis.channelPower(it, center, bw, s.offsetDb) }
        val rise = if (channel != null && baseCh != null) channel.totalDb - baseCh.totalDb else null

        var corrected: ChannelPower? = null
        var correctedRise: Double? = null
        if (s.internalCorrection) {
            val known = Analysis.xtalHarmonicsIn(trace.plan.startHz, trace.plan.stopHz) + recPeaks.map { it.freqHz }
            corrected = Analysis.channelPower(Analysis.maskBins(trace, known, tol), center, bw, s.offsetDb)
            val cb = base?.let { Analysis.channelPower(Analysis.maskBins(it, known, tol), center, bw, s.offsetDb) }
            if (corrected != null && cb != null) correctedRise = corrected.totalDb - cb.totalDb
        }

        val blocks: List<Block>
        val peaks: List<Peak>
        when (s.mode) {
            Mode.REVERSE -> {
                blocks = Analysis.blocks(trace, center, bw, 1e6, s.offsetDb, base)
                peaks = emptyList()
                channel?.let { notes += "채널 전력 ${f1(it.totalDb)} dB · ${f1(it.psdDbPerMhz)} dB/MHz" }
                when {
                    baseline == null -> notes += "기준 없음 · 기준 대비 변화 미계산"
                    base == null -> notes += "기준이 다른 주파수 격자 · 비교 안 함"
                    rise != null -> notes += "기준 대비 ${signed(rise)} dB"
                    else -> notes += "기준 또는 현재에 미측정 구간 · 기준 대비 미계산"
                }
                val t = s.thresholdDb
                val high = blocks.filter { (it.aboveMedianDb ?: 0.0) >= t }
                if (high.isNotEmpty()) notes += "블록 중앙값보다 ${f1(t)} dB 이상 높은 구간: ${ranges(high)}"
                val risen = blocks.filter { (it.riseDb ?: 0.0) >= t }
                if (risen.isNotEmpty()) notes += "기준보다 ${f1(t)} dB 이상 높은 구간: ${ranges(risen)}"
                val missing = blocks.filter { !it.measured }
                if (missing.isNotEmpty()) notes += "미측정 블록: ${ranges(missing)}"
            }
            Mode.SPURIOUS -> {
                blocks = emptyList()
                peaks = spuriousPeaks(s, trace, hold, history, recPeaks, lo, hi, tol)
                val cur = peaks.filter { it.source == PeakSource.CURRENT }
                val repeated = cur.count { it.seenSweeps >= CONFIRM_SWEEPS }
                notes += "플로어 +${f0(s.thresholdDb)} dB 이상 피크 ${cur.size}개" +
                    if (cur.isNotEmpty()) " (반복 $repeated · 이번만 ${cur.size - repeated})" else ""
                val recent = history.count { it.plan.sameGrid(trace.plan) && !it.clipped }
                if (recent < CONFIRM_SWEEPS) notes += "반복 확인용 스윕 $recent/$CONFIRM_SWEEPS"
                peaks.count { it.source == PeakSource.HOLD }.takeIf { it > 0 }?.let { notes += "Max Hold에만 있는 피크 ${it}개" }
                peaks.count { it.source == PeakSource.FRAME_PEAK }.takeIf { it > 0 }?.let { notes += "짧은 신호(프레임 최대에서만) ${it}개" }
                peaks.count { it.xtalHarmonic }.takeIf { it > 0 }?.let { notes += "28.8 MHz 배수 위치 ${it}개 (내부 신호 가능성, 제외 안 함)" }
                peaks.count { it.inNoInputRecord }.takeIf { it > 0 }?.let { notes += "무입력 기록과 같은 주파수 ${it}개 (제외 안 함)" }
                peaks.count { it.atDc }.takeIf { it > 0 }?.let { notes += "DC 위치 ${it}개 · 메뉴 '중심 이동'으로 재확인" }
                if (s.channelPower) channel?.let { notes += "채널 전력 ${f1(it.totalDb)} dB" }
            }
        }
        if (trace.meanRemoved) notes += "I/Q 평균 제거 적용 (중심 주파수 신호도 줄어듦)"
        if (trace.dcPatched) notes += "중심 3 bin 보간 적용 (보정값)"
        if (corrected != null) notes += "동글 신호 보정값은 별도 표시 (원본 아님)"

        val status = when {
            trace.clipped -> Status(Validity.INVALID, "입력 과다 · 수치 신뢰 불가",
                listOf("ADC 클리핑 ${String.format(Locale.US, "%.2f", trace.clippedFraction * 100)}% · 이득을 낮추거나 감쇠기 사용") + notes)
            !trace.complete -> Status(Validity.INVALID, "스윕 미완료", notes)
            trace.unlockedSegments > 0 -> Status(Validity.PARTIAL, "미측정 구간 있음",
                listOf("PLL 잠금 실패 ${trace.unlockedSegments}구간 (${trace.missingPoints} bin 미측정)") + notes)
            channelNote != null && (s.mode == Mode.REVERSE || s.channelPower) -> Status(Validity.PARTIAL, "채널 전력 미계산", listOf(channelNote) + notes)
            else -> Status(Validity.VALID, "측정 완료", notes)
        }
        return Results(channel, channelNote, corrected, floor, blocks, peaks, rise, correctedRise, status)
    }

    private fun spuriousPeaks(
        s: Settings, trace: Trace, hold: Trace?, history: List<Trace>, recPeaks: List<Peak>,
        lo: Double, hi: Double, tol: Double,
    ): List<Peak> {
        val recent = history.filter { it.plan.sameGrid(trace.plan) && !it.clipped }.takeLast(3)
        val recentPeaks = recent.map { Analysis.peaks(it, s.thresholdDb, maxCount = 200) }
        val dc = trace.plan.dcPoints().map { trace.freqAt(it) }
        val halfBin = trace.plan.binHz / 2 + 1.0
        fun annotate(p: Peak, source: PeakSource) = p.copy(
            source = source,
            seenSweeps = max(1, recentPeaks.count { ps -> ps.any { abs(it.freqHz - p.freqHz) <= tol } }),
            xtalHarmonic = Analysis.nearXtalHarmonic(p.freqHz, tol),
            inNoInputRecord = recPeaks.any { abs(it.freqHz - p.freqHz) <= tol },
            atDc = source != PeakSource.HOLD && dc.any { abs(it - p.freqHz) <= halfBin },
        )
        val out = ArrayList<Peak>()
        Analysis.peaks(trace, s.thresholdDb, lo, hi, s.offsetDb, maxCount = 60).forEach { out += annotate(it, PeakSource.CURRENT) }
        fun addIfNew(list: List<Peak>, source: PeakSource) {
            for (p in list) if (out.none { abs(it.freqHz - p.freqHz) <= tol }) out += annotate(p, source)
        }
        if (s.maxHold && hold != null && hold.plan.sameGrid(trace.plan))
            addIfNew(Analysis.peaks(hold, s.thresholdDb, lo, hi, s.offsetDb, maxCount = 60), PeakSource.HOLD)
        trace.framePeakDb?.let { fp ->
            addIfNew(Analysis.peaks(trace.withLevels(fp), s.thresholdDb, lo, hi, s.offsetDb, maxCount = 60), PeakSource.FRAME_PEAK)
        }
        return out.sortedByDescending { it.levelDb }.take(40)
    }

    private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun signed(v: Double) = String.format(Locale.US, "%+.1f", v)

    private fun ranges(blocks: List<Block>) =
        blocks.joinToString(", ") { String.format(Locale.US, "%.1f–%.1f", it.startHz / 1e6, it.stopHz / 1e6) } + " MHz"
}
