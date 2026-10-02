package com.lteduenv.spectrum.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lteduenv.spectrum.data.*
import com.lteduenv.spectrum.data.sdr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class UiState(
    val selectedMode:FieldMode?=null,
    val autoSetupStatus:String="",
    val config:SweepConfig=SweepConfig(spanMhz=15.0,integrationBwMhz=10.0,rbwKhz=10.0),val frame:SpectrumFrame?=null,val rawFrame:SpectrumFrame?=null,
    val completeFrame:SpectrumFrame?=null,val held:SpectrumFrame?=null,val running:Boolean=false,val maxHold:Boolean=false,
    val markers:List<Marker> = (1..5).map{Marker(it)},val selectedMarker:Int=1,
    val message:String="V4 연결 후 ‘실측 시작’을 누르세요.",
) {
    val shownFrame get()=if(maxHold)held?:frame else frame
    val marker get()=markers.first{it.index==selectedMarker}
}
class SpectrumViewModel:ViewModel() {
    private val mutable=MutableStateFlow(UiState());val state=mutable.asStateFlow()
    private var autoPending=false
    private var job:Job?=null;private var source:SpectrumSource?=null;private var generation=0
    fun stop(){autoPending=false;generation++;source?.close();source=null;job?.cancel();job=null;mutable.update{it.copy(running=false,autoSetupStatus="",message="정지 — 화면은 마지막 측정값입니다.")}}
    fun error(message:String){stop();mutable.update{it.copy(message=message)}}
    private fun captureKey(c:SweepConfig)=c.copy(refLevelDb=0.0,refLevelOffsetDb=0.0,integrationBwMhz=1.0,channelPowerEnabled=false,dbPerDiv=10.0)
    private fun refreshed(markers:List<Marker>,f:SpectrumFrame?)=markers.map{it.copy(levelDb=if(it.enabled)f?.levelAt(it.freqMhz)?:Float.NaN else Float.NaN)}
    fun configure(c:SweepConfig):Boolean=try {
        SweepMath.plan(c)
        val old=state.value;stop()
        val sameCapture=captureKey(c)==captureKey(old.config)
        val raw=if(sameCapture)old.rawFrame else null
        val f=raw?.let{TraceProcessing().apply(it,c)}
        mutable.value=old.copy(config=c,frame=f,rawFrame=raw,completeFrame=null,held=null,running=false,
            markers=refreshed(old.markers,f),autoSetupStatus="",message="")
        true
    }catch(e:Exception){mutable.update{it.copy(message=e.message?:"설정 오류")};false}
    fun applyProfile(profile:FieldProfile,mode:FieldMode?=null):Boolean {
        if(!configure(profile.config))return false
        autoPending=mode!=null
        mutable.update{it.copy(selectedMode=mode?:it.selectedMode,frame=null,rawFrame=null,completeFrame=null,held=null,maxHold=profile.maxHold,
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
    fun start(nativeContext:android.content.Context?=null,autoPass:Int=0){
        val doAuto=autoPending || autoPass>0
        stop();val id=generation;val c=state.value.config
        try{SweepMath.plan(c)}catch(e:Exception){error(e.message?:"설정 오류");return}
        val src:SpectrumSource=if(nativeContext==null)RtlTcpSource() else NativeUsbSource(nativeContext){status->if(id==generation)mutable.update{it.copy(message=status)}};source=src
        mutable.update{it.copy(frame=null,rawFrame=null,completeFrame=null,held=null,markers=refreshed(it.markers,null),running=true,
            autoSetupStatus=if(doAuto)"자동 조정 중 · ${autoPass+1}/4 단계" else "",message="연결/수신 중 · 넓은 Span은 순차 스윕입니다")}
        job=viewModelScope.launch {
            try {
                var fullFrames=0;var finishedAuto=!doAuto
                var peak=Double.NEGATIVE_INFINITY;var floor=Double.POSITIVE_INFINITY;var clip=0.0
                val processing=TraceProcessing()
                val stream=src.frames(c)
                stream.map{raw->raw to processing.apply(raw,c)}.flowOn(Dispatchers.Default).collect{(raw,f)->
                    if(id==generation && !finishedAuto && raw.completedSegments==raw.segmentCount) {
                        val valid=raw.levelsDb.filter{it.isFinite()}.sorted()
                        if(valid.isNotEmpty()){
                            peak=maxOf(peak,valid.last().toDouble())
                            floor=minOf(floor,valid[valid.size/10].toDouble())
                            clip=maxOf(clip,raw.clippedFraction);fullFrames++
                        }
                        if(fullFrames>=2){
                            val result=AutoSetup.decide(state.value.selectedMode?:FieldMode.EQUIPMENT,c.manualGainLevel,clip,peak,floor,c.refLevelOffsetDb)
                            if(result.gain!=c.manualGainLevel && autoPass<3 && nativeContext!=null){
                                mutable.update{it.copy(config=it.config.copy(manualGainLevel=result.gain,autoGain=false))}
                                start(nativeContext,autoPass+1)
                                return@collect
                            }
                            finishedAuto=true
                            mutable.update{it.copy(config=it.config.copy(refLevelDb=result.ref,dbPerDiv=result.scale),held=null,
                                autoSetupStatus=if(clip>0.001)"자동 조정 종료 · 입력 과다 확인 필요" else "자동 조정 완료 · 이득 ${c.manualGainLevel}/10 고정")}
                        }
                    }
                    if(id==generation)mutable.update{old->
                        val held=if(old.maxHold)SweepMath.hold(old.held,f)else null
                        old.copy(rawFrame=raw,frame=f,completeFrame=if(f.completedSegments==f.segmentCount)f else old.completeFrame,held=held,markers=refreshed(old.markers,held?:f),
                            message=if(f.clippedFraction>0.001)"입력 클리핑 감지: 이득을 낮추거나 감쇠하세요"
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
            appendLine("# SpectrumCheck 2.6.5; ${f.source}; ${f.displayUnit}; max_hold=${s.maxHold}; dc_removed=${f.dcRemoved}")
            f.timingNs?.let { appendLine("# timing_raw=" + it.joinToString(";") + "; order=tune_ns,settle_ns,reset_ns,discard_ns,read_ns,dsp_ns,mux_ns,pll_ns,i2c_write_ns,i2c_read_ns,other_usb_ns,write_count,read_count,other_count") }
            appendLine("# sample_rate_hz=${f.sampleRateHz}; fft=${f.fftSize}; rbw_hz=${f.rbwHz}; enbw_hz=${f.enbwHz}; vbw_khz=${f.vbwKhz}; offset_db=${f.offsetDb}; gain_step=${f.gainStep}; tuner_agc=${f.autoGain}; completed_segments=${f.completedSegments}; sweep_ms=${f.lastSweepMs}; tune_settle_ms=${s.config.tuneSettleMs}; native_settle_ms=${s.config.nativeSettleMs}; segments=${f.segmentCount}; start_ms=${f.startedMs}; end_ms=${f.timestampMs}; clipping=${f.clippedFraction}")
            appendLine("# rbw_requested_khz=${s.config.rbwKhz}; ref_level=${s.config.refLevelDb}; db_per_div=${s.config.dbPerDiv}; integration_bw_mhz=${s.config.integrationBwMhz}")
            for(m in s.markers.filter{it.enabled})appendLine("# marker_${m.index}=${m.freqMhz} MHz; ${m.levelDb} ${f.displayUnit}")
            if(s.config.channelPowerEnabled)Measurements.channelPower(s.completeFrame,s.config.centerMhz,s.config.integrationBwMhz)?.let{
                appendLine("# live_channel_power=${it.totalDb}; live_psd_per_mhz=${it.psdDbPerMhz}; unit=${s.completeFrame?.displayUnit}; completed_at_ms=${s.completeFrame?.timestampMs}; max_hold_not_integrated=true")
            }
            f.timingNs?.let { appendLine("# timing_ns_tune_settle_reset_discard_read_dsp=${it.joinToString(",")}") }
            appendLine("frequency_mhz,display_level,level_without_offset,unit,last_observed_at_epoch_ms")
            for(i in f.levelsDb.indices)appendLine("${f.frequencyAt(i)},${f.levelsDb[i]},${f.levelsDb[i]-f.offsetDb},${f.displayUnit},${f.observedAtMs[i]}")
        }
    }
}
