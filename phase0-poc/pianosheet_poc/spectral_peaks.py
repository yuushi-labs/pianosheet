"""Spectral-peak polyphonic detector: the cheapest possible baseline.

Pure STFT peak picking with harmonic pruning, then per-pitch temporal tracking.
No matrix factorisation, no neural network: this is roughly what a first
attempt on a phone in Kotlin/NDK would look like, and it shows how much of the
accuracy comes from the model rather than from the signal processing.

Steps
  1. magnitude STFT (22.05 kHz, n_fft 2048, hop 256 -> 11.6 ms frames)
  2. per frame: local maxima above a floor, frequency refined by parabolic
     interpolation, mapped to the nearest MIDI key
  3. harmonic pruning: a peak that is a near-integer multiple of a stronger,
     lower accepted peak (within 3 %) is dropped as a partial
  4. per key: activation over time; an onset is a rise from below the floor to
     above it, a note ends when the activation stays below for a few frames
"""

from __future__ import annotations

from typing import Dict, List

import numpy as np

from .live import LiveConfig, StreamingTranscriber
from .spectral_nmf import HOP, N_FFT, SR, stft_mag


def activation_to_notes(
    act: np.ndarray,
    fps: float,
    centre_offset: float = 0.0,
    window_peak: float = 1.0,
    abs_ratio: float = 0.25,
    rise_ratio_min: float = 0.10,
    min_frames: int = 4,
    abs_floor: float = 1e-4,
) -> List[Dict]:
    """Per-key activation matrix -> note events.

    A note starts where a key's activation rises sharply (not merely where it
    crosses a floor: a repeated key never leaves the floor while it rings) and
    ends when the activation falls away or the next strike of that key begins.
    """
    notes: List[Dict] = []
    for i in range(act.shape[1]):
        a = act[:, i]
        peak = float(a.max())
        if peak <= 0 or peak < abs_ratio * window_peak:
            continue
        floor = max(abs_floor, 0.12 * peak, abs_ratio * window_peak)
        rise = np.diff(a, prepend=a[0])
        cand = np.where((a > floor) & (rise > rise_ratio_min * peak))[0]
        starts: List[int] = []
        for t in cand:
            if starts and t - starts[-1] < 3:  # ~35 ms
                if a[t] > a[starts[-1]]:
                    starts[-1] = int(t)
                continue
            starts.append(int(t))
        for k, start in enumerate(starts):
            nxt = starts[k + 1] if k + 1 < len(starts) else len(a)
            end = start + 1
            while end < nxt and a[end] > floor * 0.5:
                end += 1
            if end - start < min_frames:
                continue
            notes.append(
                {
                    "start_s": start / fps + centre_offset,
                    "end_s": end / fps + centre_offset,
                    "pitch_midi": 21 + i,
                    "confidence": float(min(1.0, a[start] / (peak + 1e-9))),
                }
            )
    return notes


class SpectralPeakDetector:
    def __init__(
        self,
        sr: int = SR,
        n_fft: int = N_FFT,
        hop: int = HOP,
        rel_floor: float = 0.04,
        abs_floor: float = 1e-4,
        min_note_frames: int = 3,
        rise_ratio: float = 1.8,
        rise_ratio_min: float = 0.10,
        abs_ratio: float = 0.25,
        max_gap_frames: int = 5,
    ) -> None:
        self.sr = sr
        self.n_fft = n_fft
        self.hop = hop
        self.rel_floor = rel_floor
        self.abs_floor = abs_floor
        self.min_note_frames = min_note_frames
        self.rise_ratio = rise_ratio
        self.rise_ratio_min = rise_ratio_min
        self.abs_ratio = abs_ratio
        self.max_gap_frames = max_gap_frames
        self.freqs = np.fft.rfftfreq(n_fft, 1.0 / sr)

    def frame_pitches(self, V: np.ndarray) -> np.ndarray:
        """(n_frames, n_bins) magnitude -> (n_frames, n_keys) activation."""
        n_frames = V.shape[1]
        act = np.zeros((n_frames, 88), dtype=np.float64)
        for t in range(n_frames):
            col = V[:, t]
            floor = max(self.abs_floor, self.rel_floor * float(col.max()))
            peaks = []
            for b in range(1, len(col) - 1):
                if col[b] < floor:
                    continue
                if col[b] >= col[b - 1] and col[b] >= col[b + 1]:
                    # parabolic interpolation in log magnitude
                    y0, y1, y2 = np.log(col[b - 1] + 1e-12), np.log(col[b] + 1e-12), np.log(col[b + 1] + 1e-12)
                    denom = y0 - 2 * y1 + y2
                    delta = 0.5 * (y0 - y2) / denom if denom != 0 else 0.0
                    delta = float(np.clip(delta, -0.5, 0.5))
                    peaks.append((self.freqs[b] + delta * (self.sr / self.n_fft), col[b]))
            peaks.sort()  # low to high
            accepted = []
            for i, (f, mag) in enumerate(peaks):
                is_partial = False
                for f_low, mag_low in accepted:
                    if f <= f_low:
                        continue
                    ratio = f / f_low
                    n = round(ratio)
                    if n >= 2 and abs(ratio - n) < 0.03 and mag <= 2.0 * mag_low:
                        is_partial = True
                        break
                if is_partial:
                    continue
                accepted.append((f, mag))
                midi = int(round(69 + 12 * np.log2(f / 440.0)))
                if 21 <= midi <= 108:
                    act[t, midi - 21] = max(act[t, midi - 21], mag)
        return act

    def detect(self, window: np.ndarray) -> List[Dict]:
        V = stft_mag(window, self.n_fft, self.hop)
        window_max = float(V.max()) if V.size else 0.0
        act = self.frame_pitches(V)
        return activation_to_notes(
            act,
            fps=self.sr / self.hop,
            centre_offset=(self.n_fft / 2.0) / self.sr,
            window_peak=window_max,
            abs_ratio=self.abs_ratio,
            rise_ratio_min=self.rise_ratio_min,
            min_frames=max(self.min_note_frames, 4),
            abs_floor=self.abs_floor,
        )


class SpectralPeakStreaming(StreamingTranscriber):
    name = "spectral_peaks"

    def __init__(self, detector: SpectralPeakDetector, config: LiveConfig) -> None:
        super().__init__(config)
        self.detector = detector

    def _detect(self, window: np.ndarray, window_start_s: float) -> List[Dict]:
        out = []
        for d in self.detector.detect(window):
            out.append(
                {
                    "start_s": window_start_s + d["start_s"],
                    "end_s": window_start_s + d["end_s"],
                    "pitch_midi": d["pitch_midi"],
                    "confidence": d["confidence"],
                }
            )
        return out
