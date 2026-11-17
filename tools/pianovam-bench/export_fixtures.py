"""Export the synthetic fixtures for the Kotlin replay (T-014 etc.)."""
from __future__ import annotations

import glob
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "phase0-poc"))
import app_final  # noqa: E402
import pvlib
from paths import REPO as REPO_ROOT  # noqa: E402
from pianosheet_poc.denoise import DenoiseConfig, Denoiser, detect_mains_hz  # noqa: E402

FIX = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "test-fixtures")
OUT = os.path.join(ROOT, "replay")
os.makedirs(OUT, exist_ok=True)


def export(test_id: str) -> None:
    gt = json.load(open(os.path.join(FIX, "ground_truth", f"{test_id}.json")))
    audio44, sr = pvlib.read_wav_mono(os.path.join(REPO_ROOT, gt["wav"]))
    audio22 = app_final.resample_to_22k(audio44, sr)
    pvlib.write_wav(os.path.join(OUT, f"{test_id}.resampled.wav"), audio22, app_final.SR)
    notch = detect_mains_hz(audio22, app_final.SR)
    cleaned = Denoiser(DenoiseConfig(strength="standard", highpass_hz=25.0, notch_hz=notch)).process(audio22)
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
        for n in app_final.ap.decode(res.note, res.onset, onset_threshold=0.55,
                                     frame_threshold=0.30, min_note_length=6, melodia_trick=True):
            rows.append(
                f"{wstart:.6f},{wstart + n.start_frame * app_final.FRAME:.6f},"
                f"{wstart + min(n.end_frame, app_final.ap.N_FRAMES) * app_final.FRAME:.6f},"
                f"{n.pitch_midi},{n.amplitude:.6f},{n.onset_strength:.6f}"
            )
    open(os.path.join(OUT, f"{test_id}.candidates.csv"), "w").write("\n".join(rows) + "\n")
    json.dump({"id": test_id, "title": gt["name"], "durationSec": len(prepared) / app_final.SR,
               "gt": [{"pitch": n["pitch_midi"], "onset": n["onset_s"], "offset": n.get("offset_s", n["onset_s"] + 0.2)}
                      for n in gt["notes"]]},
              open(os.path.join(OUT, f"{test_id}.info.json"), "w"), indent=1)
    print(test_id, "windows", len(starts), "candidates", len(rows) - 1, flush=True)


if __name__ == "__main__":
    ids = sys.argv[1:] or ["T-010", "T-014", "T-019", "T-020"]
    for test_id in ids:
        export(test_id)
