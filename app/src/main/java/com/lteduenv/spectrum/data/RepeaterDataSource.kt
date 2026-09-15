package com.lteduenv.spectrum.data

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over where readings come from. The app ships with [SimulatedRepeaterDataSource]
 * so the UI is fully usable with no hardware attached, and [HttpRepeaterDataSource] as a starting
 * point for wiring up a real repeater / base station's TX-RX monitoring API — point it at that
 * equipment's JSON endpoint (see HttpRepeaterDataSource for the expected schema) once it's known.
 */
interface RepeaterDataSource {
    val name: String

    /** Continuously emits spectrum sweeps for the given config until the flow is cancelled. */
    fun spectrum(config: SweepConfig): Flow<SpectrumFrame>

    /** Continuously emits VSWR / return-loss sweeps across the given band. */
    fun vswr(config: SweepConfig): Flow<VswrFrame>

    /** Continuously emits a Distance-To-Fault trace out to [maxDistanceM]. */
    fun dtf(config: SweepConfig, maxDistanceM: Double = 100.0): Flow<DtfFrame>

    /** One-shot cable loss measurement between the analyzer and a far-end reference. */
    suspend fun measureCableLoss(config: SweepConfig, lengthM: Double): CableLossResult
}
