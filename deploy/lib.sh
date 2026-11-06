#!/bin/bash
# deploy.sh・start.sh・stop.sh が共通で使う関数（直接は実行しない）

tf=deploy/terraform

# -y を付けると、terraform plan の内容を確かめずに反映する
auto_approve=false
if [[ "${1:-}" == -y ]]; then
  auto_approve=true
fi

tf_output() {
  terraform -chdir="$tf" output -raw "$1"
}

# running（true / false）を切り替える terraform apply。先に plan を出し、確かめてから反映する
tf_apply() {
  echo "== terraform plan（running=$1）"
  terraform -chdir="$tf" plan -input=false -var "running=$1" -out=tfplan
  if [[ "$auto_approve" != true ]]; then
    read -r -p "この内容で反映しますか？ [y/N] " answer
    if [[ "$answer" != y && "$answer" != Y ]]; then
      echo "やめました" >&2
      exit 1
    fi
  fi
  echo "== terraform apply（ALB・ECS の作成や CloudFront の反映に 5〜10 分かかる）"
  terraform -chdir="$tf" apply -input=false tfplan
}

db_status() {
  aws rds describe-db-instances --db-instance-identifier "$(tf_output db_instance_id)" \
    --query "DBInstances[0].DBInstanceStatus" --output text
}

# 公開 URL の /actuator/health が UP になるまで待つ（CloudFront の反映を含めて最大 10 分）
wait_healthy() {
  local url
  url=$(tf_output url)
  echo "== 公開 URL で確認（$url）"
  for _ in $(seq 1 120); do
    if curl -s -m 5 "$url/actuator/health" | grep -q '"status":"UP"'; then
      echo "== 動いています: $url"
      return 0
    fi
    sleep 5
  done
  echo "10 分たっても API が応答しません。基本設計書 2.2.1 の「困ったとき」を見てください" >&2
  exit 1
}
