package com.lteduenv.spectrum.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lteduenv.spectrum.data.*
import com.lteduenv.spectrum.data.sdr.*
import com.lteduenv.spectrum.viewmodel.SpectrumViewModel

@Composable
fun SpectrumApp(vm:SpectrumViewModel=viewModel()) {
    val s by vm.state.collectAsState();val context=LocalContext.current
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP && vm.state.value.running)vm.stop()}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}
    }
    var settings by remember{mutableStateOf(false)};var pendingCsv by remember{mutableStateOf("")}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
        if(uri!=null)runCatching{context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(pendingCsv)}?:error("파일을 열 수 없습니다")}.onFailure{vm.error("저장 오류: ${it.message}")}
    }
    var opening by remember{mutableStateOf(false)}
    val driver=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        opening=false
        if(result.resultCode==Activity.RESULT_OK)vm.start(false)
        else vm.error(result.data?.getStringExtra("detailed_exception_message")?:"드라이버 연결이 취소되었습니다. USB 연결과 권한을 확인하세요.")
    }
    val profilePrefs=remember(context){context.getSharedPreferences("field_profiles_v1",android.content.Context.MODE_PRIVATE)}
    var profileDialog by remember{mutableStateOf(false)}
    fun loadProfile(mode:FieldMode) {
        runCatching {
            val saved=profilePrefs.getString(mode.name,null)
            val profile=if(saved==null)FieldProfiles.initial(mode,s.config)else FieldProfiles.decode(saved)
            vm.applyProfile(profile)
        }.onFailure{vm.error("저장 설정을 불러올 수 없습니다. 빠른 설정 관리에서 초기화하세요.")}
    }
    val frame=s.shownFrame
    val cp=remember(s.frame,s.config.centerMhz,s.config.integrationBwMhz,s.config.channelPowerEnabled){
        if(s.config.channelPowerEnabled)Measurements.channelPower(s.frame,s.config.centerMhz,s.config.integrationBwMhz)else null
    }
    Column(Modifier.fillMaxSize().padding(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Button(enabled=!s.running && !opening,onClick={
                opening=true
                val intent=Intent(Intent.ACTION_VIEW,Uri.parse("iqsrc://-a 127.0.0.1 -p 1234 -s 2400000 -f ${(s.config.centerMhz*1e6).toLong()}"))
                    .setPackage("marto.rtl_tcp_andro")
                runCatching{driver.launch(intent)}.onFailure{opening=false;vm.error("SDR Driver를 실행할 수 없습니다. 앱 설치 상태와 업데이트를 확인하세요.")}
            }){Text(if(opening)"연결 중" else "실측 시작")}
            OutlinedButton(onClick={vm.stop()}){Text("정지")}
            OutlinedButton(onClick={vm.stop();settings=true}){Text("측정 설정")}
            OutlinedButton(onClick={vm.hold()}){Text(if(s.maxHold)"Max Hold ON" else "Max Hold OFF")}
            OutlinedButton(onClick={vm.clearHold()}){Text("피크 초기화")}
            OutlinedButton(enabled=frame!=null,onClick={pendingCsv=vm.csv();save.launch("SpectrumCheck-${System.currentTimeMillis()}.csv")}){Text("CSV")}
            TextButton(enabled=!s.running,onClick={vm.start(true)}){Text("DEMO")}
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FieldMode.values().forEach{mode->OutlinedButton(enabled=!opening,onClick={loadProfile(mode)}){Text(mode.title)}}
            TextButton(enabled=!opening,onClick={vm.stop();profileDialog=true}){Text("빠른 설정 저장/관리")}
        }
        Text("Ref ${s.config.refLevelDb.fmt(1)} · Offset ${s.config.refLevelOffsetDb.fmt(1)} dB · ${s.config.dbPerDiv.fmt(1)} dB/div · RBW 목표 ${s.config.rbwKhz.fmt(2)} kHz · VBW ${if(s.config.vbwKhz==0.0)"OFF" else "${s.config.vbwKhz.fmt(2)} kHz (SW)"}",fontSize=11.sp,maxLines=1)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            s.markers.forEach{m->FilterChip(selected=m.index==s.selectedMarker,onClick={vm.selectMarker(m.index)},label={Text(if(m.enabled)"M${m.index} ${m.levelDb.toDouble().fmt(1)}" else "M${m.index}",fontSize=11.sp)})}
            TextButton(enabled=frame!=null,onClick={vm.peak()}){Text("Peak")}
            TextButton(onClick={vm.clearMarker()}){Text("Clear")}
            FilterChip(selected=s.config.channelPowerEnabled,onClick={vm.toggleChannelPower()},label={Text("Ch Power",fontSize=11.sp)})
        }
        SpectrumTraceCanvas(frame,s.config.refLevelDb,s.markers,s.selectedMarker,
            Modifier.weight(1f).fillMaxWidth(),dbSpan=s.config.dbPerDiv*8,onTapFrequency={vm.mark(it)})
        if(s.config.channelPowerEnabled)Text(
            if(cp==null)"Ch Power: 측정 대기 또는 Integration BW가 관측 범위를 벗어납니다"
            else "Total Ch Power ${cp.totalDb.fmt(2)} ${s.frame?.displayUnit} · PSD ${cp.psdDbPerMhz.fmt(2)} /MHz · IBW ${s.config.integrationBwMhz.fmt(2)} MHz · 현재 트레이스${if((s.frame?.segmentCount?:1)>1)" / 순차 합산" else ""}",fontSize=11.sp,maxLines=2)
        Text(if(s.marker.enabled)"M${s.marker.index}: ${s.marker.freqMhz.fmt(4)} MHz / ${s.marker.levelDb.toDouble().fmt(2)} ${frame?.displayUnit.orEmpty()}" else "M1~M5 선택 후 그래프 터치 · Peak로 최대점 검색",fontSize=11.sp)
        Text(if(frame==null)"Center ${s.config.centerMhz.fmt(3)} MHz / Span ${s.config.spanMhz.fmt(3)} MHz" else
            "${frame.displayUnit} · ${frame.startMhz.fmt(3)}–${frame.stopMhz.fmt(3)} MHz · RBW 적용 ${(frame.rbwHz/1000).fmt(3)} kHz · ENBW ${(frame.enbwHz/1000).fmt(3)} kHz · ${frame.pointCount}점 / ${frame.segmentCount}구간 · ${frame.timestampMs-frame.startedMs} ms · ${if(frame.autoGain)"AGC" else "Gain ${frame.gainStep}/10"}",fontSize=10.sp,maxLines=2)
        if(s.message.isNotBlank())Text(s.message,color=if(s.demo)AnalyzerColors.Bad else AnalyzerColors.TextPrimary,fontSize=11.sp,maxLines=1)
    }
    if(profileDialog)AlertDialog(onDismissRequest={profileDialog=false},title={Text("빠른 설정 저장/관리")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("측정 설정에서 조정한 현재 값과 Max Hold 상태를 저장합니다. 주파수·Span·RBW·VBW·이득·Ref·Offset·Ch Power가 포함됩니다.")
            Text("저장 전: Center ${s.config.centerMhz} MHz / Span ${s.config.spanMhz} MHz / Gain ${s.config.manualGainLevel}/10 / AGC ${s.config.autoGain} / Offset ${s.config.refLevelOffsetDb} dB",fontSize=12.sp)
            Text("초기값은 최적값이나 dBm 교정값이 아닙니다. 장비 연결 전 모니터 포트 감쇠량과 허용 입력을 확인하세요. 낮은 이득은 과입력 보호를 대신하지 않습니다.",fontSize=12.sp)
            FieldMode.values().forEach{mode->
                Button(onClick={
                    val encoded=FieldProfiles.encode(FieldProfile(s.config,s.maxHold))
                    profilePrefs.edit().putString(mode.name,encoded).apply()
                    profileDialog=false
                }){Text("현재 값을 '${mode.title}'에 저장")}
                TextButton(onClick={
                    profilePrefs.edit().remove(mode.name).apply()
                    profileDialog=false
                }){Text("${mode.title} 저장값 초기화")}
            }
            Text("최초 선택: 현재 주파수/Span 유지, Offset 0, Gain 1, AGC OFF. 장비: RBW 100 kHz / VBW 1 kHz / Ch Power ON. 탐색: RBW 10 kHz / VBW OFF / Max Hold ON. 이동 후 피크 초기화를 사용하세요.",fontSize=12.sp)
        }
    },confirmButton={TextButton(onClick={profileDialog=false}){Text("닫기")}})
    if(settings)MeasurementSettings(s.config,frame,vm){settings=false}
}

@Composable
private fun MeasurementSettings(c:SweepConfig,frame:SpectrumFrame?,vm:SpectrumViewModel,onDismiss:()->Unit) {
    var tab by remember{mutableStateOf(0)}
    var center by remember{mutableStateOf(c.centerMhz.toString())};var span by remember{mutableStateOf(c.spanMhz.toString())}
    var ref by remember{mutableStateOf(c.refLevelDb.toString())};var offset by remember{mutableStateOf(c.refLevelOffsetDb.toString())}
    var scale by remember{mutableStateOf(c.dbPerDiv.toString())};var rbw by remember{mutableStateOf(c.rbwKhz.toString())}
    var vbw by remember{mutableStateOf(c.vbwKhz.toString())};var ibw by remember{mutableStateOf(c.integrationBwMhz.toString())}
    var gain by remember{mutableStateOf(c.manualGainLevel.toFloat())};var agc by remember{mutableStateOf(c.autoGain)}
    var dc by remember{mutableStateOf(c.removeDc)};var channel by remember{mutableStateOf(c.channelPowerEnabled)}
    var error by remember{mutableStateOf("")}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.95f).imePadding(),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("FREQ / SPAN","AMPLITUDE","BANDWIDTH","MEASURE","GAIN").forEachIndexed{i,title->
                        FilterChip(selected=tab==i,onClick={tab=i},label={Text(title)})
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    when(tab) {
                        0->{
                            Row(Modifier.horizontalScroll(rememberScrollState())){
                                BandPresets.all.forEach{p->TextButton(onClick={center=p.uplinkMhz.toString();span=p.spanMhz.toString();ibw=p.integrationBwMhz.toString()}){Text(p.label)}}
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                NumberField("Center Frequency (MHz)",center,{center=it},Modifier.weight(1f))
                                NumberField("Span (MHz)",span,{span=it},Modifier.weight(1f))
                            }
                            Text("넓은 Span은 순차 스윕입니다. B3 30M은 V4 주파수 상한 때문에 Span 30 MHz로 설정합니다.",fontSize=12.sp)
                            Text("현장 RX 주파수를 확인하세요. 약 1.8 MHz 이내 관측부터 시작하면 수신 확인이 빠릅니다.",fontSize=12.sp)
                        }
                        1->{
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                NumberField("Ref Level (그래프 상단)",ref,{ref=it},Modifier.weight(1f))
                                NumberField("Scale / Div (dB)",scale,{scale=it},Modifier.weight(1f))
                            }
                            NumberField("Ref Level Offset (dB)",offset,{offset=it})
                            Text("Ref Level은 축 위치만 변경합니다. Offset은 표시 레벨에 더합니다: 원본 -70 + Offset 10 = -60. Offset 입력만으로 dBm 교정이 되지는 않습니다.",fontSize=12.sp)
                            TextButton(enabled=frame!=null,onClick={
                                val peak=frame?.levelsDb?.filter{it.isFinite()}?.maxOrNull()
                                if(peak!=null)ref=(kotlin.math.ceil((peak-(frame?.offsetDb?:0.0)+(offset.toDoubleOrNull()?:0.0)+5)/5)*5).toString()
                            }){Text("Auto Ref Level · 현재 피크에 맞춤")}
                        }
                        2->{
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                NumberField("RBW 목표 (kHz)",rbw,{rbw=it},Modifier.weight(1f))
                                NumberField("VBW (kHz, 0=OFF)",vbw,{vbw=it},Modifier.weight(1f))
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState())){
                                listOf(1.7,10.0,30.0,100.0).forEach{v->TextButton(onClick={rbw=v.toString()}){Text("RBW ${v.fmt(1)}")}}
                                TextButton(onClick={vbw="1.0"}){Text("VBW 1")};TextButton(onClick={vbw="0.0"}){Text("VBW OFF")}
                            }
                            val requested=rbw.toDoubleOrNull()
                            if(requested!=null && requested in 0.5..300.0){
                                val n=SweepMath.fftSize(requested);val w=SweepMath.bandwidth(n)
                                Text("적용 예상: RBW ${(w.rbwHz/1000).fmt(3)} kHz · ENBW ${(w.enbwHz/1000).fmt(3)} kHz · FFT $n",fontSize=12.sp)
                            }
                            Text("RBW는 가능한 FFT 크기 중 가까운 값을 적용합니다. VBW는 연속 IQ 구간의 선형 전력을 평활하는 소프트웨어 필터입니다. 계측기의 동일 숫자와 성능이 같지는 않습니다.",fontSize=12.sp)
                            Text("입력 범위: RBW 0.5–300 kHz / VBW 0 또는 0.05–300 kHz. Points는 Span·FFT에 따라 실제 측정점 수로 결정됩니다.",fontSize=12.sp)
                        }
                        3->{
                            Row{Checkbox(channel,{channel=it});Text("Channel Power 표시")}
                            NumberField("Integration BW (MHz)",ibw,{ibw=it})
                            Row(Modifier.horizontalScroll(rememberScrollState())){
                                listOf(1.0,10.0,20.0,30.0).forEach{v->TextButton(onClick={ibw=v.toString()}){Text("${v.toInt()} MHz")}}
                            }
                            Text("Total Ch Power와 PSD(/MHz)는 현재 트레이스를 적분합니다. Max Hold를 합산하지 않으며, 넓은 대역은 동시 측정이 아닌 순차 합산입니다. 전체 Integration BW를 관측할 수 있어야 표시합니다.",fontSize=12.sp)
                            Text("1 MHz → 20 MHz의 +13.01 dB 환산은 균일한 잡음 밀도를 가정한 추정입니다. 이 앱은 자동으로 그 값을 더하지 않습니다.",fontSize=12.sp)
                        }
                        4->{
                            Row{Switch(agc,{agc=it});Text("  Auto Gain (튜너 AGC)")}
                            Text("Manual Gain ${gain.toInt()}/10${if(agc)" · AGC 사용 중 수동값 미적용" else ""}")
                            Slider(gain,{gain=it},valueRange=1f..10f,steps=8,enabled=!agc)
                            Text("위치별 레벨 비교는 AGC OFF와 같은 수동 이득을 권장합니다. 이득 조절은 외부 감쇠기나 과입력 보호를 대신하지 않습니다.",fontSize=12.sp)
                            Row{Checkbox(dc,{dc=it});Text("DC 제거")}
                            Text("DC 제거는 중심의 실제 신호도 약하게 만들 수 있습니다. V4에는 계측기와 같은 별도 Preamp/Auto Atten 제어를 제공하지 않습니다.",fontSize=12.sp)
                        }
                    }
                }
                if(error.isNotEmpty())Text(error,color=AnalyzerColors.Bad,fontSize=12.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                    TextButton(onClick=onDismiss){Text("취소")}
                    Button(onClick={
                        val numbers=listOf(center,span,ref,offset,scale,rbw,vbw,ibw).map{it.toDoubleOrNull()}
                        if(numbers.any{it==null || !it.isFinite()})error="올바른 숫자를 입력하세요"
                        else {
                            val v=numbers.map{it!!}
                            val config=SweepConfig(centerMhz=v[0],spanMhz=v[1],refLevelDb=v[2],manualGainLevel=gain.toInt(),removeDc=dc,
                                refLevelOffsetDb=v[3],dbPerDiv=v[4],rbwKhz=v[5],vbwKhz=v[6],integrationBwMhz=v[7],channelPowerEnabled=channel,autoGain=agc)
                            if(vm.configure(config))onDismiss()else error=vm.state.value.message
                        }
                    }){Text("적용")}
                }
            }
        }
    }
}
@Composable
private fun NumberField(label:String,value:String,onValue:(String)->Unit,modifier:Modifier=Modifier.fillMaxWidth()) {
    OutlinedTextField(value,onValue,label={Text(label)},singleLine=true,modifier=modifier)
}
private fun Double.fmt(n:Int)=String.format(java.util.Locale.US,"%.${n}f",this)
