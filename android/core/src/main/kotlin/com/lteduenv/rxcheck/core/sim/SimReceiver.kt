package com.lteduenv.rxcheck.core.sim

import com.lteduenv.rxcheck.core.dsp.Fft
import com.lteduenv.rxcheck.core.sweep.Receiver
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Demo / test source. Signals are total powers in dBFS; noise is a density.
 * bwHz = 0 is a CW tone, otherwise a flat band (e.g. loaded LTE uplink).
 */
class SimReceiver(
    val signals: List<Signal> = demoSignals(),
    private val noiseDbFsPerHz: Double = -128.0,
    override val sampleRate: Int = 2_400_000,
    private val tuneDelayMs: Long = 0,
    seed: Long = 1,
    /** Constant I/Q offset added to every sample, like a receiver's DC residue (linear, full scale = 1). */
    private val dcOffset: Double = 0.0,
) : Receiver {
    /**
     * [onFraction] < 1 makes a CW tone a burst: present only in the first part
     * of each capture (shorter than the averaging time).
     */
    data class Signal(val freqHz: Double, val powerDb: Double, val bwHz: Double = 0.0, val onFraction: Double = 1.0)

    private val rnd = Random(seed)
    private var center = 0L
    override val description = "시뮬레이터 (데모)"

    override fun tune(hz: Long): Boolean {
        center = hz
        if (tuneDelayMs > 0) Thread.sleep(tuneDelayMs)
        return true
    }

    override fun capture(out: FloatArray, discardSamples: Int): Int {
        val total = out.size / 2
        var n = 1
        while (n < min(total, 4096)) n *= 2
        val fft = Fft(n)
        val re = FloatArray(n); val im = FloatArray(n)
        val fs = sampleRate.toDouble()
        val noiseBin = 10.0.pow(noiseDbFsPerHz / 10) * fs // total noise power in the IF
        var pos = 0
        while (pos < total) {
            // Build one block in the frequency domain (noise + bands), inverse FFT via conj trick.
            for (k in 0 until n) {
                val f = (if (k < n / 2) k else k - n) * fs / n
                var p = noiseBin
                for (s in signals) if (s.bwHz > 0 && abs(center + f - s.freqHz) <= s.bwHz / 2)
                    p += 10.0.pow(s.powerDb / 10) * fs / s.bwHz
                val a = sqrt(p / 2 / n)
                re[k] = (rnd.nextGaussian() * a).toFloat()
                im[k] = -(rnd.nextGaussian() * a).toFloat()
            }
            fft.transform(re, im)
            val len = min(n, total - pos)
            for (i in 0 until len) {
                out[2 * (pos + i)] = re[i]
                out[2 * (pos + i) + 1] = -im[i]
            }
            pos += len
        }
        val t0 = rnd.nextDouble() * 1000
        for (s in signals) {
            if (s.bwHz > 0) continue
            val off = s.freqHz - center
            if (abs(off) >= fs / 2) continue
            val amp = sqrt(10.0.pow(s.powerDb / 10))
            val w = 2 * PI * off / fs
            val on = (total * s.onFraction).toInt().coerceIn(0, total)
            for (i in 0 until on) {
                val ph = w * (i + t0)
                out[2 * i] += (amp * cos(ph)).toFloat()
                out[2 * i + 1] += (amp * sin(ph)).toFloat()
            }
        }
        if (dcOffset != 0.0) for (i in out.indices) out[i] += dcOffset.toFloat()
        var clipped = 0
        for (i in out.indices) {
            if (out[i] <= -0.996f || out[i] >= 0.992f) clipped++
            out[i] = max(-0.996f, min(0.992f, out[i]))
        }
        return clipped
    }

    override fun close() {}

    companion object {
        /** B8 uplink-like scene: loaded 10 MHz channel, an in-band interferer and a few spurs. */
        fun demoSignals() = listOf(
            Signal(909.3e6, -52.0, 9.0e6),
            Signal(906.1e6, -58.0, 180e3),
            Signal(912.8e6, -66.0),
            Signal(918.4e6, -70.0),
            Signal(1745.0e6, -50.0, 18.0e6),
            Signal(1751.3e6, -62.0),
            Signal(1758.6e6, -60.0, 300e3),
        )
    }
}
