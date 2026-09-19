package com.lteduenv.spectrum.data
import kotlinx.coroutines.flow.Flow
interface RepeaterDataSource {val name:String;fun spectrum(config:SweepConfig):Flow<SpectrumFrame>}
