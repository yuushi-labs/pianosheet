"""Basic Pitch behind the streaming engine (`LiveTranscriber` candidate)."""

from __future__ import annotations

from typing import Dict, List

import numpy as np

from .basicpitch_onnx import (
    AUDIO_N_SAMPLES,
    DEFAULT_FRAME_THRESHOLD,
    DEFAULT_ONSET_THRESHOLD,
    BasicPitchOnnx,
    output_to_notes_polyphonic,
)
from .live import LiveConfig, StreamingTranscriber


class BasicPitchStreaming(StreamingTranscriber):
    """2 s window -> model -> decode, every `step_s` seconds.

    A window shorter than the model's 43844 samples (only at the very start of
    a session, or when `window_s` < 2 s) is zero-padded on the *left*, so the
    newest audio always sits at the end of the window.
    """

    name = "basic_pitch_onnx"

    def __init__(
        self,
        model: BasicPitchOnnx,
        config: LiveConfig,
        onset_thresh: float = DEFAULT_ONSET_THRESHOLD,
        frame_thresh: float = DEFAULT_FRAME_THRESHOLD,
        min_note_len: int = 11,
        infer_onsets: bool = True,
        melodia_trick: bool = True,
        confidence_floor: float = 0.0,
        preprocess=None,
    ) -> None:
        super().__init__(config, preprocess=preprocess)
        self.model = model
        self.onset_thresh = onset_thresh
        self.frame_thresh = frame_thresh
        self.min_note_len = min_note_len
        self.infer_onsets = infer_onsets
        self.melodia_trick = melodia_trick
        self.confidence_floor = confidence_floor

    def _detect(self, window: np.ndarray, window_start_s: float) -> List[Dict]:
        n = len(window)
        if n > AUDIO_N_SAMPLES:
            window = window[n - AUDIO_N_SAMPLES :]
            window_start_s += (n - AUDIO_N_SAMPLES) / 22050.0
        elif n < AUDIO_N_SAMPLES:
            pad = AUDIO_N_SAMPLES - n
            window = np.pad(window, (pad, 0))
            # the model sees `pad` samples of silence before the audio, so its
            # frame 0 is `pad` samples earlier than the buffer start
            window_start_s -= pad / 22050.0
        res = self.model.predict_window(window)
        raw = output_to_notes_polyphonic(
            res.note,
            res.onset,
            onset_thresh=self.onset_thresh,
            frame_thresh=self.frame_thresh,
            min_note_len=self.min_note_len,
            infer_onsets=self.infer_onsets,
            max_freq=None,
            min_freq=None,
            melodia_trick=self.melodia_trick,
        )
        fps = 22050.0 / 256.0
        out = []
        for start, end, pitch, amp, onset_strength in raw:
            out.append(
                {
                    "start_s": window_start_s + start / fps,
                    "end_s": window_start_s + min(end, 172) / fps,
                    "pitch_midi": int(pitch),
                    "confidence": float(amp),
                    "onset_strength": float(onset_strength),
                }
            )
        return out
