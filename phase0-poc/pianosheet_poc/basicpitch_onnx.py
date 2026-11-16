"""Basic Pitch inference through ONNX Runtime, with the official decoding ported.

Why this file exists
--------------------
`pip install basic-pitch` cannot be installed on this environment: basic-pitch
0.4.0 pins `tensorflow<2.15.1`, which has no wheel for CPython 3.12, and the
package's note decoding also pulls librosa / mir_eval / resampy / pretty_midi.
But the package ships the ONNX export of the same ICASSP-2022 model
(`basic_pitch/saved_models/icassp_2022/nmp.onnx`, 228 KB, sha256
2c3c1d144bfa61ad236e92e169c13535c880469a12a047d4e73451f2c059a0ec) and
onnxruntime runs it here.  So the model is used unmodified and the surrounding
steps of basic_pitch 0.4.0 (windowing, output unwrapping, note decoding) are
ported to numpy/scipy below.

Model facts (from the graph itself):
    input   serving_default_input_2:0   (batch, 43844, 1)      float32
    outputs StatefulPartitionedCall:1   (batch, 172, 88)       note activations
            StatefulPartitionedCall:2   (batch, 172, 88)       onset activations
            StatefulPartitionedCall:0   (batch, 172, 264)      pitch contours

43844 samples = 2 s at 22.05 kHz minus one hop; 172 frames per window ->
86 frames/s (11.6 ms per frame), 88 piano keys (MIDI 21..108).

Ported code: basic_pitch/note_creation.py and basic_pitch/inference.py
(Copyright 2022 Spotify AB, Apache License 2.0).  The port keeps the same
thresholds and the "melodia trick" so numbers are comparable to the official
`predict()`.
"""

from __future__ import annotations

import pathlib
import time
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import numpy as np

# ---- constants from basic_pitch/constants.py ---------------------------------
AUDIO_SAMPLE_RATE = 22050
FFT_HOP = 256
AUDIO_WINDOW_LENGTH = 2
AUDIO_N_SAMPLES = AUDIO_SAMPLE_RATE * AUDIO_WINDOW_LENGTH - FFT_HOP  # 43844
ANNOTATIONS_FPS = AUDIO_SAMPLE_RATE // FFT_HOP  # 86
ANNOT_N_FRAMES = ANNOTATIONS_FPS * AUDIO_WINDOW_LENGTH  # 172
ANNOTATIONS_N_SEMITONES = 88
ANNOTATIONS_BASE_FREQUENCY = 27.5
CONTOURS_BINS_PER_SEMITONE = 3
N_FREQ_BINS_CONTOURS = ANNOTATIONS_N_SEMITONES * CONTOURS_BINS_PER_SEMITONE
MIDI_OFFSET = 21
MAX_FREQ_IDX = 87

DEFAULT_ONSET_THRESHOLD = 0.5
DEFAULT_FRAME_THRESHOLD = 0.3
DEFAULT_MINIMUM_NOTE_LENGTH_MS = 127.70  # 11 frames
OVERLAP_FRAMES = 30  # basic_pitch uses a 30-frame (349 ms) window overlap

ONNX_INPUT = "serving_default_input_2:0"
ONNX_NOTE = "StatefulPartitionedCall:1"
ONNX_ONSET = "StatefulPartitionedCall:2"
ONNX_CONTOUR = "StatefulPartitionedCall:0"


def hz_to_midi(freq: float) -> float:
    return 69.0 + 12.0 * np.log2(freq / 440.0)


def midi_to_hz(pitch: float) -> float:
    return 440.0 * (2.0 ** ((pitch - 69.0) / 12.0))


def model_frames_to_time(n_frames: int) -> np.ndarray:
    """Frame indices -> seconds, matching basic_pitch's unwrapped output."""
    original_times = np.arange(n_frames) * (FFT_HOP / AUDIO_SAMPLE_RATE)
    window_numbers = np.floor(np.arange(n_frames) / ANNOT_N_FRAMES)
    window_offset = (FFT_HOP / AUDIO_SAMPLE_RATE) * (
        ANNOT_N_FRAMES - (AUDIO_N_SAMPLES / FFT_HOP)
    ) + 0.0018  # "magic number" from basic_pitch, needed for alignment
    return original_times - (window_offset * window_numbers)


# ---- decoding (ported from basic_pitch/note_creation.py) ---------------------


def get_infered_onsets(onsets: np.ndarray, frames: np.ndarray, n_diff: int = 2) -> np.ndarray:
    diffs = []
    for n in range(1, n_diff + 1):
        frames_appended = np.concatenate([np.zeros((n, frames.shape[1])), frames])
        diffs.append(frames_appended[n:, :] - frames_appended[:-n, :])
    frame_diff = np.min(diffs, axis=0)
    frame_diff[frame_diff < 0] = 0
    frame_diff[:n_diff, :] = 0
    scale = float(np.max(frame_diff))
    if scale > 0:
        frame_diff = float(np.max(onsets)) * frame_diff / scale
    return np.max([onsets, frame_diff], axis=0)


def constrain_frequency(
    onsets: np.ndarray,
    frames: np.ndarray,
    max_freq: Optional[float],
    min_freq: Optional[float],
) -> Tuple[np.ndarray, np.ndarray]:
    if max_freq is not None:
        idx = int(np.round(hz_to_midi(max_freq) - MIDI_OFFSET))
        onsets[:, idx:] = 0
        frames[:, idx:] = 0
    if min_freq is not None:
        idx = int(np.round(hz_to_midi(min_freq) - MIDI_OFFSET))
        onsets[:, :idx] = 0
        frames[:, :idx] = 0
    return onsets, frames


def output_to_notes_polyphonic(
    frames: np.ndarray,
    onsets: np.ndarray,
    onset_thresh: float,
    frame_thresh: float,
    min_note_len: int,
    infer_onsets: bool,
    max_freq: Optional[float],
    min_freq: Optional[float],
    melodia_trick: bool = True,
    energy_tol: int = 11,
) -> List[Tuple[int, int, int, float]]:
    """Raw model output -> [(start_frame, end_frame, midi_pitch, amplitude, onset_strength)].

    `onset_strength` is the model's own onset activation at the note start
    (before the inferred-onset step), 0.0..1.0; ~0 for notes found only by the
    melodia pass inside a decay tail.
    """
    from scipy.signal import argrelmax

    n_frames = frames.shape[0]
    onsets = onsets.copy()
    frames = frames.copy()
    onsets, frames = constrain_frequency(onsets, frames, max_freq, min_freq)
    if infer_onsets:
        onsets = get_infered_onsets(onsets, frames)

    peak_thresh_mat = np.zeros(onsets.shape)
    peaks = argrelmax(onsets, axis=0)
    peak_thresh_mat[peaks] = onsets[peaks]

    onset_idx = np.where(peak_thresh_mat >= onset_thresh)
    onset_time_idx = onset_idx[0][::-1]
    onset_freq_idx = onset_idx[1][::-1]

    # Keep the *raw* predicted onset activation: a real key strike has a high
    # value here, while notes the melodia pass invents inside a decay tail have
    # ~0.  This is what separates a repeated note from a ringing string.
    onset_strength_mat = onsets.copy()

    remaining_energy = frames.copy()
    note_events: List[Tuple[int, int, int, float, float]] = []

    for note_start_idx, freq_idx in zip(onset_time_idx, onset_freq_idx):
        if note_start_idx >= n_frames - 1:
            continue
        i = note_start_idx + 1
        k = 0
        while i < n_frames - 1 and k < energy_tol:
            if remaining_energy[i, freq_idx] < frame_thresh:
                k += 1
            else:
                k = 0
            i += 1
        i -= k
        if i - note_start_idx <= min_note_len:
            continue
        remaining_energy[note_start_idx:i, freq_idx] = 0
        if freq_idx < MAX_FREQ_IDX:
            remaining_energy[note_start_idx:i, freq_idx + 1] = 0
        if freq_idx > 0:
            remaining_energy[note_start_idx:i, freq_idx - 1] = 0
        amplitude = float(np.mean(frames[note_start_idx:i, freq_idx]))
        note_events.append(
            (
                int(note_start_idx),
                int(i),
                int(freq_idx + MIDI_OFFSET),
                amplitude,
                float(onset_strength_mat[note_start_idx, freq_idx]),
            )
        )

    if melodia_trick:
        energy_shape = remaining_energy.shape
        while np.max(remaining_energy) > frame_thresh:
            i_mid, freq_idx = np.unravel_index(np.argmax(remaining_energy), energy_shape)
            remaining_energy[i_mid, freq_idx] = 0
            i = i_mid + 1
            k = 0
            while i < n_frames - 1 and k < energy_tol:
                if remaining_energy[i, freq_idx] < frame_thresh:
                    k += 1
                else:
                    k = 0
                remaining_energy[i, freq_idx] = 0
                if freq_idx < MAX_FREQ_IDX:
                    remaining_energy[i, freq_idx + 1] = 0
                if freq_idx > 0:
                    remaining_energy[i, freq_idx - 1] = 0
                i += 1
            i_end = i - 1 - k
            i = i_mid - 1
            k = 0
            while i > 0 and k < energy_tol:
                if remaining_energy[i, freq_idx] < frame_thresh:
                    k += 1
                else:
                    k = 0
                remaining_energy[i, freq_idx] = 0
                if freq_idx < MAX_FREQ_IDX:
                    remaining_energy[i, freq_idx + 1] = 0
                if freq_idx > 0:
                    remaining_energy[i, freq_idx - 1] = 0
                i -= 1
            i_start = i + 1 + k
            if i_start < 0 or i_end >= n_frames:
                continue
            if i_end - i_start <= min_note_len:
                continue
            amplitude = float(np.mean(frames[i_start:i_end, freq_idx]))
            note_events.append(
                (
                    int(i_start),
                    int(i_end),
                    int(freq_idx + MIDI_OFFSET),
                    amplitude,
                    float(onset_strength_mat[i_start, freq_idx]),
                )
            )

    return note_events


# ---- ONNX adapter ------------------------------------------------------------


@dataclass
class WindowResult:
    note: np.ndarray
    onset: np.ndarray
    contour: np.ndarray
    compute_ms: float


class BasicPitchOnnx:
    """The ICASSP-2022 Basic Pitch model, executed by ONNX Runtime on CPU."""

    def __init__(
        self,
        model_path: str | pathlib.Path,
        intra_op_threads: Optional[int] = None,
        inter_op_threads: int = 1,
        allow_spinning: bool = False,
    ) -> None:
        import onnxruntime as ort

        self.model_path = str(model_path)
        so = ort.SessionOptions()
        so.intra_op_num_threads = int(intra_op_threads or 0)
        so.inter_op_num_threads = int(inter_op_threads)
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        # ORT spins worker threads between calls by default, which burns CPU while
        # idle and would not be acceptable in an Android foreground service. It
        # makes no difference to per-window latency here (22.5 ms either way).
        so.add_session_config_entry(
            "session.intra_op.allow_spinning", "1" if allow_spinning else "0"
        )
        self.session = ort.InferenceSession(
            self.model_path, sess_options=so, providers=["CPUExecutionProvider"]
        )
        self._first_predict_ms: Optional[float] = None

    # -- single 2 s window ----------------------------------------------------
    def predict_window(self, audio: np.ndarray) -> WindowResult:
        """`audio`: float32 mono 22.05 kHz, up to AUDIO_N_SAMPLES samples.

        Shorter input is zero-padded at the end, exactly as basic_pitch does.
        """
        if audio.dtype != np.float32:
            audio = audio.astype(np.float32)
        if audio.ndim > 1:
            audio = audio.reshape(-1)
        if len(audio) < AUDIO_N_SAMPLES:
            audio = np.pad(audio, (0, AUDIO_N_SAMPLES - len(audio)))
        elif len(audio) > AUDIO_N_SAMPLES:
            audio = audio[:AUDIO_N_SAMPLES]
        x = audio.reshape(1, AUDIO_N_SAMPLES, 1)
        t0 = time.perf_counter()
        note, onset, contour = self.session.run(
            [ONNX_NOTE, ONNX_ONSET, ONNX_CONTOUR], {ONNX_INPUT: x}
        )
        dt = (time.perf_counter() - t0) * 1000.0
        if self._first_predict_ms is None:
            self._first_predict_ms = dt
        return WindowResult(note=note[0], onset=onset[0], contour=contour[0], compute_ms=dt)

    @property
    def first_predict_ms(self) -> Optional[float]:
        return self._first_predict_ms

    # -- whole file (FinalTranscriber baseline) -------------------------------
    def transcribe(
        self,
        audio: np.ndarray,
        onset_thresh: float = DEFAULT_ONSET_THRESHOLD,
        frame_thresh: float = DEFAULT_FRAME_THRESHOLD,
        min_note_len: int = int(np.round(ANNOTATIONS_FPS * DEFAULT_MINIMUM_NOTE_LENGTH_MS / 1000)),
        infer_onsets: bool = True,
        melodia_trick: bool = True,
        min_freq: Optional[float] = None,
        max_freq: Optional[float] = None,
    ) -> Tuple[List[dict], dict]:
        """Run the whole file in overlapping 2 s windows, then decode.

        Returns (note_events, info) where note_events are dicts with
        start_s / end_s / pitch_midi / confidence, and info holds the
        measured inference statistics.
        """
        if audio.dtype != np.float32:
            audio = audio.astype(np.float32)
        overlap_len = OVERLAP_FRAMES * FFT_HOP
        hop_size = AUDIO_N_SAMPLES - overlap_len
        original_length = len(audio)
        padded = np.concatenate(
            [np.zeros(int(overlap_len / 2), dtype=np.float32), audio]
        )

        note_parts: List[np.ndarray] = []
        onset_parts: List[np.ndarray] = []
        contour_parts: List[np.ndarray] = []
        times: List[float] = []
        n_windows = 0
        for start in range(0, len(padded), hop_size):
            window = padded[start : start + AUDIO_N_SAMPLES]
            res = self.predict_window(window)
            note_parts.append(res.note)
            onset_parts.append(res.onset)
            contour_parts.append(res.contour)
            times.append(res.compute_ms)
            n_windows += 1
            if start + AUDIO_N_SAMPLES >= len(padded):
                break

        n_olap = int(0.5 * OVERLAP_FRAMES)
        n_output_frames = int(np.floor(original_length * (ANNOTATIONS_FPS / AUDIO_SAMPLE_RATE)))

        def unwrap(parts: List[np.ndarray]) -> np.ndarray:
            # basic_pitch trims `n_olap` frames from BOTH ends of EVERY window
            # (batch axis preserved), then flattens.  Trimming the stacked array
            # as a whole instead would shift every window after the first by
            # 2*n_olap frames.
            arr = np.stack(parts, axis=0)  # (n_windows, 172, n_freqs)
            if n_olap > 0:
                arr = arr[:, n_olap:-n_olap, :]
            flat = arr.reshape(-1, arr.shape[-1])
            return flat[:n_output_frames, :]

        note = unwrap(note_parts)
        onset = unwrap(onset_parts)
        contour = unwrap(contour_parts)

        raw = output_to_notes_polyphonic(
            note,
            onset,
            onset_thresh=onset_thresh,
            frame_thresh=frame_thresh,
            min_note_len=min_note_len,
            infer_onsets=infer_onsets,
            max_freq=max_freq,
            min_freq=min_freq,
            melodia_trick=melodia_trick,
        )
        frame_times = model_frames_to_time(contour.shape[0])
        events = [
            {
                "start_s": float(frame_times[s]),
                "end_s": float(frame_times[min(e, len(frame_times) - 1)]),
                "pitch_midi": int(p),
                "confidence": float(a),
                "onset_strength": float(o),
            }
            for s, e, p, a, o in raw
        ]
        events.sort(key=lambda d: d["start_s"])
        info = {
            "n_windows": n_windows,
            "hop_size": hop_size,
            "overlap_len": overlap_len,
            "n_output_frames": int(contour.shape[0]),
            "compute_ms_total": float(np.sum(times)),
            "compute_ms_mean": float(np.mean(times)),
            "compute_ms_p95": float(np.percentile(times, 95)),
        }
        return events, info
