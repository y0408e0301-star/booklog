# ログ・監視・費用の通知（インフラ設計書 3.3）

# API のログ（1 行 1 つの JSON。基本設計書 2.3）。タスクの awslogs が送る。
# 採らない指摘（trivy）：KMS の顧客管理キー（AWS-0017）は月 1 ドルかかる。ログにパスワード・JWT・Cookie・本文は出さない（基本設計書 2.3）
#trivy:ignore:AWS-0017
resource "aws_cloudwatch_log_group" "app" {
  name              = "/bl/app"
  retention_in_days = 30
}

# 採らない指摘（trivy）：暗号化（AWS-0095）。送るのはアラームの名前と値だけで、秘密の情報は含まない
#trivy:ignore:AWS-0095
resource "aws_sns_topic" "alerts" {
  name = "bl-alerts"
}

# 届いたメールの「Confirm subscription」を押すまで通知は届かない
resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# 動いている間に、ALB のヘルスチェックに通るタスクがなくなった（タスクが落ちて起動し直せないなど）
resource "aws_cloudwatch_metric_alarm" "no_healthy_task" {
  count               = local.running_count
  alarm_name          = "bl-no-healthy-task"
  alarm_description   = "ALB のヘルスチェックに通る API のタスクが 3 分続けてない"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 3
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    LoadBalancer = aws_lb.app[0].arn_suffix
    TargetGroup  = aws_lb_target_group.app[0].arn_suffix
  }
}

# API が 5xx を返し続けている
resource "aws_cloudwatch_metric_alarm" "api_5xx" {
  count               = local.running_count
  alarm_name          = "bl-api-5xx"
  alarm_description   = "API の 5xx が 5 分で 10 回以上"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 10
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]

  dimensions = {
    LoadBalancer = aws_lb.app[0].arn_suffix
    TargetGroup  = aws_lb_target_group.app[0].arn_suffix
  }
}

# RDS の空き容量が 2GB 未満（止めている間は値が来ないので鳴らさない）
resource "aws_cloudwatch_metric_alarm" "db_free_storage" {
  alarm_name          = "bl-db-free-storage"
  alarm_description   = "RDS の空き容量が 2GB 未満"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = 2 * 1024 * 1024 * 1024
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }
}

# 要件定義書 R-1。実際の費用が予算の 80% を超えたとき、月末の予測が予算を超えそうなときにメールを送る
resource "aws_budgets_budget" "monthly" {
  name         = "bl-budget"
  budget_type  = "COST"
  limit_amount = var.monthly_budget_usd
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}
