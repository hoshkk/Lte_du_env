# 2.5.4 profile
Diagnostic build based on v2.5.3, with acquisition and RF register order unchanged.
Thread-local statistics are enabled only around synchronous set_center_freq.
Two overlapping views of the last completed sweep:
- RF mux configuration and PLL function elapsed nanoseconds.
- USB control transfers: tuner I2C write, tuner I2C read, other control; durations and counts.
USB time is INCLUDED in stage time, not additive. PLL time includes calculations, register transfers and lock checking, not just physical lock wait.
Stats are reset each tune and accumulated across the completed sweep. Failed sweeps are not presented as completed measurements.
CSV timing_raw fields document units and ordering. Clock instrumentation has some overhead.
This does not promise faster sweeps; hardware results are required.
Native mock test validates grouping, counts, propagation of errors, disabled bypass, stage time and resetting counters.
Claude's separate reconnect/FFT changes are not merged in this diagnostic build, to keep the measured baseline v2.5.3.
