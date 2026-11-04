# 第三方内容来源与复核记录

复核日期：2026-11-04

## SomaFM

内置以下 4 个 SomaFM 官方公开播放入口，每台提供 `ice1` / `ice2` 两条 HTTPS 线路：

| 电台 | 官方页面 | 播放入口 |
|---|---|---|
| Groove Salad | https://somafm.com/groovesalad/ | `https://ice1.somafm.com/groovesalad-128-mp3` |
| Drone Zone | https://somafm.com/dronezone/ | `https://ice1.somafm.com/dronezone-128-mp3` |
| Space Station Soma | https://somafm.com/spacestation/ | `https://ice1.somafm.com/spacestation-128-mp3` |
| Deep Space One | https://somafm.com/deepspaceone/ | `https://ice1.somafm.com/deepspaceone-128-mp3` |

- 备用线路仅将 `ice1` 改为 `ice2`。
- 2026-11-04 实测上述 8 条线路均返回 HTTP 200 / `audio/mpeg`。
- 来源依据：SomaFM 官网 `Listen` / `Direct Stream Links`，以及 `Linking to Us` 页面。
- SomaFM 页面明确标注 Copyright、All rights reserved 和商标信息。**本项目没有把这些源认定为无版权内容，也没有声称取得录音再分发或商业使用许可。**
- 本项目与 SomaFM 无官方合作，不打包其 Logo 或台标，不使用“官方客户端”表述。
- 录音入口按项目需求保留，但必须同时展示 `TERMS.md` 的权利提示。权利人提出要求时应优先下架对应源。

相关链接：

- https://somafm.com/listen/
- https://somafm.com/linktous/
- https://somafm.com/about/

## LRCLIB

- 用途：按歌名、歌手、时长检索和缓存歌词。
- 服务：https://lrclib.net/
- API 说明：https://lrclib.net/llms.txt
- 公开说明：免费、开放歌词数据库，无需 API key；要求客户端标识并遵守限流。
- 实现：`Lyrics.kt` 使用 `8dmusic-core/1.0` 标识，针对 429/503 展示 `Retry-After`（如有）。
- 不批量打包歌词，不将歌词文本纳入 Apache-2.0。

## 自定义直播

用户主动输入的 URL 不属于维护者审核或推荐的内容目录。用户应自行确认播放、录音和分享权利，不应输入含账号、密码或私人令牌的链接。
