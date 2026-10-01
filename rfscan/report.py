"""Spectrum file I/O, plots and console tables."""

from __future__ import annotations

import csv
import json

import numpy as np

from .dsp import Spectrum, noise_floor_db


def save_spectrum(spec: Spectrum, path: str, meta: dict | None = None) -> None:
    with open(path, "w", newline="", encoding="utf-8") as f:
        f.write(f"# rbw_hz={spec.rbw_hz} enbw={spec.enbw}\n")
        if meta:
            f.write("# meta=" + json.dumps(meta, ensure_ascii=False) + "\n")
        w = csv.writer(f)
        w.writerow(["freq_hz", "power_dbfs"])
        for fr, p in zip(spec.freqs, spec.power_db):
            w.writerow([f"{fr:.1f}", f"{p:.2f}"])


def load_spectrum(path: str) -> Spectrum:
    rbw, enbw = None, 1.5
    freqs, power = [], []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("# rbw_hz="):
                parts = dict(kv.split("=") for kv in line[2:].split())
                rbw, enbw = float(parts["rbw_hz"]), float(parts["enbw"])
            elif line.startswith("#") or line.startswith("freq_hz"):
                continue
            elif line.strip():
                a, b = line.split(",")
                freqs.append(float(a))
                power.append(float(b))
    freqs = np.array(freqs)
    if rbw is None:
        rbw = float(np.median(np.diff(freqs)))
    return Spectrum(freqs, np.array(power), rbw, enbw)


def save_json(obj, path: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)


def mhz(f: float) -> str:
    return f"{f/1e6:.4f}"


def print_signals(signals, title="검출 신호") -> None:
    print(f"\n[{title}] {len(signals)}건")
    if not signals:
        return
    print(f"{'피크(MHz)':>12} {'피크(dBFS)':>10} {'전력(dBFS)':>10} "
          f"{'dBc':>7} {'BW-10dB(kHz)':>12} {'종류':>8}  플래그")
    for s in signals:
        d = s if isinstance(s, dict) else s.to_dict()
        dbc = "" if d.get("level_dbc") is None else f"{d['level_dbc']:.1f}"
        print(f"{mhz(d['peak_freq']):>12} {d['peak_db']:>10.1f} "
              f"{d['power_db']:>10.1f} {dbc:>7} "
              f"{d['bw_10db_hz']/1e3:>12.1f} {d['kind']:>8}  "
              f"{','.join(d['flags'])}")


def plot_spectrum(spec: Spectrum, path: str, title: str = "",
                  markers=(), baseline: Spectrum | None = None,
                  bands=(), limit_line: float | None = None) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, ax = plt.subplots(figsize=(13, 5.5))
    if baseline is not None:
        ax.plot(baseline.freqs / 1e6, baseline.power_db, lw=0.6,
                color="#999999", label="baseline")
    ax.plot(spec.freqs / 1e6, spec.power_db, lw=0.6, color="#1f5fbf",
            label="measured")
    floor = noise_floor_db(spec)
    ax.axhline(floor, color="#2a9d4b", ls="--", lw=0.8,
               label=f"noise floor {floor:.1f} dBFS/bin")
    if limit_line is not None:
        ax.axhline(limit_line, color="#d62728", ls=":", lw=1,
                   label=f"limit for CW spur {limit_line:.1f} dBFS")
    for lo, hi, label in bands:
        ax.axvspan(lo / 1e6, hi / 1e6, color="#f4a261", alpha=0.15)
        ax.text((lo + hi) / 2e6, ax.get_ylim()[1], label, ha="center",
                va="top", fontsize=8)
    for f, p, label in markers:
        ax.plot(f / 1e6, p, "v", color="#d62728", ms=6)
        ax.annotate(label, (f / 1e6, p), textcoords="offset points",
                    xytext=(0, 6), ha="center", fontsize=7)
    ax.set_xlabel("Frequency (MHz)")
    ax.set_ylabel("Power (dBFS / bin)")
    ax.set_title(title or f"RBW {spec.rbw_hz/1e3:.2f} kHz")
    ax.grid(alpha=0.3)
    ax.legend(loc="best", fontsize=8)
    fig.tight_layout()
    fig.savefig(path, dpi=130)
    plt.close(fig)
