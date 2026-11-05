# データベース（インフラ設計書 3.2）。プライベートサブネットに置き、Fargate のタスクからだけ接続できる。
# 使わない間は deploy/stop.sh が止める（Terraform では止めない）

resource "aws_db_subnet_group" "main" {
  name       = "bl-db"
  subnet_ids = [for s in aws_subnet.private : s.id]
}

# 採らない指摘（trivy）：
# - バックアップの期間（AWS-0077）：無料プランの上限で 1 日にしている（下の backup_retention_period）
# - Performance Insights（AWS-0133）：利用者が数十人で、遅いクエリは API のログ（遅いリクエストの WARN）で見つける
# - IAM 認証（AWS-0176）：DB はプライベートサブネットにあり Fargate のタスクからしかつながらない（SG-3）。パスワードは Parameter Store
#trivy:ignore:AWS-0077
#trivy:ignore:AWS-0133
#trivy:ignore:AWS-0176
resource "aws_db_instance" "main" {
  identifier     = "bl-db"
  engine         = "postgres"
  engine_version = "17"
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "booklog"
  username = "booklog"
  # Parameter Store（ssm.tf）にも同じ値を置き、タスクには環境変数 DB_PASSWORD で渡す
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = false

  # 要件定義書 N-9。無料プランでは 1 日より長くできない（FreeTierRestrictionError）。有料プランにしたら 7 にする
  backup_retention_period = 1
  # 誤って消したときのために、削除の前にスナップショットを取る
  skip_final_snapshot       = false
  final_snapshot_identifier = "bl-db-final"
  # 誤って RDS を消す apply・destroy を止める。後片付けのときだけ false にする
  deletion_protection = var.rds_deletion_protection
  apply_immediately   = true
}
