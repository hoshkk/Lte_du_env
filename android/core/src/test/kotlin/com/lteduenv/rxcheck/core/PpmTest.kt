package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.rtl.RtlSdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PpmTest {
    /** A crystal running +50 ppm fast: the corrected LO, counted with the nominal crystal, is 50 ppm low. */
    @Test fun correctionShiftsTheProgrammedLo() {
        val usb = FakeRtlUsb(tunerAddr = 0x34, manufacturer = "Realtek", product = "RTL2838UHIDIR")
        val sdr = RtlSdr.open(usb)
        sdr.tune(1_745_000_000L)
        val lo0 = usb.programmedLoHz()
        sdr.setPpm(50)
        sdr.tune(1_745_000_000L)
        val lo50 = usb.programmedLoHz()
        assertEquals(-50e-6 * lo0, lo50 - lo0, lo0 * 2e-6) // PLL fraction resolution allowance
        // Demod offset as librtlsdr: -50 * 2^24 / 1e6 = -838 (truncated) -> 14-bit two's complement in 0x3e/0x3f.
        val off = ((usb.demod[(1 shl 8) or 0x3e]!! shl 8) or usb.demod[(1 shl 8) or 0x3f]!!)
        assertEquals((-838) and 0x3fff, off)
        sdr.setPpm(-50)
        val offNeg = ((usb.demod[(1 shl 8) or 0x3e]!! shl 8) or usb.demod[(1 shl 8) or 0x3f]!!)
        assertEquals(838, offNeg)
    }

    @Test fun markerCalibrationMath() {
        val s = Settings()
        // Shown 52 kHz low at 1745 MHz -> crystal +30 ppm.
        assertEquals(30, s.ppmFor(1_744.948e6, 1_745.0e6))
        // Already corrected by +20: the residual adds.
        assertEquals(30, s.copy(ppm = 20).ppmFor(1_744.9826e6, 1_745.0e6))
        assertNull(s.copy(ppm = 150).validate())
        assertNotNull(s.copy(ppm = 250).validate())
    }
}
