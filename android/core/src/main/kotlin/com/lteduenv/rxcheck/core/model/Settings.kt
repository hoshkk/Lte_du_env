package com.lteduenv.rxcheck.core.model

import com.lteduenv.rxcheck.core.sweep.SweepPlan
import kotlin.math.roundToInt

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
    /** Requested RBW; the FFT gives the nearest of [RBW_CHOICES_KHZ] ([rbwActualHz]). */
    val rbwKhz: Double = 54.0,
    /** FFT frames power-averaged per segment (used when [vbwKhz] is null = VBW AUTO). */
    val averages: Int = 16,
    /**
     * VBW. There is no analog video filter: a set VBW is turned into an
     * averaging count N = RBW / VBW (rounded, 1..256), the usual FFT-analyzer
     * equivalent. null = AUTO (use [averages]).
     */
    val vbwKhz: Double? = null,
    /** Tuner gain step 0..15, or null for tuner AGC. */
    val gainStep: Int? = 2,
    /** IF (VGA) gain 0..15 used with a manual [gainStep]; 8 = librtlsdr's fixed value. */
    val vgaStep: Int = 8,
    val offsetDb: Double = 0.0,
    val maxHold: Boolean = false,
    /** Spurious: dB above floor. Reverse: dB above the median block. */
    val thresholdDb: Double = 6.0,
    val fastTune: Boolean = true,
    /** librtlsdr-style IF filter matched to the sample rate (vs. wide 6 MHz). */
    val narrowIf: Boolean = true,
    /** Extra wait after PLL lock before capturing, ms. */
    val settleMs: Int = 0,
    /** Show integrated channel power for the channel BW. */
    val channelPower: Boolean = true,
    /** Replace the 3 bins at each segment centre with their neighbours (a correction; off = raw). */
    val dcPatch: Boolean = false,
    /** Subtract each FFT frame's I/Q mean (also removes a real signal exactly on the centre). */
    val iqMeanRemoval: Boolean = false,
    /** Move the segment centres so DC bins fall on other frequencies (recheck of a DC-position peak). */
    val dcShift: Boolean = false,
    /** Also show channel power with 28.8 MHz harmonics / recorded internal spurs replaced (raw stays primary). */
    val internalCorrection: Boolean = false,
    /**
     * Frequency correction of the dongle's crystal, ppm (positive = crystal runs
     * fast). Dongles without a TCXO can be tens of ppm off.
     */
    val ppm: Int = 0,
    /** Show the waterfall (time/frequency history) under the spectrum. Display only. */
    val waterfall: Boolean = false,
    val refLevelDb: Double = -20.0,
    val dbPerDiv: Double = 10.0,
) {
    val startMhz get() = centerMhz - spanMhz / 2
    val stopMhz get() = centerMhz + spanMhz / 2

    fun validate(): String? = when {
        !listOf(centerMhz, spanMhz, channelBwMhz, rbwKhz, offsetDb, thresholdDb, refLevelDb, dbPerDiv).all { it.isFinite() } ->
            "숫자 입력을 확인하세요: " + listOf("Center" to centerMhz, "Span" to spanMhz, "채널 BW" to channelBwMhz,
                "RBW" to rbwKhz, "Offset" to offsetDb, "임계" to thresholdDb, "Ref Level" to refLevelDb, "dB/div" to dbPerDiv)
                .filter { !it.second.isFinite() }.joinToString(", ") { it.first }
        spanMhz !in 0.05..MAX_SPAN_MHZ -> "Span 범위: 0.05–50 MHz"
        startMhz < 24.0 || stopMhz > MAX_MHZ -> "측정 범위 전체가 24–2200 MHz 안이어야 합니다 (1766 MHz 이상은 하모닉 수신)"
        rbwKhz !in 0.1..300.0 -> "RBW 범위: 0.1–300 kHz"
        vbwKhz != null && (!vbwKhz.isFinite() || vbwKhz !in 0.001..300.0) -> "VBW 범위: 0.001–300 kHz 또는 AUTO"
        averages !in 1..256 -> "평균 횟수: 1–256"
        gainStep != null && gainStep !in 0..MAX_GAIN_STEP -> "이득 단계: 0–15"
        settleMs !in 0..100 -> "안정화 대기: 0–100 ms"
        ppm !in -200..200 -> "주파수 보정: -200–200 ppm"
        vgaStep !in 0..15 -> "IF 이득(VGA): 0–15"
        channelBwMhz !in 0.01..60.0 -> "채널 대역폭: 0.01–60 MHz"
        dbPerDiv !in 1.0..20.0 -> "dB/div 범위: 1–20"
        else -> null
    }

    /** Starting point per mode; not a calibrated instrument setting. */
    fun withProfile(mode: Mode, band: Band?): Settings {
        val b = band ?: return copy(mode = mode)
        return when (mode) {
            Mode.REVERSE -> copy(mode = mode, band = b, centerMhz = b.rxCenterMhz, spanMhz = b.reverseSpanMhz,
                channelBwMhz = b.channelBwMhz, rbwKhz = 54.0, averages = 16, vbwKhz = null, gainStep = 2,
                maxHold = false, thresholdDb = 6.0, channelPower = true)
            Mode.SPURIOUS -> copy(mode = mode, band = b,
                centerMhz = (b.spuriousStartMhz + b.spuriousStopMhz) / 2,
                spanMhz = b.spuriousStopMhz - b.spuriousStartMhz,
                channelBwMhz = b.channelBwMhz, rbwKhz = 13.5, averages = 4, vbwKhz = null, gainStep = 8,
                maxHold = true, thresholdDb = 10.0, channelPower = false)
        }
    }

    /**
     * ppm that makes a signal shown at [shownHz] read [actualHz] (from the
     * current correction). A fast crystal shows signals low, by f * ppm.
     */
    fun ppmFor(shownHz: Double, actualHz: Double): Int =
        ppm + Math.round((actualHz - shownHz) / actualHz * 1e6).toInt()

    /** RBW the sweep actually uses (Hann -3 dB width of the chosen FFT bin), Hz. */
    fun rbwActualHz(sampleRate: Int = SAMPLE_RATE) = 1.44 * sampleRate / SweepPlan.fftSizeFor(rbwKhz * 1e3, sampleRate)

    /** Frames averaged per segment: from VBW when set, else [averages]. */
    fun effectiveAverages(sampleRate: Int = SAMPLE_RATE): Int =
        vbwKhz?.let { (rbwActualHz(sampleRate) / (it * 1e3)).roundToInt().coerceIn(1, 256) } ?: averages

    /** VBW equivalent of the averaging in use (RBW / N), Hz. */
    fun vbwActualHz(sampleRate: Int = SAMPLE_RATE) = rbwActualHz(sampleRate) / effectiveAverages(sampleRate)

    /** Largest span that stays inside 24–2200 MHz around the current centre. */
    fun maxSpanAtCenter() = minOf(MAX_SPAN_MHZ, 2 * (centerMhz - 24.0), 2 * (MAX_MHZ - centerMhz))

    /** Part of the span is above the direct tuning limit (5th-harmonic reception, experimental). */
    val usesHarmonic get() = stopMhz > DIRECT_MAX_MHZ

    /** RX channel edges in Hz (the band preset's channel, which may differ from the span centre). */
    fun channelHz(): Pair<Double, Double> {
        val c = (band?.rxCenterMhz ?: centerMhz) * 1e6
        return (c - channelBwMhz * 5e5) to (c + channelBwMhz * 5e5)
    }

    companion object {
        const val MAX_SPAN_MHZ = 50.0
        const val MAX_GAIN_STEP = 15
        const val SAMPLE_RATE = 2_400_000
        const val DIRECT_MAX_MHZ = 1766.0
        const val MAX_MHZ = 2200.0

        /** Every RBW the FFT can give at 2.4 MS/s (FFT 64..16384), widest first, kHz. */
        val RBW_CHOICES_KHZ: List<Double> = (6..14).map { 1.44 * SAMPLE_RATE / (1 shl it) / 1e3 }

        /** VBW/RBW ratios offered in the settings (= averaging 1, 3, 10, 30, 100). */
        val VBW_RATIOS = listOf(1, 3, 10, 30, 100)
    }
}
