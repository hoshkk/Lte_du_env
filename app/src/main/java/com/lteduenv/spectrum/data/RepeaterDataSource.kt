package com.lteduenv.spectrum.data

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over where spectrum readings come from. The app ships with
 * [SimulatedRepeaterDataSource] so the UI is fully usable with no hardware attached, and a USB
 * SDR-backed implementation (see [com.lteduenv.spectrum.data.sdr.UsbSdrDataSource]) for real
 * spectrum readings.
 */
interface RepeaterDataSource {
    val name: String

    /** Continuously emits spectrum sweeps for the given config until the flow is cancelled. */
    fun spectrum(config: SweepConfig): Flow<SpectrumFrame>
}
