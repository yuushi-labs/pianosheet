#!/usr/bin/env python3
"""Run a real piano recording through the app's analysis front half.

The Android app cannot run its ONNX model on this machine (it uses
`onnxruntime-android`), so this script does exactly what
`FinalTranscriber.transcribe` does up to the model — 22.05 kHz mono, v1.3
denoise (25 Hz high-pass + spectral subtraction + stationary-tone notches) —
then runs the same 2 s / 1 s-hop window plan through the ONNX model and the
Basic Pitch decoder, and writes every window's candidates as CSV.

The Kotlin test `RealTakeAnalysisTest` then feeds those candidates through the
real `NoteCandidateFilter` → `FinalNoteMerger` → `ScoreBuilder` chain, so what is
verified is the app's own code; only the inference itself happens in Python.

Usage:
    python3 phase0-poc/scripts/verify_real_piece.py --wav a.wav --name fur-elise \
        --out app/src/test/resources/real-takes/fur-elise.csv
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from verify_debug_take import (  # noqa: E402
    ROOT,
    MODEL,
    analyse,
    detect_stationary_tones,
    stats,
    tone_mask,
)
from pianosheet_poc.basicpitch_onnx import BasicPitchOnnx  # noqa: E402
from pianosheet_poc.denoise import DenoiseConfig, Denoiser  # noqa: E402

ANNOTATIONS_FPS = 86


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--wav", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--start", type=float, default=0.0)
    parser.add_argument("--seconds", type=float, default=45.0)
    args = parser.parse_args()

    import wave

    with wave.open(args.wav) as handle:
        sample_rate = handle.getframerate()
        raw = np.frombuffer(handle.readframes(handle.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
    start = int(args.start * sample_rate)
    end = min(len(raw), int((args.start + args.seconds) * sample_rate))
    audio = raw[start:end]
    print(f"{args.name}: {len(audio) / sample_rate:.2f}s at {sample_rate} Hz, peak {np.abs(audio).max():.3f}")

    tones = detect_stationary_tones(audio, sample_rate)
    print("stationary tones:", [(round(t.frequency_hz, 1), round(t.prominence_db, 1), round(t.presence, 2)) for t in tones])
    config = DenoiseConfig(strength="standard", highpass_hz=25.0, sample_rate=sample_rate)
    cleaned = Denoiser(config).process(audio, tone_mask=tone_mask(tones, sample_rate))

    model = BasicPitchOnnx(MODEL, intra_op_threads=2)
    notes, windows = analyse(model, cleaned)
    stats(f"{args.name} denoised", notes)

    # Raw per-window candidates (before the app's filter), so the Kotlin test
    # exercises NoteCandidateFilter instead of trusting this script's copy of it.
    window_length = 43844
    hop = sample_rate
    if len(cleaned) <= window_length:
        starts = [0]
        cleaned = np.pad(cleaned, (0, window_length - len(cleaned)))
    else:
        starts = list(range(0, len(cleaned) - window_length, hop)) + [len(cleaned) - window_length]

    from pianosheet_poc.basicpitch_onnx import output_to_notes_polyphonic

    rows = []
    for window_start in starts:
        chunk = cleaned[window_start : window_start + window_length].astype(np.float32)
        result = model.predict_window(chunk)
        raw_notes = output_to_notes_polyphonic(
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
        start_s = window_start / sample_rate
        for s, e, pitch, amplitude, onset_strength in raw_notes:
            onset = start_s + s / ANNOTATIONS_FPS
            end_s = start_s + min(e, 172) / ANNOTATIONS_FPS
            if onset < 0.0:
                continue
            rows.append((start_s, onset, end_s, int(pitch), float(amplitude), float(onset_strength)))

    out = pathlib.Path(args.out)
    if not out.is_absolute():
        out = ROOT / out
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w") as handle:
        handle.write("window_start,onset,end,pitch,confidence,onset_strength\n")
        for row in rows:
            handle.write(
                f"{row[0]:.3f},{row[1]:.4f},{row[2]:.4f},{row[3]},{row[4]:.4f},{row[5]:.4f}\n"
            )
    print(f"wrote {out} ({len(rows)} candidates in {len(starts)} windows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
