# Explicit release command. Authentication comes from Git Credential Manager or GH_TOKEN.
param(
    [string]$Repository = 'Mikuku-Kawaii/FluxManga',
    [Parameter(Mandatory)][string]$Tag,
    [Parameter(Mandatory)][string]$Commit,
    [Parameter(Mandatory)][string]$Notes,
    [Parameter(Mandatory)][string[]]$Assets,
    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' -or $Tag -notmatch '^v\d+\.\d+\.\d+$' -or $Commit -notmatch '^[a-f0-9]{40}$') { throw 'Invalid repository, tag or commit.' }
$files = @($Assets | ForEach-Object { Get-Item -LiteralPath $_ })
if (($files.Name | Select-Object -Unique).Count -ne $files.Count) { throw 'Duplicate release asset names.' }
$token = $env:GH_TOKEN
if (-not $token) {
    $credentials = "protocol=https`nhost=github.com`n`n" | git credential fill
    if ($LASTEXITCODE -ne 0) { throw 'Sign in to GitHub with Git Credential Manager or provide GH_TOKEN.' }
    $token = ($credentials | Where-Object { $_.StartsWith('password=') } | Select-Object -First 1).Substring(9)
    $credentials = $null
}
$headers = @{Authorization="Bearer $token";Accept='application/vnd.github+json';'X-GitHub-Api-Version'='2026-03-10';'User-Agent'='FluxManga-Release'}
$base = "https://api.github.com/repos/$Repository/releases"
try {
    $existing = @(Invoke-RestMethod -Uri "$base`?per_page=100" -Headers $headers)
    $release = $existing | Where-Object tag_name -EQ $Tag | Select-Object -First 1
    if ($release -and -not $release.draft) { throw 'This version is already public; publish a new version instead of replacing assets.' }
    if (-not $release) {
        $body = @{tag_name=$Tag;target_commitish=$Commit;name="FluxManga $($Tag.Substring(1))";body=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $Notes).Path);draft=$true;prerelease=$false} | ConvertTo-Json
        $release = Invoke-RestMethod -Method Post -Uri $base -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    }
    foreach ($file in $files) {
        $old = @($release.assets) | Where-Object name -EQ $file.Name
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($old) {
            if ($old.size -ne $file.Length -or $old.digest -ne "sha256:$hash") { throw "Existing draft asset differs: $($file.Name)" }
        } else {
            $uri = "https://uploads.github.com/repos/$Repository/releases/$($release.id)/assets?name=$([uri]::EscapeDataString($file.Name))"
            $asset = Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -ContentType 'application/octet-stream' -InFile $file.FullName -TimeoutSec 600
            if ($asset.size -ne $file.Length -or $asset.digest -ne "sha256:$hash") { throw "Uploaded asset verification failed: $($file.Name)" }
        }
        Write-Output "Uploaded and verified: $($file.Name)"
    }
    if ($Publish) {
        $release = Invoke-RestMethod -Method Patch -Uri "$base/$($release.id)" -Headers $headers -ContentType 'application/json' -Body '{"draft":false,"make_latest":"true"}'
    }
    Write-Output $release.html_url
} finally { $headers.Clear(); $token=$null }
