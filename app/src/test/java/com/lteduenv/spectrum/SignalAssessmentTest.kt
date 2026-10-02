package com.lteduenv.spectrum
import com.lteduenv.spectrum.data.*
import org.junit.Assert.*
import org.junit.Test

class SignalAssessmentTest {
    private val flat=SpectrumFrame(900.0,901.0,FloatArray(101){-70f},1000,startedMs=900,rbwHz=10000.0)

    @Test fun flatSweepHasNoPeak(){
        assertTrue(SignalAssessment.assess(flat,null,false).contains("피크 없음"))
    }
    @Test fun narrowPeakIsLocatedByFrequency(){
        val tone=flat.copy(levelsDb=flat.levelsDb.copyOf().also{it[40]=-45f})
        assertTrue(SignalAssessment.assess(tone,null,false).contains("900.4000"))
    }
    @Test fun clippingBlocksAssessment(){
        assertTrue(SignalAssessment.assess(flat.copy(clippedFraction=.01),null,false).contains("클리핑"))
    }
    @Test fun autoGainBlocksAssessment(){
        assertTrue(SignalAssessment.assess(flat.copy(autoGain=true),null,false).contains("AGC"))
    }
    @Test fun dcRemovedLimitsAssessment(){
        assertTrue(SignalAssessment.assess(flat.copy(dcRemoved=true),null,false).contains("분석 제한"))
    }
    @Test fun nonFiniteLevelWaits(){
        val f=flat.copy(levelsDb=flat.levelsDb.copyOf().also{it[0]=Float.NaN})
        assertTrue(SignalAssessment.assess(f,null,false).contains("대기"))
    }
    @Test fun incompleteSweepWaits(){
        assertTrue(SignalAssessment.assess(flat.copy(completedSegments=0),null,false).contains("대기"))
    }
    @Test fun staleObservedTimeWaits(){
        val f=flat.copy(observedAtMs=LongArray(101){800})
        assertTrue(SignalAssessment.assess(f,null,false).contains("대기"))
    }
    @Test fun broadRiseAgainstBaselineIsFlagged(){
        val raised=flat.copy(levelsDb=FloatArray(101){-62f})
        assertTrue(SignalAssessment.assess(raised,flat,true).contains("광대역 레벨 상승 의심"))
    }
    @Test fun incompatibleBaselineIsRejected(){
        val raised=flat.copy(levelsDb=FloatArray(101){-62f})
        assertTrue(SignalAssessment.assess(raised.copy(gainStep=4),flat,true).contains("조건 불일치"))
    }
    @Test fun missingBaselineIsReported(){
        assertTrue(SignalAssessment.assess(flat,null,true).contains("기준 미저장"))
    }
    @Test fun matchingBaselineShowsNoRise(){
        val tone=flat.copy(levelsDb=flat.levelsDb.copyOf().also{it[40]=-45f})
        assertTrue(SignalAssessment.assess(flat,tone,true).contains("상승 없음"))
    }
}
