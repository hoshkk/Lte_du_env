package com.lteduenv.spectrum.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Polls a repeater / base station's own TX-RX monitoring API over HTTP.
 *
 * This is a starting point, not a finished integration: every real repeater/eNB/gNB vendor
 * exposes its spectrum/VSWR/DTF readings differently (REST, SNMP, a vendor SDK, a serial port on
 * the unit itself...). Point [baseUrl] at that equipment's HTTP endpoint and adjust the request
 * paths and JSON parsing below to match its actual API once you have that document; the shape
 * this class expects today is:
 *
 * GET {baseUrl}/spectrum?centerMhz=&spanMhz=&dir=TX|RX
 *   { "startMhz": 829.0, "stopMhz": 839.0, "levelsDbm": [-91.2, -90.8, ...], "timestampMs": 169... }
 *
 * GET {baseUrl}/vswr?centerMhz=&spanMhz=
 *   { "samples": [ {"freqMhz":829.0,"vswr":1.3,"returnLossDb":-18.2}, ... ] }
 *
 * GET {baseUrl}/dtf?centerMhz=&maxDistanceM=
 *   { "cableLossDbPer100m":4.1, "velocityFactor":0.88,
 *     "samples": [ {"distanceM":0.0,"returnLossDb":-14.0}, ... ] }
 *
 * GET {baseUrl}/cable-loss?centerMhz=&lengthM=
 *   { "lengthM":50, "measuredLossDb":2.1, "lossPer100mDb":4.2 }
 */
class HttpRepeaterDataSource(
    private val baseUrl: String,
    private val apiKey: String? = null,
    private val pollIntervalMs: Long = 1000L,
) : RepeaterDataSource {

    override val name: String = "HTTP: $baseUrl"

    override fun spectrum(config: SweepConfig): Flow<SpectrumFrame> = flow {
        while (true) {
            runCatching {
                val path = "spectrum?centerMhz=${config.centerMhz}&spanMhz=${config.spanMhz}" +
                    "&dir=${config.direction.name}"
                val json = get(path)
                val levels = json.getJSONArray("levelsDbm")
                val out = FloatArray(levels.length()) { levels.getDouble(it).toFloat() }
                SpectrumFrame(
                    startMhz = json.getDouble("startMhz"),
                    stopMhz = json.getDouble("stopMhz"),
                    levelsDbm = out,
                    timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
                )
            }.onSuccess { emit(it) }
            delay(pollIntervalMs)
        }
    }.flowOn(Dispatchers.IO)

    override fun vswr(config: SweepConfig): Flow<VswrFrame> = flow {
        while (true) {
            runCatching {
                val path = "vswr?centerMhz=${config.centerMhz}&spanMhz=${config.spanMhz}"
                val json = get(path)
                val arr = json.getJSONArray("samples")
                val samples = (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    VswrSample(
                        freqMhz = s.getDouble("freqMhz"),
                        vswr = s.getDouble("vswr").toFloat(),
                        returnLossDb = s.getDouble("returnLossDb").toFloat(),
                    )
                }
                VswrFrame(samples, System.currentTimeMillis())
            }.onSuccess { emit(it) }
            delay(pollIntervalMs)
        }
    }.flowOn(Dispatchers.IO)

    override fun dtf(config: SweepConfig, maxDistanceM: Double): Flow<DtfFrame> = flow {
        while (true) {
            runCatching {
                val path = "dtf?centerMhz=${config.centerMhz}&maxDistanceM=$maxDistanceM"
                val json = get(path)
                val arr = json.getJSONArray("samples")
                val samples = (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    DtfSample(
                        distanceM = s.getDouble("distanceM"),
                        returnLossDb = s.getDouble("returnLossDb").toFloat(),
                    )
                }
                DtfFrame(
                    samples = samples,
                    cableLossDbPer100m = json.optDouble("cableLossDbPer100m", 4.0),
                    velocityFactor = json.optDouble("velocityFactor", 0.88),
                    timestampMs = System.currentTimeMillis(),
                )
            }.onSuccess { emit(it) }
            delay(pollIntervalMs)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun measureCableLoss(config: SweepConfig, lengthM: Double): CableLossResult =
        withContext(Dispatchers.IO) {
            val path = "cable-loss?centerMhz=${config.centerMhz}&lengthM=$lengthM"
            val json = get(path)
            CableLossResult(
                lengthM = json.optDouble("lengthM", lengthM),
                measuredLossDb = json.getDouble("measuredLossDb"),
                lossPer100mDb = json.getDouble("lossPer100mDb"),
            )
        }

    private fun get(path: String): JSONObject {
        val separator = if (baseUrl.endsWith("/")) "" else "/"
        val url = URL("$baseUrl$separator$path")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            apiKey?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode}: $body")
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
