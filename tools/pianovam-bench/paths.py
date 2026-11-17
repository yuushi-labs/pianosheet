"""Where the benchmark keeps its data (all paths used by the other scripts)."""
from __future__ import annotations

import os

HERE = os.path.dirname(os.path.abspath(__file__))
# tools/pianovam-bench -> repository root
REPO = os.path.dirname(os.path.dirname(HERE))

# The dataset, excerpts, caches and results live outside the repository: the
# audio is ~220 MB and the model outputs another ~50 MB.  Point PIANOVAM_ROOT at
# a folder of your own; the default is a sibling of the checkout.
DATA_ROOT = os.environ.get("PIANOVAM_ROOT", os.path.abspath(os.path.join(REPO, "..", "pianovam")))

PHASE0 = os.path.join(REPO, "phase0-poc")
FIXTURES = os.path.join(REPO, "test-fixtures")
