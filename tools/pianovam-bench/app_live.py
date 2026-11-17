"""Python twin of the live path (LiveTranscriptionEngine + BasicPitchDetector)."""
from __future__ import annotations

import os

import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "phase0-poc"))

import app_pipeline as ap  # noqa: E402
from pianosheet_poc.denoise import DenoiseConfig, StreamingDenoiser  # noqa: E402

from app_final import is_startup_artifact  # noqa: E402

SR = 22050
FRAME = ap.FRAME_SECONDS


class LiveEngine:
    """Mirror of LiveTranscriptionEngine (0.3.0-phase2)."""

    def __init__(self, model, config=None, denoise="weak"):
        self.model = model
        c = dict(
            window_s=2.0, step_s=0.25, onset_tolerance_ms=120.0, tail_merge_ms=120.0,
            confidence_floor=0.0, onset_strength_min=0.30, restrike_onset_min=0.50,
            guard_ms=260.0, startup_ramp_ms=250.0, startup_guard_ms=250.0,
            onset_threshold=0.5, frame_threshold=0.3, min_note_length=11,
        )
        if config:
            c.update(config)
        self.c = c
        self.window_samples = int(round(c["window_s"] * SR))
        self.step_samples = int(round(c["step_s"] * SR))
        self.denoiser = StreamingDenoiser(DenoiseConfig(strength=denoise, highpass_hz=25.0)) if denoise != "off" else None
        self.start_session()

    def start_session(self):
        self.chunks = []
        self.chunks_len = 0
        self.abs_samples = 0
        self.next_step = 0
        self.committed_until = 0.0
        self.first_step = True
        self.events = []
        self.steps = []
        self.raw_candidates = 0
        self.duplicates = 0
        self.stale = 0
        self.weak = 0
        self.startup_suppressed = 0

    # ---------------------------------------------------------------- input --
    def push(self, chunk: np.ndarray):
        chunk = np.asarray(chunk, dtype=np.float32)
        if self.denoiser is not None:
            chunk = self.denoiser.process_chunk(chunk)
        if len(chunk) == 0:
            return []
        # StartupGuard.applyStartupTreatment on the analysis copy
        start = self.abs_samples
        working = chunk.copy()
        guard_end = int(0.25 * SR) - start
        if guard_end > 0:
            n = min(len(working), guard_end)
            working[:n] -= float(working[:n].mean())
        ramp = int(0.25 * SR)
        if start < ramp:
            idx = np.arange(len(working)) + start
            gain = np.clip(idx / ramp, 0.0, 1.0).astype(np.float32)
            working = working * gain
        self.chunks.append(working)
        self.chunks_len += len(working)
        self.abs_samples += len(working)
        self._trim()
        if self.first_step:
            self.next_step = min(self.step_samples, self.abs_samples)
            self.first_step = False
        out = []
        while self.abs_samples >= self.next_step:
            out.extend(self._step())
            self.next_step += self.step_samples
        return out

    def finish(self):
        if self.abs_samples > self.next_step - self.step_samples and self.next_step != self.abs_samples:
            self.next_step = self.abs_samples
            return self._step()
        return []

    def _trim(self):
        while len(self.chunks) > 1 and self.chunks_len - len(self.chunks[0]) >= self.window_samples:
            self.chunks_len -= len(self.chunks.pop(0))

    def _window(self, end_sample):
        start = max(0, end_sample - self.window_samples)
        need = end_sample - start
        allc = np.concatenate(self.chunks) if len(self.chunks) > 1 else self.chunks[0]
        off = self.abs_samples - len(allc)
        lo = max(0, start - off)
        hi = max(lo, end_sample - off)
        audio = allc[lo:hi]
        if len(audio) < need:
            audio = np.pad(audio, (need - len(audio), 0))
        return audio.astype(np.float32), start

    # ---------------------------------------------------------------- step ---
    def _step(self):
        end_sample = self.next_step
        audio, start = self._window(end_sample)
        audio_time = end_sample / SR
        wstart = start / SR
        if len(audio) < ap.AUDIO_N_SAMPLES:
            pad = ap.AUDIO_N_SAMPLES - len(audio)
            audio = np.concatenate([np.zeros(pad, dtype=np.float32), audio])
            wstart -= pad / SR
        res = self.model.predict_window(audio)
        notes = ap.decode(res.note, res.onset, onset_threshold=self.c["onset_threshold"],
                          frame_threshold=self.c["frame_threshold"],
                          min_note_length=self.c["min_note_length"])
        dets = [ap.Detection(n.pitch_midi, wstart + n.start_frame * FRAME,
                             wstart + min(n.end_frame, ap.N_FRAMES) * FRAME,
                             n.amplitude, n.onset_strength) for n in notes]
        dets = ap.filter_candidates(dets).kept
        self.raw_candidates += len(dets)

        raw = []
        for d in dets:
            if d.onset_s < 0:
                continue
            if d.onset_strength is not None and d.onset_strength < self.c["onset_strength_min"]:
                self.weak += 1
                continue
            if d.confidence < self.c["confidence_floor"]:
                continue
            if is_startup_artifact(d.onset_s, d.confidence, d.onset_strength, self.c["startup_guard_ms"]):
                self.startup_suppressed += 1
                continue
            raw.append({
                "pitch": d.pitch_midi, "onset": d.onset_s,
                "end": d.end_s, "conf": d.confidence, "os": d.onset_strength,
            })

        allowed_max = audio_time - self.c["guard_ms"] / 1000.0
        lo = self.committed_until - self.c["onset_tolerance_ms"] / 1000.0
        region = [c for c in raw if lo < c["onset"] <= allowed_max]
        self.stale += len(raw) - len(region)
        if allowed_max > self.committed_until:
            self.committed_until = allowed_max

        new = []
        for cand in region:
            dup = None
            for ev in reversed(new):
                if self._is_dup(ev, cand):
                    dup = ev
                    break
            if dup is None:
                for ev in reversed(self.events[-96:]):
                    if self._is_dup(ev, cand):
                        dup = ev
                        break
            if dup is not None:
                self.duplicates += 1
                dup["conf"] = max(dup["conf"], cand["conf"])
                if dup["onset"] + dup["dur"] < cand["onset"] + (cand["end"] - cand["onset"]):
                    dup["dur"] = cand["end"] - dup["onset"]
                continue
            e = {"pitch": cand["pitch"], "onset": cand["onset"],
                 "dur": cand["end"] - cand["onset"], "conf": cand["conf"],
                 "os": cand["os"], "emitted": audio_time}
            new.append(e)
        self.events.extend(new)
        self.steps.append({"t": round(audio_time, 3), "new": len(new),
                           "cands": len(region), "compute_ms": res.compute_ms})
        return new

    def _is_dup(self, existing, cand):
        if existing["pitch"] != cand["pitch"]:
            return False
        if abs(existing["onset"] - cand["onset"]) * 1000.0 <= self.c["onset_tolerance_ms"]:
            return True
        weak = cand["os"] is not None and cand["os"] < self.c["restrike_onset_min"]
        end = existing["onset"] + existing["dur"]
        return (weak and end > cand["onset"] - self.c["tail_merge_ms"] / 1000.0
                and cand["onset"] >= existing["onset"])

    # -------------------------------------------------------------- output ---
    def latency_p95(self):
        lat = sorted((e["emitted"] - e["onset"]) * 1000.0 for e in self.events)
        if not lat:
            return None, None
        return lat[0] if False else lat[len(lat) // 2], lat[int((len(lat) - 1) * 0.95)]

    def notes(self):
        return [{"pitch": e["pitch"], "onset": e["onset"], "offset": e["onset"] + e["dur"],
                 "confidence": e["conf"], "onset_strength": e["os"]} for e in self.events]


def run_live(audio44: np.ndarray, sr: int = 44100, chunk_ms: float = 100.0, denoise: str = "weak", model=None, config=None):
    audio = ap.load_wav if False else None
    if sr != SR:
        from app_final import resample_to_22k

        audio = resample_to_22k(audio44, sr)
    else:
        audio = audio44.astype(np.float32)
    eng = LiveEngine(model, config=config, denoise=denoise)
    chunk = int(SR * chunk_ms / 1000.0)
    for i in range(0, len(audio), chunk):
        eng.push(audio[i:i + chunk])
    eng.finish()
    median, p95 = eng.latency_p95()
    return {
        "notes": eng.notes(),
        "latency_median_ms": median,
        "latency_p95_ms": p95,
        "raw_candidates": eng.raw_candidates,
        "duplicates": eng.duplicates,
        "stale": eng.stale,
        "weak": eng.weak,
        "startup_suppressed": eng.startup_suppressed,
        "step_compute_ms_mean": float(np.mean([s["compute_ms"] for s in eng.steps])) if eng.steps else 0.0,
        "steps": len(eng.steps),
    }
