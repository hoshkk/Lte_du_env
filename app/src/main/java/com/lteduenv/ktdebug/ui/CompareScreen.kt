package com.lteduenv.ktdebug.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lteduenv.ktdebug.data.EquipmentRepository
import com.lteduenv.ktdebug.data.LiveCellInfoProvider
import com.lteduenv.ktdebug.model.KtBandCatalog
import com.lteduenv.ktdebug.model.NetworkType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class CompareRow(
    val bandLabel: String,
    val found: Boolean,
    val pci: Int?,
    val rsrp: Int?,
    val sinr: Double?,
    val rsrq: Int?,
    val equipmentName: String
)

private const val POLL_INTERVAL_MS = 1500L
private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.ACCESS_FINE_LOCATION
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    bandKeys: List<BandKey>,
    equipmentRepository: EquipmentRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val liveProvider = remember { LiveCellInfoProvider(context) }

    var hasPermissions by remember { mutableStateOf(liveProvider.hasRequiredPermissions()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasPermissions = result.values.all { it } }

    var rows by remember { mutableStateOf<List<CompareRow>>(emptyList()) }

    // Real-time: only bands the device is actually camping on right now show real values;
    // a normal app can't force the modem onto a specific band, so the rest read "안 잡힘".
    LaunchedEffect(bandKeys, hasPermissions) {
        if (!hasPermissions) return@LaunchedEffect
        while (isActive) {
            val liveLte = liveProvider.currentLteCells()
            val liveNr = liveProvider.currentNrCells()
            rows = bandKeys.mapNotNull { key ->
                val band = KtBandCatalog.find(key.networkType, key.band, key.bandwidthMHz) ?: return@mapNotNull null
                val cellPci: Int?
                val rsrp: Int?
                val sinr: Double?
                val rsrq: Int?
                when (key.networkType) {
                    NetworkType.LTE -> {
                        val cell = liveLte.firstOrNull { it.band == band.band }
                        cellPci = cell?.pci; rsrp = cell?.rsrpDbm; sinr = cell?.sinrDb; rsrq = cell?.rsrqDb
                    }
                    NetworkType.NR -> {
                        val cell = liveNr.firstOrNull { it.band == band.band }
                        cellPci = cell?.pci; rsrp = cell?.rsrpDbm; sinr = cell?.ssbSinrDb; rsrq = cell?.rsrqDb
                    }
                }
                val equipmentName = cellPci?.let { pci ->
                    equipmentRepository.findByPci(key.networkType, pci).firstOrNull()?.record?.name
                } ?: "-"
                CompareRow(band.displayName, cellPci != null, cellPci, rsrp, sinr, rsrq, equipmentName)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("밴드 비교 (${bandKeys.size}개)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        if (!hasPermissions) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("실제 값으로 비교하려면 전화 상태 권한과 위치 권한이 필요합니다.")
                Button(
                    onClick = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("권한 허용")
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp)
        ) {
            Text("TxPwr/RB/MCS 등은 Android가 제공하지 않아 표에서 뺐습니다. 현재 실제로 잡히는 밴드만 값이 나옵니다.")
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                CompareHeaderRow()
                HorizontalDivider()
                LazyColumn {
                    items(rows) { row -> CompareDataRow(row) }
                }
            }
        }
    }
}

private val columnWidth = 96.dp

@Composable
private fun CompareHeaderRow() {
    Row(Modifier.padding(vertical = 6.dp)) {
        listOf("밴드", "PCI", "RSRP", "SINR", "RSRQ", "장비명").forEach {
            Text(
                text = it,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(if (it == "장비명") columnWidth * 2 else columnWidth)
            )
        }
    }
}

@Composable
private fun CompareDataRow(row: CompareRow) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(
            if (row.found) row.bandLabel else "${row.bandLabel}(안 잡힘)",
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(columnWidth)
        )
        Text(row.pci?.toString() ?: "-", fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth))
        Text(row.rsrp?.toString() ?: "-", fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth))
        Text(row.sinr?.toString() ?: "-", fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth))
        Text(row.rsrq?.toString() ?: "-", fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth))
        Text(row.equipmentName, fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth * 2))
    }
    HorizontalDivider()
}
