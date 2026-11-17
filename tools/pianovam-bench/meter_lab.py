"""Prototype: meter with bar phase (anacrusis) + bar-line placement."""
from __future__ import annotations

import json
import os

import numpy as np

import app_final as af
from paths import DATA_ROOT as ROOT
import pvlib

CANDS = ((4, 4), (3, 4), (2, 4), (6, 8), (3, 8))
GRID = 0.25


def folded_profile(beats, weights, bar_slots):
    prof = np.zeros(bar_slots)
    for b, w in zip(beats, weights):
        if b < -1e-6 or w <= 0:
            continue
        prof[int(round(b / GRID)) % bar_slots] += w
    return prof


def best_phase(prof):
    """Index of the accent peak (coarse then fine): the most likely downbeat slot."""
    return int(np.argmax(prof))


def meter_with_phase(beats, weights, candidates=CANDS, compound_test=True):
    best = None
    rows = []
    for cand in candidates:
        bar_beats = cand[0] * 4.0 / cand[1]
        bar_slots = int(round(bar_beats / GRID))
        if bar_slots < 2:
            continue
        prof = folded_profile(beats, weights, bar_slots)
        shift = best_phase(prof)
        down = prof[shift]
        others = np.delete(prof, shift)
        # only slots that carry notes count, as in the current estimator
        active_others = others[others > 1e-9]
        score = down / active_others.mean() if active_others.size and active_others.mean() > 0 else 0.0
        rows.append({"meter": cand, "bar_beats": bar_beats, "score": score, "shift_slots": shift,
                     "coverage": float((prof > 1e-9).mean())})
    if not rows:
        return (4, 4), 0.0, 0
    # longer measures must clear a slightly higher bar so noise cannot invent one
    def rank(r):
        return r["score"] * (1.0 + 0.02 * (np.log2(r["bar_beats"] / 4.0) if r["bar_beats"] > 0 else 0))
    best = max(rows, key=rank)
    cand = best["meter"]
    if compound_test and cand == (3, 4):
        bar_slots = int(round(3.0 / GRID))
        prof = folded_profile(beats, weights, bar_slots)
        shift = best["shift_slots"]
        middle = prof[(shift + int(1.5 / GRID)) % bar_slots]
        second = prof[(shift + int(1.0 / GRID)) % bar_slots]
        third = prof[(shift + int(2.0 / GRID)) % bar_slots]
        if middle > (second + third) / 2.0 * 1.3 and middle > 1e-9:
            for r in rows:
                if r["meter"] == (6, 8):
                    cand = (6, 8)
                    best = r
                    break
    return cand, round(min(1.0, best["score"] / 2.0), 3), best["shift_slots"]


def load_notes(pid, kind):
    if kind == "gt":
        gt = pvlib.load_gt_excerpt(pid)
        return [{"pitch": g["pitch"], "onset": g["onset"], "offset": g["offset"]} for g in gt], None
    path = fos.path.join(ROOT, "results", f"baseline-{pid}-final-notes.json") if kind == "baseline" else kind
    return json.load(open(path)), None


def evaluate(notes, ref_bpm, tempo=None):
    onsets = [n["onset"] for n in notes]
    if tempo is None:
        w = [0.5 + 0.5 * min(2.0, n.get("offset", n["onset"] + 0.2) - n["onset"]) for n in notes]
        tempo, _ = af.tempo_estimate(onsets, w)
    beat = 60.0 / tempo
    origin = min(onsets)
    beats = [(o - origin) / beat for o in onsets]
    weights = [0.5 + 0.5 * min(2.0, n.get("offset", n["onset"] + 0.2) - n["onset"]) for n in notes]
    return tempo, meter_with_phase(beats, weights)


if __name__ == "__main__":
    refs = json.load(open(os.path.join(ROOT, "results", "baseline.json")))["pieces"]
    for kind in ("gt", "baseline"):
        print("==", kind)
        for pid in refs:
            notes, _ = load_notes(pid, kind)
            r = refs[pid]
            tempo, (meter, conf, shift) = evaluate(notes, r["refs"]["audio_bpm"])
            bar = meter[0] * 4.0 / meter[1]
            phase_beats = shift * GRID
            print(f"{pid} tempo={tempo:6.2f} meter={meter} conf={conf} phase={phase_beats:.2f} "
                  f"ref_meter={r['refs']['meter'][0]} ref_bpm={r['refs']['audio_bpm']} "
                  f"match={meter == tuple(r['refs']['meter'][0])}")
