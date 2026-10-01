"""Spectrum estimation and band-power helpers.

Levels are dBFS (relative to ADC full scale), not dBm: the RTL-SDR is not
calibrated, so only relative quantities (dBc, SNR, change vs. baseline) are
meaningful unless a reference signal is used to calibrate.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass
class Spectrum:
    freqs: np.ndarray      # Hz, ascending
    power_db: np.ndarray   # dBFS per bin (tone-calibrated)
    rbw_hz: float          # bin spacing
    enbw: float            # window equivalent noise bandwidth, in bins

    @property
    def power_lin(self) -> np.ndarray:
        return 10 ** (self.power_db / 10)

    def slice(self, f_lo: float, f_hi: float) -> "Spectrum":
        m = (self.freqs >= f_lo) & (self.freqs <= f_hi)
        return Spectrum(self.freqs[m], self.power_db[m], self.rbw_hz, self.enbw)


def welch_psd(iq: np.ndarray, fs: float, nfft: int = 4096,
              overlap: float = 0.5, remove_dc: bool = True):
    """Averaged periodogram, fftshifted.

    Scaled so that a CW tone of power P reads P at its peak bin; summing
    bins over a band and dividing by ENBW gives the band power.
    Returns (freq_offsets, power_lin, enbw).
    """
    iq = np.asarray(iq, dtype=np.complex64)
    if remove_dc:
        iq = iq - iq.mean()
    if len(iq) < nfft:
        raise ValueError(f"샘플 수({len(iq)})가 nfft({nfft})보다 적습니다")
    win = np.hanning(nfft).astype(np.float32)
    step = max(1, int(nfft * (1 - overlap)))
    nseg = 1 + (len(iq) - nfft) // step
    acc = np.zeros(nfft)
    for i in range(nseg):
        seg = iq[i * step:i * step + nfft] * win
        acc += np.abs(np.fft.fft(seg)) ** 2
    acc /= nseg * win.sum() ** 2
    enbw = nfft * np.sum(win.astype(np.float64) ** 2) / win.sum() ** 2
    freqs = np.fft.fftshift(np.fft.fftfreq(nfft, 1 / fs))
    psd = np.fft.fftshift(acc)
    if remove_dc:
        # The RTL-SDR has a DC/LO leakage spike; patch the centre bins.
        c = nfft // 2
        neighbours = np.r_[psd[c - 6:c - 2], psd[c + 3:c + 7]]
        psd[c - 2:c + 3] = np.median(neighbours)
    return freqs, psd, enbw


def to_db(x):
    return 10 * np.log10(np.maximum(x, 1e-30))


def noise_floor_db(spec: Spectrum, percentile: float = 50.0) -> float:
    """Robust noise floor per bin: median of all bins.

    Works as long as signals occupy less than half the span; use a lower
    percentile for heavily occupied spans.
    """
    return float(to_db(np.percentile(spec.power_lin, percentile)))


def band_power_lin(spec: Spectrum, f_lo: float, f_hi: float) -> float:
    m = (spec.freqs >= f_lo) & (spec.freqs <= f_hi)
    if not m.any():
        raise ValueError(
            f"{f_lo/1e6:.3f}~{f_hi/1e6:.3f} MHz 구간이 스펙트럼에 없습니다")
    return float(spec.power_lin[m].sum() / spec.enbw)


def band_power_db(spec: Spectrum, f_lo: float, f_hi: float) -> float:
    return float(to_db(band_power_lin(spec, f_lo, f_hi)))


def noise_density_lin(spec: Spectrum, f_lo: float, f_hi: float) -> float:
    """Noise power per Hz estimated from the median of bins in a range."""
    m = (spec.freqs >= f_lo) & (spec.freqs <= f_hi)
    if not m.any():
        raise ValueError("잡음 기준 구간이 스펙트럼에 없습니다")
    return float(np.median(spec.power_lin[m]) / spec.enbw / spec.rbw_hz)
