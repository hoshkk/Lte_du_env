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
            refLevelDb=-20.0,refLevelOffsetDb=0.0,dbPerDiv=10.0,
            manualGainLevel=if(equipment)1 else 4,autoGain=false,removeDc=false,
            tuneSettleMs=80,nativeSettleMs=10,
            rbwKhz=if(equipment)100.0 else 10.0,vbwKhz=0.0,
            integrationBwMhz=minOf(current.integrationBwMhz,current.spanMhz),
            channelPowerEnabled=equipment),maxHold=!equipment)
    }
    fun encode(p:FieldProfile):String=with(p.config){
        listOf("3",centerMhz,spanMhz,refLevelDb,manualGainLevel,removeDc,refLevelOffsetDb,
            rbwKhz,vbwKhz,integrationBwMhz,channelPowerEnabled,autoGain,dbPerDiv,p.maxHold,tuneSettleMs,nativeSettleMs).joinToString("|")
    }
    fun decode(text:String):FieldProfile {
        val v=text.split('|');require((v.size==14 && v[0]=="1") || (v.size==15 && v[0]=="2") || (v.size==16 && v[0]=="3")){"저장 설정 형식 오류"}
        val c=SweepConfig(centerMhz=v[1].toDouble(),spanMhz=v[2].toDouble(),refLevelDb=v[3].toDouble(),
            manualGainLevel=v[4].toInt(),removeDc=v[5].toBooleanStrict(),refLevelOffsetDb=v[6].toDouble(),
            rbwKhz=v[7].toDouble(),vbwKhz=v[8].toDouble(),integrationBwMhz=v[9].toDouble(),
            channelPowerEnabled=v[10].toBooleanStrict(),autoGain=v[11].toBooleanStrict(),dbPerDiv=v[12].toDouble(),tuneSettleMs=if(v.size>=15)v[14].toInt()else 80,nativeSettleMs=if(v.size==16)v[15].toInt()else 10)
        SweepMath.plan(c)
        return FieldProfile(c,v[13].toBooleanStrict())
    }
}
