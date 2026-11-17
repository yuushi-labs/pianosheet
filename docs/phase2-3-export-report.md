# Phase 2.3 — MusicXML・MIDI 出力と楽譜ビューワー刷新

対象コミット: `main`（`0.3.0-phase2` / versionCode 5）。依頼は「楽謄ビューワーがおかしいから、とりあえず MusicXML に保存してそれを楽譜にしたり MIDI に変換できるようにしたい」。

## 1. 何が変わったか

| 依頼 | 実装 |
|---|---|
| MusicXML に保存 | `domain/export/MusicXmlExporter`（MusicXML 4.0 `score-partwise`）＋ SAF 保存・FileProvider 共有 |
| MIDI に変換 | `domain/export/MidiExporter`（SMF Format 1・3トラック）＋ SAF 保存・FileProvider 共有 |
| 楽譜ビューワーを直す | 自前 Canvas 描画を廃止し、**生成した MusicXML を OSMD で描く WebView** へ刷新（OSMD は APK に同梱・完全オフライン） |
| 1画面で分かるUI | SCR-004 に「保存 ▾ / 共有 ▾」の2ボタン（MusicXML・MIDI・生録音WAV）と再解析・ズームを集約 |

## 2. MusicXML（`MusicXmlExporter`）

- **1パート・2段（`<staves>2</staves>`）**: 右手＝staff 1＋ト音記号、左手＝staff 2＋ヘ音記号。声部は staff 1 が voice 1、staff 2 が voice 2 で、間に `<backup>` を置く。
- **音価と表記グリッド**: スコアは演奏時刻と表記値を別々に持つ（仕様 §5.3）が、紙面に載るのは表記値だけ。そこで出力時に 16分グリッド（0.25拍）へ丸める（`Options.gridBeats`）。`<duration>` と `<type>` が必ず一致する。
- **表記できない長さ**（例: 次音に押されて 1.25 拍になった音）は、書ける音価へ分割して**タイ**でつなぐ（4分＋16分など）。
- **小節線をまたぐ音**は小節境界で分割し `<tie type="start|stop">` と `<notations><tied>` を対で出す。
- **和音**は同じ打鍵の音を1つの `<chord/>` 群にまとめる。
- **休符**は各声部の空きを長い音価から順に埋める（全・付点全・付点2分・2分・付点4分・4分・付点8分・8分・16分）。結果として **どの小節も `<duration>` 合計＝拍子×divisions に一致**する。
- **調号・拍子・テンポ**は先頭小節の `<attributes>` と `<direction><metronome>`＋`<sound tempo>` に出す。調号は `<fifths>` と `<mode>`。
- 綴りは `PitchSpeller` の letter/octave をそのまま使い、`<alter>` が範囲外のときだけ嬰記号綴りへ退避する。

## 3. MIDI（`MidiExporter`）

- **Format 1 / 3トラック**: トラック0=テンポ・拍子・調号（`FF 51` / `FF 58` / `FF 59`）、トラック1=右手（ch 1）、トラック2=左手（ch 2）。PPQ=480。
- テンポは演奏テンポ（`score.tempoBpm`）をそのままマイクロ秒/4分音符へ。拍子は `beats` と分母の2の冪、調号は `fifths` とモード。
- 同じ音高の連打は、前のノートオフを次のノートオン位置まで切り詰める（ノートオフが次の音を消さない）。
- ベロシティは `note.velocity` を 1〜127 に丸める（0 にしない）。

## 4. 楽譜ビューワー（OSMD in WebView）

- `app/src/main/assets/score/` に **OpenSheetMusicDisplay 2.3.1**（`opensheetmusicdisplay.min.js`, 1.47 MB, BSD-3-Clause）を同梱。`index.html` は相対パスだけで読み、外部URLを参照しない。WebView 側も `blockNetworkLoads = true`・`allowFileAccess = false` で、ネットワークに出る手段を塞いでいる（assets は常に読める）。
- `ScoreWebView`（Compose）が `PianoSheet.loadScore(xml, {dark, zoom})` を呼び、以降は**変化した時だけ** `setTheme` / `setZoom` / `setPosition` を送る。再描画や回転でページが作り直されたら `onPageFinished` から全状態を送り直す。
- **配色**: ダーク時は app 背景 `#141218` の上に、少し明るい譜面カード `#1D1A23`（枠 `#322C3D`）を置き、五線・音符・記号は `defaultColorMusic: #EFEAF5` の明るいインク。ライト時は白カードに `#1C1B1F`。どちらも背景との境界が分かるよう枠と影を付ける。
- **再生連動**: 再生位置（小節・拍）に合わせて (1) 現在の小節を半透明の帯で示し、(2) 拍位置に縦のプレイヘッドを置き、(3) 再生中は画面外に出ないよう自動スクロールする。拍位置は OSMD の `staffEntries[].relInMeasureTimestamp` から最寄りの符頭の X を取るので、等間隔近似ではなく実際の符頭に合う。
- **ズーム**は OSMD の `Zoom` を 70〜180% で変更（画面右下の −／＋）。

## 5. テスト

- ユニットテスト（JVM）:
  - `MusicXmlExporterTest`（12件）: XMLとして再パース、ヘッダ（staves=2・G/Fクレフ・調号・拍子・テンポ）、**全小節で staff 1/2 の duration 合計＝拍子×divisions**、和音 `<chord/>`、小節をまたぐタイ、休符での穴埋め、付点、臨時記号・オクターブ、XMLエスケープ、空スコア、グリッド丸め。
  - `MidiExporterTest`（7件）: `MThd`/`MTrk` の長さ整合、format 1・3トラック・PPQ、テンポ/拍子/調号メタイベント、NoteOn/NoteOff の対応とチャンネル、同音連打の切り詰め、ベロシティ範囲、デルタ時間が非負。
  - `ExportRealTakeTest`（3件）: 実録音 fixture（Für Elise / Gymnopédie / 本人のテイク）を解析→出力し、XML再パース＋小節のduration合計、MIDIのヘッダとサイズを検証。
- CI: `Android CI` で全 **177テスト**が合格し、Debug APK をビルド（run は §7）。
- **外部リーダーでの確認**（Python 3.12 + music21 10.5.2）: 本人テイクから生成した MusicXML（11小節・223符頭・63和音・タイ10組）を `music21.converter.parse()` で読み、2パート・11小節・C# major・4/4・♩=102 として解釈され、小節長の誤差0、music21 から MIDI へ再出力できることを確認。
- **OSMD での実描画確認**: OSMD 公式デモ（2.3.1）に `docs/samples/sample.musicxml` を読み込ませ、大譜表・調号2♯・和音・タイ・休符・終止線が正しく描かれること、ダークモードで白インクに切り替わることを目視確認。
- **ビューワーJSの動作確認**: `viewer.js` をダミーDOM＋ダミーOSMD上で実行し、テーマ切替・描画通知・小節帯/プレイヘッドの座標・自動スクロール（follow時のみ）・clearPosition・ズーム・status の15項目を確認。

## 6. 既知の制限

- ビューワーのプレイヘッドは「小節＋拍」単位。音符単位のきめ細かい追従はしない。
- 小節内の休符分割は「長い音価優先」。4/4 の中央での分割（拍の頭に休符を揃える）まではしない。
- 左手が和音で動く区間は voice 2 の1声にまとめるため、声部が2つに割れる書法（内声）は表さない。
- 16分より細かい演奏（32分など）は表記グリッドで丸められる。演奏ニュアンスは MIDI 側に残る（MIDI は丸めない）。
- OSMD の描画は WebView 依存。万一描画に失敗しても画面に日本語のエラーが出るだけで、録音・解析・書き出しは影響を受けない。

## 7. 確認手順（再現用）

```bash
# APK
gh run list -R yuushi-labs/pianosheet -L 1

# MusicXML/MIDI の生成（JVMテスト。出力はテストレポートの Standard output に残る）
./gradlew :app:testDebugUnitTest --tests '*ExportRealTakeTest*'

# 生成物を music21 で読む
python3 -c "from music21 import converter; s=converter.parse('user-take.musicxml'); print(s.parts)"
```
