"""Ablation runner over cached Basic Pitch window outputs."""
from __future__ import annotations

import itertools
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app_final as af  # noqa: E402
import app_pipeline as ap  # noqa: E402
import pvlib  # noqa: E402

from paths import DATA_ROOT as ROOT  # noqa: E402
CACHE = os.path.join(ROOT, "cache")
FRAME = ap.FRAME_SECONDS
IDS = ["29", "60", "74", "12", "9", "70"]


def pipeline_from_cache(pid, tag="h1", onset_threshold=0.5, frame_threshold=0.3,
                        min_note_length=11, melodia=True, filter_settings=None,
                        merge_tol=80.0, harmonic=True, harmonic_gate=72,
                        harmonic_h2=0.10, harmonic_h3=0.05, startup_guard=True,
                        extra_filter=None, score=True):
    z = np.load(os.path.join(CACHE, f"pv{pid}-{tag}.npz"))
    note, onset, windows, audio = z["note"], z["onset"], z["windows"], z["audio"]
    fs = filter_settings or ap.FilterSettings()
    results = []
    for i in range(note.shape[0]):
        wstart, wend = float(windows[i][0]), float(windows[i][1])
        notes = ap.decode(note[i], onset[i], onset_threshold=onset_threshold,
                          frame_threshold=frame_threshold,
                          min_note_length=min_note_length,
                          melodia_trick=melodia)
        dets = [ap.Detection(n.pitch_midi, wstart + n.start_frame * FRAME,
                             wstart + min(n.end_frame, ap.N_FRAMES) * FRAME,
                             n.amplitude, n.onset_strength) for n in notes]
        kept = ap.filter_candidates(dets, fs).kept
        kept2 = []
        for d in kept:
            if startup_guard and af.is_startup_artifact(d.onset_s, d.confidence, d.onset_strength):
                continue
            kept2.append(d)
        results.append(ap.WindowResult(wstart, wend, kept2))
    merged, mstats = ap.merge_detailed(results, tolerance_ms=merge_tol)
    kept = merged
    dropped = []
    examined = 0
    if harmonic:
        hf = af.HarmonicRealnessFilter(audio)
        kept, dropped, examined = hf.apply(merged, register_gate=harmonic_gate,
                                           min_h2=harmonic_h2, min_h3=harmonic_h3)
    if extra_filter is not None:
        kept = extra_filter(kept, audio)
    kept.sort(key=lambda n: (n.onset_ms, n.pitch_midi))
    for i, n in enumerate(kept):
        n.sequence = i
    out = {"notes": kept, "harmonic_dropped": len(dropped), "harmonic_examined": examined,
           "merge": mstats}
    if score:
        out["score"] = af.build_score(kept)
    return out


def metrics(pid, notes):
    gt = pvlib.load_gt_excerpt(pid)
    ref = [{"pitch": g["pitch"], "onset": g["onset"], "offset": g["offset"],
            "pedal": (g["offset"] - g["key_offset"]) > 0.05} for g in gt]
    est = [{"pitch": n.pitch_midi, "onset": n.onset_ms / 1000.0,
            "offset": (n.onset_ms + n.duration_ms) / 1000.0} for n in notes]
    s = pvlib.summary(ref, est, pid)
    np_ref = [r for r in ref if not r["pedal"]]
    m = pvlib.match(np_ref, est)
    s["timing_no_pedal"] = pvlib.timing_errors(np_ref, est, m["pairs"])
    return s


def evaluate(ids=IDS, **cfg):
    out = {}
    for pid in ids:
        r = pipeline_from_cache(pid, **cfg)
        out[pid] = metrics(pid, r["notes"])
        out[pid]["n_est"] = len(r["notes"])
    tp = sum(v["tp"] for v in out.values())
    fp = sum(v["fp"] for v in out.values())
    fn = sum(v["fn"] for v in out.values())
    p, rec = tp / (tp + fp), tp / (tp + fn)
    chords = [(v["chord"]["matched"], v["chord"]["ref_chords"]) for v in out.values()]
    return {
        "micro_f1": 2 * p * rec / (p + rec),
        "micro_p": p, "micro_r": rec,
        "macro_f1": float(np.mean([v["f1"] for v in out.values()])),
        "per": {k: {"f1": round(v["f1"], 3), "P": round(v["precision"], 3), "R": round(v["recall"], 3),
                    "n": v["n_est"], "chord": round(v["chord"]["accuracy"] or 0, 3)} for k, v in out.items()},
        "chord": round(sum(a for a, _ in chords) / max(1, sum(b for _, b in chords)), 4),
    }


if __name__ == "__main__":
    configs = []
    base = dict(onset_threshold=0.5, frame_threshold=0.3, min_note_length=11, melodia=True)
    # one-factor-at-a-time grid
    for ot in (0.5, 0.45, 0.4, 0.35, 0.3):
        configs.append(("onset_thr", ot, {**base, "onset_threshold": ot}))
    for ft in (0.3, 0.25, 0.2):
        configs.append(("frame_thr", ft, {**base, "frame_threshold": ft}))
    for ml in (11, 8, 6, 4):
        configs.append(("min_len", ml, {**base, "min_note_length": ml}))
    configs.append(("melodia", False, {**base, "melodia": False}))

    rows = []
    for name, value, cfg in configs:
        r = evaluate(**cfg)
        rows.append((name, value, r))
        print(f"{name:10s} {str(value):6s} microF1={r['micro_f1']:.4f} P={r['micro_p']:.3f} "
              f"R={r['micro_r']:.3f} macro={r['macro_f1']:.4f} chord={r['chord']:.3f}", flush=True)
        print("   ", {k: v["f1"] for k, v in r["per"].items()}, flush=True)
    json.dump([{"knob": n, "value": str(v), **r} for n, v, r in rows],
              open(os.path.join(ROOT, "results", "ablation-stage1.json"), "w"), indent=1)
