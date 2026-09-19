package com.lteduenv.ktdebug.model

/**
 * KT's band list as given by the user: LTE Band 3 (1.8GHz, both a 10MHz and a
 * 20MHz carrier), Band 8 (900MHz), Band 1 (2.1GHz), and NR n78 (3.5GHz 5G).
 * Bandwidth is fixed per entry (Band 3 has two separate entries) rather than a
 * pick-list, since 10MHz and 20MHz Band 3 are meant to show up as distinct rows.
 *
 * Frequency/EARFCN ranges are reference values for the mock signal screen only,
 * not sourced from KT's internal RF planning systems - re-verify before relying
 * on them operationally.
 */
data class BandDefinition(
    val networkType: NetworkType,
    val band: Int,
    val bandwidthMHz: Int,
    val displayName: String,
    val frequencyLabel: String,
    val duplexMode: String,
    val earfcnRange: IntRange
)

object KtBandCatalog {

    val lteBands: List<BandDefinition> = listOf(
        BandDefinition(
            networkType = NetworkType.LTE,
            band = 3,
            bandwidthMHz = 10,
            displayName = "LTE Band 3 (10MHz)",
            frequencyLabel = "1.8GHz",
            duplexMode = "FDD",
            earfcnRange = 1200..1949
        ),
        BandDefinition(
            networkType = NetworkType.LTE,
            band = 3,
            bandwidthMHz = 20,
            displayName = "LTE Band 3 (20MHz)",
            frequencyLabel = "1.8GHz",
            duplexMode = "FDD",
            earfcnRange = 1200..1949
        ),
        BandDefinition(
            networkType = NetworkType.LTE,
            band = 8,
            bandwidthMHz = 10,
            displayName = "LTE Band 8",
            frequencyLabel = "900MHz",
            duplexMode = "FDD",
            earfcnRange = 3450..3799
        ),
        BandDefinition(
            networkType = NetworkType.LTE,
            band = 1,
            bandwidthMHz = 20,
            displayName = "LTE Band 1",
            frequencyLabel = "2.1GHz",
            duplexMode = "FDD",
            earfcnRange = 0..599
        )
    )

    val nrBands: List<BandDefinition> = listOf(
        BandDefinition(
            networkType = NetworkType.NR,
            band = 78,
            bandwidthMHz = 100,
            displayName = "NR n78 (5G)",
            frequencyLabel = "3.5GHz",
            duplexMode = "TDD",
            earfcnRange = 620000..653333
        )
    )

    val all: List<BandDefinition> get() = lteBands + nrBands

    fun find(networkType: NetworkType, band: Int, bandwidthMHz: Int): BandDefinition? =
        all.firstOrNull { it.networkType == networkType && it.band == band && it.bandwidthMHz == bandwidthMHz }
}
