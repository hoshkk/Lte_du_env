package com.lteduenv.spectrum.data

/** Which side of the repeater/base station link a reading was taken from. */
enum class LinkDirection { TX, RX }

/** Which screen of the instrument is currently active, mirrors the tabs on the photographed unit. */
enum class MeasurementMode(val label: String) {
    SPECTRUM("5G/LTE"),
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
) {
    fun centerMhzFor(direction: LinkDirection): Double =
        if (direction == LinkDirection.TX) downlinkMhz else uplinkMhz
}

object BandPresets {
    val all = listOf(
        // Korean domestic "5A" 800 MHz band: only the single frequency confirmed from the
        // reference field-instrument photo (829-839 MHz sweep centered at 834.0) is used for
        // both sides here, since the paired uplink/downlink split for this specific domestic
        // band plan isn't confidently known - deliberately not guessing a duplex offset.
        BandPreset("b5a", "5A", "CDMA/LTE", downlinkMhz = 834.0, uplinkMhz = 834.0, spanMhz = 10.0),
        // Standard 3GPP FDD band plans below (uplink = UE tx / network rx, downlink = network tx).
        BandPreset("lte_b1", "LTE B1", "LTE", downlinkMhz = 2140.0, uplinkMhz = 1950.0, spanMhz = 60.0),
        BandPreset("lte_b3", "LTE B3", "LTE", downlinkMhz = 1842.5, uplinkMhz = 1747.5, spanMhz = 75.0),
        BandPreset("lte_b5", "LTE B5", "LTE", downlinkMhz = 881.5, uplinkMhz = 836.5, spanMhz = 25.0),
        BandPreset("lte_b7", "LTE B7", "LTE", downlinkMhz = 2655.0, uplinkMhz = 2535.0, spanMhz = 70.0),
        BandPreset("lte_b8", "LTE B8", "LTE", downlinkMhz = 942.5, uplinkMhz = 897.5, spanMhz = 35.0),
        // n78 is TDD: uplink and downlink share the same frequency, just time-multiplexed.
        BandPreset("nr_n78", "NR n78", "5G NR", downlinkMhz = 3600.0, uplinkMhz = 3600.0, spanMhz = 400.0),
        BandPreset("nr_n28", "NR n28", "5G NR", downlinkMhz = 780.5, uplinkMhz = 725.5, spanMhz = 45.0),
    )
}

/** Sweep parameters shown along the bottom control bar (Freq / Span / Amp / RBW / VBW). */
data class SweepConfig(
    val centerMhz: Double = 834.0,
    val spanMhz: Double = 10.0,
    val refLevelDbm: Double = 0.0,
    val rbwKhz: Double = 100.0,
    val vbwKhz: Double = 100.0,
    val direction: LinkDirection = LinkDirection.RX,
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
