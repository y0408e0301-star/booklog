# BookLog

読書記録アプリ。ISBN か書名で本を探し、星（1〜5）・感想・写真でレビューを書いて共有する。ほかの人のレビューには「参考になった」とコメントで反応でき、気になる人をフォローできる。本ごとに「読みたい」「読書中」「読了」を付けて本棚として見られる。

- 何を作るか：[要件定義書](docs/requirements/requirements.md)
- 文書の一覧：[docs/README.md](docs/README.md)
- 作る順番と進み具合：[開発計画書](docs/development-plan.md)
- 開発ルール：[CONTRIBUTING.md](CONTRIBUTING.md)

いまは API サーバーと画面の土台、ユーザー登録・ログイン、書籍検索と本の取り込み、レビュー、タイムライン、レビューの写真、読書ステータスと本棚の API、参考になった、コメント、フォロー、プロフィール、退会（開発計画書 1-1〜1-12）と、画面を通したテスト（2-1）、性能テスト（2-2）、公開先の API のイメージ（`backend/Dockerfile`）と環境を作る Terraform（`deploy/terraform/`。2-3）まで作っています。まだ公開はしていません。公開の手順は、作ったときに足します。

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

### 写真の置き場所（AWS S3）

レビューの写真（FN-04）は、開発中も本物の S3 の開発用バケット `bl-photos-dev-467112307516`（東京リージョン、非公開）に置きます。API サーバーは AWS の鍵を AWS SDK の標準の探し方で見つけるので、開発する PC で `aws configure` を済ませ、`~/.aws/credentials` に、このバケットの `reviews/` を読み書きできる IAM ユーザーの鍵を置いておきます。

- 画面は API が発行した署名付き URL（15 分）で S3 から直接読みます。バケットの URL を直接開いても 403 で見られません（N-16）
- 自動のテスト（`mvnw verify`）は Testcontainers で MinIO（S3 の代わり）を起動するので、AWS の鍵は要りません
- S3 から消せなかった写真は storage_deletions の表に記録し、毎日 4:30 に消し直します

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
- 開発では、データベースの接続先と JWT の秘密の値は `backend/src/main/resources/application.properties` の既定値（開発用）を使うので、設定は要りません（Google Books API の API キーだけは上のとおり渡します）。公開環境では環境変数（`DB_URL`・`DB_USERNAME`・`DB_PASSWORD`・`JWT_SECRET`・`COOKIE_SECURE=true`・`GOOGLE_BOOKS_API_KEY`・`S3_BUCKET`）で渡します

### 止める

- API サーバー・画面：それぞれのターミナルで `Ctrl + C`
- データベース：`docker compose down`（データは残ります）

## テストとコードチェック

| 対象 | コマンド | 内容 |
| --- | --- | --- |
| API サーバー | `backend` で `.\mvnw.cmd verify` | テスト（下の「API サーバーのテストの種類」）、書式チェック（spring-javaformat。直すときは `.\mvnw.cmd spring-javaformat:apply`）、静的解析（SpotBugs）。API の仕様書（docs/api/openapi.yaml）がコードと違えば、書き直して失敗する |
| 画面 | `frontend` で `npm run lint` → `npm test` → `npm run build` | lint（oxlint。React Hooks の決まり・アクセシビリティを含む）、テスト（Vitest・Testing Library。下の「画面のテスト」）、型チェックとビルド |
| 画面を通したテスト | `frontend` で `npm run test:e2e` | Playwright で、主な流れ・各機能のシナリオ・権限・ログインの続き・全画面のアクセシビリティと横幅を画面から確かめる（下の「画面を通したテスト」） |
| 公開環境（Terraform） | `deploy/terraform` で `terraform fmt -check -recursive` → `terraform init` → `terraform validate` → `tflint --init` → `tflint` | 書式・構文・lint（AWS には何も作らない） |

Pull Request を作ったときは、GitHub Actions（[.github/workflows/ci.yml](.github/workflows/ci.yml)）が上の 4 つ、画面の API の型が仕様書と同じか、公開用のイメージが作れるかを自動で確かめます。push の前に、手元で両方を流し直してください（[開発計画書 5 章](docs/development-plan.md)）。

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

### 画面を通したテスト（E2E テスト）

[frontend/e2e](frontend/e2e) のテストは、Playwright でブラウザ（パソコンに入っている Microsoft Edge）を動かし、本物の API と DB につないで、利用者の操作の流れ（シナリオ）を確かめます。何人かのユーザーを別々のブラウザで操作し、操作の後に API で読んだ DB の中身と、相手の画面も確かめます。Google Books API だけは、本物を呼ばずに偽物（`e2e/fake-google-books.ts`）を呼びます（[技術スタック定義書 5.4](docs/tech-stack.md#54-e2e-での-google-books-api-の偽物)）。

| ファイル | 確かめること |
| --- | --- |
| `main-flow.spec.ts` | 主な流れ（登録 → 本を探す → レビュー → フォロー → 参考になった・コメント）、ログアウトとログインし直し |
| `books.spec.ts` | ISBN・「もっと見る」・0 件・Google Books API の障害（M-14）、本の情報の HTML の記号、API キーが画面に出ない、平均評価、読書ステータス |
| `reviews.spec.ts` | 入力の条件、編集・削除と確認ダイアログ、ネタバレ、「続きを読む」、写真（4 枚まで・拡大・削除で S3 からも消える） |
| `social.spec.ts` | 参考になった、コメント、フォロー・解除と G-10、タイムラインのタブと続きの読み込み |
| `profile.spec.ts` | プロフィール・本棚のタブ、G-9 の編集、M-28、退会 |
| `security.spec.ts` | ログインしていないと使えない、他人のものを画面からも API からも変えられない、CSRF、HTML が文字のまま出る、5 回の失敗でロック |
| `session.spec.ts` | JWT が切れてもリフレッシュトークンで使い続けられる、ログアウト後・使い回したリフレッシュトークンは使えない、通信切れのお知らせ |
| `screens.spec.ts` | すべての画面（G-1〜G-13）に、アクセシビリティの違反（axe-core。WCAG 2.1 の A・AA）と横のスクロールがない |

画面の幅は 2 通りで動かします（[playwright.config.ts](frontend/playwright.config.ts) の projects）。`pc`（1280px）ではすべてを、`mobile`（375px）では `main-flow.spec.ts` と `screens.spec.ts` を動かします。

使い方：

1. データベースと画面を「起動する」のとおりに起動します
2. API サーバーは、Google Books API の接続先を偽物に向けて起動します。ふだんの開発で本物の検索を使うときは、起動し直してください

   ```
   cd backend
   $env:GOOGLE_BOOKS_BASE_URL = "http://localhost:9100"
   .\mvnw.cmd spring-boot:run
   ```

3. `frontend` で `npm run test:e2e` を実行します（2 分ほど）。偽物（9100）は Playwright が起動します。1 つのファイルだけなら `npm run test:e2e -- reviews`、片方の幅だけなら `-- --project=pc`

- 行き先は既定で画面の Vite（5173）です。環境変数 `E2E_BASE_URL` で変えられます（CI は jar の 8080）
- 環境変数 `E2E_BROWSERS=all` を付けると、Edge に加えて Chrome・Firefox・WebKit（Safari の代わり）・iPhone と Android のエミュレーションでも流します（Firefox と WebKit は先に `npx playwright install firefox webkit`）
- テストは開発用の DB にユーザーと本を作ります。ユーザー ID は `e2e_`、本の ID は `e2e-` で始まり、テストを始める前と終わった後に `docker compose exec` で DB から消します
- 写真を使うテストは S3 の開発用のバケットに写真を置き、テストの中でレビューを消す・退会して消します。テストが途中で失敗すると、写真が残ることがあります
- 落ちたテストは `frontend/test-results/` にトレースが残ります。`npx playwright show-trace <trace.zip のパス>` で、操作ごとの画面を見られます

### 性能テスト

要件定義書 11.1 の性能（N-1〜N-6）を、要件の量のデータ（ユーザー 1,000 人・本 5,000 冊・レビュー 1 万件など）を入れて測ります。方針は[基本設計書 2.4](docs/design/basic-design.md#24-性能テスト)にあります。P95（小さい順に並べて 95% の位置の値）が目標以内なら合格です。Pull Request の CI では動かしません。

| コマンド（`frontend` で実行） | 内容 | かかる時間 |
| --- | --- | --- |
| `npm run perf -- seed` | 性能テスト用の DB `booklog_perf` にデータを入れ、タイムラインの写真 100 枚を S3 に置く。何回流しても同じデータになる | 約 1 分 |
| `npm run perf -- api` | k6 で、1 人で各 API を 50 回ずつ呼ぶ（N-2・N-3・N-4） | 約 30 秒 |
| `npm run perf -- load` | k6 で、30 人が同時に使う | 約 6 分半 |
| `npm run perf -- ui` | Playwright（Edge）で、パソコンとスマートフォンの幅で N-1〜N-4 を 20 回ずつ測る。回線は下り 10Mbps・上り 30Mbps | 約 5 分 |
| `npm run perf -- mixed` | k6 で 30 人の負荷をかけながら、画面で N-1〜N-4 を測る | 約 5 分 |
| `npm run perf -- all` | 上の 4 つを順にすべて動かし、結果を `perf/results/summary.md` にまとめる | 約 20 分 |
| `npm run perf -- clean` | データと S3 の写真を消す | 数秒 |

手元で動かす手順：

1. Docker Desktop を起動し、リポジトリのフォルダで `docker compose up -d`
2. 下の「画面も入れた jar」の 1・2 で jar を作る
3. 8080 の API サーバー（`mvnw spring-boot:run`）が動いていれば止め、リポジトリのフォルダで `java -Xmx768m -jar backend\target\backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=perf` を実行する（perf プロファイルは、開発用の DB ではなく `booklog_perf` につなぐ。ポートは 8080 のまま）
4. `frontend` で `npm run perf -- seed`（初回は DB を作って止まるので、3 の API を起動し直してから、もう一度実行する。表は API の起動のときに Flyway が作る）
5. `npm run perf -- all`（または `api`・`ui` など 1 つずつ）。終わったら `npm run perf -- clean`

- k6 は Docker のイメージ（`grafana/k6:2.2.0`）で動くので、入れる必要はありません。Playwright はパソコンの Edge を使います
- 写真 4 枚（20MB）の投稿（N-5）は、`PERF_UPLOAD=1` を付けたときだけ測ります。手元では API から S3 への送信が家の回線を通るので、公開先とは条件が違うためです
- 書籍検索（N-6）は、`PERF_BOOK_SEARCH=1` を付けたときだけ、本物の Google Books API で 20 回測ります。API サーバーに環境変数 `GOOGLE_BOOKS_API_KEY` を渡しておきます
- 測る回数は `PERF_RUNS`（既定 20）、測る先は `PERF_BASE_URL`（既定 `http://localhost:8080`）で変えられます
- 手元とランナーの数値は参考です。合否は公開先（AWS）で測った値で決めます（開発計画書 2-8）

GitHub Actions で動かす：Actions の画面で「性能テスト」を選び、「Run workflow」を押します（[.github/workflows/perf.yml](.github/workflows/perf.yml)）。測る先を空にすると、ランナーの中で DB・MinIO（S3 の代わり）・jar を起動して、データを入れてから測ります（N-5 も測ります。N-6 は測りません）。結果は実行の画面に表で出て、成果物 `perf-results` にも残ります。

### 画面も入れた jar

E2E の CI と性能テストでは、画面も入れた jar 1 つでアプリを動かします（公開先の画面は CloudFront が配るので、この jar の画面は使いません）。

1. `frontend` で `npm run build`
2. `backend` で `.\mvnw.cmd clean package -Pwith-frontend`（画面をビルドしていなければ止まります）
3. `java -jar target\backend-0.0.1-SNAPSHOT.jar` で起動すると、http://localhost:8080/ で画面も使えます（API サーバーと同じ 8080 なので、開発用の API サーバーは止めておきます）

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
