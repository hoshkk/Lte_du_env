package com.lteduenv.spectrum
import com.lteduenv.spectrum.data.*
import com.lteduenv.spectrum.data.sdr.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
class SpectrumMathTest {
 @Test fun toneFrequencyAndLevel(){
  val n=2048
  for(bin in listOf(-600,-17,0,31,600)){
   val iq=FloatArray(n*2){i->val phase=2*PI*bin*(i/2)/n;if(i%2==0)(0.5*cos(phase)).toFloat()else(0.5*sin(phase)).toFloat()}
   val db=Fft.magnitudeSpectrumDb(iq,n)
   assertEquals(n/2+bin,db.indices.maxBy{db[it]});assertEquals(-6.0206,db[n/2+bin].toDouble(),0.01)
  }
 }
 @Test fun iqAcrossOddPackets(){val a=IqAssembler(2);a.append(byteArrayOf(0,127,(-1).toByte()),3);assertNull(a.take());a.append(byteArrayOf((-128).toByte()),1);assertArrayEquals(floatArrayOf(-127.5f/128,-0.5f/128,127.5f/128,0.5f/128),a.take(),0f)}
 @Test fun sweepHasNoGapsOrMislabelledBins(){for(span in listOf(0.05,1.5,1.799,1.8,2.0,15.0,25.0,35.0)){
  val p=SweepMath.plan(SweepConfig(centerMhz=900.0,spanMhz=span));var next=0
  for(s in p.segments){assertEquals(next,s.startIndex);assertTrue(s.fftStart>=256);assertTrue(s.fftStart+s.count<=1792)
   for(k in 0 until s.count)assertEquals(p.startMhz+(s.startIndex+k)*SweepMath.BIN_HZ/1e6,s.centerMhz+(s.fftStart+k-1024)*SweepMath.BIN_HZ/1e6,1e-9)
   next+=s.count
  };assertEquals(p.pointCount,next);assertTrue(p.stopMhz-p.startMhz<=span+1e-9)
 }}
 @Test(expected=IllegalArgumentException::class) fun upperBandMustNotBeTruncated(){SweepMath.plan(SweepConfig(1750.0,35.0))}
 @Test(expected=IllegalArgumentException::class) fun nanRejected(){SweepMath.plan(SweepConfig(Double.NaN))}
 @Test fun maxHoldKeepsPeakTimeAndResetsOnGainChange(){
  val a=SpectrumFrame(900.0,901.0,floatArrayOf(-20f,-60f),100)
  val b=a.copy(levelsDb=floatArrayOf(-40f,-30f),timestampMs=200,observedAtMs=longArrayOf(200,200))
  val h=SweepMath.hold(a,b);assertArrayEquals(floatArrayOf(-20f,-30f),h.levelsDb,0f);assertArrayEquals(longArrayOf(100,200),h.observedAtMs)
  assertArrayEquals(b.levelsDb,SweepMath.hold(a,b.copy(gainStep=2)).levelsDb,0f)
 }
 @Test fun dcRemovalIsExplicit(){val iq=FloatArray(4096){0.5f};assertTrue(Fft.magnitudeSpectrumDb(iq,2048)[1024]>-4);assertTrue(Fft.magnitudeSpectrumDb(iq,2048,true)[1024]<-140)}
}
