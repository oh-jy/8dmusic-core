# Provider JSON 格式（版本 1）

应用默认不内置在线歌曲或 FM 来源。文件选择「管理来源 / 导入」，也可扫描包含 JSON 或 HTTPS 配置地址的二维码；导入前显示域名、能力及 HTTP 风险。配置不得包含 Cookie、令牌或密码。

## 在线歌曲

```json
{
  "version": 1,
  "id": "example.music",
  "name": "示例歌曲来源",
  "type": "music",
  "search": {
    "url": "https://api.example.com/search?q={query}&page={page}",
    "results": "data.items",
    "id": "id",
    "title": "title",
    "artist": "artist",
    "audio": "audio.url"
  }
}
```

搜索发送 GET。`{query}`、`{page}` 仅允许在查询参数中；字段路径使用英文点号访问 JSON 对象，`results` 必须指向数组。`audio` 是每条搜索结果中直接给出的 HTTP(S) 音频地址；未提供地址时显示不可播放。当前不支持单独的播放解析请求、POST、登录、凭据、加密签名、脚本、试听识别或复杂字段变换。示例域名仅用于说明，不能直接使用。

## FM 电台

```json
{
  "version": 1,
  "id": "example.fm",
  "name": "示例电台来源",
  "type": "fm",
  "stations": [
    { "id": "station1", "name": "示例电台", "url": "https://radio.example.com/live.mp3" }
  ]
}
```

每台使用 `url` 指定主线路，可选 `alternateUrls` 字符串数组指定最多 20 条备用线路；可选 `region`、`city`、`category`、`level`、`note` 保存地区、城市、分类、级别及说明。支持现有播放器支持的直连流和 HLS。文件最大 256 KB，单个 FM 来源最多 1000 台。暂不支持节目单。

## 多来源包

可用 `{"version":1,"providers":[来源配置1,来源配置2]}` 包含多个完整来源，文件和二维码均可导入；所有来源会一起预览并确认保存。来源 ID 不能重复，最多 100 个，总大小不超过 256 KB。

## 安全与授权

仅允许 HTTP(S)，禁止访问本机、私网或保留地址；搜索、音频、HLS 分片及每次重定向均使用相同网络检查。若配置包含 HTTP，导入时会提示明文风险；HTTPS 配置不允许降级为 HTTP。应用不会为 Provider 附加平台登录 Cookie。请自行确认来源、在线播放、录音和导出的权限；导入来源不代表取得版权或平台授权。

## JAR 平台插件

QQ 音乐、哔哩哔哩、网易云音乐和酷狗音乐使用独立 Provider 仓库构建的签名 JAR。主应用通过文件选择器导入，展示并核对证书 SHA-256，启用后才会提供搜索与播放解析。示例 JAR 使用开发测试证书，并非正式信任根。Provider 源码和构建脚本位于独立仓库 `8dmusic-provider`，不随主应用仓库分发。

支持平台官方网页登录、旧版 Cookie 文本文件、手动 Cookie 和 `.8dp` 加密登录文件。QQ 网页入口为微信官方授权页，其他平台使用各自的官方登录页。网易云短信等验证由官方网页处理，主应用不接收验证码；不会绕过风控、会员、付费或试听限制。

凭据由主应用使用 AndroidKeyStore 加密保存，并只在所属平台的受控请求中使用。插件在隔离进程执行，网络请求经过 HTTP(S) 域名白名单和公网地址校验，不能访问宿主 WebView 或自行联网。登录文件与插件均不包含应用预置的账号凭据。

插件请求和音频支持 HTTP 和 HTTPS 及非标准端口，导入时须确认明文风险；不再要求声明 `http-audio`，旧声明仍兼容。HTTP 请求可能明文发送 Cookie；跨域重定向不转发 Cookie，并继续检查公网地址。

Provider 仓库中的 `provider-project\build-platforms.ps1` 构建五个平台插件。主应用仓库只保留运行时 ABI 模块 `provider-sdk`，不包含平台实现和 JAR 生成产物。
