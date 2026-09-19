package com.lteduenv.spectrum.data

import com.lteduenv.spectrum.data.sdr.SweepMath

/** Starting profiles, not calibrated instrument settings or automatic RF optimization. */
enum class FieldMode(val title:String) {
    EQUIPMENT("장비 리버스"), ANTENNA("불요파")
}
data class FieldProfile(val config:SweepConfig,val maxHold:Boolean)
object FieldProfiles {
    fun initial(mode:FieldMode,current:SweepConfig):FieldProfile {
        val equipment=mode==FieldMode.EQUIPMENT
        return FieldProfile(current.copy(
            refLevelDb=0.0,refLevelOffsetDb=0.0,dbPerDiv=10.0,
            manualGainLevel=1,autoGain=false,removeDc=false,
            rbwKhz=if(equipment)100.0 else 10.0,vbwKhz=if(equipment)1.0 else 0.0,
            integrationBwMhz=minOf(current.integrationBwMhz,current.spanMhz),
            channelPowerEnabled=equipment),maxHold=!equipment)
    }
    fun encode(p:FieldProfile):String=with(p.config){
        listOf("1",centerMhz,spanMhz,refLevelDb,manualGainLevel,removeDc,refLevelOffsetDb,
            rbwKhz,vbwKhz,integrationBwMhz,channelPowerEnabled,autoGain,dbPerDiv,p.maxHold).joinToString("|")
    }
    fun decode(text:String):FieldProfile {
        val v=text.split('|');require(v.size==14 && v[0]=="1"){"저장 설정 형식 오류"}
        val c=SweepConfig(centerMhz=v[1].toDouble(),spanMhz=v[2].toDouble(),refLevelDb=v[3].toDouble(),
            manualGainLevel=v[4].toInt(),removeDc=v[5].toBooleanStrict(),refLevelOffsetDb=v[6].toDouble(),
            rbwKhz=v[7].toDouble(),vbwKhz=v[8].toDouble(),integrationBwMhz=v[9].toDouble(),
            channelPowerEnabled=v[10].toBooleanStrict(),autoGain=v[11].toBooleanStrict(),dbPerDiv=v[12].toDouble())
        SweepMath.plan(c)
        return FieldProfile(c,v[13].toBooleanStrict())
    }
}
