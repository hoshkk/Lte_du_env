package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.analysis.Analysis
import com.lteduenv.rxcheck.core.dsp.PowerSpectrum
import com.lteduenv.rxcheck.core.model.Band
import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.rtl.RtlSdr
import com.lteduenv.rxcheck.core.sim.SimReceiver
import com.lteduenv.rxcheck.core.sim.SimReceiver.Signal
import com.lteduenv.rxcheck.core.sweep.SweepEngine
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

class MeasurementTest {
    private val noise = -128.0 // dBFS/Hz

    @Test fun toneReadsItsPowerAtPeakBin() {
        val n = 1024; val fs = 2.4e6
        val iq = FloatArray(2 * n * 8)
        val f = 37.3 * fs / n
        for (i in 0 until n * 8) {
            iq[2 * i] = (0.01 * cos(2 * PI * f * i / fs)).toFloat()
            iq[2 * i + 1] = (0.01 * sin(2 * PI * f * i / fs)).toFloat()
        }
        val p = PowerSpectrum(n).compute(iq)
        val peak = p.indices.maxBy { p[it] }
        assertTrue(abs(peak - (n / 2 + 37)) <= 1)
        // Scalloping loss of Hann at 0.3 bin offset is < 0.5 dB.
        assertEquals(-40.0, 10 * log10(p[peak]), 0.7)
        assertEquals(1.5, PowerSpectrum(n).enbwBins, 0.01)
    }

    @Test fun planCoversSpanWithContiguousSegments() {
        val plan = SweepPlan.create(909.3e6, 15e6, 100e3)
        assertTrue(plan.startHz <= 909.3e6 - 7.5e6 + plan.binHz)
        assertTrue(plan.stopHz >= 909.3e6 + 7.5e6 - plan.binHz)
        var next = 0
        val usable = (plan.fftSize * SweepPlan.USABLE_FRACTION).toInt()
        for (s in plan.segments) {
            assertEquals(next, s.firstPoint)
            assertTrue(s.firstBin >= (plan.fftSize - usable) / 2)
            assertTrue(s.firstBin + s.count <= (plan.fftSize + usable) / 2)
            // Segment centre maps to FFT centre bin.
            val centrePoint = s.firstPoint + (plan.fftSize / 2 - s.firstBin)
            assertEquals(plan.freqAt(centrePoint), s.centerHz.toDouble(), 1.0)
            next += s.count
        }
        assertEquals(plan.points, next)
        assertEquals(9, plan.segments.size) // 15 MHz / 1.8 MHz usable
    }

    @Test fun channelPowerAndBlocksOnSimulatedUplink() {
        val sim = SimReceiver(listOf(Signal(909.3e6, -50.0, 9e6), Signal(906.1e6, -56.0, 180e3)), noise)
        val plan = SweepPlan.create(909.3e6, 15e6, 30e3)
        val t = SweepEngine(sim).sweep(plan, 16)!!
        assertTrue(t.complete)
        val cp = Analysis.channelPower(t, 909.3e6, 10e6)!!
        // -50 dBFS carrier + 10 MHz of noise at -128 dBFS/Hz (-58 dBFS) + -56 dBFS interferer
        val expected = 10 * log10(1e-5 + 10.0.pow(-5.8) + 10.0.pow(-5.6))
        assertEquals(expected, cp.totalDb, 0.3)
        val blocks = Analysis.blocks(t, 909.3e6, 10e6)
        assertEquals(10, blocks.size)
        val worst = blocks.maxBy { it.aboveMedianDb }
        assertTrue(906.1e6 in worst.startHz..worst.stopHz)
        assertTrue(worst.aboveMedianDb > 3)
    }

    @Test fun baselineRiseIsReportedPerBlock() {
        val quiet = SimReceiver(listOf(Signal(909.3e6, -60.0, 9e6)), noise, seed = 2)
        val busy = SimReceiver(listOf(Signal(909.3e6, -60.0, 9e6), Signal(911.5e6, -58.0, 500e3)), noise, seed = 3)
        val plan = SweepPlan.create(909.3e6, 15e6, 30e3)
        val base = SweepEngine(quiet).sweep(plan, 16)!!
        val now = SweepEngine(busy).sweep(plan, 16)!!
        val blocks = Analysis.blocks(now, 909.3e6, 10e6, baseline = base)
        val hit = blocks.first { 911.5e6 in it.startHz..it.stopHz }
        assertTrue("rise ${hit.riseDb}", hit.riseDb!! > 6)
        val other = blocks.first { 905.5e6 in it.startHz..it.stopHz }
        assertEquals(0.0, other.riseDb!!, 0.5)
    }

    @Test fun spuriousPeaksFoundWithLevels() {
        val sim = SimReceiver(listOf(Signal(901.0e6, -70.0), Signal(918.4e6, -65.0), Signal(909.3e6, -55.0, 9e6)), noise)
        val plan = SweepPlan.create(909.3e6, 30e6, 10e3)
        val t = SweepEngine(sim).sweep(plan, 4)!!
        val peaks = Analysis.peaks(t, 10.0, 904.3e6, 914.3e6)
        val p918 = peaks.first { abs(it.freqHz - 918.4e6) < 2 * plan.binHz }
        val p901 = peaks.first { abs(it.freqHz - 901.0e6) < 2 * plan.binHz }
        assertEquals(-65.0, p918.levelDb, 1.0)
        assertEquals(-70.0, p901.levelDb, 1.0)
        assertTrue(!p918.inChannel && !p901.inChannel)
        assertTrue(p918.bw10dBHz < 5 * plan.binHz)
    }

    @Test fun sweepOverRealDriverWithFakeUsb() {
        val sdr = RtlSdr.open(FakeRtlUsb())
        val plan = SweepPlan.create(1745e6, 25e6, 100e3)
        val t = SweepEngine(sdr).sweep(plan, 4)!!
        assertTrue(t.complete)
        assertEquals(0, t.unlockedSegments)
        val usb = t.timing!!.usb!!
        // per segment: 7 tune + 2 FIFO reset control transfers (fast mode); the first
        // tune also switches the V4 input (GPIO read-modify-write).
        assertTrue("control ${usb.controlOut + usb.controlIn}", usb.controlOut + usb.controlIn <= 9 * plan.segments.size + 12)
    }

    @Test fun settingsProfilesAndValidation() {
        val s = Settings().withProfile(Mode.SPURIOUS, Band.B3_30)
        assertNull(s.validate())
        assertEquals(1720.0, s.startMhz, 1e-9)
        assertEquals(1766.0, s.stopMhz, 1e-9)
        val r = Settings().withProfile(Mode.REVERSE, Band.B3_30)
        assertNull(r.validate())
        assertTrue(r.stopMhz <= 1766.0)
        assertNotNull(r.copy(centerMhz = 1760.0).validate())
        val (lo, hi) = r.channelHz()
        assertEquals(1735e6, lo, 1.0); assertEquals(1765e6, hi, 1.0)
    }

    @Test fun evaluateFlagsInterferenceAndSpurs() {
        val sim = SimReceiver(listOf(Signal(909.3e6, -52.0, 9e6), Signal(906.1e6, -54.0, 180e3),
            Signal(918.4e6, -66.0)), noise)
        val rev = Settings().withProfile(Mode.REVERSE, Band.B8)
        val tr = SweepEngine(sim).sweep(SweepPlan.create(rev.centerMhz * 1e6, rev.spanMhz * 1e6, rev.rbwKhz * 1e3), rev.averages)!!
        val r = com.lteduenv.rxcheck.core.analysis.Evaluate.run(rev, tr, null, null)
        assertNotNull(r.channel)
        assertTrue(r.alarms >= 1)
        assertTrue(r.blocks.any { 906.1e6 in it.startHz..it.stopHz && it.aboveMedianDb > rev.thresholdDb })

        val sp = Settings().withProfile(Mode.SPURIOUS, Band.B8)
        val ts = SweepEngine(sim).sweep(SweepPlan.create(sp.centerMhz * 1e6, sp.spanMhz * 1e6, sp.rbwKhz * 1e3), sp.averages)!!
        val q = com.lteduenv.rxcheck.core.analysis.Evaluate.run(sp, ts, ts, null)
        assertTrue(q.peaks.any { abs(it.freqHz - 918.4e6) < 30e3 && !it.inChannel })
        assertEquals(q.peaks.size, q.alarms)
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)
}
