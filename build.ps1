[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug',
    [switch]$Clean,
    [switch]$Test,
    [switch]$Install,
    [switch]$WithAsr,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'

$env:ANDROID_HOME = 'D:\temp\tools\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME = "$env:USERPROFILE\AppData\Local\mise\installs\java\corretto-21.0.12.8.1"
$gradle = 'D:\temp\tools\gradle-8.14.3\bin\gradle.bat'
$variant = $Configuration.ToLowerInvariant()
$package = "j5i.spatial8d.native"

if ($Configuration -eq 'Release') {
    $passwordFile = Join-Path $PSScriptRoot 'docs\signing\password.txt'
    $keystore = Join-Path $PSScriptRoot 'docs\signing\8dmusic-native-release.jks'
    if (-not (Test-Path -LiteralPath $passwordFile) -or -not (Test-Path -LiteralPath $keystore)) {
        throw '正式签名文件缺失：请恢复 docs/signing/password.txt 和 docs/signing/8dmusic-native-release.jks。'
    }
    $env:SPATIAL8D_KEYSTORE_PASSWORD = (Get-Content -LiteralPath $passwordFile -Raw).Trim()
}

if ($Clean) {
    # 只清理项目构建状态，不动全局 Gradle 依赖缓存，也不触源码和签名。
    $targets = @('.gradle', 'app\build', 'core\build') | ForEach-Object {
        Join-Path $PSScriptRoot $_
    }
    $targets += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'build') -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -NE 'signing' | ForEach-Object FullName
    foreach ($target in $targets) {
        $resolvedParent = [System.IO.Path]::GetFullPath((Split-Path -Parent $target))
        if (-not $resolvedParent.StartsWith([System.IO.Path]::GetFullPath($PSScriptRoot), [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "拒绝清理项目外路径：$target"
        }
        if (Test-Path -LiteralPath $target) {
            Remove-Item -LiteralPath $target -Recurse -Force
        }
    }
}

$tasks = @()
if ($Test) { $tasks += ':app:testDebugUnitTest' }
$tasks += ":app:assemble$Configuration"
if ($WithAsr) { $GradleArgs += '-PwithAsr=true' }
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
& $gradle --project-dir $PSScriptRoot --project-cache-dir "$PSScriptRoot\build\gradle" `
    "-Dkotlin.project.persistent.dir=$PSScriptRoot\build\kotlin" `
    '-Pkotlin.compiler.execution.strategy=in-process' `
    @tasks --console=plain --no-daemon @GradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed: $LASTEXITCODE" }
$stopwatch.Stop()

$apk = Join-Path $PSScriptRoot "build\outputs\apk\$variant\app-$variant.apk"
if (-not (Test-Path -LiteralPath $apk)) { throw "APK not found: $apk" }
$deliveryDir = Join-Path $PSScriptRoot 'build\deliveries'
New-Item -ItemType Directory -Path $deliveryDir -Force | Out-Null
$namedApk = Join-Path $deliveryDir "app-$variant-$(if ($WithAsr) { 'asr' } else { 'no-asr' }).apk"
Copy-Item -LiteralPath $apk -Destination $namedApk -Force
if ($Install) {
    $adb = 'D:\temp\tools\android-sdk\platform-tools\adb.exe'
    $devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" })
    if ($devices.Count -ne 1) { throw "需要且仅需要一台已连接设备，当前数量：$($devices.Count)" }
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "ADB install failed: $LASTEXITCODE" }
    & $adb shell dumpsys package $package | Select-String 'versionCode=|versionName='
}
Get-Item -LiteralPath $apk | Select-Object FullName, Length, LastWriteTime
Get-Item -LiteralPath $namedApk | Select-Object FullName, Length
"Build elapsed: $($stopwatch.Elapsed.ToString('mm\:ss'))"
