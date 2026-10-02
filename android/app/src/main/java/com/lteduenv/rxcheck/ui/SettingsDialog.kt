package com.lteduenv.rxcheck.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lteduenv.rxcheck.core.model.Settings
import kotlin.math.roundToInt

@Composable
fun SettingsDialog(current: Settings, onDismiss: () -> Unit, onApply: (Settings) -> String?) {
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
    var dc by remember { mutableStateOf(current.dcPatch) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("측정 설정") },
        confirmButton = {
            TextButton(onClick = {
                val nums = listOf(center, span, chBw, rbw, offset, threshold, ref, div).map { it.trim().toDoubleOrNull() }
                val a = avg.trim().toIntOrNull()
                if (nums.any { it == null } || a == null) { error = "숫자 입력을 확인하세요"; return@TextButton }
                val v = nums.map { it!! }
                val s = current.copy(
                    centerMhz = v[0], spanMhz = v[1], channelBwMhz = v[2], rbwKhz = v[3], averages = a,
                    offsetDb = v[4], thresholdDb = v[5], refLevelDb = v[6], dbPerDiv = v[7],
                    gainStep = if (agc) null else gain.roundToInt(), fastTune = fast, dcPatch = dc,
                    band = if (v[0] == current.centerMhz && v[2] == current.channelBwMhz) current.band else null,
                )
                error = onApply(s)
            }) { Text("적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Center (MHz)", center, { center = it }, Modifier.weight(1f))
                    Field("Span (MHz)", span, { span = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("채널 BW (MHz)", chBw, { chBw = it }, Modifier.weight(1f))
                    Field("RBW (kHz)", rbw, { rbw = it }, Modifier.weight(1f))
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(10.0, 30.0, 100.0).forEach { v -> FilterChip(rbw.toDoubleOrNull() == v, { rbw = v.toString() }, { Text("RBW ${v.toInt()}k") }) }
                    listOf(1, 4, 16, 64).forEach { v -> FilterChip(avg.toIntOrNull() == v, { avg = v.toString() }, { Text("평균 $v") }) }
                }
                Field("평균 횟수 (구간당 FFT 프레임)", avg, { avg = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(agc, { agc = it }); Text("  튜너 AGC")
                }
                Text("수동 이득 ${gain.roundToInt()}/15${if (agc) " (AGC 사용 중)" else ""}", fontSize = 13.sp)
                Slider(gain, { gain = it }, valueRange = 0f..15f, steps = 14, enabled = !agc)
                Text("장비 커플러/모니터 포트처럼 레벨이 높으면 이득을 낮추세요. 위치 간 비교는 같은 수동 이득으로 하세요.",
                    fontSize = 11.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Offset (dB)", offset, { offset = it }, Modifier.weight(1f))
                    Field("판정 임계 (dB)", threshold, { threshold = it }, Modifier.weight(1f))
                }
                Text("Offset: 커플러·케이블 손실 등을 더해 표시합니다. 교정 dBm이 되지는 않습니다.\n" +
                    "임계: 장비 리버스는 블록이 중앙값/기준보다 높은 dB, 불요파는 플로어보다 높은 dB.",
                    fontSize = 11.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Ref Level (dB)", ref, { ref = it }, Modifier.weight(1f))
                    Field("dB/div", div, { div = it }, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(fast, { fast = it }); Text("  고속 동조")
                }
                Text("켜기: I2C 리피터 유지, 바뀐 레지스터만 전송, PLL 레지스터 묶음 전송 (구간당 USB 제어 약 9회). " +
                    "끄기: 참고 드라이버와 같은 순서로 전송. 측정값이 다르게 보이면 끄고 비교하세요.",
                    fontSize = 11.sp, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(dc, { dc = it }); Text("  구간 중심 3 bin 보간")
                }
                error?.let { Text(it, color = Color(0xFFFF5D5D)) }
            }
        },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 12.sp) }, singleLine = true, modifier = modifier)
}
