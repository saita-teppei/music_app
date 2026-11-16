# OpenCloud FLAC Player (Android)

OpenCloud に置いた FLAC を WebDAV でフォルダから選んでダウンロードし、端末内でオフライン再生する個人用アプリ。
OpenCloud(共有システム)には一切変更を加えない。読み取り(PROPFIND / GET)だけを行う。

設計書: 「OpenCloud FLACプレイヤー(Android) システム設計書」

## 機能と実装の対応

| ID | 内容 | 主な実装 |
|---|---|---|
| F1 | サーバー設定・接続テスト | `ui/screens/OtherScreens.kt` ServerSetupScreen, `data/SettingsStore.kt` |
| F2 | フォルダを辿ってFLAC一覧 | BrowserScreen, `data/webdav/WebDavClient.kt`, `PropfindParser.kt` |
| F3 | ファイル/フォルダをキューへ | BrowserViewModel.enqueueSelected(フォルダは再帰) |
| F4 | 進捗・中断・再開・再試行 | `download/DownloadWorker.kt`(Range + If-Range、指数バックオフ5回), DownloadsScreen |
| F5 | 内部ストレージ保存・ライブラリ | `files/music/<id>.flac`、タグとジャケットは `download/TagReader.kt` |
| F6 | バックグラウンド再生・ロック画面・シャッフル/リピート | `playback/PlaybackService.kt`(Media3 MediaLibraryService) |
| F7 | プレイリスト作成・編集・並べ替え | `data/PlaylistRepository.kt`, PlaylistScreen |
| F8 | 曲単位のローカル削除 | `LibraryRepository.deleteLocal`(行とタグは残す) |
| F9 | ETag変更で再取得を促す | `track.latest_etag`(閲覧・同期で更新)≠ `remote_etag` → 「更新あり」 |
| F10 | OTA更新 | `update/UpdateManager.kt`(SHA-256照合 → PackageInstaller) |
| F11 | フォルダ同期と提案通知 | `sync/SyncWorker.kt`, `SyncScanner.kt`, SyncScreen |
| F12 | 曲/アーティスト/アルバム/プレイリスト表示と検索 | LibraryScreen(LIKE検索) |
| F13 | Android Auto | `playback/MediaTree.kt`(閲覧ツリー)、`res/xml/automotive_app_desc.xml`、音声検索 |
| F14 | Wear OS から操作 | 再生サービスのメディアセッション経由(下記) |
| 9.1 | アプリ内ログ | `data/AppLog.kt`(直近500件、トークン・クエリ文字列を除去) |
| 9.3 | プレイリストのエクスポート/インポート | `backup/PlaylistBackup.kt`(JSON、パスで曲を表す) |

構成: Kotlin / Jetpack Compose / Room / WorkManager / Media3 / OkHttp のみ。DIや画像読み込みのライブラリは使っていない。

## 設計書から判断・追加した点

設計書で未確定だった点、または実装上必要だった点。いずれも後から変更しやすい。

- **最低APIレベル**: `minSdk 26`(Android 8.0)、`targetSdk 35`。手元端末が確定したら `app/build.gradle.kts` で変更する。
- **Wear OS(F14)**: 時計用アプリは作らず、Wear OS 標準の「メディアコントロール」がスマホのメディアセッションを操作する方式にした。
  時計用アプリを作るには Google Play 開発者サービス(Data Layer API)への依存が要り、「標準コンポーネント中心」の方針(2.2)に反するため。
  再生/一時停止・前後の曲・曲名表示はこれで満たせる想定(**実機未確認**)。
- **同期の頻度**: 8時間ごとの定期実行(ネットワーク接続・バッテリー低下なしが条件)+ アプリ起動時 + 手動の「今すぐ同期」。
  自動の走査は同じフォルダを2時間以内に繰り返さない。走査はフォルダのETagが変わった所だけ降りる。
- **同期モード**: フォルダごとに「提案のみ」(既定)/「自動でダウンロード」をスイッチで切り替え。
- **テーブルの追加**(5.2への追加):
  - `track.latest_etag`(サーバーで最後に見たETag。F9用)、`track.playback_error`(再生失敗。再ダウンロード提示用)
  - `download_task.attempts`(再試行回数)、`download_task.part_etag`(書きかけファイルのETag。If-Range用)、`download_task.created_at`
  - `sync_dir_cache`(走査用のフォルダETagキャッシュ)、`log_entry`(アプリ内ログ)
- **プレイリストのエクスポート/インポート**(未確定事項)は実装した。設定画面から使う。
- **リリースの署名**: GitHub Actions で行う(`.github/workflows/release.yml`)。手元ビルドも環境変数で可能。
- **クリアテキスト通信**: 既定はHTTPSのみ。`*.ts.net`(MagicDNS)だけhttpを許可している。別のホスト名でhttpを使う場合は
  `app/src/main/res/xml/network_security_config.xml` にそのホストを追加する。証明書エラーを無視する設定は入れていない。
- **メディアセッションへの接続制限**: 自アプリ・システムUI・Bluetooth・Android Auto・Wear OS関連・信頼済みコントローラーのみ許可。

## 実装前に確認すること(設計書 11.1)

アプリを入れる前に、tailnetに接続したPCから以下を確認する。

```sh
URL=https://<opencloud-host>/remote.php/webdav   # 通らなければ /dav/spaces/<space-id> を試す
USER=<ユーザー名>
TOKEN=<アプリトークン>

# 一覧(207が返ればOK)
curl -u "$USER:$TOKEN" -X PROPFIND -H "Depth: 1" "$URL/" -i | head -20

# Range付きGET(206とETagが返ればOK)
curl -u "$USER:$TOKEN" -H "Range: bytes=0-99" "$URL/<path/to/file.flac>" -o /dev/null -D -
```

- 405 になる場合は、そのパスがWebDAVではなくWeb画面(静的ファイル)に当たっている。下の「URLの探し方」で正しいパスを調べる。
- 401 になる場合はアプリトークンのBasic認証が通っていない。認証方式の再検討が必要(設計書 7章)。
### URLの探し方(PowerShell)

```powershell
$HOSTURL = "https://<opencloud-host>"
# 1) パスの候補を総当たり(207 が正解)
foreach ($p in "/remote.php/webdav/", "/webdav/", "/dav/files/$USER/", "/remote.php/dav/files/$USER/") {
  $code = curl.exe -s -o NUL -w "%{http_code}" -u "${USER}:${TOKEN}" -X PROPFIND -H "Depth: 1" "$HOSTURL$p"
  "$code  $p"
}
# 2) Graph API から個人スペースのWebDAV URLを取得(これをアプリのURLに入れる)
(curl.exe -s -u "${USER}:${TOKEN}" "$HOSTURL/graph/v1.0/me/drives" | ConvertFrom-Json).value |
  Select-Object driveType, name, @{n='webDavUrl'; e={ $_.root.webDavUrl }}
```

- フォルダのETagが配下の変更で変わることも確認しておくと、同期の負荷を抑えられる。

## ビルド

JDK 17 以上と Android SDK(API 35)が必要。

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## リリース(OTA更新)

1. 署名鍵を作る(**紛失すると上書き更新できなくなる。別の場所に保管する**)
   ```sh
   keytool -genkeypair -v -keystore release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000
   ```
2. GitHubリポジトリの Settings → Secrets and variables → Actions に登録
   - `SIGNING_KEYSTORE_BASE64`(`base64 -w0 release.jks` の出力)
   - `SIGNING_KEYSTORE_PASSWORD`(keytool で入力したパスワード)
   - `SIGNING_KEY_ALIAS`(上の例なら `release`)
   - `SIGNING_KEY_PASSWORD` は**不要**。keytool の既定形式(PKCS12)では鍵のパスワードがストアのパスワードと同じになるため、
     未設定ならストアのパスワードを使う。古いJKS形式で別のパスワードを付けた場合だけ登録する。
3. タグを push する。バージョンはタグから決まる(`v1.2.3` → versionName `1.2.3`, versionCode `10203`)。
   ```sh
   git tag v0.1.0 && git push origin v0.1.0
   ```
   Actions が署名済みAPKと `.sha256` を Release に添付し、リリースノートに `SHA-256: …` を書く。
4. 初回だけAPKをサイドロードする。以降は アプリの 設定 → 「更新を確認」で更新する。
   SHA-256 が一致しないAPKはインストールしない。

更新確認は `https://api.github.com/repos/<updateRepo>/releases/latest` を認証なしで呼ぶため、**リポジトリは公開が前提**
(`gradle.properties` の `updateRepo`)。

## Android Auto

サイドロードしたアプリは既定では Android Auto に表示されない。Android Auto の設定でバージョン番号を10回タップして
開発者モードにし、開発者設定の「提供元不明のアプリ」を有効にする。事前に Desktop Head Unit で表示を確認するとよい(**実機未確認**)。

## 未検証のこと

このリポジトリでは、ビルド・Lint・単体テスト(PROPFIND解析、URL生成、同期走査、版比較、リリース解析)までを確認している。
次は実機とOpenCloudで確認が必要。

- OpenCloud への接続、Range再開、ETag の挙動
- バックグラウンド再生・ロック画面・Android 14以降の長時間ダウンロード
- Android Auto / Wear OS での表示と操作
- OTA更新(PackageInstaller)の一連の流れ
