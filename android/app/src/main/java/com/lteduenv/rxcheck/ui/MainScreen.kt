package com.lteduenv.rxcheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lteduenv.rxcheck.MeasureViewModel
import com.lteduenv.rxcheck.UiState
import com.lteduenv.rxcheck.core.analysis.Analysis
import com.lteduenv.rxcheck.core.analysis.Results
import com.lteduenv.rxcheck.core.model.Band
import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import java.util.Locale

internal fun Double.f(d: Int) = String.format(Locale.US, "%.${d}f", this)
private fun signed(v: Double, d: Int = 1) = (if (v >= 0) "+" else "") + v.f(d)

internal val Good = Color(0xFF33C47A)
internal val Warn = Color(0xFFFFB020)
internal val Bad = Color(0xFFFF5D5D)
internal val Dim = Color(0xFF9AA3B2)

@Composable
fun MainScreen(
    state: UiState,
    vm: MeasureViewModel,
    onSaveCsv: () -> Unit,
    onShareCsv: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    val s = state.settings

    Column(Modifier.fillMaxSize().background(ChartColors.background).padding(6.dp)) {
        // Row 1: run control and trace actions
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (state.running) {
                Button(onClick = vm::stop, colors = ButtonDefaults.buttonColors(containerColor = Bad)) {
                    Text(if (state.demo) "■ 데모 정지" else "■ 정지")
                }
            } else {
                Button(onClick = { vm.start(false) }) { Text("측정 시작") }
                OutlinedButton(onClick = { vm.start(true) }) { Text("데모") }
            }
            OutlinedButton(onClick = { showSettings = true }) { Text("측정 설정") }
            FilterChip(selected = s.maxHold, onClick = vm::toggleHold, label = { Text(if (s.maxHold) "Max Hold ON" else "Max Hold OFF") })
            TextButton(onClick = vm::resetHold) { Text("피크 초기화") }
            TextButton(onClick = onSaveCsv, enabled = state.last != null) { Text("CSV 저장") }
            TextButton(onClick = onShareCsv, enabled = state.last != null) { Text("공유") }
        }
        // Row 2: mode, band, auto fit, gain, presets
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Mode.values().forEach { m -> FilterChip(selected = s.mode == m, onClick = { vm.selectMode(m) }, label = { Text(m.title) }) }
            Band.values().forEach { b -> FilterChip(selected = s.band == b, onClick = { vm.selectBand(b) }, label = { Text(b.label) }) }
            OutlinedButton(onClick = vm::autoFit, enabled = state.running) { Text("자동 맞춤") }
            OutlinedButton(onClick = { vm.stepGain(-1) }, enabled = (s.gainStep ?: 0) > 0) { Text("Gain −") }
            Text(s.gainStep?.let { "$it/15" } ?: "AGC", color = Color.White)
            OutlinedButton(onClick = { vm.stepGain(1) }, enabled = (s.gainStep ?: Settings.MAX_GAIN_STEP) < Settings.MAX_GAIN_STEP) { Text("Gain ＋") }
            TextButton(onClick = { showPresets = true }) { Text("빠른 설정 저장/관리") }
        }
        Text(
            "SPAN ${s.spanMhz.f(3)} · START ${s.startMhz.f(3)} · CENTER ${s.centerMhz.f(3)} · STOP ${s.stopMhz.f(3)} MHz · 채널 ${s.channelBwMhz.f(1)} MHz",
            color = Color.White, fontSize = 11.sp, maxLines = 1,
        )
        Text(
            "Ref ${s.refLevelDb.f(1)} · ${s.dbPerDiv.f(0)} dB/div · Offset ${s.offsetDb.f(1)} dB · RBW ${s.rbwKhz.f(1)} kHz · 평균 ${s.averages} · " +
                "IF ${if (s.narrowIf) "좁음" else "6 MHz"} · ${if (s.fastTune) "고속 동조" else "기본 동조"}" +
                (if (s.settleMs > 0) " · 대기 ${s.settleMs} ms" else ""),
            color = Dim, fontSize = 11.sp, maxLines = 1,
        )
        // Row 3: markers and channel power
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val shown = state.shown
            for (m in state.markers) {
                val v = m.freqHz?.let { f -> shown?.let { Analysis.levelAt(it, f, s.offsetDb) } }
                FilterChip(selected = m.index == state.selectedMarker, onClick = { vm.selectMarker(m.index) },
                    label = { Text(if (m.freqHz == null) "M${m.index}" else "M${m.index} ${v?.f(1) ?: "—"}", fontSize = 11.sp) })
            }
            TextButton(onClick = vm::markerToPeak, enabled = state.shown != null) { Text("Peak") }
            TextButton(onClick = vm::clearMarker) { Text("Clear") }
            FilterChip(selected = s.channelPower, onClick = vm::toggleChannelPower, label = { Text("Ch Power") })
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    Chart(state, vm, Modifier.weight(0.66f).fillMaxHeight())
                    Spacer(Modifier.width(8.dp))
                    ResultPanel(state, vm, Modifier.weight(0.34f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Chart(state, vm, Modifier.fillMaxWidth().weight(0.58f))
                    Spacer(Modifier.height(6.dp))
                    ResultPanel(state, vm, Modifier.fillMaxWidth().weight(0.42f))
                }
            }
        }
        val warn = state.clipped
        Text(
            listOfNotNull(state.status, state.device, state.autoFit).joinToString(" · "),
            color = if (warn) Bad else Dim, fontSize = 11.sp, maxLines = 2,
        )
    }

    if (showSettings) SettingsDialog(s, onDismiss = { showSettings = false }, onApply = { new ->
        vm.apply(new).also { if (it == null) showSettings = false }
    })
    if (showPresets) PresetDialog(state, vm) { showPresets = false }
    state.error?.let { msg ->
        AlertDialog(onDismissRequest = vm::dismissError, confirmButton = { TextButton(onClick = vm::dismissError) { Text("확인") } },
            title = { Text("측정 오류") }, text = { Text(msg) })
    }
}

@Composable
private fun Chart(state: UiState, vm: MeasureViewModel, modifier: Modifier) {
    val s = state.settings
    SpectrumChart(
        live = state.live, livePoints = state.livePoints,
        hold = if (s.maxHold) state.hold else null, baseline = state.baseline,
        offsetDb = s.offsetDb, refLevelDb = s.refLevelDb, dbPerDiv = s.dbPerDiv,
        startHz = s.startMhz * 1e6, stopHz = s.stopMhz * 1e6,
        channelHz = s.channelHz(), peaks = state.results?.peaks ?: emptyList(),
        markers = state.markers, selectedMarker = state.selectedMarker,
        onTap = vm::placeMarker, onRefDrag = vm::dragRef, onRefDragEnd = vm::commitRef, onPinch = vm::zoomSpan,
        modifier = modifier,
    )
}

@Composable
private fun PresetDialog(state: UiState, vm: MeasureViewModel, onDismiss: () -> Unit) {
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text("빠른 설정 저장/관리") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val s = state.settings
                Text("현재: ${s.mode.title} · ${s.centerMhz.f(3)} MHz / Span ${s.spanMhz.f(2)} · RBW ${s.rbwKhz.f(1)} kHz · 이득 ${s.gainStep ?: "AGC"} · Offset ${s.offsetDb.f(1)} dB",
                    fontSize = 12.sp)
                for (m in Mode.values()) {
                    Text(m.title + if (m in state.presets) " (저장값 있음)" else "", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { vm.savePreset(m); message = "${m.title}에 저장했습니다" }) { Text("현재 값 저장") }
                        TextButton(enabled = m in state.presets, onClick = { message = vm.loadPreset(m) ?: "${m.title} 저장값을 불러왔습니다" }) { Text("불러오기") }
                        TextButton(enabled = m in state.presets, onClick = { vm.clearPreset(m); message = "${m.title} 저장값을 지웠습니다" }) { Text("초기화") }
                    }
                }
                Text("상단 모드·대역 버튼은 기본 시작값을 적용합니다. 현장에 맞춘 값은 여기서 저장하고 불러옵니다. 앱 재실행 후에도 유지됩니다.",
                    fontSize = 11.sp, color = Dim)
                message?.let { Text(it, color = Good, fontSize = 12.sp) }
            }
        },
    )
}

@Composable
private fun ResultPanel(state: UiState, vm: MeasureViewModel, modifier: Modifier) {
    val s = state.settings
    val r = state.results
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.markers.firstOrNull { it.index == state.selectedMarker }?.freqHz?.let { f ->
            val v = state.shown?.let { Analysis.levelAt(it, f, s.offsetDb) }
            Text("M${state.selectedMarker} ${(f / 1e6).f(4)} MHz · ${v?.f(2) ?: "—"} dB", color = ChartColors.marker, fontSize = 13.sp)
        }
        if (r == null) {
            Text(if (state.running) "첫 스윕 진행 중…" else "측정 대기 · 데모로 화면을 먼저 볼 수 있습니다", color = Dim)
        } else {
            if (s.channelPower || s.mode == Mode.REVERSE) r.channel?.let { ch ->
                Text("채널 전력 ${ch.totalDb.f(1)} dB", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("PSD ${ch.psdDbPerMhz.f(1)} dB/MHz · ${(ch.bwHz / 1e6).f(1)} MHz 적분", color = Color.White, fontSize = 13.sp)
            } ?: Text("채널이 Span 밖에 있어 채널 전력을 계산할 수 없습니다", color = Warn, fontSize = 12.sp)
            when (s.mode) {
                Mode.REVERSE -> ReverseResults(state, r, vm)
                Mode.SPURIOUS -> SpuriousResults(state, r)
            }
        }
        state.timing?.let { t ->
            val usb = t.usb
            val segs = state.last?.plan?.segments?.size ?: 0
            Text(
                "스윕 ${t.totalMs} ms (${segs}구간, ${if (t.totalMs > 0) (1000.0 / t.totalMs).f(1) else "-"}회/초)" +
                    (usb?.let { u ->
                        val n = u.controlOut + u.controlIn
                        " · USB 제어 ${n}회" + (if (segs > 0) " (구간당 ${(n.toDouble() / segs).f(1)})" else "")
                    } ?: ""),
                color = Dim, fontSize = 11.sp,
            )
        }
        Text("레벨은 dBFS(+Offset) 상대값입니다. 교정된 dBm이 아닙니다.", color = Dim, fontSize = 10.sp)
    }
}

@Composable
private fun ReverseResults(state: UiState, r: Results, vm: MeasureViewModel) {
    val s = state.settings
    r.riseDb?.let {
        Text("기준 대비 ${signed(it)} dB", fontSize = 16.sp, color = if (it > s.thresholdDb) Bad else if (it > 3) Warn else Good)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = vm::saveBaseline, enabled = state.last != null) { Text("현재를 기준으로 저장") }
        if (state.baseline != null) TextButton(onClick = vm::clearBaseline) { Text("기준 삭제") }
    }
    state.baselineTime?.let {
        Text("기준: " + java.text.SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(java.util.Date(it)) +
            " (같은 Span·RBW·이득·IF 설정일 때만 비교)", color = Dim, fontSize = 11.sp)
    }
    if (r.blocks.isNotEmpty()) {
        Text("1 MHz 블록 PSD (중앙값 대비${if (state.baseline != null) " / 기준 대비" else ""})", color = Dim, fontSize = 12.sp)
        BlockBars(r, s.thresholdDb)
        for (b in r.blocks) {
            val hot = b.aboveMedianDb > s.thresholdDb || (b.riseDb ?: 0.0) > s.thresholdDb
            Text(
                "${(b.startHz / 1e6).f(1)}–${(b.stopHz / 1e6).f(1)}  ${b.psdDbPerMhz.f(1)} dB/MHz  ${signed(b.aboveMedianDb)}" +
                    (b.riseDb?.let { "  기준 ${signed(it)}" } ?: "") + if (hot) "  ◀ 간섭 의심" else "",
                color = if (hot) Bad else Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun BlockBars(r: Results, threshold: Double) {
    val maxDev = (r.blocks.maxOfOrNull { maxOf(it.aboveMedianDb, it.riseDb ?: 0.0) } ?: 0.0).coerceAtLeast(threshold * 1.5)
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom) {
        for (b in r.blocks) {
            val dev = maxOf(b.aboveMedianDb, b.riseDb ?: 0.0)
            val frac = ((dev / maxDev).coerceIn(0.05, 1.0)).toFloat()
            Box(Modifier.weight(1f).fillMaxHeight(frac)
                .background(if (dev > threshold) Bad else if (dev > threshold / 2) Warn else Good))
        }
    }
}

@Composable
private fun SpuriousResults(state: UiState, r: Results) {
    val s = state.settings
    r.floorDbPerMhz?.let { Text("노이즈 플로어 ${it.f(1)} dB/MHz", color = Color.White, fontSize = 14.sp) }
    Text(
        "불요파 후보 ${r.peaks.size}건 (플로어 +${s.thresholdDb.f(0)} dB 이상${if (s.maxHold && state.hold != null) ", Max Hold 기준" else ""})",
        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (r.peaks.isEmpty()) Good else Warn,
    )
    if (r.peaks.isNotEmpty())
        Text("주파수(MHz)   레벨    플로어대비  폭(kHz)  위치", color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    for (p in r.peaks) {
        Text(
            String.format(Locale.US, "%10.4f  %6.1f   %+6.1f   %7.1f  %s", p.freqHz / 1e6, p.levelDb, p.aboveFloorDb,
                p.bw10dBHz / 1e3, if (p.inChannel) "RX대역 내" else "대역 외"),
            color = if (p.inChannel) Bad else Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        )
    }
}
