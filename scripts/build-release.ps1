param([string]$VersionName = '0.1.4', [int]$VersionCode = 7)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
if (-not $env:FLUX_SIGNING_STORE) { throw 'Set FLUX_SIGNING_STORE, FLUX_SIGNING_STORE_PASSWORD, FLUX_SIGNING_ALIAS and FLUX_SIGNING_KEY_PASSWORD in this process.' }
Push-Location $fluxRoot
try {
    & ./gradlew.bat :app:assembleRelease "-PfluxVersionName=$VersionName" "-PfluxVersionCode=$VersionCode" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Release build failed ($LASTEXITCODE)" }
} finally { Pop-Location }
