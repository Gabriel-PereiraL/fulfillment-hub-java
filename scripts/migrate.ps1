$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$image = 'maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976'
$jar = '/workspace/api/target/api-1.0.0-SNAPSHOT.jar'

if (-not (Test-Path (Join-Path $projectRoot 'api/target/api-1.0.0-SNAPSHOT.jar'))) {
    throw 'API jar not found. Run scripts/build.ps1 first.'
}

& docker run --rm --mount "type=bind,source=$projectRoot,target=/workspace,readonly" `
    -e 'DATABASE_URL=jdbc:postgresql://host.docker.internal:55432/fulfillment' `
    -e 'DATABASE_USER=fulfillment' `
    -e 'DATABASE_PASSWORD=local-development-only' `
    $image java -jar $jar migrate
if ($LASTEXITCODE -ne 0) { throw "Migration exited with code $LASTEXITCODE" }
