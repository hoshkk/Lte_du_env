package com.lteduenv.spectrum

import com.lteduenv.spectrum.data.*
import com.lteduenv.spectrum.data.sdr.SweepMath
import org.junit.Assert.*
import org.junit.Test

class FieldProfilesTest {
    @Test fun initialModesDoNotCarryOverOldGainOrOffset() {
        for(band in BandPresets.all)for(mode in FieldMode.values()) {
            val old=SweepConfig(centerMhz=band.uplinkMhz,spanMhz=band.spanMhz,
                integrationBwMhz=band.integrationBwMhz,refLevelOffsetDb=-43.0,manualGainLevel=10,autoGain=true)
            val p=FieldProfiles.initial(mode,old)
            assertEquals(old.centerMhz,p.config.centerMhz,0.0)
            assertEquals(old.spanMhz,p.config.spanMhz,0.0)
            assertEquals(0.0,p.config.refLevelOffsetDb,0.0)
            assertFalse(p.config.autoGain);assertEquals(1,p.config.manualGainLevel)
            SweepMath.plan(p.config)
            assertEquals(mode==FieldMode.EQUIPMENT,p.config.channelPowerEnabled)
            assertEquals(mode==FieldMode.ANTENNA,p.maxHold)
        }
    }
    @Test fun savedProfilesRoundTripEveryManualSetting() {
        val p=FieldProfile(SweepConfig(centerMhz=1745.0,spanMhz=25.0,refLevelDb=-35.0,
            manualGainLevel=7,removeDc=true,refLevelOffsetDb=43.0,rbwKhz=30.0,vbwKhz=0.5,
            integrationBwMhz=20.0,channelPowerEnabled=true,autoGain=false,dbPerDiv=5.0),true)
        assertEquals(p,FieldProfiles.decode(FieldProfiles.encode(p)))
    }
    @Test fun invalidPersistedFrequenciesAreRejected() {
        val invalid=FieldProfile(SweepConfig(centerMhz=1750.0,spanMhz=35.0),false)
        assertThrows(IllegalArgumentException::class.java){FieldProfiles.decode(FieldProfiles.encode(invalid))}
        assertThrows(IllegalArgumentException::class.java){FieldProfiles.decode("broken")}
    }
}
