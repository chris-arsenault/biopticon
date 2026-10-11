terraform {
  required_version = ">= 1.12"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.22" }
  }
  backend "s3" {
    key          = "projects/biopticon-probe.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "us-east-1"
  default_tags { tags = { Project = "biopticon", Component = "probe", ManagedBy = "Terraform" } }
}

locals { prefix = "biopticon-probe" }
data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

module "platform" {
  source = "git::https://github.com/chris-arsenault/ahara-tf-patterns.git//modules/platform-context?ref=fff59f90fcf84b58d9328fa58b87e51414bec008"
}

module "mobile" {
  source              = "git::https://github.com/chris-arsenault/ahara-tf-patterns.git//modules/cognito-app?ref=fff59f90fcf84b58d9328fa58b87e51414bec008"
  name                = "biopticon-probe-mobile"
  cognito             = module.platform.cognito
  public_oauth_client = true
  callback_urls       = ["io.ahara.biopticon.probe:/oauth2redirect"]
  logout_urls         = ["io.ahara.biopticon.probe:/logout"]
}

resource "aws_ssm_parameter" "auth_trigger_client" {
  name  = "/ahara/auth-trigger/clients/biopticon-probe-mobile"
  type  = "String"
  value = module.mobile.client_id
}

resource "aws_s3_bucket" "events" { bucket = "${local.prefix}-events-${data.aws_caller_identity.current.account_id}" }
resource "aws_s3_bucket_public_access_block" "events" {
  bucket                  = aws_s3_bucket.events.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_server_side_encryption_configuration" "events" {
  bucket = aws_s3_bucket.events.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}
resource "aws_s3_bucket_lifecycle_configuration" "events" {
  bucket = aws_s3_bucket.events.id
  rule {
    id     = "expire-probe-events"
    status = "Enabled"
    filter { prefix = "diagnostics/" }
    expiration { days = 90 }
  }
}

resource "aws_s3_bucket_policy" "events" {
  bucket = aws_s3_bucket.events.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Sid = "TLSOnly", Effect = "Deny", Principal = "*", Action = "s3:*", Resource = [aws_s3_bucket.events.arn, "${aws_s3_bucket.events.arn}/*"], Condition = { Bool = { "aws:SecureTransport" = "false" } } },
    { Sid = "CreateOnly", Effect = "Deny", Principal = "*", Action = "s3:PutObject", Resource = "${aws_s3_bucket.events.arn}/diagnostics/*", Condition = { Null = { "s3:if-none-match" = "true" } } }
  ] })
}

module "api" {
  source   = "git::https://github.com/chris-arsenault/ahara-tf-patterns.git//modules/alb-api?ref=fff59f90fcf84b58d9328fa58b87e51414bec008"
  prefix   = local.prefix
  hostname = "api.biopticon.ahara.io"
  vpc      = module.platform.vpc
  alb      = module.platform.alb
  cognito  = module.platform.cognito
  environment = {
    EVIDENCE_BUCKET         = aws_s3_bucket.events.id
    COGNITO_ISSUER          = module.platform.cognito.issuer
    COGNITO_CLIENT_ID       = module.mobile.client_id
    TARGET_GROUP_ARN_PREFIX = "arn:aws:elasticloadbalancing:us-east-1:${data.aws_caller_identity.current.account_id}:targetgroup/${local.prefix}-ingest-tg/"
  }
  iam_policy = [jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["s3:PutObject", "s3:GetObject"], Resource = "${aws_s3_bucket.events.arn}/diagnostics/owner/*" }
  ] })]
  lambdas = {
    ingest = {
      binary = "${path.module}/../../target/lambda/biopticon-probe/bootstrap"
      routes = [
        { priority = 380, paths = ["/health"], methods = ["GET"], authenticated = false },
        { priority = 381, paths = ["/v1/runs/*/events/*"], methods = ["PUT"], authenticated = true }
      ]
    }
  }
}

output "api_url" { value = "https://${module.api.hostname}" }
output "oauth_base_url" { value = "https://${module.platform.cognito.domain}" }
output "client_id" { value = module.mobile.client_id }
output "evidence_bucket" { value = aws_s3_bucket.events.id }
