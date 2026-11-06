# 第三方依赖与许可证

## 运行时构建依赖

| 组件 | 用途 | 许可证/来源 |
|---|---|---|
| AndroidX Core KTX 1.13.1 | Android 基础能力 | Apache-2.0，Google / Android Open Source Project |
| AndroidX AppCompat 1.7.0 | Activity 与兼容 UI | Apache-2.0，Google / Android Open Source Project |
| Kotlin 2.2.0 / Kotlin Stdlib | Kotlin 编译与运行时 | Apache-2.0，JetBrains |
| Android SDK / NDK | 平台 API、MediaCodec、AudioTrack、原生构建 | Android SDK 许可，Google |
| ICU / Android `Transliterator` | 歌词繁简转换 | ICU License，Unicode / ICU 项目 |

AndroidX 和 Kotlin 许可证正文见项目的 `LICENSE`。原生 C++ 运行库相关许可与声明见随发行包提供的 Android NDK sysroot NOTICE。下表记录 Gradle 解析的 Release 运行时依赖，具体打包内容可能受构建工具处理影响。

`com.google.guava:listenablefuture:1.0` 自身 POM 通过父 POM `guava-parent:26.0-android` 继承 Apache-2.0。Android NDK sysroot 的声明见 `third-party/ANDROID-NDK-SYSROOT-NOTICE.txt`。

## Release 解析依赖

- `androidx.activity:activity:1.7.0`
- `androidx.annotation:annotation:1.6.0`
- `androidx.annotation:annotation-experimental:1.4.0`
- `androidx.annotation:annotation-jvm:1.6.0`
- `androidx.appcompat:appcompat:1.7.0`
- `androidx.appcompat:appcompat-resources:1.7.0`
- `androidx.arch.core:core-common:2.2.0`
- `androidx.arch.core:core-runtime:2.2.0`
- `androidx.collection:collection:1.1.0`
- `androidx.concurrent:concurrent-futures:1.1.0`
- `androidx.core:core:1.13.1`
- `androidx.core:core-ktx:1.13.1`
- `androidx.cursoradapter:cursoradapter:1.0.0`
- `androidx.customview:customview:1.0.0`
- `androidx.drawerlayout:drawerlayout:1.0.0`
- `androidx.emoji2:emoji2:1.3.0`
- `androidx.emoji2:emoji2-views-helper:1.3.0`
- `androidx.fragment:fragment:1.5.4`
- `androidx.interpolator:interpolator:1.0.0`
- `androidx.lifecycle:lifecycle-common:2.6.2`
- `androidx.lifecycle:lifecycle-livedata:2.6.2`
- `androidx.lifecycle:lifecycle-livedata-core:2.6.2`
- `androidx.lifecycle:lifecycle-process:2.6.2`
- `androidx.lifecycle:lifecycle-runtime:2.6.2`
- `androidx.lifecycle:lifecycle-viewmodel:2.6.2`
- `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2`
- `androidx.loader:loader:1.0.0`
- `androidx.profileinstaller:profileinstaller:1.3.1`
- `androidx.resourceinspection:resourceinspection-annotation:1.0.1`
- `androidx.savedstate:savedstate:1.2.1`
- `androidx.startup:startup-runtime:1.1.1`
- `androidx.tracing:tracing:1.0.0`
- `androidx.vectordrawable:vectordrawable:1.1.0`
- `androidx.vectordrawable:vectordrawable-animated:1.1.0`
- `androidx.versionedparcelable:versionedparcelable:1.1.1`
- `androidx.viewpager:viewpager:1.0.0`
- `com.google.guava:listenablefuture:1.0`
- `org.jetbrains:annotations:13.0`
- `org.jetbrains.kotlin:kotlin-stdlib:2.2.0`
- `org.jetbrains.kotlin:kotlin-stdlib-common:2.2.0`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4`
- `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.4`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4`

## 测试依赖

- Kotlin Test / JUnit 5：Apache-2.0（JUnit 使用 EPL-2.0 的部分按其原始许可保留）。
- 测试依赖不随正式 APK 作为独立第三方库发布。

## MP3 编码说明

发布物不包含 LAME、MPGLIB 或 MP3 编码导出，因此不以这些组件的 LGPL/GPL 义务作为分发前提。

如果后续增加 MP3 编码，必须重新审核相关库的链接方式、许可证、对应源码、修改和重新链接要求。

## 内容与商标

SomaFM、LRCLIB 及其他第三方名称、直播和歌词不属于本项目的 Apache-2.0 授权范围，详见 `THIRD-PARTY-SOURCES.md` 和 `TERMS.md`。
