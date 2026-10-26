# Dot-source this file: . .\scripts\env.ps1
$fluxRoot = Split-Path -Parent $PSScriptRoot
$fluxLocalJdk = Join-Path $fluxRoot '.tools\jdk-17.0.20.1+1'
$fluxLocalSdk = Join-Path $fluxRoot '.tools\android-sdk'
$env:ANDROID_AVD_HOME = Join-Path $fluxRoot '.tools\avd'
if (Test-Path -LiteralPath $fluxLocalJdk) { $env:JAVA_HOME = $fluxLocalJdk }
if (Test-Path -LiteralPath $fluxLocalSdk) { $env:ANDROID_HOME = $fluxLocalSdk }
if ($env:ANDROID_HOME) {
    $env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\28.2.13676358'
}
$fluxPaths = @(
    (Join-Path $fluxRoot '.tools\cargo\bin'),
    (Join-Path $env:USERPROFILE '.cargo\bin')
)
if ($env:JAVA_HOME) { $fluxPaths += Join-Path $env:JAVA_HOME 'bin' }
if ($env:ANDROID_HOME) {
    $fluxPaths += Join-Path $env:ANDROID_HOME 'platform-tools'
    $fluxPaths += Join-Path $env:ANDROID_HOME 'emulator'
    $fluxPaths += Join-Path $env:ANDROID_HOME 'cmdline-tools\19.0\bin'
}
$env:PATH = ($fluxPaths -join ';') + ';' + $env:PATH
