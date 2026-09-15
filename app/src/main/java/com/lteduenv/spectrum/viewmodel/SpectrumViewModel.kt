package com.lteduenv.spectrum.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lteduenv.spectrum.data.BandPreset
import com.lteduenv.spectrum.data.BandPresets
import com.lteduenv.spectrum.data.CableLossResult
import com.lteduenv.spectrum.data.DtfFrame
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
enum class DataSourceMode { SIMULATED, USB_SDR }

data class SpectrumUiState(
    val mode: MeasurementMode = MeasurementMode.SPECTRUM,
    val config: SweepConfig = SweepConfig(),
    val selectedBandId: String = "lte_b3_20m",
    val markers: List<Marker> = (1..5).map { Marker(it) },
    val selectedMarker: Int = 1,
    val spectrumFrame: SpectrumFrame? = null,
    /** Total Channel Power over [SweepConfig.integrationBwMhz] - see [SpectrumFrame.channelPowerDbm]. */
    val channelPowerDbm: Double? = null,
    /** Whether the Channel Power readout is shown - like selecting/deselecting MEASURE > Channel Power. */
    val channelPowerEnabled: Boolean = false,
    val vswrFrame: VswrFrame? = null,
    val dtfFrame: DtfFrame? = null,
    val cableLossResult: CableLossResult? = null,
    val cableLossLengthM: Double = 50.0,
    val cableLossLoading: Boolean = false,
    val dataSourceLabel: String = "",
    val dataSourceMode: DataSourceMode = DataSourceMode.SIMULATED,
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

    /** Running VBW trace average - see [applyVbwSmoothing]; reset whenever the sweep restarts. */
    private var vbwAverage: FloatArray? = null

    init {
        restartReadingLoop()
    }

    private fun restartReadingLoop() {
        val state = _uiState.value
        val previousJob = readingJob
        vbwAverage = null
        // Clear stale frames so a leftover trace from the previous mode/source doesn't linger
        // on screen until the new source produces its first frame.
        _uiState.update { it.copy(spectrumFrame = null, vswrFrame = null, dtfFrame = null, channelPowerDbm = null) }
        readingJob = viewModelScope.launch {
            // Wait for the previous job (and its stopRx()/cleanup) to fully finish before this
            // one starts collecting, so its teardown can't run after - and turn off - the new job.
            previousJob?.cancelAndJoin()
            try {
                when (state.mode) {
                    MeasurementMode.SPECTRUM -> dataSource.spectrum(state.config).collect { frame ->
                        val offsetApplied = applyRefLevelOffset(frame, state.config.refLevelOffsetDb)
                        val adjusted = applyVbwSmoothing(offsetApplied, state.config.rbwKhz, state.config.vbwKhz)
                        val channelPower = adjusted.channelPowerDbm(state.config.centerMhz, state.config.integrationBwMhz)
                        _uiState.update {
                            it.copy(
                                spectrumFrame = adjusted,
                                channelPowerDbm = channelPower,
                                markers = refreshMarkerLevels(it.markers, adjusted),
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

    /** Adds the REF LEVEL OFFSET calibration (see [SweepConfig.refLevelOffsetDb]) to every point. */
    private fun applyRefLevelOffset(frame: SpectrumFrame, offsetDb: Double): SpectrumFrame {
        if (offsetDb == 0.0) return frame
        val offset = offsetDb.toFloat()
        return frame.copy(levelsDbm = FloatArray(frame.levelsDbm.size) { frame.levelsDbm[it] + offset })
    }

    /**
     * Video-bandwidth trace smoothing, like a real analyzer's VBW: an exponential moving average
     * across successive sweeps, applied per point. A VBW narrower than the RBW smooths out noise
     * fluctuations at the cost of a slower-responding trace; VBW >= RBW (the usual default) turns
     * smoothing off, matching real instrument behavior.
     */
    private fun applyVbwSmoothing(frame: SpectrumFrame, rbwKhz: Double, vbwKhz: Double): SpectrumFrame {
        if (vbwKhz >= rbwKhz) {
            vbwAverage = null
            return frame
        }
        val alpha = (vbwKhz / rbwKhz).toFloat().coerceIn(0.02f, 1f)
        val previous = vbwAverage
        val smoothed = if (previous != null && previous.size == frame.levelsDbm.size) {
            FloatArray(frame.levelsDbm.size) { i -> previous[i] + alpha * (frame.levelsDbm[i] - previous[i]) }
        } else {
            frame.levelsDbm.copyOf()
        }
        vbwAverage = smoothed
        return frame.copy(levelsDbm = smoothed)
    }

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
                    integrationBwMhz = preset.integrationBwMhz,
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

    /** Front-end RF preamp (HackRF's AMP stage only - see [SweepConfig.preampEnabled]). */
    fun setPreampEnabled(enabled: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(preampEnabled = enabled)) }
        restartReadingLoop()
    }

    /** REF LEVEL OFFSET calibration - see [SweepConfig.refLevelOffsetDb]. */
    fun setRefLevelOffsetDb(value: Double) {
        _uiState.update { it.copy(config = it.config.copy(refLevelOffsetDb = value)) }
        restartReadingLoop()
    }

    /** Resolution bandwidth - on the USB SDR source this directly drives the FFT size used. */
    fun setRbwKhz(value: Double) {
        _uiState.update { it.copy(config = it.config.copy(rbwKhz = value.coerceIn(1.0, 1_000.0))) }
        restartReadingLoop()
    }

    /** Video bandwidth - see [applyVbwSmoothing]. */
    fun setVbwKhz(value: Double) {
        _uiState.update { it.copy(config = it.config.copy(vbwKhz = value.coerceIn(0.1, 1_000.0))) }
        restartReadingLoop()
    }

    /** Channel Power's Integration BW - see [SpectrumFrame.channelPowerDbm]. */
    fun setIntegrationBwMhz(value: Double) {
        _uiState.update { it.copy(config = it.config.copy(integrationBwMhz = value.coerceAtLeast(0.01))) }
        restartReadingLoop()
    }

    /** Shows/hides the Channel Power readout - it's always computed, this just controls display. */
    fun setChannelPowerEnabled(enabled: Boolean) {
        _uiState.update { it.copy(channelPowerEnabled = enabled) }
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
    fun applyDataSource(mode: DataSourceMode) {
        dataSource = when (mode) {
            DataSourceMode.SIMULATED -> SimulatedRepeaterDataSource()
            DataSourceMode.USB_SDR -> usbSdrDataSource
        }
        _uiState.update {
            it.copy(
                dataSourceMode = mode,
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
            applyDataSource(DataSourceMode.USB_SDR)
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
            if (granted) applyDataSource(DataSourceMode.USB_SDR)
        }
    }

    override fun onCleared() {
        readingJob?.cancel()
    }
}
