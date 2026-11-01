$ErrorActionPreference = 'Stop'

$env:ANDROID_HOME = 'D:\temp\tools\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME = "$env:USERPROFILE\AppData\Local\mise\installs\java\corretto-21.0.12.8.1"

& 'D:\temp\tools\gradle-8.14.3\bin\gradle.bat' --project-dir "$PSScriptRoot" --project-cache-dir "$PSScriptRoot\build\gradle" "-Dkotlin.project.persistent.dir=$PSScriptRoot\build\kotlin" :app:assembleDebug --no-daemon @args

if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed: $LASTEXITCODE"
}

Get-ChildItem "$PSScriptRoot\build\outputs\apk\debug\app-debug.apk" |
    Select-Object FullName, Length, LastWriteTime
