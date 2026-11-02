# 配置

[← 返回文档目录](../README.md#-文档)

## config.json

`config.json` 的 `sources` 里每个 key 是一个来源实例，`type` 决定用哪个插件，其余字段是该插件的参数。通用字段：

| 字段 | 含义 |
|:--|:--|
| `type` | 插件类型，`python3 -m catchup sources` 可查看全部 |
| `title` | 页面上的来源名 |
| `tier` | `official`（官方一级源）/ `primary`（一手技术源）/ `signal`（弱信号），影响排序 |
| `kind` | 内容类别，如 代码、模型、论文、社区、视频、新闻 |
| `interval` | 采样间隔（分钟，默认 60）。cron 每 10 分钟一轮，所以取 10 的倍数；距上次采集满间隔才采 |
| `enabled` | 设为 `false` 可临时停用 |
| `important_only` | 只推重要的：只来自这个来源的事件要模型判为“重要”（`push` 的 `board_min_importance`，默认 4）才推送、进信息流，一般的留在专题里。榜单来源默认打开；其他来源（如创投媒体 `venture`）打开后每件都会写简报、选主话题，不论排名 |

`interests` 是兴趣画像（名称、权重、关键词），每个兴趣也是一个话题专题（模型给每件事选的主话题从这里面挑）；`ranking` 是排序参数（时间窗、半衰期、各等级权重等，默认值见 `pipeline/ranking.py` 的 `DEFAULTS`）。

## 凭据

放在项目根目录的 `.env`，参考 `.env.example`；已经设置的环境变量优先。

| 变量 | 说明 |
|:--|:--|
| `DEEPSEEK_API_KEY` | 简报、合并和 DeepSeek 来源；没有则只跑规则部分 |
| `GITHUB_TOKEN` | 可选，提高 GitHub API 额度 |
| `FEED_URL` / `FEED_TOKEN` | 推送到 hub |
| `SHARE_KEY` | 可选，分享密钥：新设备在待确认页填对就直接成为只读设备 |
| `APK_KEYSTORE_PASS` | 只在构建 App 时用，签名密钥的密码 |
