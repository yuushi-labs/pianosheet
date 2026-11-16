"""Audio loading / resampling helpers for the Phase 0 PoC.

The Android pipeline captures 16-bit PCM at the device's native rate (usually
44.1 or 48 kHz) and has to bring it to the rate the transcription model wants
(22.05 kHz for Basic Pitch).  The same conversion is done here with a
polyphase filter so the measured latency includes it.
"""

from __future__ import annotations

import math
import pathlib
from fractions import Fraction
from typing import Tuple

import numpy as np

try:  # soundfile is a libsndfile binding, has wheels for CPython 3.12
    import soundfile as sf
except Exception:  # pragma: no cover
    sf = None


def load_wav(path: str | pathlib.Path, target_sr: int = 22050) -> Tuple[np.ndarray, int]:
    """Read a WAV file as mono float32 at `target_sr`."""
    path = pathlib.Path(path)
    if sf is not None:
        audio, sr = sf.read(str(path), dtype="float32", always_2d=True)
        audio = audio.mean(axis=1)
    else:  # minimal fallback, 16/24/32-bit PCM only
        import wave

        with wave.open(str(path), "rb") as w:
            sr = w.getframerate()
            n_ch = w.getnchannels()
            width = w.getsampwidth()
            raw = w.readframes(w.getnframes())
        dtype = {2: "<i2", 3: None, 4: "<i4"}[width]
        if width == 3:
            buf = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3)
            ints = (
                buf[:, 0].astype(np.int32)
                | (buf[:, 1].astype(np.int32) << 8)
                | (buf[:, 2].astype(np.int32) << 16)
            )
            ints = np.where(ints >= 1 << 23, ints - (1 << 24), ints)
            audio = ints.astype(np.float32) / float(1 << 23)
        else:
            audio = np.frombuffer(raw, dtype=dtype).astype(np.float32)
            audio /= float(np.iinfo(np.dtype(dtype)).max)
        audio = audio.reshape(-1, n_ch).mean(axis=1)

    audio = np.ascontiguousarray(audio, dtype=np.float32)
    if sr != target_sr:
        audio = resample(audio, sr, target_sr)
    return audio, target_sr


def resample(audio: np.ndarray, sr_in: int, sr_out: int) -> np.ndarray:
    """Polyphase resampling with a rational ratio (44.1k -> 22.05k is /2)."""
    if sr_in == sr_out:
        return np.ascontiguousarray(audio, dtype=np.float32)
    from scipy.signal import resample_poly

    ratio = Fraction(sr_out, sr_in).limit_denominator(1000)
    out = resample_poly(audio.astype(np.float64), ratio.numerator, ratio.denominator)
    return np.ascontiguousarray(out, dtype=np.float32)


def write_wav(path: str | pathlib.Path, audio: np.ndarray, sr: int) -> None:
    pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
    if sf is not None:
        sf.write(str(path), audio, sr, subtype="PCM_16")
        return
    import wave

    data = np.clip(audio, -1.0, 1.0)
    pcm = (data * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def db(x: float) -> float:
    return 20.0 * math.log10(max(x, 1e-12))
