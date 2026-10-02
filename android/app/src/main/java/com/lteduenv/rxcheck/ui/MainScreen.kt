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
import com.lteduenv.rxcheck.UiState
import com.lteduenv.rxcheck.core.analysis.Results
import com.lteduenv.rxcheck.core.model.Band
import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import java.util.Locale

private fun Double.f(d: Int) = String.format(Locale.US, "%.${d}f", this)
private fun signed(v: Double, d: Int = 1) = (if (v >= 0) "+" else "") + v.f(d)

private val Good = Color(0xFF33C47A)
private val Warn = Color(0xFFFFB020)
private val Bad = Color(0xFFFF5D5D)
private val Dim = Color(0xFF9AA3B2)

@Composable
fun MainScreen(
    state: UiState,
    onStart: (demo: Boolean) -> Unit,
    onStop: () -> Unit,
    onMode: (Mode) -> Unit,
    onBand: (Band) -> Unit,
    onApply: (Settings) -> String?,
    onHoldToggle: () -> Unit,
    onResetHold: () -> Unit,
    onSaveBaseline: () -> Unit,
    onClearBaseline: () -> Unit,
    onExport: () -> Unit,
    onDismissError: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var markerHz by remember { mutableStateOf<Double?>(null) }
    val s = state.settings

    Column(Modifier.fillMaxSize().background(ChartColors.background).padding(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (state.running) {
                Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Bad)) { Text("정지") }
            } else {
                Button(onClick = { onStart(false) }) { Text("측정 시작") }
                OutlinedButton(onClick = { onStart(true) }) { Text("데모") }
            }
            Mode.values().forEach { m -> FilterChip(selected = s.mode == m, onClick = { onMode(m) }, label = { Text(m.title) }) }
            Spacer(Modifier.width(4.dp))
            Band.values().forEach { b -> FilterChip(selected = s.band == b, onClick = { onBand(b) }, label = { Text(b.label) }) }
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = s.maxHold, onClick = onHoldToggle, label = { Text("Max Hold") })
            TextButton(onClick = onResetHold) { Text("Hold 초기화") }
            TextButton(onClick = { showSettings = true }) { Text("설정") }
            TextButton(onClick = onExport, enabled = state.last != null) { Text("CSV") }
        }
        Text(
            "${state.status}${state.device?.let { " · $it" } ?: ""} · ${s.startMhz.f(3)}–${s.stopMhz.f(3)} MHz · " +
                "RBW ${s.rbwKhz.f(1)} kHz · 평균 ${s.averages} · 이득 ${s.gainStep?.let { "$it/15" } ?: "AGC"}" +
                (if (s.offsetDb != 0.0) " · Offset ${signed(s.offsetDb)} dB" else ""),
            color = Dim, fontSize = 12.sp, maxLines = 2,
        )
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth > maxHeight
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Chart(state, markerHz, { markerHz = it }, Modifier.weight(0.64f).fillMaxHeight())
                    Spacer(Modifier.width(8.dp))
                    ResultPanel(state, markerHz, onSaveBaseline, onClearBaseline, Modifier.weight(0.36f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Chart(state, markerHz, { markerHz = it }, Modifier.fillMaxWidth().weight(0.55f))
                    Spacer(Modifier.height(6.dp))
                    ResultPanel(state, markerHz, onSaveBaseline, onClearBaseline, Modifier.fillMaxWidth().weight(0.45f))
                }
            }
        }
    }

    if (showSettings) SettingsDialog(s, onDismiss = { showSettings = false }, onApply = { new ->
        onApply(new).also { if (it == null) showSettings = false }
    })
    state.error?.let { msg ->
        AlertDialog(onDismissRequest = onDismissError, confirmButton = { TextButton(onClick = onDismissError) { Text("확인") } },
            title = { Text("측정 오류") }, text = { Text(msg) })
    }
}

@Composable
private fun Chart(state: UiState, markerHz: Double?, onMarker: (Double) -> Unit, modifier: Modifier) {
    val s = state.settings
    SpectrumChart(
        live = state.live, livePoints = state.livePoints, hold = state.hold, baseline = state.baseline,
        offsetDb = s.offsetDb, refLevelDb = s.refLevelDb, dbPerDiv = s.dbPerDiv,
        channelHz = s.channelHz(), peaks = state.results?.peaks ?: emptyList(),
        markerHz = markerHz, onTap = onMarker, modifier = modifier,
    )
}

@Composable
private fun ResultPanel(state: UiState, markerHz: Double?, onSaveBaseline: () -> Unit, onClearBaseline: () -> Unit,
                        modifier: Modifier) {
    val s = state.settings
    val r = state.results
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        markerHz?.let { f ->
            val t = state.live
            val v = t?.let { tr ->
                val i = ((f - tr.plan.startHz) / tr.plan.binHz).toInt().coerceIn(0, tr.points - 1)
                tr.levelsDb[i].takeIf { it.isFinite() }?.let { it + s.offsetDb }
            }
            Text("마커 ${(f / 1e6).f(4)} MHz · ${v?.f(1) ?: "-"} dB", color = ChartColors.marker, fontSize = 13.sp)
        }
        if (r == null) {
            Text(if (state.running) "첫 스윕 진행 중…" else "측정 대기", color = Dim)
        } else when (s.mode) {
            Mode.REVERSE -> ReverseResults(state, r, onSaveBaseline, onClearBaseline)
            Mode.SPURIOUS -> SpuriousResults(state, r)
        }
        state.timing?.let { t ->
            val usb = t.usb
            val segs = state.last?.plan?.segments?.size ?: 0
            Text(
                "스윕 ${t.totalMs} ms (${segs}구간) · 동조 ${t.tuneMs} · 수집 ${t.captureMs} · 연산 ${t.dspMs} ms" +
                    (usb?.let { u ->
                        val n = u.controlOut + u.controlIn
                        " · USB 제어 ${n}회 ${u.controlNanos / 1_000_000} ms" +
                            (if (segs > 0) " (구간당 ${(n.toDouble() / segs).f(1)}회)" else "")
                    } ?: ""),
                color = Dim, fontSize = 11.sp,
            )
        }
        Text("레벨은 dBFS(+Offset) 상대값입니다. 교정된 dBm이 아닙니다.", color = Dim, fontSize = 10.sp)
    }
}

@Composable
private fun ReverseResults(state: UiState, r: Results, onSaveBaseline: () -> Unit, onClearBaseline: () -> Unit) {
    val s = state.settings
    r.channel?.let { ch ->
        Text("채널 전력 ${ch.totalDb.f(1)} dB", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("PSD ${ch.psdDbPerMhz.f(1)} dB/MHz · 채널 ${(ch.bwHz / 1e6).f(1)} MHz", color = Color.White, fontSize = 14.sp)
    } ?: Text("채널이 Span 밖에 있습니다", color = Warn)
    r.riseDb?.let {
        Text("기준 대비 ${signed(it)} dB", fontSize = 16.sp, color = if (it > s.thresholdDb) Bad else if (it > 3) Warn else Good)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onSaveBaseline, enabled = state.last != null) { Text("현재를 기준으로 저장") }
        if (state.baseline != null) TextButton(onClick = onClearBaseline) { Text("기준 삭제") }
    }
    state.baselineTime?.let {
        Text("기준: " + java.text.SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(java.util.Date(it)), color = Dim, fontSize = 11.sp)
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
    Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
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
        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (r.peaks.isEmpty()) Good else Warn,
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
