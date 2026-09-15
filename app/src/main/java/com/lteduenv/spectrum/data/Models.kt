package com.lteduenv.spectrum.data

import kotlin.math.log10

/**
 * A tappable band button, e.g. "LTE B3 (30M)", matching the row of preset buttons in the photo.
 * [uplinkMhz] is the UE-transmit / network-receive center frequency - the reverse/UL side, which
 * is all this app currently tunes to (see [SweepConfig] - there's no downlink/TX tuning anymore).
 */
data class BandPreset(
    val id: String,
    val label: String,
    val system: String,
    val uplinkMhz: Double,
    val spanMhz: Double,
    /** Channel Power's Integration BW - the channel's real occupied bandwidth (narrower than [spanMhz], which adds sweep guard room). */
    val integrationBwMhz: Double,
)

object BandPresets {
    val all = listOf(
        // LTE only for now, per KT's own MS2090A field-instrument training material (ROU DL/UL
        // measurement steps) - real deployed channel plan, not a generic 3GPP band-plan guess.
        // Uplink (reverse) only, to match RTL-SDR's ~1.7GHz tuning ceiling - the matching downlink
        // frequencies are B3(30M) 1845.0, B3(20M) 1840.0, B8 954.3, out of reach on this dongle.
        BandPreset("lte_b3_30m", "LTE B3 (30M)", "LTE", uplinkMhz = 1750.0, spanMhz = 35.0, integrationBwMhz = 30.0),
        BandPreset("lte_b3_20m", "LTE B3 (20M)", "LTE", uplinkMhz = 1745.0, spanMhz = 25.0, integrationBwMhz = 20.0),
        BandPreset("lte_b8", "LTE B8 (900)", "LTE", uplinkMhz = 909.3, spanMhz = 15.0, integrationBwMhz = 10.0),
    )
}

/** Sweep parameters shown along the bottom control bar (Freq / Span / Amp / RBW / VBW). */
data class SweepConfig(
    val centerMhz: Double = 1745.0,
    val spanMhz: Double = 25.0,
    val refLevelDbm: Double = 0.0,
    val rbwKhz: Double = 100.0,
    val vbwKhz: Double = 100.0,
    /**
     * Front-end RF preamp, like a spectrum analyzer's Preamp toggle - trades headroom for
     * sensitivity. Only HackRF has this (its ~14dB broadband AMP stage).
     */
    val preampEnabled: Boolean = false,
    /**
     * RTL-SDR gain mode - like a real analyzer's AGC toggle. True runs the R820T/R828D tuner's
     * own chip-level automatic gain control (LNA+Mixer AGC); false uses [manualGainLevel] instead.
     * RTL-SDR has one gain axis (no separate Attenuator/Preamp stages like a calibrated
     * instrument), so manual mode doubles as both: low levels behave like an attenuator (protects
     * against strong nearby signals), high levels like a preamp (more sensitivity to weak ones).
     * Ignored on HackRF, which has its own fixed LNA/VGA + [preampEnabled].
     */
    val autoGain: Boolean = true,
    /** Manual RTL-SDR gain step, 1 (min, attenuator-like) to 10 (max, preamp-like). See [autoGain]. */
    val manualGainLevel: Int = 5,
    /**
     * Calibration offset added to every displayed level, mirroring a real analyzer's REF LEVEL
     * OFFSET: enter the monitor port's rated loss plus the measured loss of your patch cable (as
     * a negative number, e.g. -43 for a 40dB port + 3dB cable) so the trace/markers read actual
     * dBm at the antenna/reference point instead of the raw level at the SDR's own input. This is
     * a manual correction - it's only as accurate as the loss values you enter, and on the USB SDR
     * source it corrects for external losses only, not the dongle's own uncalibrated gain chain.
     */
    val refLevelOffsetDb: Double = 0.0,
    /** Channel Power's Integration BW - see [BandPreset.integrationBwMhz]. */
    val integrationBwMhz: Double = 20.0,
) {
    val startMhz: Double get() = centerMhz - spanMhz / 2.0
    val stopMhz: Double get() = centerMhz + spanMhz / 2.0
}

/** One sweep of spectrum trace samples, evenly spaced from startMhz to stopMhz. */
data class SpectrumFrame(
    val startMhz: Double,
    val stopMhz: Double,
    val levelsDbm: FloatArray,
    val timestampMs: Long,
) {
    val pointCount: Int get() = levelsDbm.size

    fun levelAt(freqMhz: Double): Float {
        if (pointCount == 0) return Float.NEGATIVE_INFINITY
        val span = stopMhz - startMhz
        if (span <= 0.0) return levelsDbm[0]
        val ratio = ((freqMhz - startMhz) / span).coerceIn(0.0, 1.0)
        val idx = (ratio * (pointCount - 1)).toInt()
        return levelsDbm[idx]
    }

    /**
     * Channel Power: sums linear power across every bin within [integrationBwMhz] of [centerMhz]
     * and converts back to dB, like a real analyzer's Channel Power measurement - the total power
     * actually present in that band, not just one bin's peak/marker reading. Null if the frame is
     * empty or the integration window falls outside it.
     */
    fun channelPowerDbm(centerMhz: Double, integrationBwMhz: Double): Double? {
        if (pointCount == 0) return null
        val span = stopMhz - startMhz
        if (span <= 0.0) return null
        val lowMhz = centerMhz - integrationBwMhz / 2.0
        val highMhz = centerMhz + integrationBwMhz / 2.0
        val startIdx = (((lowMhz - startMhz) / span) * (pointCount - 1)).toInt().coerceIn(0, pointCount - 1)
        val endIdx = (((highMhz - startMhz) / span) * (pointCount - 1)).toInt().coerceIn(0, pointCount - 1)
        if (endIdx < startIdx) return null
        var sumLinearMw = 0.0
        for (i in startIdx..endIdx) {
            sumLinearMw += Math.pow(10.0, levelsDbm[i] / 10.0)
        }
        if (sumLinearMw <= 0.0) return null
        return 10.0 * log10(sumLinearMw)
    }
}

/** A single frequency-domain marker (M1..M5 in the photo). */
data class Marker(
    val index: Int,
    val enabled: Boolean = false,
    val freqMhz: Double = 0.0,
    val levelDbm: Float = Float.NEGATIVE_INFINITY,
)
