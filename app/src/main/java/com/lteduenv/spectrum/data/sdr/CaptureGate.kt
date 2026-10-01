package com.lteduenv.spectrum.data.sdr

/** Keeps retune discard and IQ assembly in the reader. No claim of an RF tune ACK:
 * rtl_tcp has none. The guard and byte discard need validation on the actual driver.
 * Absolute byte parity survives fragmented reads and gate changes (I must precede Q).
 */
class CaptureGate(private val complexSamples:Int) {
    private val assembler=IqAssembler(complexSamples)
    private var streamBytes=0L
    private var readyAt=Long.MAX_VALUE
    private var discard=0
    private var collecting=false
    private var latest:FloatArray?=null
    @Synchronized fun arm(nowNanos:Long,settleMs:Int,discardBytes:Int=131072) {
        require(settleMs>=0 && discardBytes>=0)
        readyAt=nowNanos+settleMs*1_000_000L
        discard=discardBytes;collecting=false;latest=null;assembler.reset()
    }
    @Synchronized fun append(raw:ByteArray,n:Int,nowNanos:Long) {
        require(n in 0..raw.size)
        val base=streamBytes;streamBytes+=n
        if(nowNanos<readyAt)return
        var start=minOf(discard,n);discard-=start
        if(discard>0 || start==n)return
        if(!collecting) {
            if((base+start)%2L!=0L)start++
            if(start>=n)return
            collecting=true
        }
        assembler.append(raw,n-start,start)
        assembler.take()?.let{latest=it}
    }
    @Synchronized fun take():FloatArray?=latest.also{latest=null}
}
