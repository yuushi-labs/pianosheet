# PianoSheet

Android専用のピアノ音声リアルタイム採譜・楽譜生成アプリ（開発中）。

ピアノの演奏をマイクまたは音声ファイルから取り込み、**録音中に音符を五線譜へ順次表示**し、録音終了後に全体を再解析して編集可能な楽譜を生成する。iOS・Web版は対象外（Kotlin + Jetpack Compose のネイティブアプリ）。

- 仕様書: [docs/PianoSheet_仕様書_v1.3.md](docs/PianoSheet_仕様書_v1.3.md)（v1.2 は [docs/PianoSheet_仕様書_v1.2.md](docs/PianoSheet_仕様書_v1.2.md)）
- Phase 0 技術検証レポート: [docs/phase0-report.md](docs/phase0-report.md)
- PianoVAM 定量評価（Phase 2.4）: [docs/phase2-4-pianovam-report.md](docs/phase2-4-pianovam-report.md) / 測定コード [tools/pianovam-bench](tools/pianovam-bench/README.md)
- PTI-ONNX 組み込み・チューニング（Phase 3）: [docs/phase3-pti-report.md](docs/phase3-pti-report.md)
- 共通ノートイベント仕様: [shared-specs/note-event.schema.json](shared-specs/note-event.schema.json)
- サードパーティ・ライセンス: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## 現在の状態

| フェーズ | 状態 |
|---|---|
| Phase 0: 技術検証（リアルタイム1〜2秒・採譜品質） | **完了** — 実測結果は [docs/phase0-report.md](docs/phase0-report.md) |
| Phase 1: Androidリアルタイム採譜MVP | **実装済み（実機チューニング待ち）** — 下記「Phase 1 の実装範囲」 |
| Phase 2: 最終解析・完成楽譜・再生・生録音WAV | **実装済み** — 下記「Phase 2 の実装範囲」 |
| Phase 2.3: MusicXML・MIDI出力 / OSMD楽譜ビューワー | **実装済み** — [docs/phase2-3-export-report.md](docs/phase2-3-export-report.md) |
| Phase 2.4: PianoVAM 定量評価・採譜/楽譜品質の改善（`0.3.1-phase2`） | **実装済み** — [docs/phase2-4-pianovam-report.md](docs/phase2-4-pianovam-report.md) |
| Phase 3: ピアノ専用モデル PTI-ONNX の組み込み（`0.4.0-phase2`） | **実装済み** — [docs/phase3-pti-report.md](docs/phase3-pti-report.md) |
| Phase 3.1: 録音・書き出しの共有フォルダ保存と再インポート（`0.4.1-phase2`） | **実装済み** — [docs/shared-storage-report.md](docs/shared-storage-report.md) |
| Phase 3.2: 楽譜の先頭の無音除去と弱起/裏拍対応（`0.4.3-phase2`） | **実装済み** — [docs/score-start-report.md](docs/score-start-report.md) |
| Phase 3.3: ノイズ除去のマイルド化と高音ノイズ対策（`0.4.4-phase2`） | **実装済み** — [docs/denoise-0.4.4-report.md](docs/denoise-0.4.4-report.md) |
| Phase 3.4: アプリ内自動アップデートと Release 自動公開（`0.4.5-phase2`） | **実装済み** — [docs/auto-update-0.4.5-report.md](docs/auto-update-0.4.5-report.md) |
| Phase 4 以降（編集・Undo/Redo・PDF） | 未着手 |

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
- **CI**: GitHub Actions（`.github/workflows/android.yml`）で `testDebugUnitTest` と `assembleDebug` を実行し、Debug APK とテストレポートを Artifact にアップロード。`main` への push ではビルド後に `v<versionName>` の GitHub Release を作り、APK を添付する（0.4.5〜）。

### Phase 2 の実装範囲

- **採譜候補の物理チェック（`NoteCandidateFilter`）**: 減衰尾の再報告（onset < 0.30）、80ms未満のクリック、C6以上の弱い検出、同時に鳴る強い下の音のオクターブ/12度上の倍音ゴーストを落とす。窓統合後にも `FinalNoteMerger.suppressGhosts()` で同じ検査を行う。詳細は [docs/phase2-1-report.md](docs/phase2-1-report.md)。
- **最終解析（`domain/final`）**: 保存済みの無加工 WAV を読み直し（`WavFileReader`）、22.05kHz へ統一（`AudioResampler`）→ 設定に応じたノイズ除去（既定 standard＋25Hz ハイパス、ハムは自動判定可）→ Basic Pitch ONNX で全体を 2秒窓・1秒ホップで再解析 → 窓間の重複を統合（`FinalNoteMerger`）。復号は onset 0.55 / frame 0.30 / 最小音符長 6 フレーム、120ms 未満の短い音は onset 強度 0.45 以上を要求（Phase 2.4 の実測で決定）。
  `0.4.0-phase2` 以降の既定エンジンは PTI-ONNX（下記 Phase 3）で、この Basic Pitch 経路はフォールバックとして残る。
  - `TempoEstimator`: 発音間隔とグリッド整合度から BPM を推定（倍/半分テンポの曖昧さは 70〜150 BPM を優先して解決）。
  - `TimeSignatureEstimator`: 各拍子候補を**最良の位相**で採点して 4/4・3/4・2/4・6/8 を推定し、下拍の位相（アウフタクト）も返す。アクセントは低音を重く数え、書きやすい拍子（4/4・3/4）を優先。6/8 は中間アクセントで 3/4 と区別。
  - 小節は推定した下拍位相に置き、最初の下拍より前は**アウフタクト**（短い第1小節、MusicXML では `implicit="yes"`）として書く。
  - `KeyEstimator`: Krumhansl–Schmuckler 相関で調性・調号（-7〜+7）を推定。7♯/7♭ は臨時記号が減る異名同音（例: 嬰ハ長調 → 変ニ長調）へ寄せる。
  - `RhythmQuantizer`: 16分グリッドへの量子化（強さ 0〜1）と「演奏時刻優先」モードの両対応。付点・全/2分/4分/8分/16分へ整理。
  - `StaffAssigner`: 音域分布の「隙間」で分割点を決め、和音は両手に分け、分割点付近は声部の連続性で左右を決める。
  - `PitchSpeller`: 調号に沿った綴り（F dur の B♭ など）と、小節内で臨時記号を持ち越す処理。
  - 小節分割・休符生成、ライブ検出との照合（一致/解析のみ/ライブのみの件数を保存、仕様 §5.7）。
- **SCR-003 解析中**: 「準備中...」「音符を書き起こしています...（n/N）」「仕上げ中...」の3段階と実進捗率。中止しても録音は残る。
- **SCR-004 完成楽譜**: テンポ・拍子・調号チップ、**生成した MusicXML を同梱の OpenSheetMusicDisplay（2.3.1）で描く WebView ビューワー**（完全オフライン、ダーク/ライト両対応、70〜180% ズーム）。再生位置の小節帯＋プレイヘッド＋自動スクロール、保存/共有メニュー（MusicXML・MIDI・生録音WAV）、再解析。
- **再生（`playback`）**: Score は `PianoSynth`（6倍音＋ADSR の加算合成）をオフラインで PCM 化して `AudioTrack` で再生（速度変更は時間軸のみ再レンダリングし音高を保つ）。Original は `MediaPlayer` で無加工 WAV を再生。0.5〜1.5倍速、シーク、楽譜/元音声の切り替え。
- **MusicXML・MIDI の出力（`domain/export`, `core/export`）**: `MusicXmlExporter`（MusicXML 4.0・2段1パート・和音/タイ/付点/休符/調号/テンポ）と `MidiExporter`（SMF Format 1・テンポ/拍子/調号トラック＋右手/左手トラック、PPQ480）。どちらも SAF 保存と `FileProvider` 共有に対応。詳細は [docs/phase2-3-export-report.md](docs/phase2-3-export-report.md)。
- **生録音WAVの保存・共有（`core/export`）**: Storage Access Framework（`CreateDocument("audio/wav")`）で任意フォルダへ保存、`FileProvider` 経由の共有シート。`res/xml/file_paths.xml` で公開範囲を `files/recordings` と `cache/exports` に限定。
- **入力監視（`core/audio/InputMonitor`）**: 100ms ごとの RMS/ピーク（dBFS）、クリップ率、無音継続時間を数え、録音画面に「音が割れています／入力が小さいです／音が届いていません」を出す。解析コピーの音量正規化は PianoVAM で効果が無かった（−34 dBFS でも F1 差 <0.001）ため行わない。
- **録音開始直後の誤検出対策（`core/dsp/StartupGuard`）**: 解析コピーの先頭 250ms を線形フェードイン＋DC 除去、ゼロ埋め窓の境界に 30ms フェード、開始 250ms 以内は「強い打鍵（onset強度0.70以上かつ信頼度0.45以上）」以外の onset を抑制。ライブ・最終解析の両方に適用（録音 WAV は無加工のまま）。
- **ライブ譜面のレイアウト修正（`domain/live/LiveStaffLayout`）**: 同時発音は同じX、打鍵間隔は最低 15dp を確保、ト音/ヘ音のヘッダーは左固定で譜面のみ横スクロール、自動追従はアニメーションではなく即時スクロールで最新音を画面の68%位置に置く（260音・3分でも破綻しないことをテスト）。
- **DB**: Room v3（`scores` テーブル追加）。`MIGRATION_1_2` / `MIGRATION_2_3` を実装し、`fallbackToDestructiveMigration` を廃止。Phase 1 の録音・暫定音符はそのまま残る。
- **暫定音符一覧の並び順**: `onsetMs` 昇順 → `midiPitch` 昇順（5.17 の後に 5.08 が出る問題を修正）。
- **テスト**: 237件（うち PianoVAM ベンチ1件は環境変数 `PIANOSHEET_PIANOVAM_DIR` 指定時のみ）（起動ガード、ライブ譜面レイアウト、テンポ/拍子/調性推定、量子化・小節・休符・左右振り分け・臨時記号、スコアのエンコード往復、ピアノシンセの波形/音高、リサンプラ、WAV 読み書き、最終解析の通し、マイグレーション）。

### 使い方（Phase 2）

1. ホームから「新しく録音」→ 録音開始 → 停止して保存（または「音声ファイルをアップロード」）。
2. 自動で解析中画面へ進む。3段階の進捗が出て、完了すると完成楽譜が開く。
3. 完成楽譜では「楽譜 (Score) / 元音声 (Original)」を切り替えて再生。速度は 0.5〜1.5倍。再生位置に合わせて現在の小節が色づく。
4. 右上の更新ボタンで再解析（設定のノイズ除去/ハムを変えたあとに有効）。
5. 「生録音(WAV)を保存」で任意のフォルダへ、「共有」で共有シートへ。プロジェクト詳細画面からも同じ操作ができる。
6. 録音は停止と同時に `Music/PianoSheet/` にも自動保存される（権限不要・アプリを入れ直しても残る）。アプリを入れ直したあとは、ホームの「このフォルダのWAVを読み込んで採譜」からその WAV を選ぶと、そのまま解析できる。

### Phase 3 の実装範囲（PTI-ONNX の組み込み）

**ライブ＝Basic Pitch / 最終解析＝PTI-ONNX のハイブリッド構成**（`0.4.0-phase2`）。

- **最終解析エンジン（`core/inference/Pti*`）**: 録音停止後の本解析を、ピアノ専用モデル PTI
  （`piano-notes-v1.onnx`、94.1 MB、CC BY 4.0）で行う。16 kHz へリサンプルし、10秒窓・5秒ホップで
  推論して各窓の中央50%を継ぎ足し、`RegressionPostProcessor` の Kotlin 移植（100 fps）で音符化する。
  PianoVAM 6曲×40秒で **micro F1 0.793 → 0.957、和音一致 0.526 → 0.911、同音重複 59 → 2**。
  ライブは従来どおり Basic Pitch（PTI は10秒で1.09秒かかるため）。
- **後処理のチューニング**: 閾値・ピーク近傍を69通り試して**公式値のままが最良**と確認。
  代わりに「キー活動がピークの40%未満に50ms連続で落ちた位置まで終端を詰める」減衰トリムを追加し、
  音符の終わりの誤差を中央値 **+102 → +58 ms**、p95 **607 → 508 ms** に改善（F1・和音一致は不変）。
- **フォールバック**: モデルの読み込み失敗・端末の空き RAM 不足（1.4 GB 未満）・`OutOfMemoryError`
  では Basic Pitch の最終解析へ自動で切り替え、解析中画面と結果に「どちらのエンジンで解析したか」と
  理由を出す。
- **進捗**: SCR-003 は「モデル読み込み → 区間推論（n/N、1区間あたり約1.1秒）→ 楽譜生成」を実測どおりに出す。
- **メモリ**: 94MB のセッションは解析中だけ持ち（解析開始時に生成・終了時に解放）、常駐による
  約1GBの消費を避ける。バックグラウンドで殺されやすい端末でも解析後に解放される。
- **ノイズ除去**: ノイズ下では Denoiser「弱」が最も F1 を守る（白12dB 0.921 → 0.942）が、
  クリーン音源では和音一致を落とすため、最終解析の既定は現行の「標準」を維持。
- **サイズ**: APK 192 MB（183 MiB、`piano-notes-v1.onnx` は無圧縮で格納）。Play 配布する場合は
  ベース APK 上限 200 MB に近いので Asset Delivery 化が必要。
- **モデルの取得**: 94.1 MB の重みはリポジトリに含めない（`.gitignore`）。`./gradlew` の
  `fetchPtiModel` タスク（`preBuild` に接続）が初回ビルド時に Hugging Face から取得し、
  SHA-256 を検証してから `assets/models/` へ置く。手動なら `python3 tools/fetch_pti_model.py`。
  APK には同梱されるので、インストール後の端末はオフラインで動く。
- **テスト**: 実モデル出力10秒をフィクスチャにして、Kotlin の復号が Python（＝公式実装と完全一致）と
  音符単位で一致することを検証。窓計画・ステッチ・進捗・キャンセル・フォールバックも含めて **237件**。

### 共有フォルダ保存の実装範囲（0.4.1）

録音をアプリ専用領域だけでなく、**アンインストールしても残る公開フォルダ**にも保存する。

- **録音（`core/storage/SharedAudioStorage` + `AndroidSharedMediaGateway`）**: 停止時に無加工のまま
  `MediaStore.Audio` へ `Music/PianoSheet/rec-YYYYMMDD-HHmmss-<projectId 8桁>.wav` として書き出す
  （`RELATIVE_PATH=Music/PianoSheet`、`IS_PENDING` で作り終わってから公開）。権限ダイアログは出ない。
  書き込み後に `DISPLAY_NAME` / `RELATIVE_PATH` を読み直すので、名前が ` (1)` になっても画面のパスは実際と一致する。
- **フォールバック**: 録音は `MediaStore.Audio` → `MediaStore.Files`（同じフォルダ）、楽譜は本来の MIME →
  `application/octet-stream` の2段。どちらも失敗しても **アプリ内の作業コピーはそのまま**残る
  （解析・SAF 保存・共有は従来どおり可能）。詳細画面の「共有フォルダ（Music/PianoSheet）に保存」で後からやり直せる。
- **書き出し楽譜**: 完成楽譜の「保存」メニューに「MusicXML / MIDI を Documents/PianoSheet へ」を追加。
  SAF でフォルダを選ばず、ワンタップで `/storage/emulated/0/Documents/PianoSheet/` に入る。
- **再インストール後**: ホームの「このフォルダのWAVを読み込んで採譜」が `OpenDocument` を
  `EXTRA_INITIAL_URI=primary:Music/PianoSheet` で開き、選んだ WAV をヘッダー確認のうえ取り込んで即解析する
  （`READ_MEDIA_AUDIO` などの追加権限は取らない）。
- **表示**: ホームに保存先のフルパス、解析中画面に「録音はアプリの外にも保存されています: /storage/...」、
  プロジェクト詳細に共有フォルダとアプリ内コピーのフルパスを出す。
- **DB**: Room v4。`recording_assets.sharedPath` を追加（`MIGRATION_3_4`）。既存の録音は NULL のままで、
  詳細画面から書き直せる。
- **テスト**: `SharedAudioStorageTest` 19件（ファイル名・フルパス・2段フォールバック・失敗時に録音が残ること・
  書き出し・SAF 取り込み・WAV 判定）。詳細は [docs/shared-storage-report.md](docs/shared-storage-report.md)。

### 楽譜の先頭の扱い（0.4.3）

- **録音開始〜最初の音までの無音は楽譜に出ない**: `core/dsp/SilenceHead` が録音自身の大きさに合わせて「鳴り始め」を測り、
  `ScoreBuilder` はその 0.15 s より前の音符（モデルが無音を音符として報告したもの）を落とす。
  演奏前のタップのような孤立した物音も、4 拍以上の間が空いていれば落ちる（`trimLeadingNoise`）。
- **最初の音が beat 0**: 先頭の小節は必ず最初の音から始まる。弱起は「最初の音から最初の強拍まで」の長さの小節として
  `implicit="yes"` で書き出し、そのあとに空の小節が入ることはない。
- **裏拍・アウフタクト**: 8分の裏から始まる演奏は半拍の弱起小節、4拍目から始まる演奏は 1 拍の弱起小節になる。
- **ライブ楽譜**も録音開始ではなく最初の音を左端に描く。
- 詳細と検証: [docs/score-start-report.md](docs/score-start-report.md)

### ノイズ除去の強さ（`0.4.4-phase2`）

- 実機で録った4本を比べた結果、0.4.3 の「弱」が一番演奏を正確に捉えていたため、
  **0.4.3 の「弱」が新しい「標準」**（既定）。全体を1段マイルドにした。
- マイルドにしたぶん、高音ノイズが出る **1.2 kHz 以上だけは今までどおり強く** 削る
  （`DenoiseConfig.highBandBoost`）。
- 残っていた高音の誤検出（オクターブゴースト・共振の線）は
  `HarmonicRealnessFilter.PTI` が **C5 以上**で、しかも**下の帯域のエネルギーから**
  判定して落とす。40 s の録音で C6 以上のノートが 85 → 41。
- 詳細と検証: [docs/denoise-0.4.4-report.md](docs/denoise-0.4.4-report.md)

### アプリ内アップデート（`0.4.5-phase2`）

- 起動時に `releases/latest` を1回だけ静かに確認し、新しい版があればホームにカードと
  更新内容つきダイアログ（今すぐ更新 / 後で）を出す。失敗しても何も出さない。
- ダウンロードは `cache/updates/` に保存し、進捗（%・MB）とキャンセル付き。完了時に
  サイズと ZIP ヘッダを確認してから、`FileProvider` 経由でシステムインストーラーに渡す。
  初回は「この提供元のアプリを許可」を案内し、戻ったらそのままインストールへ進む。
- **設定 → アプリ**に現在のバージョンと「アップデートを確認」ボタン（手動確認・即更新）。
- 署名は `keystore/pianosheet-debug.keystore` に固定（CI とローカルで同じ鍵）。Android は
  同じ鍵の APK しか上書きインストールできないため、この鍵は作り直さない。
- `main` へ push すると CI が `v<versionName>` の Release を自動作成するので、次の版からは
  push だけで端末に更新が届く。詳細: [docs/auto-update-0.4.5-report.md](docs/auto-update-0.4.5-report.md)

### Phase 2 の既知の制限

- 楽譜編集（音符の追加・削除・移調）と Undo/Redo は Phase 4。
- PDF の書き出しは Phase 4（MusicXML・MIDI は Phase 2.3 で実装済み）。
- 調性・拍子の手動変更 UI は未実装（推定値を表示するのみ）。
- 最終解析（PTI-ONNX）は端末内推論で、3分の録音に約40秒かかる（検証機・2コア）。
  空きRAMが1.4GB未満の端末やメモリ不足時は Basic Pitch に自動で切り替わる。
- 演奏中にペダルを踏んだ場合、PTI の音符枝はペダル情報を持たないため音の長さは鍵の解放で決まる。

### Phase 1 の既知の制限

- 実機マイクでの精度・遅延チューニングは未実施（Phase 0 は合成音源）。T-013（実ピアノ＋実機マイク）での再評価が次の課題。
- 録音は画面内のみ（Foreground Service によるバックグラウンド継続はPhase 4）。画面を離れると録音は停止する。
- 停止後の最終解析・リズム/小節の確定・再生は Phase 2 で実装済み（上記）。編集・PDF は Phase 4、MusicXML/MIDI は Phase 2.3 で実装済み。
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
