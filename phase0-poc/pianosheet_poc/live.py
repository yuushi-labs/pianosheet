"""Streaming (live) transcription engine used for the Phase 0 latency tests.

The engine mirrors what the Android `LiveTranscriber` will do:

    AudioRecord chunk  ->  ring buffer  ->  every `step_s` seconds take the last
    `window_s` seconds  ->  model  ->  note events with absolute times  ->
    de-duplicate against events already reported  ->  provisional NoteEvents

Two things are measured here that the earlier `transcribe()` cannot show:

  * algorithmic latency: audio time of the note versus the audio time of the
    step that first reported it (`emitted_at - onset`)
  * duplicate behaviour at chunk boundaries: every candidate the model returns
    is counted, and how many survive de-duplication is reported

`StreamingTranscriber` is detector-agnostic: a subclass supplies `_detect()`.
"""

from __future__ import annotations

import time
from collections import deque
from dataclasses import dataclass, field
from typing import Deque, Dict, List, Optional

import numpy as np

from .notes import (
    NoteEvent,
    STATE_PROVISIONAL,
    dedupe_events,
)


@dataclass
class LiveConfig:
    sample_rate: int = 22050
    window_s: float = 2.0
    step_s: float = 0.25
    onset_tolerance_ms: float = 120.0  # same pitch + near onset => same note
    tail_merge_ms: float = 120.0  # same pitch, previous end this close => continuation
    confidence_floor: float = 0.0
    # minimum model onset activation for a new note.  A struck key gives ~0.9;
    # a note the melodia pass invents inside a ringing tail gives ~0.0
    onset_strength_min: float = 0.30
    # with a low onset activation, a note that continues a same-pitch event is
    # treated as the same note rather than a re-strike
    restrike_onset_min: float = 0.50
    # audio at the very end of a window has too little following context for the
    # model to decide on it; onsets in the newest `guard_ms` are held back for the
    # next step instead of being committed or lost
    guard_ms: float = 260.0
    # Android would hold a candidate for a moment before drawing it; 0 = draw at once
    confirmation_delay_ms: float = 0.0

    @property
    def window_samples(self) -> int:
        return int(round(self.window_s * self.sample_rate))

    @property
    def step_samples(self) -> int:
        return int(round(self.step_s * self.sample_rate))


@dataclass
class StepStat:
    step_index: int
    audio_time_s: float
    compute_ms: float
    n_candidates: int
    n_new: int
    buffer_ms: float


class StreamingTranscriber:
    """Chunk-in / note-events-out engine with event merging."""

    name = "streaming"

    def __init__(self, config: LiveConfig) -> None:
        self.config = config
        self._chunks: Deque[np.ndarray] = deque()
        self._chunks_len = 0
        self._abs_samples = 0  # samples pushed so far
        self._next_step = 0  # sample position of the next step boundary
        self._committed_until_s = 0.0  # onsets up to here have been reported
        self._first_step = True
        self.events: List[NoteEvent] = []
        self.stats: List[StepStat] = []
        self.raw_candidates = 0
        self.duplicates_dropped = 0
        self.stale_filtered = 0
        self.weak_dropped = 0
        self.low_confidence_dropped = 0
        self.step_compute_ms: List[float] = []

    # -- public API (mirrors the spec's LiveTranscriber) ----------------------
    def start_session(self) -> None:
        self._chunks.clear()
        self._chunks_len = 0
        self._abs_samples = 0
        self._next_step = 0
        self._committed_until_s = 0.0
        self._first_step = True
        self.events = []
        self.stats = []
        self.raw_candidates = 0
        self.duplicates_dropped = 0
        self.stale_filtered = 0
        self.weak_dropped = 0
        self.low_confidence_dropped = 0
        self.step_compute_ms = []

    def push_audio_chunk(self, chunk: np.ndarray, sequence: Optional[int] = None) -> List[NoteEvent]:
        """Push audio; returns the note events newly reported by this call."""
        chunk = np.ascontiguousarray(np.asarray(chunk, dtype=np.float32).reshape(-1))
        self._chunks.append(chunk)
        self._chunks_len += len(chunk)
        self._abs_samples += len(chunk)
        self._trim()

        emitted: List[NoteEvent] = []
        if self._first_step:
            self._next_step = min(self.config.step_samples, self._abs_samples)
            self._first_step = False
        while self._abs_samples >= self._next_step:
            emitted.extend(self._run_step())
            self._next_step += self.config.step_samples
        return emitted

    def finish_session(self) -> List[NoteEvent]:
        """Flush audio that no step boundary has covered yet, then close."""
        if self._abs_samples > self._next_step - self.config.step_samples:
            if self._next_step != self._abs_samples:
                self._next_step = self._abs_samples
                return self._run_step()
        return []

    def get_note_events(self) -> List[NoteEvent]:
        return list(self.events)

    # -- internals -----------------------------------------------------------
    def _trim(self) -> None:
        keep = self.config.window_samples
        while self._chunks_len - len(self._chunks[0]) >= keep and len(self._chunks) > 1:
            dropped = self._chunks.popleft()
            self._chunks_len -= len(dropped)

    def _window_audio(self, end_sample: int) -> tuple[np.ndarray, int]:
        """Return (audio, start_sample) for the window ending at `end_sample`."""
        start = max(0, end_sample - self.config.window_samples)
        need = end_sample - start
        chunks = list(self._chunks)
        audio = np.concatenate(chunks) if len(chunks) > 1 else chunks[0]
        # `audio` ends at self._abs_samples
        offset = self._abs_samples - len(audio)
        lo = max(0, start - offset)
        hi = max(lo, end_sample - offset)
        audio = audio[lo:hi]
        if len(audio) < need:
            audio = np.pad(audio, (need - len(audio), 0))
        return np.ascontiguousarray(audio, dtype=np.float32), start

    def _run_step(self) -> List[NoteEvent]:
        end_sample = self._next_step
        audio, start = self._window_audio(end_sample)
        audio_time_s = end_sample / self.config.sample_rate
        t0 = time.perf_counter()
        detections = self._detect(audio, start / self.config.sample_rate)
        compute_ms = (time.perf_counter() - t0) * 1000.0
        self.step_compute_ms.append(compute_ms)

        raw = [
            NoteEvent(
                pitch_midi=int(d["pitch_midi"]),
                onset_ms=float(d["start_s"]) * 1000.0,
                duration_ms=(float(d["end_s"]) - float(d["start_s"])) * 1000.0,
                confidence=float(d.get("confidence", 1.0)),
                onset_strength=(
                    None if d.get("onset_strength") is None else float(d["onset_strength"])
                ),
                state=STATE_PROVISIONAL,
                source_chunk=len(self.stats),
            )
            for d in detections
            if float(d["start_s"]) >= 0.0
        ]
        self.raw_candidates += len(raw)
        candidates = []
        for c in raw:
            weak = c.onset_strength is not None and c.onset_strength < self.config.onset_strength_min
            if weak:
                self.weak_dropped += 1
                continue
            if c.confidence < self.config.confidence_floor:
                self.low_confidence_dropped += 1
                continue
            candidates.append(c)

        # Only onsets in the newly reachable part of the timeline are committed.
        # The newest `guard_ms` is still unresolved (the model lacks the audio
        # after it), so it is left for the next step; anything at or before the
        # commit point was already decided and is dropped here.
        allowed_max = audio_time_s - self.config.guard_ms / 1000.0
        lo = self._committed_until_s - self.config.onset_tolerance_ms / 1000.0
        in_region = [c for c in candidates if lo < c.onset_s <= allowed_max]
        self.stale_filtered += len(candidates) - len(in_region)
        candidates = in_region
        if allowed_max > self._committed_until_s:
            self._committed_until_s = allowed_max

        new_events = self._merge(candidates, audio_time_s)
        self.stats.append(
            StepStat(
                step_index=len(self.stats),
                audio_time_s=round(audio_time_s, 3),
                compute_ms=round(compute_ms, 2),
                n_candidates=len(candidates),
                n_new=len(new_events),
                buffer_ms=round(1000.0 * end_sample / self.config.sample_rate),
            )
        )
        self.events.extend(new_events)
        return new_events

    def _merge(self, candidates: List[NoteEvent], audio_time_s: float) -> List[NoteEvent]:
        new: List[NoteEvent] = []
        for cand in candidates:
            duplicate = None
            for existing in self.events[-96:]:
                if existing.pitch_midi != cand.pitch_midi:
                    continue
                if abs(existing.onset_ms - cand.onset_ms) <= self.config.onset_tolerance_ms:
                    duplicate = existing
                    break
                existing_end = existing.onset_ms + (existing.duration_ms or 0.0)
                # only a detector that reports attack strength can claim "this
                # is the same note still ringing"; a plain DSP onset is a strike
                weak_attack = (
                    cand.onset_strength is not None
                    and cand.onset_strength < self.config.restrike_onset_min
                )
                if (
                    weak_attack
                    and existing_end > cand.onset_ms - self.config.tail_merge_ms
                    and cand.onset_ms >= existing.onset_ms
                ):
                    duplicate = existing
                    break
            if duplicate is not None:
                self.duplicates_dropped += 1
                duplicate.confidence = max(duplicate.confidence, cand.confidence)
                cand_end = cand.onset_ms + (cand.duration_ms or 0.0)
                duplicate_end = duplicate.onset_ms + (duplicate.duration_ms or 0.0)
                if cand.duration_ms is not None and cand_end > duplicate_end:
                    duplicate.duration_ms = cand_end - duplicate.onset_ms
                continue
            cand.sequence = len(self.events) + len(new)
            cand.emitted_at_s = audio_time_s  # for latency reporting
            cand.render_ms = self.step_compute_ms[-1]
            new.append(cand)
        return new

    # -- subclass hook -------------------------------------------------------
    def _detect(self, window: np.ndarray, window_start_s: float) -> List[Dict]:
        raise NotImplementedError
