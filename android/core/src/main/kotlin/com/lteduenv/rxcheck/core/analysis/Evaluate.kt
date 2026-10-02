package com.lteduenv.rxcheck.core.analysis

import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.sweep.Trace

data class Results(
    val channel: ChannelPower?,
    val floorDbPerMhz: Double?,
    val blocks: List<Block>,
    val peaks: List<Peak>,
    /** Channel power change versus the baseline, dB. */
    val riseDb: Double?,
    /** Blocks or peaks that crossed the threshold. */
    val alarms: Int,
)

/** What each field mode reports for a completed sweep. */
object Evaluate {
    fun run(s: Settings, trace: Trace, hold: Trace?, baseline: Trace?): Results {
        val (lo, hi) = s.channelHz()
        val center = (lo + hi) / 2
        val bw = hi - lo
        val channel = Analysis.channelPower(trace, center, bw, s.offsetDb)
        val floor = Analysis.noiseFloorDbPerMhz(trace, s.offsetDb)
        return when (s.mode) {
            Mode.REVERSE -> {
                val blocks = Analysis.blocks(trace, center, bw, 1e6, s.offsetDb, baseline)
                val baseCh = baseline?.let { Analysis.channelPower(it, center, bw, s.offsetDb) }
                val rise = if (channel != null && baseCh != null) channel.totalDb - baseCh.totalDb else null
                val alarms = blocks.count { it.aboveMedianDb > s.thresholdDb || (it.riseDb ?: 0.0) > s.thresholdDb }
                Results(channel, floor, blocks, emptyList(), rise, alarms)
            }
            Mode.SPURIOUS -> {
                val src = if (s.maxHold && hold != null) hold else trace
                val peaks = Analysis.peaks(src, s.thresholdDb, lo, hi, s.offsetDb)
                Results(channel, floor, emptyList(), peaks, null, peaks.size)
            }
        }
    }
}
