# 技術スタック定義書：読書記録アプリ（BookLog）

| 項目 | 内容 |
| --- | --- |
| 文書番号 | BL-TECH |
| 版数 | 0.2 |
| 作成日 | 2026-11-01 |
| 最終更新日 | 2026-11-02 |
| 作成者 | （氏名） |
| 元にした文書 | [要件定義書](requirements/requirements.md) 版数 0.3、[機能定義書](functions/function-definition.md) 版数 0.1 |
| 状態 | 承認待ち |

## 改訂履歴

| 版数 | 日付 | 内容 | 作成者 |
| --- | --- | --- | --- |
| 0.1 | 2026-11-01 | 初版。Raise timeline と同じ技術・同じバージョンを基本にし、Google Books API を使うためのもの（RestClient・jsoup・テストでの偽物）を足した | （氏名） |
| 0.2 | 2026-11-02 | インフラ設計書 0.1 で、公開先から Google Books API へは NAT ゲートウェイを置かずにパブリックサブネットのタスクから出ると決めたので、3.5・4 章を直した。1 章と 5.3 にインフラ設計書・データ設計書へのリンクを足した | （氏名） |

## 承認

| 役割 | 氏名 | 承認日 | 署名 |
| --- | --- | --- | --- |
| 承認者 | | | |
| 作成者 | | | |

---

## 1. 本書の目的

使う言語・フレームワーク・サービス・開発ツールとそのバージョンを決め、その理由を記録する。要件定義書の次の項目に対応する。

| 要件定義書 | 内容 |
| --- | --- |
| P-1 | データを保存する RDB（PostgreSQL）のバージョンと動かす場所を決める |
| P-2 | 写真を保存するクラウドストレージ（S3）と、自動のテストで使う代わり（MinIO）を決める |
| P-3 | 本の情報を取る Google Books API の呼び方と、テストでの代わりを決める |
| P-5 | 公開するときの AWS のサービスを決める。構成は[インフラ設計書](design/infra-design.md)に書く |

### 1.1 基本の方針

技術スタックは、前に作ったタイムライン型 SNS（Raise timeline）と同じにする。**バージョンも Raise timeline と同じものを使う**。Raise timeline にないもの（Google Books API を使うためのもの）だけを新しく選ぶ。Raise timeline との違いは 4 章にまとめる。

| 理由 | 内容 |
| --- | --- |
| 学習 | Raise timeline で覚えたこと（JWT 認証・S3・ECS Fargate など）をそのまま使い、新しく学ぶことを外部の API の使い方に絞る |
| 動作の実績 | Raise timeline で一緒に動くことを確かめた組み合わせなので、相性の問題が起きにくい |
| 保守 | 2 つのリポジトリで、バージョンを上げる作業を同じ手順で行える |

3 章のバージョンは、2026-11-01 時点で Raise timeline の pom.xml・package.json・compose.yaml・Dockerfile・Terraform で使っているものである。新しく足すものは、公開から 2 週間以上たった版を選ぶ。

---

## 2. 選定の条件

要件定義書と機能定義書から、使うものには次のことが求められる。

| No | 確認すること | 理由 |
| --- | --- | --- |
| 1 | RDB で、1 人 1 冊 1 つなどの一意の制約と、まとめて消す（ON DELETE CASCADE）を使える | P-1、FN-03、FN-01 の退会 |
| 2 | パスワードを BCrypt で保存でき、JWT の作成・確認を自分で一から作らなくてよい | N-14、N-19 |
| 3 | 写真をクラウドストレージに置き、署名付き URL を発行できる | P-2、N-16 |
| 4 | サーバーから外部の API を、時間の上限（5 秒）を付けて呼べる。API キーを画面に出さない | P-3、FN-02 B-8、N-18 |
| 5 | 自動のテストで、本物の Google Books API を呼ばずに、決まった結果・エラー・遅い応答を再現できる | N-28、FN-02 の 10 章 |
| 6 | 画面と API の両方で自動テストを書け、GitHub Actions で動かせる | N-28 |
| 7 | スマートフォンの幅でも使える画面を作れる | N-23 |

---

## 3. 使う技術とバージョン

「RT」の列：○ は Raise timeline と同じもの・同じバージョン、新 は本アプリで新しく足すもの。

### 3.1 API（バックエンド）

| 区分 | 使うもの | バージョン | RT | 用途・理由 |
| --- | --- | --- | --- | --- |
| 言語 | Java | 21 | ○ | 長期サポート版 |
| フレームワーク | Spring Boot | 4.1.1 | ○ | spring-boot-starter-parent で指定 |
| Web | Spring Boot Starter Web MVC | Spring Boot 4.1.1 に合わせる | ○ | API |
| データベースの読み書き | Spring Boot Starter Data JPA（Hibernate） | 同上 | ○ | 一覧の件数をまとめて取る SQL は @Query で書く。jOOQ は使わない |
| 入力の確認 | Spring Boot Starter Validation | 同上 | ○ | 文字数などの確認 |
| 表の作成・変更 | Flyway（spring-boot-starter-flyway・flyway-database-postgresql） | 同上 | ○ | 表の変更の履歴をリポジトリに残す |
| データベースの接続 | PostgreSQL JDBC Driver | 同上 | ○ | |
| ログイン・権限 | Spring Boot Starter Security | 同上 | ○ | BCrypt・CSRF 対策・権限の確認（条件 2）。セッションは使わない（STATELESS） |
| JWT の作成・確認 | Spring Boot Starter Security OAuth2 Resource Server | 同上 | ○ | JWT（HS256）の作成と確認。Cookie から JWT を読むところだけを自分で書く（機能定義書 3.2） |
| 外部の API の呼び出し | Spring の RestClient（spring-boot-starter-restclient） | 同上 | 新 | Google Books API を呼ぶ（条件 4）。接続・読み込みの時間の上限を設定できる。Spring Boot に含まれるので、ライブラリを足さなくてよい |
| HTML のタグの除去 | jsoup | 1.23.2（2026-08-26 公開） | 新 | Google Books API の紹介文（description）から HTML のタグを取り除き、`&amp;` などを文字に戻す（FN-02 5 章の 6）。正規表現で取り除くと、タグの書き方の違いや文字の参照の戻し忘れが起きやすいため |
| 稼働の確認 | Spring Boot Starter Actuator | 同上 | ○ | 公開した後の稼働の確認（/actuator/health） |
| API の仕様書 | springdoc-openapi（springdoc-openapi-starter-webmvc-ui） | 3.1.1 | ○ | Controller・record から OpenAPI の仕様書を作る。公開先では出さない |
| ログ | Spring Boot の構造化ログ（Logback。ECS 形式） | Spring Boot 4.1.1 に含まれる | ○ | 公開先ではログを JSON で出し、CloudWatch Logs Insights で探す |
| S3 の操作 | AWS SDK for Java 2.x（s3） | 2.56.0（BOM で固定） | ○ | 写真の保存・削除と署名付き URL（条件 3）。接続先を変えれば MinIO にも使える |
| ビルド | Maven | 3.9.16（Maven Wrapper 3.3.4） | ○ | mvnw をリポジトリに入れる |

「同上」は、バージョンを自分で書かず、Spring Boot 4.1.1 が決めるバージョンを使うという意味である。

### 3.2 画面（フロントエンド）

| 区分 | 使うもの | バージョン | RT | 用途・理由 |
| --- | --- | --- | --- | --- |
| 実行環境 | Node.js | 24 | ○ | GitHub Actions の setup-node と同じ |
| 言語 | TypeScript | ~6.0.2 | ○ | |
| ライブラリ | React・React DOM | ^19.2.8 | ○ | |
| 開発サーバー・ビルド | Vite | ^8.3.0 | ○ | ポート 5173 |
| Vite の React 対応 | @vitejs/plugin-react | ^6.1.1 | ○ | |
| 型定義 | @types/react・@types/react-dom・@types/node | ^19.2.18・^19.2.7・^24.13.3 | ○ | |
| 画面の切り替え | React Router（react-router） | 7.18.4 | ○ | 13 個の画面（G-1〜G-13）を URL で切り替える |
| 見た目 | CSS Modules | （Vite に含まれる） | ○ | 部品ごとに CSS を分けられ、追加のライブラリが要らない |

バージョンは package.json に書く範囲である。実際に入るバージョンは package-lock.json で固定し、Raise timeline の package-lock.json と同じものにそろえる。星の表示（★）は文字で出し、アイコンのライブラリは使わない。

### 3.3 データベース・ストレージ

| 区分 | 開発環境 | AWS | RT | 用途・理由 |
| --- | --- | --- | --- | --- |
| データベース | PostgreSQL 17（Docker のイメージ postgres:17） | Amazon RDS for PostgreSQL 17 | ○ | 条件 1。Google Books API の検索の結果の使い回し（24 時間）と、その日の呼び出し回数もここに置く（5.3） |
| 写真の保存先 | Amazon S3 の開発用のバケット。自動のテストでは MinIO（Docker のイメージ chainguard/minio。ダイジェストで固定） | Amazon S3 | ○ | 開発中は本番と同じ S3 で確かめる。テストは S3 と同じ API の MinIO を使い、AWS の鍵を GitHub に渡さない |

### 3.4 外部のサービス

| 区分 | 使うもの | 用途・理由 |
| --- | --- | --- |
| 本の情報 | Google Books API（v1。`https://www.googleapis.com/books/v1`） | 本の検索（volumes?q=）と 1 冊の情報（volumes/{id}）。API キーは Google Cloud のプロジェクトで作り、Books API だけに使えるよう制限する（要件定義書 R-8）。開発中は開発用、公開先は公開用のキーを分ける。無料で使え、1 日の上限は 1,000 回とみなす（U-2） |

API キーは、開発中は環境変数で、公開先は Parameter Store で渡す（N-18、N-27）。

### 3.5 公開（AWS）

構成と費用はインフラ設計書に書く。Raise timeline の最後の構成と同じにする。

| 区分 | 使うもの | バージョン・種類 | RT | 用途・理由 |
| --- | --- | --- | --- | --- |
| API の実行 | Amazon ECS（Fargate） | ARM64（Graviton）、0.5 vCPU・メモリ 1GB、タスク 1 つ | ○ | サーバー（EC2）を持たずにコンテナで動かす |
| API のイメージ | Docker（backend/Dockerfile）、Amazon ECR | ベースは amazoncorretto:21-al2023-headless（ダイジェストで固定） | ○ | 手元で作った jar を入れるだけ |
| データベース | Amazon RDS for PostgreSQL | 17、db.t4g.micro | ○ | |
| 入口・HTTPS | Amazon CloudFront、CloudFront Functions | PriceClass_200、cloudfront-js-2.0 | ○ | HTTPS の終端。画面を S3 から配る |
| ロードバランサー | Application Load Balancer | HTTP 80 | ○ | 止めている間は消す（P-6） |
| 写真・画面の保存先 | Amazon S3 | | ○ | P-2 |
| 外への通信 | インターネットゲートウェイ（NAT ゲートウェイは使わない） | | 新 | API のタスクから Google Books API（インターネット）を呼ぶ必要がある。Raise timeline は AWS の中のサービスだけを呼んでいた。タスクはもともとパブリックサブネットに置いてパブリック IP を付けているので、費用の高い NAT ゲートウェイを置かずにインターネットゲートウェイから出られる（インフラ設計書 方針 6・7） |
| 秘密の値 | AWS Systems Manager Parameter Store | | ○ | N-18。Google Books API の API キーもここに置く |
| ログ・監視 | Amazon CloudWatch | | ○ | API のログ（JSON）、ALB・RDS のアラーム |
| 費用の通知 | AWS Budgets | | ○ | R-1 |
| AWS の構築 | Terraform | 1.16.5（required_version = ">= 1.9"） | ○ | 検収のたびに作って消すため |
| Terraform の AWS プロバイダー | hashicorp/aws | 6.68.0（version = "~> 6.0"） | ○ | |
| Terraform の乱数のプロバイダー | hashicorp/random | 3.9.1（version = "~> 3.6"） | ○ | DB のパスワード・JWT の秘密の値などを作る |
| Terraform の lint | TFLint ＋ tflint-ruleset-aws | ruleset 0.49.0 | ○ | |

### 3.6 開発ツール

| 区分 | 使うもの | バージョン | RT | 用途・理由 |
| --- | --- | --- | --- | --- |
| ソース管理 | Git・GitHub | | ○ | N-25 |
| 自動のチェック | GitHub Actions | actions/checkout@v7、actions/setup-node@v7、actions/setup-java@v6（temurin 21）、actions/upload-artifact@v7、hashicorp/setup-terraform@v4、terraform-linters/setup-tflint@v6 | ○ | N-28。Pull Request ごとに書式・lint・テスト・ビルドを動かす |
| 開発用のコンテナ | Docker Compose | | ○ | PostgreSQL を起動する |
| API のテスト | JUnit 5、MockMvc、Testcontainers（PostgreSQL・MinIO） | Spring Boot 4.1.1 に合わせる | ○ | 本物の PostgreSQL と MinIO でテストする。開発用の DB は使わない。H2 は使わない |
| API の単体テスト | Mockito、@WebMvcTest、@DataJpaTest | Spring Boot 4.1.1 に合わせる | ○ | 層ごとに確かめる |
| Google Books API の呼び出しのテスト | MockRestServiceServer（spring-test に含まれる） | Spring Boot 4.1.1 に合わせる | 新 | RestClient が送る問い合わせ（q・startIndex・key）と、返ってきた JSON の読み方、500・429・時間切れのときの動きを確かめる（条件 5）。それより上の層のテストでは、Google Books API を呼ぶ部品を Mockito で偽物にする |
| 画面のテスト | Vitest | ^5.0.3 | ○ | |
| 画面の部品のテスト | Testing Library（@testing-library/react・user-event・jest-dom） | 16.3.3・14.6.8・7.0.1 | ○ | |
| テストの中のブラウザ | jsdom | 30.1.3 | ○ | |
| 画面を通したテスト | Playwright（@playwright/test） | ^1.63.0 | ○ | 主な流れと各機能のシナリオ、375px の横幅を確かめる。ブラウザはパソコンの Microsoft Edge。検収の対応環境の確認では Chrome・Firefox・WebKit と端末のエミュレーションでも流す |
| E2E での Google Books API の偽物 | Node.js の標準の http で書いた小さなサーバー（e2e/ の中に置く） | Node.js 24 に含まれる | 新 | E2E では API の jar から本物の Google Books API を呼ばず、決まった本の一覧を返すこの偽物を呼ぶ（条件 5）。Playwright の webServer で起動し、API の Google Books の接続先を環境変数で偽物に向ける。ライブラリを足さずに済む（5.4） |
| 画面のアクセシビリティの検査 | @axe-core/playwright | 4.13.0 | ○ | 全画面で WCAG 2.1 の A・AA の違反がないかを確かめる（N-21 など） |
| API の性能テスト | k6（Docker のイメージ grafana/k6） | 2.2.0 | ○ | 要件定義書 11.1 の目標を確かめる。書籍検索（N-6）は偽物ではなく本物の Google Books API で、回数の上限に気をつけて測る |
| Java の書式 | spring-javaformat | 0.0.48 | ○ | |
| Java の静的解析 | SpotBugs（spotbugs-maven-plugin） | 4.10.4.1 | ○ | |
| TypeScript の lint | oxlint | ^1.81.0 | ○ | |
| API の型の生成 | openapi-typescript | 7.13.0 | ○ | API の仕様書から画面の型を作る |
| API の仕様書の HTML | Redocly CLI（@redocly/cli） | 2.62.1 | ○ | npx で使い、package.json には入れない |
| エディタ | Visual Studio Code | | ○ | |

---

## 4. Raise timeline との違い

| 項目 | Raise timeline | 本アプリ | 理由 |
| --- | --- | --- | --- |
| 外部の API | なし | Google Books API（RestClient で呼ぶ） | 本の情報を手で入れずに取るため（FN-02） |
| HTML の処理 | なし | jsoup | 紹介文の HTML のタグを取り除くため |
| 外部の API のテスト | なし | MockRestServiceServer、E2E では Node.js の偽物のサーバー | 自動のテストで本物を呼ばないため（N-28） |
| 外への通信 | AWS の中のサービスだけ | インターネット（Google Books API）にも出る | タスクのパブリック IP からインターネットゲートウェイを通って出る（インフラ設計書 方針 7） |
| 部分一致の検索 | pg_trgm（ユーザー検索） | 使わない | 本アプリにユーザー検索はなく、本の検索は Google Books API で行うため |
| アイコン画像 | S3 の icons/ | なし | 要件定義書 2.2 |
| 画面の数 | 11 画面 | 13 画面 | G-1〜G-13 |

---

## 5. 候補の比較

Raise timeline と同じものを使うことを前提にしたため、比較したのは新しく決めるものだけである。「2. 選定の条件」の No ごとに ○・△・× をつける。

### 5.1 本の情報を取るところ

| 候補 | 4 | 5 | 備考 |
| --- | --- | --- | --- |
| **Google Books API** | ○ | ○ | 採用（利用者の決定）。ISBN と書名の両方で探せ、JSON で表紙の URL・紹介文も返る。日本の本の一部がない（R-7） |
| 国立国会図書館サーチの API | ○ | ○ | 日本の本は多いが、結果が XML で、表紙の画像は別に取る必要がある |
| openBD | ○ | ○ | ISBN でしか探せず、書名で探せない。バージョン 1 の提供の終了が 2023 年に発表されている |
| 手入力だけ | — | — | 利用者が書名・著者を毎回入れることになり、目的（要件定義書 1.2 の 1）に合わない。U-3 で作らないことにした |

### 5.2 Google Books API を呼ぶ道具

| 候補 | 4 | 5 | 備考 |
| --- | --- | --- | --- |
| **RestClient** | ○ | ○ | 採用。Spring の標準で、Web MVC と同じく順番に処理する書き方ができる。MockRestServiceServer でテストできる |
| WebClient | ○ | ○ | 非同期の処理（WebFlux）のための道具で、本アプリには要らない依存が増える |
| OpenFeign（Spring Cloud） | ○ | △ | インターフェースだけで書けるが、Spring Cloud の依存とバージョンの管理が増える |

### 5.3 検索の結果の使い回しと、呼び出し回数の置き場所

| 候補 | 1 | 4 | 備考 |
| --- | --- | --- | --- |
| **PostgreSQL の表** | ○ | ○ | 採用。追加のサービスが要らない。API のタスクを止めても（P-6）、作り直しても、その日の回数と 24 時間の結果が残る |
| API のメモリ（Caffeine など） | — | △ | 速いが、タスクを止める・作り直すと回数が 0 に戻り、1 日の上限を超えるおそれがある |
| Amazon ElastiCache（Redis） | — | ○ | 数十人の利用には大きすぎ、費用もかかる |

表の形・古い結果の消し方は、[データ設計書](design/data-design.md)（4.11・4.12、BR-12・BR-13）のとおり。

### 5.4 E2E での Google Books API の偽物

| 候補 | 5 | 備考 |
| --- | --- | --- |
| **Node.js の標準の http で書いた偽物** | ○ | 採用。決まった JSON を返すだけなので数十行で書け、ライブラリが要らない。Playwright と同じ Node.js で動く |
| WireMock | ○ | 高機能だが、安定版の 3 系は 2025 年から新しい版が出ておらず、4 系はまだベータ版である |
| 本物の Google Books API | × | 結果が変わることがあり、テストが不安定になる。1 日の回数の上限も使ってしまう。CI に API キーを渡すことになる |

---

## 6. 費用

AWS の月額の概算はインフラ設計書に書く。Google Books API は無料で使える（1 日の上限の中）。構築の前に AWS Pricing Calculator で確かめ、ここに記録する。

| サービス | 項目 | 金額 | 確認日 | 確認した URL |
| --- | --- | --- | --- | --- |
| （構築前に記入） | | | | |
