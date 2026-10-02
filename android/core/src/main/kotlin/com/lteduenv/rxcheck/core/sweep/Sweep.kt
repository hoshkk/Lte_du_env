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

    companion object {
        /** Fraction of each capture used: the IF/decimation filter rolls off at the edges. */
        const val USABLE_FRACTION = 0.75

        /** Hann -3 dB bandwidth is about 1.44 bins; picks the FFT size closest to the target RBW. */
        fun fftSizeFor(rbwHz: Double, sampleRate: Int): Int =
            (6..14).map { 1 shl it }.minBy { abs(ln(1.44 * sampleRate / it / rbwHz)) }

        fun create(centerHz: Double, spanHz: Double, rbwHz: Double,
                   sampleRate: Int = 2_400_000): SweepPlan {
            require(spanHz > 0 && rbwHz > 0)
            val n = fftSizeFor(rbwHz, sampleRate)
            val bin = sampleRate.toDouble() / n
            val usable = (n * USABLE_FRACTION).toInt() and 1.inv()
            val total = max(2, floor(spanHz / bin + 1e-9).toInt() + 1)
            val start = centerHz - (total - 1) * bin / 2
            val segs = ArrayList<Segment>()
            var p = 0
            while (p < total) {
                val count = min(usable, total - p)
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
) {
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

    fun sweep(
        plan: SweepPlan,
        averages: Int,
        dcPatch: Boolean = false,
        onSegment: ((Trace) -> Unit)? = null,
        isActive: () -> Boolean = { true },
    ): Trace? {
        require(plan.sampleRate == rx.sampleRate) { "sample rate mismatch" }
        val n = plan.fftSize
        val ps = spectrum?.takeIf { it.size == n } ?: PowerSpectrum(n).also { spectrum = it }
        val frames = averages.coerceIn(1, 256)
        if (iq.size != 2 * n * frames) iq = FloatArray(2 * n * frames)
        val levels = FloatArray(plan.points) { Float.NaN }
        val enbwHz = ps.enbwBins * plan.binHz
        val out = DoubleArray(n)
        rx.takeStats()
        val t0 = System.nanoTime()
        var tuneNs = 0L; var capNs = 0L; var dspNs = 0L
        var unlocked = 0
        var clipped = 0L; var components = 0L
        for ((index, seg) in plan.segments.withIndex()) {
            if (!isActive()) return null
            val a = System.nanoTime()
            // A span that fits one capture stays tuned: no retune between sweeps.
            val locked = if (plan.segments.size == 1 && tunedHz == seg.centerHz && tunedLocked) true
                else rx.tune(seg.centerHz).also { tunedHz = seg.centerHz; tunedLocked = it }
            val b = System.nanoTime()
            clipped += rx.capture(iq, discardSamples)
            components += iq.size
            val c = System.nanoTime()
            ps.compute(iq, out)
            if (dcPatch) patchDc(out)
            for (k in 0 until seg.count) {
                levels[seg.firstPoint + k] =
                    if (locked) (10 * log10(max(out[seg.firstBin + k], 1e-20))).toFloat() else Float.NaN
            }
            if (!locked) unlocked++
            val d = System.nanoTime()
            tuneNs += b - a; capNs += c - b; dspNs += d - c
            val last = index == plan.segments.lastIndex
            if (onSegment != null || last) {
                val timing = if (last) SweepTiming((d - t0) / 1_000_000, tuneNs / 1_000_000,
                    capNs / 1_000_000, dspNs / 1_000_000, rx.takeStats()) else null
                val trace = Trace(plan, levels.copyOf(), enbwHz, index + 1, unlocked, timing,
                    System.currentTimeMillis(), clipped.toDouble() / components)
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
