package com.lteduenv.ktdebug.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.ContextCompat
import com.lteduenv.ktdebug.data.CurrentLocationProvider
import com.lteduenv.ktdebug.data.EquipmentRepository
import com.lteduenv.ktdebug.data.MockDebugDataGenerator
import com.lteduenv.ktdebug.model.DebugSnapshot
import com.lteduenv.ktdebug.model.EquipmentMatch
import com.lteduenv.ktdebug.model.KtBandCatalog
import com.lteduenv.ktdebug.model.NetworkType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    bandKey: BandKey,
    equipmentRepository: EquipmentRepository,
    generator: MockDebugDataGenerator,
    onBack: () -> Unit
) {
    val band = remember(bandKey) { KtBandCatalog.find(bandKey.networkType, bandKey.band, bandKey.bandwidthMHz) }
    val context = LocalContext.current
    val locationProvider = remember { CurrentLocationProvider(context) }

    var refreshCounter by remember { mutableIntStateOf(0) }
    var snapshot by remember(bandKey) { mutableStateOf<DebugSnapshot?>(null) }
    var lteMatches by remember(bandKey) { mutableStateOf<List<EquipmentMatch>>(emptyList()) }
    var nrMatches by remember(bandKey) { mutableStateOf<List<EquipmentMatch>>(emptyList()) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var deviceLocation by remember { mutableStateOf<Location?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPermission = granted }

    // GPS 위치를 알면, 잡고 있는 PCI가 재사용되는 사이트 중 실제로 내 근처에 있는 것을
    // 가장 가까운 순으로 우선 보여줄 수 있다 (findByPci가 deviceLocation으로 거리순 정렬).
    LaunchedEffect(bandKey, refreshCounter, hasLocationPermission) {
        val b = band ?: return@LaunchedEffect
        val location = if (hasLocationPermission) locationProvider.getCurrentLocation() else null
        deviceLocation = location
        val result = generator.generate(b)
        snapshot = result
        lteMatches = equipmentRepository.findByPci(NetworkType.LTE, result.lte.pci, deviceLocation = location)
        nrMatches = result.nr?.let {
            equipmentRepository.findByPci(NetworkType.NR, it.pci, deviceLocation = location)
        } ?: emptyList()
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
                    IconButton(onClick = { refreshCounter++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                    }
                }
            )
        }
    ) { padding ->
        val current = snapshot
        if (current == null || band == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            LabelValueLine("IMEI (PRIMARY)", current.imei)
            LabelValueLine("MDN", current.mdn)
            SectionDivider()

            val lte = current.lte
            FieldRow(
                listOf("Band/BW", "EN-DC", "EARFCN", "PCI"),
                listOf("${lte.band}/${lte.bandwidthMHz}MHz", lte.enDcSupport, "${lte.earfcn}", "${lte.pci}")
            )
            FieldRow(
                listOf("RSRP", "RSRQ", "RSSI/SINR", "RPLMN/TAC"),
                listOf("${lte.rsrpDbm}", "${lte.rsrqDb}", "${lte.rssiDbm}/${lte.sinrDb}", "${lte.rplmn}/${lte.tac}")
            )
            FieldRow(
                listOf("AvgRSRP", "AvgRSRQ", "ANT/Diff", "CQI/RI"),
                listOf("${lte.avgRsrpDbm}", "${lte.avgRsrqDb}", "${lte.antDiffDb}", "${lte.cqi}/${lte.ri}")
            )
            FieldRow(
                listOf("TxPwr", "TxPusch", "TxPucch", "Tx(SRS)"),
                listOf("${lte.txPwrDbm}", "${lte.txPuschDbm}", "${lte.txPucchDbm}", lte.txSrs)
            )
            FieldRow(
                listOf("RB", "MCS", "MOD(QAM)", "BLER(D/U)", "DRX"),
                listOf(
                    "${lte.rb}", "${lte.mcs}", lte.modulation,
                    "${lte.blerDownPercent}%/${lte.blerUpPercent}%", "${lte.drxMs}ms"
                )
            )

            EquipmentSection(
                title = "LTE PCI ${lte.pci} 장비 조회 결과",
                matches = lteMatches,
                hasLocation = hasLocationPermission && deviceLocation != null,
                onRequestLocation = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
            )

            SectionDivider()
            SectionHeader("NR Information")
            val nr = current.nr
            if (nr == null) {
                Text("NR_Mode: 비활성 (EN-DC 없음)")
            } else {
                LabelValueLine("NR_Mode", nr.mode)
                FieldRow(
                    listOf("Band/BW SCG", "NR-ARFCN", "PCI"),
                    listOf("${nr.band}/${nr.bandwidthMHz} ${nr.scgState}", "${nr.nrArfcn}", "${nr.pci}")
                )
                FieldRow(
                    listOf("RSRP", "RSRQ", "SSB-SINR", "CQI/RI"),
                    listOf("${nr.rsrpDbm}", "${nr.rsrqDb}", "${nr.ssbSinrDb}", "${nr.cqi}/${nr.ri}")
                )
                FieldRow(
                    listOf("RB", "MCS", "MOD(QAM)", "BLER"),
                    listOf("${nr.rb}", "${nr.mcs}", "${nr.modulation}", "${nr.blerPercent}%")
                )
                FieldRow(
                    listOf("UpLayerInd", "RestrictDCNR"),
                    listOf(if (nr.upperLayerIndSupport) "Support" else "-", "${nr.restrictDcNr}")
                )
                FieldRow(
                    listOf("NRTxPwr", "EN-DCTotalTxPwr"),
                    listOf("${nr.nrTxPwrDbm}", "${nr.enDcTotalTxPwrDbm}")
                )
                EquipmentSection(
                    title = "NR PCI ${nr.pci} 장비 조회 결과",
                    matches = nrMatches,
                    hasLocation = hasLocationPermission && deviceLocation != null,
                    onRequestLocation = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                )
            }

            SectionDivider()
            val status = current.status
            LabelValueLine("STATUS", status.status)
            LabelValueLine("SUB STATUS", status.subStatus)
            LabelValueLine("ESM CAUSE", "${status.esmCause}")
            LabelValueLine("RRC", status.rrc)
            LabelValueLine("RRE REQ CAUSE", status.rreReqCause)
            LabelValueLine("SCG FAIL CAUSE", status.scgFailCause)
            LabelValueLine("GUTI", status.guti)
        }
    }
}

@Composable
private fun EquipmentSection(
    title: String,
    matches: List<EquipmentMatch>,
    hasLocation: Boolean,
    onRequestLocation: () -> Unit
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
                        Text("동일 PCI를 쓰는 후보 ${matches.size}곳입니다. 위치 권한을 허용하면 실제로 잡고 있을 가능성이 높은(가장 가까운) 장비를 먼저 보여줍니다.")
                        TextButton(onClick = onRequestLocation) {
                            Text("GPS로 내 근처 장비 찾기")
                        }
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
