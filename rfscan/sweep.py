"""Wideband sweep by stepping the tuner and stitching captures.

Two RTL-SDR artefacts are avoided:
- the IF filter rolls off near the band edges, so only the centre
  `usable_frac` of each capture is used;
- the LO leakage spike at DC (tuned centre), so bins close to DC are never
  used. Steps overlap by half so every frequency is also seen off-centre by
  a neighbouring capture.
"""

from __future__ import annotations

import numpy as np

from .dsp import Spectrum, to_db, welch_psd


def capture(source, center_hz: float, nfft: int = 2048,
            n_samples: int = 262144) -> Spectrum:
    """Single capture at one tuning, full IF bandwidth."""
    source.tune(center_hz)
    iq = source.read(n_samples)
    fs = source.sample_rate
    f, p, enbw = welch_psd(iq, fs, nfft, remove_dc=False)
    return Spectrum(f + center_hz, to_db(p), fs / nfft, enbw)


def sweep(source, f_start: float, f_stop: float, nfft: int = 2048,
          n_samples: int = 262144, usable_frac: float = 0.75,
          dc_guard_hz: float | None = None, progress=None) -> Spectrum:
    if f_stop <= f_start:
        raise ValueError("종료 주파수가 시작 주파수보다 커야 합니다")
    fs = source.sample_rate
    rbw = fs / nfft
    if dc_guard_hz is None:
        dc_guard_hz = max(8 * rbw, 0.005 * fs)
    half_bins = int(usable_frac * nfft / 2)
    guard_bins = int(np.ceil(dc_guard_hz / rbw))
    step_bins = half_bins
    if guard_bins >= step_bins // 2:
        raise ValueError("dc_guard_hz 가 너무 큽니다")

    n_grid = int(np.floor((f_stop - f_start) / rbw)) + 1
    grid = f_start + np.arange(n_grid) * rbw
    out = np.full(n_grid, np.nan)
    score = np.full(n_grid, np.inf)   # |offset from DC| of the chosen capture

    centers_idx = list(range(step_bins // 2, n_grid + step_bins, step_bins))
    k = np.arange(-nfft // 2, nfft // 2)
    use = (np.abs(k) <= half_bins) & (np.abs(k) > guard_bins)
    enbw = 1.5
    for i, ci in enumerate(centers_idx):
        if progress:
            progress(i + 1, len(centers_idx), f_start + ci * rbw)
        spec = capture(source, f_start + ci * rbw, nfft, n_samples)
        enbw = spec.enbw
        gi = ci + k[use]
        ok = (gi >= 0) & (gi < n_grid)
        gi, kk, pw = gi[ok], np.abs(k[use][ok]), spec.power_db[use][ok]
        better = kk < score[gi]
        out[gi[better]] = pw[better]
        score[gi[better]] = kk[better]

    if np.isnan(out).any():  # only possible at the very edges
        good = ~np.isnan(out)
        out = np.interp(grid, grid[good], out[good])
    return Spectrum(grid, out, rbw, enbw)


def max_hold(a: Spectrum, b: Spectrum) -> Spectrum:
    return Spectrum(a.freqs, np.maximum(a.power_db, b.power_db),
                    a.rbw_hz, a.enbw)
