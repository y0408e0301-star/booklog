# セキュリティグループ（インフラ設計書 4 章）。受け付ける通信だけを書く

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# SG-1：ALB。CloudFront からの HTTP だけを受ける
resource "aws_security_group" "alb" {
  name        = "bl-alb"
  description = "BookLog ALB"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "alb_http_from_cloudfront" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from CloudFront"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
}

resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  description                  = "To Fargate tasks"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.app.id
}

# SG-2：Fargate のタスク。パブリック IP があるが、ALB からの 8080 だけを受ける
resource "aws_security_group" "app" {
  name        = "bl-app"
  description = "BookLog Fargate tasks"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  description                  = "API from ALB"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.alb.id
}

# Google Books API・ECR・Parameter Store・CloudWatch Logs（インターネット経由）と S3（エンドポイント経由）は、どれも HTTPS で宛先の IP が決まらない。
# NAT ゲートウェイも VPC エンドポイント（インターフェース型）も置かないので、443 は宛先を絞れない（インフラ設計書 方針 6）
#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "app_https" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS to Google Books API and AWS APIs (ECR, SSM, CloudWatch Logs, S3)"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "app_to_db" {
  security_group_id            = aws_security_group.app.id
  description                  = "PostgreSQL to RDS"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.db.id
}

# SG-3：RDS。Fargate のタスクからの 5432 だけを受ける
resource "aws_security_group" "db" {
  name        = "bl-db"
  description = "BookLog database"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from Fargate tasks"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.app.id
}
