#!/usr/bin/env python3
"""Phase 0 measurement driver.

    python3 phase0-poc/run_phase0.py                 # everything
    python3 phase0-poc/run_phase0.py --only accuracy  # one stage

Stages
  accuracy   full-file Basic Pitch transcription vs ground truth
  stream     chunked Basic Pitch (window x step grid): accuracy, latency,
             duplicate behaviour, per-step compute time
  nmf        the harmonic-NMF baseline on the same case grid
  resources  model load, memory, CPU totals for a long session

Results are written to phase0-poc/results/phase0_results.json and
phase0-poc/results/summary.md.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import platform
import resource
import shutil
import statistics
import sys
import tempfile
import time

import numpy as np

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

from pianosheet_poc.audio_io import load_wav, write_wav  # noqa: E402
from pianosheet_poc.basicpitch_onnx import BasicPitchOnnx  # noqa: E402
from pianosheet_poc.bp_live import BasicPitchStreaming  # noqa: E402
from pianosheet_poc.denoise import (  # noqa: E402
    DenoiseConfig,
    Denoiser,
    StreamingDenoiser,
    detect_mains_hz,
    highpass_response_db,
)
from pianosheet_poc.live import LiveConfig  # noqa: E402
from pianosheet_poc.metrics import (  # noqa: E402
    chord_report,
    load_ground_truth,
    match_notes,
    summarize,
)
from pianosheet_poc.spectral_nmf import NMFDetector, NMFStreaming  # noqa: E402

FIXTURE_DIR = ROOT / "test-fixtures"
GT_DIR = FIXTURE_DIR / "ground_truth"
WAV_DIR = FIXTURE_DIR / "wav"
RESULTS_DIR = HERE / "results"
MODEL_PATH = pathlib.Path(os.environ.get("PIANOSHEET_BP_MODEL", HERE / "models" / "nmp.onnx"))

SAMPLE_RATE = 22050

ALL_TESTS = [
    "T-001",
    "T-002",
    "T-003",
    "T-004",
    "T-004b",
    "T-005",
    "T-006",
    "T-007",
    "T-008",
    "T-010",
    "T-011",
    "T-012",
    "T-014",
    "T-015",
]
SPARSE_TESTS = ["T-001", "T-002", "T-003", "T-004", "T-005", "T-006"]
NMF_TESTS = ["T-001", "T-002", "T-003", "T-004", "T-005", "T-006", "T-010"]


def fixtures(tests):
    out = []
    for tid in tests:
        gt = load_ground_truth(GT_DIR / f"{tid}.json")
        audio, sr = load_wav(ROOT / gt["wav"], SAMPLE_RATE)
        out.append((tid, gt, audio))
    return out


def peak_rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def cpu_time() -> float:
    return time.process_time()


def env_info() -> dict:
    try:
        mem_total = os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / 2**20
    except Exception:
        mem_total = None
    return {
        "python": platform.python_version(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "cpu_count": os.cpu_count(),
        "mem_total_mb": round(mem_total) if mem_total else None,
        "numpy": np.__version__,
        "onnxruntime": __import__("onnxruntime").__version__,
        "model_sha256": __import__("hashlib").sha256(MODEL_PATH.read_bytes()).hexdigest(),
        "model_bytes": MODEL_PATH.stat().st_size,
        "model_path": str(MODEL_PATH.relative_to(ROOT)),
    }


# --------------------------------------------------------------------------- #
# stage: accuracy (full-file)
# --------------------------------------------------------------------------- #
def stage_accuracy(model: BasicPitchOnnx) -> dict:
    rows = []
    for tid, gt, audio in fixtures(ALL_TESTS):
        t0 = time.perf_counter()
        events, info = model.transcribe(audio)
        elapsed = time.perf_counter() - t0
        score = match_notes(gt["refs"], events, onset_tol_s=0.05)
        score_loose = match_notes(gt["refs"], events, onset_tol_s=0.10)
        strong = [e for e in events if e["confidence"] >= 0.5]
        score_strong = match_notes(gt["refs"], strong, onset_tol_s=0.05)
        struck = [e for e in events if e.get("onset_strength", 1.0) >= 0.30]
        score_struck = match_notes(gt["refs"], struck, onset_tol_s=0.05)
        row = {
            "test_id": tid,
            "name": gt["name"],
            "n_notes": gt["n_notes"],
            "duration_s": gt["duration_s"],
            "detected": len(events),
            "detected_ge0.5": len(strong),
            "f1": score["f1"],
            "precision": score["precision"],
            "recall": score["recall"],
            "tp": score["tp"],
            "fp": score["fp"],
            "fn": score["fn"],
            "f1_tol100ms": score_loose["f1"],
            "f1_conf0.5": score_strong["f1"],
            "f1_onset0.3": score_struck["f1"],
            "n_onset0.3": len(struck),
            "onset_mae_ms": score["onset_mae_ms"],
            "onset_p90_ms": score["onset_p90_ms"],
            "offset_mae_ms": score["offset_mae_ms"],
            "missed": score["missed"],
            "spurious": score["spurious"][:12],
            "n_spurious": len(score["spurious"]),
            "chords": chord_report(gt["refs"], events) if gt["n_notes"] and _is_chordy(gt) else None,
            "inference_ms": round(elapsed * 1000, 1),
            "realtime_factor": round(info["compute_ms_total"] / (gt["duration_s"] * 1000), 4),
            "n_windows": info["n_windows"],
        }
        rows.append(row)
        print(
            f"  {tid:7s} F1={row['f1']:.3f} P={row['precision']:.3f} R={row['recall']:.3f} "
            f"det={row['detected']:3d}/{row['n_notes']:3d} onset_mae={row['onset_mae_ms']}ms "
            f"rtf={row['realtime_factor']:.3f}"
        )
    return {"fixtures": rows, "summary": summarize(rows)}


def _is_chordy(gt: dict) -> bool:
    from collections import Counter

    starts = Counter(round(n["onset_s"], 2) for n in gt["notes"])
    return any(c >= 3 for c in starts.values())


# --------------------------------------------------------------------------- #
# stage: streaming (Basic Pitch)
# --------------------------------------------------------------------------- #
def run_stream(model, tid, gt, audio, window_s, step_s, confidence_floor=0.0,
               onset_tol_ms=120.0, tail_merge_ms=120.0, guard_ms=260.0,
               denoiser_factory=None):
    cfg = LiveConfig(
        sample_rate=SAMPLE_RATE,
        window_s=window_s,
        step_s=step_s,
        onset_tolerance_ms=onset_tol_ms,
        tail_merge_ms=tail_merge_ms,
        confidence_floor=confidence_floor,
        guard_ms=guard_ms,
    )
    denoiser = denoiser_factory() if denoiser_factory is not None else None
    tr = BasicPitchStreaming(model, cfg, preprocess=None if denoiser is None else denoiser.process_chunk)
    # the denoiser holds the newest audio back until its window is complete; the
    # onsets keep the input timebase, so the delay is added to the latency only
    extra_latency_ms = 0.0 if denoiser is None else denoiser.delay_ms
    tr.start_session()

    step_samples = cfg.step_samples
    chunk = int(0.10 * SAMPLE_RATE)  # AudioRecord-like 100 ms reads
    wall0 = time.perf_counter()
    cpu0 = cpu_time()
    rss0 = peak_rss_mb()
    emitted = []
    pos = 0
    while pos < len(audio):
        emitted.extend(tr.push_audio_chunk(audio[pos : pos + chunk]))
        pos += chunk
    emitted.extend(tr.finish_session())
    wall = time.perf_counter() - wall0
    cpu = cpu_time() - cpu0
    rss = peak_rss_mb() - rss0

    events = [ev for ev in tr.events]
    est = [
        {
            "start_s": ev.onset_s,
            "end_s": ev.offset_s if ev.offset_s is not None else ev.onset_s + 0.1,
            "pitch_midi": ev.pitch_midi,
            "confidence": ev.confidence,
        }
        for ev in events
    ]
    score = match_notes(gt["refs"], est, onset_tol_s=0.05)
    latencies = sorted(
        (ev.emitted_at_s - ev.onset_s) * 1000.0 + extra_latency_ms
        for ev in events
        if ev.emitted_at_s is not None
    )
    compute = tr.step_compute_ms
    render_latency = sorted(
        (ev.emitted_at_s - ev.onset_s) * 1000.0 + (ev.render_ms or 0.0) + extra_latency_ms
        for ev in events
        if ev.emitted_at_s is not None
    )
    return {
        "window_s": window_s,
        "step_s": step_s,
        "guard_ms": guard_ms,
        "duration_s": round(len(audio) / SAMPLE_RATE, 2),
        "n_steps": len(tr.stats),
        "raw_candidates": tr.raw_candidates,
        "events": len(events),
        "duplicates_dropped": tr.duplicates_dropped,
        "stale_filtered": tr.stale_filtered,
        "weak_dropped": tr.weak_dropped,
        "low_confidence_dropped": tr.low_confidence_dropped,
        "duplicate_rate": round(tr.duplicates_dropped / max(1, tr.raw_candidates), 3),
        "stale_rate": round(tr.stale_filtered / max(1, tr.raw_candidates), 3),
        "f1": score["f1"],
        "precision": score["precision"],
        "recall": score["recall"],
        "tp": score["tp"],
        "fp": score["fp"],
        "fn": score["fn"],
        "onset_mae_ms": score["onset_mae_ms"],
        "latency_median_ms": round(statistics.median(latencies), 1) if latencies else None,
        "latency_p95_ms": round(latencies[int(0.95 * (len(latencies) - 1))], 1) if latencies else None,
        "latency_max_ms": round(max(latencies), 1) if latencies else None,
        "latency_mean_ms": round(statistics.mean(latencies), 1) if latencies else None,
        "render_latency_median_ms": round(statistics.median(render_latency), 1) if render_latency else None,
        "render_latency_p95_ms": round(render_latency[int(0.95 * (len(render_latency) - 1))], 1) if render_latency else None,
        "frac_within_2s": round(sum(1 for x in render_latency if x <= 2000) / len(render_latency), 4) if render_latency else None,
        "step_compute_median_ms": round(statistics.median(compute), 2) if compute else None,
        "step_compute_p95_ms": round(sorted(compute)[int(0.95 * (len(compute) - 1))], 2) if compute else None,
        "rtf": round(sum(compute) / (len(audio) / SAMPLE_RATE * 1000), 4) if compute else None,
        "wall_s": round(wall, 2),
        "cpu_s": round(cpu, 2),
        "cpu_percent": round(100.0 * cpu / wall, 1) if wall else None,
        "peak_rss_delta_mb": round(rss, 1),
        "missed": score["missed"][:10],
        "spurious": score["spurious"][:10],
        "denoise": None
        if denoiser is None
        else {
            "strength": denoiser.config.strength,
            "delay_ms": round(extra_latency_ms, 1),
            "n_chunks": len(denoiser.preprocess_ms),
            "preprocess_ms_median": round(statistics.median(denoiser.preprocess_ms), 2),
            "preprocess_ms_p95": round(sorted(denoiser.preprocess_ms)[int(0.95 * (len(denoiser.preprocess_ms) - 1))], 2),
            "preprocess_rtf": round(
                sum(denoiser.preprocess_ms) / (len(audio) / SAMPLE_RATE * 1000.0), 4
            ),
        },
    }


def stage_stream(model: BasicPitchOnnx) -> dict:
    grid = [
        (2.0, 0.25, 0.0),
        (2.0, 0.5, 0.0),
        (2.0, 0.25, 0.45),
        (1.5, 0.25, 0.0),
        (1.0, 0.25, 0.0),
        (2.0, 0.1, 0.0),
    ]
    cases = fixtures(ALL_TESTS)
    results = []
    for window_s, step_s, conf in grid:
        print(f"  window={window_s}s step={step_s}s confidence_floor={conf}")
        rows = []
        for tid, gt, audio in cases:
            r = run_stream(model, tid, gt, audio, window_s, step_s, confidence_floor=conf)
            r["test_id"] = tid
            r["confidence_floor"] = conf
            rows.append(r)
            print(
                f"    {tid:7s} F1={r['f1']:.3f} P={r['precision']:.3f} R={r['recall']:.3f} "
                f"lat={r['latency_median_ms']}ms p95={r['render_latency_p95_ms']}ms "
                f"dup={r['duplicate_rate']} rtf={r['rtf']}"
            )
        agg = _aggregate(rows)
        results.append({"window_s": window_s, "step_s": step_s, "confidence_floor": conf, "fixtures": rows, "aggregate": agg})
        print(f"    -> micro F1={agg['micro_f1']} mean_lat={agg['latency_median_ms']} raw={agg['raw_candidates']} events={agg['events']}")

    # how much of the newest audio must be withheld before an onset is committed
    sweep = []
    subset = [(tid, gt, audio) for tid, gt, audio in cases if tid in ("T-001", "T-003", "T-010", "T-015")]
    for guard in (120.0, 180.0, 260.0, 360.0):
        print(f"  guard={guard:.0f}ms")
        rows = []
        for tid, gt, audio in subset:
            r = run_stream(model, tid, gt, audio, 2.0, 0.25, guard_ms=guard)
            r["test_id"] = tid
            rows.append(r)
        agg = _aggregate(rows)
        sweep.append({"guard_ms": guard, "aggregate": agg})
        print(f"    -> micro F1={agg['micro_f1']} lat={agg['latency_median_ms']}ms p95={agg['render_latency_p95_max']}ms")
    return {"grid": results, "guard_sweep": sweep}


def _aggregate(rows):
    tp = sum(r["tp"] for r in rows)
    fp = sum(r["fp"] for r in rows)
    fn = sum(r["fn"] for r in rows)
    p = tp / (tp + fp) if tp + fp else 0.0
    rcl = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * p * rcl / (p + rcl) if p + rcl else 0.0

    def vals(key):
        return [r[key] for r in rows if r.get(key) is not None]

    lat = vals("latency_median_ms")
    lat95 = vals("render_latency_p95_ms")
    step95 = vals("step_compute_p95_ms")
    rtfs = vals("rtf")
    cpus = vals("cpu_percent")
    rss = vals("peak_rss_delta_mb")
    return {
        "micro_precision": round(p, 4),
        "micro_recall": round(rcl, 4),
        "micro_f1": round(f1, 4),
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "raw_candidates": sum(r["raw_candidates"] for r in rows),
        "events": sum(r["events"] for r in rows),
        "duplicates_dropped": sum(r["duplicates_dropped"] for r in rows),
        "stale_filtered": sum(r.get("stale_filtered", 0) for r in rows),
        "weak_dropped": sum(r.get("weak_dropped", 0) for r in rows),
        "duplicate_rate": round(
            sum(r["duplicates_dropped"] for r in rows) / max(1, sum(r["raw_candidates"] for r in rows)), 3
        ),
        "latency_median_ms": round(statistics.median(lat), 1) if lat else None,
        "render_latency_p95_max": max(lat95) if lat95 else None,
        "step_compute_p95_max": max(step95) if step95 else None,
        "rtf_max": max(rtfs) if rtfs else None,
        "cpu_percent_median": round(statistics.median(cpus), 1) if cpus else None,
        "peak_rss_delta_mb_max": max(rss) if rss else None,
    }


# --------------------------------------------------------------------------- #
# stage: NMF baseline
# --------------------------------------------------------------------------- #
def stage_baselines() -> dict:
    """DSP alternatives on the same fixtures: spectral peaks and harmonic NMF."""
    from pianosheet_poc.spectral_peaks import SpectralPeakDetector, SpectralPeakStreaming

    cases = fixtures(["T-001", "T-002", "T-003", "T-005", "T-006", "T-010", "T-011", "T-012", "T-015"])
    out: dict = {"peaks": {"offline": [], "stream": []}, "nmf": {"offline": []}}

    peaks = SpectralPeakDetector()
    for tid, gt, audio in cases:
        t0 = time.perf_counter()
        notes = peaks.detect(audio)
        elapsed = (time.perf_counter() - t0) * 1000
        score = match_notes(gt["refs"], notes, onset_tol_s=0.05)
        out["peaks"]["offline"].append(
            {
                "test_id": tid,
                "n_notes": gt["n_notes"],
                "detected": len(notes),
                "f1": score["f1"],
                "precision": score["precision"],
                "recall": score["recall"],
                "tp": score["tp"],
                "fp": score["fp"],
                "fn": score["fn"],
                "onset_mae_ms": score["onset_mae_ms"],
                "compute_ms": round(elapsed, 0),
                "rtf": round(elapsed / (len(audio) / SAMPLE_RATE * 1000), 4),
            }
        )
        print(f"  peaks {tid:7s} F1={score['f1']:.3f} det={len(notes):3d}/{gt['n_notes']:3d} {elapsed:.0f}ms")

    # streaming form (1.5 s window looks at the same 22 ms frames, but only the
    # newest audio is new, so the window can be shorter than Basic Pitch's 2 s)
    for tid, gt, audio in cases:
        cfg = LiveConfig(sample_rate=SAMPLE_RATE, window_s=1.5, step_s=0.25)
        tr = SpectralPeakStreaming(peaks, cfg)
        tr.start_session()
        chunk = int(0.10 * SAMPLE_RATE)
        pos = 0
        while pos < len(audio):
            tr.push_audio_chunk(audio[pos : pos + chunk])
            pos += chunk
        tr.finish_session()
        est = [
            {
                "start_s": ev.onset_s,
                "end_s": ev.offset_s or ev.onset_s + 0.1,
                "pitch_midi": ev.pitch_midi,
                "confidence": ev.confidence,
            }
            for ev in tr.events
        ]
        score = match_notes(gt["refs"], est, onset_tol_s=0.05)
        lat = sorted((ev.emitted_at_s - ev.onset_s) * 1000 for ev in tr.events if ev.emitted_at_s)
        out["peaks"]["stream"].append(
            {
                "test_id": tid,
                "f1": score["f1"],
                "precision": score["precision"],
                "recall": score["recall"],
                "tp": score["tp"],
                "fp": score["fp"],
                "fn": score["fn"],
                "detected": len(tr.events),
                "n_notes": gt["n_notes"],
                "latency_median_ms": round(statistics.median(lat), 1) if lat else None,
                "step_compute_median_ms": round(statistics.median(tr.step_compute_ms), 1),
                "rtf": round(sum(tr.step_compute_ms) / (len(audio) / SAMPLE_RATE * 1000), 3),
            }
        )
        print(f"  peaks-stream {tid:7s} F1={score['f1']:.3f} lat={out['peaks']['stream'][-1]['latency_median_ms']}ms")

    nmf = NMFDetector()
    for tid, gt, audio in cases:
        t0 = time.perf_counter()
        notes = nmf.detect(audio)
        elapsed = (time.perf_counter() - t0) * 1000
        score = match_notes(gt["refs"], notes, onset_tol_s=0.05)
        out["nmf"]["offline"].append(
            {
                "test_id": tid,
                "n_notes": gt["n_notes"],
                "detected": len(notes),
                "f1": score["f1"],
                "precision": score["precision"],
                "recall": score["recall"],
                "tp": score["tp"],
                "fp": score["fp"],
                "fn": score["fn"],
                "compute_ms": round(elapsed, 0),
            }
        )
        print(f"  nmf   {tid:7s} F1={score['f1']:.3f} det={len(notes):3d}/{gt['n_notes']:3d} {elapsed:.0f}ms")

    out["peaks"]["summary"] = summarize(out["peaks"]["offline"])
    out["peaks"]["stream_summary"] = summarize(out["peaks"]["stream"])
    out["nmf"]["summary"] = summarize(out["nmf"]["offline"])
    return out



# --------------------------------------------------------------------------- #
# stage: denoising (T-014/T-016..T-020 + clean controls)
# --------------------------------------------------------------------------- #
DENOISE_CASES = [
    ("T-014", "SNR 20dB＋60Hzハム（既存）"),
    ("T-016", "低周波（空調ランブル28/42/62Hz＋50Hzハム）"),
    ("T-017", "SNR 15dB＋50Hzハム"),
    ("T-018", "SNR 10dB＋60Hzハム"),
    ("T-019", "弱音(pp)＋SNR 10dB"),
    ("T-020", "ペダル＋SNR 10dB"),
]
DENOISE_CONTROLS = ["T-005", "T-008", "T-011", "T-012"]
DENOISE_MODES = ["off", "weak", "standard", "strong", "standard+notch"]
MODE_LABELS = {
    "off": "除去なし",
    "weak": "弱",
    "standard": "標準",
    "strong": "強",
    "standard+notch": "標準＋ハム自動",
}


def band_rms_db(audio: np.ndarray, lo_hz: float, hi_hz: float, sr: int = SAMPLE_RATE) -> float:
    """Energy in a frequency band, relative to full scale (dBFS)."""
    spec = np.abs(np.fft.rfft(audio.astype(np.float64) * np.hanning(len(audio))))
    freqs = np.fft.rfftfreq(len(audio), 1.0 / sr)
    band = spec[(freqs >= lo_hz) & (freqs < hi_hz)]
    if band.size == 0:
        return -240.0
    return round(20.0 * np.log10(float(np.sqrt(np.mean(band**2))) / (len(audio) / 2) + 1e-12), 1)


def tail_preservation_db(audio: np.ndarray, processed: np.ndarray, refs, tail_s: float = 0.45) -> float:
    """How much of the sound *after* each note's release survives (0 dB = untouched).

    A denoiser that eats decay tails or quiet notes shows up here even when the
    note-detection F1 does not move.
    """
    ratios = []
    for r in refs:
        a = int((r["end_s"] - 0.02) * SAMPLE_RATE)
        b = int((r["end_s"] + tail_s) * SAMPLE_RATE)
        if a < 0 or b > len(audio):
            continue
        before = float(np.sqrt(np.mean(audio[a:b] ** 2)))
        after = float(np.sqrt(np.mean(processed[a:b] ** 2)))
        if before > 1e-6:
            ratios.append(20.0 * np.log10(after / before))
    return round(statistics.mean(ratios), 2) if ratios else 0.0


def stage_denoise(model: BasicPitchOnnx) -> dict:
    """Denoise off/weak/standard/strong, final analysis and live, same scoring."""
    import functools

    ids = [tid for tid, _ in DENOISE_CASES] + DENOISE_CONTROLS
    labels = dict(DENOISE_CASES)
    rows = []
    for tid, gt, audio in fixtures(ids):
        group = "noise" if tid in labels else "control"
        print(f"  {tid:7s} {labels.get(tid, gt['name'])}")
        for mode in DENOISE_MODES:
            mains = detect_mains_hz(audio)
            if mode == "off":
                processed, dsp = audio, None
            else:
                strength = "standard" if mode == "standard+notch" else mode
                notch = mains if mode == "standard+notch" else None
                processed, dsp = Denoiser(
                    DenoiseConfig(strength=strength, notch_hz=notch)
                ).process_with_stats(audio)

            events, info = model.transcribe(processed)
            score = match_notes(gt["refs"], events, onset_tol_s=0.05)

            factory = None
            if mode != "off":
                strength = "standard" if mode == "standard+notch" else mode
                notch = mains if mode == "standard+notch" else None
                factory = functools.partial(
                    StreamingDenoiser, DenoiseConfig(strength=strength, notch_hz=notch)
                )
            live = run_stream(model, tid, gt, audio, 2.0, 0.25, denoiser_factory=factory)

            row = {
                "test_id": tid,
                "group": group,
                "condition": labels.get(tid, "クリーン（ノイズなし）"),
                "strength": mode,
                "n_notes": gt["n_notes"],
                "offline": {
                    "detected": len(events),
                    "f1": score["f1"],
                    "precision": score["precision"],
                    "recall": score["recall"],
                    "tp": score["tp"],
                    "fp": score["fp"],
                    "fn": score["fn"],
                    "onset_mae_ms": score["onset_mae_ms"],
                    "denoise_ms": None if dsp is None else dsp.compute_ms,
                    "denoise_rtf": None if dsp is None else dsp.rtf,
                    "input_rms_db": None if dsp is None else dsp.input_rms_db,
                    "output_rms_db": None if dsp is None else dsp.output_rms_db,
                    "noise_floor_db": None if dsp is None else dsp.noise_floor_db,
                    "mean_gain_db": None if dsp is None else dsp.mean_gain_db,
                    "tail_db": tail_preservation_db(audio, processed, gt["refs"]),
                },
                "live": {
                    "f1": live["f1"],
                    "precision": live["precision"],
                    "recall": live["recall"],
                    "detected": live["events"],
                    "latency_median_ms": live["latency_median_ms"],
                    "render_latency_p95_ms": live["render_latency_p95_ms"],
                    "frac_within_2s": live["frac_within_2s"],
                    "step_compute_p95_ms": live["step_compute_p95_ms"],
                    "denoise": live["denoise"],
                },
            }
            rows.append(row)
            d = row["offline"]
            print(
                f"    {mode:9s} offline F1={d['f1']:.3f} (P{d['precision']:.3f}/R{d['recall']:.3f}) "
                f"live F1={row['live']['f1']:.3f} lat={row['live']['latency_median_ms']}ms "
                f"tail={d['tail_db']:+.1f}dB "
                + (f"dsp={d['denoise_ms']}ms" if d["denoise_ms"] else "")
            )

    summary = {}
    for group in ("noise", "control"):
        for mode in DENOISE_MODES:
            sel = [r for r in rows if r["group"] == group and r["strength"] == mode]
            tp = sum(r["offline"]["tp"] for r in sel)
            fp = sum(r["offline"]["fp"] for r in sel)
            fn = sum(r["offline"]["fn"] for r in sel)
            p = tp / (tp + fp) if tp + fp else 0.0
            rcl = tp / (tp + fn) if tp + fn else 0.0
            ltp = sum(r["live"]["f1"] for r in sel) / len(sel) if sel else 0.0
            summary[f"{group}:{mode}"] = {
                "offline_micro_f1": round(2 * p * rcl / (p + rcl), 4) if p + rcl else 0.0,
                "offline_precision": round(p, 4),
                "offline_recall": round(rcl, 4),
                "live_mean_f1": round(ltp, 4),
                "mean_latency_median_ms": round(
                    statistics.mean([r["live"]["latency_median_ms"] for r in sel if r["live"]["latency_median_ms"]]), 1
                )
                if sel
                else None,
                "mean_denoise_ms": round(
                    statistics.mean([r["offline"]["denoise_ms"] for r in sel if r["offline"]["denoise_ms"]]), 1
                )
                if any(r["offline"]["denoise_ms"] for r in sel)
                else None,
            }

    # what the front-end filters actually do to the room noise itself
    hum = []
    for tid in ["T-014", "T-016", "T-017", "T-018"]:
        gt = load_ground_truth(GT_DIR / f"{tid}.json")
        audio, _ = load_wav(ROOT / gt["wav"], SAMPLE_RATE)
        mains = float(gt["noise"]["hum_hz"])
        detected = detect_mains_hz(audio)
        base = Denoiser(DenoiseConfig(strength="standard", notch_hz=None)).process(audio)
        notch = Denoiser(DenoiseConfig(strength="standard", notch_hz=detected)).process(audio) if detected else base
        f1_plain = match_notes(gt["refs"], model.transcribe(base)[0], onset_tol_s=0.05)["f1"]
        f1_notch = match_notes(gt["refs"], model.transcribe(notch)[0], onset_tol_s=0.05)["f1"]
        hum.append(
            {
                "test_id": tid,
                "hum_hz": mains,
                "hum_detected_hz": detected,
                "hum_db_before": band_rms_db(audio, mains - 1.0, mains + 1.0),
                "hum_db_subtract_only": band_rms_db(base, mains - 1.0, mains + 1.0),
                "hum_db_with_notch": band_rms_db(notch, mains - 1.0, mains + 1.0),
                "sub25_db_before": band_rms_db(audio, 0.0, 25.0),
                "sub25_db_after": band_rms_db(base, 0.0, 25.0),
                "f1_no_notch": f1_plain,
                "f1_with_notch": f1_notch,
            }
        )
        print(
            f"  hum {tid}: {mains:.0f}Hz (自動判定 {detected}) … {hum[-1]['hum_db_before']} dB → "
            f"{hum[-1]['hum_db_subtract_only']} (減算のみ) / {hum[-1]['hum_db_with_notch']} (ノッチ併用), "
            f"<25Hz {hum[-1]['sub25_db_before']} → {hum[-1]['sub25_db_after']} dB, "
            f"F1 {f1_plain} → {f1_notch}"
        )

    return {
        "rows": rows,
        "summary": summary,
        "hum": hum,
        "highpass_response_db": {
            str(f): round(float(highpass_response_db([f])[0]), 2)
            for f in (5, 10, 20, 25, 27.5, 32.7, 50, 60, 110, 440)
        },
        "delay_ms": {
            "offline": 0.0,
            "live": round(StreamingDenoiser(DenoiseConfig()).delay_ms, 1),
        },
    }

# --------------------------------------------------------------------------- #
# stage: resources / cold start
# --------------------------------------------------------------------------- #
def stage_resources() -> dict:
    base_rss = peak_rss_mb()
    t0 = time.perf_counter()
    model = BasicPitchOnnx(MODEL_PATH)
    load_ms = (time.perf_counter() - t0) * 1000.0
    rss_after_load = peak_rss_mb()

    gt = load_ground_truth(GT_DIR / "T-015.json")
    audio, _ = load_wav(ROOT / gt["wav"], SAMPLE_RATE)

    # single-window warm-up and steady-state timing
    r = model.predict_window(audio[:43844])
    single = [model.predict_window(audio[i : i + 43844]).compute_ms for i in range(0, 10)]

    # long session: stream the 24 s file with 0.25 s steps
    cfg = LiveConfig(sample_rate=SAMPLE_RATE, window_s=2.0, step_s=0.25)
    tr = BasicPitchStreaming(model, cfg)
    tr.start_session()
    cpu0, wall0 = cpu_time(), time.perf_counter()
    chunk = int(0.10 * SAMPLE_RATE)
    pos = 0
    while pos < len(audio):
        tr.push_audio_chunk(audio[pos : pos + chunk])
        pos += chunk
    tr.finish_session()
    cpu_s = cpu_time() - cpu0
    wall_s = time.perf_counter() - wall0

    # thread scaling: what one core costs versus two
    thread_scaling = {}
    for n_thread, label in ((1, "1"), (2, "2")):
        m2 = BasicPitchOnnx(MODEL_PATH, intra_op_threads=n_thread)
        for _ in range(3):
            m2.predict_window(audio[:43844])
        ts = [m2.predict_window(audio[1000 : 1000 + 43844]).compute_ms for _ in range(15)]
        thread_scaling[label] = {
            "median_ms": round(statistics.median(ts), 2),
            "min_ms": round(min(ts), 2),
            "max_ms": round(max(ts), 2),
        }

    # determinism check: run the same file twice, compare events
    def once():
        t = BasicPitchStreaming(model, cfg)
        t.start_session()
        p = 0
        while p < len(audio):
            t.push_audio_chunk(audio[p : p + chunk])
            p += chunk
        t.finish_session()
        return [(round(e.onset_ms), e.pitch_midi) for e in t.events]

    a, b = once(), once()
    return {
        "model_load_ms": round(load_ms, 1),
        "first_window_compute_ms": round(r.compute_ms, 2),
        "window_compute_median_ms": round(statistics.median(single), 2),
        "window_compute_min_ms": round(min(single), 2),
        "window_compute_max_ms": round(max(single), 2),
        "process_rss_before_mb": round(base_rss, 1),
        "process_rss_after_model_load_mb": round(rss_after_load, 1),
        "peak_rss_mb": round(peak_rss_mb(), 1),
        "long_session": {
            "audio_s": round(len(audio) / SAMPLE_RATE, 2),
            "wall_s": round(wall_s, 2),
            "cpu_s": round(cpu_s, 2),
            "cpu_percent": round(100.0 * cpu_s / wall_s, 1),
            "rtf": round(sum(tr.step_compute_ms) / (len(audio) / SAMPLE_RATE * 1000), 4),
            "steps": len(tr.stats),
            "events": len(tr.events),
            "raw_candidates": tr.raw_candidates,
            "duplicates_dropped": tr.duplicates_dropped,
        },
        "thread_scaling": thread_scaling,
        "deterministic": a == b,
        "n_events_run1": len(a),
        "n_events_run2": len(b),
    }


# --------------------------------------------------------------------------- #
# report tables
# --------------------------------------------------------------------------- #
def write_summary(results: dict) -> str:
    lines = []
    add = lines.append
    add("# Phase 0 実測サマリ（自動生成）\n")
    env = results.get("env", {})
    add(f"- 実行環境: {env.get('platform')} / CPU {env.get('cpu_count')} cores / "
        f"RAM {env.get('mem_total_mb')} MB / Python {env.get('python')} / onnxruntime {env.get('onnxruntime')}")
    add(f"- モデル: `{env.get('model_path')}` ({env.get('model_bytes')} bytes, sha256 `{str(env.get('model_sha256'))[:16]}…`)\n")

    acc = results.get("accuracy")
    if acc:
        add("## 1. 全体解析（Basic Pitch ONNX, ファイル全体）\n")
        add("| テスト | 内容 | 正解数 | 検出 | F1 | P | R | F1(±100ms) | F1(conf≥0.5) | F1(onset強度≥0.3) | onset誤差(中央値) | 実時間比 |")
        add("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for r in acc["fixtures"]:
            add(
                f"| {r['test_id']} | {r['name']} | {r['n_notes']} | {r['detected']} | "
                f"{r['f1']:.3f} | {r['precision']:.3f} | {r['recall']:.3f} | {r['f1_tol100ms']:.3f} | "
                f"{r['f1_conf0.5']:.3f} | {r.get('f1_onset0.3', 0):.3f} | {r['onset_mae_ms']} ms | {r['realtime_factor']:.3f} |"
            )
        s = acc["summary"]
        add(f"\n**マイクロ平均: P {s['micro_precision']} / R {s['micro_recall']} / F1 {s['micro_f1']}** "
            f"(TP {s['tp']} / FP {s['fp']} / FN {s['fn']})\n")

    bl = results.get("baselines")
    if bl:
        add("## 2. DSPベースライン（同一音源・同一スコアリング）\n")
        add("| 検出器 | 方式 | micro F1 | P | R | 備考 |")
        add("|---|---|---|---|---|---|")
        ps = bl["peaks"].get("summary", {})
        ps2 = bl["peaks"].get("stream_summary", {})
        ns = bl["nmf"].get("summary", {})
        add(f"| スペクトルピーク | STFTピーク＋倍音除去＋立ち上がり検出（全体解析） | "
            f"{ps.get('micro_f1')} | {ps.get('micro_precision')} | {ps.get('micro_recall')} | "
            f"T-003連打・T-010速い走句も検出 |")
        add(f"| スペクトルピーク | 同上を1.5秒窓・0.25秒ステップでストリーム実行 | "
            f"{ps2.get('micro_f1')} | {ps2.get('micro_precision')} | {ps2.get('micro_recall')} | |")
        add(f"| 倍音辞書NMF | KL-NMF（88鍵テンプレート） | "
            f"{ns.get('micro_f1')} | {ns.get('micro_precision')} | {ns.get('micro_recall')} | "
            f"辞書の音高分離が不十分で不採用 |")
        add("")
        add("| テスト | ピーク F1 | 検出/正解 | NMF F1 | ピーク計算時間 |")
        add("|---|---|---|---|---|")
        for row in bl["peaks"]["offline"]:
            nm = next((r for r in bl["nmf"]["offline"] if r["test_id"] == row["test_id"]), {})
            add(f"| {row['test_id']} | {row['f1']:.3f} | {row['detected']}/{row['n_notes']} | "
                f"{nm.get('f1', 0):.3f} | {row['compute_ms']:.0f} ms |")
        add("")
        add("> 合成音源は倍音構成が理想に近いため、DSPのピーク追跡が有利に出る。"
            "実機マイク・実ピアノ・ペダルでは差異が変わる見込みで、T-013（実機録音）での再評価が必要。\n")

    st = results.get("stream")
    if st:
        add("## 3. チャンク処理（Basic Pitch, ライブ想定）\n")
        add("| window | step | conf下限 | micro F1 | P | R | 候補数 → 確定済み除外 → 統合 → 新規表示 | 統合率 | 遅延 中央値 | 描画遅延 p95(最大) | ステップ推論 p95 | 実時間比 |")
        add("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for g in st["grid"]:
            a = g["aggregate"]
            add(
                f"| {g['window_s']}s | {g['step_s']}s | {g.get('confidence_floor', 0)} | {a['micro_f1']} | "
                f"{a['micro_precision']} | {a['micro_recall']} | "
                f"{a['raw_candidates']} → {a.get('stale_filtered', 0)} → {a['duplicates_dropped']} → {a['events']} | "
                f"{a['duplicate_rate']} | {a['latency_median_ms']} ms | "
                f"{a['render_latency_p95_max']} ms | {a['step_compute_p95_max']} ms | {a['rtf_max']} |"
            )
        add("")
        sw = st.get("guard_sweep")
        if sw:
            add("### 確定保留時間（guard）の影響 — window 2.0s / step 0.25s、T-001/T-003/T-010/T-015\n")
            add("| guard | micro F1 | P | R | 遅延 中央値 | 描画遅延 p95 |")
            add("|---|---|---|---|---|---|")
            for g in sw:
                a = g["aggregate"]
                add(f"| {g['guard_ms']:.0f} ms | {a['micro_f1']} | {a['micro_precision']} | {a['micro_recall']} | "
                    f"{a['latency_median_ms']} ms | {a['render_latency_p95_max']} ms |")
            add("")

    res = results.get("resources")
    if res:
        add("## 4. 起動・リソース（24秒セッション）\n")
        add(f"- モデルロード: {res['model_load_ms']} ms / 初回推論: {res['first_window_compute_ms']} ms")
        add(f"- 2秒窓の推論時間: 中央値 {res['window_compute_median_ms']} ms (min {res['window_compute_min_ms']} / max {res['window_compute_max_ms']})")
        tsc = res.get("thread_scaling", {})
        if tsc:
            add("- スレッド数と推論時間: 1スレッド " + str(tsc.get("1", {}).get("median_ms")) +
                " ms / 2スレッド " + str(tsc.get("2", {}).get("median_ms")) + " ms（2秒窓）")
        add(f"- プロセスRSS: 開始 {res['process_rss_before_mb']} MB → モデル読込後 {res['process_rss_after_model_load_mb']} MB → ピーク {res['peak_rss_mb']} MB")
        ls = res["long_session"]
        add(f"- {ls['audio_s']}s セッション: wall {ls['wall_s']}s / CPU {ls['cpu_s']}s ({ls['cpu_percent']}%), "
            f"ステップ数 {ls['steps']}, イベント {ls['events']} (候補 {ls['raw_candidates']}, 統合 {ls['duplicates_dropped']}), RTF {ls['rtf']}")
        add(f"- 同一入力での再現性: {'一致' if res['deterministic'] else '不一致'} ({res['n_events_run1']} / {res['n_events_run2']} イベント)\n")

    dnz = results.get("denoise")
    if dnz:
        add("## 5. ノイズ除去（DSP前処理）あり／なし\n")
        add("| 音源グループ | 強さ | 全体解析 micro F1 | P | R | ライブ平均 F1 | ライブ遅延(中央値) | DSP時間(8秒あたり) | 実時間比 |")
        add("|---|---|---|---|---|---|---|---|---|")
        for group, label in (("noise", "ノイズあり6音源"), ("control", "クリーン4音源")):
            for mode in DENOISE_MODES:
                a = dnz["summary"].get(f"{group}:{mode}")
                if not a:
                    continue
                rtf = (
                    round((a["mean_denoise_ms"] or 0) / 8250.0, 4) if a.get("mean_denoise_ms") else None
                )
                add(
                    f"| {label} | {MODE_LABELS.get(mode, mode)} | {a['offline_micro_f1']} | {a['offline_precision']} | "
                    f"{a['offline_recall']} | {a['live_mean_f1']} | {a['mean_latency_median_ms']} ms | "
                    f"{a['mean_denoise_ms'] if a.get('mean_denoise_ms') else '—'} ms | "
                    f"{rtf if rtf else '—'} |"
                )
        add("")
        add("| テスト | 部屋の条件 | 除去なし F1 | 弱 F1 | 標準 F1 | 強 F1 | 標準＋ハム F1 | ライブ(標準＋ハム) F1 | 余韻の残り(標準＋ハム) | ライブ遅延 なし→推奨 |")
        add("|---|---|---|---|---|---|---|---|---|---|")
        by_case = {}
        for row in dnz["rows"]:
            by_case.setdefault(row["test_id"], {})[row["strength"]] = row
        for tid, modes in by_case.items():
            m = modes.get("standard", {})
            rec = modes.get("standard+notch", m)
            off = modes.get("off", {})
            add(
                f"| {tid} | {off.get('condition', '')} | {off['offline']['f1']:.3f} | "
                f"{modes['weak']['offline']['f1']:.3f} | {m['offline']['f1']:.3f} | "
                f"{modes['strong']['offline']['f1']:.3f} | {rec['offline']['f1']:.3f} | "
                f"{rec['live']['f1']:.3f} | {rec['offline']['tail_db']:+.1f} dB | "
                f"{off['live']['latency_median_ms']} → {rec['live']['latency_median_ms']} ms |"
            )
        add("")
        add("### 前段フィルタの効果（ハム・超低域）\n")
        add("| テスト | ハム | 自動判定 | ハム成分 除去前 | 減算のみ | ＋ノッチ | 25Hz未満 除去前→後 | F1 ノッチなし→あり |")
        add("|---|---|---|---|---|---|---|---|")
        for h in dnz["hum"]:
            add(
                f"| {h['test_id']} | {h['hum_hz']:.0f} Hz | {h['hum_detected_hz'] or 'なし'} Hz | "
                f"{h['hum_db_before']} dB | {h['hum_db_subtract_only']} dB | {h['hum_db_with_notch']} dB | "
                f"{h['sub25_db_before']} → {h['sub25_db_after']} dB | "
                f"{h['f1_no_notch']:.3f} → {h['f1_with_notch']:.3f} |"
            )
        add("")
        hp = dnz.get("highpass_response_db", {})
        add("25Hz ハイパスの振幅応答: " + " / ".join(f"{f} Hz: {v} dB" for f, v in hp.items()) + "\n")
        add(f"- ライブ経路のDSP遅延（フレーム長1024＋ホップ256 @22.05kHz）: **{dnz['delay_ms']['live']} ms**")
        add("- 最終解析（ファイル全体）は遅延なし\n")

    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["accuracy", "stream", "baselines", "denoise", "resources"], action="append")
    ap.add_argument("--out", default=str(RESULTS_DIR / "phase0_results.json"))
    args = ap.parse_args()
    stages = args.only or ["resources", "accuracy", "baselines", "stream", "denoise"]

    if not MODEL_PATH.exists():
        print(f"model not found: {MODEL_PATH}\nrun scripts/fetch_basic_pitch_model.py first")
        return 2

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = pathlib.Path(args.out)
    results = {}
    if out_path.exists():
        try:
            results = json.loads(out_path.read_text())
        except Exception:
            results = {}
    results["env"] = env_info()

    model = BasicPitchOnnx(MODEL_PATH)
    print("model loaded:", MODEL_PATH.name, results["env"]["model_sha256"][:12])

    if "resources" in stages:
        print("[resources]")
        results["resources"] = stage_resources()
    if "accuracy" in stages:
        print("[accuracy] full-file")
        results["accuracy"] = stage_accuracy(model)
    if "baselines" in stages:
        print("[baselines] spectral peaks / NMF")
        results["baselines"] = stage_baselines()
    if "denoise" in stages:
        print("[denoise] noise removal off/weak/standard/strong")
        results["denoise"] = stage_denoise(model)
    if "stream" in stages:
        print("[stream] chunked basic pitch")
        results["stream"] = stage_stream(model)

    results["generated_at"] = time.strftime("%Y-%m-%d %H:%M:%S")
    out_path.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (RESULTS_DIR / "summary.md").write_text(write_summary(results), encoding="utf-8")
    print(f"\nwrote {out_path} and {RESULTS_DIR / 'summary.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
