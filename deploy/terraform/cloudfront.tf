# 公開用の URL（インフラ設計書 3.2）。ブラウザからは HTTPS で受け、画面は S3 から、/api/*・/actuator/* は ALB へ HTTP で渡す。
# 証明書は CloudFront の既定のもの（*.cloudfront.net）を使う。running = false の間は、すべてのパスで「止めています」のページを返す。
# CloudFront は消さないので、止めても URL は変わらない

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# Host 以外のヘッダー・クエリ文字列・Cookie をすべて ALB へ渡す
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

locals {
  web_origin_id = "web"
  alb_origin_id = "alb"
  # キャッシュせず、すべてのメソッドを ALB へ渡すパス
  api_paths = var.running ? ["/api/*", "/actuator/*"] : []
}

resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "bl-web"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# 動いている間：画面の URL（/books/56 など）を index.html に書き換える（React Router に任せる）。
# 止めている間：すべてを maintenance/index.html に書き換える。
# CloudFront のエラーページの設定で index.html を返すと、API の 403・404 まで index.html になるので使わない
resource "aws_cloudfront_function" "router" {
  name    = "bl-router"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = file("${path.module}/functions/${var.running ? "spa" : "maintenance"}.js")
}

# 採らない指摘（trivy）：アクセスログ（AWS-0010）は API のログ（/bl/app）で足りる。WAF（AWS-0011）は月 5 ドル以上かかり、
# 利用者が数十人の学習用には見合わない（要件定義書 R-1）
#trivy:ignore:AWS-0010
#trivy:ignore:AWS-0011
resource "aws_cloudfront_distribution" "main" {
  enabled         = true
  comment         = "booklog"
  is_ipv6_enabled = true
  http_version    = "http2and3"
  # 日本を含む中で一番安い価格帯
  price_class = "PriceClass_200"

  origin {
    origin_id                = local.web_origin_id
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  dynamic "origin" {
    for_each = aws_lb.app

    content {
      origin_id   = local.alb_origin_id
      domain_name = origin.value.dns_name

      custom_origin_config {
        http_port              = 80
        https_port             = 443
        origin_protocol_policy = "http-only"
        origin_ssl_protocols   = ["TLSv1.2"]
      }

      # ALB はこのヘッダーがある通信だけを Fargate に渡す（alb.tf）
      custom_header {
        name  = "X-Origin-Verify"
        value = random_password.origin_verify.result
      }
    }
  }

  # API と稼働の確認はキャッシュせず、すべてのメソッドとクエリ文字列・Cookie をそのまま渡す
  dynamic "ordered_cache_behavior" {
    for_each = local.api_paths

    content {
      path_pattern             = ordered_cache_behavior.value
      target_origin_id         = local.alb_origin_id
      viewer_protocol_policy   = "redirect-to-https"
      allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods           = ["GET", "HEAD"]
      cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
      origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
    }
  }

  # 画面。キャッシュの長さは S3 のファイルの Cache-Control（deploy.sh が付ける）に従う
  default_cache_behavior {
    target_origin_id       = local.web_origin_id
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
    compress               = true

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.router.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
