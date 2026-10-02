package com.lteduenv.spectrum.data

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** One contiguous narrowband emission standing above the sweep's own median level. */
data class SpurCandidate(val freqMhz:Double,val levelDb:Float,val aboveFloorDb:Float,val widthKhz:Double)
/** One ~1 MHz slice of the sweep, compared against the sweep's own median block level. */
data class PowerBlock(val startMhz:Double,val stopMhz:Double,val avgDb:Double,val aboveMedianDb:Double)
/** A short, hedged, Korean one-line read of a completed sweep. Never a pass/fail certification. */
data class Verdict(val text:String,val anomaly:Boolean)

/**
 * Floor- and median-relative anomaly summaries. Everything here is a *relative*
 * comparison within one completed sweep (dBFS/offset, not calibrated dBm), and
 * is meant as a quick pointer for the field engineer, not an automatic PIM or
 * pass/fail verdict.
 */
object SpectrumAnalysis {
    const val DEFAULT_THRESHOLD_DB = 10.0

    fun medianLevelDb(f:SpectrumFrame):Float? {
        val v=f.levelsDb.filter{it.isFinite()}.sorted()
        return if(v.isEmpty())null else v[v.size/2]
    }

    /**
     * Narrow emissions standing [thresholdDb] above the sweep's median level.
     * Adjacent above-threshold bins (gaps up to [mergeGapBins]) merge into one
     * candidate; its -10 dB width is reported so a wide legitimate carrier can
     * be told apart from a narrowband spur by eye.
     */
    fun spurCandidates(f:SpectrumFrame,thresholdDb:Float=DEFAULT_THRESHOLD_DB.toFloat(),mergeGapBins:Int=2,maxCount:Int=10):List<SpurCandidate> {
        val floor=medianLevelDb(f)?:return emptyList()
        val lv=f.levelsDb
        if(lv.size<2)return emptyList()
        val binMhz=(f.stopMhz-f.startMhz)/(f.pointCount-1)
        val above=BooleanArray(lv.size){lv[it].isFinite() && lv[it]>floor+thresholdDb}
        val out=ArrayList<SpurCandidate>()
        var i=0
        while(i<lv.size){
            if(!above[i]){i++;continue}
            var end=i;var gap=0;var j=i+1
            while(j<lv.size && gap<=mergeGapBins){if(above[j]){end=j;gap=0}else gap++;j++}
            var k=i
            for(m in i..end)if(lv[m].isFinite() && lv[m]>lv[k])k=m
            val peak=lv[k]
            var a=k;while(a>i && lv[a-1].isFinite() && lv[a-1]>=peak-10)a--
            var b=k;while(b<end && lv[b+1].isFinite() && lv[b+1]>=peak-10)b++
            out+=SpurCandidate(f.frequencyAt(k),peak,peak-floor,(b-a+1)*binMhz*1000)
            i=end+1
        }
        return out.sortedByDescending{it.levelDb}.take(maxCount)
    }

    /** Splits the sweep into ~[blockMhz] blocks and reports each one's average level vs the sweep's own median block. */
    fun powerBlocks(f:SpectrumFrame,blockMhz:Double=1.0):List<PowerBlock> {
        if(f.pointCount<2)return emptyList()
        val binMhz=(f.stopMhz-f.startMhz)/(f.pointCount-1)
        if(binMhz<=0)return emptyList()
        val perBlock=max(1,(blockMhz/binMhz).toInt())
        val raw=ArrayList<Pair<Int,Int>>()
        var i=0
        while(i<f.pointCount){raw+=i to min(i+perBlock,f.pointCount);i+=perBlock}
        val levels=raw.mapNotNull{(start,end)->
            var sum=0.0;var n=0
            for(k in start until end){val v=f.levelsDb[k];if(v.isFinite()){sum+=10.0.pow(v/10.0);n++}}
            if(n==0)null else Triple(start,end,10*log10(sum/n))
        }
        if(levels.isEmpty())return emptyList()
        val median=levels.map{it.third}.sorted()[levels.size/2]
        return levels.map{(start,end,avg)->PowerBlock(f.frequencyAt(start),f.frequencyAt(end-1),avg,avg-median)}
    }

    /** A completed sweep only; mid-sweep frames are too partial for a floor/median read. */
    fun summarize(f:SpectrumFrame?,mode:FieldMode?):Verdict? {
        if(f==null || f.completedSegments<f.segmentCount)return null
        return if(mode==FieldMode.EQUIPMENT)summarizeEquipment(f) else summarizeAntenna(f)
    }

    private fun summarizeAntenna(f:SpectrumFrame):Verdict {
        val floor=medianLevelDb(f)?:return Verdict("AI 요약: 분석할 유효 데이터가 없습니다",false)
        val candidates=spurCandidates(f)
        if(candidates.isEmpty())
            return Verdict("AI 요약: 불요파 의심 신호 없음 · 노이즈 플로어 %.1f dB 대비 대역 전체가 평탄합니다 (상대 레벨 기준, 참고용)".format(floor),false)
        val top=candidates.first()
        val extra=if(candidates.size>1)" 외 ${candidates.size-1}건" else ""
        return Verdict("AI 요약: 불요파 의심 %d건%s · 최강 %.4f MHz, 플로어 대비 +%.1f dB, 폭 %.1f kHz — 협대역 간섭 가능성 (참고용, 확정 판정 아님)"
            .format(candidates.size,extra,top.freqMhz,top.aboveFloorDb,top.widthKhz),true)
    }

    private fun summarizeEquipment(f:SpectrumFrame):Verdict {
        val blocks=powerBlocks(f)
        if(blocks.isEmpty())return Verdict("AI 요약: 분석할 유효 데이터가 없습니다",false)
        val anomalies=blocks.filter{it.aboveMedianDb>DEFAULT_THRESHOLD_DB}
        if(anomalies.isEmpty())
            return Verdict("AI 요약: 리버스 경로 이상 구간 없음 · 전 대역 레벨이 구간 중앙값 대비 고르게 분포합니다 (참고용)",false)
        val top=anomalies.maxByOrNull{it.aboveMedianDb}!!
        val extra=if(anomalies.size>1)" 외 ${anomalies.size-1}개 구간" else ""
        return Verdict("AI 요약: 리버스 경로 이상 의심 %d개 구간%s · 최대 %.3f–%.3f MHz, 중앙값 대비 +%.1f dB — 반사/불량 접속부 등 확인 권장 (참고용, 확정 판정 아님)"
            .format(anomalies.size,extra,top.startMhz,top.stopMhz,top.aboveMedianDb),true)
    }
}
