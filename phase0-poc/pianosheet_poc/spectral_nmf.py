"""A classic DSP baseline: harmonic-dictionary NMF over a log-free STFT.

This is the "no neural network" comparison point for Phase 0.  It exists to
answer one question honestly: can plain spectral analysis on a phone CPU get
close enough to Basic Pitch that the 1-2 s live budget could be met with a much
smaller implementation?

Method
  * STFT of the window (22.05 kHz, n_fft 2048, hop 256 = 11.6 ms frames)
  * dictionary W with one harmonic template per piano key (MIDI 21..108);
    partial amplitudes ~ 1/h, each partial spread over neighbouring bins with a
    Gaussian whose width grows with frequency
  * non-negative matrix factorisation (KL divergence, multiplicative updates)
    of the magnitude spectrogram onto that dictionary -> activation H[pitch, t]
  * note events from positive jumps in H that also stay above a floor

It is deliberately simple.  Its purpose is to show the accuracy/latency gap and
to be a fallback if a neural model turns out to be impossible on a device.
"""

from __future__ import annotations

from typing import Dict, List, Optional

import numpy as np

from .live import LiveConfig, StreamingTranscriber

SR = 22050
N_FFT = 2048
HOP = 256


def _freqs(sr: int, n_fft: int) -> np.ndarray:
    return np.fft.rfftfreq(n_fft, 1.0 / sr)


def build_piano_dictionary(
    sr: int = SR,
    n_fft: int = N_FFT,
    max_hz: float = 4186.0,
    n_harmonics: int = 20,
    harmonic_rolloff: float = 1.0,
) -> tuple[np.ndarray, np.ndarray]:
    """Return (W, freqs) with W shape (n_bins_kept, 88), columns L2-normalised."""
    freqs = _freqs(sr, n_fft)
    keep = freqs <= max_hz
    freqs_k = freqs[keep]
    n_pitch = 88
    W = np.zeros((len(freqs_k), n_pitch), dtype=np.float64)
    bin_hz = sr / n_fft
    for i in range(n_pitch):
        f0 = 440.0 * 2 ** ((21 + i - 69) / 12.0)
        for h in range(1, n_harmonics + 1):
            fh = f0 * h
            if fh > max_hz:
                break
            amp = 1.0 / (h**harmonic_rolloff)
            spread = max(1.0, 0.35 * fh / bin_hz) * 0.5 + 0.8  # bins
            lo = int(max(0, np.floor((fh - 4 * spread * bin_hz) / bin_hz)))
            hi = int(min(len(freqs_k), np.ceil((fh + 4 * spread * bin_hz) / bin_hz)))
            if hi <= lo:
                continue
            g = np.exp(-0.5 * ((freqs_k[lo:hi] - fh) / (spread * bin_hz)) ** 2)
            W[lo:hi, i] += amp * g
    norms = np.linalg.norm(W, axis=0)
    norms[norms == 0] = 1.0
    W = W / norms
    return W, freqs_k, keep


def stft_mag(audio: np.ndarray, n_fft: int = N_FFT, hop: int = HOP) -> np.ndarray:
    if len(audio) < n_fft:
        audio = np.pad(audio, (n_fft - len(audio), 0))
    n_frames = 1 + (len(audio) - n_fft) // hop
    win = np.hanning(n_fft).astype(np.float32)
    idx = np.arange(n_fft)[None, :] + hop * np.arange(n_frames)[:, None]
    frames = audio[idx] * win
    spec = np.fft.rfft(frames, axis=1)
    return np.abs(spec).T.astype(np.float64)  # (freq, time)


def nmf_kl(V: np.ndarray, W: np.ndarray, n_iter: int = 60, H0: Optional[np.ndarray] = None) -> np.ndarray:
    eps = 1e-9
    H = np.full((W.shape[1], V.shape[1]), 0.1) if H0 is None else H0.copy()
    H = np.maximum(H, eps)
    for _ in range(n_iter):
        Vh = W @ H + eps
        H *= (W.T @ (V / Vh)) / (W.sum(axis=0)[:, None] + eps)
        Wt = W * ((V / Vh) @ H.T) / (H.sum(axis=1)[None, :] + eps)
        W = Wt / (np.linalg.norm(Wt, axis=0, keepdims=True) + eps)
    return H


class NMFDetector:
    def __init__(
        self,
        sr: int = SR,
        n_fft: int = N_FFT,
        hop: int = HOP,
        n_iter: int = 60,
        onset_floor_ratio: float = 0.12,
        onset_jump_ratio: float = 0.22,
        min_note_frames: int = 3,
    ) -> None:
        self.sr = sr
        self.n_fft = n_fft
        self.hop = hop
        self.n_iter = n_iter
        self.onset_floor_ratio = onset_floor_ratio
        self.onset_jump_ratio = onset_jump_ratio
        self.min_note_frames = min_note_frames
        self.W, self.freqs, self.freq_mask = build_piano_dictionary(sr, n_fft)
        self.W = self.W.astype(np.float64)

    def frame_rate(self) -> float:
        return self.sr / self.hop

    def detect(self, window: np.ndarray) -> List[Dict]:
        V = stft_mag(window, self.n_fft, self.hop)
        window_peak = float(V.max()) if V.size else 0.0
        V = V[self.freq_mask, :]
        H = nmf_kl(V, self.W, n_iter=self.n_iter)
        # H is scaled by the dictionary, so put it on a comparable scale first
        H = H / (np.max(H) + 1e-12)
        from .spectral_peaks import activation_to_notes

        return activation_to_notes(
            H.T,
            fps=self.frame_rate(),
            centre_offset=(self.n_fft / 2.0) / self.sr,
            window_peak=1.0,
            abs_ratio=0.18,
            rise_ratio_min=0.10,
            min_frames=4,
        )


class NMFStreaming(StreamingTranscriber):
    name = "nmf_harmonic"

    def __init__(self, detector: NMFDetector, config: LiveConfig) -> None:
        super().__init__(config)
        self.detector = detector

    def _detect(self, window: np.ndarray, window_start_s: float) -> List[Dict]:
        # only the newest part of the window can produce new events; decoding the
        # whole window every step would re-report everything already shown
        keep_from = max(0.0, window_start_s + len(window) / self.detector.sr
                        - self.config.step_s - 0.30)
        out = []
        for d in self.detector.detect(window):
            start = window_start_s + d["start_s"]
            end = window_start_s + d["end_s"]
            if start < keep_from:
                continue
            out.append(
                {
                    "start_s": start,
                    "end_s": end,
                    "pitch_midi": d["pitch_midi"],
                    "confidence": d["confidence"],
                }
            )
        return out
