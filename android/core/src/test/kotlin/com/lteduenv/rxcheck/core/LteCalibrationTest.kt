package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.diag.LteCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class LteCalibrationTest {
    /**
     * Synthetic LTE downlink at 2.4 MS/s: 1200-sample slots of 7 OFDM symbols
     * (160 samples + CP 13/11/11/11/11/12/11), random QPSK on subcarriers
     * +-1..+-72, PSS (root of [nid2]) in the last symbol of slots 0 and 10,
     * a frequency offset of [offsetHz], white noise at [snrDb], and a random start.
     */
    private fun lte(offsetHz: Double, nid2: Int, snrDb: Double, seed: Int, dc: Float = 0f): FloatArray {
        val rnd = Random(seed)
        val cp = intArrayOf(13, 11, 11, 11, 11, 12, 11)
        val n = LteCalibration.LEN
        val start = rnd.nextInt(24_000)
        val total = n + start + 2400
        val sig = DoubleArray(2 * total)
        val cosT = DoubleArray(160) { cos(2 * PI * it / 160) }
        val sinT = DoubleArray(160) { sin(2 * PI * it / 160) }
        val pss = LteCalibration.pssSequence(LteCalibration.ROOTS[nid2])
        var pos = 0
        var slot = 0
        val re = DoubleArray(145); val im = DoubleArray(145) // subcarrier k at index k + 72
        while (pos < total) {
            for (sym in 0 until 7) {
                for (k in -72..72) {
                    if (k == 0) { re[72] = 0.0; im[72] = 0.0; continue }
                    val isPss = sym == 6 && (slot % 20 == 0 || slot % 20 == 10) && k in -31..31
                    if (isPss) {
                        val idx = if (k < 0) k + 31 else k + 30
                        re[k + 72] = pss[idx][0]; im[k + 72] = pss[idx][1]
                    } else {
                        re[k + 72] = if (rnd.nextBoolean()) 1.0 else -1.0
                        im[k + 72] = if (rnd.nextBoolean()) 1.0 else -1.0
                    }
                }
                val body = DoubleArray(320)
                for (m in 0 until 160) {
                    var a = 0.0; var b = 0.0
                    for (k in -72..72) {
                        val t = ((k * m) % 160 + 160) % 160
                        val c = cosT[t]; val s = sinT[t]
                        a += re[k + 72] * c - im[k + 72] * s
                        b += re[k + 72] * s + im[k + 72] * c
                    }
                    body[2 * m] = a; body[2 * m + 1] = b
                }
                for (m in -cp[sym] until 160) {
                    if (pos >= total) break
                    val src = (m + 160) % 160
                    sig[2 * pos] = body[2 * src]; sig[2 * pos + 1] = body[2 * src + 1]
                    pos++
                }
            }
            slot++
        }
        var p = 0.0
        for (v in sig) p += v * v
        val sigRms = sqrt(p / total)
        val noise = sigRms / sqrt(2.0) * Math.pow(10.0, -snrDb / 20)
        val scale = 0.25 / sigRms
        val out = FloatArray(2 * n)
        for (i in 0 until n) {
            val j = i + start
            val ph = 2 * PI * offsetHz * i / LteCalibration.FS
            val c = cos(ph); val s = sin(ph)
            val a = sig[2 * j] + noise * rnd.nextGaussian(); val b = sig[2 * j + 1] + noise * rnd.nextGaussian()
            out[2 * i] = ((a * c - b * s) * scale).toFloat() + dc
            out[2 * i + 1] = ((a * s + b * c) * scale).toFloat() - dc / 2
        }
        return out
    }

    private fun Random.nextGaussian(): Double {
        val u = nextDouble().coerceAtLeast(1e-12); val v = nextDouble()
        return sqrt(-2 * kotlin.math.ln(u)) * cos(2 * PI * v)
    }

    @Test fun findsCrystalErrorOnCarrier() {
        val tuned = 954_300_000L
        // Crystal +23 ppm fast, nothing applied: the carrier shows 23 ppm low.
        val off = -23e-6 * tuned
        val est = LteCalibration.estimate(lte(off, nid2 = 1, snrDb = 5.0, seed = 1))
        assertTrue(est.found)
        assertEquals(1, est.nid2)
        assertTrue(est.repeats)
        assertEquals(off, est.offsetHz, 50.0)
        val c = LteCalibration.correction(est.offsetHz, tuned, 0)
        assertEquals(0, c.rasterSteps)
        assertEquals(23.0, c.crystalPpm, 0.1)
    }

    @Test fun residualAfterAppliedCorrectionAndRasterStep() {
        val tuned = 954_300_000L
        // Carrier really at 954.4 MHz, crystal -12 ppm, +5 ppm already applied: residual -17 ppm.
        val off = 100_000.0 + 17e-6 * tuned
        val est = LteCalibration.estimate(lte(off, nid2 = 2, snrDb = 10.0, seed = 2))
        assertTrue(est.found)
        assertEquals(2, est.nid2)
        assertEquals(off, est.offsetHz, 50.0)
        val c = LteCalibration.correction(est.offsetHz, tuned, 5)
        assertEquals(1, c.rasterSteps)
        assertEquals(-12.0, c.crystalPpm, 0.1)
    }

    @Test fun fractionalOffsetNearSubcarrierEdge() {
        val off = 7_400.0 // close to the +-7.5 kHz CP ambiguity edge
        val est = LteCalibration.estimate(lte(off, nid2 = 0, snrDb = 10.0, seed = 3))
        assertTrue(est.found)
        assertEquals(off, est.offsetHz, 50.0)
    }

    /** Weak signal (signal 3 dB under the noise across the capture) still found, several random starts. */
    @Test fun weakSignal() {
        for (seed in 10 until 15) {
            val off = -31_000.0 + seed * 1_000
            val est = LteCalibration.estimate(lte(off, nid2 = seed % 3, snrDb = -3.0, seed = seed))
            assertTrue("seed $seed", est.found)
            assertTrue(est.repeats)
            assertEquals(seed % 3, est.nid2)
            // 300 Hz = 0.3 ppm at 954 MHz, inside the 0.5 ppm rounding to whole ppm.
            assertEquals(off, est.offsetHz, 300.0)
        }
    }

    /** The dongle's DC offset (here stronger than the cell) must not pull the estimate to 0 Hz. */
    @Test fun dcOffsetIgnored() {
        val off = -4_100.0
        val est = LteCalibration.estimate(lte(off, nid2 = 1, snrDb = 3.0, seed = 21, dc = 0.4f))
        assertTrue(est.found && est.repeats)
        assertEquals(off, est.offsetHz, 300.0)
    }

    /** LTE-like block (10 dB-plus plateau, 9 MHz wide) found and its centre put on the 100 kHz raster. */
    @Test fun carrierCentreFromSpectrum() {
        val n = 741
        val f = DoubleArray(n) { 944.3e6 + it * 27_000.0 }
        val rnd = Random(5)
        val lv = FloatArray(n) { i ->
            val inBlock = f[i] in 949.8e6..958.8e6 // 954.3 +- 4.5 MHz, read 0.02 MHz high
            ((if (inBlock) -55.0 else -80.0) + 3 * rnd.nextGaussian()).toFloat()
        }
        assertEquals(954_300_000L, LteCalibration.findCarrier(f, lv, 955.0e6))
        // Flat noise: nothing to lock onto.
        val flat = FloatArray(n) { (-80.0 + 3 * rnd.nextGaussian()).toFloat() }
        assertEquals(null, LteCalibration.findCarrier(f, flat, 954.3e6))
    }

    @Test fun noiseIsNotLte() {
        val rnd = Random(4)
        val iq = FloatArray(2 * LteCalibration.LEN) { (rnd.nextGaussian() * 0.1).toFloat() }
        val est = LteCalibration.estimate(iq)
        println("LTE noise pss=${est.pssDb} hits=${est.hits}")
        assertTrue(!est.found || !est.repeats)
    }
}
