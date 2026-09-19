package com.lteduenv.ktdebug.data

import android.content.Context
import android.location.Location
import com.lteduenv.ktdebug.model.EquipmentMatch
import com.lteduenv.ktdebug.model.EquipmentRecord
import com.lteduenv.ktdebug.model.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Looks up which physical KT site/equipment corresponds to a PCI, using the same
 * lte.json / 5g.json / repeater.json inventory bundled with KT's internal
 * equipment-lookup app (assets copied verbatim, see app/src/main/assets).
 *
 * A PCI is reused across many distant sites, so a lookup can return several
 * candidates; pass a device location to sort them by distance.
 */
class EquipmentRepository(context: Context) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    private var lteIndex: Map<Int, List<EquipmentRecord>>? = null
    private var nrIndex: Map<Int, List<EquipmentRecord>>? = null
    private var repeaterIndex: Map<Int, List<EquipmentRecord>>? = null

    suspend fun findByPci(
        networkType: NetworkType,
        pci: Int,
        includeRepeaters: Boolean = true,
        deviceLocation: Location? = null
    ): List<EquipmentMatch> = withContext(Dispatchers.Default) {
        val primaryIndex = when (networkType) {
            NetworkType.LTE -> lteIndex()
            NetworkType.NR -> nrIndex()
        }
        val matches = mutableListOf<EquipmentMatch>()
        primaryIndex[pci]?.forEach { matches += EquipmentMatch(it, pci) }
        if (includeRepeaters) {
            repeaterIndex()[pci]?.forEach { matches += EquipmentMatch(it, pci) }
        }

        val withDistance = if (deviceLocation != null) {
            matches.map { match ->
                val record = match.record
                if (record.latitude != null && record.longitude != null) {
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        deviceLocation.latitude, deviceLocation.longitude,
                        record.latitude, record.longitude,
                        results
                    )
                    match.copy(distanceMeters = results[0].toDouble())
                } else match
            }
        } else matches

        withDistance.sortedWith(
            compareBy(nullsLast<Double>()) { it.distanceMeters }
        )
    }

    /** Picks a random real PCI from the bundled dataset for the given network type, used to seed mock signal data. */
    suspend fun randomKnownPci(networkType: NetworkType): Int? {
        val index = when (networkType) {
            NetworkType.LTE -> lteIndex()
            NetworkType.NR -> nrIndex()
        }
        return index.keys.randomOrNull()
    }

    private suspend fun lteIndex(): Map<Int, List<EquipmentRecord>> = mutex.withLock {
        lteIndex ?: buildStandardIndex("lte.json", "LTE").also { lteIndex = it }
    }

    private suspend fun nrIndex(): Map<Int, List<EquipmentRecord>> = mutex.withLock {
        nrIndex ?: buildStandardIndex("5g.json", "5G").also { nrIndex = it }
    }

    private suspend fun repeaterIndex(): Map<Int, List<EquipmentRecord>> = mutex.withLock {
        repeaterIndex ?: buildRepeaterIndex().also { repeaterIndex = it }
    }

    private suspend fun buildStandardIndex(assetFile: String, category: String): Map<Int, List<EquipmentRecord>> =
        withContext(Dispatchers.IO) {
            val array = readAssetArray(assetFile)
            val index = HashMap<Int, MutableList<EquipmentRecord>>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val pciList = parsePciTokens(obj.optString("pc"))
                if (pciList.isEmpty()) continue
                val record = obj.toEquipmentRecord(category, pciList)
                for (pci in pciList) {
                    index.getOrPut(pci) { mutableListOf() }.add(record)
                }
            }
            index
        }

    private suspend fun buildRepeaterIndex(): Map<Int, List<EquipmentRecord>> =
        withContext(Dispatchers.IO) {
            val array = readAssetArray("repeater.json")
            val index = HashMap<Int, MutableList<EquipmentRecord>>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val pciList = (
                    parsePciTokens(obj.optString("pc")) +
                        parsePciTokens(obj.optString("p9")) +
                        parsePciTokens(obj.optString("p18")) +
                        parsePciTokens(obj.optString("p21"))
                    ).distinct()
                if (pciList.isEmpty()) continue
                val record = obj.toEquipmentRecord("중계기", pciList)
                for (pci in pciList) {
                    index.getOrPut(pci) { mutableListOf() }.add(record)
                }
            }
            index
        }

    private fun readAssetArray(fileName: String): JSONArray {
        val text = appContext.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONArray(text)
    }

    private fun parsePciTokens(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
    }

    private fun JSONObject.toEquipmentRecord(category: String, pciList: List<Int>): EquipmentRecord = EquipmentRecord(
        id = optString("i"),
        category = category,
        kind = optString("k"),
        name = optString("n"),
        province = optString("s"),
        city = optString("g"),
        dong = optString("d"),
        buildingName = optString("bn"),
        roadAddress = optString("ra"),
        description = optString("ds"),
        floor = optString("fl"),
        duId = optString("du").ifBlank { null },
        duGroupName = optString("dn").ifBlank { null },
        placement = optString("pl").ifBlank { null },
        antennaPosition = optString("an").ifBlank { null },
        manufacturer = optString("mk").ifBlank { null },
        hostRef = optString("mh").ifBlank { null },
        pciList = pciList,
        latitude = if (has("la")) optDouble("la") else null,
        longitude = if (has("lo")) optDouble("lo") else null
    )
}
