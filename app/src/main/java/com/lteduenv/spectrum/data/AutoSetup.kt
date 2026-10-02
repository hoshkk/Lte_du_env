package com.lteduenv.spectrum.data
import kotlin.math.ceil
object AutoSetup {
 data class Result(val gain:Int,val ref:Double,val scale:Double)
 fun decide(mode:FieldMode,gain:Int,clipping:Double,peak:Double,floor:Double,offset:Double):Result {
  val next=when {
   clipping>0.001 -> (gain-1).coerceAtLeast(1)
   else -> gain
  }
  // Eight divisions: representative low level 1.5 divisions above bottom.
  var scale=10.0
  if(peak-floor+10 > 65)scale=15.0
  val ref=(ceil(maxOf(floor+offset+6.5*scale,peak+offset+10)/5)*5).coerceIn(-100.0,100.0)
  return Result(next,ref,scale)
 }
}
