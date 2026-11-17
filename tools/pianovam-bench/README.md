# PianoVAM benchmark harness

The measurement code behind [`docs/phase2-4-pianovam-report.md`](../../docs/phase2-4-pianovam-report.md).

`onnxruntime-android` cannot run on a desktop JVM, so the split is:

* **here (Python)** — download the dataset, cut excerpts, run the Basic Pitch ONNX
  model over the app's window plan, decode it with a line-by-line port of
  `BasicPitchDecoder`, and score the result;
* **the app's own Kotlin** — everything after inference (`NoteCandidateFilter`,
  `StartupGuard`, `FinalNoteMerger`, `HarmonicRealnessFilter`, `ScoreBuilder`,
  `MusicXmlExporter`) runs unchanged in `PianoVamBenchmarkTest`
  (`app/src/test/java/com/yuushilabs/pianosheet/benchmark/`).

The port was checked against the app before it was used for measurements: on the
Hanon excerpt the note set matches exactly, on Yiruma it differs by one borderline
note of the harmonic filter.

## Data

[PianoVAM v1.2](https://huggingface.co/datasets/PianoVAM/PianoVAM_v1) (CC BY-NC 4.0).
Only `Audio/`, `MIDI/` and `TSV/` of six test-split recordings are fetched (~220 MB);
the audio is **not** in this repository.

```bash
pip install numpy scipy onnxruntime librosa music21 soundfile
export PIANOVAM_ROOT=$HOME/pianovam        # where the data and results go
python3 fetch.py                           # 6 recordings + metadata.json + MIDI/TSV
python3 -c "import pvlib; pvlib.prepare_excerpts()"   # 40 s excerpts + GT JSON
```

## Runs

```bash
python3 run_bench.py baseline   # before/after pipeline on the 6 excerpts (final + live)
python3 ablate.py               # one-knob-at-a-time decode/post-filter grid
python3 fixtures_eval.py        # the repo's 19 synthetic fixtures with exact ground truth
python3 audio_lab.py            # noise / level variants x denoise strengths
python3 chord_complete.py       # spectral chord completion (measured, not adopted)

python3 decode_cache.py         # cache per-window model outputs for fast ablations
python3 export_kotlin.py        # per-window candidates + resampled WAV for the Kotlin replay
python3 export_fixtures.py      # the same for T-010/T-014/T-019/T-020
```

Then, in the repository root:

```bash
PIANOSHEET_PIANOVAM_DIR=$PIANOVAM_ROOT/replay \
  ./gradlew :app:testDebugUnitTest --tests '*PianoVamBenchmarkTest*'
cd tools/pianovam-bench && python3 replay_metrics.py
```

`replay_metrics.py` prints the comparison table and writes
`$PIANOVAM_ROOT/results/v15-kotlin.json`; `replay/pv*.kotlin.json` and
`replay/pv*.musicxml` are the finished notes and scores the app produced.

## Files

| file | role |
|---|---|
| `fetch.py` | download the six recordings and the metadata |
| `pvlib.py` | ground truth (TSV), excerpt selection, matching / F1 / chord / timing metrics |
| `refs.py` | reference tempo (librosa beat tracking), key (music21 on GT), meter, bar-accent ratio |
| `app_pipeline.py` | Python port of the app's Basic Pitch decoder + candidate filter + merger |
| `app_final.py` | port of the final-analysis chain (resample → denoise → windows → score estimates) |
| `app_live.py` | port of `LiveTranscriptionEngine` (2 s window / 0.25 s step) |
| `run_bench.py` | end-to-end run over the six excerpts |
| `ablate.py` | ablation grid over cached windows |
| `fixtures_eval.py` | absolute F1 on `test-fixtures/` |
| `audio_lab.py` | level and noise variants × denoise strength |
| `chord_lab.py`, `chord_complete.py`, `meter_lab.py` | the experiments behind the "measured and rejected" list |
| `decode_cache.py`, `export_kotlin.py`, `export_fixtures.py`, `replay_metrics.py` | the Kotlin replay bridge |

`metadata.json` from the dataset is downloaded into `$PIANOVAM_ROOT`, never committed.
