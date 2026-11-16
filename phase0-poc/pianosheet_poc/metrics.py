"""Scoring: compare transcribed note events with the fixture ground truth."""

from __future__ import annotations

import json
import pathlib
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


def load_ground_truth(path: str | pathlib.Path) -> dict:
    d = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    refs = [
        {
            "start_s": n["onset_s"],
            "end_s": n["offset_s"],
            "pitch_midi": n["pitch_midi"],
            "confidence": 1.0,
        }
        for n in d["notes"]
    ]
    d["refs"] = refs
    d["n_notes"] = len(refs)
    return d


def match_notes(
    refs: Sequence[dict],
    ests: Sequence[dict],
    onset_tol_s: float = 0.05,
) -> Dict[str, object]:
    """Greedy one-to-one matching on (pitch, onset within tolerance).

    `refs` / `ests` are dicts with start_s, end_s, pitch_midi, confidence.
    """
    pairs: List[Tuple[float, int, int]] = []
    for i, r in enumerate(refs):
        for j, e in enumerate(ests):
            if int(r["pitch_midi"]) != int(e["pitch_midi"]):
                continue
            dt = float(e["start_s"]) - float(r["start_s"])
            if abs(dt) <= onset_tol_s:
                pairs.append((abs(dt), i, j))
    pairs.sort()
    used_r, used_e = set(), set()
    matched: List[Tuple[int, int]] = []
    for _, i, j in pairs:
        if i in used_r or j in used_e:
            continue
        used_r.add(i)
        used_e.add(j)
        matched.append((i, j))

    tp = len(matched)
    fp = len(ests) - tp
    fn = len(refs) - tp
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0

    onset_errors = [
        abs(float(ests[j]["start_s"]) - float(refs[i]["start_s"])) * 1000.0
        for i, j in matched
    ]
    offset_errors = [
        abs(float(ests[j].get("end_s", 0.0)) - float(refs[i].get("end_s", i * 0 + refs[i]["start_s"]))) * 1000.0
        for i, j in matched
        if ests[j].get("end_s") is not None
    ]
    onset_errors.sort()
    missed = [refs[i] for i in range(len(refs)) if i not in used_r]
    spurious = [ests[j] for j in range(len(ests)) if j not in used_e]

    return {
        "n_ref": len(refs),
        "n_est": len(ests),
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": round(precision, 4),
        "recall": round(recall, 4),
        "f1": round(f1, 4),
        "onset_mae_ms": round(sum(onset_errors) / len(onset_errors), 2) if onset_errors else None,
        "onset_median_ms": round(onset_errors[len(onset_errors) // 2], 2) if onset_errors else None,
        "onset_p90_ms": round(onset_errors[int(0.9 * (len(onset_errors) - 1))], 2) if onset_errors else None,
        "offset_mae_ms": round(sum(offset_errors) / len(offset_errors), 2) if offset_errors else None,
        "missed": [
            {"pitch_midi": int(m["pitch_midi"]), "start_s": round(float(m["start_s"]), 3)}
            for m in missed
        ],
        "spurious": [
            {"pitch_midi": int(s["pitch_midi"]), "start_s": round(float(s["start_s"]), 3)}
            for s in spurious
        ],
    }


def chord_report(refs: Sequence[dict], ests: Sequence[dict], group_window_s: float = 0.06) -> List[dict]:
    """Per-chord (simultaneity group) detection completeness."""
    groups: List[List[dict]] = []
    for r in sorted(refs, key=lambda x: x["start_s"]):
        if groups and abs(r["start_s"] - groups[-1][0]["start_s"]) <= group_window_s:
            groups[-1].append(r)
        else:
            groups.append([r])
    out = []
    for g in groups:
        start = g[0]["start_s"]
        wanted = {int(r["pitch_midi"]) for r in g}
        got = {
            int(e["pitch_midi"])
            for e in ests
            if abs(float(e["start_s"]) - start) <= 0.09
        }
        out.append(
            {
                "t_s": round(start, 3),
                "expected": sorted(wanted),
                "detected": sorted(wanted & got),
                "missing": sorted(wanted - got),
                "found": f"{len(wanted & got)}/{len(wanted)}",
            }
        )
    return out


def summarize(per_fixture: Iterable[dict]) -> dict:
    items = [p for p in per_fixture if "precision" in p]
    if not items:
        return {}
    tp = sum(p["tp"] for p in items)
    fp = sum(p["fp"] for p in items)
    fn = sum(p["fn"] for p in items)
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "micro_precision": round(precision, 4),
        "micro_recall": round(recall, 4),
        "micro_f1": round(f1, 4),
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "fixtures": len(items),
    }
