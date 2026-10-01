"""Signal detection, spurious/leakage analysis, SNR and baseline comparison."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field

import numpy as np

from .dsp import (Spectrum, band_power_db, band_power_lin, noise_density_lin,
                  noise_floor_db, to_db)

# RTL-SDR Blog V4 reference crystal; its harmonics show up as internal spurs.
V4_XTAL_HZ = 28.8e6
# Practical instantaneous dynamic range of an 8-bit RTL-SDR.
DONGLE_DYNAMIC_RANGE_DB = 45.0


@dataclass
class Signal:
    f_lo: float
    f_hi: float
    peak_freq: float
    peak_db: float
    power_db: float          # integrated over the detected run
    bw_10db_hz: float        # width where level is within 10 dB of peak
    kind: str                # "CW" or "wideband"
    flags: list = field(default_factory=list)
    level_dbc: float | None = None

    def to_dict(self):
        return asdict(self)


def _runs(mask: np.ndarray, gap: int):
    """Contiguous True runs, bridging gaps of up to `gap` False bins."""
    idx = np.flatnonzero(mask)
    if idx.size == 0:
        return []
    runs = []
    start = prev = idx[0]
    for i in idx[1:]:
        if i - prev > gap + 1:
            runs.append((start, prev))
            start = i
        prev = i
    runs.append((start, prev))
    return runs


def detect_signals(spec: Spectrum, threshold_db: float = 10.0,
                   floor_db: float | None = None, merge_gap_bins: int = 3,
                   exclude=()) -> list[Signal]:
    """Find emissions more than `threshold_db` above the noise floor.

    exclude: iterable of (f_lo, f_hi) ranges to ignore (e.g. the carrier).
    """
    if floor_db is None:
        floor_db = noise_floor_db(spec)
    mask = spec.power_db > floor_db + threshold_db
    for lo, hi in exclude:
        mask &= ~((spec.freqs >= lo) & (spec.freqs <= hi))

    out = []
    for a, b in _runs(mask, merge_gap_bins):
        seg = spec.power_db[a:b + 1]
        k = a + int(np.argmax(seg))
        peak = float(spec.power_db[k])
        within = np.flatnonzero(seg >= peak - 10)
        bw10 = (within[-1] - within[0] + 1) * spec.rbw_hz
        kind = "CW" if bw10 <= 6 * spec.rbw_hz else "wideband"
        out.append(Signal(
            f_lo=float(spec.freqs[a]), f_hi=float(spec.freqs[b]),
            peak_freq=float(spec.freqs[k]), peak_db=peak,
            power_db=float(to_db(spec.power_lin[a:b + 1].sum() / spec.enbw)),
            bw_10db_hz=float(bw10), kind=kind))
    return out


def near_multiple(f: float, base: float, tol: float, n_max: int = 100):
    n = round(f / base)
    if 1 <= n <= n_max and abs(f - n * base) <= tol:
        return n
    return None


def spurious_analysis(spec: Spectrum, carrier_freq: float, carrier_bw: float,
                      limit_dbc: float = -40.0, threshold_db: float = 10.0,
                      guard_hz: float | None = None) -> dict:
    """Spurious / out-of-band emissions around a known carrier.

    Levels are reported in dBc relative to total carrier power, so the
    uncalibrated dongle gain cancels out.
    """
    lo, hi = carrier_freq - carrier_bw / 2, carrier_freq + carrier_bw / 2
    if lo < spec.freqs[0] or hi > spec.freqs[-1]:
        raise ValueError("캐리어 대역이 스캔 범위 안에 있어야 합니다")
    if guard_hz is None:
        guard_hz = 0.05 * carrier_bw
    p_carrier = band_power_db(spec, lo, hi)
    floor = noise_floor_db(spec)

    # ACLR: adjacent channels of the same bandwidth on each side.
    aclr, aclr_noise_limited = {}, {}
    for name, off in (("lower", -carrier_bw), ("upper", carrier_bw)):
        a_lo, a_hi = lo + off, hi + off
        if a_lo >= spec.freqs[0] and a_hi <= spec.freqs[-1]:
            p_adj = band_power_db(spec, a_lo, a_hi)
            aclr[name] = p_carrier - p_adj
            # Adjacent band power indistinguishable from the receiver noise.
            p_noise = float(to_db(noise_density_lin(spec, a_lo, a_hi)
                                  * (a_hi - a_lo)))
            aclr_noise_limited[name] = p_adj - p_noise < 3.0
        else:
            aclr[name] = None
            aclr_noise_limited[name] = None

    sigs = detect_signals(spec, threshold_db, floor,
                          exclude=[(lo - guard_hz, hi + guard_hz)])
    tol = 3 * spec.rbw_hz
    for s in sigs:
        s.level_dbc = s.power_db - p_carrier
        if s.level_dbc > limit_dbc:
            s.flags.append("EXCEEDS_LIMIT")
        n = near_multiple(s.peak_freq, carrier_freq, max(tol, carrier_bw / 2))
        if n and n >= 2:
            s.flags.append(f"HARMONIC_{n}")
        if near_multiple(s.peak_freq, V4_XTAL_HZ, tol):
            s.flags.append("POSSIBLE_DONGLE_XTAL_SPUR")
        if s.kind == "CW" and abs(s.peak_freq - carrier_freq) < 3 * carrier_bw:
            mirror = 2 * carrier_freq - s.peak_freq
            if any(abs(o.peak_freq - mirror) <= tol for o in sigs if o is not s):
                s.flags.append("SYMMETRIC_PAIR")

    # Lowest dBc we can trust: noise floor (per bin) relative to carrier.
    sensitivity_dbc = floor + threshold_db - p_carrier
    warnings = []
    if -sensitivity_dbc > DONGLE_DYNAMIC_RANGE_DB:
        warnings.append(
            f"캐리어 대비 {-sensitivity_dbc:.0f} dB 아래까지 보이지만, 동글의 "
            f"실효 다이나믹레인지(~{DONGLE_DYNAMIC_RANGE_DB:.0f} dB)를 넘는 "
            "영역은 동글 자체 왜곡일 수 있습니다")
    for name, v in aclr.items():
        if aclr_noise_limited[name]:
            warnings.append(
                f"ACLR({name}) {v:.1f} dB는 수신 잡음에 묻힌 값: 실제 ACLR은 "
                "이 값 이상입니다. 캐리어 레벨을 올리거나(이득/근접) 판정 보류")
        elif v is not None and v > DONGLE_DYNAMIC_RANGE_DB - 5:
            warnings.append(
                f"ACLR({name}) {v:.1f} dB는 동글 한계에 근접: 실제 값은 "
                "이보다 좋을 수 있습니다(측정 하한)")
    return {
        "carrier_freq_hz": carrier_freq,
        "carrier_bw_hz": carrier_bw,
        "carrier_power_dbfs": p_carrier,
        "noise_floor_dbfs_per_bin": floor,
        "rbw_hz": spec.rbw_hz,
        "limit_dbc": limit_dbc,
        "sensitivity_dbc": sensitivity_dbc,
        "aclr_db": aclr,
        "aclr_noise_limited": aclr_noise_limited,
        "spurs": [s.to_dict() for s in sigs],
        "violations": sum("EXCEEDS_LIMIT" in s.flags for s in sigs),
        "warnings": warnings,
    }


def measure_snr(spec: Spectrum, freq: float, bw: float,
                noise_ref: tuple[float, float] | None = None,
                guard_hz: float | None = None) -> dict:
    """Band SNR: (in-band power - noise) / noise.

    Noise density is taken from `noise_ref` (f_lo, f_hi) if given, otherwise
    from equal-width regions just outside the band on both sides.
    """
    lo, hi = freq - bw / 2, freq + bw / 2
    m = (spec.freqs >= lo) & (spec.freqs <= hi)
    if not m.any():
        raise ValueError("측정 대역이 스펙트럼 범위 밖입니다")
    nbins = int(m.sum())
    eff_bw = nbins * spec.rbw_hz
    total = band_power_lin(spec, lo, hi)

    if noise_ref is not None:
        n0 = noise_density_lin(spec, *noise_ref)
        ref_desc = f"{noise_ref[0]/1e6:.3f}~{noise_ref[1]/1e6:.3f} MHz"
    else:
        if guard_hz is None:
            guard_hz = max(0.1 * bw, 5 * spec.rbw_hz)
        ref_w = max(bw, 20 * spec.rbw_hz)
        cands = []
        for a, b in ((lo - guard_hz - ref_w, lo - guard_hz),
                     (hi + guard_hz, hi + guard_hz + ref_w)):
            if a >= spec.freqs[0] and b <= spec.freqs[-1]:
                cands.append(noise_density_lin(spec, a, b))
        if not cands:
            raise ValueError(
                "대역 양옆에 잡음 기준 구간이 없습니다. 스캔 폭을 넓히거나 "
                "--noise-ref 를 지정하세요")
        # The quieter side is less likely to contain another emission.
        n0 = min(cands)
        ref_desc = "인접 대역(자동)"

    noise = n0 * eff_bw
    sig = total - noise
    snr = to_db(sig / noise) if sig > 0 else None
    warnings = []
    if snr is None or snr < 3:
        warnings.append("대역 내 전력이 잡음 수준과 구분되지 않습니다")
    elif snr > DONGLE_DYNAMIC_RANGE_DB - 5:
        warnings.append("동글 다이나믹레인지 한계 근처: 실제 SNR은 더 높을 수 있음")
    return {
        "freq_hz": freq, "bw_hz": bw, "effective_bw_hz": eff_bw,
        "total_power_dbfs": float(to_db(total)),
        "noise_power_dbfs": float(to_db(noise)),
        "noise_density_dbfs_hz": float(to_db(n0)),
        "snr_db": None if snr is None else float(snr),
        "noise_reference": ref_desc,
        "warnings": warnings,
    }


def _smooth_db(power_db: np.ndarray, bins: int) -> np.ndarray:
    if bins <= 1:
        return power_db
    lin = 10 ** (power_db / 10)
    return to_db(np.convolve(lin, np.ones(bins) / bins, mode="same"))


def compare_to_baseline(spec: Spectrum, baseline: Spectrum,
                        delta_db: float = 6.0, min_above_floor_db: float = 6.0,
                        merge_gap_bins: int = 10,
                        smooth_bins: int = 9) -> list[dict]:
    """Regions that rose by more than `delta_db` vs a reference sweep.

    Typical uses: a sweep taken when the site was known-good (new interferer
    detection), or a sweep with the antenna replaced by a 50 ohm load
    (separating dongle-internal spurs from real ones).
    """
    # Smoothing keeps weak wideband emissions from fragmenting into many
    # events due to per-bin noise; peaks are still read from the raw data.
    base = _smooth_db(np.interp(spec.freqs, baseline.freqs,
                                baseline.power_db), smooth_bins)
    cur = _smooth_db(spec.power_db, smooth_bins)
    diff = cur - base
    floor = noise_floor_db(spec)
    core = (diff > delta_db) & (cur > floor + min_above_floor_db)
    # Hysteresis: grow each detection over bins 3 dB short of both criteria
    # so one emission near the threshold is reported once, not in fragments.
    loose = (diff > delta_db - 3.0) & (cur > floor + min_above_floor_db - 3.0)
    events = []
    for a, b in _runs(loose, merge_gap_bins):
        if not core[a:b + 1].any():
            continue
        k = a + int(np.argmax(spec.power_db[a:b + 1]))
        events.append({
            "f_lo": float(spec.freqs[a]), "f_hi": float(spec.freqs[b]),
            "peak_freq": float(spec.freqs[k]),
            "peak_db": float(spec.power_db[k]),
            "baseline_db": float(base[k]),
            "increase_db": float(diff[k]),
        })
    return events


def mark_internal(signals: list[Signal], terminated: Spectrum,
                  margin_db: float = 6.0) -> None:
    """Flag detections also present with the antenna terminated (50 ohm)."""
    floor = noise_floor_db(terminated)
    for s in signals:
        lvl = float(np.interp(s.peak_freq, terminated.freqs,
                              terminated.power_db))
        if lvl > floor + margin_db and s.peak_db - lvl < margin_db:
            s.flags.append("DONGLE_INTERNAL")
