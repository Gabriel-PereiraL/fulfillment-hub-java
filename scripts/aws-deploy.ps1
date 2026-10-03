[CmdletBinding()]
param(
    [string]$TerraformDirectory = (Join-Path $PSScriptRoot '..\infra\aws-ec2')
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$terraform = (Resolve-Path $TerraformDirectory).Path
$artifactDirectory = Join-Path $repository 'build\aws'
$artifact = Join-Path $artifactDirectory 'images.tar'
$parametersFile = Join-Path $artifactDirectory 'ssm-parameters.json'

function Assert-NativeCommandSucceeded([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE."
    }
}

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
    Assert-NativeCommandSucceeded 'API image build'
    docker build --build-arg MODULE=worker -t fulfillment-hub-java-worker:aws .
    Assert-NativeCommandSucceeded 'Worker image build'
    docker build --build-arg MODULE=provider-simulator -t fulfillment-hub-java-provider-simulator:aws .
    Assert-NativeCommandSucceeded 'Provider simulator image build'
    New-Item -ItemType Directory -Force $artifactDirectory | Out-Null
    docker image save --output $artifact `
        fulfillment-hub-java-api:aws `
        fulfillment-hub-java-worker:aws `
        fulfillment-hub-java-provider-simulator:aws
    Assert-NativeCommandSucceeded 'Image archive creation'
    aws s3 cp $artifact "s3://$bucket/$key" --region $region --sse AES256
    Assert-NativeCommandSucceeded 'Artifact upload'
} finally {
    Pop-Location
}

$parameters = @{
    commands = @('sudo systemctl restart fulfillment-hub-deploy.service')
    executionTimeout = @('1800')
} | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText($parametersFile, $parameters, [System.Text.UTF8Encoding]::new($false))

$commandId = aws ssm send-command `
    --region $region `
    --instance-ids $instance `
    --document-name AWS-RunShellScript `
    --parameters "file://$($parametersFile.Replace('\', '/'))" `
    --query 'Command.CommandId' `
    --output text
Assert-NativeCommandSucceeded 'SSM command submission'

if ([string]::IsNullOrWhiteSpace($commandId) -or $commandId -eq 'None') {
    throw 'AWS did not return an SSM command id.'
}

aws ssm wait command-executed --region $region --command-id $commandId --instance-id $instance
Assert-NativeCommandSucceeded 'SSM command wait'
aws ssm get-command-invocation --region $region --command-id $commandId --instance-id $instance `
    --query '{Status:Status,Output:StandardOutputContent,Error:StandardErrorContent}'
Assert-NativeCommandSucceeded 'SSM command result lookup'

Remove-Item -LiteralPath $artifact -Force
[System.IO.File]::Delete($parametersFile)
Write-Host "Deployment finished. Run 'terraform output -raw api_url' in $terraform to open the API."
