package com.lteduenv.spectrum.data.sdr

import com.lteduenv.spectrum.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Local Android SDR Driver backend; never connects to an external host. */
class RtlTcpSource {
    @Volatile private var socket:Socket?=null
    private fun shutdown(s:Socket){synchronized(s){runCatching { s.getOutputStream().write(byteArrayOf(0x7e,0,0,0,0)) };runCatching{s.close()}}}
    fun close(){socket?.let{shutdown(it)};socket=null}
    fun frames(config:SweepConfig):Flow<SpectrumFrame> = flow {
        val plan=SweepMath.plan(config)
        val s=Socket();socket=s
        try {
            s.receiveBufferSize=65536;s.soTimeout=4000;s.tcpNoDelay=true
            s.connect(InetSocketAddress("127.0.0.1",1234),3000)
            val input=DataInputStream(s.getInputStream());val output=DataOutputStream(s.getOutputStream())
            val magic=ByteArray(4);input.readFully(magic)
            check(String(magic,Charsets.US_ASCII)=="RTL0"){"RTL-SDR 응답이 아닙니다"}
            val tuner=input.readInt();val count=input.readInt()
            check(tuner==5 || tuner==6){"R820T/R828D용입니다. 감지 튜너: $tuner"}
            check(count==GAINS.size){"지원하지 않는 이득표($count). 드라이버를 업데이트하세요"}
            fun command(code:Int,value:Int){synchronized(s){output.writeByte(code);output.writeInt(value);output.flush()}}
            command(2,SweepMath.RATE);command(3,if(config.autoGain)0 else 1);command(8,0)
            if(!config.autoGain)command(4,GAINS[((config.manualGainLevel-1)*(GAINS.size-1)/9.0).roundToInt()])
            // No transmit or bias-tee enable commands are sent.
            coroutineScope {
                val latest=AtomicReference<FloatArray?>(null)
                val reader=launch(Dispatchers.IO) {
                    val assembler=IqAssembler(plan.fftSize*SpectrumDsp.blockFrames(plan.fftSize,config.vbwKhz));val raw=ByteArray(32768)
                    try {while(isActive){val n=input.read(raw);check(n>0){"USB/드라이버 연결이 끊겼습니다"};assembler.append(raw,n);assembler.take()?.let{latest.set(it)}}}
                    catch(e:Exception){if(isActive && !s.isClosed)throw e}
                }
                try {
                    var tuned:Double?=null
                    while(currentCoroutineContext().isActive) {
                        val started=System.currentTimeMillis();val levels=FloatArray(plan.pointCount);val times=LongArray(plan.pointCount)
                        var clipped=0L;var samples=0L
                        for(seg in plan.segments) {
                            if(tuned!=seg.centerMhz) {
                                command(1,(seg.centerMhz*1e6).roundToInt());tuned=seg.centerMhz
                                // Reader continuously drains TCP; discard after settling. rtl_tcp has no tune ACK.
                                delay(650);latest.set(null)
                            }
                            val iq=withTimeout(4000){var v:FloatArray?=null;while(v==null){v=latest.getAndSet(null);if(v==null)delay(5)};v}
                            clipped+=iq.count { it<=-0.992f || it>=0.992f };samples+=iq.size
                            val fft=SpectrumDsp.spectrum(iq,plan.fftSize,config.removeDc,config.vbwKhz)
                            val now=System.currentTimeMillis()
                            for(k in 0 until seg.count){levels[seg.startIndex+k]=fft[seg.fftStart+k];times[seg.startIndex+k]=now}
                        }
                        emit(SpectrumFrame(plan.startMhz,plan.stopMhz,levels,System.currentTimeMillis(),started,
                            "RTL-SDR / SDR Driver","dBFS",config.manualGainLevel,SweepMath.RATE,plan.fftSize,
                            plan.enbwHz,plan.segments.size,clipped.toDouble()/samples,config.removeDc,times,
                            rbwHz=plan.rbwHz,vbwKhz=config.vbwKhz,autoGain=config.autoGain))
                        delay(100)
                    }
                } finally {reader.cancel();shutdown(s)}
            }
        } finally {shutdown(s);if(socket===s)socket=null}
    }.flowOn(Dispatchers.IO)
    companion object {val GAINS=intArrayOf(0,9,14,27,37,77,87,125,144,157,166,197,207,229,254,280,297,328,338,364,372,386,402,421,434,439,445,480,496)}
}
