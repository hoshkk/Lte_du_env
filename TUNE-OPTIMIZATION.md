# SpectrumCheck Fast 2.5.1

## Actual acquisition change

The R82xx RF mux/filter setup now skips writes whose final register value is already present in the successfully-written shadow cache. The optimization is limited to r82xx_set_mux: static filter/mux/capacitance settings. PLL and calibration write sequences and lock checks retain their original behavior. Shadow cache updates occur only after successful hardware writes.

The native tuning call returns measured monotonic time for frequency control, settling, and FIFO reset. Kotlin accumulates those plus discard/read/DSP times over each completed sweep. The UI and CSV show completed-sweep phase totals. UI scheduling, JNI overhead outside the measured intervals and frame publication are not attributed to individual phases; phase sums can differ from total sweep time.

Guard remains 10 ms (first tune minimum 50 ms). Discard remains 4096 bytes. Span, RBW, gain, FFT and sample rate are unchanged. No interpolation or replay is used to manufacture faster traces.

## Verification

- Android unit suite and ARM64/ARMv7 native build.
- native-tests/test_mux_cache.py compiles the actual C write/cache helper code with a mock I2C transport. It checks unchanged/changed register values, preservation of unrelated bits, retry after a failed write, and that ordinary PLL-style writes are not suppressed.
- Hardware speed and RF correctness of this change are unverified. The preceding 2.5.0 user recording shows roughly 590 ms / 15 MHz and 930 ms / 25 MHz; those are baselines, not measurements of this version.

## Comparison

Use USB 직접 with the previous center/span/RBW/gain settings. Record the total sweep time and the new phase line after the first sweep. Compare B8 at center 909.3 MHz / span 15 MHz and B3 at center 1745 MHz / span 25 MHz separately. The reference SK video is a qualitative responsiveness target; it does not establish simultaneous capture or equivalent hardware.
