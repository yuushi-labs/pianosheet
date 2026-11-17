"""Reference (ground-truth side) analyses: tempo, meter, key, accent grid."""
from __future__ import annotations

import numpy as np

import pvlib

RATIOS = (0.5, 2 / 3, 1.0, 3 / 2, 2.0)


def audio_tempo(audio22: np.ndarray, sr: int = 22050) -> dict:
    """Independent beat tracking on the audio itself (librosa)."""
    import librosa

    onset_env = librosa.onset.onset_strength(y=audio22.astype(np.float32), sr=sr, hop_length=256)
    bpm, beats = librosa.beat.beat_track(onset_envelope=onset_env, sr=sr, hop_length=256,
                                         start_bpm=100.0, tightness=100.0)
    bpm = float(np.atleast_1d(bpm)[0])
    return {"bpm": bpm, "beats_s": (np.asarray(beats) * 256 / sr).tolist()}


def onset_tempo(gt: list[dict]) -> dict:
    """IOI histogram of the ground-truth notes (median of consistent beat multiples)."""
    on = np.array(sorted(r["onset"] for r in gt))
    d = np.diff(on)
    d = d[(d > 0.08) & (d < 2.5)]
    if d.size == 0:
        return {"bpm": None}
    hist, edges = np.histogram(d, bins=np.arange(0.08, 2.5, 0.005))
    # take the strongest short interval and its multiples to a plausible beat
    best = edges[int(np.argmax(hist))]
    beat = best
    while beat < 0.4:  # < 150 BPM
        beat *= 2
    return {"bpm": 60.0 / beat, "mode_interval_s": float(best)}


def tempo_accuracy(est_bpm: float, ref_bpm: float) -> dict:
    if not est_bpm or not ref_bpm:
        return {"ratio": None, "within_2pct": None, "within_4pct": None, "octave_exact": None}
    ratio = est_bpm / ref_bpm
    best = min(RATIOS, key=lambda r: abs(np.log(ratio / r)))
    rel = abs(est_bpm - ref_bpm * best) / (ref_bpm * best)
    return {"ratio": round(float(ratio), 3), "folded_ratio": best,
            "within_2pct": bool(rel <= 0.02), "within_4pct": bool(rel <= 0.04),
            "exact_no_octave": bool(abs(rel) <= 0.04 and abs(best - 1.0) < 1e-9),
            "exact_with_octave": bool(rel <= 0.04)}


def meter_from_gt(gt: list[dict], bpm: float, candidates=((4, 4), (3, 4), (2, 4), (6, 8))):
    """Meter whose downbeat pattern best explains the accents of the GT notes.

    The GT MIDI carries no time signature, so this is a reference *estimate* from
    the ground-truth note times, not the printed score.
    """
    beat = 60.0 / bpm
    t0 = min(r["onset"] for r in gt)
    beats = [(r["onset"] - t0) / beat for r in gt]
    weights = [0.5 + 0.5 * min(2.0, r["offset"] - r["onset"]) for r in gt]
    grid = 0.25
    slots = int(12 / grid)
    best, best_score = candidates[0], 0.0
    for cand in candidates:
        per = int(round((cand[0] * 4.0 / cand[1]) / grid))
        prof = np.zeros(slots)
        for b, w in zip(beats, weights):
            prof[int(round(b / grid)) % slots] += w
        active = [i for i in range(slots) if prof[i] > 1e-9]
        down = [i for i in active if i % per == 0]
        others = [i for i in active if i % per != 0]
        if not down or not others:
            continue
        score = (sum(prof[i] for i in down) / len(down)) / (sum(prof[i] for i in others) / len(others))
        if score > best_score:
            best, best_score = cand, score
    return best, round(best_score, 3)


def key_from_gt(gt: list[dict]) -> dict:
    """music21's key analysis of the ground-truth notes (independent of our notes)."""
    from music21 import chord, key, stream, note

    s = stream.Stream()
    t = 0.0
    for r in sorted(gt, key=lambda z: z["onset"]):
        if r["onset"] > t:
            s.insert(t, note.Rest(quarterLength=max(0.0, (r["onset"] - t) * 2)))
            t = r["onset"]
        n = note.Note(r["pitch"])
        n.quarterLength = max(0.1, (r["offset"] - r["onset"]) * 2)
        s.insert(r["onset"] * 2, n)
    k = s.analyze("key")
    return {"tonic": k.tonic.name, "mode": k.mode, "correlation": round(float(k.correlationCoefficient), 3),
            "name": f"{k.tonic.name} {k.mode}"}


FIFTHS_MAJOR = {"C": 0, "G": 1, "D": 2, "A": 3, "E": 4, "B": 5, "F#": 6, "C#": 7,
                "F": -1, "Bb": -2, "Eb": -3, "Ab": -4, "Db": -5, "Gb": -6, "Cb": -7}
RELATIVE_MAJOR = {"A": "C", "E": "G", "B": "D", "F#": "A", "C#": "E", "G#": "B",
                  "D#": "F#", "A#": "C#", "D": "F", "G": "Bb", "C": "Eb", "F": "Ab"}


def key_to_fifths(name: str, mode: str) -> tuple[int, str] | None:
    if mode == "major":
        return (FIFTHS_MAJOR.get(name, 0), "MAJOR")
    rel = RELATIVE_MAJOR.get(name)
    if rel is None:
        return None
    return (FIFTHS_MAJOR[rel], "MINOR")


def downbeat_alignment(est_notes: list[dict], gt: list[dict], bar_beats: float, beat_sec: float,
                       origin_sec: float) -> dict:
    """Do our bar lines land on the GT's strongest attacks?

    Compares the total GT accent weight at our measure starts with the average
    weight at the other beat positions of the bar.  > 1 means bar lines sit on
    accents.
    """
    if not gt or beat_sec <= 0:
        return {"ratio": None, "bar_starts": 0}
    times = np.array([r["onset"] for r in gt])
    w = np.array([0.5 + 0.5 * min(2.0, r["offset"] - r["onset"]) for r in gt])
    tol = 0.08
    n_bars = max(1, int(np.ceil((times.max() - origin_sec) / (bar_beats * beat_sec))))
    on_bar, off_bar = [], []
    for m in range(n_bars):
        for beat in range(int(round(bar_beats))):
            t = origin_sec + (m * bar_beats + beat) * beat_sec
            sel = np.abs(times - t) <= tol
            val = float(w[sel].sum())
            (on_bar if beat == 0 else off_bar).append(val)
    if not off_bar:
        return {"ratio": None, "bar_starts": n_bars}
    on = float(np.mean(on_bar))
    off = float(np.mean(off_bar))
    return {"ratio": round(on / off, 3) if off > 0 else None, "bar_starts": n_bars,
            "on_bar_mean_weight": round(on, 2), "off_bar_mean_weight": round(off, 2)}
