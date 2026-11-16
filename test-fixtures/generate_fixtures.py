#!/usr/bin/env python3
"""Generate the Phase 0 WAV fixtures and their ground truth.

    python3 test-fixtures/generate_fixtures.py

Writes
  test-fixtures/wav/<test_id>_<name>.wav      44.1 kHz / 16 bit / mono
  test-fixtures/ground_truth/<test_id>.json   exact note events used to render

The audio is rendered by `piano_synth.py` with fixed seeds, so re-running the
script reproduces the same files (recorded sha256 in ground_truth/*.json).
"""

from __future__ import annotations

import hashlib
import json
import pathlib
import sys

import numpy as np
import soundfile as sf

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from piano_synth import SR, build_fixtures, render_fixture  # noqa: E402


def main() -> int:
    wav_dir = HERE / "wav"
    gt_dir = HERE / "ground_truth"
    wav_dir.mkdir(exist_ok=True)
    gt_dir.mkdir(exist_ok=True)

    for fixture in build_fixtures():
        audio = render_fixture(fixture, sr=SR)
        path = wav_dir / f"{fixture.test_id}_{fixture.name}.wav"
        sf.write(path, audio, SR, subtype="PCM_16")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()

        gt = {
            "test_id": fixture.test_id,
            "name": fixture.name,
            "description": fixture.description,
            "sample_rate": SR,
            "duration_s": round(len(audio) / SR, 3),
            "level_db": fixture.level_db,
            "noise_snr_db": fixture.noise_snr_db,
            "noise": None
            if fixture.noise_snr_db is None
            else {
                "hum_hz": fixture.hum_hz,
                "hum_ratio": fixture.hum_ratio,
                "rumble_level": fixture.rumble_level,
                "rumble_hz": list(fixture.rumble_hz),
                "rumble_mod_hz": fixture.rumble_mod_hz,
            },
            "wav": str(path.relative_to(HERE.parent)),
            "sha256": digest,
            "notes": [
                {
                    "pitch_midi": n.pitch,
                    "onset_s": round(n.onset, 4),
                    "offset_s": round(n.onset + n.duration, 4),
                    "velocity": n.velocity,
                    "pedal": n.pedal,
                }
                for n in sorted(fixture.notes, key=lambda x: (x.onset, x.pitch))
            ],
        }
        (gt_dir / f"{fixture.test_id}.json").write_text(
            json.dumps(gt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        peak = float(np.max(np.abs(audio)))
        print(
            f"{fixture.test_id:7s} {fixture.name:14s} {len(audio)/SR:6.2f}s "
            f"{len(fixture.notes):3d} notes  peak={peak:.3f}  {digest[:12]}"
        )

    print(f"\nwrote {len(list(wav_dir.glob('*.wav')))} wav files to {wav_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
