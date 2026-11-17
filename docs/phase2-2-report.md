---
title: "PianoSheet v1.4（0.2.2-phase2）実装レポート: 倍音実在チェック"
---

# 何を入れたか

`work/pianosheet_exp/v14_simple.py` の実験（F1 0.869 → 0.944、305 → 257音符）を
アプリの最終解析に忠実に移植した。

| 変更 | ファイル |
|---|---|
| 倍音実在チェック本体 | `app/src/main/java/com/yuushilabs/pianosheet/core/inference/HarmonicRealnessFilter.kt`（新規） |
| `FinalNoteMerger` 統合直後に適用 | `app/src/main/java/com/yuushilabs/pianosheet/domain/final/FinalTranscriber.kt` |
| 単体テスト8件 | `app/src/test/java/com/yuushilabs/pianosheet/core/inference/HarmonicRealnessFilterTest.kt`（新規） |
| 調号と拍子記号の重なり解消 | `app/src/main/java/com/yuushilabs/pianosheet/ui/components/CompletedScoreCanvas.kt` |
| バージョン `0.2.2-phase2` / code 4 | `app/build.gradle.kts` |

# 判定の中身（実験と同じ）

- 対象: MIDI 72（C5）以上の音符。それ未満は従来どおり。
- 波形: **無加工の録音**（リサンプル後 22.05 kHz、ノイズ除去前）。実験と同じ。
  ノイズ除去後の波形は倍音を削っている可能性があるため使わない。
- STFT: 2048点 / hop 256 / 周期ハン窓 / フレーム中心合わせ（librosa `center=True` 相当）。
- 各音符の `[onset, onset+200ms]` のフレームについて、`f0`・`2f0`・`3f0` の ±2 % 帯域の最大振幅を見る。
- `2f0 < 10% × f0` かつ `3f0 < 5% × f0` なら「純音1本」＝下の音の8倍音ゴーストとして削除。
- 削除後は `sequence` を振り直し、以降の楽譜化は削除後の音符列で行う。

コスト: 必要なフレーム（検査対象の音符が使うフレーム）だけを STFT する。
44秒の実録音で **152 ms**（この開発VM・x86 JVM、2コア）。端末でも1秒未満の見込みで、
モデル・重み（nmp.onnx 230KB）は変更なし。

# 実測（ユーザーの実録音 140–184秒）

`work/pianosheet_debug2/audio.wav` の 140–184秒、v1.3 の出力 305音符に対して:

| | 音符数 | MIDI 84以上 | 検査対象（≥C5） | 削除 |
|---|---|---|---|---|
| v1.3 | 305 | 53 | — | — |
| **v1.4（Kotlin 実行）** | **257** | **5** | 129 | **48** |

削除した48音は、実験（Python + librosa 実装）の削除集合と**完全に一致**した
（onset の下3桁の丸めだけが4件で違う）。実装の移植差はない。

# ライブ表示について

`LiveTranscriptionEngine` には入れていない。理由:

- 判定には onset から200msの音声が必要で、ライブは確定を遅らせずに出す設計（遅延100ms級）。
- ライブの音符は暫定表示で、録音停止後の最終解析が全曲を作り直す。ゴーストの除去は
  最終解析の1か所で足りる（ユーザーが見る完成楽譜はこちら）。

最終解析 `FinalTranscriber` のみに適用している。

# 調号と拍子記号の重なり

固定幅（62dp）だった譜表ヘッダを、調号の数から算出するようにした
（`StaffHeader`）。嬰ハ長調（♯7個）ではヘッダが約102dpに広がり、
最後の♯と 6/8 のあいだに約1.3 staff space の空きができる。
ハ長調（調号なし）は従来と同じ約63dp。
