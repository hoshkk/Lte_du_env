package com.lteduenv.ktdebug.data

import android.content.Context
import android.location.Location
import com.lteduenv.ktdebug.model.EquipmentMatch
import com.lteduenv.ktdebug.model.EquipmentRecord
import com.lteduenv.ktdebug.model.NearbyResult
import com.lteduenv.ktdebug.model.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Looks up which physical KT site/equipment corresponds to a PCI (or a GPS
 * position), using the same lte.json / 5g.json / repeater.json inventory
 * bundled with KT's internal equipment-lookup app (assets copied verbatim,
 * see app/src/main/assets).
 *
 * A PCI is reused across many distant sites, so a lookup can return several
 * candidates; pass a device location to sort them by distance.
 */
class EquipmentRepository(context: Context) {

    private val appContext = context.applicationContext

    private data class DatasetCache(
        val records: List<EquipmentRecord>,
        val pciIndex: Map<Int, List<EquipmentRecord>>
    )

    private val lteMutex = Mutex()
    private val nrMutex = Mutex()
    private val repeaterMutex = Mutex()
    private var lteCache: DatasetCache? = null
    private var nrCache: DatasetCache? = null
    private var repeaterCache: DatasetCache? = null

    suspend fun findByPci(
        networkType: NetworkType,
        pci: Int,
        includeRepeaters: Boolean = true,
        deviceLocation: Location? = null
    ): List<EquipmentMatch> = withContext(Dispatchers.Default) {
        val primary = when (networkType) {
            NetworkType.LTE -> lteCache().pciIndex[pci]
            NetworkType.NR -> nrCache().pciIndex[pci]
        }
        val matches = mutableListOf<EquipmentMatch>()
        primary?.forEach { matches += EquipmentMatch(it, pci) }
        if (includeRepeaters) {
            repeaterCache().pciIndex[pci]?.forEach { matches += EquipmentMatch(it, pci) }
        }

        val withDistance = if (deviceLocation != null) {
            matches.map { match -> match.copy(distanceMeters = distanceTo(deviceLocation, match.record)) }
        } else matches

        withDistance.sortedWith(compareBy(nullsLast<Double>()) { it.distanceMeters })
    }

    /** Real sites/repeaters within [radiusMeters] of [location], nearest first. */
    suspend fun nearby(
        location: Location,
        radiusMeters: Double,
        includeLte: Boolean = true,
        includeNr: Boolean = true,
        includeRepeaters: Boolean = true,
        limit: Int = 50
    ): List<NearbyResult> = withContext(Dispatchers.Default) {
        val records = mutableListOf<EquipmentRecord>()
        if (includeLte) records += lteCache().records
        if (includeNr) records += nrCache().records
        if (includeRepeaters) records += repeaterCache().records

        records.mapNotNull { record ->
            if (record.latitude == null || record.longitude == null) return@mapNotNull null
            val distance = distanceTo(location, record) ?: return@mapNotNull null
            if (distance <= radiusMeters) NearbyResult(record, distance) else null
        }.sortedBy { it.distanceMeters }.take(limit)
    }

    private fun distanceTo(deviceLocation: Location, record: EquipmentRecord): Double? {
        val lat = record.latitude ?: return null
        val lon = record.longitude ?: return null
        val results = FloatArray(1)
        Location.distanceBetween(deviceLocation.latitude, deviceLocation.longitude, lat, lon, results)
        return results[0].toDouble()
    }

    private suspend fun lteCache(): DatasetCache = lteMutex.withLock {
        lteCache ?: buildDatasetCache("lte.json", "LTE") { obj -> parsePciTokens(obj.optString("pc")) }
            .also { lteCache = it }
    }

    private suspend fun nrCache(): DatasetCache = nrMutex.withLock {
        nrCache ?: buildDatasetCache("5g.json", "5G") { obj -> parsePciTokens(obj.optString("pc")) }
            .also { nrCache = it }
    }

    private suspend fun repeaterCache(): DatasetCache = repeaterMutex.withLock {
        repeaterCache ?: buildDatasetCache("repeater.json", "중계기") { obj ->
            (
                parsePciTokens(obj.optString("pc")) +
                    parsePciTokens(obj.optString("p9")) +
                    parsePciTokens(obj.optString("p18")) +
                    parsePciTokens(obj.optString("p21"))
                ).distinct()
        }.also { repeaterCache = it }
    }

    private suspend fun buildDatasetCache(
        assetFile: String,
        category: String,
        pciExtractor: (JSONObject) -> List<Int>
    ): DatasetCache = withContext(Dispatchers.IO) {
        val array = readAssetArray(assetFile)
        val records = ArrayList<EquipmentRecord>(array.length())
        val index = HashMap<Int, MutableList<EquipmentRecord>>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val pciList = pciExtractor(obj)
            if (pciList.isEmpty()) continue
            val record = obj.toEquipmentRecord(category, pciList)
            records += record
            for (pci in pciList) {
                index.getOrPut(pci) { mutableListOf() }.add(record)
            }
        }
        DatasetCache(records, index)
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
