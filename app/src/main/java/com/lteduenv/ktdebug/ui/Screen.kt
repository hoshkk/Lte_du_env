package com.lteduenv.ktdebug.ui

import com.lteduenv.ktdebug.model.NetworkType

data class BandKey(val networkType: NetworkType, val band: Int, val bandwidthMHz: Int)

sealed interface Screen {
    data object BandList : Screen
    data class Debug(val bandKey: BandKey) : Screen
    data class Compare(val bandKeys: List<BandKey>) : Screen
    data object Nearby : Screen
}
