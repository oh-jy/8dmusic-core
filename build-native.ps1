$ErrorActionPreference = 'Stop'

$ndk = 'D:\temp\tools\android-sdk\ndk\27.2.12479018'
$bin = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$lame = Join-Path $PSScriptRoot 'native\lame-3.100'
$output = Join-Path $PSScriptRoot 'app\src\main\jniLibs\arm64-v8a'
$pageSize = '-Wl,-z,max-page-size=16384'
$lameSources = @('VbrTag', 'bitstream', 'encoder', 'fft', 'gain_analysis', 'id3tag',
    'lame', 'newmdct', 'presets', 'psymodel', 'quantize', 'quantize_pvt', 'reservoir',
    'set_get', 'tables', 'takehiro', 'util', 'vbrquantize', 'version', 'mpglib_interface') |
    ForEach-Object { Join-Path $lame "libmp3lame\$_.c" }

& (Join-Path $bin 'clang.exe') --target=aarch64-linux-android24 -O3 -fPIC -shared `
    -DHAVE_CONFIG_H "-I$lame" "-I$lame\libmp3lame" "-I$lame\include" "-I$lame\mpglib" `
    $pageSize '-Wl,-soname,libmp3lame.so' @lameSources -lm `
    -o (Join-Path $output 'libmp3lame.so')
if ($LASTEXITCODE -ne 0) { throw 'LAME build failed' }

$cpp = Get-ChildItem (Join-Path $PSScriptRoot 'app\src\main\cpp') -Filter '*.cpp' | ForEach-Object FullName
& (Join-Path $bin 'clang++.exe') --target=aarch64-linux-android24 -O3 -fPIC -shared -std=c++17 `
    -fuse-ld=lld $pageSize "-I$lame\include" @cpp "-L$output" -lmp3lame -llog `
    -o (Join-Path $output 'libspatialmp3.so')
if ($LASTEXITCODE -ne 0) { throw 'JNI build failed' }
Copy-Item (Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so') $output -Force

& (Join-Path $bin 'llvm-readelf.exe') -lW (Join-Path $output '*.so') |
    Select-String 'File:|LOAD'
