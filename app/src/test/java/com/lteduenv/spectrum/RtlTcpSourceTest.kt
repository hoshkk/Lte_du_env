package com.lteduenv.spectrum

import com.lteduenv.spectrum.data.SweepConfig
import com.lteduenv.spectrum.data.SpectrumFrame
import com.lteduenv.spectrum.data.sdr.RtlTcpSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.io.DataOutputStream
import java.io.DataInputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Protocol fixture only: does not certify USB hardware or RF tuning. */
class RtlTcpSourceTest {
    private fun receiveMany(c:SweepConfig,n:Int):Pair<List<SpectrumFrame>,List<Pair<Int,Int>>> = runBlocking {
        val commands=CopyOnWriteArrayList<Pair<Int,Int>>()
        val server=ServerSocket().apply{reuseAddress=true;bind(InetSocketAddress("127.0.0.1",1234))}
        val writer=thread(isDaemon=true) {
            runCatching{server.accept().use{s->
                val controls=thread(isDaemon=true){runCatching{
                    val input=DataInputStream(s.getInputStream())
                    while(true){commands.add(input.readUnsignedByte() to input.readInt())}
                }}
                try {
                    val out=DataOutputStream(s.getOutputStream())
                    out.writeBytes("RTL0");out.writeInt(6);out.writeInt(29);out.flush()
                    val data=ByteArray(48000){if(it%2==0)255.toByte()else 128.toByte()}
                    while(true){out.write(data,0,997);out.write(data,997,data.size-997);out.flush();Thread.sleep(10)}
                }finally{s.close();controls.join(2000)}
            }}
        }
        val source=RtlTcpSource()
        try {
            val f=withTimeout(10000){source.frames(c).take(n).toList()}
            writer.join(2000)
            f to commands.toList()
        }finally{source.close();server.close();writer.join(2000)}
    }
    private fun receive(c:SweepConfig):Pair<SpectrumFrame,List<Pair<Int,Int>>> {
        val(frames,commands)=receiveMany(c,1)
        return frames.first() to commands
    }
    @Test fun narrowStreamDoesNotRetuneBetweenFrames() {
        val(frames,commands)=receiveMany(SweepConfig(),3)
        assertEquals(3,frames.size)
        assertEquals(1,commands.count{it.first==1})
        assertTrue(frames.all{it.completedSegments==1 && it.levelsDb.all{v->v.isFinite()}})
    }
    @Test fun wideSweepPublishesImmutablePartialFrames() {
        val(frames,_)=receiveMany(SweepConfig(spanMhz=3.0),2)
        assertEquals(1,frames[0].completedSegments)
        assertEquals(2,frames[1].completedSegments)
        assertTrue(frames[0].levelsDb.any{it.isNaN()})
        assertTrue(frames[1].levelsDb.all{it.isFinite()})
        assertNull(com.lteduenv.spectrum.data.sdr.Measurements.channelPower(frames[0],909.3,1.0))
        val held=com.lteduenv.spectrum.data.sdr.SweepMath.hold(frames[1],frames[0])
        assertTrue(held.levelsDb.all{it.isFinite()})
    }
    @Test fun fullBandSweepsFinishWithoutShrinkingSpan() {
        for(band in com.lteduenv.spectrum.data.BandPresets.all) {
            val config=SweepConfig(centerMhz=band.uplinkMhz,spanMhz=band.spanMhz,rbwKhz=10.0)
            val segments=com.lteduenv.spectrum.data.sdr.SweepMath.plan(config).segments.size
            val(frames,_)=receiveMany(config,segments)
            val full=frames.last()
            assertEquals(segments,full.completedSegments)
            assertTrue(full.levelsDb.all{it.isFinite()})
            assertTrue(full.stopMhz-full.startMhz>band.spanMhz-0.02)
            assertTrue("Mock sweep exceeded 6 seconds",full.lastSweepMs in 1..5999)
            println("MOCK_SWEEP ${band.id}: ${full.lastSweepMs} ms, ${full.segmentCount} segments")
        }
    }
    @Test fun rtlHeaderAndFragmentedIqReachSpectrum() {
        val(f,commands)=receive(SweepConfig())
        assertEquals("dBFS",f.unit);assertEquals(1,f.segmentCount);assertTrue(f.levelsDb.max()>-1f)
        assertTrue(f.clippedFraction>0.4);assertTrue(f.timestampMs>=f.startedMs)
        assertTrue(commands.contains(3 to 1));assertTrue(commands.contains(8 to 0))
        assertTrue(commands.any{it.first==4});assertTrue(commands.any{it.first==1})
    }
    @Test fun agcRbwAndVbwSettingsReachTheAcquisitionPath() {
        val(f,commands)=receive(SweepConfig(rbwKhz=100.0,vbwKhz=1.0,autoGain=true))
        assertEquals(32,f.fftSize);assertTrue(f.rbwHz>100000);assertEquals(1.0,f.vbwKhz,0.0)
        assertTrue(f.autoGain);assertTrue(f.levelsDb.max()>-1f)
        assertTrue(commands.contains(3 to 0));assertTrue(commands.contains(8 to 0))
        assertFalse(commands.any{it.first==4})
    }
}
