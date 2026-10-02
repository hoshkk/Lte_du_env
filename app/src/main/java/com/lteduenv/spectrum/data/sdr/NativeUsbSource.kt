package com.lteduenv.spectrum.data.sdr

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import androidx.core.content.ContextCompat
import com.lteduenv.spectrum.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

interface SpectrumSource {
    fun close()
    fun frames(config:SweepConfig):Flow<SpectrumFrame>
}
private class UsbPermissionDenied : IllegalStateException("USB 사용 권한이 필요합니다 · 실측 시작을 눌러 다시 허용하세요")
class NativeRtl {
    companion object { init { System.loadLibrary("spectrumusb") } }
    external fun open(fd:Int,path:String,agc:Boolean,gain:Int,fast:Boolean):Long
    external fun tune(handle:Long,hz:Int,settleMs:Int):LongArray
    external fun reset(handle:Long)
    external fun read(handle:Long,data:ByteArray,length:Int):Int
    external fun close(handle:Long)
}
class NativeUsbSource(context:Context,private val onStatus:(String)->Unit = {}):SpectrumSource {
    private val context=context.applicationContext
    private val stopped=AtomicBoolean(false)
    override fun close(){stopped.set(true)}
    companion object {
        private val deviceLock=Mutex()
        private const val MAX_RECONNECT_ATTEMPTS=5
        private const val RECONNECT_BACKOFF_MS=1000L
    }
    private suspend fun permission(manager:UsbManager,device:UsbDevice) {
        if(manager.hasPermission(device))return
        withTimeout(60_000) {
            suspendCancellableCoroutine<Unit> { c ->
                val action=context.packageName+".USB_PERMISSION."+UUID.randomUUID()
                val cleaned=AtomicBoolean(false)
                lateinit var receiver:BroadcastReceiver
                fun cleanup(){if(cleaned.compareAndSet(false,true))runCatching{context.unregisterReceiver(receiver)}}
                receiver=object:BroadcastReceiver(){override fun onReceive(ctx:Context?,intent:Intent?) {
                    if(intent?.action!=action)return
                    cleanup()
                    if(c.isActive) {
                        if(manager.hasPermission(device))c.resume(Unit)
                        else c.resumeWithException(UsbPermissionDenied())
                    }
                }}
                ContextCompat.registerReceiver(context,receiver,IntentFilter(action),ContextCompat.RECEIVER_NOT_EXPORTED)
                c.invokeOnCancellation{cleanup()}
                try {
                    // Attach/default-app handling may have granted permission
                    // since the initial check, while the receiver was installed.
                    if(manager.hasPermission(device)) {
                        cleanup()
                        if(c.isActive)c.resume(Unit)
                        return@suspendCancellableCoroutine
                    }
                    val flags=PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31)PendingIntent.FLAG_MUTABLE else 0
                    manager.requestPermission(device,PendingIntent.getBroadcast(context,0,Intent(action).setPackage(context.packageName),flags))
                }catch(e:Exception){cleanup();if(c.isActive)c.resumeWithException(e)}
            }
        }
    }
    override fun frames(config:SweepConfig):Flow<SpectrumFrame> = flow {
        deviceLock.withLock {
            val plan=SweepMath.plan(config)
            val manager=context.getSystemService(Context.USB_SERVICE) as UsbManager
            // Survives a tune/PLL failure or a transient USB drop by re-running discovery and
            // reopening the device, instead of ending the whole measurement on the first such
            // error. discoverUsb() already re-polls Android's enumeration for ~3s, which covers
            // a dongle that briefly disappears and comes back on its own; this adds a bounded
            // number of full reconnect cycles on top of that for errors from open()/tune()/read()
            // once a device is found. A user-requested stop (CancellationException, or `stopped`
            // observed) always wins over retrying.
            val levels=FloatArray(plan.pointCount){Float.NaN};val times=LongArray(plan.pointCount)
            var lastSweepMs=0L;var lastTiming:LongArray?=null
            var reconnectAttempt=0
            while(currentCoroutineContext().isActive && !stopped.get()) {
                try {
                    val device=discoverUsb(
                        snapshot={manager.deviceList.values.toList()},
                        supported={it.vendorId==0x0bda && it.productId in listOf(0x2832,0x2838)},
                        describe={"%04X:%04X".format(it.vendorId,it.productId)},
                        pause={delay(250)},
                        checkActive={if(stopped.get())throw CancellationException()}
                    )
                    permission(manager,device)
                    currentCoroutineContext().ensureActive();check(!stopped.get()){"측정 취소"}
                    val native=NativeRtl()
                    val connection=manager.openDevice(device)?:error("USB 열기 실패")
                    var handle=0L
                    try {
                        val gain=RtlTcpSource.GAINS[((config.manualGainLevel-1)*(RtlTcpSource.GAINS.size-1)/9.0).roundToInt()]
                        handle=native.open(connection.fileDescriptor,device.deviceName,config.autoGain,gain,config.fastUsbTune)
                        val byteCount=plan.fftSize*SpectrumDsp.blockFrames(plan.fftSize,config.vbwKhz)*2
                        val iq=FloatArray(byteCount)
                        val reader=UsbIqReader({raw,n->native.read(handle,raw,n)},{if(stopped.get())throw CancellationException()})
                        var tuned:Int?=null
                        while(currentCoroutineContext().isActive && !stopped.get()) {
                            val started=System.currentTimeMillis();val monotonic=System.nanoTime()
                            var clipped=0L;var samples=0L;val timing=LongArray(14)
                            for((index,seg) in plan.segments.withIndex()) {
                                currentCoroutineContext().ensureActive()
                                val hz=(seg.centerMhz*1e6).roundToInt()
                                if(tuned!=hz) {
                                    val t=native.tune(handle,hz,if(tuned==null)maxOf(50,config.nativeSettleMs)else config.nativeSettleMs)
                                    for(i in 0..2)timing[i]+=t[i]
                                    for(i in 3 until t.size)timing[i+3]+=t[i]
                                    val discardStart=System.nanoTime();reader.discard();timing[3]+=System.nanoTime()-discardStart;tuned=hz
                                } else {
                                    // Single-window mode drops samples accumulated while DSP/UI ran.
                                    val resetStart=System.nanoTime();native.reset(handle);timing[2]+=System.nanoTime()-resetStart
                                }
                                val readStart=System.nanoTime();reader.capture(iq);timing[4]+=System.nanoTime()-readStart
                                currentCoroutineContext().ensureActive()
                                val dspStart=System.nanoTime()
                                clipped+=iq.count{it<=-0.992f || it>=0.992f};samples+=iq.size
                                val fft=SpectrumDsp.spectrum(iq,plan.fftSize,config.removeDc,config.vbwKhz)
                                val now=System.currentTimeMillis()
                                for(k in 0 until seg.count){levels[seg.startIndex+k]=fft[seg.fftStart+k];times[seg.startIndex+k]=now}
                                timing[5]+=System.nanoTime()-dspStart
                                if(index==plan.segments.lastIndex){
                                    lastSweepMs=(System.nanoTime()-monotonic)/1_000_000;lastTiming=timing.copyOf()
                                    // A fully completed sweep means the connection is healthy again.
                                    reconnectAttempt=0
                                }
                                emit(SpectrumFrame(plan.startMhz,plan.stopMhz,levels.copyOf(),now,started,
                                    "RTL-SDR / USB 직접","dBFS",config.manualGainLevel,SweepMath.RATE,plan.fftSize,
                                    plan.enbwHz,plan.segments.size,clipped.toDouble()/samples,config.removeDc,times.copyOf(),
                                    rbwHz=plan.rbwHz,vbwKhz=config.vbwKhz,autoGain=config.autoGain,completedSegments=index+1,lastSweepMs=lastSweepMs,timingNs=lastTiming))
                            }
                            if(plan.segments.size==1)delay(16)
                        }
                    } finally {if(handle!=0L)native.close(handle);connection.close()}
                } catch(e:CancellationException) {
                    throw e
                } catch(e:Exception) {
                    // Denial is a user decision, not a transient USB fault.
                    // Do not reopen the system permission dialog in the retry loop.
                    if(e is UsbPermissionDenied)throw e
                    if(stopped.get())throw CancellationException()
                    reconnectAttempt++
                    if(reconnectAttempt>MAX_RECONNECT_ATTEMPTS)throw e
                    onStatus("USB 재연결 중 ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS) · 표시 파형은 이전 측정값 · ${e.message}")
                    delay(RECONNECT_BACKOFF_MS)
                }
            }
        }
    }.flowOn(Dispatchers.IO).conflate()
}
