package com.lteduenv.rxcheck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lteduenv.rxcheck.MeasureViewModel
import com.lteduenv.rxcheck.UiState
import com.lteduenv.rxcheck.core.analysis.Analysis
import com.lteduenv.rxcheck.core.analysis.PeakSource
import com.lteduenv.rxcheck.core.analysis.Results
import com.lteduenv.rxcheck.core.analysis.Status
import com.lteduenv.rxcheck.core.analysis.Validity
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
internal val Accent = Color(0xFF4C8DFF)
private val Panel = Color(0xFF151922)
private val Line = Color(0xFF2A303C)

/** Colour says how usable the numbers are, not whether the equipment is good. */
private fun Validity.color() = when (this) {
    Validity.VALID -> Accent
    Validity.PARTIAL -> Warn
    Validity.INVALID -> Bad
}

@Composable
fun MainScreen(state: UiState, vm: MeasureViewModel, onSaveCsv: () -> Unit, onShareCsv: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showPanel by rememberSaveable { mutableStateOf(true) }
    val s = state.settings

    Column(Modifier.fillMaxSize().background(ChartColors.background).padding(horizontal = 6.dp, vertical = 4.dp)) {
        TopBar(state, vm, onSettings = { showSettings = true }, onPresets = { showPresets = true },
            onSaveCsv = onSaveCsv, onShareCsv = onShareCsv)
        InfoLine(state)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp)) {
            val wide = maxWidth > maxHeight
            val panelWidth = if (maxWidth > 900.dp) 260.dp else 220.dp
            val panelHeight = maxHeight * 0.36f
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    ChartBox(state, vm, Modifier.weight(1f).fillMaxHeight())
                    if (showPanel) {
                        Spacer(Modifier.width(6.dp))
                        ResultPanel(state, vm, Modifier.width(panelWidth).fillMaxHeight())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    ChartBox(state, vm, Modifier.fillMaxWidth().weight(1f))
                    if (showPanel) {
                        Spacer(Modifier.height(6.dp))
                        ResultPanel(state, vm, Modifier.fillMaxWidth().height(panelHeight))
                    }
                }
            }
        }
        BottomBar(state, vm, showPanel) { showPanel = !showPanel }
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

// ---- top -------------------------------------------------------------------------

@Composable
private fun TopBar(
    state: UiState, vm: MeasureViewModel,
    onSettings: () -> Unit, onPresets: () -> Unit, onSaveCsv: () -> Unit, onShareCsv: () -> Unit,
) {
    val s = state.settings
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.running) {
            Pill(if (state.demo) "■ 데모 정지" else "■ 정지", fill = Bad, bold = true, onClick = vm::stop)
        } else {
            Pill("▶ 측정 시작", fill = Good, textColor = Color.Black, bold = true) { vm.start(false) }
        }
        // Fits a phone in landscape; scrolls only on very narrow screens.
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Segmented(Mode.values().map { it.title }, Mode.values().indexOf(s.mode)) { vm.selectMode(Mode.values()[it]) }
            Segmented(Band.values().map { it.label.removeSuffix(" RX").replace(" RX ", " ") }, s.band?.let { Band.values().indexOf(it) }) {
                vm.selectBand(Band.values()[it])
            }
            GainControl(s, vm)
            Pill("자동 맞춤", enabled = state.running, onClick = vm::autoFit)
        }
        Pill("⚙ 설정", onClick = onSettings)
        Box {
            Pill(" ⋮ ", bold = true) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val close = { menu = false }
                if (!state.running) MenuItem("데모 실행 (동글 없이 화면 보기)", true, close) { vm.start(true) }
                MenuItem("CSV 파일로 저장", state.last != null, close, onSaveCsv)
                MenuItem("CSV 공유 (카톡 등)", state.last != null, close, onShareCsv)
                MenuItem("빠른 설정 저장/불러오기", true, close, onPresets)
                HorizontalDivider()
                MenuItem("동글 자체 신호 기록 (안테나 분리 상태)", state.last != null, close, vm::recordInternal)
                MenuItem("동글 자체 신호 기록 삭제", state.internal != null, close, vm::clearInternal)
                MenuItem(if (s.dcShift) "중심 이동 해제 (원래 구간)" else "중심 이동 (DC 위치 재확인)", true, close, vm::toggleDcShift)
                if (s.mode == Mode.REVERSE) {
                    MenuItem("현재를 기준으로 저장", state.last != null, close, vm::saveBaseline)
                    MenuItem("기준 삭제", state.baseline != null, close, vm::clearBaseline)
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { close(); action() })
}

@Composable
private fun GainControl(s: Settings, vm: MeasureViewModel) {
    val shape = RoundedCornerShape(20.dp)
    val canDown = (s.gainStep ?: 0) > 0
    val canUp = (s.gainStep ?: Settings.MAX_GAIN_STEP) < Settings.MAX_GAIN_STEP
    Row(Modifier.clip(shape).border(1.dp, Line, shape), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clickable(enabled = canDown) { vm.stepGain(-1) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("−", color = if (canDown) Accent else Line, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text("Gain ${s.gainStep?.let { "$it" } ?: "AGC"}", color = Color.White, fontSize = 13.sp)
        Box(Modifier.clickable(enabled = canUp) { vm.stepGain(1) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("＋", color = if (canUp) Accent else Line, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Compact rounded button used across the bars. */
@Composable
private fun Pill(
    label: String, enabled: Boolean = true, fill: Color? = null, textColor: Color = Color.White,
    bold: Boolean = false, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.clip(shape)
        .then(if (fill != null) Modifier.background(if (enabled) fill else fill.copy(alpha = 0.4f)) else Modifier.border(1.dp, Line, shape))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, color = if (!enabled) Line else if (fill != null) textColor else Accent, fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

/** Connected pill buttons; one selected (or none). */
@Composable
private fun Segmented(options: List<String>, selected: Int?, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.clip(shape).border(1.dp, Line, shape)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(Modifier.background(if (on) Accent else Color.Transparent).clickable { onSelect(i) }
                .padding(horizontal = 11.dp, vertical = 8.dp)) {
                Text(label, color = if (on) Color.White else Dim, fontSize = 13.sp, maxLines = 1,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun InfoLine(state: UiState) {
    val s = state.settings
    val t = state.timing
    val parts = listOfNotNull(
        "CENTER ${s.centerMhz.f(3)}",
        "SPAN ${s.spanMhz.f(if (s.spanMhz < 10) 2 else 1)} MHz",
        "RBW ${s.rbwKhz.f(if (s.rbwKhz < 10) 1 else 0)}k",
        "평균 ${s.averages}",
        if (s.offsetDb != 0.0) "Offset ${signed(s.offsetDb)}" else null,
        // Full-span completion time vs. how often the screen gets new data (every segment).
        t?.let { "SPAN 완료 ${it.totalMs} ms (${if (it.totalMs > 0) (1000.0 / it.totalMs).f(1) else "-"}회/s)" },
        t?.let { tm -> state.last?.plan?.segments?.size?.takeIf { it > 0 }?.let { n ->
            "화면 갱신 ${(tm.totalMs.toDouble() / n).f(1)} ms마다 ($n구간)" } },
        if (s.dcShift) "중심 이동 중" else null,
        state.device,
    )
    Text(parts.joinToString("  ·  "), color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp))
}

// ---- chart -----------------------------------------------------------------------

@Composable
private fun ChartBox(state: UiState, vm: MeasureViewModel, modifier: Modifier) {
    val s = state.settings
    Box(modifier) {
        SpectrumChart(
            live = state.live, livePoints = state.livePoints,
            hold = if (s.maxHold) state.hold else null, baseline = if (s.mode == Mode.REVERSE) state.baseline else null,
            offsetDb = s.offsetDb, refLevelDb = s.refLevelDb, dbPerDiv = s.dbPerDiv,
            startHz = s.startMhz * 1e6, stopHz = s.stopMhz * 1e6,
            channelHz = s.channelHz(),
            peaks = state.results?.peaks ?: emptyList(),
            markers = state.markers, selectedMarker = state.selectedMarker,
            onTap = vm::placeMarker, onDoubleTap = vm::autoScale,
            onRefDrag = vm::dragRef, onRefDragEnd = vm::commitRef, onPinch = vm::zoomSpan,
            modifier = Modifier.fillMaxSize(),
        )
        val banner = when {
            state.clipped -> "입력 과다 (클리핑) · Gain을 낮추거나 감쇠기를 쓰세요" to Bad
            !state.running && state.live == null -> "▶ 측정 시작을 누르세요  ·  메뉴(⋮) → 데모로 미리보기" to Dim
            state.error == null && state.status.startsWith("USB 재연결") -> state.status to Warn
            else -> null
        }
        state.markers.firstOrNull { it.index == state.selectedMarker }?.freqHz?.let { f ->
            val v = state.shown?.let { Analysis.levelAt(it, f, s.offsetDb) }
            Text("M${state.selectedMarker}  ${(f / 1e6).f(4)} MHz  ${v?.f(1) ?: "—"} dB",
                color = ChartColors.marker, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 6.dp)
                    .background(Color(0xCC0B0D12), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        banner?.let { (text, color) ->
            Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp)
                    .background(Color(0xCC0B0D12), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

// ---- bottom ----------------------------------------------------------------------

@Composable
private fun BottomBar(state: UiState, vm: MeasureViewModel, showPanel: Boolean, onTogglePanel: () -> Unit) {
    val s = state.settings
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Segmented(state.markers.map { "M${it.index}" + if (it.freqHz != null) "•" else "" }, state.selectedMarker - 1) {
                vm.selectMarker(it + 1)
            }
            Pill("Peak", enabled = state.shown != null, onClick = vm::markerToPeak)
            Pill("Clear", onClick = vm::clearMarker)
            Spacer(Modifier.width(6.dp))
            Toggle("Max Hold", s.maxHold, vm::toggleHold)
            Pill("초기화", enabled = s.maxHold, onClick = vm::resetHold)
            Toggle("Ch Power", s.channelPower, vm::toggleChannelPower)
        }
        Spacer(Modifier.width(6.dp))
        Pill(if (showPanel) "결과 ▸" else "◂ 결과", onClick = onTogglePanel)
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.clip(shape).border(BorderStroke(1.dp, if (on) Accent else Line), shape)
        .background(if (on) Accent.copy(alpha = 0.25f) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp)) {
        Text((if (on) "● " else "○ ") + label, color = if (on) Color.White else Dim, fontSize = 13.sp, maxLines = 1)
    }
}

// ---- results ---------------------------------------------------------------------

@Composable
private fun ResultPanel(state: UiState, vm: MeasureViewModel, modifier: Modifier) {
    val s = state.settings
    val r = state.results
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Panel).verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r == null) {
            StatusCard(Status(Validity.PARTIAL, if (state.running) "첫 스윕 측정 중…" else "측정 대기", emptyList()))
        } else {
            StatusCard(r.status)
            when (s.mode) {
                Mode.REVERSE -> ReverseResults(state, r, vm)
                Mode.SPURIOUS -> SpuriousResults(r)
            }
        }
        if (s.channelPower && s.mode == Mode.SPURIOUS && r != null) {
            val ch = r.channel
            if (ch != null) Stat("채널 전력", "${ch.totalDb.f(1)} dB") else Stat("채널 전력", "미측정", Warn)
        }
        Text("레벨: dBFS(+Offset) 상대값", color = Dim, fontSize = 10.sp)
    }
}

@Composable
private fun StatusCard(v: Status) {
    val c = v.validity.color()
    var all by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.16f))
        .border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).clickable { all = !all }.padding(10.dp)) {
        Text(v.title, color = c, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        for (note in if (all) v.notes else v.notes.take(4)) Text("· $note", color = Color.White, fontSize = 12.sp)
        if (!all && v.notes.size > 4) Text("… ${v.notes.size - 4}개 더 (눌러서 보기)", color = Dim, fontSize = 11.sp)
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = Color.White) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Dim, fontSize = 13.sp)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReverseResults(state: UiState, r: Results, vm: MeasureViewModel) {
    val s = state.settings
    var details by remember { mutableStateOf(false) }
    val ch = r.channel
    if (ch != null) {
        Stat("채널 전력", "${ch.totalDb.f(1)} dB")
        Stat("PSD", "${ch.psdDbPerMhz.f(1)} dB/MHz")
    } else Stat("채널 전력", "미측정", Warn)
    r.correctedChannel?.let { Stat("보정값(동글 신호 대체)", "${it.totalDb.f(1)} dB", Dim) }
    val rise = r.riseDb
    if (rise != null) Stat("기준 대비", "${signed(rise)} dB")
    else if (state.baseline != null) Stat("기준 대비", "미계산", Warn)
    r.correctedRiseDb?.let { Stat("기준 대비 보정값", "${signed(it)} dB", Dim) }
    if (state.baseline == null) {
        OutlinedButton(onClick = vm::saveBaseline, enabled = state.last != null && !state.clipped, modifier = Modifier.fillMaxWidth()) {
            Text("현재를 기준으로 저장")
        }
    } else state.baselineTime?.let {
        Text("기준 " + java.text.SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(java.util.Date(it)) + " (회색 선)",
            color = Dim, fontSize = 11.sp)
    }
    if (r.blocks.isNotEmpty()) {
        Text("1 MHz 구간별", color = Dim, fontSize = 12.sp)
        BlockBars(r, s.thresholdDb)
        TextButton(onClick = { details = !details }) { Text(if (details) "구간 상세 닫기" else "구간 상세 보기") }
        if (details) for (b in r.blocks) {
            val hot = (b.aboveMedianDb ?: 0.0) >= s.thresholdDb || (b.riseDb ?: 0.0) >= s.thresholdDb
            val psd = b.psdDbPerMhz
            Text("${(b.startHz / 1e6).f(1)}  " + (if (psd == null) "미측정 (${b.missingBins} bin)" else
                "${psd.f(1)}  ${b.aboveMedianDb?.let { signed(it) } ?: "-"}" +
                    (b.riseDb?.let { "  기준${signed(it)}" } ?: if (b.baselineMissingBins > 0) "  기준 미측정" else "")),
                color = if (psd == null) Warn else if (hot) Bad else Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun BlockBars(r: Results, threshold: Double) {
    fun dev(b: com.lteduenv.rxcheck.core.analysis.Block) = maxOf(b.aboveMedianDb ?: 0.0, b.riseDb ?: 0.0)
    val maxDev = (r.blocks.maxOfOrNull { dev(it) } ?: 0.0).coerceAtLeast(threshold * 1.5)
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom) {
        for (b in r.blocks) {
            // Unmeasured: full-height outline only, never a value-looking bar.
            if (!b.measured) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).border(1.dp, Warn, RoundedCornerShape(2.dp)))
                continue
            }
            val d = dev(b)
            val frac = ((d / maxDev).coerceIn(0.06, 1.0)).toFloat()
            Box(Modifier.weight(1f).fillMaxHeight(frac).clip(RoundedCornerShape(2.dp))
                .background(if (d >= threshold) Bad else if (d >= threshold / 2) Warn else Accent))
        }
    }
}

@Composable
private fun SpuriousResults(r: Results) {
    r.floorDbPerMhz?.let { Stat("노이즈 플로어", "${it.f(1)} dB/MHz") }
    if (r.peaks.isNotEmpty()) {
        Text("플로어 위 피크 (반복 = 최근 3회 중 2회 이상)", color = Dim, fontSize = 12.sp)
        for (p in r.peaks.take(15)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C212B)).padding(6.dp)) {
                Text("${(p.freqHz / 1e6).f(3)} MHz", color = if (p.inChannel) Warn else Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                val seen = when (p.source) {
                    PeakSource.CURRENT -> if (p.seenSweeps >= 2) "반복 ${p.seenSweeps}회" else "이번 스윕만"
                    else -> p.source.label
                }
                Text("${p.levelDb.f(1)} dB · 플로어 +${p.aboveFloorDb.f(0)} · 폭 ${(p.bw10dBHz / 1e3).f(0)}k · $seen" +
                    if (p.inChannel) " · RX 대역 안" else "", color = Dim, fontSize = 11.sp)
                if (p.hints.isNotEmpty()) Text(p.hints.joinToString(" · "), color = Warn, fontSize = 11.sp)
            }
        }
        if (r.peaks.size > 15) Text("… ${r.peaks.size - 15}개 더 (CSV에 전체)", color = Dim, fontSize = 11.sp)
    }
}

// ---- presets ---------------------------------------------------------------------

@Composable
private fun PresetDialog(state: UiState, vm: MeasureViewModel, onDismiss: () -> Unit) {
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text("빠른 설정 저장/불러오기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val s = state.settings
                Text("현재: ${s.mode.title} · ${s.centerMhz.f(3)} MHz / Span ${s.spanMhz.f(2)} · RBW ${s.rbwKhz.f(1)} kHz · Gain ${s.gainStep ?: "AGC"} · Offset ${s.offsetDb.f(1)} dB",
                    fontSize = 12.sp)
                for (m in Mode.values()) {
                    Text(m.title + if (m in state.presets) " (저장값 있음)" else "", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { vm.savePreset(m); message = "${m.title}에 저장했습니다" }) { Text("현재 값 저장") }
                        TextButton(enabled = m in state.presets, onClick = { message = vm.loadPreset(m) ?: "${m.title} 저장값을 불러왔습니다" }) { Text("불러오기") }
                        TextButton(enabled = m in state.presets, onClick = { vm.clearPreset(m); message = "${m.title} 저장값을 지웠습니다" }) { Text("초기화") }
                    }
                }
                Text("상단 모드·대역 버튼은 기본 시작값을 적용합니다. 현장에 맞춘 값은 여기서 저장해 두세요.", fontSize = 11.sp, color = Dim)
                message?.let { Text(it, color = Good, fontSize = 12.sp) }
            }
        },
    )
}
