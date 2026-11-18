# アプリ内自動アップデートと Release 自動公開（0.4.5-phase2）

`0.4.5-phase2` で、アプリの中から新しいバージョンへ更新できるようにした。あわせて CI が
`main` への push ごとにユニットテスト → Debug APK ビルド → GitHub Release 公開まで進むように
したので、次の版からは「push するだけ」で端末に更新が届く。

- バージョン: `0.4.5-phase2`（`versionCode 12`）
- リリース: https://github.com/yuushi-labs/pianosheet/releases/latest
- 確認先: `https://api.github.com/repos/yuushi-labs/pianosheet/releases/latest`

## 端末側の流れ

1. **起動時に1回、静かに確認**（`core/update`, `data/update`）。`releases/latest` を
   `HttpURLConnection` で取得し、`tag_name` を `BuildConfig.VERSION_NAME` と比較する。
   失敗してもダイアログは出さず、ログに残すだけ。自動確認はクールダウン（6時間）付きで、
   同じプロセスでは1回だけ。
2. **新しい版があれば控えめに知らせる**。ホームに「新しいバージョン 0.4.5-phase2 があります」
   のカードと、更新内容の要約つきダイアログ（今すぐ更新 / 後で）。「後で」を押した版は
   ホームでは二度と出さず、設定画面の「アプリ」節にだけ残す。
3. **ダウンロード**（`AppContainer` の `cacheDir/updates/`）。進捗（%・MB）を表示し、
   キャンセルできる。完了時にサイズと ZIP ヘッダ（`PK\x03\x04`）を確かめ、HTML の
   エラーページなどを APK として扱わない。
4. **インストール**。Android 8.0+ で `canRequestPackageInstalls()` が false のときは
   「この提供元のアプリを許可」画面（`ACTION_MANAGE_UNKNOWN_APP_SOURCES`）へ案内し、
   戻ってきたら許可を読み直してそのままシステムインストーラーを開く
   （`FileProvider` + `ACTION_VIEW`, `application/vnd.android.package-archive`）。
5. **設定 → アプリ**に現在のバージョンと「アップデートを確認」ボタン。手動確認の失敗は
   ここでだけ表示する。

## 比較ロジック（純粋関数・テスト対象）

`core/update/AppVersion.kt` は `v0.4.5-phase2` / `0.4.5-phase2` / `pianosheet-0.4.5-phase2` を
同じ版として読み、数字は数値で比較する（`0.4.10 > 0.4.9`）。サフィックスは素の版が最上位で、
`phase2 < phase10`。数字が2つ未満（`build-12`, `2026-11-18`）はバージョン名と見なさず、
リリース本文の `versionCode: 12` 表記にフォールバックする。

`core/update/UpdateInfo.kt` の `UpdateDecision` は次をすべて満たすときだけ更新を提示する。

- draft / pre-release ではない（`releases/latest` でも念のため確認）
- `.apk` アセットが https URL 付きで存在し、512 KiB 以上ある
- 版が現在より新しい

`core/update/Json.kt` は JSON ライブラリを増やさないための小さなリーダー（`org.json` は
JVM のユニットテストで動かないため）。

## CI（`.github/workflows/android.yml`）

`main` への push で、既存のユニットテストと Debug APK ビルドのあとに:

1. `app/build.gradle.kts` から `versionName` / `versionCode` を読む
2. `pianosheet-<versionName>-debug.apk` にリネーム（`.apk` のまま添付）
3. `gh release create v<versionName>`（既存タグなら `gh release upload --clobber`）で
   Release を公開し、本文に更新内容と `versionCode` を書く

`permissions: contents: write` が必要（`GITHUB_TOKEN` で Release を作るため）。
`checkout` は履歴つき（`fetch-depth: 0`）にして、前回のタグからのコミットを本文に載せる。

## テスト

`testDebugUnitTest` は 337 件（新規 65 件）。今回追加した範囲:

- `AppVersionTest` — タグの読み方と比較（パッチ/マイナー、`0.4.10`、phase 番号、素の版）
- `JsonTest` — 壊れた JSON・HTML・末尾ゴミを null に落とす
- `GithubReleaseJsonTest` — 実際の `/releases/latest` の形、404 の本文、URL なしアセット
- `UpdateDecisionTest` — 同一版・古い版・draft・pre-release・APK なし・小さすぎる APK を除外
- `UpdateRepositoryTest` — クールダウン、「後で」の記憶、失敗の扱い、ダウンロードの再利用
- `UpdateControllerTest` — 起動時確認は1回、キャンセル、インストール後の再通知抑止

## 署名鍵（更新が入るための前提）

Android は同じ署名鍵の APK しか上書きインストールできない。GitHub Actions の
ランナーは debug 鍵を毎回作り直す（実行ごとに別の鍵）ため、そのままでは次の版が
「アプリがインストールされていません」で失敗する。そこで
`keystore/pianosheet-debug.keystore`（alias `androiddebugkey`、password `android`）を
リポジトリに置き、`app/build.gradle.kts` の `signingConfigs.debug` がそれを使うようにした。
CI もローカルも同じ鍵で署名するので、公開済みの APK にそのまま上書きできる。

- 証明書 SHA-256: `5494FEBBA8E143630C69F62D240699A1410447AF5C0EA3ECA48A30BA6EDD30E`（有効期限 2054-04-03）
- 取り扱いは [keystore/README.md](../keystore/README.md)。作り直すと既存インストールの
  上に入らなくなるので、鍵は固定のまま使う。
- リポジトリが Public なので鍵も公開される（第三者が同じ鍵で APK を作れる）。ストア配布へ
  移るときは非公開鍵に切り替え、その一度だけ入れ直すのがよい。

## 既知の制限

- APK は debug 署名。debug と release で署名が変わると上書きインストールできないため、
  配布は debug APK に統一している。
- アプリ内更新は Play ストアを経由しない（Public リポジトリの Release から直接）。ストア
  配布に切り替えるときは Play Core の in-app updates に置き換える。
- 端末側の自動確認は起動時のみ。録音中や解析中に割り込まないよう、ホーム表示時だけ動かす。
