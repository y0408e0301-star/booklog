# 秘密の値（インフラ設計書 3.3、要件定義書 N-18）。Parameter Store に SecureString で置き、タスクの起動時に環境変数で渡す

# URL に入れても困らないよう、記号は使わない
resource "random_password" "db" {
  length  = 32
  special = false
}

# JWT の署名（HS256。32 バイト以上）。値を変えると、全員のアクセストークンが使えなくなる
resource "random_password" "jwt" {
  length  = 48
  special = false
}

resource "aws_ssm_parameter" "db_password" {
  name  = "/bl/db/password"
  type  = "SecureString"
  value = random_password.db.result
}

resource "aws_ssm_parameter" "jwt_secret" {
  name  = "/bl/jwt/secret"
  type  = "SecureString"
  value = random_password.jwt.result
}

# Google Books API の API キーは、手で AWS CLI から /bl/google-books/api-key に置く（インフラ設計書 3.3）。
# data "aws_ssm_parameter" で読むと値が状態ファイルに残るので、ARN を組み立てて名前だけを使う
locals {
  google_books_api_key_arn = "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/bl/google-books/api-key"
}
