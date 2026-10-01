"""IQ sample sources: RTL-SDR Blog V4 hardware and a simulator for testing."""

from __future__ import annotations

import json
from dataclasses import dataclass, field

import numpy as np

# RTL-SDR Blog V4 (R828D) usable tuning range.
V4_MIN_FREQ = 24e6
V4_MAX_FREQ = 1766e6
# Stable sample rate for RTL2832U without dropped samples.
DEFAULT_SAMPLE_RATE = 2.4e6


def check_range(freq_hz: float) -> None:
    if not V4_MIN_FREQ <= freq_hz <= V4_MAX_FREQ:
        raise ValueError(
            f"{freq_hz/1e6:.3f} MHz는 RTL-SDR V4 수신 범위"
            f"({V4_MIN_FREQ/1e6:.0f}~{V4_MAX_FREQ/1e6:.0f} MHz)를 벗어납니다")


class RtlSdrSource:
    """Wrapper around pyrtlsdr.

    The V4 needs the rtl-sdr-blog fork of librtlsdr installed on the system;
    the stock librtlsdr misdetects the R828D tuner and HF/upconverter paths.
    """

    def __init__(self, sample_rate=DEFAULT_SAMPLE_RATE, gain="auto", ppm=0,
                 device_index=0, bias_tee=False, settle_samples=16384):
        try:
            from rtlsdr import RtlSdr
        except ImportError as e:  # pragma: no cover - hardware only
            raise RuntimeError(
                "pyrtlsdr가 설치되어 있지 않습니다: pip install pyrtlsdr") from e

        self.sdr = RtlSdr(device_index)
        self.sdr.sample_rate = sample_rate
        if gain == "auto":
            self.sdr.gain = "auto"
        else:
            self.sdr.gain = float(gain)
        if ppm:
            self.sdr.freq_correction = int(ppm)
        if bias_tee and hasattr(self.sdr, "set_bias_tee"):
            self.sdr.set_bias_tee(True)
        self.sample_rate = float(self.sdr.sample_rate)
        self.settle_samples = settle_samples
        self.center_freq = None

    def tune(self, freq_hz: float) -> None:
        check_range(freq_hz)
        self.sdr.center_freq = freq_hz
        self.center_freq = freq_hz
        # Discard samples captured while the PLL settles.
        self.sdr.read_samples(self.settle_samples)

    def read(self, n: int) -> np.ndarray:
        return np.asarray(self.sdr.read_samples(n), dtype=np.complex64)

    def close(self) -> None:
        self.sdr.close()


@dataclass
class SimSignal:
    """A simulated emitter.

    freq_hz: centre frequency.
    power_db: total power in dBFS.
    bw_hz: 0 for a CW tone (spur), otherwise a flat band (LTE-like carrier).
    """

    freq_hz: float
    power_db: float
    bw_hz: float = 0.0


@dataclass
class SimScenario:
    signals: list = field(default_factory=list)
    # Noise density in dBFS/Hz.
    noise_density_db: float = -125.0
    # LO leakage at the tuned centre, like a real RTL-SDR (None to disable).
    dc_spike_db: float | None = -45.0
    seed: int = 1

    @classmethod
    def from_json(cls, path: str) -> "SimScenario":
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        return cls(
            signals=[SimSignal(**s) for s in data.get("signals", [])],
            noise_density_db=data.get("noise_density_db", -125.0),
            dc_spike_db=data.get("dc_spike_db", -45.0),
            seed=data.get("seed", 1),
        )

    @classmethod
    def default(cls) -> "SimScenario":
        # A 10 MHz LTE carrier at 940 MHz with a few spurs and an interferer.
        return cls(signals=[
            SimSignal(940e6, -20.0, 9e6),
            SimSignal(951.2e6, -62.0),     # close-in spur
            SimSignal(928.8e6, -65.0),     # mirror spur
            SimSignal(963.5e6, -55.0, 200e3),  # narrowband interferer
        ])


class SimulatedSource:
    """Generates IQ for a SimScenario as seen by a receiver tuned to fc."""

    def __init__(self, scenario: SimScenario | None = None,
                 sample_rate=DEFAULT_SAMPLE_RATE):
        self.scenario = scenario or SimScenario.default()
        self.sample_rate = float(sample_rate)
        self.center_freq = None
        self.rng = np.random.default_rng(self.scenario.seed)

    def tune(self, freq_hz: float) -> None:
        check_range(freq_hz)
        self.center_freq = float(freq_hz)

    def read(self, n: int) -> np.ndarray:
        fs = self.sample_rate
        fc = self.center_freq
        noise_var = 10 ** (self.scenario.noise_density_db / 10) * fs
        x = (self.rng.standard_normal(n) + 1j * self.rng.standard_normal(n))
        x *= np.sqrt(noise_var / 2)
        t = np.arange(n) / fs
        if self.scenario.dc_spike_db is not None:
            x += np.sqrt(10 ** (self.scenario.dc_spike_db / 10))
        freqs = np.fft.fftfreq(n, 1 / fs)
        for s in self.scenario.signals:
            off = s.freq_hz - fc
            p = 10 ** (s.power_db / 10)
            if s.bw_hz <= 0:
                if abs(off) < fs / 2:
                    phase = self.rng.uniform(0, 2 * np.pi)
                    x += np.sqrt(p) * np.exp(1j * (2 * np.pi * off * t + phase))
                continue
            lo, hi = off - s.bw_hz / 2, off + s.bw_hz / 2
            mask = (freqs >= lo) & (freqs <= hi)
            if not mask.any():
                continue
            # Flat band: density p/bw, only the part inside the IF passband.
            spec = np.zeros(n, dtype=np.complex128)
            k = mask.sum()
            spec[mask] = (self.rng.standard_normal(k)
                          + 1j * self.rng.standard_normal(k))
            band = np.fft.ifft(spec)
            visible = k * (fs / n)
            target = p * min(visible / s.bw_hz, 1.0)
            band *= np.sqrt(target / np.mean(np.abs(band) ** 2))
            x += band
        return x.astype(np.complex64)

    def close(self) -> None:
        pass
