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
| Phase 2: 最終解析・完成楽譜・再生・生録音WAV | **実装済み** — 下記「Phase 2 の実装範囲」 |
| Phase 3 以降（編集・Undo/Redo・PDF/MIDI/MusicXML） | 未着手 |

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

### Phase 2 の実装範囲

- **採譜候補の物理チェック（`NoteCandidateFilter`）**: 減衰尾の再報告（onset < 0.20）、80ms未満のクリック、C6以上の弱い検出、同時に鳴る強い下の音のオクターブ/12度上の倍音ゴーストを落とす。窓統合後にも `FinalNoteMerger.suppressGhosts()` で同じ検査を行う。詳細は [docs/phase2-1-report.md](docs/phase2-1-report.md)。
- **最終解析（`domain/final`）**: 保存済みの無加工 WAV を読み直し（`WavFileReader`）、22.05kHz へ統一（`AudioResampler`）→ 設定に応じたノイズ除去（既定 standard＋25Hz ハイパス、ハムは自動判定可）→ Basic Pitch ONNX で全体を 2秒窓・1秒ホップで再解析 → 窓間の重複を統合（`FinalNoteMerger`）。
  - `TempoEstimator`: 発音間隔とグリッド整合度から BPM を推定（倍/半分テンポの曖昧さは 70〜150 BPM を優先して解決）。
  - `TimeSignatureEstimator`: 拍ごとのアクセント比から 4/4・3/4・2/4・6/8 を推定（6/8 は中間アクセントで 3/4 と区別）。
  - `KeyEstimator`: Krumhansl–Schmuckler 相関で調性・調号（-7〜+7）を推定。
  - `RhythmQuantizer`: 16分グリッドへの量子化（強さ 0〜1）と「演奏時刻優先」モードの両対応。付点・全/2分/4分/8分/16分へ整理。
  - `StaffAssigner`: 音域分布の「隙間」で分割点を決め、和音は両手に分け、分割点付近は声部の連続性で左右を決める。
  - `PitchSpeller`: 調号に沿った綴り（F dur の B♭ など）と、小節内で臨時記号を持ち越す処理。
  - 小節分割・休符生成、ライブ検出との照合（一致/解析のみ/ライブのみの件数を保存、仕様 §5.7）。
- **SCR-003 解析中**: 「準備中...」「音符を書き起こしています...（n/N）」「仕上げ中...」の3段階と実進捗率。中止しても録音は残る。
- **SCR-004 完成楽譜**: テンポ・拍子・調号チップ、複数段に折り返す大譜表（`ScoreLayoutEngine` による版面計算）、小節線・小節番号・拍子/調号・音符（全〜16分＋付点＋符尾）・休符・加線・臨時記号、再生中の小節ハイライトとプレイヘッド。再解析ボタン。
- **再生（`playback`）**: Score は `PianoSynth`（6倍音＋ADSR の加算合成）をオフラインで PCM 化して `AudioTrack` で再生（速度変更は時間軸のみ再レンダリングし音高を保つ）。Original は `MediaPlayer` で無加工 WAV を再生。0.5〜1.5倍速、シーク、楽譜/元音声の切り替え。
- **生録音WAVの保存・共有（`core/export`）**: Storage Access Framework（`CreateDocument("audio/wav")`）で任意フォルダへ保存、`FileProvider` 経由の共有シート。`res/xml/file_paths.xml` で公開範囲を `files/recordings` と `cache/exports` に限定。
- **録音開始直後の誤検出対策（`core/dsp/StartupGuard`）**: 解析コピーの先頭 250ms を線形フェードイン＋DC 除去、ゼロ埋め窓の境界に 30ms フェード、開始 250ms 以内は「強い打鍵（onset強度0.70以上かつ信頼度0.45以上）」以外の onset を抑制。ライブ・最終解析の両方に適用（録音 WAV は無加工のまま）。
- **ライブ譜面のレイアウト修正（`domain/live/LiveStaffLayout`）**: 同時発音は同じX、打鍵間隔は最低 15dp を確保、ト音/ヘ音のヘッダーは左固定で譜面のみ横スクロール、自動追従はアニメーションではなく即時スクロールで最新音を画面の68%位置に置く（260音・3分でも破綻しないことをテスト）。
- **DB**: Room v3（`scores` テーブル追加）。`MIGRATION_1_2` / `MIGRATION_2_3` を実装し、`fallbackToDestructiveMigration` を廃止。Phase 1 の録音・暫定音符はそのまま残る。
- **暫定音符一覧の並び順**: `onsetMs` 昇順 → `midiPitch` 昇順（5.17 の後に 5.08 が出る問題を修正）。
- **テスト**: 121件（起動ガード、ライブ譜面レイアウト、テンポ/拍子/調性推定、量子化・小節・休符・左右振り分け・臨時記号、スコアのエンコード往復、ピアノシンセの波形/音高、リサンプラ、WAV 読み書き、最終解析の通し、マイグレーション）。

### 使い方（Phase 2）

1. ホームから「新しく録音」→ 録音開始 → 停止して保存（または「音声ファイルをアップロード」）。
2. 自動で解析中画面へ進む。3段階の進捗が出て、完了すると完成楽譜が開く。
3. 完成楽譜では「楽譜 (Score) / 元音声 (Original)」を切り替えて再生。速度は 0.5〜1.5倍。再生位置に合わせて現在の小節が色づく。
4. 右上の更新ボタンで再解析（設定のノイズ除去/ハムを変えたあとに有効）。
5. 「生録音(WAV)を保存」で任意のフォルダへ、「共有」で共有シートへ。プロジェクト詳細画面からも同じ操作ができる。

### Phase 2 の既知の制限

- 楽譜編集（音符の追加・削除・移調）と Undo/Redo は Phase 3。
- PDF / MIDI / MusicXML の書き出しは Phase 3（本フェーズは生録音 WAV の保存・共有まで）。
- 調性・拍子の手動変更 UI は未実装（推定値を表示するのみ）。
- 最終解析は端末内の逐次推論のため、長い録音では時間がかかる（3分で約180窓）。

### Phase 1 の既知の制限

- 実機マイクでの精度・遅延チューニングは未実施（Phase 0 は合成音源）。T-013（実ピアノ＋実機マイク）での再評価が次の課題。
- 録音は画面内のみ（Foreground Service によるバックグラウンド継続はPhase 4）。画面を離れると録音は停止する。
- 停止後の最終解析・リズム/小節の確定・再生は Phase 2 で実装済み（上記）。編集・PDF/MIDI/MusicXML は Phase 3。
- 音声ファイルの取り込みは Phase 2 から取り込み直後に自動解析する。

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
