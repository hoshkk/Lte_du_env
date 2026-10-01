package com.lteduenv.spectrum.data.sdr

/** Use the driver's documented launch form; RF settings are sent over TCP after opening. */
object DriverLaunch {
    fun uri(minimal:Boolean=false):String =
        "iqsrc://-a 127.0.0.1 -p 1234" + if(minimal) "" else " -s 2400000"
    fun isArgumentError(code:Int,detail:String):Boolean =
        code==1 || detail.contains("Wrong arguments",ignoreCase=true)
}
