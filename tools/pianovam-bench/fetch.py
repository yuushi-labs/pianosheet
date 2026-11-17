import json
import os
import urllib.request

import paths

META = os.path.join(ROOT, "metadata.json")
from paths import DATA_ROOT as ROOT
os.makedirs(ROOT, exist_ok=True)  # noqa: E402
IDS = ["29", "60", "74", "12", "9", "70"]
BASE = "https://huggingface.co/datasets/PianoVAM/PianoVAM_v1/resolve/main/"

if not os.path.exists(META):
    # The dataset card's metadata (CC BY-NC 4.0) is downloaded, never committed.
    urllib.request.urlretrieve(
        "https://huggingface.co/datasets/PianoVAM/PianoVAM_v1/resolve/main/metadata.json", META)
m = json.load(open(META))
for i in IDS:
    rt = m[i]["record_time"]
    for sub, ext in (("Audio", "wav"), ("MIDI", "mid"), ("TSV", "tsv")):
        out = os.path.join(ROOT, sub, f"{rt}.{ext}")
        if os.path.exists(out) and os.path.getsize(out) > 0:
            print("skip", out, flush=True)
            continue
        os.makedirs(os.path.dirname(out), exist_ok=True)
        urllib.request.urlretrieve(BASE + f"{sub}/{rt}.{ext}", out)
        print("done", out, os.path.getsize(out), flush=True)
print("ALL DONE", flush=True)
