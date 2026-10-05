package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.rtl.R82xx
import com.lteduenv.rxcheck.core.rtl.RtlSdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarmonicTest {
    /** Above 1766 MHz the PLL runs at LO/5 (librtlsdr fork's harmonic mode); below, directly. */
    @Test fun fifthHarmonicAboveDirectLimit() {
        // vcoFineTune = R820T's VCO power reference (2), so the fake's LO readback needs no divider shift.
        val usb = FakeRtlUsb(tunerAddr = 0x34, manufacturer = "Realtek", product = "RTL2838UHIDIR", vcoFineTune = 2)
        val sdr = RtlSdr.open(usb)
        assertTrue(sdr.tune(1_745_000_000L))
        assertEquals(1, sdr.tuner.harmonic)
        assertEquals(1_745_000_000.0 + sdr.tuner.ifHz, usb.programmedLoHz(), 2_000.0)
        assertTrue(sdr.tune(1_845_000_000L))
        assertEquals(R82xx.HARMONIC, sdr.tuner.harmonic)
        assertEquals((1_845_000_000.0 + sdr.tuner.ifHz) / 5, usb.programmedLoHz(), 2_000.0)
        assertTrue(sdr.tune(2_140_000_000L)) // B1 downlink
        assertEquals((2_140_000_000.0 + sdr.tuner.ifHz) / 5, usb.programmedLoHz(), 2_000.0)
    }

    @Test fun settingsAllowHarmonicRangeAndFlagIt() {
        val dl = Settings(band = null, centerMhz = 1845.0, spanMhz = 30.0)
        assertNull(dl.validate())
        assertTrue(dl.usesHarmonic)
        assertFalse(Settings(band = null, centerMhz = 1745.0, spanMhz = 40.0).usesHarmonic)
        assertTrue(Settings(band = null, centerMhz = 2190.0, spanMhz = 30.0).validate() != null)
    }
}
