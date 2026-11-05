variable "region" {
  description = "AWS のリージョン"
  type        = string
  default     = "ap-northeast-1"
}

variable "running" {
  description = "公開しているか。false にすると ECS のサービス・ALB を消し、CloudFront は「止めています」のページを出す（deploy/stop.sh）。true にすると作り直す（deploy/start.sh・deploy.sh）。ECR にイメージがないうちは true にできない"
  type        = bool
  default     = false
}

variable "alert_email" {
  description = "アラームと予算の通知を送るメールアドレス（terraform.tfvars に書く。SNS の購読は届いたメールで承認する）"
  type        = string
}

variable "monthly_budget_usd" {
  description = "AWS Budgets の月の予算（ドル）。要件定義書 R-1 の 10,000 円を 1 ドル 150 円で換算した値"
  type        = string
  default     = "67"
}

variable "rds_deletion_protection" {
  description = "RDS の削除保護。true の間は RDS を消す apply・terraform destroy が失敗する。後片付けのときだけ false にして apply する"
  type        = bool
  default     = true
}
