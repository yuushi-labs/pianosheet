"""Python twin of PianoSheet 0.3.0-phase2 final analysis + score estimates.

Stages, matching the Kotlin sources:

  WAV 44.1k -> AudioResampler -> Denoiser(standard, 25 Hz HP, hum auto)
            -> StartupGuard(ramp 250 ms, DC) -> 2 s / 1 s-hop windows
            -> BasicPitch(ONNX) -> decode -> NoteCandidateFilter
            -> StartupGuard.isStartupArtifact -> FinalNoteMerger.mergeDetailed
            -> HarmonicRealnessFilter(v1.4) -> ScoreBuilder
               (TempoEstimator, RhythmQuantizer, TimeSignatureEstimator,
                KeyEstimator, measures)

Everything after the model is either imported from `pianosheet_exp/app_pipeline.py`
(itself a line-by-line port used for the v1.4 work and validated against the
device take) or re-implemented here from the Kotlin.
"""
from __future__ import annotations

import os

import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "phase0-poc"))

import app_pipeline as ap  # noqa: E402
from pianosheet_poc.denoise import DenoiseConfig, Denoiser, detect_mains_hz  # noqa: E402

SR = 22050
FRAME = ap.FRAME_SECONDS


# ------------------------------------------------------------- resampling ----
def resample_to_22k(x: np.ndarray, sr: int) -> np.ndarray:
    if sr == SR:
        return x.astype(np.float32)
    from scipy.signal import resample_poly

    from math import gcd

    g = gcd(int(sr), SR)
    up, down = SR // g, sr // g
    return resample_poly(x.astype(np.float32), up, down).astype(np.float32)


# --------------------------------------------------------- startup guard -----
def startup_treatment(audio: np.ndarray, sample_rate: int = SR, ramp_ms: float = 250.0, guard_ms: float = 250.0) -> np.ndarray:
    """StartupGuard.applyStartupTreatment."""
    out = audio.astype(np.float32).copy()
    guard = int(guard_ms * sample_rate / 1000.0)
    n = min(len(out), guard)
    if n > 0:
        out[:n] -= float(out[:n].mean())
    ramp = int(ramp_ms * sample_rate / 1000.0)
    m = min(len(out), ramp)
    if m > 0:
        out[:m] *= np.linspace(0.0, 1.0, ramp, dtype=np.float32)[:m]
    return out


def is_startup_artifact(onset_s: float, confidence: float, onset_strength: float | None,
                        guard_ms: float = 250.0) -> bool:
    if onset_s >= guard_ms / 1000.0:
        return False
    strong = (onset_strength or 0.0) >= 0.70 and confidence >= 0.45
    return not strong


# --------------------------------------------------- harmonic realness -------
class HarmonicRealnessFilter:
    """HarmonicRealnessFilter.kt: a high note with no 2nd/3rd partial is one
    spectral line of a lower note's 8th partial."""

    def __init__(self, audio: np.ndarray, sample_rate: int = SR, n_fft: int = 2048, hop: int = 256):
        self.sr = sample_rate
        self.hop = hop
        n = len(audio)
        if n < n_fft:
            audio = np.pad(audio, (0, n_fft - n))
        frames = 1 + (len(audio) - n_fft) // hop
        win = np.hanning(n_fft + 1)[:-1].astype(np.float32)  # periodic Hann
        idx = np.arange(n_fft)[None, :] + hop * np.arange(frames)[:, None]
        spec = np.abs(np.fft.rfft(audio[idx] * win, axis=1))
        self.S = spec.T  # bins x frames
        self.freqs = np.fft.rfftfreq(n_fft, 1.0 / sample_rate)

    def _max_in(self, col: np.ndarray, f: float, tol: float = 0.02) -> float:
        if f > self.sr / 2:
            return 0.0
        m = (self.freqs >= f * (1 - tol)) & (self.freqs <= f * (1 + tol))
        return float(col[m].max()) if m.any() else 0.0

    def evidence(self, pitch: int, onset_s: float, span: float = 0.20) -> dict:
        f0 = 440.0 * 2.0 ** ((pitch - 69) / 12.0)
        i0 = int(max(0.0, onset_s) * self.sr / self.hop)
        i1 = max(i0 + 1, int((onset_s + span) * self.sr / self.hop))
        i0 = min(i0, self.S.shape[1] - 1)
        col = self.S[:, i0:min(i1, self.S.shape[1])].max(axis=1)
        fund = self._max_in(col, f0)
        h2 = self._max_in(col, 2 * f0)
        h3 = self._max_in(col, 3 * f0)
        return {
            "fundamental": fund,
            "h2": h2 / fund if fund > 0 else 0.0,
            "h3": h3 / fund if fund > 0 else 0.0,
        }

    def apply(self, notes, register_gate: int = 72, min_h2: float = 0.10, min_h3: float = 0.05,
              require_owner: bool = False, owner_span_ms: float = 80.0):
        kept, dropped, examined = [], [], 0
        for n in notes:
            if n.pitch_midi >= register_gate:
                examined += 1
                ev = self.evidence(n.pitch_midi, n.onset_ms / 1000.0)
                if ev["h2"] < min_h2 and ev["h3"] < min_h3:
                    if require_owner:
                        owned = any(
                            abs(m.onset_ms - n.onset_ms) <= owner_span_ms
                            and (n.pitch_midi - m.pitch_midi) in (24, 36)
                            for m in notes
                        )
                        if not owned:
                            kept.append(n)
                            continue
                    dropped.append((n, ev))
                    continue
            kept.append(n)
        for i, n in enumerate(kept):
            n.sequence = i
        return kept, dropped, examined


# ------------------------------------------------------------- score math ----
def cluster_attacks(onset_times, weights, tol=0.09):
    order = np.argsort(onset_times)
    out = []
    for i in order:
        t, w = float(onset_times[i]), float(weights[i])
        if out and t - out[-1][2] <= tol:
            anchor_t, anchor_w, start, _ = out[-1]
            if w > anchor_w:
                out[-1] = (t, w, start, min(3.0, out[-1][3] + w))
            else:
                out[-1] = (anchor_t, anchor_w, start, min(3.0, out[-1][3] + w))
        else:
            out.append((t, w, t, min(3.0, w)))
    return [(o[0], o[3]) for o in out]


def tempo_estimate(onsets, weights, min_bpm=50.0, max_bpm=200.0, preferred_bpm=100.0,
                   preference_octaves=0.9):
    attacks = cluster_attacks(np.array(onsets), np.array(weights))
    if len(attacks) < 2:
        return 120.0, 0.0
    times = np.array([a[0] for a in attacks])
    wts = np.array([a[1] for a in attacks])
    total = wts.sum()
    intervals = []
    for i in range(len(times)):
        d = times[i + 1:] - times[i]
        intervals.extend(d[(d > 1e-4) & (d <= 2.5)].tolist())
        if len(intervals) > 400:
            break
    intervals = np.array(intervals) if intervals else np.array([])
    min_p, max_p = 60.0 / max_bpm, 60.0 / min_bpm
    cands = set()
    for iv in intervals:
        for div in range(1, 5):
            v = iv / div
            if min_p <= v <= max_p:
                cands.add(round(v, 4))
        for mul in (1, 2):
            v = iv * mul
            if min_p <= v <= max_p:
                cands.add(round(v, 4))
    if not cands:
        cands = {60.0 / 120.0}
    steps = 48
    scored = []
    for period in cands:
        phase = np.mod(times, period)
        idx = np.clip((phase / period * steps).astype(int), 0, steps - 1)
        hist = np.bincount(idx, weights=wts, minlength=steps)
        sigma = steps * 0.09
        best = 0.0
        for shift in range(steps):
            d = np.abs(np.arange(steps) - shift)
            d = np.minimum(d, steps - d)
            ker = np.exp(-0.5 * (d / sigma) ** 2)
            best = max(best, float((hist * ker).sum()))
        score = min(1.0, best / total)
        bpm = 60.0 / period
        pref = float(np.exp(-0.5 * (np.log2(bpm / preferred_bpm) / preference_octaves) ** 2))
        scored.append((period, score * pref))
    best_score = max(s for _, s in scored)
    if best_score <= 0:
        return 120.0, 0.0
    eq = [p for p, s in scored if s >= best_score * 0.985]
    chosen = min(eq, key=lambda p: abs(60.0 / p - preferred_bpm))
    return round(60.0 / chosen, 2), round(min(1.0, best_score), 3)


def _profile(beats, weights, grid=0.25, length=12):
    slots = int(round(length / grid))
    prof = np.zeros(slots)
    for b, w in zip(beats, weights):
        if b < -1e-6 or w <= 0:
            continue
        prof[int(round(b / grid)) % slots] += w
    return prof


def _downbeat_score(prof, measure_beats):
    slots = len(prof)
    ms = int(round(measure_beats / 0.25))
    if ms < 2 or ms > slots:
        return 0.0
    active = [i for i in range(slots) if prof[i] > 1e-9]
    down = [i for i in active if i % ms == 0]
    others = [i for i in active if i % ms != 0]
    if not down or not others:
        return 0.0
    dw = sum(prof[i] for i in down) / len(down)
    ow = sum(prof[i] for i in others) / len(others)
    return dw / ow if ow > 1e-9 else 0.0


def meter_estimate(beats, weights, candidates=((4, 4), (3, 4), (2, 4), (6, 8))):
    if len(beats) < 3:
        return (4, 4), 0.0
    prof = _profile(beats, weights)
    best, best_score = (4, 4), 0.0
    for cand in candidates:
        bpm_ = cand[0] * 4.0 / cand[1]
        score = _downbeat_score(prof, bpm_)
        if score > best_score + 1e-9:
            best_score, best = score, cand
        elif abs(score - best_score) <= 1e-9 and cand == (4, 4):
            best = (4, 4)
    if best == (3, 4):
        middle = prof[int(round(1.5 / 0.25))]
        second = prof[int(1.0 / 0.25)]
        third = prof[int(2.0 / 0.25)]
        if middle > (second + third) / 2.0 * 1.15 and middle > 1e-9:
            best = (6, 8)
    return best, round(min(1.0, best_score / 2.0), 3)


MAJOR_PROFILE = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
MINOR_PROFILE = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])
MAJOR_FIFTHS_BY_TONIC = [0, 7, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5]


def key_estimate(pitches, weights):
    hist = np.zeros(12)
    for p, w in zip(pitches, weights):
        if w > 0:
            hist[p % 12] += w
    if hist.sum() <= 0:
        return (0, "MAJOR"), 0.0

    def corr(profile, tonic):
        h = np.array([hist[(tonic + i) % 12] for i in range(12)])
        hm, pm = h - h.mean(), profile - profile.mean()
        den = np.sqrt((hm ** 2).sum() * (pm ** 2).sum())
        return float((hm * pm).sum() / den) if den > 1e-12 else 0.0

    best, second, best_tonic, best_mode = -9e9, -9e9, 0, "MAJOR"
    for tonic in range(12):
        for mode, profile in (("MAJOR", MAJOR_PROFILE), ("MINOR", MINOR_PROFILE)):
            s = corr(profile, tonic)
            if s > best:
                second, best, best_tonic, best_mode = best, s, tonic, mode
            elif s > second:
                second = s
    fifths = MAJOR_FIFTHS_BY_TONIC[best_tonic] if best_mode == "MAJOR" else MAJOR_FIFTHS_BY_TONIC[(best_tonic + 3) % 12]
    margin = max(0.0, best - second) if np.isfinite(second) else 0.0
    conf = max(0.0, min(1.0, best)) * max(0.35, min(1.0, 1.0 - margin * 2.0))
    return (fifths, best_mode), round(conf, 3)


def quantize(beats, durations, grid=0.25, strength=0.85):
    sb = np.maximum(0.0, beats)
    target = np.round(sb / grid) * grid
    qs = np.maximum(0.0, sb + (target - sb) * strength)
    d = np.maximum(durations, grid)
    td = np.maximum(np.round(d / grid) * grid, grid)
    qd = np.maximum(d + (td - d) * strength, grid)
    return qs, qd


# --------------------------------------------------------------- pipeline ----
def run_final(audio44: np.ndarray, sr: int = 44100, denoise: str = "standard",
              onset_threshold: float = 0.5, frame_threshold: float = 0.3,
              min_note_length: int = 11, melodia: bool = True, harmonic: bool = True,
              filter_settings=None, harmonic_gate: int = 72, harmonic_owner: bool = False,
              merge_tol: float = 80.0, gain_target: float | None = None, extra_filter=None,
              short_note_rule: bool = True,
              return_windows: bool = False, windows_override=None):
    audio = resample_to_22k(audio44, sr)
    gain_applied = 1.0
    if gain_target:
        peak = float(np.abs(audio).max())
        if peak > 1e-6:
            gain_applied = min(8.0, float(gain_target) / peak)
            audio = (audio * gain_applied).astype(np.float32)
    notch = detect_mains_hz(audio, SR) if denoise != "off" else None
    cfg = DenoiseConfig(strength=denoise, highpass_hz=25.0, notch_hz=notch)
    cleaned = Denoiser(cfg).process(audio) if denoise != "off" else audio
    prepared = startup_treatment(cleaned)

    model = _MODEL
    starts = ap.window_starts(len(prepared), ap.AUDIO_N_SAMPLES, 22050)
    windows = []
    raw = 0
    filt_totals = dict(weak=0, short=0, high=0, overtone=0)
    startup_suppressed = 0
    compute_ms = []
    for wi, start in enumerate(starts):
        end = min(start + ap.AUDIO_N_SAMPLES, len(prepared))
        window = prepared[start:end]
        wstart = start / SR
        pad = ap.AUDIO_N_SAMPLES - len(window)
        if pad > 0:
            window = np.concatenate([np.zeros(pad, dtype=np.float32), window])
            wstart -= pad / SR
        res = model.predict_window(window)
        compute_ms.append(res.compute_ms)
        notes = ap.decode(res.note, res.onset, onset_threshold=onset_threshold,
                          frame_threshold=frame_threshold, min_note_length=min_note_length,
                          melodia_trick=melodia)
        dets = [
            ap.Detection(n.pitch_midi, wstart + n.start_frame * FRAME,
                         wstart + min(n.end_frame, ap.N_FRAMES) * FRAME,
                         n.amplitude, n.onset_strength,
                         "melodia" if n.onset_strength < 0.2 else "onset")
            for n in notes
        ]
        raw += len(dets)
        f = ap.filter_candidates(dets, filter_settings or ap.FilterSettings())
        for k in filt_totals:
            filt_totals[k] += getattr(f, {"weak": "dropped_weak_onset", "short": "dropped_too_short",
                                          "high": "dropped_high_register", "overtone": "dropped_overtone"}[k])
        kept = []
        for d in f.kept:
            if is_startup_artifact(d.onset_s, d.confidence, d.onset_strength):
                startup_suppressed += 1
                continue
            kept.append(d)
        windows.append(ap.WindowResult(wstart, end / SR, kept))

    merged, mstats = ap.merge_detailed(windows, tolerance_ms=merge_tol)
    kept = merged
    hdrop, hexamined = [], 0
    if harmonic:
        hf = HarmonicRealnessFilter(audio)  # untouched resampled take
        kept, hdrop, hexamined = hf.apply(merged, register_gate=harmonic_gate,
                                          require_owner=harmonic_owner)
    if short_note_rule:
        # FinalNoteMerger: a note shorter than 120 ms needs a real attack (0.45).
        kept = [n for n in kept
                if not (n.duration_ms < 120.0 and (n.onset_strength or 0.0) < 0.45)]
    if extra_filter is not None:
        kept = extra_filter(kept, audio)
    kept.sort(key=lambda n: (n.onset_ms, n.pitch_midi))
    for i, n in enumerate(kept):
        n.sequence = i

    score = build_score(kept)
    out = {
        "notes": kept,
        "score": score,
        "n_windows": len(starts),
        "raw_candidates": raw,
        "filter": filt_totals,
        "merge": mstats,
        "startup_suppressed": startup_suppressed,
        "harmonic_dropped": len(hdrop),
        "harmonic_examined": hexamined,
        "compute_ms_mean": float(np.mean(compute_ms)),
        "hum_hz": notch,
        "gain_applied": gain_applied,
    }
    if return_windows:
        out["windows"] = windows
    return out


def build_score(notes, grid=0.25, strength=0.85):
    """ScoreBuilder: tempo -> quantise -> meter -> measures -> key."""
    usable = [n for n in notes if 21 <= n.pitch_midi <= 108 and n.onset_ms >= 0 and n.duration_ms >= 80.0]
    if not usable:
        return {"tempo": 120.0, "meter": (4, 4), "key": (0, "MAJOR"), "measures": 1,
                "bars": [], "notes": []}
    onsets = [n.onset_ms / 1000.0 for n in usable]
    weights = [max(0.05, min(1.0, n.confidence or 0.5)) * (0.5 + 0.5 * min(2.0, n.duration_ms / 1000.0)) for n in usable]
    tempo, tempo_conf = tempo_estimate(onsets, weights)
    beat = 60.0 / tempo
    origin = min(onsets)
    played = [(o - origin) / beat for o in onsets]
    durations = [d / 1000.0 / beat for d in [n.duration_ms for n in usable]]
    qs, qd = quantize(np.array(played), np.array(durations), grid=grid, strength=strength)
    meter, meter_conf = meter_estimate(
        qs, [max(0.05, min(1.0, n.confidence or 0.5)) * (0.5 + 0.5 * min(4.0, d)) for n, d in zip(usable, qd)],
    )
    per = meter[0] * 4.0 / meter[1]
    last = float((qs + qd).max()) + 0.5
    measure_count = max(1, int(np.ceil(last / per - 1e-9)))
    key, key_conf = key_estimate([n.pitch_midi for n in usable], list(qd))
    bars = [{"index": i, "startBeat": i * per, "beats": per} for i in range(measure_count)]
    # bar phase agreement helper: how many attacks land on grid positions inside the bar
    return {
        "tempo": tempo, "tempo_confidence": tempo_conf,
        "meter": meter, "meter_confidence": meter_conf,
        "key": key, "key_confidence": key_conf,
        "beats_per_measure": per, "measures": measure_count, "bars": bars,
        "score_start_sec": round(origin, 4),
        "beat_sec": round(beat, 5),
        "quantized": [{"pitch": n.pitch_midi, "start": float(s), "dur": float(d),
                       "played": float(p), "onset": n.onset_ms / 1000.0}
                      for n, s, d, p in zip(usable, qs, qd, played)],
    }


_MODEL = None


def get_model():
    global _MODEL
    if _MODEL is None:
        _MODEL = ap.AppPipeline(denoise=False).model
    return _MODEL


_MODEL = get_model()
