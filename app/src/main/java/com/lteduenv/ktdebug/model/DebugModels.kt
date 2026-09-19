package com.lteduenv.ktdebug.model

/**
 * LTE serving-cell block, mirrors the fields shown on the carrier engineering "System/Debug
 * Screen" - but built from real android.telephony.CellInfoLte readings (see LiveCellInfoProvider),
 * not simulated. Android's public API only exposes PCI/TAC/EARFCN/band/bandwidth and the
 * RSRP/RSRQ/RSSI/SINR measurements to a normal app; everything else a real engineering menu shows
 * (TxPwr, RB, MCS, BLER, DRX, ANT/Diff, averaged RSRP/RSRQ, and often CQI/RI too) comes from the
 * baseband and is not obtainable outside the device vendor's own hidden diagnostic tools - those
 * fields stay null and render as "-".
 */
data class LteCellInfo(
    val band: Int?,
    val bandwidthMHz: Int?,
    val enDcSupport: String,
    val earfcn: Int,
    val pci: Int,
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
    val rssiDbm: Int?,
    val sinrDb: Double?,
    val rplmn: String,
    val tac: Int?,
    val avgRsrpDbm: Int? = null,
    val avgRsrqDb: Int? = null,
    val antDiffDb: Int? = null,
    val cqi: Int? = null,
    val ri: Int? = null,
    val txPwrDbm: Int? = null,
    val txPuschDbm: Int? = null,
    val txPucchDbm: Int? = null,
    val txSrs: String = "-",
    val rb: Int? = null,
    val mcs: Int? = null,
    val modulation: String = "-",
    val blerDownPercent: Int? = null,
    val blerUpPercent: Int? = null,
    val drxMs: Int? = null
)

/** NR (5G) secondary-cell-group block for EN-DC / SA - same real-data-only caveat as [LteCellInfo]. */
data class NrCellInfo(
    val mode: String,
    val band: Int?,
    val bandwidthMHz: Int? = null,
    val scgState: String,
    val nrArfcn: Int,
    val pci: Int,
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
    val ssbSinrDb: Double?,
    val cqi: Int? = null,
    val ri: Int? = null,
    val rb: Int? = null,
    val mcs: Int? = null,
    val modulation: String? = null,
    val blerPercent: Int? = null,
    val upperLayerIndSupport: Boolean? = null,
    val restrictDcNr: Boolean? = null,
    val nrTxPwrDbm: Int? = null,
    val enDcTotalTxPwrDbm: Int? = null
)

/**
 * RRC state, ESM cause and GUTI are core-network/baseband internals that Android never exposes to
 * apps at all; only a coarse in-service/out-of-service reading is available (from ServiceState).
 */
data class RegistrationStatus(
    val status: String,
    val subStatus: String = "-",
    val esmCause: String = "-",
    val rrc: String = "-",
    val rreReqCause: String = "-",
    val scgFailCause: String = "-",
    val guti: String = "-"
)

data class DebugSnapshot(
    val imei: String,
    val mdn: String,
    val lte: LteCellInfo,
    val nr: NrCellInfo?,
    val status: RegistrationStatus
)
