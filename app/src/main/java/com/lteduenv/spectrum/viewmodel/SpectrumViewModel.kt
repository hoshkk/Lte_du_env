package com.lteduenv.spectrum.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lteduenv.spectrum.data.BandPreset
import com.lteduenv.spectrum.data.BandPresets
import com.lteduenv.spectrum.data.CableLossResult
import com.lteduenv.spectrum.data.DtfFrame
import com.lteduenv.spectrum.data.HttpRepeaterDataSource
import com.lteduenv.spectrum.data.LinkDirection
import com.lteduenv.spectrum.data.Marker
import com.lteduenv.spectrum.data.MeasurementMode
import com.lteduenv.spectrum.data.RepeaterDataSource
import com.lteduenv.spectrum.data.SimulatedRepeaterDataSource
import com.lteduenv.spectrum.data.SpectrumFrame
import com.lteduenv.spectrum.data.SweepConfig
import com.lteduenv.spectrum.data.VswrFrame
import com.lteduenv.spectrum.data.sdr.UsbSdrDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which backend is currently feeding the app; mirrors the choice in the Settings dialog. */
enum class DataSourceMode { SIMULATED, HTTP, USB_SDR }

data class SpectrumUiState(
    val mode: MeasurementMode = MeasurementMode.SPECTRUM,
    val config: SweepConfig = SweepConfig(),
    val selectedBandId: String = "b5a",
    val markers: List<Marker> = (1..5).map { Marker(it) },
    val selectedMarker: Int = 1,
    val spectrumFrame: SpectrumFrame? = null,
    val vswrFrame: VswrFrame? = null,
    val dtfFrame: DtfFrame? = null,
    val cableLossResult: CableLossResult? = null,
    val cableLossLengthM: Double = 50.0,
    val cableLossLoading: Boolean = false,
    val dataSourceLabel: String = "",
    val dataSourceMode: DataSourceMode = DataSourceMode.SIMULATED,
    val httpBaseUrl: String = "",
    /** Status/error text for the active data source (e.g. USB SDR connect progress). */
    val sourceStatusMessage: String? = null,
) {
    val selectedBand: BandPreset? get() = BandPresets.all.find { it.id == selectedBandId }
}

class SpectrumViewModel(application: Application) : AndroidViewModel(application) {

    private var dataSource: RepeaterDataSource = SimulatedRepeaterDataSource()

    private val usbSdrDataSource by lazy { UsbSdrDataSource(getApplication()) }

    private val _uiState = MutableStateFlow(SpectrumUiState(dataSourceLabel = dataSource.name))
    val uiState: StateFlow<SpectrumUiState> = _uiState.asStateFlow()

    private var readingJob: Job? = null

    init {
        restartReadingLoop()
    }

    private fun restartReadingLoop() {
        val state = _uiState.value
        val previousJob = readingJob
        // Clear stale frames so a leftover trace from the previous mode/source doesn't linger
        // on screen until the new source produces its first frame.
        _uiState.update { it.copy(spectrumFrame = null, vswrFrame = null, dtfFrame = null) }
        readingJob = viewModelScope.launch {
            // Wait for the previous job (and its stopRx()/cleanup) to fully finish before this
            // one starts collecting, so its teardown can't run after - and turn off - the new job.
            previousJob?.cancelAndJoin()
            try {
                when (state.mode) {
                    MeasurementMode.SPECTRUM -> dataSource.spectrum(state.config).collect { frame ->
                        _uiState.update {
                            it.copy(
                                spectrumFrame = frame,
                                markers = refreshMarkerLevels(it.markers, frame),
                                sourceStatusMessage = null,
                            )
                        }
                    }
                    MeasurementMode.VSWR -> dataSource.vswr(state.config).collect { frame ->
                        _uiState.update { it.copy(vswrFrame = frame, sourceStatusMessage = null) }
                    }
                    MeasurementMode.DTF -> dataSource.dtf(state.config).collect { frame ->
                        _uiState.update { it.copy(dtfFrame = frame, sourceStatusMessage = null) }
                    }
                    MeasurementMode.CABLE_LOSS -> {
                        // No continuous stream here; the user triggers a one-shot measurement.
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(sourceStatusMessage = e.message ?: "Data source error") }
            }
        }
    }

    private fun refreshMarkerLevels(markers: List<Marker>, frame: SpectrumFrame): List<Marker> =
        markers.map { if (it.enabled) it.copy(levelDbm = frame.levelAt(it.freqMhz)) else it }

    fun selectMode(mode: MeasurementMode) {
        if (_uiState.value.mode == mode) return
        _uiState.update { it.copy(mode = mode) }
        restartReadingLoop()
    }

    fun selectBand(preset: BandPreset) {
        _uiState.update {
            it.copy(
                selectedBandId = preset.id,
                config = it.config.copy(
                    centerMhz = preset.centerMhzFor(it.config.direction),
                    spanMhz = preset.spanMhz,
                ),
            )
        }
        restartReadingLoop()
    }

    /**
     * Switches TX/RX. When a band preset is selected, this retunes to that band's downlink (TX)
     * or uplink (RX) center frequency - otherwise only the direction label changes, since a
     * manually-entered frequency has no known paired uplink/downlink counterpart to jump to.
     */
    fun setDirection(direction: LinkDirection) {
        if (_uiState.value.config.direction == direction) return
        _uiState.update { state ->
            val newCenterMhz = state.selectedBand?.centerMhzFor(direction) ?: state.config.centerMhz
            state.copy(config = state.config.copy(direction = direction, centerMhz = newCenterMhz))
        }
        restartReadingLoop()
    }

    fun setCenterMhz(value: Double) {
        _uiState.update { it.copy(config = it.config.copy(centerMhz = value), selectedBandId = "") }
        restartReadingLoop()
    }

    fun setSpanMhz(value: Double) {
        _uiState.update { it.copy(config = it.config.copy(spanMhz = value.coerceAtLeast(0.1))) }
        restartReadingLoop()
    }

    fun setRefLevelDbm(value: Double) {
        _uiState.update { it.copy(config = it.config.copy(refLevelDbm = value)) }
    }

    fun selectMarker(index: Int) {
        _uiState.update { it.copy(selectedMarker = index) }
    }

    /** Moves the currently selected marker to the highest point of the live spectrum trace. */
    fun peakSearch() {
        val frame = _uiState.value.spectrumFrame ?: return
        val peakIdx = frame.levelsDbm.indices.maxByOrNull { frame.levelsDbm[it] } ?: return
        val ratio = peakIdx.toDouble() / (frame.pointCount - 1).coerceAtLeast(1)
        val freq = frame.startMhz + ratio * (frame.stopMhz - frame.startMhz)
        placeSelectedMarkerAt(freq, frame.levelsDbm[peakIdx])
    }

    fun placeMarkerAtFrequency(freqMhz: Double) {
        val frame = _uiState.value.spectrumFrame ?: return
        placeSelectedMarkerAt(freqMhz, frame.levelAt(freqMhz))
    }

    private fun placeSelectedMarkerAt(freqMhz: Double, levelDbm: Float) {
        _uiState.update { state ->
            val markers = state.markers.map { marker ->
                if (marker.index == state.selectedMarker) {
                    marker.copy(enabled = true, freqMhz = freqMhz, levelDbm = levelDbm)
                } else marker
            }
            state.copy(markers = markers)
        }
    }

    fun clearSelectedMarker() {
        _uiState.update { state ->
            val markers = state.markers.map {
                if (it.index == state.selectedMarker) Marker(it.index) else it
            }
            state.copy(markers = markers)
        }
    }

    fun setCableLossLengthM(value: Double) {
        _uiState.update { it.copy(cableLossLengthM = value.coerceAtLeast(0.0)) }
    }

    fun measureCableLoss() {
        viewModelScope.launch {
            _uiState.update { it.copy(cableLossLoading = true) }
            val result = runCatching {
                dataSource.measureCableLoss(_uiState.value.config, _uiState.value.cableLossLengthM)
            }
            _uiState.update {
                it.copy(
                    cableLossLoading = false,
                    cableLossResult = result.getOrNull() ?: it.cableLossResult,
                    sourceStatusMessage = result.exceptionOrNull()?.message ?: it.sourceStatusMessage,
                )
            }
        }
    }

    /** Switches the active [RepeaterDataSource]. For [DataSourceMode.USB_SDR], call [connectUsbSdr] first. */
    fun applyDataSource(mode: DataSourceMode, httpBaseUrl: String) {
        dataSource = when (mode) {
            DataSourceMode.SIMULATED -> SimulatedRepeaterDataSource()
            DataSourceMode.HTTP -> if (httpBaseUrl.isNotBlank()) {
                HttpRepeaterDataSource(httpBaseUrl)
            } else {
                SimulatedRepeaterDataSource()
            }
            DataSourceMode.USB_SDR -> usbSdrDataSource
        }
        _uiState.update {
            it.copy(
                dataSourceMode = mode,
                httpBaseUrl = httpBaseUrl,
                dataSourceLabel = dataSource.name,
                // A cable-loss reading from the old source no longer applies to the new one.
                cableLossResult = null,
            )
        }
        restartReadingLoop()
    }

    /**
     * Looks for an attached RTL-SDR or HackRF dongle, requests USB permission if needed (shows a
     * system dialog), and on success switches to [DataSourceMode.USB_SDR]. Safe to call repeatedly.
     */
    fun connectUsbSdr() {
        val device = usbSdrDataSource.findSupportedDevice()
        if (device == null) {
            _uiState.update {
                it.copy(sourceStatusMessage = "No RTL-SDR/HackRF dongle found. Check the USB OTG connection.")
            }
            return
        }
        if (usbSdrDataSource.hasPermission(device)) {
            _uiState.update { it.copy(sourceStatusMessage = "USB SDR connected: ${device.deviceName}") }
            applyDataSource(DataSourceMode.USB_SDR, _uiState.value.httpBaseUrl)
            return
        }
        _uiState.update { it.copy(sourceStatusMessage = "Requesting USB permission...") }
        usbSdrDataSource.requestPermission(device) { granted ->
            _uiState.update {
                it.copy(
                    sourceStatusMessage = if (granted) {
                        "USB SDR connected: ${device.deviceName}"
                    } else {
                        "USB permission denied."
                    },
                )
            }
            if (granted) applyDataSource(DataSourceMode.USB_SDR, _uiState.value.httpBaseUrl)
        }
    }

    override fun onCleared() {
        readingJob?.cancel()
    }
}
