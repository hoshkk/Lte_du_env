package com.lteduenv.rxcheck.data

import android.content.Context
import android.content.SharedPreferences
import com.lteduenv.rxcheck.core.model.Band
import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import com.lteduenv.rxcheck.core.sweep.Trace
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Settings and per-mode quick presets in SharedPreferences; baselines as small
 * binary files keyed by everything that changes the measured levels.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("rxcheck", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "baselines").apply { mkdirs() }

    fun loadSettings(): Settings = read("") ?: Settings()

    fun saveSettings(s: Settings) = write("", s)

    fun hasPreset(mode: Mode) = prefs.contains(presetPrefix(mode) + "mode")
    fun savePreset(mode: Mode, s: Settings) = write(presetPrefix(mode), s.copy(mode = mode))
    fun loadPreset(mode: Mode): Settings? = read(presetPrefix(mode))
    fun clearPreset(mode: Mode) {
        val p = presetPrefix(mode)
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith(p) }.forEach { remove(it) } }.apply()
    }

    private fun presetPrefix(mode: Mode) = "preset_${mode.name}_"

    private fun read(p: String): Settings? {
        if (!prefs.contains(p + "mode")) return null
        val d = Settings()
        return runCatching {
            Settings(
                mode = Mode.valueOf(prefs.getString(p + "mode", d.mode.name)!!),
                band = prefs.getString(p + "band", null)?.let { b -> Band.values().firstOrNull { it.name == b } },
                centerMhz = prefs.double(p + "center", d.centerMhz),
                spanMhz = prefs.double(p + "span", d.spanMhz),
                channelBwMhz = prefs.double(p + "chbw", d.channelBwMhz),
                rbwKhz = prefs.double(p + "rbw", d.rbwKhz),
                averages = prefs.getInt(p + "avg", d.averages),
                gainStep = prefs.getInt(p + "gain", d.gainStep ?: -1).takeIf { it >= 0 },
                offsetDb = prefs.double(p + "offset", 0.0),
                maxHold = prefs.getBoolean(p + "hold", d.maxHold),
                thresholdDb = prefs.double(p + "thr", d.thresholdDb),
                fastTune = prefs.getBoolean(p + "fast", d.fastTune),
                narrowIf = prefs.getBoolean(p + "narrowif", d.narrowIf),
                settleMs = prefs.getInt(p + "settle", d.settleMs),
                channelPower = prefs.getBoolean(p + "chpow", d.channelPower),
                dcPatch = prefs.getBoolean(p + "dc", d.dcPatch),
                iqMeanRemoval = prefs.getBoolean(p + "iqmean", d.iqMeanRemoval),
                dcShift = prefs.getBoolean(p + "dcshift", d.dcShift),
                internalCorrection = prefs.getBoolean(p + "corr", d.internalCorrection),
                refLevelDb = prefs.double(p + "ref", d.refLevelDb),
                dbPerDiv = prefs.double(p + "div", d.dbPerDiv),
            ).takeIf { it.validate() == null }
        }.getOrNull()
    }

    private fun write(p: String, s: Settings) {
        prefs.edit()
            .putString(p + "mode", s.mode.name).putString(p + "band", s.band?.name)
            .putString(p + "center", s.centerMhz.toString()).putString(p + "span", s.spanMhz.toString())
            .putString(p + "chbw", s.channelBwMhz.toString()).putString(p + "rbw", s.rbwKhz.toString())
            .putInt(p + "avg", s.averages).putInt(p + "gain", s.gainStep ?: -1)
            .putString(p + "offset", s.offsetDb.toString()).putBoolean(p + "hold", s.maxHold)
            .putString(p + "thr", s.thresholdDb.toString()).putBoolean(p + "fast", s.fastTune)
            .putBoolean(p + "narrowif", s.narrowIf).putInt(p + "settle", s.settleMs)
            .putBoolean(p + "chpow", s.channelPower).putBoolean(p + "dc", s.dcPatch)
            .putBoolean(p + "iqmean", s.iqMeanRemoval).putBoolean(p + "dcshift", s.dcShift)
            .putBoolean(p + "corr", s.internalCorrection)
            .putString(p + "ref", s.refLevelDb.toString()).putString(p + "div", s.dbPerDiv.toString())
            .apply()
    }

    private fun SharedPreferences.double(key: String, def: Double): Double =
        runCatching { getString(key, null)?.toDouble() }.getOrNull() ?: def

    /**
     * Everything that shifts raw levels must match for a baseline to be comparable.
     * Files from 1.3.1 and earlier had I/Q mean removal always on and no suffix;
     * "_m0" marks recordings without it.
     */
    private fun key(p: SweepPlan, s: Settings) =
        "%d_%d_%d_%d_g%s_if%s_dc%s%s".format(p.startHz.toLong(), p.binHz.toLong(), p.points, p.sampleRate,
            s.gainStep?.toString() ?: "agc", if (s.narrowIf) "n" else "w", if (s.dcPatch) "1" else "0",
            if (s.iqMeanRemoval) "" else "_m0")

    fun saveBaseline(t: Trace, s: Settings) = saveTrace("", t, s)
    fun loadBaseline(plan: SweepPlan, s: Settings) = loadTrace("", plan, s)
    fun deleteBaseline(plan: SweepPlan, s: Settings) { File(dir, key(plan, s)).delete() }

    /** No-input recording of the dongle's own spurs (antenna removed or 50 ohm load). */
    fun saveInternal(t: Trace, s: Settings) = saveTrace(INTERNAL, t, s)
    fun loadInternal(plan: SweepPlan, s: Settings) = loadTrace(INTERNAL, plan, s)
    fun deleteInternal(plan: SweepPlan, s: Settings) { File(dir, INTERNAL + key(plan, s)).delete() }

    private fun saveTrace(prefix: String, t: Trace, s: Settings) {
        DataOutputStream(File(dir, prefix + key(t.plan, s)).outputStream().buffered()).use { out ->
            out.writeLong(System.currentTimeMillis())
            out.writeDouble(t.enbwHz)
            out.writeInt(t.points)
            for (v in t.levelsDb) out.writeFloat(v)
        }
    }

    /** Trace stored for exactly this plan and level-affecting settings, or null. */
    private fun loadTrace(prefix: String, plan: SweepPlan, s: Settings): Pair<Trace, Long>? {
        val f = File(dir, prefix + key(plan, s))
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                val time = inp.readLong()
                val enbw = inp.readDouble()
                val n = inp.readInt()
                if (n != plan.points) return null
                val v = FloatArray(n) { inp.readFloat() }
                Trace(plan, v, enbw, plan.segments.size, 0, null, time) to time
            }
        }.getOrNull()
    }

    private companion object {
        const val INTERNAL = "internal_"
    }
}
