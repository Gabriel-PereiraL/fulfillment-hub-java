output "api_url" {
  description = "Public HTTPS URL after the deployment bundle has started."
  value       = "https://${replace(aws_eip.app.public_ip, ".", "-")}.nip.io"
}

output "instance_id" {
  value = aws_instance.app.id
}

output "artifact_bucket" {
  value = aws_s3_bucket.artifacts.id
}

output "artifact_key" {
  value = var.artifact_key
}

output "iam_role" {
  value = aws_iam_role.instance.name
}

output "aws_region" {
  value = var.aws_region
}
