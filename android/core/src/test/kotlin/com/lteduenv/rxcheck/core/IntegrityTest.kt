package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.analysis.Analysis
import com.lteduenv.rxcheck.core.analysis.Evaluate
import com.lteduenv.rxcheck.core.analysis.PeakSource
import com.lteduenv.rxcheck.core.analysis.Results
import com.lteduenv.rxcheck.core.analysis.Validity
import com.lteduenv.rxcheck.core.model.Band
import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.sim.SimReceiver
import com.lteduenv.rxcheck.core.sim.SimReceiver.Signal
import com.lteduenv.rxcheck.core.sweep.Receiver
import com.lteduenv.rxcheck.core.sweep.SweepEngine
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import com.lteduenv.rxcheck.core.sweep.Trace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Nothing real may be hidden and nothing unmeasured may look measured.
 * All signals here come from [SimReceiver] (simulated I/Q), not from a dongle.
 */
class IntegrityTest {
    private val noise = -128.0
    private val spur = Settings().withProfile(Mode.SPURIOUS, Band.B8)
    private val rev = Settings().withProfile(Mode.REVERSE, Band.B8)

    private fun plan(s: Settings, dcShift: Boolean = false) =
        SweepPlan.create(s.centerMhz * 1e6, s.spanMhz * 1e6, s.rbwKhz * 1e3, dcShift = dcShift)

    private fun sweeps(s: Settings, signals: List<Signal>, n: Int, seed: Long = 1, dcShift: Boolean = false): List<Trace> {
        val engine = SweepEngine(SimReceiver(signals, noise, seed = seed))
        return (1..n).map { engine.sweep(plan(s, dcShift), s.averages)!! }
    }

    private fun Results.peakAt(f: Double) = peaks.firstOrNull { abs(it.freqHz - f) < 30e3 }

    // ---- 1. internal-spur candidates are flagged, never removed --------------------------

    /** A real external tone exactly on 921.6 MHz (32 x 28.8 MHz) must be reported. */
    @Test fun externalToneOnCrystalHarmonicIsReported() {
        val h = sweeps(spur, listOf(Signal(921.6e6, -60.0)), 3)
        val r = Evaluate.run(spur, h.last(), h.last(), null, h)
        val p = r.peakAt(921.6e6)
        assertNotNull(p)
        assertTrue(p!!.xtalHarmonic)
        assertEquals(-60.0, p.levelDb, 1.5)
        assertTrue(r.confirmedPeaks.contains(p))
    }

    /** Reverse: the raw channel power includes a tone on 1756.8 MHz (61 x 28.8) inside B3. */
    @Test fun rawChannelPowerKeepsToneOnCrystalHarmonic() {
        val b3 = Settings().withProfile(Mode.REVERSE, Band.B3_30)
        val p = plan(b3)
        val load = Signal(1750e6, -55.0, 28e6)
        val without = SweepEngine(SimReceiver(listOf(load), noise)).sweep(p, 16)!!
        val with = SweepEngine(SimReceiver(listOf(load, Signal(1756.8e6, -50.0)), noise)).sweep(p, 16)!!
        val r0 = Evaluate.run(b3, without, null, null)
        val r1 = Evaluate.run(b3, with, null, null)
        // -55 dBFS load + 30 MHz of noise (-53.2 dBFS) + -50 dBFS tone
        val expected = 10 * log10(10.0.pow(-5.5) + 10.0.pow(-5.32) + 1e-5)
        assertEquals(expected, r1.channel!!.totalDb, 0.4)
        assertTrue(r1.channel!!.totalDb - r0.channel!!.totalDb > 2.5)
        assertNull(r1.correctedChannel) // correction only on request
        val corrected = Evaluate.run(b3.copy(internalCorrection = true), with, null, null)
        assertEquals(r1.channel!!.totalDb, corrected.channel!!.totalDb, 1e-9) // raw stays raw
        assertEquals(r0.channel!!.totalDb, corrected.correctedChannel!!.totalDb, 0.4)
    }

    /** Same frequency as the no-input recording, but a stronger real signal now: still listed. */
    @Test fun peakMatchingNoInputRecordIsListedWithHint() {
        val internal = sweeps(spur, listOf(Signal(899.95e6, -75.0)), 1, seed = 9).last()
        val h = sweeps(spur, listOf(Signal(899.95e6, -60.0), Signal(916.2e6, -62.0)), 3)
        val r = Evaluate.run(spur, h.last(), null, null, h, internal)
        val p = r.peakAt(899.95e6)!!
        assertTrue(p.inNoInputRecord)
        assertEquals(-60.0, p.levelDb, 1.5)
        assertFalse(r.peakAt(916.2e6)!!.inNoInputRecord)
    }

    // ---- 2. DC handling ----------------------------------------------------------------

    private fun centreTone(removeMean: Boolean, dcPatch: Boolean = false, offsetHz: Double = 0.0): Pair<Trace, Double> {
        val p = SweepPlan.create(909.3e6, 1.0e6, 10e3)
        assertEquals(1, p.segments.size)
        val f = p.segments[0].centerHz + offsetHz
        val t = SweepEngine(SimReceiver(listOf(Signal(f, -60.0)), noise)).sweep(p, 8, dcPatch = dcPatch, removeMean = removeMean)!!
        return t to f
    }

    @Test fun toneExactlyOnCentreSurvivesWithDcProcessingOff() {
        val (raw, f) = centreTone(removeMean = false)
        assertEquals(-60.0, Analysis.levelAt(raw, f)!!, 1.0)
        assertFalse(raw.meanRemoved || raw.dcPatched)
        // Documented effect of the optional processing: it erases the centre tone.
        val (meanOff, _) = centreTone(removeMean = true)
        assertTrue(Analysis.levelAt(meanOff, f)!! < -80)
        assertTrue(meanOff.meanRemoved)
        val (patched, _) = centreTone(removeMean = false, dcPatch = true)
        assertTrue(Analysis.levelAt(patched, f)!! < -75)
        assertTrue(patched.dcPatched)
    }

    @Test fun toneNearCentreIsUnaffectedByMeanRemoval() {
        val (a, f) = centreTone(removeMean = false, offsetHz = 100e3)
        val (b, _) = centreTone(removeMean = true, offsetHz = 100e3)
        assertEquals(-60.0, Analysis.levelAt(a, f)!!, 1.0)
        assertEquals(Analysis.levelAt(a, f)!!, Analysis.levelAt(b, f)!!, 0.5)
    }

    @Test fun dcShiftMovesEveryDcBinAndKeepsTheGrid() {
        val cases = listOf(Mode.REVERSE, Mode.SPURIOUS).flatMap { m -> Band.values().map { Settings().withProfile(m, it) } } +
            listOf(spur.copy(spanMhz = 0.5), spur.copy(spanMhz = 1.5), spur.copy(spanMhz = 2.5), rev.copy(spanMhz = 50.0))
        for (s in cases) {
            val a = plan(s); val b = plan(s, dcShift = true)
            assertTrue(a.sameGrid(b))
            val usable = (a.fftSize * SweepPlan.USABLE_FRACTION).toInt() and 1.inv()
            for (seg in b.segments) {
                assertTrue(seg.firstBin >= a.fftSize / 2 - usable / 2)
                assertTrue(seg.firstBin + seg.count <= a.fftSize / 2 + usable / 2)
            }
            assertEquals(a.points, b.segments.sumOf { it.count })
            val da = a.dcPoints().toSet(); val db = b.dcPoints()
            assertTrue("${s.spanMhz} MHz: DC bins overlap", db.none { p -> (p - 2..p + 2).any { it in da } })
        }
    }

    /** DC residue is flagged; with the centres moved it disappears while a real tone stays. */
    @Test fun dcResidueIsFlaggedAndRecheckSeparatesItFromARealTone() {
        val p = plan(spur)
        val dcFreqs = p.dcPoints().map { p.freqAt(it) }
        val realAtDc = dcFreqs[dcFreqs.size / 2 + 1]
        val signals = listOf(Signal(realAtDc, -60.0))
        val normal = SweepEngine(SimReceiver(signals, noise, dcOffset = 0.003)).sweep(p, spur.averages)!!
        val r = Evaluate.run(spur, normal, null, null)
        val dcPeaks = r.peaks.filter { it.atDc }
        assertTrue(dcPeaks.size >= p.segments.size - 1) // every segment centre shows the residue
        val shifted = SweepEngine(SimReceiver(signals, noise, dcOffset = 0.003)).sweep(plan(spur, dcShift = true), spur.averages)!!
        val r2 = Evaluate.run(spur.copy(dcShift = true), shifted, null, null)
        val real = r2.peakAt(realAtDc)
        assertNotNull("real tone kept after moving the centres", real)
        assertFalse(real!!.atDc)
        assertEquals(-60.0, real.levelDb, 1.5)
        // A residue-only DC frequency of the normal plan shows nothing once moved.
        val residueOnly = dcFreqs.first { abs(it - realAtDc) > 100e3 }
        assertNull(r2.peakAt(residueOnly))
    }

    // ---- 3. unmeasured bins -------------------------------------------------------------

    /** Receiver whose PLL "fails" on the segments tuned inside [badHz]. */
    private class Unlocking(val inner: Receiver, val badHz: ClosedRange<Double>) : Receiver by inner {
        override fun tune(hz: Long): Boolean = inner.tune(hz) && hz.toDouble() !in badHz
    }

    @Test fun missingBinsInsideChannelWithholdChannelPowerAndBlock() {
        val p = plan(rev)
        val bad = p.segments[p.segments.size / 2].centerHz.toDouble()
        val sim = SimReceiver(listOf(Signal(909.3e6, -55.0, 9e6)), noise)
        val t = SweepEngine(Unlocking(sim, bad..bad)).sweep(p, rev.averages)!!
        assertEquals(1, t.unlockedSegments)
        assertNull(Analysis.channelPower(t, 909.3e6, 10e6))
        val base = SweepEngine(SimReceiver(listOf(Signal(909.3e6, -55.0, 9e6)), noise, seed = 2)).sweep(p, rev.averages)!!
        val r = Evaluate.run(rev, t, null, base)
        assertNull(r.channel)
        assertNull(r.riseDb)
        assertTrue(r.channelNote!!.contains("미측정"))
        assertEquals(Validity.PARTIAL, r.status.validity)
        assertEquals(10, r.blocks.size) // unmeasured blocks are kept, as unmeasured
        val missing = r.blocks.filter { !it.measured }
        assertTrue(missing.isNotEmpty())
        assertTrue(missing.all { it.psdDbPerMhz == null && it.riseDb == null && it.aboveMedianDb == null && it.missingBins > 0 })
        assertTrue(r.blocks.filter { it.measured }.all { it.riseDb != null })
    }

    @Test fun missingBinsOutsideChannelDoNotBlockChannelPower() {
        val p = plan(spur)
        val bad = p.segments.first().centerHz.toDouble() // 894-896 MHz, outside 904.3-914.3
        val sim = SimReceiver(listOf(Signal(909.3e6, -55.0, 9e6)), noise)
        val t = SweepEngine(Unlocking(sim, bad..bad)).sweep(p, spur.averages)!!
        assertTrue(t.missingPoints > 0)
        val r = Evaluate.run(spur.copy(channelPower = true), t, null, null)
        assertNotNull(r.channel)
        assertEquals(Validity.PARTIAL, r.status.validity) // the sweep still has a hole
    }

    @Test fun missingBinsInBaselineWithholdRise() {
        val p = plan(rev)
        val bad = p.segments[1].centerHz.toDouble()
        val sim = SimReceiver(listOf(Signal(909.3e6, -55.0, 9e6)), noise)
        val base = SweepEngine(Unlocking(sim, bad..bad)).sweep(p, rev.averages)!!
        val now = SweepEngine(SimReceiver(listOf(Signal(909.3e6, -55.0, 9e6)), noise, seed = 3)).sweep(p, rev.averages)!!
        val r = Evaluate.run(rev, now, null, base)
        assertNotNull(r.channel)
        assertNull(r.riseDb)
        assertTrue(r.blocks.any { it.measured && it.riseDb == null && it.baselineMissingBins > 0 })
    }

    // ---- 4. clipping --------------------------------------------------------------------

    @Test fun clippingMarksResultsInvalidButHidesNothing() {
        val t = sweeps(spur, listOf(Signal(909.3e6, 3.0), Signal(918.4e6, -50.0)), 1).last()
        assertTrue(t.clipped)
        val r = Evaluate.run(spur, t, null, null)
        assertEquals(Validity.INVALID, r.status.validity)
        assertTrue(r.status.title.contains("입력 과다"))
        assertNotNull(r.peakAt(918.4e6))
        val rr = Evaluate.run(rev, sweeps(rev, listOf(Signal(909.3e6, 3.0)), 1).last(), null, null)
        assertEquals(Validity.INVALID, rr.status.validity)
    }

    // ---- 5. intermittent signals ----------------------------------------------------------

    @Test fun oneOffBurstIsListedAndKeptByMaxHold() {
        val quiet = sweeps(spur, emptyList(), 2, seed = 4)
        val burst = sweeps(spur, listOf(Signal(910.0e6, -60.0)), 1, seed = 5).last()
        val h = quiet + burst
        val r = Evaluate.run(spur, burst, null, null, h)
        val p = r.peakAt(910.0e6)!!
        assertEquals(1, p.seenSweeps)
        assertEquals(PeakSource.CURRENT, p.source)
        assertFalse(r.confirmedPeaks.contains(p))
        // Next sweep is quiet again: Max Hold still shows it and it is listed from there.
        val after = sweeps(spur, emptyList(), 1, seed = 6).last()
        val hold = Analysis.maxHold(burst, after)
        val r2 = Evaluate.run(spur, after, hold, null, h.drop(1) + after)
        assertEquals(PeakSource.HOLD, r2.peakAt(910.0e6)!!.source)
        // A steady tone is marked as repeated.
        val steady = sweeps(spur, listOf(Signal(910.0e6, -60.0)), 3, seed = 7)
        val r3 = Evaluate.run(spur, steady.last(), null, null, steady)
        assertEquals(3, r3.peakAt(910.0e6)!!.seenSweeps)
    }

    /** A burst in 1 of 16 FFT frames is averaged down 12 dB; the frame maximum still shows it. */
    @Test fun burstShorterThanAveragingIsFoundOnFramePeak() {
        val s = spur.copy(averages = 16)
        val t = sweeps(s, listOf(Signal(912.0e6, -70.0, onFraction = 1.0 / 16)), 1).last()
        assertTrue(Analysis.peaks(t, s.thresholdDb).none { abs(it.freqHz - 912.0e6) < 30e3 })
        val p = Evaluate.run(s, t, null, null).peakAt(912.0e6)
        assertNotNull(p)
        assertEquals(PeakSource.FRAME_PEAK, p!!.source)
        assertEquals(-70.0, p.levelDb, 2.0)
    }

    @Test fun noiseOnlySweepsGiveNoPeaks() {
        for (s in listOf(spur, spur.copy(averages = 16))) {
            val h = sweeps(s, emptyList(), 5, seed = 11)
            for (t in h) assertTrue(Evaluate.run(s, t, null, null, h).peaks.isEmpty())
        }
    }

    // ---- 6. wording ----------------------------------------------------------------------

    @Test fun noPassFailWording() {
        val forbidden = listOf("정상", "불량", "불요파 없음", "불요파 ", "간섭 의심", "잡음 상승")
        val scenes = listOf(
            Evaluate.run(rev, sweeps(rev, listOf(Signal(909.3e6, -55.0, 9e6)), 1).last(), null, null),
            Evaluate.run(rev, sweeps(rev, listOf(Signal(909.3e6, -55.0, 9e6), Signal(906.1e6, -55.0, 180e3)), 1).last(), null, null),
            Evaluate.run(spur, sweeps(spur, emptyList(), 1).last(), null, null),
            Evaluate.run(spur, sweeps(spur, listOf(Signal(910.0e6, -60.0)), 1).last(), null, null),
        )
        for (r in scenes) for (text in listOf(r.status.title) + r.status.notes)
            assertTrue(text, forbidden.none { text.contains(it) })
    }
}
