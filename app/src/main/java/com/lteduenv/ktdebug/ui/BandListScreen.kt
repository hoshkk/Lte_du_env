package com.lteduenv.ktdebug.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lteduenv.ktdebug.model.BandDefinition
import com.lteduenv.ktdebug.model.KtBandCatalog
import com.lteduenv.ktdebug.model.NetworkType

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BandListScreen(
    selectedForCompare: Set<BandKey>,
    onToggleSelect: (BandKey) -> Unit,
    onOpenDebug: (BandKey) -> Unit,
    onCompare: () -> Unit,
    onOpenNearby: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("KT Debug Viewer - 밴드 선택") }) }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SectionCaption("LTE 밴드") }
            items(KtBandCatalog.lteBands) { band ->
                BandRow(band, selectedForCompare, onToggleSelect, onOpenDebug)
            }
            item { SectionCaption("NR(5G) 밴드") }
            items(KtBandCatalog.nrBands) { band ->
                BandRow(band, selectedForCompare, onToggleSelect, onOpenDebug)
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("체크박스로 여러 밴드를 선택하면 동시 비교 화면을 볼 수 있습니다.")
                    Button(
                        onClick = onCompare,
                        enabled = selectedForCompare.size >= 2,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("선택한 ${selectedForCompare.size}개 밴드 비교하기")
                    }
                    OutlinedButton(
                        onClick = onOpenNearby,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("GPS로 내 주변 PCI/장비 보기")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun BandRow(
    band: BandDefinition,
    selectedForCompare: Set<BandKey>,
    onToggleSelect: (BandKey) -> Unit,
    onOpenDebug: (BandKey) -> Unit
) {
    val key = BandKey(band.networkType, band.band, band.bandwidthMHz)
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = key in selectedForCompare, onCheckedChange = { onToggleSelect(key) })
                Column {
                    Text("${band.displayName} (${band.frequencyLabel})")
                    Text("${band.duplexMode} · ${if (band.networkType == NetworkType.NR) "NR" else "LTE"}")
                }
            }
            OutlinedButton(onClick = { onOpenDebug(key) }) {
                Text("상세보기")
            }
        }
    }
}
