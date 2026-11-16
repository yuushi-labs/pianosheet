"""Shared note-event model for the PianoSheet Phase 0 proof of concept.

This is the Python-side draft of the event that both transcriber families
return, and it mirrors `shared-specs/note-event.schema.json`.
"""

from __future__ import annotations

import math
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional

STATE_PENDING = "pending"
STATE_PROVISIONAL = "provisional"
STATE_CONFIRMED = "confirmed"
STATE_REVISED = "revised"
STATE_REJECTED = "rejected"


@dataclass
class NoteEvent:
    pitch_midi: int
    onset_ms: float
    duration_ms: Optional[float] = None
    confidence: float = 1.0
    onset_strength: Optional[float] = None  # model's own attack activation, 0..1
    state: str = STATE_PROVISIONAL
    source_chunk: Optional[int] = None
    sequence: Optional[int] = None
    emitted_at_s: Optional[float] = None  # audio time of the step that reported it
    render_ms: Optional[float] = None  # compute time of that step
    id: str = field(default_factory=lambda: str(uuid.uuid4()))

    @property
    def onset_s(self) -> float:
        return self.onset_ms / 1000.0

    @property
    def offset_s(self) -> Optional[float]:
        if self.duration_ms is None:
            return None
        return (self.onset_ms + self.duration_ms) / 1000.0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "sequence": self.sequence,
            "pitch_midi": int(self.pitch_midi),
            "onset_ms": round(float(self.onset_ms), 2),
            "duration_ms": None if self.duration_ms is None else round(float(self.duration_ms), 2),
            "confidence": round(float(self.confidence), 4),
            "onset_strength": None if self.onset_strength is None else round(float(self.onset_strength), 4),
            "state": self.state,
            "source_chunk": self.source_chunk,
            "emitted_at_s": None if self.emitted_at_s is None else round(float(self.emitted_at_s), 3),
        }

    @staticmethod
    def from_dict(d: Dict[str, Any]) -> "NoteEvent":
        return NoteEvent(
            pitch_midi=int(d["pitch_midi"]),
            onset_ms=float(d["onset_ms"]),
            duration_ms=None if d.get("duration_ms") is None else float(d["duration_ms"]),
            confidence=float(d.get("confidence", 1.0)),
            state=d.get("state", STATE_PROVISIONAL),
            source_chunk=d.get("source_chunk"),
            sequence=d.get("sequence"),
            id=d.get("id", str(uuid.uuid4())),
        )


def midi_to_hz(pitch: float) -> float:
    return 440.0 * (2.0 ** ((pitch - 69.0) / 12.0))


def midi_to_name(pitch: int) -> str:
    names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    return f"{names[int(pitch) % 12]}{int(pitch) // 12 - 1}"


def dedupe_events(
    events: Iterable[NoteEvent],
    onset_tolerance_ms: float = 120.0,
) -> List[NoteEvent]:
    """Merge candidates that describe the same strike of the same key.

    Two events are the same note when the pitch matches and their onsets are
    closer than `onset_tolerance_ms`.  The survivor keeps the earliest onset
    and the higher confidence, and is marked `provisional`.
    """
    ordered = sorted(events, key=lambda e: (e.onset_ms, -e.confidence))
    kept: List[NoteEvent] = []
    for ev in ordered:
        duplicate = None
        for existing in reversed(kept[-32:]):
            if existing.pitch_midi != ev.pitch_midi:
                continue
            if abs(existing.onset_ms - ev.onset_ms) <= onset_tolerance_ms:
                duplicate = existing
                break
        if duplicate is None:
            kept.append(ev)
            continue
        if ev.confidence > duplicate.confidence:
            duplicate.confidence = ev.confidence
        if ev.duration_ms and duplicate.duration_ms:
            duplicate.duration_ms = max(duplicate.duration_ms, ev.duration_ms)
        elif ev.duration_ms and not duplicate.duration_ms:
            duplicate.duration_ms = ev.duration_ms
    kept.sort(key=lambda e: e.onset_ms)
    for i, ev in enumerate(kept):
        ev.sequence = i
    return kept


def group_chords(events: List[NoteEvent], window_ms: float = 60.0) -> List[List[NoteEvent]]:
    """Group simultaneous notes, useful for reporting chord detection counts."""
    groups: List[List[NoteEvent]] = []
    for ev in sorted(events, key=lambda e: e.onset_ms):
        if groups and abs(ev.onset_ms - groups[-1][0].onset_ms) <= window_ms:
            groups[-1].append(ev)
        else:
            groups.append([ev])
    return groups


def hz_to_midi(freq: float) -> float:
    return 69.0 + 12.0 * math.log2(freq / 440.0)
