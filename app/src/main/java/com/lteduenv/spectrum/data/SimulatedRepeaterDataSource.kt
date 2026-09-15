package com.lteduenv.spectrum.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * Generates a physically-plausible demo noise floor (with an occasional interferer spike) so the
 * UI works with no hardware attached. Not measured data - switch to USB SDR mode (see
 * [com.lteduenv.spectrum.data.sdr.UsbSdrDataSource]) for a real spectrum.
 */
class SimulatedRepeaterDataSource(
    private val random: Random = Random.Default,
) : RepeaterDataSource {

    override val name: String = "Simulated (UL demo)"

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
        val baseFloorDbm = -92f

        for (i in 0 until pointCount) {
            // Slow random walk so the trace looks "live" without jumping wildly between sweeps.
            val drift = (random.nextFloat() - 0.5f) * 1.5f
            val prev = if (noiseFloor[i] == 0f) baseFloorDbm else noiseFloor[i]
            noiseFloor[i] = (prev + drift).coerceIn(baseFloorDbm - 8f, baseFloorDbm + 8f)
        }

        // Occasional narrowband spike, e.g. an interferer or PIM product worth flagging.
        if (random.nextFloat() < 0.05f) {
            val spikeIdx = random.nextInt(pointCount)
            noiseFloor[spikeIdx] = (noiseFloor[spikeIdx] + random.nextFloat() * 20f + 10f)
                .coerceAtMost(config.refLevelDbm.toFloat() - 2f)
        }

        return SpectrumFrame(config.startMhz, config.stopMhz, noiseFloor.copyOf(), System.currentTimeMillis())
    }

    companion object {
        private const val SWEEP_INTERVAL_MS = 450L
    }
}
