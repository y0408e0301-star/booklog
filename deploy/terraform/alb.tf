# ALB（インフラ設計書 3.2）。止められず置いておくだけで費用がかかるので、running = false の間は消す。
# CloudFront が付ける秘密のヘッダー（X-Origin-Verify）が合うときだけ Fargate に渡し、ALB の名前に直接来た通信は 403 にする

locals {
  running_count = var.running ? 1 : 0
}

# 作り直しても同じ値（state に残る）。CloudFront の origin の custom_header で付ける
resource "random_password" "origin_verify" {
  length  = 32
  special = false
}

resource "aws_lb" "app" {
  count                      = local.running_count
  name                       = "bl-alb"
  load_balancer_type         = "application"
  internal                   = false
  security_groups            = [aws_security_group.alb.id]
  subnets                    = [for s in aws_subnet.public : s.id]
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "app" {
  count       = local.running_count
  name        = "bl-app"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id
  # 反映のとき、古いタスクへの通信を待つ時間（既定の 300 秒は長い）
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  count             = local.running_count
  load_balancer_arn = aws_lb.app[0].arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "from_cloudfront" {
  count        = local.running_count
  listener_arn = aws_lb_listener.http[0].arn
  priority     = 1

  condition {
    http_header {
      http_header_name = "X-Origin-Verify"
      values           = [random_password.origin_verify.result]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app[0].arn
  }
}
