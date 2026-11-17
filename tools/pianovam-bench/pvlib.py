"""PianoVAM benchmark helpers: ground truth, excerpt selection, metrics."""
from __future__ import annotations

import json
import os
import wave

import numpy as np

from paths import DATA_ROOT as ROOT  # noqa: E402
META = json.load(open(os.path.join(ROOT, "metadata.json")))

# id -> (excerpt length s, description)
PIECES = {
    "29": ("Yiruma - Kiss The Rain", 40.0),
    "60": ("D. Scarlatti - Sonata K.360", 40.0),
    "74": ("Hanon - Exercises No.1", 40.0),
    "12": ("N. Tsuru - Last Carnival", 40.0),
    "9": ("R. Schubert - Piano Sonata No.19", 40.0),
    "70": ("Junhyung - Improvisation", 40.0),
}


def read_tsv(path: str) -> list[dict]:
    rows = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("\t")
            rows.append(
                {
                    "onset": float(p[0]),
                    "key_offset": float(p[1]),
                    "offset": float(p[2]),
                    "pitch": int(p[3]),
                    "velocity": int(p[4]),
                }
            )
    rows.sort(key=lambda r: (r["onset"], r["pitch"]))
    return rows


def gt_for(pid: str) -> list[dict]:
    rt = META[pid]["record_time"]
    return read_tsv(os.path.join(ROOT, "TSV", f"{rt}.tsv"))


def read_wav_mono(path: str, start_s: float = 0.0, end_s: float | None = None) -> tuple[np.ndarray, int]:
    with wave.open(path) as w:
        sr = w.getframerate()
        ch = w.getnchannels()
        sw = w.getsampwidth()
        i0 = int(round(start_s * sr))
        n = int(round(((end_s if end_s is not None else 1e12) - start_s) * sr))
        w.setpos(min(i0, w.getnframes()))
        raw = w.readframes(max(0, min(n, w.getnframes() - i0)))
    dtype = {2: np.int16, 4: np.int32}[sw]
    data = np.frombuffer(raw, dtype=dtype).astype(np.float32)
    scale = 32768.0 if sw == 2 else 2147483648.0
    data /= scale
    if ch > 1:
        data = data.reshape(-1, ch).mean(axis=1)
    return data, sr


def write_wav(path: str, data: np.ndarray, sr: int) -> None:
    pcm = np.clip(data, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def pick_excerpt(gt: list[dict], length_s: float, audio_len_s: float) -> tuple[float, dict]:
    """Pick the busiest window of `length_s` that stays inside the recording."""
    if audio_len_s <= length_s:
        return 0.0, {"notes": len(gt)}
    onsets = np.array([r["onset"] for r in gt])
    best = (0.0, -1)
    start = 0.0
    while start + length_s <= audio_len_s:
        n = int(np.searchsorted(onsets, start + length_s) - np.searchsorted(onsets, start))
        if n > best[1]:
            best = (start, n)
        start += 2.0
    start = best[0]
    # Snap to the first note of the window, leaving a small lead-in.
    inside = [r for r in gt if start <= r["onset"] < start + length_s]
    if inside:
        start = max(0.0, inside[0]["onset"] - 0.15)
    return start, {"notes": best[1]}


def prepare_excerpts(length_default: float = 40.0, force: bool = False) -> dict:
    out = {}
    os.makedirs(os.path.join(ROOT, "excerpts"), exist_ok=True)
    for pid, (title, length) in PIECES.items():
        rt = META[pid]["record_time"]
        src = os.path.join(ROOT, "Audio", f"{rt}.wav")
        with wave.open(src) as w:
            audio_len = w.getnframes() / w.getframerate()
        gt = gt_for(pid)
        start, info = pick_excerpt(gt, length, audio_len)
        end = min(audio_len, start + length)
        data, sr = read_wav_mono(src, start, end)
        path44 = os.path.join(ROOT, "excerpts", f"pv{pid}.wav")
        if force or not os.path.exists(path44):
            write_wav(path44, data, sr)
        sub = [r for r in gt if start <= r["onset"] < end]
        for r in sub:
            r["onset"] -= start
            r["offset"] -= start
            r["key_offset"] -= start
        gtpath = os.path.join(ROOT, "excerpts", f"pv{pid}.gt.json")
        json.dump(
            {"id": pid, "title": title, "start_s": start, "length_s": end - start,
             "instrument": META[pid]["composer"], "skill": META[pid]["P1_skill"],
             "notes": sub},
            open(gtpath, "w"), indent=1,
        )
        out[pid] = {"title": title, "start": start, "length": round(end - start, 2),
                    "gt_notes": len(sub), "sr": sr, "path": path44}
        print(pid, out[pid], flush=True)
    return out


# --------------------------------------------------------------------- metrics

def load_gt_excerpt(pid: str) -> list[dict]:
    return json.load(open(os.path.join(ROOT, "excerpts", f"pv{pid}.gt.json")))["notes"]


def match(ref: list[dict], est: list[dict], tol: float = 0.05):
    """Greedy one-to-one matching by pitch then closest onset within tol."""
    by_pitch: dict[int, list[int]] = {}
    for i, e in enumerate(est):
        by_pitch.setdefault(e["pitch"], []).append(i)
    used = [False] * len(est)
    pairs = []
    for ri, r in enumerate(ref):
        best, best_dt = -1, 1e9
        for i in by_pitch.get(r["pitch"], []):
            if used[i]:
                continue
            dt = abs(est[i]["onset"] - r["onset"])
            if dt <= tol and dt < best_dt:
                best, best_dt = i, dt
        if best >= 0:
            used[best] = True
            pairs.append((ri, best))
    matched = len(pairs)
    p = matched / len(est) if est else 0.0
    rec = matched / len(ref) if ref else 0.0
    f = 2 * p * rec / (p + rec) if p + rec else 0.0
    return {"matched": matched, "n_ref": len(ref), "n_est": len(est),
            "tp": matched, "fn": len(ref) - matched, "fp": len(est) - matched,
            "precision": p, "recall": rec, "f1": f, "pairs": pairs}


def chord_accuracy(ref: list[dict], est: list[dict], cluster: float = 0.045, tol: float = 0.06) -> dict:
    """Share of struck chords (2+ simultaneous notes) reproduced with the same pitch set."""
    def groups(notes):
        g = []
        for n in sorted(notes, key=lambda z: z["onset"]):
            if g and n["onset"] - g[-1][0]["onset"] <= cluster:
                g[-1].append(n)
            else:
                g.append([n])
        return [sorted({n["pitch"] for n in grp}) for grp in g]

    ref_groups = [g for g in _group_lists(ref, cluster) if len(g) >= 2]
    est_groups = _group_lists(est, cluster)
    if not ref_groups:
        return {"ref_chords": 0, "matched": 0, "accuracy": None}
    ok = 0
    for chord in ref_groups:
        t = min(n["onset"] for n in chord)
        pitches = set()
        for g in est_groups:
            if abs(min(n["onset"] for n in g) - t) <= tol:
                pitches |= {n["pitch"] for n in g}
        if {n["pitch"] for n in chord} <= pitches:
            ok += 1
    return {"ref_chords": len(ref_groups), "matched": ok, "accuracy": ok / len(ref_groups)}


def _group_lists(notes, cluster):
    g = []
    for n in sorted(notes, key=lambda z: z["onset"]):
        if g and n["onset"] - g[-1][0]["onset"] <= cluster:
            g[-1].append(n)
        else:
            g.append([n])
    return g


def duplicates(est: list[dict], min_gap: float = 0.10, overlap_slack: float = 0.02) -> int:
    """Same pitch repeated closer than a hand can strike, or overlapping the same pitch."""
    count = 0
    last: dict[int, dict] = {}
    for n in sorted(est, key=lambda z: z["onset"]):
        prev = last.get(n["pitch"])
        if prev is not None:
            gap = n["onset"] - prev["onset"]
            overlaps = n["onset"] < prev["onset"] + prev.get("duration", 0.0) - overlap_slack
            if gap < min_gap or overlaps:
                count += 1
        last[n["pitch"]] = n
    return count


def timing_errors(ref: list[dict], est: list[dict], pairs) -> dict:
    on_err, off_err, dur_err = [], [], []
    for ri, ei in pairs:
        r, e = ref[ri], est[ei]
        on_err.append((e["onset"] - r["onset"]) * 1000.0)
        if "offset" in e:
            off_err.append((e["offset"] - r["offset"]) * 1000.0)
            dur_err.append(((e["offset"] - e["onset"]) - (r["offset"] - r["onset"])) * 1000.0)
    def stat(v):
        if not v:
            return None
        a = np.array(v)
        return {"mean": float(a.mean()), "median": float(np.median(a)),
                "p95_abs": float(np.percentile(np.abs(a), 95))}
    return {"onset_ms": stat(on_err), "offset_ms": stat(off_err),
            "duration_ms": stat(dur_err), "n": len(pairs)}


def pitch_class_profile(notes: list[dict]) -> np.ndarray:
    w = np.zeros(12)
    for n in notes:
        w[n["pitch"] % 12] += n.get("duration", 0.2)
    return w


def summary(ref, est, label, tol=0.05):
    m = match(ref, est, tol)
    out = {
        "label": label,
        "onset_tol_s": tol,
        **{k: m[k] for k in ("n_ref", "n_est", "tp", "fn", "fp")},
        "precision": round(m["precision"], 4),
        "recall": round(m["recall"], 4),
        "f1": round(m["f1"], 4),
        "chord": chord_accuracy(ref, est),
        "duplicates": duplicates(est),
        "timing": timing_errors(ref, est, m["pairs"]),
    }
    return out
