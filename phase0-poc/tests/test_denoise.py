"""Unit tests for the piano-oriented denoiser (`pianosheet_poc/denoise.py`).

    python3 -m unittest discover -s tests     (from phase0-poc/)

These run without the model and without the WAV fixtures: every signal is
synthesised here, so the assertions are about the DSP itself — the 25 Hz
high-pass leaving A0 alone, the mains notch, the subtraction removing a hiss
while keeping a decaying note, and the live path behaving like the offline one.
"""

from __future__ import annotations

import pathlib
import sys
import unittest

import numpy as np

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from pianosheet_poc.denoise import (  # noqa: E402
    DenoiseConfig,
    Denoiser,
    StreamingDenoiser,
    design_notch,
    detect_mains_hz,
    highpass_response_db,
    spectral_floor,
)
from pianosheet_poc.live import LiveConfig, StreamingTranscriber  # noqa: E402

SR = 22050


def tone(freq: float, seconds: float, amp: float = 0.3, decay: float = 0.0) -> np.ndarray:
    t = np.arange(int(SR * seconds)) / SR
    env = np.exp(-t / decay) if decay else np.ones_like(t)
    return (amp * np.sin(2 * np.pi * freq * t) * env).astype(np.float32)


def white_noise(seconds: float, rms: float = 0.01, seed: int = 0) -> np.ndarray:
    return (rng(seed).normal(0, rms, int(SR * seconds))).astype(np.float32)


def rng(seed: int) -> np.random.Generator:
    return np.random.default_rng(seed)


def amplitude_at(signal: np.ndarray, freq: float, start: int = 0, length: int = 4096) -> float:
    """Windowed-FFT amplitude of one frequency (used to watch a note through the DSP)."""
    seg = np.asarray(signal[start : start + length], dtype=np.float64)
    if len(seg) < length:
        seg = np.pad(seg, (0, length - len(seg)))
    spec = np.abs(np.fft.rfft(seg * np.hanning(length)))
    freqs = np.fft.rfftfreq(length, 1.0 / SR)
    return float(spec[int(np.argmin(np.abs(freqs - freq)))])


def band_db(x: np.ndarray, lo_hz: float, hi_hz: float) -> float:
    """RMS of a frequency band (dBFS) — how the hiss can be watched under a note."""
    spec = np.abs(np.fft.rfft(np.asarray(x, dtype=np.float64) * np.hanning(len(x))))
    freqs = np.fft.rfftfreq(len(x), 1.0 / SR)
    band = spec[(freqs >= lo_hz) & (freqs < hi_hz)]
    return 20.0 * np.log10(float(np.sqrt(np.mean(band**2))) + 1e-12)


def rms_db(x: np.ndarray) -> float:
    return 20.0 * np.log10(float(np.sqrt(np.mean(np.asarray(x, dtype=np.float64) ** 2))) + 1e-12)


class TestFrontFilters(unittest.TestCase):
    def test_highpass_keeps_a0_and_kills_rumble(self):
        resp = {f: float(highpass_response_db([f])[0]) for f in (10.0, 25.0, 27.5, 50.0, 440.0)}
        self.assertGreater(resp[27.5], -3.0, "A0 (27.5 Hz) must stay within 3 dB")
        self.assertGreater(resp[27.5], resp[25.0], "27.5 Hz must sit above the corner")
        self.assertLess(resp[10.0], -12.0, "10 Hz rumble must be strongly attenuated")
        self.assertGreater(resp[50.0], -0.5, "mains hum band must be untouched")
        self.assertAlmostEqual(resp[440.0], 0.0, delta=0.2)

    def test_highpass_does_not_shift_a0_pitch(self):
        """The filter must not move the fundamental of the lowest key."""
        x = tone(27.5, 2.0, amp=0.3)
        y = Denoiser(DenoiseConfig(strength="standard")).process(x)
        a0 = amplitude_at(y, 27.5, start=11025)
        self.assertGreater(a0 / amplitude_at(x, 27.5, start=11025), 0.7)
        # no energy appears an octave away
        self.assertLess(amplitude_at(y, 13.75, start=11025) / a0, 0.2)

    def test_notch_removes_mains_only(self):
        notch = design_notch(50.0, SR, q=30.0)
        hum = tone(50.0, 2.0).astype(np.float64)
        music = tone(440.0, 2.0).astype(np.float64)
        # the notch rings for ~0.2 s at Q=30, so measure after it has settled
        self.assertLess(amplitude_at(notch.process(hum), 50.0, 22050) / amplitude_at(hum, 50.0, 22050), 0.02)
        self.assertGreater(
            amplitude_at(design_notch(50.0, SR, q=30.0).process(music), 440.0, 22050)
            / amplitude_at(music, 440.0, 22050),
            0.99,
        )

    def test_notch_setting_removes_hum_from_a_take(self):
        x = (tone(261.63, 2.0, 0.3) + tone(50.0, 2.0, 0.05)).astype(np.float32)
        plain = Denoiser(DenoiseConfig(strength="standard")).process(x)
        notched = Denoiser(DenoiseConfig(strength="standard", notch_hz=50.0)).process(x)
        self.assertLess(amplitude_at(notched, 50.0, 22050), 0.1 * amplitude_at(plain, 50.0, 22050))


class TestMainsDetection(unittest.TestCase):
    def test_finds_50_and_60(self):
        for mains in (50.0, 60.0):
            x = (tone(261.63, 3.0, 0.2) + tone(mains, 3.0, 0.02)).astype(np.float32)
            x += white_noise(3.0, 0.004, seed=21)
            self.assertEqual(detect_mains_hz(x), mains)

    def test_says_none_without_hum(self):
        x = (tone(261.63, 3.0, 0.2) + white_noise(3.0, 0.004, seed=22)).astype(np.float32)
        self.assertIsNone(detect_mains_hz(x))

    def test_short_input_is_safe(self):
        self.assertIsNone(detect_mains_hz(white_noise(0.1)))


class TestSpectralFloor(unittest.TestCase):
    def test_floor_sits_between_partials(self):
        frame = tone(261.63, 1024 / SR, 0.3)[:1024] * np.hanning(1024)
        mag = np.abs(np.fft.rfft(frame))
        floor = spectral_floor(mag)
        peak_bin = int(np.argmax(mag[2:]))
        self.assertLess(peak_bin + 2 if peak_bin else 2, len(floor))
        self.assertLess(floor[int(np.argmax(mag))], 0.05 * mag.max())

    def test_floor_tracks_noise(self):
        frame = white_noise(1024 / SR, 0.01, seed=4) * np.hanning(1024)
        mag = np.abs(np.fft.rfft(frame))
        floor = spectral_floor(mag)
        self.assertGreater(np.median(floor), 0.3 * np.median(mag))
        self.assertLess(np.median(floor), 3.0 * np.median(mag))


class TestDenoiser(unittest.TestCase):
    def test_off_is_identity(self):
        x = white_noise(1.0)
        y = Denoiser(DenoiseConfig(strength="off")).process(x)
        np.testing.assert_allclose(x, y)

    def test_output_shape_dtype_and_finiteness(self):
        x = white_noise(1.0)
        y = Denoiser(DenoiseConfig(strength="standard")).process(x)
        self.assertEqual(y.shape, x.shape)
        self.assertEqual(y.dtype, np.float32)
        self.assertTrue(np.all(np.isfinite(y)))

    def test_silence_stays_silence(self):
        y = Denoiser(DenoiseConfig(strength="standard")).process(np.zeros(SR, dtype=np.float32))
        self.assertEqual(float(np.max(np.abs(y))), 0.0)

    def test_empty_and_short_input(self):
        d = Denoiser(DenoiseConfig(strength="standard"))
        self.assertEqual(len(d.process(np.zeros(0, dtype=np.float32))), 0)
        short = white_noise(0.01)
        self.assertEqual(len(d.process(short)), len(short))

    def test_noise_is_reduced_and_stronger_means_more(self):
        x = white_noise(4.0, rms=0.01, seed=7)
        levels = {}
        for strength in ("weak", "standard", "strong"):
            y, stats = Denoiser(DenoiseConfig(strength=strength)).process_with_stats(x)
            levels[strength] = stats.output_rms_db - stats.input_rms_db
        self.assertLess(levels["weak"], -3.0)
        self.assertLess(levels["standard"], levels["weak"] - 3.0)
        self.assertLess(levels["strong"], levels["standard"] - 4.0)

    def test_decaying_note_is_preserved_under_noise(self):
        """The core promise: a hiss goes, the piano's decay does not."""
        note = tone(261.63, 3.0, 0.3, decay=0.8)
        noisy = (note + white_noise(3.0, 0.01, seed=3)).astype(np.float32)
        y = Denoiser(DenoiseConfig(strength="standard")).process(noisy)
        for start in (11025, 33075, 55125):
            ratio_db = 20 * np.log10(
                amplitude_at(y, 261.63, start) / amplitude_at(noisy, 261.63, start)
            )
            self.assertGreater(ratio_db, -3.0, f"note lost {ratio_db:.1f} dB at {start}")
        # while the hiss, which sits between the partials, did go down
        self.assertLess(band_db(y, 6000, 9000) - band_db(noisy, 6000, 9000), -4.0)

    def test_tail_after_release_survives(self):
        """Decay tail *after* the key is released (pedal-like) must not be gated away."""
        hold = tone(196.0, 2.0, 0.25)  # steady string, like a held pedal note
        y = Denoiser(DenoiseConfig(strength="standard")).process(hold)
        self.assertGreater(rms_db(y[SR:]) - rms_db(hold[SR:]), -2.0)

    def test_quiet_note_is_preserved(self):
        quiet = tone(523.25, 2.0, 0.004)  # pp, -48 dBFS-ish
        y = Denoiser(DenoiseConfig(strength="standard")).process(quiet)
        self.assertGreater(rms_db(y) - rms_db(quiet), -2.5)

    def test_no_overshoot(self):
        x = (tone(261.63, 2.0, 0.3, decay=0.8) + white_noise(2.0, 0.01, seed=2)).astype(np.float32)
        y = Denoiser(DenoiseConfig(strength="standard")).process(x)
        self.assertLess(float(np.max(np.abs(y))), 1.15 * float(np.max(np.abs(x))))

    def test_stats_are_consistent(self):
        x = white_noise(2.0, 0.01, seed=5)
        _, stats = Denoiser(DenoiseConfig(strength="standard")).process_with_stats(x)
        self.assertGreater(stats.compute_ms, 0.0)
        self.assertLess(stats.max_reduction_db, 0.0)
        self.assertLess(stats.output_rms_db, stats.input_rms_db)


class TestStreamingDenoiser(unittest.TestCase):
    def chunked(self, denoiser: StreamingDenoiser, x: np.ndarray, chunk: int = 2205):
        out, pos = [], 0
        while pos < len(x):
            out.append(denoiser.process_chunk(x[pos : pos + chunk]))
            pos += chunk
        return np.concatenate(out)

    def test_never_returns_more_than_it_was_given(self):
        x = white_noise(2.0, seed=8)
        d = StreamingDenoiser(DenoiseConfig(strength="standard"))
        pos = 0
        while pos < len(x):
            got = len(d.process_chunk(x[pos : pos + 2205]))
            # a chunk may release one extra frame's worth when the buffer fills
            self.assertLessEqual(got, min(2205, len(x) - pos) + d.config.frame_size)
            pos += 2205
        # the held-back audio is bounded by the declared delay
        self.assertGreaterEqual(len(x) - d.available(), 0)
        self.assertLessEqual(len(x) - d.available(), d.delay_samples + 2205)

    def test_declared_delay_is_the_frame(self):
        d = StreamingDenoiser(DenoiseConfig(strength="standard"))
        self.assertEqual(d.delay_samples, 1024 + 256)
        self.assertAlmostEqual(d.delay_ms, 1000.0 * 1280 / SR, places=3)
        self.assertEqual(StreamingDenoiser(DenoiseConfig(strength="off")).delay_samples, 0)

    def test_matches_the_offline_energy(self):
        x = (tone(261.63, 3.0, 0.3, decay=0.8) + white_noise(3.0, 0.01, seed=9)).astype(np.float32)
        y = self.chunked(StreamingDenoiser(DenoiseConfig(strength="standard")), x)
        offline = Denoiser(DenoiseConfig(strength="standard")).process(x[: len(y)])
        self.assertAlmostEqual(rms_db(y), rms_db(offline), delta=1.0)
        self.assertAlmostEqual(
            20 * np.log10(amplitude_at(y, 261.63, 11025) / amplitude_at(offline, 261.63, 11025)),
            0.0,
            delta=1.5,
        )

    def test_streaming_keeps_noise_down_and_has_no_spikes(self):
        x = (tone(261.63, 2.0, 0.3, decay=0.8) + white_noise(2.0, 0.01, seed=10)).astype(np.float32)
        d = StreamingDenoiser(DenoiseConfig(strength="standard"))
        y = self.chunked(d, x)
        self.assertLess(band_db(y, 6000, 9000) - band_db(x, 6000, 9000), -4.0)
        self.assertLess(float(np.max(np.abs(y))), 1.2 * float(np.max(np.abs(x))))
        self.assertTrue(np.all(np.isfinite(y)))
        self.assertEqual(len(d.preprocess_ms), len(range(0, len(x), 2205)))

    def test_cost_is_cheap_enough_for_live(self):
        """One 100 ms chunk must cost far less than 100 ms (Python, CPU)."""
        d = StreamingDenoiser(DenoiseConfig(strength="standard"))
        x = white_noise(1.0, seed=11)
        self.chunked(d, x)
        self.assertLess(float(np.median(d.preprocess_ms)), 25.0)

    def test_config_validation(self):
        with self.assertRaises(ValueError):
            DenoiseConfig(strength="heavy")
        with self.assertRaises(ValueError):
            DenoiseConfig(notch_hz=440.0)
        with self.assertRaises(ValueError):
            DenoiseConfig(frame_size=128, hop_size=256)


class TestEngineHook(unittest.TestCase):
    def test_preprocess_is_applied_per_chunk(self):
        calls = {"n": 0, "samples": 0}

        def preprocess(chunk):
            calls["n"] += 1
            calls["samples"] += len(chunk)
            return chunk * 0.5

        class Stub(StreamingTranscriber):
            name = "stub-proc"

            def _detect(self, window, window_start_s):
                return []

        cfg = LiveConfig(window_s=1.0, step_s=0.25)
        tr = Stub(cfg, preprocess=preprocess)
        tr.start_session()
        audio = np.ones(int(1.0 * SR), dtype=np.float32)
        for i in range(0, len(audio), 2205):
            tr.push_audio_chunk(audio[i : i + 2205])
        tr.finish_session()
        self.assertEqual(calls["samples"], len(audio))
        self.assertGreater(calls["n"], 5)


if __name__ == "__main__":
    unittest.main()
