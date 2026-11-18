"""Piano-oriented denoising for the *analysis* input (never for the saved take).

Why this file exists
--------------------
The Android capture path stores the microphone signal unprocessed
(`AudioSource.UNPROCESSED`, falling back to `MIC`), because the platform's
speech-oriented `NoiseSuppressor` / `AcousticEchoCanceler` / AGC treat a
piano's decay tails and upper partials as noise and cut them.  Cleaning is
therefore done here, on a *copy* of the audio, just before the transcription
model sees it — so the original recording stays untouched and the final
analysis can be re-run with another setting.

Three stages, in the order the spec lists them (spec v1.3, chapter 20):

1. **25 Hz high-pass** (2nd-order Butterworth biquad).  Removes air-conditioner
   rumble, traffic and handling noise below the piano's range.  The lowest key
   A0 = 27.5 Hz stays audible: -2.3 dB at A0 and -16 dB at 10 Hz (see
   `highpass_response_db`).
2. **Stationary noise suppression** (gentle spectral subtraction).  The noise
   floor is the *local median of the magnitude spectrum* (the level between the
   partials) taken down to its minimum over the last second: a hiss sits there
   and is removed, a note does not and is kept.  A gain floor and gain
   smoothing keep the result from gating quiet notes or decay tails, and nothing
   below 150 Hz is subtracted (the bins there are too coarse to sit "between"
   the partials of a bass note).
3. **Optional mains hum notch** (50 or 60 Hz biquad notch, Q=30).  Off by
   default: it removes the hum by ~30 dB, but on a very quiet take it made the
   model hallucinate a few extra bass notes, so the settings screen only turns
   it on when the room hum is audible (`detect_mains_hz` can pick the frequency).

Strength presets (`off` / `weak` / `standard` / `strong`) map to the
oversubtraction factor, the gain floor and the time smoothing.  `strong` exists
mostly to *measure* the side effect (pedal tails losing 10 dB) so the default
could be chosen from data: live = weak, final analysis = standard.

The implementation is deliberately tiny and stateful-by-chunk (NumPy only, all
filters as biquads, one rFFT per frame) so the same algorithm can be ported to
Kotlin / NDK for the Android live path.

Frame geometry: 1024 samples at 22.05 kHz (46 ms, 21.5 Hz per bin) with a
256-sample hop (75 % overlap, Hann analysis+synthesis windows, overlap-add
normalised by the accumulated window energy).  Sample numbering is preserved
(output sample *n* is input sample *n*), but the newest audio is held back
until every window covering it has been seen: the live path therefore has a
wall-clock delay of `frame_size + hop_size` (58 ms at 22.05 kHz), which the
transcriber adds to its display latency.  The offline path has no delay.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import numpy as np

STRENGTHS = ("off", "weak", "standard", "strong")


# --------------------------------------------------------------------------- #
# configuration
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class SubtractiveParams:
    """Knobs of the spectral-subtraction stage for one strength preset."""

    oversubtraction: float  # how many times the estimated noise floor is subtracted
    floor_db: float  # deepest attenuation allowed (the "residual floor")
    gain_smoothing: float  # 0..1, larger = smoother gains over time (less "musical noise")


# Stationary-tone suppression depth (mirrors DenoiseConfig.toneNotchGainDb).
# 0.4.4: -18 -> -26 dB; a 1.25 kHz room/speaker resonance left enough of a line
# at -18 dB for PTI to keep reporting it as D#6.
TONE_NOTCH_GAIN_DB = -26.0
TONE_NOTCH_GAIN = 10.0 ** (TONE_NOTCH_GAIN_DB / 20.0)

PRESETS: Dict[str, SubtractiveParams] = {
    # 0.4.4 moved the whole table one step milder: the user compared the three
    # settings on his phone and 0.4.3's "weak" caught the playing best, so that is
    # the new default and 0.4.3's "standard" is the new "strong".
    # gentle: only the clearly-stationary part of the floor is removed
    "weak": SubtractiveParams(oversubtraction=0.45, floor_db=-5.0, gain_smoothing=0.65),
    # default: the 0.4.3 "weak" setting
    "standard": SubtractiveParams(oversubtraction=0.70, floor_db=-8.0, gain_smoothing=0.60),
    # 0.4.3's "standard": noticeably stronger, still keeps decays
    "strong": SubtractiveParams(oversubtraction=1.40, floor_db=-14.0, gain_smoothing=0.50),
}

# The high band is taken back out of that gentleness (mirrors
# DenoiseConfig.highBand*): above 1.2 kHz the preset's factor grows to
# HIGH_BAND_BOOST times by 1.8 kHz and the floor drops HIGH_BAND_FLOOR_DB dB.
HIGH_BAND_FROM_HZ = 1200.0
HIGH_BAND_FULL_HZ = 1800.0
HIGH_BAND_BOOST = 2.0
HIGH_BAND_FLOOR_DB = 4.0


@dataclass(frozen=True)
class DenoiseConfig:
    """Settings of the analysis-time denoiser (spec SCR-007)."""

    strength: str = "standard"  # off | weak | standard | strong
    highpass_hz: float = 25.0  # A0 = 27.5 Hz must survive
    highpass_q: float = 0.70710678  # Butterworth
    notch_hz: Optional[float] = None  # None = off, 50.0 / 60.0 = mains hum
    notch_q: float = 30.0
    # Below this frequency the STFT bins are too coarse for the median floor
    # (the partials of a bass note are only a few bins apart), so the
    # subtraction is not applied there — the high-pass and the notch still are.
    min_subtract_hz: float = 150.0
    frame_size: int = 1024
    hop_size: int = 256
    sample_rate: int = 22050

    def __post_init__(self) -> None:
        if self.strength not in STRENGTHS:
            raise ValueError(f"unknown strength: {self.strength!r} (use one of {STRENGTHS})")
        if self.hop_size <= 0 or self.frame_size < self.hop_size:
            raise ValueError("frame_size must be >= hop_size > 0")
        if self.notch_hz is not None and not (20.0 <= self.notch_hz <= 120.0):
            raise ValueError("notch_hz is only meaningful for mains hum (20..120 Hz)")

    @property
    def enabled(self) -> bool:
        return self.strength != "off"

    @property
    def params(self) -> SubtractiveParams:
        return PRESETS.get(self.strength, PRESETS["standard"])

    @property
    def n_bins(self) -> int:
        return self.frame_size // 2 + 1

    def with_strength(self, strength: str) -> "DenoiseConfig":
        return DenoiseConfig(**{**self.__dict__, "strength": strength})


# --------------------------------------------------------------------------- #
# biquads (RBJ cookbook coefficients, direct form II transposed)
# --------------------------------------------------------------------------- #
@dataclass
class Biquad:
    """A single second-order section that can be fed chunk by chunk."""

    b: Tuple[float, float, float]
    a: Tuple[float, float, float]
    z1: float = 0.0
    z2: float = 0.0

    def process(self, x: np.ndarray) -> np.ndarray:
        b0, b1, b2 = self.b
        a1, a2 = self.a[1], self.a[2]
        z1, z2 = self.z1, self.z2
        out = np.empty_like(x)
        for n in range(len(x)):
            xn = float(x[n])
            yn = b0 * xn + z1
            z1 = b1 * xn - a1 * yn + z2
            z2 = b2 * xn - a2 * yn
            out[n] = yn
        self.z1, self.z2 = z1, z2
        return out

    def reset(self) -> None:
        self.z1 = self.z2 = 0.0

    def frequency_response(self, freqs_hz: np.ndarray, sample_rate: float) -> np.ndarray:
        w = 2.0 * math.pi * np.asarray(freqs_hz, dtype=np.float64) / sample_rate
        z = np.exp(-1j * w)
        b0, b1, b2 = self.b
        a0, a1, a2 = self.a
        h = (b0 + b1 * z + b2 * z * z) / (a0 + a1 * z + a2 * z * z)
        return np.abs(h)


def design_highpass(fc_hz: float, sample_rate: float, q: float = 0.70710678) -> Biquad:
    """2nd-order high-pass (RBJ), -3 dB at `fc_hz`, Butterworth shape at q=1/sqrt2."""
    w0 = 2.0 * math.pi * fc_hz / sample_rate
    cos_w0, sin_w0 = math.cos(w0), math.sin(w0)
    alpha = sin_w0 / (2.0 * q)
    a0 = 1.0 + alpha
    b = ((1.0 + cos_w0) / 2.0, -(1.0 + cos_w0), (1.0 + cos_w0) / 2.0)
    a = (a0, -2.0 * cos_w0, 1.0 - alpha)
    return Biquad(tuple(v / a0 for v in b), tuple(v / a0 for v in a))


def design_notch(f0_hz: float, sample_rate: float, q: float = 30.0) -> Biquad:
    """Narrow notch at the mains frequency (Q=30 => ~1.7 Hz wide at 50 Hz)."""
    w0 = 2.0 * math.pi * f0_hz / sample_rate
    cos_w0, sin_w0 = math.cos(w0), math.sin(w0)
    alpha = sin_w0 / (2.0 * q)
    a0 = 1.0 + alpha
    b = (1.0, -2.0 * cos_w0, 1.0)
    a = (a0, -2.0 * cos_w0, 1.0 - alpha)
    return Biquad(tuple(v / a0 for v in b), tuple(v / a0 for v in a))


def highpass_response_db(freqs_hz, sample_rate: float = 22050.0, fc_hz: float = 25.0,
                         q: float = 0.70710678) -> np.ndarray:
    """Frequency response of the high-pass stage (used in tests and the report)."""
    h = design_highpass(fc_hz, sample_rate, q).frequency_response(freqs_hz, sample_rate)
    return 20.0 * np.log10(np.maximum(h, 1e-12))


def detect_mains_hz(
    audio: np.ndarray,
    sample_rate: int = 22050,
    candidates: Tuple[float, ...] = (50.0, 60.0),
    margin_db: float = 10.0,
) -> Optional[float]:
    """Which mains frequency the room is humming at, or None if it is not there.

    Compares the energy at each candidate (±1.5 Hz) with the surrounding floor
    (±6 Hz, excluding the peak).  A notch is only worth its (small) phase cost
    when there really is a hum, so the settings screen can offer "auto".
    """
    x = np.asarray(audio, dtype=np.float64).reshape(-1)
    if len(x) < sample_rate // 2:
        return None
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x))))
    freqs = np.fft.rfftfreq(len(x), 1.0 / sample_rate)
    best: Optional[Tuple[float, float]] = None
    for f0 in candidates:
        peak = np.max(spec[np.abs(freqs - f0) <= 1.5]) if np.any(np.abs(freqs - f0) <= 1.5) else 0.0
        around = spec[(np.abs(freqs - f0) <= 6.0) & (np.abs(freqs - f0) > 1.5)]
        floor = float(np.median(around)) if around.size else 0.0
        if peak <= 0 or floor <= 0:
            continue
        margin = 20.0 * math.log10(peak / floor)
        if best is None or margin > best[1]:
            best = (f0, margin)
    if best is None or best[1] < margin_db:
        return None
    return best[0]


# --------------------------------------------------------------------------- #
# spectral gain
# --------------------------------------------------------------------------- #
def subtract_gains(magnitude: np.ndarray, noise: np.ndarray, params: SubtractiveParams) -> np.ndarray:
    """Per-bin gain for one frame: 1 - alpha*N/M, floored and capped."""
    floor = 10.0 ** (params.floor_db / 20.0)
    gain = 1.0 - params.oversubtraction * noise / (magnitude + 1e-12)
    return np.clip(gain, floor, 1.0)


def subtract_gains_band(
    magnitude: np.ndarray, noise: np.ndarray, oversubtraction: np.ndarray, floor: np.ndarray
) -> np.ndarray:
    """The same subtraction with a per-bin factor and floor (the high-band weight)."""
    gain = 1.0 - oversubtraction * noise / (magnitude + 1e-12)
    return np.clip(gain, floor, 1.0)


def _smooth3(g: np.ndarray) -> np.ndarray:
    """3-bin moving average over frequency (kills isolated musical-noise spikes)."""
    if len(g) < 3:
        return g
    out = g.copy()
    out[1:-1] = (g[:-2] + g[1:-1] + g[2:]) / 3.0
    return out


def _smooth5(x: np.ndarray) -> np.ndarray:
    """5-bin moving average over frequency (smooths a noise-magnitude profile)."""
    if len(x) < 5:
        return x
    kernel = np.array([1.0, 2.0, 3.0, 2.0, 1.0])
    kernel /= kernel.sum()
    return np.convolve(x, kernel, mode="same")


# How the noise floor is found
# ----------------------------
# A single frame cannot tell a hiss from a note by its level, and a floor that
# *follows the level over time* ends up following the piano's own decay (and
# then removes it).  What separates the two is *shape*: a hiss is smooth across
# frequency, while a piano note is a few narrow partials with quiet gaps between
# them.  The floor is therefore the **local median of the magnitude spectrum**
# (a ~670 Hz window, 31 bins): between the partials of a chord it sits on the
# room noise, exactly where the noise actually is, and at a partial it stays far
# below the peak.  That is why a decaying tail or a quiet note is not touched
# until it really sinks into the noise, and it needs no state, so the live and
# the final path compute the same floor.
FLOOR_BINS = 31  # odd; ~670 Hz at 22.05 kHz / 1024-sample frames
FADE_IN_MS = 5.0  # first full-overlap window edge, masked at the very start


def spectral_floor(mag: np.ndarray, size: int = FLOOR_BINS, bias: float = 1.0) -> np.ndarray:
    """Local median of one frame's magnitude spectrum (the level between partials)."""
    size = max(3, int(size) | 1)
    pad = size // 2
    padded = np.pad(np.asarray(mag, dtype=np.float64), (pad, pad), mode="edge")
    windows = np.lib.stride_tricks.sliding_window_view(padded, size)
    return np.median(windows, axis=-1) * bias


def fade_in(n_samples: int, fade_samples: int) -> np.ndarray:
    """Raised-cosine fade over the first `fade_samples` (removes the STFT edge)."""
    out = np.ones(n_samples, dtype=np.float64)
    f = min(int(fade_samples), n_samples)
    if f > 1:
        out[:f] = 0.5 - 0.5 * np.cos(np.linspace(0.0, math.pi, f))
    return out


class FloorTracker:
    """Stationary floor per bin: minimum of the local median over ~1 s.

    The frequency median finds the level *between* the partials; taking the
    minimum of that over the last second keeps only the part of it that stays
    there when the piano is quiet — a stationary hiss.  A clean take therefore
    has a floor of ~0 and is left alone, while a room with a hiss gets the
    hiss subtracted even during a loud passage.
    """

    def __init__(self, n_bins: int, window_frames: int = 86, refresh: int = 8, bias: float = 1.6) -> None:
        self.n_bins = n_bins
        self.window_frames = window_frames
        self.refresh = refresh
        self.bias = bias
        self._buf = np.zeros((window_frames, n_bins), dtype=np.float32)
        self._idx = 0
        self._filled = 0
        self._count = 0
        self.estimate = np.zeros(n_bins)

    def push(self, mag: np.ndarray) -> np.ndarray:
        med = spectral_floor(mag).astype(np.float32)
        self._buf[self._idx] = med
        self._idx = (self._idx + 1) % self.window_frames
        self._filled = min(self._filled + 1, self.window_frames)
        self._count += 1
        if self._count == 1 or self._count % self.refresh == 0:
            self.estimate = np.min(self._buf[: self._filled], axis=0) * self.bias
        return self.estimate

    def reset(self) -> None:
        self._buf[:] = 0.0
        self._idx = 0
        self._filled = 0
        self._count = 0
        self.estimate = np.zeros(self.n_bins)


def track_noise_floors(magnitude: np.ndarray, window_frames: int = 86) -> np.ndarray:
    """Per-frame floor for a whole recording, (n_frames, n_bins) -> same shape."""
    if magnitude.shape[0] == 0:
        return np.zeros_like(magnitude)
    tracker = FloorTracker(magnitude.shape[1], window_frames=window_frames)
    out = np.empty_like(magnitude, dtype=np.float64)
    for i in range(magnitude.shape[0]):
        out[i] = tracker.push(magnitude[i])
    return out


def estimate_noise_profile(magnitude: np.ndarray) -> np.ndarray:
    """A single representative floor for a recording (reporting / diagnostics)."""
    if magnitude.shape[0] == 0:
        return np.zeros(magnitude.shape[1])
    return np.median(track_noise_floors(magnitude), axis=0)


def fade_in(n_samples: int, fade_samples: int) -> np.ndarray:
    """Raised-cosine fade over the first `fade_samples` (removes the STFT edge)."""
    out = np.ones(n_samples, dtype=np.float64)
    f = min(int(fade_samples), n_samples)
    if f > 1:
        out[:f] = 0.5 - 0.5 * np.cos(np.linspace(0.0, math.pi, f))
    return out


def track_noise_floors(magnitude: np.ndarray, size: int = FLOOR_BINS) -> np.ndarray:
    """Per-frame floor for a whole recording, (n_frames, n_bins) -> same shape."""
    return np.stack([spectral_floor(row, size) for row in magnitude], axis=0) if magnitude.size else np.zeros_like(magnitude)


def estimate_noise_profile(magnitude: np.ndarray, size: int = FLOOR_BINS) -> np.ndarray:
    """A single representative floor for a recording (reporting / diagnostics)."""
    if magnitude.shape[0] == 0:
        return np.zeros(magnitude.shape[1])
    return np.median(track_noise_floors(magnitude, size), axis=0)


# --------------------------------------------------------------------------- #
# offline denoiser
# --------------------------------------------------------------------------- #
@dataclass
class DenoiseStats:
    compute_ms: float = 0.0
    audio_s: float = 0.0
    rtf: float = 0.0
    n_frames: int = 0
    noise_floor_db: float = 0.0
    mean_gain_db: float = 0.0
    max_reduction_db: float = 0.0
    input_rms_db: float = 0.0
    output_rms_db: float = 0.0


class Denoiser:
    """Whole-file denoiser (final analysis, and the reference for the tests)."""

    def __init__(self, config: Optional[DenoiseConfig] = None) -> None:
        self.config = config or DenoiseConfig()
        n = self.config.frame_size
        self.window = np.hanning(n + 1)[:-1].astype(np.float64)  # periodic Hann
        self._g_prev = np.ones(self.config.n_bins, dtype=np.float64)
        self._subtract_mask = self._mask()

    def _mask(self) -> np.ndarray:
        freqs = np.fft.rfftfreq(self.config.frame_size, 1.0 / self.config.sample_rate)
        return freqs >= self.config.min_subtract_hz

    # -- helpers -------------------------------------------------------------
    def _front_filters(self) -> List[Biquad]:
        cfg = self.config
        out = [design_highpass(cfg.highpass_hz, cfg.sample_rate, cfg.highpass_q)]
        if cfg.notch_hz is not None:
            out.append(design_notch(cfg.notch_hz, cfg.sample_rate, cfg.notch_q))
        return out

    def _frames(self, x: np.ndarray) -> np.ndarray:
        n, hop = self.config.frame_size, self.config.hop_size
        if len(x) < n:
            x = np.pad(x, (0, n - len(x)))
        n_frames = 1 + (len(x) - n) // hop
        idx = np.arange(n)[None, :] + hop * np.arange(n_frames)[:, None]
        return x[idx] * self.window[None, :]

    def _overlap_add(self, frames: np.ndarray, length: int) -> np.ndarray:
        n, hop = self.config.frame_size, self.config.hop_size
        out = np.zeros(length + n, dtype=np.float64)
        env = np.zeros(length + n, dtype=np.float64)
        w2 = self.window**2
        for k in range(frames.shape[0]):
            s = k * hop
            out[s : s + n] += frames[k] * self.window
            env[s : s + n] += w2
        env = np.maximum(env, 1e-3 * float(np.max(env)) if np.max(env) > 0 else 1.0)
        out = out / env
        return out[:length].astype(np.float32)

    # -- public -------------------------------------------------------------
    def process(self, audio: np.ndarray, tone_mask: Optional[np.ndarray] = None) -> np.ndarray:
        return self.process_with_stats(audio, tone_mask=tone_mask)[0]

    def process_with_stats(
        self, audio: np.ndarray, tone_mask: Optional[np.ndarray] = None
    ) -> Tuple[np.ndarray, DenoiseStats]:
        """`tone_mask`: bins of a stationary tone to hold down (see the Kotlin
        Denoiser.toneMask / ToneNoiseDetector).  A steady high whistle is not
        "between the partials" so the statistical floor keeps it; this mask takes
        those few bins down by TONE_NOTCH_GAIN_DB regardless."""
        import time

        cfg = self.config
        x = np.asarray(audio, dtype=np.float32).reshape(-1)
        if not cfg.enabled or len(x) == 0:
            return x.copy(), DenoiseStats(audio_s=len(x) / cfg.sample_rate)

        t0 = time.perf_counter()
        y = x.astype(np.float64)
        for f in self._front_filters():
            y = f.process(y)

        frames = self._frames(y)
        spec = np.fft.rfft(frames, axis=1)
        mag = np.abs(spec)
        noise = track_noise_floors(mag)
        params = cfg.params
        freqs = np.fft.rfftfreq(cfg.frame_size, 1.0 / cfg.sample_rate)
        ramp = np.clip(
            (freqs - HIGH_BAND_FROM_HZ) / (HIGH_BAND_FULL_HZ - HIGH_BAND_FROM_HZ), 0.0, 1.0
        )
        alpha = params.oversubtraction * (1.0 + (HIGH_BAND_BOOST - 1.0) * ramp)
        floor = 10.0 ** ((params.floor_db - HIGH_BAND_FLOOR_DB * ramp) / 20.0)
        gains = np.empty_like(mag)
        g = self._g_prev
        for i in range(mag.shape[0]):
            g = params.gain_smoothing * g + (1.0 - params.gain_smoothing) * subtract_gains_band(
                mag[i], noise[i], alpha, floor
            )
            gains[i] = np.where(self._subtract_mask, _smooth3(np.clip(g, 0.0, 1.0)), 1.0)
            if tone_mask is not None:
                gains[i] = np.where(tone_mask, np.minimum(gains[i], TONE_NOTCH_GAIN), gains[i])
        cleaned = np.fft.irfft(spec * gains, n=cfg.frame_size, axis=1)
        out = self._overlap_add(cleaned, len(x))
        # the first few ms of a windowed STFT have no full overlap yet; fade them
        fade = int(round(FADE_IN_MS * cfg.sample_rate / 1000.0))
        out = out * fade_in(len(out), fade).astype(np.float32)

        # no level re-normalisation: what is left after the subtraction is the
        # take with the room noise removed, and the live path (which cannot know
        # the peak) behaves the same way

        dt_ms = (time.perf_counter() - t0) * 1000.0
        gain_db = 20.0 * np.log10(np.maximum(gains, 1e-6))
        floor_db = 20.0 * np.log10(max(float(np.mean(noise)), 1e-12) + 1e-12)
        stats = DenoiseStats(
            compute_ms=round(dt_ms, 1),
            audio_s=round(len(x) / cfg.sample_rate, 2),
            rtf=round(dt_ms / (len(x) / cfg.sample_rate * 1000.0), 4),
            n_frames=int(mag.shape[0]),
            noise_floor_db=round(float(floor_db), 1),
            mean_gain_db=round(float(np.mean(gain_db)), 2),
            max_reduction_db=round(float(np.min(gain_db)), 2),
            input_rms_db=round(20.0 * math.log10(float(np.sqrt(np.mean(x**2))) + 1e-12), 1),
            output_rms_db=round(20.0 * math.log10(float(np.sqrt(np.mean(out**2))) + 1e-12), 1),
        )
        return out, stats


# --------------------------------------------------------------------------- #
# streaming denoiser (live path)
# --------------------------------------------------------------------------- #
class StreamingDenoiser:
    """Chunk-in / chunk-out denoiser for the live transcription path.

    `process_chunk` returns only the samples that can no longer change, so a
    call may return slightly less audio than it was given (never more).  Sample
    indices keep the input numbering — output sample *n* is input sample *n* —
    but the newest audio is held back until the windows covering it have been
    seen, so the wall-clock latency of the stage is `delay_ms` (~58 ms at
    22.05 kHz: one 1024-sample frame plus one 256-sample hop).  The caller adds
    that delay when it reports the display latency; onset times themselves need
    no correction.
    """

    def __init__(self, config: Optional[DenoiseConfig] = None) -> None:
        self.config = config or DenoiseConfig()
        self.window = np.hanning(self.config.frame_size + 1)[:-1].astype(np.float64)
        freqs = np.fft.rfftfreq(self.config.frame_size, 1.0 / self.config.sample_rate)
        self._subtract_mask = freqs >= self.config.min_subtract_hz
        # steady-state sum of squared windows under overlap-add (1.5 for a
        # periodic Hann at 75 % overlap); a small fraction of it is the floor
        # used to normalise the very first samples, where the window is ~0
        self._env_floor = 1e-3 * float(np.sum(self.window**2) / self.config.hop_size * 1.5 * 0 + 1.5)
        self.reset()

    # -- setup ---------------------------------------------------------------
    def _make_front_filters(self) -> List[Biquad]:
        cfg = self.config
        out = [design_highpass(cfg.highpass_hz, cfg.sample_rate, cfg.highpass_q)]
        if cfg.notch_hz is not None:
            out.append(design_notch(cfg.notch_hz, cfg.sample_rate, cfg.notch_q))
        return out

    @property
    def delay_samples(self) -> int:
        return (self.config.frame_size + self.config.hop_size) if self.config.enabled else 0

    @property
    def delay_ms(self) -> float:
        return 1000.0 * self.delay_samples / self.config.sample_rate

    def reset(self) -> None:
        cfg = self.config
        self._front = self._make_front_filters()
        self._pending = np.zeros(0, dtype=np.float64)  # input not yet framed
        self._ola = np.zeros(cfg.frame_size * 2, dtype=np.float64)
        self._env = np.zeros(cfg.frame_size * 2, dtype=np.float64)
        self._base = 0  # absolute index of self._ola[0]
        self._frame_start = 0  # absolute sample index of the next frame
        self._emitted = 0  # absolute index of the next sample to return
        self._g_prev = np.ones(cfg.n_bins, dtype=np.float64)
        self._frames_seen = 0
        self._out: List[float] = []
        self._fade_len = int(round(FADE_IN_MS * cfg.sample_rate / 1000.0))
        self._fade_pos = 0
        self._floor = FloorTracker(cfg.n_bins)
        self.preprocess_ms: List[float] = []

    # -- public --------------------------------------------------------------
    def process_chunk(self, chunk: np.ndarray) -> np.ndarray:
        import time

        cfg = self.config
        x = np.asarray(chunk, dtype=np.float32).reshape(-1)
        if not cfg.enabled or len(x) == 0:
            return x.copy()

        t0 = time.perf_counter()
        y = x.astype(np.float64)
        for f in self._front:
            y = f.process(y)
        self._pending = np.concatenate([self._pending, y])

        # process every complete frame that starts inside the pending buffer
        while len(self._pending) >= cfg.frame_size:
            frame = self._pending[: cfg.frame_size]
            self._pending = self._pending[cfg.hop_size :]
            self._process_frame(frame)
            self._frame_start += cfg.hop_size
            # samples below the newest window start are covered by every window
            # that can still change them, so they are final
            self._finalize(self._frame_start)
        self._trim()
        out = self._take()
        self.preprocess_ms.append((time.perf_counter() - t0) * 1000.0)
        return out

    def available(self) -> int:
        """Samples returned so far (the caller's audio clock for this stage)."""
        return self._emitted

    # -- internals -----------------------------------------------------------
    def _process_frame(self, frame: np.ndarray) -> None:
        cfg = self.config
        params = cfg.params
        spec = np.fft.rfft(frame * self.window)
        mag = np.abs(spec)
        self._frames_seen += 1
        noise = self._floor.push(mag)
        g = params.gain_smoothing * self._g_prev + (1.0 - params.gain_smoothing) * subtract_gains(
            mag, noise, params
        )
        self._g_prev = g
        gain = np.where(self._subtract_mask, _smooth3(np.clip(g, 0.0, 1.0)), 1.0)
        cleaned = np.fft.irfft(spec * gain, n=cfg.frame_size)
        s = self._frame_start - self._base
        need = s + cfg.frame_size - len(self._ola)
        if need > 0:
            self._ola = np.concatenate([self._ola, np.zeros(need)])
            self._env = np.concatenate([self._env, np.zeros(need)])
        self._ola[s : s + cfg.frame_size] += cleaned * self.window
        self._env[s : s + cfg.frame_size] += self.window**2

    def _trim(self) -> None:
        """Drop processed history so a long session does not grow the buffers."""
        keep_back = 4 * self.config.frame_size
        if self._emitted - self._base < keep_back:
            return
        cut = (self._emitted - self.config.frame_size) - self._base
        self._ola = self._ola[cut:]
        self._env = self._env[cut:]
        self._base += cut

    def _finalize(self, upto: int) -> None:
        self._read(upto)

    def _read(self, upto: int) -> None:
        start = self._emitted - self._base
        end = upto - self._base
        if end <= start:
            return
        env = np.maximum(self._env[start:end], self._env_floor)
        self._out.extend((self._ola[start:end] / env).tolist())
        self._ola[start:end] = 0.0
        self._env[start:end] = 0.0
        self._emitted = upto

    def _take(self) -> np.ndarray:
        out = np.asarray(self._out, dtype=np.float32)
        self._out = []
        if len(out) and self._fade_pos < self._fade_len:
            fade = fade_in(len(out), self._fade_len - self._fade_pos)
            out = (out * fade.astype(np.float32)).astype(np.float32)
            self._fade_pos += len(out)
        return out


# --------------------------------------------------------------------------- #
# convenience
# --------------------------------------------------------------------------- #
def denoise_signal(
    audio: np.ndarray,
    strength: str = "standard",
    sample_rate: int = 22050,
    notch_hz: Optional[float] = None,
) -> np.ndarray:
    """One-liner used by the measurement driver and by callers that do not need stats."""
    cfg = DenoiseConfig(strength=strength, sample_rate=sample_rate, notch_hz=notch_hz)
    return Denoiser(cfg).process(audio)


def silence_floor_db(audio: np.ndarray) -> float:
    """RMS level (dBFS) of the quietest 10 % of 46 ms frames — the room noise."""
    cfg = DenoiseConfig()
    n = cfg.frame_size
    x = np.asarray(audio, dtype=np.float64)
    if len(x) < n:
        x = np.pad(x, (0, n - len(x)))
    n_frames = 1 + (len(x) - n) // cfg.hop_size
    powers = np.array([np.mean(x[k * cfg.hop_size : k * cfg.hop_size + n] ** 2) for k in range(n_frames)])
    quiet = powers[powers.argsort()[: max(1, len(powers) // 10)]]
    return 20.0 * math.log10(float(np.sqrt(np.mean(quiet))) + 1e-12)
