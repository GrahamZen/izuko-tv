# 重新编译 libani_dataspace.so 到 src/androidMain/jniLibs/<abi>/.
# 用法: 装好 NDK 后  .\build.ps1 -NdkRoot "C:\path\to\android-ndk-r27c"
# 产物是提交进仓库的预编译库 (避免给 Gradle/CI 加 NDK 依赖), 改了 ani_dataspace.c 记得重跑并提交.
param(
    [Parameter(Mandatory = $true)] [string] $NdkRoot
)
$ErrorActionPreference = "Stop"
$clang = Join-Path $NdkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe"
$src = Join-Path $PSScriptRoot "ani_dataspace.c"
$outRoot = Join-Path $PSScriptRoot "..\src\androidMain\jniLibs"
$targets = @{
    "arm64-v8a"   = "aarch64-linux-android21"
    "armeabi-v7a" = "armv7a-linux-androideabi21"
    "x86_64"      = "x86_64-linux-android21"
    "x86"         = "i686-linux-android21"
}
foreach ($abi in $targets.Keys) {
    $dir = Join-Path $outRoot $abi
    New-Item -ItemType Directory -Force $dir | Out-Null
    $out = Join-Path $dir "libani_dataspace.so"
    & $clang --target=$($targets[$abi]) -shared -fPIC -O2 -Wall -Werror `
        -o $out $src -landroid
    Write-Host "built $out"
}
