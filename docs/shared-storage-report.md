# 録音・書き出しの共有フォルダ保存（0.4.1-phase2）

要望:「録音したファイルとかはアプリ内じゃなくて共通のフォルダにいれてほしい。場所はどこでもいいけどアプリ入れ直してもきえないようにしたいね」

これまでの録音 WAV は `files/recordings/`（アプリ専用領域）だけにあり、アンインストールで消えていた。
0.4.1 では**録音を止めた時点で、端末の公開フォルダ `Music/PianoSheet/` にも無加工のまま自動保存**し、
アプリを入れ直しても残るようにした。あわせて、その WAV を読み直して採譜し直す導線をホームに置いた。

## 保存先

| 種類 | 場所（フルパス） | 書き込み |
|---|---|---|
| 録音 WAV（無加工） | `/storage/emulated/0/Music/PianoSheet/rec-YYYYMMDD-HHmmss-<projectId 8桁>.wav` | `MediaStore.Audio.Media`（`IS_PENDING` → 書き込み → 公開） |
| 書き出し楽譜 MusicXML / MIDI | `/storage/emulated/0/Documents/PianoSheet/<タイトル>-YYYYMMDD-HHmm.xml` | `MediaStore.Files`（`Documents/`） |
| アプリ内の作業コピー | `/data/user/0/com.yuushilabs.pianosheet/files/recordings/rec-<projectId>.wav` | 従来どおり（解析はこちらを読む） |

- 権限は**不要**。`targetSdk 34 / minSdk 29` では、自分が作るメディア行の追加に `WRITE_EXTERNAL_STORAGE` も
  `READ_MEDIA_AUDIO` も要らない（他アプリのファイルを一覧するときだけ必要）。
- 行は `IS_PENDING=1` で作り、コピーが終わってから `0` にして公開する。途中で失敗した行は削除するので、
  半端な WAV が音楽フォルダに見えることはない。
- ファイル名は日時＋プロジェクトIDの先頭8文字。同じ秒に2テイク録っても衝突せず、名前順＝時刻順になる。
  名前が既にある場合は MediaStore が ` (1)` を付けるので、**書き込み後に DISPLAY_NAME / RELATIVE_PATH を
  読み直して**画面に出すパスを確定する。

## 失敗したときの安全策

`SharedAudioStorage` は「公開フォルダ → 別のコレクション」の2段で書く。

1. 録音: `MediaStore.Audio`（`Music/PianoSheet`）→ 失敗したら `MediaStore.Files`（同じ `Music/PianoSheet`、`audio/wav`）
2. 楽譜: `MediaStore.Files`（`Documents/PianoSheet`、本来の MIME）→ 失敗したら同じ場所・同じ名前で `application/octet-stream`

どちらも失敗しても、**アプリ内の作業コピーには一切触らない**。録音はそのまま残り、解析・SAF 保存・共有は
従来どおりできる。詳細画面には「共有フォルダに保存」ボタンが出て、あとから書き直せる。

## 再インストール後

アンインストールで消えるのはアプリ専用領域（作業コピー）と Room の DB。`Music/PianoSheet/` の WAV は
メディア行として残るが、再インストール後は**他アプリ所有の行**になるため、アプリから直接 `File` で開くことも
一覧することもできない（`READ_MEDIA_AUDIO` を取れば一覧はできるが、まずは権限ダイアログを出さない形にした）。

そこで、ホームに「**このフォルダのWAVを読み込んで採譜**」を置いた。

- SAF の `OpenDocument` を `EXTRA_INITIAL_URI = primary:Music/PianoSheet` で開くので、ピッカーは最初から
  そのフォルダを表示する。選んだ瞬間に SAF が読み取り権限を渡すので、追加の権限は要らない。
- 選ばれた WAV はヘッダー（`RIFF`/`WAVE`）を確認してから `files/recordings/import-<id8>.wav` に取り込み、
  既存のアップロード経路と同じくそのまま最終解析へ進む。WAV 以外を選んだ場合は従来の取り込みにフォールバックし、
  「解析できるのは WAV のみ」と案内する。

## 画面

- **ホーム**: 「録音の保存先」カード（`/storage/emulated/0/Music/PianoSheet` と再インポートボタン）。
- **録音停止後**: 共有コピーを書いてから DB に保存するので、解析中画面に
  「録音はアプリの外にも保存されています: /storage/.../rec-....wav」が出る。
- **プロジェクト詳細**: 共有フォルダのフルパス（未保存なら「まだ保存していません」＋保存ボタン）と、
  アプリ内コピーのフルパスを並べて表示。
- **完成楽譜**: 「保存」メニューに「MusicXML / MIDI を Documents/PianoSheet へ」「生録音を Music/PianoSheet へ」を追加。
  保存後はスナックバーにフルパスを出す。

## DB

Room v4。`recording_assets` に `sharedPath TEXT`（NULL 可）を追加（`MIGRATION_3_4`）。
既存の録音は NULL のままで、詳細画面から共有コピーを書き直せる。

## テスト

`SharedAudioStorageTest`（19件）を追加。MediaStore 自体はユニットテストから触れないので、
`SharedMediaGateway` を差し替えて、次を検証する。

- ファイル名（日時＋ID、ID の無害化、秒が同じでも衝突しない）とフルパス表示
- `Music/PianoSheet` への `audio/wav` 書き込み（バイト数・内容・パス）
- 空ファイル／存在しないファイルは書かない
- 録音コレクションが拒否したときの `MediaStore.Files` フォールバック（同じフォルダ・同じ名前）
- 2段とも失敗したときに**元の録音が残っている**こと、メッセージが「共有フォルダに保存できませんでした: …」になること
- `Documents/PianoSheet` への書き出しと、MIME 拒否時の `application/octet-stream` リトライ
- SAF からの取り込み（コピー内容、`import-<id8>.wav`、WAV 以外／短い読み込み／開けない URI の拒否と残骸なし）
- `RIFF`/`WAVE` 判定

全体は **237件**（PianoVAM ベンチ1件は環境変数指定時のみ）で全て成功。

## 制限

- 再インストール後の「一覧」はしない（SAF で1ファイルずつ選ぶ）。フォルダ全体を自動で拾うには
  `READ_MEDIA_AUDIO`（API 33+）／`READ_EXTERNAL_STORAGE`（API 29–32）が必要で、今回は権限ダイアログを
  出さない方針を優先した。
- SD カードなど**セカンダリボリューム**には書かない（一次ボリュームのみ）。
- 公開フォルダのコピーはアプリから削除しない。消すのはユーザーがファイルアプリ／音楽アプリから行う。
