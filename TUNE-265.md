# 2.6.5-tune: bounded configuration-write reduction

Base: 2.6.4. Same bandwidth/FFT/gain/settle settings and USB permission handling.

During normal post-initialization tuning, suppress unchanged writes only for:
- 0x10 reference/mixer divider masks 0x10/0xe0 (existing optimization).
- 0x12 SDM power mask 0x08 (new). The preceding VCO-current write remains unconditional and updates the same register shadow.
- 0x14 integer divider mask 0xff (new).

Track successful hardware writes with a per-register validity bitset. A failed/short write invalidates the cache; initializing the tuner clears it. New 0x12/0x14 suppression is disabled during initialization/calibration. Different values always write to hardware.

Fractional SDM writes at 0x16 then 0x15 remain in their original order. VCO-current setup, autotune switching, fine-tune read, PLL lock checks/retry, I2C repeater transitions/acknowledgement, FIFO reset and IQ discard remain intact. The settled-time calculation from 2.6.4 remains intact.

Validation: six native scripts, with 285 mocked PLL paths comparing final registers/lock reads against the unsuppressed sequence; includes integer-frequency transitions, calibration bypass and register-specific validity. Steady B8 mock sweep removes 36 writes relative to the original uncached sequence, 18 more than 2.6.4. Expected normal diagnostic write counts are approximately 63/9 segments and 98/14 segments, but divider/SDM transitions and lock retries can add writes.

Physical RF accuracy and phone sweep times are NOT validated by these tests. Field screenshots for 2.6.4 showed 412–413 ms (15 MHz) and 624–656 ms (25 MHz). Do not label any estimated 2.6.5 timing as measured.

Further ideas reviewed and not applied: removing lock/fine-tune reads; skipping VCO-current or autotune transitions; merging/reordering SDM writes; leaving the I2C repeater open during capture; removing FIFO reset/discard; increasing usable FFT edge bandwidth/sample rate; reducing the actual stable interval. Those require further hardware-specific evidence or RF measurement. This is a conservative endpoint for the present review, not proof of the hardware's absolute speed limit.
