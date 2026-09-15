package com.lteduenv.spectrum.viewmodel

import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val useHttpSource: Boolean = false,
    val httpBaseUrl: String = "",
) {
    val selectedBand: BandPreset? get() = BandPresets.all.find { it.id == selectedBandId }
}

class SpectrumViewModel : ViewModel() {

    // The Compose `viewModel()` factory instantiates this via a no-arg constructor, so the
    // starting data source is fixed here rather than injected; swap sources at runtime via
    // applyDataSource() instead (see the Settings dialog).
    private var dataSource: RepeaterDataSource = SimulatedRepeaterDataSource()

    private val _uiState = MutableStateFlow(SpectrumUiState(dataSourceLabel = dataSource.name))
    val uiState: StateFlow<SpectrumUiState> = _uiState.asStateFlow()

    private var readingJob: Job? = null

    init {
        restartReadingLoop()
    }

    private fun restartReadingLoop() {
        readingJob?.cancel()
        val state = _uiState.value
        readingJob = viewModelScope.launch {
            when (state.mode) {
                MeasurementMode.SPECTRUM -> dataSource.spectrum(state.config).collect { frame ->
                    _uiState.update {
                        it.copy(spectrumFrame = frame, markers = refreshMarkerLevels(it.markers, frame))
                    }
                }
                MeasurementMode.VSWR -> dataSource.vswr(state.config).collect { frame ->
                    _uiState.update { it.copy(vswrFrame = frame) }
                }
                MeasurementMode.DTF -> dataSource.dtf(state.config).collect { frame ->
                    _uiState.update { it.copy(dtfFrame = frame) }
                }
                MeasurementMode.CABLE_LOSS -> {
                    // No continuous stream here; the user triggers a one-shot measurement.
                }
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
                config = it.config.copy(centerMhz = preset.centerMhz, spanMhz = preset.spanMhz),
            )
        }
        restartReadingLoop()
    }

    fun setDirection(direction: LinkDirection) {
        if (_uiState.value.config.direction == direction) return
        _uiState.update { it.copy(config = it.config.copy(direction = direction)) }
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
            }.getOrNull()
            _uiState.update {
                it.copy(cableLossLoading = false, cableLossResult = result ?: it.cableLossResult)
            }
        }
    }

    /** Switches between the built-in demo generator and a real repeater/base station HTTP API. */
    fun applyDataSource(useHttp: Boolean, baseUrl: String) {
        dataSource = if (useHttp && baseUrl.isNotBlank()) {
            HttpRepeaterDataSource(baseUrl)
        } else {
            SimulatedRepeaterDataSource()
        }
        _uiState.update {
            it.copy(useHttpSource = useHttp, httpBaseUrl = baseUrl, dataSourceLabel = dataSource.name)
        }
        restartReadingLoop()
    }

    override fun onCleared() {
        readingJob?.cancel()
    }
}
