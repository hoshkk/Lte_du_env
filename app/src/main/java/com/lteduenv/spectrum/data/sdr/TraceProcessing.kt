package com.lteduenv.spectrum.data.sdr

import com.lteduenv.spectrum.data.*
import kotlin.math.*

/** Linear-power first-order smoothing within a consecutive IQ block (software VBW). */
object SpectrumDsp {
    fun blockFrames(fftSize:Int,vbwKhz:Double):Int = if(vbwKhz==0.0)1 else
        ceil(5.0*SweepMath.RATE/(2*PI*vbwKhz*1000*fftSize)).toInt().coerceIn(1,65536/fftSize)
    fun spectrum(iq:FloatArray,fftSize:Int,removeDc:Boolean,vbwKhz:Double):FloatArray {
        require(iq.isNotEmpty() && iq.size%(fftSize*2)==0)
        val frames=iq.size/(fftSize*2)
        val alpha=if(vbwKhz<=0)1.0 else -expm1(-2*PI*vbwKhz*1000*fftSize/SweepMath.RATE)
        val power=DoubleArray(fftSize)
        for(frame in 0 until frames){
            val db=Fft.magnitudeSpectrumDb(iq.copyOfRange(frame*fftSize*2,(frame+1)*fftSize*2),fftSize,removeDc)
            for(i in db.indices){val p=10.0.pow(db[i]/10.0);power[i]=if(frame==0)p else power[i]*(1-alpha)+p*alpha}
        }
        return FloatArray(fftSize){(10*log10(power[it].coerceAtLeast(1e-30))).toFloat()}
    }
}
/** Offset is an explicit additive display correction. It never declares dBm calibration. */
class TraceProcessing {
    fun apply(raw:SpectrumFrame,c:SweepConfig)=raw.copy(
        levelsDb=FloatArray(raw.pointCount){raw.levelsDb[it]+c.refLevelOffsetDb.toFloat()},
        offsetDb=c.refLevelOffsetDb,vbwKhz=c.vbwKhz,autoGain=c.autoGain)
}
data class ChannelPower(val totalDb:Double,val psdDbPerMhz:Double)
object Measurements {
    /** Integral of PSD: Hann coherent-tone normalized bins are divided by ENBW. */
    fun channelPower(f:SpectrumFrame?,centerMhz:Double,bwMhz:Double):ChannelPower? {
        if(f==null || f.pointCount<2 || !centerMhz.isFinite() || !bwMhz.isFinite() || bwMhz<=0 || f.enbwHz<=0)return null
        val step=(f.stopMhz-f.startMhz)/(f.pointCount-1)
        if(step<=0)return null
        val low=centerMhz-bwMhz/2;val high=centerMhz+bwMhz/2
        if(low<f.startMhz-step/2-1e-9 || high>f.stopMhz+step/2+1e-9)return null
        var sum=0.0
        for(i in f.levelsDb.indices){
            val frequency=f.frequencyAt(i)
            val width=(min(high,frequency+step/2)-max(low,frequency-step/2)).coerceAtLeast(0.0)
            sum+=10.0.pow(f.levelsDb[i]/10.0)*width*1e6/f.enbwHz
        }
        if(sum<=0 || !sum.isFinite())return null
        val total=10*log10(sum)
        return ChannelPower(total,total-10*log10(bwMhz))
    }
    fun peak(f:SpectrumFrame?):Double?=f?.levelsDb?.indices?.filter{f.levelsDb[it].isFinite()}?.maxByOrNull{f.levelsDb[it]}?.let{f.frequencyAt(it)}
}
