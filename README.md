# BookLog

読書記録アプリ。ISBN か書名で本を探し、星（1〜5）・感想・写真でレビューを書いて共有する。ほかの人のレビューには「参考になった」とコメントで反応でき、気になる人をフォローできる。本ごとに「読みたい」「読書中」「読了」を付けて本棚として見られる。

- 何を作るか：[要件定義書](docs/requirements/requirements.md)
- 文書の一覧：[docs/README.md](docs/README.md)
- 作る順番と進み具合：[開発計画書](docs/development-plan.md)
- 開発ルール：[CONTRIBUTING.md](CONTRIBUTING.md)

いまは API サーバーと画面の土台、ユーザー登録・ログイン、書籍検索と本の取り込み、レビュー、タイムライン（開発計画書 1-1〜1-5）まで作っています。公開の手順は、作ったときに足します。

## 起動方法

### 必要なもの

- JDK 21
- Node.js 24
- Docker Desktop

### 初回だけ

```
git clone https://github.com/y0408e0301-star/booklog.git
cd booklog
git config core.hooksPath .githooks
```

`git config core.hooksPath .githooks` は開発ルールを守らせる git フックを有効にする設定です（[CONTRIBUTING.md](CONTRIBUTING.md)）。

画面の部品を入れます（`frontend` で 1 回だけ。`package.json` が変わったときもやり直します）。

```
cd frontend
npm ci
```

### Google Books API の API キー

書籍検索（FN-02）は、API サーバーから Google Books API を呼びます。開発用の API キーを環境変数 `GOOGLE_BOOKS_API_KEY` で渡してから API サーバーを起動します。キーは画面には渡さず、リポジトリにも置きません（N-18）。

1. [Google Cloud のコンソール](https://console.cloud.google.com/) でプロジェクトを作り（既にあればそれを使う）、「API とサービス」→「ライブラリ」で **Books API** を有効にする
2. 「API とサービス」→「認証情報」→「認証情報を作成」→「API キー」でキーを作る
3. 作ったキーの「API の制限」で「キーを制限」を選び、**Books API だけ**にする（漏れたときに、ほかの API に使われないようにする。要件定義書 R-8）
4. API サーバーを起動するターミナルで、キーを環境変数に入れる（PowerShell の例。ターミナルを閉じると消える）

   ```
   $env:GOOGLE_BOOKS_API_KEY = "作ったキー"
   ```

- キーを入れずに起動すると、key= を付けずに呼びます。その場合は Google の共有の枠になり、その日の上限で 429 が返ることが多く、画面には M-14（本の検索がいまできません）が出ます
- 呼び出しは 1 日 1,000 回までに数えています（google_books_usage の表。日本時間の 0 時に 0 に戻る）。同じ文字・同じページの検索は 24 時間使い回すので、Google Books API は呼びません
- 自動のテスト（`mvnw verify`・`npm test`）は Google Books API を呼ばないので、キーは要りません

### 起動する

ターミナルを使い、リポジトリのフォルダから次を順に実行します。

| 順番 | 起動するもの | コマンド | ポート |
| --- | --- | --- | --- |
| 1 | データベース | `docker compose up -d` | 5432（PostgreSQL） |
| 2 | API サーバー | `cd backend` → `.\mvnw.cmd spring-boot:run` | 8080 |
| 3 | 画面 | 別のターミナルで `cd frontend` → `npm run dev` | 5173 |

ブラウザで http://localhost:5173 を開きます。画面の dev サーバーは `/api` へのリクエストを API サーバー（8080）へ転送するので、画面と API は同じ場所（localhost:5173）にあるように見え、ログインの Cookie がそのまま使えます。

- API サーバーは http://localhost:8080/actuator/health で `{"status":"UP"}` が返れば起動済みです
- API の仕様書は http://localhost:8080/swagger-ui.html （Swagger UI）で見られます（下の「API の仕様書」）
- ポートは上の表のものだけを使います（[CLAUDE.md](CLAUDE.md) の「サーバーのポート」）。使われていたら、そのポートを使っているプロセスを止めてから起動し直します（別のポートでは起動しません）。ほかのアプリの PostgreSQL のコンテナが 5432 を使っていたら、`docker stop <コンテナ名>` で止めてから起動します
- 開発では、データベースの接続先と JWT の秘密の値は `backend/src/main/resources/application.properties` の既定値（開発用）を使うので、設定は要りません（Google Books API の API キーだけは上のとおり渡します）。公開環境では環境変数（`DB_URL`・`DB_USERNAME`・`DB_PASSWORD`・`JWT_SECRET`・`COOKIE_SECURE=true`・`GOOGLE_BOOKS_API_KEY`）で渡します

### 止める

- API サーバー・画面：それぞれのターミナルで `Ctrl + C`
- データベース：`docker compose down`（データは残ります）

## テストとコードチェック

| 対象 | コマンド | 内容 |
| --- | --- | --- |
| API サーバー | `backend` で `.\mvnw.cmd verify` | テスト（下の「API サーバーのテストの種類」）、書式チェック（spring-javaformat。直すときは `.\mvnw.cmd spring-javaformat:apply`）、静的解析（SpotBugs）。API の仕様書（docs/api/openapi.yaml）がコードと違えば、書き直して失敗する |
| 画面 | `frontend` で `npm run lint` → `npm test` → `npm run build` | lint（oxlint。React Hooks の決まり・アクセシビリティを含む）、テスト（Vitest・Testing Library。下の「画面のテスト」）、型チェックとビルド |

Pull Request を作ったときは、GitHub Actions（[.github/workflows/ci.yml](.github/workflows/ci.yml)）が上の 2 つと、画面の API の型が仕様書と同じかを自動で確かめます。push の前に、手元で両方を流し直してください（[開発計画書 5 章](docs/development-plan.md)）。

### API サーバーのテストの種類

`mvnw verify` で、次の 5 種類をまとめて実行します。落ちたテストの種類で、どの層に原因があるかが分かります。

| 種類 | クラス名 | 確かめること | 本物を使うもの | 偽物にするもの |
| --- | --- | --- | --- | --- |
| 小さなクラスのテスト | `DisplayNamesTests`・`UserTests` など | 入力の条件と境界値 | そのクラスだけ | なし |
| Service のテスト | `*ServiceTests` | 処理の順番とエラー | Service | Repository・時計（Mockito） |
| Repository のテスト | `*RepositoryTests`（`@RepositoryTest`） | SQL の結果、テーブルの制約 | PostgreSQL（Flyway でテーブルを作る） | なし（Controller・Service は動かさない） |
| Controller のテスト | `*ControllerTests`（`@ControllerTest`） | URL・ステータス・JSON の形、ログインと CSRF トークンが要るか、エラーの形 | Controller・ログインの確かめ方（SecurityConfig） | Service（`@MockitoBean`） |
| API のテスト | `*ApiTests` | 機能定義書 10 章の観点を、API を通して（Controller から DB まで） | すべて | なし |

- **開発用の DB（compose.yaml）は使いません。** DB を使うテストは、Testcontainers が使い捨ての PostgreSQL 17 を Docker で起動し、終わったら消します。Docker は動かしておいてください
- H2 などのメモリーの DB は使いません。PostgreSQL にしかない機能を本番と同じ形で確かめるためです
- Controller のテストで、ログインした状態は `ControllerTestSupport.loggedIn(ユーザーの ID)` で作ります。spring-security-test の `jwt()` は CSRF の確認を飛ばすため使いません（本アプリは Cookie で JWT を送るので CSRF を確かめています）

### 画面のテスト

`npm test` で、`src` の `*.test.ts(x)` をまとめて実行します。API は呼ばず、`fetch` を偽物（`src/test/fetchMock.ts`）にして、決めた応答を返します。

| 種類 | ファイル | 確かめること |
| --- | --- | --- |
| 関数のテスト | `api.test.ts`・`validation.test.ts` | API の URL・メソッド・送る形、CSRF のヘッダー、JWT の取り直し（同時に 401 でも 1 回だけ）、入力の条件と境界値 |
| 部品のテスト | `components/*.test.tsx`・`auth/*.test.tsx` | ヘッダー・トースト・通信切れのお知らせ・ログインの状態と移り先 |
| 画面のテスト | `pages/*.test.tsx`・`App.test.tsx` | 機能定義書 10 章の観点のうち画面で確かめるもの（メッセージの出し方、押せる・押せない、移り先） |

## API の仕様書（OpenAPI）

仕様書は API のコード（Controller と record）から springdoc-openapi で作ります。手で直しません（[基本設計書 2.1](docs/design/basic-design.md#21-api-の詳細設計)）。

| 見たいもの | 方法 |
| --- | --- |
| 開発中に見る・試す | API サーバーを起動して http://localhost:8080/swagger-ui.html を開く。「Try it out」でログイン（`POST /api/auth/login`）すると、そのあとの API も Cookie と CSRF のヘッダー付きで試せる |
| ファイルで読む | [docs/api/openapi.yaml](docs/api/openapi.yaml) |

API を変えたときは、`backend` で `.\mvnw.cmd verify` を実行します。仕様書が変われば、`docs/api/openapi.yaml` を書き直してテストが失敗するので、もう一度実行して通ることを確かめます。続けて `frontend` で `npm run gen:api` を実行し、画面が使う API の型（`src/api-schema.d.ts`）を作り直してから、両方をコミットします。仕様書と Swagger UI は、公開環境では出しません（`mvnw spring-boot:run` で起動したときの dev プロファイルだけで出します）。

## ログ

API サーバーのログは、公開用の jar（`java -jar`）では 1 行 1 つの JSON（ECS 形式）で、開発中（`mvnw spring-boot:run`）は読みやすいテキストで出ます（[基本設計書 2.3](docs/design/basic-design.md#23-ログ)）。

- `/api/` のリクエストごとに `GET /api/auth/me 200 42ms` のような 1 行を出し、JSON ではリクエスト ID・ステータス・処理時間・ログインした人の ID を項目として持ちます
- リクエスト ID は応答のヘッダー `X-Request-Id` でも返します
- 5xx は ERROR、1 秒以上かかったリクエストは WARN で出します（閾値は `application.properties` の `app.logging.slow-request-threshold`）
- Google Books API を呼ぶたびに `Google Books API search 200 312ms（今日 12 回目）` のような 1 行を出します。JSON では種類（search・volume）・ステータス（つながらない・時間切れは 0）・処理時間・使い回したか・その日の回数を項目として持ちます。失敗したときと、その日の回数が 800 回を超えたときは WARN で出します
- パスワード・JWT・Cookie・リクエストの本文・クエリ文字列（書籍検索の文字を含む）・Google Books API の API キーは出しません
