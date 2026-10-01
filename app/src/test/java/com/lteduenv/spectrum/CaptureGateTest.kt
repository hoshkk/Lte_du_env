package com.lteduenv.spectrum
import com.lteduenv.spectrum.data.sdr.CaptureGate
import com.lteduenv.spectrum.data.*
import org.junit.Assert.*
import org.junit.Test

class CaptureGateTest {
    @Test fun settlingBytesAreDiscardedAndOddReadsKeepIqParity() {
        val g=CaptureGate(2)
        g.arm(0,10,3)
        g.append(byteArrayOf(0,1,2,3,4),5,5_000_000)
        assertNull(g.take())
        g.append(byteArrayOf(5,6,7,8,9,10,11),7,11_000_000)
        val x=g.take()!!
        assertArrayEquals(floatArrayOf(8f,9f,10f,11f).map{(it-127.5f)/128f}.toFloatArray(),x,0f)
        assertNull(g.take())
    }
    @Test fun retuneDropsPartialAssemblyAndAlignsNextIByte() {
        val g=CaptureGate(2)
        g.arm(0,0,0)
        g.append(byteArrayOf(0,1,2),3,1)
        g.arm(2,0,0)
        g.append(byteArrayOf(3,4,5,6,7),5,3)
        assertArrayEquals(floatArrayOf(4f,5f,6f,7f).map{(it-127.5f)/128f}.toFloatArray(),g.take()!!,0f)
    }
    @Test fun discardBudgetSpansMultiplePackets() {
        val g=CaptureGate(2);g.arm(0,0,8)
        repeat(3){g.append(ByteArray(3){128.toByte()},3,1);assertNull(g.take())}
        g.append(ByteArray(3){128.toByte()},3,1)
        assertEquals(4,g.take()!!.size)
    }
    @Test fun olderSavedProfilesMigrateAndSettleRoundTrips() {
        val p=FieldProfile(SweepConfig(tuneSettleMs=160),true)
        assertEquals(p,FieldProfiles.decode(FieldProfiles.encode(p)))
        val legacy=FieldProfiles.encode(p).split('|').take(14).toMutableList()
        legacy[0]="1"
        assertEquals(80,FieldProfiles.decode(legacy.joinToString("|")).config.tuneSettleMs)
    }
}
