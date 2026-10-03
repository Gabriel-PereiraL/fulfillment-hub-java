[CmdletBinding()]
param(
    [string]$TerraformDirectory = (Join-Path $PSScriptRoot '..\infra\aws-ec2')
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$terraform = (Resolve-Path $TerraformDirectory).Path
$artifactDirectory = Join-Path $repository 'build\aws'
$artifact = Join-Path $artifactDirectory 'images.tar'

foreach ($command in @('aws', 'docker', 'terraform')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is not installed or not on PATH: $command"
    }
}

Push-Location $terraform
try {
    $bucket = terraform output -raw artifact_bucket
    $key = terraform output -raw artifact_key
    $instance = terraform output -raw instance_id
    $region = terraform output -raw aws_region
} finally {
    Pop-Location
}

Push-Location $repository
try {
    docker build --build-arg MODULE=api -t fulfillment-hub-java-api:aws .
    docker build --build-arg MODULE=worker -t fulfillment-hub-java-worker:aws .
    docker build --build-arg MODULE=provider-simulator -t fulfillment-hub-java-provider-simulator:aws .
    New-Item -ItemType Directory -Force $artifactDirectory | Out-Null
    docker image save --output $artifact `
        fulfillment-hub-java-api:aws `
        fulfillment-hub-java-worker:aws `
        fulfillment-hub-java-provider-simulator:aws
    aws s3 cp $artifact "s3://$bucket/$key" --region $region --sse AES256
} finally {
    Pop-Location
}

$parameters = @(
    @{ name = 'commands'; value = @('sudo systemctl restart fulfillment-hub-deploy.service') },
    @{ name = 'executionTimeout'; value = @('1800') }
) | ConvertTo-Json -Compress

$commandId = aws ssm send-command `
    --region $region `
    --instance-ids $instance `
    --document-name AWS-RunShellScript `
    --parameters $parameters `
    --query 'Command.CommandId' `
    --output text

aws ssm wait command-executed --region $region --command-id $commandId --instance-id $instance
aws ssm get-command-invocation --region $region --command-id $commandId --instance-id $instance `
    --query '{Status:Status,Output:StandardOutputContent,Error:StandardErrorContent}'

Remove-Item -LiteralPath $artifact -Force
Write-Host "Deployment finished. Run 'terraform output -raw api_url' in $terraform to open the API."
