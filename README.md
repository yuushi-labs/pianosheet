# PianoSheet

Android専用のピアノ音声リアルタイム採譜・楽譜生成アプリ（開発中）。

ピアノの演奏をマイクまたは音声ファイルから取り込み、**録音中に音符を五線譜へ順次表示**し、録音終了後に全体を再解析して編集可能な楽譜を生成する。iOS・Web版は対象外（Kotlin + Jetpack Compose のネイティブアプリ）。

- 仕様書: [docs/PianoSheet_仕様書_v1.3.md](docs/PianoSheet_仕様書_v1.3.md)（v1.2 は [docs/PianoSheet_仕様書_v1.2.md](docs/PianoSheet_仕様書_v1.2.md)）
- Phase 0 技術検証レポート: [docs/phase0-report.md](docs/phase0-report.md)
- 共通ノートイベント仕様: [shared-specs/note-event.schema.json](shared-specs/note-event.schema.json)
- サードパーティ・ライセンス: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## 現在の状態

| フェーズ | 状態 |
|---|---|
| Phase 0: 技術検証（リアルタイム1〜2秒・採譜品質） | **完了** — 実測結果は [docs/phase0-report.md](docs/phase0-report.md) |
| Phase 1: Androidリアルタイム採譜MVP | **実装済み（実機チューニング待ち）** — 下記「Phase 1 の実装範囲」 |
| Phase 2 以降（最終解析・編集・エクスポート） | 未着手 |

### Phase 0 の要点

- Basic Pitch（ICASSP 2022）の ONNX モデルを端末内推論の第一候補として実測。**2秒窓・0.25秒ステップで、発音から表示まで中央値 377 ms / p95 552 ms**（2秒以内に収まる割合100%）。
- 和音・同音連打・保持を含むライブ処理の **F1 0.90**、音高と発音時刻の誤差は中央値 4〜9 ms。
- チャンク境界の重複は「確定済み区間の除外＋末尾260msの保留＋同音統合」で処理し、モデル候補2,585件から実イベント234件に整理。
- DSPのみのベースライン（スペクトルピーク追跡）も比較実測（合成音源では F1 0.94 と健闘。実機マイクでの再評価が課題）。
- **ピアノ向けノイズ除去**（25Hzハイパス＋軽量スペクトル減算＋任意の50/60Hzノッチ）を実装し、あり／なしで実測。ノイズ入り6音源の micro F1 は **0.829 → 0.840**（最終解析・標準）、ライブ平均 F1 **0.968 → 0.974**、DSP遅延は 58ms。詳細は [docs/phase0-report.md](docs/phase0-report.md) の「ノイズ除去」節。

### Phase 1 の実装範囲（MVP）

- **Android構成**: Kotlin 1.9.24 / Jetpack Compose（Material 3）/ minSdk 29 / targetSdk 34、Gradle 8.9 + AGP 8.5.2。Gradle Wrapper同梱。
- **音声取得（`core/audio`）**: `RECORD_AUDIO` 権限UI（拒否時の案内つき）、`AudioRecord` の `UNPROCESSED` 優先＋`MIC` フォールバック、通話用 NS/AEC/AGC の無効化、100msチャンクを専用スレッドで取得。録音は端末内WAVへ逐次保存（無加工・無正規化）。
- **音声前処理（`core/dsp`）**: 25Hzハイパス（常時）＋軽量スペクトル減算（弱/標準/強/OFF）＋任意の50/60Hzノッチ。ライブはチャンクをまたいで状態を保持（DSP遅延 = 58ms）。録音データには掛けない。
- **端末内推論（`core/inference`）**: `onnxruntime-android` で Basic Pitch ONNX を読み込み、専用の単一スレッド Executor で 2.0秒窓・0.25秒ステップ推論。`BasicPitchDecoder` は上流 `note_creation.py` のKotlin移植（infer onsets・melodia trick 込み）。
- **イベント統合（`domain/live`）**: Phase 0 の4段重複排除（onset強度 0.30 未満除外・確定済み区間の除外と 260ms ガード・同一音高120ms統合・弱いアタックの継続判定）。
- **永続化（`data`, `core/database`）**: Room にプロジェクト・録音アセット（`audio_source` / `denoise_profile` / 無加工フラグ）・検出イベントを保存。DataStore に解析設定を保存。
- **UI（`feature/`, Jetpack Compose）**:
  - SCR-001 ホーム: 新規録音・音声ファイル取り込み・プロジェクト一覧・設定。
  - SCR-002 ライブ採譜: 経過時間 / 音符数 / 入力状態・遅延インジケーター、Compose Canvas の大譜表（ト音・ヘ音、加線、臨時記号、和音の縦積み、自動右スクロール）、録音開始・停止・破棄、最下部のピアノ鍵盤（検出音を紫色で点灯）。`ViewModel` がセッションを保持するため画面回転で録音・採譜状態を失わない。
  - SCR-007 設定: ノイズ除去（自動/オン/オフ）、強さ（弱/標準/強）、ライブ適用、電源ハム除去（自動/50/60/なし）。
  - SCR-003/004 最小受け皿: 保存した録音と暫定音符の一覧（最終解析はPhase 2）。
- **テスト**: DSP（バイクアッド応答・FFT・スペクトル減算・ストリーミング遅延・リサンプラ）、重複排除、Basic Pitch復号、MIDI→五線譜位置のユニットテスト。
- **CI**: GitHub Actions（`.github/workflows/android.yml`）で `testDebugUnitTest` と `assembleDebug` を実行し、Debug APK とテストレポートを Artifact にアップロード。

### Phase 1 の既知の制限

- 実機マイクでの精度・遅延チューニングは未実施（Phase 0 は合成音源）。T-013（実ピアノ＋実機マイク）での再評価が次の課題。
- 録音は画面内のみ（Foreground Service によるバックグラウンド継続はPhase 4）。画面を離れると録音は停止する。
- 停止後の最終解析・リズム/小節の確定・再生・編集・エクスポートは Phase 2 以降。
- 音声ファイルの取り込みは保存とプロジェクト作成まで（自動解析はPhase 2）。

## ビルド

必要なもの: JDK 17、Android SDK（platform 34 / build-tools 34.0.0）。

```bash
# ユニットテスト
./gradlew :app:testDebugUnitTest

# Debug APK（app/build/outputs/apk/debug/app-debug.apk）
./gradlew :app:assembleDebug
```

CI と同じ手順は `.github/workflows/android.yml`。`local.properties` の `sdk.dir` か
`ANDROID_HOME` / `ANDROID_SDK_ROOT` を設定すればローカルでもビルドできる。

### モデルの配置

Basic Pitch の ONNX モデルは **リポジトリに同梱**している（`app/src/main/assets/models/nmp.onnx`、
230,444 bytes、SHA-256 `2c3c1d144bfa61ad236e92e169c13535c880469a12a047d4e73451f2c059a0ec`）。
APK単体・オフラインで実機が動くようにするためで、Apache License 2.0 の条件に従い
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) にライセンス全文と出典を置いている。
差し替える場合は同じパスに置き、`BasicPitchModel.ensureModelFile` が初回起動時に
アプリ領域へコピーする。Phase 0 の Python 検証コードは従来どおり
`phase0-poc/scripts/fetch_basic_pitch_model.py` で取得する（リポジトリには含めない）。

### CI

`.github/workflows/android.yml`（GitHub App の `workflows` 権限がないため Web UI から追加）:

1. JDK 17 と Android SDK をセットアップ
2. モデルの SHA-256 を検証
3. `./gradlew :app:testDebugUnitTest`
4. `./gradlew :app:assembleDebug`
5. Debug APK とテストレポートを Artifact にアップロード

## リポジトリ構成

```
pianosheet/
├── app/                                  # Androidアプリ（Phase 1）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/models/nmp.onnx        # Basic Pitch ONNX（同梱）
│       ├── java/com/yuushilabs/pianosheet/
│       │   ├── MainActivity.kt           # Compose NavHost
│       │   ├── app/                      # Application / DIコンテナ
│       │   ├── core/
│       │   │   ├── audio/                # AudioRecord・WAV保存・音声ストレージ
│       │   │   ├── dsp/                  # FFT・バイクアッド・ノイズ除去・リサンプラ
│       │   │   └── inference/            # ONNX Runtimeアダプタ・Basic Pitch復号
│       │   ├── data/                     # Room・DataStore・Repository実装
│       │   ├── domain/
│       │   │   ├── live/                 # ストリーミング採譜エンジン
│       │   │   ├── model/                # NoteEvent・音高・五線譜位置
│       │   │   └── repository/
│       │   ├── feature/                  # home / live / project / settings
│       │   └── ui/                       # テーマ・大譜表・鍵盤
│       └── res/
│   └── src/test/                         # ユニットテスト
├── docs/                                 # 仕様書・Phase 0レポート
├── shared-specs/                         # NoteEvent JSON Schema
├── test-fixtures/                        # 合成ピアノ音源と正解データ
└── phase0-poc/                           # Phase 0 の検証コード（Python）
```

## Phase 0 の再現

```bash
python3 phase0-poc/scripts/fetch_basic_pitch_model.py   # モデル取得（再配布しない）
pip install -r phase0-poc/requirements.txt
python3 test-fixtures/generate_fixtures.py              # テスト音源の再生成
python3 phase0-poc/run_phase0.py                        # 全実測（約6分）
python3 phase0-poc/run_phase0.py --only denoise          # ノイズ除去の比較だけ
```

## ライセンス

アプリ本体のライセンスは未定。第三者の著作物の扱いは [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) を参照:

- Basic Pitch（Spotify AB）: Apache License 2.0。**モデル重み（nmp.onnx）はApache 2.0の条件でアプリに同梱**。
- ONNX Runtime（Microsoft）: MIT License（Gradle依存）。
- テスト音源: 本リポジトリの合成スクリプトによる生成物。
