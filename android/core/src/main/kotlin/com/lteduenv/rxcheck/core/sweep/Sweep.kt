package com.lteduenv.rxcheck.core.sweep

import com.lteduenv.rxcheck.core.dsp.PowerSpectrum
import com.lteduenv.rxcheck.core.rtl.UsbStats
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

const val CLIP_LIMIT = 0.001

/** One tuning step: points [firstPoint, firstPoint+count) come from FFT bins starting at firstBin. */
data class Segment(val centerHz: Long, val firstPoint: Int, val count: Int, val firstBin: Int)

data class SweepPlan(
    val startHz: Double,
    val binHz: Double,
    val points: Int,
    val fftSize: Int,
    val sampleRate: Int,
    val segments: List<Segment>,
) {
    val stopHz get() = startHz + (points - 1) * binHz
    fun freqAt(i: Int) = startHz + i * binHz

    /** Same frequency grid (traces can be compared bin by bin even if segmented differently). */
    fun sameGrid(o: SweepPlan) = startHz == o.startHz && binHz == o.binHz && points == o.points && fftSize == o.fftSize

    /**
     * Points that fall on a segment's tuned centre (the DC bin) or right next to
     * it. A peak there may be the receiver's own DC/LO residue or a real signal
     * on that frequency; moving the centres ([create] with dcShift) tells them apart.
     */
    fun dcPoints(): IntArray {
        val out = ArrayList<Int>()
        for (seg in segments) {
            val dc = seg.firstPoint + (fftSize / 2 - seg.firstBin)
            for (p in dc - 1..dc + 1) if (p >= seg.firstPoint && p < seg.firstPoint + seg.count) out += p
        }
        return out.toIntArray()
    }

    companion object {
        /** Fraction of each capture used: the IF/decimation filter rolls off at the edges. */
        const val USABLE_FRACTION = 0.75

        /**
         * Widest span that [create] with dcShift=true takes in one capture with the
         * tuned centre (DC bin) outside the span: no retune between sweeps and no
         * DC residue on screen. About 0.8-0.9 MHz at 2.4 MS/s.
         */
        fun zoomSpanHz(rbwHz: Double, sampleRate: Int = 2_400_000): Double {
            val n = fftSizeFor(rbwHz, sampleRate)
            val usable = (n * USABLE_FRACTION).toInt() and 1.inv()
            // usable/2 - 2 points: the DC bin and its neighbours land past the span edge.
            return (usable / 2 - 3) * sampleRate.toDouble() / n
        }

        /** Hann -3 dB bandwidth is about 1.44 bins; picks the FFT size closest to the target RBW. */
        fun fftSizeFor(rbwHz: Double, sampleRate: Int): Int =
            (6..14).map { 1 shl it }.minBy { abs(ln(1.44 * sampleRate / it / rbwHz)) }

        /**
         * [dcShift] moves every segment's tuned centre (by about half a segment,
         * still using only the usable middle of each capture) so the DC bins land
         * on different frequencies than in the normal plan. Same grid either way.
         */
        fun create(centerHz: Double, spanHz: Double, rbwHz: Double,
                   sampleRate: Int = 2_400_000, dcShift: Boolean = false): SweepPlan {
            require(spanHz > 0 && rbwHz > 0)
            val n = fftSizeFor(rbwHz, sampleRate)
            val bin = sampleRate.toDouble() / n
            val usable = (n * USABLE_FRACTION).toInt() and 1.inv()
            val total = max(2, floor(spanHz / bin + 1e-9).toInt() + 1)
            val start = centerHz - (total - 1) * bin / 2
            val segs = ArrayList<Segment>()
            var p = 0
            if (dcShift && total <= usable / 2) {
                // One capture: tune off the span centre as far as the usable window allows.
                val shift = (usable - total) / 2
                val ci = total / 2 + shift
                segs += Segment((start + ci * bin).roundToLong(), 0, total, n / 2 - total / 2 - shift)
                p = total
            }
            var first = dcShift
            while (p < total) {
                // Shifted plan: a half-size first segment moves every later boundary (and centre).
                val count = min(if (first) usable / 2 else usable, total - p)
                first = false
                // Centre the bins this segment supplies inside its usable window.
                val ci = p + count / 2
                val centerHz = (start + ci * bin).roundToLong()
                segs += Segment(centerHz, p, count, n / 2 - count / 2)
                p += count
            }
            return SweepPlan(start, bin, total, n, sampleRate, segs)
        }
    }
}

class SweepTiming(
    val totalMs: Long,
    val tuneMs: Long,
    val captureMs: Long,
    val dspMs: Long,
    val usb: UsbStats?,
)

/** A (possibly partial) sweep result in dBFS per bin. */
class Trace(
    val plan: SweepPlan,
    val levelsDb: FloatArray,
    val enbwHz: Double,
    val completedSegments: Int,
    val unlockedSegments: Int,
    val timing: SweepTiming?,
    val timestampMs: Long,
    /** Fraction of I/Q components at the ADC limits in the segments so far. */
    val clippedFraction: Double = 0.0,
    /** Largest single-frame level per point (dBFS); shows bursts shorter than the averaging. */
    val framePeakDb: FloatArray? = null,
    /** Processing applied to these levels (recorded so results can say so). */
    val meanRemoved: Boolean = false,
    val dcPatched: Boolean = false,
    /** Largest |I| or |Q| seen in the captures (1.0 = ADC full scale); for gain headroom. */
    val peakAdc: Double = 0.0,
) {
    /** Same trace with other levels (and no frame peaks unless given). */
    fun withLevels(levels: FloatArray, peaks: FloatArray? = null, clipped: Double = clippedFraction) =
        Trace(plan, levels, enbwHz, completedSegments, unlockedSegments, timing, timestampMs, clipped, peaks, meanRemoved, dcPatched, peakAdc)

    /** Points with no valid measurement (PLL not locked, not yet swept). */
    val missingPoints get() = levelsDb.count { !it.isFinite() }
    /** More than 0.1% of samples at full scale: the dongle is overloaded. */
    val clipped get() = clippedFraction > CLIP_LIMIT
    val complete get() = completedSegments == plan.segments.size
    val points get() = plan.points
    fun freqAt(i: Int) = plan.freqAt(i)
}

/**
 * Runs sweeps. Each segment: tune, drop transition samples, capture
 * [averages] FFT frames, average power, place the centre bins on the trace.
 */
class SweepEngine(private val rx: Receiver) {
    /** Forget the current tuning (e.g. after the receiver retuned elsewhere). */
    fun invalidateTuning() { tunedHz = 0L }

    private var spectrum: PowerSpectrum? = null
    private var iq = FloatArray(0)
    private var tunedHz = 0L
    private var tunedLocked = false

    var discardSamples = 2048

    /**
     * [dcPatch] replaces the 3 bins around each segment centre with their
     * neighbours (a correction, recorded on the trace). [removeMean] subtracts
     * each FFT frame's I/Q mean. Both off = raw spectrum.
     */
    fun sweep(
        plan: SweepPlan,
        averages: Int,
        dcPatch: Boolean = false,
        removeMean: Boolean = false,
        onSegment: ((Trace) -> Unit)? = null,
        isActive: () -> Boolean = { true },
    ): Trace? {
        require(plan.sampleRate == rx.sampleRate) { "sample rate mismatch" }
        val n = plan.fftSize
        val ps = spectrum?.takeIf { it.size == n } ?: PowerSpectrum(n).also { spectrum = it }
        val frames = averages.coerceIn(1, 256)
        if (iq.size != 2 * n * frames) iq = FloatArray(2 * n * frames)
        val levels = FloatArray(plan.points) { Float.NaN }
        val framePeak = FloatArray(plan.points) { Float.NaN }
        val enbwHz = ps.enbwBins * plan.binHz
        val out = DoubleArray(n)
        val peak = DoubleArray(n)
        rx.takeStats()
        val t0 = System.nanoTime()
        var tuneNs = 0L; var capNs = 0L; var dspNs = 0L
        var unlocked = 0
        var clipped = 0L; var components = 0L
        var peakAdc = 0.0
        for ((index, seg) in plan.segments.withIndex()) {
            if (!isActive()) return null
            val a = System.nanoTime()
            // A span that fits one capture stays tuned: no retune between sweeps.
            val locked = if (plan.segments.size == 1 && tunedHz == seg.centerHz && tunedLocked) true
                else rx.tune(seg.centerHz).also { tunedHz = seg.centerHz; tunedLocked = it }
            val b = System.nanoTime()
            clipped += rx.capture(iq, discardSamples)
            for (v in iq) { val a = if (v < 0) -v else v; if (a > peakAdc) peakAdc = a.toDouble() }
            components += iq.size
            val c = System.nanoTime()
            ps.compute(iq, out, removeMean, peak)
            if (dcPatch) { patchDc(out); patchDc(peak) }
            for (k in 0 until seg.count) {
                // An unlocked segment stays NaN (unmeasured), never a number.
                levels[seg.firstPoint + k] =
                    if (locked) (10 * log10(max(out[seg.firstBin + k], 1e-20))).toFloat() else Float.NaN
                framePeak[seg.firstPoint + k] =
                    if (locked) (10 * log10(max(peak[seg.firstBin + k], 1e-20))).toFloat() else Float.NaN
            }
            if (!locked) unlocked++
            val d = System.nanoTime()
            tuneNs += b - a; capNs += c - b; dspNs += d - c
            val last = index == plan.segments.lastIndex
            if (onSegment != null || last) {
                val timing = if (last) SweepTiming((d - t0) / 1_000_000, tuneNs / 1_000_000,
                    capNs / 1_000_000, dspNs / 1_000_000, rx.takeStats()) else null
                val trace = Trace(plan, levels.copyOf(), enbwHz, index + 1, unlocked, timing,
                    System.currentTimeMillis(), clipped.toDouble() / components,
                    if (last) framePeak.copyOf() else null, removeMean, dcPatch, peakAdc)
                onSegment?.invoke(trace)
                if (last) return trace
            }
        }
        return null
    }

    /** Replaces the three centre bins with their neighbours' mean (residual LO/DC). */
    private fun patchDc(p: DoubleArray) {
        val c = p.size / 2
        val m = (p[c - 3] + p[c - 2] + p[c + 2] + p[c + 3]) / 4
        p[c - 1] = m; p[c] = m; p[c + 1] = m
    }

    companion object {
        /** -3 dB bandwidth of the Hann window for a plan, Hz. */
        fun rbwHz(plan: SweepPlan) = 1.44 * plan.binHz
    }
}
