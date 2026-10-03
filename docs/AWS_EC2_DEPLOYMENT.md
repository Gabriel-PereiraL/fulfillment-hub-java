# AWS EC2 deployment

I designed this environment as a real, disposable AWS demonstration. It runs the API, worker, provider simulator and PostgreSQL on one EC2 instance, uses real SQS queues and DLQs, retrieves a private image bundle from S3 through an EC2 IAM Role, and reads secrets from Systems Manager Parameter Store.

This topology proves deployment mechanics, IAM and service integration. It is not a high-availability production topology: PostgreSQL and all application processes share one instance, and destroying the instance destroys its local database volume.

## Architecture

```text
Internet
   |
 HTTPS (Caddy, automatic TLS on <elastic-ip>.nip.io)
   |
EC2 ------------------------------------------------------+
 | IAM instance profile                                  |
 |-- API                                                  |
 |-- Worker -----------------------> SQS + two DLQs       |
 |-- Provider simulator                                  |
 |-- PostgreSQL (encrypted EBS root volume)               |
 |                                                        |
 +---- GetObject ------------------> private S3 bucket    |
 +---- GetParametersByPath --------> SSM Parameter Store |
```

The instance stores no AWS access key. The AWS SDK uses the default credential chain and obtains temporary credentials from the instance profile. LocalStack still uses the explicit local endpoint and test credentials in Compose.

## What Terraform creates

- one Amazon Linux 2023 EC2 instance and encrypted 24 GB gp3 root volume;
- one Elastic IP and security group exposing only ports 80/443;
- one private, encrypted S3 artifact bucket with public access blocked and seven-day bundle expiration;
- domain-event and webhook SQS queues, each with a DLQ and five-receive redrive policy;
- generated application secrets stored as `SecureString` parameters;
- an EC2 IAM Role limited to the deployment prefix in S3, the application parameter path, the four queues and Systems Manager management;
- a Systems Manager path for deployment without SSH or an inbound port 22;
- a USD 100 monthly AWS Budget with an automatic action that stops the demonstration EC2 instance when actual cost reaches 50%.

## Verified deployment

I deployed this environment to AWS `us-east-1` on 2026-10-03. At verification time, the public endpoint was `https://34-237-247-213.nip.io`.

I collected the following evidence from the running environment:

- `/health/live` returned HTTP 200 and `Healthy` over HTTPS;
- `/health/ready` returned HTTP 200 and `Healthy` over HTTPS;
- the EC2 instance was online in Systems Manager without SSH ingress;
- the AWS Budget reported `HEALTHY`, a USD 100 monthly limit and zero calculated spend at that instant;
- its automatic action was in `STANDBY`, configured to stop instance `i-00e36d3159c823180` at 50% of actual monthly spend;
- the public end-to-end test authenticated the seeded customer, read the catalog, created order `3702ed54-5531-4855-a946-c5d5879f185e`, proved idempotent replay and payload-mismatch rejection, then observed payment and delivery complete with final state `Delivered`.

The URL is evidence of the deployed demonstration and remains available only while I keep the disposable environment running. This single-instance topology is a portfolio environment, not a production or high-availability claim.

## Prerequisites

- AWS CLI authenticated to an account you control;
- Terraform 1.8 or newer;
- Docker Desktop running;
- PowerShell 7;
- a default VPC in the selected region.

This creates billable resources, including EC2, EBS and a public IPv4 address. I check AWS Free Tier/credit eligibility in the account before applying and destroy the environment when the demonstration ends. AWS Budgets evaluates billing data periodically, so its automatic stop is a delayed safety control rather than a real-time hard spending cap.

## Deploy

```powershell
cd infra/aws-ec2
terraform init
terraform plan -out tfplan
terraform apply tfplan

cd ../..
./scripts/aws-deploy.ps1

cd infra/aws-ec2
terraform output -raw api_url
```

`aws-deploy.ps1` builds the three application images from the current checkout, saves them into one local tar archive, uploads it to the private S3 key and asks Systems Manager to run the instance deployment service. The archive is removed locally after a successful command.

The instance downloads the bundle using its IAM Role, reads the generated secrets, runs Flyway, performs the idempotent demonstration seed and starts the complete stack. Caddy requests and renews TLS for the hostname derived from the Elastic IP.

## Verify

```powershell
$url = terraform -chdir=infra/aws-ec2 output -raw api_url
Invoke-RestMethod "$url/health/live"
Invoke-RestMethod "$url/health/ready"

aws sqs get-queue-attributes `
  --queue-url (aws sqs get-queue-url --queue-name fh-domain-events --query QueueUrl --output text) `
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
```

Retrieve the generated customer password only when running the smoke test:

```powershell
aws ssm get-parameter `
  --name /fulfillment-hub-java/dev/seed-customer-password `
  --with-decryption `
  --query Parameter.Value `
  --output text
```

Run the same public end-to-end flow without printing the generated password:

```powershell
$password = aws ssm get-parameter `
  --name /fulfillment-hub-java/dev/seed-customer-password `
  --with-decryption `
  --query Parameter.Value `
  --output text

./scripts/e2e.ps1 `
  -ApiUrl (terraform -chdir=infra/aws-ec2 output -raw api_url) `
  -Password $password `
  -TimeoutSeconds 120
```

I only describe a deployment as complete after both health endpoints answer successfully and a login/order flow reaches `Delivered`.

## Operate and inspect

No SSH ingress is created. I use Session Manager when investigation is necessary:

```powershell
$instance = terraform -chdir=infra/aws-ec2 output -raw instance_id
aws ssm start-session --target $instance
```

Useful commands inside the instance:

```bash
sudo systemctl status fulfillment-hub-deploy
sudo journalctl -u fulfillment-hub-deploy --no-pager
cd /opt/fulfillment-hub && sudo docker compose ps
cd /opt/fulfillment-hub && sudo docker compose logs --tail=200 api worker
```

## Destroy

The environment is disposable. I export any evidence I need, then remove it:

```powershell
terraform -chdir=infra/aws-ec2 destroy
```

The artifact bucket uses `force_destroy` and the EBS volume uses `delete_on_termination`, so this command intentionally removes the demonstration data as well.
