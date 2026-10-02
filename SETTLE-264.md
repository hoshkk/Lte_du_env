# 2.6.4-settle — duplicate settling time experiment

Base: 2.6.3, including USB attach handling and 2.6.2 divider-write caching.

During synchronous tuning, record the CLOCK_MONOTONIC completion time of the last successful USB OUT transfer. A failed/short OUT invalidates that timestamp. After tuning and PLL checks succeed, wait until that timestamp plus the configured settling interval, rather than always starting a fresh interval after all status reads have also finished.

Conservative boundaries:
- All register commands, their order, PLL checks, FIFO reset and IQ discard unchanged.
- Timestamp must lie inside this tune call; missing/invalid values use the previous full post-tune delay.
- Intervals >= 50 ms keep the old delay, preserving the startup wait.
- Interrupted sleeps are retried against the deadline; unexpected sleep errors abort capture.
- The minimum configured interval is maintained after the last tracked successful USB write. This changes the reference point of the delay and is not a physical RF accuracy certification.
- No span, gain, FFT/RBW/VBW, or amplitude scaling changes.

Validation: six native test scripts, including final-write timing/fallback properties and the existing 240 mocked PLL-sequence comparisons. Android unit tests/build separately recorded by Gradle.

Expected scope: only the overlap between the final write completion and tune return can be saved. USB writes remain 81 per steady B8 nine-segment sweep. This change does NOT eliminate the ~300 ms tuning work or establish SK-like real-time performance. Phone/RF measurements are still required.

Compare with 2.6.3 at fixed center, span, RBW, VBW, manual gain, offset and antenna position. Check total sweep time and wait time over multiple completed sweeps. Also compare a stable known RF signal for peak position, level and segment-boundary artifacts. Natural-noise screenshots alone cannot certify accuracy.
