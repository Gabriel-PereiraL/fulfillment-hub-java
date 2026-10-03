variable "aws_region" {
  description = "AWS region for the demonstration environment."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Short environment name used in resource names."
  type        = string
  default     = "dev"
}

variable "instance_type" {
  description = "EC2 size. t3.small is the practical minimum for the complete demonstration stack."
  type        = string
  default     = "t3.small"
}

variable "allowed_cidr" {
  description = "CIDR allowed to reach HTTPS. Prefer your public IP with /32."
  type        = string
  default     = "0.0.0.0/0"
}

variable "artifact_key" {
  description = "S3 key used by the deployment bundle."
  type        = string
  default     = "deploy/images.tar"
}
