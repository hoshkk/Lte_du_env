package com.lteduenv.spectrum.data
import com.lteduenv.spectrum.data.sdr.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlin.math.*
import kotlin.random.Random
class SimulatedRepeaterDataSource:RepeaterDataSource {
    override val name="DEMO"
    override fun spectrum(config:SweepConfig)=flow {
        val plan=SweepMath.plan(config)
        while(true) {
            val levels=FloatArray(plan.pointCount){i->
                val hz=(plan.startMhz+i*plan.binHz/1e6-config.centerMhz)*1e6
                val psd=10.0.pow((-115+Random.nextDouble(-2.0,2.0))/10.0)
                val signal=1e-7*exp(-((hz+config.spanMhz*1e6*0.1)/(config.spanMhz*1e6*0.015)).pow(2))
                (10*log10((psd+signal)*plan.enbwHz)).toFloat()
            }
            emit(SpectrumFrame(plan.startMhz,plan.stopMhz,levels,System.currentTimeMillis(),
                gainStep=config.manualGainLevel,fftSize=plan.fftSize,enbwHz=plan.enbwHz,segmentCount=plan.segments.size,
                dcRemoved=config.removeDc,rbwHz=plan.rbwHz,autoGain=config.autoGain))
            delay(150)
        }
    }
}
