package com.lteduenv.ktdebug.ui

import android.Manifest
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lteduenv.ktdebug.data.CurrentLocationProvider
import com.lteduenv.ktdebug.data.EquipmentRepository
import com.lteduenv.ktdebug.data.LiveCellInfoProvider
import com.lteduenv.ktdebug.model.DebugSnapshot
import com.lteduenv.ktdebug.model.EquipmentMatch
import com.lteduenv.ktdebug.model.KtBandCatalog
import com.lteduenv.ktdebug.model.LteCellInfo
import com.lteduenv.ktdebug.model.NetworkType
import com.lteduenv.ktdebug.model.NrCellInfo
import com.lteduenv.ktdebug.model.RegistrationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.ACCESS_FINE_LOCATION
)
private const val POLL_INTERVAL_MS = 1500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    bandKey: BandKey,
    equipmentRepository: EquipmentRepository,
    onBack: () -> Unit
) {
    val band = remember(bandKey) { KtBandCatalog.find(bandKey.networkType, bandKey.band, bandKey.bandwidthMHz) }
    val context = LocalContext.current
    val liveProvider = remember { LiveCellInfoProvider(context) }
    val locationProvider = remember { CurrentLocationProvider(context) }

    var hasPermissions by remember { mutableStateOf(liveProvider.hasRequiredPermissions()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasPermissions = result.values.all { it } }

    var refreshCounter by remember { mutableIntStateOf(0) }
    var snapshot by remember(bandKey) { mutableStateOf<DebugSnapshot?>(null) }
    var matchesSelectedBand by remember(bandKey) { mutableStateOf(true) }
    var noSignal by remember(bandKey) { mutableStateOf(false) }
    var deviceLocation by remember { mutableStateOf<Location?>(null) }
    var lteMatches by remember(bandKey) { mutableStateOf<List<EquipmentMatch>>(emptyList()) }
    var nrMatches by remember(bandKey) { mutableStateOf<List<EquipmentMatch>>(emptyList()) }

    LaunchedEffect(bandKey, hasPermissions, refreshCounter) {
        if (!hasPermissions || band == null) return@LaunchedEffect
        deviceLocation = locationProvider.getCurrentLocation()

        while (isActive) {
            val liveLte = liveProvider.currentLteCells()
            val liveNr = liveProvider.currentNrCells()

            val lte: LteCellInfo?
            val nr: NrCellInfo?
            val onBand: Boolean
            when (band.networkType) {
                NetworkType.LTE -> {
                    val matched = liveLte.firstOrNull { it.band == band.band } ?: liveLte.firstOrNull()
                    lte = matched
                    nr = liveNr.firstOrNull()
                    onBand = matched?.band == band.band
                }
                NetworkType.NR -> {
                    val matchedNr = liveNr.firstOrNull { it.band == band.band } ?: liveNr.firstOrNull()
                    lte = liveLte.firstOrNull()
                    nr = matchedNr
                    onBand = matchedNr?.band == band.band
                }
            }

            if (lte == null) {
                noSignal = true
                snapshot = null
            } else {
                noSignal = false
                matchesSelectedBand = onBand
                snapshot = DebugSnapshot(
                    imei = liveProvider.tryGetImei() ?: "N/A (OS 정책상 일반 앱은 조회 불가)",
                    mdn = liveProvider.tryGetLine1Number() ?: "N/A",
                    lte = lte,
                    nr = nr,
                    status = RegistrationStatus(status = liveProvider.serviceStateSummary())
                )
                lteMatches = equipmentRepository.findByPci(NetworkType.LTE, lte.pci, deviceLocation = deviceLocation)
                nrMatches = nr?.let {
                    equipmentRepository.findByPci(NetworkType.NR, it.pci, deviceLocation = deviceLocation)
                } ?: emptyList()
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(band?.let { "${it.displayName} 디버그" } ?: "디버그") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        deviceLocation = null
                        refreshCounter++
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "위치 다시 확인")
                    }
                }
            )
        }
    ) { padding ->
        if (!hasPermissions) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("실제 단말의 PCI/RSRP 등을 읽으려면 전화 상태 권한과 위치 권한이 필요합니다.")
                Button(
                    onClick = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("권한 허용")
                }
            }
            return@Scaffold
        }

        val current = snapshot
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (noSignal || current == null) {
                Text("이 밴드/네트워크로 잡히는 신호를 찾지 못했습니다. 실외로 이동하거나 잠시 후 다시 시도하세요.")
                return@Column
            }
            if (!matchesSelectedBand) {
                Text(
                    "선택한 밴드(${band?.displayName})가 아니라 현재 실제로 잡고 있는 다른 밴드/셀 정보를 표시 중입니다.",
                    fontWeight = FontWeight.Bold
                )
            }

            LabelValueLine("IMEI (PRIMARY)", current.imei)
            LabelValueLine("MDN", current.mdn)
            SectionDivider()

            val lte = current.lte
            FieldRow(
                listOf("Band/BW", "EN-DC", "EARFCN", "PCI"),
                listOf(
                    "${lte.band ?: "-"}/${lte.bandwidthMHz?.let { "${it}MHz" } ?: "-"}",
                    lte.enDcSupport,
                    "${lte.earfcn}",
                    "${lte.pci}"
                )
            )
            FieldRow(
                listOf("RSRP", "RSRQ", "RSSI/SINR", "RPLMN/TAC"),
                listOf(
                    lte.rsrpDbm?.toString() ?: "-",
                    lte.rsrqDb?.toString() ?: "-",
                    "${lte.rssiDbm ?: "-"}/${lte.sinrDb ?: "-"}",
                    "${lte.rplmn}/${lte.tac ?: "-"}"
                )
            )
            FieldRow(
                listOf("TxPwr", "RB", "MCS", "BLER(D/U)", "DRX"),
                listOf(
                    lte.txPwrDbm?.toString() ?: "-",
                    lte.rb?.toString() ?: "-",
                    lte.mcs?.toString() ?: "-",
                    blerLabel(lte.blerDownPercent, lte.blerUpPercent),
                    lte.drxMs?.let { "${it}ms" } ?: "-"
                )
            )
            Text("* TxPwr/RB/MCS/BLER/DRX는 Android 공개 API로는 제공되지 않는 값입니다 (베이스밴드 내부 값).")

            EquipmentSection(
                title = "LTE PCI ${lte.pci} 장비 조회 결과",
                matches = lteMatches,
                hasLocation = deviceLocation != null
            )

            SectionDivider()
            SectionHeader("NR Information")
            val nr = current.nr
            if (nr == null) {
                Text("NR_Mode: 비활성 (5G 셀 없음)")
            } else {
                LabelValueLine("NR_Mode", nr.mode)
                FieldRow(
                    listOf("Band", "NR-ARFCN", "PCI"),
                    listOf("${nr.band ?: "-"}", "${nr.nrArfcn}", "${nr.pci}")
                )
                FieldRow(
                    listOf("RSRP", "RSRQ", "SSB-SINR"),
                    listOf(
                        nr.rsrpDbm?.toString() ?: "-",
                        nr.rsrqDb?.toString() ?: "-",
                        nr.ssbSinrDb?.toString() ?: "-"
                    )
                )
                Text("* RB/MCS/BLER/TxPwr 등은 LTE와 동일한 이유로 제공되지 않습니다.")
                EquipmentSection(
                    title = "NR PCI ${nr.pci} 장비 조회 결과",
                    matches = nrMatches,
                    hasLocation = deviceLocation != null
                )
            }

            SectionDivider()
            val status = current.status
            LabelValueLine("STATUS", status.status)
            LabelValueLine("RRC", status.rrc)
            LabelValueLine("GUTI", status.guti)
            Text("* RRC 상태/GUTI는 코어망 내부 식별자라 일반 앱에는 원천적으로 제공되지 않습니다.")
        }
    }
}

private fun blerLabel(down: Int?, up: Int?): String =
    if (down == null && up == null) "-" else "${down ?: "-"}%/${up ?: "-"}%"

@Composable
private fun EquipmentSection(
    title: String,
    matches: List<EquipmentMatch>,
    hasLocation: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(10.dp)) {
            SectionHeader(title)
            if (matches.isEmpty()) {
                Text("실제 장비 DB에서 이 PCI로 일치하는 사이트를 찾지 못했습니다.")
            } else {
                if (matches.size > 1) {
                    if (hasLocation) {
                        Text("동일 PCI를 쓰는 후보 ${matches.size}곳 — 내 GPS 위치에서 가까운 순으로 정렬했습니다.")
                    } else {
                        Text("동일 PCI를 쓰는 후보 ${matches.size}곳입니다. (위치를 가져오지 못해 정렬 안 됨)")
                    }
                }
                matches.take(5).forEachIndexed { index, match ->
                    val record = match.record
                    Column(Modifier.padding(vertical = 4.dp)) {
                        if (index == 0 && hasLocation && matches.size > 1) {
                            Text("★ 가장 가까움 (실제로 잡고 있는 장비일 가능성 높음)", fontWeight = FontWeight.Bold)
                        }
                        Text("${record.name} [${record.kind}] · ${record.category}")
                        Text("장비ID: ${record.id}" + (record.duGroupName?.let { " · DU그룹: $it" } ?: ""))
                        Text("주소: ${record.addressLine}")
                        match.distanceMeters?.let { Text("거리: ${"%.0f".format(it)}m") }
                    }
                }
            }
        }
    }
}
