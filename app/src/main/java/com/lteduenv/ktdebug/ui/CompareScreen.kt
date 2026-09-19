package com.lteduenv.ktdebug.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lteduenv.ktdebug.data.EquipmentRepository
import com.lteduenv.ktdebug.data.MockDebugDataGenerator
import com.lteduenv.ktdebug.model.DebugSnapshot
import com.lteduenv.ktdebug.model.KtBandCatalog
import com.lteduenv.ktdebug.model.NetworkType

private data class CompareRow(
    val bandLabel: String,
    val networkType: NetworkType,
    val pci: Int,
    val rsrp: Int?,
    val sinr: Double?,
    val txPwr: Int?,
    val rsrq: Int?,
    val equipmentName: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    bandKeys: List<BandKey>,
    equipmentRepository: EquipmentRepository,
    generator: MockDebugDataGenerator,
    onBack: () -> Unit
) {
    val rowsState = produceState(initialValue = emptyList<CompareRow>(), bandKeys) {
        val result = mutableListOf<CompareRow>()
        for (key in bandKeys) {
            val band = KtBandCatalog.find(key.networkType, key.band, key.bandwidthMHz) ?: continue
            val snapshot: DebugSnapshot = generator.generate(band)
            result += if (key.networkType == NetworkType.NR && snapshot.nr != null) {
                val nr = snapshot.nr
                val matches = equipmentRepository.findByPci(NetworkType.NR, nr.pci)
                CompareRow(
                    bandLabel = band.displayName,
                    networkType = NetworkType.NR,
                    pci = nr.pci,
                    rsrp = nr.rsrpDbm,
                    sinr = nr.ssbSinrDb,
                    txPwr = nr.nrTxPwrDbm,
                    rsrq = nr.rsrqDb,
                    equipmentName = matches.firstOrNull()?.record?.name ?: "미확인"
                )
            } else {
                val lte = snapshot.lte
                val matches = equipmentRepository.findByPci(NetworkType.LTE, lte.pci)
                CompareRow(
                    bandLabel = band.displayName,
                    networkType = NetworkType.LTE,
                    pci = lte.pci,
                    rsrp = lte.rsrpDbm,
                    sinr = lte.sinrDb,
                    txPwr = lte.txPwrDbm,
                    rsrq = lte.rsrqDb,
                    equipmentName = matches.firstOrNull()?.record?.name ?: "미확인"
                )
            }
        }
        value = result
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            CompareHeaderRow()
            HorizontalDivider()
            LazyColumn {
                items(rowsState.value) { row -> CompareDataRow(row) }
            }
        }
    }
}

private val columnWidth = 96.dp

// Band/PCI/RSRP/SINR/TxPwr are the required columns; RSRQ and 장비명 are shown after them for extra context.
@Composable
private fun CompareHeaderRow() {
    Row(Modifier.padding(vertical = 6.dp)) {
        listOf("밴드", "PCI", "RSRP", "SINR", "TxPwr", "RSRQ", "장비명").forEach {
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
        Text(row.bandLabel, fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth))
        Text("${row.pci}", fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth))
        Text(row.rsrp?.toString() ?: "-", fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth))
        Text(row.sinr?.toString() ?: "-", fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth))
        Text(row.txPwr?.toString() ?: "-", fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth))
        Text(row.rsrq?.toString() ?: "-", fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth))
        Text(row.equipmentName, fontFamily = FontFamily.Monospace, modifier = Modifier.width(columnWidth * 2))
    }
    HorizontalDivider()
}
