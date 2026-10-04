package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.analysis.GainLadder
import com.lteduenv.rxcheck.core.analysis.GainLadder.Step
import com.lteduenv.rxcheck.core.sim.SimReceiver
import com.lteduenv.rxcheck.core.sim.SimReceiver.Signal
import com.lteduenv.rxcheck.core.sweep.SweepEngine
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GainLadderTest {
    /** Floor flat (ADC noise) up to step 5, then rising 3 dB/step; signal peak grows with gain. */
    private fun ladder(peakAt0: Double, clipFrom: Int = 99) = (0..15).map { g ->
        val floor = -80.0 + maxOf(0.0, (g - 5) * 3.0)
        val peak = peakAt0 * Math.pow(10.0, g * 3.0 / 20)
        Step(g, if (g >= clipFrom) 0.01 else 0.0, minOf(1.0, peak), floor)
    }

    @Test fun picksLowestGainWhereFrontEndNoiseDominates() {
        val c = GainLadder.choose(ladder(0.01))!!
        assertEquals(9, c.step.gain) // first step with floor >= +10 dB (step 9: +12)
        assertTrue(c.step.headroomDb >= GainLadder.MIN_HEADROOM_DB)
    }

    @Test fun headroomLimitsTheGain() {
        // A strong signal: peak 0.1 at gain 0 reaches 0.5 (6 dB headroom) at about step 4.
        val c = GainLadder.choose(ladder(0.1))!!
        assertTrue(c.step.gain <= 4)
        assertTrue(c.step.headroomDb >= 6.0)
        assertTrue(c.reason.contains("여유를 지키는"))
    }

    @Test fun clippingStepsAreNeverChosenAndAllClippingGivesNull() {
        val c = GainLadder.choose(ladder(0.01, clipFrom = 7))!!
        assertTrue(c.step.gain < 7)
        assertNull(GainLadder.choose(ladder(0.01, clipFrom = 0)))
    }

    @Test fun sweepReportsAdcPeak() {
        val p = SweepPlan.create(909.3e6, 1.0e6, 13.5e3)
        val weak = SweepEngine(SimReceiver(listOf(Signal(909.5e6, -40.0)), -128.0)).sweep(p, 4)!!
        val strong = SweepEngine(SimReceiver(listOf(Signal(909.5e6, -10.0)), -128.0)).sweep(p, 4)!!
        assertTrue(weak.peakAdc in 0.005..0.1)        // -40 dBFS tone: amplitude 0.01 plus noise
        assertTrue(strong.peakAdc in 0.25..0.5)       // -10 dBFS: amplitude about 0.32
        assertEquals(strong.peakAdc, strong.withLevels(strong.levelsDb).peakAdc, 0.0)
    }
}
