"""Prototype: chord-aware candidate filter (a chord is one attack).

A note that is part of a chord often has no onset peak of its own (its attack
shares the chord's onset activation), which is why the model reports it through
the melodia pass with a low onset strength.  The current floor (0.30) throws it
away.  The rule tested here: keep such a candidate when another candidate in the
same window starts within 40 ms with a strong onset.
"""
from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "phase0-poc"))
import app_final as af  # noqa: E402
import app_pipeline as ap  # noqa: E402
import pvlib  # noqa: E402

FRAME = ap.FRAME_SECONDS
CACHE = os.path.join(ROOT, "cache")


def chord_filter(dets, settings=None, mate_span=0.045, mate_onset=0.50, weak_floor=0.05):
    s = settings or ap.FilterSettings()
    strong = [d for d in dets if (d.onset_strength or 0) >= mate_onset]
    kept = []
    weak = short = high = overtone = mate_kept = 0
    for d in dets:
        duration = d.end_s - d.onset_s
        strength = d.onset_strength or 0.0
        is_mate = False
        if strength < s.min_onset_strength:
            if strength >= weak_floor and any(
                abs(o.onset_s - d.onset_s) <= mate_span and o.pitch_midi != d.pitch_midi
                for o in strong
            ):
                is_mate = True
            else:
                weak += 1
                continue
        if duration < s.min_duration_s:
            short += 1
            continue
        if d.pitch_midi >= s.high_pitch_midi:
            if strength < s.high_pitch_min_onset or duration < s.high_pitch_min_duration_s or d.confidence < s.high_pitch_min_confidence:
                high += 1
                continue
        if ap.is_overtone_of(d, dets, s) or ap.is_overtone_of(d, kept, s):
            overtone += 1
            continue
        kept.append(d)
        if is_mate:
            mate_kept += 1
    return kept, dict(weak=weak, short=short, high=high, overtone=overtone, mate=mate_kept)


def run(pid, onset_threshold=0.55, frame_threshold=0.30, min_note_length=6, melodia=True,
        short_rule=True, harmonic_owner=True, mate_span=0.045, mate_onset=0.50, tag="h1"):
    z = np.load(os.path.join(CACHE, f"pv{pid}-{tag}.npz"))
    note, onset, windows, audio = z["note"], z["onset"], z["windows"], z["audio"]
    results = []
    mates = 0
    for i in range(note.shape[0]):
        wstart, wend = float(windows[i][0]), float(windows[i][1])
        raw = ap.decode(note[i], onset[i], onset_threshold=onset_threshold,
                        frame_threshold=frame_threshold, min_note_length=min_note_length,
                        melodia_trick=melodia)
        dets = [ap.Detection(n.pitch_midi, wstart + n.start_frame * FRAME,
                             wstart + min(n.end_frame, ap.N_FRAMES) * FRAME,
                             n.amplitude, n.onset_strength) for n in raw]
        kept, st = chord_filter(dets, mate_span=mate_span, mate_onset=mate_onset)
        mates += st["mate"]
        out = []
        for d in kept:
            if af.is_startup_artifact(d.onset_s, d.confidence, d.onset_strength):
                continue
            out.append(d)
        results.append(ap.WindowResult(wstart, wend, out))
    merged, mstats = ap.merge_detailed(results, tolerance_ms=80.0)
    notes = merged
    if harmonic_owner:
        hf = af.HarmonicRealnessFilter(audio)
        kept2 = []
        for n in notes:
            if n.pitch_midi >= 72:
                ev = hf.evidence(n.pitch_midi, n.onset_ms / 1000)
                if ev["h2"] < 0.10 and ev["h3"] < 0.05 and any(
                    abs(m.onset_ms - n.onset_ms) <= 80 and (n.pitch_midi - m.pitch_midi) in (24, 36) for m in notes
                ):
                    continue
            kept2.append(n)
        notes = kept2
    if short_rule:
        notes = [n for n in notes if not ((n.duration_ms / 1000.0) < 0.12 and (n.onset_strength or 0) < 0.45)]
    notes.sort(key=lambda n: (n.onset_ms, n.pitch_midi))
    return notes, mates


def evaluate(ids=("29", "60", "74", "12", "9", "70"), **kw):
    per = {}
    tp = fp = fn = 0
    chords = [0, 0]
    for pid in ids:
        notes, mates = run(pid, **kw)
        gt = pvlib.load_gt_excerpt(pid)
        ref = [{"pitch": g["pitch"], "onset": g["onset"], "offset": g["offset"]} for g in gt]
        est = [{"pitch": n.pitch_midi, "onset": n.onset_ms / 1000, "offset": (n.onset_ms + n.duration_ms) / 1000}
               for n in notes]
        s = pvlib.summary(ref, est, pid)
        per[pid] = {"f1": s["f1"], "P": s["precision"], "R": s["recall"], "n": len(est),
                    "chord": s["chord"]["accuracy"], "mates": mates}
        tp += s["tp"]; fp += s["fp"]; fn += s["fn"]
        chords[0] += s["chord"]["matched"]; chords[1] += s["chord"]["ref_chords"]
    p, r = tp / (tp + fp), tp / (tp + fn)
    return {"micro_f1": 2 * p * r / (p + r), "p": p, "r": r, "per": per, "chord": chords[0] / max(1, chords[1])}


if __name__ == "__main__":
    for label, kw in (
        ("mateless (current)", dict(mate_onset=99.0)),
        ("mate span 40ms", dict(mate_span=0.040)),
        ("mate span 60ms", dict(mate_span=0.060)),
        ("mate onset 0.4", dict(mate_onset=0.40)),
        ("mate onset 0.6", dict(mate_onset=0.60)),
    ):
        res = evaluate(**kw)
        print(f"{label:20s} F1={res['micro_f1']:.4f} P={res['p']:.3f} R={res['r']:.3f} chord={res['chord']:.3f}",
              {k: round(v["f1"], 3) for k, v in res["per"].items()}, flush=True)
