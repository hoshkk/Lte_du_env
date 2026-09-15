@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lteduenv.spectrum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lteduenv.spectrum.data.BandPresets
import com.lteduenv.spectrum.viewmodel.DataSourceMode
import com.lteduenv.spectrum.viewmodel.SpectrumUiState
import com.lteduenv.spectrum.viewmodel.SpectrumViewModel

@Composable
fun SpectrumApp(viewModel: SpectrumViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(containerColor = AnalyzerColors.Background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
        ) {
            TopBar(onSettingsClick = { showSettings = true })
            Spacer(Modifier.height(6.dp))

            BandRow(state, onSelect = viewModel::selectBand)
            Spacer(Modifier.height(6.dp))
            MarkerRow(
                state,
                onSelectMarker = viewModel::selectMarker,
                onPeak = viewModel::peakSearch,
                onClear = viewModel::clearSelectedMarker,
                onToggleChannelPower = viewModel::setChannelPowerEnabled,
            )
            Spacer(Modifier.height(6.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                SpectrumTraceCanvas(
                    frame = state.spectrumFrame,
                    refLevelDbm = state.config.refLevelDbm,
                    markers = state.markers,
                    selectedMarker = state.selectedMarker,
                    modifier = Modifier.fillMaxSize(),
                    onTapFrequency = viewModel::placeMarkerAtFrequency,
                )
            }

            if (state.channelPowerEnabled) {
                Spacer(Modifier.height(6.dp))
                ChannelPowerBar(state)
            }
            Spacer(Modifier.height(6.dp))
            BottomInfoBar(state)
        }
    }

    if (showSettings) {
        SettingsDialog(state = state, viewModel = viewModel, onDismiss = { showSettings = false })
    }
}

@Composable
private fun TopBar(onSettingsClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            "Spectrum Check - LTE UL",
            color = AnalyzerColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = AnalyzerColors.TextSecondary)
        }
    }
}

@Composable
private fun BandRow(state: SpectrumUiState, onSelect: (com.lteduenv.spectrum.data.BandPreset) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BandPresets.all.forEach { preset ->
            FilterChip(
                selected = state.selectedBandId == preset.id,
                onClick = { onSelect(preset) },
                label = { Text(preset.label, fontSize = 12.sp) },
            )
        }
    }
}

@Composable
private fun MarkerRow(
    state: SpectrumUiState,
    onSelectMarker: (Int) -> Unit,
    onPeak: () -> Unit,
    onClear: () -> Unit,
    onToggleChannelPower: (Boolean) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.markers.forEach { marker ->
            FilterChip(
                selected = state.selectedMarker == marker.index,
                onClick = { onSelectMarker(marker.index) },
                label = {
                    Text(
                        if (marker.enabled) "M${marker.index} %.1f".format(marker.levelDbm) else "M${marker.index}",
                        fontSize = 11.sp,
                    )
                },
            )
        }
        Spacer(Modifier.width(4.dp))
        Button(onClick = onPeak) {
            Icon(Icons.Filled.GpsFixed, contentDescription = null, modifier = Modifier.width(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Peak", fontSize = 12.sp)
        }
        Button(onClick = onClear) { Text("Clear", fontSize = 12.sp) }
        FilterChip(
            selected = state.channelPowerEnabled,
            onClick = { onToggleChannelPower(!state.channelPowerEnabled) },
            label = { Text("Ch Power", fontSize = 11.sp) },
        )
    }
}

/** MEASURE > Channel Power readout: total integrated power over the Integration BW, plus its PSD. */
@Composable
private fun ChannelPowerBar(state: SpectrumUiState) {
    val channelPowerDbm = state.channelPowerDbm ?: return
    val integrationBwMhz = state.config.integrationBwMhz
    val psdDbmPerMhz = channelPowerDbm - 10.0 * kotlin.math.log10(integrationBwMhz)
    Row(
        Modifier
            .fillMaxWidth()
            .background(AnalyzerColors.Panel, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        InfoField("Total Ch Power", "%.2f dBm".format(channelPowerDbm))
        InfoField("PSD", "%.2f dBm/MHz".format(psdDbmPerMhz))
        InfoField("Integration BW", "%.1f MHz".format(integrationBwMhz))
    }
}

@Composable
private fun BottomInfoBar(state: SpectrumUiState) {
    val config = state.config
    // USB SDR dongles ignore the requested span and always capture their fixed sample-rate
    // bandwidth (~2.4 MHz for RTL-SDR, 8 MHz for HackRF), so once a real frame has arrived, show
    // what was actually captured rather than echoing back the (unused) requested span.
    val displaySpanMhz = state.spectrumFrame?.let { it.stopMhz - it.startMhz } ?: config.spanMhz
    val isUncalibrated = state.dataSourceMode == DataSourceMode.USB_SDR
    Row(
        Modifier
            .fillMaxWidth()
            .background(AnalyzerColors.Panel, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        InfoField("Center", "%.2f MHz".format(config.centerMhz))
        InfoField("Span", "%.2f MHz".format(displaySpanMhz))
        if (config.refLevelOffsetDb != 0.0) {
            InfoField("Offset", "%.1f dB".format(config.refLevelOffsetDb))
        }
        Spacer(Modifier.weight(1f))
        InfoField("Source", state.dataSourceLabel)
    }
    if (isUncalibrated) {
        Text(
            "USB SDR's own gain chain is relative (uncalibrated), not absolute dBm - the Ref level offset above " +
                "only corrects for port/cable loss you entered, not this. Do not use for pass/fail power limits.",
            color = AnalyzerColors.Warn,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
    state.sourceStatusMessage?.let { message ->
        Text(
            message,
            color = AnalyzerColors.Warn,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

@Composable
private fun InfoField(label: String, value: String) {
    Column {
        Text(label, color = AnalyzerColors.TextSecondary, fontSize = 10.sp)
        Text(value, color = AnalyzerColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsDialog(state: SpectrumUiState, viewModel: SpectrumViewModel, onDismiss: () -> Unit) {
    var centerText by remember(state.config.centerMhz) { mutableStateOf(state.config.centerMhz.toString()) }
    var spanText by remember(state.config.spanMhz) { mutableStateOf(state.config.spanMhz.toString()) }
    var refOffsetText by remember(state.config.refLevelOffsetDb) { mutableStateOf(state.config.refLevelOffsetDb.toString()) }
    var rbwText by remember(state.config.rbwKhz) { mutableStateOf(state.config.rbwKhz.toString()) }
    var vbwText by remember(state.config.vbwKhz) { mutableStateOf(state.config.vbwKhz.toString()) }
    var integrationBwText by remember(state.config.integrationBwMhz) { mutableStateOf(state.config.integrationBwMhz.toString()) }
    var sourceMode by remember(state.dataSourceMode) { mutableStateOf(state.dataSourceMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = centerText,
                    onValueChange = { centerText = it },
                    label = { Text("Center (MHz)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = spanText,
                    onValueChange = { spanText = it },
                    label = { Text("Span (MHz)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rbwText,
                        onValueChange = { rbwText = it },
                        label = { Text("RBW (kHz)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = vbwText,
                        onValueChange = { vbwText = it },
                        label = { Text("VBW (kHz)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = integrationBwText,
                    onValueChange = { integrationBwText = it },
                    label = { Text("Integration BW (MHz)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = refOffsetText,
                    onValueChange = { refOffsetText = it },
                    label = { Text("Ref level offset (dB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(12.dp))
                Text("Data source", fontSize = 12.sp, color = AnalyzerColors.TextSecondary)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = sourceMode == DataSourceMode.SIMULATED,
                        onClick = { sourceMode = DataSourceMode.SIMULATED },
                        label = { Text("Simulated", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = sourceMode == DataSourceMode.USB_SDR,
                        onClick = { sourceMode = DataSourceMode.USB_SDR },
                        label = { Text("USB SDR", fontSize = 11.sp) },
                    )
                }
                if (sourceMode == DataSourceMode.USB_SDR) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Preamp (HackRF only)", fontSize = 12.sp, color = AnalyzerColors.TextSecondary)
                        Switch(
                            checked = state.config.preampEnabled,
                            onCheckedChange = viewModel::setPreampEnabled,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = viewModel::connectUsbSdr) { Text("Connect USB SDR") }
                    state.sourceStatusMessage?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, fontSize = 11.sp, color = AnalyzerColors.Warn)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                centerText.toDoubleOrNull()?.let(viewModel::setCenterMhz)
                spanText.toDoubleOrNull()?.let(viewModel::setSpanMhz)
                rbwText.toDoubleOrNull()?.let(viewModel::setRbwKhz)
                vbwText.toDoubleOrNull()?.let(viewModel::setVbwKhz)
                integrationBwText.toDoubleOrNull()?.let(viewModel::setIntegrationBwMhz)
                refOffsetText.toDoubleOrNull()?.let(viewModel::setRefLevelOffsetDb)
                if (sourceMode != DataSourceMode.USB_SDR) {
                    viewModel.applyDataSource(sourceMode)
                }
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
