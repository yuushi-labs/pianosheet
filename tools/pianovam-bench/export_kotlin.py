"""Export per-window model candidates so the app's own Kotlin can replay them.

The JVM cannot run `onnxruntime-android`, so the model half runs here (Python
ONNX, same 2 s / 1 s-hop window plan, same decoder port) and every stage after
inference is executed by the app's Kotlin in `PianoVamBenchmarkTest`.
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app_final
from pianosheet_poc.denoise import detect_mains_hz as _dmh
app_final.detect_mains_hz = _dmh
import pvlib

from paths import DATA_ROOT as ROOT  # noqa: E402
OUT = os.path.join(ROOT, "replay")
os.makedirs(OUT, exist_ok=True)


def export(pid: str, denoise: str = "standard") -> None:
    audio44, sr = pvlib.read_wav_mono(os.path.join(ROOT, "excerpts", f"pv{pid}.wav"))
    audio22 = app_final.resample_to_22k(audio44, sr)
    # WAV the harmonic filter reads in the app: the resampled take before denoise
    pvlib.write_wav(os.path.join(OUT, f"pv{pid}.resampled.wav"), audio22, app_final.SR)

    # Same window plan as FinalTranscriber, candidates before any filtering.
    notch = app_final.detect_mains_hz(audio22, app_final.SR)
    from pianosheet_poc.denoise import DenoiseConfig, Denoiser

    cleaned = Denoiser(DenoiseConfig(strength=denoise, highpass_hz=25.0, notch_hz=notch)).process(audio22)
    prepared = app_final.startup_treatment(cleaned)
    starts = app_final.ap.window_starts(len(prepared), app_final.ap.AUDIO_N_SAMPLES, 22050)
    rows = ["windowStart,onsetS,endS,pitch,confidence,onsetStrength"]
    model = app_final.get_model()
    for start in starts:
        end = min(start + app_final.ap.AUDIO_N_SAMPLES, len(prepared))
        window = prepared[start:end]
        wstart = start / app_final.SR
        pad = app_final.ap.AUDIO_N_SAMPLES - len(window)
        if pad > 0:
            window = np.concatenate([np.zeros(pad, dtype=np.float32), window])
            wstart -= pad / app_final.SR
        res = model.predict_window(window)
        # Decoder settings of the app after this change (0.3.1-phase2):
        # onset 0.55, frame 0.30, minimum note length 6 frames, melodia on.
        notes = app_final.ap.decode(
            res.note,
            res.onset,
            onset_threshold=0.55,
            frame_threshold=0.30,
            min_note_length=6,
            melodia_trick=True,
        )
        for n in notes:
            rows.append(
                f"{wstart:.6f},{wstart + n.start_frame * app_final.FRAME:.6f},"
                f"{wstart + min(n.end_frame, app_final.ap.N_FRAMES) * app_final.FRAME:.6f},"
                f"{n.pitch_midi},{n.amplitude:.6f},{n.onset_strength:.6f}"
            )
    open(os.path.join(OUT, f"pv{pid}.candidates.csv"), "w").write("\n".join(rows) + "\n")
    info = {
        "id": pid,
        "title": pvlib.PIECES[pid][0],
        "durationSec": len(prepared) / app_final.SR,
        "humHz": notch,
        "windows": len(starts),
        "candidates": len(rows) - 1,
        "gt": json.load(open(os.path.join(ROOT, "excerpts", f"pv{pid}.gt.json")))["notes"],
    }
    json.dump(info, open(os.path.join(OUT, f"pv{pid}.info.json"), "w"), indent=1)
    print(pid, "windows", len(starts), "candidates", len(rows) - 1, flush=True)


if __name__ == "__main__":
    ids = sys.argv[1:] or ["29", "60", "74", "12", "9", "70"]
    for pid in ids:
        export(pid)
