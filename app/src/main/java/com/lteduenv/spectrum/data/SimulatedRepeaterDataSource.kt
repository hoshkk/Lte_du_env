package com.lteduenv.spectrum.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * Generates physically-plausible demo readings so the UI works with no hardware attached.
 * Not measured data - switch to USB SDR mode (see [com.lteduenv.spectrum.data.sdr.UsbSdrDataSource])
 * for a real spectrum.
 */
class SimulatedRepeaterDataSource(
    private val random: Random = Random.Default,
) : RepeaterDataSource {

    override val name: String = "Simulated (repeater TX/RX demo)"

    override fun spectrum(config: SweepConfig): Flow<SpectrumFrame> = flow {
        val pointCount = 501
        val noiseFloor = FloatArray(pointCount)
        while (true) {
            val frame = buildSpectrumFrame(config, pointCount, noiseFloor)
            emit(frame)
            delay(SWEEP_INTERVAL_MS)
        }
    }

    private fun buildSpectrumFrame(
        config: SweepConfig,
        pointCount: Int,
        noiseFloor: FloatArray,
    ): SpectrumFrame {
        val baseFloorDbm = if (config.direction == LinkDirection.TX) -78f else -92f
        val carrierLevelDbm = if (config.direction == LinkDirection.TX) -15f else -55f
        val carrierFraction = if (config.direction == LinkDirection.TX) 0.85 else 0.0

        for (i in 0 until pointCount) {
            // Slow random walk so the trace looks "live" without jumping wildly between sweeps.
            val drift = (random.nextFloat() - 0.5f) * 1.5f
            val prev = if (noiseFloor[i] == 0f) baseFloorDbm else noiseFloor[i]
            noiseFloor[i] = (prev + drift).coerceIn(baseFloorDbm - 8f, baseFloorDbm + 8f)
        }

        // Occasional narrowband spike, e.g. an interferer or PIM product worth flagging.
        if (config.direction == LinkDirection.RX && random.nextFloat() < 0.05f) {
            val spikeIdx = random.nextInt(pointCount)
            noiseFloor[spikeIdx] = (noiseFloor[spikeIdx] + random.nextFloat() * 20f + 10f)
                .coerceAtMost(config.refLevelDbm.toFloat() - 2f)
        }

        val levels = FloatArray(pointCount)
        val carrierHalfWidth = carrierFraction / 2.0
        for (i in 0 until pointCount) {
            val ratio = i.toDouble() / (pointCount - 1)
            val centered = ratio - 0.5
            var level = noiseFloor[i]
            if (carrierFraction > 0.0 && abs(centered) <= carrierHalfWidth) {
                // Flat-top with soft roll-off at the shoulders, like an OFDM carrier.
                val edgeDistance = carrierHalfWidth - abs(centered)
                val rolloff = (edgeDistance / 0.03).coerceIn(0.0, 1.0)
                level = max(level, (carrierLevelDbm - (1.0 - rolloff) * 25.0).toFloat())
            }
            levels[i] = level
        }
        return SpectrumFrame(config.startMhz, config.stopMhz, levels, System.currentTimeMillis())
    }

    override fun vswr(config: SweepConfig): Flow<VswrFrame> = flow {
        while (true) {
            val samples = buildVswrSamples(config)
            emit(VswrFrame(samples, System.currentTimeMillis()))
            delay(SWEEP_INTERVAL_MS)
        }
    }

    private fun buildVswrSamples(config: SweepConfig): List<VswrSample> {
        val pointCount = 201
        val bestReturnLossDb = -24.0 - random.nextDouble() * 6.0 // resonance quality varies a bit
        return (0 until pointCount).map { i ->
            val ratio = i.toDouble() / (pointCount - 1)
            val freq = config.startMhz + ratio * (config.stopMhz - config.startMhz)
            val detuning = (ratio - 0.5) * 2.0 // -1..1 across the band
            val ripple = 1.2 * kotlin.math.sin(ratio * 24.0) * exp(-abs(detuning) * 1.5)
            val returnLossDb = (bestReturnLossDb * exp(-detuning * detuning * 2.2) + (1 - exp(-detuning * detuning * 2.2)) * -9.0 + ripple)
                .coerceIn(-40.0, -6.0)
            val gammaMag = Math.pow(10.0, returnLossDb / 20.0)
            val vswr = (1 + gammaMag) / (1 - gammaMag)
            VswrSample(freq, vswr.toFloat(), returnLossDb.toFloat())
        }
    }

    override fun dtf(config: SweepConfig, maxDistanceM: Double): Flow<DtfFrame> = flow {
        val faultDistanceM = 5.0 + random.nextDouble() * (maxDistanceM * 0.6)
        val antennaDistanceM = maxDistanceM * (0.75 + random.nextDouble() * 0.2)
        val lossPer100m = cableLossPer100mDb(config.centerMhz)
        while (true) {
            emit(buildDtfFrame(config, maxDistanceM, faultDistanceM, antennaDistanceM, lossPer100m))
            delay(SWEEP_INTERVAL_MS)
        }
    }

    private fun buildDtfFrame(
        config: SweepConfig,
        maxDistanceM: Double,
        faultDistanceM: Double,
        antennaDistanceM: Double,
        lossPer100m: Double,
    ): DtfFrame {
        val pointCount = 401
        val samples = (0 until pointCount).map { i ->
            val distance = i.toDouble() / (pointCount - 1) * maxDistanceM
            var returnLossDb = -38.0 + random.nextDouble() * 2.0
            returnLossDb += bump(distance, 0.0, 0.8, 16.0) // near-end connector
            returnLossDb += bump(distance, faultDistanceM, 1.2, 20.0) // simulated fault / bad splice
            returnLossDb += bump(distance, antennaDistanceM, 1.5, 12.0) // antenna port
            if (distance > antennaDistanceM + 3.0) {
                returnLossDb += 18.0 // beyond the antenna: just noise, nothing to reflect off
            }
            DtfSample(distance, returnLossDb.coerceIn(-45.0, -2.0).toFloat())
        }
        return DtfFrame(samples, lossPer100m, velocityFactor = 0.88, System.currentTimeMillis())
    }

    private fun bump(x: Double, center: Double, widthM: Double, heightDb: Double): Double {
        val d = (x - center) / widthM
        return heightDb * exp(-d * d)
    }

    override suspend fun measureCableLoss(config: SweepConfig, lengthM: Double): CableLossResult {
        delay(400)
        val lossPer100m = cableLossPer100mDb(config.centerMhz)
        val measured = lengthM / 100.0 * lossPer100m + (random.nextDouble() - 0.5) * 0.3
        return CableLossResult(lengthM, measured, lossPer100m)
    }

    private fun cableLossPer100mDb(centerMhz: Double): Double {
        // Rough heuristic loss curve for a typical 1/2" feeder, rises with frequency.
        return 3.2 + ln(1.0 + centerMhz / 400.0) * 3.5
    }

    companion object {
        private const val SWEEP_INTERVAL_MS = 450L
    }
}
