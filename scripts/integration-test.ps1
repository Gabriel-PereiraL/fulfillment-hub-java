$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$builder = 'maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976'

& docker rm -f fulfillment-java-api 2>$null | Out-Null
& docker compose -f (Join-Path $projectRoot 'compose.yaml') up -d --wait postgres localstack
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL or LocalStack did not become healthy.' }

& (Join-Path $PSScriptRoot 'build.ps1')
& (Join-Path $PSScriptRoot 'migrate.ps1')

& docker run --rm --mount "type=bind,source=$projectRoot,target=/workspace" `
    --mount 'type=volume,source=fulfillment-java-maven-cache,target=/root/.m2' `
    -w /workspace $builder mvn -B -ntp -Pintegration clean verify `
    '-Dit.database.url=jdbc:postgresql://host.docker.internal:55432/fulfillment' `
    '-Dit.database.user=fulfillment' `
    '-Dit.database.password=local-development-only' `
    '-Dit.sqs.endpoint=http://host.docker.internal:4566'
if ($LASTEXITCODE -ne 0) { throw "Integration tests exited with code $LASTEXITCODE" }
