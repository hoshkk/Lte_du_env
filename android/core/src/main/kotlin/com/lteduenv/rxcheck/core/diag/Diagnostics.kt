package com.lteduenv.rxcheck.core.diag

import com.lteduenv.rxcheck.core.dsp.PowerSpectrum
import com.lteduenv.rxcheck.core.sweep.Receiver
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import com.lteduenv.rxcheck.core.sweep.SweepTiming
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** One line of a check: [ok] null = information only. */
data class Check(val name: String, val value: String, val ok: Boolean?, val note: String = "")

private fun f(pattern: String, vararg a: Any?) = String.format(Locale.US, pattern, *a)
private fun db(x: Double) = 10 * log10(max(x, 1e-30))

/**
 * Quick health check of the receiver, run on the measurement thread with the
 * receiver open. Thresholds are loose sanity limits, not specifications.
 */
object SelfTest {
    val PLL_TEST_HZ = listOf(100_000_000L, 909_300_000L, 1_745_000_000L, 1_765_000_000L)

    fun run(rx: Receiver, centerHz: Long, setGain: ((Int?) -> Unit)?, restoreGain: Int?): List<Check> {
        val out = ArrayList<Check>()
        out += Check("수신기", rx.description, null)

        val locked = PLL_TEST_HZ.count { rx.tune(it) }
        out += Check("PLL 잠금", "$locked/${PLL_TEST_HZ.size} (100·909·1745·1765 MHz)", locked == PLL_TEST_HZ.size,
            if (locked < PLL_TEST_HZ.size) "잠기지 않는 주파수가 있음 · 동글/전원 확인" else "")

        rx.tune(centerHz)
        val n = 240_000
        val iq = FloatArray(2 * n)
        val t0 = System.nanoTime()
        val clipped = rx.capture(iq, 2048)
        val sec = (System.nanoTime() - t0) / 1e9
        val rate = (n + 2048) / sec
        out += Check("샘플 수신 속도", f("%.2f MS/s (설정 %.2f)", rate / 1e6, rx.sampleRate / 1e6), rate >= 0.9 * rx.sampleRate,
            if (rate < 0.9 * rx.sampleRate) "USB가 따라가지 못함 · 케이블/허브/OTG 확인" else "")

        var mi = 0.0; var mq = 0.0
        for (i in 0 until n) { mi += iq[2 * i]; mq += iq[2 * i + 1] }
        mi /= n; mq /= n
        var p = 0.0
        for (i in 0 until n) { val a = iq[2 * i] - mi; val b = iq[2 * i + 1] - mq; p += a * a + b * b }
        val rms = sqrt(p / n)
        val clipPct = clipped * 100.0 / (2 * n)
        out += Check("ADC 신호 크기", f("RMS %.4f FS · 클리핑 %.3f%%", rms, clipPct), rms > 0.001 && clipPct < 0.1,
            // 1 LSB of the 8-bit ADC is 1/128; real noise never sits below 1/8 LSB RMS.
            when { rms <= 0.001 -> "신호가 거의 0 · 동글 이상 가능"; clipPct >= 0.1 -> "입력 과다 · Gain 낮추기"; else -> "" })
        out += Check("DC 오프셋", f("I %+.4f · Q %+.4f FS", mi, mq), abs(mi) < 0.05 && abs(mq) < 0.05,
            if (abs(mi) >= 0.05 || abs(mq) >= 0.05) "오프셋이 큼 · 중심 DC 스파이크가 커질 수 있음" else "")

        if (setGain != null) {
            fun floorDb(): Double {
                val sz = 1024
                val buf = FloatArray(2 * sz * 16)
                rx.capture(buf, 2048)
                val spec = PowerSpectrum(sz).compute(buf)
                val v = spec.indices.filter { abs(it - sz / 2) > 4 }.map { spec[it] }.sorted()
                return db(v[v.size / 2])
            }
            setGain(0); val lo = floorDb()
            setGain(15); val hi = floorDb()
            setGain(restoreGain)
            val d = hi - lo
            out += Check("Gain 반응 (0→15 잡음 바닥)", f("%+.1f dB", d), d >= 6,
                if (d < 6) "이득을 올려도 잡음이 거의 안 변함 · 앞단(LNA) 이상 가능" else "")
        }
        return out
    }
}

/**
 * Measures how long after a retune the samples settle, to give the discard
 * length a measured basis. Each retune also flips the gain between low and high
 * (when [setGain] is available) so samples from before the retune are
 * recognisable by their level; nothing here changes the sweep settings.
 */
object SettleProbe {
    const val BLOCK = 256
    const val CAPTURE = 16_384
    const val TOL_DB = 1.5

    data class Result(
        val transitions: Int,
        /** Samples until the level stayed within [TOL_DB] of its final value, per transition. */
        val settleSamples: List<Int>,
        /** Level change across each transition (old -> new), dB: small steps say little. */
        val stepDb: List<Double>,
        val currentDiscard: Int,
    ) {
        val maxSettle get() = settleSamples.maxOrNull() ?: 0
        /** Transitions with a level step of 6 dB or more (the informative ones). */
        val informative get() = stepDb.count { abs(it) >= 6 }
        val maxInformativeSettle get() = settleSamples.filterIndexed { i, _ -> abs(stepDb[i]) >= 6 }.maxOrNull()
    }

    /** Blocks until the power stays within [TOL_DB] of the median of the second half. */
    fun settleIndex(power: DoubleArray): Int {
        val tail = power.copyOfRange(power.size / 2, power.size).sorted()
        val final = tail[tail.size / 2]
        var k = power.size
        for (i in power.indices.reversed()) {
            if (abs(db(power[i]) - db(final)) <= TOL_DB) k = i else break
        }
        return k
    }

    fun blockPower(iq: FloatArray, samples: Int): DoubleArray = DoubleArray(samples / BLOCK) { b ->
        var s = 0.0
        for (i in b * BLOCK until (b + 1) * BLOCK) { val x = iq[2 * i]; val y = iq[2 * i + 1]; s += x * x + y * y }
        s / BLOCK
    }

    fun run(rx: Receiver, plan: SweepPlan, currentDiscard: Int, setGain: ((Int?) -> Unit)?, restoreGain: Int?, repeats: Int = 12): Result {
        val freqs = plan.segments.map { it.centerHz }.let { if (it.size >= 2) it else listOf(it[0], it[0] + 1_000_000L) }
        val iq = FloatArray(2 * CAPTURE)
        val settle = ArrayList<Int>(); val steps = ArrayList<Double>()
        var prevFinal: Double? = null
        try {
            for (r in 0 until repeats) {
                setGain?.invoke(if (r % 2 == 0) 2 else 14)
                rx.tune(freqs[r % freqs.size])
                rx.capture(iq, 0)
                val p = blockPower(iq, CAPTURE)
                val k = settleIndex(p)
                val tail = p.copyOfRange(p.size / 2, p.size).sorted()
                val final = tail[tail.size / 2]
                if (prevFinal != null) { settle += k * BLOCK; steps += db(final) - db(prevFinal) }
                prevFinal = final
            }
        } finally {
            setGain?.invoke(restoreGain)
        }
        return Result(settle.size, settle, steps, currentDiscard)
    }
}

/** Per-segment breakdown of the last sweep's time. */
data class SpeedReport(
    val segments: Int,
    val totalMs: Long,
    val tuneMsPerSeg: Double,
    val captureMsPerSeg: Double,
    val dspMsPerSeg: Double,
    val otherMsPerSeg: Double,
    val controlPerSeg: Double?,
    val controlMsPerSeg: Double?,
    val bulkMsPerSeg: Double?,
    /** Time the samples themselves take at the sample rate (discard + frames). */
    val dataMsPerSeg: Double,
) {
    companion object {
        fun of(t: SweepTiming, plan: SweepPlan, averages: Int, discard: Int): SpeedReport {
            val n = plan.segments.size.coerceAtLeast(1).toDouble()
            val other = t.totalMs - t.tuneMs - t.captureMs - t.dspMs
            val usb = t.usb
            return SpeedReport(plan.segments.size, t.totalMs, t.tuneMs / n, t.captureMs / n, t.dspMs / n, other / n,
                usb?.let { (it.controlOut + it.controlIn) / n }, usb?.let { it.controlNanos / 1e6 / n },
                usb?.let { it.bulkNanos / 1e6 / n },
                (discard + averages.toLong() * plan.fftSize) * 1000.0 / plan.sampleRate)
        }
    }
}
