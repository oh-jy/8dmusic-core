# 8dmusic-kt 迁移计划

## 全量迁移覆盖口径

- 行为以 `8dmusic-uts` 当前 Android 版为准，不追求逐行翻译或复用 UTS 的临时绕坑代码。
- 保持 Kotlin + 原生 View；算法复用 `core`，列表和播放状态复用 Android 平台能力。
- 每项覆盖以可执行功能为准；最终真机验收实际画面、音频和导出文件。

## 当前已落地

- 工程：`D:\temp\tools\8dmusic-kt`
- core 算法：Curve / Params / Binaural / Reverb / Engine / WSOLA / Resampler 已从 UTS 迁移。
- Android MVP：主界面按钮 → 生成 3 秒 440Hz 8D WAV → 私有目录 → AudioTrack 播放。
- 验证：`:core:test`（SpaceEngine 非静音 + WSOLA/Resampler 产出）与 `:app:assembleDebug` 均通过。

> 上述条目是第一版 MVP 记录。完整功能与缺陷状态以 `SESSION-REQUIREMENTS-AND-VERIFICATION.md` 第 9 节为准，避免继续用“MVP”概括当前应用。

## 下一步顺序

1. **真实音频 MVP**
   - `ACTION_OPEN_DOCUMENT` 选择音频。
   - `MediaExtractor + MediaCodec` 解码到 PCM。
   - 用现有 `SpaceEngine` 渲染并通过 `AudioTrack` 播放。
   - 目标：单文件加载/播放/暂停/seek/停止。

2. **参数与 UI 最小化**
   - 滑块控制 `SpaceEngine.setSpace/setRoom`。
   - 预设选择。
   - 不做 Compose，先用 View 验证交互；后续再决定 Compose 时机。

3. **导出与预处理**
   - 迁移 `PretreatChain`：下混 + WSOLA + Resampler。
   - WAV 导出；MP3 通过原生 LAME 或 ffmpeg-kit（评估体积）。

4. **播放列表与系统集成**
   - 文件列表/队列/排序/搜索/持久化。
   - 后台播放、MediaSession、耳机拔出。

5. **全量迁移与对齐**
   - 对照 `8dmusic-uts` 的 `pages/index/index.uvue` 和组件功能逐项迁移。
   - 用 `8dmusic-uts/tools/` 的 Python 基准做算法对拍。

## 验证口径

- core 不依赖 Android API，保留 JVM 单测。
- Android 端先验证实际声音输出，再迁 UI。
- 禁止用旧 APK 或基座日志冒充新构建验证。

## 本轮执行

- [x] 修正 PCM 测试基线（core 测试 + APK 构建通过）。
- [x] 队列模型：媒体元数据、搜索/排序、手动顺序、自建列表、隐藏恢复及持久化。
- [x] 页面行为：完整列表交互、参数说明/变换快捷项、空间曲线展示、恢复上次播放。
- [x] 播放与导出：切歌竞态、播放位置、导出并发、原始立体声与公共 Music 发布。
- [x] 构建、JVM 测试、真机安装及关键链路视觉/音频/文件验证已多次执行，详见台账。
- [ ] 最新工作区（11-03 后台/歌词/退出修改）的统一构建、单测与双机验收。

## 验收记录（2026-10-30）

- 原 UTS 应用保持原样；原生调试包使用 `j5i.spatial8d.native`，便于并行验证。
- 删除覆盖 `.kts` 的旧 `app/build.gradle`；包名、`versionCode` 现在只由 `app/build.gradle.kts` 生成。
- 无线设备安装新包被系统安装器拒绝（`Failure [-99]`），尚未取得原生应用的真实运行结果。
- APK 已推送至设备 `/sdcard/Download/8dmusic-native-debug.apk`，测试音频为 `/sdcard/Download/8d-test-tone.wav`。
- 待人工允许安装后验证：原生截图、实际播放/暂停/seek、参数切换、列表交互、WAV/MP3 文件内容。

## 后续进展摘要（2026-11-03）

- 本地音乐、队列/收藏、在线音源/FM、歌词、可视化、录音导出与后台服务均已形成可运行实现。
- 已修复的确定性问题包括 Reverb 悬挂引用、列表拖动反向死区、歌词最终进度覆盖手动检索、曲线误调参、音频焦点与首块解码发布、服务级自动续播框架等。
- 仍需按当前代码重新验证后台保活/进程恢复、播放中切参是否保持播放、FM 暂停重连、真实导出/录音文件，以及新增歌词候选预览和退出行为。
- `README.md` 的“迁移进度”与后续待办存在历史矛盾，后续维护以会话台账第 9 节为唯一细粒度索引。
