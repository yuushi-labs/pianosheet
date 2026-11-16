# PianoSheet

Android専用のピアノ音声リアルタイム採譜・楽譜生成アプリ（開発中）。

ピアノの演奏をマイクまたは音声ファイルから取り込み、**録音中に音符を五線譜へ順次表示**し、録音終了後に全体を再解析して編集可能な楽譜を生成する。iOS・Web版は対象外（Kotlin + Jetpack Compose のネイティブアプリ）。

- 仕様書: [docs/PianoSheet_仕様書_v1.3.md](docs/PianoSheet_仕様書_v1.3.md)（v1.2 は [docs/PianoSheet_仕様書_v1.2.md](docs/PianoSheet_仕様書_v1.2.md)）
- Phase 0 技術検証レポート: [docs/phase0-report.md](docs/phase0-report.md)
- 共通ノートイベント仕様: [shared-specs/note-event.schema.json](shared-specs/note-event.schema.json)

## 現在の状態

| フェーズ | 状態 |
|---|---|
| Phase 0: 技術検証（リアルタイム1〜2秒・採譜品質） | **完了** — 実測結果は [docs/phase0-report.md](docs/phase0-report.md) |
| Phase 1: Androidリアルタイム採譜MVP | 未着手 |
| Phase 2 以降（最終解析・編集・エクスポート） | 未着手 |

### Phase 0 の要点

- Basic Pitch（ICASSP 2022）の ONNX モデルを端末内推論の第一候補として実測。**2秒窓・0.25秒ステップで、発音から表示まで中央値 377 ms / p95 552 ms**（2秒以内に収まる割合100%）。
- 和音・同音連打・保持を含むライブ処理の **F1 0.90**、音高と発音時刻の誤差は中央値 4〜9 ms。
- チャンク境界の重複は「確定済み区間の除外＋末尾260msの保留＋同音統合」で処理し、モデル候補2,585件から実イベント234件に整理。
- DSPのみのベースライン（スペクトルピーク追跡）も比較実測（合成音源では F1 0.94 と健闘。実機マイクでの再評価が課題）。
- **ピアノ向けノイズ除去**（25Hzハイパス＋軽量スペクトル減算＋任意の50/60Hzノッチ）を実装し、あり／なしで実測。ノイズ入り6音源の micro F1 は **0.829 → 0.840**（最終解析・標準）、ライブ平均 F1 **0.968 → 0.974**、DSP遅延は 58ms。詳細は [docs/phase0-report.md](docs/phase0-report.md) の「ノイズ除去」節。

## リポジトリ構成

```
pianosheet/
├── docs/
│   ├── PianoSheet_仕様書_v1.3.md      # 仕様書（最新。20章がノイズ除去仕様）
│   ├── PianoSheet_仕様書_v1.2.md      # 旧版（参照用）
│   └── phase0-report.md              # Phase 0 実測レポート
├── shared-specs/
│   └── note-event.schema.json        # 共通NoteEvent（ライブ／最終解析）
├── test-fixtures/
│   ├── piano_synth.py                # ピアノ風合成音源（決定的）
│   ├── generate_fixtures.py          # WAV + 正解JSONの生成
│   ├── wav/                          # 生成済みテスト音源（T-001〜T-020）
│   └── ground_truth/                 # 各音源の正解ノート
└── phase0-poc/                       # Phase 0 の検証コード（Python）
    ├── pianosheet_poc/
    │   ├── basicpitch_onnx.py        # ONNX推論＋Basic Pitch復号の移植
    │   ├── bp_live.py                # ライブ用アダプタ
    │   ├── live.py                   # チャンク処理・イベント統合エンジン
    │   ├── denoise.py                # ピアノ向けノイズ除去（ハイパス／スペクトル減算／ノッチ）
    │   ├── spectral_peaks.py         # DSPベースライン（ピーク追跡）
    │   ├── spectral_nmf.py           # DSPベースライン（倍音辞書NMF・不採用）
    │   ├── metrics.py                # P/R/F1・発音時刻誤差
    │   └── notes.py                  # NoteEventモデル
    ├── run_phase0.py                 # 実測ドライバ
    └── results/                      # 実測結果（JSON・サマリ）
```

Androidアプリ本体（`app/`）は Phase 1 で追加する。

## Phase 0 の再現

```bash
python3 phase0-poc/scripts/fetch_basic_pitch_model.py   # モデル取得（再配布しない）
pip install -r phase0-poc/requirements.txt
python3 test-fixtures/generate_fixtures.py              # テスト音源の再生成
python3 phase0-poc/run_phase0.py                        # 全実測（約6分）
python3 phase0-poc/run_phase0.py --only denoise          # ノイズ除去の比較だけ
```

## ライセンス

未定（リポジトリ公開時点では未設定）。第三者の著作物・モデルの扱いは [docs/phase0-report.md](docs/phase0-report.md) 付録B を参照:

- Basic Pitch（Spotify AB）: Apache License 2.0。モデル重みは再配布せず取得スクリプトのみを含む。
- テスト音源: 本リポジトリの合成スクリプトによる生成物。
