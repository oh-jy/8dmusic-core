# 构建手册

## 环境与签名

- 工具链：JDK 21、Gradle 8.14.3、Android SDK 34；路径由仓库根目录的 `build.ps1` 固定为本机工具路径。
- Debug 不需要签名配置，产物为 `build/outputs/apk/debug/app-debug.apk`。
- Release 需要本机 `docs/signing/password.txt` 和 `docs/signing/8dmusic-native-release.jks`，整个 `docs/signing/` 均不提交 Git；脚本只把密码读入 `SPATIAL8D_KEYSTORE_PASSWORD`。该目录必须随项目持久保存并另行安全备份，不能当构建缓存清理。
- 发布产物为 `build/outputs/apk/release/app-release.apk`。缺少签名文件时脚本直接停止，不生成未签名正式包。

## 日常增量构建

在仓库根目录执行：

```powershell
.\build.ps1                         # Debug 增量构建
.\build.ps1 -Test                  # Debug 构建并运行 app JVM 单元测试
.\build.ps1 -Configuration Release # 同签名正式包
.\build.ps1 -Configuration Release -Test
.\build.ps1 -Configuration Release -Install # 构建后同签名覆盖安装，保留应用数据
```

增量构建复用项目缓存和全局依赖缓存。需要附加 Gradle 参数时直接放在命令末尾，例如：

```powershell
.\build.ps1 -Test --stacktrace
```

`build.ps1` 默认运行 `:app:assembleDebug` 或 `:app:assembleRelease`；`Install` 要求仅有一台 ADB 设备。安装成功只代表部署完成，音频和界面仍需实际验收。

## 清理后完整重建

先确保没有其他会话或 Android Studio 正在构建本项目，然后执行：

```powershell
.\build.ps1 -Configuration Release -Clean -Test
```

`-Clean` 只删除项目内 `.gradle`、`app/build`、`core/build` 和 `build/` 下的构建状态，保留 `docs/signing/`、源码和全局 Gradle 依赖缓存。脚本随后从空项目缓存重新执行测试、编译、打包和签名。

若需要重新下载依赖，额外传 `--refresh-dependencies`；它不是日常缓存清理的必要步骤。不要把项目缓存清理与删除 `%USERPROFILE%\.gradle` 混为一谈。

## 版本号

- `versionName` 的 patch 每轮并行修复完成后加 1；patch 满 10 时 minor 加 1、patch 归零，例如 `2.5.9 -> 2.6.0`。
- `versionCode` 使用本机时间的 `yy + 年内日序 + 小时 + 两位当小时发布序号`，例如 `263081304`，始终在 Android 32 位整数范围内。
- 只有 `assembleRelease` 递增 `docs/signing/version-code.properties` 中的发布序号；Debug 和测试不消耗序号。
- 清理不会删除 `docs/signing/`，因此不会重置发布序号或签名。

## 验证与排障

```powershell
# APK 基本信息（SDK build-tools 下需存在 aapt.exe）
& 'D:\temp\tools\android-sdk\build-tools\34.0.0\aapt.exe' dump badging .\build\outputs\apk\release\app-release.apk

# 查看连接设备并覆盖安装
& 'D:\temp\tools\android-sdk\platform-tools\adb.exe' devices -l
& 'D:\temp\tools\android-sdk\platform-tools\adb.exe' install -r .\build\outputs\apk\release\app-release.apk
```

- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：优先使用同一发布密钥生成 Release 后 `install -r`，不要直接卸载，以免清空应用数据。
- `fstat failed EBADF`、缓存已注册等并发构建异常：停止重复构建，确认只有一个会话操作本项目，再使用 `-Clean` 重建。
- Kotlin/Gradle 的弃用警告不等于构建失败；只有进程退出码非 0、测试失败或 APK 缺失才表示本次构建未完成。
- 构建、安装、FM 首 PCM 与实际出声是四个不同验收点，不能相互替代。
