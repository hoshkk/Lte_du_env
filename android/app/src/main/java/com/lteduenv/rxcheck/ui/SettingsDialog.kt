package com.lteduenv.rxcheck.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lteduenv.rxcheck.core.model.Band
import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import kotlin.math.roundToInt

private val TABS = listOf("FREQ / SPAN", "AMPLITUDE", "BANDWIDTH", "MEASURE", "GAIN / 수신기")

@Composable
fun SettingsDialog(current: Settings, onDismiss: () -> Unit, onApply: (Settings) -> String?) {
    var tab by remember { mutableStateOf(0) }
    var band by remember { mutableStateOf(current.band) }
    var center by remember { mutableStateOf(current.centerMhz.toString()) }
    var span by remember { mutableStateOf(current.spanMhz.toString()) }
    var chBw by remember { mutableStateOf(current.channelBwMhz.toString()) }
    var rbw by remember { mutableStateOf(current.rbwKhz.toString()) }
    var avg by remember { mutableStateOf(current.averages.toString()) }
    var offset by remember { mutableStateOf(current.offsetDb.toString()) }
    var threshold by remember { mutableStateOf(current.thresholdDb.toString()) }
    var ref by remember { mutableStateOf(current.refLevelDb.toString()) }
    var div by remember { mutableStateOf(current.dbPerDiv.toString()) }
    var agc by remember { mutableStateOf(current.gainStep == null) }
    var gain by remember { mutableStateOf((current.gainStep ?: 4).toFloat()) }
    var fast by remember { mutableStateOf(current.fastTune) }
    var narrowIf by remember { mutableStateOf(current.narrowIf) }
    var settle by remember { mutableStateOf(current.settleMs.toString()) }
    var chPower by remember { mutableStateOf(current.channelPower) }
    var dc by remember { mutableStateOf(current.dcPatch) }
    var iqMean by remember { mutableStateOf(current.iqMeanRemoval) }
    var dcShift by remember { mutableStateOf(current.dcShift) }
    var correction by remember { mutableStateOf(current.internalCorrection) }
    var error by remember { mutableStateOf<String?>(null) }

    fun build(): Settings? {
        val nums = listOf(center, span, chBw, rbw, offset, threshold, ref, div).map { it.trim().toDoubleOrNull() }
        val a = avg.trim().toIntOrNull(); val st = settle.trim().toIntOrNull()
        if (nums.any { it == null } || a == null || st == null) { error = "숫자 입력을 확인하세요"; return null }
        val v = nums.map { it!! }
        return current.copy(
            band = band?.takeIf { it.rxCenterMhz * 1e6 in (v[0] - v[1] / 2) * 1e6..(v[0] + v[1] / 2) * 1e6 },
            centerMhz = v[0], spanMhz = v[1], channelBwMhz = v[2], rbwKhz = v[3], averages = a,
            offsetDb = v[4], thresholdDb = v[5], refLevelDb = v[6], dbPerDiv = v[7],
            gainStep = if (agc) null else gain.roundToInt(), fastTune = fast, narrowIf = narrowIf,
            settleMs = st, channelPower = chPower, dcPatch = dc,
            iqMeanRemoval = iqMean, dcShift = dcShift, internalCorrection = correction,
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f).imePadding(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TABS.forEachIndexed { i, t -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) }) }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (tab) {
                        0 -> {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Band.values().forEach { b ->
                                    FilterChip(selected = band == b, onClick = {
                                        band = b; center = b.rxCenterMhz.toString(); chBw = b.channelBwMhz.toString()
                                        span = b.reverseSpanMhz.toString()
                                    }, label = { Text(b.label) })
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Field("Center (MHz)", center, { center = it }, Modifier.weight(1f))
                                Field("Span (MHz)", span, { span = it }, Modifier.weight(1f))
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(1.5, 15.0, 30.0, 50.0).forEach { w ->
                                    FilterChip(selected = span.toDoubleOrNull() == w, onClick = { span = w.toString() },
                                        label = { Text("SPAN ${if (w < 10) w.f(1) else w.f(0)}") })
                                }
                            }
                            val c = center.toDoubleOrNull(); val sp = span.toDoubleOrNull()
                            if (c != null && c in 24.0..1766.0) {
                                val maxSpan = Settings(centerMhz = c).maxSpanAtCenter()
                                Text("현재 Center에서 최대 SPAN ${maxSpan.f(2)} MHz", fontSize = 12.sp)
                                if (sp != null && sp > 0) {
                                    Text("START ${(c - sp / 2).f(3)} · STOP ${(c + sp / 2).f(3)} MHz", fontSize = 12.sp)
                                    if (sp > maxSpan) Text("수신 범위(24–1766 MHz)를 벗어납니다", color = Bad, fontSize = 12.sp)
                                    val segs = SweepPlan.create(c * 1e6, sp * 1e6, (rbw.toDoubleOrNull() ?: 100.0) * 1e3).segments.size
                                    Text(if (segs == 1) "1구간 · 재동조 없이 연속 갱신 (가장 빠름)" else "${segs}구간 순차 스윕", fontSize = 12.sp, color = Dim)
                                }
                            }
                            Field("채널 BW (MHz)", chBw, { chBw = it })
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(5.0, 10.0, 20.0, 30.0).forEach { w ->
                                    FilterChip(selected = chBw.toDoubleOrNull() == w, onClick = { chBw = w.toString() }, label = { Text("${w.toInt()} MHz") })
                                }
                            }
                            Text("그래프의 녹색 영역이 채널(채널 전력·블록 분석 범위)입니다. 2 MHz 이하 Span은 한 번에 수신해서 실시간에 가깝게 갱신됩니다.",
                                fontSize = 11.sp, color = Dim)
                        }
                        1 -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Field("Ref Level (상단, dB)", ref, { ref = it }, Modifier.weight(1f))
                                Field("Scale (dB/div)", div, { div = it }, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(5.0, 10.0, 15.0).forEach { w ->
                                    FilterChip(selected = div.toDoubleOrNull() == w, onClick = { div = w.toString() }, label = { Text("${w.toInt()} dB/div") })
                                }
                            }
                            Field("Offset (dB)", offset, { offset = it })
                            Text("Ref는 축 위치만 바꿉니다(그래프를 위아래로 끌거나, 두 번 탭하면 자동으로 맞춥니다). Offset은 표시 레벨에 더합니다: 원본 -70 + Offset 10 = -60. " +
                                "커플러·케이블 손실 등 알고 있는 값만 넣으세요. Offset으로 dBm 교정이 되지는 않습니다.", fontSize = 11.sp, color = Dim)
                        }
                        2 -> {
                            Field("RBW (kHz)", rbw, { rbw = it })
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(3.0, 10.0, 30.0, 100.0).forEach { v ->
                                    FilterChip(selected = rbw.toDoubleOrNull() == v, onClick = { rbw = v.toString() }, label = { Text("RBW ${v.toInt()}k") })
                                }
                            }
                            rbw.toDoubleOrNull()?.takeIf { it in 1.0..300.0 }?.let { r ->
                                val n = SweepPlan.fftSizeFor(r * 1e3, 2_400_000)
                                val bin = 2_400_000.0 / n
                                Text("적용: RBW ${(1.44 * bin / 1e3).f(2)} kHz · ENBW ${(1.5 * bin / 1e3).f(2)} kHz · FFT $n", fontSize = 12.sp)
                            }
                            Field("평균 (구간당 FFT 프레임 수)", avg, { avg = it })
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(1, 4, 16, 64).forEach { v ->
                                    FilterChip(selected = avg.toIntOrNull() == v, onClick = { avg = v.toString() }, label = { Text("평균 $v") })
                                }
                            }
                            Text("평균은 같은 잡음이 bin마다 출렁이는 것을 줄입니다(1장: ±5 dB 이상, 16장: 약 ±1 dB). 계측기의 VBW 역할이며 " +
                                "16장 평균은 2.4 MS/s에서 구간당 수 ms만 더 걸립니다. 순간 신호는 평균 1~4 + Max Hold로 보세요.",
                                fontSize = 11.sp, color = Dim)
                        }
                        3 -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(chPower, { chPower = it }); Text("Channel Power 표시")
                            }
                            Field("판정 임계 (dB)", threshold, { threshold = it })
                            Text("장비 리버스: 1 MHz 블록이 중앙값 또는 저장한 기준보다 이 값 이상 높으면 '간섭 의심'.\n" +
                                "불요파: 노이즈 플로어보다 이 값 이상 높은 피크를 목록에 올립니다.", fontSize = 11.sp, color = Dim)
                            Text("채널 전력은 녹색 채널 영역을 적분한 값이며 완료된 스윕 기준입니다(넓은 Span은 순차 측정 합산).",
                                fontSize = 11.sp, color = Dim)
                        }
                        else -> {
                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(agc, { agc = it }); Text("  튜너 AGC") }
                            Text("수동 이득 ${gain.roundToInt()}/15${if (agc) " (AGC 사용 중)" else ""}", fontSize = 13.sp)
                            Slider(gain, { gain = it }, valueRange = 0f..15f, steps = 14, enabled = !agc)
                            Text("장비 커플러·모니터 포트처럼 레벨이 높으면 낮게. 위치·시간 비교는 같은 수동 이득으로. 클리핑 경고가 뜨면 이득을 낮추거나 감쇠기를 쓰세요.",
                                fontSize = 11.sp, color = Dim)
                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(narrowIf, { narrowIf = it }); Text("  좁은 IF 필터 (권장)") }
                            Text("켜기: 샘플레이트에 맞춘 약 2.4 MHz IF 필터(librtlsdr 방식). 강한 인접 신호(B8 RX 옆 DL 등)에 의한 동글 포화를 줄입니다. 끄기: 6 MHz 필터.",
                                fontSize = 11.sp, color = Dim)
                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(fast, { fast = it }); Text("  고속 동조") }
                            Text("켜기: I2C 리피터 유지, 바뀐 레지스터만, PLL 레지스터 묶음 전송(구간당 USB 제어 약 9회). 끄기: 참고 드라이버 순서. 레벨이 다르게 보이면 끄고 비교하세요.",
                                fontSize = 11.sp, color = Dim)
                            Field("안정화 대기 (ms, PLL 잠금 후)", settle, { settle = it })
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(0, 2, 5, 10).forEach { v ->
                                    FilterChip(selected = settle.toIntOrNull() == v, onClick = { settle = v.toString() }, label = { Text("$v ms") })
                                }
                            }
                            Text("0: PLL 잠금 확인 후 바로 수집(가장 빠름). 구간 경계에서 레벨이 튀면 2~10 ms로 늘려 비교하세요(기존 앱은 10 ms).",
                                fontSize = 11.sp, color = Dim)
                            Text("DC 처리 (기본: 모두 끔 = 원본 스펙트럼)", fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(iqMean, { iqMean = it }); Text("I/Q 평균 제거") }
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(dc, { dc = it }); Text("구간 중심 3 bin 보간 (보정)") }
                            Text("두 처리 모두 각 구간 중심 주파수에 정확히 겹친 실제 신호도 지웁니다. 켜면 결과에 '적용'으로 표시됩니다.",
                                fontSize = 11.sp, color = Dim)
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(dcShift, { dcShift = it }); Text("중심 이동 (DC 위치 재확인)") }
                            Text("구간 중심을 약 반 구간 옮겨 측정합니다. 'DC 위치' 피크가 그대로 남으면 실제 신호, 사라지면 수신기 DC입니다.",
                                fontSize = 11.sp, color = Dim)
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(correction, { correction = it }); Text("동글 신호 보정값 함께 표시") }
                            Text("28.8 MHz 배수·무입력 기록 위치를 주변값으로 바꾼 채널 전력을 '보정값'으로 따로 보여줍니다. 원본 값은 그대로입니다.",
                                fontSize = 11.sp, color = Dim)
                        }
                    }
                }
                error?.let { Text(it, color = Bad, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("취소") }
                    Button(onClick = { build()?.let { error = onApply(it) } }) { Text("적용") }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 12.sp) }, singleLine = true, modifier = modifier)
}
