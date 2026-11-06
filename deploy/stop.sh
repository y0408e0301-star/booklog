#!/bin/bash
# 公開環境を止める。CloudFront を「止めています」のページにして、ECS のサービスと ALB を消し、RDS を止める。
# データは RDS と S3 に残り、URL も変わらない。起動は bash deploy/start.sh
# リポジトリのフォルダで実行する（Windows では Git Bash）：bash deploy/stop.sh
# 止めている間も、RDS のストレージと S3・ECR の分（月 約 800 円）はかかる（インフラ設計書 7 章）
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=deploy/lib.sh
source deploy/lib.sh

tf_apply false

db_id=$(tf_output db_instance_id)
status=$(db_status)
echo "== RDS（$db_id）: $status"
if [[ "$status" == available ]]; then
  aws rds stop-db-instance --db-instance-identifier "$db_id" >/dev/null
  # 止まりきるまで数分かかるが、待たなくてよい（止める処理は AWS 側で続く）
  echo "RDS を止めています（数分で stopped になります）"
fi
echo "== 止めました。RDS は止めてから 7 日たつと AWS が自動で起動します。長く止めるときは、7 日以内にもう一度 stop.sh を実行してください"
