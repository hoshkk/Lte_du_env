package com.lteduenv.spectrum.data

import kotlin.math.log10

/** Which side of the repeater/base station link a reading was taken from. */
enum class LinkDirection { TX, RX }

/** Which screen of the instrument is currently active, mirrors the tabs on the photographed unit. */
enum class MeasurementMode(val label: String) {
    SPECTRUM("LTE"),
    VSWR("VSWR"),
    DTF("DTF"),
    CABLE_LOSS("Cable Loss"),
}

/**
 * A tappable band button, e.g. "5A", "LTE B3", matching the row of preset buttons in the photo.
 *
 * [downlinkMhz] is the network-transmit / UE-receive center frequency (TX side, per
 * [LinkDirection]); [uplinkMhz] is the UE-transmit / network-receive center frequency (RX side).
 * For TDD bands (e.g. NR n78) uplink and downlink share the same frequency.
 */
data class BandPreset(
    val id: String,
    val label: String,
    val system: String,
    val downlinkMhz: Double,
    val uplinkMhz: Double,
    val spanMhz: Double,
    /** Channel Power's Integration BW - the channel's real occupied bandwidth (narrower than [spanMhz], which adds sweep guard room). */
    val integrationBwMhz: Double,
) {
    fun centerMhzFor(direction: LinkDirection): Double =
        if (direction == LinkDirection.TX) downlinkMhz else uplinkMhz
}

object BandPresets {
    val all = listOf(
        // LTE only for now, per KT's own MS2090A field-instrument training material (ROU DL/UL
        // measurement steps) - real deployed channel plan, not a generic 3GPP band-plan guess.
        // LTE1.8 has two channel-width configs in that material - both kept as separate presets
        // since they tune to different center frequencies. B1 and NR n78 dropped: neither was in
        // that material, so both were unconfirmed guesses.
        BandPreset("lte_b3_30m", "LTE B3 (30M)", "LTE", downlinkMhz = 1845.0, uplinkMhz = 1750.0, spanMhz = 35.0, integrationBwMhz = 30.0),
        BandPreset("lte_b3_20m", "LTE B3 (20M)", "LTE", downlinkMhz = 1840.0, uplinkMhz = 1745.0, spanMhz = 25.0, integrationBwMhz = 20.0),
        BandPreset("lte_b8", "LTE B8 (900)", "LTE", downlinkMhz = 954.3, uplinkMhz = 909.3, spanMhz = 15.0, integrationBwMhz = 10.0),
    )
}

/** Sweep parameters shown along the bottom control bar (Freq / Span / Amp / RBW / VBW). */
data class SweepConfig(
    val centerMhz: Double = 1840.0,
    val spanMhz: Double = 25.0,
    val refLevelDbm: Double = 0.0,
    val rbwKhz: Double = 100.0,
    val vbwKhz: Double = 100.0,
    val direction: LinkDirection = LinkDirection.RX,
    /**
     * Front-end RF preamp, like a spectrum analyzer's Preamp toggle - trades headroom for
     * sensitivity. Only HackRF has this (its ~14dB broadband AMP stage); RTL-SDR ignores it since
     * its tuner is always run in automatic-gain mode.
     */
    val preampEnabled: Boolean = false,
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

/** One point of a VSWR / return-loss sweep across a band. */
data class VswrSample(val freqMhz: Double, val vswr: Float, val returnLossDb: Float)

data class VswrFrame(val samples: List<VswrSample>, val timestampMs: Long) {
    val worst: VswrSample? get() = samples.maxByOrNull { it.vswr }
}

/** One point of a Distance-To-Fault trace: reflection magnitude vs. cable distance. */
data class DtfSample(val distanceM: Double, val returnLossDb: Float)

data class DtfFrame(
    val samples: List<DtfSample>,
    val cableLossDbPer100m: Double,
    val velocityFactor: Double,
    val timestampMs: Long,
) {
    /**
     * Largest reflection beyond the near-end connector, i.e. the likely fault location.
     * returnLossDb follows the S11 convention (0 dB = full reflection, more negative = better
     * match), so the worst point is the one closest to 0 dB.
     */
    fun worstFault(excludeFirstMeters: Double = 1.0): DtfSample? =
        samples.filter { it.distanceM > excludeFirstMeters }.maxByOrNull { it.returnLossDb }
}

/** Simple two-way cable-loss measurement result (source power known, measured power at far end). */
data class CableLossResult(
    val lengthM: Double,
    val measuredLossDb: Double,
    val lossPer100mDb: Double,
)
