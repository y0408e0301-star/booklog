# ネットワーク（インフラ設計書 3.1）。NAT ゲートウェイは置かない（月 7,000 円ほどかかるため）。
# ALB と Fargate のタスクはパブリックサブネット（ALB は 2 つの AZ が必要）、RDS はプライベートサブネットに置く

locals {
  public_subnets  = { a = "10.0.0.0/24", c = "10.0.1.0/24" }
  private_subnets = { a = "10.0.10.0/24", c = "10.0.11.0/24" }
}

# 採らない指摘（trivy）：VPC フローログ（AWS-0178）。受ける通信は SG で ALB と CloudFront に絞っていて、ログの分の費用を増やさない
#trivy:ignore:AWS-0178
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "bl-vpc"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "bl-igw"
  }
}

resource "aws_subnet" "public" {
  for_each          = local.public_subnets
  vpc_id            = aws_vpc.main.id
  cidr_block        = each.value
  availability_zone = "${var.region}${each.key}"

  tags = {
    Name = "bl-public-${each.key}"
  }
}

resource "aws_subnet" "private" {
  for_each          = local.private_subnets
  vpc_id            = aws_vpc.main.id
  cidr_block        = each.value
  availability_zone = "${var.region}${each.key}"

  tags = {
    Name = "bl-private-${each.key}"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "bl-public"
  }
}

# プライベートサブネットはインターネットに出ない（RDS だけを置く）
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "bl-private"
  }
}

resource "aws_route_table_association" "public" {
  for_each       = aws_subnet.public
  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  for_each       = aws_subnet.private
  subnet_id      = each.value.id
  route_table_id = aws_route_table.private.id
}

# S3 への通信をインターネットに出さない（無料）
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id, aws_route_table.private.id]

  tags = {
    Name = "bl-s3-endpoint"
  }
}
