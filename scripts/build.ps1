param([string[]]$MavenArguments = @('clean', 'verify'))
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$builder = 'maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976'
& docker run --rm --mount "type=bind,source=$projectRoot,target=/workspace" --mount 'type=volume,source=fulfillment-java-maven-cache,target=/root/.m2' -w /workspace $builder mvn -B -ntp @MavenArguments
if ($LASTEXITCODE -ne 0) { throw "Maven exited with code $LASTEXITCODE" }
