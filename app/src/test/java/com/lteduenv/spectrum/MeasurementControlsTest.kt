package com.lteduenv.spectrum

import com.lteduenv.spectrum.data.*
import com.lteduenv.spectrum.data.sdr.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MeasurementControlsTest {
    @Test fun peakMarkerReadsTheActualPeakBin() {
        val p=SweepMath.plan(SweepConfig(centerMhz=1745.0,spanMhz=25.0))
        val values=FloatArray(p.pointCount){-90f};values[12345]=-20f
        val f=SpectrumFrame(p.startMhz,p.stopMhz,values,1)
        val frequency=Measurements.peak(f)!!
        assertEquals(-20f,f.levelAt(frequency),0f)
        assertTrue(f.levelAt(p.stopMhz+1).isNaN())
    }
    @Test fun refLevelDoesNotChangeDataAndOffsetAddsExactly() {
        val raw=SpectrumFrame(900.0,901.0,floatArrayOf(-70f,-50f),1,unit="dBFS",source="RTL-SDR")
        val p=TraceProcessing()
        assertArrayEquals(raw.levelsDb,p.apply(raw,SweepConfig(refLevelDb=-40.0)).levelsDb,0f)
        assertArrayEquals(floatArrayOf(-27f,-7f),p.apply(raw,SweepConfig(refLevelOffsetDb=43.0)).levelsDb,0f)
        val down=p.apply(raw,SweepConfig(refLevelOffsetDb=-43.0))
        assertArrayEquals(floatArrayOf(-113f,-93f),down.levelsDb,0f)
        assertFalse(down.displayUnit.contains("dBm"));assertArrayEquals(floatArrayOf(-70f,-50f),raw.levelsDb,0f)
    }
    @Test fun bandwidthControlsChangeActualResolution() {
        val narrow=SweepMath.plan(SweepConfig(rbwKhz=1.7))
        val wide=SweepMath.plan(SweepConfig(rbwKhz=100.0))
        assertEquals(2048,narrow.fftSize);assertEquals(32,wide.fftSize)
        assertTrue(wide.rbwHz>narrow.rbwHz*40)
        assertTrue(narrow.enbwHz>narrow.rbwHz)
        assertEquals(1.44,narrow.rbwHz/narrow.binHz,0.02)
    }
    @Test fun sweepBinsRemainAlignedAtEverySelectableResolution() {
        for(rbw in listOf(0.5,1.7,10.0,30.0,100.0,300.0))for(span in listOf(1.5,15.0,30.0,35.0)) {
            val p=SweepMath.plan(SweepConfig(centerMhz=900.0,spanMhz=span,rbwKhz=rbw));var cursor=0
            for(s in p.segments) {
                assertEquals(cursor,s.startIndex);assertTrue(s.fftStart>=p.fftSize/8)
                assertTrue(s.fftStart+s.count<=p.fftSize*7/8)
                for(k in 0 until s.count)assertEquals(p.startMhz+(s.startIndex+k)*p.binHz/1e6,
                    s.centerMhz+(s.fftStart+k-p.fftSize/2)*p.binHz/1e6,1e-9)
                cursor+=s.count
            }
            assertEquals(p.pointCount,cursor)
        }
    }
    private fun flatFrame():SpectrumFrame {
        val p=SweepMath.plan(SweepConfig(centerMhz=900.0,spanMhz=25.0))
        return SpectrumFrame(p.startMhz,p.stopMhz,FloatArray(p.pointCount){(-100+10*log10(p.enbwHz)).toFloat()},1,
            enbwHz=p.enbwHz,fftSize=p.fftSize)
    }
    @Test fun integratedWhiteNoisePowerUsesBandwidthAndEnbw() {
        val f=flatFrame();val one=Measurements.channelPower(f,900.0,1.0)!!
        val twenty=Measurements.channelPower(f,900.0,20.0)!!
        assertEquals(-40.0,one.totalDb,0.001)
        assertEquals(10*log10(20.0),twenty.totalDb-one.totalDb,0.001)
        assertEquals(one.psdDbPerMhz,twenty.psdDbPerMhz,0.001)
        assertEquals(-40+10*log10(0.713),Measurements.channelPower(f,900.0,0.713)!!.totalDb,0.001)
    }
    @Test fun channelPowerRejectsMissingCoverageAndOffsetChangesItOnce() {
        val f=flatFrame()
        assertNull(Measurements.channelPower(f,900.0,30.0))
        assertNull(Measurements.channelPower(f,930.0,1.0))
        val shifted=TraceProcessing().apply(f,SweepConfig(refLevelOffsetDb=13.0))
        assertEquals(13.0,Measurements.channelPower(shifted,900.0,1.0)!!.totalDb-Measurements.channelPower(f,900.0,1.0)!!.totalDb,0.001)
    }
    @Test fun hannIntegrationRecoversKnownTonePower() {
        for(n in listOf(32,2048)) {
            val iq=FloatArray(n*2){i->val a=2*PI*3*(i/2)/n;if(i%2==0)(0.5*cos(a)).toFloat()else(0.5*sin(a)).toFloat()}
            val bin=SweepMath.RATE.toDouble()/n
            val f=SpectrumFrame(-1.2,1.2-bin/1e6,Fft.magnitudeSpectrumDb(iq,n),1,enbwHz=SweepMath.bandwidth(n).enbwHz)
            assertEquals(-6.0205999,Measurements.channelPower(f,-bin/2e6,2.4)!!.totalDb,0.001)
        }
    }
    @Test fun vbwFiltersLinearPowerWithinConsecutiveIqFrames() {
        val n=32;val count=SpectrumDsp.blockFrames(n,1.0);assertTrue(count>1)
        val iq=FloatArray(n*count*2){i->
            val sample=i/2;val amplitude=if(sample/n==count-1)0.9 else 0.1
            val angle=2*PI*3*(sample%n)/n
            (amplitude*if(i%2==0)cos(angle)else sin(angle)).toFloat()
        }
        val filtered=SpectrumDsp.spectrum(iq,n,false,1.0)[n/2+3]
        val raw=SpectrumDsp.spectrum(iq,n,false,0.0)[n/2+3]
        val alpha=-expm1(-2*PI*1000*n/SweepMath.RATE)
        assertEquals(10*log10(0.01*(1-alpha)+0.81*alpha),filtered.toDouble(),0.002)
        assertEquals(20*log10(0.9),raw.toDouble(),0.002)
        assertTrue(filtered<raw)
    }
    @Test fun maxHoldResetsAcrossOffsetAgcAndBandwidthChanges() {
        val old=flatFrame()
        for(new in listOf(old.copy(offsetDb=10.0),old.copy(autoGain=true),old.copy(vbwKhz=1.0),old.copy(fftSize=32)))assertFalse(SweepMath.compatible(old,new))
    }
    @Test(expected=IllegalArgumentException::class) fun channelBandwidthCannotExceedSpan(){SweepMath.plan(SweepConfig(channelPowerEnabled=true,integrationBwMhz=20.0))}
    @Test fun allBandPresetsAllowFullIntegration() {
        for(b in BandPresets.all) {
            val p=SweepMath.plan(SweepConfig(centerMhz=b.uplinkMhz,spanMhz=b.spanMhz,integrationBwMhz=b.integrationBwMhz,channelPowerEnabled=true))
            val f=SpectrumFrame(p.startMhz,p.stopMhz,FloatArray(p.pointCount){-80f},1,enbwHz=p.enbwHz)
            assertNotNull(Measurements.channelPower(f,b.uplinkMhz,b.integrationBwMhz))
        }
    }
}
