# Requires PowerShell 7 (.NET RSA import/export); never prints private keys.
param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$SigningKey,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$Repository = 'Mikuku-Kawaii/FluxManga',
    [string]$Notes = 'release/notes/0.1.1.md',
    [int]$ValidDays = 365
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Run with PowerShell 7 (pwsh).' }
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid GitHub repository.' }
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$tools = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0'
$certs = & (Join-Path $tools 'apksigner.bat') verify --verbose --print-certs $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$badging = (& (Join-Path $tools 'aapt.exe') dump badging $apkPath) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Unable to read APK identity.' }
$packageLine = ($badging -split "`n" | Where-Object { $_.StartsWith('package:') })
$package = [regex]::Match($packageLine, "name='([^']+)'").Groups[1].Value
$version = [long][regex]::Match($packageLine, "versionCode='(\d+)'").Groups[1].Value
$name = [regex]::Match($packageLine, "versionName='([^']+)'").Groups[1].Value
$sdk = [int][regex]::Match($badging, "(?m)^sdkVersion:'(\d+)'").Groups[1].Value
$abiLine = ($badging -split "`n" | Where-Object { $_.StartsWith('native-code:') })
$abis = @([regex]::Matches($abiLine, "'([^']+)'") | ForEach-Object { $_.Groups[1].Value })
if ($package -ne 'com.flux.manga' -or $version -le 0 -or $sdk -lt 31 -or
    $name -notmatch '^\d+\.\d+\.\d+(?:[-.][A-Za-z0-9.-]+)?$' -or
    'arm64-v8a' -notin $abis -or 'x86_64' -notin $abis) { throw 'APK metadata does not match the universal release contract.' }
$hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$length = (Get-Item -LiteralPath $apkPath).Length
$utf8 = [Text.UTF8Encoding]::new($false)
$noteText = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Notes).Path)
if ($noteText.Length -gt 16000 -or $ValidDays -lt 1) { throw 'Invalid notes or validity duration.' }
$filename = "FluxManga-$name-universal.apk"
$url = "https://github.com/$Repository/releases/download/v$name/$filename"
$channel = ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $fluxRoot 'release/update.properties')))
$record = @('apk', $version, $name, $sdk, ($abis -join ','), $length, $hash, $url,
    [Convert]::ToBase64String($utf8.GetBytes($noteText))) -join "`t"
$expires = [DateTimeOffset]::UtcNow.AddDays($ValidDays).ToUnixTimeSeconds()
$payload = $utf8.GetBytes((@('flux-update-v2', "package=$package", "channel=$($channel.channel)", "expires=$expires", $record) -join "`n") + "`n")
$rsa = [Security.Cryptography.RSA]::Create()
try {
    $read = 0
    $rsa.ImportPkcs8PrivateKey([IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $SigningKey).Path), [ref]$read)
    if ([Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo()) -ne $channel.publicKey) { throw 'Manifest key does not match the public key embedded by Gradle.' }
    $signature = $rsa.SignData($payload, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    if (-not $rsa.VerifyData($payload, $signature, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)) { throw 'Manifest signature verification failed.' }
} finally { $rsa.Dispose() }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$output = (Resolve-Path -LiteralPath $OutputDirectory).Path
$destination = Join-Path $output $filename
if (Test-Path -LiteralPath $destination) { throw 'Release APK already exists; choose a new output directory.' }
Copy-Item -LiteralPath $apkPath -Destination $destination
$envelope = (@('flux-update-envelope-v1', [Convert]::ToBase64String($payload), [Convert]::ToBase64String($signature)) -join "`n") + "`n"
[IO.File]::WriteAllText((Join-Path $output 'update-v2.signed'), $envelope, $utf8)
$signerHashes = @($certs | Select-String 'certificate SHA-256 digest:' | ForEach-Object { ($_ -split ': ')[-1].Trim() })
$metadata = @{package=$package;versionCode=$version;versionName=$name;minSdk=$sdk;abis=$abis;bytes=$length;sha256=$hash;signers=$signerHashes;url=$url;manifestExpires=$expires}
[IO.File]::WriteAllText((Join-Path $output 'release-artifacts.json'), ($metadata | ConvertTo-Json -Depth 4), $utf8)
$sums = @($filename,'update-v2.signed','release-artifacts.json') | ForEach-Object {
    (Get-FileHash -LiteralPath (Join-Path $output $_) -Algorithm SHA256).Hash.ToLowerInvariant() + "  $_"
}
[IO.File]::WriteAllLines((Join-Path $output 'SHA256SUMS.txt'), $sums, $utf8)
Write-Output "Prepared $filename ($length bytes), versionCode=$version, SHA256=$hash"
