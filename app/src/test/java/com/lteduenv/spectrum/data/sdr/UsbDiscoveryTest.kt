package com.lteduenv.spectrum.data.sdr
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UsbDiscoveryTest {
    @Test fun immediate()=runBlocking {
        assertEquals(1,discoverUsb<Int>({listOf(1)},{it==1},{it.toString()},{error("unnecessary delay")},{}))
    }
    @Test fun delayedEnumeration()=runBlocking {
        var calls=0;var waits=0
        assertEquals(1,discoverUsb<Int>({if(++calls<4)emptyList()else listOf(1)},{it==1},{it.toString()},{waits++},{}))
        assertEquals(3,waits)
    }
    @Test fun boundedMissingAndUnsupported()=runBlocking {
        for(items in listOf(emptyList(),listOf(2))) {
            var calls=0;var waits=0
            try {
                discoverUsb<Int>({calls++;items},{it==1},{"ID-$it"},{waits++},{})
                fail("must fail")
            }catch(e:IllegalStateException) {
                assertTrue(e.message!!.contains(if(items.isEmpty())"USB 인식 없음" else "ID-2"))
            }
            assertEquals(13,calls);assertEquals(12,waits)
        }
    }
    @Test fun multipleStopsWithoutWaiting()=runBlocking {
        try {discoverUsb<Int>({listOf(1,1)},{it==1},{it.toString()},{error("delay")},{});fail()}
        catch(e:IllegalStateException){assertTrue(e.message!!.contains("한 대"))}
    }
    @Test fun stopCancelsBeforeReadingDevices()=runBlocking {
        try {discoverUsb<Int>({error("read")},{true},{it.toString()},{},{throw CancellationException()});fail()}
        catch(e:CancellationException){ }
    }
}
