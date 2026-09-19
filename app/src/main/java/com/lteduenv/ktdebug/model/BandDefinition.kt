package com.lteduenv.ktdebug.model

/**
 * Reference band/frequency table for KT's public network.
 *
 * These band numbers and frequency ranges are widely-cited public information about
 * KT's spectrum holdings, but carrier spectrum assignments do change over time and
 * this list is NOT sourced from KT's internal RF planning systems. Treat it as a
 * label for the mock signal screen, verify against current RF engineering
 * documentation before relying on it operationally.
 */
data class BandDefinition(
    val networkType: NetworkType,
    val band: Int,
    val displayName: String,
    val frequencyLabel: String,
    val duplexMode: String,
    val earfcnRange: IntRange,
    val bandwidthOptionsMHz: List<Int>
)

object KtBandCatalog {

    val lteBands: List<BandDefinition> = listOf(
        BandDefinition(
            networkType = NetworkType.LTE,
            band = 1,
            displayName = "LTE Band 1",
            frequencyLabel = "2.1GHz",
            duplexMode = "FDD",
            earfcnRange = 0..599,
            bandwidthOptionsMHz = listOf(10, 15, 20)
        ),
        BandDefinition(
            networkType = NetworkType.LTE,
            band = 3,
            displayName = "LTE Band 3",
            frequencyLabel = "1.8GHz",
            duplexMode = "FDD",
            earfcnRange = 1200..1949,
            bandwidthOptionsMHz = listOf(10, 15, 20)
        ),
        BandDefinition(
            networkType = NetworkType.LTE,
            band = 8,
            displayName = "LTE Band 8",
            frequencyLabel = "900MHz",
            duplexMode = "FDD",
            earfcnRange = 3450..3799,
            bandwidthOptionsMHz = listOf(10)
        ),
        BandDefinition(
            networkType = NetworkType.LTE,
            band = 7,
            displayName = "LTE Band 7",
            frequencyLabel = "2.6GHz",
            duplexMode = "FDD",
            earfcnRange = 2750..3449,
            bandwidthOptionsMHz = listOf(10, 20)
        ),
        BandDefinition(
            networkType = NetworkType.LTE,
            band = 41,
            displayName = "LTE Band 41",
            frequencyLabel = "2.6GHz TDD",
            duplexMode = "TDD",
            earfcnRange = 39650..41589,
            bandwidthOptionsMHz = listOf(20, 40)
        )
    )

    val nrBands: List<BandDefinition> = listOf(
        BandDefinition(
            networkType = NetworkType.NR,
            band = 78,
            displayName = "NR n78",
            frequencyLabel = "3.5GHz",
            duplexMode = "TDD",
            earfcnRange = 620000..653333,
            bandwidthOptionsMHz = listOf(80, 100)
        ),
        BandDefinition(
            networkType = NetworkType.NR,
            band = 257,
            displayName = "NR n257",
            frequencyLabel = "28GHz mmWave",
            duplexMode = "TDD",
            earfcnRange = 2054166..2104165,
            bandwidthOptionsMHz = listOf(400, 800)
        )
    )

    val all: List<BandDefinition> get() = lteBands + nrBands

    fun find(networkType: NetworkType, band: Int): BandDefinition? =
        all.firstOrNull { it.networkType == networkType && it.band == band }
}
