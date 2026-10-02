package com.lteduenv.spectrum.data

import java.util.Locale

/** Local rule-based screening, not a learned model or a fault verdict. Raw complete sweeps only. */
object SignalAssessment {
    private fun valid(f:SpectrumFrame)=f.completedSegments==f.segmentCount && f.pointCount>=8 &&
        f.levelsDb.all{it.isFinite()} && f.observedAtMs.size==f.pointCount &&
        f.observedAtMs.all{it>=f.startedMs}
    fun compatible(a:SpectrumFrame,b:SpectrumFrame)=a.startMhz==b.startMhz && a.stopMhz==b.stopMhz &&
        a.pointCount==b.pointCount && a.sampleRateHz==b.sampleRateHz && a.fftSize==b.fftSize &&
        a.gainStep==b.gainStep && a.autoGain==b.autoGain && a.rbwHz==b.rbwHz &&
        a.enbwHz==b.enbwHz && a.dcRemoved==b.dcRemoved && a.source==b.source && a.unit==b.unit &&
        a.offsetDb==b.offsetDb && a.vbwKhz==b.vbwKhz
    fun usableBaseline(f:SpectrumFrame)=valid(f) && !f.autoGain && !f.dcRemoved && f.clippedFraction<=0.001
    fun assess(f:SpectrumFrame?,baseline:SpectrumFrame?,equipment:Boolean):String {
        if(f==null || !valid(f))return "분석 대기 · 유효한 전체 스윕 필요"
        if(f.clippedFraction>0.001)return "입력 과다 의심 · 클리핑 감지 · 이득 또는 입력 감쇠 확인"
        if(f.autoGain)return "분석 제한 · AGC를 끄고 같은 이득으로 비교하세요"
        if(f.dcRemoved)return "분석 제한 · DC 제거로 중심 신호가 가려질 수 있습니다"
        val sorted=f.levelsDb.sorted(); val floor=sorted[sorted.size/2]
        val peak=f.levelsDb.indices.maxBy{f.levelsDb[it]}
        val excess=f.levelsDb[peak]-floor
        var left=peak; var right=peak
        while(left>0 && f.levelsDb[left-1]>floor+6)left--
        while(right<f.pointCount-1 && f.levelsDb[right+1]>floor+6)right++
        val width=(right-left+1)*(f.stopMhz-f.startMhz)*1e6/(f.pointCount-1)
        val candidate=excess>=10 && width<=maxOf(200_000.0,3*f.rbwHz)
        val parts=mutableListOf<String>()
        if(candidate)parts += String.format(Locale.US,"협대역 피크 의심 · %.4f MHz · 바닥 대비 +%.1f dB",f.frequencyAt(peak),excess)
        if(equipment){
            if(baseline==null)parts += "기준 미저장 · 광대역 잡음 상승 비교 대기"
            else if(!usableBaseline(baseline)||!compatible(f,baseline))parts += "기준 조건 불일치 · 기준 재저장 필요"
            else {
                val differences=f.levelsDb.indices.map{f.levelsDb[it]-baseline.levelsDb[it]}.sorted()
                val rise=differences[differences.size/2]
                val ratio=differences.count{it>=6}.toDouble()/differences.size
                if(rise>=6 && ratio>=0.7)parts += String.format(Locale.US,"광대역 레벨 상승 의심 · 기준 대비 중앙값 +%.1f dB",rise)
                else parts += String.format(Locale.US,"기준 대비 중앙값 %+.1f dB · 뚜렷한 광대역 상승 없음",rise)
            }
        } else if(!candidate)parts += "현재 스윕에서 뚜렷한 협대역 피크 없음"
        return parts.joinToString(" / ")+" · 규칙 기반 참고, 정상·불량/PIM 확정 아님"
    }
}
