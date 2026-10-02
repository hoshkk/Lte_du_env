package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.rtl.R82xx
import com.lteduenv.rxcheck.core.rtl.RtlSdr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DriverTest {
    private val sweepFreqs = listOf(
        // B8 RX 15 MHz span segment centres, B3 RX, mux/divider boundaries and back.
        903_000_000L, 904_800_000L, 906_600_000L, 908_400_000L, 910_200_000L, 912_000_000L,
        913_800_000L, 915_600_000L, 1_735_900_000L, 1_737_700_000L, 1_765_100_000L,
        600_000_000L, 300_000_000L, 100_000_000L, 909_300_000L,
    )

    @Test fun detectsBlogV4R828D() {
        val usb = FakeRtlUsb()
        val sdr = RtlSdr.open(usb)
        assertEquals(R82xx.Chip.R828D, sdr.tuner.chip)
        assertTrue(sdr.tuner.isBlogV4)
        assertFalse(sdr.tuner.calibrationUnlocked)
    }

    @Test fun detectsR820T() {
        val sdr = RtlSdr.open(FakeRtlUsb(tunerAddr = 0x34, manufacturer = "Realtek", product = "RTL2838UHIDIR"))
        assertEquals(R82xx.Chip.R820T, sdr.tuner.chip)
        assertFalse(sdr.tuner.isBlogV4)
    }

    @Test fun programmedFrequencyMatchesRequest() {
        val usb = FakeRtlUsb()
        val sdr = RtlSdr.open(usb)
        for (f in sweepFreqs) {
            assertTrue(sdr.tune(f))
            val lo = usb.programmedLoHz()
            // SDM resolution: 2*28.8 MHz / 65536 / mixDiv < 900 Hz
            assertTrue("LO for $f: $lo", abs(lo - (f + sdr.tuner.ifHz)) < 1000)
        }
    }

    /** Fast tuning must leave the chip in exactly the state the reference sequence does. */
    @Test fun fastAndReferenceTuningProgramIdenticalRegisters() {
        val fastUsb = FakeRtlUsb(); val refUsb = FakeRtlUsb()
        val fast = RtlSdr.open(fastUsb, fastTune = true)
        val ref = RtlSdr.open(refUsb, fastTune = false)
        for (f in sweepFreqs) {
            fast.tune(f); ref.tune(f)
            assertArrayEquals("regs after $f", refUsb.tunerRegs, fastUsb.tunerRegs)
        }
    }

    @Test fun lockRetryAlsoMatchesReference() {
        val fastUsb = FakeRtlUsb(lockOnFirstRead = false); val refUsb = FakeRtlUsb(lockOnFirstRead = false)
        val fast = RtlSdr.open(fastUsb, fastTune = true)
        val ref = RtlSdr.open(refUsb, fastTune = false)
        for (f in sweepFreqs) {
            assertTrue(fast.tune(f)); assertTrue(ref.tune(f))
            assertArrayEquals(refUsb.tunerRegs, fastUsb.tunerRegs)
        }
    }

    @Test fun fastRetuneUsesFewControlTransfersAndNoRepeaterToggles() {
        val usb = FakeRtlUsb()
        val sdr = RtlSdr.open(usb, fastTune = true)
        sdr.tune(903_000_000L)
        val out0 = usb.outCount; val in0 = usb.inCount; val d0 = usb.demodWrites
        sdr.tune(904_800_000L)
        val transfers = usb.outCount - out0 + usb.inCount - in0
        // autotune 128k, status read (2), PLL burst, lock read (2), autotune 8k
        assertEquals(7, transfers)
        assertEquals(0, usb.demodWrites - d0)
        assertTrue(usb.repeaterOpen)
    }

    @Test fun referenceRetuneTogglesRepeater() {
        val usb = FakeRtlUsb()
        val sdr = RtlSdr.open(usb, fastTune = false)
        sdr.tune(903_000_000L)
        val out0 = usb.outCount; val in0 = usb.inCount; val d0 = usb.demodWrites
        sdr.tune(904_800_000L)
        assertEquals(2, usb.demodWrites - d0)
        assertFalse(usb.repeaterOpen)
        assertTrue(usb.outCount - out0 + usb.inCount - in0 > 7)
    }

    @Test fun unlockedPllIsReported() {
        val usb = FakeRtlUsb()
        val sdr = RtlSdr.open(usb)
        usb.lockOnFirstRead = false
        // Fake locks on every second read; force failure by making both reads odd.
        val never = object : com.lteduenv.rxcheck.core.usb.UsbIo by usb {
            override fun controlIn(value: Int, index: Int, buffer: ByteArray, length: Int): Int {
                val n = usb.controlIn(value, index, buffer, length)
                if (index == 0x600 && length == 3) buffer[2] = 0
                return n
            }
        }
        val sdr2 = RtlSdr.open(never)
        assertFalse(sdr2.tune(905_000_000L))
        assertTrue(sdr.tune(905_000_000L))
    }

    @Test fun captureConvertsSamples() {
        val usb = FakeRtlUsb()
        val sdr = RtlSdr.open(usb)
        sdr.tune(909_300_000L)
        val iq = FloatArray(2 * 4096)
        sdr.capture(iq, 2048)
        assertTrue(iq.all { abs(it - (127 - 127.4f) / 128f) < 1e-6 })
    }

    @Test fun closeReleasesRepeater() {
        val usb = FakeRtlUsb()
        val sdr = RtlSdr.open(usb)
        sdr.close()
        assertFalse(usb.repeaterOpen)
    }
}
