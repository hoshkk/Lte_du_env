package com.lteduenv.rxcheck.core.sweep

import com.lteduenv.rxcheck.core.rtl.UsbStats

/** A tunable IQ source: the real RTL-SDR or the simulator. */
interface Receiver : AutoCloseable {
    val sampleRate: Int
    val description: String

    /** Tunes; returns false if the PLL did not lock (segment is then marked invalid). */
    fun tune(hz: Long): Boolean

    /**
     * Fills [out] with interleaved I/Q in [-1, 1) captured after the last tune.
     * Samples produced while the tuner was moving are dropped first.
     * Returns how many I/Q components hit the ADC limits (clipping).
     */
    fun capture(out: FloatArray, discardSamples: Int): Int

    /** USB counters since the last call (null for sources without USB). */
    fun takeStats(): UsbStats? = null
}
