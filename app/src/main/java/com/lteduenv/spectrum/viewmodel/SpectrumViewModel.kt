package com.lteduenv.spectrum.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lteduenv.spectrum.data.*
import com.lteduenv.spectrum.data.sdr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class UiState(
    val config:SweepConfig=SweepConfig(),val frame:SpectrumFrame?=null,val rawFrame:SpectrumFrame?=null,
    val held:SpectrumFrame?=null,val running:Boolean=false,val demo:Boolean=false,val maxHold:Boolean=false,
    val markers:List<Marker> = (1..5).map{Marker(it)},val selectedMarker:Int=1,
    val message:String="V4 연결 후 ‘실측 시작’을 누르세요. DEMO는 가상 신호입니다.",
) {
    val shownFrame get()=if(maxHold)held?:frame else frame
    val marker get()=markers.first{it.index==selectedMarker}
}
class SpectrumViewModel:ViewModel() {
    private val mutable=MutableStateFlow(UiState());val state=mutable.asStateFlow()
    private var job:Job?=null;private var source:RtlTcpSource?=null;private var generation=0
    fun stop(){generation++;source?.close();source=null;job?.cancel();job=null;mutable.update{it.copy(running=false,message="정지 — 화면은 마지막 측정값입니다.")}}
    fun error(message:String){stop();mutable.update{it.copy(message=message)}}
    private fun captureKey(c:SweepConfig)=c.copy(refLevelDb=0.0,refLevelOffsetDb=0.0,integrationBwMhz=1.0,channelPowerEnabled=false,dbPerDiv=10.0)
    private fun refreshed(markers:List<Marker>,f:SpectrumFrame?)=markers.map{it.copy(levelDb=if(it.enabled)f?.levelAt(it.freqMhz)?:Float.NaN else Float.NaN)}
    fun configure(c:SweepConfig):Boolean=try {
        SweepMath.plan(c)
        val old=state.value;stop()
        val sameCapture=captureKey(c)==captureKey(old.config)
        val raw=if(sameCapture)old.rawFrame else null
        val f=raw?.let{TraceProcessing().apply(it,c)}
        mutable.value=old.copy(config=c,frame=f,rawFrame=raw,held=null,running=false,
            markers=refreshed(old.markers,f),message="")
        true
    }catch(e:Exception){mutable.update{it.copy(message=e.message?:"설정 오류")};false}
    fun applyProfile(profile:FieldProfile):Boolean {
        if(!configure(profile.config))return false
        mutable.update{it.copy(frame=null,rawFrame=null,held=null,maxHold=profile.maxHold,demo=false,
            markers=(1..5).map{n->Marker(n)},selectedMarker=1,
            message="")}
        return true
    }
    fun selectBand(p:BandPreset){configure(state.value.config.copy(centerMhz=p.uplinkMhz,spanMhz=p.spanMhz,integrationBwMhz=p.integrationBwMhz))}
    fun hold(){mutable.update{val next=it.copy(maxHold=!it.maxHold,held=null);next.copy(markers=refreshed(next.markers,next.shownFrame))}}
    fun clearHold(){mutable.update{it.copy(held=null,markers=refreshed(it.markers,it.frame))}}
    fun selectMarker(index:Int){if(index in 1..5)mutable.update{it.copy(selectedMarker=index)}}
    fun mark(f:Double){if(!f.isFinite())return;mutable.update{old->old.copy(markers=old.markers.map{if(it.index==old.selectedMarker)Marker(it.index,true,f,old.shownFrame?.levelAt(f)?:Float.NaN)else it})}}
    fun peak(){Measurements.peak(state.value.shownFrame)?.let{mark(it)}}
    fun clearMarker(){mutable.update{old->old.copy(markers=old.markers.map{if(it.index==old.selectedMarker)Marker(it.index)else it})}}
    fun toggleChannelPower(){mutable.update{it.copy(config=it.config.copy(channelPowerEnabled=!it.config.channelPowerEnabled))}}
    fun start(demo:Boolean){
        stop();val id=generation;val c=state.value.config
        try{SweepMath.plan(c)}catch(e:Exception){error(e.message?:"설정 오류");return}
        val src=if(demo)null else RtlTcpSource();source=src
        mutable.update{it.copy(frame=null,rawFrame=null,held=null,markers=refreshed(it.markers,null),running=true,demo=demo,
            message=if(demo)"DEMO · 가상 신호" else "연결/수신 중 · 넓은 Span은 순차 스윕입니다")}
        job=viewModelScope.launch {
            try {
                val processing=TraceProcessing()
                val stream=src?.frames(c)?:SimulatedRepeaterDataSource().spectrum(c).flowOn(Dispatchers.Default)
                stream.map{raw->raw to processing.apply(raw,c)}.flowOn(Dispatchers.Default).collect{(raw,f)->
                    if(id==generation)mutable.update{old->
                        val held=if(old.maxHold)SweepMath.hold(old.held,f)else null
                        old.copy(rawFrame=raw,frame=f,held=held,markers=refreshed(old.markers,held?:f),
                            message=if(demo)"DEMO · 현장 측정 아님" else if(f.clippedFraction>0.001)"입력 클리핑 감지: 이득을 낮추거나 감쇠하세요"
                            else if(c.autoGain)"수신 중 · AGC ON: 위치별 상대 레벨 비교에 주의하세요"
                            else "수신 중 · 상대 레벨 관측 / PIM 판정 불가")
                    }
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){if(id==generation)mutable.update{it.copy(running=false,message="측정 중단: ${e.message}")}}
        }
    }
    override fun onCleared(){source?.close();job?.cancel()}
    fun csv():String {
        val s=state.value;val f=s.shownFrame?:return ""
        return buildString {
            appendLine("# SpectrumCheck 2.2; ${f.source}; ${f.displayUnit}; max_hold=${s.maxHold}; dc_removed=${f.dcRemoved}")
            appendLine("# sample_rate_hz=${f.sampleRateHz}; fft=${f.fftSize}; rbw_hz=${f.rbwHz}; enbw_hz=${f.enbwHz}; vbw_khz=${f.vbwKhz}; offset_db=${f.offsetDb}; gain_step=${f.gainStep}; tuner_agc=${f.autoGain}; segments=${f.segmentCount}; start_ms=${f.startedMs}; end_ms=${f.timestampMs}; clipping=${f.clippedFraction}")
            appendLine("# rbw_requested_khz=${s.config.rbwKhz}; ref_level=${s.config.refLevelDb}; db_per_div=${s.config.dbPerDiv}; integration_bw_mhz=${s.config.integrationBwMhz}")
            for(m in s.markers.filter{it.enabled})appendLine("# marker_${m.index}=${m.freqMhz} MHz; ${m.levelDb} ${f.displayUnit}")
            if(s.config.channelPowerEnabled)Measurements.channelPower(s.frame,s.config.centerMhz,s.config.integrationBwMhz)?.let{
                appendLine("# live_channel_power=${it.totalDb}; live_psd_per_mhz=${it.psdDbPerMhz}; unit=${s.frame?.displayUnit}; max_hold_not_integrated=true")
            }
            appendLine("frequency_mhz,display_level,level_without_offset,unit,last_observed_at_epoch_ms")
            for(i in f.levelsDb.indices)appendLine("${f.frequencyAt(i)},${f.levelsDb[i]},${f.levelsDb[i]-f.offsetDb},${f.displayUnit},${f.observedAtMs[i]}")
        }
    }
}
