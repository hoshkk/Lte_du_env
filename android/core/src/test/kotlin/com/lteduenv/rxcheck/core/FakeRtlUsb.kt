package com.lteduenv.rxcheck.core

import com.lteduenv.rxcheck.core.rtl.R82xx
import com.lteduenv.rxcheck.core.usb.UsbIo

/**
 * Register-level stand-in for an RTL2832U with an R828D (or R820T) behind its
 * I2C repeater. Tuner writes only land while the repeater is open, like the
 * real hardware, so a driver that forgets to open it fails the tests.
 */
class FakeRtlUsb(
    private val tunerAddr: Int = 0x74,
    override val manufacturer: String? = "RTLSDRBlog",
    override val product: String? = "Blog V4",
    var lockOnFirstRead: Boolean = true,
    var vcoFineTune: Int = 1,
    /** R82xx behaviour: reads start at register 0 regardless of the pointer. */
    var readStartsAtZero: Boolean = true,
    /**
     * Bytes still in the USB FIFO/pipeline from before the last FIFO reset
     * (i.e. from the previous frequency). They come first and read [STALE].
     */
    var staleBytesAfterReset: Int = 0,
) : UsbIo {
    private var staleLeft = 0
    /** Tuner register writes seen after the last FIFO reset (must be 0 when capturing). */
    var i2cWritesSinceFifoReset = 0
    /** I2C register pointer, for chips whose reads continue from it. */
    var pointer = 0
    var bulkCalls = 0
    val tunerRegs = IntArray(32)
    val demod = HashMap<Int, Int>()
    var repeaterOpen = false
    var outCount = 0
    var inCount = 0
    var demodWrites = 0
    val i2cWrites = ArrayList<IntArray>()
    private var lockReads = 0

    override fun controlOut(value: Int, index: Int, data: ByteArray, length: Int): Int {
        outCount++
        val bytes = IntArray(length) { data[it].toInt() and 0xff }
        when {
            index == 0x610 -> {
                if (value != tunerAddr || !repeaterOpen) return -1
                if (length > 1) {
                    for (i in 1 until length) tunerRegs[bytes[0] + i - 1] = bytes[i]
                    i2cWrites += bytes
                    i2cWritesSinceFifoReset++
                    pointer = bytes[0] + length - 1
                } else pointer = bytes[0]
            }
            index == 0x110 && value == 0x2148 && length == 2 && bytes[0] == 0x10 -> { // EPA_CTL: FIFO reset
                staleLeft = staleBytesAfterReset
                i2cWritesSinceFifoReset = 0
            }
            value and 0xff == 0x20 -> { // demod register
                demodWrites++
                val page = index and 0x0f
                val addr = value shr 8
                demod[(page shl 8) or addr] = bytes.last()
                if (page == 1 && addr == 0x01 && length == 1) repeaterOpen = bytes[0] and 0x08 != 0
            }
        }
        return length
    }

    override fun controlIn(value: Int, index: Int, buffer: ByteArray, length: Int): Int {
        inCount++
        buffer.fill(0, 0, length)
        if (index == 0x600) {
            if (value != tunerAddr || !repeaterOpen) return -1
            val raw = IntArray(5)
            raw[0] = R82xx.CHIP_ID
            // Lock depends on the programmed VCO current, not on how often status is read:
            // a "hard" PLL only locks after the driver's retry lowers the current to 3 (0x60).
            if (lockOnFirstRead || (tunerRegs[0x12] and 0xe0) == 0x60) raw[2] = R82xx.bitRev(0x40)
            if (length == 3) lockReads++
            raw[4] = R82xx.bitRev(vcoFineTune shl 4)
            val start = if (readStartsAtZero) 0 else pointer
            for (i in 0 until length) {
                val r = start + i
                buffer[i] = (if (r < 5) raw[r] else tunerRegs[r and 31]).toByte()
            }
            if (!readStartsAtZero) pointer = start + length
        }
        return length
    }

    override fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        bulkCalls++
        val stale = minOf(staleLeft, length)
        buffer.fill(STALE.toByte(), 0, stale)
        buffer.fill(127.toByte(), stale, length)
        staleLeft -= stale
        return length
    }

    /** LO frequency implied by the PLL registers (fine tune == vcoPowerRef, so no divider shift). */
    fun programmedLoHz(xtal: Long = 28_800_000L): Double {
        val divNum = (tunerRegs[0x10] shr 5) and 0x07
        val mixDiv = 1 shl (divNum + 1)
        val reg14 = tunerRegs[0x14]
        val nint = (reg14 and 0x3f) * 4 + (reg14 shr 6) + 13
        val sdm = (tunerRegs[0x16] shl 8) or tunerRegs[0x15]
        return 2.0 * xtal * (nint + sdm / 65536.0) / mixDiv
    }

    companion object {
        const val STALE = 200
    }
}
