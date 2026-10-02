package com.lteduenv.rxcheck.data

import android.content.Context
import com.lteduenv.rxcheck.core.model.Band
import com.lteduenv.rxcheck.core.model.Mode
import com.lteduenv.rxcheck.core.model.Settings
import com.lteduenv.rxcheck.core.sweep.SweepPlan
import com.lteduenv.rxcheck.core.sweep.Trace
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Settings in SharedPreferences; baselines as small binary files keyed by sweep plan. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("rxcheck", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "baselines").apply { mkdirs() }

    fun loadSettings(): Settings {
        val d = Settings()
        if (!prefs.contains("mode")) return d
        return runCatching {
            Settings(
                mode = Mode.valueOf(prefs.getString("mode", d.mode.name)!!),
                band = prefs.getString("band", null)?.let { b -> Band.values().firstOrNull { it.name == b } },
                centerMhz = prefs.getFloat("center", d.centerMhz.toFloat()).toDouble(),
                spanMhz = prefs.getFloat("span", d.spanMhz.toFloat()).toDouble(),
                channelBwMhz = prefs.getFloat("chbw", d.channelBwMhz.toFloat()).toDouble(),
                rbwKhz = prefs.getFloat("rbw", d.rbwKhz.toFloat()).toDouble(),
                averages = prefs.getInt("avg", d.averages),
                gainStep = prefs.getInt("gain", d.gainStep ?: -1).takeIf { it >= 0 },
                offsetDb = prefs.getFloat("offset", 0f).toDouble(),
                maxHold = prefs.getBoolean("hold", d.maxHold),
                thresholdDb = prefs.getFloat("thr", d.thresholdDb.toFloat()).toDouble(),
                fastTune = prefs.getBoolean("fast", true),
                dcPatch = prefs.getBoolean("dc", false),
                refLevelDb = prefs.getFloat("ref", d.refLevelDb.toFloat()).toDouble(),
                dbPerDiv = prefs.getFloat("div", d.dbPerDiv.toFloat()).toDouble(),
            ).takeIf { it.validate() == null } ?: d
        }.getOrDefault(d)
    }

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("mode", s.mode.name).putString("band", s.band?.name)
            .putFloat("center", s.centerMhz.toFloat()).putFloat("span", s.spanMhz.toFloat())
            .putFloat("chbw", s.channelBwMhz.toFloat()).putFloat("rbw", s.rbwKhz.toFloat())
            .putInt("avg", s.averages).putInt("gain", s.gainStep ?: -1)
            .putFloat("offset", s.offsetDb.toFloat()).putBoolean("hold", s.maxHold)
            .putFloat("thr", s.thresholdDb.toFloat()).putBoolean("fast", s.fastTune)
            .putBoolean("dc", s.dcPatch).putFloat("ref", s.refLevelDb.toFloat())
            .putFloat("div", s.dbPerDiv.toFloat())
            .apply()
    }

    private fun key(p: SweepPlan) =
        "%d_%d_%d_%d".format(p.startHz.toLong(), p.binHz.toLong(), p.points, p.sampleRate)

    fun saveBaseline(t: Trace) {
        DataOutputStream(File(dir, key(t.plan)).outputStream().buffered()).use { out ->
            out.writeLong(System.currentTimeMillis())
            out.writeDouble(t.enbwHz)
            out.writeInt(t.points)
            for (v in t.levelsDb) out.writeFloat(v)
        }
    }

    /** Baseline for exactly this plan (same span, RBW and sample rate), or null. */
    fun loadBaseline(plan: SweepPlan): Pair<Trace, Long>? {
        val f = File(dir, key(plan))
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

    fun deleteBaseline(plan: SweepPlan) { File(dir, key(plan)).delete() }
}
