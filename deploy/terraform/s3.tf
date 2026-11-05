# S3 のバケット（インフラ設計書 3.2）。どちらも非公開・SSE-S3

locals {
  account_id = data.aws_caller_identity.current.account_id
  buckets = {
    # 画面（Vite でビルドしたファイル。deploy/deploy.sh が置く）と、止めている間のページ（maintenance/）。CloudFront だけが読む（OAC）
    web = "bl-web-${local.account_id}"
    # レビューの写真（reviews/）。ブラウザは API が発行した署名付き URL で読む
    photos = "bl-photos-${local.account_id}"
  }
}

# 採らない指摘（trivy）：
# - アクセスログ（AWS-0089）・バージョニング（AWS-0090）：学習用で、ログとバージョンの分の費用を増やさない。
#   写真はレビューの削除などで消すもので（要件定義書 N-10）、画面は deploy.sh でいつでも置き直せる
# - 顧客管理の KMS キー（AWS-0132）：月 1 ドルかかる。SSE-S3 で保存時の暗号化はしている
#trivy:ignore:AWS-0089
#trivy:ignore:AWS-0090
#trivy:ignore:AWS-0132
resource "aws_s3_bucket" "web" {
  bucket = local.buckets.web
}

#trivy:ignore:AWS-0089
#trivy:ignore:AWS-0090
#trivy:ignore:AWS-0132
resource "aws_s3_bucket" "photos" {
  bucket = local.buckets.photos
}

# for_each でまとめると、trivy がバケットとの対応を読み取れず「ブロックがない」と判定するので、バケットごとに書く
resource "aws_s3_bucket_public_access_block" "web" {
  bucket                  = aws_s3_bucket.web.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_public_access_block" "photos" {
  bucket                  = aws_s3_bucket.photos.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "all" {
  for_each = { web = aws_s3_bucket.web.id, photos = aws_s3_bucket.photos.id }
  bucket   = each.value

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# SSE-S3（AES256）。顧客管理の KMS キー（AWS-0132）は使わない（上の aws_s3_bucket と同じ理由）
#trivy:ignore:AWS-0132
resource "aws_s3_bucket_server_side_encryption_configuration" "all" {
  for_each = { web = aws_s3_bucket.web.id, photos = aws_s3_bucket.photos.id }
  bucket   = each.value

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 画面のバケットは、この CloudFront からだけ読める
resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.web.arn}/*"
      Condition = {
        StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.main.arn }
      }
    }]
  })

  depends_on = [aws_s3_bucket_public_access_block.web]
}

# 止めている間のページ（deploy/maintenance/index.html）
resource "aws_s3_object" "maintenance" {
  bucket        = aws_s3_bucket.web.id
  key           = "maintenance/index.html"
  source        = "${path.module}/../maintenance/index.html"
  etag          = filemd5("${path.module}/../maintenance/index.html")
  content_type  = "text/html; charset=utf-8"
  cache_control = "no-cache"
}
