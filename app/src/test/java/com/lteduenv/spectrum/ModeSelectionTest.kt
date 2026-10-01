package com.lteduenv.spectrum

import com.lteduenv.spectrum.data.*
import com.lteduenv.spectrum.viewmodel.SpectrumViewModel
import org.junit.Assert.*
import org.junit.Test

class ModeSelectionTest {
    @Test fun editingControlsPreservesBothModes() {
        for(mode in FieldMode.values()) {
            val vm=SpectrumViewModel()
            assertTrue(vm.applyProfile(FieldProfiles.initial(mode,vm.state.value.config),mode))
            vm.hold();vm.toggleChannelPower();vm.clearHold();vm.selectMarker(2);vm.clearMarker()
            assertEquals(mode,vm.state.value.selectedMode)
            assertTrue(vm.configure(vm.state.value.config.copy(refLevelDb=-30.0,refLevelOffsetDb=2.0,
                rbwKhz=30.0,vbwKhz=1.0,manualGainLevel=3,spanMhz=10.0)))
            assertEquals(mode,vm.state.value.selectedMode)
            vm.selectBand(BandPresets.all[1]);vm.stop()
            assertEquals(mode,vm.state.value.selectedMode)
            val other=if(mode==FieldMode.EQUIPMENT)FieldMode.ANTENNA else FieldMode.EQUIPMENT
            vm.applyProfile(FieldProfiles.initial(other,vm.state.value.config),other)
            assertEquals(other,vm.state.value.selectedMode)
        }
    }
}
