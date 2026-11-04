# 第三方依赖与许可证

## 运行时构建依赖

| 组件 | 用途 | 许可证/来源 |
|---|---|---|
| AndroidX Core KTX 1.13.1 | Android 基础能力 | Apache-2.0，Google / Android Open Source Project |
| AndroidX AppCompat 1.7.0 | Activity 与兼容 UI | Apache-2.0，Google / Android Open Source Project |
| Kotlin 2.2.0 / Kotlin Stdlib | Kotlin 编译与运行时 | Apache-2.0，JetBrains |
| Android SDK / NDK | 平台 API、MediaCodec、AudioTrack、原生构建 | Android SDK 许可，Google |
| ICU / Android `Transliterator` | 歌词繁简转换 | ICU License，Unicode / ICU 项目 |

AndroidX/Kotlin 的完整许可证可由对应官方仓库和 Maven 包获取。公开二进制分发时应将本文件及必要的依赖许可文本加入“关于/开源许可”页面或随包声明。

## 测试依赖

- Kotlin Test / JUnit 5：Apache-2.0（JUnit 使用 EPL-2.0 的部分按其原始许可保留）。
- 测试依赖不随正式 APK 作为独立第三方库发布。

## 已移除组件

Core 版不包含 LAME、MPGLIB、`lame-3.100.aar`、`libmp3lame.so` 或 MP3 导出，因此当前发布物不再以这些组件的 LGPL/GPL 义务作为分发前提。

**完整版特别说明：** 主分支仍包含上述 LAME/MPGLIB 组件与 MP3 导出。此“已移除”结论仅适用于 `opensource-core`，不可作为完整版依赖清单或免责依据。

如果后续恢复 MP3 编码，必须重新审核 LAME/MPGLIB 的实际链接、许可证、对应源码、修改和重新链接要求，不得直接沿用本 Core 版声明。

## 内容与商标

SomaFM、LRCLIB 及其他第三方名称、直播和歌词不属于本项目的 Apache-2.0 授权范围，详见 `THIRD-PARTY-SOURCES.md` 和 `TERMS.md`。
