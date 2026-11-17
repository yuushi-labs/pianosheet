"""Python twin of PianoSheet v1.3 final analysis, for measurement.

Reimplements, stage by stage, what the Android app does on a saved take:

  WAV -> (resample 22.05k) -> Denoiser(standard, 25Hz high-pass) -> StartupGuard
      -> N windows of 43844 samples with 22050-sample hop (2.0s window / 1.0s hop)
      -> per window: Basic Pitch ONNX -> BasicPitchDecoder (infer onsets + melodia)
                     -> NoteCandidateFilter (per-window physical checks)
      -> FinalNoteMerger.mergeDetailed (80ms tolerance, edge guard, ghosts)

The Kotlin sources mirrored here:
  core/inference/BasicPitchDecoder.kt, BasicPitchDetector.kt, NoteCandidateFilter.kt
  domain/final/FinalTranscriber.kt, FinalNoteMerger.kt
  core/dsp/Denoiser.kt (via phase0-poc/pianosheet_poc/denoise.py)
"""

from __future__ import annotations

import os

import sys
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "phase0-poc"))
from pianosheet_poc.basicpitch_onnx import (  # noqa: E402
    AUDIO_N_SAMPLES,
    ANNOTATIONS_FPS,
    FFT_HOP,
    MIDI_OFFSET,
    ONNX_INPUT,
    ONNX_NOTE,
    ONNX_ONSET,
    ONNX_CONTOUR,
    BasicPitchOnnx,
)
from pianosheet_poc.basicpitch_onnx import AUDIO_SAMPLE_RATE as SAMPLE_RATE  # noqa: E402
from pianosheet_poc.denoise import DenoiseConfig, Denoiser  # noqa: E402

FRAME_SECONDS = FFT_HOP / SAMPLE_RATE
N_FRAMES = 172
_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(os.path.dirname(_HERE))
MODEL_PATH = os.path.join(_REPO, "phase0-poc", "models", "nmp.onnx")


# ---------------------------------------------------------------- decoder ----


def get_inferred_onsets(onsets: np.ndarray, frames: np.ndarray, n_diff: int = 2) -> np.ndarray:
    """BasicPitchDecoder.getInferredOnsets (peak-friendly Kotlin variant)."""
    n_frames = frames.shape[0]
    n_bins = frames.shape[1]
    frame_diff = np.zeros((n_frames, n_bins), dtype=np.float64)
    for n in range(1, n_diff + 1):
        shifted = np.zeros_like(frames)
        shifted[n:] = frames[:-n] if n else frames
        diff = frames - shifted
        if n == 1:
            frame_diff = diff
        else:
            frame_diff = np.minimum(frame_diff, diff)
    frame_diff[frame_diff < 0] = 0.0
    scale = float(frame_diff.max(initial=0.0))
    frame_diff[: min(n_diff, n_frames), :] = 0.0
    max_onset = float(onsets.max(initial=0.0))
    if scale > 0:
        frame_diff = max_onset * frame_diff / scale
    return np.maximum(onsets, frame_diff)


@dataclass
class RawNote:
    start_frame: int
    end_frame: int
    pitch_midi: int
    amplitude: float
    onset_strength: float


def decode(
    note: np.ndarray,
    onset: np.ndarray,
    onset_threshold: float = 0.5,
    frame_threshold: float = 0.3,
    min_note_length: int = 11,
    infer_onsets: bool = True,
    melodia_trick: bool = True,
    energy_tolerance: int = 11,
) -> List[RawNote]:
    n_frames, n_bins = note.shape
    frames = note.astype(np.float64)
    onsets = onset.astype(np.float64)
    if infer_onsets:
        onsets = get_inferred_onsets(np.maximum(onsets, 0.0), frames)

    peak = np.zeros_like(onsets)
    for t in range(n_frames):
        prev = onsets[t - 1] if t > 0 else onsets[t]
        nxt = onsets[t + 1] if t < n_frames - 1 else onsets[t]
        mask = (onsets[t] > prev) & (onsets[t] > nxt) & (onsets[t] > 0.0)
        peak[t][mask] = onsets[t][mask]

    ts, bs = np.nonzero(peak >= onset_threshold)
    peaks = list(zip(ts.tolist(), bs.tolist()))[::-1]

    onset_strength_matrix = onsets.copy()
    remaining = frames.copy()
    out: List[RawNote] = []

    def clear_bin(start: int, end: int, b: int) -> None:
        if start >= end:
            return
        remaining[start:end, b] = 0.0
        if b + 1 < n_bins:
            remaining[start:end, b + 1] = 0.0
        if b > 0:
            remaining[start:end, b - 1] = 0.0

    for start, b in peaks:
        if start >= n_frames - 1:
            continue
        i = start + 1
        gap = 0
        while i < n_frames - 1 and gap < energy_tolerance:
            if remaining[i, b] < frame_threshold:
                gap += 1
            else:
                gap = 0
            i += 1
        i -= gap
        if i - start <= min_note_length:
            continue
        clear_bin(start, i, b)
        out.append(
            RawNote(
                start,
                i,
                b + MIDI_OFFSET,
                float(np.mean(frames[start:i, b])),
                float(onset_strength_matrix[start, b]),
            )
        )

    if melodia_trick:
        while True:
            idx = np.unravel_index(np.argmax(remaining), remaining.shape)
            best = float(remaining[idx])
            if best <= frame_threshold:
                break
            best_frame, b = int(idx[0]), int(idx[1])
            remaining[best_frame, b] = 0.0

            i = best_frame + 1
            gap = 0
            while i < n_frames - 1 and gap < energy_tolerance:
                if remaining[i, b] < frame_threshold:
                    gap += 1
                else:
                    gap = 0
                remaining[i, b] = 0.0
                if b + 1 < n_bins:
                    remaining[i, b + 1] = 0.0
                if b > 0:
                    remaining[i, b - 1] = 0.0
                i += 1
            end = i - 1 - gap

            i = best_frame - 1
            gap = 0
            while i > 0 and gap < energy_tolerance:
                if remaining[i, b] < frame_threshold:
                    gap += 1
                else:
                    gap = 0
                remaining[i, b] = 0.0
                if b + 1 < n_bins:
                    remaining[i, b + 1] = 0.0
                if b > 0:
                    remaining[i, b - 1] = 0.0
                i -= 1
            start = i + 1 + gap

            if start < 0 or end >= n_frames:
                continue
            if end - start <= min_note_length:
                continue
            out.append(
                RawNote(
                    start,
                    end,
                    b + MIDI_OFFSET,
                    float(np.mean(frames[start:end, b])),
                    float(onset_strength_matrix[start, b]),
                )
            )
    return out


# --------------------------------------------------------------- filter ------


@dataclass
class FilterSettings:
    min_onset_strength: float = 0.30
    min_duration_s: float = 0.08
    high_pitch_midi: int = 84
    high_pitch_min_onset: float = 0.40
    high_pitch_min_duration_s: float = 0.10
    high_pitch_min_confidence: float = 0.25
    ghost_window_s: float = 0.06
    ghost_confidence_ratio: float = 0.55
    ghost_fundamental_min_confidence: float = 0.35
    ghost_intervals: Tuple[int, ...] = (12, 19, 24, 28, 31, 36)


@dataclass
class Detection:
    pitch_midi: int
    onset_s: float
    end_s: float
    confidence: float
    onset_strength: float
    origin: str = "onset"  # "onset" (real peak) or "melodia"


@dataclass
class FilterResult:
    kept: List[Detection]
    dropped_weak_onset: int = 0
    dropped_too_short: int = 0
    dropped_high_register: int = 0
    dropped_overtone: int = 0

    @property
    def dropped_total(self) -> int:
        return (
            self.dropped_weak_onset
            + self.dropped_too_short
            + self.dropped_high_register
            + self.dropped_overtone
        )


def is_overtone_of(det: Detection, candidates: List[Detection], s: FilterSettings) -> bool:
    if det.pitch_midi < s.high_pitch_midi - 24:
        return False
    best = 0.0
    for other in candidates:
        if other is det or other.pitch_midi >= det.pitch_midi:
            continue
        if abs(other.onset_s - det.onset_s) > s.ghost_window_s:
            continue
        if (det.pitch_midi - other.pitch_midi) not in s.ghost_intervals:
            continue
        if other.confidence < s.ghost_fundamental_min_confidence:
            continue
        best = max(best, other.confidence)
    if best <= 0:
        return False
    return det.confidence < s.ghost_confidence_ratio * best


def filter_candidates(dets: List[Detection], s: FilterSettings = FilterSettings()) -> FilterResult:
    weak = short = high = overtone = 0
    kept: List[Detection] = []
    for det in dets:
        duration = det.end_s - det.onset_s
        if det.onset_strength < s.min_onset_strength:
            weak += 1
            continue
        if duration < s.min_duration_s:
            short += 1
            continue
        if det.pitch_midi >= s.high_pitch_midi:
            if (
                det.onset_strength < s.high_pitch_min_onset
                or duration < s.high_pitch_min_duration_s
                or det.confidence < s.high_pitch_min_confidence
            ):
                high += 1
                continue
        if is_overtone_of(det, dets, s) or is_overtone_of(det, kept, s):
            overtone += 1
            continue
        kept.append(det)
    return FilterResult(kept, weak, short, high, overtone)


# --------------------------------------------------------------- merger ------


@dataclass
class WindowResult:
    window_start_s: float
    window_end_s: float
    detections: List[Detection]


@dataclass
class Note:
    pitch_midi: int
    onset_ms: float
    duration_ms: float
    confidence: float
    onset_strength: float
    origin: str = "onset"

    @property
    def end_ms(self) -> float:
        return self.onset_ms + self.duration_ms


def merge_detailed(
    windows: List[WindowResult],
    tolerance_ms: float = 80.0,
    edge_guard_s: float = 0.02,
    s: FilterSettings = FilterSettings(),
) -> Tuple[List[Note], Dict[str, int]]:
    candidates: List[Detection] = []
    for wi, w in enumerate(windows):
        for det in w.detections:
            if det.onset_s < 0:
                continue
            if wi > 0 and det.onset_s < w.window_start_s + edge_guard_s:
                continue
            candidates.append(det)
    ordered = sorted(candidates, key=lambda d: (d.onset_s, d.pitch_midi))
    merged: List[Note] = []
    by_pitch: Dict[int, List[int]] = {}
    for det in ordered:
        onset_ms = det.onset_s * 1000.0
        recent = by_pitch.setdefault(det.pitch_midi, [])
        found = -1
        for pos in range(len(recent) - 1, -1, -1):
            index = recent[pos]
            existing = merged[index]
            existing_end = existing.end_ms
            if abs(existing.onset_ms - onset_ms) <= tolerance_ms:
                found = index
                break
            if existing.onset_ms < onset_ms < existing.onset_ms + 0.5 * (existing_end - existing.onset_ms):
                found = index
                break
            if existing_end <= onset_ms:
                break
        if found < 0:
            merged.append(
                Note(
                    det.pitch_midi,
                    onset_ms,
                    max(0.0, (det.end_s - det.onset_s) * 1000.0),
                    det.confidence,
                    det.onset_strength,
                    det.origin,
                )
            )
            recent.append(len(merged) - 1)
            while len(recent) > 8:
                recent.pop(0)
        else:
            existing = merged[found]
            new_end = det.end_s * 1000.0
            if new_end > existing.end_ms:
                existing.duration_ms = new_end - existing.onset_ms
            if det.confidence > existing.confidence:
                existing.confidence = det.confidence
    merged.sort(key=lambda n: (n.onset_ms, n.pitch_midi))

    # suppressGhosts
    stats = {"short_dropped": 0, "overtone_dropped": 0}
    long_enough = []
    for n in merged:
        if n.duration_ms < 80.0:
            stats["short_dropped"] += 1
            continue
        long_enough.append(n)
    kept: List[Note] = []
    for n in long_enough:
        ghost = False
        for lower in long_enough:
            if lower is n or lower.pitch_midi >= n.pitch_midi:
                continue
            if abs(lower.onset_ms - n.onset_ms) > 60.0:
                continue
            if (n.pitch_midi - lower.pitch_midi) not in s.ghost_intervals:
                continue
            if lower.confidence < 0.35:
                continue
            if n.confidence < 0.55 * lower.confidence:
                ghost = True
                break
        if ghost:
            stats["overtone_dropped"] += 1
        else:
            kept.append(n)
    return kept, stats


# --------------------------------------------------------------- pipeline ----


def load_wav(path: str, start_s: Optional[float] = None, end_s: Optional[float] = None) -> np.ndarray:
    import wave

    with wave.open(path) as w:
        sr = w.getframerate()
        n = w.getnframes()
        data = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float32) / 32768.0
        if w.getnchannels() > 1:
            data = data.reshape(-1, w.getnchannels()).mean(axis=1)
    assert sr == SAMPLE_RATE, sr
    if start_s is not None:
        data = data[int(start_s * sr) : int(end_s * sr) if end_s else None]
    return data


def window_starts(total: int, window: int = AUDIO_N_SAMPLES, hop: int = 22050) -> List[int]:
    if total <= 0:
        return [0]
    if total <= window:
        return [0]
    starts = []
    start = 0
    while start + window < total:
        starts.append(start)
        start += hop
    starts.append(total - window)
    return starts


class AppPipeline:
    def __init__(self, model_path: str = MODEL_PATH, denoise: bool = True):
        self.model = BasicPitchOnnx(model_path)
        self.denoise = denoise

    def run(
        self,
        audio: np.ndarray,
        onset_threshold: float = 0.5,
        frame_threshold: float = 0.3,
        min_note_length: int = 11,
        hop: int = 22050,
        filter_settings: Optional[FilterSettings] = None,
        tolerance_ms: float = 80.0,
        keep_windows: bool = False,
        progress: bool = True,
    ) -> dict:
        fs = filter_settings or FilterSettings()
        raw_audio = audio
        if self.denoise:
            cfg = DenoiseConfig()
            audio = Denoiser(cfg).process(audio.astype(np.float32))

        starts = window_starts(len(audio), AUDIO_N_SAMPLES, hop)
        windows: List[WindowResult] = []
        raw_count = 0
        filt = FilterResult(kept=[])
        totals = dict(weak=0, short=0, high=0, overtone=0)
        compute_ms = []
        onset_origin = dict(onset=0, melodia=0)
        for wi, start in enumerate(starts):
            end = min(start + AUDIO_N_SAMPLES, len(audio))
            window = audio[start:end].astype(np.float32)
            window_start_s = start / SAMPLE_RATE
            pad = AUDIO_N_SAMPLES - len(window)
            if pad > 0:
                window = np.concatenate([np.zeros(pad, dtype=np.float32), window])
                window_start_s -= pad / SAMPLE_RATE
            res = self.model.predict_window(window)
            compute_ms.append(res.compute_ms)
            notes = decode(
                res.note,
                res.onset,
                onset_threshold=onset_threshold,
                frame_threshold=frame_threshold,
                min_note_length=min_note_length,
            )
            # tag melodia-only notes by their onset strength == activation at start
            dets = [
                Detection(
                    n.pitch_midi,
                    window_start_s + n.start_frame * FRAME_SECONDS,
                    window_start_s + min(n.end_frame, N_FRAMES) * FRAME_SECONDS,
                    n.amplitude,
                    n.onset_strength,
                    "melodia" if n.onset_strength < 0.2 else "onset",
                )
                for n in notes
            ]
            raw_count += len(dets)
            for d in dets:
                onset_origin[d.origin] += 1
            f = filter_candidates(dets, fs)
            totals["weak"] += f.dropped_weak_onset
            totals["short"] += f.dropped_too_short
            totals["high"] += f.dropped_high_register
            totals["overtone"] += f.dropped_overtone
            windows.append(WindowResult(window_start_s, end / SAMPLE_RATE, f.kept))
            if progress and wi % 10 == 0:
                print(f"  window {wi+1}/{len(starts)}", flush=True)

        notes, mstats = merge_detailed(windows, tolerance_ms=tolerance_ms, s=fs)
        out = {
            "notes": notes,
            "windows": windows if keep_windows else None,
            "n_windows": len(starts),
            "raw_candidates": raw_count,
            "after_filter": sum(len(w.detections) for w in windows),
            "filter_drops": totals,
            "merge": mstats,
            "final": len(notes),
            "compute_ms_mean": float(np.mean(compute_ms)),
            "compute_ms_total": float(np.sum(compute_ms)),
            "origin": onset_origin,
        }
        return out
