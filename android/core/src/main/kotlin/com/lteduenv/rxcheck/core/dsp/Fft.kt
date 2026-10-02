package com.lteduenv.rxcheck.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 complex FFT with precomputed twiddles for one size. */
class Fft(val size: Int) {
    private val cosT = FloatArray(size / 2)
    private val sinT = FloatArray(size / 2)
    private val rev = IntArray(size)

    init {
        require(size >= 4 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        for (i in 0 until size / 2) {
            cosT[i] = cos(-2 * PI * i / size).toFloat()
            sinT[i] = sin(-2 * PI * i / size).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) {
            val j = rev[i]
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                i += len
            }
            len *= 2
        }
    }
}

/**
 * Averaged power spectrum (Hann window, linear power mean over frames).
 *
 * Scaled so a CW tone of power P reads P at its peak bin. Band power is the
 * sum of bins times binHz / enbwHz.
 */
class PowerSpectrum(val size: Int) {
    private val fft = Fft(size)
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val norm: Double
    /** Equivalent noise bandwidth in bins. */
    val enbwBins: Double

    init {
        var s = 0.0; var s2 = 0.0
        for (w in window) { s += w; s2 += w.toDouble() * w }
        norm = 1.0 / (s * s)
        enbwBins = size * s2 / (s * s)
    }

    /** [iq] interleaved; frames = iq.size / (2*size). Result is fftshifted, linear. */
    fun compute(iq: FloatArray, out: DoubleArray = DoubleArray(size)): DoubleArray {
        val frames = iq.size / (2 * size)
        require(frames >= 1) { "IQ shorter than one FFT frame" }
        out.fill(0.0)
        for (f in 0 until frames) {
            val base = f * 2 * size
            var mi = 0f; var mq = 0f
            for (i in 0 until size) { mi += iq[base + 2 * i]; mq += iq[base + 2 * i + 1] }
            mi /= size; mq /= size
            for (i in 0 until size) {
                re[i] = (iq[base + 2 * i] - mi) * window[i]
                im[i] = (iq[base + 2 * i + 1] - mq) * window[i]
            }
            fft.transform(re, im)
            for (k in 0 until size) {
                val src = (k + size / 2) % size
                out[k] += re[src].toDouble() * re[src] + im[src].toDouble() * im[src]
            }
        }
        val scale = norm / frames
        for (k in 0 until size) out[k] *= scale
        return out
    }
}
