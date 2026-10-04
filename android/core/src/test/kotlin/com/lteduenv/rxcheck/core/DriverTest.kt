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
        // The first status reads verify pointer-less reads against normal ones.
        for (f in listOf(903_000_000L, 904_800_000L, 906_600_000L)) sdr.tune(f)
        assertTrue(sdr.tuner.pointerlessReads)
        val out0 = usb.outCount; val in0 = usb.inCount; val d0 = usb.demodWrites
        sdr.tune(908_400_000L)
        val transfers = usb.outCount - out0 + usb.inCount - in0
        // autotune 128k, status read, PLL burst, lock read, autotune 8k
        assertEquals(5, transfers)
        assertEquals(0, usb.demodWrites - d0)
        assertTrue(usb.repeaterOpen)
    }

    /** A chip whose reads follow the register pointer must fall back to pointer writes. */
    @Test fun pointerlessReadsFallBackSafely() {
        val usb = FakeRtlUsb(readStartsAtZero = false)
        val refUsb = FakeRtlUsb(readStartsAtZero = false)
        val sdr = RtlSdr.open(usb, fastTune = true)
        val ref = RtlSdr.open(refUsb, fastTune = false)
        for (f in sweepFreqs) {
            assertTrue(sdr.tune(f)); assertTrue(ref.tune(f))
            assertArrayEquals(refUsb.tunerRegs, usb.tunerRegs)
        }
        assertFalse(sdr.tuner.pointerlessReads)
        val out0 = usb.outCount; val in0 = usb.inCount
        sdr.tune(904_800_000L); sdr.tune(906_600_000L)
        assertEquals(7, (usb.outCount - out0 + usb.inCount - in0) / 2)
    }

    @Test fun pointerlessReadsKeepRegistersIdentical() {
        val fastUsb = FakeRtlUsb(lockOnFirstRead = false); val refUsb = FakeRtlUsb(lockOnFirstRead = false)
        val fast = RtlSdr.open(fastUsb, fastTune = true)
        val ref = RtlSdr.open(refUsb, fastTune = false)
        repeat(2) { for (f in sweepFreqs) {
            assertTrue(fast.tune(f)); assertTrue(ref.tune(f))
            assertArrayEquals(refUsb.tunerRegs, fastUsb.tunerRegs)
        } }
        assertTrue(fast.tuner.pointerlessReads)
    }

    @Test fun discardAndCaptureShareBulkReads() {
        val usb = FakeRtlUsb()
        val sdr = RtlSdr.open(usb)
        sdr.tune(909_300_000L)
        val before = usb.bulkCalls
        sdr.capture(FloatArray(2 * 1024), 2048) // 4096 + 2048 bytes
        assertEquals(1, usb.bulkCalls - before)
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

    // ---- USB speed paths, checked against the fake dongle (not real hardware) ----------

    /** Samples from before the FIFO reset (previous frequency) never reach the analysis buffer. */
    @Test fun staleSamplesAreDroppedEvenWhenDiscardAndCaptureShareReads() {
        for (outLen in listOf(2 * 1024, 2 * 1000, 2 * 16384, 2 * 64)) {
            val usb = FakeRtlUsb(staleBytesAfterReset = 2048 * 2)
            val sdr = RtlSdr.open(usb)
            for (f in listOf(903_000_000L, 904_800_000L, 906_600_000L)) {
                sdr.tune(f)
                val iq = FloatArray(outLen)
                sdr.capture(iq, 2048)
                assertTrue(iq.none { abs(it - (FakeRtlUsb.STALE - 127.4f) / 128f) < 1e-6 })
                assertEquals(0, usb.i2cWritesSinceFifoReset) // FIFO reset came after the last tuner write
            }
        }
        // Control: with too short a discard the stale bytes do show up, so the check above has teeth.
        val usb = FakeRtlUsb(staleBytesAfterReset = 2048 * 2)
        val sdr = RtlSdr.open(usb)
        sdr.tune(903_000_000L)
        val iq = FloatArray(2 * 1024)
        sdr.capture(iq, 1024)
        assertTrue(iq.any { abs(it - (FakeRtlUsb.STALE - 127.4f) / 128f) < 1e-6 })
    }

    /** Reopening (USB reconnect) starts the pointer-less read check from scratch. */
    @Test fun reopenResetsPointerlessVerification() {
        val first = FakeRtlUsb()
        val a = RtlSdr.open(first)
        for (f in sweepFreqs.take(4)) a.tune(f)
        assertTrue(a.tuner.pointerlessReads)
        a.close()
        // Same device again: not trusted until verified again.
        val b = RtlSdr.open(first)
        assertFalse(b.tuner.pointerlessReads)
        for (f in sweepFreqs.take(4)) assertTrue(b.tune(f))
        assertTrue(b.tuner.pointerlessReads)
        // A different device whose reads follow the pointer: must fall back, registers as reference.
        val other = FakeRtlUsb(readStartsAtZero = false)
        val refUsb = FakeRtlUsb(readStartsAtZero = false)
        val c = RtlSdr.open(other)
        val ref = RtlSdr.open(refUsb, fastTune = false)
        for (f in sweepFreqs) {
            assertTrue(c.tune(f)); assertTrue(ref.tune(f))
            assertArrayEquals(refUsb.tunerRegs, other.tunerRegs)
        }
        assertFalse(c.tuner.pointerlessReads)
    }

    /** Fails every [every]-th control transfer once and every [bulkEvery]-th bulk read once. */
    private class FlakyUsb(val inner: FakeRtlUsb, val every: Int = 13, val bulkEvery: Int = 5) :
        com.lteduenv.rxcheck.core.usb.UsbIo by inner {
        var n = 0; var b = 0
        override fun controlOut(value: Int, index: Int, data: ByteArray, length: Int): Int =
            if (++n % every == 0) -1 else inner.controlOut(value, index, data, length)
        override fun controlIn(value: Int, index: Int, buffer: ByteArray, length: Int): Int =
            if (++n % every == 0) -1 else inner.controlIn(value, index, buffer, length)
        override fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int =
            if (++b % bulkEvery == 0) -1 else inner.bulkIn(buffer, length, timeoutMs)
    }

    /** Occasional dropped transfers are retried: same registers, clean samples, sweep completes. */
    @Test fun transientUsbErrorsAreRetried() {
        val flakyUsb = FakeRtlUsb(staleBytesAfterReset = 4096); val refUsb = FakeRtlUsb()
        val flaky = RtlSdr.open(FlakyUsb(flakyUsb), fastTune = true)
        val ref = RtlSdr.open(refUsb, fastTune = false)
        for (f in sweepFreqs) {
            assertTrue(flaky.tune(f)); assertTrue(ref.tune(f))
            assertArrayEquals(refUsb.tunerRegs, flakyUsb.tunerRegs)
            val iq = FloatArray(2 * 4096)
            flaky.capture(iq, 2048)
            assertTrue(iq.all { abs(it - (127 - 127.4f) / 128f) < 1e-6 }) // no stale bytes after a restarted capture
        }
        assertTrue(flaky.takeStats().retries > 0)
    }

    /** A dongle that stays silent is still an error (and the app reopens it). */
    @Test fun persistentUsbErrorStillFails() {
        val usb = FakeRtlUsb()
        val sdr = RtlSdr.open(usb)
        val dead = object : com.lteduenv.rxcheck.core.usb.UsbIo by usb {
            override fun controlOut(value: Int, index: Int, data: ByteArray, length: Int) = -1
        }
        val failed = runCatching { RtlSdr.open(dead) }.exceptionOrNull()
        assertTrue(failed is com.lteduenv.rxcheck.core.usb.UsbIoException)
        assertTrue(sdr.tune(909_300_000L))
    }
}
