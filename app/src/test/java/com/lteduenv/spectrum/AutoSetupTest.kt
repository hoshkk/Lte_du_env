package com.lteduenv.spectrum
import com.lteduenv.spectrum.data.*
import org.junit.Assert.*
import org.junit.Test
class AutoSetupTest {
 @Test fun weakSignalNeverRaisesGain() {
  for(mode in FieldMode.values())assertEquals(4,AutoSetup.decide(mode,4,0.0,-80.0,-100.0,0.0).gain)
 }
 @Test fun clippingReducesGainButNeverBelowOne() {
  assertEquals(3,AutoSetup.decide(FieldMode.ANTENNA,4,0.01,-20.0,-80.0,0.0).gain)
  assertEquals(1,AutoSetup.decide(FieldMode.EQUIPMENT,1,0.01,-20.0,-80.0,0.0).gain)
 }
 @Test fun displayUsesOffsetWithoutChangingIt() {
  val a=AutoSetup.decide(FieldMode.ANTENNA,4,0.0,-50.0,-90.0,20.0)
  assertEquals(-5.0,a.ref,0.0)
  assertEquals(10.0,a.scale,0.0)
  assertEquals(17.0,FieldProfiles.initial(FieldMode.EQUIPMENT,SweepConfig(refLevelOffsetDb=17.0)).config.refLevelOffsetDb,0.0)
 }
 @Test fun wideDynamicRangeGetsVisibleScale() {
  assertEquals(15.0,AutoSetup.decide(FieldMode.EQUIPMENT,1,0.0,-10.0,-100.0,0.0).scale,0.0)
 }
}
