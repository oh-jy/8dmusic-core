# 应用更新发布约定

稳定版从 `https://api.github.com/repos/oh-jy/8dmusic-core/releases/latest` 读取 GitHub Latest Release，发布时须将对应 Release 标记为 Latest，而不是仅创建标签。测试版从 `https://api.github.com/repos/oh-jy/8dmusic-core/releases/tags/beta-latest` 读取固定标签的预发布 Release；更新测试版时维护该 Release 和资产，标题以 `v主版本.次版本.修订号` 结尾，例如 `测试版 v2.19.0`，并保持预发布状态，不替换稳定版 Latest。

- 标签以 `v主版本.次版本.修订号` 结尾，例如 `mainv2-v2.19.0`；版本号须与 APK 的 `versionName` 一致。
- Release 正文填写本次更新说明，应用原样展示。
- 标准包资产命名为 `app-release-no-asr.apk`，含离线 ASR 的包命名为 `app-release-asr.apk`。应用只选择与当前安装包相同的版本。
- GitHub 资产须提供 `sha256:` 摘要；应用下载后校验摘要，交由系统安装器校验包名、版本代码和签名。升级 APK 必须使用与已安装版本相同的签名，且 `versionCode` 更大。
- 尚未发布对应包时，应用显示版本和更新说明，但不会提供其他变体的安装包。
- 应用前台时在已验证、非计费 Wi-Fi 下检查并预下载，下载过程中会绑定该网络，网络切换后停止；下载和摘要校验完成后才提示用户确认安装。手动检查在其他网络下仍需用户确认下载。
