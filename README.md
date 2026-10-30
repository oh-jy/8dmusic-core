# 8D 空间音频 · Kotlin 原生重写

`D:\temp\tools\8dmusic-uts` 的 Kotlin/Android 原生复刻。

## 目标

- 弃用 uni-app x：消除 HBuilderX 编译黑盒、运行时坑、APK 体积。
- 算法层与 Android 平台层分离：`core/` 不依赖 Android API，便于 PC/iOS 复用。
- 先 MVP，再全量迁移现有功能。

## 结构

```
core/                         纯 Kotlin 算法模块
  src/main/kotlin/jiangyi/spatial8d/core/
    Curve.kt                  轨迹与距离曲线（UTS curve.uts）
    Params.kt                 参数/预设/滑块（UTS params.uts）
    ...                       后续 Binaural/Reverb/Engine/WSOLA/Resampler

app/                          Android 外壳
  src/main/kotlin/jiangyi/spatial8d/MainActivity.kt
```

## 构建

使用本机工具链（JDK 21 + Gradle 8.14.3 + Android SDK 34）：

```powershell
$env:ANDROID_HOME='D:\temp\tools\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:JAVA_HOME="$env:USERPROFILE\AppData\Local\mise\installs\java\corretto-21.0.12.8.1"
D:\temp\tools\gradle-8.14.3\bin\gradle.bat :app:assembleDebug --no-daemon
```

当前产物：`app/build/outputs/apk/debug/app-debug.apk`（约 3.2MB）。

也可直接运行 `build.ps1`，自动设置 JDK/SDK 环境并编译。

## 迁移进度

- [x] Gradle 工程 + Android 最小启动页。
- [x] `curve.uts` → `Curve.kt`。
- [x] `params.uts` → `Params.kt`。
- [x] `binaural.uts`、`reverb.uts`、`engine.uts`。
- [x] `dsp/wsola.uts`、`dsp/resample.uts`。
- [x] Android 生成 3 秒 8D 测试音并播放的 MVP。
- [ ] 系统文件选择 + MediaCodec 解码真实音频 + AudioTrack 播放。
- [ ] 预处理/导出/播放列表/Compose UI。

## 对拍策略

沿用 `8dmusic-uts/tools/` 的 Python 参照脚本作为 WAV/算法基准。Kotlin 侧补 JVM 单测，逐函数与 UTS 数值对齐，再对整段输出听感/波形。
