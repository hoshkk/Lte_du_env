package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.analysis.Analysis
import com.lteduenv.rxcheck.core.analysis.AutoFit
import com.lteduenv.rxcheck.core.rtl.R82xx
import com.lteduenv.rxcheck.core.rtl.RtlCom
import com.lteduenv.rxcheck.core.rtl.RtlSdr
import com.lteduenv.rxcheck.core.sim.SimReceiver
import com.lteduenv.rxcheck.core.sim.SimReceiver.Signal
import com.lteduenv.rxcheck.core.sweep.SweepEngine
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MergeFeaturesTest {
    /**
     * Golden values from librtlsdr's r82xx_set_bandwidth compiled unchanged
     * (rate, reg 0x0a & 0x10, reg 0x0b & 0xef, IF Hz).
     */
    private val golden = listOf(
        listOf(250000, 0x00, 0xe6, 2125000), listOf(1000000, 0x00, 0xeb, 1700000),
        listOf(1024000, 0x00, 0xeb, 1700000), listOf(1400000, 0x00, 0xec, 1575000),
        listOf(1800000, 0x00, 0xac, 1750000), listOf(1920000, 0x00, 0xae, 1675000),
        listOf(2048000, 0x00, 0xaf, 1625000), listOf(2400000, 0x00, 0x8f, 1815000),
        listOf(2560000, 0x10, 0x6b, 3570000), listOf(2880000, 0x10, 0x6b, 3570000),
        listOf(3200000, 0x10, 0x6b, 3570000), listOf(6500000, 0x10, 0x2a, 4570000),
        listOf(7500000, 0x10, 0x0b, 4570000),
    )

    @Test fun ifBandwidthMatchesLibrtlsdr() {
        for ((rate, r0a, r0b, ifHz) in golden) {
            val usb = FakeRtlUsb()
            val com = RtlCom(usb)
            com.setDemodReg(1, 0x01, 0x18, 1) // open repeater for the fake
            val t = R82xx(com, R82xx.Chip.R828D, isBlogV4 = true, fast = false)
            t.init()
            assertEquals("IF for $rate", ifHz.toLong(), t.setBandwidth(rate))
            assertEquals("0x0a for $rate", r0a, usb.tunerRegs[0x0a] and 0x10)
            assertEquals("0x0b for $rate", r0b, usb.tunerRegs[0x0b] and 0xef)
        }
    }

    @Test fun narrowIfIsDefaultAndTunesWithItsIf() {
        val usb = FakeRtlUsb()
        val sdr = RtlSdr.open(usb)
        assertEquals(1_815_000L, sdr.tuner.ifHz)
        assertTrue(sdr.tune(909_300_000L))
        assertTrue(abs(usb.programmedLoHz() - (909_300_000.0 + 1_815_000)) < 1000)
        val wide = RtlSdr.open(FakeRtlUsb(), narrowIf = false)
        assertEquals(R82xx.IF_HZ, wide.tuner.ifHz)
    }

    @Test fun clippingIsDetected() {
        val loud = SimReceiver(listOf(Signal(909.3e6, 3.0)), seed = 5)
        val quiet = SimReceiver(listOf(Signal(909.3e6, -40.0)), seed = 5)
        val plan = SweepPlan.create(909.3e6, 1e6, 10e3)
        val t1 = SweepEngine(loud).sweep(plan, 4)!!
        val t2 = SweepEngine(quiet).sweep(plan, 4)!!
        assertTrue(t1.clipped)
        assertFalse(t2.clipped)
    }

    @Test fun singleSegmentSpanDoesNotRetune() {
        var tunes = 0
        val sim = SimReceiver(listOf(Signal(909.3e6, -50.0)))
        val counting = object : com.lteduenv.rxcheck.core.sweep.Receiver by sim {
            override fun tune(hz: Long): Boolean { tunes++; return sim.tune(hz) }
        }
        val engine = SweepEngine(counting)
        val narrow = SweepPlan.create(909.3e6, 1.5e6, 10e3)
        assertEquals(1, narrow.segments.size)
        repeat(5) { engine.sweep(narrow, 4) }
        assertEquals(1, tunes)
        val wide = SweepPlan.create(909.3e6, 15e6, 100e3)
        engine.sweep(wide, 4)
        assertEquals(1 + wide.segments.size, tunes)
    }

    @Test fun autoFitLowersGainOnlyWhenClipped() {
        val plan = SweepPlan.create(909.3e6, 1e6, 10e3)
        val loud = SweepEngine(SimReceiver(listOf(Signal(909.3e6, 3.0)))).sweep(plan, 4)!!
        val d1 = AutoFit.decide(listOf(loud), 5, 0.0)!!
        assertTrue(d1.lowerGain); assertEquals(4, d1.gainStep)
        val ok = SweepEngine(SimReceiver(listOf(Signal(909.3e6, -40.0)))).sweep(plan, 4)!!
        val d2 = AutoFit.decide(listOf(ok), 5, 0.0)!!
        assertFalse(d2.lowerGain); assertEquals(5, d2.gainStep)
        // Peak (-40 dB) on screen; floor around two divisions above the bottom.
        assertTrue(d2.refLevelDb >= -35.0)
        val floor = Analysis.medianBinDb(ok)!!
        assertTrue(floor > d2.refLevelDb - 10 * d2.dbPerDiv)
        assertTrue(floor < d2.refLevelDb - 6 * d2.dbPerDiv)
        // Minimum gain: nothing more to lower, fit display instead.
        assertFalse(AutoFit.decide(listOf(loud), 0, 0.0)!!.lowerGain)
    }

    @Test fun markerHelpers() {
        val plan = SweepPlan.create(909.3e6, 2e6, 10e3)
        val t = SweepEngine(SimReceiver(listOf(Signal(909.8e6, -45.0)))).sweep(plan, 8)!!
        val pk = Analysis.peakFreq(t)!!
        assertTrue(abs(pk - 909.8e6) < 2 * plan.binHz)
        assertEquals(-45.0 + 3.0, Analysis.levelAt(t, pk, 3.0)!!, 1.0)
        assertEquals(null, Analysis.levelAt(t, 920e6))
        assertNotNull(Analysis.levelAt(t, plan.startHz))
    }
}
