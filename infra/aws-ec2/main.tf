data "aws_caller_identity" "current" {}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_subnet" "default" {
  for_each = toset(data.aws_subnets.default.ids)
  id       = each.value
}

data "aws_ec2_instance_type_offerings" "selected" {
  filter {
    name   = "instance-type"
    values = [var.instance_type]
  }

  location_type = "availability-zone"
}

data "aws_ami" "amazon_linux" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "random_id" "suffix" {
  byte_length = 4
}

resource "random_password" "database" {
  length  = 32
  special = false
}

resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "random_password" "payment_webhook" {
  length  = 48
  special = false
}

resource "random_password" "delivery_webhook" {
  length  = 48
  special = false
}

resource "random_password" "provider_token" {
  length  = 48
  special = false
}

resource "random_password" "seed_admin" {
  length  = 24
  special = false
}

resource "random_password" "seed_customer" {
  length  = 24
  special = false
}

locals {
  name           = "fh-java-${var.environment}"
  parameter_path = "/fulfillment-hub-java/${var.environment}"
  compatible_subnet_ids = sort([
    for id, subnet in data.aws_subnet.default : id
    if contains(data.aws_ec2_instance_type_offerings.selected.locations, subnet.availability_zone)
  ])
  queue_names = {
    domain      = "fh-domain-events"
    domain_dlq  = "fh-domain-events-dlq"
    webhook     = "fh-webhooks-inbound"
    webhook_dlq = "fh-webhooks-inbound-dlq"
  }
}

resource "aws_s3_bucket" "artifacts" {
  bucket        = "${local.name}-artifacts-${random_id.suffix.hex}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    id     = "expire-deployment-bundles"
    status = "Enabled"
    filter { prefix = "deploy/" }
    expiration { days = 7 }
  }
}

resource "aws_sqs_queue" "domain_dlq" {
  name                      = local.queue_names.domain_dlq
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "domain" {
  name                       = local.queue_names.domain
  visibility_timeout_seconds = 60
  receive_wait_time_seconds  = 20
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.domain_dlq.arn
    maxReceiveCount     = 5
  })
}

resource "aws_sqs_queue" "webhook_dlq" {
  name                      = local.queue_names.webhook_dlq
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "webhook" {
  name                       = local.queue_names.webhook
  visibility_timeout_seconds = 60
  receive_wait_time_seconds  = 20
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.webhook_dlq.arn
    maxReceiveCount     = 5
  })
}

resource "aws_ssm_parameter" "secrets" {
  for_each = {
    database-password      = random_password.database.result
    jwt-signing-key        = random_password.jwt.result
    payment-webhook-key    = random_password.payment_webhook.result
    delivery-webhook-key   = random_password.delivery_webhook.result
    provider-token         = random_password.provider_token.result
    seed-admin-password    = random_password.seed_admin.result
    seed-customer-password = random_password.seed_customer.result
  }
  name  = "${local.parameter_path}/${each.key}"
  type  = "SecureString"
  value = each.value
}

resource "aws_iam_role" "instance" {
  name = "${local.name}-instance"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "runtime" {
  name = "${local.name}-least-privilege"
  role = aws_iam_role.instance.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadDeploymentArtifact"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "${aws_s3_bucket.artifacts.arn}/deploy/*"
      },
      {
        Sid      = "ReadApplicationParameters"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
        Resource = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${local.parameter_path}/*"
      },
      {
        Sid    = "UseFulfillmentQueues"
        Effect = "Allow"
        Action = [
          "sqs:GetQueueUrl", "sqs:GetQueueAttributes", "sqs:SendMessage", "sqs:ReceiveMessage",
          "sqs:DeleteMessage", "sqs:ChangeMessageVisibility"
        ]
        Resource = "arn:aws:sqs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:fh-*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "instance" {
  name = "${local.name}-instance"
  role = aws_iam_role.instance.name
}

resource "aws_security_group" "app" {
  name        = "${local.name}-app"
  description = "HTTPS ingress and unrestricted egress for the Fulfillment Hub demonstration"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description      = "HTTP for ACME redirect"
    from_port        = 80
    to_port          = 80
    protocol         = "tcp"
    cidr_blocks      = [var.allowed_cidr]
    ipv6_cidr_blocks = []
  }

  ingress {
    description      = "HTTPS API"
    from_port        = 443
    to_port          = 443
    protocol         = "tcp"
    cidr_blocks      = [var.allowed_cidr]
    ipv6_cidr_blocks = []
  }

  egress {
    from_port        = 0
    to_port          = 0
    protocol         = "-1"
    cidr_blocks      = ["0.0.0.0/0"]
    ipv6_cidr_blocks = []
  }
}

resource "aws_eip" "app" {
  domain = "vpc"
}

resource "aws_instance" "app" {
  ami                         = data.aws_ami.amazon_linux.id
  instance_type               = var.instance_type
  subnet_id                   = local.compatible_subnet_ids[0]
  vpc_security_group_ids      = [aws_security_group.app.id]
  iam_instance_profile        = aws_iam_instance_profile.instance.name
  associate_public_ip_address = true
  user_data_replace_on_change = true

  root_block_device {
    volume_type           = "gp3"
    volume_size           = 24
    encrypted             = true
    delete_on_termination = true
  }

  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }

  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    aws_region       = var.aws_region
    artifact_bucket  = aws_s3_bucket.artifacts.id
    artifact_key     = var.artifact_key
    parameter_path   = local.parameter_path
    public_hostname  = "${replace(aws_eip.app.public_ip, ".", "-")}.nip.io"
    compose_yaml_b64 = base64encode(file("${path.module}/compose.aws.yaml"))
  })

  depends_on = [
    aws_iam_role_policy.runtime,
    aws_iam_role_policy_attachment.ssm_core,
    aws_ssm_parameter.secrets
  ]
}

resource "aws_eip_association" "app" {
  allocation_id = aws_eip.app.id
  instance_id   = aws_instance.app.id
}

resource "aws_budgets_budget" "monthly" {
  name         = "${local.name}-monthly-cost"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"
}

resource "aws_iam_role" "budget_action" {
  name = "${local.name}-budget-action"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "budgets.amazonaws.com"
      }
      Action = "sts:AssumeRole"
      Condition = {
        StringEquals = {
          "aws:SourceAccount" = data.aws_caller_identity.current.account_id
        }
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "budget_action" {
  role       = aws_iam_role.budget_action.name
  policy_arn = "arn:aws:iam::aws:policy/AWSBudgetsActions_RolePolicyForResourceAdministrationWithSSM"
}

resource "aws_sns_topic" "budget_alerts" {
  name = "${local.name}-budget-alerts"
}

data "aws_iam_policy_document" "budget_alerts" {
  statement {
    sid       = "AllowBudgetsToPublish"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.budget_alerts.arn]

    principals {
      type        = "Service"
      identifiers = ["budgets.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "budget_alerts" {
  arn    = aws_sns_topic.budget_alerts.arn
  policy = data.aws_iam_policy_document.budget_alerts.json
}

resource "aws_budgets_budget_action" "stop_demo_instance" {
  budget_name        = aws_budgets_budget.monthly.name
  action_type        = "RUN_SSM_DOCUMENTS"
  approval_model     = "AUTOMATIC"
  notification_type  = "ACTUAL"
  execution_role_arn = aws_iam_role.budget_action.arn

  action_threshold {
    action_threshold_type  = "PERCENTAGE"
    action_threshold_value = var.budget_stop_percentage
  }

  definition {
    ssm_action_definition {
      action_sub_type = "STOP_EC2_INSTANCES"
      instance_ids    = [aws_instance.app.id]
      region          = var.aws_region
    }
  }

  subscriber {
    address           = aws_sns_topic.budget_alerts.arn
    subscription_type = "SNS"
  }

  depends_on = [
    aws_iam_role_policy_attachment.budget_action,
    aws_sns_topic_policy.budget_alerts
  ]
}
