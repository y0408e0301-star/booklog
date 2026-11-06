#!/bin/bash
# 今のコミットの API と画面を公開環境に反映する（インフラ設計書 5.2、基本設計書 2.2）
# リポジトリのフォルダで実行する（Windows では Git Bash。Docker Desktop を起動しておく）：bash deploy/deploy.sh
# 止めているときに実行すると、そのまま起動もする（RDS が止まっていれば先に bash deploy/start.sh）
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=deploy/lib.sh
source deploy/lib.sh

if [[ -n "$(git status --porcelain)" ]]; then
  echo "コミットしていない変更があります。コミットしてから反映してください" >&2
  exit 1
fi

status=$(db_status)
if [[ "$status" != available ]]; then
  echo "RDS が $status です。先に bash deploy/start.sh で起動してください" >&2
  exit 1
fi

# キーがないとタスクが起動できず、apply が長く待ってから失敗するので、先に確かめる。
# MSYS_NO_PATHCONV：Git Bash が /bl/... を Windows のパスに書き換えないようにする。値は読まない
if ! MSYS_NO_PATHCONV=1 aws ssm get-parameter --name /bl/google-books/api-key --query Parameter.Name --output text >/dev/null 2>&1; then
  echo "Parameter Store に /bl/google-books/api-key がありません。基本設計書 2.2.1 の手順で置いてください" >&2
  exit 1
fi

tag=$(git rev-parse --short=12 HEAD)
repo=$(tf_output ecr_repository_url)
repo_name=${repo#*/}

echo "== API の jar を作る"
(cd backend && ./mvnw -B -q package -DskipTests)

echo "== API のイメージ（arm64）を作って ECR に push する（タグ $tag）"
if aws ecr describe-images --repository-name "$repo_name" --image-ids "imageTag=$tag" >/dev/null 2>&1; then
  echo "このコミットのイメージはもうあります"
else
  aws ecr get-login-password | docker login --username AWS --password-stdin "${repo%%/*}"
  # --provenance=false：ECR にタグのない付属のファイルを作らない（10 個まで残す数に入ってしまう）
  docker buildx build --platform linux/arm64 --provenance=false -t "$repo:$tag" --push backend
fi

# Terraform は ECR に最後に push したイメージを使う（ecr.tf）。タグは上書きできないので、古いコミットには戻せない。
# 中身が前と同じなら、ECR は同じイメージにタグを足すだけなので、タグではなくダイジェストで比べる
digest=$(aws ecr describe-images --repository-name "$repo_name" --image-ids "imageTag=$tag" \
  --query "imageDetails[0].imageDigest" --output text)
latest=$(aws ecr describe-images --repository-name "$repo_name" \
  --query "sort_by(imageDetails, &imagePushedAt)[-1].imageDigest" --output text)
if [[ "$latest" != "$digest" ]]; then
  echo "ECR の最新のイメージがこのコミット（$tag）のものではありません。戻すときは git revert して新しいコミットで反映してください" >&2
  exit 1
fi

tf_apply true

echo "== 画面をビルドして S3 に置く"
(cd frontend && npm ci && npm run build)
web=s3://$(tf_output web_bucket)
# ファイル名にハッシュが付くので 1 年キャッシュする。前の画面を開いている人のため、古いファイルは消さない
aws s3 sync frontend/dist/assets "$web/assets" --cache-control "public, max-age=31536000, immutable"
# index.html とそれ以外のファイル（アイコンなど）は、反映したらすぐ読ませる
aws s3 sync frontend/dist "$web" --exclude "assets/*" --cache-control "no-cache"

echo "== CloudFront のキャッシュを消す"
distribution=$(tf_output distribution_id)
invalidation=$(aws cloudfront create-invalidation --distribution-id "$distribution" --paths "/*" \
  --query Invalidation.Id --output text)
aws cloudfront wait invalidation-completed --distribution-id "$distribution" --id "$invalidation"

wait_healthy
