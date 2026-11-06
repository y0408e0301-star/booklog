#!/bin/bash
# 止めてある公開環境を起動する。RDS を起動し、ECS のサービスと ALB を作り直して、公開 URL で動くまで待つ
# リポジトリのフォルダで実行する（Windows では Git Bash）：bash deploy/start.sh
# 反映したイメージ（ECR）と画面（S3）はそのまま使う。初めてのときは start.sh ではなく deploy.sh を使う
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=deploy/lib.sh
source deploy/lib.sh

# API は起動時に DB へつなぐので、RDS を先に起動する
db_id=$(tf_output db_instance_id)
status=$(db_status)
echo "== RDS（$db_id）: $status"
# 止めている途中は起動を受け付けないので、止まりきるのを待つ（stop.sh の直後に実行したとき）
#（AWS CLI に止まりきるまで待つコマンドはない）
if [[ "$status" == stopping ]]; then
  echo "止まりきるのを待っています"
  while [[ "$status" == stopping ]]; do
    sleep 15
    status=$(db_status)
  done
fi
if [[ "$status" == stopped ]]; then
  aws rds start-db-instance --db-instance-identifier "$db_id" >/dev/null
fi
if [[ "$status" != available ]]; then
  echo "起動を待っています（5〜10 分）"
  aws rds wait db-instance-available --db-instance-identifier "$db_id"
fi
echo "RDS が起動しました"

tf_apply true
wait_healthy
