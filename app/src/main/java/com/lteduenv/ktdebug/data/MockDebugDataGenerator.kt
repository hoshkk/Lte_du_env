package com.lteduenv.ktdebug.data

import com.lteduenv.ktdebug.model.BandDefinition
import com.lteduenv.ktdebug.model.DebugSnapshot
import com.lteduenv.ktdebug.model.KtBandCatalog
import com.lteduenv.ktdebug.model.LteCellInfo
import com.lteduenv.ktdebug.model.NetworkType
import com.lteduenv.ktdebug.model.NrCellInfo
import com.lteduenv.ktdebug.model.RegistrationStatus
import kotlin.random.Random

/**
 * Generates plausible-looking radio metrics (RSRP/RSRQ/SINR/MCS/...) for the
 * selected band, since no live modem is wired in yet. The PCI itself is drawn
 * from [EquipmentRepository]'s real KT inventory when possible, so the equipment
 * lookup below the debug table resolves against actual site data rather than a
 * made-up id. KT's LTE PLMN (450-08) is real public MCC/MNC info; every other
 * radio value here is a simulated placeholder, not a live reading.
 */
class MockDebugDataGenerator(private val equipmentRepository: EquipmentRepository) {

    private val random = Random.Default

    suspend fun generate(selectedBand: BandDefinition): DebugSnapshot {
        val lteBand = if (selectedBand.networkType == NetworkType.LTE) {
            selectedBand
        } else {
            KtBandCatalog.lteBands.random(random)
        }
        val lte = buildLteInfo(lteBand)

        // NR is only present for an NR band selection, or ~60% of the time on an LTE
        // band to represent EN-DC with a 5G secondary cell group.
        val nr = when {
            selectedBand.networkType == NetworkType.NR -> buildNrInfo(selectedBand, scgState = "Add")
            random.nextInt(100) < 60 -> buildNrInfo(KtBandCatalog.nrBands.random(random), scgState = "Add")
            else -> null
        }

        return DebugSnapshot(
            imei = randomImei(),
            mdn = randomMdn(),
            lte = lte,
            nr = nr,
            status = RegistrationStatus(
                status = "SRV/REGISTERED",
                subStatus = "NORMAL",
                esmCause = 0,
                rrc = "CONNECTED",
                rreReqCause = "--",
                scgFailCause = "--",
                guti = randomGuti()
            )
        )
    }

    private suspend fun buildLteInfo(band: BandDefinition): LteCellInfo {
        val pci = equipmentRepository.randomKnownPci(NetworkType.LTE) ?: random.nextInt(0, 504)
        val rsrp = random.nextInt(-110, -60)
        val cqi = random.nextInt(3, 16)
        return LteCellInfo(
            band = band.band,
            bandwidthMHz = band.bandwidthOptionsMHz.random(random),
            enDcSupport = "Support",
            earfcn = band.earfcnRange.random(random),
            pci = pci,
            rsrpDbm = rsrp,
            rsrqDb = random.nextInt(-16, -3),
            rssiDbm = rsrp + random.nextInt(20, 40),
            sinrDb = randomDecimal(-5.0, 30.0),
            rplmn = KT_PLMN,
            tac = random.nextInt(1000, 9999),
            avgRsrpDbm = rsrp + random.nextInt(-2, 3),
            avgRsrqDb = random.nextInt(-16, -3),
            antDiffDb = random.nextInt(-20, 10),
            cqi = cqi,
            ri = random.nextInt(1, 3),
            txPwrDbm = random.nextInt(-40, 24),
            txPuschDbm = random.nextInt(0, 24),
            txPucchDbm = random.nextInt(-5, 5),
            txSrs = "-",
            rb = random.nextInt(5, 101),
            mcs = random.nextInt(0, 29),
            modulation = MODULATIONS.random(random) + "/-",
            blerDownPercent = random.nextInt(0, 6),
            blerUpPercent = random.nextInt(0, 6),
            drxMs = DRX_OPTIONS_MS.random(random)
        )
    }

    private suspend fun buildNrInfo(band: BandDefinition, scgState: String): NrCellInfo {
        val pci = equipmentRepository.randomKnownPci(NetworkType.NR) ?: random.nextInt(0, 1008)
        val rsrp = random.nextInt(-110, -65)
        return NrCellInfo(
            mode = "NSA",
            band = band.band,
            bandwidthMHz = band.bandwidthOptionsMHz.random(random),
            scgState = scgState,
            nrArfcn = band.earfcnRange.random(random),
            pci = pci,
            rsrpDbm = rsrp,
            rsrqDb = random.nextInt(-16, -3),
            ssbSinrDb = randomDecimal(-5.0, 25.0),
            cqi = random.nextInt(3, 16),
            ri = random.nextInt(1, 5),
            rb = random.nextInt(5, 273),
            mcs = random.nextInt(0, 29),
            modulation = MODULATIONS.random(random),
            blerPercent = random.nextInt(0, 6),
            upperLayerIndSupport = true,
            restrictDcNr = false,
            nrTxPwrDbm = random.nextInt(-40, 24),
            enDcTotalTxPwrDbm = random.nextInt(-5, 24)
        )
    }

    private fun randomDecimal(min: Double, max: Double): Double {
        val value = min + random.nextDouble() * (max - min)
        return kotlin.math.round(value * 10) / 10.0
    }

    private fun randomImei(): String = buildString {
        append("35")
        repeat(13) { append(random.nextInt(0, 10)) }
    }

    private fun randomMdn(): String = buildString {
        append("8210")
        repeat(8) { append(random.nextInt(0, 10)) }
    }

    private fun randomGuti(): String {
        val tail = (1..8).joinToString("") { HEX_CHARS.random(random).toString() }
        return "450-8-291-e-$tail"
    }

    private companion object {
        const val KT_PLMN = "45008"
        val MODULATIONS = listOf("QPSK", "16QAM", "64QAM", "256QAM")
        val DRX_OPTIONS_MS = listOf(40, 80, 160, 320, 640, 1280, 2560)
        const val HEX_CHARS = "0123456789abcdef"
    }
}
