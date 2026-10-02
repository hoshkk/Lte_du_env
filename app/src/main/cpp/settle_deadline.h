#ifndef SPECTRUM_SETTLE_DEADLINE_H
#define SPECTRUM_SETTLE_DEADLINE_H
#include <stdint.h>
/* Never credit time before the final USB write. Missing or invalid timing
 * falls back to the complete old post-tune wait. Startup uses that fallback. */
static int64_t spectrum_settle_deadline(int64_t start, int64_t end,
                                      int64_t last_write, int settle_ms) {
    int64_t base = end;
    if (settle_ms < 50 && last_write >= start && last_write <= end && last_write > 0)
        base = last_write;
    return base + (int64_t)settle_ms * 1000000LL;
}
#endif
