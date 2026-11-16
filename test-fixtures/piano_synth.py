"""Deterministic piano-like tone synthesiser for PianoSheet test fixtures.

Phase 0 needs reproducible audio with a known ground truth.  A real piano
recording cannot be licensed and re-generated at will, so the fixtures are
synthesised from an additive model with piano-ish properties:

  * stiff-string inharmonicity:  f_n = n * f0 * sqrt(1 + B*n^2)
  * harmonic amplitudes that roll off faster for quiet notes (velocity ->
    brightness), roughly 1/n^(1.35 - 0.5*velocity)
  * per-harmonic exponential decay, shorter for higher partials, and a
    fundamental decay time that lengthens towards the bass
  * a short hammer-noise burst at the attack
  * a damper (fast decay) at key release, with an optional long release that
    imitates the sustain pedal (T-012)

The result is a signal with the properties that make piano transcription hard
(strong partials, overlapping decay tails, inharmonicity), while the onset
times and pitches stay exactly known.

All randomness is seeded, so the WAV files are byte-reproducible.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Iterable, List, Optional, Sequence

import numpy as np

SR = 44100  # fixtures are rendered like a normal 44.1 kHz recording
MAX_HARMONICS = 24


def midi_to_hz(pitch: float) -> float:
    return 440.0 * (2.0 ** ((pitch - 69.0) / 12.0))


def hz_to_midi(freq: float) -> float:
    return 69.0 + 12.0 * math.log2(freq / 440.0)


def note_name(pitch: int) -> str:
    names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    return f"{names[pitch % 12]}{pitch // 12 - 1}"


@dataclass
class Note:
    """A scored note; times are in seconds, pitch is a MIDI number."""

    pitch: int
    onset: float
    duration: float
    velocity: float = 0.7
    pedal: bool = False  # long release, imitates the sustain pedal


@dataclass
class Fixture:
    test_id: str
    name: str
    description: str
    notes: List[Note]
    noise_snr_db: Optional[float] = None
    level_db: float = -3.0
    seed: int = 0
    extra: dict = field(default_factory=dict)

    @property
    def duration(self) -> float:
        tail = 3.0 if any(n.pedal for n in self.notes) else 2.0
        return max(n.onset + n.duration for n in self.notes) + tail


def render_note(
    pitch: int,
    duration: float,
    sr: int = SR,
    velocity: float = 0.7,
    rng: Optional[np.random.Generator] = None,
    pedal: bool = False,
) -> np.ndarray:
    """Render one piano-ish note (attack + sustain + release) as float32."""
    if rng is None:
        rng = np.random.default_rng(0)
    f0 = midi_to_hz(pitch)
    release = 0.55 if pedal else 0.14  # damper time constant, seconds
    tail = 4.0 if pedal else 2.2
    n = int(sr * (duration + tail))
    t = np.arange(n) / sr

    inharmonicity = 1e-4 * (2.0 ** ((pitch - 60) / 18.0))
    tilt = 1.35 - 0.5 * velocity
    tau0 = float(np.clip(2.6 * (261.63 / f0) ** 0.45, 0.30, 7.0))
    # A key release starts the damper; long notes keep their natural decay.
    damper_start = duration

    out = np.zeros(n, dtype=np.float64)
    for h in range(1, MAX_HARMONICS + 1):
        fh = h * f0 * math.sqrt(1.0 + inharmonicity * h * h)
        if fh >= sr / 2.0 * 0.98:
            break
        amp = (1.0 / h**tilt) * rng.uniform(0.85, 1.0)
        tau_h = tau0 / (1.0 + 0.12 * (h - 1))
        env_natural = np.exp(-t / tau_h)
        env = env_natural.copy()
        # damper: exponential decay from the release onwards
        after = t > damper_start
        if np.any(after):
            env[after] = env_natural[after] * np.exp(
                -(t[after] - damper_start) / release
            )
        phase = rng.uniform(0, 2 * math.pi)
        detune = rng.normal(0, 0.0004)  # ~0.7 cent random detune per partial
        out += amp * env * np.sin(2 * math.pi * fh * (1 + detune) * t + phase)

    # attack: raised-cosine rise over 5 ms
    attack = int(0.005 * sr)
    if attack > 1:
        ramp = 0.5 - 0.5 * np.cos(np.linspace(0, math.pi, attack))
        out[:attack] *= ramp

    # hammer noise: short noise burst, low-passed, decaying in ~8 ms
    hn_len = int(0.012 * sr)
    noise = rng.normal(0, 1, hn_len)
    kernel = np.ones(8) / 8.0
    noise = np.convolve(noise, kernel, mode="same")
    noise *= np.exp(-np.arange(hn_len) / (0.008 * sr))
    env_peak = float(np.max(np.abs(out))) or 1.0
    out[:hn_len] += 0.06 * velocity * noise * env_peak

    out *= 0.5 + 0.9 * velocity  # velocity -> loudness
    return out.astype(np.float32)


def render_fixture(fixture: Fixture, sr: int = SR) -> np.ndarray:
    """Render a whole fixture (all notes mixed) as float32 mono at `sr`."""
    rng = np.random.default_rng(fixture.seed)
    total = int(fixture.duration * sr) + sr
    buf = np.zeros(total, dtype=np.float64)
    for note in fixture.notes:
        tone = render_note(
            note.pitch,
            note.duration,
            sr=sr,
            velocity=note.velocity,
            rng=rng,
            pedal=note.pedal,
        )
        start = int(round(note.onset * sr))
        end = min(start + len(tone), len(buf))
        buf[start:end] += tone[: end - start]

    if fixture.noise_snr_db is not None:
        rms = math.sqrt(float(np.mean(buf**2))) or 1e-6
        noise_rms = rms / (10 ** (fixture.noise_snr_db / 20.0))
        buf += rng.normal(0, noise_rms, len(buf))
        # a little mains hum, as a phone microphone in a room picks up
        t = np.arange(len(buf)) / sr
        buf += 0.3 * noise_rms * np.sin(2 * math.pi * 60.0 * t)

    peak = float(np.max(np.abs(buf))) or 1.0
    target = 10 ** (fixture.level_db / 20.0)
    buf *= target / peak
    return buf.astype(np.float32)


def _triad(root: int, quality: str) -> List[int]:
    intervals = {
        "maj": (0, 4, 7),
        "min": (0, 3, 7),
        "maj7": (0, 4, 7, 11),
        "dom7": (0, 4, 7, 10),
        "min7": (0, 3, 7, 10),
    }[quality]
    return [root + i for i in intervals]


def _seq(notes: Sequence[Note], gap: float) -> List[Note]:
    out = []
    t = 0.0
    for n in notes:
        out.append(Note(n.pitch, t, n.duration, n.velocity, n.pedal))
        t += n.duration + gap
    return out


def build_fixtures() -> List[Fixture]:
    """All Phase 0 fixtures (spec 13.1 T-001..T-006 plus useful extras)."""
    fx: List[Fixture] = []

    # T-001: single notes, one at a time (pitch detection)
    singles = [60, 64, 67, 72, 57, 69, 48, 76]
    fx.append(
        Fixture(
            "T-001",
            "single_notes",
            "単音を1音ずつ（C4 E4 G4 C5 A3 A4 C3 E5）",
            [
                Note(p, i * 0.9, 0.55, 0.65)
                for i, p in enumerate(singles)
            ],
            seed=1,
        )
    )

    # T-002: one long held note, then another (no duplicate events)
    fx.append(
        Fixture(
            "T-002",
            "held_notes",
            "同じ音を長く保持（C4を3秒、続いてG4を2秒）",
            [Note(60, 0.2, 3.0, 0.7), Note(67, 3.6, 2.0, 0.7)],
            seed=2,
        )
    )

    # T-003: repeated same note (each repeat must be a new event)
    fx.append(
        Fixture(
            "T-003",
            "repeated_note",
            "同じ音の連打（C4を0.28秒間隔で8回、E4を0.28秒間隔で4回）",
            [Note(60, 0.3 + i * 0.28, 0.12, 0.7) for i in range(8)]
            + [Note(64, 3.0 + i * 0.28, 0.12, 0.7) for i in range(4)],
            seed=3,
        )
    )

    # T-004: ascending / descending melody
    up = [60, 62, 64, 65, 67, 69, 71, 72]
    fx.append(
        Fixture(
            "T-004",
            "melody_up",
            "上行メロディ（C4→C5の長音階、0.42秒間隔）",
            [Note(p, 0.3 + i * 0.42, 0.32, 0.68) for i, p in enumerate(up)],
            seed=4,
        )
    )
    fx.append(
        Fixture(
            "T-004b",
            "melody_down",
            "下行メロディ（C5→C4の長音階、0.42秒間隔）",
            [
                Note(p, 0.3 + i * 0.42, 0.32, 0.68)
                for i, p in enumerate(reversed(up))
            ],
            seed=5,
        )
    )

    # T-005: three-note chords
    chords3 = [
        ("C4-E4-G4", _triad(60, "maj")),
        ("A3-C4-E4", _triad(57, "min")),
        ("G3-B3-D4", _triad(55, "maj")),
        ("F3-A3-C4", _triad(53, "maj")),
    ]
    notes = []
    for i, (_, pitches) in enumerate(chords3):
        for p in pitches:
            notes.append(Note(p, 0.3 + i * 1.35, 0.9, 0.62))
    fx.append(Fixture("T-005", "chords_3", "3音和音（C, Am, G, F）", notes, seed=6))

    # T-006: four-note chords
    chords4 = [
        ("Cmaj7", _triad(60, "maj7")),
        ("G7", _triad(55, "dom7")),
        ("Dm7", _triad(50, "min7")),
    ]
    notes = []
    for i, (_, pitches) in enumerate(chords4):
        for p in pitches:
            notes.append(Note(p, 0.3 + i * 1.6, 1.0, 0.66))
    fx.append(Fixture("T-006", "chords_4", "4音和音（Cmaj7, G7, Dm7）", notes, seed=7))

    # T-007: right hand only (treble staff)
    rh = [72, 74, 76, 77, 79, 81, 79, 77, 76]
    fx.append(
        Fixture(
            "T-007",
            "right_hand",
            "右手のみ（C5〜A5の旋律）",
            [Note(p, 0.3 + i * 0.45, 0.35, 0.65) for i, p in enumerate(rh)],
            seed=8,
        )
    )

    # T-008: left hand only (bass staff)
    lh = [43, 45, 47, 48, 43, 41, 43]
    fx.append(
        Fixture(
            "T-008",
            "left_hand",
            "左手のみ（G2〜C3の低音）",
            [Note(p, 0.3 + i * 0.55, 0.45, 0.66) for i, p in enumerate(lh)],
            seed=9,
        )
    )

    # T-010: fast passage
    fast = [60, 62, 64, 65, 67, 69, 71, 72, 74, 76]
    notes = [Note(p, 0.3 + i * 0.09, 0.08, 0.6) for i, p in enumerate(fast)]
    notes += [Note(p, 1.5 + i * 0.07, 0.06, 0.6) for i, p in enumerate(reversed(fast))]
    fx.append(Fixture("T-010", "fast_run", "速い演奏（90ms/70ms間隔の走句）", notes, seed=10))

    # T-011: quiet playing
    fx.append(
        Fixture(
            "T-011",
            "quiet_notes",
            "弱い音（T-001と同じ内容をpp相当の音量で）",
            [Note(p, i * 0.9, 0.55, 0.12) for i, p in enumerate([60, 64, 67])],
            level_db=-24.0,
            seed=11,
        )
    )

    # T-014: noisy recording of chords
    notes = []
    for i, (_, pitches) in enumerate(chords3):
        for p in pitches:
            notes.append(Note(p, 0.3 + i * 1.35, 0.9, 0.62))
    fx.append(
        Fixture(
            "T-014",
            "noisy_chords",
            "ノイズを含む和音（T-005にSNR20dBのノイズと60Hzハム）",
            notes,
            noise_snr_db=20.0,
            seed=12,
        )
    )

    # T-015: longer source for streaming stability
    notes = []
    t = 0.3
    pattern = [
        (60, 64, 67),
        (65, 69, 72),
        (67, 71, 74),
        (60, 64, 67),
        (57, 60, 64),
        (55, 59, 62),
    ]
    for rep in range(4):
        for chord in pattern:
            for p in chord:
                notes.append(Note(p, t, 0.55, 0.6))
            mel = 72 + (rep + chord[0]) % 8
            notes.append(Note(mel, t + 0.05, 0.4, 0.7))
            t += 1.0
    fx.append(Fixture("T-015", "long_session", "長時間音源（和音＋旋律24秒）", notes, seed=13))

    # T-012: sustain pedal (long release, overlapping tails)
    notes = []
    t = 0.3
    for chord in [(48, 55, 60, 64), (50, 57, 62, 65), (52, 59, 64, 67)]:
        for p in chord:
            notes.append(Note(p, t, 0.6, 0.6, pedal=True))
        t += 0.8
    fx.append(Fixture("T-012", "pedal", "ペダル使用（長い残響が重なる）", notes, seed=14))

    return fx
