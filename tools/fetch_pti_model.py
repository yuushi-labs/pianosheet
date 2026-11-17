#!/usr/bin/env python3
"""Fetch the PTI (piano-notes-v1) ONNX model into the app's assets.

The 94.1 MB weights are not committed: the repository stays small enough to
clone and push, and the file is reproducible from its published hash.  The app
ships them inside the APK, so the installed app still needs no network.

    python3 tools/fetch_pti_model.py

Downloads `piano-notes-v1.onnx` from Hugging Face
(`Clement11800/pianino-model`, an ONNX export of ByteDance's PTI note branch)
and checks the SHA-256 published in THIRD_PARTY_NOTICES.md.

Model: Kong, Li, Song, Wan, Wang, "High-resolution Piano Transcription with
Pedals by Regressing Onset and Offset Times", IEEE/ACM TASLP 2021.
Weights: CC BY 4.0 (attribution required — see THIRD_PARTY_NOTICES.md).
"""

from __future__ import annotations

import hashlib
import pathlib
import shutil
import sys
import urllib.request

URL = "https://huggingface.co/Clement11800/pianino-model/resolve/main/piano-notes-v1.onnx"
EXPECTED_SHA256 = "9e33ffc0382fb023e0b1aac2abd0c3cb26a5d56fbcc246d7f2c0fc33c569f2ba"
EXPECTED_BYTES = 98_689_388
HERE = pathlib.Path(__file__).resolve().parent.parent
DEST = HERE / "app" / "src" / "main" / "assets" / "models" / "piano-notes-v1.onnx"


def digest_of(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main() -> int:
    DEST.parent.mkdir(parents=True, exist_ok=True)
    if DEST.exists():
        if DEST.stat().st_size == EXPECTED_BYTES and digest_of(DEST) == EXPECTED_SHA256:
            print(f"already present: {DEST}")
            return 0
        print("existing file has an unexpected size or hash; re-fetching")

    temp = DEST.with_suffix(".onnx.part")
    if temp.exists():
        temp.unlink()
    print(f"downloading {URL}")
    with urllib.request.urlopen(URL, timeout=300) as response, open(temp, "wb") as out:
        shutil.copyfileobj(response, out, length=1 << 20)

    size = temp.stat().st_size
    digest = digest_of(temp)
    if size != EXPECTED_BYTES or digest != EXPECTED_SHA256:
        temp.unlink()
        print(f"unexpected download: {size} bytes, sha256 {digest}", file=sys.stderr)
        return 1
    temp.replace(DEST)
    print(f"wrote {DEST} ({size} bytes)")
    print(f"sha256 {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
