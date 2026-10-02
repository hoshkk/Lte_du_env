package com.lteduenv.rxcheck.core.analysis

import com.lteduenv.rxcheck.core.sweep.Trace
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Levels are dBFS + user offset. Relative quantities (dB differences) are what matter. */
private fun lin(db: Float) = 10.0.pow(db / 10.0)
private fun db(x: Double) = 10 * log10(max(x, 1e-30))

data class ChannelPower(val totalDb: Double, val psdDbPerMhz: Double, val bwHz: Double)

data class Block(
    val startHz: Double,
    val stopHz: Double,
    val psdDbPerMhz: Double,
    /** Versus the median block of this measurement. */
    val aboveMedianDb: Double,
    /** Versus the saved baseline, if any. */
    val riseDb: Double?,
)

/** Where a detected emission most likely comes from. */
enum class Origin(val label: String) {
    EXTERNAL("외부 신호"),
    /** At a harmonic of the dongle's 28.8 MHz reference crystal. */
    DONGLE_XTAL("동글 자체 (28.8 MHz 배수)"),
    /** Also present in the no-input recording of this dongle. */
    DONGLE_RECORDED("동글 자체 (무입력 기록)"),
}

data class Peak(
    val freqHz: Double,
    val levelDb: Double,
    val aboveFloorDb: Double,
    val bw10dBHz: Double,
    val inChannel: Boolean,
    val origin: Origin = Origin.EXTERNAL,
    /** In how many of the recent sweeps this peak was seen. */
    val seenSweeps: Int = 1,
)

object Analysis {
    /** Integrated power over [centerHz ± bwHz/2], with partial bins weighted. */
    fun channelPower(t: Trace, centerHz: Double, bwHz: Double, offsetDb: Double = 0.0): ChannelPower? {
        val lo = centerHz - bwHz / 2; val hi = centerHz + bwHz / 2
        val bin = t.plan.binHz
        if (lo < t.freqAt(0) - bin / 2 || hi > t.freqAt(t.points - 1) + bin / 2) return null
        var sum = 0.0
        for (i in 0 until t.points) {
            val v = t.levelsDb[i]
            if (!v.isFinite()) continue
            val f = t.freqAt(i)
            val w = min(hi, f + bin / 2) - max(lo, f - bin / 2)
            if (w > 0) sum += lin(v) * w / t.enbwHz
        }
        if (sum <= 0) return null
        val total = db(sum) + offsetDb
        return ChannelPower(total, total - 10 * log10(bwHz / 1e6), bwHz)
    }

    /** Median bin level converted to dB per MHz (noise floor estimate). */
    fun noiseFloorDbPerMhz(t: Trace, offsetDb: Double = 0.0): Double? {
        val v = t.levelsDb.filter { it.isFinite() }.sorted()
        if (v.isEmpty()) return null
        return v[v.size / 2] + 10 * log10(1e6 / t.enbwHz) + offsetDb
    }

    fun medianBinDb(t: Trace): Double? {
        val v = t.levelsDb.filter { it.isFinite() }.sorted()
        return if (v.isEmpty()) null else v[v.size / 2].toDouble()
    }

    /**
     * Splits the channel into equal blocks (1 MHz by default) and reports each
     * block's PSD. A block well above the others points at a narrowband
     * interferer inside the RX channel; [baseline] gives the rise versus a
     * reference measurement taken when the site was quiet.
     */
    fun blocks(t: Trace, centerHz: Double, bwHz: Double, blockHz: Double = 1e6,
               offsetDb: Double = 0.0, baseline: Trace? = null): List<Block> {
        val count = max(1, (bwHz / blockHz).toInt())
        val width = bwHz / count
        val start = centerHz - bwHz / 2
        val psd = (0 until count).map { k ->
            val c = start + (k + 0.5) * width
            channelPower(t, c, width, offsetDb)?.psdDbPerMhz
        }
        val valid = psd.filterNotNull().sorted()
        if (valid.isEmpty()) return emptyList()
        val median = valid[valid.size / 2]
        return psd.mapIndexedNotNull { k, p ->
            if (p == null) return@mapIndexedNotNull null
            val c = start + (k + 0.5) * width
            val rise = baseline?.let { b -> channelPower(b, c, width, offsetDb)?.psdDbPerMhz?.let { p - it } }
            Block(start + k * width, start + (k + 1) * width, p, p - median, rise)
        }
    }

    /**
     * Narrow emissions standing [thresholdDb] above the median floor. Contiguous
     * bins above the threshold (gaps up to [mergeGapBins]) form one emission;
     * its -10 dB width separates CW-like spurs from wideband signals.
     */
    fun peaks(t: Trace, thresholdDb: Double, channelLoHz: Double? = null, channelHiHz: Double? = null,
              offsetDb: Double = 0.0, mergeGapBins: Int = 2, maxCount: Int = 30): List<Peak> {
        val floor = medianBinDb(t) ?: return emptyList()
        val lv = t.levelsDb
        val above = BooleanArray(lv.size) { lv[it].isFinite() && lv[it] > floor + thresholdDb }
        val out = ArrayList<Peak>()
        var i = 0
        while (i < lv.size) {
            if (!above[i]) { i++; continue }
            var end = i; var gap = 0; var j = i + 1
            while (j < lv.size && gap <= mergeGapBins) {
                if (above[j]) { end = j; gap = 0 } else gap++
                j++
            }
            var k = i
            for (m in i..end) if (lv[m].isFinite() && lv[m] > lv[k]) k = m
            val peak = lv[k]
            var a = k; while (a > i && lv[a - 1].isFinite() && lv[a - 1] >= peak - 10) a--
            var b = k; while (b < end && lv[b + 1].isFinite() && lv[b + 1] >= peak - 10) b++
            val f = t.freqAt(k)
            val inCh = channelLoHz != null && channelHiHz != null && f in channelLoHz..channelHiHz
            out += Peak(f, peak + offsetDb, peak - floor, (b - a + 1) * t.plan.binHz, inCh)
            i = end + 1
        }
        return out.sortedByDescending { it.levelDb }.take(maxCount)
    }

    /** Harmonics of the RTL-SDR reference crystal show up as internal spurs. */
    const val XTAL_HZ = 28_800_000.0

    fun nearXtalHarmonic(freqHz: Double, tolHz: Double): Boolean {
        val n = Math.round(freqHz / XTAL_HZ)
        return n >= 1 && kotlin.math.abs(freqHz - n * XTAL_HZ) <= tolHz
    }

    fun xtalHarmonicsIn(startHz: Double, stopHz: Double): List<Double> {
        val first = kotlin.math.ceil(startHz / XTAL_HZ).toLong()
        val last = kotlin.math.floor(stopHz / XTAL_HZ).toLong()
        return (first..last).map { it * XTAL_HZ }
    }

    /**
     * Copy of [t] with the bins within [halfWidthHz] of each frequency replaced by
     * the median of the bins just outside, so a known internal spur does not
     * inflate channel or block power.
     */
    fun maskBins(t: Trace, freqsHz: List<Double>, halfWidthHz: Double): Trace {
        if (freqsHz.isEmpty()) return t
        val v = t.levelsDb.copyOf()
        val half = max(1, kotlin.math.ceil(halfWidthHz / t.plan.binHz).toInt())
        for (f in freqsHz) {
            val c = Math.round((f - t.plan.startHz) / t.plan.binHz).toInt()
            if (c < -half || c >= t.points + half) continue
            val ring = ((c - 3 * half)..(c - half - 1)) + ((c + half + 1)..(c + 3 * half))
            val ref = ring.filter { it in 0 until t.points && t.levelsDb[it].isFinite() }.map { t.levelsDb[it] }.sorted()
            if (ref.isEmpty()) continue
            val m = ref[ref.size / 2]
            for (i in (c - half)..(c + half)) if (i in 0 until t.points) v[i] = m
        }
        return Trace(t.plan, v, t.enbwHz, t.completedSegments, t.unlockedSegments, t.timing, t.timestampMs, t.clippedFraction)
    }

    /** Level at a frequency (nearest bin), dB incl. offset; null outside the trace. */
    fun levelAt(t: Trace, freqHz: Double, offsetDb: Double = 0.0): Double? {
        if (freqHz < t.freqAt(0) - t.plan.binHz / 2 || freqHz > t.freqAt(t.points - 1) + t.plan.binHz / 2) return null
        val i = Math.round((freqHz - t.plan.startHz) / t.plan.binHz).toInt().coerceIn(0, t.points - 1)
        val v = t.levelsDb[i]
        return if (v.isFinite()) v + offsetDb else null
    }

    /** Frequency of the highest finite bin. */
    fun peakFreq(t: Trace): Double? {
        var k = -1
        for (i in 0 until t.points) if (t.levelsDb[i].isFinite() && (k < 0 || t.levelsDb[i] > t.levelsDb[k])) k = i
        return if (k < 0) null else t.freqAt(k)
    }

    /** Element-wise maximum of two traces of the same plan. */
    fun maxHold(prev: Trace?, cur: Trace): Trace {
        if (prev == null || prev.plan != cur.plan) return cur
        val v = FloatArray(cur.points) {
            val a = prev.levelsDb[it]; val b = cur.levelsDb[it]
            when { !a.isFinite() -> b; !b.isFinite() -> a; else -> max(a, b) }
        }
        return Trace(cur.plan, v, cur.enbwHz, cur.completedSegments, cur.unlockedSegments, cur.timing, cur.timestampMs,
            maxOf(prev.clippedFraction, cur.clippedFraction))
    }
}
