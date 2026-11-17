"""Audio-input experiments: level, noise, denoise strength, normalisation."""
from __future__ import annotations

import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app_final as af  # noqa: E402
import app_pipeline as ap  # noqa: E402
import pvlib  # noqa: E402

from paths import DATA_ROOT as ROOT  # noqa: E402
IDS = ["29", "60", "74", "9"]


def rms(x):
    return float(np.sqrt(np.mean(np.square(x))))


def add_noise(audio, snr_db, seed=7):
    rng = np.random.default_rng(seed)
    sig = rms(audio)
    noise = rng.normal(0.0, 1.0, len(audio)).astype(np.float32)
    target = sig / (10 ** (snr_db / 20.0))
    noise *= target / max(1e-9, rms(noise))
    # a little pink-ish colour instead of pure white
    b = np.array([0.049922035, -0.095993537, 0.050612699, -0.004408786])
    a = np.array([1.0, -2.494956002, 2.017265875, -0.522189400])
    from scipy.signal import lfilter

    noise = lfilter(b, a, noise).astype(np.float32)
    noise *= target / max(1e-9, rms(noise))
    return np.clip(audio + noise, -1.0, 1.0)


def variants(pid):
    audio, sr = pvlib.read_wav_mono(os.path.join(ROOT, "excerpts", f"pv{pid}.wav"))
    peak = float(np.abs(audio).max())
    quiet = audio * min(1.0, (0.02 / max(peak, 1e-9)))  # about -34 dBFS peak
    return {
        "clean": (audio, sr),
        "quiet": (quiet, sr),
        "snr20": (add_noise(audio, 20.0), sr),
        "snr12": (add_noise(audio, 12.0), sr),
    }


def metrics_for(pid, notes):
    gt = pvlib.load_gt_excerpt(pid)
    ref = [{"pitch": g["pitch"], "onset": g["onset"], "offset": g["offset"]} for g in gt]
    est = [{"pitch": n.pitch_midi, "onset": n.onset_ms / 1000.0,
            "offset": (n.onset_ms + n.duration_ms) / 1000.0} for n in notes]
    s = pvlib.summary(ref, est, pid)
    return s["f1"], s["precision"], s["recall"], len(notes)


def run_grid():
    v15 = dict(onset_threshold=0.55, frame_threshold=0.30, min_note_length=6, melodia=True,
               filter_settings=ap.FilterSettings(min_onset_strength=0.30), harmonic_owner=True)
    rows = []
    for pid in IDS:
        vs = variants(pid)
        for vname, (audio, sr) in vs.items():
            for den in ("off", "weak", "standard", "strong"):
                for norm in (0.0, 0.02):
                    r = af.run_final(audio, sr, denoise=den, gain_target=norm or None, **v15)
                    f1, p, rc, n = metrics_for(pid, r["notes"])
                    rows.append({"piece": pid, "variant": vname, "denoise": den,
                                 "norm": bool(norm), "f1": round(f1, 4), "p": round(p, 4),
                                 "r": round(rc, 4), "notes": n})
                    print(f"{pid} {vname:6s} denoise={den:8s} norm={int(bool(norm))} "
                          f"F1={f1:.3f} P={p:.3f} R={rc:.3f} n={n}", flush=True)
    json.dump(rows, open(os.path.join(ROOT, "results", "audio-input.json"), "w"), indent=1)
    return rows


if __name__ == "__main__":
    run_grid()
