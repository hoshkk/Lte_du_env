package com.lteduenv.rxcheck.core.model

enum class Mode(val title: String) {
    /** RX (uplink) path at the equipment: channel power, per-MHz PSD, rise vs. baseline. */
    REVERSE("장비 리버스"),
    /** Antenna side: narrow emissions around and inside the RX band. */
    SPURIOUS("불요파"),
}

/**
 * KT RX (uplink) centre frequencies as provided by the field user; confirm
 * the site's actual assignment before relying on them.
 */
enum class Band(
    val label: String,
    val rxCenterMhz: Double,
    val channelBwMhz: Double,
    /** Span for the reverse measurement: channel plus a margin each side. */
    val reverseSpanMhz: Double,
    /** Spurious search range: channel plus one channel-width each side, inside 24–1766 MHz. */
    val spuriousStartMhz: Double,
    val spuriousStopMhz: Double,
) {
    B8("B8 RX", 909.3, 10.0, 15.0, 894.3, 924.3),
    B3_20("B3 RX 20M", 1745.0, 20.0, 25.0, 1725.0, 1765.0),
    B3_30("B3 RX 30M", 1750.0, 30.0, 31.0, 1720.0, 1766.0),
}

data class Settings(
    val mode: Mode = Mode.REVERSE,
    val band: Band? = Band.B8,
    val centerMhz: Double = Band.B8.rxCenterMhz,
    val spanMhz: Double = Band.B8.reverseSpanMhz,
    val channelBwMhz: Double = Band.B8.channelBwMhz,
    val rbwKhz: Double = 100.0,
    val averages: Int = 16,
    /** Tuner gain step 0..15, or null for tuner AGC. */
    val gainStep: Int? = 2,
    val offsetDb: Double = 0.0,
    val maxHold: Boolean = false,
    /** Spurious: dB above floor. Reverse: dB above the median block. */
    val thresholdDb: Double = 6.0,
    val fastTune: Boolean = true,
    val dcPatch: Boolean = false,
    val refLevelDb: Double = -20.0,
    val dbPerDiv: Double = 10.0,
) {
    val startMhz get() = centerMhz - spanMhz / 2
    val stopMhz get() = centerMhz + spanMhz / 2

    fun validate(): String? = when {
        !listOf(centerMhz, spanMhz, channelBwMhz, rbwKhz, offsetDb, thresholdDb, refLevelDb, dbPerDiv).all { it.isFinite() } ->
            "숫자 입력을 확인하세요"
        spanMhz !in 0.1..60.0 -> "Span 범위: 0.1–60 MHz"
        startMhz < 24.0 || stopMhz > 1766.0 -> "측정 범위 전체가 24–1766 MHz 안이어야 합니다 (RTL-SDR V4 한계)"
        rbwKhz !in 1.0..300.0 -> "RBW 범위: 1–300 kHz"
        averages !in 1..256 -> "평균 횟수: 1–256"
        gainStep != null && gainStep !in 0..15 -> "이득 단계: 0–15"
        channelBwMhz <= 0 || channelBwMhz > spanMhz -> "채널 대역폭은 0보다 크고 Span 이하여야 합니다"
        dbPerDiv !in 1.0..20.0 -> "dB/div 범위: 1–20"
        else -> null
    }

    /** Starting point per mode; not a calibrated instrument setting. */
    fun withProfile(mode: Mode, band: Band?): Settings {
        val b = band ?: return copy(mode = mode)
        return when (mode) {
            Mode.REVERSE -> copy(mode = mode, band = b, centerMhz = b.rxCenterMhz, spanMhz = b.reverseSpanMhz,
                channelBwMhz = b.channelBwMhz, rbwKhz = 100.0, averages = 16, gainStep = 2,
                maxHold = false, thresholdDb = 6.0)
            Mode.SPURIOUS -> copy(mode = mode, band = b,
                centerMhz = (b.spuriousStartMhz + b.spuriousStopMhz) / 2,
                spanMhz = b.spuriousStopMhz - b.spuriousStartMhz,
                channelBwMhz = b.channelBwMhz, rbwKhz = 10.0, averages = 4, gainStep = 8,
                maxHold = true, thresholdDb = 10.0)
        }
    }

    /** RX channel edges in Hz (the band preset's channel, which may differ from the span centre). */
    fun channelHz(): Pair<Double, Double> {
        val c = (band?.rxCenterMhz ?: centerMhz) * 1e6
        return (c - channelBwMhz * 5e5) to (c + channelBwMhz * 5e5)
    }
}
