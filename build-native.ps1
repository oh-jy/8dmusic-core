$ErrorActionPreference = 'Stop'

$ndk = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME }
elseif ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'ndk\27.2.12479018' }
elseif ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT 'ndk\27.2.12479018' }
else { throw '请先设置 ANDROID_NDK_HOME，或设置 ANDROID_HOME / ANDROID_SDK_ROOT' }
$ndk = Resolve-Path $ndk -ErrorAction Stop
$bin = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$output = Join-Path $PSScriptRoot 'app\src\main\jniLibs\arm64-v8a'
$pageSize = '-Wl,-z,max-page-size=16384'

$cpp = Get-ChildItem (Join-Path $PSScriptRoot 'app\src\main\cpp') -Filter '*.cpp' | ForEach-Object FullName
& (Join-Path $bin 'clang++.exe') --target=aarch64-linux-android24 -O3 -fPIC -shared -std=c++17 `
    -fuse-ld=lld $pageSize @cpp -llog `
    -o (Join-Path $output 'libspatialmp3.so')
if ($LASTEXITCODE -ne 0) { throw 'JNI build failed' }
Copy-Item (Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so') $output -Force

& (Join-Path $bin 'llvm-readelf.exe') -lW (Join-Path $output '*.so') |
    Select-String 'File:|LOAD'
