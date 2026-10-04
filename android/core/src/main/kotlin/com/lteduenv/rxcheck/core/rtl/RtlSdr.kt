package com.lteduenv.rxcheck.core.rtl

import com.lteduenv.rxcheck.core.sweep.Receiver
import com.lteduenv.rxcheck.core.usb.UsbIo
import com.lteduenv.rxcheck.core.usb.UsbIoException
import kotlin.math.floor
import kotlin.math.min

/**
 * RTL2832U + R820T/R828D receiver.
 *
 * Demodulator setup follows librtlsdr / webrtlsdr. See [R82xx] for what
 * [fastTune] changes; in addition it keeps the tuner I2C repeater open instead
 * of toggling it around every retune.
 */
class RtlSdr private constructor(
    private val com: RtlCom,
    val tuner: R82xx,
    override val sampleRate: Int,
    /** What the tuner probe saw at each I2C address (for diagnostics). */
    val probeInfo: String = "",
) : Receiver {
    override val details: List<Pair<String, String>>
        get() = listOf(
            "USB 문자열" to "제조사 '${com.manufacturer ?: "?"}' · 제품 '${com.product ?: "?"}'",
            "튜너 탐지" to probeInfo,
            "Blog V4 처리" to if (tuner.isBlogV4) "켜짐 (입력 경로 전환 사용)" else "꺼짐",
        )

    private var raw = ByteArray(16384)
    private var ppm = 0

    /** Extra wait after the PLL reports lock, before the FIFO reset (0 = none). */
    var settleMs = 0
    var centerHz = 0L
        private set

    override val description: String
        get() = buildString {
            append(if (tuner.isBlogV4) "RTL-SDR Blog V4" else "RTL-SDR ${tuner.chip}")
            append(" · ").append(if (tuner.fast) "고속 동조" else "기본 동조")
            append(" · IF ").append(if (tuner.ifHz == R82xx.IF_HZ) "6 MHz" else "좁음")
        }

    var fastTune: Boolean
        get() = tuner.fast
        set(value) {
            tuner.fast = value
            com.repeaterHold = value
        }

    val usbStats: UsbStats get() = com.stats

    override fun tune(hz: Long): Boolean {
        require(hz in MIN_HZ..MAX_HZ) { "주파수 범위: 24–1766 MHz" }
        com.setRepeater(true)
        tuner.setFrequency(hz)
        com.setRepeater(false)
        centerHz = hz
        if (settleMs > 0) Thread.sleep(settleMs.toLong())
        return tuner.pllLocked
    }

    /**
     * A failed bulk read leaves a gap in the sample stream, so the whole capture
     * starts over (FIFO reset, discard, read) instead of stitching around it;
     * only a second failure in a row is an error.
     */
    override fun capture(out: FloatArray, discardSamples: Int): Int = try {
        captureOnce(out, discardSamples)
    } catch (e: UsbIoException) {
        com.stats.retries++
        com.stats.lastRetryError = "샘플 수신 실패 후 수집 다시 시작 (${e.message})"
        captureOnce(out, discardSamples)
    }

    private fun captureOnce(out: FloatArray, discardSamples: Int): Int {
        com.resetFifo()
        // Transition samples and the capture come in the same bulk reads; the
        // first [skip] bytes are dropped exactly as a separate discard read would.
        var skip = discardSamples * 2
        var pos = 0
        var clipped = 0
        while (pos < out.size) {
            val want = align(min(skip + out.size - pos, raw.size))
            val n = com.bulkRead(raw, want) and 1.inv()
            var i = min(skip, n)
            skip -= i
            while (i < n && pos < out.size) {
                val b = raw[i].toInt() and 0xff
                if (b == 0 || b == 255) clipped++
                out[pos++] = (b - 127.4f) / 128f
                i++
            }
        }
        return clipped
    }

    override fun takeStats(): UsbStats = com.stats.snapshot().also { com.stats.reset() }

    fun setGain(step: Int?) {
        com.setRepeater(true)
        if (step == null) tuner.setAutoGain() else tuner.setManualGain(step)
        com.setRepeater(false)
    }

    fun setPpm(value: Int) {
        ppm = value
        val offset = -floor(value * (1 shl 24) / 1e6).toInt()
        com.setDemodReg(1, 0x3e, (offset shr 8) and 0x3f, 1)
        com.setDemodReg(1, 0x3f, offset and 0xff, 1)
        tuner.xtalHz = xtal()
        setIfFrequency(tuner.ifHz)
        if (centerHz != 0L) tune(centerHz)
    }

    override fun close() {
        runCatching {
            com.repeaterHold = false
            com.setRepeater(true)
            tuner.standby()
            com.setRepeater(false)
            com.setSysReg(RtlCom.DEMOD_CTL, 0x20) // power down demod and ADCs
        }
    }

    private fun xtal(): Long = floor(RtlSdr.XTAL_HZ * (1 + ppm / 1e6)).toLong()

    private fun setIfFrequency(ifHz: Long) {
        val m = -floor(ifHz.toDouble() * (1 shl 22) / xtal()).toInt()
        com.setDemodReg(1, 0x19, (m shr 16) and 0x3f, 1)
        com.setDemodReg(1, 0x1a, (m shr 8) and 0xff, 1)
        com.setDemodReg(1, 0x1b, m and 0xff, 1)
    }

    private fun setSampleRate(rate: Int, narrowIf: Boolean): Int {
        if (narrowIf) {
            com.setRepeater(true)
            tuner.setBandwidth(rate)
            com.setRepeater(false)
        }
        var ratio = floor(xtal().toDouble() * (1 shl 22) / rate).toLong().toInt()
        ratio = ratio and 0x0ffffffc
        com.setDemodReg(1, 0x9f, (ratio shr 16) and 0xffff, 2)
        com.setDemodReg(1, 0xa1, ratio and 0xffff, 2)
        resetDemod()
        return floor(xtal().toDouble() * (1 shl 22) / ratio).toInt()
    }

    private fun resetDemod() {
        com.setDemodReg(1, 0x01, 0x14, 1)
        com.setDemodReg(1, 0x01, 0x10, 1)
    }

    companion object {
        const val XTAL_HZ = 28_800_000L
        const val MIN_HZ = 24_000_000L
        const val MAX_HZ = 1_766_000_000L
        const val DEFAULT_RATE = 2_400_000

        private fun align(n: Int) = (n + 511) / 512 * 512

        /** Initializes the dongle and tuner. Takes a fraction of a second. */
        /**
         * [narrowIf]: librtlsdr-style IF filter matched to the sample rate (default);
         * false keeps the wider 6 MHz IF filter.
         */
        fun open(io: UsbIo, fastTune: Boolean = true, gainStep: Int? = 4,
                 sampleRate: Int = DEFAULT_RATE, narrowIf: Boolean = true): RtlSdr {
            val com = RtlCom(io)
            initBaseband(com)
            val (tuner, probe) = findTuner(com)
            com.setRepeater(true)
            tuner.init()
            com.setRepeater(false)
            val sdr = RtlSdr(com, tuner, sampleRate, probe)
            sdr.setSampleRate(sampleRate, narrowIf)
            sdr.setPpm(0)
            com.setDemodReg(0, 0x19, 0x05, 1) // RTL AGC off
            sdr.setGain(gainStep)
            sdr.fastTune = fastTune
            com.stats.reset()
            return sdr
        }

        private fun initBaseband(com: RtlCom) {
            com.setUsbReg(RtlCom.USB_SYSCTL, 0x09, 1)
            com.setUsbReg(RtlCom.USB_EPA_MAXPKT, 0x0002, 2)
            com.setUsbReg(RtlCom.USB_EPA_CTL, 0x1002, 2)
            com.setSysReg(RtlCom.DEMOD_CTL_1, 0x22)
            com.setSysReg(RtlCom.DEMOD_CTL, 0xe8)
            com.setDemodReg(1, 0x01, 0x14, 1) // soft reset
            com.setDemodReg(1, 0x01, 0x10, 1)
            com.setDemodReg(1, 0x15, 0x00, 1) // no spectrum inversion, no ACR
            for (reg in 0x16..0x1b) com.setDemodReg(1, reg, 0x00, 1) // carrier/IF offset 0
            FIR.forEachIndexed { i, v -> com.setDemodReg(1, 0x1c + i, v, 1) }
            com.setDemodReg(0, 0x19, 0x05, 1) // SDR mode, DAGC off
            com.setDemodReg(1, 0x93, 0xf0, 1) // FSM init
            com.setDemodReg(1, 0x94, 0x0f, 1)
            com.setDemodReg(1, 0x11, 0x00, 1) // DAGC off
            com.setDemodReg(1, 0x04, 0x00, 1) // AGC loop gain 0
            com.setDemodReg(0, 0x61, 0x60, 1) // PID filter off
            com.setDemodReg(0, 0x06, 0x80, 1) // default ADC datapath
            com.setDemodReg(1, 0xb1, 0x1b, 1) // zero-IF input
            com.setDemodReg(0, 0x0d, 0x83, 1) // TP_CK0 off
        }

        /** True when the USB strings say this is an RTL-SDR Blog V4 (R828D with input switching). */
        fun isBlogV4Strings(manufacturer: String?, product: String?) =
            manufacturer?.trim() == "RTLSDRBlog" && product?.trim() == "Blog V4"

        /**
         * Probes both tuner addresses and records what each returned. A Blog V4
         * (by its USB strings) takes the R828D when 0x74 answers, even if 0x34
         * also seems to; otherwise R820T first, as librtlsdr does.
         */
        private fun findTuner(com: RtlCom): Pair<R82xx, String> {
            val blogV4 = isBlogV4Strings(com.manufacturer, com.product)
            com.setRepeater(true)
            val ids = HashMap<R82xx.Chip, Int?>()
            try {
                for (chip in R82xx.Chip.values())
                    ids[chip] = runCatching { com.i2cRead(chip.i2c, 0x00, 1)[0].toInt() and 0xff }.getOrNull()
            } finally {
                com.setRepeater(false)
            }
            val info = R82xx.Chip.values().joinToString(" · ") { c ->
                "0x%02x(%s): %s".format(c.i2c, c.name, ids[c]?.let { "0x%02x".format(it) } ?: "응답 없음")
            }
            fun ok(c: R82xx.Chip) = ids[c] == R82xx.CHIP_ID
            val order = if (blogV4) listOf(R82xx.Chip.R828D, R82xx.Chip.R820T) else listOf(R82xx.Chip.R820T, R82xx.Chip.R828D)
            val chip = order.firstOrNull { ok(it) }
                ?: throw UsbIoException("지원하지 않는 튜너입니다 (R820T/R828D만 지원) · $info")
            return R82xx(com, chip, blogV4 && chip == R82xx.Chip.R828D, fast = false) to info
        }

        /** Default RTL2832U low-pass FIR, 20 bytes at demod page 1 regs 0x1c-0x2f. */
        private val FIR = intArrayOf(
            0xca, 0xdc, 0xd7, 0xd8, 0xe0, 0xf2, 0x0e, 0x35, 0x06, 0x50,
            0x9c, 0x0d, 0x71, 0x11, 0x14, 0x71, 0x74, 0x19, 0x41, 0xa5,
        )
    }
}
