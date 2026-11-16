#!/usr/bin/env python3
"""Fetch the official Basic Pitch ONNX model without installing basic-pitch itself.

`pip install basic-pitch` pins tensorflow<2.15.1 (no wheel for CPython 3.12), so
the PoC takes the model file straight out of the published wheel instead of
installing the package:

    python3 phase0-poc/scripts/fetch_basic_pitch_model.py

The wheel is downloaded from PyPI, the `saved_models/icassp_2022/nmp.onnx` entry
is extracted, and its SHA-256 is checked against the value published for the
upstream file.  Nothing else from the wheel is used at runtime; the decoding
steps the PoC needs are ported in `pianosheet_poc/basicpitch_onnx.py`.

Model: Spotify Basic Pitch (ICASSP 2022), Apache License 2.0.
"""

from __future__ import annotations

import hashlib
import pathlib
import shutil
import subprocess
import sys
import tempfile
import zipfile

MODEL_ENTRY = "basic_pitch/saved_models/icassp_2022/nmp.onnx"
EXPECTED_SHA256 = "2c3c1d144bfa61ad236e92e169c13535c880469a12a047d4e73451f2c059a0ec"
HERE = pathlib.Path(__file__).resolve().parent.parent
DEST = HERE / "models" / "nmp.onnx"


def main() -> int:
    DEST.parent.mkdir(parents=True, exist_ok=True)
    if DEST.exists():
        digest = hashlib.sha256(DEST.read_bytes()).hexdigest()
        if digest == EXPECTED_SHA256:
            print(f"already present: {DEST} ({digest[:16]}…)")
            return 0
        print(f"existing file has unexpected hash {digest}; re-fetching")

    with tempfile.TemporaryDirectory() as tmp:
        cmd = [sys.executable, "-m", "pip", "download", "--no-deps", "-d", tmp, "basic-pitch"]
        print("running:", " ".join(cmd))
        subprocess.run(cmd, check=True)
        wheels = sorted(pathlib.Path(tmp).glob("basic_pitch-*.whl"))
        if not wheels:
            print("no wheel downloaded", file=sys.stderr)
            return 1
        with zipfile.ZipFile(wheels[0]) as zf:
            with zf.open(MODEL_ENTRY) as src, open(DEST, "wb") as dst:
                shutil.copyfileobj(src, dst)

    digest = hashlib.sha256(DEST.read_bytes()).hexdigest()
    print(f"wrote {DEST} ({DEST.stat().st_size} bytes)")
    print(f"sha256 {digest}")
    if digest != EXPECTED_SHA256:
        print("WARNING: hash differs from the published upstream model", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
