package com.lteduenv.rxcheck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lteduenv.rxcheck.core.analysis.Analysis
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
import com.lteduenv.rxcheck.data.Store
import com.lteduenv.rxcheck.usb.UsbAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
    val results: Results? = null,
    val timing: SweepTiming? = null,
    val sweeps: Int = 0,
    val error: String? = null,
)

class MeasureViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val _state = MutableStateFlow(UiState(store.loadSettings()))
    val state: StateFlow<UiState> = _state
    private var job: Job? = null

    fun start(demo: Boolean) {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true, demo = demo, error = null, status = "연결 중…") }
        job = viewModelScope.launch(Dispatchers.IO) {
            var usb: AutoCloseable? = null
            var rx: Receiver? = null
            try {
                val s0 = _state.value.settings
                val r: Receiver = if (demo) SimReceiver(tuneDelayMs = 4) else {
                    val io = UsbAccess.open(getApplication<Application>())
                    usb = io
                    RtlSdr.open(io, fastTune = s0.fastTune, gainStep = s0.gainStep)
                }
                rx = r
                _state.update { it.copy(device = r.description, status = "측정 중") }
                loop(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString(), status = "중지됨 (오류)") }
            } finally {
                runCatching { rx?.close() }
                runCatching { usb?.close() }
                _state.update { it.copy(running = false, status = if (it.error == null) "중지됨" else it.status) }
            }
        }
    }

    private suspend fun loop(rx: Receiver) {
        val engine = SweepEngine(rx)
        var applied: Settings? = null
        var plan: SweepPlan? = null
        val scope = kotlinx.coroutines.currentCoroutineContext()
        while (scope.isActive) {
            val s = _state.value.settings
            if (rx is RtlSdr && (applied == null || applied.gainStep != s.gainStep || applied.fastTune != s.fastTune)) {
                rx.setGain(s.gainStep)
                rx.fastTune = s.fastTune
            }
            val p = SweepPlan.create(s.centerMhz * 1e6, s.spanMhz * 1e6, s.rbwKhz * 1e3, rx.sampleRate)
            if (p != plan) {
                plan = p
                val base = store.loadBaseline(p)
                _state.update { it.copy(live = null, last = null, hold = null, results = null,
                    baseline = base?.first, baselineTime = base?.second) }
            }
            applied = s
            val prev = _state.value.last
            val trace = engine.sweep(p, s.averages, s.dcPatch, onSegment = { part ->
                val merged = if (prev != null && prev.plan == part.plan) {
                    val v = prev.levelsDb.copyOf()
                    val upto = part.plan.segments[part.completedSegments - 1].let { it.firstPoint + it.count }
                    System.arraycopy(part.levelsDb, 0, v, 0, upto)
                    Trace(part.plan, v, part.enbwHz, part.completedSegments, part.unlockedSegments, null, part.timestampMs)
                } else part
                val upto = part.plan.segments[part.completedSegments - 1].let { it.firstPoint + it.count }
                _state.update { it.copy(live = merged, livePoints = upto) }
            }, isActive = { scope.isActive }) ?: break
            val cur = _state.value
            if (cur.settings != s) continue // settings changed mid-sweep; discard
            val hold = if (s.maxHold) Analysis.maxHold(cur.hold, trace) else null
            val results = Evaluate.run(s, trace, hold, cur.baseline)
            _state.update {
                it.copy(live = trace, livePoints = trace.points, last = trace, hold = hold,
                    results = results, timing = trace.timing, sweeps = it.sweeps + 1,
                    status = if (trace.unlockedSegments > 0) "PLL 미잠금 구간 ${trace.unlockedSegments}개 (해당 구간 제외)" else "측정 중")
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun apply(settings: Settings): String? {
        settings.validate()?.let { return it }
        store.saveSettings(settings)
        _state.update {
            val holdReset = it.settings.maxHold != settings.maxHold || it.settings.offsetDb != settings.offsetDb
            it.copy(settings = settings, hold = if (holdReset) null else it.hold)
        }
        return null
    }

    fun selectMode(mode: Mode) = apply(_state.value.settings.withProfile(mode, _state.value.settings.band))

    fun selectBand(band: Band) = apply(_state.value.settings.withProfile(_state.value.settings.mode, band))

    fun resetHold() = _state.update { it.copy(hold = null) }

    fun saveBaseline() {
        val t = _state.value.last ?: return
        store.saveBaseline(t)
        _state.update { it.copy(baseline = t, baselineTime = System.currentTimeMillis(), status = "기준 저장됨") }
    }

    fun clearBaseline() {
        val t = _state.value.last ?: _state.value.baseline ?: return
        store.deleteBaseline(t.plan)
        _state.update { it.copy(baseline = null, baselineTime = null) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Writes the last completed sweep (with hold/baseline) to a CSV in the cache. */
    fun exportCsv(): File? {
        val st = _state.value
        val t = st.last ?: return null
        val s = st.settings
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(t.timestampMs))
        val file = File(dir, "rxcheck_${s.mode.name.lowercase()}_${s.band?.name ?: "custom"}_$stamp.csv")
        file.bufferedWriter().use { w ->
            w.write("# mode=${s.mode.title}; band=${s.band?.label ?: "-"}; center_mhz=${s.centerMhz}; span_mhz=${s.spanMhz}; " +
                "channel_bw_mhz=${s.channelBwMhz}; rbw_hz=${fmt("%.0f", 1.44 * t.plan.binHz)}; enbw_hz=${fmt("%.0f", t.enbwHz)}; " +
                "averages=${s.averages}; gain_step=${s.gainStep ?: "AGC"}; offset_db=${s.offsetDb}; unit=dBFS+offset (상대값)\n")
            st.results?.let { r ->
                r.channel?.let { w.write("# channel_power_db=${fmt("%.2f", it.totalDb)}; psd_db_per_mhz=${fmt("%.2f", it.psdDbPerMhz)}\n") }
                r.riseDb?.let { w.write("# rise_vs_baseline_db=${fmt("%.2f", it)}\n") }
                for (b in r.blocks) w.write("# block ${fmt("%.3f", b.startHz / 1e6)}-${fmt("%.3f", b.stopHz / 1e6)} MHz psd=${fmt("%.2f", b.psdDbPerMhz)} above_median=${fmt("%.2f", b.aboveMedianDb)}" +
                    (b.riseDb?.let { " rise=${fmt("%.2f", it)}" } ?: "") + "\n")
                for (p in r.peaks) w.write("# peak ${fmt("%.4f", p.freqHz / 1e6)} MHz level=${fmt("%.2f", p.levelDb)} above_floor=${fmt("%.2f", p.aboveFloorDb)} bw10=${fmt("%.0f", p.bw10dBHz)} in_channel=${p.inChannel}\n")
            }
            w.write("freq_mhz,level_db,max_hold_db,baseline_db\n")
            val hold = st.hold?.takeIf { it.plan == t.plan }
            val base = st.baseline?.takeIf { it.plan == t.plan }
            for (i in 0 until t.points) {
                fun f(v: Float?) = if (v == null || !v.isFinite()) "" else fmt("%.2f", v + s.offsetDb)
                w.write(fmt("%.6f,%s,%s,%s\n", t.freqAt(i) / 1e6, f(t.levelsDb[i]), f(hold?.levelsDb?.get(i)), f(base?.levelsDb?.get(i))))
            }
        }
        return file
    }

    private fun fmt(pattern: String, vararg args: Any?) = String.format(java.util.Locale.US, pattern, *args)
}
