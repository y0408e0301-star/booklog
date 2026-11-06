# 基本設計書：読書記録アプリ（BookLog）

| 項目 | 内容 |
| --- | --- |
| 文書番号 | BL-BD |
| 版数 | 0.4 |
| 作成日 | 2026-11-01 |
| 最終更新日 | 2026-11-06 |
| 作成者 | （氏名） |
| 元にした文書 | [要件定義書](../requirements/requirements.md)（BL-REQ）版数 0.4、[機能定義書](../functions/function-definition.md)（BL-FD）版数 0.1、[技術スタック定義書](../tech-stack.md)（BL-TECH）版数 0.1 |
| 状態 | 承認待ち |

## 改訂履歴

| 版数 | 日付 | 内容 | 作成者 |
| --- | --- | --- | --- |
| 0.1 | 2026-11-01 | 初版。構成は Raise timeline の基本設計書と同じにした。画面設計書 0.1 を作成。データ設計書・インフラ設計書は次の版で足す | （氏名） |
| 0.2 | 2026-11-02 | データ設計書 0.1・インフラ設計書 0.1 を作成し、1.2 にリンクを足した | （氏名） |
| 0.3 | 2026-11-05 | 画面設計書を 0.2 にした（G-8 の本棚に著者を足した） | （氏名） |
| 0.4 | 2026-11-06 | 2.2.1 に公開環境の手順（初めて作るとき、API キーの置き方、deploy.sh・start.sh・stop.sh、すべて消すとき、反映のきまり、困ったとき、費用の確認）を書いた。インフラ設計書を 0.2 にした | （氏名） |

## 承認

| 役割 | 氏名 | 承認日 | 署名 |
| --- | --- | --- | --- |
| 承認者 | | | |
| 作成者 | | | |

承認は、本書と 1.2 の詳細文書をまとめて行う。詳細文書を変えた場合は、その文書の版数を上げて改訂履歴に記録し、あわせて本書の版数を上げて改訂履歴に記録する。

---

## 1. 概要

### 1.1 本書の目的

要件定義書・機能定義書で決めた「何ができるか」をもとに、「画面がどう見えて、どう動くか」「どんなデータを、どんな形で持つか」「どこで、どんな構成で動かすか」を決める。

### 1.2 基本設計書の構成

基本設計書は、本書と次の詳細文書でできている。

| 文書 | 文書番号 | 内容 | 技術との関係 |
| --- | --- | --- | --- |
| 基本設計書（本書） | BL-BD | 全体の概要、表記のルール、これから行う設計、要件定義書に反映した事項 | |
| [画面設計書](screen-design.md) | BL-BD-SCR | 共通仕様（色・文字・ボタン・メッセージ・共通の部品）、画面ごとのレイアウト・表示項目・操作 | 技術に依存しない |
| [データ設計書](data-design.md) | BL-BD-DATA | 方針、エンティティ一覧、ER 図、テーブル定義、データのルール、CRUD 表、データ量の見積もり | PostgreSQL の型で書く（要件定義書 P-1） |
| [インフラ設計書](infra-design.md) | BL-BD-INFRA | AWS の構成図、ネットワーク、外への通信（Google Books API）、通信の許可、開発環境との対応、費用の目安 | AWS（CloudFront・ALB・ECS Fargate・RDS・S3）を前提とする（要件定義書 P-5） |

機能ごとの処理・API（案）・エラーは[機能定義書](../functions/function-definition.md)で定める。使う言語・フレームワーク・サービスは[技術スタック定義書](../tech-stack.md)で定める。文書の一覧は [docs/README.md](../README.md) にある。

### 1.3 表記のルール

本書と詳細文書に共通のルールである。

- 「A-1」「RV-3」などの ID は機能定義書の要件 ID を、「FN-01」などは要件定義書 8 章の機能 ID を、「G-1」などは画面一覧の画面 ID を表す（どの文書で定めているかは [docs/README.md](../README.md) の ID の一覧を参照）
- 図は Mermaid 記法かテキストで書く。VS Code で Mermaid を図として見るには、拡張機能「Markdown Preview Mermaid Support」を入れてプレビューを開く。GitHub ではそのまま図として表示される

---

## 2. これから行う設計

次の内容は、開発を始めるときに決め、本書か詳細文書に足す。Raise timeline で決めたやり方を基本にする。

### 2.1 API の詳細設計

API の一覧（URL・メソッド・入力・出力・主なエラー）、エラーの形、ページング、写真のアップロードの方法は、機能定義書（共通の決まりは 3 章、機能ごとは各文書の 6 章）で決めた。開発を始めるときに、次の内容を足す。

| 項目 | 内容 |
| --- | --- |
| 入力・出力の項目の一覧 | 手で表を書かず、API のコード（Controller と record）から OpenAPI の形式で自動で作る（springdoc-openapi）。docs/api/openapi.yaml に書き出してコミットし、画面の型もそこから作る（openapi-typescript）。テストで、コミットした仕様書とコードがずれていないかを確かめる。Raise timeline と同じやり方 |
| 処理の分け方 | Controller・Service・Repository の分け方と、1 つのトランザクションにまとめる範囲（レビューの投稿と本棚への追加、退会など） |
| Google Books API を呼ぶ部品 | Google Books API を呼ぶところを 1 つの部品（インターフェース）にまとめ、テストではその部品を偽物に替えられるようにする（技術スタック定義書 3.6）。検索の結果の使い回しと、呼び出し回数の数え方もこの部品の中に閉じ込める |
| 画面の部品の分け方 | 画面（G）ごとの React の部品と、共通の部品（ヘッダー・レビューのカード・星・表紙・ユーザーの行） |

### 2.2 運用設計

| 項目 | 内容 |
| --- | --- |
| 環境の作り方 | 開発環境（Docker Compose）と AWS の構築手順。AWS は Terraform で作る |
| 表の変更の管理 | Flyway のマイグレーションの書き方と履歴の残し方 |
| 公開の手順 | API のイメージ（ECR）と画面（S3）の反映、止め方・起動し直し方、検収の後の消し方（要件定義書 P-6）。2.2.1 に書いた |
| データベースのバックアップと復旧 | RDS の自動バックアップからの戻し方（N-9） |
| 不適切なレビュー・写真の削除 | データベースと S3 から消す手順（R-2） |
| JWT の秘密の値の変え方 | 漏れた・漏れた疑いがあるときに、Parameter Store の値を変え、リフレッシュトークンを全部無効にして全員をログアウトさせる手順 |
| Google Books API の API キー | キーの作り方と、Books API だけに使える制限のかけ方。漏れたときの作り直し方（R-8）。公開の前に、実際の 1 日の上限を Google Cloud のコンソールで確かめる手順（U-2）。2.2.1 に書いた |
| 定期の処理 | 期限切れのリフレッシュトークンの削除、S3 から消せなかった写真の削除、24 時間を過ぎた検索の結果の削除を 1 日 1 回動かす方法 |
| 費用の確認 | 月 1 回の確認手順と AWS Budgets の設定（R-1） |
| 障害時の対応 | 障害に気づく方法（CloudWatch）、Google Books API が止まったとき・上限に達したときの確かめ方（N-8） |

#### 2.2.1 公開環境の手順

構成は[インフラ設計書](infra-design.md)のとおり。スクリプトはリポジトリのフォルダで Git Bash から実行する。どれも先に terraform plan を出し、「この内容で反映しますか？」に y と答えたときだけ反映する（`-y` を付けると確かめずに反映する）。

| スクリプト | すること | かかる時間 |
| --- | --- | --- |
| `bash deploy/deploy.sh` | 今のコミットを反映する。jar を作り、API のイメージ（arm64）をコミット ID のタグで ECR に push し、`terraform apply -var running=true` で新しいタスクに入れ替える。画面をビルドして S3 に置き（assets/ は 1 年、ほかは no-cache）、CloudFront のキャッシュを消して、公開 URL の /actuator/health が UP になるのを待つ。コミットしていない変更があるとき、Parameter Store に API キーがないときは止まる | 10〜15 分 |
| `bash deploy/start.sh` | RDS を起動して待ち、`running=true` で ECS のサービスと ALB を作り直す。イメージと画面は前に反映したものを使う | 10〜20 分 |
| `bash deploy/stop.sh` | `running=false` で CloudFront を止めている間のページにし、ECS のサービスと ALB を消して、RDS を止める。データと URL はそのまま | 5〜10 分 |

**初めて作るとき**

1. 手元に AWS CLI（~/.aws の鍵）・Terraform・Docker Desktop を用意する（技術スタック定義書 3.4）
2. 下の「Google Books API の API キー」のとおり、公開用のキーを作って Parameter Store に置く
3. deploy/terraform/terraform.tfvars に `alert_email = "通知を受け取るメールアドレス"` を書く（リポジトリには入れない）
4. deploy/terraform で `terraform init` → `terraform plan` で中身を確かめ → `terraform apply`（running は既定の false。CloudFront・RDS などを作る。15〜20 分）
5. 届いたメール（AWS Notification）の「Confirm subscription」を押す
6. `bash deploy/deploy.sh` で最初のイメージと画面を反映する（ECR にイメージがないうちは running=true にできないため、start.sh ではなく deploy.sh を使う）

**Google Books API の API キー**

開発用とは別のキーを使う（インフラ設計書 6 章）。作り方は [README](../../README.md#google-books-api-の-api-キー) の 1〜3 と同じで、Books API だけに使える制限をかける（R-8）。

1. Git Bash で次を実行し、キーを貼り付けて Enter を押す。キーは画面にも履歴にも残らない。`MSYS_NO_PATHCONV=1` は、Git Bash が /bl/... を Windows のパスに書き換えないために付ける

   ```
   read -rs key
   MSYS_NO_PATHCONV=1 aws ssm put-parameter --name /bl/google-books/api-key --type SecureString --value "$key"
   unset key
   ```

2. 実際の 1 日の上限を確かめる（U-2）。Google Cloud のコンソールで、キーのプロジェクトを選び、「API とサービス」→「有効な API とサービス」→「Books API」→「割り当てとシステム制限」の「Queries per day」を見る
3. キーを変えたとき（漏れたときを含む。R-8）は、Google Cloud で新しいキーを作り、上の 1 に `--overwrite` を付けて置き直し、`aws ecs update-service --cluster bl-cluster --service bl-app --force-new-deployment` でタスクを入れ替えてから、古いキーを消す。タスクは起動するときにしか値を読まない

**すべて消すとき**

元に戻せない。公開の URL も変わる（作り直すと別の URL になる）。

1. deploy/terraform で `terraform apply -var rds_deletion_protection=false` で RDS の削除保護を外す（RDS が止まっていると、変更の後の待ち合わせで失敗と出るが、削除保護は外れる。`aws rds describe-db-instances` の DeletionProtection で確かめる）
2. S3 の画面と写真のバケット（`aws s3 rm s3://<バケット> --recursive`）と ECR（`aws ecr batch-delete-image`）を空にする。中身があると Terraform は消せない
3. `terraform plan -destroy -var rds_deletion_protection=false` で中身を確かめてから、`terraform destroy -var rds_deletion_protection=false`（CloudFront の削除に 20 分ほどかかる）
4. 消す前に取った最後のスナップショット（bl-db-final）が要らなければ、`aws rds delete-db-snapshot --db-snapshot-identifier bl-db-final` で消す
5. Parameter Store の /bl/google-books/api-key は Terraform で作っていないので残る。`MSYS_NO_PATHCONV=1 aws ssm delete-parameter --name /bl/google-books/api-key` で消し、Google Cloud でキーも消す

**反映のきまり**

- イメージのタグは上書きできず、Terraform は ECR に最後に push したイメージを使う。前のコミットに戻すときは、`git revert` で新しいコミットを作ってから反映する
- 新しいタスクが起動しなければ、ECS が前のタスク定義に戻す（デプロイのサーキットブレーカー）。このとき terraform apply は失敗で終わる
- 画面の古い assets/ は消さない（反映の前から開いている人が、古い画面のファイルを読めるように）

**困ったとき**

| 症状 | 調べるところ・対処 |
| --- | --- |
| deploy.sh・start.sh の apply がタスクの起動で止まる・失敗する | CloudWatch Logs の /bl/app で API のログを見る（起動の失敗・DB に接続できないなど）。ECS のコンソールのサービスの「イベント」で、イメージが取れない・ヘルスチェックに通らない・Parameter Store の値を読めないなどを見る |
| start.sh の plan が ECR のイメージを読めずに失敗する | ECR にイメージがない（初めてのとき）。deploy.sh で反映する |
| 公開 URL が「止めています」のページのまま | CloudFront の反映（5〜10 分）を待つ。続くなら `terraform output` と CloudFront のコンソールで、/api/* のビヘイビアがあるかを見る |
| 書籍検索が M-14 になる | CloudWatch Logs の /bl/app で Google Books API の応答を見る。403 ならキーの制限か Parameter Store の値が違う。429 なら上限に達した |
| RDS が勝手に起動している | 止めてから 7 日たつと AWS が起動する。もう一度 stop.sh を実行する |
| deploy.sh の `npm ci` が EPERM（operation not permitted）で止まる | 開発用の Vite（5173）などが frontend/node_modules のファイルを使っている。止めてから deploy.sh をもう一度実行する（イメージの push と apply は済んでいれば飛ばされる） |
| 反映の直後に /api/* が 502 になる | CloudFront の設定の反映中。数分待つ |
| apply が `Unrecognized remote plugin message` で途中で止まる | Windows で、.terraform の中の random プロバイダーの exe が消えていることがある（Raise timeline で起きた。原因は確かめていない）。deploy/terraform で `terraform init` をして入れ直し、同じスクリプトをもう一度実行する（途中まで済んだ分は state に残っている） |

**費用の確認**

月 1 回、請求のコンソールで費用とクレジットの残りを確かめる。AWS Budgets（bl-budget）は、実際の費用が予算の 80% を超えたときと、月末の予測が予算を超えそうなときにメールを送る（R-1）。無料プランの間は、クレジットの残りと期限にも気を付ける（インフラ設計書 7 章）。

### 2.3 ログ

Raise timeline と同じく、公開先ではログを JSON（ECS 形式）で出し、CloudWatch Logs Insights で探す。開発を始めるときに、次の内容を足す。

| 項目 | 内容 |
| --- | --- |
| リクエストのログ | 1 つのリクエストに 1 行。メソッド・URL・ステータス・かかった時間・ログインした人の ID |
| Google Books API の呼び出しのログ | 呼ぶたびに 1 行。問い合わせの種類（検索・1 冊）・ステータス・かかった時間・使い回したかどうか・その日の回数。上限に近づいた（800 回を超えた）ときは警告のログを出す |
| 出さないもの | パスワード、JWT・リフレッシュトークン、Cookie、Google Books API の API キー（URL の key= の値も伏せる）、レビュー・コメントの本文 |

### 2.4 性能テスト

Raise timeline と同じく、API は k6、画面は Playwright で測り、要件定義書 11.1 の条件のデータを入れて P95 を見る。開発を始めるときに、次の内容を足す。

| 項目 | 内容 |
| --- | --- |
| データの作り方 | ユーザー 1,000 人・本 5,000 冊・レビュー 1 万件などを入れるスクリプト。本は Google Books API から取らず、作った値を入れる |
| 書籍検索（N-6）の測り方 | Google Books API の応答を含むため、本物の Google Books API で測る。1 日の上限を使い切らないよう、回数を決めて（20 回など）測る |
| 合否を決める環境 | 公開先（AWS）。手元・GitHub Actions のランナーの値は参考 |

---

## 3. 要件定義書に反映した事項

設計中に見つかった要件の不足（Q-1〜）と、その対応を書く。本書の初版の時点では、まだない。
