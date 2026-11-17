"""Cache Basic Pitch window outputs so decoding can be re-run many times cheaply."""
from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app_final
import pvlib

from paths import DATA_ROOT as ROOT  # noqa: E402
CACHE = os.path.join(ROOT, "cache")
os.makedirs(CACHE, exist_ok=True)


def prepare_audio(pid: str, denoise: str = "standard"):
    audio44, sr = pvlib.read_wav_mono(os.path.join(ROOT, "excerpts", f"pv{pid}.wav"))
    audio22 = app_final.resample_to_22k(audio44, sr)
    if denoise == "off":
        return audio22, audio22
    from pianosheet_poc.denoise import DenoiseConfig, Denoiser

    notch = app_final.detect_mains_hz(audio22, app_final.SR)
    cleaned = Denoiser(DenoiseConfig(strength=denoise, highpass_hz=25.0, notch_hz=notch)).process(audio22)
    return app_final.startup_treatment(cleaned), audio22


def build(pid: str, hop_s: float = 1.0, denoise: str = "standard", tag: str = "h1"):
    path = os.path.join(CACHE, f"pv{pid}-{tag}.npz")
    prepared, _ = prepare_audio(pid, denoise)
    hop = int(round(hop_s * app_final.SR))
    starts = app_final.ap.window_starts(len(prepared), app_final.ap.AUDIO_N_SAMPLES, hop)
    model = app_final.get_model()
    notes, onsets, seeds = [], [], []
    for start in starts:
        end = min(start + app_final.ap.AUDIO_N_SAMPLES, len(prepared))
        window = prepared[start:end]
        wstart = start / app_final.SR
        pad = app_final.ap.AUDIO_N_SAMPLES - len(window)
        if pad > 0:
            window = np.concatenate([np.zeros(pad, dtype=np.float32), window])
            wstart -= pad / app_final.SR
        res = model.predict_window(window)
        notes.append(res.note.astype(np.float32))
        onsets.append(res.onset.astype(np.float32))
        seeds.append((wstart, end / app_final.SR))
    np.savez_compressed(
        path,
        note=np.stack(notes),
        onset=np.stack(onsets),
        windows=np.array(seeds),
        audio=prepared.astype(np.float32),
    )
    print("cached", path, len(starts), "windows", flush=True)


if __name__ == "__main__":
    ids = sys.argv[1:] or ["29", "60", "74", "12", "9", "70"]
    for pid in ids:
        build(pid)
