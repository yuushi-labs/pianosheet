"""Metrics for the Kotlin replay output (or any note JSON) against PianoVAM GT."""
from __future__ import annotations

import json
import os

import numpy as np

import pvlib
import refs

from paths import DATA_ROOT as ROOT  # noqa: E402


def load_kotlin(pid):
    d = json.load(open(os.path.join(ROOT, "replay", f"pv{pid}.kotlin.json")))
    return d


def metrics(pid, est, meta=None):
    gt = pvlib.load_gt_excerpt(pid)
    ref = [{"pitch": g["pitch"], "onset": g["onset"], "offset": g["offset"],
            "pedal": (g["offset"] - g["key_offset"]) > 0.05} for g in gt]
    s = pvlib.summary(ref, est, pid)
    no_pedal = [r for r in ref if not r["pedal"]]
    m = pvlib.match(no_pedal, est)
    s["timing_no_pedal"] = pvlib.timing_errors(no_pedal, est, m["pairs"])
    if meta:
        sc = {
            "tempo": meta["tempoBpm"], "meter": meta["timeSignature"],
            "fifths": meta["fifths"], "mode": meta["mode"], "measures": meta["measures"],
        }
        s["score"] = sc
        s["tempo_acc"] = refs.tempo_accuracy(meta["tempoBpm"], refs.audio_tempo(pvlib.read_wav_mono(
            os.path.join(ROOT, "excerpts", f"pv{pid}.wav"))[1] if False else
            pvlib.read_wav_mono(os.path.join(ROOT, "excerpts", f"pv{pid}.wav"))[0])["bpm"])
    return s


def table(ids=("29", "60", "74", "12", "9", "70")):
    base = json.load(open(os.path.join(ROOT, "results", "baseline.json")))
    rows = []
    for pid in ids:
        k = load_kotlin(pid)
        est = [{"pitch": n["pitch"], "onset": n["onset"], "offset": n["onset"] + n["duration"]}
               for n in k["notes"]]
        s = metrics(pid, est)
        rows.append({"pid": pid, "title": pvlib.PIECES[pid][0], "kotlin": s,
                     "notes": len(est), "tempo": k["tempoBpm"], "meter": k["timeSignature"],
                     "fifths": k["fifths"], "mode": k["mode"], "measures": k["measures"],
                     "baseline": base["pieces"][pid]["final"],
                     "refs": base["pieces"][pid]["refs"],
                     "harmonic": (k["harmonicDropped"], k["harmonicExamined"])})
    return rows


if __name__ == "__main__":
    rows = table()
    print("| 曲 | 前 F1 | 後 F1 | 後 P | 後 R | 音数(前→後) | 和音一致(前→後) | 拍子 | 調 | テンポ |")
    for r in rows:
        k = r["kotlin"]
        b = r["baseline"]
        print(f"| {r['pid']} {r['title'][:22]} | {b['f1']:.3f} | {k['f1']:.3f} | {k['precision']:.3f} | "
              f"{k['recall']:.3f} | {b['n_est']}→{r['notes']} | {b['chord']['accuracy']:.2f}→{k['chord']['accuracy']:.2f} | "
              f"{r['meter']}(ref {r['refs']['meter'][0]}) | {r['fifths']}/{r['mode']} (ref {r['refs']['key']['name']}) | {r['tempo']}")
    tp = sum(r["kotlin"]["tp"] for r in rows)
    fp = sum(r["kotlin"]["fp"] for r in rows)
    fn = sum(r["kotlin"]["fn"] for r in rows)
    p, rec = tp / (tp + fp), tp / (tp + fn)
    chords = [(r["kotlin"]["chord"]["matched"], r["kotlin"]["chord"]["ref_chords"]) for r in rows]
    macro = float(np.mean([r["kotlin"]["f1"] for r in rows]))
    print(f"micro P={p:.4f} R={rec:.4f} F1={2*p*rec/(p+rec):.4f} macro={macro:.4f} "
          f"chord={sum(a for a,_ in chords)/sum(b for _,b in chords):.4f}")
    json.dump([{k: (v if not isinstance(v, np.floating) else float(v)) for k, v in r.items()} for r in rows],
              open(os.path.join(ROOT, "results", "v15-kotlin.json"), "w"), indent=1, default=float)
