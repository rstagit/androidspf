$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutputDir = Join-Path $ScriptDir "..\app\src\main\jniLibs"
$LibName = "libsnispf.so"

if (-not $env:ANDROID_NDK_HOME) {
    $defaultNdk = Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk"
    if (Test-Path $defaultNdk) {
        $env:ANDROID_NDK_HOME = (Get-ChildItem $defaultNdk | Sort-Object Name -Descending | Select-Object -First 1).FullName
    }
}

if (-not $env:ANDROID_NDK_HOME -or -not (Test-Path $env:ANDROID_NDK_HOME)) {
    throw "ANDROID_NDK_HOME is not set and no NDK was found under $defaultNdk"
}

$NdkBin = Join-Path $env:ANDROID_NDK_HOME "toolchains\llvm\prebuilt\windows-x86_64\bin"
if (-not (Test-Path $NdkBin)) {
    throw "NDK toolchain not found: $NdkBin"
}

$ArchMap = @(
    @{ Abi = "arm64-v8a";   GoArch = "arm64"; CC = "aarch64-linux-android21-clang.cmd" },
    @{ Abi = "armeabi-v7a"; GoArch = "arm";   CC = "armv7a-linux-androideabi21-clang.cmd" },
    @{ Abi = "x86_64";      GoArch = "amd64"; CC = "x86_64-linux-android21-clang.cmd" },
    @{ Abi = "x86";         GoArch = "386";   CC = "i686-linux-android21-clang.cmd" }
)

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

foreach ($entry in $ArchMap) {
    $out = Join-Path $OutputDir $entry.Abi
    New-Item -ItemType Directory -Force -Path $out | Out-Null

    Write-Host "Building $($entry.Abi) ($($entry.GoArch))..."

    $env:CGO_ENABLED = "1"
    $env:GOOS = "android"
    $env:GOARCH = $entry.GoArch
    $env:CC = Join-Path $NdkBin $entry.CC

    & go build -buildmode=c-shared `
        -ldflags="-extldflags=-Wl,-soname,libsnispf.so" `
        -o (Join-Path $out $LibName) `
        (Join-Path $ScriptDir "snispf.go")

    if ($LASTEXITCODE -ne 0) {
        throw "Go build failed for $($entry.Abi)"
    }

    Write-Host "  -> $(Join-Path $out $LibName)"
}

Write-Host "Build complete."
