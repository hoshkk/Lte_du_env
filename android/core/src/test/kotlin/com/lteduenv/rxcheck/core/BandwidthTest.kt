package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.model.Band
import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BandwidthTest {
    @Test fun everyRbwChoiceIsWhatTheSweepUses() {
        for (k in Settings.RBW_CHOICES_KHZ) {
            val s = Settings(rbwKhz = k)
            assertEquals(k * 1e3, s.rbwActualHz(), 1e-6)
            val plan = SweepPlan.create(909.3e6, 1e6, k * 1e3)
            assertEquals(s.rbwActualHz(), 1.44 * plan.binHz, 1e-6)
        }
        assertEquals(54.0, Settings.RBW_CHOICES_KHZ.first(), 1e-9)
    }

    @Test fun requestedRbwMapsToNearestAchievable() {
        assertEquals(54_000.0, Settings(rbwKhz = 100.0).rbwActualHz(), 1e-6) // 1.3.1 default
        assertEquals(13_500.0, Settings(rbwKhz = 10.0).rbwActualHz(), 1e-6)
        assertEquals(54_000.0, Settings().withProfile(Mode.REVERSE, Band.B8).rbwActualHz(), 1e-6)
        assertEquals(13_500.0, Settings().withProfile(Mode.SPURIOUS, Band.B8).rbwActualHz(), 1e-6)
    }

    @Test fun vbwSetsTheAveragingCount() {
        val auto = Settings(rbwKhz = 54.0, averages = 16)
        assertEquals(16, auto.effectiveAverages())
        assertEquals(54_000.0 / 16, auto.vbwActualHz(), 1e-6)
        for (r in Settings.VBW_RATIOS) {
            val s = auto.copy(vbwKhz = 54.0 / r)
            assertEquals(r, s.effectiveAverages())
            assertEquals(54_000.0 / r, s.vbwActualHz(), 1e-6)
        }
        assertEquals(256, auto.copy(vbwKhz = 0.01).effectiveAverages()) // capped
        assertEquals(1, auto.copy(vbwKhz = 300.0).effectiveAverages())  // VBW > RBW = no averaging
        assertNull(auto.copy(vbwKhz = 3.0).validate())
        assertNotNull(auto.copy(vbwKhz = 0.0).validate())
    }
}
