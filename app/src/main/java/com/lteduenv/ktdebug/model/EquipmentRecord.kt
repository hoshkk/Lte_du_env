package com.lteduenv.ktdebug.model

/**
 * A single site/equipment entry sourced from KT's internal equipment-lookup data
 * (lte.json / 5g.json / repeater.json). Field names below mirror the short JSON
 * keys used in that dataset:
 *
 *   i  equipment id            k   equipment kind (RU/DU/RIU/repeater model)
 *   c  category (LTE/5G/중계기)  n   site/equipment name
 *   s  province                g   city/gu                d  dong
 *   b  lot number              bn  building name           ra  road address
 *   ds description / directions  fl floor / location note
 *   du parent DU id            dn  DU group name (e.g. "용인-01-00")
 *   pl repeater placement note an  antenna position note
 *   mk manufacturer (repeater) mh  host cell reference (repeater)
 *   pc / p9 / p18 / p21  PCI value(s) - "pc" can hold a comma separated list
 *   la / lo latitude / longitude
 */
data class EquipmentRecord(
    val id: String,
    val category: String,
    val kind: String,
    val name: String,
    val province: String,
    val city: String,
    val dong: String,
    val buildingName: String,
    val roadAddress: String,
    val description: String,
    val floor: String,
    val duId: String?,
    val duGroupName: String?,
    val placement: String?,
    val antennaPosition: String?,
    val manufacturer: String?,
    val hostRef: String?,
    val pciList: List<Int>,
    val latitude: Double?,
    val longitude: Double?
) {
    val networkType: NetworkType?
        get() = when (category) {
            "LTE" -> NetworkType.LTE
            "5G" -> NetworkType.NR
            else -> null
        }

    val isRepeater: Boolean get() = category == "중계기"

    val addressLine: String
        get() = roadAddress.ifBlank { listOf(province, city, dong, buildingName).filter { it.isNotBlank() }.joinToString(" ") }
}

data class EquipmentMatch(
    val record: EquipmentRecord,
    val matchedPci: Int,
    val distanceMeters: Double? = null
)

data class NearbyResult(
    val record: EquipmentRecord,
    val distanceMeters: Double
)
