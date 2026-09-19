package com.lteduenv.spectrum.data.sdr
import com.lteduenv.spectrum.data.*
import kotlin.math.*

data class SweepSegment(val centerMhz:Double,val startIndex:Int,val count:Int,val fftStart:Int)
data class SweepPlan(val startMhz:Double,val stopMhz:Double,val pointCount:Int,val segments:List<SweepSegment>,
    val fftSize:Int,val binHz:Double,val enbwHz:Double,val rbwHz:Double)
data class WindowBandwidth(val enbwHz:Double,val rbwHz:Double)
object SweepMath {
    const val RATE=2_400_000
    const val FFT=2048
    const val BIN_HZ=RATE.toDouble()/FFT
    const val USABLE=1536
    private val windows=java.util.concurrent.ConcurrentHashMap<Int,WindowBandwidth>()
    fun bandwidth(n:Int):WindowBandwidth=windows.getOrPut(n) {
        val w=DoubleArray(n){0.5-0.5*cos(2*PI*it/(n-1))};val sum=w.sum()
        var lo=0.0;var hi=2.0*RATE/n
        repeat(35){
            val f=(lo+hi)/2
            val amplitude=w.indices.sumOf{w[it]*cos(2*PI*f*(it-(n-1)/2.0)/RATE)}/sum
            if(amplitude*amplitude>0.5)lo=f else hi=f
        }
        WindowBandwidth(RATE*w.sumOf{it*it}/(sum*sum),lo+hi)
    }
    val enbwHz:Double get()=bandwidth(FFT).enbwHz
    fun fftSize(rbwKhz:Double):Int=(4..13).map{1 shl it}.minBy{abs(ln(bandwidth(it).rbwHz/(rbwKhz*1000)))}
    fun plan(c:SweepConfig):SweepPlan {
        require(listOf(c.centerMhz,c.spanMhz,c.refLevelDb,c.refLevelOffsetDb,c.rbwKhz,c.vbwKhz,c.integrationBwMhz,c.dbPerDiv).all{it.isFinite()}){"숫자 입력을 확인하세요"}
        require(c.spanMhz in 0.05..35.0){"Span 범위: 0.05–35 MHz"}
        require(c.centerMhz-c.spanMhz/2>=24.0 && c.centerMhz+c.spanMhz/2<=1766.0){"관측 범위 전체가 24–1766 MHz 안이어야 합니다"}
        require(c.manualGainLevel in 1..10){"수동 이득 단계는 1–10"}
        require(c.refLevelDb in -200.0..200.0){"Ref Level 범위: -200–200 dB"}
        require(c.refLevelOffsetDb in -150.0..150.0){"Offset 범위: -150–150 dB"}
        require(c.rbwKhz in 0.5..300.0){"RBW 목표 범위: 0.5–300 kHz"}
        require(c.vbwKhz==0.0 || c.vbwKhz in 0.05..300.0){"VBW: 0 또는 0.05–300 kHz (0=평활 끔)"}
        require(c.integrationBwMhz in 0.001..35.0){"Integration BW 범위: 0.001–35 MHz"}
        require(!c.channelPowerEnabled || c.integrationBwMhz<=c.spanMhz){"Channel Power를 켜려면 Span을 Integration BW 이상으로 설정하세요"}
        require(c.dbPerDiv in 1.0..20.0){"dB/div 범위: 1–20"}
        val n=fftSize(c.rbwKhz);val bin=RATE.toDouble()/n;val usable=n*3/4
        // Need at least two bins to plot a frequency axis; do not silently expand Span.
        val total=floor(c.spanMhz*1e6/bin+1e-9).toInt()+1
        require(total>=2){"현재 Span에 비해 RBW가 큽니다. RBW를 낮추거나 Span을 넓히세요"}
        val start=c.centerMhz-(total-1)*bin/2e6
        val segments=mutableListOf<SweepSegment>();var offset=0
        while(offset<total) {
            val take=min(usable,total-offset)
            val centerIndex=if(total<=usable)(total-1)/2.0 else min(offset+usable/2.0,total-usable/2.0)
            val ci=round(centerIndex).toInt()
            segments.add(SweepSegment(start+ci*bin/1e6,offset,take,n/2+offset-ci));offset+=take
        }
        val bw=bandwidth(n)
        return SweepPlan(start,start+(total-1)*bin/1e6,total,segments,n,bin,bw.enbwHz,bw.rbwHz)
    }
    fun compatible(a:SpectrumFrame,b:SpectrumFrame)=a.startMhz==b.startMhz && a.stopMhz==b.stopMhz &&
        a.pointCount==b.pointCount && a.gainStep==b.gainStep && a.dcRemoved==b.dcRemoved && a.source==b.source && a.unit==b.unit && a.fftSize==b.fftSize && a.enbwHz==b.enbwHz &&
        a.offsetDb==b.offsetDb && a.vbwKhz==b.vbwKhz && a.autoGain==b.autoGain
    fun hold(previous:SpectrumFrame?,current:SpectrumFrame):SpectrumFrame {
        if(previous==null || !compatible(previous,current))return current.copy(levelsDb=current.levelsDb.copyOf(),observedAtMs=current.observedAtMs.copyOf())
        val levels=current.levelsDb.copyOf();val times=current.observedAtMs.copyOf()
        for(i in levels.indices) if(previous.levelsDb[i]>levels[i]) { levels[i]=previous.levelsDb[i];times[i]=previous.observedAtMs[i] }
        return current.copy(levelsDb=levels,observedAtMs=times)
    }
}
/** Assemble complete IQ windows across USB packet boundaries without per-byte object allocation. */
class IqAssembler(val size:Int=2048) {
    private val work=FloatArray(size*2)
    private var offset=0
    private var result:FloatArray?=null
    fun reset(){offset=0;result=null}
    fun append(raw:ByteArray,n:Int) {
        require(n in 0..raw.size)
        for(i in 0 until n) {
            work[offset++]=((raw[i].toInt() and 255)-127.5f)/128f
            if(offset==work.size){result=work.copyOf();offset=0}
        }
    }
    fun take():FloatArray? {val r=result;result=null;return r}
}
