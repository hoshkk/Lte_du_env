package com.lteduenv.rxcheck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lteduenv.rxcheck.core.analysis.Analysis
import com.lteduenv.rxcheck.core.analysis.AutoFit
import com.lteduenv.rxcheck.core.analysis.Evaluate
import com.lteduenv.rxcheck.core.analysis.Results
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
) {
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
            val p = SweepPlan.create(s.centerMhz * 1e6, s.spanMhz * 1e6, s.rbwKhz * 1e3, rx.sampleRate)
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
            val trace = engine.sweep(p, s.averages, s.dcPatch, onSegment = { part ->
                val upto = part.plan.segments[part.completedSegments - 1].let { it.firstPoint + it.count }
                val merged = if (prev != null && prev.plan == part.plan) {
                    val v = prev.levelsDb.copyOf()
                    System.arraycopy(part.levelsDb, 0, v, 0, upto)
                    Trace(part.plan, v, part.enbwHz, part.completedSegments, part.unlockedSegments, null,
                        part.timestampMs, part.clippedFraction)
                } else part
                _state.update { it.copy(live = merged, livePoints = upto) }
            }, isActive = { ctx.isActive }) ?: return LoopEnd.STOPPED
            onHealthy()
            val cur = _state.value
            if (cur.settings.captureKey() != s.captureKey()) continue // changed mid-sweep; discard
            val hold = if (cur.settings.maxHold) Analysis.maxHold(cur.hold, trace) else null
            history += trace
            if (history.size > 3) history.removeAt(0)
            val results = Evaluate.run(cur.settings, trace, hold, cur.baseline, history.toList(), cur.internal)
            _state.update {
                it.copy(live = trace, livePoints = trace.points, last = trace, hold = hold,
                    results = results, timing = trace.timing, sweeps = it.sweeps + 1, clipped = trace.clipped,
                    status = when {
                        trace.clipped -> "입력 클리핑 감지 · 이득을 낮추거나 감쇠기를 사용하세요"
                        trace.unlockedSegments > 0 -> "PLL 미잠금 구간 ${trace.unlockedSegments}개 (해당 구간 제외)"
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
        maxHold = false, channelPower = false, band = null, mode = Mode.REVERSE)

    private fun baselineKeyChanged(a: Settings?, b: Settings) =
        a == null || a.gainStep != b.gainStep || a.narrowIf != b.narrowIf || a.dcPatch != b.dcPatch

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
                old.dcPatch != settings.dcPatch || old.maxHold != settings.maxHold
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
        store.saveInternal(t, st.settings)
        _state.update { it.copy(internal = t, status = "동글 자체 신호 기록됨 (같은 Span·RBW·이득에서 적용)") }
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
                "averages=${s.averages}; gain_step=${s.gainStep ?: "AGC"}; if_filter=${if (s.narrowIf) "narrow" else "6MHz"}; " +
                "fast_tune=${s.fastTune}; settle_ms=${s.settleMs}; offset_db=${s.offsetDb}; clipping=${fmt("%.5f", t.clippedFraction)}; " +
                "sweep_ms=${t.timing?.totalMs ?: ""}; device=${st.device ?: ""}; unit=dBFS+offset (상대값)\n")
            st.results?.let { r ->
                append("# verdict=${r.verdict.title}; reasons=${r.verdict.reasons.joinToString(" | ")}\n")
                r.channel?.let { append("# channel_power_db=${fmt("%.2f", it.totalDb)}; psd_db_per_mhz=${fmt("%.2f", it.psdDbPerMhz)}\n") }
                r.riseDb?.let { append("# rise_vs_baseline_db=${fmt("%.2f", it)}\n") }
                for (b in r.blocks) append("# block ${fmt("%.3f", b.startHz / 1e6)}-${fmt("%.3f", b.stopHz / 1e6)} MHz psd=${fmt("%.2f", b.psdDbPerMhz)} above_median=${fmt("%.2f", b.aboveMedianDb)}" +
                    (b.riseDb?.let { " rise=${fmt("%.2f", it)}" } ?: "") + "\n")
                for (p in r.peaks) append("# peak ${fmt("%.4f", p.freqHz / 1e6)} MHz level=${fmt("%.2f", p.levelDb)} above_floor=${fmt("%.2f", p.aboveFloorDb)} bw10=${fmt("%.0f", p.bw10dBHz)} in_channel=${p.inChannel} origin=${p.origin.name} seen=${p.seenSweeps}\n")
            }
            val shown = st.shown
            for (m in st.markers) m.freqHz?.let { f ->
                val v = shown?.let { Analysis.levelAt(it, f, s.offsetDb) }
                append("# marker M${m.index} ${fmt("%.4f", f / 1e6)} MHz ${v?.let { fmt("%.2f", it) } ?: ""}\n")
            }
            append("freq_mhz,level_db,max_hold_db,baseline_db\n")
            val hold = st.hold?.takeIf { it.plan == t.plan }
            val base = st.baseline?.takeIf { it.plan == t.plan }
            fun f(v: Float?) = if (v == null || !v.isFinite()) "" else fmt("%.2f", v + s.offsetDb)
            for (i in 0 until t.points)
                append(fmt("%.6f,%s,%s,%s\n", t.freqAt(i) / 1e6, f(t.levelsDb[i]), f(hold?.levelsDb?.get(i)), f(base?.levelsDb?.get(i))))
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
