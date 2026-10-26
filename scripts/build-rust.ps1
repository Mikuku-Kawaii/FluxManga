$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\env.ps1"
function Invoke-Cargo {
    & cargo @args
    if ($LASTEXITCODE -ne 0) { throw "cargo failed ($LASTEXITCODE): $args" }
}
if (-not (Test-Path -LiteralPath "$env:ANDROID_NDK_HOME\source.properties")) {
    throw 'Install Android NDK 28.2.13676358 and set ANDROID_HOME; see readme.md.'
}
$ndkVersion = & cargo ndk --version
if ($LASTEXITCODE -ne 0 -or $ndkVersion -notmatch '4\.1\.2') {
    throw 'cargo-ndk 4.1.2 is required; see readme.md.'
}
Push-Location "$fluxRoot\rust-core"
try {
    Invoke-Cargo build --locked --lib
    Invoke-Cargo run --locked --features bindgen --bin uniffi-bindgen -- generate `
        --library target/debug/flux_manga_core.dll --language kotlin `
        --config uniffi.toml --out-dir ../app/build/generated/uniffi --no-format
    Invoke-Cargo ndk -t arm64-v8a -t x86_64 --platform 31 `
        -o ../app/build/generated/jniLibs build --locked --release --lib
} finally {
    Pop-Location
}
