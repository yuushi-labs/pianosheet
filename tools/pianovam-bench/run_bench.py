"""Run the PianoVAM benchmark for the current (or an experimental) pipeline."""
from __future__ import annotations

import json
import os
import sys
import time

import numpy as np

import app_final
import app_live
import pvlib
import refs

from paths import DATA_ROOT as ROOT  # noqa: E402
RES = os.path.join(ROOT, "results")
os.makedirs(RES, exist_ok=True)

IDS = ["29", "60", "74", "12", "9", "70"]


def notes_to_est(notes) -> list[dict]:
    out = []
    for n in notes:
        if isinstance(n, dict):
            out.append(n)
        else:
            out.append({"pitch": n.pitch_midi, "onset": n.onset_ms / 1000.0,
                        "offset": n.onset_ms / 1000.0 + n.duration_ms / 1000.0,
                        "duration": n.duration_ms / 1000.0})
    return out


def gt_to_ref(gt: list[dict], no_pedal_only: bool = False) -> list[dict]:
    rows = []
    for g in gt:
        if no_pedal_only and (g["offset"] - g["key_offset"]) > 0.05:
            continue
        rows.append({"pitch": g["pitch"], "onset": g["onset"], "offset": g["offset"],
                     "key_offset": g["key_offset"], "pedal": (g["offset"] - g["key_offset"]) > 0.05})
    return rows


def evaluate(pid: str, ref: list[dict], est: list[dict]) -> dict:
    base = pvlib.summary(ref, est, pid)
    # offset error on notes the player did not pedal
    np_ref = [r for r in ref if not r["pedal"]]
    m = pvlib.match(np_ref, est)
    pedal_stats = pvlib.timing_errors(np_ref, est, m["pairs"])
    base["timing_no_pedal"] = pedal_stats
    base["ref_no_pedal_notes"] = len(np_ref)
    return base


def score_comparison(score, ref, entry, est, gt):
    tempo = refs.tempo_accuracy(float(score["tempo"]), float(ref["audio_bpm"]))
    tempo_vs_onset = refs.tempo_accuracy(float(score["tempo"]), float(ref["gt_onset_bpm"] or 0))
    meter_ref = tuple(ref["meter"][0])
    key_ref = refs.key_to_fifths(ref["key"]["tonic"], ref["key"]["mode"])
    key_est = (int(score["key"][0]), score["key"][1])
    align = refs.downbeat_alignment(est, gt, float(score["beats_per_measure"]),
                                    float(score["beat_sec"]), float(score["score_start_sec"]))
    return {
        "tempo_est": score["tempo"], "tempo_ref_audio": ref["audio_bpm"],
        "tempo_ref_gt_onsets": ref["gt_onset_bpm"], "tempo_vs_audio": tempo,
        "tempo_vs_gt_onsets": tempo_vs_onset,
        "meter_est": list(score["meter"]), "meter_ref": list(meter_ref),
        "meter_match": tuple(score["meter"]) == meter_ref,
        "key_est_fifths_mode": list(key_est), "key_ref": ref["key"]["name"],
        "key_ref_fifths_mode": list(key_ref) if key_ref else None,
        "key_match": bool(key_ref and key_est == key_ref),
        "key_match_relative": bool(key_ref and key_est[0] == key_ref[0]),
        "measures": score["measures"], "bar_alignment": align,
    }


def run(ids=None, out_name="baseline", skip_live=False, final_kwargs=None, live_kwargs=None):
    ids = ids or IDS
    final_kwargs = final_kwargs or {}
    live_kwargs = live_kwargs or {}
    results = {"config": {"final": final_kwargs, "live": live_kwargs}, "pieces": {}}
    totals = {"tp": 0, "fn": 0, "fp": 0, "n_ref": 0, "n_est": 0,
              "chord_ref": 0, "chord_ok": 0, "dupes": 0}
    for pid in ids:
        gt = pvlib.load_gt_excerpt(pid)
        audio, sr = pvlib.read_wav_mono(os.path.join(ROOT, "excerpts", f"pv{pid}.wav"))
        entry = {"title": pvlib.PIECES[pid][0], "gt_notes": len(gt)}
        t0 = time.time()
        fin = app_final.run_final(audio, sr, **final_kwargs)
        entry["final_ms"] = int((time.time() - t0) * 1000)
        est = notes_to_est(fin["notes"])
        ref = gt_to_ref(gt)
        entry["final"] = evaluate(pid, ref, est)
        entry["final_stats"] = {
            "n_windows": fin["n_windows"], "raw": fin["raw_candidates"],
            "filter": fin["filter"], "merge": fin["merge"],
            "startup": fin["startup_suppressed"],
            "harmonic": {"examined": fin["harmonic_examined"], "dropped": fin["harmonic_dropped"]},
            "score": {k: v for k, v in fin["score"].items() if k not in ("bars", "quantized")},
        }
        json.dump(est, open(os.path.join(RES, f"{out_name}-{pid}-final-notes.json"), "w"))

        # ---- reference side: tempo / meter / key from the audio and the GT MIDI
        audio22 = app_final.resample_to_22k(audio, sr)
        at = refs.audio_tempo(audio22)
        ot = refs.onset_tempo(gt)
        entry["refs"] = {"audio_bpm": round(at["bpm"], 2), "gt_onset_bpm": round(ot["bpm"], 2) if ot.get("bpm") else None}
        meta_bpm = entry["refs"]["audio_bpm"]
        entry["refs"]["meter"] = refs.meter_from_gt(gt, meta_bpm)
        entry["refs"]["key"] = refs.key_from_gt(gt)
        entry["final_score_vs_ref"] = score_comparison(fin["score"], entry["refs"], entry,
                                                          est, gt)

        if not skip_live:
            t0 = time.time()
            live = app_live.run_live(audio, sr, model=fin and app_final.get_model(), **live_kwargs)
            entry["live_ms"] = int((time.time() - t0) * 1000)
            lest = notes_to_est(live["notes"])
            entry["live"] = evaluate(pid, ref, lest)
            entry["live_stats"] = {k: v for k, v in live.items() if k != "notes"}
            json.dump(lest, open(os.path.join(RES, f"{out_name}-{pid}-live-notes.json"), "w"))

        e = entry["final"]
        totals["tp"] += e["tp"]; totals["fn"] += e["fn"]; totals["fp"] += e["fp"]
        totals["n_ref"] += e["n_ref"]; totals["n_est"] += e["n_est"]
        totals["dupes"] += e["duplicates"]
        if e["chord"]["accuracy"] is not None:
            totals["chord_ref"] += e["chord"]["ref_chords"]
            totals["chord_ok"] += e["chord"]["matched"]
        results["pieces"][pid] = entry
        f = entry["final"]
        print(f"{pid} {pvlib.PIECES[pid][0][:28]:30s} final F1={f['f1']:.3f} P={f['precision']:.3f} "
              f"R={f['recall']:.3f} n={f['n_est']}/{f['n_ref']} tempo={entry['final_stats']['score']['tempo']}"
              f" meter={entry['final_stats']['score']['meter']} key={entry['final_stats']['score']['key']}", flush=True)
        if "live" in entry:
            l = entry["live"]
            print(f"    live  F1={l['f1']:.3f} P={l['precision']:.3f} R={l['recall']:.3f} "
                  f"n={l['n_est']}/{l['n_ref']} p95={entry['live_stats']['latency_p95_ms']}", flush=True)

    p = totals["tp"] / totals["n_est"] if totals["n_est"] else 0
    r = totals["tp"] / totals["n_ref"] if totals["n_ref"] else 0
    results["micro"] = {
        "tp": totals["tp"], "fn": totals["fn"], "fp": totals["fp"],
        "precision": round(p, 4), "recall": round(r, 4),
        "f1": round(2 * p * r / (p + r), 4) if p + r else 0,
        "macro_f1": round(float(np.mean([results["pieces"][i]["final"]["f1"] for i in ids])), 4),
        "chord_accuracy": round(totals["chord_ok"] / totals["chord_ref"], 4) if totals["chord_ref"] else None,
        "duplicates": totals["dupes"],
    }
    score_rows = [results["pieces"][i]["final_score_vs_ref"] for i in ids]
    results["score_accuracy"] = {
        "tempo_within_4pct_octave": sum(1 for r in score_rows if r["tempo_vs_audio"]["exact_with_octave"]) / len(score_rows),
        "tempo_exact_no_octave": sum(1 for r in score_rows if r["tempo_vs_audio"]["exact_no_octave"]) / len(score_rows),
        "meter_match": sum(1 for r in score_rows if r["meter_match"]) / len(score_rows),
        "key_match": sum(1 for r in score_rows if r["key_match"]) / len(score_rows),
        "key_match_relative": sum(1 for r in score_rows if r["key_match_relative"]) / len(score_rows),
        "bar_alignment_mean": float(np.mean([r["bar_alignment"]["ratio"] for r in score_rows if r["bar_alignment"]["ratio"]])),
    }
    live_rows = [results["pieces"][i]["live"] for i in ids if "live" in results["pieces"][i]]
    if live_rows:
        p = sum(r["tp"] for r in live_rows) / max(1, sum(r["n_est"] for r in live_rows))
        rc = sum(r["tp"] for r in live_rows) / max(1, sum(r["n_ref"] for r in live_rows))
        p95s = [results["pieces"][i]["live_stats"]["latency_p95_ms"] for i in ids
                if "live" in results["pieces"][i] and results["pieces"][i]["live_stats"]["latency_p95_ms"]]
        results["live_micro"] = {
            "precision": round(p, 4), "recall": round(rc, 4),
            "f1": round(2 * p * rc / (p + rc), 4) if p + rc else 0,
            "latency_p95_ms": max(p95s) if p95s else None,
        }
    json.dump(results, open(os.path.join(RES, f"{out_name}.json"), "w"), indent=1, default=float)
    print("MICRO", results["micro"])
    return results


if __name__ == "__main__":
    name = sys.argv[1] if len(sys.argv) > 1 else "baseline"
    skip_live = "--no-live" in sys.argv
    run(out_name=name, skip_live=skip_live)
