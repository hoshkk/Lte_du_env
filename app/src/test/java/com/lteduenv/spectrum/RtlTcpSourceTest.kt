package com.lteduenv.spectrum

import com.lteduenv.spectrum.data.SweepConfig
import com.lteduenv.spectrum.data.SpectrumFrame
import com.lteduenv.spectrum.data.sdr.RtlTcpSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
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
    private fun receive(c:SweepConfig):Pair<SpectrumFrame,List<Pair<Int,Int>>> = runBlocking {
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
                    val data=ByteArray(8192){if(it%2==0)255.toByte()else 128.toByte()}
                    while(true){out.write(data,0,997);out.write(data,997,data.size-997);out.flush()}
                }finally{s.close();controls.join(2000)}
            }}
        }
        val source=RtlTcpSource()
        try {
            val f=withTimeout(10000){source.frames(c).first()}
            writer.join(2000)
            f to commands.toList()
        }finally{source.close();server.close();writer.join(2000)}
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
