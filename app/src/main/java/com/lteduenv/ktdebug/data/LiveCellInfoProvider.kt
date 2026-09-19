package com.lteduenv.ktdebug.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.lteduenv.ktdebug.model.KtBandCatalog
import com.lteduenv.ktdebug.model.LteCellInfo
import com.lteduenv.ktdebug.model.NrCellInfo

/**
 * Reads the device's actual current radio state via android.telephony.TelephonyManager, instead of
 * simulating it. This is a real (if limited) integration, not a mock: Android only lets a normal
 * app see PCI/TAC/EARFCN/band/bandwidth plus RSRP/RSRQ/RSSI/SINR - the rest of what a vendor's
 * hidden engineering menu shows (TxPwr, RB, MCS, BLER, DRX, GUTI, RRC state, real IMEI, ...) is
 * baseband/core-network internal state that no public API exposes, on any non-rooted, non-system
 * app. Every getter below returns null rather than a fabricated number when the platform can't
 * supply a real value.
 */
class LiveCellInfoProvider(context: Context) {

    private val appContext = context.applicationContext
    private val telephonyManager: TelephonyManager? =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun hasRequiredPermissions(): Boolean {
        val phoneState = ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE)
        val fineLocation = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        return phoneState == PackageManager.PERMISSION_GRANTED && fineLocation == PackageManager.PERMISSION_GRANTED
    }

    /** Currently reported LTE cells, the actually-serving one first (usually plus a few neighbors). */
    fun currentLteCells(): List<LteCellInfo> {
        val tm = telephonyManager ?: return emptyList()
        if (!hasRequiredPermissions()) return emptyList()
        val cells = safeAllCellInfo(tm) ?: return emptyList()
        return cells.filterIsInstance<CellInfoLte>()
            .sortedByDescending { runCatching { it.isRegistered }.getOrDefault(false) }
            .mapNotNull { runCatching { it.toLteCellInfo() }.getOrNull() }
    }

    /** Currently reported NR cells, serving first - empty on API < 29 or when no 5G is present. */
    fun currentNrCells(): List<NrCellInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val tm = telephonyManager ?: return emptyList()
        if (!hasRequiredPermissions()) return emptyList()
        val cells = safeAllCellInfo(tm) ?: return emptyList()
        return cells.filterIsInstance<CellInfoNr>()
            .sortedByDescending { runCatching { it.isRegistered }.getOrDefault(false) }
            .mapNotNull { runCatching { it.toNrCellInfoOrNull() }.getOrNull() }
    }

    /** Coarse in-service/out-of-service reading; that's all Android exposes about registration state. */
    fun serviceStateSummary(): String {
        val tm = telephonyManager ?: return "-"
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return "-"
        return try {
            when (tm.serviceState?.state) {
                ServiceState.STATE_IN_SERVICE -> "SRV/IN_SERVICE"
                ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
                ServiceState.STATE_POWER_OFF -> "POWER_OFF"
                else -> "-"
            }
        } catch (_: SecurityException) {
            "-"
        }
    }

    /**
     * Real IMEI is blocked for regular (non system/carrier-privileged) apps on Android 10+
     * regardless of permission - this will return null there almost always. Not a bug to "fix".
     */
    fun tryGetImei(): String? {
        val tm = telephonyManager ?: return null
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            @Suppress("DEPRECATION")
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm.imei else tm.deviceId)?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Rarely populated by carriers; most devices simply don't know their own number. */
    fun tryGetLine1Number(): String? {
        val tm = telephonyManager ?: return null
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            @Suppress("DEPRECATION")
            tm.line1Number?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun safeAllCellInfo(tm: TelephonyManager): List<CellInfo>? = try {
        @Suppress("MissingPermission")
        tm.allCellInfo
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun CellInfoLte.toLteCellInfo(): LteCellInfo {
        val identity = cellIdentity
        val strength = cellSignalStrength
        val pci = identity.pci.takeIfAvailable()
        val tac = identity.tac.takeIfAvailable()
        val earfcn = identity.earfcn.takeIfAvailable()
        val band = inferLteBand(earfcn)
        val bandwidthMHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { identity.bandwidth }.getOrNull()?.takeIfAvailable()?.let { it / 1000 }
        } else null
        val rplmn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val mcc = runCatching { identity.mccString }.getOrNull()
            val mnc = runCatching { identity.mncString }.getOrNull()
            if (!mcc.isNullOrBlank() && !mnc.isNullOrBlank()) "$mcc$mnc" else "-"
        } else {
            "-"
        }
        val rsrp = strength.rsrp.takeIfAvailable()
        val rsrq = strength.rsrq.takeIfAvailable()
        val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { strength.rssi }.getOrNull()?.takeIfAvailable()
        } else null
        val sinr = strength.rssnr.takeIfAvailable()?.let { it / 10.0 }
        return LteCellInfo(
            band = band,
            bandwidthMHz = bandwidthMHz,
            enDcSupport = "-",
            earfcn = earfcn ?: -1,
            pci = pci ?: -1,
            rsrpDbm = rsrp,
            rsrqDb = rsrq,
            rssiDbm = rssi,
            sinrDb = sinr,
            rplmn = rplmn,
            tac = tac
        )
    }

    private fun inferLteBand(earfcn: Int?): Int? {
        if (earfcn == null || earfcn < 0) return null
        return KtBandCatalog.lteBands.firstOrNull { earfcn in it.earfcnRange }?.band
    }

    private fun CellInfoNr.toNrCellInfoOrNull(): NrCellInfo? {
        val identity = cellIdentity as? CellIdentityNr ?: return null
        val strength = cellSignalStrength as? CellSignalStrengthNr ?: return null
        val pci = identity.pci.takeIfAvailable() ?: return null
        val nrArfcn = identity.nrarfcn.takeIfAvailable() ?: -1
        val band = inferNrBand(nrArfcn)
        val rsrp = strength.ssRsrp.takeIfAvailable()
        val rsrq = strength.ssRsrq.takeIfAvailable()
        val sinr = strength.ssSinr.takeIfAvailable()?.toDouble()
        return NrCellInfo(
            mode = "NSA/SA (구분 불가)",
            band = band,
            scgState = "-",
            nrArfcn = nrArfcn,
            pci = pci,
            rsrpDbm = rsrp,
            rsrqDb = rsrq,
            ssbSinrDb = sinr
        )
    }

    private fun inferNrBand(nrArfcn: Int): Int? {
        if (nrArfcn < 0) return null
        return KtBandCatalog.nrBands.firstOrNull { nrArfcn in it.earfcnRange }?.band
    }

    private fun Int.takeIfAvailable(): Int? = takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE }
}
