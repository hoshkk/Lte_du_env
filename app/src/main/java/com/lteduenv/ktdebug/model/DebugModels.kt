package com.lteduenv.ktdebug.model

/** LTE serving-cell block, mirrors the fields shown on the carrier engineering "System/Debug Screen". */
data class LteCellInfo(
    val band: Int,
    val bandwidthMHz: Int,
    val enDcSupport: String,
    val earfcn: Int,
    val pci: Int,
    val rsrpDbm: Int,
    val rsrqDb: Int,
    val rssiDbm: Int,
    val sinrDb: Double,
    val rplmn: String,
    val tac: Int,
    val avgRsrpDbm: Int,
    val avgRsrqDb: Int,
    val antDiffDb: Int,
    val cqi: Int,
    val ri: Int,
    val txPwrDbm: Int,
    val txPuschDbm: Int,
    val txPucchDbm: Int,
    val txSrs: String,
    val rb: Int,
    val mcs: Int,
    val modulation: String,
    val blerDownPercent: Int,
    val blerUpPercent: Int,
    val drxMs: Int
)

/** NR (5G) secondary-cell-group block for EN-DC / SA. */
data class NrCellInfo(
    val mode: String,
    val band: Int,
    val bandwidthMHz: Int,
    val scgState: String,
    val nrArfcn: Int,
    val pci: Int,
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
    val ssbSinrDb: Double?,
    val cqi: Int?,
    val ri: Int?,
    val rb: Int?,
    val mcs: Int?,
    val modulation: String?,
    val blerPercent: Int?,
    val upperLayerIndSupport: Boolean,
    val restrictDcNr: Boolean,
    val nrTxPwrDbm: Int?,
    val enDcTotalTxPwrDbm: Int?
)

data class RegistrationStatus(
    val status: String,
    val subStatus: String,
    val esmCause: Int,
    val rrc: String,
    val rreReqCause: String,
    val scgFailCause: String,
    val guti: String
)

data class DebugSnapshot(
    val imei: String,
    val mdn: String,
    val lte: LteCellInfo,
    val nr: NrCellInfo?,
    val status: RegistrationStatus
)
