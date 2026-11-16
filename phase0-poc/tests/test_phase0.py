"""Smoke tests for the Phase 0 PoC.

    python3 -m unittest discover -s tests      (from phase0-poc/)

These run without the model file except for `test_model_window_if_present`.
"""

from __future__ import annotations

import pathlib
import sys
import unittest

import numpy as np

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from pianosheet_poc.live import LiveConfig, StreamingTranscriber  # noqa: E402
from pianosheet_poc.metrics import match_notes  # noqa: E402
from pianosheet_poc.notes import NoteEvent, dedupe_events  # noqa: E402

MODEL = HERE.parent / "models" / "nmp.onnx"


class StubDetector(StreamingTranscriber):
    """Emits one detection for every 0.5 s of audio, with controllable strength."""

    name = "stub"

    def __init__(self, config: LiveConfig, strength: float = 0.9) -> None:
        super().__init__(config)
        self.strength = strength
        self.calls = 0

    def _detect(self, window, window_start_s):
        self.calls += 1
        # one strike per step, just before the end of the window (like a real
        # detector that sees the newest audio)
        t = window_start_s + len(window) / 22050.0 - 0.10
        return [
            {
                "start_s": t,
                "end_s": t + 0.10,
                "pitch_midi": 60,
                "confidence": 0.9,
                "onset_strength": self.strength,
            }
        ]


class TestDedupe(unittest.TestCase):
    def test_same_pitch_near_onset_is_merged(self):
        a = NoteEvent(60, 1000.0, 200.0, confidence=0.5)
        b = NoteEvent(60, 1050.0, 300.0, confidence=0.9)
        out = dedupe_events([a, b], onset_tolerance_ms=120.0)
        self.assertEqual(len(out), 1)
        self.assertAlmostEqual(out[0].confidence, 0.9)
        self.assertAlmostEqual(out[0].duration_ms, 300.0)

    def test_different_pitch_is_not_merged(self):
        a = NoteEvent(60, 1000.0)
        b = NoteEvent(64, 1000.0)
        self.assertEqual(len(dedupe_events([a, b])), 2)

    def test_repeat_beyond_tolerance_is_kept(self):
        a = NoteEvent(60, 1000.0)
        b = NoteEvent(60, 1300.0)
        self.assertEqual(len(dedupe_events([a, b])), 2)


class TestMetrics(unittest.TestCase):
    def test_exact_match(self):
        refs = [{"start_s": 1.0, "end_s": 1.5, "pitch_midi": 60, "confidence": 1.0}]
        ests = [{"start_s": 1.02, "end_s": 1.4, "pitch_midi": 60, "confidence": 0.8}]
        s = match_notes(refs, ests, onset_tol_s=0.05)
        self.assertEqual((s["tp"], s["fp"], s["fn"]), (1, 0, 0))
        self.assertAlmostEqual(s["onset_mae_ms"], 20.0, places=3)

    def test_wrong_pitch_is_miss(self):
        refs = [{"start_s": 1.0, "end_s": 1.5, "pitch_midi": 60, "confidence": 1.0}]
        ests = [{"start_s": 1.0, "end_s": 1.4, "pitch_midi": 61, "confidence": 0.8}]
        s = match_notes(refs, ests, onset_tol_s=0.05)
        self.assertEqual((s["tp"], s["fp"], s["fn"]), (0, 1, 1))

    def test_onset_outside_tolerance_is_miss(self):
        refs = [{"start_s": 1.0, "end_s": 1.5, "pitch_midi": 60, "confidence": 1.0}]
        ests = [{"start_s": 1.2, "end_s": 1.4, "pitch_midi": 60, "confidence": 0.8}]
        s = match_notes(refs, ests, onset_tol_s=0.05)
        self.assertEqual(s["tp"], 0)


class TestStreamingEngine(unittest.TestCase):
    def test_commits_only_new_audio(self):
        cfg = LiveConfig(window_s=2.0, step_s=0.25, guard_ms=0.0, onset_strength_min=0.0)
        tr = StubDetector(cfg)
        tr.start_session()
        audio = np.zeros(int(3.0 * 22050), dtype=np.float32)
        chunk = int(0.1 * 22050)
        emitted = []
        for i in range(0, len(audio), chunk):
            emitted.extend(tr.push_audio_chunk(audio[i : i + chunk]))
        # the stub reports the window start every step; each onset must be
        # committed once, not once per overlapping window
        onsets = [round(e.onset_ms) for e in tr.events]
        self.assertEqual(len(onsets), len(set(onsets)))
        self.assertGreater(len(tr.events), 5)
        # one strike per step: never more events than steps
        self.assertLessEqual(len(tr.events), len(tr.stats))

    def test_reported_old_onset_is_dropped(self):
        """A detector that keeps reporting a note in the past must not re-add it."""

        class OldOnsetStub(StreamingTranscriber):
            name = "old"

            def _detect(self, window, window_start_s):
                return [
                    {
                        "start_s": 0.0,
                        "end_s": 0.2,
                        "pitch_midi": 60,
                        "confidence": 0.9,
                        "onset_strength": 0.9,
                    }
                ]

        cfg = LiveConfig(window_s=1.0, step_s=0.25, guard_ms=0.0)
        tr = OldOnsetStub(cfg)
        tr.start_session()
        audio = np.zeros(int(2.0 * 22050), dtype=np.float32)
        chunk = int(0.1 * 22050)
        for i in range(0, len(audio), chunk):
            tr.push_audio_chunk(audio[i : i + chunk])
        self.assertEqual(len(tr.events), 1)
        self.assertGreater(tr.stale_filtered, 0)

    def test_weak_onset_is_held_back(self):
        cfg = LiveConfig(window_s=2.0, step_s=0.25, guard_ms=0.0, onset_strength_min=0.3)
        tr = StubDetector(cfg, strength=0.05)
        tr.start_session()
        audio = np.zeros(int(1.0 * 22050), dtype=np.float32)
        chunk = int(0.1 * 22050)
        for i in range(0, len(audio), chunk):
            tr.push_audio_chunk(audio[i : i + chunk])
        self.assertEqual(len(tr.events), 0)
        self.assertGreater(tr.weak_dropped, 0)


@unittest.skipUnless(MODEL.exists(), "model not fetched (scripts/fetch_basic_pitch_model.py)")
class TestModelWindow(unittest.TestCase):
    def test_onset_frame_matches_time(self):
        from pianosheet_poc.basicpitch_onnx import AUDIO_N_SAMPLES, BasicPitchOnnx

        model = BasicPitchOnnx(MODEL)
        sr = 22050
        buf = np.zeros(AUDIO_N_SAMPLES, dtype=np.float32)
        t = np.arange(int(0.5 * sr)) / sr
        tone = 0.3 * np.sin(2 * np.pi * 392.0 * t) * np.exp(-t / 0.5)
        start = int(1.0 * sr)
        buf[start : start + len(tone)] = tone
        res = model.predict_window(buf)
        col = res.onset[:, 67 - 21]
        peak_frame = int(np.argmax(col))
        self.assertAlmostEqual(peak_frame / 86.0, 1.0, delta=0.03)


if __name__ == "__main__":
    unittest.main()
