import numpy as np
import pytest

from rfscan import analysis
from rfscan.cli import freq, main
from rfscan.report import load_spectrum, save_spectrum
from rfscan.source import SimScenario, SimSignal, SimulatedSource
from rfscan.sweep import sweep

N0 = -125.0  # dBFS/Hz


def make(signals, seed=3):
    return SimulatedSource(SimScenario(signals=signals, noise_density_db=N0,
                                       seed=seed))


def test_freq_parse():
    assert freq("940M") == 940e6
    assert freq("1.2G") == 1.2e9
    assert freq("200k") == 200e3
    assert freq("1000") == 1000


def test_sweep_covers_range_and_hides_dc_spike():
    spec = sweep(make([]), 900e6, 920e6)
    assert spec.freqs[0] == pytest.approx(900e6)
    assert spec.freqs[-1] == pytest.approx(920e6, abs=spec.rbw_hz)
    assert np.all(np.diff(spec.freqs) > 0)
    # Simulated LO leakage (-45 dBFS) must not appear as a signal.
    assert analysis.detect_signals(spec, 10) == []


def test_tone_level_and_frequency():
    spec = sweep(make([SimSignal(910.123e6, -50.0)]), 900e6, 920e6)
    sigs = analysis.detect_signals(spec, 10)
    assert len(sigs) == 1
    s = sigs[0]
    assert s.kind == "CW"
    assert s.peak_freq == pytest.approx(910.123e6, abs=2 * spec.rbw_hz)
    assert s.power_db == pytest.approx(-50.0, abs=1.0)


@pytest.mark.parametrize("bw,p", [(9e6, -20.0), (200e3, -60.0), (10e3, -75.0)])
def test_snr_matches_truth(bw, p):
    fc = 940e6
    spec = sweep(make([SimSignal(fc, p, bw if bw > 50e3 else 0)]),
                 fc - 1.8 * bw - 1e6, fc + 1.8 * bw + 1e6)
    res = analysis.measure_snr(spec, fc, bw)
    truth = p - (N0 + 10 * np.log10(res["effective_bw_hz"]))
    assert res["snr_db"] == pytest.approx(truth, abs=1.0)


def test_spurious_dbc_harmonic_and_limit():
    fc, bw = 600e6, 5e6
    src = make([SimSignal(fc, -20.0, bw), SimSignal(fc + 8e6, -65.0),
                SimSignal(fc + 11e6, -50.0)])
    spec = sweep(src, fc - 15e6, fc + 15e6)
    res = analysis.spurious_analysis(spec, fc, bw, limit_dbc=-40)
    spurs = {round(s["peak_freq"] / 1e6, 1): s for s in res["spurs"]}
    assert spurs[608.0]["level_dbc"] == pytest.approx(-45, abs=1)
    assert spurs[611.0]["level_dbc"] == pytest.approx(-30, abs=1)
    assert "EXCEEDS_LIMIT" in spurs[611.0]["flags"]
    assert "EXCEEDS_LIMIT" not in spurs[608.0]["flags"]
    assert res["violations"] == 1
    assert res["aclr_noise_limited"]["lower"]


def test_harmonic_and_xtal_flags():
    fc = 300e6
    src = make([SimSignal(fc, -20.0), SimSignal(2 * fc, -60.0),
                SimSignal(28.8e6 * 20, -70.0)])
    spec = sweep(src, 280e6, 610e6, n_samples=65536)
    res = analysis.spurious_analysis(spec, fc, 100e3)
    flags = {round(s["peak_freq"] / 1e6, 1): s["flags"] for s in res["spurs"]}
    assert "HARMONIC_2" in flags[600.0]
    assert "POSSIBLE_DONGLE_XTAL_SPUR" in flags[576.0]


def test_baseline_compare_finds_new_interferer(tmp_path):
    base_src = make([SimSignal(905e6, -40.0)], seed=1)
    base = sweep(base_src, 900e6, 915e6)
    path = tmp_path / "base.csv"
    save_spectrum(base, str(path))
    base = load_spectrum(str(path))

    now = sweep(make([SimSignal(905e6, -40.0), SimSignal(912.3e6, -70.0, 50e3)],
                     seed=2), 900e6, 915e6)
    ev = analysis.compare_to_baseline(now, base, 6.0)
    assert len(ev) == 1
    assert ev[0]["peak_freq"] == pytest.approx(912.3e6, abs=30e3)


def test_mark_internal():
    term = sweep(make([SimSignal(576e6, -70.0)]), 570e6, 580e6)
    live = sweep(make([SimSignal(576e6, -70.0), SimSignal(574e6, -60.0)]),
                 570e6, 580e6)
    sigs = analysis.detect_signals(live, 10)
    analysis.mark_internal(sigs, term)
    flags = {round(s.peak_freq / 1e6): s.flags for s in sigs}
    assert "DONGLE_INTERNAL" in flags[576]
    assert "DONGLE_INTERNAL" not in flags[574]


def test_out_of_range_rejected():
    with pytest.raises(ValueError):
        make([]).tune(1850e6)


def test_cli_end_to_end(tmp_path):
    out = str(tmp_path)
    assert main(["scan", "--sim", "--start", "925M", "--stop", "975M",
                 "--out", out, "--tag", "s", "--quiet"]) == 0
    assert main(["spur", "--sim", "--carrier", "940M", "--bw", "9M",
                 "--span", "50M", "--out", out, "--tag", "p", "--quiet",
                 "--fail-on-violation"]) == 1
    assert main(["snr", "--sim", "--freq", "940M", "--bw", "9M",
                 "--quiet"]) == 0
    assert main(["compare", "--sim", "--baseline", f"{out}/s.csv",
                 "--out", out, "--tag", "c", "--quiet", "--no-plot"]) == 0
    assert main(["scan", "--sim", "--start", "1.8G", "--stop", "1.85G",
                 "--out", out, "--quiet"]) == 2
    for f in ("s.csv", "s.json", "s.png", "p.json", "p.png", "c.json"):
        assert (tmp_path / f).exists()


def test_b3_uplink_and_max_freq(tmp_path):
    from rfscan import source
    out = str(tmp_path)
    # 1.8 GHz UL below 1766 MHz works with default limits.
    assert main(["scan", "--sim", "--start", "1720M", "--stop", "1760M",
                 "--out", out, "--quiet", "--no-plot"]) == 0
    # Above the V4 spec needs --max-freq.
    assert main(["scan", "--sim", "--start", "1760M", "--stop", "1785M",
                 "--out", out, "--quiet", "--no-plot"]) == 2
    try:
        assert main(["scan", "--sim", "--start", "1760M", "--stop", "1785M",
                     "--max-freq", "1800M", "--out", out, "--quiet",
                     "--no-plot"]) == 0
    finally:
        source.max_freq_hz = source.V4_MAX_FREQ
