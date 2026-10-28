$ErrorActionPreference = 'Stop'

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    throw '请先设置 ANDROID_HOME 或 ANDROID_SDK_ROOT'
}
if (-not $env:JAVA_HOME) {
    throw '请先设置 JAVA_HOME，并指向 JDK 21'
}

& (Join-Path $PSScriptRoot 'gradlew.bat') --project-dir "$PSScriptRoot" --project-cache-dir "$PSScriptRoot\build\gradle" "-Dkotlin.project.persistent.dir=$PSScriptRoot\build\kotlin" :app:assembleDebug --no-daemon @args

if ($LASTEXITCODE -ne 0) {
    throw "Gradle 构建失败：$LASTEXITCODE"
}

Get-ChildItem "$PSScriptRoot\build\outputs\apk\debug\app-debug.apk" |
    Select-Object FullName, Length, LastWriteTime
