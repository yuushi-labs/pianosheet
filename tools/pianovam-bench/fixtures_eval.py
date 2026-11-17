"""Absolute note F1 on the repo's synthetic fixtures (exact ground truth)."""
from __future__ import annotations

import glob
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "phase0-poc"))
import app_final  # noqa: E402
import app_pipeline as ap  # noqa: E402
import pvlib
from paths import DATA_ROOT as ROOT  # noqa: E402
from paths import REPO as REPO_ROOT  # noqa: E402

FIX = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "test-fixtures")
CASES = sorted(glob.glob(os.path.join(FIX, "ground_truth", "*.json")))


def run(**cfg):
    rows = []
    for gtfile in CASES:
        gt = json.load(open(gtfile))
        wav = os.path.join(REPO_ROOT, gt["wav"])
        audio, sr = pvlib.read_wav_mono(wav)
        r = app_final.run_final(audio, sr, **cfg)
        est = [{"pitch": n.pitch_midi, "onset": n.onset_ms / 1000.0,
                "offset": (n.onset_ms + n.duration_ms) / 1000.0} for n in r["notes"]]
        ref = [{"pitch": n["pitch_midi"], "onset": n["onset_s"], "offset": n.get("offset_s", n["onset_s"] + 0.2)}
               for n in gt["notes"]]
        s = pvlib.summary(ref, est, gt["test_id"])
        rows.append({"id": gt["test_id"], "name": gt["name"], "f1": s["f1"], "P": s["precision"],
                     "R": s["recall"], "n": len(est), "n_ref": len(ref), "pedal": gt.get("pedal", False),
                     "snr": gt.get("noise_snr_db")})
    macro = float(np.mean([r["f1"] for r in rows]))
    tp = sum(round(r["f1"] * 0, 0) for r in rows)  # placeholder
    return rows, macro


if __name__ == "__main__":
    configs = {
        "baseline": dict(),
        "v15": dict(onset_threshold=0.55, frame_threshold=0.25, min_note_length=6, melodia=True,
                    filter_settings=ap.FilterSettings(min_onset_strength=0.30),
                    harmonic_owner=True),
    }
    out = {}
    for name, cfg in configs.items():
        rows, macro = run(**cfg)
        out[name] = {"rows": rows, "macro_f1": macro}
        print(name, "macroF1", round(macro, 4))
        for r in rows:
            print(f"  {r['id']:7s} {r['name'][:22]:24s} F1={r['f1']:.3f} P={r['P']:.3f} R={r['R']:.3f} "
                  f"n={r['n']}/{r['n_ref']}")
    json.dump(out, open(os.path.join(ROOT, "results", "fixtures.json"), "w"), indent=1)
