#!/usr/bin/env python3
"""Re-run the Phase 2 analysis over the take recorded from the debug video.

The APK was tried on a real device and the finished score was clearly wrong:
287 notes for a 32 s take, tempo 160 BPM, 2/4 and C# major.  The screen
recording's audio track (`work/pianosheet_debug/audio.wav`) shows why there is
something to clean: a stationary narrow-band tone around 3.2 kHz sits on the
piano, and Basic Pitch's high semitones are exactly where that tone lives.

This script reproduces the analysis path of the app (2 s model window every
1 s, per-window decode, the same merge tolerance) so the effect of every change
can be measured instead of guessed:

  * baseline:  25 Hz high-pass + the v1.3 spectral subtraction ('standard')
  * candidate: the same, plus a narrow notch at every stationary tone the
               device/room leaves in the recording (`ToneNoiseDetector`)

It reports note counts, how many notes are shorter than 80 ms, how many are
above MIDI 84, how many are octave/duodecime ghosts of a stronger note that
starts at the same time, and which tempo the onsets imply.  The raw detections
are written as JSON so the Kotlin unit tests can replay them
(`app/src/test/resources/debug-take-detections.json`).

Usage:
    python3 phase0-poc/scripts/verify_debug_take.py [--wav PATH] [--out PATH]
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "phase0-poc"))

from pianosheet_poc.basicpitch_onnx import (  # noqa: E402
    AUDIO_N_SAMPLES,
    AUDIO_SAMPLE_RATE,
    ANNOTATIONS_FPS,
    BasicPitchOnnx,
    output_to_notes_polyphonic,
)
from pianosheet_poc.denoise import (  # noqa: E402
    DenoiseConfig as PyDenoiseConfig,
    Denoiser as PyDenoiser,
)

MODEL = ROOT / "phase0-poc" / "models" / "nmp.onnx"


# --------------------------------------------------------------------------- #
# stationary tone detection (mirror of core/dsp/ToneNoiseDetector.kt)
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class TonePeak:
    frequency_hz: float
    prominence_db: float
    presence: float


def spectral_floor(magnitude: np.ndarray, size: int = 31) -> np.ndarray:
    pad = size // 2
    padded = np.pad(magnitude, (pad, pad), mode="edge")
    return np.median(np.lib.stride_tricks.sliding_window_view(padded, size, axis=-1), axis=-1)


def detect_stationary_tones(
    audio: np.ndarray,
    sample_rate: int = AUDIO_SAMPLE_RATE,
    frame_size: int = 1024,
    hop_size: int = 256,
    min_hz: float = 1200.0,
    max_hz: float = 8000.0,
    presence_ratio: float = 3.0,
    presence_min: float = 0.80,
    min_prominence_db: float = 15.0,
    max_count: int = 3,
) -> List[TonePeak]:
    window = np.hanning(frame_size + 1)[:-1]
    n_frames = 1 + max(0, (len(audio) - frame_size)) // hop_size
    if n_frames < 60:
        return []
    frames = np.stack(
        [
            np.abs(np.fft.rfft(audio[i * hop_size : i * hop_size + frame_size] * window))
            for i in range(n_frames)
        ]
    )
    floor = np.stack([spectral_floor(row) for row in frames])
    tonal = frames > presence_ratio * np.maximum(floor, 1e-12)
    presence = tonal.mean(axis=0)
    median_spectrum = np.median(frames, axis=0)

    freqs = np.fft.rfftfreq(frame_size, 1.0 / sample_rate)
    candidates: List[TonePeak] = []
    for b in range(2, len(freqs) - 2):
        if not (min_hz <= freqs[b] <= max_hz):
            continue
        if presence[b] < presence_min:
            continue
        if median_spectrum[b] < median_spectrum[b - 1] or median_spectrum[b] < median_spectrum[b + 1]:
            continue
        shoulders = []
        for d in range(4, 21):
            if b - d >= 0:
                shoulders.append(median_spectrum[b - d])
            if b + d < len(freqs):
                shoulders.append(median_spectrum[b + d])
        shoulder = float(np.median(shoulders)) if shoulders else 0.0
        if shoulder <= 0.0:
            continue
        prominence = 20.0 * np.log10(median_spectrum[b] / shoulder)
        if prominence < min_prominence_db:
            continue
        # Parabolic interpolation around the peak of the median spectrum.
        left, centre, right = median_spectrum[b - 1 : b + 2]
        denom = left - 2 * centre + right
        offset = 0.0 if abs(denom) < 1e-12 else 0.5 * (left - right) / denom
        frequency = float(freqs[b] + offset * (freqs[1] - freqs[0]))
        candidates.append(TonePeak(frequency, float(prominence), float(presence[b])))

    candidates.sort(key=lambda p: -p.prominence_db)
    chosen: List[TonePeak] = []
    for candidate in candidates:
        if all(abs(candidate.frequency_hz - other.frequency_hz) > 60.0 for other in chosen):
            chosen.append(candidate)
        if len(chosen) >= max_count:
            break
    return chosen


def tone_mask(tones: List[TonePeak], sample_rate: int, frame_size: int = 1024, half_width: int = 2) -> np.ndarray:
    """Bins held down for each stationary tone (mirrors Denoiser.toneMask)."""
    n_bins = frame_size // 2 + 1
    mask = np.zeros(n_bins, dtype=bool)
    if not tones:
        return mask
    bin_hz = sample_rate / frame_size
    for tone in tones:
        centre = int(round(tone.frequency_hz / bin_hz))
        mask[max(0, centre - half_width) : min(n_bins, centre + half_width + 1)] = True
    return mask


# --------------------------------------------------------------------------- #
# analysis: same window plan and merge as FinalTranscriber / FinalNoteMerger
# --------------------------------------------------------------------------- #
@dataclass
class Detection:
    pitch: int
    onset_s: float
    end_s: float
    confidence: float
    onset_strength: float


def analyse(model: BasicPitchOnnx, audio: np.ndarray) -> Tuple[List[Detection], int]:
    window = AUDIO_N_SAMPLES
    hop = AUDIO_SAMPLE_RATE  # 1 s, like FinalAnalysisOptions.windowHopS
    if len(audio) <= window:
        starts = [0]
        audio = np.pad(audio, (0, window - len(audio)))
    else:
        starts = list(range(0, len(audio) - window, hop)) + [len(audio) - window]
    detections: List[Detection] = []
    for start in starts:
        chunk = audio[start : start + window].astype(np.float32)
        result = model.predict_window(chunk)
        raw = output_to_notes_polyphonic(
            result.note,
            result.onset,
            onset_thresh=0.5,
            frame_thresh=0.3,
            min_note_len=int(round(ANNOTATIONS_FPS * 0.1277)),
            infer_onsets=True,
            max_freq=None,
            min_freq=None,
            melodia_trick=True,
        )
        start_s = start / AUDIO_SAMPLE_RATE
        for s, e, pitch, amplitude, onset_strength in raw:
            onset = start_s + s / ANNOTATIONS_FPS
            end = start_s + min(e, 172) / ANNOTATIONS_FPS
            if onset < 0.0:
                continue
            detections.append(
                Detection(int(pitch), onset, end, float(amplitude), float(onset_strength))
            )
    return merge(detections), len(starts)


def merge(detections: List[Detection], tolerance_ms: float = 80.0) -> List[Detection]:
    ordered = sorted(detections, key=lambda d: (d.onset_s, d.pitch))
    merged: List[Detection] = []
    for detection in ordered:
        same = [
            d for d in merged
            if d.pitch == detection.pitch and abs(d.onset_s - detection.onset_s) * 1000.0 <= tolerance_ms
        ]
        if same:
            existing = same[0]
            existing.end_s = max(existing.end_s, detection.end_s)
            existing.confidence = max(existing.confidence, detection.confidence)
            existing.onset_strength = max(existing.onset_strength, detection.onset_strength)
        else:
            merged.append(
                Detection(
                    detection.pitch,
                    detection.onset_s,
                    detection.end_s,
                    detection.confidence,
                    detection.onset_strength,
                )
            )
    merged.sort(key=lambda d: (d.onset_s, d.pitch))
    return merged


def filter_candidates(notes: List[Detection]) -> List[Detection]:
    """Mirror of NoteCandidateFilter: weak onsets, short blips, high ghosts."""
    kept: List[Detection] = []
    for note in notes:
        duration = note.end_s - note.onset_s
        if note.onset_strength < 0.22:
            continue
        if duration < 0.08:
            continue
        if note.pitch >= 84 and (note.onset_strength < 0.45 or note.confidence < 0.12):
            continue
        ghost = False
        for lower in notes:
            if abs(lower.onset_s - note.onset_s) > 0.06 or lower.pitch >= note.pitch:
                continue
            interval = note.pitch - lower.pitch
            if interval not in (12, 19, 24, 28):
                continue
            if lower.confidence > 0.0 and note.confidence < 0.35 * lower.confidence:
                ghost = True
                break
        if ghost:
            continue
        kept.append(note)
    return kept


def tempo_from_onsets(notes: List[Detection], min_bpm: float = 50.0, max_bpm: float = 200.0) -> float:
    """Plain IOI-histogram tempo, close enough to spot a doubled tempo."""
    times = sorted({round(n.onset_s, 3) for n in notes})
    if len(times) < 3:
        return 0.0
    intervals = np.diff(times)
    intervals = intervals[intervals > 0.05]
    if intervals.size == 0:
        return 0.0
    best_bpm, best_score = 0.0, -1.0
    for bpm in np.arange(min_bpm, max_bpm + 0.1, 0.5):
        period = 60.0 / bpm
        score = 0.0
        for interval in intervals:
            ratio = interval / period
            error = abs(ratio - round(ratio))
            if round(ratio) >= 1 and error < 0.12:
                score += 1.0 - error / 0.12
        if score > best_score:
            best_score, best_bpm = score, float(bpm)
    return best_bpm


def stats(name: str, notes: List[Detection]) -> Dict[str, float]:
    pitches = [n.pitch for n in notes]
    short = sum(1 for n in notes if n.end_s - n.onset_s < 0.08)
    high = sum(1 for p in pitches if p >= 84)
    summary = {
        "name": name,
        "notes": len(notes),
        "short_lt_80ms": short,
        "midi_ge_84": high,
        "median_duration_ms": float(np.median([(n.end_s - n.onset_s) * 1000 for n in notes])) if notes else 0.0,
        "tempo_bpm": tempo_from_onsets(notes),
    }
    print(
        f"{name:<34} notes={summary['notes']:>4}  <80ms={short:>3}  MIDI>=84={high:>3}  "
        f"median={summary['median_duration_ms']:6.0f}ms  tempo={summary['tempo_bpm']:6.1f}"
    )
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--wav", default=str(ROOT / "work" / "pianosheet_debug" / "audio.wav"))
    parser.add_argument("--out", default=str(ROOT / "app" / "src" / "test" / "resources" / "debug-take-detections.json"))
    args = parser.parse_args()

    import wave

    with wave.open(args.wav) as handle:
        sample_rate = handle.getframerate()
        samples = np.frombuffer(handle.readframes(handle.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
    print(f"audio: {args.wav}  {len(samples) / sample_rate:.1f}s  {sample_rate} Hz")

    # The app recorded 5..30 s of this screen recording; 75..100 s is the app
    # playing the take back, which is a clean reference of the real playing.
    sections = {"recording 5-30s": (5.0, 30.0), "reference playback 75-100s": (75.0, 100.0)}
    model = BasicPitchOnnx(MODEL, intra_op_threads=2)
    report: Dict[str, object] = {"sections": {}, "detections": {}}

    for label, (start_s, end_s) in sections.items():
        segment = samples[int(start_s * sample_rate) : int(end_s * sample_rate)]
        tones = detect_stationary_tones(segment, sample_rate)
        print(f"\n== {label}")
        print("stationary tones:", [(round(t.frequency_hz, 1), round(t.prominence_db, 1), round(t.presence, 2)) for t in tones])

        baseline_cfg = PyDenoiseConfig(strength="standard", highpass_hz=25.0, sample_rate=sample_rate)
        baseline = PyDenoiser(baseline_cfg).process(segment)
        cleaned = PyDenoiser(baseline_cfg).process(segment, tone_mask=tone_mask(tones, sample_rate))

        base_notes, windows = analyse(model, baseline)
        clean_notes, _ = analyse(model, cleaned)
        filtered = filter_candidates(clean_notes)

        section_stats = [
            stats(f"{label} baseline", base_notes),
            stats(f"{label} + tone notch", clean_notes),
            stats(f"{label} + notch + filter", filtered),
        ]
        report["sections"][label] = {"tones": [t.__dict__ for t in tones], "windows": windows, "stats": section_stats}
        report["detections"][label] = [
            {"pitch": n.pitch, "onset": round(n.onset_s, 4), "end": round(n.end_s, 4),
             "confidence": round(n.confidence, 4), "onsetStrength": round(n.onset_strength, 4)}
            for n in filtered
        ]

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=1, ensure_ascii=False))
    print(f"\nwrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
