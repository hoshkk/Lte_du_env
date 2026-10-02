package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.analysis.Evaluate
import com.lteduenv.rxcheck.core.analysis.Level
import com.lteduenv.rxcheck.core.analysis.Origin
import com.lteduenv.rxcheck.core.model.Band
import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.sim.SimReceiver
import com.lteduenv.rxcheck.core.sim.SimReceiver.Signal
import com.lteduenv.rxcheck.core.sweep.SweepEngine
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import com.lteduenv.rxcheck.core.sweep.Trace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VerdictTest {
    private val noise = -128.0
    private val spur = Settings().withProfile(Mode.SPURIOUS, Band.B8)
    private val rev = Settings().withProfile(Mode.REVERSE, Band.B8)

    private fun plan(s: Settings) = SweepPlan.create(s.centerMhz * 1e6, s.spanMhz * 1e6, s.rbwKhz * 1e3)
    private fun sweeps(s: Settings, signals: List<Signal>, n: Int, seed: Long = 1): List<Trace> {
        val engine = SweepEngine(SimReceiver(signals, noise, seed = seed))
        return (1..n).map { engine.sweep(plan(s), s.averages)!! }
    }

    /** No antenna: only the dongle's 921.6 MHz (32 x 28.8 MHz) spur is visible. */
    @Test fun crystalHarmonicIsNotASpuriousFinding() {
        val h = sweeps(spur, listOf(Signal(921.6e6, -60.0)), 3)
        val r = Evaluate.run(spur, h.last(), h.last(), null, h)
        assertTrue(r.peaks.any { abs(it.freqHz - 921.6e6) < 30e3 && it.origin == Origin.DONGLE_XTAL })
        assertTrue(r.externalPeaks.isEmpty())
        assertEquals(Level.OK, r.verdict.level)
    }

    @Test fun recordedInternalSpurIsExcluded() {
        val internal = sweeps(spur, listOf(Signal(899.95e6, -65.0)), 1, seed = 9).last()
        val h = sweeps(spur, listOf(Signal(899.95e6, -65.0), Signal(916.2e6, -62.0)), 3)
        val r = Evaluate.run(spur, h.last(), null, null, h, internal)
        assertEquals(Origin.DONGLE_RECORDED, r.peaks.first { abs(it.freqHz - 899.95e6) < 30e3 }.origin)
        assertEquals(1, r.externalPeaks.size)
        assertTrue(abs(r.externalPeaks[0].freqHz - 916.2e6) < 30e3)
        assertEquals(Level.WARN, r.verdict.level)
    }

    @Test fun oneOffBurstIsNotConfirmed() {
        val quiet = sweeps(spur, emptyList(), 2, seed = 4)
        val burst = sweeps(spur, listOf(Signal(910.0e6, -60.0)), 1, seed = 5)
        val h = quiet + burst
        val r = Evaluate.run(spur, h.last(), null, null, h)
        assertTrue(r.externalPeaks.isEmpty())
        val steady = sweeps(spur, listOf(Signal(910.0e6, -60.0)), 3, seed = 6)
        val r2 = Evaluate.run(spur, steady.last(), null, null, steady)
        assertEquals(Level.ALERT, r2.verdict.level) // 910 MHz is inside the B8 RX channel
    }

    @Test fun reverseVerdicts() {
        val load = Signal(909.3e6, -55.0, 9e6)
        val flat = sweeps(rev, listOf(load), 1).last()
        assertEquals(Level.OK, Evaluate.run(rev, flat, null, null).verdict.level)
        val hot = sweeps(rev, listOf(load, Signal(906.1e6, -55.0, 180e3)), 1).last()
        val v = Evaluate.run(rev, hot, null, null).verdict
        assertEquals(Level.ALERT, v.level)
        assertEquals("협대역 간섭 의심", v.title)
        // Whole channel 8 dB up against the saved baseline.
        val up = sweeps(rev, listOf(Signal(909.3e6, -47.0, 9e6)), 1, seed = 3).last()
        val v2 = Evaluate.run(rev, up, null, flat).verdict
        assertEquals(Level.ALERT, v2.level)
        assertEquals("잡음 상승 의심", v2.title)
    }

    /** B3 RX 1735-1765 MHz contains 1756.8 MHz = 61 x 28.8 MHz: it must not look like interference. */
    @Test fun crystalSpurInsideB3ChannelIsMasked() {
        val b3 = Settings().withProfile(Mode.REVERSE, Band.B3_30)
        val p = SweepPlan.create(b3.centerMhz * 1e6, b3.spanMhz * 1e6, b3.rbwKhz * 1e3)
        val t = SweepEngine(SimReceiver(listOf(Signal(1750e6, -55.0, 28e6), Signal(1756.8e6, -50.0)), noise)).sweep(p, 16)!!
        assertEquals(Level.OK, Evaluate.run(b3, t, null, null).verdict.level)
    }

    @Test fun clippingHoldsTheVerdict() {
        val t = sweeps(rev, listOf(Signal(909.3e6, 3.0)), 1).last()
        assertTrue(t.clipped)
        assertEquals(Level.HOLD, Evaluate.run(rev, t, null, null).verdict.level)
    }
}
