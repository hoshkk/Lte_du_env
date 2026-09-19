package com.lteduenv.spectrum.data

import kotlin.math.roundToInt

/** User-supplied RX presets; verify the actual site's assignment before measuring. */
data class BandPreset(val id:String,val label:String,val uplinkMhz:Double,val spanMhz:Double,val integrationBwMhz:Double)
object BandPresets {
    val all = listOf(
        BandPreset("b8","B8 RX",909.3,15.0,10.0),
        BandPreset("b3_20","B3 RX 20M",1745.0,25.0,20.0),
        // 35 MHz would extend past the usual 1766 MHz tuner limit; no silent truncation.
        BandPreset("b3_30","B3 RX 30M",1750.0,30.0,30.0),
    )
}
data class SweepConfig(
    val centerMhz:Double=909.3,
    val spanMhz:Double=1.5,
    val refLevelDb:Double=0.0,
    val manualGainLevel:Int=1,
    val removeDc:Boolean=false,
    val refLevelOffsetDb:Double=0.0,
    val rbwKhz:Double=1.7,
    val vbwKhz:Double=0.0,
    val integrationBwMhz:Double=1.0,
    val channelPowerEnabled:Boolean=false,
    val autoGain:Boolean=false,
    val dbPerDiv:Double=10.0,
)
data class SpectrumFrame(
    val startMhz:Double,
    val stopMhz:Double,
    val levelsDb:FloatArray,
    val timestampMs:Long,
    val startedMs:Long=timestampMs,
    val source:String="DEMO",
    val unit:String="DEMO dB",
    val gainStep:Int=1,
    val sampleRateHz:Int=2_400_000,
    val fftSize:Int=2048,
    val enbwHz:Double=0.0,
    val segmentCount:Int=1,
    val clippedFraction:Double=0.0,
    val dcRemoved:Boolean=false,
    val observedAtMs:LongArray=LongArray(levelsDb.size){timestampMs},
    val offsetDb:Double=0.0,
    val rbwHz:Double=0.0,
    val vbwKhz:Double=0.0,
    val autoGain:Boolean=false,
) {
    val pointCount get()=levelsDb.size
    val displayUnit get()=if(offsetDb==0.0)unit else if(source=="DEMO")"DEMO 상대 dB (Offset)" else "상대 dB (Offset)"
    fun frequencyAt(i:Int)=startMhz+i.toDouble()*(stopMhz-startMhz)/(pointCount-1).coerceAtLeast(1)
    fun levelAt(f:Double):Float {
        if(pointCount==0 || f !in startMhz..stopMhz) return Float.NaN
        if(pointCount==1 || stopMhz==startMhz)return levelsDb[0]
        val i=(((f-startMhz)/(stopMhz-startMhz))*(pointCount-1)).roundToInt().coerceIn(0,pointCount-1)
        return levelsDb[i]
    }
}
data class Marker(val index:Int=1,val enabled:Boolean=false,val freqMhz:Double=0.0,val levelDb:Float=Float.NaN)
