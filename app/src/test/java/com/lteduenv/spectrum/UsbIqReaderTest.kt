package com.lteduenv.spectrum
import com.lteduenv.spectrum.data.*
import com.lteduenv.spectrum.data.sdr.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException

class UsbIqReaderTest {
 @Test fun fragmentedReadsPreserveIqAndDiscardBoundary() {
  var count=0
  val reader=UsbIqReader({raw,requested->
   assertEquals(0,requested%512);assertTrue(requested<=16384)
   val n=minOf(126,requested)
   repeat(n){raw[it]=(count++%256).toByte()};n
  },{})
  reader.discard();val start=count;val iq=FloatArray(1024);reader.capture(iq)
  iq.forEachIndexed{i,v->assertEquals(((start+i)%256-127.5f)/128f,v,0f)}
 }
 @Test fun malformedOrOddReadsFailInsteadOfDrawingWrongIq() {
  for(n in listOf(-1,0,3,513))assertThrows(IllegalStateException::class.java){
   UsbIqReader({_,_->n},{}).capture(FloatArray(32))
  }
 }
 @Test fun cancellationStopsBeforeUsbRead() {
  assertThrows(CancellationException::class.java){UsbIqReader({_,_->error("must not read")},{throw CancellationException()}).capture(FloatArray(32))}
 }
 @Test fun profilesPreserveSeparateBackendGuardsAndMigrateOldFormat() {
  val p=FieldProfile(SweepConfig(tuneSettleMs=160,nativeSettleMs=20),false)
  assertEquals(p,FieldProfiles.decode(FieldProfiles.encode(p)))
  val old=FieldProfiles.encode(p).split('|').take(15).toMutableList();old[0]="2"
  assertEquals(10,FieldProfiles.decode(old.joinToString("|")).config.nativeSettleMs)
 }
}
