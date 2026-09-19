package com.lteduenv.spectrum.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** Minimal in-place radix-2 Cooley-Tukey FFT. [size] must be a power of two. */
object Fft {

    /**
     * Runs an FFT over interleaved [I0, Q0, I1, Q1, ...] samples (as produced by an RTL-SDR's
     * ByteToFloatSampleAdapter-style conversion) and returns per-bin power in dB, FFT-shifted so
     * index 0 is the most negative frequency and the last index is the most positive - i.e.
     * ready to plot left-to-right across the captured span.
     */
    fun magnitudeSpectrumDb(iqInterleaved: FloatArray, size: Int, removeDc: Boolean = false): FloatArray {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two" }
        val re = FloatArray(size)
        val im = FloatArray(size)
        require(iqInterleaved.size == size * 2) { "Incomplete IQ frame" }
        val pairs = size
        var mi=0.0; var mq=0.0; var windowSum=0.0
        if(removeDc) {
            for(i in 0 until size) { mi+=iqInterleaved[2*i]; mq+=iqInterleaved[2*i+1] }
            mi/=size; mq/=size
        }
        for (i in 0 until pairs) {
            // Hann window to reduce spectral leakage.
            val w = (0.5 - 0.5 * cos(2.0 * PI * i / (size - 1))).toFloat()
            windowSum+=w
            re[i] = ((iqInterleaved[2*i]-mi)*w).toFloat()
            im[i] = ((iqInterleaved[2*i+1]-mq)*w).toFloat()
        }
        fftInPlace(re, im)
        val shifted = FloatArray(size)
        for (k in 0 until size) {
            val srcIndex = (k + size / 2) % size
            val power = re[srcIndex] * re[srcIndex] + im[srcIndex] * im[srcIndex]
            shifted[k] = 10f * log10((power / (windowSum * windowSum) + 1e-15).toDouble()).toFloat()
        }
        return shifted
    }

    private fun fftInPlace(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        // Iterative Cooley-Tukey.
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    val nextIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                    curIm = nextIm
                }
                i += len
            }
            len = len shl 1
        }
    }
}
