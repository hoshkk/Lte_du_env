package com.lteduenv.spectrum.data.sdr

/** Synchronous, bounded USB reads. No queued windows are carried across tuning boundaries. */
class UsbIqReader(private val read:(ByteArray,Int)->Int,private val checkActive:()->Unit) {
    private val raw=ByteArray(16384)
    fun discard(bytes:Int=4096){ transfer(bytes,null) }
    fun capture(iq:FloatArray){transfer(iq.size,iq)}
    private fun transfer(bytes:Int,iq:FloatArray?) {
        require(bytes>0 && bytes%2==0)
        var offset=0
        while(offset<bytes) {
            checkActive()
            val requested=(((bytes-offset).coerceAtMost(raw.size)+511)/512)*512
            val n=read(raw,requested)
            check(n in 1..requested && n%2==0){"USB IQ 길이 오류"}
            val use=minOf(n,bytes-offset)
            if(iq!=null)for(i in 0 until use)iq[offset+i]=((raw[i].toInt() and 255)-127.5f)/128f
            offset+=use
        }
    }
}
