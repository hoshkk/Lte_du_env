package com.lteduenv.rxcheck.core.rtl

import com.lteduenv.rxcheck.core.usb.UsbIo
import com.lteduenv.rxcheck.core.usb.UsbIoException

/** Counters for one sweep; control transfers dominate retune time on phones. */
class UsbStats {
    var controlOut = 0
    var controlIn = 0
    var controlNanos = 0L
    var bulkBytes = 0L
    var bulkNanos = 0L

    fun reset() {
        controlOut = 0; controlIn = 0; controlNanos = 0; bulkBytes = 0; bulkNanos = 0
    }

    fun snapshot() = UsbStats().also {
        it.controlOut = controlOut; it.controlIn = controlIn; it.controlNanos = controlNanos
        it.bulkBytes = bulkBytes; it.bulkNanos = bulkNanos
    }
}

/**
 * Register access to the RTL2832U over vendor control transfers.
 *
 * Multi-byte register values are sent most significant byte first (as librtlsdr
 * does). Every demodulator write is followed by a dummy read, which the chip
 * needs before the next demodulator access.
 */
class RtlCom(private val io: UsbIo, private val clock: () -> Long = System::nanoTime) {
    val stats = UsbStats()

    /**
     * Keep the tuner I2C repeater open between tuner accesses. Opening and closing
     * it around every retune costs two demodulator writes (4 control transfers).
     */
    var repeaterHold = false
        set(value) {
            field = value
            if (!value) setRepeater(false)
        }

    /** Demod page 1 reg 0x01 bit 0x08; -1 unknown. Updated on every write of that register. */
    var repeaterState = -1
        private set

    val manufacturer: String? get() = io.manufacturer
    val product: String? get() = io.product

    fun write(value: Int, index: Int, data: ByteArray) {
        val t = clock()
        val rc = io.controlOut(value, index, data, data.size)
        stats.controlNanos += clock() - t
        stats.controlOut++
        if (rc != data.size) throw UsbIoException(
            "USB 제어 쓰기 실패 (value=0x%04x index=0x%04x rc=%d)".format(value, index, rc))
    }

    fun read(value: Int, index: Int, length: Int): ByteArray {
        val buf = ByteArray(length)
        val t = clock()
        val rc = io.controlIn(value, index, buf, length)
        stats.controlNanos += clock() - t
        stats.controlIn++
        if (rc != length) throw UsbIoException(
            "USB 제어 읽기 실패 (value=0x%04x index=0x%04x rc=%d)".format(value, index, rc))
        return buf
    }

    fun setUsbReg(addr: Int, value: Int, len: Int) = write(addr, BLOCK_USB shl 8 or WRITE, be(value, len))

    fun setSysReg(addr: Int, value: Int) = write(addr, BLOCK_SYS shl 8 or WRITE, be(value, 1))

    fun getSysReg(addr: Int): Int = read(addr, BLOCK_SYS shl 8, 1)[0].toInt() and 0xff

    fun setDemodReg(page: Int, addr: Int, value: Int, len: Int) {
        val repeaterReg = page == 1 && addr == 0x01 && len == 1
        if (repeaterReg) repeaterState = -1
        write((addr shl 8) or 0x20, page or WRITE, be(value, len))
        read(0x0120, 0x0a, 1)
        if (repeaterReg) repeaterState = if (value and 0x08 != 0) 1 else 0
    }

    fun setRepeater(on: Boolean) {
        val want = if (on || repeaterHold) 1 else 0
        if (repeaterState == want) return
        setDemodReg(1, 0x01, if (want == 1) 0x18 else 0x10, 1)
    }

    fun i2cWrite(addr: Int, data: ByteArray) = write(addr, BLOCK_I2C shl 8 or WRITE, data)

    fun i2cRead(addr: Int, reg: Int, length: Int): ByteArray {
        write(addr, BLOCK_I2C shl 8 or WRITE, byteArrayOf(reg.toByte()))
        return read(addr, BLOCK_I2C shl 8, length)
    }

    /** I2C read without first writing a register address (device-defined start). */
    fun i2cReadDirect(addr: Int, length: Int): ByteArray = read(addr, BLOCK_I2C shl 8, length)

    fun setGpioOutput(gpio: Int) {
        val bit = 1 shl gpio
        setSysReg(GPD, getSysReg(GPD) and bit.inv())
        setSysReg(GPOE, getSysReg(GPOE) or bit)
    }

    fun setGpioBit(gpio: Int, on: Boolean) {
        val bit = 1 shl gpio
        val r = getSysReg(GPO)
        setSysReg(GPO, if (on) r or bit else r and bit.inv())
    }

    /** Clears the sample FIFO so the next bulk read starts with fresh samples. */
    fun resetFifo() {
        setUsbReg(USB_EPA_CTL, 0x1002, 2)
        setUsbReg(USB_EPA_CTL, 0x0000, 2)
    }

    fun bulkRead(buffer: ByteArray, length: Int, timeoutMs: Int = 500): Int {
        val t = clock()
        val n = io.bulkIn(buffer, length, timeoutMs)
        stats.bulkNanos += clock() - t
        if (n <= 0) throw UsbIoException("USB 샘플 수신 실패 (rc=$n)")
        stats.bulkBytes += n
        return n
    }

    companion object {
        const val WRITE = 0x10
        const val BLOCK_USB = 1
        const val BLOCK_SYS = 2
        const val BLOCK_I2C = 6
        const val USB_SYSCTL = 0x2000
        const val USB_EPA_CTL = 0x2148
        const val USB_EPA_MAXPKT = 0x2158
        const val DEMOD_CTL = 0x3000
        const val GPO = 0x3001
        const val GPOE = 0x3003
        const val GPD = 0x3004
        const val DEMOD_CTL_1 = 0x300b

        fun be(value: Int, len: Int): ByteArray = when (len) {
            1 -> byteArrayOf(value.toByte())
            2 -> byteArrayOf((value shr 8).toByte(), value.toByte())
            else -> throw IllegalArgumentException("len $len")
        }
    }
}
