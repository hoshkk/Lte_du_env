"""Command line entry point: python -m rfscan <command> ..."""

from __future__ import annotations

import argparse
import datetime as dt
import os
import sys
import time

from . import analysis, report
from .dsp import noise_floor_db
from .source import (DEFAULT_SAMPLE_RATE, RtlSdrSource, SimScenario,
                     SimulatedSource)
from .sweep import max_hold, sweep

_SUFFIX = {"k": 1e3, "m": 1e6, "g": 1e9}


def freq(text: str) -> float:
    """Parse '940M', '1.2G', '200k' or plain Hz."""
    t = text.strip().lower().rstrip("hz")
    if t and t[-1] in _SUFFIX:
        return float(t[:-1]) * _SUFFIX[t[-1]]
    return float(t)


def open_source(args):
    if args.sim is not None:
        scen = SimScenario.from_json(args.sim) if args.sim else None
        return SimulatedSource(scen, args.sample_rate)
    return RtlSdrSource(args.sample_rate, args.gain, args.ppm,
                        args.device, args.bias_tee)


def _progress(i, n, c):
    print(f"\r  스윕 {i}/{n}  {c/1e6:9.3f} MHz", end="", file=sys.stderr,
          flush=True)
    if i == n:
        print(file=sys.stderr)


def do_sweep(src, args, f_start, f_stop, avg=None):
    spec = None
    for _ in range(avg or args.avg_sweeps):
        s = sweep(src, f_start, f_stop, args.nfft, args.samples,
                  progress=None if args.quiet else _progress)
        spec = s if spec is None else max_hold(spec, s) if args.max_hold \
            else _avg(spec, s)
    return spec


def _avg(a, b):
    import numpy as np
    from .dsp import Spectrum, to_db
    return Spectrum(a.freqs, to_db((10 ** (a.power_db / 10)
                                    + 10 ** (b.power_db / 10)) / 2),
                    a.rbw_hz, a.enbw)


def _outpath(args, name):
    os.makedirs(args.out, exist_ok=True)
    return os.path.join(args.out, name)


def _stamp():
    return dt.datetime.now().strftime("%Y%m%d_%H%M%S")


def cmd_scan(args):
    src = open_source(args)
    try:
        spec = do_sweep(src, args, args.start, args.stop)
    finally:
        src.close()
    sigs = analysis.detect_signals(spec, args.threshold)
    if args.terminated:
        analysis.mark_internal(sigs, report.load_spectrum(args.terminated))
    floor = noise_floor_db(spec)
    print(f"노이즈 플로어: {floor:.1f} dBFS/bin, RBW {spec.rbw_hz/1e3:.2f} kHz")
    report.print_signals(sigs)

    tag = args.tag or f"scan_{_stamp()}"
    meta = {"cmd": "scan", "start": args.start, "stop": args.stop,
            "gain": args.gain, "time": _stamp()}
    report.save_spectrum(spec, _outpath(args, f"{tag}.csv"), meta)
    report.save_json({"meta": meta, "noise_floor_dbfs": floor,
                      "signals": [s.to_dict() for s in sigs]},
                     _outpath(args, f"{tag}.json"))
    if not args.no_plot:
        report.plot_spectrum(
            spec, _outpath(args, f"{tag}.png"),
            title=f"Scan {args.start/1e6:.1f}-{args.stop/1e6:.1f} MHz",
            markers=[(s.peak_freq, s.peak_db, f"{s.peak_freq/1e6:.3f}")
                     for s in sigs[:40]])
    print(f"\n저장: {args.out}/{tag}.(csv|json|png)")


def cmd_spur(args):
    span = args.span or max(4 * args.bw, 10e6)
    start, stop = args.carrier - span / 2, args.carrier + span / 2
    src = open_source(args)
    try:
        spec = do_sweep(src, args, start, stop)
    finally:
        src.close()
    res = analysis.spurious_analysis(spec, args.carrier, args.bw,
                                     args.limit_dbc, args.threshold)
    if args.terminated:
        sigs = [analysis.Signal(**d) for d in res["spurs"]]
        analysis.mark_internal(sigs, report.load_spectrum(args.terminated))
        res["spurs"] = [s.to_dict() for s in sigs]

    print(f"캐리어 {args.carrier/1e6:.3f} MHz / BW {args.bw/1e6:.2f} MHz: "
          f"{res['carrier_power_dbfs']:.1f} dBFS")
    for k, v in res["aclr_db"].items():
        print(f"  ACLR {k:5s}: " + ("범위 밖" if v is None else f"{v:.1f} dB"))
    print(f"  검출 한계: {res['sensitivity_dbc']:.1f} dBc, "
          f"기준: {args.limit_dbc:.1f} dBc")
    report.print_signals(res["spurs"], "스퓨리어스/불요파")
    print(f"\n기준 초과: {res['violations']}건")
    for w in res["warnings"]:
        print("  ! " + w)

    tag = args.tag or f"spur_{_stamp()}"
    report.save_spectrum(spec, _outpath(args, f"{tag}.csv"))
    report.save_json(res, _outpath(args, f"{tag}.json"))
    if not args.no_plot:
        limit_abs = res["carrier_power_dbfs"] + args.limit_dbc
        lo, hi = args.carrier - args.bw / 2, args.carrier + args.bw / 2
        report.plot_spectrum(
            spec, _outpath(args, f"{tag}.png"),
            title=f"Spurious around {args.carrier/1e6:.3f} MHz",
            bands=[(lo, hi, "carrier")], limit_line=limit_abs,
            markers=[(s["peak_freq"], s["peak_db"], f"{s['level_dbc']:.0f}dBc")
                     for s in res["spurs"]])
    print(f"저장: {args.out}/{tag}.(csv|json|png)")
    return 1 if res["violations"] and args.fail_on_violation else 0


def cmd_snr(args):
    # Band + guard + an equal-width noise reference on each side.
    span = args.span or max(3.6 * args.bw, args.sample_rate * 0.75)
    src = open_source(args)
    try:
        spec = do_sweep(src, args, args.freq - span / 2, args.freq + span / 2)
    finally:
        src.close()
    ref = (args.noise_ref[0], args.noise_ref[1]) if args.noise_ref else None
    res = analysis.measure_snr(spec, args.freq, args.bw, ref)
    snr = res["snr_db"]
    print(f"{args.freq/1e6:.3f} MHz, BW {args.bw/1e3:.1f} kHz")
    print(f"  대역 전력   : {res['total_power_dbfs']:.1f} dBFS")
    print(f"  잡음 전력   : {res['noise_power_dbfs']:.1f} dBFS "
          f"({res['noise_reference']})")
    print("  SNR         : " + ("측정 불가" if snr is None else f"{snr:.1f} dB"))
    for w in res["warnings"]:
        print("  ! " + w)
    if args.out_json:
        report.save_json(res, args.out_json)


def cmd_compare(args):
    base = report.load_spectrum(args.baseline)
    start, stop = args.start or base.freqs[0], args.stop or base.freqs[-1]
    src = open_source(args)
    try:
        spec = do_sweep(src, args, start, stop)
    finally:
        src.close()
    events = analysis.compare_to_baseline(spec, base, args.delta)
    print(f"[기준 대비 {args.delta:.0f} dB 이상 증가] {len(events)}건")
    for e in events:
        print(f"  {e['peak_freq']/1e6:10.4f} MHz  {e['peak_db']:7.1f} dBFS "
              f"(+{e['increase_db']:.1f} dB, 폭 "
              f"{(e['f_hi']-e['f_lo'])/1e3:.1f} kHz)")
    tag = args.tag or f"compare_{_stamp()}"
    report.save_json({"baseline": args.baseline, "events": events},
                     _outpath(args, f"{tag}.json"))
    if not args.no_plot:
        report.plot_spectrum(spec, _outpath(args, f"{tag}.png"),
                             title="Compare vs baseline", baseline=base,
                             markers=[(e["peak_freq"], e["peak_db"],
                                       f"+{e['increase_db']:.0f}dB")
                                      for e in events])
    print(f"저장: {args.out}/{tag}.(json|png)")


def cmd_monitor(args):
    """Repeated sweeps; logs emissions that appear vs. the first (or given)
    reference. Catches intermittent interferers."""
    src = open_source(args)
    log_path = _outpath(args, args.tag or f"monitor_{_stamp()}.csv")
    try:
        base = (report.load_spectrum(args.baseline) if args.baseline
                else do_sweep(src, args, args.start, args.stop))
        hold = None
        with open(log_path, "w", encoding="utf-8") as log:
            log.write("time,peak_freq_hz,peak_dbfs,increase_db,width_hz\n")
            i = 0
            while args.count == 0 or i < args.count:
                i += 1
                spec = do_sweep(src, args, args.start, args.stop, avg=1)
                hold = spec if hold is None else max_hold(hold, spec)
                ev = analysis.compare_to_baseline(spec, base, args.delta)
                now = dt.datetime.now().isoformat(timespec="seconds")
                for e in ev:
                    log.write(f"{now},{e['peak_freq']:.0f},{e['peak_db']:.1f},"
                              f"{e['increase_db']:.1f},"
                              f"{e['f_hi']-e['f_lo']:.0f}\n")
                    print(f"{now}  {e['peak_freq']/1e6:10.4f} MHz  "
                          f"+{e['increase_db']:.1f} dB")
                log.flush()
                if not ev and not args.quiet:
                    print(f"{now}  이상 없음")
                if args.interval:
                    time.sleep(args.interval)
    except KeyboardInterrupt:
        pass
    finally:
        src.close()
    if hold is not None:
        report.save_spectrum(hold, log_path.replace(".csv", "_maxhold.csv"))
        if not args.no_plot:
            report.plot_spectrum(hold, log_path.replace(".csv", "_maxhold.png"),
                                 title="Max hold", baseline=base)
    print(f"로그: {log_path}")


def build_parser():
    p = argparse.ArgumentParser(
        prog="rfscan",
        description="RTL-SDR V4 간섭/스퓨리어스/SNR 간이 측정 도구")
    common = argparse.ArgumentParser(add_help=False)
    g = common.add_argument_group("수신기")
    g.add_argument("--sim", nargs="?", const="", default=None,
                   metavar="SCENARIO.json",
                   help="하드웨어 없이 시뮬레이터 사용 (파일 생략 시 기본 시나리오)")
    g.add_argument("--gain", default="30",
                   help="수신 이득 dB 또는 auto (기본 30; 측정에는 고정 이득 권장)")
    g.add_argument("--ppm", type=int, default=0, help="주파수 오차 보정")
    g.add_argument("--device", type=int, default=0)
    g.add_argument("--bias-tee", action="store_true", help="LNA 전원 공급")
    g.add_argument("--sample-rate", type=freq, default=DEFAULT_SAMPLE_RATE)
    g.add_argument("--nfft", type=int, default=2048,
                   help="FFT 크기 (RBW = sample_rate/nfft)")
    g.add_argument("--samples", type=int, default=262144,
                   help="스텝당 IQ 샘플 수 (많을수록 평균↑)")
    g.add_argument("--avg-sweeps", type=int, default=1)
    g.add_argument("--max-hold", action="store_true",
                   help="--avg-sweeps 를 평균 대신 max-hold 로 합침")
    o = common.add_argument_group("출력")
    o.add_argument("--out", default="results")
    o.add_argument("--tag", help="출력 파일 이름")
    o.add_argument("--no-plot", action="store_true")
    o.add_argument("--quiet", action="store_true")

    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("scan", parents=[common], help="광대역 스캔 + 신호 검출")
    s.add_argument("--start", type=freq, required=True)
    s.add_argument("--stop", type=freq, required=True)
    s.add_argument("--threshold", type=float, default=10.0,
                   help="노이즈 플로어 대비 검출 임계 dB")
    s.add_argument("--terminated", metavar="CSV",
                   help="안테나 대신 50Ω 종단 상태 스캔 결과 (동글 내부 스퓨리어스 표시)")
    s.set_defaults(func=cmd_scan)

    s = sub.add_parser("spur", parents=[common],
                       help="캐리어 주변 스퓨리어스/ACLR")
    s.add_argument("--carrier", type=freq, required=True)
    s.add_argument("--bw", type=freq, required=True, help="캐리어 대역폭")
    s.add_argument("--span", type=freq, help="분석 폭 (기본 4×BW)")
    s.add_argument("--limit-dbc", type=float, default=-40.0)
    s.add_argument("--threshold", type=float, default=10.0)
    s.add_argument("--terminated", metavar="CSV")
    s.add_argument("--fail-on-violation", action="store_true",
                   help="기준 초과 시 종료코드 1")
    s.set_defaults(func=cmd_spur)

    s = sub.add_parser("snr", parents=[common], help="대역 SNR 간이 측정")
    s.add_argument("--freq", type=freq, required=True)
    s.add_argument("--bw", type=freq, required=True)
    s.add_argument("--span", type=freq)
    s.add_argument("--noise-ref", type=freq, nargs=2, metavar=("LO", "HI"),
                   help="잡음 기준 구간 직접 지정")
    s.add_argument("--out-json")
    s.set_defaults(func=cmd_snr)

    s = sub.add_parser("compare", parents=[common],
                       help="기준 스캔 대비 새 신호(간섭원) 검출")
    s.add_argument("--baseline", required=True)
    s.add_argument("--start", type=freq)
    s.add_argument("--stop", type=freq)
    s.add_argument("--delta", type=float, default=6.0)
    s.set_defaults(func=cmd_compare)

    s = sub.add_parser("monitor", parents=[common],
                       help="반복 스캔으로 간헐 간섭 기록")
    s.add_argument("--start", type=freq, required=True)
    s.add_argument("--stop", type=freq, required=True)
    s.add_argument("--baseline")
    s.add_argument("--delta", type=float, default=6.0)
    s.add_argument("--interval", type=float, default=0.0, help="스윕 간격(초)")
    s.add_argument("--count", type=int, default=0, help="0 = 무한 (Ctrl+C)")
    s.set_defaults(func=cmd_monitor)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.gain != "auto":
        args.gain = float(args.gain)
    try:
        return args.func(args) or 0
    except (ValueError, RuntimeError) as e:
        print(f"오류: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
