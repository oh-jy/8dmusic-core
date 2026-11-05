# 8D Space Audio

独立开源的 Android 空间音频播放器。Kotlin + 原生 View + C++ DSP，支持中文简体、中文繁体和 English。

## 功能

- 本地音乐扫描、文件选择、播放/暂停、进度定位、循环/随机、后台连续播放。
- 播放列表、收藏、搜索/排序、拖动排序、歌曲信息编辑。
- 空间风格、混响、环绕、倍速、半音、整曲倒放与可调降噪。
- 曲线、波形、频谱、声谱、响度、相位、粒子、穿梭与驾驶模式。
- LRCLIB 歌词检索、缓存、候选预览与手动修正。
- 4 个 SomaFM 官方公开播放入口、自定义直播流、FM/麦克风录音。
- 本地音频无损 WAV 导出；不包含 MP3 编码器。
- 深色、浅色、跟随系统主题；中文简体、中文繁体、英文界面。

## 设计与依赖边界

- 本地音频播放优先，无账号系统或在线曲库。
- 歌词检索使用 LRCLIB 公开 API；歌词内容权利仍归各自权利人。
- 内置 4 个 SomaFM 官方公开播放入口。SomaFM 是独立第三方服务，本项目与其无官方合作。
- 公开直播、公开歌词不代表无版权；内容权利仍归各自权利人。
- 音频导出使用无损 WAV；播放已有 MP3 文件由 Android 平台解码器处理。
- 内置直播地址和用户自定义直播均要求 HTTPS；应用不上传本地文件或录音。

发布前请阅读：

- `LICENSE`：项目原创代码许可证。
- `PRIVACY.md`：本地数据、权限和联网行为。
- `TERMS.md`：使用条款、内容与录音责任。
- `THIRD-PARTY-NOTICES.md`：依赖许可证。
- `THIRD-PARTY-SOURCES.md`：FM 与歌词来源。
- `OPEN-SOURCE-RELEASE-ASSESSMENT.md`：第三方内容与发布合规核对。

## 构建

要求：JDK 21、Android SDK 34、Android NDK 27.2.12479018（修改原生代码时需要）。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_HOME = 'C:\path\to\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug
```

Debug APK：`build/outputs/apk/debug/app-debug.apk`。

修改 `app/src/main/cpp/` 后，先编译原生库：

```powershell
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME\ndk\27.2.12479018"
.\build-native.ps1
```

原生构建仅输出 `arm64-v8a`，并检查 16 KB 页对齐。

`build.ps1` 是调用 Gradle Wrapper 的快捷脚本；也可直接运行 `gradlew.bat`。

## 发布签名

签名文件与密码不进入仓库。设置 `SPATIAL8D_KEYSTORE_PASSWORD`，并将 release keystore 放在本机 `build/signing/8dmusic-native-release.jks` 后运行 `:app:assembleRelease`。未设置时生成未签名 release 包，不要用于公开安装分发。

## 责任与反馈

本项目按 Apache License 2.0 提供，不保证第三方服务持续可用。请只处理你有权使用的音频、直播和歌词，勿将 FM 录音用于未经授权的公开传播或商业使用。

第三方内容下架、安全漏洞及许可证问题请发送邮件至 `ooh.jiangy@gmail.com`；非敏感的项目问题也可通过公开仓库 Issue 反馈，具体渠道见 `TERMS.md` 与 `SECURITY.md`。
