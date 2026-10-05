package com.lteduenv.rxcheck.core.diag

import com.lteduenv.rxcheck.core.dsp.Fft
import com.lteduenv.rxcheck.core.sweep.Receiver
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Frequency-offset measurement on an LTE downlink carrier (base stations are
 * GPS-disciplined, so their carrier is a far better reference than the
 * dongle's crystal). Method, after LTESS-track / LTE-Cell-Scanner:
 *
 *  1. Fractional offset (within +-7.5 kHz) from the cyclic prefix: every OFDM
 *     symbol starts with a copy of its last 160 samples (at 2.4 MS/s), so the
 *     phase of sum(conj(r[n]) * r[n+160]) is 2*pi*df*160/fs. No timing needed.
 *  2. Integer number of 15 kHz subcarriers and proof that it is LTE: the
 *     primary synchronisation signal (PSS, Zadoff-Chu root 25/29/34, every
 *     5 ms in the centre 62 subcarriers) is correlated for each candidate
 *     offset; it must stand out and repeat 5 ms later.
 *  3. The carrier sits on the 100 kHz raster, so the remaining offset from the
 *     tuned frequency is (raster step) + (crystal error); the step that gives
 *     the smallest crystal error is taken (valid for errors under ~50 ppm).
 */
object LteCalibration {
    const val FS = 2_400_000
    /** Useful OFDM symbol (2048 Ts) at 2.4 MS/s. */
    const val SYMBOL = 160
    /** Samples per 5 ms (PSS period). */
    const val PSS_PERIOD = 12_000
    const val LEN = 65_536
    const val SUBCARRIER_HZ = 15_000.0
    val ROOTS = intArrayOf(25, 29, 34)
    /** PSS periods summed (5 x 5 ms = 60000 of the 65536 samples). */
    const val FOLDS = 5
    /**
     * Folded peak / mean correlation power needed to call it a PSS. Noise alone,
     * summed over 5 periods and searched over all hypotheses, stays under ~4.5.
     */
    const val MIN_PSS_METRIC = 8.0
    /** Single-period peak over its mean that counts as a PSS hit. */
    const val HIT_METRIC = 6.0
    /** Hits needed among the [FOLDS] periods. */
    const val MIN_HITS = 3
    const val SEARCH_HZ = 160_000.0

    data class Estimate(
        /** Apparent carrier offset in the baseband (Hz): the carrier shows up at +offsetHz. */
        val offsetHz: Double,
        /** N_ID(2) of the cell (0..2), from the PSS root. */
        val nid2: Int,
        /** PSS peak over mean correlation power (summed over the 5 ms periods), dB. */
        val pssDb: Double,
        /** Periods (of [FOLDS]) in which the PSS stood out on its own. */
        val hits: Int,
    ) {
        /** The strongest candidate stands out enough to be a PSS. */
        val found get() = pssDb >= 10 * log10(MIN_PSS_METRIC)
        /** The PSS repeated every 5 ms, as a real cell does. */
        val repeats get() = hits >= MIN_HITS
    }

    /** PSS sequence d_u(n), n = 0..61 (TS 36.211 6.11.1.1). */
    fun pssSequence(root: Int): Array<DoubleArray> = Array(62) { n ->
        val ph = if (n <= 30) -PI * root * n * (n + 1) / 63.0 else -PI * root * (n + 1) * (n + 2) / 63.0
        doubleArrayOf(cos(ph), sin(ph))
    }

    /** One PSS OFDM symbol (no CP) at 2.4 MS/s: d(n) on subcarriers -31..-1, +1..+31. */
    fun pssTime(root: Int): FloatArray {
        val d = pssSequence(root)
        val out = FloatArray(2 * SYMBOL)
        for (m in 0 until SYMBOL) {
            var re = 0.0; var im = 0.0
            for (n in 0 until 62) {
                val k = if (n <= 30) n - 31 else n - 30
                val w = 2 * PI * k * SUBCARRIER_HZ * m / FS
                val c = cos(w); val s = sin(w)
                re += d[n][0] * c - d[n][1] * s
                im += d[n][0] * s + d[n][1] * c
            }
            out[2 * m] = re.toFloat(); out[2 * m + 1] = im.toFloat()
        }
        return out
    }

    /** Samples per 0.5 ms slot. */
    const val SLOT = 1200
    /** Start of the PSS symbol's useful part within its slot (last of 7 symbols). */
    const val PSS_USEFUL_START = 1040
    /**
     * Cyclic-prefix sample ranges within a slot at 2.4 MS/s, kept 2 samples clear
     * of each edge (symbol boundaries fall on half samples, plus drift from a
     * crystal error over the capture). Symbol 0 prefix is [0, 12.5), the others
     * [172.5 + 171.25 (k - 1), + 11.25).
     */
    private val CP_GATES: List<IntRange> = (0 until 7).map { k ->
        val start = if (k == 0) 0.0 else 172.5 + 171.25 * (k - 1)
        val len = if (k == 0) 12.5 else 11.25
        kotlin.math.ceil(start + 2).toInt() until kotlin.math.floor(start + len - 2).toInt()
    }

    /**
     * Fractional offset from the cyclic prefix, in (-7.5, +7.5] kHz. Without
     * [slotStart] every sample is used (no timing needed); with it, only the
     * prefix samples of slots starting at [slotStart] (mod [SLOT]).
     */
    fun cpOffsetHz(iq: FloatArray, samples: Int, slotStart: Int? = null): Double {
        var re = 0.0; var im = 0.0
        fun add(n: Int) {
            val ar = iq[2 * n].toDouble(); val ai = iq[2 * n + 1].toDouble()
            val br = iq[2 * (n + SYMBOL)].toDouble(); val bi = iq[2 * (n + SYMBOL) + 1].toDouble()
            // conj(a) * b
            re += ar * br + ai * bi
            im += ar * bi - ai * br
        }
        if (slotStart == null) {
            for (n in 0 until samples - SYMBOL) add(n)
        } else {
            var s0 = ((slotStart % SLOT) + SLOT) % SLOT - SLOT
            while (s0 < samples) {
                for (g in CP_GATES) for (o in g) { val n = s0 + o; if (n >= 0 && n + SYMBOL < samples) add(n) }
                s0 += SLOT
            }
        }
        return atan2(im, re) * FS / (2 * PI * SYMBOL)
    }

    /** Frequency hypotheses are tried every 3.75 kHz (worst miss 1.9 kHz, about 0.2 dB loss over a symbol). */
    const val SEARCH_STEP_HZ = 3_750.0

    /**
     * Full estimate on [iq] (interleaved, at least [LEN] samples, 2.4 MS/s). The
     * strongest PSS candidate is always returned; [Estimate.found] says whether
     * it stands out enough to be a cell.
     */
    fun estimate(iq: FloatArray): Estimate {
        require(iq.size >= 2 * LEN)
        // The dongle's own DC offset sits right on the carrier centre: remove it so it
        // neither biases the prefix phase nor the correlation.
        var mi = 0.0; var mq = 0.0
        for (i in 0 until LEN) { mi += iq[2 * i]; mq += iq[2 * i + 1] }
        mi /= LEN; mq /= LEN
        val x = FloatArray(2 * LEN) { if (it % 2 == 0) (iq[it] - mi).toFloat() else (iq[it] - mq).toFloat() }
        val fft = Fft(LEN)
        val rRe = FloatArray(LEN); val rIm = FloatArray(LEN)
        for (i in 0 until LEN) { rRe[i] = x[2 * i]; rIm[i] = x[2 * i + 1] }
        fft.transform(rRe, rIm)
        val binHz = FS.toDouble() / LEN
        // PSS spectra (conjugated later); only bins within the PSS bandwidth matter.
        val band = ((32 * SUBCARRIER_HZ) / binHz).toInt()
        val templates = ROOTS.map { root ->
            val t = pssTime(root)
            val pr = FloatArray(LEN); val pi = FloatArray(LEN)
            for (m in 0 until SYMBOL) { pr[m] = t[2 * m]; pi[m] = t[2 * m + 1] }
            fft.transform(pr, pi)
            pr to pi
        }
        val xr = FloatArray(LEN); val xi = FloatArray(LEN)
        val fold = DoubleArray(PSS_PERIOD)
        var bestMetric = 0.0; var bestOff = 0.0; var bestRoot = 0
        var bestPeak = 0
        val bestCorr = FloatArray(LEN)
        val steps = (SEARCH_HZ / SEARCH_STEP_HZ).toInt()
        for (k in -steps..steps) {
            val off = k * SEARCH_STEP_HZ
            val shift = (off / binHz).roundToInt()
            for ((ri, tp) in templates.withIndex()) {
                xr.fill(0f); xi.fill(0f)
                for (b in -band..band) {
                    val dst = (b + LEN) % LEN
                    val src = ((b + shift) % LEN + LEN) % LEN
                    // R[src] * conj(P[dst]); then conj for the inverse-by-forward trick
                    val ar = rRe[src]; val ai = rIm[src]; val pr = tp.first[dst]; val pi = tp.second[dst]
                    val cr = ar * pr + ai * pi
                    val ci = ai * pr - ar * pi
                    xr[dst] = cr; xi[dst] = -ci
                }
                fft.transform(xr, xi)
                // Sum the correlation power over the 5 ms periods: a cell's PSS adds up
                // at one position, noise and data average out.
                fold.fill(0.0)
                for (j in 0 until FOLDS) {
                    val o = j * PSS_PERIOD
                    for (i in 0 until PSS_PERIOD) fold[i] += xr[o + i].toDouble() * xr[o + i] + xi[o + i].toDouble() * xi[o + i]
                }
                var peak = 0; var pmax = 0.0; var sum = 0.0
                for (i in 0 until PSS_PERIOD) { val p = fold[i]; sum += p; if (p > pmax) { pmax = p; peak = i } }
                val metric = pmax / (sum / PSS_PERIOD)
                if (metric > bestMetric) {
                    bestMetric = metric; bestOff = off; bestRoot = ri; bestPeak = peak
                    for (i in 0 until LEN) bestCorr[i] = xr[i] * xr[i] + xi[i] * xi[i]
                }
            }
        }
        // Each period on its own: the peak (within +-2 samples, for sample-clock
        // drift) against that period's mean.
        var hits = 0
        for (j in 0 until FOLDS) {
            val o = j * PSS_PERIOD
            var mean = 0.0
            for (i in 0 until PSS_PERIOD) mean += bestCorr[o + i]
            mean /= PSS_PERIOD
            var m = 0f
            for (d in -2..2) m = maxOf(m, bestCorr[o + ((bestPeak + d) % PSS_PERIOD + PSS_PERIOD) % PSS_PERIOD])
            if (m > HIT_METRIC * mean) hits++
        }
        // With the symbol timing known from the PSS, the cyclic-prefix phase on the
        // prefix samples only gives the fine offset (within +-7.5 kHz); the coarse
        // search (within 1.9 kHz) picks the right 15 kHz multiple.
        val fine = cpOffsetHz(x, LEN, bestPeak - PSS_USEFUL_START)
        val total = fine + SUBCARRIER_HZ * Math.round((bestOff - fine) / SUBCARRIER_HZ)
        return Estimate(total, bestRoot, 10 * log10(bestMetric.coerceAtLeast(1e-9)), hits)
    }

    /**
     * Centre of the LTE block around [nearHz] in a spectrum ([freqHz], [levelsDb]),
     * rounded to the 100 kHz raster, or null when no clear block (>= 10 dB above
     * the floor, 1–20 MHz wide) is there. Used to tune onto the carrier centre,
     * where the PSS is, without knowing the operator's exact channel.
     */
    fun findCarrier(freqHz: DoubleArray, levelsDb: FloatArray, nearHz: Double): Long? {
        val n = levelsDb.size
        if (n < 10) return null
        val step = (freqHz[n - 1] - freqHz[0]) / (n - 1)
        // Smooth over about 300 kHz in power, so the block reads as one plateau.
        val half = maxOf(1, (150_000 / step).toInt())
        val sm = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            var acc = 0.0; var c = 0
            for (j in maxOf(0, i - half)..minOf(n - 1, i + half)) {
                val v = levelsDb[j]; if (!v.isNaN()) { acc += Math.pow(10.0, v / 10.0); c++ }
            }
            if (c > 0) sm[i] = 10 * log10(acc / c)
        }
        val valid = sm.filter { !it.isNaN() }.sorted()
        if (valid.size < 10) return null
        val floor = valid[(valid.size * 0.1).toInt()]
        val top = valid[(valid.size * 0.95).toInt().coerceAtMost(valid.size - 1)]
        if (top - floor < 10) return null
        val thr = (floor + top) / 2
        fun above(i: Int) = !sm[i].isNaN() && sm[i] >= thr
        var start = (0 until n).minBy { abs(freqHz[it] - nearHz) }
        if (!above(start)) {
            val reach = (3_000_000 / step).toInt()
            start = (0 until n).filter { above(it) && abs(it - start) <= reach }.minByOrNull { abs(it - start) } ?: return null
        }
        var lo = start; var hi = start
        while (lo > 0 && above(lo - 1)) lo--
        while (hi < n - 1 && above(hi + 1)) hi++
        val width = freqHz[hi] - freqHz[lo]
        if (width < 1_000_000 || width > 20_000_000) return null
        val centre = (freqHz[lo] + freqHz[hi]) / 2
        return Math.round(centre / 100_000) * 100_000
    }

    data class Correction(
        /** Crystal error with the correction that was applied removed, ppm (fractional). */
        val crystalPpm: Double,
        /** Raster steps (100 kHz) between the tuned frequency and the carrier. */
        val rasterSteps: Int,
    )

    /**
     * Crystal error from an apparent offset on a carrier tuned at [tunedHz] with
     * [appliedPpm] already in effect. The carrier is on the 100 kHz raster; the
     * raster step giving the smallest crystal error is assumed.
     */
    fun correction(offsetHz: Double, tunedHz: Long, appliedPpm: Int): Correction {
        val candidates = (-2..2).map { step ->
            val residual = (step * 100_000.0 - offsetHz) / tunedHz * 1e6
            Correction(appliedPpm + residual, step)
        }
        return candidates.minBy { abs(it.crystalPpm) }
    }

    data class Outcome(
        val ok: Boolean,
        val message: String,
        val estimate: Estimate? = null,
        val newPpm: Int? = null,
        val crystalPpm: Double? = null,
        val clippedFraction: Double = 0.0,
    )

    /** Captures tried before giving up (a PSS can be lost to a deep fade in one). */
    const val TRIES = 3

    /**
     * Tunes [rx] to [tunedHz] (an LTE downlink carrier centre), captures 27 ms and
     * estimates, up to [TRIES] times. Lowers the gain once if the capture clips.
     * The receiver must be running at 2.4 MS/s. Tuning and gain are left for the
     * caller to restore.
     */
    fun run(rx: Receiver, tunedHz: Long, appliedPpm: Int, setGain: ((Int?) -> Unit)?, gain: Int?): Outcome {
        if (rx.sampleRate != FS) return Outcome(false, "샘플레이트가 2.4 MS/s가 아닙니다")
        val iq = FloatArray(2 * LEN)
        var g = gain
        var clip = 0.0
        var best: Estimate? = null
        for (attempt in 0 until TRIES + 1) {
            if (!rx.tune(tunedHz)) return Outcome(false, "PLL 잠금 실패 (${tunedHz / 1e6} MHz)")
            clip = rx.capture(iq, 2048).toDouble() / iq.size
            if (clip > 1e-3 && setGain != null && g != null && g > 0 && attempt == 0) {
                g = maxOf(0, g - 5); setGain(g); continue
            }
            val est = estimate(iq)
            if (best == null || est.pssDb > best.pssDb) best = est
            if (est.found && est.repeats) { best = est; break }
        }
        val est = best!!
        val need = 10 * log10(MIN_PSS_METRIC)
        if (!est.found) return Outcome(false,
            "LTE 동기 신호(PSS)를 찾지 못했습니다 (가장 비슷한 후보 %.1f dB · 기준 %.1f dB) · 하향 중심 주파수와 신호 세기를 확인하세요".format(est.pssDb, need),
            est, clippedFraction = clip)
        if (!est.repeats) return Outcome(false,
            "PSS 후보(%.1f dB)가 5 ms 간격으로 충분히 반복되지 않아 (${est.hits}/$FOLDS) 신뢰할 수 없습니다 · 다시 시도하세요".format(est.pssDb),
            est, clippedFraction = clip)
        val c = correction(est.offsetHz, tunedHz, appliedPpm)
        if (abs(c.crystalPpm) > 50) return Outcome(false,
            "계산된 오차 %.1f ppm이 너무 큽니다 (±50 ppm 넘으면 판단 불가)".format(c.crystalPpm), est, clippedFraction = clip)
        val ppm = c.crystalPpm.roundToLong().toInt()
        return Outcome(true, "보정 %+d ppm (측정 %+.2f ppm · 오프셋 %+.0f Hz · N_ID2=%d · PSS %.1f dB · 반복 %d/%d%s)".format(
            ppm, c.crystalPpm, est.offsetHz, est.nid2, est.pssDb, est.hits, FOLDS,
            if (c.rasterSteps != 0) " · 캐리어가 %+d00 kHz 옆".format(c.rasterSteps) else ""),
            est, ppm, c.crystalPpm, clip)
    }
}
