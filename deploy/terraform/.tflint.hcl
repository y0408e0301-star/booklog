# tflint の設定（deploy/terraform で tflint --init のあと tflint を実行する。CI でも同じ）

# Terraform の書き方（未使用の変数・型のない変数・バージョンの固定など）
plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

# AWS（存在しないインスタンスタイプ・AMI の指定ミスなど）
# 最新版：gh api repos/terraform-linters/tflint-ruleset-aws/releases/latest --jq .tag_name
plugin "aws" {
  enabled = true
  version = "0.49.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}
