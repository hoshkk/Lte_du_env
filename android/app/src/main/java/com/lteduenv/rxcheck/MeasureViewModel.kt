package com.lteduenv.rxcheck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lteduenv.rxcheck.core.analysis.Analysis
import com.lteduenv.rxcheck.core.analysis.AutoFit
import com.lteduenv.rxcheck.core.analysis.Evaluate
import com.lteduenv.rxcheck.core.analysis.Results
import com.lteduenv.rxcheck.core.diag.Check
import com.lteduenv.rxcheck.core.diag.SelfTest
import com.lteduenv.rxcheck.core.diag.SettleProbe
import com.lteduenv.rxcheck.core.view.Waterfall
import com.lteduenv.rxcheck.core.model.Band
import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.rtl.RtlSdr
import com.lteduenv.rxcheck.core.sim.SimReceiver
import com.lteduenv.rxcheck.core.sweep.Receiver
import com.lteduenv.rxcheck.core.sweep.SweepEngine
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import com.lteduenv.rxcheck.core.sweep.SweepTiming
import com.lteduenv.rxcheck.core.sweep.Trace
import com.lteduenv.rxcheck.core.usb.UsbIoException
import com.lteduenv.rxcheck.data.Store
import com.lteduenv.rxcheck.usb.UsbAccess
import com.lteduenv.rxcheck.usb.UsbPermissionDenied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale

data class Marker(val index: Int, val freqHz: Double? = null)

/** Self-test / settle-probe state for the diagnostics dialog. */
data class DiagState(
    val busy: String? = null,
    val checks: List<Check>? = null,
    val settle: SettleProbe.Result? = null,
    val error: String? = null,
    /** Results came from the demo simulator, not a dongle. */
    val demo: Boolean = false,
)

data class UiState(
    val settings: Settings,
    val running: Boolean = false,
    val demo: Boolean = false,
    val status: String = "대기 · 동글을 연결하고 측정 시작",
    val device: String? = null,
    /** Live trace: this sweep's segments over the previous sweep. */
    val live: Trace? = null,
    /** Points of [live] already refreshed in the current sweep. */
    val livePoints: Int = 0,
    val last: Trace? = null,
    val hold: Trace? = null,
    val baseline: Trace? = null,
    val baselineTime: Long? = null,
    /** No-input recording of the dongle's own spurs for the current plan. */
    val internal: Trace? = null,
    val results: Results? = null,
    val timing: SweepTiming? = null,
    val sweeps: Int = 0,
    val clipped: Boolean = false,
    val markers: List<Marker> = (1..5).map { Marker(it) },
    val selectedMarker: Int = 1,
    val autoFit: String? = null,
    val presets: Set<Mode> = emptySet(),
    val error: String? = null,
    /** Near-field sniffing (probe the dongle along cables/connectors, watch the level rise). */
    val sniff: Boolean = false,
    /** Highest level of the first sweep after sniffing started (or after "다시 기준"). */
    val sniffRefDb: Double? = null,
    /** Beep faster as the level rises (sniffing). */
    val sniffSound: Boolean = true,
    /** Tap-zoom active: one capture around the tapped frequency, real-time. */
    val zoomed: Boolean = false,
    /** Bumped when the waterfall gets a row (the buffer itself is [MeasureViewModel.waterfall]). */
    val waterfallVersion: Int = 0,
    val diag: DiagState = DiagState(),
) {
    /** Highest finite level of the last completed sweep and its frequency (dB incl. offset). */
    val peakNow: Pair<Double, Double>? get() {
        val t = last ?: return null
        val f = Analysis.peakFreq(t) ?: return null
        return Analysis.levelAt(t, f, settings.offsetDb)?.let { it to f }
    }
    /** Trace the markers and peak search read: Max Hold when on, else the live trace. */
    val shown: Trace? get() = if (settings.maxHold && hold != null) hold else live
}

class MeasureViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val _state = MutableStateFlow(UiState(store.loadSettings(), presets = presetSet()))
    val state: StateFlow<UiState> = _state
    private var job: Job? = null

    /**
     * One dedicated, high-priority thread for USB work. Each retune is a chain of
     * blocking control transfers; waking up promptly after each one is most of
     * what can still be gained without changing what is sent to the dongle.
     */
    private val measureExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            r.run()
        }, "rx-measure")
    }
    private val measureDispatcher = measureExecutor.asCoroutineDispatcher()

    override fun onCleared() {
        // Let the measurement finish its cleanup (closing the dongle) before the thread goes.
        val j = job
        if (j == null) measureExecutor.shutdown()
        else { j.invokeOnCompletion { measureExecutor.shutdown() }; j.cancel() }
    }

    /** Waterfall rows; written on the measurement thread, drawn by the UI. */
    val waterfall = Waterfall()

    /** 1 = self-test, 2 = settle probe; picked up by the measurement loop. */
    @Volatile private var diagRequest = 0

    /** Auto-fit progress, touched only by the measurement coroutine and [autoFit]. */
    @Volatile private var autoFitRequested = false

    private fun presetSet() = Mode.values().filter { store.hasPreset(it) }.toSet()

    // ---- measurement ---------------------------------------------------------------

    fun start(demo: Boolean) {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true, demo = demo, error = null, status = "연결 중…") }
        job = viewModelScope.launch(measureDispatcher) {
            var attempt = 0
            try {
                while (currentCoroutineContext().isActive) {
                    var usb: AutoCloseable? = null
                    var rx: Receiver? = null
                    try {
                        val s0 = _state.value.settings
                        val r: Receiver = if (demo) SimReceiver(tuneDelayMs = 4) else {
                            val io = UsbAccess.open(getApplication<Application>())
                            usb = io
                            RtlSdr.open(io, fastTune = s0.fastTune, gainStep = s0.gainStep, narrowIf = s0.narrowIf)
                        }
                        rx = r
                        _state.update { it.copy(device = r.description, status = "측정 중") }
                        val outcome = loop(r) { attempt = 0 }
                        if (outcome == LoopEnd.STOPPED) break
                        // RECONFIGURE: reopen with the new receiver settings.
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: UsbPermissionDenied) {
                        throw e
                    } catch (e: IOException) {
                        // A dongle that drops off the bus briefly: reopen a bounded number of times.
                        attempt++
                        if (attempt > MAX_RECONNECTS) throw e
                        _state.update { it.copy(status = "USB 재연결 중 ($attempt/$MAX_RECONNECTS) · ${e.message}") }
                        delay(1000)
                    } finally {
                        runCatching { rx?.close() }
                        runCatching { usb?.close() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString(), status = "중지됨 (오류)") }
            } finally {
                autoFitRequested = false
                _state.update { it.copy(running = false, autoFit = null,
                    status = if (it.error == null) "중지됨 · 화면은 마지막 측정값" else it.status) }
            }
        }
    }

    private enum class LoopEnd { STOPPED, RECONFIGURE }

    private suspend fun loop(rx: Receiver, onHealthy: () -> Unit): LoopEnd {
        val engine = SweepEngine(rx)
        val opened = _state.value.settings
        var applied: Settings? = null
        var plan: SweepPlan? = null
        val ctx = currentCoroutineContext()
        val fitTraces = ArrayList<Trace>()
        val history = ArrayList<Trace>()
        var reductions = 0
        while (ctx.isActive) {
            val s = _state.value.settings
            if (rx is RtlSdr) {
                if (s.narrowIf != opened.narrowIf) return LoopEnd.RECONFIGURE
                if (applied == null || applied.gainStep != s.gainStep || applied.fastTune != s.fastTune) {
                    rx.setGain(s.gainStep)
                    rx.fastTune = s.fastTune
                }
                rx.settleMs = s.settleMs
            }
            val p = SweepPlan.create(s.centerMhz * 1e6, s.spanMhz * 1e6, s.rbwKhz * 1e3, rx.sampleRate, s.dcShift)
            val req = diagRequest
            if (req != 0) {
                diagRequest = 0
                runDiag(req, rx, engine, s, p)
                applied = null // gain and tuning were touched: re-apply below
                continue
            }
            if (p != plan || baselineKeyChanged(applied, s)) {
                if (p != plan) _state.update { it.copy(live = null, last = null, hold = null, results = null) }
                plan = p
                history.clear()
                val base = store.loadBaseline(p, s)
                val internal = store.loadInternal(p, s)?.first
                _state.update { it.copy(baseline = base?.first, baselineTime = base?.second, internal = internal) }
            }
            applied = s
            val prev = _state.value.last
            val trace = engine.sweep(p, s.effectiveAverages(rx.sampleRate), s.dcPatch, s.iqMeanRemoval, onSegment = { part ->
                val upto = part.plan.segments[part.completedSegments - 1].let { it.firstPoint + it.count }
                val merged = if (prev != null && prev.plan == part.plan) {
                    val v = prev.levelsDb.copyOf()
                    System.arraycopy(part.levelsDb, 0, v, 0, upto)
                    part.withLevels(v)
                } else part
                _state.update { it.copy(live = merged, livePoints = upto) }
            }, isActive = { ctx.isActive }) ?: return LoopEnd.STOPPED
            onHealthy()
            val cur = _state.value
            if (cur.settings.captureKey() != s.captureKey()) continue // changed mid-sweep; discard
            val hold = if (cur.settings.maxHold) Analysis.maxHold(cur.hold, trace) else null
            history += trace
            if (history.size > 3) history.removeAt(0)
            if (cur.settings.waterfall) waterfall.add(trace)
            val results = Evaluate.run(cur.settings, trace, hold, cur.baseline, history.toList(), cur.internal)
            _state.update {
                val ref = if (it.sniff && it.sniffRefDb == null && !trace.clipped)
                    Analysis.peakFreq(trace)?.let { f -> Analysis.levelAt(trace, f, it.settings.offsetDb) } else it.sniffRefDb
                it.copy(sniffRefDb = ref, waterfallVersion = it.waterfallVersion + 1, live = trace, livePoints = trace.points, last = trace, hold = hold,
                    results = results, timing = trace.timing, sweeps = it.sweeps + 1, clipped = trace.clipped,
                    status = when {
                        trace.clipped -> "입력 클리핑 감지 · 이득을 낮추거나 감쇠기를 사용하세요"
                        trace.unlockedSegments > 0 -> "PLL 잠금 실패 ${trace.unlockedSegments}구간 · 해당 구간 미측정"
                        else -> "측정 중"
                    })
            }
            if (autoFitRequested) {
                fitTraces += trace
                if (fitTraces.size >= AutoFit.SWEEPS_PER_PASS) {
                    val d = AutoFit.decide(fitTraces, s.gainStep, s.offsetDb)
                    fitTraces.clear()
                    when {
                        d == null -> finishAutoFit("자동 맞춤 실패 · 유효한 스윕 없음")
                        d.lowerGain && reductions < AutoFit.MAX_GAIN_REDUCTIONS -> {
                            reductions++
                            commit(s.copy(gainStep = d.gainStep), clearTraces = true)
                            _state.update { it.copy(autoFit = "자동 맞춤 · 클리핑으로 이득 ${d.gainStep}/15로 낮춤 ($reductions/${AutoFit.MAX_GAIN_REDUCTIONS})") }
                        }
                        else -> {
                            commit(_state.value.settings.copy(refLevelDb = d.refLevelDb, dbPerDiv = d.dbPerDiv), clearTraces = false)
                            finishAutoFit(if (trace.clipped) "자동 맞춤 종료 · 입력 과다 지속, 감쇠기 확인 필요"
                                else "자동 맞춤 완료 · 이득 ${s.gainStep?.let { "$it/15" } ?: "AGC"} 고정")
                            reductions = 0
                        }
                    }
                }
            } else {
                fitTraces.clear(); reductions = 0
            }
        }
        return LoopEnd.STOPPED
    }

    private fun finishAutoFit(msg: String) {
        autoFitRequested = false
        _state.update { it.copy(autoFit = msg) }
    }

    /** Settings that change what is captured (not just how it is drawn). */
    private fun Settings.captureKey() = copy(refLevelDb = 0.0, dbPerDiv = 10.0, offsetDb = 0.0, thresholdDb = 0.0,
        maxHold = false, channelPower = false, band = null, mode = Mode.REVERSE, waterfall = false)

    /** Runs a diagnostic on the measurement thread with the open receiver. */
    private fun runDiag(req: Int, rx: Receiver, engine: SweepEngine, s: Settings, p: SweepPlan) {
        val setGain: ((Int?) -> Unit)? = (rx as? RtlSdr)?.let { r -> { g: Int? -> r.setGain(g) } }
        val demo = rx !is RtlSdr
        try {
            when (req) {
                1 -> {
                    val checks = SelfTest.run(rx, Math.round(s.centerMhz * 1e6), setGain, s.gainStep)
                    _state.update { it.copy(diag = it.diag.copy(busy = null, checks = checks, error = null, demo = demo)) }
                }
                2 -> {
                    val r = SettleProbe.run(rx, p, engine.discardSamples, setGain, s.gainStep)
                    _state.update { it.copy(diag = it.diag.copy(busy = null, settle = r, error = null, demo = demo)) }
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(diag = it.diag.copy(busy = null, error = e.message ?: e.toString())) }
        } finally {
            engine.invalidateTuning()
        }
    }

    fun requestSelfTest() = requestDiag(1, "자가점검 중…")
    fun requestSettleProbe() = requestDiag(2, "전환 안정 시간 측정 중…")

    private fun requestDiag(kind: Int, label: String) {
        if (!_state.value.running) {
            _state.update { it.copy(diag = it.diag.copy(error = "측정 중에만 실행할 수 있습니다 (▶ 측정 시작 후)")) }
            return
        }
        _state.update { it.copy(diag = it.diag.copy(busy = label, error = null)) }
        diagRequest = kind
    }

    private fun baselineKeyChanged(a: Settings?, b: Settings) =
        a == null || a.gainStep != b.gainStep || a.narrowIf != b.narrowIf || a.dcPatch != b.dcPatch ||
            a.iqMeanRemoval != b.iqMeanRemoval

    /** Double tap on the graph: fit Ref and dB/div to what is on screen. Gain is not touched. */
    fun autoScale() {
        val st = _state.value
        val t = st.shown ?: return
        val d = AutoFit.decide(listOf(t), null, st.settings.offsetDb) ?: return
        commit(st.settings.copy(refLevelDb = d.refLevelDb, dbPerDiv = d.dbPerDiv), clearTraces = false)
    }

    fun stop() {
        job?.cancel()
    }

    /** Lowers gain while the ADC clips (never raises it), then fits Ref and dB/div. */
    fun autoFit() {
        if (!_state.value.running) {
            _state.update { it.copy(autoFit = "측정 중에만 자동 맞춤을 할 수 있습니다") }
            return
        }
        autoFitRequested = true
        _state.update { it.copy(autoFit = "자동 맞춤 중 · 스윕 ${AutoFit.SWEEPS_PER_PASS}회 관측") }
    }

    // ---- settings ------------------------------------------------------------------

    fun apply(settings: Settings): String? {
        settings.validate()?.let { return it }
        commit(settings, clearTraces = false)
        return null
    }

    private fun commit(settings: Settings, clearTraces: Boolean) {
        store.saveSettings(settings)
        _state.update {
            val old = it.settings
            val levelsChanged = clearTraces || old.gainStep != settings.gainStep || old.narrowIf != settings.narrowIf ||
                old.dcPatch != settings.dcPatch || old.iqMeanRemoval != settings.iqMeanRemoval ||
                old.maxHold != settings.maxHold
            it.copy(settings = settings, hold = if (levelsChanged) null else it.hold,
                results = if (old.mode != settings.mode) null else it.results)
        }
    }

    fun selectMode(mode: Mode) = apply(_state.value.settings.withProfile(mode, _state.value.settings.band))

    fun selectBand(band: Band) = apply(_state.value.settings.withProfile(_state.value.settings.mode, band))

    fun stepGain(delta: Int) {
        val s = _state.value.settings
        val g = s.gainStep ?: return
        val next = (g + delta).coerceIn(0, Settings.MAX_GAIN_STEP)
        if (next != g) commit(s.copy(gainStep = next), clearTraces = true)
    }

    /** One-finger vertical drag on the graph: moves Ref without touching the capture. */
    fun dragRef(deltaDb: Double) {
        if (!deltaDb.isFinite()) return
        _state.update { it.copy(settings = it.settings.copy(refLevelDb = (it.settings.refLevelDb + deltaDb).coerceIn(-200.0, 100.0))) }
    }

    /** End of a Ref drag: snap to whole dB and persist. */
    fun commitRef() {
        _state.update { it.copy(settings = it.settings.copy(refLevelDb = Math.round(it.settings.refLevelDb).toDouble())) }
        store.saveSettings(_state.value.settings)
    }

    /** Pinch: span / factor around the same centre, inside the receiver range. */
    fun zoomSpan(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val s = _state.value.settings
        val span = (s.spanMhz / factor).coerceIn(0.05, s.maxSpanAtCenter())
        if (kotlin.math.abs(span - s.spanMhz) < 0.001) return
        apply(s.copy(spanMhz = String.format(Locale.US, "%.3f", span).toDouble()))
    }

    fun toggleHold() {
        val s = _state.value.settings
        commit(s.copy(maxHold = !s.maxHold), clearTraces = false)
    }

    fun toggleChannelPower() {
        val s = _state.value.settings
        commit(s.copy(channelPower = !s.channelPower), clearTraces = false)
    }

    fun resetHold() = _state.update { it.copy(hold = null) }

    fun toggleWaterfall() {
        val s = _state.value.settings
        if (s.waterfall) waterfall.clear()
        commit(s.copy(waterfall = !s.waterfall), clearTraces = false)
    }

    /** Settings before a tap-zoom, restored by [unzoom]. */
    private var beforeZoom: Settings? = null

    /**
     * Long press on the graph: one capture (about 0.8-0.9 MHz) centred on the
     * strongest point near the press, with the receiver's DC bin outside the
     * span, so it refreshes without any retune. Again (or [unzoom]) goes back.
     */
    fun zoomAt(freqHz: Double) {
        val st = _state.value
        if (st.zoomed) { unzoom(); return }
        val s = st.settings
        val f = st.shown?.let { snapToPeak(it, freqHz, 300e3) } ?: freqHz
        val rbwHz = minOf(s.rbwActualHz(), 13_500.0)
        val span = SweepPlan.zoomSpanHz(rbwHz) / 1e6
        val c = (f / 1e6).coerceIn(24.0 + span, 1766.0 - span)
        beforeZoom = s
        commit(s.copy(centerMhz = c, spanMhz = span, rbwKhz = rbwHz / 1e3, dcShift = true), clearTraces = true)
        _state.update { it.copy(zoomed = true) }
    }

    fun unzoom() {
        beforeZoom?.let { commit(it, clearTraces = true) }
        beforeZoom = null
        _state.update { it.copy(zoomed = false) }
    }

    private fun snapToPeak(t: Trace, f: Double, radiusHz: Double): Double? {
        var best = -1
        for (i in 0 until t.points) {
            if (kotlin.math.abs(t.freqAt(i) - f) > radiusHz || !t.levelsDb[i].isFinite()) continue
            if (best < 0 || t.levelsDb[i] > t.levelsDb[best]) best = i
        }
        return if (best < 0) null else t.freqAt(best)
    }

    fun toggleSniffSound() = _state.update { it.copy(sniffSound = !it.sniffSound) }

    /** Settings in use before near-field sniffing started, restored when it ends. */
    private var beforeSniff: Settings? = null

    /**
     * Near-field sniffing like a handheld analyzer held at the connectors: the
     * RX channel (at least 10 MHz) at the widest RBW, VBW = RBW (2 frames, no
     * smoothing so bursts show), no Max Hold, high gain. Turning it off
     * restores the previous settings.
     */
    fun toggleSniff() {
        if (_state.value.zoomed) unzoom()
        val st = _state.value
        if (st.sniff) {
            beforeSniff?.let { commit(it, clearTraces = true) }
            beforeSniff = null
            _state.update { it.copy(sniff = false, sniffRefDb = null) }
            return
        }
        beforeSniff = st.settings
        val b = st.settings.band ?: Band.B8
        val span = maxOf(10.0, b.channelBwMhz).coerceAtMost(Settings(centerMhz = b.rxCenterMhz).maxSpanAtCenter())
        val s = st.settings.withProfile(Mode.SPURIOUS, b).copy(centerMhz = b.rxCenterMhz, spanMhz = span,
            rbwKhz = 54.0, vbwKhz = null, averages = 2, maxHold = false, gainStep = 12, thresholdDb = 10.0)
        commit(s, clearTraces = true)
        _state.update { it.copy(sniff = true, sniffRefDb = null) }
    }

    /** Take the current peak as the new starting level (e.g. at a known-good spot). */
    fun resetSniffRef() = _state.update { it.copy(sniffRefDb = it.peakNow?.first) }

    /** Moves the segment centres (and back) to tell DC residue from a real signal at a DC bin. */
    fun toggleDcShift() {
        val s = _state.value.settings
        commit(s.copy(dcShift = !s.dcShift), clearTraces = false)
    }

    // ---- markers -------------------------------------------------------------------

    fun selectMarker(index: Int) = _state.update { it.copy(selectedMarker = index) }

    fun placeMarker(freqHz: Double) = _state.update { st ->
        st.copy(markers = st.markers.map { if (it.index == st.selectedMarker) it.copy(freqHz = freqHz) else it })
    }

    fun markerToPeak() {
        val t = _state.value.shown ?: return
        Analysis.peakFreq(t)?.let { placeMarker(it) }
    }

    fun clearMarker() = _state.update { st ->
        st.copy(markers = st.markers.map { if (it.index == st.selectedMarker) Marker(it.index) else it })
    }

    // ---- presets & baseline --------------------------------------------------------

    fun savePreset(mode: Mode) {
        store.savePreset(mode, _state.value.settings)
        _state.update { it.copy(presets = presetSet(), status = "${mode.title} 빠른 설정 저장됨") }
    }

    fun loadPreset(mode: Mode): String? {
        val p = store.loadPreset(mode) ?: return "${mode.title} 저장값이 없습니다"
        commit(p, clearTraces = true)
        return null
    }

    fun clearPreset(mode: Mode) {
        store.clearPreset(mode)
        _state.update { it.copy(presets = presetSet()) }
    }

    fun saveBaseline() {
        val st = _state.value
        val t = st.last ?: return
        if (t.clipped) {
            _state.update { it.copy(status = "클리핑된 스윕은 기준으로 저장할 수 없습니다") }
            return
        }
        if (!t.complete || t.unlockedSegments > 0) {
            _state.update { it.copy(status = "미측정 구간이 있는 스윕은 기준으로 저장할 수 없습니다") }
            return
        }
        store.saveBaseline(t, st.settings)
        _state.update { it.copy(baseline = t, baselineTime = System.currentTimeMillis(), status = "기준 저장됨") }
    }

    fun clearBaseline() {
        val st = _state.value
        val t = st.last ?: st.baseline ?: return
        store.deleteBaseline(t.plan, st.settings)
        _state.update { it.copy(baseline = null, baselineTime = null) }
    }

    /**
     * Records the current sweep as the dongle's own spurs. Do this with the
     * antenna disconnected (or a 50 ohm load) at the same span, RBW and gain.
     */
    fun recordInternal() {
        val st = _state.value
        val t = st.last ?: run { _state.update { it.copy(status = "완료된 스윕이 없습니다") }; return }
        if (t.clipped || !t.complete || t.unlockedSegments > 0) {
            _state.update { it.copy(status = "클리핑·미측정 구간이 있는 스윕은 기록할 수 없습니다") }
            return
        }
        store.saveInternal(t, st.settings)
        _state.update { it.copy(internal = t, status = "무입력 기록 저장됨 (같은 Span·RBW·이득에서 피크에 표시만, 제외 안 함)") }
    }

    fun clearInternal() {
        val st = _state.value
        val t = st.last ?: st.internal ?: return
        store.deleteInternal(t.plan, st.settings)
        _state.update { it.copy(internal = null, status = "동글 자체 신호 기록 삭제") }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // ---- export --------------------------------------------------------------------

    fun csvFileName(): String {
        val st = _state.value
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date(st.last?.timestampMs ?: 0L))
        return "rxcheck_${st.settings.mode.name.lowercase()}_${st.settings.band?.name ?: "custom"}_$stamp.csv"
    }

    /** CSV of the last completed sweep with hold/baseline, markers and results. */
    fun csvText(): String? {
        val st = _state.value
        val t = st.last ?: return null
        val s = st.settings
        return buildString {
            append("# RX 점검; mode=${s.mode.title}; band=${s.band?.label ?: "-"}; center_mhz=${s.centerMhz}; span_mhz=${s.spanMhz}; " +
                "channel_bw_mhz=${s.channelBwMhz}; rbw_hz=${fmt("%.0f", 1.44 * t.plan.binHz)}; enbw_hz=${fmt("%.0f", t.enbwHz)}; " +
                "averages=${s.effectiveAverages()}; vbw_hz=${fmt("%.0f", s.vbwActualHz())}; vbw_setting=${s.vbwKhz?.let { "${it}k" } ?: "AUTO"}; gain_step=${s.gainStep ?: "AGC"}; if_filter=${if (s.narrowIf) "narrow" else "6MHz"}; " +
                "fast_tune=${s.fastTune}; settle_ms=${s.settleMs}; offset_db=${s.offsetDb}; clipping=${fmt("%.5f", t.clippedFraction)}; " +
                "sweep_ms=${t.timing?.totalMs ?: ""}; device=${st.device ?: ""}; unit=dBFS+offset (상대값)\n")
            st.results?.let { r ->
                append("# status=${r.status.validity.name}; title=${r.status.title}; notes=${r.status.notes.joinToString(" | ")}\n")
                append("# processing; iq_mean_removal=${t.meanRemoved}; dc_patch=${t.dcPatched}; dc_shift=${s.dcShift}; " +
                    "unlocked_segments=${t.unlockedSegments}; unmeasured_points=${t.missingPoints}\n")
                val ch = r.channel
                if (ch != null) append("# channel_power_db=${fmt("%.2f", ch.totalDb)}; psd_db_per_mhz=${fmt("%.2f", ch.psdDbPerMhz)}\n")
                else append("# channel_power_db=UNMEASURED; reason=${r.channelNote ?: ""}\n")
                r.correctedChannel?.let { append("# channel_power_corrected_db=${fmt("%.2f", it.totalDb)} (internal-spur bins replaced; not raw)\n") }
                append("# rise_vs_baseline_db=${r.riseDb?.let { fmt("%.2f", it) } ?: if (st.baseline != null) "UNMEASURED" else "NO_BASELINE"}\n")
                r.correctedRiseDb?.let { append("# rise_vs_baseline_corrected_db=${fmt("%.2f", it)}\n") }
                fun v(x: Double?) = x?.let { fmt("%.2f", it) } ?: "UNMEASURED"
                for (b in r.blocks) append("# block ${fmt("%.3f", b.startHz / 1e6)}-${fmt("%.3f", b.stopHz / 1e6)} MHz psd=${v(b.psdDbPerMhz)} " +
                    "above_median=${v(b.aboveMedianDb)} rise=${b.riseDb?.let { fmt("%.2f", it) } ?: if (st.baseline != null) "UNMEASURED" else "-"} " +
                    "missing_bins=${b.missingBins} baseline_missing_bins=${b.baselineMissingBins}\n")
                for (p in r.peaks) append("# peak ${fmt("%.4f", p.freqHz / 1e6)} MHz level=${fmt("%.2f", p.levelDb)} above_floor=${fmt("%.2f", p.aboveFloorDb)} " +
                    "bw10=${fmt("%.0f", p.bw10dBHz)} in_channel=${p.inChannel} source=${p.source.name} seen_sweeps=${p.seenSweeps} " +
                    "xtal_harmonic=${p.xtalHarmonic} in_no_input_record=${p.inNoInputRecord} at_dc=${p.atDc}\n")
            }
            val shown = st.shown
            for (m in st.markers) m.freqHz?.let { f ->
                val v = shown?.let { Analysis.levelAt(it, f, s.offsetDb) }
                append("# marker M${m.index} ${fmt("%.4f", f / 1e6)} MHz ${v?.let { fmt("%.2f", it) } ?: ""}\n")
            }
            append("freq_mhz,level_db,max_hold_db,baseline_db,frame_peak_db,point_status\n")
            val hold = st.hold?.takeIf { it.plan == t.plan }
            val base = st.baseline?.takeIf { it.plan == t.plan }
            fun f(v: Float?) = if (v == null || !v.isFinite()) "" else fmt("%.2f", v + s.offsetDb)
            val dc = t.plan.dcPoints().toHashSet()
            val fp = t.framePeakDb
            // Empty level = no value; point_status says why, so a gap never reads as a low level.
            for (i in 0 until t.points) {
                val status = when {
                    !t.levelsDb[i].isFinite() -> "UNMEASURED"
                    t.clipped -> "CLIPPED"
                    i in dc -> if (t.dcPatched) "DC_PATCHED" else "DC_BIN"
                    else -> "OK"
                }
                append(fmt("%.6f,%s,%s,%s,%s,%s\n", t.freqAt(i) / 1e6, f(t.levelsDb[i]), f(hold?.levelsDb?.get(i)),
                    f(base?.levelsDb?.get(i)), f(fp?.get(i)), status))
            }
        }
    }

    /** Same CSV written to the cache for the share sheet. */
    fun csvFile(): File? {
        val text = csvText() ?: return null
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        return File(dir, csvFileName()).apply { writeText(text) }
    }

    private fun fmt(pattern: String, vararg args: Any?) = String.format(Locale.US, pattern, *args)

    companion object {
        private const val MAX_RECONNECTS = 5
    }
}
