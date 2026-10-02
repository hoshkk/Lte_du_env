package com.lteduenv.spectrum
import com.lteduenv.spectrum.data.*
import org.junit.Assert.*
import org.junit.Test

class SpectrumAnalysisTest {
    private fun flatFrame(n:Int=400,levelDb:Float=-70f,startMhz:Double=900.0,stopMhz:Double=930.0)=SpectrumFrame(
        startMhz=startMhz,stopMhz=stopMhz,levelsDb=FloatArray(n){levelDb},timestampMs=0,
        segmentCount=1,completedSegments=1)

    @Test fun flatSweepHasNoSpursAndNoAnomaly(){
        val f=flatFrame()
        assertTrue(SpectrumAnalysis.spurCandidates(f).isEmpty())
        val v=SpectrumAnalysis.summarize(f,FieldMode.ANTENNA)!!
        assertFalse(v.anomaly)
        assertTrue(v.text.contains("없음"))
    }

    @Test fun narrowSpikeIsReportedAsOneCandidate(){
        val f=flatFrame()
        val lv=f.levelsDb.copyOf()
        for(i in 198..200)lv[i]=-70f+12f // shoulders above the +10 dB threshold
        lv[199]=-70f+20f // single clear peak so the candidate's centre is unambiguous
        val spiked=f.copy(levelsDb=lv)
        val spurs=SpectrumAnalysis.spurCandidates(spiked)
        assertEquals(1,spurs.size)
        val peakMhz=spiked.frequencyAt(199)
        assertEquals(peakMhz,spurs[0].freqMhz,1e-6)
        assertTrue(spurs[0].aboveFloorDb>15f)
        val v=SpectrumAnalysis.summarize(spiked,FieldMode.ANTENNA)!!
        assertTrue(v.anomaly)
        assertTrue(v.text.contains("불요파 의심 1건"))
    }

    @Test fun elevatedBlockIsReportedAsReverseAnomaly(){
        val f=flatFrame(n=300,startMhz=900.0,stopMhz=930.0) // ~0.1 MHz/bin, 1 MHz ~= 10 bins/block
        val lv=f.levelsDb.copyOf()
        for(i in 100 until 110)lv[i]+=15f // one block raised well above the rest
        val raised=f.copy(levelsDb=lv)
        val blocks=SpectrumAnalysis.powerBlocks(raised)
        assertTrue(blocks.any{it.aboveMedianDb>SpectrumAnalysis.DEFAULT_THRESHOLD_DB})
        val v=SpectrumAnalysis.summarize(raised,FieldMode.EQUIPMENT)!!
        assertTrue(v.anomaly)
        assertTrue(v.text.contains("리버스 경로 이상 의심"))
    }

    @Test fun incompleteSweepYieldsNoVerdict(){
        val f=flatFrame().copy(segmentCount=3,completedSegments=2)
        assertNull(SpectrumAnalysis.summarize(f,FieldMode.ANTENNA))
    }
}
