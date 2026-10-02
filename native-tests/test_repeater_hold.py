"""Compare the repeater-hold-open tune path against the original toggling
path with mocked I2C/tuner calls. This checks call sequencing and final
tuned-frequency state, not RF accuracy or real hardware timing.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
s = (root / 'app/src/main/cpp/librtlsdr/src/librtlsdr.c').read_text()
repeater = s[s.index('void rtlsdr_set_i2c_repeater'):s.index('int rtlsdr_set_fir')]
center_freq = s[s.index('int rtlsdr_set_center_freq(rtlsdr_dev_t'):s.index('uint32_t rtlsdr_get_center_freq')]

prefix = r'''
#include <stdint.h>
#include <stdio.h>
#include <assert.h>

typedef struct rtlsdr_dev rtlsdr_dev_t;

typedef struct rtlsdr_tuner_iface {
	int (*init)(void *);
	int (*exit)(void *);
	int (*set_freq)(void *, uint32_t freq);
	int (*set_bw)(void *, int bw);
	int (*set_gain)(void *, int gain);
	int (*set_if_gain)(void *, int stage, int gain);
	int (*set_gain_mode)(void *, int manual);
} rtlsdr_tuner_iface_t;

struct rtlsdr_dev {
	int direct_sampling;
	rtlsdr_tuner_iface_t *tuner;
	uint32_t offs_freq;
	uint32_t freq;
};

static int repeater_open, repeater_writes, if_freq_calls, if_freq_rc;
static int set_freq_calls, set_freq_rc, set_freq_saw_repeater_open;
static uint32_t last_set_freq_hz;
#define MAXN 32
static uint32_t calls_log[MAXN];
static int calls_log_n;

int rtlsdr_demod_write_reg(rtlsdr_dev_t *dev, uint8_t page, uint16_t addr, uint16_t val, uint8_t len) {
	(void)dev;(void)len;
	if (page == 1 && addr == 0x01) { repeater_writes++; repeater_open = (val == 0x18); }
	return 0;
}
static int rtlsdr_set_if_freq(rtlsdr_dev_t *dev, uint32_t freq) {
	if_freq_calls++;
	if (!if_freq_rc) dev->freq = freq;
	return if_freq_rc;
}
static int mock_set_freq(void *d, uint32_t freq) {
	(void)d;
	set_freq_calls++; last_set_freq_hz = freq; set_freq_saw_repeater_open = repeater_open;
	if (calls_log_n < MAXN) calls_log[calls_log_n++] = freq;
	return set_freq_rc;
}
static void reset_counters(void) {
	repeater_writes = if_freq_calls = set_freq_calls = calls_log_n = 0;
	set_freq_rc = if_freq_rc = 0;
}
'''

main = r'''
int main(void) {
	rtlsdr_tuner_iface_t tuner = {0,0,mock_set_freq,0,0,0,0};

	/* Null dev/tuner must be rejected without touching anything. */
	reset_counters();
	assert(rtlsdr_set_center_freq(0, 900000000) == -1);
	assert(rtlsdr_set_center_freq_no_repeater_toggle(0, 900000000) == -1);
	assert(repeater_writes == 0 && set_freq_calls == 0);
	{
		rtlsdr_dev_t d = {0};
		assert(rtlsdr_set_center_freq(&d, 900000000) == -1);
		assert(rtlsdr_set_center_freq_no_repeater_toggle(&d, 900000000) == -1);
	}

	/* Direct-sampling path never touches the I2C repeater, in either function. */
	{
		rtlsdr_dev_t a = {0}, b = {0};
		a.tuner = b.tuner = &tuner; a.direct_sampling = b.direct_sampling = 1;
		reset_counters();
		int ra = rtlsdr_set_center_freq(&a, 100000000);
		int wa = repeater_writes, ia = if_freq_calls;
		reset_counters();
		int rb = rtlsdr_set_center_freq_no_repeater_toggle(&b, 100000000);
		assert(ra == rb && a.freq == b.freq);
		assert(wa == 0 && ia == 1 && repeater_writes == 0 && if_freq_calls == 1);
	}

	/* Normal tune: the original function brackets the tuner call with an
	   open/close pair, and the tuner sees the repeater open while it runs. */
	{
		rtlsdr_dev_t t = {0}; t.tuner = &tuner; t.offs_freq = 1000;
		reset_counters(); repeater_open = 0; set_freq_rc = 0;
		int r = rtlsdr_set_center_freq(&t, 905000000);
		assert(r == 0 && t.freq == 905000000);
		assert(repeater_writes == 2 && repeater_open == 0);
		assert(set_freq_calls == 1 && last_set_freq_hz == 905000000u - 1000 && set_freq_saw_repeater_open == 1);

		/* A tuner failure still closes the repeater again and clears dev->freq. */
		reset_counters(); repeater_open = 0; set_freq_rc = -5;
		r = rtlsdr_set_center_freq(&t, 910000000);
		assert(r == -5 && t.freq == 0);
		assert(repeater_writes == 2 && repeater_open == 0);

		/* The no-toggle variant never writes the repeater register itself,
		   on success or failure, and leaves whatever state the caller set. */
		reset_counters(); repeater_open = 1; set_freq_rc = 0;
		r = rtlsdr_set_center_freq_no_repeater_toggle(&t, 915000000);
		assert(r == 0 && t.freq == 915000000 && repeater_writes == 0 && repeater_open == 1);
		assert(set_freq_saw_repeater_open == 1);
		reset_counters(); repeater_open = 1; set_freq_rc = -7;
		r = rtlsdr_set_center_freq_no_repeater_toggle(&t, 920000000);
		assert(r == -7 && t.freq == 0 && repeater_writes == 0 && repeater_open == 1);
	}

	/* A full multi-segment sweep: holding the repeater open across every
	   retune must reach the exact same final tuned state and the exact
	   same sequence of frequencies handed to the tuner as the original
	   per-tune toggling path, while eliminating all but the one initial
	   repeater write the caller makes up front (as NativeRtl_open now does). */
	{
		uint32_t freqs[] = {905170000,907170000,908970000,910770000,925000000,
			1748570000,1750370000,880000000,450000000};
		int n = (int)(sizeof(freqs)/sizeof(freqs[0]));
		uint32_t ref_log[MAXN];

		rtlsdr_dev_t ref = {0}; ref.tuner = &tuner;
		reset_counters(); repeater_open = 0; set_freq_rc = 0;
		for (int i = 0; i < n; i++) assert(rtlsdr_set_center_freq(&ref, freqs[i]) == 0);
		assert(repeater_writes == 2*n);
		for (int i = 0; i < n; i++) ref_log[i] = calls_log[i];

		rtlsdr_dev_t hold = {0}; hold.tuner = &tuner;
		reset_counters(); repeater_open = 1; set_freq_rc = 0;
		for (int i = 0; i < n; i++) {
			assert(rtlsdr_set_center_freq_no_repeater_toggle(&hold, freqs[i]) == 0);
			assert(set_freq_saw_repeater_open == 1);
		}
		assert(repeater_writes == 0 && repeater_open == 1);
		assert(ref.freq == hold.freq);
		for (int i = 0; i < n; i++) assert(ref_log[i] == calls_log[i]);
	}

	puts("PASS: repeater-hold tune matches toggling tune's final state and call sequence; zero repeater writes across a 9-segment sweep vs 2 per tune (mock only)");
}
'''

with tempfile.TemporaryDirectory() as d:
    p = Path(d)
    (p / 'test.c').write_text(prefix + repeater + center_freq + main)
    subprocess.run(['gcc', '-Wall', str(p / 'test.c'), '-o', str(p / 'test')], check=True)
    subprocess.run([str(p / 'test')], check=True)
