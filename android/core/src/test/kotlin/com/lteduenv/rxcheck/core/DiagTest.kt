package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.diag.SelfTest
import com.lteduenv.rxcheck.core.diag.SettleProbe
import com.lteduenv.rxcheck.core.diag.SpeedReport
import com.lteduenv.rxcheck.core.rtl.RtlSdr
import com.lteduenv.rxcheck.core.sim.SimReceiver
import com.lteduenv.rxcheck.core.sim.SimReceiver.Signal
import com.lteduenv.rxcheck.core.sweep.SweepEngine
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import com.lteduenv.rxcheck.core.view.Waterfall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DiagTest {
    /** Zoom: one capture, tapped frequency centred, DC bin outside the span. */
    @Test fun zoomSpanIsOneSegmentWithoutDcOnScreen() {
        for (rbw in listOf(54e3, 13.5e3, 6.75e3, 1.6875e3)) {
            val span = SweepPlan.zoomSpanHz(rbw)
            assertTrue("$rbw: $span", span in 0.75e6..0.9e6)
            val p = SweepPlan.create(912.0e6, span, rbw, dcShift = true)
            assertEquals(1, p.segments.size)
            assertEquals(0, p.dcPoints().size)
            assertTrue(abs((p.startHz + p.stopHz) / 2 - 912.0e6) <= p.binHz)
        }
        // And a tone at the zoom centre reads its level (sim).
        val p = SweepPlan.create(912.0e6, SweepPlan.zoomSpanHz(13.5e3), 13.5e3, dcShift = true)
        val t = SweepEngine(SimReceiver(listOf(Signal(912.0e6, -60.0)), -128.0)).sweep(p, 4)!!
        assertEquals(-60.0, com.lteduenv.rxcheck.core.analysis.Analysis.levelAt(t, 912.0e6)!!, 1.5)
    }

    @Test fun waterfallKeepsNarrowPeaksAndUnmeasuredPoints() {
        val p = SweepPlan.create(909.3e6, 15e6, 13.5e3)
        val eng = SweepEngine(SimReceiver(listOf(Signal(912.345e6, -60.0)), -128.0))
        val wf = Waterfall(columns = 200, rows = 4)
        repeat(6) { wf.add(eng.sweep(p, 4)!!) }
        assertEquals(4, wf.size)
        val row = wf.row(0)!!
        val col = ((912.345e6 - p.startHz) / (p.stopHz - p.startHz) * 200).toInt()
        val peak = (col - 1..col + 1).maxOf { row[it] }
        assertEquals(-60.0, peak.toDouble(), 1.5) // 1600+ points into 200 columns: peak survives
        // Unmeasured points stay unmeasured.
        val t = eng.sweep(p, 4)!!
        val holes = t.levelsDb.copyOf().also { for (i in 0 until 100) it[i] = Float.NaN }
        wf.add(t.withLevels(holes))
        assertTrue(wf.row(0)!![0].isNaN())
        // A new grid starts over.
        wf.add(SweepEngine(SimReceiver(emptyList(), -128.0)).sweep(SweepPlan.create(1745e6, 25e6, 54e3), 4)!!)
        assertEquals(1, wf.size)
        val px = IntArray(200 * 4)
        wf.render(px, -100.0, -40.0, Waterfall.palette(), 0, 1)
        assertTrue(px.drop(200).all { it == 0 })
    }

    /** Fake dongle with a frozen ADC (every byte 127): the self-test must say so. */
    @Test fun selfTestFlagsDeadInput() {
        val sdr = RtlSdr.open(FakeRtlUsb())
        val checks = SelfTest.run(sdr, 909_300_000L, sdr::setGain, 4)
        assertEquals(true, checks.first { it.name == "PLL 잠금" }.ok)
        assertEquals(false, checks.first { it.name == "ADC 신호 크기" }.ok)
        assertEquals(false, checks.first { it.name.startsWith("Gain 반응") }.ok)
    }

    @Test fun selfTestPassesOnSimulatedSignal() {
        val checks = SelfTest.run(SimReceiver(), 909_300_000L, null, null)
        assertEquals(true, checks.first { it.name == "ADC 신호 크기" }.ok)
        assertEquals(true, checks.first { it.name == "DC 오프셋" }.ok)
        assertNull(checks.firstOrNull { it.name.startsWith("Gain 반응") })
    }

    /**
     * Model receiver: each capture starts with [stale] samples at the previous
     * capture's level, and the level alternates 20 dB per capture; noise-like
     * wobble (+-2 dB) on every block, like a real bursty input.
     */
    private class StaleModel(val stale: Int) : com.lteduenv.rxcheck.core.sweep.Receiver {
        override val sampleRate = 2_400_000
        override val description = "model"
        private var n = 0
        private val rnd = java.util.Random(3)
        override fun tune(hz: Long) = true
        override fun capture(out: FloatArray, discardSamples: Int): Int {
            val newAmp = if (n % 2 == 0) 0.01 else 0.1; val oldAmp = if (n % 2 == 0) 0.1 else 0.01
            n++
            for (i in 0 until out.size / 2) {
                val a = (if (i < stale) oldAmp else newAmp) * Math.pow(10.0, (rnd.nextDouble() * 4 - 2) / 20)
                out[2 * i] = a.toFloat(); out[2 * i + 1] = 0f
            }
            return 0
        }
        override fun close() {}
    }

    @Test fun settleProbeCountsSamplesAtThePreviousLevel() {
        val plan = SweepPlan.create(909.3e6, 15e6, 54e3)
        val r = SettleProbe.run(StaleModel(3000), plan, 2048, null, null, repeats = 6)
        assertEquals(5, r.transitions)
        assertEquals(5, r.informative)
        assertTrue("${r.settleSamples}", r.settleSamples.all { it in 2816..3328 }) // 3000 rounded to 256-sample blocks
        val clean = SettleProbe.run(StaleModel(0), plan, 2048, null, null, repeats = 6)
        assertEquals(0, clean.maxSettle)
        // Steady level (no step): nothing to judge, no false "too long".
        val flat = SettleProbe.run(RtlSdr.open(FakeRtlUsb()), plan, 2048, null, null, repeats = 4)
        assertEquals(0, flat.informative)
    }

    @Test fun speedReportSplitsSegmentTime() {
        val sdr = RtlSdr.open(FakeRtlUsb())
        val plan = SweepPlan.create(909.3e6, 15e6, 54e3)
        val t = SweepEngine(sdr).sweep(plan, 4)!!
        val r = SpeedReport.of(t.timing!!, plan, 4, 2048)
        assertEquals(plan.segments.size, r.segments)
        assertNotNull(r.controlPerSeg)
        assertTrue("${r.controlPerSeg}", r.controlPerSeg!! in 5.0..10.0) // 7 per retune + first-tune extras
        assertEquals((2048 + 4 * 64) * 1000.0 / 2_400_000, r.dataMsPerSeg, 1e-9)
        assertFalse(r.dataMsPerSeg > 2.0)
    }
}
