package com.lteduenv.rxcheck.core.rtl

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min

/**
 * Rafael Micro R820T / R828D tuner, including RTL-SDR Blog V4 input switching.
 *
 * Register values and the init/calibration sequence follow the Apache-2.0
 * webrtlsdr driver (Jacobo Tarrio, Google); the IF bandwidth selection in
 * [setBandwidth] follows librtlsdr (GPL-2.0-or-later). See THIRD_PARTY.md.
 *
 * [fast] tuning differs only in how registers reach the chip:
 *  - a write whose value equals the last successfully written value is skipped
 *    (these are host-written configuration registers the chip never changes);
 *  - PLL registers 0x14-0x16 go out as one 3-byte I2C burst instead of three writes.
 * The values programmed, the VCO fine-tune read and the PLL lock check are the
 * same as the reference sequence. Initialization and filter calibration always
 * use the reference (unskipped) sequence.
 */
class R82xx(
    private val com: RtlCom,
    val chip: Chip,
    val isBlogV4: Boolean,
    var fast: Boolean,
) {
    enum class Chip(val i2c: Int, val vcoPowerRef: Int) { R820T(0x34, 2), R828D(0x74, 1) }

    private val shadow = IntArray(32)
    private val known = BooleanArray(32)
    private var forceWrites = true
    private var input = -1
    var xtalHz = XTAL_HZ
    /** Tuner IF. 3.57 MHz with the default 6 MHz filter; lower after [setBandwidth]. */
    var ifHz = IF_HZ
        private set
    var pllLocked = false
        private set
    /** Filter calibration ran without PLL lock; filter bandwidth may be off. */
    var calibrationUnlocked = false
        private set

    private val muxCfgs = if (isBlogV4) MUX_BLOG_V4 else MUX_STD

    /** Writes the default registers, runs filter calibration. Repeater must be open. */
    fun init() {
        com.setDemodReg(1, 0xb1, 0x1a, 1) // disable zero-IF, enable DC/IQ estimation
        com.setDemodReg(0, 0x08, 0x4d, 1) // ADC_Q only
        com.setDemodReg(1, 0x15, 0x01, 1) // spectrum inversion
        forceWrites = true
        known.fill(false)
        input = -1
        writeBurst(0x05, INIT_REGS)
        initElectronics()
        forceWrites = false
    }

    /** Tunes the LO. Returns the actual RF frequency in Hz. Repeater must be open. */
    fun setFrequency(freqHz: Long): Double {
        val upconvert = if (isBlogV4 && freqHz < 28_800_000L) 28_800_000L else 0L
        val lo = freqHz + upconvert + ifHz
        setMux(lo)
        val actualLo = setPll(lo)
        if (isBlogV4) {
            val want = if (freqHz <= 28_800_000L) 2 else if (freqHz < 250_000_000L) 1 else 0
            if (want != input) {
                input = want
                when (want) {
                    0 -> { writeMask(0x06, 0x00, 0x08); writeMask(0x05, 0x00, 0x60) }
                    1 -> { writeMask(0x06, 0x00, 0x08); writeMask(0x05, 0x60, 0x60) }
                    else -> { writeMask(0x06, 0x08, 0x08); writeMask(0x05, 0x20, 0x60) }
                }
                com.setGpioOutput(5)
                com.setGpioBit(5, want != 2) // upconverter path off except HF
            }
        } else if (chip == Chip.R828D) {
            val want = if (freqHz > 345_000_000L) 0 else 1
            if (want != input) {
                input = want
                writeMask(0x05, if (want == 0) 0x00 else 0x60, 0x60)
            }
        }
        return actualLo - ifHz - upconvert
    }

    /**
     * IF filter for the given bandwidth (normally the sample rate), as librtlsdr's
     * r82xx_set_bandwidth does. At 2.4 MS/s this narrows the IF filter from 6 MHz
     * to about 2.4 MHz, so strong signals just outside the capture (e.g. B8 DL
     * above the B8 RX band) reach the ADC attenuated. Returns the new IF in Hz;
     * the demodulator IF must be set to it. Repeater must be open.
     */
    fun setBandwidth(bwHz: Int): Long {
        val reg0a: Int
        var reg0b: Int
        var intFreq: Long
        if (bwHz > 7_000_000) {
            reg0a = 0x10; reg0b = 0x0b; intFreq = 4_570_000
        } else if (bwHz > 6_000_000) {
            reg0a = 0x10; reg0b = 0x2a; intFreq = 4_570_000
        } else if (bwHz > IF_LPF_HZ[0] + FILT_HP_BW1 + FILT_HP_BW2) {
            reg0a = 0x10; reg0b = 0x6b; intFreq = 3_570_000
        } else {
            reg0a = 0x00; reg0b = 0x80; intFreq = 2_300_000
            var bw = bwHz
            var realBw = 0
            if (bw > IF_LPF_HZ[0] + FILT_HP_BW1) {
                bw -= FILT_HP_BW2; intFreq += FILT_HP_BW2; realBw += FILT_HP_BW2
            } else reg0b = reg0b or 0x20
            if (bw > IF_LPF_HZ[0]) {
                bw -= FILT_HP_BW1; intFreq += FILT_HP_BW1; realBw += FILT_HP_BW1
            } else reg0b = reg0b or 0x40
            var i = 0
            while (i < IF_LPF_HZ.size && bw <= IF_LPF_HZ[i]) i++
            i--
            reg0b = reg0b or (15 - i)
            realBw += IF_LPF_HZ[i]
            intFreq -= realBw / 2
        }
        writeMask(0x0a, reg0a, 0x10)
        writeMask(0x0b, reg0b, 0xef)
        ifHz = intFreq
        return intFreq
    }

    /** Manual gain step 0..15: LNA and mixer gain index (roughly 3.5 dB per step). */
    fun setManualGain(step: Int) {
        val s = step.coerceIn(0, 15)
        writeMask(0x05, 0x10, 0x10) // LNA manual
        writeMask(0x07, 0x00, 0x10) // mixer manual
        writeMask(0x0c, 0x08, 0x9f) // VGA manual, fixed
        writeMask(0x05, s, 0x0f)
        writeMask(0x07, s, 0x0f)
    }

    fun setAutoGain() {
        writeMask(0x05, 0x00, 0x10)
        writeMask(0x07, 0x10, 0x10)
        writeMask(0x0c, 0x0b, 0x9f)
    }

    fun standby() {
        forceWrites = true
        writeMask(0x06, 0xb1, 0xff); writeMask(0x05, 0xb3, 0xff); writeMask(0x07, 0x3a, 0xff)
        writeMask(0x08, 0x40, 0xff); writeMask(0x09, 0xc0, 0xff); writeMask(0x0a, 0x3a, 0xff)
        writeMask(0x0c, 0x35, 0xff); writeMask(0x0f, 0x68, 0xff); writeMask(0x11, 0x03, 0xff)
        writeMask(0x17, 0xf4, 0xff); writeMask(0x19, 0x0c, 0xff)
    }

    /** Last value written to a register (for diagnostics and tests). */
    fun shadowReg(reg: Int) = shadow[reg]

    private fun setMux(loHz: Long) {
        val mhz = loHz / 1e6
        var i = 0
        while (i < muxCfgs.size - 1 && mhz >= muxCfgs[i + 1].fromMhz) i++
        val c = muxCfgs[i]
        writeMask(0x17, c.openDrain, 0x08)
        writeMask(0x1a, c.rfMux, 0xc3)
        writeMask(0x1b, c.tfBand, 0xff)
        writeMask(0x10, 0x00, 0x0b)
        writeMask(0x08, 0x00, 0x3f)
        writeMask(0x09, 0x00, 0x3f)
    }

    private fun setPll(freqHz: Long): Double {
        val pllRef = xtalHz
        writeMask(0x10, 0x00, 0x10) // reference divider 1:1
        writeMask(0x1a, 0x00, 0x0c) // PLL autotune 128 kHz
        writeMask(0x12, 0x80, 0xe0) // VCO core power 4

        var divNum = min(6, floor(ln(1_770_000_000.0 / freqHz) / ln(2.0)).toInt())
        require(divNum >= 0) { "주파수 범위 초과: $freqHz Hz" }
        val mixDiv = 1 shl (divNum + 1)
        val status = readRegs(5)
        val vcoFineTune = (status[4] and 0x30) shr 4
        if (vcoFineTune > chip.vcoPowerRef) divNum-- else if (vcoFineTune < chip.vcoPowerRef) divNum++
        writeMask(0x10, divNum shl 5, 0xe0)

        val vcoHz = freqHz * mixDiv
        val nint = (vcoHz / (2 * pllRef)).toInt()
        val vcoFra = vcoHz % (2 * pllRef)
        require(nint in 13..127) { "PLL 분주값 범위 초과: $freqHz Hz" }
        val ni = (nint - 13) / 4
        val si = (nint - 13) % 4
        val sdm = min(65535L, 32768L * vcoFra / pllRef).toInt()
        val reg14 = ni + (si shl 6)

        if (fast) {
            writeMask(0x12, if (vcoFra == 0L) 0x08 else 0x00, 0x08) // SDM dither
            val values = intArrayOf(reg14, sdm and 0xff, sdm shr 8)
            if ((0..2).any { !known[0x14 + it] || shadow[0x14 + it] != values[it] }) writeBurst(0x14, values)
        } else {
            writeMask(0x14, reg14, 0xff)
            writeMask(0x12, if (vcoFra == 0L) 0x08 else 0x00, 0x08)
            writeMask(0x16, sdm shr 8, 0xff)
            writeMask(0x15, sdm and 0xff, 0xff)
        }

        pllLocked = false
        for (attempt in 0..1) {
            if (attempt == 1) Thread.sleep(1)
            if (readRegs(3)[2] and 0x40 != 0) { pllLocked = true; break }
            if (attempt == 0) writeMask(0x12, 0x60, 0xe0) // VCO core power 3, retry
        }
        writeMask(0x1a, 0x08, 0x08) // PLL autotune 8 kHz
        return 2.0 * pllRef * (nint + sdm / 65536.0) / mixDiv
    }

    private fun calibrateFilter(): Int {
        var first = true
        while (true) {
            writeMask(0x0b, 0x60, 0x60)
            writeMask(0x0f, 0x04, 0x04)
            writeMask(0x10, 0x00, 0x03)
            setPll(56_000_000L)
            if (!pllLocked) calibrationUnlocked = true
            writeMask(0x0b, 0x10, 0x10)
            writeMask(0x0b, 0x00, 0x10)
            writeMask(0x0f, 0x00, 0x04)
            var cap = readRegs(5)[4] and 0x0f
            if (cap == 0x0f) cap = 0
            if (cap == 0 || !first) return cap
            first = false
        }
    }

    private fun initElectronics() {
        writeMask(0x0c, 0x00, 0x0f)
        writeMask(0x13, 0x31, 0x3f)
        writeMask(0x1d, 0x00, 0x38)
        val cap = calibrateFilter()
        writeMask(0x0a, 0x10 or cap, 0x1f)
        writeMask(0x0b, 0x6b, 0xef)
        writeMask(0x07, 0x00, 0x80)
        writeMask(0x06, 0x10, 0x30)
        writeMask(0x1e, 0x40, 0x60)
        writeMask(0x05, 0x00, 0x80)
        writeMask(0x1f, 0x00, 0x80)
        writeMask(0x0f, 0x00, 0x80)
        writeMask(0x19, 0x60, 0x60)
        writeMask(0x1d, 0xe5, 0xc7)
        writeMask(0x1c, 0x24, 0xf8)
        writeMask(0x0d, 0x53, 0xff)
        writeMask(0x0e, 0x75, 0xff)
        writeMask(0x05, 0x00, 0x60)
        writeMask(0x06, 0x00, 0x08)
        writeMask(0x11, 0x38, 0x08)
        writeMask(0x17, 0x30, 0x30)
        writeMask(0x0a, 0x40, 0x60)
        writeMask(0x1d, 0x00, 0x38)
        writeMask(0x1c, 0x00, 0x04)
        writeMask(0x06, 0x00, 0x40)
        writeMask(0x1a, 0x30, 0x30)
        writeMask(0x1d, 0x18, 0x38)
        writeMask(0x1c, 0x24, 0x04)
        writeMask(0x1e, 0x0d, 0x1f)
        writeMask(0x1a, 0x20, 0x30)
    }

    private fun writeMask(reg: Int, value: Int, mask: Int) {
        val v = (shadow[reg] and mask.inv()) or (value and mask)
        if (fast && !forceWrites && known[reg] && shadow[reg] == v) return
        known[reg] = false
        com.i2cWrite(chip.i2c, byteArrayOf(reg.toByte(), v.toByte()))
        shadow[reg] = v and 0xff
        known[reg] = true
    }

    /** Consecutive registers in one I2C transaction (7 data bytes max per message). */
    private fun writeBurst(start: Int, values: IntArray) {
        var i = 0
        while (i < values.size) {
            val n = min(7, values.size - i)
            val msg = ByteArray(n + 1)
            msg[0] = (start + i).toByte()
            for (k in 0 until n) { msg[k + 1] = values[i + k].toByte(); known[start + i + k] = false }
            com.i2cWrite(chip.i2c, msg)
            for (k in 0 until n) { shadow[start + i + k] = values[i + k] and 0xff; known[start + i + k] = true }
            i += n
        }
    }

    /** Status registers from 0x00; the chip returns them bit-reversed. */
    private fun readRegs(len: Int): IntArray {
        val raw = com.i2cRead(chip.i2c, 0x00, len)
        return IntArray(len) { bitRev(raw[it].toInt() and 0xff) }
    }

    private class MuxCfg(val fromMhz: Double, val openDrain: Int, val rfMux: Int, val tfBand: Int)

    companion object {
        const val XTAL_HZ = 28_800_000L
        const val IF_HZ = 3_570_000L
        const val CHIP_ID = 0x69
        private const val FILT_HP_BW1 = 350_000
        private const val FILT_HP_BW2 = 380_000
        /** IF low-pass corners selectable in reg 0x0b[3:0] (15 - index). */
        private val IF_LPF_HZ = intArrayOf(1_700_000, 1_600_000, 1_550_000, 1_450_000, 1_200_000,
            900_000, 700_000, 550_000, 450_000, 350_000)

        /** Registers 0x05..0x1f power-on values. */
        val INIT_REGS = intArrayOf(
            0x83, 0x32, 0x75, 0xc0, 0x40, 0xd6, 0x6c, 0xf5, 0x63, 0x75, 0x68,
            0x6c, 0x83, 0x80, 0x00, 0x0f, 0x00, 0xc0, 0x30, 0x48, 0xcc, 0x60,
            0x00, 0x54, 0xae, 0x4a, 0xc0,
        )

        fun bitRev(b: Int): Int {
            var r = 0
            for (i in 0 until 8) if (b and (1 shl i) != 0) r = r or (1 shl (7 - i))
            return r
        }

        private val MUX_STD = listOf(
            MuxCfg(0.0, 0x08, 0x02, 0xdf), MuxCfg(50.0, 0x08, 0x02, 0xbe),
            MuxCfg(55.0, 0x08, 0x02, 0x8b), MuxCfg(60.0, 0x08, 0x02, 0x7b),
            MuxCfg(65.0, 0x08, 0x02, 0x69), MuxCfg(70.0, 0x08, 0x02, 0x58),
            MuxCfg(75.0, 0x00, 0x02, 0x44), MuxCfg(90.0, 0x00, 0x02, 0x34),
            MuxCfg(110.0, 0x00, 0x02, 0x24), MuxCfg(140.0, 0x00, 0x02, 0x14),
            MuxCfg(180.0, 0x00, 0x02, 0x13), MuxCfg(250.0, 0x00, 0x02, 0x11),
            MuxCfg(280.0, 0x00, 0x02, 0x00), MuxCfg(310.0, 0x00, 0x41, 0x00),
            MuxCfg(588.0, 0x00, 0x40, 0x00),
        )

        private val MUX_BLOG_V4 = listOf(
            MuxCfg(0.0, 0x00, 0x02, 0xdf), MuxCfg(2.2, 0x08, 0x02, 0xdf),
            MuxCfg(50.0, 0x08, 0x02, 0xbe), MuxCfg(55.0, 0x08, 0x02, 0x8b),
            MuxCfg(60.0, 0x08, 0x02, 0x7b), MuxCfg(65.0, 0x08, 0x02, 0x69),
            MuxCfg(70.0, 0x08, 0x02, 0x58), MuxCfg(75.0, 0x08, 0x02, 0x44),
            MuxCfg(85.0, 0x00, 0x02, 0x44), MuxCfg(90.0, 0x00, 0x02, 0x34),
            MuxCfg(110.0, 0x00, 0x02, 0x24), MuxCfg(112.0, 0x08, 0x02, 0x24),
            MuxCfg(140.0, 0x08, 0x02, 0x14), MuxCfg(172.0, 0x00, 0x02, 0x14),
            MuxCfg(180.0, 0x00, 0x02, 0x13), MuxCfg(242.0, 0x08, 0x02, 0x13),
            MuxCfg(250.0, 0x08, 0x02, 0x11), MuxCfg(280.0, 0x08, 0x02, 0x00),
            MuxCfg(310.0, 0x08, 0x41, 0x00), MuxCfg(588.0, 0x08, 0x40, 0x00),
        )
    }
}
