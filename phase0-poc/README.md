# phase0-poc — Phase 0 技術検証コード

Basic Pitch の ONNX モデルを「Android のライブ採譜」と同じ条件（チャンク入力・固定窓・固定ステップ）で動かし、精度・遅延・負荷を実測するためのコード。結果と考察は [`../docs/phase0-report.md`](../docs/phase0-report.md)。

## セットアップ

```bash
# モデル（Basic Pitch / Apache-2.0）を PyPI のホイールから取り出す
python3 scripts/fetch_basic_pitch_model.py

# 実行環境（この検証では numpy 2.5.4 / scipy 1.18 / onnxruntime 1.31.1）
pip install -r requirements.txt
```

`basic-pitch` パッケージ自体は**インストールしない**。理由は `docs/phase0-report.md` §1.1 のとおり（TensorFlow `<2.15.1` の固定で Python 3.12 に入らない）。モデルは無改変で使い、前処理と復号は本リポジトリで再実装している。

## 実行

```bash
python3 run_phase0.py                    # 全ステージ（約5分）
python3 run_phase0.py --only accuracy     # 全体解析の精度
python3 run_phase0.py --only stream       # チャンク処理（窓×ステップのグリッド）
python3 run_phase0.py --only baselines    # DSPベースライン（スペクトルピーク／NMF）
python3 run_phase0.py --only denoise       # ノイズ除去あり／なし（T-014〜T-020）
python3 run_phase0.py --only resources    # 起動・メモリ・スレッド数・再現性
python3 -m unittest discover -s tests     # 動作確認
```

出力は `results/phase0_results.json`（全数値）と `results/summary.md`（表形式）。

## 構成

| ファイル | 役割 |
|---|---|
| `pianosheet_poc/basicpitch_onnx.py` | ONNX Runtime で Basic Pitch を実行。2秒窓の推論、ファイル全体の窓連結、復号（Basic Pitch の `note_creation.py` を移植） |
| `pianosheet_poc/bp_live.py` | `StreamingTranscriber` に Basic Pitch を差し込むアダプタ |
| `pianosheet_poc/live.py` | チャンク入力 → リングバッファ → ステップ実行 → 候補整理（減衰尾除去／確定済み除外／保留／統合） |
| `pianosheet_poc/denoise.py` | ピアノ向けノイズ除去: 25Hzハイパス＋局所中央値ベースのスペクトル減算＋50/60Hzノッチ（ライブ／最終解析の両経路、NumPyのみ） |
| `pianosheet_poc/spectral_peaks.py` | DSPベースライン: STFTピーク＋倍音除去＋立ち上がり検出 |
| `pianosheet_poc/spectral_nmf.py` | DSPベースライン: 88鍵テンプレートのKL-NMF（実測で不採用） |
| `pianosheet_poc/metrics.py` | 発音時刻±50 ms・音高一致によるP/R/F1 |
| `pianosheet_poc/notes.py` | `shared-specs/note-event.schema.json` に対応する `NoteEvent` |
| `pianosheet_poc/audio_io.py` | WAV読み込み・ポリフェーズリサンプル（44.1k→22.05k） |

## 採用パラメータ（実測で決めた初期値）

| パラメータ | 値 |
|---|---|
| 窓長 / ステップ | 2.0 s / 0.25 s（1.0 s 窓でも精度はほぼ同等） |
| 保留（guard） | 260 ms |
| onset活性下限 | 0.30 |
| 同音統合の時間幅 | 120 ms |
| 最小音符長 | 128 ms（11フレーム、上流既定） |
| ノイズ除去（ライブ） | 25Hzハイパス常時＋「弱」のスペクトル減算 |
| ノイズ除去（最終解析） | 25Hzハイパス常時＋「標準」＋ハム自動判定でノッチ |
| DSPフレーム / ホップ | 1024 / 256 サンプル（DSP遅延 58ms） |

## ノイズ除去（`pianosheet_poc/denoise.py`）

録音そのものは無加工で残し、**解析に渡すコピーだけ**を整える。仕様は `../docs/PianoSheet_仕様書_v1.3.md` の20章。

```python
from pianosheet_poc.denoise import DenoiseConfig, Denoiser, StreamingDenoiser

final = Denoiser(DenoiseConfig(strength="standard", notch_hz=50.0)).process(audio)  # 最終解析
live = StreamingDenoiser(DenoiseConfig(strength="weak"))                            # ライブ
denoiser = live.process_chunk(chunk)   # 入力より少し短い（保持分がある）
```

- `strength`: `off` / `weak` / `standard` / `strong`
- `notch_hz`: `None`（なし）/ 50.0 / 60.0。`detect_mains_hz()` で自動判定できる
- ライブ経路の遅延は `StreamingDenoiser.delay_ms`（=58ms）。発音時刻は変わらないので、表示遅延にだけ加算する

## 注意

- テスト音源は合成音（`../test-fixtures/piano_synth.py`）。実ピアノ＋実機マイクでの再評価が必要。
- モデル重みはリポジトリに含めない（`.gitignore`）。`scripts/fetch_basic_pitch_model.py` が SHA-256 を検証して取得する。
- 復号処理の移植元: `basic_pitch/inference.py`, `basic_pitch/note_creation.py`（Copyright 2022 Spotify AB, Apache License 2.0）。各ファイル冒頭に出典を明記している。
