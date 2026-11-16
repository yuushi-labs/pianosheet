# 実録音の採譜 fixture

`RealTakeAnalysisTest` が読む、実ピアノ録音の ONNX 推論結果（窓ごとの採譜候補）。

| ファイル | 音源 | ライセンス | 長さ |
|---|---|---|---|
| `fur-elise.csv` | [For Elise (Für Elise) – Student Performance Zhe, Zhang](https://commons.wikimedia.org/wiki/File:F%C3%BCr_Elise_-Student_Performance_Zhe%2CZhang.ogg)（ベートーヴェン「エリーゼのために」学生演奏） | CC BY-SA 4.0 | 45秒 |
| `gymnopedie.csv` | [Gymnopedie No. 1 (ISRC USUAN1100787)](https://commons.wikimedia.org/wiki/File:Gymnopedie_No._1_(ISRC_USUAN1100787).mp3)（サティ「ジムノペディ第1番」） | CC BY-SA 4.0 | 45秒 |
| `user-take.csv` | ユーザー本人の録音（デバッグ動画 5–30秒の音声を22.05kHzモノラル化） | 本人の録音 | 25秒 |

音声そのものはリポジトリに含めない（ライセンスとサイズのため）。CSV は

```text
window_start,onset,end,pitch,confidence,onset_strength
```

の形式で、`phase0-poc/scripts/verify_real_piece.py` が生成する:

```bash
python3 phase0-poc/scripts/verify_real_piece.py --wav <22.05kHz mono wav> \
    --name fur-elise --out app/src/test/resources/real-takes/fur-elise.csv
```

このスクリプトは 22.05kHz モノラル化 → v1.3 ノイズ除去（25Hz ハイパス＋スペクトル減算＋
定常トーン抑圧）→ 2秒窓・1秒ホップで ONNX 推論 → Basic Pitch 復号、までを行う
（Android 側は `onnxruntime-android` のため、この機械では推論だけ Python で実行する）。
