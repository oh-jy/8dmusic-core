# 8D 空间音频 · Kotlin 原生重写

`D:\temp\tools\8dmusic-uts` 的 Kotlin/Android 原生复刻。

## 目标

- 弃用 uni-app x：消除 HBuilderX 编译黑盒、运行时坑、APK 体积。
- 算法层与 Android 平台层分离：`core/` 不依赖 Android API，便于 PC/iOS 复用。
- 先 MVP，再全量迁移现有功能。

## 结构

```
core/                         纯 Kotlin 算法模块
  src/main/kotlin/j5i/spatial8d/core/
    Curve.kt                  轨迹与距离曲线（UTS curve.uts）
    Params.kt                 参数/预设/滑块（UTS params.uts）
    ...                       后续 Binaural/Reverb/Engine/WSOLA/Resampler

app/                          Android 外壳
  src/main/kotlin/j5i/spatial8d/MainActivity.kt
```

## 构建

使用本机工具链（JDK 21 + Gradle 8.14.3 + Android SDK 34）：

```powershell
$env:ANDROID_HOME='D:\temp\tools\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:JAVA_HOME="$env:USERPROFILE\AppData\Local\mise\installs\java\corretto-21.0.12.8.1"
D:\temp\tools\gradle-8.14.3\bin\gradle.bat :app:assembleDebug --no-daemon
```

当前产物：`build/outputs/apk/debug/app-debug.apk`。

也可直接运行 `build.ps1`，自动设置 JDK/SDK 环境并编译；Gradle/Kotlin 工程缓存也写入 `build/`。

日常增量构建、正式签名包、缓存清理后全量重建及 ADB 覆盖安装见 `docs/BUILD.md`：

```powershell
.\build.ps1 -Test
.\build.ps1 -Configuration Release -Clean -Test
```

## 迁移进度

- [x] Gradle 工程 + Android 最小启动页。
- [x] `curve.uts` → `Curve.kt`。
- [x] `params.uts` → `Params.kt`。
- [x] `binaural.uts`、`reverb.uts`、`engine.uts`。
- [x] `dsp/wsola.uts`、`dsp/resample.uts`。
- [x] Android 生成 3 秒 8D 测试音并播放的 MVP。
- [x] 系统文件选择 + MediaCodec 解码真实音频 + AudioTrack 播放。
- [x] 预处理/导出/播放列表（保持原生 View，不强制迁移 Compose）。
- [x] 在线音源、FM、歌词、可视化、收藏、后台媒体服务。
- [ ] 当前工作区的完整双机功能与音频质量回归。

当前详细功能清单、历史 Bug 状态和验证证据统一维护在 `SESSION-REQUIREMENTS-AND-VERIFICATION.md` 第 9 节。

## 音乐可视化

可视化画面支持左右滑动切换效果，激活后再次点击可视化标签可弹窗选择，双击进入或退出全屏。独占效果包括声谱、图谱、粒子、穿梭、3D 频谱山脉、水流、琴弦、银河、星座、环形均衡器、莫比乌斯环、屋檐雨幕、光束星芒、流光丝线、轨道光球和四季流山；驾驶模式的切换按钮和左右滑动也可遍历这些模式。MP4 导出支持 720p 和 1080p 横竖屏。

效果使用实际输出 PCM 的频谱、响度和节拍，复用原生 Canvas。3D 山脉采用透视投影，每 100 ms 保存一行真实频谱，仅显示已采集的历史；暂停或切换音源后清理旧历史。水流等为音频驱动的风格化动画，雨幕带落点涟漪。除声谱外的独占模式保留歌词叠加，并适配应用深浅主题。

计划公开发布前请先阅读 `OPEN-SOURCE-RELEASE-ASSESSMENT.md`。当前第三方音源、内置 FM、在线导出/录音和原生依赖许可证链尚未达到直接公开发布条件。

## 完整版与 Core 版边界

本 `master` 是完整功能开发版，包含本地音乐、聚合在线音源、第三方登录、更多 FM 目录、在线歌词和录音/导出。`opensource-core` 才是公开 Core 基线；`LICENSE`、`NOTICE`、`PRIVACY.md`、`TERMS.md`、`SECURITY.md` 和 `THIRD-PARTY-*` 的 Core 描述不能被解释为完整版已获得第三方内容、账号、直播或录音授权。

完整版公开分发前仍需逐项核对音源和歌词服务条款、账号数据告知、电台来源与录音权利、LAME/MPGLIB 对应源码和许可证义务。应用说明只展示普通用户需要的版本、基本操作、联网数据、录音与第三方服务边界；完整许可证和发布审查保留在仓库文档，不堆进首页。

## 权限最小化

完整版仅声明本地媒体读取、联网、用户主动麦克风录音、后台媒体播放、媒体通知和播放防休眠所需权限。SAF 文件选择和应用自有 MediaStore 导出不需要写入外部存储权限，因此不声明 `WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`，也不申请定位、蓝牙、联系人或设备标识权限。

## 对拍策略

沿用 `8dmusic-uts/tools/` 的 Python 参照脚本作为 WAV/算法基准。Kotlin 侧补 JVM 单测，逐函数与 UTS 数值对齐，再对整段输出听感/波形。
