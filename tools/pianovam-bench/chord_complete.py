"""Prototype: chord completion from spectral peaks at strong attacks.

The model reports some chord tones and misses the ones masked by the lowest
partials.  This adds a note when the spectrum at a strong attack carries a clean
semitone peak that no detected note explains as a partial.
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
HARMONICS = (12, 19, 24, 28, 31, 36)


class ChordCompleter:
    def __init__(self, audio, sr=22050, n_fft=2048, hop=256):
        self.sr = sr
        self.hop = hop
        n = len(audio)
        frames = 1 + (n - n_fft) // hop
        win = np.hanning(n_fft + 1)[:-1].astype(np.float32)
        idx = np.arange(n_fft)[None, :] + hop * np.arange(frames)[:, None]
        self.S = np.abs(np.fft.rfft(audio[idx] * win, axis=1)).T
        self.freqs = np.fft.rfftfreq(n_fft, 1.0 / sr)

    def _band(self, f, tol=0.02):
        m = (self.freqs >= f * (1 - tol)) & (self.freqs <= f * (1 + tol))
        return m

    def add_notes(self, notes, strong_onset=0.6, rel_floor=0.30, max_add=2,
                  neighbour_ratio=2.5, partial_margin=1.6):
        """Return extra Note-like objects (pitch, onset_ms, duration_ms, conf, strength)."""
        strong = [n for n in notes if (n.onset_strength or 0) >= strong_onset]
        extra = []
        for anchor in strong:
            i0 = int((anchor.onset_ms / 1000.0) * self.sr / self.hop)
            i1 = max(i0 + 1, int((anchor.onset_ms / 1000.0 + 0.10) * self.sr / self.hop))
            i0 = max(0, min(i0, self.S.shape[1] - 1))
            col = self.S[:, i0:min(i1, self.S.shape[1])].max(axis=1)
            # magnitude of the anchor's own fundamental
            f_anchor = 440.0 * 2 ** ((anchor.pitch_midi - 69) / 12)
            anchor_mag = float(col[self._band(f_anchor, 0.01)].max()) if self._band(f_anchor, 0.01).any() else 0.0
            if anchor_mag <= 0:
                continue
            candidates = []
            for pitch in range(21, 97):
                f = 440.0 * 2 ** ((pitch - 69) / 12)
                m = self._band(f, 0.012)
                if not m.any():
                    continue
                peak = float(col[m].max())
                if peak < rel_floor * anchor_mag:
                    continue
                # tonal peak: stands above its own neighbourhood
                lo = self._band(f * 2 ** (-1.5 / 12), 0.02)
                hi = self._band(f * 2 ** (1.5 / 12), 0.02)
                neigh = float(np.mean(np.concatenate([col[lo], col[hi]]))) if lo.any() and hi.any() else 0.0
                if neigh <= 0 or peak < neighbour_ratio * neigh:
                    continue
                # must not be a partial of something that is already detected
                explained = False
                for other in notes:
                    if abs(other.onset_ms - anchor.onset_ms) > 70:
                        continue
                    base = other.pitch_midi
                    if pitch == base:
                        explained = True
                        break
                    if (pitch - base) in HARMONICS:
                        f_base = 440.0 * 2 ** ((base - 69) / 12)
                        base_mag = float(col[self._band(f_base, 0.01)].max()) if self._band(f_base, 0.01).any() else 0.0
                        if base_mag > 0 and peak < partial_margin * base_mag * 0.3:
                            explained = True
                            break
                if explained:
                    continue
                if any(e["pitch"] == pitch and abs(e["onset"] - anchor.onset_ms / 1000.0) <= 0.05 for e in extra):
                    continue
                candidates.append((peak, pitch))
            candidates.sort(reverse=True)
            for peak, pitch in candidates[:max_add]:
                extra.append({
                    "pitch": pitch,
                    "onset": anchor.onset_ms / 1000.0,
                    "duration": min(0.5, max(0.1, anchor.duration_ms / 1000.0)),
                    "confidence": 0.35,
                    "strength": 0.30,
                })
        return extra


def run(pid, use_completion=True, **kw):
    z = np.load(os.path.join(CACHE, f"pv{pid}-h1.npz"))
    note, onset, windows, audio = z["note"], z["onset"], z["windows"], z["audio"]
    fs30 = ap.FilterSettings(min_onset_strength=0.30)
    results = []
    for i in range(note.shape[0]):
        ws, we = float(windows[i][0]), float(windows[i][1])
        raw = ap.decode(note[i], onset[i], onset_threshold=0.55, frame_threshold=0.30,
                        min_note_length=6, melodia_trick=True)
        dets = [ap.Detection(n.pitch_midi, ws + n.start_frame * FRAME,
                             ws + min(n.end_frame, ap.N_FRAMES) * FRAME,
                             n.amplitude, n.onset_strength) for n in raw]
        kept = ap.filter_candidates(dets, fs30).kept
        results.append(ap.WindowResult(ws, we, [d for d in kept if not af.is_startup_artifact(d.onset_s, d.confidence, d.onset_strength)]))
    merged, _ = ap.merge_detailed(results, tolerance_ms=80.0)
    hf = af.HarmonicRealnessFilter(audio)
    kept = []
    for n in merged:
        if (n.duration_ms / 1000.0) < 0.12 and (n.onset_strength or 0) < 0.45:
            continue
        if n.pitch_midi >= 72:
            ev = hf.evidence(n.pitch_midi, n.onset_ms / 1000)
            if ev["h2"] < 0.10 and ev["h3"] < 0.05 and any(
                abs(m.onset_ms - n.onset_ms) <= 80 and (n.pitch_midi - m.pitch_midi) in (24, 36) for m in merged
            ):
                continue
        kept.append(n)
    added = 0
    if use_completion:
        completer = ChordCompleter(audio)
        extras = completer.add_notes(kept, **kw)
        added = len(extras)
        for e in extras:
            kept.append(af.ap.Note(e["pitch"], e["onset"] * 1000.0, e["duration"] * 1000.0,
                                   e["confidence"], e["strength"]))
    kept.sort(key=lambda n: (n.onset_ms, n.pitch_midi))
    return kept, added


def evaluate(ids=("29", "60", "74", "12", "9", "70"), **kw):
    tp = fp = fn = 0
    per = {}
    total_added = 0
    for pid in ids:
        notes, added = run(pid, **kw)
        total_added += added
        gt = pvlib.load_gt_excerpt(pid)
        ref = [{"pitch": g["pitch"], "onset": g["onset"], "offset": g["offset"]} for g in gt]
        est = [{"pitch": n.pitch_midi, "onset": n.onset_ms / 1000, "offset": (n.onset_ms + n.duration_ms) / 1000}
               for n in notes]
        s = pvlib.summary(ref, est, pid)
        per[pid] = round(s["f1"], 3)
        tp += s["tp"]; fp += s["fp"]; fn += s["fn"]
    p, r = tp / (tp + fp), tp / (tp + fn)
    return {"f1": 2 * p * r / (p + r), "p": p, "r": r, "per": per, "added": total_added}


if __name__ == "__main__":
    for label, kw in (
        ("no completion", dict(use_completion=False)),
        ("completion rel0.30", dict(rel_floor=0.30)),
        ("completion rel0.40", dict(rel_floor=0.40)),
        ("completion rel0.50", dict(rel_floor=0.50)),
    ):
        res = evaluate(**kw)
        print(f"{label:20s} F1={res['f1']:.4f} P={res['p']:.3f} R={res['r']:.3f} added={res['added']} {res['per']}", flush=True)
