# 项目工作规则

## 发布版本

- `versionCode` 在发布构建时按本机时间 `yyMMddHH` 加当小时发布序号生成，例如 `261104101`，同小时依次累计 1、2、3；序号保存在本机 `build/signing/version-code.properties`，九位数始终在 Android 32 位整数范围内。
- 每一轮并行修复完成后将 `versionName` 的 patch 加 1；patch 满 10 时进位到 minor，并将 patch 归零，例如 `1.0.9 -> 1.1.0`。

## 排查证据

- 排查优先查看日志和代码，辅以参数断言及可重复的逻辑检查；非必要不截图，截图不能作为唯一验收证据。
